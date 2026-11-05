package cn.gridcalc.gridcalc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 搜索层（[MktSuggest]）的单源行为。
 *
 * 2026-11-03 用户裁定后的链路只剩三条：内置离线目录（UsNames）+ Nasdaq slookup（美股）+
 * 币安 exchangeInfo（加密），**美股与加密二选一、绝不并联**，也没有任何兜底链。
 * 这里断言的就是这三条 + 已下线市场（港股/A股）的明确判负 + **远端召回的目录准入**。
 *
 * ⚠ 2026-11-03 t11（t3 实测回归）：Nasdaq slookup 会返回 ETF（SPY/TLT/QQQ…）与优先股
 *   （JPM.PRC/.PRA），它们曾被直接并入候选 ⟹ 用户能搜到、还能加自选。
 *   修法是「美股分支只认 us_dir.tsv 内的代码」，见 [MktSuggest.usDirHas] /
 *   [MktSuggest.mergeNasdaqHits]；用例里的代码全部取自目录（AAPL/MSFT/NVDA/STX/WDC/…），
 *   负例代码（SPY/TLT/QQQ/JPM.PRC）则断言**不在**目录里。
 */
class MktSuggestTest {

    @Before
    fun loadAsset() {
        assertTrue(TestAssets.loadAll())
    }

    @Test
    fun parsesNasdaqLookupRowsDefensively() {
        // 解析层只做「形状」清洗（asset 类别、重复、非法符号），**不做业务准入** ——
        // 准入在 [MktSuggest.mergeNasdaqHits]（是否在离线目录内），见下面的目录闸用例。
        val payload = """
            {"data":[
              {"symbol":"AAPL","name":"Apple Inc. Common Stock","asset":"STOCKS","exchange":"NASDAQ-GS"},
              {"symbol":"aapl","name":"Apple duplicate","asset":"STOCKS","exchange":"NASDAQ-GS"},
              {"symbol":"MSFT","name":"Microsoft Corporation","asset":"STOCKS","exchange":"NASDAQ-GS"},
              {"symbol":"SPY","name":"SPDR S&P 500 ETF Trust","asset":"ETF","exchange":"NYSE ARCA"},
              {"symbol":"^TNX","name":"CBOE 10-Year Treasury","asset":"INDEX","exchange":"INDEX"},
              {"symbol":"","name":"missing","asset":"STOCKS","exchange":"NASDAQ-GS"},
              {"symbol":"BAD SYMBOL","name":"illegal","asset":"STOCKS","exchange":"NASDAQ-GS"}
            ]}
        """.trimIndent()

        val rows = MktSuggest.parseNasdaqLookup(payload)
        assertEquals(listOf("AAPL", "MSFT", "SPY"), rows.map { it.symbol })
        assertEquals("Apple Inc. Common Stock", rows[0].name)
    }

    @Test
    fun parsesBinanceExchangeInfoAsSpotPairsOnly() {
        val payload = """
            {"symbols":[
              {"symbol":"BTCUSDT","baseAsset":"BTC","quoteAsset":"USDT","status":"TRADING"},
              {"symbol":"BTCUSDC","baseAsset":"BTC","quoteAsset":"USDC","status":"TRADING"},
              {"symbol":"ETHUSDT","baseAsset":"ETH","quoteAsset":"USDT","status":"TRADING"},
              {"symbol":"ETHBTC","baseAsset":"ETH","quoteAsset":"BTC","status":"TRADING"},
              {"symbol":"SOLUSDT","baseAsset":"SOL","quoteAsset":"USDT","status":"BREAK"},
              {"symbol":"ADAUSD","baseAsset":"ADA","quoteAsset":"USD","status":"TRADING"}
            ]}
        """.trimIndent()

        // 只收 TRADING + USDT/USDC/USD 报价；BTC 打头只留 BTCUSDT（既有收敛口径）。
        assertEquals(listOf("BTCUSDT", "ETHUSDT", "ADAUSD"), MktSuggest.parseBinanceSymbols(payload))
    }

    @Test
    fun localSearchIsOfflineDirectoryOnly() {
        // 本地即时结果 = 内置目录（中文名→代码），不联网就能出。
        val hits = MktSuggest.local("希捷")
        assertTrue("希捷 未命中 STX: $hits", hits.any { it.s == "STX" })
        // 中文名回填走同一张表。
        assertEquals("希捷科技", MktSuggest.favName("STX"))
        assertEquals("西部数据", MktSuggest.favName("wdc"))
    }

    @Test
    fun droppedMarketIsNeverGivenASourceFallback() {
        // 港股 00700 / A股 sh688825 已下线：判成 unsupported，**不再产出任何候选**，
        // 也不会去打一次恒空的 Nasdaq（remote 对 unsupported 直接短路）。
        assertEquals("unsupported", MktData.symType("00700"))
        assertEquals("unsupported", MktData.symType("sh688825"))
        assertTrue(MktSuggest.remote("00700").isEmpty())
        assertTrue(MktSuggest.remote("sh688825").isEmpty())
        assertTrue(MktData.unsupportedText("00700").contains("港股"))
        assertTrue(MktData.unsupportedText("sh688825").contains("A 股"))
    }

    @Test
    fun usDirectoryIsTheOnlyAdmissionGate() {
        // 目录内的普通股：放行。
        for (t in listOf("AAPL", "MSFT", "NVDA", "IBM", "DELL")) {
            assertTrue("目录内代码被误挡: $t", MktSuggest.usDirHas(t))
        }
        // 目录外：ETF（SPY/TLT/QQQ）与优先股（JPM.PRC/.PRA）一律不放行。
        for (t in listOf("SPY", "TLT", "QQQ", "JPM.PRC", "JPM.PRA")) {
            assertFalse("目录外代码未被挡: $t", MktSuggest.usDirHas(t))
        }
    }

    @Test
    fun remoteEtfAndPreferredHitsAreDroppedBeforeMerging() {
        // 模拟 Nasdaq slookup 的真实回包形状：正股与 ETF/优先股混在一起返回。
        val entries = listOf(
            NasdaqSymbol("AAPL", "Apple Inc. Common Stock"),
            NasdaqSymbol("SPY", "SPDR S&P 500 ETF Trust"),
            NasdaqSymbol("MSFT", "Microsoft Corporation"),
            NasdaqSymbol("TLT", "iShares 20+ Year Treasury Bond ETF"),
            NasdaqSymbol("QQQ", "Invesco QQQ Trust, Series 1"),
            NasdaqSymbol("JPM.PRC", "J P Morgan Chase & Co"),
            NasdaqSymbol("NVDA", "NVIDIA Corporation")
        )
        // ⚠ ETF 负例（t3 的 03/03b/03c 三张）：按代码搜，SPY/TLT/QQQ/JPM.PRC 一个都不许进候选。
        for (q in listOf("SPY", "TLT", "QQQ", "JPM.PRC")) {
            assertTrue("$q 不该有候选（目录外）", MktSuggest.mergeNasdaqHits(entries, q).isEmpty())
        }
        // 正股照常出，且不受同批 ETF 影响。
        assertEquals(listOf("AAPL"), MktSuggest.mergeNasdaqHits(entries, "AAPL").map { it.s })
    }

    @Test
    fun rankFavKeepsChineseNameHitsAndRanksCodePrefixFirst() {
        // 样例全部取自目录里真实存在的代码（STX/WDC/AAPL）——不再用假的 STXX 之类占位。
        val items = listOf(
            SugItem("STX", "希捷科技", true),
            SugItem("WDC", "西部数据", false),
            SugItem("AAPL", "苹果", false)
        )
        // 中文名命中靠 tag 参与过滤（s 是代码，不 contains 查询词）。
        assertEquals(listOf("STX"), MktSuggest.rankFav(items, "希捷", emptySet()).map { it.s })
        // 代码查询按「精确 > 前缀」排序。
        assertEquals(listOf("WDC"), MktSuggest.rankFav(items, "WDC", emptySet()).map { it.s })
    }

    @Test
    fun rankFavMatchesEnglishNamesCaseInsensitively() {
        // 目录里没有中文名时 tag 是英文名（`Apple Inc. - Common Stock`，非全大写）。
        // 搜 APPLE 必须命中 AAPL —— 大小写敏感会把本地目录的命中整条丢掉（「无匹配」误报）。
        val items = listOf(SugItem("AAPL", "Apple Inc. - Common Stock", false))
        assertEquals(listOf("AAPL"), MktSuggest.rankFav(items, "apple", emptySet()).map { it.s })
    }

    @Test
    fun sortTopPrefersCodeHitsAndCapsAtSix() {
        // 样例用目录里真实存在的代码（AAPL/MSFT/NVDA/TSLA/AMZN/META/GOOGL/AMD/NFLX）。
        val codes = listOf("AAPL", "MSFT", "NVDA", "TSLA", "AMZN", "META", "GOOGL", "AMD", "NFLX")
        val items = codes.mapIndexed { i, c -> SugItem(c, MktSuggest.favName(c).ifEmpty { "美股" }, i % 2 == 0) }
        val top = MktSuggest.sortTop(items)
        assertEquals(6, top.size)
        assertTrue("pre 命中必须排前面", top.take(3).all { it.pre })
        // pre 条目都是目录里真实存在的代码（说明样例不是编的）。
        assertTrue(top.filter { it.pre }.all { MktSuggest.usDirHas(it.s) })
    }

    // ───────────────────────── t16（t4 的 R2 / R3 / R5）─────────────────────────

    @Test
    fun usDirHasIgnoresNamesExtraTable() {
        // ⚠ t4 的 R2：usDirHas 曾经走 UsNames.search，而 search 把 names_extra 也扫进来 ⟹
        //   「目录是唯一权威」这句话在实现上不成立（往 extra 写一个 ETF 码就能静默放行）。
        //   这里把 SPY 注入 names_extra，断言两道闸都不认它。
        UsNames.loadExtra(sequenceOf("SPY\t标普500ETF"))
        try {
            assertFalse("names_extra 里的代码不是美股目录成员", MktSuggest.usDirHas("SPY"))
            val hits = MktSuggest.mergeNasdaqHits(
                listOf(NasdaqSymbol("SPY", "State Street SPDR S&P 500 ETF Trust")), "SPY"
            )
            assertTrue("注入 names_extra 也不许把 ETF 放进候选", hits.isEmpty())
        } finally {
            // 还原真资产，避免把注入表漏给后面的用例。
            assertTrue(TestAssets.loadNamesExtra())
        }
    }

    @Test
    fun remoteTargetOnlyGoesOutForUsAndCrypto() {
        // ⚠ t4 的 R5：改前「非加密即 Nasdaq」——韩股/金银/商品也会白打一次 Nasdaq。
        assertEquals(MktSuggest.RemoteTarget.NONE, MktSuggest.remoteTarget("000660"))
        assertEquals(MktSuggest.RemoteTarget.NONE, MktSuggest.remoteTarget("XAUUSD"))
        assertEquals(MktSuggest.RemoteTarget.NONE, MktSuggest.remoteTarget("CL00Y"))
        assertEquals(MktSuggest.RemoteTarget.NONE, MktSuggest.remoteTarget("00700"))
        assertEquals(MktSuggest.RemoteTarget.NONE, MktSuggest.remoteTarget("SH688825"))
        assertEquals(MktSuggest.RemoteTarget.NONE, MktSuggest.remoteTarget("比特币"))
        assertEquals(MktSuggest.RemoteTarget.NASDAQ, MktSuggest.remoteTarget("AAPL"))
        assertEquals(MktSuggest.RemoteTarget.NASDAQ, MktSuggest.remoteTarget("apple"))
        assertEquals(MktSuggest.RemoteTarget.BN, MktSuggest.remoteTarget("BTCUSDT"))
    }

    @Test
    fun legacyFavoriteTypesAreNormalisedToUnsupported() {
        // ⚠ t4 的 R3：旧版本给自选存过 hk / ashare。载入时必须按现役 symType 归一成 unsupported，
        //   否则徽章印内部枚举名、行内还把它当有源品种（折算/显示 USD）。
        val out = FavStore.canonicalItems(
            listOf(FavItem("00700", "hk"), FavItem("sh688825", "ashare"), FavItem("STX", "stock"))
        )
        assertEquals(3, out.size)
        assertEquals(FavItem("00700", "unsupported"), out[0])
        assertEquals(FavItem("SH688825", "unsupported"), out[1])
        assertEquals(FavItem("STX", "stock"), out[2])
        // 徽章：旧值兜底也必须说「不支持」，不能把 "hk" 原样印上屏。
        assertEquals("不支持", FavStore.tag("hk"))
        assertEquals("不支持", FavStore.tag("ashare"))
        assertEquals("不支持", FavStore.tag("unsupported"))
    }
}
