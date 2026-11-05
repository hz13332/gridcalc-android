package cn.gridcalc.gridcalc

import android.content.Context
import org.json.JSONObject

// ---------- 自选距离列(对标稿子favDist/refreshFavDist终态) ----------
// 每行距第一支撑pct+现价+涨跌幅;key=type|SYM|tf|n字符串键(tf/n读distWin冻结窗),
// 成功缓存5分钟/失败只压5秒让重试环真能再拉;币/股走现有legs,
// 贵金属与17种商品走东财单源(稿fetchMetals:GC=F/SI=F/*00Y),不再无源;
// 支撑:价在第一支撑上方取最高支撑(绿),全部支撑在上方(已跌破)取最近一条(红,负值)。
// 无支撑软分支(稿NOSUP):数据已到但该窗口算不出支撑→soft+「—无支撑」,按成功缓5分钟、不进5秒重试。
// 跨进程持久化(t78):成功值落SharedPreferences(与记录引擎同介质),只存派生量
// (pct/现价/涨跌/时间戳/状态,不落K线);冷启动/断网时沿用旧值,仅从未成功过才显示无源/超时。

data class DistR(
    val ok: Boolean,
    val pct: Double = 0.0,
    val price: Double = 0.0,
    val chg: Double = 0.0,
    val why: String = "—",
    val soft: Boolean = false,
    val ts: Long = 0L,
    // 2026-10-15 收益比改口径：第一/第二支撑线价格
    val sup1: Double = 0.0,
    val sup2: Double = 0.0
)

object FavDist {
    private const val TTL_OK = 5 * 60 * 1000L
    private const val TTL_FAIL = 5 * 1000L

    // 沿用旧值的可见性约定(t78⑦选定"时间明显陈旧给弱提示"一支):
    // 沿用值距上次成功抓取超过24小时才加「·旧值」后缀,24小时内视为当日正常值不打扰
    const val STALE_MS = 24 * 60 * 60 * 1000L

    // 持久化与记录引擎同介质 SharedPreferences;条目key同缓存 key=type|SYM|tf|n;
    // 只存派生量 pct/px/chg/ts/st,绝不把K线序列化进来;上限MAXE条按ts淘汰最旧;
    // 读写全程try吞异常,存储失败降级为"无旧值"、绝不崩溃
    private const val PF = "gridcalc_dist_v1"
    private const val PKEY = "gc_dist"
    private const val MAXE = 200

    private val cache = mutableMapOf<String, Pair<Long, DistR>>()
    private var app: Context? = null
    private val store = mutableMapOf<String, JSONObject>()
    @Volatile private var loaded = false
    // 本会话已成功过的key(与save同刻写入):后台补抓轮只补未成功行,幂等不再重复请求
    private val okSeen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    // 同key在途复用:第一个发起者负责抓取(**在锁外**),并发到达者等它的 Future 拿到同一结果。
    // v4.1/t94-O2:原先用 per-key monitor 把「网络取数 + SP 落盘」整段包在 synchronized(lock) 里。
    // 于是 ART 打出 `Long monitor contention ... FavDist.compute(FavDist.kt:206) ... for 10.392s`,
    // 且违反 v4_spec §0.1「不阻塞界面、不做长等待」——网络取数绝不该在锁内做。
    // 改为 Future 复用:去重语义不变(同key仍只发一轮请求),但等待方阻塞在 Future.get 而非 monitor,
    // 锁内只剩 synchronized(this) 写缓存 / synchronized(store) 写值这类纯操作。
    private val inflight =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<DistR>>()

    // 在途等待上限:超过即认为首抓者已失联,回落缓存/失败文案,绝不无限期挂住调用线程
    private val inflightWaitMs = 60_000L

    fun attach(c: Context) {
        if (app == null) app = c.applicationContext
        ensureLoaded()
    }

    // 字符串键(照稿favKey().key):对象作键会永远miss,此处自查为字符串拼接
    fun key(s: String, tf: String, n: Int): String =
        MktData.symType(s) + "|" + MktData.canonicalSym(s) + "|" + tf + "|" + n

    /** Canonicalize persisted/cache keys while preserving legacy type/tf/n segments. */
    internal fun canonicalKey(raw: String): String {
        val p = raw.split("|")
        if (p.size < 4) return raw
        return p[0] + "|" + MktData.canonicalSym(p[1]) + "|" +
            p.drop(2).joinToString("|")
    }

    @Synchronized
    fun cached(s: String, tf: String, n: Int): DistR? {
        val h = cache[key(s, tf, n)] ?: return null
        val ttl = if (h.second.ok || h.second.soft) TTL_OK else TTL_FAIL
        if (System.currentTimeMillis() - h.first >= ttl) return null
        return h.second
    }

    // 本会话是否已成功过该key(后台补抓的幂等判据)
    fun doneOk(k: String): Boolean = okSeen.contains(k)

    /**
     * 丢掉某品种的距离缓存（**只清内存态**），让下一次 [compute] 重新按最新 K 线算一遍。
     *
     * 触发点：行情页刚为它取到新 K 线（`MktPanel.renderAgg` → `MktData.LastChart.put` 之后）。
     * 2026-11-02 用户报「行情页拉到了，自选页还是旧值，得一个品种一个品种点」——
     * 根因就在这里：行情页的数据落地后没人通知自选，自选那行只能等自己下一轮全量抓取，
     * 而它的 5 分钟缓存还会把刚取到的新数据挡在外面。
     *
     * ⚠ 不动落盘的 `lastOk`：新值还没算出来之前，旧值仍是唯一可展示的兜底。
     * ⚠ 按**品种**清（所有 tf/n 键），因为行情页的周期与自选的冻结窗不保证一致。
     */
    @Synchronized
    fun invalidateSymbol(s: String) {
        val u = MktData.canonicalSym(s)
        if (u.isEmpty()) return
        val prefix = MktData.symType(u) + "|" + u + "|"
        cache.keys.removeAll { it.startsWith(prefix) }
    }


    /**
     * ⚠ 2026-10-07 **落盘毒值防线（实机实证）**。
     *   真机上同一行先显示 +1532.43%、清数据后变 +1117.12%，而正解（尺度带收紧后）是 1.52%
     *   —— 说明**上一版算出的坏值被持久化了**，SP 里的旧值可以压过新代码最多 24 小时。
     *   判据：尺度带生效后，支撑必然落在 [现价/3, 现价*3] 内 ⟹ 距支撑数学上不可能超过 200%。
     *   超过即判定为旧版/坏数据，**丢弃并让调用方重算**，绝不拿它顶屏。
     *   ⚠ 墓碑 · 2026-10-12 用户裁定选 A：`profileOf` 的尺度带已整段删除（见 [MktData.profileOf]），
     *     上面「尺度带保证支撑必然落在 [现价/3, 现价*3] 内」那条判据**随之不成立**。
     *     本函数**保留**，但语义降级为「明显异常值的粗防线」，不再有尺度带兜底：
     *     · 负值 = 支撑算在现价上方（本列本就不显示）；· >200% = 异常值（未填拆股表的
     *       拆股票可能出现，如 NFLX 现价 70 而支撑停在拆股前的千元档）—— 一律丢弃重算。
     */
    private fun sane(pct: Double): Boolean = pct.isFinite() && pct >= 0.0 && pct <= 200.0

    /**
     * 该品种在**取数侧**用的符号 —— 与 [deriveFromAvailable] 的 when 分支**同源**，改一处必须改两处。
     *
     * ⚠⚠ 2026-10-08 这个函数存在的唯一理由：**同一个品种在两处用了两个名字**。
     *   · 自选/存储侧：XAUUSD / XAGUSD（用户在搜索框里看到、FavStore 里存的就是这个）
     *   · 取数/留底侧：GC=F / SI=F（`fetchMetals` 要的是这个，留底键也是这个）
     *   而留底 `MktSave.saved()` 是**按名字**取的，两套名字对不上就**永远查不到**
     *   —— 黄金因此每次冷启动都重拉，东财单腿一抖就「—无源」。
     *
     * 这里**只做名字对应**：不发起任何请求、不新增数据源，纯粹是把已经躺在盘里的
     * 同一份数据用它的另一个名字取出来。
     *
     * @return 取数符号；与自身同名（不需要别名）时返回 null。
     */
    private fun fetchSym(s: String): String? = when (MktData.symType(s)) {
        "gold" -> "GC=F"
        "silver" -> "SI=F"
        else -> null
    }

    // 跨进程持久化旧值(无/坏则null):杀进程重进、断网时兜底展示上次成功值
    fun lastOk(s: String, tf: String, n: Int): DistR? {
        ensureLoaded()
        val v = synchronized(store) { store[key(s, tf, n)] } ?: return null
        if (v.optString("st") != "ok") return null
        val pct = v.optDouble("pct", Double.NaN)
        if (pct.isNaN()) return null
        if (!sane(pct)) return null          // 旧版坏值：不顶屏，交给重算
        return DistR(true, pct, v.optDouble("px", 0.0), v.optDouble("chg", 0.0),
            ts = v.optLong("ts", 0L),
            sup1 = v.optDouble("sup1", 0.0), sup2 = v.optDouble("sup2", 0.0))
    }

    // 展示优先级唯一所有者:新值ok/soft(数据已到,含—无支撑)恒优先;
    // 硬失败(无源/超时/null)时有持久化旧值→沿用旧值,从未成功过才回落失败文案
    fun display(s: String, tf: String, n: Int, fresh: DistR?): DistR? {
        if (fresh != null && (fresh.ok || fresh.soft)) return fresh
        lastOk(s, tf, n)?.let { return it }
        return fresh
    }

    /**
     * 屏上这一行**最终会显示的东西**（fresh 由本函数自己取）。
     * 与 [display] 逐字同源，只是把「新鲜缓存」这一参也包进来 —— 调用方不必自己拼
     * `display(s, tf, n, cached(s, tf, n))`，少一处能写歪的地方。
     */
    fun shown(s: String, tf: String, n: Int): DistR? = display(s, tf, n, cached(s, tf, n))

    /**
     * ⚠⚠ 2026-10-10 **当前零调用点，但按裁定「保留函数、不删」**（本次只改注释，函数体一字未动）。
     *
     * 【为什么零调用点】它**唯一**的调用者是 FavPanel.onShow ③「按缺值补刷」那条路径
     *   （`!FavDist.hasValue(s, tf, n) && now - lastMissFetchAt[s] >= MISS_REFETCH_GAP_MS`
     *   → `refreshFavDist(only = missing)`）。用户 2026-10-10 裁定「**切页不刷**」
     *   （原话：「每次到自选页面就要自动刷新数据，明明只是随便切一下页面而已。」）
     *   ⟹ 那条路径连同 `lastMissFetchAt` / `MISS_REFETCH_GAP_MS` 一起删了，本函数随之成为死代码。
     *   墓碑在 **FavPanel.kt:1672-1688**（删了什么、为什么删、还活着的刷新入口有哪些）。
     *
     * 【保留理由】它是 [shown] / [display] 这组**同源判据**的一部分：
     *   `hasValue(s,tf,n) = shown(s,tf,n)?.let { it.ok || it.soft } ?: false` ——
     *   与「屏上显示什么」用的是**同一个** [shown]，结构上不可能分叉。
     *   将来任何补刷需求（"只补真正缺的那几行"）都该拿它当判据；自己另写一套
     *   `cached() != null` 之类的判据，就会重演下面那条已修过的缺陷 A。
     *
     * 【判据（原文保留，仍是这个函数的口径）】与 [display] 的取值来源**逐条对应**，
     *   用 [shown] 保证结构上同源：
     *   ① cached 命中且 ok/soft（新鲜值；soft=数据已到、只是该窗口算不出支撑）
     *   ② 否则 lastOk() 的落盘旧值
     *   ⚠ 失败结果（ok=false 且 soft=false，如「—无源」/「—超时」）**不算有值** ——
     *     它虽然会被 [display] 顶屏，但那是失败文案不是值；这种行正是「要补」的对象。
     *
     * 【为什么不能拿 [cached] 当判据 —— 这就是「每次切页就自动刷新」的根因（缺陷 A）】
     *   [cached] 只认 **5 分钟**（[TTL_OK]）的新鲜缓存；而屏上显示走 [display]，
     *   新鲜值没有时会回落 [lastOk] 的**落盘旧值**（冷启动/断网都能顶屏，见本文件头注释）。
     *   两个判据不同源 ⟹ 缓存一过 5 分钟，每一行都被判成「一个值都没有」
     *   ⟹ 切一次页就把整屏重算一遍。
     *
     * 【历史 · 已作废（别当依据）】本函数 2026-10-09 的旧注释自称是「判缺值的**唯一判据**」，
     *   依据是用户 2026-10-05 原话（**【已作废】**，见下句）「没有获取到的，可以在限流措施内继续刷，但是只刷新他」。
     *   **两句都已过期**：2026-10-10 用户裁定「切页不刷」正是对那句 2026-10-05 的**取代**
     *   （两条都不再要了，见 FavPanel.kt:1635-1639），唯一调用点也随之删除
     *   ⟹ 现在没有任何东西在读这个判据，「唯一判据」这个自称不再成立。
     */
    fun hasValue(s: String, tf: String, n: Int): Boolean {
        val d = shown(s, tf, n) ?: return false
        return d.ok || d.soft
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(store) {
            if (loaded) return
            val a = app ?: return
            try {
                val txt = a.getSharedPreferences(PF, Context.MODE_PRIVATE)
                    .getString(PKEY, "{}") ?: "{}"
                val o = JSONObject(txt)
                val normalized = LinkedHashMap<String, JSONObject>()
                val it = o.keys()
                while (it.hasNext()) {
                    val rawKey = it.next()
                    val entry = o.optJSONObject(rawKey) ?: continue
                    val canonical = canonicalKey(rawKey)
                    // A canonical record wins even when an old alias was serialized first.
                    if (!normalized.containsKey(canonical) || rawKey == canonical) {
                        normalized[canonical] = entry
                    }
                }
                store.clear()
                store.putAll(normalized)
                if (normalized.keys != o.keys().asSequence().toSet()) {
                    val migrated = JSONObject()
                    for ((k, v) in normalized) migrated.put(k, v)
                    a.getSharedPreferences(PF, Context.MODE_PRIVATE)
                        .edit().putString(PKEY, migrated.toString()).apply()
                }
            } catch (_: Exception) {
            }
            loaded = true
        }
    }

    // 成功即落盘(仅派生量);超上限按ts淘汰最旧;任何异常吞掉不崩
    private fun save(k: String, r: DistR) {
        val a = app ?: return
        ensureLoaded()
        val canonical = canonicalKey(k)
        try {
            synchronized(store) {
                store[canonical] = JSONObject()
                    .put("pct", r.pct).put("px", r.price).put("chg", r.chg)
                    .put("ts", r.ts).put("st", "ok")
                    .put("sup1", r.sup1).put("sup2", r.sup2)
                while (store.size > MAXE) {
                    var oldest: String? = null
                    var ots = Long.MAX_VALUE
                    for ((kk, v) in store) {
                        val t = v.optLong("ts", 0L)
                        if (t < ots) {
                            ots = t
                            oldest = kk
                        }
                    }
                    val od = oldest ?: break
                    store.remove(od)
                }
                try {
                    val o = JSONObject()
                    for ((kk, v) in store) o.put(kk, v)
                    a.getSharedPreferences(PF, Context.MODE_PRIVATE)
                        .edit().putString(PKEY, o.toString()).apply()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    // 同步从共享数据引擎读取并缓存派生结果（调用方放后台线程）；同 key 的重复计算复用在途结果。
    fun compute(s: String, tf: String, n: Int): DistR = compute(s, tf, n, false)

    /**
     * 绕过本地 5 分钟派生缓存，按共享数据引擎当前批次重新计算距离。
     * ⚠ 本函数**不发网络请求**；唯一取数口是 [MktData.acquire]。
     * 调用方必须先确认 acquire(refresh=true) 返回了新批次；失败时不能用旧留底重算并刷新时间戳。
     */
    fun recomputeFresh(s: String, tf: String, n: Int): DistR = compute(s, tf, n, true)

    private fun compute(s: String, tf: String, n: Int, forceRecompute: Boolean): DistR {
        if (!forceRecompute) cached(s, tf, n)?.let { return it }
        val k = key(s, tf, n)
        // 强制重算用独立在途键，避免与普通的 5 分钟缓存命中路径串用结果。
        val ik = if (forceRecompute) "$k#recompute" else k
        val mine = java.util.concurrent.CompletableFuture<DistR>()
        val prev = inflight.putIfAbsent(ik, mine)
        if (prev != null) {
            // 同 key 已有在途计算：等待并复用结果，不重复计算。此处不持 monitor，
            // 因此不会再出现 ART 的 monitor contention 警告。
            return try {
                prev.get(inflightWaitMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
                // 首算者异常/超时：能读缓存就回落缓存，否则给失败文案，绝不无限期挂住
                cached(s, tf, n) ?: DistR(false, why = "—超时")
            }
        }
        try {
            val r = deriveFromAvailable(s, tf, n, k, forceRecompute) // 只读共享数据并派生，无网络请求
            mine.complete(r)
            return r
        } catch (e: Throwable) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            inflight.remove(ik, mine)
        }
    }

    // 只从共享数据引擎读当前批次并派生距离；所有网络取数都由 MktData.acquire 负责。

    private fun deriveFromAvailable(
        s: String, tf: String, n: Int, k: String, forceRecompute: Boolean = false
    ): DistR {
        if (!forceRecompute) cached(s, tf, n)?.let { return it }
        // ⚠ 2026-11-03 t16（t4 的 R3）：已下线市场（旧收藏里的港股/A股）不当有源品种：
        //   不读旧批次、不折算、不算支撑，行内直接说「—不支持」（徽章另显示「不支持」）。
        if (MktData.symType(s) == "unsupported") {
            val bad = DistR(false, why = "—不支持")
            synchronized(this) { cache[k] = Pair(System.currentTimeMillis(), bad) }
            return bad
        }
        val t0 = System.currentTimeMillis()
        var r = DistR(false)
        try {
            // ⚠ 2026-11-02 **自选页不取数**（用户裁定）：这里只读数据引擎已经落好的那批
            //   （内存缓存 → 落盘留底），绝不自己发请求。取数由 MktData.acquire 在
            //   「行情页取数」或「前台定时刷新」时统一完成 ⟹ 两屏永远是同一批数据。
            //   `forceRecompute` 现在只表示"绕过本文件 5 分钟缓存、按当前这批重算"，不再表示"真抓"。
            val vs = MktData.readOnly(s, tf)
            if (vs.isNotEmpty()) {
                val ag = MktData.aggregate(vs, s)
                // 显示侧与取数侧分离后 ag.ks 可能是 240 根；距离按 distWin 的 n 切尾，与行情 renderAgg 同口径。
                val show = ag.ks.takeLast(n.coerceAtLeast(2))
                if (show.size >= 2) {
                    val vp = MktData.profileOf(show)
                    val cur = show.last().c
                    val prv = show[show.size - 2].c
                    // 只取现价下方的支撑：第一 = 其中最高的一条，第二 = 次高的一条（与行情页 supShow 同源）。
                    val below = vp.sup.filter { it < cur }
                    val sup1 = below.maxOrNull()
                    val sup2 = if (sup1 != null) below.filter { it < sup1 }.maxOrNull() else null
                    val sup = sup1 ?: vp.sup.filter { it >= cur }.minOrNull()
                    r = if (sup != null && cur > 0) {
                        // 距支撑恒 ≥ 0（「有没有跌破」由图上的价格线表达，不由这个数表达）。
                        DistR(
                            true, Math.abs((cur - sup) / cur * 100.0), cur,
                            if (prv > 0) (cur - prv) / prv * 100.0 else 0.0,
                            ts = System.currentTimeMillis(), sup1 = sup, sup2 = sup2 ?: 0.0
                        )
                    } else {
                        // 数据拉到、但该窗口算不出支撑（≠「无源」）：软失败，按成功缓存。
                        DistR(false, soft = true, why = "—无支撑")
                    }
                }
            }
        } catch (_: Exception) {
            // 汇率缺失 / 网络异常一律落到下面的「—无源 / —超时」，绝不拿半截数据顶屏。
        }
        // 仅真失败(数据没到)才标无源/超时;soft按成功走5分钟缓存、不进5秒重试
        if (!r.ok && !r.soft) r = DistR(
            false,
            why = if (System.currentTimeMillis() - t0 >= 9000) "—超时" else "—无源"
        )
        synchronized(this) {
            cache[k] = Pair(System.currentTimeMillis(), r)
        }
        if (r.ok) {
            okSeen.add(k)
            save(k, r)
        }
        return r
    }

    // fullSup:强制绕过派生缓存，用共享数据引擎当前批次重算支撑；网络由 MktData.acquire 预先完成。
    fun fullSup(s: String, tf: String, n: Int): DistR = compute(s, tf, n)

    // lightPx:轻量重用派生缓存；过期后只从共享 K 线批次重算，不发网络请求。
    fun lightPx(s: String, tf: String, n: Int): DistR =
        cached(s, tf, n) ?: compute(s, tf, n)
}
