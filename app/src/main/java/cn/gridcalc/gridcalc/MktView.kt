package cn.gridcalc.gridcalc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ---------- 行情 VPVR · 绘制层 ----------
// 照搬设计稿 renderMK:K线+右侧分布柱(涨#2962FF跌#FF6D00,VA内外透明度,
// 低价在下)+POC绿线+价格轴6档+时间轴+十字(横吸K线纵自由,价签反算价)。
// 图已锁定:无缩放/复位/平移手势,可见窗口恒为全量(MK.view=null常态),
// 只由图上方输入(品种+周期+K数)决定;单指拖动仅移动十字,
// 纵向滚动交还父ScrollView。

data class MktInfo(
    val ohlc: String, val chgUp: Boolean,
    val legRow: String, val poc: Double,
    val vah: Double, val val_: Double, val count: Int
)

class MktView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var all: List<KLine> = emptyList()
    private var crossPx: Float? = null
    private var crossPy: Float? = null

    var onInfo: ((MktInfo) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun attrColor(name: String): Int {
        val id = resources.getIdentifier(name, "attr", context.packageName)
        val tv = TypedValue()
        context.theme.resolveAttribute(id, tv, true)
        return tv.data
    }

    fun setData(ks: List<KLine>) {
        all = ks
        crossPx = null
        crossPy = null
        redraw()
    }

    fun clear() {
        all = emptyList()
        crossPx = null
        crossPy = null
        invalidate()
    }

    private fun redraw() {
        invalidate()
    }

    // 窗口恒为全量(MK.view=null常态),只由上方输入决定
    private fun visible(): List<KLine> = all

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (e.pointerCount == 1) {
                    crossPx = e.x
                    crossPy = e.y
                    redraw()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount == 1 && crossPx != null) {
                    crossPx = e.x
                    crossPy = e.y
                    redraw()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                crossPx = null
                crossPy = null
                redraw()
            }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val ks = visible()
        if (ks.isEmpty()) return
        val vp = MktData.profileOf(ks)

        val W = width.toFloat()
        val H = height.toFloat()
        val m = dp(8f)
        val axisW = dp(56f)
        val axH = dp(20f)
        val plotH = H - m - axH
        if (plotH <= 0) return

        val grid = attrColor("colorBorder")
        val mut = attrColor("colorSub")
        val upC = attrColor("colorTeal")
        val dnC = attrColor("colorRed")
        val mono = Typeface.MONOSPACE

        fun y(p: Double): Float {
            val span = if (vp.hi - vp.lo == 0.0) 1.0 else vp.hi - vp.lo
            return (m + plotH - (p - vp.lo) / span * plotH).toFloat()
        }

        // 价格轴6档+网格线
        paint.style = Paint.Style.FILL
        paint.typeface = mono
        paint.textSize = dp(9f)
        paint.textAlign = Paint.Align.RIGHT
        for (i in 0..5) {
            val p = vp.lo + (vp.hi - vp.lo) * i / 5
            val yy = y(p)
            paint.color = grid
            paint.alpha = 153
            paint.strokeWidth = 1f
            c.drawLine(m, yy, m + chartW(W, m, axisW) + m + profW(W, m, axisW), yy, paint)
            paint.alpha = 255
            paint.color = mut
            c.drawText(MktData.mkFmt(p), W - dp(6f), yy - dp(1f), paint)
        }

        val n = ks.size
        val cw = chartW(W, m, axisW) / n
        val bw = max(1f, cw * 0.6f)

        // K线
        ks.forEachIndexed { i, k ->
            val cx = m + i * cw + cw / 2
            val col = if (k.c >= k.o) upC else dnC
            paint.color = col
            paint.strokeWidth = max(1f, cw * 0.15f)
            c.drawLine(cx, y(k.h), cx, y(k.l), paint)
            val yO = y(k.o)
            val yC = y(k.c)
            paint.style = Paint.Style.FILL
            c.drawRect(cx - bw / 2, min(yO, yC), cx + bw / 2,
                min(yO, yC) + max(1f, abs(yC - yO)), paint)
        }

        // 右侧分布柱(低价在下:行0画在最下)
        val rows = vp.rv.size
        val rh = plotH / rows
        val mx = vp.rv.maxOrNull() ?: 0.0
        val profX = m + chartW(W, m, axisW) + m
        val pw = profW(W, m, axisW)
        vp.rv.forEachIndexed { i, v ->
            val yy = m + (rows - 1 - i) * rh
            val inVA = i >= vp.vaDn && i <= vp.vaUp
            val uw = if (mx > 0) (vp.ru[i] / mx * pw).toFloat() else 0f
            val dw = if (mx > 0) (vp.rd[i] / mx * pw).toFloat() else 0f
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#2962FF")
            paint.alpha = if (inVA) 178 else 71
            c.drawRect(profX, yy + 1, profX + max(if (uw > 0) 1f else 0f, uw),
                yy + 1 + max(1f, rh - 2), paint)
            paint.color = Color.parseColor("#FF6D00")
            paint.alpha = if (inVA) 178 else 71
            c.drawRect(profX + uw, yy + 1, profX + uw + max(if (dw > 0) 1f else 0f, dw),
                yy + 1 + max(1f, rh - 2), paint)
        }
        paint.alpha = 255

        // POC绿线
        val py = y(vp.poc)
        paint.color = Color.parseColor("#3DDC84")
        paint.strokeWidth = dp(2f)
        c.drawLine(m, py, m + chartW(W, m, axisW) + m + profW(W, m, axisW), py, paint)

        // 时间轴5档
        paint.color = mut
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = dp(9f)
        for (i in 0..4) {
            val idx = min(n - 1, (i * (n - 1) / 4.0).roundToInt())
            c.drawText(MktData.fmtMD(ks[idx].t), m + idx * cw + cw / 2, H - dp(6f), paint)
        }

        // 十字:横吸K线,纵自由
        val cx0 = crossPx
        val cxi = if (cx0 == null) n - 1
        else max(0, min(n - 1, ((cx0 - m - cw / 2) / cw).roundToInt()))
        val info = ks[cxi]
        var legRow = ""
        if (cx0 != null && crossPy != null) {
            val px = m + cxi * cw + cw / 2
            val pyy = max(m, min(m + plotH, crossPy!!))
            val ri = max(0, min(rows - 1, rows - 1 - ((pyy - m) / rh).toInt()))
            val pw2 = (vp.hi - vp.lo) / rows
            legRow = "行 " + MktData.mkFmt(vp.lo + pw2 * ri) + "~" +
                MktData.mkFmt(vp.lo + pw2 * (ri + 1)) +
                " · 量 " + MktData.mkVol(vp.rv[ri])
            dashPaint.color = mut
            dashPaint.strokeWidth = 1f
            dashPaint.pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(3f)), 0f)
            c.drawLine(px, m, px, m + plotH, dashPaint)
            c.drawLine(m, pyy, W - m, pyy, dashPaint)
            // 价签反算价
            paint.textSize = dp(10f)
            paint.textAlign = Paint.Align.LEFT
            val pv = vp.lo + (m + plotH - pyy) / plotH * (vp.hi - vp.lo)
            val label = MktData.mkFmt(pv)
            val tw = paint.measureText(label) + dp(10f)
            paint.color = mut
            val tx = W - m - tw
            val ty = pyy - dp(9f)
            c.drawRoundRect(RectF(tx, ty, tx + tw, ty + dp(18f)), dp(4f), dp(4f), paint)
            paint.color = attrColor("colorCard")
            c.drawText(label, tx + dp(5f), ty + dp(13f), paint)
        }

        val chg = (info.c - info.o) / info.o * 100
        val ohlc = MktData.fmtDT(info.t) + " 开 " + MktData.mkFmt(info.o) +
            " 高 " + MktData.mkFmt(info.h) + " 低 " + MktData.mkFmt(info.l) +
            " 收 " + MktData.mkFmt(info.c)
        onInfo?.invoke(MktInfo(ohlc, chg >= 0, legRow, vp.poc, vp.vah, vp.val_, n))
    }

    private fun chartW(W: Float, m: Float, axisW: Float): Float {
        val profW = ((W - m - axisW) * 0.32f).roundToInt().toFloat()
        return W - m - profW - m - axisW
    }

    private fun profW(W: Float, m: Float, axisW: Float): Float {
        return ((W - m - axisW) * 0.32f).roundToInt().toFloat()
    }
}
