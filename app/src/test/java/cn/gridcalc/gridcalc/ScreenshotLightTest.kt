package cn.gridcalc.gridcalc

import android.view.View
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

/**
 * 闸 2 浅色基线：elev_1/2/3 有真阴影，最容易在「圆角 + 抬升」处漂。
 *
 *   ./gradlew recordPaparazziDebug   改完视觉后主动录基线
 *   ./gradlew verifyPaparazziDebug   收工 / CI 校验，红了就是有差异
 *
 * 两条边界（写在这里，别删）：
 *  1. 这里渲的是 **XML 静态布局**。Kotlin 运行期才填进去的东西（自选列表行、
 *     行情蜡烛图、计算结果）不会出现 ⟹ 它看住的是「布局 + drawable + token + 间距」，
 *     看不住「数据填充后的样子」。数据态归闸 3（真机截图）。
 *  2. 状态栏 / 导航栏不渲（showSystemUi = false）—— 那部分归闸 3。
 */
class ScreenshotLightTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = ShotDevice.config,
        theme = "Theme.GridCalc.Light",
        showSystemUi = false,
        maxPercentDifference = 0.1,
    )

    @Test fun main() = paparazzi.snapshot(paparazzi.inflate<View>(R.layout.activity_main))
    @Test fun fav() = paparazzi.snapshot(paparazzi.inflate<View>(R.layout.page_fav))
    @Test fun market() = paparazzi.snapshot(paparazzi.inflate<View>(R.layout.page_mkt))
    @Test fun calc() = paparazzi.snapshot(paparazzi.inflate<View>(R.layout.page_calc))
    @Test fun settings() = paparazzi.snapshot(paparazzi.inflate<View>(R.layout.page_settings))
}
