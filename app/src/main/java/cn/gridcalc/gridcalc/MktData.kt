package cn.gridcalc.gridcalc

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// ---------- 行情 VPVR · 数据层(零外部依赖,HttpURLConnection+org.json) ----------
// 逻辑照搬设计稿:币三家现货按报价成交额qv选量最大一家,股走Nasdaq,
// 金银走Yahoo期货(GC=F/SI=F),
// tf映射 W:1w/1W/W M:1M/1M/M Q:取1M后三月合成季,
// limit按K数,Q按3倍截100,Yahoo按根数倒推range,
// Nasdaq按天数倒推fromdate后日线重采样,≥1家即画。

data class KLine(
    val t: Long, val o: Double, val h: Double,
    val l: Double, val c: Double, val v: Double, val qv: Double
)

data class VendorKs(val v: String, var k: List<KLine>)

data class Agg(val ks: List<KLine>, val src: String)

data class Profile(
    val rv: DoubleArray, val ru: DoubleArray, val rd: DoubleArray,
    val vaUp: Int, val vaDn: Int,
    val lo: Double, val hi: Double,
    val poc: Double, val vah: Double, val val_: Double
) {
    val pocRow: Int get() {
        var bi = 0
        for (i in rv.indices) if (rv[i] > rv[bi]) bi = i
        return bi
    }
}

object MktData {

    private const val UA = "Mozilla/5.0 (Linux; Android 13) GridCalc/2.2"

    fun symType(s: String): String {
        val u = (s ?: "").trim().uppercase(Locale.US)
        if (u.isEmpty()) return ""
        if (u.contains("XAU") || u.contains("GOLD") || u.startsWith("GC=F")) return "gold"
        if (u.contains("XAG") || u.contains("SILVER") || u.startsWith("SI=F")) return "silver"
        if (u.endsWith("USDT") || u.endsWith("USD") || u.endsWith("USDC") ||
            u.endsWith("BTC") || u.endsWith("ETH") ||
            u.contains("/") || u.contains("-")
        ) return "crypto"
        return "stock"
    }

    private fun numD(x: Any?): Double {
        val v = when (x) {
            null, JSONObject.NULL -> Double.NaN
            is Number -> x.toDouble()
            else -> x.toString().toDoubleOrNull() ?: Double.NaN
        }
        return if (v.isNaN()) 0.0 else v
    }

    private fun arrD(a: JSONArray, i: Int): Double =
        if (i < a.length()) numD(if (a.isNull(i)) null else a.get(i)) else 0.0

    private fun arrL(a: JSONArray, i: Int): Long =
        if (i < a.length()) a.optString(i, "0").toDoubleOrNull()?.toLong() ?: 0L else 0L

    internal fun get(url: String, accept: String? = null, ua: String = UA): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 15000
            setRequestProperty("User-Agent", ua)
            if (accept != null) setRequestProperty("Accept", accept)
            instanceFollowRedirects = true
        }
        try {
            val code = c.responseCode
            if (code !in 200..299) throw Exception("http $code")
            return c.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } finally {
            c.disconnect()
        }
    }

    fun fetchCrypto(sym: String, tf: String, count: Int): List<VendorKs> {
        var base = sym.uppercase(Locale.US).replace(Regex("[^A-Z]"), "")
            .replace(Regex("(USDT|USD|USDC)$"), "")
        if (base.isEmpty()) base = "BTC"
        val n = max(5, min(100, count))
        val mq = if (tf == "Q") min(n * 3, 100) else n
        val iv = when (tf) {
            "W" -> arrayOf("1w", "1W", "W")
            else -> arrayOf("1M", "1M", "M")
        }
        val out = mutableListOf<VendorKs>()
        // Binance 现货:data-api.binance.vision,k[7]=报价成交额
        try {
            val a = JSONArray(get(
                "https://data-api.binance.vision/api/v3/klines?symbol=${base}USDT&interval=${iv[0]}&limit=$mq"))
            val ks = (0 until a.length()).map { i ->
                val k = a.getJSONArray(i)
                KLine(arrL(k, 0), arrD(k, 1), arrD(k, 2), arrD(k, 3),
                    arrD(k, 4), arrD(k, 5), arrD(k, 7))
            }
            if (ks.size >= 3) out.add(VendorKs("BN", ks))
        } catch (_: Exception) {
        }
        // OKX 现货:instId=BASE-USDT,k[7]=volCcyQuote
        try {
            val data = JSONObject(get(
                "https://www.okx.com/api/v5/market/candles?instId=$base-USDT&bar=${iv[1]}&limit=$mq"))
                .optJSONArray("data") ?: JSONArray()
            val ks = (0 until data.length()).map { i ->
                val k = data.getJSONArray(i)
                KLine(arrL(k, 0), arrD(k, 1), arrD(k, 2), arrD(k, 3),
                    arrD(k, 4), arrD(k, 5), arrD(k, 7))
            }
            if (ks.size >= 3) out.add(VendorKs("OK", ks))
        } catch (_: Exception) {
        }
        // Bybit 现货:category=spot,k[6]=turnover
        try {
            val list = JSONObject(get(
                "https://api.bybit.com/v5/market/kline?category=spot&symbol=${base}USDT&interval=${iv[2]}&limit=$mq"))
                .optJSONObject("result")?.optJSONArray("list") ?: JSONArray()
            val ks = (0 until list.length()).map { i ->
                val k = list.getJSONArray(i)
                KLine(arrL(k, 0), arrD(k, 1), arrD(k, 2), arrD(k, 3),
                    arrD(k, 4), arrD(k, 5), arrD(k, 6))
            }
            if (ks.size >= 3) out.add(VendorKs("BB", ks))
        } catch (_: Exception) {
        }
        if (out.isEmpty()) throw Exception("行情拉取失败(网络或品种名不对)")
        if (tf == "Q") out.forEach { it.k = toQuarterly(it.k) }
        return out
    }

    fun toQuarterly(ks: List<KLine>): List<KLine> {
        val out = mutableListOf<KLine>()
        var i = 0
        while (i + 2 < ks.size) {
            val a = ks[i]; val b = ks[i + 1]; val c = ks[i + 2]
            out.add(KLine(a.t, a.o, max(a.h, max(b.h, c.h)), min(a.l, min(b.l, c.l)),
                c.c, a.v + b.v + c.v, numD(a.qv) + numD(b.qv) + numD(c.qv)))
            i += 3
        }
        return out
    }

    fun fetchStock(sym: String, tf: String, count: Int): List<VendorKs> {
        val n = max(5, min(200, count))
        val per = when (tf) { "M" -> 31; "Q" -> 92; else -> 7 }
        val need = n * per * 1.5
        val ranges = listOf("3mo" to 90, "6mo" to 180, "1y" to 365, "2y" to 730, "5y" to 1825)
        var rg = "5y"
        for ((r, d) in ranges) {
            if (need <= d) {
                rg = r
                break
            }
        }
        val iv = when (tf) { "M" -> "1mo"; "Q" -> "3mo"; else -> "1wk" }
        val enc = URLEncoder.encode(sym.trim(), "UTF-8")
        val j = JSONObject(get(
            "https://query1.finance.yahoo.com/v8/finance/chart/$enc?interval=$iv&range=$rg"))
        val r = j.optJSONObject("chart")?.optJSONArray("result")?.optJSONObject(0)
            ?: throw Exception("行情拉取失败(网络或品种名不对)")
        val ts = r.optJSONArray("timestamp") ?: throw Exception("行情拉取失败(网络或品种名不对)")
        val q = r.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)
            ?: throw Exception("行情拉取失败(网络或品种名不对)")
        val oo = q.optJSONArray("open"); val hh = q.optJSONArray("high")
        val ll = q.optJSONArray("low"); val cc = q.optJSONArray("close")
        val vv = q.optJSONArray("volume")
        val out = mutableListOf<KLine>()
        for (i in 0 until ts.length()) {
            val o = oo?.optDouble(i, Double.NaN) ?: Double.NaN
            if (o.isNaN()) continue
            out.add(KLine(ts.optLong(i, 0) * 1000, o,
                hh?.optDouble(i, o) ?: o, ll?.optDouble(i, o) ?: o,
                cc?.optDouble(i, o) ?: o, vv?.optDouble(i, 0.0) ?: 0.0, 0.0))
        }
        if (out.size < 3) throw Exception("行情拉取失败(网络或品种名不对)")
        return listOf(VendorKs("YH", out.takeLast(n)))
    }

    // 日线按周/月/季重采样(周一为周首,自然月/季,UTC)
    fun resampleDaily(ks: List<KLine>, tf: String): List<KLine> {
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val bucket = LinkedHashMap<Long, KLine>()
        for (k in ks) {
            val d = Calendar.getInstance(utc).apply { timeInMillis = k.t }
            val y = d.get(Calendar.YEAR)
            val m = d.get(Calendar.MONTH)
            val kk = Calendar.getInstance(utc).apply {
                clear()
                when (tf) {
                    "M" -> set(y, m, 1)
                    "Q" -> set(y, m / 3 * 3, 1)
                    else -> {
                        val jsDay = d.get(Calendar.DAY_OF_WEEK) - 1 // 日=0..六=6
                        val day = (jsDay + 6) % 7 // 周一起点偏移
                        set(y, m, d.get(Calendar.DAY_OF_MONTH) - day)
                    }
                }
            }.timeInMillis
            val e = bucket[kk]
            if (e != null) {
                bucket[kk] = e.copy(h = max(e.h, k.h), l = min(e.l, k.l), c = k.c, v = e.v + k.v)
            } else {
                bucket[kk] = KLine(kk, k.o, k.h, k.l, k.c, k.v, 0.0)
            }
        }
        return bucket.values.sortedBy { it.t }
    }

    private fun parseNasdaqDate(s: String): Long {
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val fmts = listOf(
            java.text.SimpleDateFormat("MM/dd/yyyy", Locale.US),
            java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US),
            java.text.SimpleDateFormat("MMM dd, yyyy", Locale.US)
        )
        for (f in fmts) {
            f.timeZone = utc
            f.isLenient = false
            try {
                return f.parse(s.trim())?.time ?: 0L
            } catch (_: Exception) {
            }
        }
        return 0L
    }

    fun fetchNasdaq(sym: String, tf: String, count: Int): List<VendorKs> {
        val n = max(5, min(200, count))
        val per = when (tf) { "M" -> 40; "Q" -> 120; else -> 9 }
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val from = Calendar.getInstance(utc).apply {
            add(Calendar.DAY_OF_YEAR, -(n * per))
        }
        val df = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = utc
        }
        val enc = URLEncoder.encode(sym.trim().uppercase(Locale.US), "UTF-8")
        val j = JSONObject(get(
            "https://api.nasdaq.com/api/quote/$enc/historical?assetclass=stocks&fromdate=${df.format(from.time)}&limit=9999",
            accept = "application/json", ua = "Mozilla/5.0"))
        val rows = j.optJSONObject("data")?.optJSONObject("tradesTable")?.optJSONArray("rows")
            ?: JSONArray()
        fun num(x: Any?): Double {
            val v = x?.toString()?.replace("$", "")?.replace(",", "")?.toDoubleOrNull()
            return v ?: 0.0
        }
        val daily = mutableListOf<KLine>()
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i) ?: continue
            val t = parseNasdaqDate(r.optString("date", ""))
            val h = num(r.opt("high"))
            if (t == 0L || !(h > 0)) continue
            daily.add(KLine(t, num(r.opt("open")), h, num(r.opt("low")),
                num(r.opt("close")), num(r.opt("volume")), 0.0))
        }
        daily.sortBy { it.t }
        val ks = resampleDaily(daily, tf)
        if (ks.size < 3) throw Exception("行情拉取失败(网络或品种名不对)")
        return listOf(VendorKs("NQ", ks.takeLast(n)))
    }

    fun aggregate(vs: List<VendorKs>): Agg {
        val names = mapOf("BN" to "Binance", "OK" to "OKX", "BB" to "Bybit",
            "YH" to "Yahoo", "NQ" to "Nasdaq")
        val win = vs.maxByOrNull { v -> v.k.sumOf { numD(it.qv) } }!!
        val disp = win.k.sortedBy { it.t }.takeLast(240)
        return Agg(disp, names[win.v] ?: win.v)
    }

    fun profileOf(ks: List<KLine>): Profile {
        val rows = 24
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        ks.forEach { c ->
            lo = min(lo, c.l)
            hi = max(hi, c.h)
        }
        val w = (hi - lo) / rows
        val ww = if (w == 0.0) 1.0 else w
        val rv = DoubleArray(rows); val ru = DoubleArray(rows); val rd = DoubleArray(rows)
        fun add(s0: Double, s1: Double, vol: Double, up: Boolean) {
            if (!(vol > 0) || !(s1 > s0)) return
            val a = max(0, floor((s0 - lo) / ww).toInt())
            val b = min(rows - 1, floor((s1 - lo) / ww).toInt())
            for (i in a..b) {
                val rl = lo + ww * i
                val rh = lo + ww * (i + 1)
                val ov = max(0.0, min(rh, s1) - max(rl, s0))
                if (ov > 0) {
                    val q = vol * ov / (s1 - s0)
                    rv[i] += q
                    if (up) ru[i] += q else rd[i] += q
                }
            }
        }
        ks.forEach { c ->
            val bTop = max(c.o, c.c)
            val bBot = min(c.o, c.c)
            val grn = c.c >= c.o
            val tw = c.h - bTop
            val bw = bBot - c.l
            val bd = bTop - bBot
            val den = 2 * tw + 2 * bw + bd
            if (!(den > 0)) return@forEach
            add(bBot, bTop, bd * c.v / den, grn)
            val twv = 2 * tw * c.v / den
            val bwv = 2 * bw * c.v / den
            add(bTop, c.h, twv / 2, true)
            add(bTop, c.h, twv / 2, false)
            add(c.l, bBot, bwv / 2, true)
            add(c.l, bBot, bwv / 2, false)
        }
        var poc = 0
        for (i in rv.indices) if (rv[i] > rv[poc]) poc = i
        var up = poc
        var dn = poc
        var sum = rv[poc]
        val t70 = rv.sum() * 0.7
        var g = 0
        while (sum < t70 && g++ < 200) {
            val vu = if (up + 1 < rows) rv[up + 1] else -1.0
            val vd = if (dn - 1 >= 0) rv[dn - 1] else -1.0
            if (vu < 0 && vd < 0) break
            if (vu >= vd) sum += rv[++up] else sum += rv[--dn]
        }
        return Profile(rv, ru, rd, up, dn, lo, hi,
            lo + ww * (poc + 0.5), lo + ww * (up + 1), lo + ww * dn)
    }

    // ---------- 格式化(照搬稿子) ----------

    fun mkFmt(v: Double?): String {
        if (v == null || v.isNaN()) return "--"
        val a = abs(v)
        return when {
            a >= 1000 -> "%,.2f".format(v)
            a >= 1 -> "%.2f".format(v)
            else -> "%.4f".format(v)
        }
    }

    fun mkVol(v: Double?): String {
        if (v == null || v.isNaN()) return "--"
        val a = abs(v)
        return when {
            a >= 1e9 -> "%.2fB".format(v / 1e9)
            a >= 1e6 -> "%.2fM".format(v / 1e6)
            a >= 1e3 -> "%.2fK".format(v / 1e3)
            else -> "%.2f".format(v)
        }
    }

    private fun cal(t: Long): Calendar =
        Calendar.getInstance().apply { timeInMillis = t }

    fun fmtDT(t: Long): String {
        val d = cal(t)
        return "${d.get(Calendar.YEAR)}-${d.get(Calendar.MONTH) + 1}-${d.get(Calendar.DAY_OF_MONTH)}"
    }

    fun fmtMD(t: Long): String {
        val d = cal(t)
        return "${d.get(Calendar.MONTH) + 1}-${d.get(Calendar.DAY_OF_MONTH)}"
    }
}
