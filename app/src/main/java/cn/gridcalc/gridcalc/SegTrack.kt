package cn.gridcalc.gridcalc

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.Button
import android.widget.LinearLayout


/**
 * 行情页周期切换轨道（周线 / 月线 / 季线）。
 *
 * ⚠⚠ 2026-11-04 用户裁定「我需要你设计一些他的动画」——**这是新设计，稿里原来零动画**
 *   （`theme.css` 的分段器只有 `:active` 改字色，没有任何 transition/animation；
 *     此前 App 里那个按下抬升是 `android:Theme.Material` 给 Button 白送的，已随本次改造移除）。
 *
 * 【为什么要把药丸从「每个按钮自己的底」改成「轨道上的一颗共享药丸」】
 *   分段器的选中态本质是**一个东西在三个位置之间移动**（同一个药丸），不是三块各自换底色。
 *   只有把它做成单一可绘制的对象，才能得到「连续移动」而不是「原地闪现」——
 *   这正是 `ui-animation` 的核心原则 *prefer continuity over teleportation*。
 *   ⚠ 做法是**自绘**（onDraw 画在子 View 之下）而不是加一个 overlay View：
 *     · 子 View 负责文字与点击，药丸只负责底，层序天然正确（底在文字下）；
 *     · 不引入额外 View 层级、不动 `mkt_tf_row` 的 LinearLayout 契约（单测按 LinearLayout 取它）。
 *
 * 【动效口径】（数值取自稿的 motion token，不另造一套）
 *   · 药丸滑移  180ms  `cubic-bezier(0.2, 0.8, 0.2, 1)`（稿 `--dur-2` / `--ease`）
 *   · 文字换色  150ms  同曲线（字重 400↔600 不可插值，靠换色与位移一起盖住跳变）
 *   · 按压反馈  按下 90ms / 抬起 140ms，缩放 0.97（稿 `--dur-1=120ms` 同量级）
 *   · 阴影      `elev_1` 的等价画法：dy=1dp、blur=3dp、rgba(16,24,40,.10)（深色无阴影）
 *   · 降低动效  系统「动画时长缩放 = 0」时 ValueAnimator 自动按比例归零 ⟹ 直接跳位，无需特判
 *
 * 【阴影为什么自己画】`elevation` 依赖 HWUI 阴影，本机模拟器（`ro.hardware.egl=emulation`）
 *   实测连 20dp 都画不出来；用 `Paint.setShadowLayer` 则在软件/硬件两种渲染器下一致可见，
 *   也正好满足稿的「浅色有 elev-1 / 深色 none」。
 */
class SegTrack @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(ctx, attrs) {

    private companion object {
        const val DUR_PILL = 180L
        const val DUR_TEXT = 150L
        const val DUR_DOWN = 90L
        const val DUR_UP = 140L
        const val PRESS_SCALE = 0.97f
    }

    private val ease = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pillPath = Path()
    private val pillRect = RectF()

    /** 药丸左边界（px，轨道坐标系）；宽度恒等于单段宽度。 */
    private var pillLeft = 0f
    private var pillWidth = 0f
    private var pendingIndex = -1
    private var havePill = false
    private var pillAnim: Animator? = null
    private val textAnims = mutableListOf<Animator>()
    private val argb = ArgbEvaluator()

    /** 无阴影（深色）时置 false；颜色由 [setPillColors] 注入，避免在本类里写死主题值。 */
    private var withShadow = true

    init {
        setWillNotDraw(false)
        orientation = HORIZONTAL
    }

    fun setPillColors(pill: Int, shadow: Int, withShadow: Boolean) {
        pillPaint.color = pill
        this.withShadow = withShadow
        if (withShadow) {
            pillPaint.setShadowLayer(shadowBlur(), 0f, shadowDy(), shadow)
        } else {
            pillPaint.clearShadowLayer()
        }
        invalidate()
    }

    private fun shadowBlur(): Float = 3f * resources.displayMetrics.density
    private fun shadowDy(): Float = 1f * resources.displayMetrics.density

    /**
     * 把药丸移到第 [index] 段。
     * @param animate false 用于首次布局（不该有入场动画：用户没有触发过任何动作）。
     */
    fun moveTo(index: Int, animate: Boolean) {
        if (index !in 0 until childCount) return
        pendingIndex = index
        val child = getChildAt(index)
        if (child.width == 0) return   // 还没布局，等 onLayout 再来一次
        val target = child.left.toFloat()
        if (!havePill) {
            pillLeft = target
            pillWidth = child.width.toFloat()
            havePill = true
            invalidate()
            return
        }
        if (!animate || pillLeft == target) {
            pillAnim?.cancel()
            pillLeft = target
            pillWidth = child.width.toFloat()
            invalidate()
            return
        }
        pillAnim?.cancel()
        val from = pillLeft
        pillAnim = ValueAnimator.ofFloat(from, target).apply {
            duration = DUR_PILL
            interpolator = ease
            addUpdateListener {
                pillLeft = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (pendingIndex >= 0) {
            val idx = pendingIndex
            pendingIndex = -1
            moveTo(idx, animate = havePill)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!havePill || pillWidth <= 0f) return
        val top = paddingTop.toFloat()
        val bottom = (height - paddingBottom).toFloat()
        if (bottom <= top) return
        pillRect.set(pillLeft, top, pillLeft + pillWidth, bottom)
        val radius = (bottom - top) / 2f
        pillPath.reset()
        pillPath.addRoundRect(pillRect, radius, radius, Path.Direction.CW)
        canvas.drawPath(pillPath, pillPaint)
    }

    /** 文字换色（150ms 交叉），字重直接切换——字重不可插值，只能靠颜色与药丸位移一起盖住跳变。 */
    fun animateTextColors(buttons: List<Button>, selected: Int, ink: Int, ink2: Int) {
        textAnims.forEach { it.cancel() }
        textAnims.clear()
        buttons.forEachIndexed { i, b ->
            val target = if (i == selected) ink else ink2
            val from = b.textColors?.defaultColor ?: ink2
            if (from == target) {
                b.setTextColor(target)
                b.setTypeface(null, if (i == selected) android.graphics.Typeface.BOLD
                else android.graphics.Typeface.NORMAL)
                return@forEachIndexed
            }
            b.setTypeface(null, if (i == selected) android.graphics.Typeface.BOLD
            else android.graphics.Typeface.NORMAL)
            val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = DUR_TEXT
                interpolator = ease
                addUpdateListener {
                    val f = it.animatedValue as Float
                    @Suppress("UNCHECKED_CAST")
                    b.setTextColor(argb.evaluate(f, from, target) as Int)
                }
                start()
            }
            textAnims += anim
        }
    }

    /** 按压反馈：只缩字面（底在轨道上，缩它会让药丸跟着抖）。 */
    fun attachPress(buttons: List<Button>) {
        buttons.forEach { b ->
            b.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> v.animate()
                        .scaleX(PRESS_SCALE).scaleY(PRESS_SCALE)
                        .setDuration(DUR_DOWN).setInterpolator(ease).start()
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> v.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(DUR_UP).setInterpolator(ease).start()
                }
                false
            }
        }
    }

    /** 可选：把动画结束后的清扫做得干净（本类目前不持有外部引用，保留钩子便于将来扩展）。 */
    fun cancelAll() {
        pillAnim?.cancel()
        textAnims.forEach { it.cancel() }
        textAnims.clear()
    }
}
