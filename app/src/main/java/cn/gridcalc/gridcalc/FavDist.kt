package cn.gridcalc.gridcalc

// ---------- 自选距离列(对标稿子favDist/refreshFavDist终态) ----------
// 每行距第一支撑pct+现价+涨跌幅;key=type|SYM|tf|n(tf/n读行情页),
// 5分钟缓存;股票/币走现有legs,贵金属直接无源;失败why按耗时判—超时/—无源。

data class DistR(
    val ok: Boolean,
    val pct: Double = 0.0,
    val price: Double = 0.0,
    val chg: Double = 0.0,
    val why: String = "—"
)

object FavDist {
    private const val TTL = 5 * 60 * 1000L

    private val cache = mutableMapOf<String, Pair<Long, DistR>>()

    fun key(s: String, tf: String, n: Int): String =
        MktData.symType(s) + "|" + s + "|" + tf + "|" + n

    @Synchronized
    fun cached(s: String, tf: String, n: Int): DistR? {
        val h = cache[key(s, tf, n)] ?: return null
        if (System.currentTimeMillis() - h.first >= TTL) return null
        return h.second
    }

    // 同步拉取并缓存(调用方放后台线程)
    fun compute(s: String, tf: String, n: Int): DistR {
        cached(s, tf, n)?.let { return it }
        val t0 = System.currentTimeMillis()
        var r = DistR(false)
        try {
            val vs = when (MktData.symType(s)) {
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
                else -> null
            }
            if (vs != null && vs.isNotEmpty()) {
                val ag = MktData.aggregate(vs)
                val vp = MktData.profileOf(ag.ks)
                val cur = ag.ks.last().c
                val below = vp.sup.filter { it < cur }
                if (below.isNotEmpty()) {
                    val prv = if (ag.ks.size > 1) ag.ks[ag.ks.size - 2].c else cur
                    r = DistR(
                        true,
                        (cur - below[0]) / cur * 100.0, cur,
                        (cur - prv) / prv * 100.0
                    )
                }
            }
        } catch (_: Exception) {
        }
        if (!r.ok) r = DistR(
            false,
            why = if (System.currentTimeMillis() - t0 >= 9000) "—超时" else "—无源"
        )
        synchronized(this) {
            cache[key(s, tf, n)] = Pair(System.currentTimeMillis(), r)
        }
        return r
    }
}
