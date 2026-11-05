package cn.gridcalc.gridcalc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression fixtures for the single VPVR support producer. */
class MktProfileTest {
    /**
     * One zero-volume sentinel fixes the profile range to [1, 25].  The remaining
     * bars each sit in one exact row, so `volumeByRow` is deterministic.
     *
     * Rows 23..19 are 100, 70, 40, 30, 20. Row 21 first crosses half-volume;
     * the walk goes 21 -> 20 -> 19 (still lower) and row 18 (60) is a >10% rise,
     * so the support is row 19, not the first hit.
     */
    private fun valleyFixture(): List<KLine> {
        val volumeByRow = listOf(
            1.0, 3.0, 2.0, 5.0, 3.0, 2.0, 1.0, 20.0,
            8.0, 6.0, 3.0, 2.0, 1.0, 5.0, 4.0, 3.0,
            2.0, 1.0, 60.0, 20.0, 30.0, 40.0, 70.0, 100.0
        )
        val sentinel = KLine(
            t = -1L, o = 1.5, h = 25.0, l = 1.0, c = 1.5,
            v = 0.0, qv = 0.0
        )
        val bars = volumeByRow.mapIndexed { row, volume ->
            val center = row + 1.5
            KLine(
                t = row.toLong(),
                o = center,
                h = center + 0.1,
                l = center - 0.1,
                c = center,
                v = volume,
                qv = volume * center
            )
        }
        return listOf(sentinel) + bars
    }

    @Test
    fun halfVolumeBandFindsItsMinimumAndSupportIsCapped() {
        val profile = MktData.profileOf(valleyFixture())

        // The first half-volume hit is row 21 (price midpoint 22.5); the band
        // minimum is row 19 (price midpoint 20.5), before row 18 exits the band.
        // 2026-11-02 峰改成"第一段爬升的第一个局部高点"后，第二支撑随之落到行 12
        // （该段峰是行 13 的量 5，腰斩点 ≤2.5 落在行 12）；旧口径是 7.5。
        // ⚠ 2026-11-04 契约变更：支撑价 = 命中档的**下沿**（原为中点）。
        //   用户裁定「支撑线要取那个柱子包含的最低价格」⟹ 期望值整体减去半档（本夹具 ww = 1.0）。
        assertEquals(2, profile.sup.size)
        assertEquals(20.0, profile.sup[0], 1e-9)
        assertEquals(13.0, profile.sup[1], 1e-9)
        assertTrue(kotlin.math.abs(profile.sup[0] - 22.5) > 1e-9)
    }

    /** Cached emulator sample for Intel's monthly window shown in the report (2025-08..2026-10). */
    private fun intcMonthly15Fixture(): List<KLine> {
        val bars = listOf(
            doubleArrayOf(19.5, 26.53, 18.965, 24.35, 2840.62461),
            doubleArrayOf(23.88, 36.3, 23.68, 33.55, 2703.39582),
            doubleArrayOf(33.53, 42.48, 33.5, 39.99, 2783.04431),
            doubleArrayOf(40.25, 40.59, 32.89, 40.56, 1483.64984),
            doubleArrayOf(39.86, 44.02, 34.95, 36.9, 1627.53612),
            doubleArrayOf(37.77, 54.6, 37.76, 46.47, 2945.80551),
            doubleArrayOf(45.63, 51.49, 42.88, 45.61, 1719.65706),
            doubleArrayOf(44.2, 49.17, 40.63, 44.13, 1882.86062),
            doubleArrayOf(45.0, 95.69, 44.98, 94.48, 2876.53754),
            doubleArrayOf(93.2, 132.75, 92.61, 114.68, 2841.52235),
            doubleArrayOf(109.43, 142.35, 98.33, 139.63, 2759.76637),
            doubleArrayOf(135.0, 135.7399, 81.79, 90.2, 2573.33001),
            doubleArrayOf(88.4, 107.57, 85.14, 89.51, 2105.72197),
            doubleArrayOf(86.84, 127.44, 85.72, 120.23, 2259.00474),
            doubleArrayOf(118.73, 126.0, 63.2001, 65.76, 2526.56971)
        )
        return bars.mapIndexed { i, b ->
            KLine(t = i.toLong(), o = b[0], h = b[1], l = b[2], c = b[3], v = b[4], qv = 0.0)
        }
    }

    @Test
    fun intcMonthlyFirstSupportBelowMarketShouldBeNear5730() {
        val profile = MktData.profileOf(intcMonthly15Fixture())
        val below = profile.sup.filter { it < profile.ks.last().c }.take(2)
        assertTrue("INTC M15 supports=${profile.sup}", below.isNotEmpty())
        assertEquals(
            "INTC M15 first support below close; supports=${profile.sup}",
            // 2026-11-04：档位下沿口径（原中点 57.3 减半档 ww/2 ≈ 2.57）
            54.95, below.first(), 1.0
        )
    }

    /** Emulator cache sample for BAC monthly 20 (2025-03..2026-10). */
    private fun bacMonthly20Fixture(): List<KLine> {
        val bars = listOf(
            doubleArrayOf(46.21, 46.96, 39.18, 41.73, 936.5171),
            doubleArrayOf(41.32, 41.97, 33.065, 39.88, 1359.33407),
            doubleArrayOf(39.82, 45.14, 39.58, 44.13, 822.88254),
            doubleArrayOf(43.88, 47.67, 43.36, 47.32, 775.78375),
            doubleArrayOf(47.02, 49.305, 45.01, 47.27, 906.38974),
            doubleArrayOf(46.36, 50.9175, 44.75, 50.74, 728.38743),
            doubleArrayOf(50.22, 52.88, 48.875, 51.59, 754.23972),
            doubleArrayOf(51.3, 53.64, 48.525, 53.45, 762.97058),
            doubleArrayOf(53.26, 54.69, 50.695, 53.65, 712.12701),
            doubleArrayOf(53.5, 56.545, 52.97, 55.0, 799.39715),
            doubleArrayOf(55.03, 57.55, 51.31, 53.2, 792.43737),
            doubleArrayOf(53.2, 56.87, 49.32, 49.83, 844.4127),
            doubleArrayOf(48.78, 50.51, 46.115, 48.75, 973.41794),
            doubleArrayOf(49.7, 55.4, 48.16, 53.46, 688.85842),
            doubleArrayOf(53.69, 54.2, 49.3, 51.6, 730.89361),
            doubleArrayOf(51.31, 59.195, 50.85, 56.98, 807.37181),
            doubleArrayOf(57.13, 62.985, 56.845, 61.95, 740.37806),
            doubleArrayOf(62.26, 65.225, 61.101, 61.94, 600.78062),
            doubleArrayOf(61.97, 63.83, 54.385, 54.43, 767.18232),
            doubleArrayOf(53.91, 54.93, 49.255, 50.01, 916.99691)
        )
        return bars.mapIndexed { i, b ->
            KLine(t = i.toLong(), o = b[0], h = b[1], l = b[2], c = b[3], v = b[4], qv = 0.0)
        }
    }

    @Test
    fun bacMonthlyFirstSupportBelowMarketShouldBeNear4298() {
        val profile = MktData.profileOf(bacMonthly20Fixture())
        val below = profile.sup.filter { it < profile.ks.last().c }.take(2)
        assertTrue("BAC M20 supports=${profile.sup}", below.isNotEmpty())
        assertEquals(
            "BAC M20 first support below close; supports=${profile.sup}; lo=${profile.lo}; hi=${profile.hi}; rv=${profile.rv.joinToString()}",
            42.98, below.first(), 1.0
        )
    }

    /** Emulator cache sample for SK Hynix monthly 15 (2025-09..2026-11, Naver, USD). */
    private fun skHynixMonthly15Fixture(): List<KLine> {
        val bars = listOf(
            doubleArrayOf(194.34, 272.6, 190.97, 260.25, 77.665167),
            doubleArrayOf(262.87, 433.62, 262.87, 418.64, 78.257169),
            doubleArrayOf(417.14, 483.8, 375.21, 396.92, 99.170969),
            doubleArrayOf(405.91, 493.53, 393.18, 487.54, 66.23135),
            doubleArrayOf(487.54, 697.24, 484.55, 680.76, 97.117009),
            doubleArrayOf(651.55, 823.05, 592.39, 794.6, 77.055002),
            doubleArrayOf(765.39, 793.85, 603.62, 604.37, 101.969986),
            doubleArrayOf(662.04, 994.56, 612.61, 963.1, 87.54028),
            doubleArrayOf(1002.79, 1781.66, 998.3, 1747.21, 111.814486),
            doubleArrayOf(1721.75, 2237.0, 1389.23, 1984.62, 120.577004),
            doubleArrayOf(1995.85, 2039.29, 933.14, 1286.63, 147.738451),
            doubleArrayOf(1229.71, 1342.05, 1028.26, 1253.68, 86.965493),
            doubleArrayOf(1247.69, 1449.15, 1166.81, 1335.31, 64.693086),
            doubleArrayOf(1319.58, 1389.23, 876.98, 1041.74, 72.323857),
            doubleArrayOf(1049.97, 1121.87, 1040.24, 1108.39, 2.506114)
        )
        return bars.mapIndexed { i, b ->
            KLine(t = i.toLong(), o = b[0], h = b[1], l = b[2], c = b[3], v = b[4], qv = 0.0)
        }
    }

    /**
     * 第二支撑曾经被"底部量墙"带到底部（用户实报：应≈536，实际画到 282/318）。
     * 峰必须是第一段爬升的第一个局部高点，而不是一路遇到的更高量柱。
     */
    @Test
    fun skHynixSecondSupportStaysNearFirstNotAtProfileBottom() {
        val profile = MktData.profileOf(skHynixMonthly15Fixture())
        assertEquals("SKHX M15 supports=${profile.sup}", 2, profile.sup.size)
        // 2026-11-04：档位下沿口径（原中点 915.6 / 574.6，各减半档 ww/2 ≈ 42.6）
        assertEquals(873.0, profile.sup[0], 1.0)
        assertEquals("SKHX second support; supports=${profile.sup}", 532.0, profile.sup[1], 1.0)
        // 底部量墙那一档（约 318）绝不能再被当成第二支撑
        assertTrue("SKHX second support=${profile.sup[1]}", profile.sup[1] > 400.0)
    }

    @Test
    fun sameCollectionIsStableAndDifferentCollectionChangesSupport() {
        val fixture = valleyFixture()
        val first = MktData.profileOf(fixture)
        val repeat = MktData.profileOf(fixture)
        assertEquals(first.sup, repeat.sup)

        val changed = fixture.dropLast(3)
        val changedProfile = MktData.profileOf(changed)
        assertNotEquals(first.sup, changedProfile.sup)
    }
}
