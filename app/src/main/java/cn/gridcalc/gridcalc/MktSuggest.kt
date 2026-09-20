package cn.gridcalc.gridcalc

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

// ---------- 品种联想 · 数据层 ----------
// 对标稿子symList:内置表即时出结果,异步合并Yahoo搜索与OKX/Bybit现货表,
// 前缀优先稳定排序,最多6条,调用方凭seq丢弃过期结果。

data class SugItem(val s: String, val tag: String, val pre: Boolean)

object MktSuggest {

    private val TOPC = listOf("BTCUSD")
    private val TOPM = listOf("XAUUSD" to "黄金", "XAGUSD" to "白银")
    private val TOPS = listOf("AAPL", "TSLA", "NVDA", "MSFT", "AMZN", "META",
        "GOOGL", "AMD", "COIN", "MSTR", "NFLX", "BABA", "TSM", "PLTR")

    // 稿子CNNAME原样照搬(中文名→代码)
    private val CNNAME = listOf(
        "美光" to "MU", "苹果" to "AAPL", "特斯拉" to "TSLA", "英伟达" to "NVDA",
        "微软" to "MSFT", "亚马逊" to "AMZN", "谷歌" to "GOOGL", "超微" to "AMD",
        "台积电" to "TSM", "阿里" to "BABA", "奈飞" to "NFLX", "微策略" to "MSTR",
        "英特尔" to "INTC", "高通" to "QCOM", "博通" to "AVGO", "甲骨文" to "ORCL",
        "拼多多" to "PDD", "京东" to "JD", "理想" to "LI", "蔚来" to "NIO",
        "小鹏" to "XPEV", "摩根大通" to "JPM", "辉瑞" to "PFE", "可口可乐" to "KO",
        "麦当劳" to "MCD", "迪士尼" to "DIS", "贝宝" to "PYPL", "思科" to "CSCO",
        "比特币" to "BTCUSD", "黄金" to "XAUUSD", "白银" to "XAGUSD"
    )

    private val SPOT_QUOTES = setOf("USDT", "USD", "USDC")

    // 本地即时结果,顺序与稿子suggest()一致
    fun local(q: String): List<SugItem> {
        val items = mutableListOf<SugItem>()
        for ((k, v) in CNNAME) {
            if (k.contains(q)) items.add(SugItem(v, k, true))
        }
        for (f in TOPC) {
            if (f.startsWith(q)) items.add(SugItem(f, "币", true))
        }
        for ((s, tag) in TOPM) {
            if (s.startsWith(q) ||
                (q.length == 1 && "黄金白银".contains(q) && tag.contains(q))
            ) items.add(SugItem(s, tag, s.startsWith(q)))
        }
        for (s in TOPS) {
            if (s.startsWith(q)) items.add(SugItem(s, "美股", true))
        }
        for ((k, v) in CNNAME) {
            if (v.startsWith(q) && items.none { it.s == v }) {
                items.add(SugItem(v, k, true))
            }
        }
        return sortTop(items)
    }

    fun sortTop(items: List<SugItem>): List<SugItem> =
        items.sortedWith(compareByDescending<SugItem> { it.pre }).take(6)

    // ---------- 异步源(后台线程调用) ----------

    @Volatile private var okCache: List<String>? = null
    @Volatile private var bbCache: List<String>? = null

    private fun okSymbols(): List<String> {
        okCache?.let { return it }
        val out = mutableListOf<String>()
        try {
            val data = JSONObject(MktData.get(
                "https://www.okx.com/api/v5/public/instruments?instType=SPOT"))
                .optJSONArray("data") ?: JSONArray()
            for (i in 0 until data.length()) {
                val id = data.optJSONObject(i)?.optString("instId", "") ?: ""
                val mm = Regex("^([A-Z0-9]+)-(USDT|USD|USDC)$").matchEntire(id)
                if (mm != null) out.add(mm.groupValues[1] + mm.groupValues[2])
            }
        } catch (_: Exception) {
        }
        okCache = out
        return out
    }

    private fun bbSymbols(): List<String> {
        bbCache?.let { return it }
        val out = mutableListOf<String>()
        try {
            val list = JSONObject(MktData.get(
                "https://api.bybit.com/v5/market/instruments-info?category=spot&limit=1000"))
                .optJSONObject("result")?.optJSONArray("list") ?: JSONArray()
            for (i in 0 until list.length()) {
                val s = list.optJSONObject(i)?.optString("symbol", "") ?: ""
                if (s.isNotEmpty()) out.add(s.uppercase(Locale.US))
            }
        } catch (_: Exception) {
        }
        bbCache = out
        return out
    }

    private fun yahooSearch(q: String): List<SugItem> {
        val out = mutableListOf<SugItem>()
        try {
            val enc = URLEncoder.encode(q, "UTF-8")
            val j = JSONObject(MktData.get(
                "https://query2.finance.yahoo.com/v1/finance/search?q=$enc&quotesCount=6&newsCount=0"))
            val quotes = j.optJSONArray("quotes") ?: JSONArray()
            for (i in 0 until quotes.length()) {
                val x = quotes.optJSONObject(i) ?: continue
                val sym = x.optString("symbol", "")
                if (sym.isEmpty()) continue
                if (x.optString("quoteType", "") != "EQUITY") continue
                val sn = x.optString("shortname", "")
                out.add(SugItem(sym, if (sn.isEmpty()) "美股" else sn, sym.startsWith(q)))
            }
        } catch (_: Exception) {
        }
        return out
    }

    // BTC收敛:BTC打头只出BTCUSD,其余变体一律不收
    private fun converged(s: String): Boolean =
        !(s.startsWith("BTC") && s != "BTCUSD")

    // 异步合并:OKX/Bybit现货表按base前缀过滤+Yahoo搜索,去重后排序截6条
    fun remote(q: String): List<SugItem> {
        val out = mutableListOf<SugItem>()
        if (q.isEmpty()) return out
        try {
            for (s in okSymbols()) {
                if (s.startsWith(q) && converged(s) &&
                    out.none { it.s == s } && out.size < 20
                ) {
                    out.add(SugItem(s, "OKX", true))
                }
            }
        } catch (_: Exception) {
        }
        try {
            for (s in bbSymbols()) {
                if (s.startsWith(q) && converged(s) &&
                    out.none { it.s == s } && out.size < 20
                ) {
                    out.add(SugItem(s, "Bybit", true))
                }
            }
        } catch (_: Exception) {
        }
        for (x in yahooSearch(q)) {
            if (!converged(x.s)) continue
            if (out.none { it.s == x.s }) out.add(x)
        }
        return out
    }
}
