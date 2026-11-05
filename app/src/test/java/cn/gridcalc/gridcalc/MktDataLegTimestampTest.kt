package cn.gridcalc.gridcalc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MktDataLegTimestampTest {
    private fun sample(): List<VendorKs> = listOf(
        VendorKs("NQ", listOf(KLine(t = 1L, o = 10.0, h = 11.0, l = 9.0, c = 10.5, v = 1.0, qv = 0.0)))
    )

    @Test
    fun unknownSavedTimeDoesNotBecomeFreshNow() {
        MktData.publishCachedLegs("TEST_UNKNOWN_TS", sample(), -1L)

        assertEquals(0L, MktData.legsAtOf("TEST_UNKNOWN_TS"))
        assertTrue(MktData.legsOf("TEST_UNKNOWN_TS").isNotEmpty())
    }

    @Test
    fun knownSavedTimeIsPreserved() {
        MktData.publishCachedLegs("TEST_KNOWN_TS", sample(), 1234L)

        assertEquals(1234L, MktData.legsAtOf("TEST_KNOWN_TS"))
    }

    @Test
    fun zeroStillMeansBatchCreatedNow() {
        val before = System.currentTimeMillis()
        MktData.publishCachedLegs("TEST_NOW_TS", sample(), 0L)

        assertTrue(MktData.legsAtOf("TEST_NOW_TS") >= before)
    }
}
