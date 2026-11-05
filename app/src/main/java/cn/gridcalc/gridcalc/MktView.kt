package cn.gridcalc.gridcalc

import android.content.Context
import android.content.res.Configuration
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
// 照稿 `方向-B/四屏-行情页.html` 的 Candles + `方向-B/theme.css` 的 .ck* / .mk-prof* / .mk-line* 画
// （**2026-10-28 t19 按当前稿逐条重述**；此前的转述已过期，见下面两条纠正）：
//   K线(影线+实体 · **涨绿跌红** theme.css:698-701 的 `--up`/`--down`，本盘 colorTeal/colorRed)、
//   **第一支撑线 = 绿**（theme.css:702 `.mk-line--sup1{stroke:var(--up)}`，用户裁定）、
//   **第二支撑线 = 红**（theme.css:703 `.mk-line--sup2{stroke:var(--down)}`，同为用户裁定；
//     两条都是实线、stroke-width 1.5、**都没有标签片** —— 标签已按 2026-10-03 用户裁定删除）、
//   右侧 VPVR 剖面(低价在下 · **命中档 = `--accent-fill`**，theme.css:697；本盘 theme attr **colorAccentFill**：
//     浅 #2A5BD7 / 深 #3D6BE0 —— 深色下**不是**当文字/图标用的 `--accent` #8FB2FF，那个偏亮)、
//   价格轴**2 个**刻度(hi/lo)+ **3 条**网格线、现价虚线(第三档墨色, theme.css:704)。
// ⚠ 2026-10-28（t19）纠正的两条过期口径 —— 本次着色问题的成因就是它们：
//   ① 「支撑线 = 决策紫(本盘 colorPrimary)」：那是 **t107 时代的稿**。当前稿 theme.css:702-703 明写
//      第一支撑绿 / 第二支撑红（用户裁定）⟹ 紫线口径**已随稿变更失效**，别再改回去。
//   ② 「命中柱与支撑线统一走 colorPrimary」：当前稿 theme.css:697 命中柱是 `--accent-fill`。浅色下两值
//      恰好相同(#2A5BD7)，**只有深色能看出差别**（稿 #3D6BE0 vs colorPrimary #8FB2FF 偏亮）。
// ⚠ 更早那一版稿的三处相反描述同样作废：涨跌色写成蓝橙（与「涨绿跌红」硬约束正好相反）、刻度写成 6 个。
// ⚠ 时间轴与十字是 **App 自有**,稿的 SVG 里没有(audit_v5 #74「稿未画」):
//   时间轴按可用宽算间隔;十字横吸 K 线、纵自由,价签反算价。
// 图已锁定:无缩放/复位/平移手势,可见窗口恒为全量(MK.view=null常态),
// 只由图上方输入(品种+周期+K数)决定;单指拖动仅移动十字,
// 纵向滚动交还父ScrollView。
// v3.3:只留现价之下支撑(supShow);POC/VA只算不显示。
// 2026-10-01 F-3 起**画前两条**支撑线。⚠ 这句当年写的「第一条=决策紫实线(带底片)、第二条=第三档墨色
//   虚线 6 4(无底片)」三段都已失效：底片与标签按 2026-10-03 用户裁定整块删除、线型同日裁定改实线、
//   颜色按当前稿改绿/红（见上）。只作里程碑保留，别照它的配色/线型去改代码。

data class MktInfo(
    val ohlc: String, val chgUp: Boolean,
    val legRow: String, val poc: Double,
    val vah: Double, val val_: Double, val count: Int,
    val supShow: List<Double>,
    // t106 ③⑤⑥:把最后一根 K 线的原始 OHLC/成交量/时刻带给 UI 层,
    // 否则 UI 只能去切 ohlc 那个拼好的字符串(脆)。引擎/取数都没动,只是把已有的值多带一份。
    val o: Double = 0.0, val h: Double = 0.0,
    val l: Double = 0.0, val c: Double = 0.0,
    val vol: Double = 0.0, val tEnd: Long = 0L
    /*
     * ⚠ 墓碑 · 2026-10-12 —— 这里原是 `hidden: Int`（「被尺度带剔除、没上屏的根数」），
     *   原样透传 Profile.hidden。尺度带已按用户裁定选 A 删除（裁定原文见
     *   MktData.profileOf 的墓碑注释），被藏的根数恒为 0，整条 Profile.hidden →
     *   本字段 → MktPanel 状态行拼接链**全部删除**，不留死代码。
     */
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

    // 2026-10-02 全局换 Inter。Canvas 直接画的字**不吃**主题的 android:fontFamily,
    // 图表刻度/价签/支撑标签必须自己解析字体,否则会留在 Roboto 上,与屏上其余文字割裂。
    // 这几处原先用 Typeface.DEFAULT / DEFAULT_BOLD / MONOSPACE,现在全部指向 res/font/inter.xml。
    private val interRegular: Typeface by lazy { resources.getFont(R.font.inter_regular) }
    private val interBold: Typeface by lazy { resources.getFont(R.font.inter_bold) }

    init {
        // tnum(等宽数字)对齐设计稿 v2.html:10 的 .num{font-variant-numeric:tabular-nums}。
        // 刻度与价签是按列排的数字,比例数字会让它们左右跳动;Inter 自带 tnum,直接开。
        // ⚠ 这是**字形特性**,不改变字宽以外的任何度量 —— 与 includeFontPadding 无关。
        paint.fontFeatureSettings = "tnum"
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun attrColor(name: String): Int {
        val id = resources.getIdentifier(name, "attr", context.packageName)
        val tv = TypedValue()
        context.theme.resolveAttribute(id, tv, true)
        return tv.data
    }

    // ══════════════════════════════════════════════════════════════════════════
    // t12(2026-10-02)【用户裁定「完全照稿子改」】支撑线标签的**文字色**改按 onFill 取。
    // ⚠⚠⚠ **改这里之前,先读这段。** 本函数与
    //   `MainActivity.kt:340 draftOnFill()`(+ :290 loadMode() / :305 resolve())
    //   是**同一个口径的两份拷贝** —— 改一处**必须**同步另一处,否则同一屏里
    //   设置屏/计算屏/行情屏会在不同主题下给出不同的压紫底文字色。
    //
    // 为什么必须是两份:MktView 是个 View,手上只有 Context,拿不到 MainActivity 实例,
    //   而 draftOnFill() 是 MainActivity 的 private 成员。**所以只能照抄,不能调用。**
    //   ⟹ 这正是「同源」二字的实际含义:**同一套输入、同一套分支、同一个取值**,
    //     不是「碰巧结果一样」。加第三套主题时两边都要改。
    //
    // 为什么文字要按 onFill 而不是照搬 colorOnPrimary:
    //   稿 THEMES.dark 的 `onFill` 是 oklch(0.160 0.012 265) = **#0B0D13**(v2.html:99),
    //   而 light 的 `onFill` 是 '#fff'(v2.html:88) —— 稿本来就把「压在色块上的文字色」
    //   **按主题分成两个值**,不是单一 token。本盘两套 colorPrimary 偏亮/偏深不同:
    //     · 浅色 ac #6749B6 压 #FFFFFF = 6.55:1(达标)
    //     · 深色 ac #987DEF 压 #FFFFFF = **3.22:1,不过 AA 4.5**;换 #0B0D13 = **6.03:1**
    //   ⟹ 深色下白字是**恰好选错了方向**,与 MainActivity:325-327 的判断同一条。
    //
    // ⚠⚠ **绝不能改 colorOnPrimary 这个 token**:它是共享的,实测全库 9 处活引用
    //   (res/color/dbl_thumb.xml:5 · page_settings.xml:114 · page_calc.xml:1184 ·
    //    panel_clear.xml:94 · page_mkt.xml:173 · MktView.kt(本处) ·
    //    MktPanel.kt:246 · MktPanel.kt:813 · MainActivity.paintSide)。
    //   那些位置语境就是「白字压紫底」,改成 per-theme 会把浅色那几处一起弄坏。
    //   **所以按稿只在需要 onFill 的绘制处局部取** —— 标签【底片】色不在此列,
    //   稿 L1841 的 `--ac` 实底是对的,一行都没动。
    // ══════════════════════════════════════════════════════════════════════════
    private fun onFill(): Int {
        // ↓↓ 与 MainActivity.kt:290 loadMode() 逐字同源(SP 名 "gridcalc"、键 "theme")
        val mode = try {
            context.getSharedPreferences("gridcalc", Context.MODE_PRIVATE)
                .getString("theme", "follow")
        } catch (_: Exception) {
            null
        } ?: "follow"
        val eff = when (mode) {
            "dark" -> "dark"
            "light" -> "light"
            // ↓↓ 与 MainActivity.kt:299-309 systemDark()/resolve() 逐字同源
            else -> if ((resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
        }
        return if (eff == "dark") 0xFF0B0D13.toInt() else attrColor("colorOnPrimary")
    }

    /**
     * #73 稿 L501 逐字: 价格轴刻度 `(C.lo+rng*t).toFixed(0)` —— **取整, 零位小数**。
     * 改前走 MktData.mkFmt(|v|>=1 → 两位小数), 于是 354 / 312 印成 354.00 / 312.00;
     * 稿那一条就是整数, 同稿要两位的地方(现价 L557、第一支撑 L496)仍是两位。
     * ⟹ mkFmt 一律**不动**(支撑标签 L262 / 腿区间 L323-324 / 光标读数 L335 / OHLC L346-348 都按稿保留两位),
     *   这里单独给刻度一个取整版, 只此一处调用点。
     */
    private fun mkFmt0(v: Double): String = "%.0f".format(v)

    fun setData(ks: List<KLine>) {
        all = ks
        crossPx = null
        crossPy = null
        redraw()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ⚠⚠ 墓碑 · 2026-10-28（方向-B 收口 · t7）· **K 线缩放状态与 API 已整块删除**。
    //
    // 删掉的三样（原 r8 B-1 / 2026-10-19 引入）：
    //   · `var zoomW: Float = 380f`（可见绘图宽，稿单位；`private set`）
    //   · `fun zoomBy(step: Float): Float`（一步 ±60，夹 [200, 520]）
    //   · `fun resetZoom()`（回 380）
    // 依据链（两步，缺一不可）：
    //   ① 2026-10-28 **t3** 把行情页右上的 `−` / `+` / `Reset` **三键整块从 page_mkt.xml 删掉**，
    //      MktPanel 侧 3 条 click 监听与 `renderAgg` 里的 `chart.resetZoom()` 同步下线；
    //   ② 于是 `zoomW` 只剩「恒 380」这一种取值、这两个方法全仓**零调用者**
    //      （t7 复验：`grep -rn "zoomW|zoomBy|resetZoom"` 在 .kt/.xml 里只剩注释，活调用 0）。
    // 现在横向几何**固定**在 380 稿单位基准（= 全量居中）：见 onDraw 的 `u = W / 380f` 与 `X()`。
    // ⚠ **零回归证明**：原式 `X(unit) = m + (unit − unitLeft) * u`，其中 `u = W / zoomW`、
    //   `unitLeft = 190f − zoomW / 2f`。取 `zoomW == 380f`：`380f/2f == 190f`、`190f − 190f == +0f`
    //   ⟹ `u = W / 380f`（同一被除数与除数，结果位级相同）、`unit − (+0f) == unit`（IEEE 减法，
    //   对任意有限 x 成立）⟹ 新式 `m + unit * u` 与原式**位级等价**，不是"约等于"。
    // ⚠ 要恢复缩放：三键（page_mkt.xml）+ MktPanel 接线 + 本节字段与方法必须**一起**加回来；
    //   只加一侧就是死的（本仓既有教训：只删调用点不收 API / 只留空壳 findViewById）。
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 上一次 onDraw **真正画出来**的那一份 K 线（= `MktData.profileOf` 过滤后的 `vp.ks`，
     * 与 lo/hi、剖面、支撑、十字读数同源）。
     * r8 B-11 的 OHLC 明细表取它 —— 表里那 5 根必须是屏上真实画出来的 5 根，
     * 不许拿 `setData()` 收到的未过滤列表另算一遍（那正是 2026-10-10 那次「两个列表来源」的病）。
     */
    var drawn: List<KLine> = emptyList()
        private set

    fun drawnKs(): List<KLine> = drawn

    fun clear() {
        all = emptyList()
        // ⚠ r8 B-11：这里必须**同时**把「画出来的那一份」清掉，否则 OHLC 明细表会在清屏后
        //   继续显示上一个品种的 5 根（屏上明明没有 K 线了）—— 那是骗人。
        //   清空后表体按无取数态画 5 行 `—`（MktPanel.paintOhlc 的 `ks.isEmpty()` 分支）。
        drawn = emptyList()
        crossPx = null
        crossPy = null
        invalidate()
    }

    private fun redraw() {
        invalidate()
    }

    // 窗口恒为全量(MK.view=null常态),只由上方输入决定
    // ⚠⚠ 2026-10-10:这里返回的是 setData() 收到的**原始**列表(可能含脏柱/跨尺度柱),
    //   它**只**用来喂 profileOf。真正上屏的那一份是 Profile.ks(过滤后),见 onDraw 顶部。
    //   ⟹ 本文件不做任何过滤:过滤逻辑只允许存在一处,在 MktData.profileOf 里。
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
        val raw = visible()
        if (raw.isEmpty()) return
        // ══════════════════════════════════════════════════════════════════════════
        // ⚠⚠⚠ 2026-10-10 【用户报:行情图 K 线掉出绘图区之外 · 真机截图】
        //   用户原话：「k线会掉出行情图显示的范围之外」「行情的上下没有变价」。
        //
        // 【根因 · 绘制集合与量程集合**不同源**】
        //   · MktData.profileOf() 先做一级过滤（clean 非法柱；2026-10-12 起**只剩这一级**，
        //     尺度带已按用户裁定删除，见 MktData.profileOf 的墓碑注释），
        //     然后**只拿过滤后的 ks** 算 lo/hi(MktData.kt 里那段 lo/hi 循环)；
        //   · 而本函数改前画的是 setData() 收到的**未过滤**列表，y() 又按 vp.lo/vp.hi
        //     线性映射且**不做钳位** ⟹ 混进来的脏柱(l≤0 那种)照样被画，
        //     y 坐标直接落到绘图区上/下边界之外 —— 截图里那些出界的柱子就是这么来的。
        //
        // 【方案 (a)：绘制侧只认 profileOf 交回来的那一份】
        //   Profile 新增 `ks` 字段(= 算 lo/hi / rv / sup 用的**同一份**列表)，
        //   本函数此后 K 线、cw、时间轴刻度、十字、支撑过滤**全部**走 `vp.ks`，
        //   不再有第二个列表来源。⟹ lo = min(l)、hi = max(h) 就是这一份的极值，
        //   而 y() 是单调映射 ⟹ 画出来的每一根恒有 y ∈ [m, m+plotH]，**结构上不可能出界**。
        //
        // 【为什么不用方案 (b)（保留全部柱子 + y() 钳位）】
        //   · 钳位只是把越界的柱子**贴在边界上画**，既没消除「两侧又不同源了」这件事，
        //     又会把它藏起来 —— 下次再犯就查不出来；
        //   · ⚠ 墓碑 · 2026-10-12 用户裁定选 A：本条原写「跨尺度柱(如 NFLX 10:1 拆股前后的
        //     1078 → 107.58)**本来就该剔除**……这正是 profileOf 当初加尺度带的原因」——
        //     该理由随尺度带**一并作废**：按绝对价格判「这根属不属于拆股前的尺度」就是
        //     自动判定拆股，用户已裁定不做（裁定原文见 MktData.profileOf 的墓碑注释）。
        //   · 过滤逻辑**只允许存在一处**:现在只剩的脏柱过滤仍只在 MktData.profileOf 里，
        //     本文件一个字都不复制(本文件已有一份 onFill() 两份拷贝的血泪注释，不添第二例)。
        //
        // ⚠ 本改动**不碰网络、不改任何请求频率**(用户 2026-10-10:「抓取行情信息不要频繁打,
        //   不要被限流」):绘制层只是换个引用去读**已经取回来**的那一份列表。
        // ⚠ 唯一例外说明:实体最矮 1px 的那道 `max(1f, abs(yC-yO))` 会让柱底最多多出 1px，
        //   与本 bug 无关(不是「整根画到框外」)，未动。
        // ══════════════════════════════════════════════════════════════════════════
        val vp = MktData.profileOf(raw)
        // vp.ks = profileOf 真正用来算 lo/hi 的那一份(退化:有效柱<3 时可能为空 ⟹ 什么都不画)
        val ks = vp.ks
        // r8 B-11：把「真正画出来的这一份」留一份给 OHLC 明细表（与 lo/hi、剖面、支撑、十字同源）。
        drawn = ks
        if (ks.isEmpty()) return

        val W = width.toFloat()
        val H = height.toFloat()
        val m = dp(8f)
        val axH = dp(20f)
        val plotH = H - m - axH
        if (plotH <= 0) return

        // ⚠ 2026-10-02 t6(视觉对照 B):横向几何改成**照 v2 稿的单位**,不再自定比例。
        //   稿 Candles(v2.html:1779) `viewBox="0 0 380 292"` + `PL=34 / PR=296`,
        //   剖面 `<rect x={0} width={2+(v/mx)*30}>`(v2.html:1822) ⟹ **剖面列恰好 32 单位**。
        //   改前 App 另立一套:profW = round((W-m-axisW)*0.32) + PROF_GAP = dp(10f)。
        //   真机实测(1080×2340@440dpi, W=992px):剖面列连间隙占图宽 **28.6%**(稿只有 8.4%),
        //   K 线区被挤到 **51.0%**(稿 69.0%),每根 K 线 **9.19dp vs 稿 12.50dp,窄 26%**。
        //   那个 0.32 是拍脑袋的;稿的比例本来就有现成数字,直接换算更省也更准。
        //   ⟹ u = W/380 —— 与下面的标签片宽、支撑线宽**同一套基准**(t287 已立的口径),
        //     换机型/换横竖屏都仍与稿同比例。旧的比例算法见文件末尾 chartW/profW 墓碑。
        // ⚠⚠⚠ r8 B-1（2026-10-19）· **横向口径**：图上所有横向坐标都是「稿单位（0..380）」，
        //   经 X() **一次性**映射成 px；绘制与触摸命中用同一个 X / chartX / cw（单一来源）。
        //     · 基准宽**固定 380 稿单位**（= 全量居中）。2026-10-28 t7 起 `zoomW` 与
        //       `zoomBy/resetZoom` 已删除（三键在 t3 就从 layout 删了，见本文件顶部的墓碑）——
        //       没有任何交互入口能改动这个基准。
        //     · `u` = 1 稿单位多少 px = `W / 380` —— 稿的 `<svg viewBox="0 0 380 h"
        //        preserveAspectRatio="none">` 的换算就是 viewportW / viewBoxW
        //     · 可见窗口左界恒为 0：原式 `unitLeft = 190 − 380/2` 在默认态正好是 **+0f**
        //   ⟹ `X(unit) == m + unit * u`，与带缩放时 `zoomW == 380` 的几何**位级相同**
        //     （`x − (+0f) == x`，其余操作数逐一相同；这就是「默认态零回归」的算术依据）。
        val u = W / 380f                                       // 1 稿单位 = 多少 px(默认态 2.6105px)
        fun X(unit: Float): Float = m + unit * u
        // ⚠ 2026-10-03 删除原 val uDp = u / density(1 稿单位 = 0.949 dp)。
        //   它被用在 paint.textSize / drawRect / drawText 坐标 / curGap 上,
        //   而这些 API 的单位是**像素不是 dp** —— 整体错了一个 density(本机 2.75)。
        //   后果实测:标签底片 18uDp=17px(应 18 稿单位=47px)、字号 12uDp=11px(应 31px),
        //   白字溢出紫底片落到浅色画布上 → 支撑线标签读不出来。
        //   全部改用 u(= W/380f,稿单位→像素)。像素实测底片已回到 319x47。

        // ⚠ 2026-10-21 裁定 C-21 / C-22：网格色按交接_方向B.md §2.4-3.10「网格 border-strong@0.55」备好
        //   （原为 colorBorder 满不透明 = 比稿弱一档）；⚠ 方向 B 的 DOM / theme.css 里其实**没有网格线**
        //   （theme.css:674 注释写「网格 12% 中性」，与 §2.4-3.10 的 0.55 也不一致 ⟹ 以交接为准）。
        //   画不画由 SHOW_AXIS 决定（2026-10-10 产品裁定：关 ⟹ 登记为残差 R-19）。
        val grid = attrColor("colorBorderStrong")
        // 稿 L500 刻度 `fill="var(--ink3)"` · L491 现价虚线 `stroke="var(--ink3)"`
        // ⟹ 这一支是**第三档墨色**，改前用的 colorSub(第二档) 偏深（A.3 复核 S6）。
        val mut = attrColor("colorFaint")
        val tickC = mut
        val upC = attrColor("colorTeal")
        val dnC = attrColor("colorRed")
        // ⚠ 2026-10-28 t19 · 支撑线**不再共用**一个取色：
        //   第一支撑 = 绿（`--up`，theme.css:702）、第二支撑 = 红（`--down`，theme.css:703），都用下面
        //   蜡烛那两支现成的 upC / dnC，**不新建取色、也不要再引入 colorPrimary**。
        //   命中柱改用 `--accent-fill`（theme.css:697）⟹ 本盘 theme attr colorAccentFill（浅 #2A5BD7 / 深 #3D6BE0）。
        //   ⚠ 深色下这个值和 colorPrimary(#8FB2FF) 才是真正的分水岭：浅色两者同为 #2A5BD7。
        val accentFillC = attrColor("colorAccentFill")
        // 2026-10-02:原 Typeface.MONOSPACE(DroidSansMono),与全屏 Inter 不搭,改用 Inter+tnum（该历史决定及其原因**保留**）；⚠ 2026-10-22 t2（R-34 / C-40）复核：本处**不是等宽落点** ⟹ 仍用 Inter+tnum 不动 —— 它只喂图表轴刻度（Y 轴价格刻度在 SHOW_AXIS=false 墓碑内、屏上不可见；X 轴日期刻度 = MktData.fmtMD 的月日标签），而稿的对应刻度是 .mk-tick（theme.css:691 无 font-family ⟹ 继承 body 的 var(--font-sans) = Inter）⟹ 改 IBM Plex Mono 反而与稿造新不一致；App 侧等宽字族已统一到 IBM Plex Mono 的落点是候选行代码列（FavPanel.kt:1123，2026-10-22 t2）
        val mono = interRegular

        // ⚠⚠ 2026-11-04 用户裁定 A：**Y 轴默认对数**（改前是线性）。
        //   为什么：月/季线看长周期时，线性按**绝对价差**映射 —— 高价区一个大气泡、低价区挤成一条，
        //   同一百分比涨跌在高位和低位占的高度完全不同（天然气 10 → 2.5 那种跌幅在图上根本看不出量级）。
        //   对数下「同样百分比 = 同样高度」，支撑位之间的距离才等于真实的风险距离。
        //   ⚠ 单一映射源：蜡烛 / 影线 / 支撑线 / 现价虚线 / 命中柱 / 标签片的纵向坐标**全部**走这里，
        //     所以只改这一处即可整体切换，别在别处另写一份 y（那会画歪）。
        //   ⚠ 数学边界：log 只在价格 > 0 有定义。若区间出现非正值（期货理论可为负），
        //     整个切换退回线性 —— 不崩、不产出 NaN；单点非正也只在必要时钳到区间下界。
        val useLog = vp.lo > 0.0 && vp.hi > 0.0 && vp.hi > vp.lo
        val lnLo = if (useLog) Math.log(vp.lo) else 0.0
        val lnSpan = if (useLog) {
            val d = Math.log(vp.hi) - lnLo
            if (d == 0.0) 1.0 else d
        } else 1.0
        fun y(p: Double): Float {
            if (useLog) {
                val v = if (p > 0.0) p else vp.lo
                return (m + plotH - ((Math.log(v) - lnLo) / lnSpan) * plotH).toFloat()
            }
            val span = if (vp.hi - vp.lo == 0.0) 1.0 else vp.hi - vp.lo
            return (m + plotH - (p - vp.lo) / span * plotH).toFloat()
        }

        // t107 ⑤/① 重新划几何:剖面独占左侧一列 + 与 K 线区留间隙 + 右侧留标签列。
        //   剖面 : [m, m+PROF_W]         稿 L1822 的 2+30=32 单位,柱子最右到 m+32u,**不越间隙**
        //   间隙 : PROF_GAP = 2u         稿 L1779 的 PL(34) − 32 = 2
        //   K线  : [chartX, plotRight]   稿 PL → PR
        //   标签 : 压在 plotRight 之内的标签片上(稿 L495 的 x={PR-标签宽},不给标签让位)
        //   价格轴: plotRight → W       稿 PR(296) → 380,刻度右对齐在 W-dp(6f)
        val PROF_BASE = 3f                                       // 恒定底宽(稿单位,保证 mx 档也看得见)
        val PROF_VAR = 77f                                       // 按量伸展的上限(稿单位)
        val PROF_W = PROF_BASE + PROF_VAR                         // 剖面列共 80 单位
        val PROF_GAP = 1f                                        // 剖面与 K 线之间的间隙(稿单位)
        // ⚠⚠ 2026-11-04 用户报「k 线图两边的缝留得太多」：右侧那 42 稿单位**本来就是给
        //   价格轴刻度留的槽**，而 `SHOW_AXIS=false`（2026-10-10 产品裁定）之后**刻度不画** ⟹
        //   这 42 单位成了纯空白。现在把 K 线区右界从 338 推到 358：右侧留 22 单位，
        //   K 线区宽度 257 → 277 单位（占宽 67.6% → 72.9%），每根蜡烛随之变宽。
        //   ⚠ 别再往右推：实测 372 时**末位 X 轴日期标签被卡片右缘裁掉**（「26-11」变「26-」），
        //     358 是「标签放得下」与「白缝尽量小」的折中；支撑标签片也压在这段之内收口。
        //   ⚠ 将来若把 SHOW_AXIS 打开，必须先把这里改回去并重算刻度槽（否则刻度会压到蜡烛上）。
        val PR_U = 358f                                    // K线区右界（原 338；372 试过太满，末位日期标签被裁）
        // t317 #72 的 `W*(112/380)` 写死宽度已作废(t6):那是"整片 112 单位",与稿
        // L1841 `width={labelW('第一支撑 '+值,12)}` 的**跟随内容**相反 —— 内容长短不同就
        // 要么截断要么留白(实测本屏「第一支撑 52.86」只占底片约一半)。改为跟随内容,见下面 chipW。
        // ⚠ r8 B-1：这三个量都是**稿单位**（不再是 px），横向落点一律过 X()。
        val chartX = X(PROF_W + PROF_GAP)    // = X(81)
        // 支撑线画满 PL → PR, 不给标签让位。
        val plotRight = X(PR_U)          // K线区右界
        if (plotRight <= chartX) { super.onDraw(c); return }

        // ⚠⚠ 2026-10-03 用户裁定，两处**同时偏离稿**（都属产品裁定，不是移植漏项）：
        //
        //   用户原话：「剖面列加宽，然后把k线放一些到右边空白处」
        //
        //   ① 剖面列 32 → 50 单位
        //      改前 PROF_BASE 2 + PROF_VAR 30 = 32，占 380 的 8.4%。
        //      改后 3 + 47 = 50，占 13.2%。
        //
        //   ② K线区右界 PR 296 → 330
        //      改前 K线区 = 296 − 34 = 262 单位；右侧 296→380 共 **84 单位(22% 屏宽)**
        //      几乎是纯空白 —— 那是价格轴的预留槽，稿的坐标系就这么分的。
        //      改后 K线区 = 330 − 52 = **278 单位**，右侧只剩 50 单位给刻度。
        //
        // ⚠ 刻度放得下：价格文字右对齐在 `W - dp(5.7f)`，四位数字(5627)宽约 24u，
        //   加右侧留白约 6u ≈ 30u < 新的 50u 槽宽。**不会挤爆、不会被裁。**
        //
        // ⚠ 这两处的「稿口径」作废，恢复照稿要把 PROF_BASE/PROF_VAR 改回 2/30、
        //   PR_U 改回 296，并把上面这些注释连同 chartX 一起回滚。
        // ⚠ 若日后要回稿，**别只改 PR_U**：chartX 是由 PROF_W 算出来的，
        //   只动其中一个会让 K线区左右同时错位。
        //
        // ⚠ 2026-10-03 追加第二次加宽（用户原话「vpvr还可以再宽一点，都没碰到k线呢」）：
        //     剖面 50→68 单位，间隙 2→1，PR 330→340。
        //     算术：多要的 19 单位，从右侧价格轴让 10、从 K线区让 9 ——
        //       剖面   50 → 68   (+18，占屏宽 13.2% → 17.9%)
        //       K线区  278 → 340−69 = 271  (−7)
        //       价格轴  50 → 40   (仍容得下约 30u 的四位刻度，不会裁)
        //     ⟹ 净效果剖面显著变宽，K 线只掉 7 单位。
        //   ⚠ **总宽 380 是死的**：剖面 / K线 / 价格轴 分同一份，任意一处加宽都从别处扣。
        //     嫌哪边窄就调哪边，但不能三边同时要。

        /** 稿 L1807-1808 `labelW(txt,fs)` 逐字:CJK 按 1 单位、ASCII 按 0.56 单位,再加 14 单位留白,
         *  并以 K 线区宽度封顶。CJK 判定照稿的正则 `/[⺀-鿿＀-￯]/`。 */
        fun chipW(txt: String, fsU: Float): Float {
            var sum = 0f
            for (ch in txt) {
                val c = ch.code
                val cjk = (c in 0x2E80..0x9FFF) || (c in 0xFF00..0xFFEF)
                sum += if (cjk) fsU else fsU * 0.56f
            }
            return min(plotRight - chartX, (sum + 14f) * u)
        }

        // 网格线**三条**（lo / 中点 / hi），刻度**两个**（lo / hi）——
        // 稿里这是**两个独立循环**，不是同一个：
        //     L471 `{[0,0.5,1].map(t=><line … stroke=var(line) strokeWidth=1/>)}`   ← 3 条
        //     L499 `{[0,1].map(t=><text … fontSize=12 fill=var(ink3)>)}`            ← 2 个
        // 改前两者合并在 `for (i in 0..1)` 里，于是线也只剩 2 条（A.3 复核 S7）。
        //
        // ⚠ 这块原先那段注释把「只显示 2 个刻度」连同网格线一起砍了,
        //   还替这个决定找了个理由 —— **那个理由的事实前提是错的**(稿 L471 是三条线)。
        //   而那个决定的**事实前提错了**。
        //   注意：**刻度只显示 2 个这个决定本身没问题**（稿 L499 也只有 2 个），
        //   错的只是它顺带把网格线也砍到了 2 条。t395。
        paint.style = Paint.Style.FILL

        // ⚠⚠ 2026-10-03 **用户裁定删除**①网格线与②价格刻度数字（原话：「不要这个上划线和数字」）。
        //   下面是**墓碑**，两段代码原样留着，要恢复就把 if(false) 改回 true。
        //   ⟹ 这**偏离稿**：稿 L471 画 3 条网格线、稿 L499 画 2 个价格刻度（都真实存在）。
        //     这次是产品裁定，不是移植对齐。
        //   ⚠ 连带后果（要恢复时一并考虑）:
        //     · 价格轴那 42 单位(110px)槽**不再有任何内容**，右侧会空出来 ——
        //       恢复刻度数字时不必再调宽度；不恢复的话，可以把 PR_U 往右放、K线区再吃回来。
        //     · vp.hi / vp.lo 仍被 `y()` 用来定 Y 映射，删掉这两段**不影响坐标**。
        //     · 用户那两张截图里数字被压成竖条状，是刻度槽被前面几轮
        //       「剖面加宽」挤压后文字放不下所致 —— 删掉正好一并解决。
        val SHOW_AXIS = false
        if (SHOW_AXIS) {
        // ① 网格线：3 条，lo / 中 / hi。稿 L472 逐字 stroke="var(--line)" strokeWidth="1"
        //   —— **稿里没有 alpha**, 所以这里也用满不透明; 与本文件剖面那段(t218)是同一条
        //   标准: 稿里没有的层就不加。改前是 paint.alpha = 153(六成), 浅色主题下比稿更淡。
        paint.color = grid
        paint.alpha = 140   // 交接 §2.4-3.10：网格 = border-strong @0.55（0.55 × 255 = 140）
        paint.strokeWidth = 1f
        for (i in 0..2) {
            val p = if (i == 0) vp.hi else if (i == 1) (vp.hi + vp.lo) / 2f else vp.lo
            val yy = y(p)
            c.drawLine(chartX, yy, plotRight, yy, paint)
        }
        paint.alpha = 255

        // ② 刻度：2 个，hi / lo，稿 L500 的 fill 是 var(--ink3)
        //   t6:字号由 dp(9f) 改为 **稿 L1864 的 fontSize="11"**。
        //   改前用 dp(9f)=9dp,真机实测比稿**小 14%**(稿 11 单位 × 0.949dp = 10.44dp)。
        paint.typeface = mono
        paint.textSize = 11f * u
        paint.textAlign = Paint.Align.RIGHT
        for (i in 0..1) {
            val p = if (i == 0) vp.hi else vp.lo
            val yy = y(p)
            paint.color = tickC
            // ⚠ 2026-10-21 t24（闸 2 裁定 2 ③）：dp(5.7f) **保持不动** —— 本块在 `if (SHOW_AXIS)` 墓碑内
            //   （MktView.kt:441 SHOW_AXIS = false）⟹ 屏上不可见；稿的图表**不画 Y 轴价格刻度**
            //   （方向-B/四屏-行情页.html:340/342、theme.css:687 `.mk-tick` 只用于横轴）⟹ 无稿出处 ⟹
            //   按裁定「死分支保持不动 + 登记台账」；若将来恢复 SHOW_AXIS ⟹ 先定稿右距（按 7 档吸 4dp 或 8dp）。
            c.drawText(mkFmt0(p), W - dp(5.7f), yy - dp(1f), paint)
        }
        }   // ← SHOW_AXIS 墓碑结束
        paint.alpha = 255

        // ⚠⚠ 2026-10-10:这里的 `ks` 是 **vp.ks**(过滤后、与 vp.lo/vp.hi 同源的那一份)。
        //   根数 n、cw、K 线、时间轴刻度(下面 tickIdx 用的也是这个 n)、十字、支撑过滤
        //   全部走它 —— 全函数**没有第二个列表来源**(这就是本 bug 的修法,见 onDraw 顶部)。
        //   ⚠ 连带:MktInfo.count = n 报的是**画出来的根数**(跨尺度柱本来就没上屏,不该计入)，
        //     与 MktPanel「N 根…已更新」的既有语义(画出来的根数,不是取到的根数)一致。
        val n = ks.size
        val cw = (plotRight - chartX) / n
        val bw = max(1f, cw * 0.6f)

        // K线
        // ⚠ 2026-10-17 用户当面裁定：**涨跌实体一律 FILL（全实心）**。
        //   稿侧同批回改：2026-10-21 按裁定 C-25（41_白名单与裁定记录.md §6 C-25）把方向 B §6.9 / M9 的旧口径「涨空心 / 跌实心」
        //   改成**涨跌两态全实心** ⟹ App 侧这两行不再是「有意偏离稿」（沿革：2026-10-16 t52 曾按稿改成空心 → 2026-10-17 用户裁定 → 2026-10-21 C-25 稿同批回改）。
        //   代价（已登记，别当没发生）：失去「空心 / 实心」这重**不依赖颜色**的第二编码，
        //   涨跌回归**只由颜色承载**——正是方向 B §3.10 与设计系统 §7.2 原本要避免的一点。
        //   影线（wick）恒为 STROKE、两态同色；实体：两态皆 FILL。
        //   ⚠ 颜色**一字未动**：涨 colorTeal（绿）/ 跌 colorRed（红）—— 语义硬约束，不许翻转。
        ks.forEachIndexed { i, k ->
            val cx = chartX + i * cw + cw / 2
            val up = k.c >= k.o
            val col = if (up) upC else dnC
            paint.color = col
            // ⚠ r8 B-13（2026-10-19）· 影线线宽改走**稿单位**口径：
            //   稿 `theme.css:686-687` 逐字 `.ck--up/--dn .ck__w { stroke-width: 1.5 }` —— SVG 单位，
            //   随 viewBox 一起缩放 ⟹ 像素宽 = `1.5 × u`（u = 1 稿单位多少 px，与支撑线/现价虚线同源）。
            //   改前是 `cw * 0.15f`（**按柱宽比例**），报告 §3.2 实测 = 1.93 稿单位，比稿粗 **28.6%**；
            //   而同一份稿里支撑线与现价虚线早就走 `1.5 * W / 380` 这一套 ⟹ 本次是把影线并到同一套换算，
            //   不另立第二套（默认态 u == W/380，与那两处的逐字表达式等值）。
            //   `max(1f, …)` 是**亚像素下限**（低密度屏/极窄绘图区别让线消失），保留。
            paint.strokeWidth = max(1f, 1.5f * u)
            paint.style = Paint.Style.STROKE
            c.drawLine(cx, y(k.h), cx, y(k.l), paint)
            val yO = y(k.o)
            val yC = y(k.c)
            val top = min(yO, yC)
            val bodyH = max(1f, abs(yC - yO))
            paint.style = Paint.Style.FILL   // 2026-10-17 用户裁定：涨跌两态全实心（见上方登记）
            c.drawRect(cx - bw / 2, top, cx + bw / 2, top + bodyH, paint)
        }

        // t106 ⑦ VPVR 剖面:左侧独立一列、灰色单色。t107 ⑤:柱子最右不越过间隙。
        val rows = vp.rv.size
        // ⚠⚠ 2026-11-04 **对数坐标的连带修复**（用户报「vpvr 柱的位置和价格对不上」）：
        //   剖面原来按**像素等分**（rh = plotH/rows，逐档往下加 rh），这隐含「价格线性」。
        //   `y()` 换成对数后价格映射非线性，等分像素 ≠ 等分价格 ⟹ 柱子与价格整体错位。
        //   现改为**按价格区间逐档求 y**：第 i 档覆盖价格 [lo + i*step, lo + (i+1)*step]，
        //   上/下边各过一次 y()，柱子高度随对数间距自适应（低价区更厚、高价区更薄）——
        //   这正是「对数坐标下每一格的像素高度本来就不相等」的正确画法。
        //   ⚠ 退化区间的兜底：hi≈lo（step==0）时退回旧的像素等分，避免除零与零高柱。
        val step = if (rows > 0) (vp.hi - vp.lo) / rows else 0.0
        val rh = plotH / rows
        val mx = vp.rv.maxOrNull() ?: 0.0
        val profX = X(0f)
        // t6:剖面列宽由 `pw - PROF_GAP`(拍脑袋的 0.32 比例)改成**稿 L1822 的 32 单位**:
        //   `width = 2 + (v/mx)*30` —— 2 是**恒定底宽**(保证 mx 那一档也看得见), 30 是变量上限。
        //   PROF_BASE / PROF_VAR 在上面定义,这里只说明**柱子最右只到 profX + PROF_W**,不越间隙。
        // 稿 L477: 高亮的是**支撑命中的档**, 稿特意注明「不是 POC」。
        // 2026-10-01 F-3 随「画两条线」一并改成稿的新语义: 改前是 `i == sup1Hit`(只高亮**第一支撑那一档**),
        //   而稿 L1820 是 `const hit = C.supIdx.indexOf(i)>=0`(高亮**所有**命中档)。只亮一档时,
        //   画出来的第二条线在剖面上没有任何对应,视觉上像第二条线不存在或算错了。
        // 支撑顺序已核: MktData 从高价往低价扫, supShow[0] 即第一支撑 ⟹ 与稿的 C.sup1 同一档。
        // 换算: 第 i 档占 y ∈ [m+(rows-1-i)*rh, +rh)  ⟹  i = rows-1-floor((y(p)-m)/rh)
        // ⚠️ 与稿有一处**有意**差别: 稿的 C.supIdx 是腰折扫出的**全部**命中(含现价之上的),
        //   这里只取**会画出线的那几条**(it < 现价)—— 否则会出现「亮了某档、却没有对应线」。
        //   下方绘制用的 supShow 用的是同一个谓词,两者永远一一对应。
        // 一个支撑都没有时 supBins 为空 ⟹ `i in supBins` 恒 false ⟹ 与改动前(sup1Hit 为 null)行为一致。
        // This exact capped sequence drives both the hit-bar highlight and the support lines/info.
        val curPx = ks.last().c
        // ⚠⚠ 2026-11-04 高亮档**不再用除法反推**，直接用剖面生产时留下的档位号（`Profile.supIdx`）。
        //   原因：支撑价已改成档位**下沿** `lo+ww*i`，正好落在边界上；
        //   浮点反推 `((p-lo)/step).toInt()` 在边界上会得到 i-ε ⟹ 取整成 i-1
        //   ⟹ 高亮柱整体下沉一档（用户实报：黄金 22 根月线「支撑柱颜色漂移到下面那根」）。
        //   生产时就把 i 带过来，绘制侧两位同一档，不存在边界取整问题。
        val supPairs: List<Pair<Double, Int>> =
            if (vp.supIdx.size == vp.sup.size) vp.sup.zip(vp.supIdx) else vp.sup.map { it to -1 }
        val supShown = supPairs.filter { it.first < curPx }.take(2)
        val supShow = supShown.map { it.first }
        val supBins = supShown.map { it.second }.filter { it in 0 until rows }.toSet()
        vp.rv.forEachIndexed { i, _ ->
            // 第 i 档：i=0 是**最低价档**（原代码 yy = m+(rows-1-i)*rh 即 0 → 底部）。
            val yBot = if (step > 0) y(vp.lo + i * step) else m + (rows - i) * rh
            val yTop = if (step > 0) y(vp.lo + (i + 1) * step) else m + (rows - 1 - i) * rh
            val yy = yTop
            // t6:逐字照稿 L1822 `width = 2 + (v/mx)*30` —— 底宽 2 单位 + 按量伸展 30 单位。
            //   ru/rd 仍按原样合并取大者(改前也是 max(wUp,wDn)),只把比例基准换成稿的单位。
            val q = vp.rv[i]                                    // ⚠⚠ 见下方墓碑：必须是 rv，不是 max(ru,rd)
            // r8 B-1：宽度是**稿单位**，落像素要乘 u（与稿的 viewBox 换算同口径；2026-10-28 起无缩放，u 恒 = W/380）。
            val w = (if (mx > 0) PROF_BASE + (q / mx * PROF_VAR).toFloat() else 0f) * u
            paint.style = Paint.Style.FILL
            // 稿 L479: fill={hit?'var(--ac)':'var(--line)'}
            // 改前是所有档统一 #9E9E9E/alpha150 —— 缺紫高亮, 且底色也不是稿的 line 色。
            // 稿 L479: fill={hit?'var(--ac)':'var(--line)'} —— 【实心, 没有 alpha】。
            // t218 修回归: t215 给非高亮档乘了 alpha=150, 而 colorBorder 在浅色主题下本就浅,
            //   乘完几乎等于白, 整块剖面在真机上消失(截图才发现, dump 发现不了 —— Canvas 没有 view 节点)。
            // 稿里本就没有这层透明度, 去掉它不是"调回我的偏好", 是回到稿。
            //   方向 B 的基色由 v1 的 colorBorder 换成 **colorBorderStrong**（浅 #C6D0DC / 深 #3A434F）。
            //   ⚠ 2026-10-28 t19：命中档**不再走 colorPrimary**，改稿的 `--accent-fill`（theme.css:697）
            //     ⟹ 本盘 **colorAccentFill**（浅 #2A5BD7 / 深 #3D6BE0）。改前深色取 colorPrimary
            //     #8FB2FF —— 那是 `--accent`（当文字/图标用的那支），压在剖面里明显偏亮。
            paint.color = if (i in supBins) accentFillC else attrColor("colorBorderStrong")
            // 非高亮档沿用稿的 0.55 alpha；命中柱按 theme.css:697 的 `opacity: 1` 保持满不透明。
            //   旧处置「只落色不落 opacity」（2026-10-16 t52）是为绕开 t215/t218 的回归
            //   （当时基色是 colorBorder，×0.55 在浅色下几乎等于白）；基色换成 colorBorderStrong 后
            //   该风险不复存在 ⟹ 回到稿的 0.55。
            //   ⚠ 作废的两处稿注释：HTML:365「非高亮档 opacity .45」、theme.css:674「剖面 16% 中性」。
            paint.alpha = 255
            if (i !in supBins) {
                // 裁定 C-21：非高亮档 = 稿的 0.55 × 255 = 140（命中柱 accentFill 满不透明）
                paint.alpha = 140
            }
            val top = yy + 1f
            val bottom = max(top + 1f, yBot - 1f)   // 上下各留 1px 缝；对数下低档更厚、高档更薄
            c.drawRect(profX, top, profX + max(if (w > 0) 1f else 0f, w), bottom, paint)
        }
        // ⚠⚠ 墓碑 2026-10-03 —— 用户三次说「VPVR 可以再宽，离 K 线还有这么远」，
        //   前两次**只加列宽、没修下面这个 bug**，所以是白加：空档按比例一起变大。
        //
        //   真正的原因在**上面两行的分子分母不是同一个量**：
        //     归一化基准  mx = vp.rv.max()        而 rv[i] = ru[i] + rd[i]  ← 涨+跌**合计**
        //     画柱子用的   q  = max(vp.ru[i], vp.rd[i])                     ← 只取**单边**
        //   因为 rv ≥ max(ru, rd)，恒有 q/mx ≤ 1 且**严格小于 1**（只要该档同时有涨跌量），
        //   ⟹ **任何一根柱子都顶不满列宽**，最坏情况只到 PROF_VAR 的一半。
        //   ⟹ 屏上看到的「剖面离 K 线还空一截」就是这么来的，且与列宽无关。
        //
        //   改法：分子也用 rv[i]，与 mx 同口径 —— 这正是稿 L1822 `width = 2 + (v/mx)*30`
        //         要求的（v 与 mx 必须是同一个量）。
        //   ⟹ 现在 q == mx 的那一档会**正好顶到 PROF_W**，剖面与 K 线之间只剩 PROF_GAP。
        // ⚠ ru / rd 现在**只**用于 (若有) 涨跌分色；本文件的紫/灰高亮走的是 supBins，
        //   与 ru/rd 无关，所以这次改动不影响高亮行为。
        paint.alpha = 255

        // ⑧⑨⑩ 支撑线 + 标签。线只画在 K 线区;第一支撑**有标签片**、第二支撑**没有标签片**。
        //   ⚠️ 「右侧标签列」是 t107 的旧说法,t6 已作废:标签片是**压在图内右端**的
        //   (稿 L495 的 x={PR-标签宽}),它盖住线的右端而不是让出一列 —— 与稿一致。
        //   底色不透明(与页面背景同色系,**不是绿色**——绿是线色,同色必糊);
        //   文字高对比墨色;垂直居中于线;两条都在且标签靠太近时**只把第二支撑的标签往下推**,互不压。
        // ⚠ 2026-10-10:现价锚点取**绘制列表**的最后一根 —— 它就是 profileOf 算 lo/hi 的
        //   那一份(= clean,2026-10-12 起只剩脏柱过滤)的末根；用同一份列表当锚，
        //   现价虚线、支撑过滤、十字读数就不会与画出来的 K 线各说各话。
        //   ⚠ 墓碑 · 2026-10-12：本条原说它「正常就是 profileOf 尺度带的锚点柱（带内集合
        //     恒含锚点柱）」—— 尺度带已按用户裁定选 A 删除，锚点/带内集合一并作废。
        // t6:供下面「现价标签」用 —— 现价标签要与第一支撑芯片防压(见那里的 curGap),
        //   而芯片的 y 在 `if (supShow.isNotEmpty())` 里面,得先把这一个数带出来。
        var supCnt = 0
        var supLabelY0 = 0f
        if (supShow.isNotEmpty()) {
            // ---- F-3(2026-10-01):画前两条 ----
            // **顺序没变,也不用改**:`vp.sup` 由 MktData 的腰折扫描产出,那边是
            // `for (i in rows-1 downTo 0)`(MktData.kt:1611),即**从高价行往低价行**扫,
            // supRows 按扫出顺序 append、价格随之单调递减。所以 supShow[0] = 离现价最近的那条
            // (=第一支撑), [1] = 再下一条(=第二支撑),天然高价→低价。
            // ⚠️ **不要**再排序,也**不要**改回稿子早年的反向 —— t20/t22 已对齐成现在这个方向。
            //
            // 为什么是两条:用户 2026-09-30 直接要求画两条,稿子照办。
            // ⚠ 2026-10-28 t19:这段原文还写着「第二支撑走第三档墨色 ink3 / 线宽 1 / 虚线 6 4 / 无底片」
            //   —— 那是 2026-10-03 之前的形态，之后的三次裁定已把它全部推翻：同日改**实线**且线宽取
            //   与第一支撑相同的 1.5；标签与底片整块删除；颜色按当前稿 theme.css:702-703 改为
            //   **第一支撑绿 / 第二支撑红**（用户裁定）。所以「紫 = 决策色、两条紫线=两个平级决策」
            //   那条论证也随之作废 —— 现在区分两条线靠的不是色系深浅，而是**绿 / 红**本身。
            val cnt = minOf(2, supShow.size)
            supCnt = cnt
            // ⚠ 2026-10-03 用户裁定「图表中不要再显示支撑价格了，只要线」——
            //   支撑**标签**(连片带字)全删之后,这一支原来那一整套「标签防压」机制失去对象,一并下线:
            //   names[] / txt / ysLabel[] / minGap 全部作废。
            //   **剩下的只有线**,而线是数据,不该被排版推着动 —— 所以 y 直接取 ysLine,不再分两套。
            val ysLine = FloatArray(cnt) { y(supShow[it]) }
            // 现价标签仍要与第一支撑**线**保持距离(见下面 curGap),故留一个基准 y。
            supLabelY0 = ysLine[0]
            for (si in 0 until cnt) {
                val primary = si == 0
                val py = ysLine[si]
                // 线:从 K 线区左端画到右端(止于 plotRight,与稿 L494 的 PL→PR 一致)
                if (primary) {
                    paint.style = Paint.Style.STROKE
                    // 第一支撑 = **绿**：稿 theme.css:702 `.mk-line--sup1 { stroke: var(--up) }`（用户裁定）
                    // ⟹ 直接复用上面蜡烛那支现成的 `upC`（colorTeal），不新建取色、也不再用 colorPrimary。
                    // ⚠ 2026-10-28 t19 改前这里是 `supC`（colorPrimary 紫），两个主题下都错。
                    paint.color = upC
                    // t287 稿 L465/L470/L494 三处串起来看:
                    //   W=380 基准 + <svg width="100%" viewBox="0 0 380 292"> ⟹ 缩放 = 实际绘图宽/380
                    //   支撑线 strokeWidth="1.5" 是 **SVG 单位**, 随容器缩放, 不是固定 dp。
                    // 本机 1080px 绘图区约 992px ⟹ 1.5×992/380 = 3.9px ≈ 1.42dp;
                    // 改前写死 dp(2f)=5.5px, 折合 2.1 SVG 单位, **比稿粗 40%**。
                    // 按基准现场算, 换机型/换横竖屏都仍与稿同比例。
                    // 最小 1px: 极窄绘图区下 1.5×W/380 可能 <1px 画不出来。
                    paint.strokeWidth = max(1f, 1.5f * W / 380f)
                    c.drawLine(chartX, py, plotRight, py, paint)
                    // ⚠ 墓碑 · 2026-10-03 用户裁定「图表中不要再显示支撑价格了，只要线」:
                    //   这里原先画一整块标签片(底片 rect + 白字 drawText),规格照稿 v2.html:1841-1845
                    //   (x={PR-标签宽}、height=18、fontSize=12、textAnchor=end)。
                    //   连同 chipW()、onFill()、interBold 在支撑这一支的用法一并失去用处。
                    //   价位改由图下方的 mkt_supblock 承担 —— 那里没有 K 线干扰,读得清。
                    //   ⚠ 别照稿把它加回来:删标签是用户明确要的。
                } else {
                    // 第二支撑 ⚠ 2026-10-03 **用户裁定改线型**:虚线 6 4 → **实线**,与第一支撑同型。
                    //   线宽一并取与第一支撑相同的 1.5 稿单位 —— 「一样」就该整条一样:
                    //   1.0 与 1.5 在真机上是 2.6px 与 3.9px,只差 1.3px,肉眼分不出是刻意还是画错。
                    //   ⚠ 2026-10-28 t19：原文「两条线都走主题 primary，同源紫色；不复用涨跌绿/红色」**已作废**
                    //     —— 当前稿 theme.css:702-703 恰恰就是复用涨跌色：第一支撑 = `--up`(绿)、
                    //     第二支撑 = `--down`(红)，两支都是用户裁定。所以下面按稿取 `dnC`（colorRed）。
                    //
                    //   ⟹ 这条改动顺带消掉了原来那个坑:原来靠「短划 3 3(现价) vs 长划 6 4(第二支撑)」
                    //   做节奏区分,但**只有两条都在场时**才构成对照;第二支撑缺席时节奏区分失效,
                    //   K 线区里就只剩一条孤零零的灰虚线,会被直接读成「第二条支撑线」。
                    //   现在两条支撑都是实线且颜色固定,不存在「多出来的那条灰虚线」。
                    // 第二支撑 = **红**：稿 theme.css:703 `.mk-line--sup2 { stroke: var(--down) }`（用户裁定）
                    // ⟹ 复用蜡烛那支 `dnC`（colorRed）。宽/型与第一支撑一致（同日裁定改实线 + 1.5）。
                    paint.style = Paint.Style.STROKE
                    paint.color = dnC
                    paint.alpha = 255
                    paint.strokeWidth = max(1f, 1.5f * W / 380f)
                    c.drawLine(chartX, py, plotRight, py, paint)
                    // ⚠ 墓碑 · 2026-10-03「图表中不要再显示支撑价格了，只要线」:
                    //   这里原先按稿 L1858 画了一个 <text>(fontSize 10、右对齐 PR-4、无底片)。
                    //   一并删掉 —— 现在两条支撑**都只有线**,数值统一由图下 mkt_supblock 承担。
                }
            }
        }

        // t108 (3) 现价虚线(DashPathEffect),位置 = 现价,画满 K 线区宽度。
        // 稿 L491 逐字: stroke="var(--ink3)" strokeWidth="1" strokeDasharray="3 3"
        // ⟹ 墨色是**第三档 ink3**(本盘 attr colorFaint), 不是第二档 colorSub:
        //    紫只给主操作,绿是涨跌/支撑,都不用于此。
        val curY = y(curPx)
        val curOnChart = curY >= m && curY <= m + plotH
        if (curOnChart) {
            dashPaint.color = mut
            // t6:节奏与线宽一起改回**稿单位** —— 稿 L1836 是 `strokeWidth="1" strokeDasharray="3 3"`,
            //   两者都是 SVG 单位、随 viewBox 一起缩放。改前写死 dp(4)/dp(4)(周期 22px),
            //   是稿 "3 3"(3×2.61=7.8px,周期 15.7px)的 **1.4 倍**,与稿逐字不符。
            dashPaint.strokeWidth = max(1f, 1f * u)
            dashPaint.pathEffect = DashPathEffect(floatArrayOf(3f * u, 3f * u), 0f)
            c.drawLine(chartX, curY, plotRight, curY, dashPaint)

            // ⚠⚠ t6 —— **「节奏区分」在没有对照物时是失效的**,这条是本屏真机量出来的:
            //   00700 月线屏上 紫实线 y=646 / 灰虚线 y=629,虚线周期实测 22px = 改前的 dp4/dp4;
            //   而剖面列**只有 1 段紫** ⟹ supShow.size=1 ⟹ cnt=minOf(2,1)=1 ⟹ **第二支撑根本没画**。
            //   于是这条灰虚线成了 K 线区里**唯一**一条灰色虚线,和支撑线同色、同宽、同走向、
            //   同为「没有标签的贯穿线」—— 视觉上就是「第二条支撑线」,而且比紫线**还高**(价高 y 小)。
            //   「短划 vs 长划」只有在**两条都在场**时才构成对照,单独一条时无从比较。
            //
            //   ⟹ 方案:**只在没有对照物的那个分支补语义** —— 给现价线加一个「现价」文字标签。
            //     · 有第二支撑(supCnt>=2)时**严格照稿、不加**(稿 L1836 本身也没有标签);
            //     · 没有时才加 ⟹ 既不偏离稿,又补上了稿照不到的边界。
            //   为什么选「文字标签」而不是「改非虚线」:
            //     · 实线是**决策线**的语言(第一支撑就是实线),现价改实线等于宣告又一个决策;
            //     · 换色档(第二档 colorSub)与第三档 #686C74 的对比太弱,小屏上仍读不出;
            //     · 加标签是 TradingView「last price tag」的原生做法(v2.html:69 form 即取自它),
            //       且**只要有字就不可能被读成另一个标签** —— 它直接消灭歧义,而不是弱化对比。
            //   排版照第二支撑那套:同一右对齐基线、**没有底色片**、只动标签不动线。
            // ⚠⚠ 2026-10-03 **用户裁定删除**「现价 + 数值」这个标签，原话：「我不需要显示价格」。
            //   与本轮早些时候删支撑标签片是**同一个口径**：图内只要线，不要价。
            //   （且此前用户已说过「图里的东西不要了」。）
            //
            //   ⚠ 这里**刻意不再补底色片**。上一轮我看到它压在 K 线上糊成一团，
            //     正准备加一块画布圆角片（TradingView last-price tag 那样）——
            //     **那个方向本身就错了**：用户不要的是「价格」本身，不是「价格看不清」。
            //     加底片只是把一个不该存在的东西画得更醒目。
            //
            //   ⟹ 现价**只保留那条虚线本身**（稿 L491 的 stroke/dasharray 未动）。
            //   ⟹ 原本这里还有一个「supCnt<2 才加标签」的分支：那是上一轮为了消解
            //     「单条虚线会被读成第二支撑线」而加的补偿。用户现在直接不要标签了，
            //     **那个歧义也随之不存在** —— 不用再靠字去消解。
            //   ⚠ 要恢复：把 SHOW_CUR_TAG 改回 true。恢复前先想清楚
            //     「单条虚线的歧义」怎么办（要么接受，要么改线型而不是加字）。
            val SHOW_CUR_TAG = false
            if (SHOW_CUR_TAG) {
                paint.style = Paint.Style.FILL
                paint.textSize = 10f * u          // 与第二支撑同档(两者都是次级文字标签)
                paint.typeface = interRegular
                paint.textAlign = Paint.Align.RIGHT
                paint.color = mut
                paint.alpha = 255
                // ⚠ 2026-10-21 t24（闸 2 裁定 2 ③）：dp(5.7f) **保持不动** —— 本块在 `if (SHOW_CUR_TAG)` 死分支内
                //   （MktView.kt:720 SHOW_CUR_TAG = false）⟹ 屏上不可见；稿的图内**不写价**
                //   （方向-B/四屏-行情页.html:399-400）⟹ 无稿出处 ⟹ 保持；若恢复 ⟹ 按 7 档吸 4dp。
                val curGap = 5f * u + dp(5.7f)
                var tagY = curY
                if (supCnt == 1 && abs(tagY - supLabelY0) < curGap) {
                    tagY = supLabelY0 + if (curY >= supLabelY0) curGap else -curGap
                }
                tagY = max(m, tagY)      // 别被推出图外
                c.drawText("现价 " + MktData.mkFmt(curPx),
                    plotRight - 4f * u, tagY + 3.5f * u, paint)
            }
        }

        // t110 (1) 时间轴刻度:**按可用宽度算间隔**,间距不够就减刻度。
        // 原先硬编码 5 档,窄屏上「12-30」「4-29」会粘连成「12-304-29」。
        // 稿的 Candles **没有**画时间轴(只画 L471 三条网格线 / L499 两个价格刻度),
        // 这是 App 自有元素; 墨色沿用第三档 ink3, 与稿 L491 现价虚线、
        // L500 价格轴刻度同一档(本盘对应 attr colorFaint)。
        paint.color = mut
        paint.textAlign = Paint.Align.CENTER
        // ⚠⚠ 2026-10-03 用户报「日期字体太粗」——**不是字重,是画笔状态泄漏**:
        //   上面 L457 画第二支撑线时置了 paint.style = STROKE + strokeWidth ≈ 1.5×u(≈3.9px),
        //   而这段画时间轴**没有把 style 复位**,于是 drawText 拿描边画笔把每个字**描了一圈边**,
        //   看着就是"粗了"。⚠ 这是**数据相关**的:只有画出第二支撑(supCnt≥2)时才会 STROKE,
        //   所以有的屏粗、有的屏不粗 —— 容易误判成字体问题。
        //   价格刻度(L319)不粗,是因为它画在 L301 刚设的 FILL 之后、支撑线之前。
        paint.style = Paint.Style.FILL
        paint.strokeWidth = 0f
        // t6 顺手补一个**typeface 泄漏**:这段以前不设 typeface,于是白捡上面最后一次设置 ——
        //   有支撑线时是 `DEFAULT_BOLD`(第一支撑片),没支撑时是 `DEFAULT`,同一处字重随数据变。
        //   时间轴是轴刻度,和价格刻度一样用等宽 `mono`,别让它继承业务文字的字重。
        paint.typeface = mono
        // t6:与价格轴刻度同档(11 稿单位)。时间轴是 App 自有(稿没有),但它是同一类「轴刻度」文字,
        //   改前 dp(9f) 会与已对齐到 10.44dp 的价格刻度差一档,同屏两套刻度字号。
        paint.textSize = 11f * u
        val tickW = 46f * resources.displayMetrics.density
        val usable = plotRight - chartX
        var ticks = 5
        while (ticks > 2 && usable / (ticks - 1) < tickW) ticks--
        // t319: 先把刻度文字都算出来, 有重复才改用带年份的 fmtYM。
        // 起因是 t318 把绘图区加宽、刻度从 3 档变 4 档, 月线跨年时
        // 「2023-02」和「2024-02」都被 fmtMD 印成 "2-1"(t318_geom.png 实锤)。
        val tickIdx = (0 until ticks).map {
            min(n - 1, ((it.toDouble() * (n - 1)) / (ticks - 1.0)).toInt())
        }
        val mdLabels = tickIdx.map { MktData.fmtMD(ks[it].t) }
        val withDup = mdLabels.size != mdLabels.distinct().size
        // ⚠ 2026-10-13 用户实报「时间轴没有年份」：t319 只在刻度**重名**时换 fmtYM，
        //   月线 5 档跨三年恰好不重名（9-1 6-1 3-1 12-1 10-1）就漏了年份。
        //   改为**刻度跨年即强制年月**（周线跨年同理；日线短窗不受影响）。
        val spanYear = MktData.fmtYM(ks[tickIdx.first()].t).substringBefore('-') !=
            MktData.fmtYM(ks[tickIdx.last()].t).substringBefore('-')
        for (k in tickIdx.indices) {
            val lbl = if (withDup || spanYear) MktData.fmtYM(ks[tickIdx[k]].t) else mdLabels[k]
            // ⚠ 2026-10-21 t24（闸 2 裁定 2 ① 1:1 返工为稿值）：5.7dp → **6dp**
            //   稿出处：方向-B/四屏-行情页.html:341 `viewBox="0 0 381.4 248"` + :403-407
            //   `<text class="mk-tick" x="95.8" y="242">25-3</text>` ⟹ 刻度文字基线距 viewBox 底
            //   = 248 − 242 = **6px** ⟹ 1:1 = 6dp（App 的 H ↔ 稿 viewBox 高 248 ⟹ `H - dp(6f)` 即 稿 y=242）。
            //   6dp 不在 7 档 ⟹ 已登记 templates/scan_whitelist.json 的 exceptions（稿值例外）。
            c.drawText(lbl, chartX + tickIdx[k] * cw + cw / 2, H - dp(6f), paint)
        }

        // 十字:横吸K线,纵自由
        val cx0 = crossPx
        val cxi = if (cx0 == null) n - 1
        else max(0, min(n - 1, ((cx0 - chartX - cw / 2) / cw).roundToInt()))
        val info = ks[cxi]
        var legRow = ""
        if (cx0 != null && crossPy != null) {
            val px = chartX + cxi * cw + cw / 2
            val pyy = max(m, min(m + plotH, crossPy!!))
            // ⚠⚠ 2026-11-04 对数坐标的**第三处连带修复**（用户报「第一支撑 3,684.89，
            //   图上却显示 3,847.86」——那正是十字光标的价签）：
            //   下面读价 / 读档位原来是**线性反算**，对数纵轴下反出来的价必然偏高（本次实测 +163）。
            //   ⟹ 价用对数反算；档位按**价格**反算（与剖面同一套 step，不再用像素 rh）。
            val pAt = if (useLog) {
                Math.exp(lnLo + (m + plotH - pyy) / plotH * lnSpan)
            } else {
                vp.lo + (m + plotH - pyy) / plotH * (vp.hi - vp.lo)
            }
            val ri = if (step > 0) ((pAt - vp.lo) / step).toInt().coerceIn(0, rows - 1)
            else max(0, min(rows - 1, rows - 1 - ((pyy - m) / rh).toInt()))
            val pw2 = (vp.hi - vp.lo) / rows
            legRow = "行 " + MktData.mkFmt(vp.lo + pw2 * ri) + "~" +
                MktData.mkFmt(vp.lo + pw2 * (ri + 1)) +
                " · 量 " + MktData.mkVol(vp.rv[ri])
            dashPaint.color = mut
            dashPaint.strokeWidth = 1f
            dashPaint.pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(3f)), 0f)
            c.drawLine(px, m, px, m + plotH, dashPaint)
            c.drawLine(m, pyy, W - m, pyy, dashPaint)
            // 价签反算价：与 y() 同一映射（对数 ⟹ 指数反算），否则价签会和它指向的位置不一致
            paint.textSize = dp(10f)
            paint.textAlign = Paint.Align.LEFT
            val pv = pAt
            val label = MktData.mkFmt(pv)
            val tw = paint.measureText(label) + dp(10f)
            paint.color = mut
            val tx = W - m - tw
            // ⚠ 2026-10-21 t24（闸 2 裁定 2 ② 无稿出处 ⟹ 吸 7 档）：气泡上移量 8.6dp → **8dp**
            val ty = pyy - dp(8f)
            // ⚠ 同上：气泡高 17.2dp → **16dp**（圆角 dp(4f) 不在本次裁定范围）
            c.drawRoundRect(RectF(tx, ty, tx + tw, ty + dp(16f)), dp(4f), dp(4f), paint)
            paint.color = attrColor("colorCard")
            c.drawText(label, tx + dp(5f), ty + dp(12f), paint)
        }

        val chg = (info.c - info.o) / info.o * 100
        val ohlc = MktData.fmtDT(info.t) + " 开 " + MktData.mkFmt(info.o) +
            " 高 " + MktData.mkFmt(info.h) + " 低 " + MktData.mkFmt(info.l) +
            " 收 " + MktData.mkFmt(info.c)
        onInfo?.invoke(MktInfo(ohlc, chg >= 0, legRow, vp.poc, vp.vah, vp.val_, n, supShow,
            info.o, info.h, info.l, info.c, info.v, info.t))
    }

    // ⚠ 墓碑 · t6(2026-10-02)删除 `chartW(W, m, axisW)` 与 `profW(W, m, axisW)`。
    //   两个函数都编码**同一个拍脑袋的比例** `round((W - m - axisW) * 0.32f)`:
    //     · chartW 从来**没有调用点**(死代码);
    //     · profW 只被 onDraw 的 `pw` 用过一次,t6 已把它换成稿 L1822 的 `2 + (v/mx)*30`。
    //   真机实测那个 0.32 的后果:剖面列连间隙占图宽 **28.6%**(稿只有 8.4%),
    //   K 线区被挤到 **51.0%**(稿 69.0%),每根 K 线 9.19dp vs 稿 12.50dp、**窄 26%**。
    //   ⟹ 留着的害处不只是「算错」:它是本文件里唯一一处**看起来像几何、其实是审美拍板**的常数,
    //     下一个人照它抄就会再错一次。几何一律走稿单位换算(`u = W/380`),比例只写在稿那一侧。
    //   ⟹ 别再加回来,也别照它推导任何新的横向几何。
}
