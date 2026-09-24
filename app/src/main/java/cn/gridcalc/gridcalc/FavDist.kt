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
    // 同key在途互斥:并发到达者在锁上等待,拿到锁先查缓存直接复用首抓结果
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()

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

    // 跨进程持久化旧值(无/坏则null):杀进程重进、断网时兜底展示上次成功值
    fun lastOk(s: String, tf: String, n: Int): DistR? {
        ensureLoaded()
        val v = synchronized(store) { store[key(s, tf, n)] } ?: return null
        if (v.optString("st") != "ok") return null
        val pct = v.optDouble("pct", Double.NaN)
        if (pct.isNaN()) return null
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

    // 同步拉取并缓存(调用方放后台线程);同key在途互斥,并发重复抓取复用首抓结果
    fun compute(s: String, tf: String, n: Int): DistR {
        cached(s, tf, n)?.let { return it }
        val k = key(s, tf, n)
        val lock = locks.computeIfAbsent(k) { Any() }
        synchronized(lock) {
            cached(s, tf, n)?.let { return it }
            val t0 = System.currentTimeMillis()
            var r = DistR(false)
            try {
                val type = MktData.symType(s)
                val vs = when (type) {
                    "crypto" -> MktData.cryptoLegs(s).mapNotNull {
                        try {
                            MktData.fetchCryptoLeg(it, s, tf, n)
                        } catch (_: Exception) {
                            null
                        }
                    }
                    "stock" -> listOf(
                        { MktData.fetchNasdaq(s, tf, n) },
                        { MktData.fetchYahooDaily(s, tf, n) },
                        { MktData.fetchEastmoney(s, tf, n) }
                    ).mapNotNull {
                        try {
                            it().firstOrNull()
                        } catch (_: Exception) {
                            null
                        }
                    }
                    // 贵金属/商品:东财单源(稿favDist分支:gold→GC=F silver→SI=F cmdty→s)
                    "gold" -> try { MktData.fetchMetals("GC=F", tf, n) } catch (_: Exception) { null }
                    "silver" -> try { MktData.fetchMetals("SI=F", tf, n) } catch (_: Exception) { null }
                    "cmdty" -> try { MktData.fetchMetals(s, tf, n) } catch (_: Exception) { null }
                    else -> null
                }
                if (vs != null && vs.isNotEmpty()) {
                    val ag = MktData.aggregate(vs)
                    val vp = MktData.profileOf(ag.ks)
                    val cur = ag.ks.last().c
                    val prv = if (ag.ks.size > 1) ag.ks[ag.ks.size - 2].c else cur
                    val chg = (cur - prv) / prv * 100.0
                    val below = vp.sup.filter { it < cur }
                    val sup = if (below.isNotEmpty()) below[0]
                    else vp.sup.filter { it >= cur }.minOrNull()
                    if (sup != null) {
                        r = DistR(true, (cur - sup) / cur * 100.0, cur, chg,
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
    }
}
