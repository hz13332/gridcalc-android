package cn.gridcalc.gridcalc

import app.cash.paparazzi.DeviceConfig
import com.android.resources.Density

/**
 * 闸 2（Paparazzi 截图回归）的设备口径。
 *
 * ⚠ **Paparazzi 1.3.4 的渲染密度只认标准档**：`Density` 枚举（120/160/213/240/320/480），
 * 它的值决定 dp → px 的比例（= 档位 dpi ÷ 160），**不是** `xdpi/ydpi`。
 * 后果：真机 1080×2400 @440dpi = 2.75×，Paparazzi 只能用 XXHIGH(480) = **3.0×** 或
 * XHIGH(320) = 2.0×。我们取 XXHIGH（Pixel 档，最接近真机）。
 *
 * ⟹ 由此产生的口径差（3.0× vs 2.75×，约 9%）登记为残差 **R-9**：
 *   - Paparazzi 图**只能**用来看「相对变化」（改了没有），**不能**与真机截图逐像素比；
 *   - 逐像素比对的闸仍是闸 3（真机 adb 截图 ↔ golden，dpr 2.75）。
 *   - 每次升级 Paparazzi 都要重跑口径探针（`ScaleProbeTest` + `scale_probe.py`）确认比例没变。
 *
 * 详见 `design/稿到应用一致性-方法包/41_白名单与裁定记录.md` R-9。
 */
object ShotDevice {
    val config = DeviceConfig(
        screenWidth = 1080,
        screenHeight = 2400,
        xdpi = 440,               // 元数据（真机实际 dpi）；不参与缩放计算
        ydpi = 440,
        density = Density.XXHIGH, // 480dpi = 3.0×（Paparazzi 唯一能给到的近档）
        softButtons = false,
    )
}
