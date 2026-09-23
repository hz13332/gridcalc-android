package cn.gridcalc.gridcalc

// ---------- 自选距离列(对标稿子favDist/refreshFavDist终态) ----------
// 每行距第一支撑pct+现价+涨跌幅;key=type|SYM|tf|n字符串键(tf/n读distWin冻结窗),
// 成功缓存5分钟/失败只压5秒让重试环真能再拉;币/股走现有legs,
// 贵金属与17种商品走东财单源(稿fetchMetals:GC=F/SI=F/*00Y),不再无源;
// 支撑:价在第一支撑上方取最高支撑(绿),全部支撑在上方(已跌破)取最近一条(红,负值)。
// 无支撑软分支(稿NOSUP):数据已到但该窗口算不出支撑→soft+「—无支撑」,按成功缓5分钟、不进5秒重试。

data class DistR(
    val ok: Boolean,
    val pct: Double = 0.0,
    val price: Double = 0.0,
    val chg: Double = 0.0,
    val why: String = "—",
    val soft: Boolean = false
)

object FavDist {
    private const val TTL_OK = 5 * 60 * 1000L
    private const val TTL_FAIL = 5 * 1000L

    private val cache = mutableMapOf<String, Pair<Long, DistR>>()

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

    // 同步拉取并缓存(调用方放后台线程)
    fun compute(s: String, tf: String, n: Int): DistR {
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
                    r = DistR(true, (cur - sup) / cur * 100.0, cur, chg)
                } else {
                    // 稿NOSUP软分支:数据拉到了,只是该窗口算不出支撑位(≠「—无源」)
                    r = DistR(false, soft = true, why = "—无支撑")
                }
            }
        } catch (_: Exception) {
        }
        // 仅真失败(数据没到)才标无源/超时;soft按成功走5分钟缓存、不进5秒重试环
        if (!r.ok && !r.soft) r = DistR(
            false,
            why = if (System.currentTimeMillis() - t0 >= 9000) "—超时" else "—无源"
        )
        synchronized(this) {
            cache[key(s, tf, n)] = Pair(System.currentTimeMillis(), r)
        }
        return r
    }
}
