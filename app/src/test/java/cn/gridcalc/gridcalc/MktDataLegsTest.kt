package cn.gridcalc.gridcalc

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * **单腿不变量**（t16 / t4 的 R6）。
 *
 * 用户 2026-11-03 裁定「数据源收敛为一家」后，每个市场**有且只有一条腿**：
 *   美股 → Nasdaq、韩股 → Naver、加密 → 币安、大宗/金银 → 东财。
 *
 * 这些函数（[MktData.cryptoLegs] / [MktData.stockLegs] / [MktData.metalLegs] /
 * [MktData.legNames]）此前**零测试调用**：谁哪天加回第二条腿/兜底源，53 个用例一个都不会红。
 * 本类就是那道闸：断言**名字与条数**，不只看「非空」。
 */
class MktDataLegsTest {

    @Test
    fun everyMarketHasExactlyOneLeg() {
        assertEquals(listOf("BN"), MktData.cryptoLegs("BTCUSDT"))
        assertEquals(1, MktData.cryptoLegs("BTCUSDT").size)

        assertEquals(listOf("Nasdaq"), MktData.legNames("AAPL"))
        assertEquals(listOf("Naver"), MktData.legNames("000660"))
        // ⚠ 屏上这个名字就是「东财」（t14 实测 mkt_srcname=[东财 · 收盘]）；
        //   vendorName 里另有一个 "ED"→"东方财富" 的映射，但金属腿的 srcKey 用的是中文字面
        //   「东财」，所以这里断言的是**用户真正看到的名字**，不是 R6 里凭印象写的那个。
        assertEquals(listOf("东财"), MktData.legNames("XAUUSD"))
        for (s in listOf("AAPL", "000660", "XAUUSD", "BTCUSDT")) {
            assertEquals("$s 必须只有一条腿", 1, MktData.legNames(s).size)
        }
    }

    @Test
    fun singleLegSurvivesAliasesAndCase() {
        // 大小写/别名入口也必须是同一条腿（别在归一化里漏出第二腿）。
        assertEquals(listOf("Nasdaq"), MktData.legNames("aapl"))
        assertEquals(listOf("东财"), MktData.legNames("xauusd"))
        assertEquals(listOf("BN"), MktData.cryptoLegs("btcusdt"))
        assertEquals(1, MktData.legNames("NG=F").size)   // 天然气别名 → NG00Y（东财单腿）
    }
}
