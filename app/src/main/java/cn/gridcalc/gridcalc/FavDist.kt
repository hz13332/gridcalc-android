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
    val ts: Long = 0L
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
    // 单次抓取最坏耗时可观(股走稿§6串行腿:东财6s+Nasdaq9s+腾讯9s+Yahoo9s;加密腿单腿10s),
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
        MktData.symType(s) + "|" + s + "|" + tf + "|" + n

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
     * ⚠ 2026-10-07 **落盘毒值防线（实机实证）**。
     *   真机上同一行先显示 +1532.43%、清数据后变 +1117.12%，而正解（尺度带收紧后）是 1.52%
     *   —— 说明**上一版算出的坏值被持久化了**，SP 里的旧值可以压过新代码最多 24 小时。
     *   用户报「博通/腾讯数字夸张」很可能看的就是这种残值。
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
     * 该品种在**取数侧**用的符号 —— 与 [fetchAndDerive] 的 when 分支**同源**，改一处必须改两处。
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
            ts = v.optLong("ts", 0L))
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
                val it = o.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    o.optJSONObject(k)?.let { e -> store[k] = e }
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
        try {
            synchronized(store) {
                store[k] = JSONObject()
                    .put("pct", r.pct).put("px", r.price).put("chg", r.chg)
                    .put("ts", r.ts).put("st", "ok")
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

    // 同步拉取并缓存(调用方放后台线程);同key在途复用首抓结果,**网络取数在任何锁之外**
    fun compute(s: String, tf: String, n: Int): DistR = compute(s, tf, n, false)

    /**
     * ⚠⚠ 2026-10-09 **真抓一轮**（用户裁定：冷启动首次进自选那轮要「后台错峰真抓一轮覆盖」）。
     *
     * 【为什么必须单开一条路 —— 反直觉但已核实的现役行为】
     *   [fetchAndDerive] 的复用链只要命中（`r.ok || r.soft`）就**直接返回、一个请求都不发**；
     *   而自选屏用过的三级复用（LastChart / MktCache.cachedFor / MktSave.saved）在
     *   「用户打开过该品种」时几乎必中 ⟹ 现役的 refreshFavDist 多半只是「拿缓存重算一遍」，
     *   裁定的「首次真抓一轮」根本落不到地上。
     *
     * 【口径】绕过**全部**复用层：LastChart / MktCache / MktSave，也绕开本文件 5 分钟新鲜缓存
     *   （[cached]），对每个品种**真的发起网络取数**。
     *   ⚠ 失败**不抛异常**：返回失败 DistR，由调用方 [display] 回落 [lastOk] 的留底旧值 ——
     *     这就是「留底先上屏 + 真抓覆盖，抓不到就继续顶着旧值」。
     *   ⚠ 真抓成功后照常写 [MktData.MktSave]（带 tf），下一轮就能被复用层兜住。
     */
    fun forceFetch(s: String, tf: String, n: Int): DistR = compute(s, tf, n, true)

    private fun compute(s: String, tf: String, n: Int, forceNet: Boolean): DistR {
        if (!forceNet) cached(s, tf, n)?.let { return it }
        val k = key(s, tf, n)
        // ⚠ 真抓轮用**独立的在途键**：若与普通轮共享同一个 Future，首轮可能等到一个
        //   由缓存/留底短路出来的结果，那就不叫真抓了。代价是极端情况下同 key 并发两条请求，
        //   而首轮每进程只跑一次，可接受。
        val ik = if (forceNet) "$k#net" else k
        val mine = java.util.concurrent.CompletableFuture<DistR>()
        val prev = inflight.putIfAbsent(ik, mine)
        if (prev != null) {
            // 同key已有在途抓取:等它完成复用结果(不重复发请求)。此处不持任何 monitor,
            // 因此不会再出现 ART 的 monitor contention 警告。
            return try {
                prev.get(inflightWaitMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
                // 首抓者异常/超时:能读缓存就回落缓存,否则给失败文案,绝不无限期挂住
                cached(s, tf, n) ?: DistR(false, why = "—超时")
            }
        }
        try {
            val r = fetchAndDerive(s, tf, n, k, forceNet) // 网络取数在锁外
            mine.complete(r)
            return r
        } catch (e: Throwable) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            inflight.remove(ik, mine)
        }
    }

    // 真正的取数 + 派生:网络在这里(无 per-key monitor),写值只在 synchronized(this)/synchronized(store) 内

    private fun fetchAndDerive(s: String, tf: String, n: Int, k: String, forceNet: Boolean = false): DistR {
        if (!forceNet) cached(s, tf, n)?.let { return it }
        val t0 = System.currentTimeMillis()
        var r = DistR(false)
        try {
            val type = MktData.symType(s)

            // ⚠⚠⚠ 2026-10-03 **优先复用行情屏抓到的数据，不重复取数**。
            //
            // 【用户原话】「直接就用行情屏抓到的，他数据不是可以存嘛？怎么可能抓不到」
            //   —— 对，它**存了**，而这里**从来没去读**。
            //
            // 【原来的行为】行情屏取完 → MktCache.put + MktSave.store（都做了），
            //   而本函数开头只有 `cached(s,tf,n)`（**本文件自己的另一个 map**），
            //   查不到就直接再抓一遍。于是**同一个品种被抓两次**。
            //
            // 【为什么必须改 —— 不是省流量，是数据要一致】
            //   两屏各自取数，各自算 `vp.sup`。源不同/根数不同/时点不同/其中一个源返回坏柱，
            //   就会算出**不同的支撑位**。用户实报「并没有跌破支撑线，却显示负数」，
            //   根因就在这里：屏上显示的支撑和这个函数算的不是同一个数。
            //   ⟹ 统一数据源，两屏的支撑位**必然一致**。
            //
            // 【查找顺序】行情末帧LastChart → MktCache按品种扫 → 落盘 MktSave → 才自己抓
            //   ⟹ 前三级覆盖「用户打开过该品种」的全部情形（含冷启动，第二次进 App 就有）。
            //   ⟹ 第四级必须保留：**从没在行情屏打开过的品种**自选屏仍要能出数。
            //   ⚠ 2026-10-07 泛化：精确键get恒miss（自选n=20 vs 取数档240），改按品种扫；
            //     LastChart是行情屏刚画完的那批K线（已fxify+已复权），与屏上支撑同源。
            //
            // 【顺带的限流收益】自选屏原本为**每个品种独立打一轮**（股类含东财腿）。
            //   这正是把 IP 打进封禁的放大器之一 —— 复用后自选屏的网络请求基本归零。
            //   ⚠ 2026-10-09 **三级复用一律只认同一周期（tf）**（用户裁定：距支撑的旧数据复用
            //     只认同一周期，根数 n 仍可放宽）。下游只有 takeLast(n) 能把**根数**归一，
            //     **周期归不了一** ⟹ 复用到另一个周期的K线，距支撑就是在那个周期上算出来的。
            //     三级的 tf 校验都在各自内部（LastChart.get / cachedFor / MktSave.saved），
            //     这里只负责把 tf 传下去。
            //   ⚠ 真抓轮（forceNet）**整段跳过**：用户裁定冷启动首轮要「真抓一轮覆盖」，
            //     而这一段只要命中就直接 return、一个请求都不发（见 forceFetch 的注释）。
            //
            // ⚠⚠⚠ 2026-10-10 **四条取数路径的数据口径对照**（用户报「取支撑的数据很奇怪。
            //   每次取完数据之后，就和之前保存的数据相差很大」——查实结论写在这里）。
            //
            //   【共同的下游】四条路径最后都汇到下面 `MktData.aggregate(vsShared, s)`，
            //     再 `takeLast(n)` → `profileOf(show)` → `sup`。所以「口径」指的就是
            //     **喂给 aggregate 的那批 K 线是什么状态**。全项目只有 aggregate 会调
            //     `SplitBook.apply`（手动拆股前复权），于是每条路径的**复权次数**是关键。
            //
            //   ① LastChart（本段第 1 级）——MktPanel.renderAgg 放的 `show`
            //        源：行情屏刚画完的那批；根数：`ag.ks.takeLast(curN())`（用户填的显示根数）
            //        复权：**已 fxify + 已复权**（它是 aggregate 的产物）⟹ 本函数再 aggregate
            //              = **复权 2 次** ❌ 改前就是这样
            //   ② MktCache.cachedFor（第 2 级）
            //        源：fetchStocks 的原始腿；根数：取数档（`|tf|n@rate`，n=240）
            //        复权：**复权之前**（写盘顺序 `fxify` → `MktCache.put` → `aggregate`）
            //              ⟹ 本函数 aggregate = **复权 1 次** ✅
            //   ③ MktSave.saved(ckShared/ckAlt)（第 3 级，含取数符号别名那一次）
            //        源：同上，落盘留底；根数：存的时候是多少就是多少（`saved()` 只校验 tf，不看 n）
            //        复权：**复权之前**（`MktSave.store` 在 `renderStock(aggregate(...))` **之前**）
            //              ⟹ 本函数 aggregate = **复权 1 次** ✅
            //   ④ 网络路径（本函数下方 fetchStocks/fetchMetals/fetchCryptoLeg）
            //        源：东财 `fqt=1`（**已前复权**）/ 腾讯 `qfq` / 百度（不复权）
            //        根数：直接按自选窗 `n` 取
            //        复权：SplitBook 意义上的**未复权** ⟹ 本函数 aggregate = **复权 1 次** ✅
            //
            //   【跳变的真实原因】②③④ 都是 1 次，**只有 ① 是 2 次**，而 `SplitBook.apply`
            //     不是幂等的（按比例除价、乘量，再来一次就再除一次）⟹ 用户只要在行情屏看过
            //     该品种（LastChart 命中），切回自选算出的支撑就和真抓/缓存路径**不是同一个数**。
            //     ⚠ 墓碑 · 2026-10-12 尺度带已删（用户裁定选 A），下面这段描述的是**历史行为**，
            //       「带内集合」指的已是一个不存在的过滤器。结论本身仍成立：二次复权会把第一段
            //       再除一次 ⟹ sup 变（只是不再经过「带内集合」这个中间量）。
            //     ⚠ 尺度带（profileOf 里的 `[cur/3, cur*3]`）**不会**把差异约掉：
            //       拆股日在窗口之前 ⟹ 整窗均匀缩放 ⟹ 带内集合不变 ⟹ sup 不变（离线实测差 0.000000）；
            //       拆股日**在窗口之内** ⟹ 只有一部分柱子被除，再除一次会把第一段推出带外
            //       ⟹ **带内集合变小** ⟹ sup 变（离线实测 r=3：带内 20 根 sup=0.606250%
            //        vs 19 根 sup=2.554861%）。完整数值见 `KLine.adj` 的注释。
            //
            //   【修法】不在本函数里分叉（那会变成"每条路径各一套口径"，正是缺陷的来源），
            //     而是让 `SplitBook.apply` **幂等**：出口给每根打 `KLine.adj`，入口整批已标记
            //     就原样返回 ⟹ 同一批数据不论被 aggregate 几次，复权**恰好生效一次**，
            //     四条路径必然同值，而本函数一个字都不用改。
            //     ⚠ **没有新增任何请求**（用户 2026-10-10：「抓取行情信息不要频繁打，
            //       不要被限流」）：标记只在内存对象上，落盘格式不变。
            var vsShared: List<VendorKs>? = null
            if (!forceNet) {
                val lastKs = MktData.LastChart.get(s, tf)
                if (lastKs != null && lastKs.size >= 2) {
                    // LastChart存的是已聚合单腿K线，直接包一层即可，不再aggregate选腿
                    vsShared = listOf(VendorKs("LAST", lastKs))
                }
                if (vsShared == null || vsShared.isEmpty()) {
                    vsShared = MktData.MktCache.cachedFor(s, tf)
                }
                val ckShared = MktData.MktCache.key(s, tf, n)
                if (vsShared == null || vsShared.isEmpty()) {
                    vsShared = runCatching { MktData.MktSave.saved(ckShared) }.getOrNull()
                }
            // ⚠⚠⚠ 2026-10-08 用户报「大宗商品自选页距支撑**老是无源**」——**最后一块拼图在这里**。
            //
            // 【受控实验证据】清空留底(gridcalc_mk_v1)→ force-stop → 启动 → 只等 12 秒：
            //   005930/00700/AVGO/NFLX/ORCL 的留底时间戳**全都没变**（06:31:5x）
            //   ⟹ 它们靠留底复用出数，兜底链路是通的；
            //   而 **GC=F 的时间戳从 06:31:53 变成 06:33:12 ⟹ 只有黄金在重新拉取**。
            //
            // 【根因：同一个品种两个符号，存用一个、查用另一个】
            //   · 自选这侧(FavStore)存的符号是 **XAUUSD**，于是这里用 `key("XAUUSD",…)` 去查；
            //   · 而落盘/行情这侧写进去的键是**取数符号** —— 贵金属走
            //     `fetchMetals("GC=F"/"SI=F",…)`（见下方 when 的 "gold"/"silver" 分支），
            //     所以留底里躺着的是 **GC=F**（实验实测：GC=F, bars=240, 东财）。
            //   ⟹ `saved()` 虽已「按品种取」，但取的是**这个品种名**，两套名字对不上 ⟹ **永远 miss**
            //   ⟹ 每次冷启动都要重拉，而贵金属被裁定删掉新浪后**只剩东财一个腿**
            //     （MktData.kt:1279 原话「没有退路」）+ **排在自选最后**
            //     （FavPanel.kt:1514 的注释早就写着「黄金稳定中招」）⟹ 一抖就无源。
            // 【修法】查询侧补一次**取数符号**。不新增数据源、不发任何请求，
            //   只是把已经躺在盘里的同一份数据用它的另一个名字取出来。
            //   ⚠ 映射表与下方 `fetchAndDerive` 的 when 分支**必须同源**，否则又会出现两套名字。
                if (vsShared == null || vsShared.isEmpty()) {
                    val fs = fetchSym(s)      // ⚠ 用 if 而非 ?.let：let 的闭包会捕获 vsShared，
                    if (fs != null) {         //   使后面的 smart cast 失效（编译期实测报错）
                        val ckAlt = MktData.MktCache.key(fs, tf, n)
                        vsShared = runCatching { MktData.MktSave.saved(ckAlt) }.getOrNull()
                    }
                }
            }   // ← 三级复用到此为止（真抓轮整段跳过；上面每一级内部都已校验 tf）

            if (vsShared != null && vsShared.isNotEmpty()) {
                val ag = MktData.aggregate(vsShared, s)
                // ⚠ 显示侧与取数侧分离后，ag.ks可能是240根；距离按distWin的n切尾，与行情renderAgg同口径
                val show = ag.ks.takeLast(n.coerceAtLeast(2))
                if (show.size >= 2) {
                    val vp = MktData.profileOf(show)
                    val cur = show.last().c
                    val prv = show[show.size - 2].c
                    // ⚠ 2026-10-07 泛化修失真：取**离现价最近**的下方支撑（max），不依赖sup列表顺序假设。
                    //   原below[0]在列表降序时正确，一旦上游顺序变了就是最远一条，AVGO/0700的夸张数即此类。
                    val below = vp.sup.filter { it < cur }
                    val sup = below.maxOrNull()
                        ?: vp.sup.filter { it >= cur }.minOrNull()
                    r = if (sup != null && cur > 0) {
                        DistR(true, Math.abs((cur - sup) / cur * 100.0), cur,
                            if (prv > 0) (cur - prv) / prv * 100.0 else 0.0, ts = System.currentTimeMillis())
                    } else {
                        DistR(false, soft = true, why = "—无支撑")
                    }
                }
            }

            // ⟹ 只有复用未命中才真去网络取
            // ⚠ 2026-10-09 **真抓轮（forceNet）无条件走网络**：上面整段复用被跳过 ⟹ 此处
            //   r 仍是初始的失败值，本来也会落进 when；写成显式条件是为了让「真抓」这件事
            //   在本行一眼可读，将来谁再往上面加一条复用捷径都不会把它悄悄短路掉。
            val vs = if (!forceNet && (r.ok || r.soft)) {
                emptyList()
            } else {
                when (type) {
                    "crypto" -> MktData.cryptoLegs(s).mapNotNull {
                        try {
                            MktData.fetchCryptoLeg(it, s, tf, n)
                        } catch (_: Exception) {
                            null
                        }
                    }
                    // 股(美/港/韩)统一走稿§6串行腿:东财→Nasdaq→腾讯→Yahoo命中即停;
                    // hk/kr 路由进这里(fullSup/lightPx两条取数路径共用本when分发),
                // 绝不落 gold/silver/cmdty 的商品兜底分支(漏接=自选恒「—无源」)
                "stock", "hk", "kr" -> try {
                    MktData.fetchStocks(s, tf, n)
                } catch (_: Exception) {
                    null
                }
                // 贵金属/商品:东财单源(稿favDist分支:gold→GC=F silver→SI=F cmdty→s)
                "gold" -> try { MktData.fetchMetals("GC=F", tf, n) } catch (_: Exception) { null }
                "silver" -> try { MktData.fetchMetals("SI=F", tf, n) } catch (_: Exception) { null }
                "cmdty" -> try { MktData.fetchMetals(s, tf, n) } catch (_: Exception) { null }
                // ⚠ 2026-10-07 外汇/美债分支已退役（用户：不要外汇，美债只留 TLT）。
                //   TLT 是 ETF，上面的 "stock" 分支本来就覆盖它，不需要单独分支。
                // ⚠ 2026-10-03 **新增 A 股路由**：用户给的 sh688825（长鑫存储/长鑫科技，科创板）
                //   走 fetchStocks —— 东财 secid 规则 `sh688825` 它认，腾讯 `sh688825` 也认。
                //   ⚠ 之前 A 股**根本没有任何分支**，`else -> null` 直接吞掉，
                //     所以 A 股在自选屏恒为「—无源」。这一条是补漏，不是新增能力。
                "ashare" -> try { MktData.fetchStocks(s, tf, n) } catch (_: Exception) { null }
                else -> null
                }
            }
            // ⟹ 复用命中时 vs 是空的，走这里不会覆盖已算好的 r
            if (vs != null && vs.isNotEmpty()) {
                // ⚠⚠ 2026-10-08 用户报「大宗商品自选页距支撑**老是无源**，即使已经保存了数据，
                //   这不就是计算一下子的事情吗」——**根因就在这里：自选页从来没写过留底**。
                //
                // 【事实】全项目 `MktSave.store` 的调用点**全在 MktPanel（行情页）**：
                //   :1328 / :1697 / :1779，**FavDist 一处都没有**。
                //   ⟹ 自选页第 4 级取到数、算出了距离，**落盘仍是空的**；
                //      下次冷启动/复用未命中又要重拉，而贵金属（gold/silver）被用户裁定
                //      删掉新浪后**只剩东财一个腿**（MktData.kt:1279 注释原话：没有退路），
                //      东财一抖就失败 ⟹ 陷入「成功→不存→再拉→失败→无源」的死循环。
                // 【实测证据】落盘 gridcalc_mk_v1 里只有 AVGO / B00Y / NG00Y，
                //   **XAUUSD 与 GC=F 都不在** —— 黄金从没有成功留底过；
                //   而天然气/布伦特有数，恰恰因为它们的留底是**行情页**那条路写进去的。
                // 【补法】与行情页对称：**真实拉到数才写**（复用命中时 vs 为空，天然不写，
                //   免得拿旧值反复刷存盘时间）。写完后，下次失败就能被第 3 级 [MktSave.saved] 兜住
                //   —— 那个函数是**按品种取**的（见其注释「按品种取而非按完整键」），
                //   正好实现用户要的「算一下就行，不必再去打源」。
                //   ⚠ 键**必须**与查询侧 `saved(ck)` 的 `symOf(ck)`（取第一段）严格一致，
                //     否则「存了但查不到」——那就是又一次「保存了却仍显示无源」。
                //     故这里直接用 $s（当前品种），不借 tf/n 间接拼键。
                runCatching { MktData.MktSave.store("$s|$tf|$n@1.0", vs) }
                val ag = MktData.aggregate(vs, s)
                val show = ag.ks.takeLast(n.coerceAtLeast(2))
                val vp = MktData.profileOf(show)
                val cur = show.last().c
                val prv = if (show.size > 1) show[show.size - 2].c else cur
                val chg = if (prv > 0) (cur - prv) / prv * 100.0 else 0.0
                val below = vp.sup.filter { it < cur }
                val sup = below.maxOrNull()
                    ?: vp.sup.filter { it >= cur }.minOrNull()
                if (sup != null && cur > 0) {
                    // ⚠⚠⚠ 2026-10-03 **距支撑改为恒正**（用户裁定：「他并没有跌破支撑线，却显示负数」）。
                    //
                    // 【原逻辑的问题】上面那个 `else` 支会在**现价跌破全部支撑**时
                    // 选中一个**高于现价**的 sup，于是 `(cur - sup)` 为负 —— 但那个数
                    // 的语义是「**已跌破 N%**」，和这一列要的「**距支撑还有 N%**」
                    // 是两回事，一个带符号一个不带。
                    //
                    // 【为什么这不是显示偏好问题】用户报「并没有跌破支撑线，却显示负数」——
                    //   除语义错配外还有一处实质不一致：**本函数自己重新取一遍数据**算 sup，
                    //   与行情屏显示的现价/支撑**未必是同一批**（tf / 根数 / 时点都可能不同）。
                    //   两处 sup 对不上，就出现「屏上看着没破、背地里算成破了」。
                    //
                    // 【现在的语义】距支撑 = |现价 − 支撑| / 现价，**恒 ≥ 0**。
                    //   「有没有跌破」在图上一眼可见（价格线在支撑线下方），不由这个数表达。
                    //   ⚠ 收益比本来就取 Math.abs(distPct)（见 FavPanel.ratioOf），本改动不影响它。
                    val rawPct = (cur - sup) / cur * 100.0
                    r = DistR(true, Math.abs(rawPct), cur, chg,
                        ts = System.currentTimeMillis())
                } else {
                    // 稿NOSUP软分支:数据拉到了,只是该窗口算不出支撑位(≠「—无源」)
                    r = DistR(false, soft = true, why = "—无支撑")
                }
            }
        } catch (_: Exception) {
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

    // 稿fullSup:重抓支撑(全量K线→profileOf→支撑→pct),后台补抓环走此路;
    // 类型分发在 compute 内,stock/hk/kr 统一串行腿,不进商品兜底
    fun fullSup(s: String, tf: String, n: Int): DistR = compute(s, tf, n)

    // 稿lightPx:轻量刷价——有新鲜缓存直接复用(不发请求),过期才回落 fullSup 重抓;
    // 自选页首帧onShow走此路;同样经 compute 分发,hk/kr 接入
    fun lightPx(s: String, tf: String, n: Int): DistR =
        cached(s, tf, n) ?: compute(s, tf, n)
}
