package cn.gridcalc.gridcalc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MktDataAliasTest {
    @Test
    fun canonicalSymNormalizesOnlyKnownNaturalGasAliases() {
        for (input in listOf("NATGAS", "ng", "NG=F", "ng00y")) {
            assertEquals("NG00Y", MktData.canonicalSym(" $input "))
            assertEquals("cmdty", MktData.symType(input))
            assertEquals("NG00Y", MktData.fetchKeyOf(input))
            assertEquals("102.NG00Y", MktData.emSecid(input))
        }
        assertNotEquals("NG00Y", MktData.canonicalSym("NGX"))
        assertNotEquals("cmdty", MktData.symType("NGX"))
    }

    @Test
    fun canonicalCacheKeysAndOldAliasKeysConverge() {
        val canonical = MktData.MktCache.key("NG00Y", "M", 20)
        val legacy = MktData.MktCache.key("NG=F", "M", 20)
        assertEquals(canonical, legacy)
    }

    @Test
    fun favoriteRecordsPreferCanonicalWhenAliasAndCanonicalCoexist() {
        val rows = FavStore.canonicalItems(listOf(
            FavItem("NG=F", "旧别名"),
            FavItem("NG00Y", "规范记录"),
            FavItem("ng", "另一个旧别名")
        ))
        assertEquals(listOf("NG00Y"), rows.map { it.s })
        assertEquals("规范记录", rows.single().t)
    }

    @Test
    fun favoriteDistanceKeysCanonicalizeLegacySymbolSegment() {
        assertEquals("cmdty|NG00Y|M|20", FavDist.canonicalKey("cmdty|NG=F|M|20"))
        assertEquals("cmdty|NG00Y|M|20", FavDist.key("NATGAS", "M", 20))
    }
}
