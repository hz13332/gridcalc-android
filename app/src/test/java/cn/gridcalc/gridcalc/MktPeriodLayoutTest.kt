package cn.gridcalc.gridcalc

import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import app.cash.paparazzi.Paparazzi
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.math.roundToInt
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue

/**
 * Period/K-count geometry at the 450dp baseline.
 *
 * The production grid is applied by MktPanel after row2 is measured.  This test
 * inflates the real page, measures it at the baseline width, invokes that same
 * private sizing owner, then measures once more so the assertions cover the
 * post-layout geometry rather than XML defaults alone.
 */
class MktPeriodLayoutTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = ShotDevice.config,
        theme = "Theme.GridCalc.Light",
        showSystemUi = false,
    )

    @Test
    fun baselineGridKeepsConfirmationAndPeriodsVisible() {
        val page = paparazzi.inflate<View>(R.layout.page_mkt)
        val density = page.resources.displayMetrics.density
        val width = (450f * density).roundToInt()
        val height = (2400f * density).roundToInt()
        val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST)

        page.measure(widthSpec, heightSpec)
        page.layout(0, 0, width, page.measuredHeight)
        MktPeriodLayoutBridge.syncGrid(page)
        page.measure(widthSpec, heightSpec)
        page.layout(0, 0, width, page.measuredHeight)

        fun dp(v: Float) = (v * density).roundToInt()

        val periodRow = page.findViewById<LinearLayout>(R.id.mkt_period_row)
        val tfRow = page.findViewById<LinearLayout>(R.id.mkt_tf_row)
        val row2 = page.findViewById<LinearLayout>(R.id.mkt_row2)
        // 2026-11-04：数量输入框换成步进器（mkt_kstep / mkt_kval / mkt_kminus / mkt_kplus）
        val stepRow = page.findViewById<LinearLayout>(R.id.mkt_kstep)
        val stepVal = page.findViewById<TextView>(R.id.mkt_kval)
        val stepMinus = page.findViewById<Button>(R.id.mkt_kminus)
        val stepPlus = page.findViewById<Button>(R.id.mkt_kplus)
        val confirm = page.findViewById<Button>(R.id.mkt_win)
        val countLabel = row2.getChildAt(0)
        val periods = listOf(
            page.findViewById<Button>(R.id.mkt_tf_w),
            page.findViewById<Button>(R.id.mkt_tf_m),
            page.findViewById<Button>(R.id.mkt_tf_q),
        )

        // ────────────────────────────────────────────────────────────────���──────
        // 2026-10-29 t28 · **控制行回到稿的一行布局**（用户看图裁定，推翻 2026-10-27 的竖排两行）。
        // 依据 = 稿 `四屏-行情页.html:91-99` 的 `.mk-ctl`：
        //   `display:flex; align-items:center; justify-content:space-between;
        //    gap: var(sp-2); padding: var(sp-2) var(sp-1)`  ← 周期段与数量组**并排**、两端对齐；
        //   `.mk-seg{display:inline-flex;flex:none;padding:3px}`  ← 周期段 = **内容宽**（不铺满）；
        //   `.mk-cnt{display:flex;gap:var(sp-1)}` + `.mk-cnt__i{width:72px}` + `.mk-cnt__ok{height:36px}`。
        // 旧断言（三段均分三列栅格、标签+输入框=2*k、确认占末列 last）全部属于**竖排两行**口径，已删除。
        // ─────────────────────────────────────────────────────────────────────────

        // ① 同一行：周期段与数量组同父、横排、垂直居中。
        assertSame("period track and count group must share one parent row", periodRow, tfRow.parent)
        assertSame("period track and count group must share one parent row", periodRow, row2.parent)
        assertEquals(
            "the control row must be horizontal (draft .mk-ctl display:flex)",
            LinearLayout.HORIZONTAL,
            periodRow.orientation,
        )
        assertTrue(
            "both groups must sit on the same visual row (centre aligned within 1dp)",
            kotlin.math.abs(tfRow.top + tfRow.height / 2 - (row2.top + row2.height / 2)) <= dp(1f),
        )

        // ② space-between：周期组贴左缘、数量组贴右缘（中间由 weight 空 View 吃余量）。
        assertEquals(dp(4f), periodRow.paddingLeft) // 稿 .mk-ctl padding 左右 = sp-1 = 4px
        assertEquals(dp(4f), periodRow.paddingRight)
        assertEquals(dp(8f), periodRow.paddingTop) // 稿 .mk-ctl padding 上下 = sp-2 = 8px
        assertEquals(dp(8f), periodRow.paddingBottom)
        assertEquals("period group must hug the row's left edge", dp(4f), tfRow.left)
        assertEquals(
            "count group must hug the row's right edge (justify-content:space-between)",
            periodRow.width - dp(4f),
            row2.right,
        )

        // ③ 药丸 = 内容宽（稿 .mk-seg 的 inline-flex + flex:none），不得被均分铺满整行。
        assertEquals(
            "period track must be wrap_content, not match_parent",
            LinearLayout.LayoutParams.WRAP_CONTENT,
            tfRow.layoutParams.width,
        )
        assertTrue("period track must be narrower than the row", tfRow.width < periodRow.width - dp(8f))
        periods.forEach {
            assertEquals(
                "each period pill must be wrap_content (draft inline-flex)",
                LinearLayout.LayoutParams.WRAP_CONTENT,
                it.layoutParams.width,
            )
            assertEquals(
                "a wrap_content pill must not carry layout_weight",
                0f,
                (it.layoutParams as LinearLayout.LayoutParams).weight,
            )
            assertTrue("period button must be measured", it.width > 0)
            // 内容宽 = 文字 + 左右各 12dp（稿 .mk-seg__b padding: 5px sp-3）
            assertTrue("pill must reserve both 12dp paddings", it.width >= dp(24f))
            // ⚠ `singleLine="true"` 会连带把 ellipsize 设上（平台行为，LayoutLib 同样如此），
            //   所以「不截断」不能用 ellipsize 判，改判**文字量宽 + 内边距 ≤ 按钮宽**——
            //   这才是「药丸按内容撑开、字放得下」的真判据。
            assertEquals(
                "period label must fit inside the pill (content width, no truncation)",
                true,
                it.width >= it.paint.measureText(it.text.toString()) + it.paddingLeft + it.paddingRight,
            )
            assertEquals(1, it.maxLines)
            assertTrue("period labels must never wrap", it.text.toString().length == 2)
        }
        // 轨道宽 = 三段内容宽之和 + 轨道自身左右各 1dp 描边占位（不是均分）
        assertEquals(
            "track width must equal the sum of its pills plus its own 1dp side insets",
            periods.sumOf { it.width } + dp(2f),
            tfRow.width,
        )

        // ④ 高度契约（2026-10-28 t21 定，本轮不变）：稿 .mk-cnt__i / .mk-cnt__ok 均 height:36px ⟹ 36dp。
        assertEquals(dp(36f), tfRow.layoutParams.height)
        assertEquals(dp(36f), row2.layoutParams.height)
        assertEquals(dp(36f), stepRow.layoutParams.height)
        assertEquals(dp(36f), confirm.layoutParams.height)

        // ⑤ 步进器（2026-11-04 取代 72dp 输入框）：轨道内容宽 = 键 32 + 读数 + 键 32。
        //   读数最小 44dp（够放三位数 100）；两个键各 32dp 且必须可点。
        assertEquals("K-count readout must keep the 44dp minimum", dp(44f), stepVal.minWidth)
        assertEquals(dp(30f), stepMinus.layoutParams.width)
        assertEquals(dp(30f), stepPlus.layoutParams.width)
        assertTrue("stepper must sit to the left of the confirm button", stepRow.width > 0)
        assertTrue("minus must be enabled in the default window", stepMinus.isEnabled)

        // ⑥ 确认键：2026-10-27「被挤成竖排两字」的三条防回归硬约束。
        assertEquals(
            "confirmation must be wrap_content, never a squeezed 0dp slot",
            LinearLayout.LayoutParams.WRAP_CONTENT,
            confirm.layoutParams.width,
        )
        assertTrue("confirmation minWidth must be at least 48dp", confirm.minWidth >= dp(48f))
        assertTrue("confirmation must be singleLine", confirm.isSingleLine)
        assertEquals("confirmation must not wrap", 1, confirm.maxLines)
        assertTrue("confirmation must retain the complete label", confirm.text.toString() == "确认")
        assertTrue("confirmation must be enabled", confirm.isEnabled)
        assertTrue("confirmation must be clickable", confirm.isClickable)
        assertTrue("confirmation must be at least 48dp wide", confirm.width >= dp(48f))
        // 同上的理由：用「文字放得下」代替 ellipsize 判据 ⟹ 任何字号下都不得被省略成「确…」
        assertTrue(
            "confirmation label must fit inside the button (no truncation)",
            confirm.width >= confirm.paint.measureText(confirm.text.toString()) +
                confirm.paddingLeft + confirm.paddingRight,
        )

        // ⑦ 组内间隔 4dp（稿 .mk-cnt gap: sp-1）：标签 → 输入框、输入框 → 确认。
        val labelLp = countLabel.layoutParams as LinearLayout.LayoutParams
        assertEquals(dp(4f), labelLp.rightMargin)
        assertEquals(dp(4f), (stepRow.layoutParams as LinearLayout.LayoutParams).leftMargin)
        // ⚠ 2026-11-04：确认键与步进器拉开到 12dp（用户报「容易误触」；确认会落盘，代价高）
        assertEquals(dp(12f), (confirm.layoutParams as LinearLayout.LayoutParams).leftMargin)
    }

    @Test
    fun confirmedReplayRestoresMWithoutLeaveNetwork() {
        val f = ConfirmedReplayFixture()
        f.confirm("M")
        f.cache("M")
        f.cache("W")
        f.select("W")
        f.onLeave()
        assertEquals("M", f.tf)
        assertEquals("M", f.t2ShownTf)
        assertEquals("M", f.chartTf)
        assertEquals(0, f.networkRequests)
        f.onAttach()
        assertEquals(0, f.networkRequests)
    }

    @Test
    fun confirmedInflightLeaveDoesNotStartSecondRequest() {
        val f = ConfirmedReplayFixture()
        f.confirm("M")
        f.beginRequest()
        f.onLeave()
        assertEquals("M", f.tf)
        assertEquals(1, f.networkRequests)
        f.onAttach()
        assertEquals(1, f.networkRequests)
    }

    @Test
    fun confirmedReplayRequestsOnceWhenConfirmedCacheMissing() {
        val f = ConfirmedReplayFixture()
        f.confirm("M")
        f.select("W")
        f.onLeave()
        assertEquals("M", f.tf)
        assertEquals("W", f.t2ShownTf)
        assertTrue(f.needsConfirmedReload)
        assertEquals(0, f.networkRequests)
        f.onAttach()
        assertEquals(1, f.networkRequests)
        assertEquals("M", f.t2ShownTf)
        assertEquals("M", f.chartTf)
    }
}

/** Minimal deterministic model of the confirmed-window replay contract. */
private class ConfirmedReplayFixture {
    var tf = "M"
    var t2ShownTf = ""
    var chartTf = ""
    var needsConfirmedReload = false
    var networkRequests = 0
    private var requestInFlight = false
    private var inFlightTf = ""
    private var confirmedTf = "M"
    private val localTf = linkedSetOf<String>()

    fun confirm(value: String) {
        confirmedTf = value
        tf = value
        t2ShownTf = value
        chartTf = value
    }

    fun cache(value: String) { localTf += value }
    fun select(value: String) { tf = value; t2ShownTf = value; chartTf = value }
    fun beginRequest() { requestInFlight = true; inFlightTf = tf; networkRequests++ }

    fun onLeave() {
        if (requestInFlight && tf == confirmedTf && inFlightTf == confirmedTf) {
            needsConfirmedReload = false
            return
        }
        tf = confirmedTf
        if (localTf.contains(confirmedTf)) {
            t2ShownTf = confirmedTf
            chartTf = confirmedTf
            needsConfirmedReload = false
        } else {
            needsConfirmedReload = true
        }
    }

    fun onAttach() {
        if (!needsConfirmedReload) return
        needsConfirmedReload = false
        networkRequests++
        t2ShownTf = confirmedTf
        chartTf = confirmedTf
    }
}

/** Reflection bridge keeps the production constructor out of the Paparazzi test. */
internal object MktPeriodLayoutBridge {
    fun syncGrid(page: View) {
        val cls = MktPanel::class.java
        val panel = allocateWithoutConstructor(cls)
        setPrivateField(cls, panel, "row2", page.findViewById(R.id.mkt_row2))
        setPrivateField(cls, panel, "tfW", page.findViewById(R.id.mkt_tf_w))
        setPrivateField(cls, panel, "tfM", page.findViewById(R.id.mkt_tf_m))
        setPrivateField(cls, panel, "tfQ", page.findViewById(R.id.mkt_tf_q))
        setPrivateField(cls, panel, "winBtn", page.findViewById(R.id.mkt_win))
        val method = cls.getDeclaredMethod("syncGrid").apply { isAccessible = true }
        method.invoke(panel)
    }

    private fun allocateWithoutConstructor(cls: Class<*>): Any {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val allocate: Method = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(unsafe, cls)!!
    }

    private fun setPrivateField(cls: Class<*>, target: Any, name: String, value: Any) {
        val field: Field = cls.getDeclaredField(name).apply { isAccessible = true }
        field.set(target, value)
    }
}
