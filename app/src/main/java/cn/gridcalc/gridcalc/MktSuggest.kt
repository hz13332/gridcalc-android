package cn.gridcalc.gridcalc

import org.json.JSONObject
import java.util.Locale

// ---------- 品种联想 · 数据层 ----------
//
// ⚠ 2026-11-03 用户裁定「行情数据源收敛为一家，其他源全部去掉，不留遗留」
//   +「中文名的问题，你提前汉化，存在程序里面就可以了」。
//
// 【现在只剩这套链路】一次搜索**远端请求 ≤2**（实际 ≤1），且**美股与加密二选一、绝不并联**：
//   ① 本地（零网络）：[UsNames] 读 res/raw 里的**两份离线资产** ——
//      · `us_dir.tsv`：美股普通股目录（代码 ↔ 中文名/英文名，Nasdaq screener 单源）；
//      · `names_extra.tsv`：非美股中文名（加密/大宗/金银/韩股）—— 只提供显示名与按名检索，
//        **不是目录、不提供代码来源**（那些品种的代码仍由用户输入/官方目录给）。
//   ② 远端，按输入形态二选一：
//      · 美股 —— Nasdaq 官方 `autocomplete/slookup`（按代码/英文名；与 K 线同域名同网络层）
//      · 加密 —— 币安 `exchangeInfo`（全币对目录；与 K 线同域名 data-api.binance.vision）
//   ⚠ **没有兜底链**：本地没命中且远端没命中就是「无匹配」，不再有第二条源来"再试一次"。
//     （用户对"多源"的立场已多次明确：宁可如实说没有，也不换源拼数。）
//   ⚠ 非美股中文名表**不增加任何远端请求**：它只参与本地命中与显示，远端那一跳没变。
//   ⚠ **远端召回一律先过目录闸**（[usDirHas]）：Nasdaq slookup 会返回 ETF（SPY/TLT/QQQ…）
//     与优先股（JPM.PRC），只有能在 `us_dir.tsv` 里精确命中的代码才并入候选 ——
//     目录是唯一权威，不另立名单、不按 asset 字段猜、不给 ETF 开例外。
//
// 【现状】远端只有两家：美股 Nasdaq 官方目录、加密币安目录（二选一，不并联）。
//   中文名统一来自**离线资产**（美股 us_dir.tsv、非美股 names_extra.tsv），
//   代码目录来自上面两个官方目录；本地不维护任何手写代码表/别名表——
//   名单会烂、会缺、会和目录漂移（用户明确否掉「白名单那一套」）。

data class SugItem(val s: String, val tag: String, val pre: Boolean)

/** Nasdaq slookup 的一条证券记录（只有代码与名字；交易所不参与取数）。 */
internal data class NasdaqSymbol(val symbol: String, val name: String)

object MktSuggest {

    // ---------- ① 本地即时（零网络） ----------

    /** 联想框的即时结果：只查内置离线名表（美股目录 + 非美股名表），命不中就等远端那一跳。 */
    fun local(q: String): List<SugItem> = sortTop(favLocal(q))

    /** 自选搜索框的即时结果（同源、不截 6 条；最终排序与条数由 [rankFav] 管）。 */
    fun favLocal(q: String): List<SugItem> {
        val query = queryOf(q)
        if (query.isEmpty()) return emptyList()
        return UsNames.search(query, 12)
    }

    fun sortTop(items: List<SugItem>): List<SugItem> =
        items.sortedWith(compareByDescending<SugItem> { it.pre }).take(6)

    /**
     * 品种显示名 —— **只认内置离线名表**，不联网、不硬译。
     *
     * 回落顺序（2026-11-03 t8 起）：**美股目录中文名 → 非美股名表中文名 → 空串**。
     *   · 美股（us_dir.tsv）覆盖代码/中文名/英文名；
     *   · 非美股（names_extra.tsv）覆盖加密/大宗/金银/韩股的中文名 —— 这些品种不在美股目录里，
     *     没有它就只能显示代码（比特币→BTCUSDT、原油→CL00Y、三星电子→005930 都退化成代码）。
     *   ⚠ 两张表都没有就返回空串，调用方一律回落**显示代码本身**
     *     （2026-10-13 用户对 ARM / IBM 的裁定：「代码本身就是名字」）。
     *   ⚠ 这里**刻意不用英文名兜底**：英文法定名顶掉代码是用户当天明确否掉的
     *     （PLTR / COIN 的标题曾被 "Palantir Technologies Inc" 之类顶掉）。
     *     英文名只用在**搜索结果**里（用户正在搜，给个英文名比给空更有用，见 [nasdaqSearch]）。
     */
    fun favName(s: String): String = UsNames.zhOf(MktData.canonicalSym(s))

    private fun queryOf(q: String): String = q.trim().uppercase(Locale.US)

    // ---------- ② 远端（美股 / 加密二选一，一次搜索只打一家） ----------

    /**
     * 远端补充（**后台线程**调用）：按输入形态二选一 —— 币对走币安目录，其余走 Nasdaq。
     *
     * ⚠ 三条短路，都是"别白打接口"（用户硬约束：「抓取行情信息不要频繁打，不要被限流」）：
     *   · 已下线市场（港股 00700 / A 股 sh688825）：直接空，不去问 Nasdaq 一个恒空的串；
     *   · 纯中文输入：中文名是**本地目录**的职责，英文接口对它必然零命中；
     *   · 空输入。
     */
    fun remote(q: String): List<SugItem> {
        val query = queryOf(q)
        return when (remoteTarget(query)) {
            RemoteTarget.NONE -> emptyList()
            RemoteTarget.BN -> cryptoSearch(query)
            RemoteTarget.NASDAQ -> nasdaqSearch(query)
        }
    }

    /** 远端查询目标：**纯函数**（无网络、无状态），单测可直接断言。 */
    internal enum class RemoteTarget { NONE, NASDAQ, BN }

    /**
     * 这次输入需不需要打远端、打哪家。
     *
     * ⚠ 2026-11-03 t16（t4 的 R5）：改前是「非加密即 Nasdaq」——6 位韩股代码、XAUUSD、
     *   商品 `*00Y` 都会去打一次 Nasdaq slookup，而 Nasdaq 对这些形态**结构上恒空**
     *   （`NASDAQ_SYMBOL` 也挡掉了 `*00Y`），纯属白打（用户硬约束：不要频繁打接口）。
     *   ⟹ 现在：
     *     · 加密 → 币安目录（唯一加密源）；
     *     · 韩股 / 金银 / 商品 → **NONE**（本地 names_extra 已覆盖显示名，不需要远端）；
     *     · 已下线市场（港股/A股）→ NONE；
     *     · 纯中文输入 → NONE（中文名是本地表的职责，英文接口必然零命中）；
     *     · 其余（美股代码/英文名）→ Nasdaq。
     */
    internal fun remoteTarget(raw: String): RemoteTarget {
        val query = queryOf(raw)
        if (query.isEmpty()) return RemoteTarget.NONE
        return when (MktData.symType(query)) {
            "unsupported" -> RemoteTarget.NONE
            "crypto" -> RemoteTarget.BN
            "kr", "gold", "silver", "cmdty" -> RemoteTarget.NONE
            else -> if (query.none { it.code in 0x20..0x7E }) RemoteTarget.NONE
                    else RemoteTarget.NASDAQ
        }
    }

    // ---------- Nasdaq 官方按词搜索（美股唯一远端） ----------

    private const val NQ_LOOKUP_URL = "https://api.nasdaq.com/api/autocomplete/slookup/10?search="

    /** Nasdaq 回的是 `^[A-Z][A-Z0-9.\\-]{0,9}$` 形态的代码；非法/重复一律丢。 */
    private val NASDAQ_SYMBOL = Regex("^[A-Z][A-Z0-9.\\-]{0,9}$")

    /**
     * 解析 Nasdaq `autocomplete/slookup` 回包（纯函数，单测直接喂样例）。
     * 只要 STOCKS / ETF 两类资产（指数、期权、基金其它形态不进联想）。
     */
    internal fun parseNasdaqLookup(payload: String): List<NasdaqSymbol> {
        if (payload.isBlank()) return emptyList()
        return try {
            val rows = JSONObject(payload).optJSONArray("data") ?: return emptyList()
            val out = ArrayList<NasdaqSymbol>(rows.length())
            val seen = HashSet<String>()
            for (i in 0 until rows.length()) {
                val r = rows.optJSONObject(i) ?: continue
                val asset = r.optString("asset", "").uppercase(Locale.US)
                if (asset != "STOCKS" && asset != "ETF") continue
                val code = r.optString("symbol", "").trim().uppercase(Locale.US)
                if (!NASDAQ_SYMBOL.matches(code) || !seen.add(code)) continue
                out.add(NasdaqSymbol(code, r.optString("name", "").trim()))
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun nasdaqLookup(query: String): List<NasdaqSymbol> {
        if (query.isEmpty()) return emptyList()
        return try {
            val payload = MktData.get(
                NQ_LOOKUP_URL + java.net.URLEncoder.encode(query, "UTF-8"),
                accept = "application/json", ua = "Mozilla/5.0",
                timeoutMs = 8_000, referer = "https://www.nasdaq.com/"
            )
            parseNasdaqLookup(payload)
        } catch (e: Exception) {
            android.util.Log.w("MktSymDir", "nq lookup failed: " + e)
            emptyList()
        }
    }

    /**
     * 美股远端命中的**准入闸**：代码必须在内置离线目录（`us_dir.tsv`）里。
     *
     * ⚠ 2026-11-03 t11（t3 实测）：Nasdaq slookup 的召回里混着 **ETF**（SPY/TLT/QQQ…）与
     *   **优先股**（JPM.PRC），而它们当时被直接并入候选 ⟹ 用户在自选搜索框里能搜到、还能加自选。
     *   离线目录（资产侧已按结构筛过：只留普通股、无 ETF/优先股/权证）**不含**这些代码。
     *   ⟹ 判据只留一条：**目录里有没有这个代码**。目录就是唯一权威，不另立名单。
     *   ⚠ 刻意**不按 `asset` 字段过滤**：该字段不可靠（JPM.PRC 的 asset 也是 STOCKS），
     *     也不给 ETF 开任何"放行例外" —— 目录外的代码一个都不进候选。
     *   ⚠ 也不维护任何黑白名单：用户明确否掉"白名单那一套"，名单会烂、会缺、会和目录漂移。
     */
    internal fun usDirHas(symbol: String): Boolean = UsNames.hasUs(symbol)

    /**
     * 美股远端命中的合并：**目录准入 → 排序 → 显示名**（纯函数，单测直接喂解析结果，不联网）。
     *
     * ⚠ 准入必须发生在「并入候选之前」——它是过滤，不是提示：目录外的代码直接丢弃。
     *   这一道就同时挡住 ETF（SPY/TLT/QQQ…）与优先股（JPM.PRC/.PRA），
     *   因为离线目录里本来就没有它们（t7 的资产校验已断言 SPY/TLT/QQQ/VOO/DIA/XLK/JPM.PRC 不在列）。
     */
    internal fun mergeNasdaqHits(entries: List<NasdaqSymbol>, q: String): List<SugItem> {
        val query = q.trim().uppercase(Locale.US)
        if (query.isEmpty()) return emptyList()
        val ranked = entries.mapNotNull { entry ->
            val symbol = entry.symbol
            // ⚠ 准入先于排序：目录外的一律不进候选（ETF/优先股/权证在这里被挡掉）。
            if (!usDirHas(symbol)) return@mapNotNull null
            val name = entry.name.uppercase(Locale.US)
            val rank = when {
                symbol == query || name == query -> 0
                symbol.startsWith(query) || name.startsWith(query) -> 1
                symbol.contains(query) || name.contains(query) -> 2
                else -> return@mapNotNull null
            }
            Triple(rank, entry, name)
        }.sortedBy { it.first }
        return ranked.take(8).map { (rank, entry, _) ->
            val cn = UsNames.zhOf(entry.symbol)
            SugItem(entry.symbol, if (cn.isNotEmpty()) cn else entry.name.ifEmpty { "美股" }, rank < 2)
        }
    }

    /**
     * 美股搜索：Nasdaq 官方按词接口（2.3–2.6KB/次；与行情同域名）+ 目录准入（见 [usDirHas]）。
     * ⚠ 曾经在这里并联过东财 suggest（拿代码）与东财 ulist（拿中文名）——
     *   本轮**两条都删了**：中文名改由内置离线目录提供，代码目录由 Nasdaq 官方给。
     *   反馈给用户的显示名取「内置中文名 > Nasdaq 英文名 > 美股」。
     */
    internal fun nasdaqSearch(q: String): List<SugItem> {
        val query = queryOf(q)
        if (query.isEmpty()) return emptyList()
        return mergeNasdaqHits(nasdaqLookup(query), query)
    }

    // ---------- 币安 exchangeInfo（加密唯一目录） ----------

    private const val BN_INFO_URL =
        "https://data-api.binance.vision/api/v3/exchangeInfo?permissions=SPOT"
    private const val BN_TTL_MS = 6 * 60 * 60 * 1000L     // 目录 6 小时（币对新上/下架不频繁）
    private const val BN_RETRY_MS = 60 * 1000L            // 失败 1 分钟内不再重试（别猛打）
    private val SPOT_QUOTES = setOf("USDT", "USD", "USDC")

    private val bnLock = Any()
    @Volatile private var bnCache: List<String>? = null
    @Volatile private var bnAt = 0L
    @Volatile private var bnRetryAt = 0L

    /**
     * 解析币安 `exchangeInfo`（纯函数，单测直接喂样例）。
     * 只收 `status=TRADING` 且报价币在 [SPOT_QUOTES] 里的现货对；
     * BTC 打头只留 BTCUSDT（既有收敛口径，见 [converged]）。
     */
    internal fun parseBinanceSymbols(payload: String): List<String> {
        if (payload.isBlank()) return emptyList()
        return try {
            val arr = JSONObject(payload).optJSONArray("symbols") ?: return emptyList()
            val out = ArrayList<String>(arr.length())
            val seen = HashSet<String>()
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                if (r.optString("status", "") != "TRADING") continue
                val quote = r.optString("quoteAsset", "").uppercase(Locale.US)
                if (quote !in SPOT_QUOTES) continue
                val base = r.optString("baseAsset", "").uppercase(Locale.US)
                if (base.isEmpty()) continue
                val sym = base + quote
                if (!converged(sym) || !seen.add(sym)) continue
                out.add(sym)
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 全币对目录（懒缓存）。
     * ⚠ 单次 ~1.0MB（exchangeInfo 含每个币对的过滤器定义）：只在**币对形态的搜索**里取一次，
     *   成功缓存 6 小时，失败 1 分钟冷却 —— 弱网下也不会每次敲键盘都拉一遍。
     */
    private fun binanceSymbols(): List<String> {
        val now = System.currentTimeMillis()
        bnCache?.let { if (now - bnAt < BN_TTL_MS) return it }
        synchronized(bnLock) {
            bnCache?.let { if (System.currentTimeMillis() - bnAt < BN_TTL_MS) return it }
            if (System.currentTimeMillis() < bnRetryAt) return bnCache ?: emptyList()
            val got = try {
                parseBinanceSymbols(MktData.get(BN_INFO_URL, accept = "application/json",
                    timeoutMs = 15_000))
            } catch (e: Exception) {
                android.util.Log.w("MktSymDir", "binance exchangeInfo failed: " + e)
                emptyList()
            }
            return if (got.isEmpty()) {
                bnRetryAt = System.currentTimeMillis() + BN_RETRY_MS
                bnCache ?: emptyList()
            } else {
                bnCache = got
                bnAt = System.currentTimeMillis()
                bnRetryAt = 0L
                got
            }
        }
    }

    // BTC收敛终态(v3.3):BTC打头只出BTCUSDT,其余变体一律不收
    private fun converged(s: String): Boolean =
        !(s.startsWith("BTC") && s != "BTCUSDT")

    private fun cryptoSearch(query: String): List<SugItem> {
        val out = mutableListOf<SugItem>()
        for (s in binanceSymbols()) {
            if (s.startsWith(query) && converged(s) && out.none { it.s == s } && out.size < 20) {
                out.add(SugItem(s, "币", true))
            }
        }
        return out
    }

    // ---------- 自选搜索的排序与过滤 ----------

    /**
     * 精确 > 前缀 > 包含，最多 12 条（照稿 `paintFavResults`）。
     *
     * ⚠ 2026-10-19 r8 · **A-1（用户拍板「按稿子口径处置」）**：**不再**用 `it.s !in mine` 过滤。
     *   改前：已收藏的 code 被从候选里剔除 ⟹ 稿的三态阶梯**在真机结构性不可达**，
     *   用户搜自己已收藏的品种只会拿到「无匹配」误报。
     *   改后：已收藏项**重新出现在候选里**，行态判定（FavPanel `it.s in mine -> SugState.IN_LIST`）
     *   于是可达，与稿三态（＋ 可加 / 已加入 / 已在自选）同构。
     *   ⚠ `mine` 形参**保留不动**（它是三态阶梯的唯一开关点，删形参要连改调用点）。
     */
    fun rankFav(items: List<SugItem>, q: String, mine: Set<String>): List<SugItem> {
        val query = queryOf(q)
        fun rank(x: SugItem): Int = when {
            x.s == query -> 0
            x.s.startsWith(query) -> 1
            else -> 2
        }
        // 中文名命中的条目 s=代码、tag=中文名（或英文名）⟹ tag 参与过滤，否则 s=STX 配 q=希捷 会被丢。
        // ⚠ 英文名不是全大写（`Apple Inc. - Common Stock`）：这里必须**大小写不敏感**，
        //   否则搜 `APPLE` 时本地目录明明命中 AAPL，却被这道过滤整条丢掉（=「无匹配」误报）。
        return items.filter {
            it.s.contains(query) || query.contains(it.s) ||
                it.tag.uppercase(Locale.US).contains(query)
        }
            .sortedWith(compareBy<SugItem> { rank(it) })
            .take(12)
    }
}
