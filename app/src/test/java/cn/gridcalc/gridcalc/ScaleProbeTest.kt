package cn.gridcalc.gridcalc

import android.view.View
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

/**
 * 口径探针：量出 Paparazzi 本次的 dp → px 实际比例。
 *
 * 这个比例**必须**与真机一致（1080×2400 @440dpi = 2.75×），否则：
 *  ① Paparazzi 的 golden 与真机截图不能逐像素比（差一个缩放因子）；
 *  ② 「App 跑在什么屏上」这件事在测试环境里是失真的。
 *
 * 量法：探针布局里有一块 100dp×100dp 的纯红方块，量出图里红色像素的宽度 ÷ 100。
 * 配套脚本：toolchain/paparazzi/scale_probe.py
 */
class ScaleProbeTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = ShotDevice.config,
        theme = "Theme.GridCalc.Light",
        showSystemUi = false,
    )

    @Test fun probe() {
        val dm = paparazzi.context.resources.displayMetrics
        println("[probe] density=${dm.density} widthPx=${dm.widthPixels} heightPx=${dm.heightPixels} scaledDensity=${dm.scaledDensity}")
        paparazzi.snapshot(paparazzi.inflate<View>(R.layout.zz_scale_probe))
    }
}
