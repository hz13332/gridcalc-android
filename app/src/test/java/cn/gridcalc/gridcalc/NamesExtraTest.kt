package cn.gridcalc.gridcalc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 非美股中文名离线表（`res/raw/names_extra.tsv`）的行为。
 *
 * ⚠ 2026-11-03 用户口径「中文名提前汉化，存在程序里面」：这张表是**只读资产**，
 *   覆盖加密/大宗/金银/韩股（美股中文名归 us_dir.tsv）；运行时零网络、只用于显示名与按名检索。
 */
class NamesExtraTest {

    @Before
    fun loadAssets() {
        assertTrue("内置资产缺失（单测 cwd 应为 app/ 模块目录）", TestAssets.loadAll())
    }

    @Test
    fun assetIsWellFormedSortedAndOnlySupportedMarkets() {
        val f = TestAssets.assetFile("names_extra.tsv")
        assertTrue("names_extra.tsv 不存在", f != null)
        val data = f!!.readBytes()
        assertFalse("资产不得带 BOM", data.size >= 3 &&
            data[0] == 0xEF.toByte() && data[1] == 0xBB.toByte() && data[2] == 0xBF.toByte())
        assertFalse("资产必须纯 LF", data.contains('\r'.code.toByte()))

        val lines = String(data, Charsets.UTF_8).split('\n').filter { it.isNotEmpty() }
        assertTrue("行数过少: ${lines.size}", lines.size >= 25)
        var prev = ""
        val allowed = setOf("crypto", "cmdty", "gold", "silver", "kr")
        val syms = HashSet<String>()
        for (ln in lines) {
            val parts = ln.split('\t')
            assertEquals("每行必须两列: $ln", 2, parts.size)
            val sym = parts[0]
            assertTrue("symbol 未按升序: $sym <= $prev", sym > prev)
            prev = sym
            assertTrue("symbol 重复: $sym", syms.add(sym))
            assertTrue("品种类型不该收录: $sym(${MktData.symType(sym)})",
                MktData.symType(sym) in allowed)
        }
        // 已下线市场与美股都不该进**这张表**（美股中文名由 us_dir.tsv 负责）。
        for (bad in listOf("00700", "688825", "TLT", "AAPL")) {
            assertFalse("names_extra 不该收录: $bad", bad in syms)
        }
        // 美股名照旧来自 us_dir（两张表各管各的，互不污染）。
        assertEquals("苹果", UsNames.cnOf("AAPL"))
    }

    @Test
    fun chineseNamesCoverCryptoMetalsCommoditiesAndKorea() {
        assertEquals("比特币", UsNames.zhOf("BTCUSDT"))
        assertEquals("黄金", UsNames.zhOf("XAUUSD"))
        assertEquals("白银", UsNames.zhOf("XAGUSD"))
        assertEquals("原油", UsNames.zhOf("CL00Y"))
        assertEquals("三星电子", UsNames.zhOf("005930"))
        assertEquals("SK海力士", UsNames.zhOf("000660"))
        // 大小写/空白不敏感
        assertEquals("比特币", UsNames.zhOf(" btcusdt "))
    }

    @Test
    fun searchByChineseNameHitsTheRightSymbol() {
        assertTrue("比特币 未命中 BTCUSDT", UsNames.search("比特币").any { it.s == "BTCUSDT" })
        assertTrue("黄金 未命中 XAUUSD", UsNames.search("黄金").any { it.s == "XAUUSD" })
        assertTrue("白银 未命中 XAGUSD", UsNames.search("白银").any { it.s == "XAGUSD" })
        assertTrue("三星 未命中 005930", UsNames.search("三星").any { it.s == "005930" })
        assertTrue("海力士 未命中 000660", UsNames.search("海力士").any { it.s == "000660" })
        assertTrue("原油 未命中 CL00Y", UsNames.search("原油").any { it.s == "CL00Y" })
        // 命中行的 tag 就是中文名（自选候选第二列要有字）
        assertEquals("三星电子", UsNames.search("三星").first { it.s == "005930" }.tag)
    }

    @Test
    fun favNameFallsBackUsDirThenExtraThenCode() {
        // 美股目录优先
        assertEquals("希捷科技", MktSuggest.favName("STX"))
        // 非美股名表兜住（原来会回落代码）
        assertEquals("比特币", MktSuggest.favName("BTCUSDT"))
        assertEquals("黄金", MktSuggest.favName("xauusd"))
        assertEquals("三星电子", MktSuggest.favName("005930"))
        // 两张表都没有 → 空串，调用方回落显示代码
        assertEquals("", MktSuggest.favName("NOT_A_REAL_TICKER"))
    }

    @Test
    fun extraTableIsSeparateFromTheUsDirectory() {
        // cnOf 只认美股目录；非美股名不会污染它（两份资产各自独立）。
        assertEquals("", UsNames.cnOf("BTCUSDT"))
        assertEquals("比特币", UsNames.zhOf("BTCUSDT"))
        assertTrue("美股目录条目数应保持 ~5.8k", UsNames.count() > 3000)
        assertTrue("非美股名表条目数异常: ${UsNames.extraCount()}", UsNames.extraCount() in 25..80)
    }

    @Test
    fun chineseQueryStaysFullyOffline() {
        // 纯中文输入在 remote() 里短路（0 次远端请求），结果全部由本地表给出。
        assertTrue(MktSuggest.remote("比特币").isEmpty())
        assertTrue(MktSuggest.remote("海力士").isEmpty())
        // 本地联想/自选搜索照常出结果（同一份离线表，零网络）。
        assertTrue(MktSuggest.local("比特币").any { it.s == "BTCUSDT" })
        assertTrue(MktSuggest.favLocal("海力士").any { it.s == "000660" })
    }
}
