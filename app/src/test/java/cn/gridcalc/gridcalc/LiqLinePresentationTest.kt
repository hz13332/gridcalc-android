package cn.gridcalc.gridcalc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiqLinePresentationTest {
    @Test
    fun currentSecondSupportShowsEvenWhenOptionalCapIsAbsent() {
        val supports = listOf(3_613.94, 2_923.81)
        val line = liquidationLinePresentation(
            liqNow = 162_650.00,
            marketSupport2 = secondSupportForLiqLine(supports),
        )

        assertNotNull(line)
        assertEquals(2_923.81, line!!.support2, 0.0)
        assertTrue(line.exceeded)
    }

    @Test
    fun lineIsHiddenWhenThereIsNoSecondSupport() {
        val supports = listOf(3_613.94)

        assertNull(liquidationLinePresentation(4_000.00, secondSupportForLiqLine(supports)))
    }

    @Test
    fun lineIsHiddenWhenTheModelHasNoLiquidationPrice() {
        assertNull(liquidationLinePresentation(null, 2_923.81))
    }

    @Test
    fun lineIsNotExceededWhenLiquidationPriceIsBelowSupport() {
        val line = liquidationLinePresentation(2_800.00, 2_923.81)

        assertNotNull(line)
        assertFalse(line!!.exceeded)
    }
}
