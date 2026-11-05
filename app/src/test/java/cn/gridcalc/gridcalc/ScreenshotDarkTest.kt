package cn.gridcalc.gridcalc

import android.view.View
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

/**
 * 闸 2 深色基线：elev 全 0dp，层次靠色调阶梯 —— 「以为有阴影」的地方最会翻车，
 * 必须单独核一遍（深色不是浅色的滤镜）。
 */
class ScreenshotDarkTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = ShotDevice.config,
        theme = "Theme.GridCalc.Dark",
        showSystemUi = false,
        maxPercentDifference = 0.1,
    )

    @Test fun main() = paparazzi.snapshot(paparazzi.inflate<View>(R.layout.activity_main))
    @Test fun fav() = paparazzi.snapshot(paparazzi.inflate<View>(R.layout.page_fav))
    @Test fun market() = paparazzi.snapshot(paparazzi.inflate<View>(R.layout.page_mkt))
    @Test fun calc() = paparazzi.snapshot(paparazzi.inflate<View>(R.layout.page_calc))
    @Test fun settings() = paparazzi.snapshot(paparazzi.inflate<View>(R.layout.page_settings))
}
