package cn.gridcalc.gridcalc

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.MathContext
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
    val l: Double, val c: Double, val v: Double, val qv: Double,
    // v4§7 美元口径:判重标记打在每根K线上(首画/终画共享同批对象,打数组上会二次折算、价格越刷越小)
    var usd: Boolean = false
)

data class VendorKs(val v: String, var k: List<KLine>)

data class Agg(val ks: List<KLine>, val src: String)

data class Profile(
    val rv: DoubleArray, val ru: DoubleArray, val rd: DoubleArray,
    val vaUp: Int, val vaDn: Int,
    val lo: Double, val hi: Double,
    val poc: Double, val vah: Double, val val_: Double,
    val sup: List<Double>
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
        // 稿序:*00Y 优先识别为商品(金银期货码 GC00Y/SI00Y 也归商品线)
        if (Regex("00Y$").containsMatchIn(u)) return "cmdty"
        if (u.contains("XAU") || u.contains("GOLD") || u.startsWith("GC=F")) return "gold"
        if (u.contains("XAG") || u.contains("SILVER") || u.startsWith("SI=F")) return "silver"
        // 稿§5:6位数字→韩股(东财177),5位(4位待补零)→港股(东财116)。
        // 顺序保持 商品→金银→6位韩→5位港→币对→美股;纯数字不会命中币对分支,放币对前安全
        if (Regex("^\\d{6}$").matches(u)) return "kr"
        if (Regex("^\\d{4,5}$").matches(u)) return "hk"
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

    internal fun get(
        url: String, accept: String? = null, ua: String = UA, timeoutMs: Int = 0,
        origin: String? = null
    ): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = if (timeoutMs > 0) timeoutMs else 12000
            readTimeout = if (timeoutMs > 0) timeoutMs else 15000
            setRequestProperty("User-Agent", ua)
            if (accept != null) setRequestProperty("Accept", accept)
            // v4§7:汇率源(frankfurter/er-api)必须显式带Origin,否则网关把CORS头回成null
            if (origin != null) setRequestProperty("Origin", origin)
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

    // ---------- 机构目标均价(稿TGT:Nasdaq官方分析师接口聚合数) ----------
    // 成功缓5min/失败缓30s;均价=priceTarget,家数=buy+hold+sell;空数据也按成功缓
    data class Tgt(val avg: Double, val n: Int)
    private val tgtLock = Any()
    private val tgtMap = mutableMapOf<String, Triple<Tgt?, Long, Boolean>>()
    fun fetchTgt(sym: String): Tgt? {
        val key = sym.trim().uppercase(Locale.US)
        if (key.isEmpty()) return null
        synchronized(tgtLock) {
            tgtMap[key]?.let { (r, t, ok) ->
                if (System.currentTimeMillis() - t < (if (ok) 300_000L else 30_000L)) return r
            }
        }
        var r: Tgt? = null
        var ok = false
        try {
            val s = get("https://api.nasdaq.com/api/analyst/$key/targetprice", null, UA, 10000)
            val j = JSONObject(s)
            ok = true
            val co = j.optJSONObject("data")?.optJSONObject("consensusOverview")
            val avg = co?.optDouble("priceTarget", 0.0) ?: 0.0
            if (co != null && avg > 0) {
                val n = co.optInt("buy", 0) + co.optInt("hold", 0) + co.optInt("sell", 0)
                if (n > 0) r = Tgt(avg, n)
            }
        } catch (_: Exception) {
            ok = false
        }
        synchronized(tgtLock) { tgtMap[key] = Triple(r, System.currentTimeMillis(), ok) }
        return r
    }

    // 币:base/quote拆分(照稿子,quote兜底USDT)
    fun splitBaseQuote(sym: String): Pair<String, String> {
        val clean = sym.uppercase(Locale.US).replace(Regex("[^A-Z]"), "")
        var base = clean
        var quote = "USDT"
        for (q in listOf("USDT", "USDC", "USD")) {
            if (clean.endsWith(q)) {
                base = clean.dropLast(q.length).ifEmpty { "BTC" }
                quote = q
                break
            }
        }
        if (base.isEmpty()) base = "BTC"
        return base to quote
    }

    // 币分支参战腿:BTC钉死币安单源,其余base走GT/OK/BB/KU/MX
    // (稿§6:Gate提到第2位——BN在前时顺序[BN,GT,OK,…];BN在USD报价时跳过,
    // 唯BTCUSD裸接口已验真货保留)+BTC限定GK
    const val PIN = "BN"
    fun cryptoLegs(sym: String): List<String> {
        val (base, quote) = splitBaseQuote(sym)
        if (base == "BTC") return listOf("BN")
        val legs = mutableListOf("GT", "OK", "BB", "KU", "MX")
        if (quote != "USD") legs.add(0, "BN")
        return legs
    }

    // 单腿拉取(含prep:Q走toQuarterly但GK除外,按t正序截最近n根,不足3根抛错)
    // iv下标:BN0/OK1/BB2/GT3/KU4/MX5,超时单腿10秒
    fun fetchCryptoLeg(v: String, sym: String, tf: String, count: Int, timeoutMs: Int = 10000): VendorKs {
        val (base, quote) = splitBaseQuote(sym)
        val n = max(5, min(100, count))
        val mq = if (tf == "Q") min(n * 3, 100) else n
        val iv = when (tf) {
            "W" -> arrayOf("1w", "1W", "W", "7d", "1week", "1W")
            else -> arrayOf("1M", "1M", "M", "30d", "1month", "1M")
        }
        val moz = "Mozilla/5.0"
        val ks: List<KLine> = when (v) {
            "BN" -> {
                if (quote == "USD" && base != "BTC") throw Exception("skip USD")
                val a = JSONArray(get(
                    "https://data-api.binance.vision/api/v3/klines?symbol=$base$quote&interval=${iv[0]}&limit=$mq",
                    timeoutMs = timeoutMs))
                (0 until a.length()).map { i ->
                    val k = a.getJSONArray(i)
                    KLine(arrL(k, 0), arrD(k, 1), arrD(k, 2), arrD(k, 3),
                        arrD(k, 4), arrD(k, 5), arrD(k, 7))
                }
            }
            "OK" -> {
                val data = JSONObject(get(
                    "https://www.okx.com/api/v5/market/candles?instId=$base-$quote&bar=${iv[1]}&limit=$mq",
                    timeoutMs = timeoutMs))
                    .optJSONArray("data") ?: JSONArray()
                (0 until data.length()).map { i ->
                    val k = data.getJSONArray(i)
                    KLine(arrL(k, 0), arrD(k, 1), arrD(k, 2), arrD(k, 3),
                        arrD(k, 4), arrD(k, 5), arrD(k, 7))
                }
            }
            "BB" -> {
                val list = JSONObject(get(
                    "https://api.bybit.com/v5/market/kline?category=spot&symbol=$base$quote&interval=${iv[2]}&limit=$mq",
                    timeoutMs = timeoutMs))
                    .optJSONObject("result")?.optJSONArray("list") ?: JSONArray()
                (0 until list.length()).map { i ->
                    val k = list.getJSONArray(i)
                    KLine(arrL(k, 0), arrD(k, 1), arrD(k, 2), arrD(k, 3),
                        arrD(k, 4), arrD(k, 5), arrD(k, 6))
                }
            }
            "GT" -> {
                // 行[t秒,qv,close,high,low,open,baseVol]
                val a = JSONArray(get(
                    "https://api.gateio.ws/api/v4/spot/candlesticks?currency_pair=${base}_${quote}&interval=${iv[3]}&limit=$mq",
                    ua = moz, timeoutMs = timeoutMs))
                (0 until a.length()).map { i ->
                    val k = a.getJSONArray(i)
                    KLine(arrL(k, 0) * 1000, arrD(k, 5), arrD(k, 3), arrD(k, 4),
                        arrD(k, 2), arrD(k, 6), arrD(k, 1))
                }
            }
            "KU" -> {
                // 行[t秒,o,c,h,l,vol,turnover],无limit参数(最多1500,靠slice截)
                val data = JSONObject(get(
                    "https://api.kucoin.com/api/v1/market/candles?symbol=$base-$quote&type=${iv[4]}",
                    ua = moz, timeoutMs = timeoutMs))
                    .optJSONArray("data") ?: JSONArray()
                (0 until data.length()).map { i ->
                    val k = data.getJSONArray(i)
                    KLine(arrL(k, 0) * 1000, arrD(k, 1), arrD(k, 3), arrD(k, 4),
                        arrD(k, 2), arrD(k, 5), arrD(k, 6))
                }
            }
            "MX" -> {
                // Binance同格式,t为毫秒,interval大写
                val a = JSONArray(get(
                    "https://api.mexc.com/api/v3/klines?symbol=$base$quote&interval=${iv[5]}&limit=$mq",
                    ua = moz, timeoutMs = timeoutMs))
                (0 until a.length()).map { i ->
                    val k = a.getJSONArray(i)
                    KLine(arrL(k, 0), arrD(k, 1), arrD(k, 2), arrD(k, 3),
                        arrD(k, 4), arrD(k, 5), arrD(k, 7))
                }
            }
            "GK" -> return fetchGecko(base, tf, n, timeoutMs).first()
            else -> throw Exception("unknown leg $v")
        }
        if (ks.size < 3) throw Exception("行情拉取失败(网络或品种名不对)")
        val qk = if (tf == "Q" && v != "GK") toQuarterly(ks) else ks
        val disp = qk.sortedBy { it.t }.takeLast(n)
        if (disp.size < 3) throw Exception("行情拉取失败(网络或品种名不对)")
        return VendorKs(v, disp)
    }

    // CoinGecko(BTC限定):ohlc取[t,o,h,l,c]+market_chart按UTC日期对齐量,
    // 日线经resample+取最近n,qv直接取和
    fun fetchGecko(base: String, tf: String, count: Int, timeoutMs: Int = 10000): List<VendorKs> {
        if (base != "BTC") throw Exception("gecko BTC限定")
        val n = max(5, min(100, count))
        val per = when (tf) { "M" -> 31; "Q" -> 92; else -> 7 }
        val days = min(365, Math.ceil(n * per * 1.3).toInt())
        val moz = "Mozilla/5.0"
        val o = JSONArray(get(
            "https://api.coingecko.com/api/v3/coins/bitcoin/ohlc?vs_currency=usd&days=$days",
            ua = moz, timeoutMs = timeoutMs))
        val m = JSONObject(get(
            "https://api.coingecko.com/api/v3/coins/bitcoin/market_chart?vs_currency=usd&days=$days&interval=daily",
            ua = moz, timeoutMs = timeoutMs))
        if (o.length() == 0) throw Exception("行情拉取失败(网络或品种名不对)")
        val vols = m.optJSONArray("total_volumes") ?: JSONArray()
        val vmap = HashMap<String, Double>()
        for (i in 0 until vols.length()) {
            val e = vols.optJSONArray(i) ?: continue
            vmap[utcDate(e.optLong(0, 0))] = e.optDouble(1, 0.0)
        }
        val ks = mutableListOf<KLine>()
        for (i in 0 until o.length()) {
            val k = o.optJSONArray(i) ?: continue
            val t = k.optLong(0, 0)
            val h = k.optDouble(2, 0.0)
            if (!(h > 0)) continue
            ks.add(KLine(t, k.optDouble(1, 0.0), h, k.optDouble(3, 0.0),
                k.optDouble(4, 0.0), vmap[utcDate(t)] ?: 0.0, 0.0))
        }
        val rs = resampleDaily(ks, tf).takeLast(n)
        if (rs.size < 3) throw Exception("行情拉取失败(网络或品种名不对)")
        return listOf(VendorKs("GK", rs.map { it.copy(qv = it.v) }))
    }

    private fun utcDate(t: Long): String {
        val d = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            .apply { timeInMillis = t }
        return "%04d-%02d-%02d".format(d.get(Calendar.YEAR),
            d.get(Calendar.MONTH) + 1, d.get(Calendar.DAY_OF_MONTH))
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

    // Yahoo日级(query1 YI映射,range按根数倒推,取最近n根,失败抛错,单路timeoutMs超时)
    fun fetchYahooDaily(sym: String, tf: String, count: Int, timeoutMs: Int = 10000): List<VendorKs> {
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
        // Yahoo chart接口认浏览器UA,自带GridCalc UA会被401,此处用Mozilla/5.0
        val j = JSONObject(get(
            "https://query1.finance.yahoo.com/v8/finance/chart/$enc?interval=$iv&range=$rg",
            ua = "Mozilla/5.0", timeoutMs = timeoutMs))
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

    fun fetchNasdaq(sym: String, tf: String, count: Int, timeoutMs: Int = 10000): List<VendorKs> {
        val n = max(5, min(200, count))
        val per = when (tf) { "M" -> 40; "Q" -> 120; else -> 9 }
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val from = Calendar.getInstance(utc).apply {
            add(Calendar.DAY_OF_YEAR, -(n * per))
        }
        val df = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = utc
        }
        val fromdate = df.format(from.time)
        // assetclass=stocks 只收正股:SPY/QQQ 等 Arca ETF 回 code1001 Symbol not exists。
        // 先按 stocks 取,回包没有可用行再按 etf 取同一接口——类别由服务端回包判定,
        // 不按代码名单写死;正股首腿即中,行为与耗时不变。详情页与自选距离共用本函数。
        var daily = nasdaqDaily(sym, fromdate, "stocks", timeoutMs)
        if (daily.isEmpty()) daily = nasdaqDaily(sym, fromdate, "etf", timeoutMs)
        daily.sortBy { it.t }
        val ks = resampleDaily(daily, tf)
        if (ks.size < 3) throw Exception("行情拉取失败(网络或品种名不对)")
        return listOf(VendorKs("NQ", ks.takeLast(n)))
    }

    // nasdaq historical 单一 assetclass 取回日线行;网络/格式失败返回空列表,
    // 由 fetchNasdaq 决定是否换 etf 类重试(两轮都空才在调用方处按原口径报错)
    private fun nasdaqDaily(
        sym: String, fromdate: String, assetClass: String, timeoutMs: Int
    ): MutableList<KLine> {
        val out = mutableListOf<KLine>()
        try {
            val enc = URLEncoder.encode(sym.trim().uppercase(Locale.US), "UTF-8")
            val j = JSONObject(get(
                "https://api.nasdaq.com/api/quote/$enc/historical?assetclass=$assetClass&fromdate=$fromdate&limit=9999",
                accept = "application/json", ua = "Mozilla/5.0", timeoutMs = timeoutMs))
            val rows = j.optJSONObject("data")?.optJSONObject("tradesTable")?.optJSONArray("rows")
                ?: JSONArray()
            fun num(x: Any?): Double {
                val v = x?.toString()?.replace("$", "")?.replace(",", "")?.toDoubleOrNull()
                return v ?: 0.0
            }
            for (i in 0 until rows.length()) {
                val r = rows.optJSONObject(i) ?: continue
                val t = parseNasdaqDate(r.optString("date", ""))
                val h = num(r.opt("high"))
                if (t == 0L || !(h > 0)) continue
                out.add(KLine(t, num(r.opt("open")), h, num(r.opt("low")),
                    num(r.opt("close")), num(r.opt("volume")), 0.0))
            }
        } catch (_: Exception) {
        }
        return out
    }

    // 稿§6 腾讯腿(东财/Nasdaq不可达的兜底):美股 us+代码+后缀依次 .OQ/.N/.AR/.P,
    // 港股 hk+5位补零;字段顺序 [日期,开,收,高,低,量] 与东财(开,高,低,收)不同——
    // 收在第3位别搞反;取回日线后按 tf 重采样;少于3根视为无效换下一个key;
    // 来源标签「腾讯」(vendorName TX)
    // D1:美股 key 必须带 `us` 市场前缀(稿L857 usAAPL.OQ),漏了 `us` 整条腿对美股100%失效
    fun fetchTencent(sym: String, tf: String, count: Int, timeoutMs: Int = 9000): List<VendorKs> {
        val u = sym.trim().uppercase(Locale.US)
        val keys = when (symType(u)) {
            "hk" -> listOf("hk" + u.padStart(5, '0'))
            "kr" -> emptyList() // 稿腾讯腿覆盖美股/港股;韩股走东财177与Yahoo .KS
            else -> listOf("us$u.OQ", "us$u.N", "us$u.AR", "us$u.P")
        }
        val n = max(5, min(200, count))
        for (key in keys) {
            try {
                val s = get(
                    "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=$key,day,,,320,qfq",
                    accept = "application/json", timeoutMs = timeoutMs)
                val d = JSONObject(s).optJSONObject("data")?.optJSONObject(key) ?: continue
                val rows = d.optJSONArray("qfqday") ?: d.optJSONArray("day") ?: continue
                val out = mutableListOf<KLine>()
                for (i in 0 until rows.length()) {
                    val a = rows.optJSONArray(i) ?: continue
                    if (a.length() < 6) continue
                    val t = parseNasdaqDate(a.optString(0).trim().take(10))
                    val o = a.optDouble(1, 0.0)
                    val c = a.optDouble(2, 0.0) // 收=第3位(腾讯序)
                    val h = a.optDouble(3, 0.0)
                    val l = a.optDouble(4, 0.0)
                    val v = a.optDouble(5, 0.0)
                    if (t == 0L || !(h > 0)) continue
                    out.add(KLine(t, o, h, l, c, v, 0.0))
                }
                if (out.size < 3) continue
                out.sortBy { it.t }
                val ks = resampleDaily(out, tf).takeLast(n)
                if (ks.size < 3) continue
                return listOf(VendorKs("TX", ks))
            } catch (_: Exception) {
            }
        }
        return emptyList()
    }

    // Yahoo 符号按市场加后缀(港 .HK / 韩 .KS,美股原样)
    private fun yahooSym(sym: String): String {
        val u = sym.trim().uppercase(Locale.US)
        return when (symType(u)) {
            "hk" -> "$u.HK"
            "kr" -> "$u.KS"
            else -> u
        }
    }

    // 稿§6 股票串行腿(美/港/韩共用):东财(6s)→Nasdaq(9s)→腾讯(9s)→Yahoo(9s),
    // 前一腿失败(抛错或空)才发下一腿,命中即停,常态只发1个请求
    fun fetchStocks(sym: String, tf: String, count: Int): List<VendorKs> {
        val legs: List<() -> List<VendorKs>> = listOf(
            { fetchEastmoney(sym, tf, count, 6000) },
            { fetchNasdaq(sym, tf, count, 9000) },
            { fetchTencent(sym, tf, count, 9000) },
            { fetchYahooDaily(yahooSym(sym), tf, count, 9000) }
        )
        for (leg in legs) {
            try {
                val vs = leg()
                if (vs.isNotEmpty()) return vs
            } catch (_: Exception) {
            }
        }
        throw Exception("行情拉取失败(网络或品种名不对)")
    }

    // 东货行情 secid 映射(照稿 EMID):金银 101.GC00Y/101.SI00Y + 17 种大宗商品 *00Y,
    // 未命中映射按 105.<代码> 走美股。代码原样大写(含数字/=,不再剥字符)。
    private val EMID = mapOf(
        "GC=F" to "101.GC00Y", "SI=F" to "101.SI00Y", "CL00Y" to "102.CL00Y",
        "B00Y" to "112.B00Y", "NG00Y" to "102.NG00Y", "HO00Y" to "102.HO00Y",
        "RB00Y" to "102.RB00Y", "HG00Y" to "101.HG00Y", "PL00Y" to "102.PL00Y",
        "PA00Y" to "102.PA00Y", "ZC00Y" to "103.ZC00Y", "ZW00Y" to "103.ZW00Y",
        "ZS00Y" to "103.ZS00Y", "ZM00Y" to "103.ZM00Y", "ZL00Y" to "103.ZL00Y",
        "ZO00Y" to "103.ZO00Y", "ZR00Y" to "103.ZR00Y", "CT00Y" to "108.CT00Y",
        "SB00Y" to "108.SB00Y"
    )

    // 稿emSecid(730-740):美股按交易所取 secid:105=纳斯达克 106=纽交所 107=美股Arca;
    // 不写死105(纽交所ORCL/IBM/VISA等105查不到→整路无源),轻量报价接口探一次、命中即缓存;
    // 全不中回落旧值105.<code>让调用方照常报无源。全树唯一 secid 拼接点收口于此。
    private val EMMKT = java.util.concurrent.ConcurrentHashMap<String, String>()

    internal fun emSecid(code: String): String {
        EMID[code]?.let { return it }
        EMMKT[code]?.let { return it }
        // 稿§5:4/5位→只试116(港股),6位→只试177(韩股),不进105/106/107美股探测;
        // 4位先补零到5位;探测失败也只用116/177(绝不回落105.),调用方照常报无源
        val shaped = if (Regex("^\\d{1,5}$").matches(code)) code.padStart(5, '0') else code
        val fixed = when {
            Regex("^\\d{5}$").matches(shaped) -> "116"
            Regex("^\\d{6}$").matches(shaped) -> "177"
            else -> null
        }
        if (fixed != null) {
            val sid = "$fixed.$shaped"
            try {
                val j = JSONObject(get(
                    "https://push2.eastmoney.com/api/qt/stock/get?secid=$sid&fields=f57" +
                        "&ut=fa5fd1943c7b386f172d6893dbfba10b",
                    timeoutMs = 6000))
                val d = j.optJSONObject("data")
                if (d != null && d.optString("f57").isNotEmpty()) {
                    EMMKT[code] = sid
                    return sid
                }
            } catch (_: Exception) {
            }
            return sid
        }
        for (m in listOf("105", "106", "107")) {
            val sid = "$m.$code"
            try {
                val j = JSONObject(get(
                    "https://push2.eastmoney.com/api/qt/stock/get?secid=$sid&fields=f57" +
                        "&ut=fa5fd1943c7b386f172d6893dbfba10b",
                    timeoutMs = 6000))
                val d = j.optJSONObject("data")
                if (d != null && d.optString("f57").isNotEmpty()) {
                    EMMKT[code] = sid
                    return sid
                }
            } catch (e: Exception) {
            }
        }
        return "105.$code"
    }

    // 东方财富push2his(secid按EMID:商品/贵金属期货,未命中走emSecid美股探测):行=date,
    // open,close,high,low,volume,amount(oc在hl前);Q取3倍月数供toQuarterly合成
    // (照搬,mq上限300);v取份额成交量f56(与NQ/YH同股数口径直接比),qv取金额f57
    fun fetchEastmoney(sym: String, tf: String, count: Int, timeoutMs: Int = 10000): List<VendorKs> {
        val code = sym.trim().uppercase(Locale.US)
        val secid = emSecid(code) // ⑤收口:EMID优先→105/106/107探测并缓存(稿emSecid)
        val n = max(5, min(200, count))
        val mq = if (tf == "Q") min(n * 3, 300) else n
        val klt = if (tf == "W") "102" else "103"
        val j = JSONObject(get(
            "https://push2his.eastmoney.com/api/qt/stock/kline/get?secid=$secid&klt=$klt&fqt=1&lmt=$mq&end=20500000" +
                "&fields1=f1,f2,f3,f4,f5,f6,f7,f8&fields2=f51,f52,f53,f54,f55,f56,f57,f58" +
                "&ut=f057cbcbce2a86e2866ab8877db1d059&forcect=1",
            timeoutMs = timeoutMs))
        val klines = j.optJSONObject("data")?.optJSONArray("klines") ?: JSONArray()
        val out = mutableListOf<KLine>()
        for (i in 0 until klines.length()) {
            val p = klines.optString(i, "").split(",")
            if (p.size < 7) continue
            val t = parseNasdaqDate(p[0].trim().take(10))
            val o = p[1].toDoubleOrNull() ?: 0.0
            val c = p[2].toDoubleOrNull() ?: 0.0
            val h = p[3].toDoubleOrNull() ?: 0.0
            val l = p[4].toDoubleOrNull() ?: 0.0
            val v = p[5].toDoubleOrNull() ?: 0.0
            val amt = p[6].toDoubleOrNull() ?: 0.0
            if (t == 0L || !(h > 0)) continue
            out.add(KLine(t, o, h, l, c, v, amt))
        }
        out.sortBy { it.t }
        if (out.size < 3) throw Exception("行情拉取失败(网络或品种名不对)")
        val ks = if (tf == "Q") toQuarterly(out) else out
        return listOf(VendorKs("ED", ks.takeLast(n)))
    }

    // 贵金属/大宗商品单源:只走东财期货(稿 fetchMetals 线路 101.GC00Y/101.SI00Y
    // 与 17 种商品 *00Y),Yahoo 贵金属线路已按稿删除;Q 合成/截根由 fetchEastmoney 承担
    fun fetchMetals(sym: String, tf: String, count: Int, timeoutMs: Int = 10000): List<VendorKs> =
        fetchEastmoney(sym, tf, count, timeoutMs)

    fun vendorName(v: String): String = when (v) {
        "BN" -> "Binance"
        "OK" -> "OKX"
        "BB" -> "Bybit"
        "GT" -> "Gate.io"
        "KU" -> "KuCoin"
        "MX" -> "MEXC"
        "GK" -> "CoinGecko"
        "YH" -> "Yahoo"
        "NQ" -> "Nasdaq"
        "ED" -> "东方财富"
        "TX" -> "腾讯"
        else -> v
    }

    // 股票多源按份额成交量选优(股数口径,可直接比Σv)
    fun pickStockWinner(vs: List<VendorKs>): VendorKs =
        vs.maxByOrNull { v -> v.k.sumOf { it.v } }!!

    // 行情多源内存缓存5分钟,key=品种|周期|根数
    object MktCache {
        private data class E(val vs: List<VendorKs>, val at: Long)
        private const val TTL = 5 * 60 * 1000L
        private val map = LinkedHashMap<String, E>()

        // v4§7:缓存键必须带汇率——汇率变了键就变,强制重新抓,不沿用旧美元价
        fun key(sym: String, tf: String, n: Int): String =
            sym.trim().uppercase(Locale.US) + "|" + tf + "|" + n + "@" + FX.keyRate(sym)

        @Synchronized
        fun get(k: String): List<VendorKs>? {
            val e = map[k] ?: return null
            if (System.currentTimeMillis() - e.at > TTL) {
                map.remove(k)
                return null
            }
            return e.vs
        }

        @Synchronized
        fun put(k: String, vs: List<VendorKs>) {
            map[k] = E(vs, System.currentTimeMillis())
            while (map.size > 20) {
                map.remove(map.keys.first())
            }
        }
    }

    // ---------- v4§8 1%价格精度:d = max(0, 2 - floor(log10 v)) ----------
    // 个位价保留必要小数(6.77→6.77),三位数以上自动取整;6个价格出口统一走这里
    fun quantD(v: Double): Int {
        if (!(v > 0) || !v.isFinite()) return 2
        return Math.max(0, 2 - Math.floor(Math.log10(v)).toInt())
    }

    fun quantPx(v: Double): String =
        String.format(Locale.US, "%.${quantD(v)}f", v)

    fun quantCeil(v: Double): String {
        val d = quantD(v)
        if (d == 0) return Math.ceil(v).toLong().toString()
        val f = Math.pow(10.0, d.toDouble())
        return String.format(Locale.US, "%.${d}f", Math.ceil(v * f) / f)
    }

    // 明细行价格显示:千分位 + quant精度
    fun fmtQ(v: Double): String =
        String.format(Locale.US, "%,.${quantD(v)}f", v)

    fun fmtCeilQ(v: Double): String {
        val d = quantD(v)
        val x = if (d == 0) Math.ceil(v)
        else { val f = Math.pow(10.0, d.toDouble()); Math.ceil(v * f) / f }
        return String.format(Locale.US, "%,.${d}f", x)
    }

    // ---------- v4§7 美元口径:汇率层(1 USD = X 外币,原生价→美元 = ÷X) ----------
    object FX {
        // 必须显式带Origin(部分网关据此放行CORS头)
        const val ORIGIN = "https://gridcalc.app"
        const val TTL = 6 * 60 * 60 * 1000L // 6小时缓存
        private const val PF = "gridcalc_fx_v1"
        private const val PKEY = "gc_fx"

        @Volatile var rates = HashMap<String, Double>()
        @Volatile var dateStr = ""
        @Volatile var ts = 0L
        @Volatile var loaded = false
        @Volatile var inflight = false
        // 汇率异步到达回调(主线程重画说明行+图表);由MainActivity接线
        var onReady: (() -> Unit)? = null

        // 币种按代码形状判断(4/5位数字=港股HKD,6位=韩股KRW,其余美元;A股CNY路由未做,币种判断保留)
        fun curOf(sym: String): String {
            val u = sym.trim().uppercase(Locale.US)
            if (Regex("^\\d{4}$").matches(u) || Regex("^\\d{5}$").matches(u)) return "HKD"
            if (Regex("^\\d{6}$").matches(u)) return "KRW"
            return "USD"
        }

        fun rateOf(cur: String): Double? = if (cur == "USD") 1.0 else rates[cur]

        // 缓存键尾缀:美元=1,无汇率=0,有汇率用去尾零十进制串(7.844/1355.05)
        fun keyRate(sym: String): String {
            val r = rateOf(curOf(sym)) ?: 0.0
            if (!(r > 0)) return "0"
            return java.math.BigDecimal(r).stripTrailingZeros().toPlainString()
        }

        private fun fmtRate(r: Double): String =
            String.format(Locale.US, "%,.4f", r).trimEnd('0').trimEnd('.')

        // 行情页说明行三态:美元报价/有汇率(带日期)/取不到保持原币
        fun fxText(sym: String): String {
            val cur = curOf(sym)
            if (cur == "USD") return "美元口径(该品种以美元报价)"
            val r = rateOf(cur)
            if (r == null || !(r > 0)) return "美元口径:暂时没取到 $cur 汇率,价格仍按原币显示"
            return "美元口径:1 USD = ${fmtRate(r)} $cur(汇率 $dateStr)"
        }

        private fun pref(c: android.content.Context): android.content.SharedPreferences? =
            try {
                c.getSharedPreferences(PF, android.content.Context.MODE_PRIVATE)
            } catch (_: Exception) {
                null // 写失败降级为内存态,不崩
            }

        // 只在App打开/回前台时调一次:缓存新鲜则不动网,过期/缺失则发起一次请求(无轮询无定时器)
        fun ensure(c: android.content.Context) {
            try {
                if (!loaded) {
                    val s = pref(c)?.getString(PKEY, null)
                    if (!s.isNullOrEmpty()) {
                        val j = JSONObject(s)
                        val m = HashMap<String, Double>()
                        for (k in listOf("HKD", "KRW", "CNY")) {
                            val v = j.optDouble(k, 0.0)
                            if (v > 0) m[k] = v
                        }
                        if (m.isNotEmpty()) {
                            rates = m
                            dateStr = j.optString("date", "")
                            ts = j.optLong("ts", 0L)
                            loaded = true
                        }
                    }
                }
            } catch (_: Exception) {
            }
            if (loaded && System.currentTimeMillis() - ts < TTL) return
            if (inflight) return
            inflight = true
            Thread {
                try {
                    val (m, d) = fetch()
                    if (m.isNotEmpty()) {
                        rates = HashMap(m)
                        if (d.isNotEmpty()) dateStr = d
                        ts = System.currentTimeMillis()
                        loaded = true
                        try {
                            val o = JSONObject()
                            for ((k, v) in m) o.put(k, v)
                            o.put("date", dateStr)
                            o.put("ts", ts)
                            pref(c)?.edit()?.putString(PKEY, o.toString())?.apply()
                        } catch (_: Exception) {
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    inflight = false
                    // 无论成败都回调:成功→按新汇率重画;失败→说明行切到「暂时没取到/沿用旧缓存」
                    onReady?.invoke()
                }
            }.start()
        }

        private fun parse(s: String, date: String): Pair<Map<String, Double>, String> {
            val j = JSONObject(s)
            val rr = j.optJSONObject("rates") ?: return Pair(emptyMap(), "")
            val m = mutableMapOf<String, Double>()
            for (k in listOf("HKD", "KRW", "CNY")) {
                val v = rr.optDouble(k, 0.0)
                if (v > 0) m[k] = v
            }
            val dt = date.ifEmpty { j.optString("date", "") }
            return if (m.isNotEmpty()) Pair(m, dt) else Pair(emptyMap(), "")
        }

        private fun fetch(): Pair<Map<String, Double>, String> {
            // 主源:frankfurter(ECB数据)
            try {
                val r = parse(
                    get("https://api.frankfurter.app/latest?from=USD&to=HKD,KRW,CNY",
                        "application/json", UA, 6000, ORIGIN), "")
                if (r.first.isNotEmpty()) return r
            } catch (_: Exception) {
            }
            // 备源:open.er-api
            try {
                val s = get("https://open.er-api.com/v6/latest/USD",
                    "application/json", UA, 6000, ORIGIN)
                val j = JSONObject(s)
                val d = try {
                    val raw = j.optString("time_last_update_utc", "")
                    java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(
                        java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
                            .parse(raw) ?: java.util.Date())
                } catch (_: Exception) {
                    ""
                }
                val r = parse(s, d)
                if (r.first.isNotEmpty()) return r
            } catch (_: Exception) {
            }
            return Pair(emptyMap(), "")
        }

        // K线入表时(aggregate之前)折算o/h/l/c。判重在每根K线上:首画/终画/缓存共享同批对象
        // 只折一次;留底(内存缓存)存已折算美元价,读取不再折;汇率变→键变→重新抓新对象再折。
        fun fxify(vs: List<VendorKs>, sym: String) {
            val cur = curOf(sym)
            if (cur == "USD") return
            val r = rateOf(cur) ?: return
            if (!(r > 0)) return
            for (vk in vs) {
                if (vk.k.all { it.usd }) continue
                vk.k = vk.k.map { k ->
                    if (k.usd) k
                    else k.copy(
                        o = k.o / r, h = k.h / r, l = k.l / r, c = k.c / r, usd = true)
                }
            }
        }
    }

    // 终画选优:PIN源(BN)有≥3根直接钉死,否则按报价成交额qv选优(照稿子)
    fun aggregate(vs: List<VendorKs>): Agg {
        val win = vs.find { it.v == PIN && it.k.size >= 3 }
            ?: vs.maxByOrNull { v -> v.k.sumOf { numD(it.qv) } }!!
        val disp = win.k.sortedBy { it.t }.takeLast(240)
        return Agg(disp, vendorName(win.v))
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
        // 支撑位(照稿子v3.3):首沿从高价行往低价行扫,行量相对运行最高腰斩即第一支撑;
        // 后继沿:抛弃旧峰,下方须先出现爬升再取新峰,峰下腰斩为下一支撑;
        // 守卫防越界,行0可入选。返回行中点价数组(现价过滤由调用方做)。
        val supRows = mutableListOf<Int>()
        var smax = -1.0
        var b = -1
        for (i in rows - 1 downTo 0) {
            if (rv[i] > smax) smax = rv[i]
            else if (smax > 0 && rv[i] <= smax * 0.5) {
                b = i
                break
            }
        }
        var gi = 0
        while (b >= 0 && gi++ < rows) {
            supRows.add(b)
            var m2 = -1.0
            var m2row = -1
            var climbed = false
            var prev = rv[b]
            for (i in b - 1 downTo 0) {
                if (rv[i] > prev) climbed = true
                prev = rv[i]
                if (climbed && rv[i] > m2) {
                    m2 = rv[i]
                    m2row = i
                } else if (climbed && m2row >= 0 && rv[i] < m2 &&
                    (i == 0 || rv[i - 1] <= rv[i])
                ) {
                    // 稿740死角修复:climb后第一个显著局部峰收口(MU月线30:732.49/334.51)
                    break
                }
            }
            if (m2row < 0) break
            b = -1
            for (i in m2row - 1 downTo 0) {
                if (rv[i] <= m2 * 0.5) {
                    b = i
                    break
                }
            }
        }
        return Profile(rv, ru, rd, up, dn, lo, hi,
            lo + ww * (poc + 0.5), lo + ww * (up + 1), lo + ww * dn,
            supRows.map { lo + ww * (it + 0.5) })
    }

    // ---------- 联动写值格式(稿 JS x.toPrecision(8) 等价) ----------
    // 8位有效数字、去尾随零、不用科学计数;与稿 linkCalc/linkPhigh 写入计算页的值同格式
    fun jsPrec(x: Double): String =
        BigDecimal.valueOf(x).round(MathContext(8)).stripTrailingZeros().toPlainString()

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
