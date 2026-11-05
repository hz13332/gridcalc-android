package cn.gridcalc.gridcalc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 内置美股目录（`res/raw/us_dir.tsv`）的解析与检索。
 *
 * ⚠ 2026-11-03 用户裁定「中文名提前汉化、存在程序里面」⟹ 名字是**离线资产**，
 *   本测试直接用磁盘上的同一份资产走产品的解析/检索路径，不联网、不依赖 Android Context。
 */
class UsNamesTest {

    @Before
    fun loadAsset() {
        assertTrue(
            "内置目录资产 res/raw/us_dir.tsv 未找到（单测 cwd 应为 app/ 模块目录）",
            TestAssets.loadAll()
        )
    }

    @Test
    fun parsesThreeColumnAssetAndFallsBackToEnglishName() {
        // 资产契约：`symbol \t zh_name \t en_name`（screener 不提供交易所 ⟹ 没有交易所列）。
        UsNames.load(
            sequenceOf(
                "STX\t希捷科技\tSeagate Technology Holdings PLC",
                "ZZZZ\t\tZeta Zero Corp",
                "TWO\tTwo Words",
                "BAD"
            )
        )
        assertEquals("希捷科技", UsNames.cnOf("STX"))
        assertEquals("Seagate Technology Holdings PLC", UsNames.enOf("STX"))
        // 中文名为空 → 上屏/检索回落英文名（captain 口径）。
        assertEquals("Zeta Zero Corp", UsNames.search("ZZZZ").first().tag)
        // 两列（列数不足）时整段当名字，不抛异常、不吞行。
        assertEquals("Two Words", UsNames.cnOf("TWO"))
        // 恢复内置资产，避免把合成样例漏给同 JVM 的其它测试类。
        assertTrue(TestAssets.loadUsDir())
    }

    @Test
    fun directoryCoversNyseCommonStocks() {
        // 目录是 Nasdaq screener **单源**，但它本来就含纽交所（IBM/DELL/BAC/JPM 都在）。
        // 只收纳斯达克自家目录的旧方案会在这里缺票 —— 这正是本轮换目录的原因。
        assertTrue("目录条目数过少: ${UsNames.count()}", UsNames.count() > 3000)
        for (t in listOf("IBM", "DELL", "BAC", "JPM", "XOM")) {
            assertTrue("目录缺 $t（纽交所普通股必须在内）", UsNames.search(t).any { it.s == t })
        }
    }

    @Test
    fun chineseNameLookupUsesOfflineTable() {
        assertEquals("希捷科技", UsNames.cnOf("STX"))
        assertEquals("西部数据", UsNames.cnOf("WDC"))
        // 大小写/空白不敏感；表里没有的代码是**空串**，绝不编名字。
        assertEquals("希捷科技", UsNames.cnOf(" stx "))
        assertEquals("", UsNames.cnOf("NOT_A_REAL_TICKER"))
    }

    @Test
    fun chineseInputResolvesToTicker() {
        // 验收原话：中文输入「希捷」「西部数据」能检索出 STX / WDC。
        val stx = UsNames.search("希捷")
        assertTrue("希捷 未命中 STX: $stx", stx.any { it.s == "STX" })
        assertEquals("希捷科技", stx.first { it.s == "STX" }.tag)

        val wdc = UsNames.search("西部数据")
        assertTrue("西部数据 未命中 WDC: $wdc", wdc.any { it.s == "WDC" })
    }

    @Test
    fun codePrefixSearchRanksExactCodeFirst() {
        val hits = UsNames.search("AAPL")
        assertEquals("AAPL", hits.first().s)
        assertTrue(hits.first().pre)
    }

    @Test
    fun everySuggestionCarriesANonEmptyTag() {
        // 中文名缺失时回落英文名（captain 口径）；tag 为空会让自选候选行第二列空白。
        for (q in listOf("A", "M", "ST", "希捷")) {
            for (it in UsNames.search(q, 8)) {
                assertTrue("tag 为空: $it", it.tag.isNotEmpty())
                assertTrue("pre/代码语义不符: $it", it.s.isNotEmpty())
            }
        }
    }

    @Test
    fun searchIsLimitedAndStable() {
        val hits = UsNames.search("A", 5)
        assertEquals(5, hits.size)
        // 同一输入两次结果必须一致（资产按 code 字典序，排序稳定）。
        assertEquals(hits.map { it.s }, UsNames.search("A", 5).map { it.s })
    }
}
