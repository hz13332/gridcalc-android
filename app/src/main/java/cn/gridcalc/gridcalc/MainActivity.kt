package cn.gridcalc.gridcalc

import cn.gridcalc.gridcalc.R
import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.pow
    // 回填/失败/超标场景一律disabled(没有新结果可存)
    /** 2026-10-03 用户裁定（两次）：手续费率 / 维持保证金率 **从界面上完全消失**。
 *  ① 「手续费率和维持保证金额移到设置界面去，这俩基本上固定的，不需要频繁改」
 *     ⟹ 当时搬到设置屏。
 *  ② 「维持保证金率和手续费率可以不要，感觉没啥作用」
 *     ⟹ **再推翻一次**：连设置屏那两行也删掉，屏上再也找不到它们。
 *
 * ⟹ 只保留两个内部常量，各有明确职责，不暴露给用户：
 *    手续费：FEE_TAKER（吃单 0.04%，市价建仓）/ FEE_MAKER（挂单 0%，网格限价单）。
 *    维持保证金率：按杠杆查表 MMR_KNOWN（币安实测），见 mmrOf。
 *
 * ⚠⚠ 关键背景（**用户自己指出来的**）：维持率不是无用参数，它是**杠杆的天花板本身**。
 *    引擎有硬门 `杠杆 × 维持率 ≥ 1 ⟹ 拒绝计算`（OverLeverageException，见其抛出点）。
 *
 * ⚠⚠ 2026-10-17 t15 **更正本段**（只改注释、逻辑一字未动）——
 *    原文写的是「⟹ 改为**由杠杆反推**（用户选定方案）：`mmr = 1/杠杆 × 0.9`，
 *    于是 `杠杆 × mmr ≡ 0.9 < 1` 恒成立」。**那是已被替换掉的旧方案，与实现不符**：
 *    真正在用的是下面 `MMR_KNOWN` 那张**阶梯表**（币安实测档位），
 *    `mmrOf()` 按「≤x 的最大档位边界」取整档、**不插值**。
 *    ⚠ 这条过期注释**已经把人带偏过一次**：t12 把它当成「OverLeverage 不可达」的
 *      数学证明引用，被 t13 实测推翻。故改准以免继续误导。
 *
 * ⚠ 现在真正的「天花板」在哪：`LEV_MAX = 150.0`（见其声明处）把输入钳到 150，
 *    而 150 档的维持率是 **0.4%** ⟹ `max(杠杆 × 维持率) = 150 × 0.004 = 0.6 < 1.0`，
 *    所以那道硬门**永远够不着**、`OverLeverageException` 实际不可达 ——
 *    原因是「杠杆被钳到 150 + 150 档只有 0.4%」，**不是**上面那个 `1/杠杆 × 0.9`。
 *    ⚠ 若日后放开 `LEV_MAX` 或改动档位表，这个结论会变 —— 改表时请一并复核本段。
 *
 * ⚠ 这**偏离稿**：稿 L597-609 有 fee / mmr 两个输入，稿 L783-784 有设置屏只读回显。
 *    是产品裁定，不是移植对齐。
 * ⚠ 风险已核对：linkPhigh 只读预测价与最低价，**不碰 fee/mmr**；
 *    唯一受影响的是「爆仓价」的取值 —— 而它正是维持率的定义本身，由杠杆反推是自洽的。
 * ⚠ 这两个常量必须放在**文件顶层**：Kotlin 的 `const val` 只允许出现在
 *    顶层 / 具名 object / companion object。写成 class 成员（`private const val`）
 *    编译期直接报「Const 'val' are only allowed on top level, in named objects,
 *    or in companion objects」—— 本轮踩过一次。
 */
/**
 * ⚠⚠ 2026-10-03 手续费**按吃单/挂单拆开**（用户提供的币安实测值）。
 *
 * 改前是一刀切 [FEE_FIXED] = 0.0005，建仓/加仓/卖出**全都**按 0.05% 扣。
 * 用户给出实际费率是**两种不同性质**的：
 *   · 吃单 taker（限价单之外，成交即吃单）= **0.04%**
 *   · 挂单 maker（网格挂的限价单，挂在盘口等着成交）= **0%**
 *
 * ⟹ 对应到本引擎的三笔交易：
 *   建仓（在触发价市价开仓）  → **吃单 0.04%**
 *   网格加仓（跌穿一格补仓）  → **挂单 0%**
 *   网格卖出（涨破一格出货）  → **挂单 0%**
 *
 * ⚠ **强平本身是吃单**，但引擎的爆仓价公式只解"权益 ≤ 维持率×仓位"这个边界，
 *   不再叠一层强平手续费 —— 那属于成交细节，不改变强平**价**。
 * ⚠ 这是**偏离稿**：稿 L597-609 有 fee 输入框且全程同价。产品裁定，非移植对齐。
 * ⚠ 若日后费率可改：这里就是唯一出处，改两个常量即可。
 */
private const val FEE_TAKER = 0.0004   // 吃单 0.04%（市价建仓）
private const val FEE_MAKER = 0.0       // 挂单 0%（网格限价单）

/**
 * ⚠⚠ 2026-10-03 **维持保证金率完整对照表**（用户提供，币安实测值）。
 *
 * 原话：「150：0.40%。100：0.5%。75：0.65%。50：1%。25：2%。20：2.5%。10：5%。5：10%」
 *
 * ⚠⚠ **推翻了本文件此前两次的判断**，两次都记在这里免得走回头路：
 *   ① 我曾断言「维持率不该跟杠杆挂钩，是独立常数」⟹ 写下 `MMR_FIXED = 0.0065`。
 *      **错** —— 它随杠杆单调下降，15x 是 5%，差 7.7 倍。
 *   ② 我曾用**两个点（15x=5% / 75x=0.65%）线性插值**，并把 75x 以上封顶 0.65%。
 *      **也错** —— 真表在 75x 以上继续降到 0.40%（150x），封顶会明显高估爆仓价；
 *      两点插值在 5x~20x 区间也偏得厉害（真值 10x=5%、5x=10%，插值给 5.7%/6.3%）。
 *
 * ⟹ 现在是**真表 + 按档取值**（阶梯函数，用户裁定「一档一档的算」，非插值）。
 *
 * 与两个已实测点一致（改表不影响它们）：15x=5%（1000SATS 组吻合）、
 * 75x=0.65%（XAG 组差 0.2%）。
 */
private val MMR_KNOWN: List<Pair<Double, Double>> = listOf(
    5.0 to 0.10,
    10.0 to 0.05,
    20.0 to 0.025,
    25.0 to 0.02,
    50.0 to 0.01,
    75.0 to 0.0065,
    100.0 to 0.005,
    150.0 to 0.004
)

/**
 * 按杠杆取维持保证金率：**表内精确命中 → 原值；表间 → 线性插值；表外 → 取最近端点**。
 *
 * ⚠ 表外取端点是**有意的保守选择**，不是偷懒：
 *   · 杠杆 < 5x  → 取 5x 的 10%。维持率越高 = 爆仓越早 = 报得更保守。
 *     若继续外推，1x 会算出 ~11.8%，那是凭空捏一个没测过的数。
 *   · 杠杆 > 150x → 取 150x 的 0.40%。LEV_MAX 就是 150，正常到不了这里。
 *
 * ⚠ 若日后拿到更完整档位表：**只改 [MMR_KNOWN]，本函数不动**。
 */
private fun mmrOf(lev: Double): Double {
    val x = if (lev.isFinite() && lev > 0) lev else 1.0
    val known = MMR_KNOWN
    // ⚠⚠⚠ 2026-10-03 用户纠正：「错，还是2%，他是一档一档的算的」
    //   ⟹ **阶梯函数，不是插值。** 26x 落在 25x 那一档里，所以是 **2%**，不是插出来的 2.1%。
    //
    // 【档位边界怎么定】表里每个点是**该档的上界**：
    //     杠杆 ≤ 5   → 10%
    //     杠杆 ≤ 10  → 5%
    //     杠杆 ≤ 20  → 2.5%
    //     杠杆 ≤ 25  → 2%      ← 26 落这里
    //     杠杆 ≤ 50  → 1%
    //     杠杆 ≤ 75  → 0.65%
    //     杠杆 ≤ 100 → 0.5%
    //     杠杆 ≤ 150 → 0.4%
    //
    // ⟹ 取「**≤ x 的最大档位边界**」那档的值（floor 到档）。
    // ⚠ 改前是线性插值，错在：档位是**离散分档**，中间没有"半档"这回事，
    //   插值出来的 2.1% 是**现实中不存在的数**，会算出一个假的爆仓价。
    var v = known[0].second
    for ((edge, rate) in known) {
        if (x <= edge) { v = rate; break }
        v = rate          // 超过本档边界就继续往下一档看
    }
    return v
}


class MainActivity : Activity() {

    private lateinit var body: FrameLayout
    private var sbTop = 0 // 状态栏inset:真融合后垫在各页根ScrollView上(静止位),换页即补
    private lateinit var calcPage: View
    private lateinit var settingsPage: View
    private lateinit var mktPage: View
    private lateinit var mktPanel: MktPanel
    private lateinit var favPage: View
    private lateinit var favPanel: FavPanel
    // t130:tab 容器在 activity_main.xml 里改成了 FrameLayout(要叠顶条),故类型改 View
    private lateinit var tabFav: View
    // t130:tab 容器在 activity_main.xml 里改成了 FrameLayout(要叠顶条),故类型改 View
    private lateinit var tabMkt: View   // t130 第 4 个 tab「行情」
    // t130:tab 容器在 activity_main.xml 里改成了 FrameLayout(要叠顶条),故类型改 View
    private lateinit var tabCalc: View
    // t130:tab 容器在 activity_main.xml 里改成了 FrameLayout(要叠顶条),故类型改 View
    private lateinit var tabSetup: View
    // t130:按稿 L240-249,tab 改纯文字(无图标),故三个 Icon 字段已删
    private lateinit var tabCalcLabel: TextView
    private lateinit var tabSetupLabel: TextView
    private lateinit var tabFavLabel: TextView
    private lateinit var tabMktLabel: TextView
    // t130:顶条(稿 L247: left/right 28%、height 2、主色;未选中透明)
    private lateinit var tabFavBar: View
    private lateinit var tabMktBar: View
    private lateinit var tabCalcBar: View
    private lateinit var tabSetupBar: View

    private val inp = mutableMapOf<String, EditText>()
    // ⚠⚠⚠ [feeInp] / [mmrInp] 已删除（2026-10-03）。
    //
    // 【它们把「保存 / 恢复」两条路都打断了 —— 用户实报「数据清零，要重新计算」】
    // 这两个是 `lateinit` 却**从来没被赋值**：计算屏那两行费率输入框早就删了，
    // 连 `findViewById` 一起删了，可**代码引用还留着**，于是：
    //     logRec()      读 feeInp.text      → UninitializedPropertyAccessException（保存崩）
    //     restoreCalc() 写 feeInp.setText   → 同样崩（恢复崩）
    //     onSaveInstanceState / onRestore   → 同样崩
    // ⟹ 记录**根本存不进去**，所以进品种永远是空的 —— 看起来像「没有这功能」，
    //   实际是**存和取两侧同时抛异常**。
    //
    // 【为什么编译器没报】lateinit 的赋值检查发生在**运行时**，编译期只要不直接取值就放过。
    //   这也是为什么删输入框时「忘了摘引用」能一路溜到今天。
    //
    // ⟹ 费率现在由顶层 [FEE_TAKER] / [FEE_MAKER] 两个常量提供，
    //   **不再需要存盘**；旧记录里残留的 "fee"/"mmr" 字段读出来直接忽略即可。
    //
    // ⚠ 教训（第二次同款错误）：删布局节点必须**同时删它的 Kotlin 引用**，
    //   哪怕编译器不报。第一次是 calc 屏删 fee/mmr 行的 findViewById 漏了，
    //   这次是这三个读写点漏了 —— 找齐方式是 `Select-String feeInp|mmrInp`，
    //   **不是**等编译器报错。

    // 稿 L783-784: 设置页那两行是计算页 in_fee / in_mmr 的【只读回显】。
    // 改前它们在 Kotlin 里零引用, 一直显示布局里写死的 0.05 / 1。
    // ⚠⚠ 2026-10-03 **设置屏这两行也删了**（用户第二次推翻，见上方常量区）。
        //   page_settings.xml 里 set_fee_ro / set_mmr_ro 两个节点已删除。
        //   ⟹ `setFeeRo` / `setMmrRo` 两个字段与这里的绑定**一并删除** ——
        //     节点不存在时 findViewById 返回 null，任何读写都会 NPE。
        //   ⟹ 费率不再是用户可改项，改由顶层 FEE_TAKER / FEE_MAKER 提供（见顶部常量区）。
        //
        // ⚠ t138 那次是「设置页 → 计算页」，t237 那次是「计算页 → 设置页」，
        //   本轮是**彻底删掉**。三轮搬了两个来回，净结果 = 都没了。
        //   这一段曾经横跨三个文件，现在只剩上面那段墓碑。
    private lateinit var dblBtn: android.widget.Switch
    private var dbl = false
    // t36（2026-10-29）：轨道颜色过渡的**当前帧记账**（0 = 还没有播过动画，起点用另一端终色）。
    private var dblTrackAnim: android.animation.ValueAnimator? = null
    private var dblTrackBg = 0
    private var dblTrackStroke = 0
    /** false = 首屏初始化阶段（此期间的状态回填不播颜色过渡，见 onCreate 的 dblBtn.post）。 */
    private var dblTrackReady = false
    private lateinit var calcHeroBox: View   // t141:净收益整块(idle 时 GONE)
private lateinit var calcIdleHint: TextView
    private lateinit var statTilesTopline: View     // t28 B4: 四格那一行的上边线(稿 L676)
    private lateinit var calcOverlineTopline: View   // t28 B3: StateLine 的上边线(稿 L290)
    private lateinit var toggleRow: View          // t23:折叠口那一行(稿 L688 的容器)
    private lateinit var toggleRowTopline: View   // t23:该行上边线
    private lateinit var calcOverline: View  // t141:over 态 StateLine
    private lateinit var hero: TextView
    private lateinit var heroSub: TextView
    private val statVals = mutableMapOf<String, TextView>()
    private lateinit var detSum: TextView
    private lateinit var rows: LinearLayout
    private lateinit var themeFollow: View
    private lateinit var themeLight: View
    private lateinit var themeDark: View

    // ⚠ 2026-10-28 t4 · `autoSw`（设置页「启动时刷新一次」的 Switch）字段已删除：
    //   节点 `set_auto_sw` 已从 page_settings.xml 整行移除（用户裁定），
    //   再留着 findViewById 会拿到 null ⟹ 布局与接线必须成对下线（本仓 t298/t316 的教训）。

    private var tab = "fav"
    private var mode = "follow"

    // ---------- 行情→计算软联动(稿L393-414:LINK/PH) ----------
    private var lkPrev: Pair<Double, Double?>? = null // 上次联动写入的一对(sup1, sup2?)；sup2 可空（单支撑品种无上限档）
    private var lkCap: Double? = null // 可选目标上限(sup2;×解除后同对不再回写)
    private var currentMarketSupport2ForLine: Double? = null // 当前行情页真实显示的支撑，供爆仓线副行使用
    private var currentCalcLiqForLine: Double? = null // 当前计算结果的主爆仓价；idle/清空时置空
    private var lkPh: Double? = null // 上次由机构目标均价写入的最高价(同值不扰手改)
    private lateinit var statLiqCard: View // 爆仓卡(超上限画红框)
    private lateinit var statLiqGap: TextView  // 爆仓线副行：当前行情第二支撑，不跟随可选 lkCap 隐藏
    /**
     * t9：「爆仓价」格的**原始面**（XML 的 `@drawable/calc_tile_bg`）。
     * 为什么需要它：v1 的 `statLiqCard.background = null` 本意是「清掉 done 态标的红框」，
     * 却把这张格的面**永久**清掉了（`background = null` 是不可逆的，XML 面回不来）。
     * bind 期先存下实例，idle 路径改用它还原 ⟹ 红框照清、面照在。
     * ⚠ 必须在那几处 `background = null` **之前**取（见 bindViews 里紧跟 findViewById 的那行）。
     */
    private var statLiqBg0: android.graphics.drawable.Drawable? = null
    private lateinit var statTiles: View   // t153 ⑩:四个 tile 的横向容器(队长在 XML 加的 id)
    private lateinit var statCap: View // 目标上限行(默认收起)
    private lateinit var statCapv: TextView
    private lateinit var capX: Button
    private lateinit var calcErr: TextView // 稿#err 报错行
    /** t304: 程序改写用户输入时那行说明(见 t304Note)。 */
    private lateinit var calcNote: TextView

    // ---------- 稿§1.1 「保存数据」按钮(阻塞项):计算不再即落库 ----------
    // 默认disabled;计算成功→enabled(暂存待存闭包);点击→落库一条+文案
    // 「已保存，继续计算后可再存」1.6s后复原「保存数据」;再次计算→重新enabled;
    private lateinit var saveBtn: Button
    // ⚠ 2026-11-04：保存按钮「常态底 / 常态字色」升成字段，供 [saveBtnSaved] 在 idle 里复用。
    private var saveBtnBg0: android.graphics.drawable.Drawable? = null
    private var saveBtnInk0: Int = 0

    // ⚠ 2026-10-05 新增：「清空数据」按钮的句柄。
    //   改前 [clearCurrentSymRecs] 把「已删除 N 条 / 没有已保存记录」写在了 **saveBtn** 上
    //   ——用户点的是「清空数据」，提示却出现在旁边「保存数据」按钮上（实机确认）。
    //   不是文案问题，是**反馈落在了你没点的那个按钮上**，等于没反馈。
    private lateinit var clearRecBtn: Button
    // ⚠ 费率的说明见文件顶层 [FEE_TAKER] / [FEE_MAKER]（class 之前）。
    //   这里只留一句指向，避免同一段墓碑散在两处互相漂移。
    // ⚠⚠ [mmrFromLev] 已作废并删除（2026-10-03）：那个 `0.9/杠杆` 把维持率和杠杆绑死，
    //   是错的。维持率改用按杠杆查表 MMR_KNOWN（币安实测），见顶层 mmrOf。
    //   ⚠ 若日后要改档位表：只改顶层 MMR_KNOWN 那一处，这里不用动。
    //     两处缺一即失效 —— 爆仓价只由 mmr 决定，别处传进来的一律不算数。

    // t104 新控件(计算页重做)
    // ⚠⚠ 2026-10-03 **用户裁定：杠杆倍率改为填空，不要滑杆**
    //   （原话：「把杠杠倍率改为填空，不要滑杆」）。
    //   ⟹ `levBar`(SeekBar) 的字段与其全部用法**已删**；节点也不在 page_calc.xml 里了。
    //   ⟹ `levText` 由 **TextView 改回 EditText**，它是杠杆的唯一入口。
    //   ⚠ 它曾被改成 TextView，理由写的是「稿 L643 是只读回显」—— 那是照稿的结论，
    //     本次按用户裁定改回可编辑。**别照旧注释再改回去。**
    //   唯一写入口仍是 t341SetLev（输入/回填/状态恢复都走它），读入口是 onCalc 的 getNumRo。
    private lateinit var levText: EditText
    private lateinit var sideLong: TextView
    // ⚠ 2026-10-03 用户裁定删除「做空」：page_calc.xml 里的 side_short 节点已移除，
    //   故 `sideShort` 这个字段**连同它的 findViewById 与 setOnClickListener 一起删了**
    //   —— 节点不存在时 findViewById 返回 null，那行会 NPE。
    //   ⚠ `sideShortMode` 与整套做空公式**一个都没删**（在 t105..t341 里大量引用），
    //   屏上只是点不到它，默认恒为 false(做多)。彻底移除做空分支是另一件事，**没做**。
    private lateinit var moreToggle: TextView
    private lateinit var moreBox: View
    // ⚠ 2026-10-19 r8 · C-4：折叠口改竖排后，第一段（展开更多）多了一个容器与两个 chevron。
    //   `more_row` 承载卡面（btn_ghost）/ 内边距 / chevron，并挂同一段点击监听（稿 `.calc-collapse__btn
    //   { width:100% }` ⟹ 整段可点）；两个 chevron 是 8dp×8dp 的 View（底 = `calc_chev`），
    //   旋转角由本文件的折叠口监听设置（折叠 45° / 展开 -135°，稿 `四屏-计算页.html:181/:184`）。
    private lateinit var moreRow: View
    private lateinit var moreChev: View
    private lateinit var detChev: View
    // #119 稿 L693-697: 第二个折叠口是一个**行容器**, 标题与右侧行数都在它里面;
    // 所以 det_toggle 从 TextView 变成 LinearLayout(行内标题不给 id), ladder_note 搬了进去。
    // 点击区与可见性语义不变, 只多了「行数也归这个点击区管」这一条。
    private lateinit var detToggle: View
private lateinit var ladderNote: TextView
// t167: -1 = 当前没有明细(未计算 / 记录回填),此时不显示行数,只显示「—」
private var lastLines: Int = -1

    /**
     * 稿 L696: 折叠口右侧 = ladder ? '收起' : R.lines.length
     * 改前这个赋值只出现在【detToggle 点击回调】里, 于是不点就永远是布局默认的「—」,
     * 而 onCalc 算出的 lastLines 没人去用。抽成函数, 三处共用一份。
     */
    /**
     * ⚠⚠ 2026-10-03 **本函数已作废，两个费率反向搬回设置屏后它不再有任何作用。**
     *
     * 原来它把计算页 in_fee/in_mmr 的值**同步到**设置屏那两行只读回显（稿 L783-784 的形态）。
     * 用户裁定「手续费率和维持保证金额移到设置界面去」⟹
     * **设置屏那两行自己就是编辑源**，不再需要从别处同步。
     *
     * 保留一个空实现而不是删函数：调用点多在「算完之后刷新一遍」的路径上，
     * 删函数要连带改每一处调用；留空实现让那些路径继续跑、什么也不做，风险更低。
     * ⚠ 若日后费率又搬回计算屏：删掉这个空实现，恢复 in_fee/in_mmr 两个节点与绑定。
     */
    /**
 * ⚠⚠⚠ 2026-10-03 **换品种时清空计算页输入框**（用户实报：「美光里保存的数据在 BTC 里出现了」）。
 *
 * 【为什么必须清】`idle()` 只清**结果区**（hero / idle 提示），**从不碰输入框**。
 *   于是：进美光 → 有记录 → 回填把 500/732.86/800/0.12/50/8 全填进输入框；
 *         再进 BTC → **没有记录** → `restoreCalc` 走 `rec == null` 分支，只调 idle()；
 *         ⟹ 美光的输入值原封不动留在屏上，而 `linkPhigh` 又把最高价刷成 BTC 的 114637。
 *   屏上就成了「保证金 500 + 最低价 732.86(美光) + 最高价 114637(比特币)」的拼接货。
 *
 * 【正确的顺序】**先清 → 取数 → 联动填 → 回填记录**
 *   清空必须发生在**取数之前**。若放在 restoreCalc 里清，会把 `linkCalc` 刚填好的
 *   最低价/最高价一起抹掉（restoreCalc 由 finishLoad 调用，在 render 之后）。
 *   ⟹ 所以挂在 mkLoad 的 `symChanged` 上，那是取数之前。
 *
 * 【清到什么程度】全清。保证金/最低价/最高价/网格数量/触发价/每格数量/杠杆 —— 七项全是**品种专属**，
 *   没有任何一项可以跨品种沿用。杠杆尤其要清：它决定维持率与爆仓价，串了就全错。
 */
fun clearCalcInputs() {
        try {
            // 清空当前品种时同时撤销回填、联动和自动网格的生命周期状态；否则
            // 下一次行情回调可能把旧品种的值/结果重新写回当前页。
            lkPrev = null
            lkCap = null
            currentMarketSupport2ForLine = null
            currentCalcLiqForLine = null
            lkPh = null
            restoreGuardSym = null
            restoreSeq++
            // 换品种：解除旧品种的联动屏障（清空按钮自己会在清空后重新立屏障）。
            linkBlockedSeq = -1
            linkBlockedSym = ""
            stepThrSeq++
            justRestored = false
            nManual = false
            nManualSym = ""
            stepThr = null
            stepThrSym = ""
            pendingRec = null
            gridMode = "geo"
            sideShortMode = false
            dbl = false
            lastLines = -1
            programWrite {
                for ((_, e) in inp) e.setText("")
                if (::levText.isInitialized) levText.setText("")
                if (::dblBtn.isInitialized) dblBtn.isChecked = false
            }
            if (::statCap.isInitialized) paintCap()
            if (::hero.isInitialized && ::rows.isInitialized) idle()
        } catch (_: Throwable) {
        }
    }

    fun restoreCalc(sym: String) {
        try {
            val restoreId = ++restoreSeq
            val key = normSym(sym)
            val rec = recs.lastOrNull { normSym(it.optString("sym")) == key }
            if (rec == null) {
                // ③ 清零点:无记录品种**不得置位**,否则该品种的联动会被永久误挡
                restoreGuardSym = null
                idle()
                // 行情数据已经完成渲染/link 后才到这里；无记录也要走一次幂等计算，
                // 但只允许当前品种消费这次异步回调，避免切品种后把旧结果画回来。
                uiHandler.post {
                    if (restoreId == restoreSeq && normSym(curCalcSym()) == key) onCalc()
                }
                return
            }
            // D4/t89:有记录→进入回填态,联动(linkCalc/linkPhigh)只更新 lkPrev/lkPh 不写输入框,
            // 直到用户下一次真实输入或点「计算」
            restoreGuardSym = key
            // D2/D4/D9 共用闸:整段 setText 都在闸内,watcher 的 afterTextChanged→idle()
            // 不会把刚回填完的结果区自己清掉,linkCalc 也不会覆盖这些值
            programWrite {
            inp["C"]!!.setText(numStr(rec.optDouble("C")))
            t341SetLev(rec.optDouble("L"))
            inp["Pl"]!!.setText(numStr(rec.optDouble("Pl")))
            inp["Ph"]!!.setText(numStr(rec.optDouble("Ph")))
            inp["N"]!!.setText(rec.optInt("N").toString())
            inp["Po"]!!.setText(numStr(rec.optDouble("Po")))
            inp["q"]!!.setText(numStr(rec.optDouble("q")))
            // "fee"/"mmr" 不再回填：两个输入框已删（墓碑见 [feeInp] 声明处）
            // 同上
            // 设置页费率回显一并删除（那一块节点已删）
            }
            gridMode = rec.optString("g").ifEmpty { "geo" }
            dbl = rec.optString("dbl") == "是"
            paintDbl()
            lkCap = if (rec.isNull("cap")) null
            else rec.optDouble("cap").takeIf { it > 0 }
            paintCap()
            // 结果区=保存时的值,不重新计算;明细行/明细行清掉(明细不落盘)
            rows.removeAllViews()
            detSum.setTextColor(attrColor("colorSub"))
            detSum.text = "明细在该品种重新计算后显示"
        lastLines = -1   // t167:记录回填不带明细(上面已 removeAllViews),不报行数
            val net = rec.optDouble("net")
            val roe = rec.optDouble("roe")
            hero.setTextColor(if (net >= 0) attrColor("colorTeal") else attrColor("colorRed"))
            hero.text = "%+.2f USD".format(net)
            heroSub.setTextColor(attrColor("colorSub"))
            val cur = MktData.FX.curOf(rec.optString("sym"))
            // D5:读**记录里存的那次汇率**(logRec 已写 fx),不要用当前汇率——否则汇率一变,
            // 回填出来的旧记录会显示成今天的价格口径,自相矛盾。
            // 老记录没有 fx 字段才回落当前汇率(按现状显示)。
            val fxRec = rec.optString("fx").toDoubleOrNull()
            val fxPart = if (cur != "USD") {
                val r = fxRec ?: MktData.FX.rateOf(cur)
                if (r != null && r > 0)
                    "（原币 $cur · 汇率 ${String.format(java.util.Locale.US, "%,.4f", r).trimEnd('0').trimEnd('.')}）"
                else "（原币 $cur）"
            } else ""
            heroSub.text =
                "收益率 %+.2f%% · %d卖%d买 · %s 已保存于 %s%s（点「计算」可重算）".format(
                    roe, rec.optInt("M"), rec.optInt("B"),
                    rec.optString("sym"), rec.optString("t"), fxPart)
            statVals["liq"]!!.text = if (rec.isNull("liq")) "不爆仓"
            else {
                val x = rec.optDouble("liq")
                if (x <= 0) "0.00" else fmt(x)
            }
            currentCalcLiqForLine = if (rec.isNull("liq")) null else rec.optDouble("liq")
            paintLiqGap()
            statVals["roe"]!!.text = (if (roe >= 0) "+" else "\u2212") + String.format("%.2f%%", Math.abs(roe))
            statVals["pos"]!!.text = "%.4f".format(rec.optDouble("pos"))
            statVals["sell"]!!.text = fmt(rec.optDouble("sell"))
            showErr(null)
            // ⚠⚠ 2026-10-17 t15 · **回填态也要有面**（t13 新发现 ⑨）。
            //   这里原是 `statLiqCard.background = null`：回填（restoreCalc）走的是 done 语义，
            //   于是刚回填完那张格又「裸在卡白上」，与其余三格不一致 —— 与 t12 修的
            //   onCalc 成功路径是**同一处病灶的第二现场**。
            //   改为还原 bind 期存下的原始面（[statLiqBg0]），与 idle / onCalc 两条路径同一手法。
            //   ⚠ 回填态不重算、没有 over 判定，所以这里**不需要** over 分支；
            //     `markLiqBad()` 与它的红框逻辑**一字未动**。
            if (::statLiqCard.isInitialized && statLiqBg0 != null) statLiqCard.background = statLiqBg0
            statVals["liq"]?.setTextColor(attrColor("colorInk"))
            // ⚠ 2026-10-05 置位「刚回填」：紧随其后的那次自动重算（步长阈值到位触发）
            //   就不该把「已保存」翻成「● 未保存」。见 [justRestored]。
            justRestored = true
            saveIdle() // 稿§1.1 回填场景按钮保持disabled(没有新结果可存)
      // ⚠⚠⚠ 2026-10-05 **回填完直接算一次，别等用户按回车**
      //   （用户真机截图：七个字段全都填好了，结果区却停在「输入参数后回车计算」）。
      //   【为什么】改前自动计算**只有一个入口**：`stepThrFor` 回调里
      //     `if (stepThr != null && !nManual) onCalc()` ⟹ **阈值拿不到就永远不自动算**。
      //   而阈值正是那个「刚加的品种经常没有」的东西 ⟹ 两个毛病叠在一起。
      //   【改法】回填成功即算一次，不以阈值为前提（没阈值只是不自动改格数）。
      //   [justRestored] 已置位，这一次不会把「已保存」翻成「● 未保存」。
   uiHandler.post {
       if (restoreId == restoreSeq && normSym(curCalcSym()) == key) onCalc()
   }
        } catch (e: Exception) {
            // ⚠⚠⚠ 2026-10-03 **删掉空 catch**。
            //   原来是 `catch (_: Exception) {}` —— 吞掉一切且不打日志。
            //   后果：回填链上任何一步抛异常，界面上只表现为「**什么都没发生**」，
            //   而日志里一条线索都没有。用户实报「二次点进去没有出来」，查了半天才定位到这里。
            // ⟹ 现在**记日志 + 在屏上报错**，让它可见。
            android.util.Log.e("RESTORE", "restoreCalc 失败 sym=" + normSym(sym), e)
            // 失败也不主动清闸：只有用户 TextWatcher/显式计算才能结束回填态，
            // 避免异常后的旧行情回调继续覆盖可能已经部分回填的输入。
            showErr("这条记录回填失败：" + (e.message ?: e.javaClass.simpleName))
        }
    }
    private fun t237SyncFeeToSettings() {
        // 作废，见上方说明。
    }

    /**
     * t7：输入框的显示格式 —— 照稿 v2.html:2201 NumField 的非编辑态：
     * ```
     *   dp ? val.toLocaleString('en-US',{min:dp,max:dp})
     *       : Math.round(val).toLocaleString('en-US')
     * ```
     * @param dp = 2  补到两位小数 + 千分位（cash/lo/hi/trig/fee/mm）
     * @param dp = 0  取整 + 千分位（网格数量）
     * @param dp < 0  **只加千分位、不取整** —— 每格数量 q 走这条。
     *   为什么 q 不能照稿取整：稿那行 `Math.round(val)` 会把 q=0.001 **抹成 0**，
     *   而 q=0.001 是这个 App 明确支持的加密最小单位（MainActivity.kt 的 #100 注释
     *   专门为它写了「不要顺手把 q 也钳到 1」，t337 实测钳到 1 会让收益率从 +58.52%
     *   变成 +87780.48%）。照稿取整等于把这个已裁决的边界改回去 —— 那是拿一条
     *   视觉对齐去换一条业务正确性，所以 q 这里有意偏离稿并在此记账。
     */
    private fun t7formatField(v: Double, dp: Int): String {
        if (!v.isFinite()) return v.toString()
        if (dp == 0) return String.format(java.util.Locale.US, "%,d", Math.round(v))
        if (dp < 0) {
            val s = String.format(java.util.Locale.US, "%,.8f", v)
            return s.trimEnd('0').trimEnd('.')
        }
        return String.format(java.util.Locale.US, "%,.${dp}f", v)
    }

    /** t7：把输入框当前文本转回**数值**（剥掉千分位与空格）；空/非数字返回 null。 */
    private fun t7num(e: EditText): Double? =
        e.text.toString().trim().replace(",", "").replace(" ", "").toDoubleOrNull()

    /** t7：把输入框当前文本转回**纯数值串**（无千分位、无补位）；解析不出来返回 null。 */

    /**
     * t7：按稿 L2201 重排输入框的显示。`dp = null` 表示这个框不参与格式化。
     * ⚠ **只在失焦与绑定时排，不在输入过程中排** —— 这与稿的行为是同一件事：
     * 稿的 NumField 在 `editing===k` 时渲染的是裸 `<input type=number>`，
     * 只有非编辑态那个 `<span>` 才格式化（v2.html:2190-2203）。
     * 边打边格式化会让「打 1 立刻变 1.00」而无法继续输入，是明确的倒退。
     * ⚠ 写值必须走 programWrite：否则 afterTextChanged → idle() 会把刚算出来的
     * 结果区清掉（本文件 suppressReset 的注释 L153-156 讲的就是这件事）。
     *   这里重排的是【同一个数的另一种写法】，不是参数变了，不该触发 idle()。
     */
    private fun t7formatField(edit: EditText, dp: Int?) {
        if (dp == null) return
        val v = t7num(edit) ?: return                 // 空/非数字：一个字都不动
        val s = t7formatField(v, dp)
        if (s == edit.text.toString()) return         // 已经排好了，别白写一次
        programWrite { edit.setText(s) }
    }

    private fun t229SyncLadderNote() {
        if (!::ladderNote.isInitialized) return
        val expanded = ::detBox.isInitialized && detBox.visibility == View.VISIBLE
        ladderNote.text = if (expanded) "收起" else if (lastLines > 0) "$lastLines" else "—"
    }
    private lateinit var detBox: View
    private var sideShortMode = false
    private var pendingRec: (() -> Unit)? = null
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // ---------- 前台定时拉取（2026-10-27 用户裁定） ----------
    // 原话：「如果 app 在后台或者没有存活就不拉取；如果 app 在前台或者在主要运行的时候，
    //        就每隔 5 分钟拉取一次」。
    //
    // 【为什么只挂生命周期、不引入任何后台设施】现有取数**全是事件驱动**（换品种/换周期/
    //   点确认/首次进自选），没有任何周期入口；这里加的定时器是**唯一**的周期入口，
    //   而且只在 onResume → onPause 之间活着 ⟹ 进后台立刻停表并移除排队中的 tick，
    //   进程被杀更是什么都不会发生。**没有** WorkManager / AlarmManager / 前台服务，
    //   也不注册任何后台唤醒 —— 「没有存活就不拉取」是结构上成立的，不是靠判断挡的。
    private val AUTOPULL_MS = 5 * 60 * 1000L
    /** 上一次周期拉取（或前台首次进入）的时刻；跨 onPause/onResume 保留，用来判断回来时要不要立刻补一次。 */
    private var lastAutoPullAt = 0L
    /** 前台闸。为 false 时 tick 即使被误触发也什么都不做，且不再续链。 */
    private var autoPullOn = false
    private val autoPullTick = object : Runnable {
        override fun run() {
            if (!autoPullOn) return
            autoPull()
            uiHandler.postDelayed(this, AUTOPULL_MS)
        }
    }

    /**
     * 前台每 5 分钟真实刷新：当前页无关，整张自选表由 MktData.acquire(refresh=true) 更新；
     * 若当前在行情页，再强制刷新当前品种（mkLoadQuiet 会绕过内存 K 线缓存，仍保留磁盘留底作临时显示）。
     * 计算/设置页本身不显示行情，阈值与汇率仍由既有事件路径提供。
     */
    private fun autoPull() {
        lastAutoPullAt = System.currentTimeMillis()
        try {
            // ⚠⚠ 2026-11-02 用户裁定：刷新由唯一数据引擎负责，不由当前页决定。
            //   每一拍都对整张自选表调用 MktData.acquire(refresh=true)；如果行情页正在显示，
            //   mkLoadQuiet 也必须绕过内存缓存再取。取数失败则保留旧批次及旧时间，不能重算后冒充新鲜。
            if (::favPanel.isInitialized) {
                android.util.Log.i("AutoPull", "tick watchlist")
                favPanel.refreshFavDist(net = true)
            }
            if (tab == "mkt" && ::mktPanel.isInitialized) {
                android.util.Log.i("AutoPull", "tick mkt " + mktPanel.curSym())
                mktPanel.mkLoadQuiet()
            }
        } catch (_: Throwable) {
        }
    }

    private fun saveIdle() {
        pendingRec = null
        if (::saveBtn.isInitialized) {
            saveBtn.text = "保存数据"
        }
    }

    // v4 D2/D4/D9 **共用一个闸**:程序正在往输入框写值(restoreCalc 回填)时,
    // 忽略连带的副作用——afterTextChanged→idle() 会把刚回填的结果区自己清掉,
    // linkCalc 也会用支撑位覆盖刚回填的最低价/目标上限。
    // 置位只包住自己那次 setText,写完立刻清零,用户下一次真实输入照常触发 idle()。
    private var suppressReset = false

    /** 程序写值:包住一组 setText,期间忽略 watcher 副作用 */
    private inline fun <T> programWrite(block: () -> T): T {
        suppressReset = true
        try {
            return block()
        } finally {
            suppressReset = false
        }
    }

    // v4 D4(t89) **回填态标志**:与 suppressReset 分工不同,不是「程序正在写值」的瞬时闸,
    // 而是「本品种的输入框现在是记录里保存的值,在用户下一次真实动作之前不许被联动覆盖」。
    //
    // 为什么 suppressReset 顶不上(这是 t86 的假绿根因):
    //   MktView.setData→redraw→invalidate() 是**排队到下一帧**的,renderAgg 末尾的
    //   finishLoad→restoreCalc 跑完时 chart.onInfo→linkCalc **根本还没发生**;
    //   下一帧再进来 programWrite 的 finally 早已把 suppressReset 清零,
    //   于是 linkCalc 用支撑位把刚回填的最低价/目标上限又覆盖一遍。
    //   linkPhigh 更远——它来自 paintTgt 的另一个异步回调,时机完全不可控。
    // 所以需要一个**活到用户下一次真实输入**为止的标志,而不是瞬时闸。
    // suppressReset 保留不动(对 D2/D9 的程序写值自激仍然必要且正确),只是不再承担 D4。
    private var restoreGuardSym: String? = null
    // 回填后的自动计算序号：切品种/清空时使排队的旧回调失效。
    private var restoreSeq: Long = 0L
    // 显式清空后的行情联动失效边界。
    // ⚠ 2026-10-26（第 4 轮）：判据从「按品种永久闭嘴」改为**按请求批次代号**。
    //   【为什么】清空按钮只该拒「清空那一刻已经发出（含已画在屏上、随时会重绘）的那一批」回调；
    //     而清空后从自选**重新进入同一品种**会产生新批次（mkLoad 里 reqSeq 自增），
    //     那一轮的 linkCalc/linkPhigh 必须放行 —— 否则 Pl/Ph 与自动网格永久失效
    //     （第 3 轮 blocker F-SAME-SYM-CLEAR-NEW-GEN）。
    //   【判据】回调携带**它自己那一批**的代号（MktPanel.shownSeq / paintTgt 的 seq）：
    //     `代号 <= 屏障` ⟹ 清空前那一批 ⟹ 拒；`>` ⟹ 清空后的新批次 ⟹ 放行。
    //   【两种错法，不要走】①「新一轮开始时无条件解除阻断」——会把清空后仍在飞的旧回调放进来；
    //     ② 用回调发生时的当前 reqSeq 冒充回调身份 —— 旧回调会被当成本轮新批次。
    //   【跨品种】clearCalcInputs 把屏障清成 -1（不生效）；旧品种回调由 MktPanel 的
    //     reqSeq/stillSame 双守卫拦。reqSeq 从 0 起，-1 不会与任何真实批次撞上。
    private var linkBlockedSeq: Int = -1
    private var linkBlockedSym: String = ""

    companion object {
        const val ANIM_TAB = 0
        const val ANIM_FROM_R = 1
        const val ANIM_FROM_L = 2

        /** 杠杆倍率上限。2026-10-03 用户裁定由 20 改为 150
         *  （原话：「杠杠上限太少了，最高改为150」）。
         *  原稿 v2.html:645 是 Slider(min 1 / max 20) ⟹ 这**偏离稿**，是产品裁定。
         *  ⚠ 只在这里定义一次。改上限时**只改这一处**，别在别处再写死数字 20。 */
        const val LEV_MAX = 150.0
    }

    private val pgBezier by lazy {
        android.view.animation.AnimationUtils.loadInterpolator(
            this, R.interpolator.pg_bezier)
    }
    private val pgEaseOut by lazy {
        android.view.animation.AnimationUtils.loadInterpolator(
            this, R.interpolator.pg_ease_out)
    }

    // ---------- 主题 ----------

    // ⚠ 2026-10-28 t4 · **`t211PaintSwitch()` 已删除**（连同它上面那整段开关配色说明）。
    //   原因：设置页「启动时刷新一次」整行移除（用户裁定），它是本函数在**本文件的最后一处
    //   调用点**；留着就是一个零调用点的 private 函数 = 死代码（静态扫描会报）。
    //   它没有 `draftOnFill()` 那种「唯一实现某口径」的价值：
    //   轨道四态一直住在 `switch_track.xml`（本函数只管 thumb 两态），
    //   而计算屏「加倍建仓」开关的滑块两态由 XML 的 `android:thumbTint="@color/dbl_thumb"` 给，
    //   不经过本函数 ⟹ 删它不影响任何在屏控件。
    //   ⚠ 若日后重新引入设置页开关：thumb 两态 = 开 `colorOnPrimary` / 关 `colorThumbOff`，
    //     轨道用 `@drawable/switch_track`（两态都带描边），别再把 trackTint 与 track 混用。

    private fun prefs() = getSharedPreferences("gridcalc", Context.MODE_PRIVATE)

    // ⚠ 2026-10-28 t4 · `PREF_AUTO_REFRESH`（"auto_refresh_once"）已随设置页开关一并删除：
    //   开关不在屏上，持久化键就没有读写的第二个主人；启动刷新改为恒定执行（见 onCreate 末尾）。
    private val PREF_LASTM = "mkt_last_sym"

    // v4 D10:主题读写的 SharedPreferences 全程 try/catch,失败降级为默认主题,不崩
    private fun loadMode(): String {
        val m = try {
            prefs().getString("theme", "follow")
        } catch (_: Exception) {
            null
        } ?: "follow"
        return if (m in setOf("follow", "light", "dark")) m else "follow"
    }

    private fun systemDark(): Boolean {
        return (resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
    }

    private fun resolve(mode: String): String = when (mode) {
        "dark" -> "dark"
        "light" -> "light"
        else -> if (systemDark()) "dark" else "light"
    }

    fun attrColor(name: String): Int {
        val id = resources.getIdentifier(name, "attr", packageName)
        val tv = TypedValue()
        theme.resolveAttribute(id, tv, true)
        return tv.data
    }

    /**
     * 稿 L88 / L99 的 `onFill` —— **「压在色块上的文字色」, 每套主题各一个值**:
     * ```
     *   浅  #fff                                  (稿 THEMES.light.onFill)
     *   深  oklch(0.160 0.012 265) = sRGB #0B0D13 (稿 THEMES.dark.onFill)
     * ```
     * 稿为什么深色要用深墨而不是白(稿 L97-98 原话): 深色主题三个填色偏浅(L .635~.745),
     * 白字压它们只有 2.13~3.76:1 —— 「紫底配白字」在这里恰好选错了方向;
     * 换成深墨是 5.16~9.11 全达标。本设置屏的 Seg 选中段 = colorPrimary 深色 #987DEF:
     * 白字 3.22:1 不过 AA, 深墨 6.03:1。
     *
     * ⚠⚠ **绝不能改 colorOnPrimary 这个 token** —— 它是共享的, 实测全库 9 处活引用:
     *   res/color/dbl_thumb.xml:5 · page_settings.xml:114 · page_calc.xml:1184 ·
     *   panel_clear.xml:94 · page_mkt.xml:173 ·
     *   MktView.kt:401 · MktPanel.kt:246 · MktPanel.kt:813 ·
     *   MainActivity.kt:paintSide(计算屏做多/做空 Seg —— t11 已改成本文件里最后一处 onFill)
     * 那些位置稿 L2501 的语境就是「白字压紫底」(浅色 ac 偏深, 白字 6.55:1 达标),
     * 改成 per-theme 会把浅色那几处一起弄坏。**所以按稿只在需要 onFill 的绘制处局部取。**
     *
     * 浅色分支直接沿用 colorOnPrimary(#FFFFFF, 与稿 L88 的 '#fff' 逐位相同),
     * 这样本次改动**浅色主题零变化**, 只有深色从 #FFFFFF 变成 #0B0D13。
     */
    private fun draftOnFill(): Int =
        if (resolve(mode) == "dark") 0xFF0B0D13.toInt() else attrColor("colorOnPrimary")

    // ---------- 计算 ----------

    data class CalcResult(
        val m: Int, val b: Int, val lines: Int, val q0: Double,
        val cost0: Double, val sellT: Double, val net: Double,
        val roe: Double, val liq: Double?, val eqBottom: Double
    )

    // ══════════════════════════════════════════════════════════════════════════
    // ⚠⚠⚠ 改这个函数之前, 先读这段。**完整的模型差异对照表在下面 50 行内**,
    //     交接文件「交接_剩余两项.md」结尾 t346–t370 那节有实测数字与算账。t349 加的, 因为同一件事已经栽过两次。
    //
    // 【1】App 的计算模型和设计稿的计算模型**是两套不同的东西**, 不是同一套的两个 bug。
    //        拿稿的示例值去对 App, 一定对不上 —— 那不是 App 算错了。
    //     稿 gridCalc（B-标杆迁移.html L154-186, UI 原型的简化演示）
    //         网格线  step=(hi-lo)/(n-1)      **等差**, n 条
    //         仓位    触发价下方**每一格**都建仓
    //         成本    Σ(买价×q)×(1+f)         一次算完
    //         见底权益 pos×lo−cost              **不含**未实现卖出
    //         爆仓    for k=0..600, 而 k=0 时 lines.filter(l>=trig && l<trig) 是**空集**
    //                 ⟹ held=0 ⟹ mv<=0 ⟹ `if(mv<=0) break`
    //                 ⟹ **稿永远返回「不爆仓」**(这不是我抄错, 是稿这段循环的事实)
    //         加倍建仓 gridCalc 里**根本没有** dbl 这个参数
    //     App calcGrid（本函数下方）
    //         网格线  r=(ph/pl)^(1/n)          **等比**, n+1 条   ← 等比是加密的标准做法
    //         仓位    **只在触发价处建仓** q0=m·q, 往上跌一格买一格
    //         成本    q0×po 起, 逐格 cost += q·pb 累加, 含手续费
    //         见底权益 c+qf×(pl−avgF)−费用       **含**未实现卖出
    //         爆仓    完整 MMR 模型, 能算出真实爆仓价
    //         加倍建仓 有
    //     实测（t346/t348, 稿自己的默认参数 P0: 12500/272.85/352.11/312.48/20/10）:
    //         稿独立算   +4,140.33 / +33.12% / 不爆仓   / 10 / 10 / −1,891.79
    //         App 真机   +1,989.52 / +15.92% / 244.32   / 11 / 10 / +6,503.52
    //     **App 的实现本身没有 bug**: t348 用 Decimal 50 位逐行重写了本函数,
    //     结果与真机差 0.00（净收益/见底权益都是）。这是**模型不同**, 不是代码错。
    //
    // 【2】结论: **保留 App 的模型, 不改成稿那样。**
    //     理由: 用户硬约束第一条就是「八位小数计算引擎不许动」;
    //           且把 App 改成稿那样 = 把一个能用的网格计算器退化成 UI 演示稿
    //           （丢掉加倍建仓、真实爆仓价、等比网格）。
    //     与 B-46/B-47/U6 同一类决策: 「按稿指的是视觉, 不是砍功能」。
    //
    // 【3】别再补 q>=1 钳制 —— 但**上一版写的理由是错的, 已更正**（t399）。
    //     ⚠ 旧注释说「稿的 min 是 HTML 校验属性、不是状态钳制」——
    //       **稿 L601 就是状态钳制**, 逐字:
    //         set(k, Math.max(min===undefined?-1e9:min, Math.min(max===undefined?1e9:max, v)))
    //       每次 onChange 都把值夹进状态。稿 L599 上的 min={min} 才是 HTML 属性,
    //       **稿两个都写了**, 而我上一版只解释了没照做的那个。
    //     ⚠ 旧注释还拿「标准算例 q=0.001」当「稿自相矛盾」的证据 ——
    //       **469/150000/55000/77000/11/0.001 这组数全稿 grep 零命中**,
    //       那是 **App 自己的算例**; 稿唯一的参数集是 L144 的 P0（q=10）。
    //     ✅ **结论不变**（钳到 1 会把标准算例的 +58.52% 抬成 +87780.48%, t337/t338 实测）,
    //        但真实理由是:
    //        **App 支持加密亚单位数量, 这是产品需求, 不是稿的要求。**
    //     与 B-46/B-47/U6 同一类: 「按稿指的是视觉, 不是砍功能」。
    //     现在本文件的口径是 `if (q <= 0.0) throw` —— 与稿 L372 自己写的
    //     `if (q <= 0)` 一致。详见 §7.3。
    // ══════════════════════════════════════════════════════════════════════════

    private fun gridLines(pl: Double, ph: Double, n: Int): List<Double> {
        val r = (ph / pl).pow(1.0 / n)
        return (0..n).map { pl * r.pow(it.toDouble()) }
    }

    /** 【稿移植 F-1 · 2026-10-01 新增】把稿子新增、App 原本没有的两个参数接进引擎。
     * App 原签名 9 参**没有** lev、也没有 sideShort —— 杠杆只写进 CSV(:752)，方向只做高亮(:1599)。
     * 本次只**新增**这两个参数与它们的效应；下面原有的表达式**一个字都没改**：
     * 手法是把形参 `q` 改名成 `qIn`，再在函数体加一行 `val q = qIn * lev` ——
     * 于是原有的 20 余行写的 `q` 自动就是杠杆后的每格数量，零改动可由逐行 diff 证明。
     *
     * ① 杠杆依据：新稿转录 §2.6「投入 = 持仓量 × 触发价 ÷ 杠杆」⟹ 杠杆是「仓位÷保证金」的倍数
     *    ⟹ 仓位 ∝ 杠杆 ⟹ 每格数量 = qIn × lev。lev=1 时 qIn*1.0 在 IEEE754 下**精确等于** qIn，
     *    所以「lev=1 且做多」逐位退化成改动前的结果（回归门）。
     * ② 做空依据：长仓「在触发价建仓、上涨逐格卖出」的时间镜像，见 calcGridShort 的注释。
     * ③ 边界依据：保证金 = 名义×mmr 且 保证金 = 名义/L ⟹ mmr = 1/L ⟹ L×mmr = 1（满杠杆）。
     *    ⟹ L×mmr ≥ 1 时本金被保证金占满，价格归零才爆 ⟹ 等价价 ≤ 0 ⟹ 拒绝计算并标红。
     *    App 原本没有这道守卫（App 没有 lev），标为**稿子新增**。
     *
     * ⚠️ 本函数**不得**参考 draft_port_calcgrid.py：那份脚本的 calcGrid 数学虽忠实，
     *    但它带三条**反向断言**（「lev 不可能改变结果」「做空零影响」）且签名是 9 参，
     *    照抄会把稿子的产出整个丢掉。
     */
    private fun calcGrid(
        c: Double, pl: Double, ph: Double, po: Double, n: Int, qIn: Double,
        mmr: Double, dbl: Boolean,
        lev: Double = 1.0, sideShort: Boolean = false
    ): CalcResult {
        val eps = 1e-9
        // ⚠⚠⚠ 2026-10-03 **删掉 `* lev`** —— 杠杆被算了两遍。
        //   改前：`val q = qIn * lev`
        //   杠杆作用在**两处**：① 保证金 → 总投入（onCalc: total = 保证金 × 杠杆）
        //                       ② 每格数量 → 实际股数
        //   物理含义只有一个：**放大仓位**。钱那边乘了、数量这边又乘，等于 75² 的效果。
        //
        //   实测（用户屏上那组）：保证金 500 × 杠杆 75 ÷ 13 个卖格 × 2.1 股/格 × 触发价 4100
        //     = 建仓 111,930 USD，而本金只有 37,500 —— **仓位是本金的 3 倍**。
        //   而 cost0 = q0 * po 压根不经过本金 c ⟹ 引擎**从没检查过仓位放不放得下**。
        //   于是「收益率 +71.54%」是拿 3 倍仓位的利润除以 1 倍本金 —— 数字自洽，��径是错的。
        //
        // ⟹ 用户明确「每格数量是我自己填的」⟹ 数量就是数量，App 不再替他乘杠杆。
        //   ⚠ 这**偏离稿**：稿 L653 侧是靠 `仓位 ∝ 杠杆` 反推每格数量的，
        //     那是**在你没填数量时**的算法。你既然自己填，杠杆那一份就不该再叠上去。
        val q = qIn
        // 【F-1 新增】满杠杆边界，先于任何算式判定（与稿子 calcGrid 的顺序一致）
        if (lev * mmr >= 1.0) throw OverLeverageException(
            "杠杆 × 维持保证金率 ≥ 1：等价价 ≤ 0，拒绝计算"
        )
        val lines = gridLines(pl, ph, n)
        // ⚠⚠ 2026-10-03 **卖格排除离触发价最近的那一档**（用户裁定）。
        //   原话：「触发价60离得60.34太近，所以60.34这里不会再被认为是卖格，
        //         而在市价建仓这里当作买个进行买入了」「对，就是这样」。
        //
        //   理由：触发价落在两档之间时，离它最近的那一档只差零点几个百分点，
        //   **不到一个格距**，挂单在那儿没有意义（价格稍微一动就成交，
        //   等于白设一个档）。那一档改当**买格**处理 —— 市价建仓时就在触发价成交。
        //
        //   实测（XAG 组：pl=50 / ph=80 / 触发60 / 每格5.138）：
        //     卖 7 档 → 建仓 2,157.96 → 爆仓 49.22
        //     卖 6 档 → 建仓 1,849.68 → 爆仓 48.10   ← 用户口径
        //     币安                                  47.99
        //   ⟹ 改后差 0.2%，与币安一致。
        //
        //   ⚠⚠ 2026-11-02 **改成按格距判断**（用户用币安真实参数对出误差）：
        //     原裁定「无条件 drop 最近一档」只适用于**触发价落在两档之间**的情形
        //     （那时最近一档离触发价不足一个格距，挂在那里没意义）。
        //     当触发价**正好在网格线上、或略高于最低价**时，最近那档离它已有整整一格，
        //     再 drop 就等于**凭多吃掉一档仓位** ⟹ 建仓少一格、爆仓价被抬高。
        //     实测（GEV 币安参数：770~1173 / 8 格等比 / 保证金 1100 / 25x / 每笔 0.42 /
        //       触发价 = 最低价 770 / 不加倍）：币安 预估强平 **451.66**，仓位 8×0.42=3.36。
        //       无条件 drop ⟹ 仓位 7×0.42=2.94 ⟹ 爆仓价 **403.9**（偏高 48）。
        //     判据：最近卖档 ÷ 触发价 < 一个格距 ⟹ 触发价在两档之间 ⟹ 丢弃；否则保留。
        val step = if (lines.size >= 2 && lines[0] > 0) lines[1] / lines[0] else 1.0
        val above = lines.filter { it > po + eps }
        val nearestInsideStep = above.isNotEmpty() && po > 0 && above[0] / po < step - 1e-12
        val sells = if (nearestInsideStep) above.drop(1) else above
        val buys = lines.filter { it < po - eps }
        val m = sells.size
        val bc = buys.size
        // 稿liqOf守卫(494):仅校验上方有格子——触发价低于最低档时下方买格可为0
        // (仓位全由触发价处建仓构成、均价=触发价;实测GRPO 1.237~1.345/14格/触发1.29→币安1.199,我们1.18)
        if (m < 1) throw IllegalArgumentException("触发价上方要有格子")
        // 【F-1 新增】空仓在触发价卖空、回补需要下方档位
        if (sideShort && bc < 1) throw IllegalArgumentException("触发价下方要有格子")
        val sumS = sells.sum()
        val sumB = buys.sum()
        // 【F-1 新增】空仓分支。长仓那套数学一个字没动，这里只是把同一套镜像一遍。
        if (sideShort) return calcGridShort(
            c, pl, ph, po, q, mmr, dbl, lines, sells, m, bc, sumS, sumB
        )
        // 加倍建仓:初始持仓翻倍,卖出总额多出一份到顶全平(M·q·Ph)
        val q0 = if (dbl) 2 * m * q else m * q
        val cost0 = q0 * po
        val buyFee = cost0 * FEE_TAKER   // 建仓=市价=吃单 0.04%
        val sellT = if (dbl) q * sumS + m * q * ph else q * sumS
        val net = sellT - cost0 - buyFee - sellT * FEE_MAKER   // 卖出=限价挂单 0%
        val buysDesc = buys.sortedDescending()
        var qq = q0
        var cost = q0 * po
        var feesPaid = buyFee
        var found: Double? = null
        fun hit(p: Double, qqv: Double, e: Double) = e <= mmr * qqv * p
        if (kotlin.math.abs(1 - mmr) < 1e-9) {
            // MMR=100%:爆仓与价格无关。本金覆盖持仓成本则永不爆仓,否则开仓即爆
            found = if (c > cost + feesPaid) null else po
        } else if (!hit(po, qq, c - feesPaid)) {
            for (pb in buysDesc) {
                val avg = cost / qq
                if (hit(pb, qq, c + qq * (pb - avg) - feesPaid)) {
                    found = (qq * avg - c + feesPaid) / (qq * (1 - mmr))
                    break
                }
                qq += q
                cost += q * pb
                feesPaid += q * pb * FEE_MAKER   // 加仓=限价挂单 0%
            }
            if (found == null) {
                val avg = cost / qq
                found = (qq * avg - c + feesPaid) / (qq * (1 - mmr))
            }
        } else {
            found = po
        }
        val qf = q0 + bc * q
        val avgF = (q0 * po + sumB * q) / qf
        val eqBottom = c + qf * (pl - avgF) - buyFee - sumB * q * FEE_MAKER
        // ⚠⚠ 2026-10-03 用户报「你这爆仓价格也没算对啊」——屏上显示的是 **0.00**。
        //   查清后：**不是算错，是「这笔仓位永远不会爆仓」**。
        //     实例（保证金 500 / 杠杆 75 / 12 卖格 × 0.028 股 / 触发 4130）：
        //       持仓 0.336 股 → 建仓 1,387.68 USD；价格跌到 0 也只亏这么多，
        //       远小于本金 ⟹ 引擎扫遍所有买格都找不到爆仓点，
        //       末尾兜底那行反解出一个**负数/极小数**，显示层渲染成了 0.00。
        //   ⟹ 「没有」被写成了「0.00」—— **拿数字冒充没有**，与用户此前骂掉的源状态行同一类毛病。
        //
        // ⟹ 这里把「算不出爆仓点」显式标成 null（而不是塞一个假数）。
        //   ⚠ 判定用 `found <= 0`：爆仓价不可能是 0 或负数，落到这个区间就是「没找到」。
        //   ⚠ 若日后要恢复显示「不会爆仓」这类文案，改 stat_liq 的渲染即可，别改这里 ——
        //     这里只负责**如实标注有没有**，不负责怎么说。
        val liq = found?.takeIf { it > 0.0 }
        return CalcResult(m, bc, n + 1, q0, cost0, sellT, net, net / c * 100.0,
            liq, eqBottom)
    }

    /** 稿子新增（2026-10-01 移植自 B-标杆迁移 v2.html calcShort）。App 原本无做空能力。
     * 【稿移植 F-1 · 2026-10-01 新增】做空分支 —— App 原本没有这个方向
     * （sideShortMode 只被 paintSide 用来高亮，从不传入 calcGrid）。
     *
     * 逐项对照长仓（长 → 空）：
     *   仓位 q0 = m·q 建仓买      ⟹  q0s = bc·q 在触发价**卖空**
     *   卖出 sumS·q（上方 m 档）  ⟹  回补 sumB·q（下方 bc 档）
     *   净收益 = 卖出 − 建仓 − 费 ⟹  净收益 = **卖空所得 − 回补成本 − 费**
     *   爆仓扫下方买格             ⟹  爆仓扫上方卖格（由低到高，价格涨上去才爆）
     *   反解 p=(…)/(qq(1−mmr))    ⟹  反解 p=(…)/(qq(**1+mmr**))
     *   见底权益取价格跌到 pl     ⟹  见底权益取价格涨到 ph（空仓的最坏情形在另一边）
     *
     * ⚠️ **分子是 `+c − fees`，与长仓的 `−c + fees` 相反。** 由权益式反解：
     *     c + qs·(avg − p) − fees = mmr·qs·p
     *   ⟹ c + qs·avg − fees = qs·p·(1 + mmr)
     *   ⟹ p = (qs·avg + c − fees) / (qs·(1 + mmr))
     *   写反的话算出的「爆仓价」会落到触发价**下方** —— 那就成了多头，不是空仓。
     *   （这个坑稿子里踩过一次，本函数把符号写进注释就是为了下次不被改回去。）
     */
    private fun calcGridShort(
        c: Double, pl: Double, ph: Double, po: Double, q: Double,
        mmr: Double, dbl: Boolean,
        lines: List<Double>, sells: List<Double>,
        m: Int, bc: Int, sumS: Double, sumB: Double
    ): CalcResult {
        val eps = 1e-9
        val q0s = if (dbl) 2 * bc * q else bc * q
        val entry = q0s * po          // 卖空所得
        val cover = q * sumB         // 回补总成本
        val shortFee = entry * FEE_TAKER + cover * FEE_MAKER
        val net = entry - cover - shortFee

        val sellsAsc = sells.sorted()      // 由低到高：价格涨上去才逐格加空
        var qs = q0s
        var costS = q0s * po
        var feesPaid = entry * FEE_TAKER   // 开空=市价=吃单
        var found: Double? = null
        fun hit(p: Double, qqv: Double, e: Double) = e <= mmr * qqv * p
        if (kotlin.math.abs(1 - mmr) < 1e-9) {
            found = if (c > costS + feesPaid) null else po
        } else if (!hit(po, qs, c - feesPaid)) {
            for (ps in sellsAsc) {
                val avgS = costS / qs
                if (hit(ps, qs, c + qs * (avgS - ps) - feesPaid)) {
                    found = (qs * avgS + c - feesPaid) / (qs * (1 + mmr))   // ← 分子 +c−fees
                    break
                }
                qs += q
                costS += q * ps
                feesPaid += q * ps * FEE_MAKER
            }
            if (found == null) {
                val avgS = costS / qs
                found = (qs * avgS + c - feesPaid) / (qs * (1 + mmr))
            }
        } else {
            found = po
        }
        val qf = q0s + m * q
        val avgF = (q0s * po + sumS * q) / qf
        // 空仓最坏情形是价格**涨到** ph（长仓是跌到 pl）
        val eqBottom = c - qf * (ph - avgF) - entry * FEE_TAKER - sumS * q * FEE_MAKER
        return CalcResult(m, bc, lines.size, q0s, entry, cover, net, net / c * 100.0,
            found, eqBottom)
    }

    /** 【F-1 新增】满杠杆边界专用异常。单独一类是为了让 onCalc 能把它**接到 over 态**
     *  （与「爆仓价高于目标值」同一套标红渲染），而不是掉进通用 IllegalArgumentException
     *  那条只写一行报文的通路。App 原本没有这个类型。 */
    private class OverLeverageException(msg: String) : IllegalArgumentException(msg)

    private fun getNum(e: EditText, name: String, isInt: Boolean = false): Double {
        val t = e.text.toString().trim()
        if (t.isEmpty()) throw IllegalArgumentException("${name}还没有填")
        // ⚠ 2026-10-02 t7：输入框现在会带千分位显示（稿 L2201 的 toLocaleString），
        // 而 String.toDoubleOrNull() **不吃分组符**——"12,500.00" 会解析失败并报
        // 「总投入不是有效数字」。所以这里先剥掉千分位再解析，与 t7formatField 成对。
        // 剥不掉就照旧抛错，错误文案一个字没改。
        val v = t.replace(",", "").replace(" ", "").toDoubleOrNull()
            ?: throw IllegalArgumentException("${name}不是有效数字")
        return if (isInt) v.toInt().toDouble() else v
    }

    /** #93 稿 L643: 杠杆值是只读回显(TextView), 没有 EditText 可读, 按文本读, 语义同 getNum。 */
    private fun getNumRo(tv: TextView, name: String): Double {
        val t = tv.text.toString().trim()
        if (t.isEmpty()) throw IllegalArgumentException("${name}还没有填")
        return t.toDoubleOrNull() ?: throw IllegalArgumentException("${name}不是有效数字")
    }

    /**
     * #93 稿 L645 `Slider val={pv.lev} min={1} max={20}` ⟹ 滑杆是杠杆的**唯一主人**。
     * 写值一律走这里, 好让「回显文字」与「滑杆位置」永远是同一个数, 不出现第二个真相。
     * @param syncBar 程序写值(记录回填/状态恢复)时把滑杆也拨到位;
     *                 用户拖动时传 false, 那时滑杆已经是主人, 由它自己回调。
     */
    private fun t341SetLev(v: Double, syncBar: Boolean = true) {
        // ⚠ 2026-10-03 滑杆已删，`syncBar` 形参**保留只为不打断所有调用点**（可空实现）。
        //   ⟹ 别再引用 levBar —— 那个字段和节点都已经不存在了。
        if (!::levText.isInitialized) return
        levText.setText(numStr(v))
        if (suppressReset) return
        // ⚠ EditText 回写会触发 TextWatcher ⟹ 递归风险。用「值相同就不写」切断：
        //   用户敲 5 → watcher 调 t341SetLev(5) → setText("5") → watcher 再调 → 文本已相同则跳过。
        onCalc()
    }

    /** ⚠ 2026-10-03 费率彻底删除后，本函数**已无调用者**。
     *  它原本把费率格式化成无千分位、无尾零的纯数值串，供设置屏回显。
     *  ⚠ 保留不删：删它要连带确认所有引用；而留着只多一个未调用的私有函数。
     *    若日后费率重回界面，这里就是现成的格式化实现。 */
    @Suppress("unused")
    private fun plainRate(v: Double): String =
        if (!v.isInfinite() && v == Math.floor(v)) v.toLong().toString() else v.toString()

    /**
     * ⚠⚠⚠ 2026-10-03 **新增按量级自适应的格式化** —— 「两位小数」病的第三次出现。
     *
     * 病史（三次，同一个根因）：
     *   ① 每格数量 q=0.001 被 Math.round 抹成 0   （#100）
     *   ② 最低/最高/触发价 0.00000957 显示成 0.00（上一轮）
     *   ③ **爆仓价 0.00000705 显示成 0.00**       （本轮）
     * 三次的根因都是**照稿假定数值为 4 位数级别**，而加密/迷你在 1e-5 量级。
     * ⟹ 不再逐字段打补丁，改成一个**按绝对值选小数位**的通用格式化。
     *
     * 量级 → 小数位：
     *   ≥1000 → 2 位（千分位）   ≥1 → 2 位   ≥0.01 → 4 位   ≥0.0001 → 6 位   更小 → 8 位
     * ⚠ 只用于**结果展示**；输入框另有 t7formatField（走 dp=-1 那条路）。
     * ⚠ 若日后又出现「某结果显示成 0.00」，先查这里的小数位，别再加特例。
     */
    private fun fmtSmart(v: Double): String {
        if (!v.isFinite()) return v.toString()
        val a = Math.abs(v)
        val dp = when {
            a >= 1.0 -> 2
            a >= 0.01 -> 4
            a >= 0.0001 -> 6
            else -> 8
        }
        return String.format(java.util.Locale.US, "%,.${dp}f", v)
    }

    private fun fmt(v: Double, d: Int = 2): String = "%,.${d}f".format(v)

    /** 稿 L183 本格金额: (q*x).toFixed(2) —— 2 位小数, 无千分位。 */
    private fun t219px2(v: Double): String = "%.2f".format(v)

    /** 稿 L182 网格价: x.toFixed(6) —— 固定 6 位小数, 无千分位。 */
    private fun t219px6(v: Double): String = "%.6f".format(v)

    /**
     * 稿 L187 fmt2:
     *   (v<0?'−':'') + Math.abs(v).toLocaleString('en-US',{min:2,max:2})
     * ⟹ 负数用 U+2212(−, 不是 ASCII '-')、【正数不带 +】、带千分位、2 位小数。
     * 改前 App 用 "%+.2f": ASCII 连字符、正数带 "+"。三样都与稿不同。
     */
    private fun t219fmt2(v: Double): String =
        (if (v < 0) "\u2212" else "") +
            String.format(java.util.Locale.US, "%,.2f", kotlin.math.abs(v))


    /**
     * 结果区的生灭开关。
     *
     * ⚠⚠ 2026-10-16 t5 · **用户授权：idle 态也显示结果整卡**（本条更新原 t154/t227 的
     * 「未算时整块不显示」契约 —— 那条按的是 v1 稿 L662 `{st!=='idle'&& ( … )}`）。
     * 依据是**方向 B 稿子源码**（判据源）：`design\四屏视觉改版\方向-B\四屏-计算页.html`
     *   · `:330` `.calc-result`（结果卡）在 idle 态**照样渲染**，里面 hero「净收益」+`—`、
     *     四格四个 `—` 全在；idle 与 done 共用同一组节点，靠 `data-calc-state` 换装；
     *   · idle 真正隐藏的只有 `:161` `.calc-collapse`（折叠口）与 `:194` `.calc-panel`（两个展开面板）。
     * ⟹ 参数拆成两档：结果**卡**（四格 + 其上边线）idle 也 VISIBLE；
     *    **折叠口那一行**只在 done 态出现。
     *
     * @param vis 结果卡（四格 + 其上边线）是否可见
     * @param collapseRow 折叠口那一行（两个口 + ladderNote + 行 + 上边线）是否可见。
     *        ⚠ 2026-10-29 t37：**形参保留但已不起作用** —— 用户裁定「展开更多 / 网格明细整块下线」，
     *        该行现在是恒 GONE（见函数体内 `val c = View.GONE` 的墓碑）。保留形参是为了不动 8 个调用点。
     */
    private fun t227SetResultArea(vis: Boolean, collapseRow: Boolean = vis) {
        val v = if (vis) View.VISIBLE else View.GONE
        if (::statTiles.isInitialized) statTiles.visibility = v
        // ⚠ 2026-10-17 t9：`stat_tiles_topline` **按稿退役，恒 GONE** ——
        //   稿的结果卡内没有这条全宽 1dp 发丝（`theme.css:431-438` 的卡片组只给
        //   bg-surface + r-card + elev-1，卡内分段一律靠 bg-surface-2 的内嵌面）；
        //   而 t9 把四格并进结果卡后，它连「卡外分段」的语义也没了、还落在**卡内**，双重不符。
        //   ⟹ 节点与绑定**保留**（MainActivity 有 findViewById + 三处赋值，删节点会断绑定 ——
        //     本仓 t298/t316 的既有教训），渲染层面等同移除。
        //   ⚠ 连带结果（登记）：done 态这条发丝随之消失。它是「合并」的**必然结果**，不是超范围改动。
        if (::statTilesTopline.isInitialized) statTilesTopline.visibility = View.GONE
        // ⚠⚠ 2026-10-29 t37 · **折叠口那一行整块下线**（用户裁定，原话：
        //   「你是稿子有这两个标签，可是我在之前就说不要这两个标签了，你没有一起删除是什么意思？」）
        //   改前：`val c = if (collapseRow) View.VISIBLE else View.GONE` —— done 态会把
        //   「展开更多 / 网格明细」两行连同 ladder_note 一起点亮。
        //   现在：**恒 GONE**。`collapseRow` 形参**保留但不再影响可见性**（本函数有 8 个调用点，
        //   改签名会把一处「要不要折叠口」的语义散到 8 个地方；而裁定是「整块下线」，与调用点无关）。
        //   ⚠ 节点与绑定全部保留（moreToggle / detToggle / ladderNote / toggleRow 都有 findViewById，
        //     删节点会断绑定 —— 本仓 t298/t316 的既有教训），渲染层面等同移除。
        //   ⚠ `more_row` / `more_chev` / `det_chev` 是 toggleRow 的**子孙**（见 page_calc.xml），
        //     父行 GONE 之后它们自然不渲染，无需单独设。
        val c = View.GONE
        if (::moreToggle.isInitialized) moreToggle.visibility = c
        if (::detToggle.isInitialized) detToggle.visibility = c
        if (::ladderNote.isInitialized) ladderNote.visibility = c
        // t23: 折叠口那一行**本身**与它的上边线也要一起收 —— 见 page_calc.xml 同行注释。
        if (::toggleRow.isInitialized) toggleRow.visibility = c
        // ⚠ 2026-10-19 r8 · C-4：顶发丝**按稿恒 GONE**（稿 `四屏-计算页.html:162`
        //   `.calc-collapse__rule { display: none }`）⟹ 不再跟折叠口一起 VISIBLE。
        //   节点与绑定保留（`:2963` 有 findViewById、本函数与 `:1563` 有赋值，删节点会断绑定 ——
        //   本仓 t298/t316 的既有教训），渲染层面等同移除。
        if (::toggleRowTopline.isInitialized) toggleRowTopline.visibility = View.GONE
        // t351 B-5 窄边界(子 agent 3f483577 报的): 上面只收 statTiles / 两个折叠口 / ladderNote,
        // **不收展开区本体** ⟹ 先点开「展开更多」或「网格明细」、再改参数回到 idle 时,
        // 折叠口已经收起、内容区却还 VISIBLE —— 与「结果区整块不显示」(稿 L662) 矛盾。
        if (::moreBox.isInitialized) {
            resetMotion(moreBox)
            moreBox.visibility = View.GONE
        }
        if (::detBox.isInitialized) {
            resetMotion(detBox)
            detBox.visibility = View.GONE
        }
    }

    private fun idle() {
    t304Note(null)   // t304: 回到 idle 态就别再挂着改写提示
        // ⚠ 2026-11-04：按钮上的「已保存 / 已删除」是**常驻状态**，恢复时机只有这里 ——
        //   用户改了参数（或结果被清）⟹ 屏上数字与已存记录不再自洽 ⟹ 按钮必须重新变回动作标签。
        if (::saveBtn.isInitialized) {
            saveBtn.text = "保存数据"
            saveBtnSaved(false)
        }
        if (::clearRecBtn.isInitialized) {
            clearRecBtn.text = "清空数据"
        }
        // ══ 2026-10-16 t5 · **用户授权：idle 态对齐方向-B 稿子** ═══════════════════════════
        // 本条推翻 t141 的「未算时**整块不显示**,不是显示一个占位破折号」—— 那条按的是 v1 稿。
        // 方向 B 稿（判据源）的 idle 态**照样渲染结果整卡**，只把值换成占位 `—` 并染成 faint：
        //   `方向-B\四屏-计算页.html:334` hero 的 DOM 字面量就是 `—`（不是空串）；`:121` idle 字色 ink-faint；
        //   `:342/:346/:350/:354` 四格四个 `—`；`:145` idle 四格字色 ink-faint；
        //   `:129` idle 只藏 hero 副行（本页 hero_sub 恒 GONE）；`:161/:194` idle 藏折叠口与展开面板。
        // ⚠ 本次**只放行 idle 态**：done 态的数字 / 涨跌色 / 展开态 / 各可见性契约一个字未动
        //   （faint 的还原见 onCalc 成功路径那行 —— 不还原就会把占位色带进 done）。
        // t143:这两个是 lateinit View,绑定在后面的 bindViews 块里;
        // idle() 会先于绑定被调用(TextWatcher),未初始化就访问会抛
        // UninitializedPropertyAccessException。**这里必须守卫**,
        // 否则异常被 L1334/L1342 的 catch(Throwable){} 吞掉,
        // 表现就是「回车按了没反应、logcat 什么都没有」—— t121 同款。
        if (::calcHeroBox.isInitialized) calcHeroBox.visibility = View.VISIBLE
        if (::calcIdleHint.isInitialized) {
            calcIdleHint.visibility = View.VISIBLE
        } else {
            // t31 收尾: 原来这里有 Log.d/Log.w 两条(calcpage 为 #140 真机复验加的探针),
            // t6 验完(t6 三条硬门全过、17 PASS/0 FAIL)后删除。**守卫本身原样保留** ——
            // 它防的是 lateinit 未初始化时访问 calcIdleHint, 至今仍有效, 别顺手删。
        }
        if (::calcOverline.isInitialized) calcOverline.visibility = View.GONE
        if (::calcOverlineTopline.isInitialized) calcOverlineTopline.visibility = View.GONE
        // t43：idle 态不保留上一份计算的主爆仓价，副行随之收起。
        currentCalcLiqForLine = null
        if (::statLiqGap.isInitialized) statLiqGap.visibility = View.GONE
        // t153 ⑩:回到未算态要恢复显示(否则 over 之后改任一参数,四格一直是空的)
        // ⚠ 2026-10-16 t5:改前这里传 false（整块收掉，因为 v1 稿 L662 要求 idle 不渲染结果区）。
        //   现在结果**卡**在 idle 也显示，只有**折叠口那一行**按稿隐藏
        //   （稿 :161 `[data-calc-state="idle"] .calc-collapse{display:none}`）
        //   ⟹ 第二个实参 collapseRow=false，与 done 态的 t227SetResultArea(true) 区分开。
        t227SetResultArea(true, collapseRow = false)
        // 稿 :121 —— idle 的 hero 字色 = ink-faint（本页 colorFaint）；`—` 是稿里的 DOM 字面量占位。
        hero.setTextColor(attrColor("colorFaint"))
        hero.text = "—"
        heroSub.setTextColor(attrColor("colorPrimary"))
        heroSub.text = ""
        // t119 ① 同上；⚠ 2026-10-16 t5：四格的**字色**也要跟着换 faint（稿 :145
        //   `[idle] .calc-tile__value{color:ink-faint}`）。其中 grids / eqb / pos / sell / cost / ccy
        //   六格的**唯一**颜色来源是 XML 的 ?attr/colorInk（Kotlin 里没有第二处赋值）
        //   ⟹ 这里染了就必须在 onCalc 的成功路径统一还原，否则 faint 会跟着进 done 态。
        val faint = attrColor("colorFaint")
        statVals.values.forEach {
            it.text = "—"
            it.setTextColor(faint)
        }
        detSum.setTextColor(attrColor("colorSub"))
        detSum.text = "明细会在计算后显示"
        lastLines = -1   // t167:与 detSum 同处一块;无明细就不报行数
        t229SyncLadderNote()
        rows.removeAllViews()
        // 稿resetIdle:清报错行+爆仓卡红框(.stat.bad remove)
        showErr(null)
        // ══ 2026-10-17 t9 · **「爆仓价」格按稿修（评审 t8 §9-F1）** ══════════════════════════
        // 稿 `方向-B\四屏-计算页.html:132-140` 把四格写成**同一类** `.calc-tile`
        // （底 = bg-surface-2、无阴影），`:145` 要求 idle 四格**值字色一律** ink-faint。
        // 改前这里两行把上面 forEach 刚做好的两件事各**反着**做了一遍（v1 的 resetIdle 语义）：
        //   ① `background = null` 本意是「清掉 done 态标的红框」，但它把 XML 的 `calc_tile_bg`
        //      面**一并永久清掉** ⟹ idle 该格裸在画布上（深色下 canvas 与 surf2 差 16/19/23 级，看得见）。
        //      改为**还原原始 Drawable 实例**（bind 期存下的 [statLiqBg0]）：红框照样清得掉、面也回来了。
        //      ⚠ 用存下来的实例而不是复述资源名 —— 与 save_btn 的 `saveBtnBg0` 同一手法。
        //   ② `statVals["liq"]?.setTextColor(colorInk)` 把刚染的 faint **覆盖回 ink** ⟹ 删掉；
        //      上面的 forEach 已经把它染成 faint，正是稿要的。
        // ⚠ **只动 idle 路径**：done 的 ink/red 规则（`:1390` 的 liq 红/ink、roe 的 colorTeal、
        //   grids/eqb 的 colorInk）与 [markLiqBad] 的红框逻辑**一字未动**；
        //   done 路径的 `statLiqCard.background = null`（onCalc 成功路径）也**保留原样** ——
        //   本次验收要求 done 态契约不变，那张格在 done 仍无面（**登记残留**，见 t9 报告 §7）。
        if (::statLiqCard.isInitialized && statLiqBg0 != null) statLiqCard.background = statLiqBg0
        // §1.1 失败/清场场景:待存结果作废,按钮回disabled
        saveIdle()
    }

    /** 网格数量是否被你手改过。true ⟹ 不再用自动值覆盖。 */
    private var nManual = false
    /**
     * ⚠ 2026-10-08 [nManual] 绑定的品种 —— 用来把「手改」的生命周期**限制在本品种内**。
     *   背景见 [stepThrFor] 里的根因说明：nManual 原先**只有置 true、没有重置**。
     */
    private var nManualSym: String = ""

    /** 该品种的**日线**步长阈值（用户裁定「固定用日线阈值」）。取不到时为 null。 */
    private var stepThr: Double? = null
    // 异步步长请求序号：只允许当前品种最新一轮回调写回计算状态。
    private var stepThrSeq: Long = 0L

    /**
     * 自动网格数量（用户 2026-10-03 裁定）：
     *   等比网格，最高价 → 最低价，**格距 = 该品种日线步长阈值 s**
     *       r = 1 − s
     *       第 k 格 = 最高价 × r^k
     *       n = ceil( ln(ph/pl) ÷ −ln(1−s) )
     *   取满足 `ph × (1−s)^n ≤ pl` 的最小整数 n，确保最后一格不高于最低价。
     *
     * @return 取不到步长阈值、或参数不合法时返回 null（调用方保留用户敲的值，不硬算）
     */
    /**
     * ⚠⚠⚠ 2026-10-05 **网格数量自动算，独立于 onCalc**。
     *
     * 【现象】用户真机截图：XOM 步长阈值 2.89% 有了、最低价 141 / 最高价 171 也自动填上了，
     *   但**网格数量是空的**，同时报「保证金还没有填」。
     * 【根因】n 的自动算原本只写在 [onCalc] 里，而 onCalc 有两个前置条件都不受我们控制：
     *   ① `autoGridN` 要 `stepThr != null` —— 步长阈值是**异步**回来的；
     *   ② n 算完往后走要读保证金/触发价/每格数量，缺一个就抛异常走 catch。
     *   两者**错开**就会漏：阈值先到时 pl/ph 还没填（或反之），那一次 onCalc 算不出 n，
     *   而事后**没有任何地方补算** ⟹ n 永远空着。
     * 【改法】把它抽成一个幂等的小函数，在「三样东西齐了就填」的每个时点都调一次：
     *   · 阈值到达（stepThrFor 回调）
     *   · 最低价/最高价被联动写入（linkCalc / linkPhigh）
     *   · onCalc 原本的位置（保留，不动它的语义）
     * 【为什么安全】nManual 为真（用户自己填过格数）时直接返回，绝不覆盖用户的数；
     *   写入走 [programWrite]，不触发 TextWatcher 把刚出的结果清掉。
     */
    private fun tryAutoGridN() {
        if (nManual) return
        if (stepThr == null) return
        val pl = inp["Pl"]?.text?.toString()?.trim()?.toDoubleOrNull() ?: return
        val ph = inp["Ph"]?.text?.toString()?.trim()?.toDoubleOrNull() ?: return
        if (ph <= pl) return
        val n = autoGridN(pl, ph) ?: return
        val ni = inp["N"] ?: return
        if (ni.text.toString().trim() == n.toString()) return
        programWrite { ni.setText(n.toString()) }
    }
private fun autoGridN(pl: Double, ph: Double): Int? {
        val s = stepThr ?: return null
        if (s <= 0.0 || s >= 1.0) return null
        if (pl <= 0.0 || ph <= pl) return null
        val denom = -Math.log1p(-s)
        if (denom <= 0.0) return null
        val raw = Math.log(ph / pl) / denom
        // 最小 n 需满足 ph * (1-s)^n <= pl；对理论整数值留出浮点误差容忍。
        val nearest = Math.rint(raw)
        val n = if (Math.abs(raw - nearest) <= 1e-10) nearest.toInt()
                else Math.ceil(raw).toInt()
        // 网格数量下限 2（与下面 onCalc 的校验同口径：一格不成网格）
        return if (n >= 2) n else null
    }

    /** 取该品种的日线步长阈值，写进 [stepThr]，然后重算一次。
     *  ⚠ 用户裁定「**固定用日线阈值**」—— 不跟行情屏当前选的周/月/季走。
     *  ⚠ 品种取 **行情屏最后看过的那个**（mkt_last_sym，用户裁定第 1 点）。
     *    拿不到 / 币种无源 ⟹ stepThr 保持 null，网格数量退回用户敲的值，不硬算。
     */
    private fun loadStepThr() {
         val requestId = ++stepThrSeq
        val sym = try {
            getSharedPreferences("gridcalc", MODE_PRIVATE).getString("mkt_last_sym", "") ?: ""
        } catch (_: Exception) { "" }
        if (sym.isBlank()) { stepThr = null; return }
        val key = normSym(sym)
        MktData.stepThresholdAsync(sym) { r ->
            runOnUiThread {
                if (requestId != stepThrSeq || normSym(curCalcSym()) != key) return@runOnUiThread
                stepThr = r.v?.takeIf { it > 0 && it < 1.0 }
                if (stepThr != null && !nManual) onCalc()
            }
        }
    }

    /**
     * ⚠⚠⚠ 2026-10-03 **步长阈值改为按当前品种取**（用户报「大量交易品种的网格数量没有自动计算」）。
     *
     * 【根因】[autoGridN] 依赖异步到达的 `stepThr`，
     *   而 [stepThr] **只由 [loadStepThr] 写一次**，那个函数取的是
     *   `mkt_last_sym`（行情屏最后看的那**一个**品种）。
     * ⟹ 于是：**只要当前算的不是「最后看过的那个品种」，stepThr 就属于别人或为 null**，
     *   `autoGridN` 恒返回 null，网格数量就停在 XML 默认值 20。
     *   品种一多，绝大多数都命中不了 —— 与用户看到的「大量品种没自动算」完全吻合。
     *
     * 【修法】进入某个品种时按**它自己**取阈值。
     *   ⟹ 复用行情屏/自选屏已抓的数据（同 FavDist 的思路），不额外打网络请求。
     *   ⟹ 取不到时保持 null（用户敲的值照用），**不猜**。
     *
     * @param sym 目标品种；与当前算的品种一致时才写 [stepThr]，避免异步回调串台。
     */
    internal fun stepThrFor(sym: String) {
         val requestId = ++stepThrSeq
        if (sym.isBlank()) return
        val key = normSym(sym)
        // ⚠⚠⚠ 2026-10-08 用户报「网格数量还是没自动算：**保存过记录的有、
        //   新的没计算过的、没加自选的就没有**」——**根因就在这下面三行**：
        //
        // 【根因】[nManual] 在全项目**只有置 true 的一处**（手填格数时），
        //   **没有任何一处把它重置回 false**。于是只要手改过一次，
        //   之后**所有品种**都永久不再自动算 —— [tryAutoGridN] 第一行 `if (nManual) return` 就拦掉了。
        // 【为什么"保存过的有"】记录里存了 n 并由 restoreCalc 回填，走的是**记录值**，
        //   不依赖自动算 ⟹ 看上去正常。新品种没有记录 ⟹ 暴露出这条卡死的开关。
        // 【裁定漏实现】2026-10-02 用户确认的默认值里就有「**换品种时重置手改**」，
        //   代码里从来没写过这句；只有被推翻前的那版注释提到过它。
        // 【为什么不直接无条件清】同一条裁定的后半句是「**本品种内一直保持手改**」——
        //   故判据绑定到品种，不是绑定到"这个函数被调用过"（[stepThrFor] 虽然目前
        //   只由 MktPanel 的 `if (symChanged)` 唯一调用，但不能靠那个巧合）。
        if (key != nManualSym) {
            nManualSym = key
            nManual = false
        }
        if (stepThrSym == key && stepThr != null) { tryAutoGridN(); if (!nManual) onCalc(); return }     // 这个品种已经有了：阈值虽在，但pl/ph可能是后填的，仍补算一次N
        MktData.stepThresholdAsync(sym) { r ->
            runOnUiThread {
                // ⚠ 异步回来时可能已经切到别的品种了 ⟹ 只在仍是同品种时写
                if (requestId != stepThrSeq || normSym(curCalcSym()) != key) return@runOnUiThread
                stepThr = r.v?.takeIf { it > 0 && it < 1.0 }
               // ⚠⚠⚠ 2026-10-05 **只有真的拿到数才记「这个品种算过了」**。
    // 改前这一行无条件写 key ⟹ **一次失败就把这个品种永久挡住**，之后进来都不再试 ——
                // 用户真机截图（00700 有价有支撑、就是没阈值）、口述「**老的有、刚加的就没有**」。
          // 拿不到数就**不写** stepThrSym，下次进来还会再试；取数侧对失败的冷却只有 30 秒
   // （见 MktData.stepCompute），所以「再试」不等于「连打接口」，限流纪律不受影响。
            if (stepThr != null) stepThrSym = key
          tryAutoGridN()   // ⚠ 阈值到了就补算网格数量（不必等 onCalc 走通）
                if (stepThr != null && !nManual) onCalc()
            }
        }
    }

    /** 当前计算页对应的品种（用于异步回调判定，避免串台）。 */
    internal fun curCalcSym(): String =
        (if (::mktPanel.isInitialized) mktPanel.curSym() else "") ?: ""

    private var stepThrSym: String = ""

    /**
     * ⚠⚠⚠ 2026-10-05 **回填后不该显示「● 未保存」**（用户实机截图）。
     *
     * 【现象】冷启动回填出一条**已保存**的记录，七个字段与结果区都对，
     *   净收益下面却写着「● 未保存」—— 数据明明在，却告警没存。
     *
     * 【根因】一次**不是用户发起的**重算把 heroSub 覆盖掉了：
     *   `restoreCalc` 把 heroSub 写成「收益率…已保存于 <时间>（点「计算」可重算）」，
     *   随后**步长阈值的异步回调**落地（`stepThrFor` → `if (!nManual) onCalc()`），
     *   onCalc 末尾 `t105SetDirty(true)` ⟹ 又写成「● 未保存」。
     *   用户一个键都没按过，脏标记却翻了。
     *
     * 【修法】回填后**第一次**自动重算照常重画数值（数值可能因为阈值到位而更准），
     *   但**不置脏**：输入框里的值仍是保存时那一套，用户没改过。
     *   消费即清，所以用户之后真的按回车重算，那次**照常**置脏。
     */
    private var justRestored: Boolean = false

    private fun onCalc(userInitiated: Boolean = false) {
        // ① 取值即消费：只有「紧跟在回填之后的那一次」自动重算豁免置脏。
        val exemptDirty = justRestored
        justRestored = false
        suppressReset = false // v4 D2/D9:真实计算清闸,之后的程序写值照常生效
        // ② 只有用户明确计算/交互才退出回填态；自动回填、阈值补算、行情联动不能清闸。
        if (userInitiated) restoreGuardSym = null
        showErr(null) // 稿calc()首行clear:清上一轮目标上限报错
        // t105 假设①:算完之后若程序自己又写了某个 inp 字段,
        // L1213 的 TextWatcher 会调 idle() 把**刚算出的结果**清掉。
        // 这里在取值前就合上闸门:onCalc 期间的所有程序写值都不触发"清结果"。
        suppressReset = true
        try {
            // ⚠⚠ 2026-10-03 用户裁定：保证金/触发价/每格数量 **不预填**（空着，用户自己填）。
            //   连带修一处**顺序事故**：n 自动算原先排在「读保证金」之后，
            //   保证金为空 ⟹ getNum 抛「保证金还没有填」⟹ 后面整段（含 n 自动算）永远走不到，
            //   网格数量就停在 XML 默认值 20。
            //   而 n 只依赖 **最低价/最高价/步长阈值**，与保证金毫无关系 ——
            //   让一个无关字段空着把另一个字段的自动算挡掉，是纯粹的顺序问题。
            // ⟹ 改成：先把 n 算出来并回写（只需 pl/ph），再去读那些必填项。
            //   必填项缺失时报错是对的，但不该顺带把 n 也废掉。
            val pl = getNum(inp["Pl"]!!, "最低价")
            val ph = getNum(inp["Ph"]!!, "最高价")
            val nIn = inp["N"]!!
            if (!nManual) {
                val autoN = autoGridN(pl, ph)
                if (autoN != null && nIn.text.toString().trim() != autoN.toString()) {
                    nIn.setText(autoN.toString())
                }
            }
            val c = getNum(inp["C"]!!, "保证金")
            val l = getNumRo(levText, "杠杆倍率")
            if (l < 1) throw IllegalArgumentException("杠杆倍率必须≥1")
            // ⚠⚠ 2026-10-03 用户裁定：「总投入改为保证金」，并确认口径
            //   **保证金 × 杠杆 = 总投入**（我在选项里给的推荐项，用户选的就是它）。
            // ⟹ 计算引擎拿到的 c 必须仍是**总投入**，所以这里先乘杠杆再传下去。
            //   若直接把保证金当 c 传进 calcGrid，收益率/爆仓价会全部偏掉一个杠杆倍数。
            // ⚠ 这**偏离稿**：稿的 in_C 就是总投入，没有"保证金"这一层。
            // ⚠⚠⚠ 2026-10-03 **本金 = 保证金，杠杆不放大它**。
            //   原话：「我保证金只有500，哪里有37,500了」。
            //
            //   改前是 `val total = c * l`（保证金 × 杠杆），那是我在设计阶段自己定的，
            //   用户当时从选项里选了它，但没有拿真实数字验过 ——
            //   拿 500 × 75 = 37,500 去当本金，收益率被分母缩小了 75 倍，全是假的。
            //
            //   用户的口径：**保证金就是本金**。杠杆的作用只有一个 ——
            //   决定维持保证金率 mmr（见 mmrOf），从而决定爆仓价。
            //   它**不放大本金**，也不放大数量（那处 `q * lev` 上一轮已删）。
            //
            //   ⟹ 杠杆在整条计算里**只喂给 mmr 一个值**。这是用户要的，不是遗漏。
            // ⚠ 偏离稿：稿 L653 的 c 是「投入」且靠 lev 反推数量；产品裁定，不是移植对齐。
            val total = c
            val po = getNum(inp["Po"]!!, "触发价")
            // ⚠⚠ 网格数量：自动算 + 允许手改（用户裁定）。
            //   nManual = true ⟹ 用你敲的值，不再自动覆盖。
            //   自动口径：等比网格，最高→最低，格距 = 该品种**日线**步长阈值 s
            //       n = ceil( ln(ph/pl) ÷ −ln(1−s) )   ← 取满足终点不高于最低价的最小整数
            //   ⚠ 步长阈值取不到（没看过品种 / 币种无源）⟹ 保持你敲的值不动，不硬算。
            //   （nIn / pl / ph / 回写都已在**上方**算完，这里只取最终值。）
            val n = if (nManual) getNum(nIn, "网格数量", true).toInt()
                    else autoGridN(pl, ph) ?: getNum(nIn, "网格数量", true).toInt()
            // B-29 稿 L653 `min={k==='n'?2:...}`: 网格数量下限是 2。
            // ⚠ **只补 n 这一道, 不要顺手把 q 也钳到 1** —— 稿的 min 是 React input 的
            //   HTML 校验属性, 不是状态钳制; q=0.001 是加密的合法最小单位,
            //   钳到 1 会让标准算例收益率从 +58.52% 变成 +87780.48%(t337 实测)。
            //   n>=2 则两边都成立: 一格不成网格, 且 gridLines 的 r=(ph/pl)^(1/n) 在 n=1
            //   时只有两档、首末即 lo/hi, 与任何网格策略都对不上。
            if (n < 2) throw IllegalArgumentException("网格数量至少是 2")
            val q = getNum(inp["q"]!!, "每格数量")
            // #100 稿 L653 `min={k=='n'?2:k=='q'?1:0}`。
            // ⚠ 这条**曾经被实现成「q<1 就把输入框改写成 1」**, 现在回退。
            //   理由三条:
            //   1) 稿的 min 是 React `<input type=number>` 的**HTML 校验属性** ——
            //      让浏览器把「小于 1」标成 invalid, **不是**在状态里改写成 1。
            //      把校验提示实现成状态钳制, 是两件不同的事。
            //   2) 真机实测(t337): 标准算例 q=0.001 被钳到 1 之后,
            //      收益率从 +58.52% 变成 **+87780.48%** —— 一个把收益率抬到五位数百分比的
            //      「按稿」不是按稿, 是把核心功能改坏了。
            //   3) 稿自己就矛盾: 它的默认演示参数(469/150000/55000/77000/11/**0.001**/1.5)
            //      正是 q=0.001 的加密场景, 而它同时给这个框写了 min=1。
            //      ⟹ min 只能是校验提示(或只对股票有意义), 不是钳制。
            // 现在只保留「必须为正」这道闸(引擎本来也要 q>0), 口径与上面
            // 「杠杆倍率必须≥1」「手续费率不能为负」完全一致。
            if (q <= 0.0) throw IllegalArgumentException("每格数量必须大于 0")
            // ⚠ 2026-10-03 维持保证金率改为**按杠杆查表**（15x=5% / 75x=0.65% 线性插值，
            //   见顶层 mmrOf）。75x 以上按 0.65% 封顶 —— 只有两个实测点，不外推。
            val mmr = mmrOf(l)
            // ⚠ 2026-10-03 这两个率已从界面删除（见顶层常量区的说明），改用内部常量。

            // 【F-1 新增】把「杠杆倍率 l」(:485) 与「做多空 sideShortMode」真正接进引擎。
            // App 原先这两个控件都不进计算（lev 只进 CSV :752，方向只做高亮 :1599）。
            val r = calcGrid(total, pl, ph, po, n, q, mmr, dbl, l, sideShortMode)
            // ⚠⚠ 2026-10-03 用户裁定：**每格数量 = 每格股数**（这格买 q、下一格卖 q），由用户自己填。
            //   ⟹ 引擎里那处 `q = qIn * lev` 已删（杠杆算了两遍，见 calcGrid 注释）。
            //
            // ⚠⚠⚠ 这里**曾经**加过一段「仓位约为本金的 X%」的提醒，**已被用户要求删掉**。
            //   理由与前一个源状态行完全相同：**用户没要求过。**
            //   我当时的理由是「如实摆出仓位/本金不匹配」—— 出发点没错，
            //   但**动机是替用户判断**，而用户要的是「我填什么就按我的算」。
            //   仓位大小是用户的决策，不是 App 该插嘴的事。
            //   ⟹ 保留修复（`q = qIn`），删掉提醒。若日后要提醒，由用户提出再加。
            val teal = attrColor("colorTeal")
            val red = attrColor("colorRed")
            val ink = attrColor("colorInk")
            val sub = attrColor("colorSub")
            // 稿451-454:目标上限硬约束——爆仓价超上限:其余结果全部归零(稿resetIdle),
            // 只亮爆仓价、爆仓卡变红、报错「爆仓价高于目标值」,不画网格明细
            // ⚠⚠⚠ 2026-10-03 **超过爆仓价改为「可计算 + 红色提醒」，不再归零**
            //   （用户裁定：「计算界面超过爆仓值无法计算。应该改为可以计算，但是要提醒用户超过爆仓价而已」）
            //
            // 【原行为】爆仓价高于目标上限 ⟹ idle() 清空全部结果 + 净收益「--」+
            //   「其余结果全部归零」+ 藏掉四格与两个折叠口 + return。
            //   ⟹ 用户拿不到任何数字，**连想比较一下都做不到**。
            //
            // 【为什么这样改是合理的】爆仓价高于目标价是一个**结论**，不是**输入非法**：
            //   参数本身合法，引擎算得出来，结果也有参考价值（它精确地告诉你
            //   「按这套网格，爆仓会发生在目标价之上」—— 这正是用户想看到的信息）。
            //   归零等于把这个结论藏起来。
            //
            // 【现在】照常算、照常画，只把三处染红提醒：
            //   爆仓价数字、爆仓卡边框、顶部 StateLine。
            //   ⚠ 净收益/收益率等**不再伪造为「--」** —— 它们是真算出来的，不是缺数据。
            val cap = lkCap
            // ⚠⚠ 2026-10-29 t42 · **泛红主条件改成「爆仓价 > 网格最低价」**（用户反馈「高于爆仓价也不泛红」）。
            //   改前唯一触发条件是 `r.liq > cap`，而 `cap` 来自「行情页第二支撑」（lkCap）**可能为空**：
            //   单支撑品种 cap = null ⟹ liqOverCap 恒 false ⟹ **泛红永不触发**。
            //   代码自己上面那段的注释就写着「cap 是可选的、最低价是网格计算的必需输入」，
            //   却把必需的提醒挂在可选值上 —— 这就是那条用户反馈的根因。
            //   ⟹ 现在**两选一**（命中任一即 markLiqBad）：
            //        ① `r.liq > gridLow`（**必需输入**，主条件）
            //           语义 = 价格还没跌到网格下界、仓位就已经强平 ⟹ 这套网格根本打不完；
            //        ② `r.liq > cap`（有第二支撑时才存在，附加条件，原逻辑保留）。
            //   ⚠ `gridLow` 直接用 **`pl`**：它就是同一函数里 `calcGrid(total, pl, ph, po, …)` 传进去的**同一个值**
            //     （同一次 getNum 解析、同一精度），**不从 EditText 重新解析**、也不用 CalcResult
            //     （CalcResult 不含下界字段，引擎也没返回）—— 重新解析会引入千分位/精度分叉。
            val gridLow = pl
            val liqOverGrid = r.liq != null && r.liq > gridLow
            val liqOverCap = cap != null && r.liq != null && r.liq > cap
            val liqBad = liqOverGrid || liqOverCap
            // ⚠⚠ 2026-11-05 用户追问「外面的框怎么还是红色」⟹ **数字与边框必须是同一个判据**。
            //   屏上唯一可见的参照物是「爆仓线 <第二支撑>」：
            //     有线 → 超过才告警（红字 + 红框），没超过 → 绿字 + 无框；
            //     没线 → 退回 t42 那套（爆仓价 > 网格最低价 / > 目标上限）。
            //   ⚠ 改前边框走 liqBad、数字走 liqLine ⟹ 会出现"绿数字 + 红框"的自相矛盾。
            val liqWarn = liquidationLinePresentation(r.liq, currentMarketSupport2ForLine)?.exceeded
                ?: liqBad
            if (liqWarn) {
                // 提醒行：沿用稿里那条 StateLine，只把文案换成「超过」而非「高于」——
                // 「高于目标值」在语义上也对，但用户说的是「超过爆仓价」，用用户的词。
                // ⚠⚠ 2026-10-29 t37 · **`calc_overline` 恒 GONE**（用户裁定「计算页不画 calc_overline」，
                //   见 `交接_四屏原型.md:352`）。改前这里是 `visibility = View.VISIBLE`：
                //   爆仓价高于目标价时点亮一条 StateLine 提醒。
                //   ⚠ 提醒**没有消失**：爆仓价数字染红 + 爆仓卡红框仍由 markLiqBad() 表达；
                //     去掉的只是稿里本来就没有的那条 overline。节点与绑定保留（删节点会断绑定）。
                if (::calcOverline.isInitialized) calcOverline.visibility = View.GONE
                if (::calcOverlineTopline.isInitialized) calcOverlineTopline.visibility = View.GONE
                markLiqBad()
            }
            // ⚠ 旧行为（idle + 净收益「--」+ 其余结果全部归零 + 藏掉四格与折叠口 + return）
            //   已按用户裁定删除：爆仓价高于目标值是**结论**不是输入非法，归零等于把结论藏起来。
            //   完整理由见上方 liqOverCap 处的注释。
            // ⚠⚠ 2026-10-17 t12 · **done 态「爆仓价」格补回 surface-2 灰面**（用户拍板）。
            //   改前这里是**无条件** `statLiqCard.background = null` —— 它把 XML 的
            //   `calc_tile_bg` 面清掉，于是 done 态那张格裸在卡白上，与其余三格不一致
            //   （稿 `:132-140` 把四格写成同一类 `.calc-tile`，底一律 bg-surface-2）。
            //   改为：**正常态还原原始面**（bind 期存下的 [statLiqBg0]，与 idle 路径同一手法）。
            //   ⚠⚠ **红框分支保持原样、且不再被抹掉** —— 这是本次顺带修掉的一处**既有 bug**：
            //     上面 L1390 的 [markLiqBad] 刚把红框画好（透明底 + 红描边），旧代码在这一行
            //     又无条件 `= null` 把它抹了 ⟹ **红框从来没显示过**，与本文件 L1380-1381 自己写的
            //     「只把三处染红提醒：爆仓价数字、**爆仓卡边框**、顶部 StateLine」直接矛盾。
            //     现在 over 分支**不碰 background** ⟹ 红框按既有逻辑（markLiqBad）显示出来。
            //   ⚠ [markLiqBad] 本体（:2000 附近）与它的 `:1974` 红框逻辑**一字未动**。
            if (!liqWarn && ::statLiqCard.isInitialized && statLiqBg0 != null) {   // t42：不告警才还原原始面（判据见上 liqWarn）
                statLiqCard.background = statLiqBg0
            }
            // ⚠⚠ 2026-10-16 t5 · **把 idle 的 faint 占位色还原成墨色**。
            //   idle() 现在把七个 statVals 全部染成 colorFaint（稿 :145 idle 四格字色）。
            //   其中 grids / eqb / pos / sell / cost / ccy 六格**没有别的赋值点** ——
            //   它们的颜色本来就只来自 XML 的 ?attr/colorInk ⟹ 不在这里还原，
            //   点一次「计算」出来的 done 态会带着 faint 的占位色，即 done 态回归。
            //   ⚠ 必须放在 roe / liq 两格各自的赋值**之前**：那两格下面会覆盖成
            //     涨跌色（roe）或墨色/红（liq），顺序反了会把它们的颜色冲掉。
            //   （原先这里那行 `statVals["liq"]!!.setTextColor(ink)` 被本行覆盖，已合并删除。）
            statVals.values.forEach { it.setTextColor(ink) }
            // t141:值不带 USD(稿里 USD 在行内单位/见底权益副标);减号用 U+2212 真减号
            if (::calcHeroBox.isInitialized) calcHeroBox.visibility = View.VISIBLE
                t227SetResultArea(true)
if (::calcIdleHint.isInitialized) calcIdleHint.visibility = View.GONE
            // ⚠ 2026-10-29 t42：**无条件 GONE** —— overline 在 t37 已按用户裁定永久下线，
            //   与 liqBad 命中与否无关（不要因为这次改判据又把那条 StateLine 点亮）。
            //   （历史：t37 之前这里是「liqOverCap 时 VISIBLE、否则 GONE」；那套已随 t37 作废。）
            if (::calcOverline.isInitialized) calcOverline.visibility = View.GONE
            if (::calcOverlineTopline.isInitialized) calcOverlineTopline.visibility = View.GONE
            // ⚠ t42：**命中任一条件（爆仓价 > 网格最低价 / > 目标上限）**就把爆仓价数字染红；否则回墨色。
            // ⚠⚠ 2026-11-05 用户裁定：**有「爆仓线」时，颜色跟着那条线走** ——
            //   实报「爆仓价没超过线也爆红，应该显示绿色」。
            //   病根：t42 的主条件是 `liq > 网格最低价(pl)`，而屏上给用户看的参照物是
            //   「爆仓线 <第二支撑>」；当最低价低于第二支撑时，`liq` 可以落在两者之间 ——
            //   对用户来说"还没到线"（该安全），按 pl 判却已算危险 ⟹ 红。
            //   ⟹ 判据顺序改为：**有线就按线**（超过 = 红，没超过 = 绿）；没线才退回 t42 那套。
            //   ⚠ 卡边框（markLiqBad）仍按 liqBad 走"网格打不完"那条独立语义，本次不动。
            val liqLine = liquidationLinePresentation(r.liq, currentMarketSupport2ForLine)
            val liqColor = when {
                liqLine != null -> if (liqLine.exceeded) red else attrColor("colorTeal")
                liqWarn -> red
                else -> ink
            }
            statVals["liq"]?.setTextColor(liqColor)
            // 爆仓线副行读当前行情页的第二支撑，不依赖可选目标上限 lkCap 是否保存/解除。
            // 主值仍是 r.liq；只有模型有爆仓价且当前行情存在第二支撑时显示。
            currentCalcLiqForLine = r.liq
            paintLiqGap()
            hero.setTextColor(if (r.net >= 0) teal else red)
            hero.text = (if (r.net >= 0) "+" else "\u2212") + fmt(Math.abs(r.net))
            animateResultValue(hero)
            // t102 第4步:hero 副行原来写「收益率 X% · N卖M买 · 见底权益约 Y USD」,
            // 这三样**下方四格里已经全有了**(收益率/初始持仓/卖出总额)——同一信息在一屏出现两遍。
            // 全局硬规则:一屏只允许「用户要的值、状态、能点的操作」,重复一律删。
            // hero 副行现在只留身份提示;真正的值看下面四格。
            // t167 悬案 D:这两行是 t105SetDirty(true) 的逐字复制(同色同文案),
            // 在 onCalc 里绕过了那个已经收敛过的函数 —— 同一件事两个主人。改为调用它。
            // ⚠ 2026-10-05：[exemptDirty] 为真时**不置脏** —— 那是回填后阈值到位触发的自动重算，
            //   用户没改过任何输入，屏上显示的就是已保存的那份（见 [justRestored] 的说明）。
            if (!exemptDirty) t105SetDirty(true)
            // ④稿582:强平价≤0显示0.00(不再显示负数);null=永不爆仓
            statVals["liq"]!!.text = r.liq?.let { fmtSmart(it) } ?: "不爆仓"
            // t153 ⑨:稿 L622 负号用 U+2212 真减号,不是 ASCII '-'(与 hero 同一处理)
        // 稿 L622 末位 R.roi>=0?1:2, 配合 L681: 1→var(--up) 绿, 2→var(--dn) 红。
        // 改前只写 text 不设色, 颜色是布局里写死的 colorInk ⟹ 涨了也是黑的。
        statVals["roe"]!!.text = (if (r.roe >= 0) "+" else "\u2212") + String.format("%.2f%%", Math.abs(r.roe))
        statVals["roe"]!!.setTextColor(
            if (r.roe >= 0) attrColor("colorTeal") else attrColor("colorRed"))
            statVals["pos"]!!.text = "%.4f".format(r.q0)
            statVals["sell"]!!.text = fmt(r.sellT) + " USD"
            // t105 缺陷②:t104 建了视图没接线,五格恒为 --。这里全部接上 onCalc 的真实返回值。
            // 稿 L625: R.nb+' / '+R.ns  ⟹ "4 / 8"
            // 改前是 "%d买 %d卖"。⚠ 我今晚的验收基线抄的就是这个错格式, 正在一并更正。
            statVals["grids"]!!.text = "%d / %d".format(r.b, r.m)
            // ⚠ 2026-10-28 t4：`statVals["eqb"]!!.text = t219fmt2(r.eqBottom)` 已删 ——
            //   「见底权益」格整块下线（用户裁定），节点与 map 项都不在了，留着这行是 **NPE**。
            //   r.eqBottom 仍由引擎照常算出（业务计算未动），只是不再上屏。
            statVals["cost"]!!.text = fmt(r.cost0) + " USD"
            // 计价币种与美元汇率:沿用 CSV 的既有语义(L460-464)——
            // USD 品种汇率恒 1,取不到汇率就留空,**不为填满而编数**。
            val cur = MktData.FX.curOf(mktPanel.curSym())
            val fx = if (cur == "USD") "1.0000"
                else MktData.FX.rateOf(cur)?.let { numStr(it) } ?: ""
            // 稿 L632:「计价币种 / 美元汇率」是【一行】,值形如「USD · 1.0000」。
            // stat_fx 行已按稿合并进 stat_ccy(本行),XML 侧那五行已删成四行。
            statVals["ccy"]!!.text = if (fx.isEmpty()) cur else "$cur · $fx"
            // t105:计算成功 = 结果区有东西了 → 置脏(● 未保存),等用户点「保存数据」才清
            // ⚠ 同上：回填后那一次自动重算不置脏（[justRestored]）。
            if (!exemptDirty) t105SetDirty(true)
            detSum.setTextColor(ink)
            detSum.text = "买%d/卖%d/线%d · 持仓%.6f · 成本%.2f%s".format(
                r.b, r.m, r.lines, r.q0, r.cost0, if (dbl) " · 加倍建仓" else "")
        // 稿 L696: 网格明细那行右侧显示 R.lines.length, 算完就该显示行数, 不必等用户点一下。
        // t167: lastLines 与 detSum 读同一个 r.lines, 不许另立真相。
        lastLines = r.lines
        t229SyncLadderNote()
        t237SyncFeeToSettings()   // 稿 L783-784: 设置页回显计算页的费率

            val buyFee = r.cost0 * FEE_TAKER   // 建仓=吃单
            var cumSell = 0.0
            rows.removeAllViews()
            rows.addView(makeRow("#", "网格价", null, "本格金额", "累计净收益", sub, false, true))
            var zebra = false
            // D8:稿L668 只量化**首末行**(quantPx),中间行用普通 f2 两位小数;
            // 序号用 lines 的原始下标(不按过滤后的重排序号),顺带修排序后 # 跳号
            val allLines = gridLines(pl, ph, n)
            val lastIdx = allLines.size - 1
            allLines.sorted().forEachIndexed { i, p ->
                if (p < po - 1e-9 || p > po + 1e-9) {
                    // 稿 L182: x.toFixed(6) —— 统一 6 位小数, 稿里没有"首末行例外"这条例外规则。
                    // 改前是 if (i==0||i==lastIdx) fmtQ(p) else fmt(p), 首末两行与中间行格式不同。
                    val txt = t219px6(p)
                    if (p < po) {
                        // 买入行:本格金额有值(q*p),累计净收益给「—」(还没卖,累计无从谈起)
                        rows.addView(makeRow(
                            "${i + 1}", txt, "买入|buy", t219px2(q * p), "—",
                            sub, zebra, false))
                    } else {
                        val amt = q * p
                        cumSell += amt
                        val cum = cumSell - r.cost0 - buyFee - cumSell * FEE_MAKER   // 卖出=挂单
                        // 卖出行:本格金额「—」,累计净收益有值(保留涨跌着色)
                        // 稿 L725: color = g[4]==='—' ? ink3 : var(--dn)
                        // ⟹ 【恒红】, 与正负无关。改前是 if (cum >= 0) teal else red, 正数会是绿的。
                        rows.addView(makeRow(
                            "${i + 1}", txt, "卖出|sell", "—",
                            t219fmt2(cum), red, zebra, false))
                    }
                }
                zebra = !zebra
            }
            if (dbl) {
                // 稿 L725 同款: 恒红
                rows.addView(makeRow(
                    "顶", MktData.fmtCeilQ(ph), "全平|sell", "—", t219fmt2(r.net),
                    red, zebra, false))
            }
            animateGridRows()
            // 稿§1.1:计算成功只暂存待存结果(不落库);点「保存数据」才追加一条
            pendingRec = { logRec(r, pl, ph, n, c, l, po, q, FEE_TAKER, mmr) }
            saveBtn.text = "保存数据"
        } catch (e: Exception) {
            val msg = e.message ?: "出错"
            // 【F-1 新增】满杠杆边界（L × mmr ≥ 1 ⟹ 等价价 ≤ 0）。App 原本没有这道守卫。
            // 走**与「爆仓价高于目标值」完全同一套 over 态渲染**（净收益「--」+「其余结果全部归零」
            // + 折叠口收起 + 爆仓卡标红），不新造一套 UI —— 上面 cap 分支那段就是同一套。
            if (e is OverLeverageException) {
                idle()
                if (::calcHeroBox.isInitialized) calcHeroBox.visibility = View.VISIBLE
                t227SetResultArea(true)
                if (::calcIdleHint.isInitialized) calcIdleHint.visibility = View.GONE
                val f1 = attrColor("colorFaint")
                hero.setTextColor(f1)
                hero.text = "--"
                heroSub.setTextColor(f1)
                heroSub.text = "其余结果全部归零"
                // ⚠️ 这里**刻意不点亮** calcOverline / calcOverlineTopline ——
                // 那两行写死的是「！爆仓价高于目标值」，是 cap 分支的原因文案。
                // 满杠杆边界的原因不同，若沿用就会**界面上说着一件错事**
                // （显示「爆仓价高于目标值」，实际是杠杆×保证金率≥1）。
                // 真正的原因由下面 showErr(msg) 以**红色**给出，不另造文案。
                if (::statTiles.isInitialized) statTiles.visibility = View.GONE
                if (::statTilesTopline.isInitialized) statTilesTopline.visibility = View.GONE
                if (::moreToggle.isInitialized) moreToggle.visibility = View.GONE
                if (::detToggle.isInitialized) detToggle.visibility = View.GONE
                if (::ladderNote.isInitialized) ladderNote.visibility = View.GONE
                if (::toggleRow.isInitialized) toggleRow.visibility = View.GONE
                if (::toggleRowTopline.isInitialized) toggleRowTopline.visibility = View.GONE
                statVals["liq"]?.let { it.setTextColor(attrColor("colorRed")) }
                markLiqBad()
                showErr(msg)
                return
            }
            // t145:所有 IllegalArgumentException(不只"还没有填")都要把**原因原文**写进 calc_err。
            // 原来只对"还没有填"调 showErr —— 像"触发价必须在最低价和最高价之间"这类拦截
            // 就只清结果、不吭声,界面上什么都不显示,用户只能猜。这与今晚揪出的
            // 七个"骗人"是同一类:**界面不告诉你发生了什么**。
            // ⚠️ **只改反馈,不改计算** —— L319 的校验逻辑与 calcGrid 一个字没动。
            if (e is IllegalArgumentException) {
                idle()
                showErr(msg)
                return
            }
            val red = attrColor("colorRed")
            hero.setTextColor(red)
            hero.text = "出错"
            heroSub.setTextColor(red)
            heroSub.text = msg
            statVals.values.forEach { it.text = "—" }  // t119 ① 同上
        } finally {
            // t105 假设①的收口:onCalc 期间把 suppressReset 闸门合上,程序写值不再触发 idle() 清结果。
            // 放在 finally:成功、报错、空值早退三条路都会恢复,不会把闸门漏在开的位置。
            suppressReset = false
        }
    }

    // ---------- ⑥计算记录(稿615-656 logRec/recCSV/recExport/recClear:localStorage→SharedPreferences) ----------
    // 表头列数与 RHEAD 同步(当前 29 列);档位线列竖线分隔全量;上限2000条(超限从头剔除)。
    // 范围缩小(用户指令):App每格数量一律手填→数量来源恒「手填」;自动算数量列保留但恒空(与稿导出schema一致便于合并)。
    private val RECCAP = 2000
    private var recs = mutableListOf<org.json.JSONObject>()
    private lateinit var recCount: TextView
    private lateinit var recSize: TextView  // t115 ⑥
    private var pendingExport = false
    private val recPref get() = getSharedPreferences("gridcalc_records_v1", Context.MODE_PRIVATE)

    // 前向声明：clearCurrentSymRecs 在它定义之前就要用（Kotlin 对成员函数本可后置，
    // 但历史上这里曾引用同文件内的局部函数 saveBtnSaved（跨定义顺序会报 unresolved；该函数已于 2026-11-04 删除）

    /**
 * ⚠⚠⚠ 2026-10-03 **恢复本函数**（我把它当死代码删掉了，那是个错误）。
 *
 * 【删错的经过 —— 这是一条要记住的链】
 *   1. 删设置屏「数据」区块 ⟹ 摘掉了它那行 `recLoad()` 接线
 *   2. 于是 `recLoad` 变成「0 调用点」⟹ 被死代码清理判成死函数 ⟹ 删掉
 *   3. **`recs` 从此只有写、没有读** —— `recSave()` 照存，但没人再从 prefs 读回来
 *   ⟹ App 一重启，记录列表就是空的 ⟹ 用户实报「退出去后，保存的数据又消失了」
 *
 * 【教训，比这个函数本身重要】
 *   **「先删调用点 ⟹ 再按‘无调用’删被调方」这两步必须一起判断。**
 *   一个函数突然没有调用点时，第一反应应该是「谁把它拆了」，
 *   而不是「它本来就没人用」。本文件里 `rateBox` / `clearRecords` / `recLoad`
 *   都是这样被我删掉的，其中 `recLoad` 删掉的是**唯一的持久化读取路径**。
 *
 *   ⟹ 判据修正：**「0 调用点」不是死代码的证据，是「有人刚拆了它」的信号。**
 *      真要删，先确认它不是持久化/序列化/生命周期回调这类「由框架间接调用」的东西。
 */
private fun recLoad() {
          try {
              val s = recPref.getString("recs", null)
              recs = if (s.isNullOrEmpty()) mutableListOf() else {
                  val a = org.json.JSONArray(s)
                  (0 until a.length()).mapNotNull { a.optJSONObject(it) }.toMutableList()
              }
          } catch (e: Exception) {
              recs = mutableListOf()
          }
          while (recs.size > RECCAP) recs.removeAt(0)
          recPaint()
      }

      /**
 * ⚠ 2026-10-03 **删除当前品种的已保存记录**（用户：「计算界面也要有个清空数据的功能」）。
 *
 * 【范围】**只删当前品种** —— 用户在三个选项里明确选的这个。
 *   ⟹ 不删全部，是为了保住「按品种关联」那套能力：删全部会把别的品种存好的参数一起清掉，
 *     那正是这个会话花大力气打通的东西。
 *   ⚠ 本函数是当初被我当「死代码」删掉的 [recLoad] 的同类邻居（clearRecords）。
 *     **教训同上：先摘调用点、再按「无调用」删被调方，这两步必须一起判断。**
 *
 * 【无二次确认】用户裁定。⚠ 这是破坏性操作且**不可撤销** —— 用户的原话是针对
 *   保存说的（「点完确定直接保存」），此处是他对删除单独给的答复，照办。
 *   ⟹ 所以给的是**即时可见反馈**（按钮短暂显示删了几条），而不是弹窗。
 *
 * 【顺带】删完当前页的回填也要撤：否则 [restoreGuardSym] 还指着这个品种，
 *   联动会被永久误挡（同 restoreCalc 里 rec == null 那个分支的清零点逻辑）。
 */

    // ⚠ 2026-10-03 **删除当前品种的已保存记录**（用户：「计算界面也要有个清空数据的功能」）。
    //   范围=**当前品种**、**无二次确认**（用户裁定）。只删当前品种是为了保住「按品种关联」——
    //   删全部会把别的品种存好的参数一起清掉，那正是本会话花大力气打通的东西。
    //   ⚠ 本函数的同类邻居 [recLoad]/[clearRecords] 都曾被我当「死代码」删掉：
    //     **先摘调用点、再按「无调用」删被调方，这两步必须一起判断。**
    //   ⚠ 历史上它曾需要放在 saveBtnSaved 之后（该函数已于 2026-11-04 删除，顺序约束不再存在），
    //     声明顺序反了会报 unresolved（Kotlin 的 class 成员不支持前向声明，抽象声明也不行）。
    fun clearCurrentSymRecs() {
        val sym = try { if (::mktPanel.isInitialized) mktPanel.curSym() else "" } catch (_: Throwable) { "" }
        if (sym.isBlank()) return
        val key = normSym(sym)
        val before = recs.size
        recs = recs.filter { normSym(it.optString("sym")) != key }.toMutableList()
        val removed = before - recs.size
        if (removed > 0) recSave()
        // 无论是否有已保存记录，清空都要撤销当前品种的输入、结果、回填闸和
        // 自动网格状态；否则「没有记录」时按钮看似成功，旧值仍会留在屏上。
        clearCalcInputs()
        // 显式清空还要拒绝「清空这一刻已经发出的那一批」行情回调（已画在屏上那批的重绘
        // onInfo、以及 paintTgt 的延时重试都算）。屏障取 MktPanel 当前已发出的最大批次代号：
        //   回调代号 <= 屏障 ⟹ 清空前那一批 ⟹ 拒（t7 的 F-CLEAR-LATE-TARGET-CALLBACK 不回归）；
        //   清空后重新进入同品种的 mkLoad 会拿到更大的代号 ⟹ 放行（本轮要修的同品种重进）。
        linkBlockedSeq = try {
            if (::mktPanel.isInitialized) mktPanel.reqSeqNow() else -1
        } catch (_: Throwable) { -1 }
        linkBlockedSym = key
        // clearCalcInputs 已清回填闸；这里保留显式判据，防止未来拆分清理路径时漏放。
        if (restoreGuardSym == key) restoreGuardSym = null
        pendingRec = null
        saveIdle()
        // ⚠⚠ 2026-10-05 **提示写到清空按钮自己身上**（原来错写在 [saveBtn] 上）。
        //   改前用户点「清空数据」，屏上却是「保存数据」按钮变成「已删除 N 条」——
        //   反馈落在了没点的那个按钮上，等于没反馈。
        //   ⟹ 这里改 [clearRecBtn]；[saveBtn] 的样式与复原逻辑全部不碰（那是保存按钮自己的）。
        // ⚠ 2026-11-04 恢复：清空按钮自己变成「已删除 N 条」（用户截图要的形态），1.6s 后复原。
        val msg = if (removed > 0) "已删除" else "没有已保存记录"
        if (::clearRecBtn.isInitialized) {
            // ⚠ 2026-11-04 同上（用户裁定「肯定要弹回去啊」）：显示「已删除」1.6s 后自动复位。
            clearRecBtn.text = msg
            uiHandler.postDelayed({
                if (clearRecBtn.text == msg) clearRecBtn.text = "清空数据"
            }, 1600)
        }
    }

    private fun recSave() {
        try {
            recPref.edit().putString("recs", org.json.JSONArray(recs).toString()).apply()
        } catch (e: Exception) {
        }
        recPaint()
    }

    private fun recPaint() {
        if (::recCount.isInitialized) recCount.text = "已记录 ${recs.size} 条"
        // t115 ⑥:右侧补记录占用大小。全程 try/catch;量不到就显示「—」而不是假装 0.0 KB。
        if (::recSize.isInitialized) {
            recSize.text = try {
                // 量记录真实占用的字节数(键值 UTF-8 长度之和),量不到就显示「—」,不假装 0.0 KB
                val bytes = recPref.all.entries.sumOf { (k, v) ->
                    val vs = (v as? String)?.toByteArray(Charsets.UTF_8)?.size ?: 0
                    k.toByteArray(Charsets.UTF_8).size + vs
                }
                "%.1f KB".format(bytes / 1024.0)
            } catch (_: Throwable) { "—" }
        }
    }

    // JS toPrecision(10):10位有效数字、去尾零(数值化后与稿+号还原等价)
    private fun prec10(v: Double): String =
        java.math.BigDecimal(v).round(java.math.MathContext(10)).stripTrailingZeros().toPlainString()

    // JS String(number):整数值不带.0(469→"469"),其余最短往返
    private fun numStr(v: Double): String =
        if (v.isFinite() && v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()

    // 稿logRec(623):每次成功计算追加一条;交易对/品种/现价取当前行情页上下文
    private fun logRec(
        r: CalcResult, pl: Double, ph: Double, n: Int, c: Double, l: Double,
        po: Double, q: Double, fee: Double, mmr: Double
    ) {
        try {
            val o = org.json.JSONObject()
            o.put("t", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date()))
            o.put("sym", mktPanel.curSym())
            o.put("typ", mktPanel.curTyp())
            o.put("mark", mktPanel.curPx() ?: org.json.JSONObject.NULL)
            o.put("Pl", pl); o.put("Ph", ph); o.put("N", n); o.put("g", gridMode)
            o.put("C", c); o.put("L", l); o.put("Po", po); o.put("q", q)
            o.put("man", "手填")
            o.put("auto", "")
            o.put("liq", r.liq ?: org.json.JSONObject.NULL)
            o.put("cap", lkCap ?: org.json.JSONObject.NULL)
            o.put("dbl", if (dbl) "是" else "否")
            o.put("pos", r.q0); o.put("cost", r.cost0); o.put("sell", r.sellT)
            o.put("net", r.net); o.put("roe", r.roe)
            o.put("B", r.b); o.put("M", r.m)
            o.put("fee", fee); o.put("mmr", mmr)
            // v4§7 记录两列:计价币种 + 美元汇率(1USD=?);USD品种汇率=1,取不到汇率留空
            val cur = MktData.FX.curOf(o.optString("sym"))
            o.put("cur", cur)
            o.put("fx", if (cur == "USD") "1"
                else MktData.FX.rateOf(cur)?.let { numStr(it) } ?: "")
            o.put("lines", gridLines(pl, ph, n).joinToString("|") { prec10(it) })
            recs.add(o)
            while (recs.size > RECCAP) recs.removeAt(0)
            recSave()
        } catch (e: Exception) {
        }
    }

    private val RHEAD = listOf(
        "时间", "交易对", "品种类型", "现价", "最低价", "最高价", "网格数", "网格模式", "保证金", "杠杆", "触发价",
        "每格数量", "数量来源", "自动算数量", "预估强平价", "目标上限", "加倍建仓", "持仓数量", "建仓成本", "卖出总额", "净收益", "收益率",
        "买格", "卖格", "手续费率", "维持保证金率", "档位线", "计价币种", "美元汇率(1USD=?)"
    )

    private fun csvCell(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    // 稿recCSV(636):列数与 RHEAD 同步(当前 29 列)一一对应;liq null→永不爆仓、≤0→0.00;档位线竖线全量
    private fun recCsv(): String {
        val sb = StringBuilder(RHEAD.joinToString(","))
        for (r in recs) {
            sb.append('\n')
            val liqCell = if (r.isNull("liq")) "永不爆仓"
            else { val x = r.optDouble("liq"); if (x <= 0) "0.00" else prec10(x) }
            val mark = if (r.isNull("mark")) "" else numStr(r.optDouble("mark"))
            val cap = if (r.isNull("cap")) "" else numStr(r.optDouble("cap"))
            val row = listOf(
                r.optString("t"), r.optString("sym"), r.optString("typ"), mark,
                numStr(r.optDouble("Pl")), numStr(r.optDouble("Ph")),
                r.optInt("N").toString(), r.optString("g"),
                numStr(r.optDouble("C")), numStr(r.optDouble("L")),
                numStr(r.optDouble("Po")), numStr(r.optDouble("q")),
                r.optString("man"), r.optString("auto"), liqCell, cap, r.optString("dbl"),
                numStr(r.optDouble("pos")), numStr(r.optDouble("cost")), numStr(r.optDouble("sell")),
                numStr(r.optDouble("net")), numStr(r.optDouble("roe")),
                r.optInt("B").toString(), r.optInt("M").toString(),
                numStr(r.optDouble("fee")), numStr(r.optDouble("mmr")), r.optString("lines"),
                r.optString("cur"), r.optString("fx")
            )
            sb.append(row.joinToString(",") { csvCell(it) })
        }
        return sb.toString()
    }

    // 稿recExport(643):UTF-8 BOM + 文件名 gridcalc_yyyyMMdd_HHmm.csv → 公共下载目录
    private fun exportCsv() {
        if (recs.isEmpty()) {
            // 稿 L815-822: 屏 4 的反馈一律走底部 toast, 这里原来弹的是系统 AlertDialog
            showToast(getString(R.string.export_empty))
            return
        }
        val name = "gridcalc_" +
            java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US).format(java.util.Date()) +
            ".csv"
        val data = "\uFEFF" + recCsv()
        val bytes = data.toByteArray(Charsets.UTF_8)
        val ok: Boolean
        val why: String?
        // 稿 L820 的「撤销」在导出这条路上也存在(稿的 toast 组件无条件渲染它)。
        // 导出唯一的逆操作就是把刚写出去的那个文件删掉 —— 做成真撤销,
        // 不画一个点了没反应的按钮(本文件上一版 t256 的教训)。
        var undoDel: (() -> Unit)? = null
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            var uri: android.net.Uri? = null
            var err: String? = null
            try {
                val v = android.content.ContentValues()
                v.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                v.put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/csv")
                v.put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOWNLOADS)
                v.put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                uri = contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                if (uri == null) {
                    err = "MediaStore.insert 返回 null,没能在下载目录建文件"
                } else {
                    val os = contentResolver.openOutputStream(uri)
                    if (os == null) {
                        // 原 bug 的正身:这里以前是 ?.use{} 静默跳过,然后照样 ok=true
                        err = "openOutputStream 返回 null,拿不到输出流"
                    } else {
                        os.use { it.write(bytes); it.flush() }
                        // 回读实际长度对账,不等就是没写全
                        val real = try {
                            contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
                        } catch (_: Exception) { null }
                        if (real == null) err = "回读文件大小失败,无法确认是否写全"
                        else if (real != bytes.size.toLong())
                            err = "写入不完整:期望 ${bytes.size} 字节,实际 $real 字节"
                    }
                }
                if (err == null) {
                    v.clear()
                    v.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                    val good = uri
                    if (good != null) {
                        contentResolver.update(good, v, null, null)
                        undoDel = { try { contentResolver.delete(good, null, null) } catch (_: Exception) { } }
                    }
                } else {
                    // 失败:把这条 pending 记录整个删掉,免得用户在"文件"里看到 0 字节残留
                    dropPending(uri)
                }
            } catch (e: Exception) {
                err = e.javaClass.simpleName + ": " + (e.message ?: "无详细信息")
                dropPending(uri)
            }
            ok = err == null
            why = err
        } else {
            // legacy(API<29):同样不能"不抛异常就算成功",要确认文件真的存在且长度对得上
            var err: String? = null
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                pendingExport = true
                requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 1001)
                return
            }
            try {
                val dir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                val f = java.io.File(dir, name)
                f.writeBytes(bytes)
                when {
                    !f.exists() -> err = "写完文件却不存在"
                    f.length() != bytes.size.toLong() ->
                        err = "写入不完整:期望 ${bytes.size} 字节,实际 ${f.length()} 字节"
                }
                if (err != null) f.delete() // 同样不留半截文件
                else undoDel = { try { f.delete() } catch (_: Exception) { } }
            } catch (e: Exception) {
                err = e.javaClass.simpleName + ": " + (e.message ?: "无详细信息")
            }
            ok = err == null
            why = err
        }
        finishExport(ok, name, why, undoDel)
    }

    // 失败时清掉 MediaStore 里那条 pending 半截文件
    private fun dropPending(uri: android.net.Uri?) {
        if (uri == null) return
        try { contentResolver.delete(uri, null, null) } catch (_: Exception) { }
    }

    // 成功/失败弹窗:成功要把**文件名与位置**一起给出去,否则用户根本找不到文件;
    // 文件名用同一个 name 变量,不在文案里再拼一遍(拼一遍必然漂)
    private fun finishExport(ok: Boolean, name: String, why: String?, undo: (() -> Unit)?) {
        val msg = if (ok) {
            // 稿 L791 逐字「已导出 N 行」(不是「N 条记录」)。
            // 第二行「位置 / 文件名」是 App 自己加的: 本函数上方那段注释里已经论证过,
            // 不把文件名给出去用户就找不到文件 —— 这条刻意保留, 不算偏差。
            "已导出 ${recs.size} 行\n" + getString(R.string.export_ok_where, name)
        } else {
            getString(R.string.export_fail, why ?: "未知原因")
        }
        // 稿 L815-822: 底部 toast, 不是对话框; 稿 L820 的「撤销」= 删掉刚写的那个文件
        showToast(msg, if (ok) undo else null)
    }

    // ---------- t254 底部动作面板 + 底部 toast(稿 L801-822) ----------
    // 稿要的是自绘面板, 不是系统对话框。面板与 toast 都挂在 android.R.id.content 上,
    // 所以四个页面都能用, 也不需要给任何布局加 id。
    // 裁定 7.8: 「撤销」是真撤销 —— 清空前存一份快照, 撤销时还原。
    //           稿画了按钮但原型没有回滚, 画一个点了没反应的按钮比不反馈更糟。
    private var undoSnap: List<org.json.JSONObject>? = null

    private fun dismissPanel(host: android.view.ViewGroup, v: View, after: (() -> Unit)? = null) {
        if (v.getTag() == "motion-closing") return
        v.setTag("motion-closing")
        if (!motionEnabled()) {
            if (v.parent === host) host.removeView(v)
            after?.invoke()
            return
        }
        val d = resources.displayMetrics.density
        val sheet = v.findViewById<View>(R.id.panel_sheet)
        v.animate().cancel()
        sheet?.animate()?.cancel()
        v.animate().alpha(0f).setDuration(160).setInterpolator(pgEaseOut).start()
        if (sheet != null) {
            sheet.animate().alpha(0.7f).translationY(24f * d)
                .setDuration(160).setInterpolator(pgEaseOut)
                .withEndAction {
                    if (v.parent === host) host.removeView(v)
                    after?.invoke()
                }.start()
        } else {
            v.animate().withEndAction {
                if (v.parent === host) host.removeView(v)
                after?.invoke()
            }.start()
        }
    }

    private fun showPanel(title: String, body: String, okText: String, onOk: () -> Unit) {
        val host = findViewById<android.view.ViewGroup>(android.R.id.content)
        val v = LayoutInflater.from(this).inflate(R.layout.panel_clear, host, false)
        v.findViewById<android.widget.TextView>(R.id.panel_title).text = title
        v.findViewById<android.widget.TextView>(R.id.panel_body).text = body
        v.findViewById<android.widget.TextView>(R.id.panel_ok).text = okText
        v.findViewById<android.view.View>(R.id.panel_mask).setOnClickListener {
            dismissPanel(host, v)          // 点遮罩关闭(稿 L802)
        }
        v.findViewById<android.widget.TextView>(R.id.panel_cancel).setOnClickListener {
            dismissPanel(host, v)          // 稿 L807 取消
        }
        v.findViewById<android.widget.TextView>(R.id.panel_ok).setOnClickListener {
            dismissPanel(host, v, onOk)
        }
        val sheet = v.findViewById<View>(R.id.panel_sheet)
        val d = resources.displayMetrics.density
        val motion = motionEnabled()
        if (motion) {
            v.alpha = 0f
            sheet?.alpha = 1f
            sheet?.translationY = 24f * d
        }
        host.addView(v)
        if (motion) {
            v.animate().alpha(1f).setDuration(180).setInterpolator(pgEaseOut).start()
            sheet?.animate()?.translationY(0f)
                ?.setDuration(280)?.setInterpolator(pgBezier)?.start()
        }
    }

    // 稿 L815-822: 底部 toast, 文案 + 右侧紫字「撤销」。undo 非空时点它真撤销。
    private fun showToast(msg: String, undo: (() -> Unit)? = null) {
        val host = findViewById<android.view.ViewGroup>(android.R.id.content)
        val d = resources.displayMetrics.density
        fun sp(id: Int) = resources.getDimension(id) / resources.displayMetrics.scaledDensity
        val padH = (15 * d).toInt()     // 稿 L818 padding:'15', 四边一致
        // 稿 L817: 左右 16, 底 80(在 Tab 之上), panel 底 + 描边 + 圆角 8 + 内边距 15
        val bar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.toast_bg)
            setPadding(padH, padH, padH, padH)
            // t256: layoutParams 不在这里设 —— 叠层建好后由 FrameLayout.LayoutParams 给,
            // 见下面 addView(ov, bar)。原来在这里 setMargins 完全不起作用,
            // 因为 android.R.id.content 是 LinearLayout, 会把新子 View 流式排在第一位(顶部)。
            // 稿 L819: 文案 T.title + ink2, 弹性 1
            addView(android.widget.TextView(this@MainActivity).apply {
                text = msg
                setTextColor(attrColor("colorSub"))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp(R.dimen.fs_title))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = android.widget.LinearLayout.LayoutParams(0,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (undo != null) {
                // 稿 L820: 「撤销」T.title + 紫 + 600, 不伸缩 —— 独立可点的那个 span
                addView(android.widget.TextView(this@MainActivity).apply {
                    text = "撤销"
                    setTextColor(attrColor("colorPrimary"))
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp(R.dimen.fs_title))
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    gravity = android.view.Gravity.CENTER
                    setPadding((12 * d).toInt(), (6 * d).toInt(), 0, (6 * d).toInt())
                    isClickable = true
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
                    setOnClickListener {
                        // t256/t257: 这里原来写成 `(parent?.parent as ViewGroup)` —— 不安全转换。
                        // parent?.parent 为 null 时它抛异常, undo() 根本执行不到,
                        // 而异常被点击分发吞掉, 于是表现为「点了没反应、又没崩溃」。
                        // 真机 logcat 定位到的(临时日志已撤)。
                        (parent?.parent as? android.view.ViewGroup)?.removeView(
                            (parent?.parent as? android.view.ViewGroup)?.getChildAt(0))
                        undo()
                    }
                })
            }
        }
        // t256: 叠一层全屏 FrameLayout, 把 bar 靠下放。
        // 稿 L817: 左右 16, 底 80(在 Tab 之上)。
        val ov = android.widget.FrameLayout(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val lp = android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = android.view.Gravity.BOTTOM
            // 稿 L817 left:16 right:16 bottom:80; 左右原来是 20dp
            setMargins((16 * d).toInt(), 0, (16 * d).toInt(), (80 * d).toInt())
        }
        ov.addView(bar, lp)
        val enterY = 8f * d
        val motion = motionEnabled()
        if (motion) {
            ov.alpha = 0f
            ov.translationY = enterY
        }
        host.addView(ov)
        if (motion) {
            ov.animate().alpha(1f).translationY(0f)
                .setDuration(220).setInterpolator(pgEaseOut).start()
        }
        uiHandler.postDelayed({
            if (ov.parent != null) {
                if (!motionEnabled()) {
                    host.removeView(ov)
                } else {
                    ov.animate().alpha(0f).translationY(enterY)
                        .setDuration(140).setInterpolator(pgEaseOut)
                        .withEndAction { if (ov.parent != null) host.removeView(ov) }
                        .start()
                }
            }
        }, 3200)
    }

    // 稿recClear(652):二次确认后清空 —— 改成稿 L804-810 的底部面板

    // ---------- 稿LINK/PH 软联动 + 目标爆仓价上限(393-414) ----------
    // 稿#err:报错行显示/隐藏(null或空=收起)
    private fun showErr(m: String?) {
        if (m.isNullOrEmpty()) {
            calcErr.visibility = View.GONE
            calcErr.text = ""
        } else {
            calcErr.visibility = View.VISIBLE
            calcErr.text = m
        }
    }

    // 爆仓线是当前行情页的第二支撑；可选 lkCap 只负责目标上限/越线判断。
    private fun paintLiqGap() {
        if (!::statLiqGap.isInitialized) return
        val line = liquidationLinePresentation(currentCalcLiqForLine, currentMarketSupport2ForLine)
        if (line == null) {
            statLiqGap.visibility = View.GONE
            return
        }
        statLiqGap.text = "爆仓线 ${t7formatField(line.support2, 2)}"
        statLiqGap.setTextColor(if (line.exceeded) attrColor("colorRed") else attrColor("colorFaint"))
        statLiqGap.setTypeface(null, if (line.exceeded) android.graphics.Typeface.BOLD
                                     else android.graphics.Typeface.NORMAL)
        statLiqGap.visibility = View.VISIBLE
    }

    // 稿paintCap(394-396):爆仓卡第二行灰字「目标上限 X」(t120 改名),无上限收起
    private fun paintCap() {
        val c = lkCap
        if (c == null) {
            statCap.visibility = View.GONE
            statCapv.text = ""
        } else {
            statCap.visibility = View.VISIBLE
            statCapv.text = "目标上限 " + fmt(c)   // t120 改名不移位:命名它约束的参数,避免用户拿它跟"最低价"比
        }
    }

    // 稿.stat.bad:爆仓卡红框(稿1px CSS边框≈density取整像素,圆角10dp=r-input)。r9:方向-B 0 命中 stat.bad(旧稿遗留,无稿依据),按三档口径(设计语言.md:22)取 r-input 10dp,与常态 calc_tile_bg / 稿 .calc-tile(四屏-计算页.html:141-143)一致。
    private fun markLiqBad() {
        statLiqCard.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.TRANSPARENT)
            setStroke((resources.displayMetrics.density + 0.5f).toInt().coerceAtLeast(1),
                attrColor("colorRed"))
            cornerRadius = resources.displayMetrics.density * 10f
        }
    }

    // ---------- 稿LINK/PH 软联动 + 目标爆仓价上限 ----------
    // ⚠ t268 更正一条我自己留下的假引用: 这一段原先注释写「(393-414)」,
    //   而稿 L390-414 实际是【自选页行的渲染】(距支撑/收益率/收益比三列 + flag 第二行,
    //   t260 改的就是 L397-399 那三行)。下面 945/946 写的 397-404 才是稿真正的联动段。
    //   归属: 稿 L144 P0 里 cap 是与 lo/hi/trig 并列的一个内部值, 初值 0;
    //   稿 L624 在「展开更多」读它显示「目标上限 X.XX」; 稿 L674 是超标态那条 StateLine。
    //   ⟹ 稿没有「从步长阈值算 cap」这回事 —— 那是我更早的臆想, 已作废。
    //   ⟹ 稿的可编辑参数表 PDEF(L144-148)只有 cash/lo/hi/trig/n/q/fee/mm,
    //      **不含 cap** —— 稿没给 cap 任何 UI 入口。
    //   ⟹ 所以 cap 恒为 null ≡ 稿 L144 的 cap:0 默认态, over 态(稿 L674)随之不出现,
    //      这和稿一致, 不是缺陷。**不要**为了"救活 over 态"去动 cap 的来源。
    //   ⟹ t280 更正: 本段原先写「cap 的真实来源是第二条支撑线, 要恢复 cap 就必须
    //      恢复第二支撑, 而那一条需要用户裁定」—— **这个因果是反的**, 现予作废。
    //      稿 L70/L496/L911 三处都只标第一支撑, t223 已按稿删净第二支撑
    //      (MktView.kt:225 names=["第一支撑"] / :234 cnt=minOf(1, supShow.size)),
    //      当时就该一并把这三行改掉, 拖到今天。
    // 稿linkCalc(397-404):同一对支撑原样返回不覆盖手改,值变才覆盖;
    // ⚠ 2026-10-07 **偏离稿**：稿的「支撑位≥2才联动」已改为「支撑≥1 即联动最低价」，
    //   理由与拆判依据见 [linkCalc] 函数体内注释（单支撑品种算不出网格数量）。
    // sup1→计算页最低价(JS toPrecision(8)=jsPrec);sup2→记为目标爆仓价上限并paintCap
    /**
     * t304 静默改写用户输入时的那行说明。
     * 稿 L913 同一精神: 发生了就要说, 不许无声无息。
     * @param src 依据说明(来自哪里), 为空则收起 —— 用户自己动手改过就不用了。
     */
    private fun t304Note(src: String?) {
        if (src.isNullOrEmpty()) {
            calcNote.visibility = View.GONE
            calcNote.text = ""
            return
        }
        calcNote.visibility = View.VISIBLE
        calcNote.text = src
    }

    /**
     * 这笔联动回调**是不是清空前那一批**（是 ⟹ 一律丢弃）。
     *
     * @param batchSeq 回调**自己那一批**的请求代号（MktPanel.shownSeq / paintTgt 的 seq）。
     *   ⚠ 不是回调发生时的当前 reqSeq：旧回调会被当成本轮新批次，正是要防的错。
     *   ⚠ 若这个代号在清空之后才产生（`> 屏障`），说明它是用户清空后重新进入同品种
     *     的新一轮取数 —— 必须放行，否则 Pl/Ph 与自动网格永久失效。
     */
    private fun linkBlockedAfterClear(batchSeq: Int): Boolean {
        if (linkBlockedSeq < 0 || linkBlockedSym.isEmpty()) return false
        // 跨品种时屏障不生效：旧品种回调由 MktPanel 的 reqSeq/stillSame 双守卫拦。
        if (normSym(curCalcSym()) != linkBlockedSym) return false
        return batchSeq <= linkBlockedSeq
    }

    fun linkCalc(sup: List<Double>, batchSeq: Int) {
        if (linkBlockedAfterClear(batchSeq)) return
        // ⚠⚠ 2026-10-07 用户实报「网格数量还是没有自动计算，都有步长阈值了」——**根因就在这道门槛**。
        // 【现象】行情页 00700 只有**一个**支撑（第一支撑 52.85，无第二支撑），原门槛
        //   `if (sup.size < 2) return` 于是直接返回：最低价不写入 → tryAutoGridN 拿不到 pl
        //   → autoGridN 返回 null → 网格数量恒空。而步长阈值(3.38%)和最高价(84.3, linkPhigh 链入)
        //   **都在**，唯独最低价缺位 —— 与用户描述逐字吻合。
        // 【稿怎么说】上方注释记的是「稿linkCalc(397-404):支撑位≥2才联动」——稿要的是
        //   「两个支撑一起联动」(最低价 + 上限 cap)。
        // 【为什么那是错的】**上限 cap 是可选的，最低价是网格计算的必需输入**。
        //   用一道门槛同时卡两件事，等于让所有单支撑品种永远算不出网格数量。
        // 【用户裁定覆盖稿】用户要的是「有步长阈值就自动算」⟹ 拆开判：
        //   支撑≥1 就写最低价；第二支撑有则写 cap，无则 lkCap 保持 null
        //   （null 本来就是已支持的常态，见 L350 记录回填 / L2939 解绑）。
        val support2ForLine = secondSupportForLiqLine(sup)
        if (currentMarketSupport2ForLine != support2ForLine) {
            currentMarketSupport2ForLine = support2ForLine
            paintLiqGap()
        }
        if (sup.isEmpty()) return
        val s1 = sup[0]
        val s2 = support2ForLine   // ⚠ 可空：单支撑品种没有上限档，不构型由它决定
        val prev = lkPrev
        if (prev != null && prev.first == s1 && prev.second == s2) return
        lkPrev = Pair(s1, s2)
        // D4/t89:回填态只更新 lkPrev(稿L552 语义),不写 inp["Pl"]/lkCap——保存值必须赢。
        // 判据是**活标志** restoreGuardSym,不是瞬时的 suppressReset:onInfo 是下一帧才触发的。
        if (restoreGuardSym != null) return
        val qPx = MktData.quantPx(s1)   // D9(a):最低价走 1% 精度(quantPx),不是 jsPrec
        // ⚠ 2026-10-02：这一行原先**没有**包 programWrite{}，是「首屏永远停在 idle」的真凶。
        //   链路:行情取到 → onInfo → linkCalc → inp["Pl"].setText(733) → calc 输入框的
        //   TextWatcher.afterTextChanged（L2160-2161，非 suppressReset 时调 idle()）→ 把
        //   启动时 onCalc() 算出的净收益/四格/折叠口整块清掉，只剩「输入参数后回车计算」。
        //   现象与「回车制没生效」一模一样，但真因在联动写值这条路。
        //   改法与 linkPhigh（L1379）逐字同款:写值走 programWrite 闸，写完**按新最低价重算一次**，
        //   这样联动后的结果与屏上参数一致（稿 L397-404 联动即重算）。
        programWrite { inp["Pl"]!!.setText(qPx) }
     tryAutoGridN()   // ⚠ 最低价写完，若阈值已到则补算
        // t304: 说清楚这个字段被程序改了、依据是什么
        // (不然用户只看到自己填的 272.85 变成了 273, 不知道发生过什么)。
        t304Note("最低价已改为 $qPx（来自行情页第一支撑，已按 1% 精度对齐；可直接改回）")
        lkCap = s2
        paintCap()
        // 重算:让净收益/四格跟着新的最低价一起更新,而不是留一份用旧参数算出的数
        // (稿的联动段是「写完即出结果」)。同值已由 L1351 的 lkPrev 门挡掉,这里不会重复重算。
        // onCalc 自己会清闸并重取全部 inp,不会自激。
        try { onCalc() } catch (t: Throwable) { /* 联动重算失败不致命:保持上一次结果 */ }
    }

    // 稿linkPhigh(406-414):Nasdaq机构目标均价>现价且>当前最低价才写入计算页最高价;
    // 最低价非数字(NaN)放行;同值(LKph门)不打扰手改
    fun linkPhigh(avg: Double, cur: Double, batchSeq: Int) {
        if (linkBlockedAfterClear(batchSeq)) return
        if (!(avg > 0) || !(cur > 0)) return
        if (avg <= cur) return
        // ⚠ 2026-10-02 t7：输入框现在可能带千分位（稿 L2201），解析前先剥掉，与 getNum 同款。
        val pv = inp["Pl"]!!.text.toString().trim().replace(",", "").replace(" ", "").toDoubleOrNull()
        if (pv != null && !(avg > pv)) return
        if (lkPh == avg) return
        lkPh = avg
        // D4/t89:回填态不覆盖刚恢复出来的最高价。linkPhigh 来自 paintTgt 的**另一个异步回调**,
        // 时机比 onInfo 更不可控,所以同样只认 restoreGuardSym 这个活标志。
        if (restoreGuardSym != null) return
        val aPx = MktData.quantPx(avg)
        programWrite {
            inp["Ph"]!!.setText(aPx)
            // t304: 同上, 说明这个字段被程序改了。
            // ⚠⚠ 整条「回填」能力是 **App 独有**, 稿里没有任何 link 函数（2026-09-30 t376）：
            //     把行情页的第一支撑/机构目标均价回填到计算页的最低价/最高价。
            //     所以它**不在 A/B 两节的任何一条里** —— 别去清单里找它的编号, 也别因为
            //     「稿没有」就当成不一致去删: 删了用户就少一个能力。
            //     它已经是**诚实**的: 每次程序改写用户字段, 都会在 calc_note 里留一行说明（t304）。
            t304Note("最高价已改为 $aPx（来自机构目标均价，已按 1% 精度对齐；可直接改回）")
        }
        tryAutoGridN()   // ⚠ 最高价写完，若阈值已到则补算（与linkCalc对称；泛化：任一联动写完都补）
        // 与 linkCalc 同理:写完按新最高价重算,净收益/四格才与屏上参数一致(稿联动即出结果)。
        // programWrite 已挡住 idle();这里补上「联动后刷新结果」,否则结果区停在旧最高价的口径。
        try { onCalc() } catch (t: Throwable) { /* 联动重算失败不致命 */ }
    }

    // ---------- v4§1 按品种记住计算参数并回填(数据=记录表,同品种只取最新一条) ----------
    // 网格模式:3.7.2无独立控件,记录值回填到状态(重算时继续写回该品种的模式)
    private var gridMode = "geo"

    // 品种归一化：大写 + 天然气别名收敛（港股/A股已下线，不再按市场补零/加前缀）
    private fun normSym(s: String): String = MktData.canonicalSym(s)

    // 费率/维持保证金率:记录存小数(0.0005),输入框是百分数(0.05)→回填×100,8位小数去尾

    // 自选三列用(稿§2/§3):该品种最新一条记录——读取时取最新,记录本身仍是追加语义
    fun latestRecOf(sym: String): org.json.JSONObject? {
        val key = normSym(sym)
        return recs.lastOrNull { normSym(it.optString("sym")) == key }
    }

    private fun makeRow(
        idx: String, price: String, pill: String?, amt: String, cum: String,
        cumColor: Int, zebra: Boolean, header: Boolean
    ): View {
        val v = LayoutInflater.from(this).inflate(R.layout.row_detail, rows, false)
        // t102 第1步:row_detail.xml 本轮 out of scope(不能改),但它里面是迁移前的 12/15/12/13 四个魔数。
        // 在这里按 id 统一覆盖成 4 档,**渲染结果**因此也是 30/21/16/12,一处改全局生效。
        v.findViewById<TextView>(R.id.row_idx).textSize = t102sp(this, R.dimen.fs_note)
        // 稿 L722: fontSize=T.note(12sp)。改前是 fs_title(16sp), 五列里只有这一列被放大。
        v.findViewById<TextView>(R.id.row_price).textSize = t102sp(this, R.dimen.fs_note)
        v.findViewById<TextView>(R.id.row_dir).textSize = t102sp(this, R.dimen.fs_note)
        // 稿 L721-725: 数据行五列【全部 T.note】, 没有一列是 16sp。
        // 改前是 fs_title —— t213 那次是为了改「恒红」, 字号是顺手一起放大的,
        // 稿里没有这条依据。表头/数据行统一 fs_note。
        v.findViewById<TextView>(R.id.row_cum).textSize = t102sp(this, R.dimen.fs_note)
        val tc = if (header) attrColor("colorFaint") else attrColor("colorInk")
        // 稿 L714: 表头五列全是 T.note + ink3。改前布局这四列【一列都没设色】——
        // row_idx/row_price 沿用 row_detail.xml 的 colorSub/colorInk, row_dir/row_cum 沿用
        // 主题默认; 而 tc 里的 colorFaint 被下面 `if (!header)` 挡掉, 算了不生效。
        // 第五列 amtCell(代码里动态插入) 的表头色本来就是对的, 不在这次范围内。
        // 【只给表头补色】, 数据行一行都不碰 —— 方向列的买绿卖红、累计净收益的恒红
        // 都在别处设, 碰了就会被冲掉(涨绿跌红是硬约束)。
        if (header) {
            for (rid in intArrayOf(R.id.row_idx, R.id.row_price,
                                   R.id.row_dir, R.id.row_cum)) {
                v.findViewById<TextView>(rid).setTextColor(tc)
            }
        }
        v.findViewById<TextView>(R.id.row_idx).apply {
            text = idx
            // 稿 L721: 序号列是 ink3(最浅那级)。改前用 tc, 非表头时是 colorInk(最深)。
            if (!header) setTextColor(attrColor("colorFaint"))
        }
        v.findViewById<TextView>(R.id.row_price).apply {
            text = price
            // 稿 L722: 网格价是 ink2, 不是 ink。改前用 tc(=colorInk) 与「#」同色。
            if (!header) setTextColor(attrColor("colorSub"))
            // t169 G5-a:t100Σ 当年的取舍是「宁可价格被裁也不许换行」——
            // 表头折行只是难看,价格被裁是【显示一个不存在的价位】,那是骗人。
            // 表头保持单行(三个字给够宽就不折);价格列改为吃剩余空间,不再被硬裁。
            maxLines = 1
            ellipsize = null
            layoutParams = (layoutParams as android.widget.LinearLayout.LayoutParams).apply {
                width = 0
                weight = 1f          // 吃掉本格金额那列让出来的宽度
            }
        }
        // t100③:网格明细原是 4 列,第 4 列在不同行装两种东西(买入行=q*p 本格金额,
        // 卖出行=累计净收益),表头却统一写「累计净收益」——同一列两种含义,已裁定拆成两列。
        // res/** 本轮 out of scope,所以这里**在代码里补一个 TextView** 插在 row_cum 之前。
        // 宽度比 row_cum(110dp)略窄,保证挤出来的空间不会让网格价列折行。
        val amtCell = TextView(this).apply {
            text = amt
            textSize = t102sp(this@MainActivity, R.dimen.fs_note)
            gravity = android.view.Gravity.RIGHT
            // t169:本格金额是数值列,等宽会把数字与「—」的对齐弄歪(同 t160/t162 行情页那一类)——删
            maxLines = 1
            // 稿 L724: color = g[3]==='—' ? ink3 : ink2 —— 「—」是弱化, 不是数值。
            setTextColor(if (header || amt == "—") attrColor("colorFaint")
                         else attrColor("colorSub"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                (80 * resources.displayMetrics.density).toInt(),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val host = v as android.widget.LinearLayout
        val at = host.indexOfChild(host.findViewById(R.id.row_cum))
        host.addView(amtCell, at)
        val dir = v.findViewById<TextView>(R.id.row_dir)
        if (pill == null) {
            // 稿 L713 表头五列齐全(['#','网格价','方向','本格金额','累计净收益'])；
            // 改前这里一律置空, 于是表头「方向」那一格是【空的】。
            // 只在表头补, 数据行走下面的 pill 非空分支, 不受影响。
            dir.text = if (header) "方向" else ""
        } else {
            val parts = pill.split("|")
            dir.text = parts[0]
            // 稿 L723: <span style={{color:sell?'var(--dn)':'var(--up)'}}>{g[2]}</span>
            // 【纯文字, 没有底色】。改前两边都挂了 pill 背景, 且卖出用的是 colorPrimary(紫),
            // 于是卖出一格是紫字紫底 —— 既不是稿的红, 也不是任何合理的字底搭配。
            // 取色沿用本项目既有的 colorTeal(涨绿)/colorRed(跌红), 不新增 attr。
            dir.setBackgroundResource(0)
            dir.setTextColor(attrColor(if (parts[1] == "buy") "colorTeal" else "colorRed"))
        }
        v.findViewById<TextView>(R.id.row_cum).apply {
            text = cum
            // 稿 L725: color = g[4]==='—' ? ink3 : var(--dn) —— 是三元, 不是"恒红"。
            // 买入行的累计净收益是「—」(还没卖, 累计无从谈起), 那一格按稿走弱化灰。
            setTextColor(if (header || cum == "—") attrColor("colorFaint") else cumColor)
        }
        // t257 #36 稿 L710-729: 表头 + 行 + 1px 下边线, 【没有斑马纹】。
        // 原来这里按 zebra 形参给偶数行刷了一层 colorSurf2 灰底, 已整行删掉。
        //   (注释里不重贴那行原文 —— 会被下面的断言当成"斑马纹还在"而误报。)
        // zebra 形参与三个调用点原样保留(留着不影响渲染), 只去掉效果。
        return v
    }

    // ---------- 界面 ----------

    // 打孔屏适配(对标稿子viewport-fit=cover+safe-area-inset):edge-to-edge,
    // 状态栏/导航栏透明,浅色主题配深色状态栏图标;真融合=不垫body,改垫各页根
    // ScrollView(sbTop)+clipToPadding=false(四页布局已设):静止时头部在栏下,
    // 滚动时行从状态栏图标后面穿过。平台差异:稿(浏览器)只能垫高,App单侧原生行为。
    // 纯框架API实现(minSdk26,无外部依赖)。
    private fun edgeToEdge(light: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    (if (light) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0)
        }
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.setSystemBarsAppearance(
                if (light) android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
        }
        val root = findViewById<View>(android.R.id.content)
        root.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            val top = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            val bottom = insets.systemWindowInsetBottom
            sbTop = top
        body.setPadding(0, 0, 0, 0)
        for (i in 0 until body.childCount) body.getChildAt(i).setPadding(0, sbTop, 0, 0)
            val bar = findViewById<View>(R.id.tabbar)
            bar.setPadding(bar.paddingLeft, bar.paddingTop, bar.paddingRight, bottom)
            insets
        }
        root.requestApplyInsets()
    }

    private fun watchKeyboard() {
        val content = findViewById<View>(android.R.id.content)
        val bar = findViewById<View>(R.id.tabbar)
        content.viewTreeObserver.addOnGlobalLayoutListener {
            val r = android.graphics.Rect()
            content.getWindowVisibleDisplayFrame(r)
            val h = content.rootView.height
            bar.visibility = if (h - r.bottom > h * 0.15) View.GONE else View.VISIBLE
        }
    }

    private fun showTab(name: String) = showTab(name, ANIM_TAB, null)

    // 页面转场(对标稿子pgIn/pgR/pgL):现行单Activity换View结构,用ViewPropertyAnimator;
    // Tab切换淡入+上浮8px/220ms/ease-out;自选进→右进48px,行情返回→左进48px,
    // 260ms/cubic-bezier(.32,.72,.35,1);插值器见res/interpolator。
    fun showTab(name: String, anim: Int, activeTab: String? = null) {
        tab = activeTab ?: name
        // ⚠ 2026-10-03 网格数量自动算需要该品种的日线步长阈值。
        //   进计算页时取一次（行情屏最后看过的品种），回来时也刷新 —— 行情屏可能换了品种。
        if (name == "calc" && ::mktPanel.isInitialized) loadStepThr()
        // 稿§4 离开行情页(返回键/‹返回/切Tab全走这里):丢弃未确认根数/周期,
        // 还原成该品种已确认默认(纯状态,不发请求)
        if (name != "mkt" && ::mktPanel.isInitialized && body.indexOfChild(mktPage) >= 0) {
            mktPanel.onLeave()
        }
        body.removeAllViews()
        val v = when (name) {
            "mkt" -> mktPage
            "setup" -> settingsPage
            "fav" -> favPage
            else -> calcPage
        }
        body.addView(v)
        if (name == "mkt" || name == "calc" || name == "setup") paintStaticCardShadows()
        v.setPadding(0, sbTop, 0, 0) // 真融合:换入页垫静止位,clipToPadding=false放行滚动穿越
        playPageAnim(v, anim)
        paintTabs()
        // 距离重试环随自选页显隐(稿showTab('fav')→refreshFavDist,离开→清FAVRetry)
        if (::favPanel.isInitialized) {
            if (name == "fav") favPanel.onShow() else favPanel.onHide()
        }
    }

    private fun playPageAnim(v: View, anim: Int) {
        v.animate().cancel()
        val d = resources.displayMetrics.density
        v.translationX = 0f
        v.translationY = 0f
        when (anim) {
            ANIM_FROM_R -> {
                v.alpha = 0f
                v.translationX = 48f * d
                v.animate().alpha(1f).translationX(0f)
                    .setDuration(260).setInterpolator(pgBezier).start()
            }
            ANIM_FROM_L -> {
                v.alpha = 0f
                v.translationX = -48f * d
                v.animate().alpha(1f).translationX(0f)
                    .setDuration(260).setInterpolator(pgBezier).start()
            }
            else -> {
                v.alpha = 0f
                v.translationY = 8f * d
                v.animate().alpha(1f).translationY(0f)
                    .setDuration(220).setInterpolator(pgEaseOut).start()
            }
        }
    }

    private fun motionEnabled(): Boolean =
        try { android.animation.ValueAnimator.getDurationScale() > 0f }
        catch (_: Throwable) { true }

    /** 只改变可见性过渡，不改变折叠区的最终尺寸、颜色或布局参数。 */
    private fun animateVisibility(v: View, show: Boolean) {
        v.animate().cancel()
        if (!motionEnabled()) {
            v.visibility = if (show) View.VISIBLE else View.GONE
            resetMotion(v)
            return
        }
        val d = resources.displayMetrics.density
        val offset = 6f * d
        if (show) {
            v.visibility = View.VISIBLE
            v.alpha = 0f
            v.translationY = -offset
            v.animate().alpha(1f).translationY(0f)
                .setDuration(220).setInterpolator(pgEaseOut)
                .withEndAction {
                    v.alpha = 1f
                    v.translationY = 0f
                }.start()
        } else {
            if (v.visibility != View.VISIBLE) return
            v.animate().alpha(0f).translationY(-offset)
                .setDuration(160).setInterpolator(pgEaseOut)
                .withEndAction {
                    v.visibility = View.GONE
                    v.alpha = 1f
                    v.translationY = 0f
                }.start()
        }
    }

    private fun resetMotion(v: View) {
        v.animate().cancel()
        v.alpha = 1f
        v.translationX = 0f
        v.translationY = 0f
    }

    /** 计算结果更新时的轻微数值强调，数值本身仍立即写入。 */
    private fun animateResultValue(v: View) {
        v.animate().cancel()
        if (!motionEnabled()) {
            resetMotion(v)
            return
        }
        val d = resources.displayMetrics.density
        v.alpha = 0.55f
        v.translationY = 5f * d
        v.animate().alpha(1f).translationY(0f)
            .setDuration(240).setInterpolator(pgBezier).start()
    }

    /** 明细已重建后逐行进入；仅使用 alpha/translation，不改行高。 */
    private fun animateGridRows() {
        if (!::rows.isInitialized) return
        if (!motionEnabled()) {
            for (i in 1 until rows.childCount) resetMotion(rows.getChildAt(i))
            return
        }
        val d = resources.displayMetrics.density
        for (i in 1 until rows.childCount) {
            val row = rows.getChildAt(i)
            row.animate().cancel()
            row.alpha = 0f
            row.translationY = 5f * d
            row.animate().alpha(1f).translationY(0f)
                .setStartDelay(minOf(i * 12L, 180L))
                .setDuration(220).setInterpolator(pgEaseOut).start()
        }
    }

    /**
     * t130 按稿 L245-247 写；2026-10-16 t59 换成**方向 B §2.4-3.1** 的形态（**执行用户改判**）。
     *
     * ⚠⚠ **裁定沿革（本仓惯例：裁定写进注释 ⟹ 改判必须写清，否则后人照旧注释改回去）**
     *   · 2026-10-03 用户裁定①：选中态**字色统一成黑**
     *     （原话「为什么字是紫色的，其他的字都是黑色的」→「统一成黑」）
     *     ⟹ 当时四个 tab 选中字色一律 `colorInk`。
     *   · 2026-10-03 用户裁定②：顶条由「tab 宽 × 44% 居中」改为**整条铺满的上划线**
     *     （原话「为什么行情上面的紫色条不一样，**我需要覆盖在上划线的形式**」）。
     *   · **2026-10-16 用户明确改判为方向 B 的「主色浅底药丸 + 主色字」** ⟹
     *     裁定②的「整条上划线」**已失效**：药丸取代上划线，四个 `tab_*_bar` 改当药丸
     *     （底 = `@drawable/tab_pill`，几何在 activity_main.xml）；
     *     裁定①的「统一成黑」也被取代：选中字色 = `colorAccentText`（方向 B 的 accent）。
     *   ⟹ **上面两条 2026-10-03 的注释描述的是历史、不是现在的行为**，别照它们改回去。
     *
     * 现在的取值（方向 B §2.4-3.1 逐条）：
     *   选中 = 底 `tab_pill`（`colorAccentSoft` + r999 药丸）+ 字 `colorAccentText` + 字重 600(BOLD)
     *   未选 = 无底 + 字 `colorFaint`（= `--ink-faint`，方向 B 的第三档墨色）+ 400
     *   药丸的**几何**（宽 = tab 宽 − 16dp、高 32dp、与标签同心）全在 `activity_main.xml`；
     *   本函数只决定**哪一项选中**，底 / 字色 / 字重三件一处给（「同一件事只留一个主人」）。
     *   ⚠ 字重 600：本仓 minSdk 26，`Typeface.create(…, 600, …)` 是 API 28+
     *     ⟹ 按 FavPanel.kt:447-450 的既有处置统一退化为 `Typeface.BOLD`(700)。
     *
     * ⚠ t41 那段「选中自选 = 紫 `--ac`、其余 = `--ink`」是**稿的行为**（v2.html:846-847 的
     *   `k==='list'` 三元分支），2026-10-03 起就不是 App 行为；2026-10-16 改判后四个 tab
     *   统一走 accent，与稿的「自选特殊」仍不同 —— **用户裁定优先于稿**。
     *
     * ⚠ 底栏标签字号**保持 `fs_title`(16sp)**：这是 t416 依 v1 稿 L246 `fontSize:T.title`
     *   特意恢复的（并删掉了 t102 那个压成 12sp 的运行期覆盖）。方向 B 原型的底栏标签是
     *   `--fs-note`(12px) —— 那是**另一层改动**（会推翻 t416），不在本任务范围，已登记。
     */
    private fun paintTabs() {
        val accent = attrColor("colorAccentText")
        val faint = attrColor("colorFaint")
        /** @param sel 选中态字色。2026-10-16 起四个 tab 一律 accent（见上方裁定沿革）。 */
        fun set(l: TextView, b: View, key: String, sel: Int) {
            val on = tab == key
            l.setTextColor(if (on) sel else faint)
            l.setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            // 底：选中 = 药丸 drawable（主题化，吃 colorAccentSoft）；未选 = 清掉底（0 = 无背景）
            b.setBackgroundResource(if (on) R.drawable.tab_pill else 0)
        }
        set(tabFavLabel, tabFavBar, "fav", accent)
        set(tabMktLabel, tabMktBar, "mkt", accent)
        set(tabCalcLabel, tabCalcBar, "calc", accent)
        set(tabSetupLabel, tabSetupBar, "setup", accent)
    }

    /**
     * ⚠ 2026-10-16 t59：**`syncTabBars()` 已删除**（连同它的 OnLayoutChange 监听与 paintTabs() 调用点）。
     *
     * 它原来干的事：按稿 L247 的 `left:'28%', right:'28%'` 算「tab 宽 × 44%」再居中设给指示条；
     * 2026-10-03 用户把语义改成「整条铺满」后，公式退化为 `w = tabFav.width`（全宽）。
     *
     * **为什么现在不需要了**（验收点名要说明的「处置 + 理由」）：药丸的宽度 = 宿主 FrameLayout
     *   的**内容盒**（padding = `space_2`）⟹ 用 `layout_width="match_parent"` 在布局里**声明式**
     *   表达即可，百分比/实测宽度那套运行期算法整条退役。**删除**而不是保留的理由：留着就是
     *   「同一件事两个主人」（布局说 match_parent、Kotlin 又按实测宽覆盖一遍），本仓已多次踩过。
     *
     * ⚠ 历史（别再回头试）：
     *   · 2026-10-03 之前：`w = tabFav.width * 0.44f`（稿的 28%/28% 内缩，屏上一小截短紫条）；
     *   · 2026-10-03 用户裁定：**整条铺满 tab 宽**（原话见 paintTabs() 的裁定沿革）；
     *   · **2026-10-16 用户改判药丸** ⟹ 全宽上划线退役，宽度交回布局。
     *   · 上移量那条一并成为历史：`translationY="-13.6dp"` 曾让紫条整个消失（截图不可信、无法定位），
     *     负 margin 在 Android 上**静默不生效**（fav_head 那次踩过，uiautomator 还照报正常 bounds）。
     *     药丸靠 `layout_gravity="center"` 与标签同心，**不需要任何偏移量**。
     *   · 连带约束也随上划线一起失效：旧注释说「FrameLayout 的 paddingTop 与标签的 paddingTop
     *     不能同时留」—— 那是为了让指示条落在灰线上。现在两者都归宿主 padding（标签自身 paddingTop
     *     已删），不存在叠加问题。
     */
    /**
     * 主题三段的两态 —— 2026-10-16 t50 换成**方向 B §2.4-3.6** 的取值：
     *   选中 = **抬起的药丸**：底 seg_sel（`colorSurfaceRaised`，浅 #FFFFFF / 深 #272E38）
     *          + elev_1（浅 2dp / 深 0）+ 字 `colorInk` + 600 —— **不再占主色实底**
     *   未选 = 透明底 seg_unsel + 字 `colorSub` + 400
     *   按压 = 字色转 `colorInk`（语言层 theme.css §6.4 末行 `.gc-seg__btn:active { color: var(--ink) }`；
     *         底与圆角都不变 ⟹ 用 ColorStateList 表达，不换 drawable）
     *
     * ⚠ **U-9 本轮关闭**（交接 §5.1-⑥「同一件事两个主人」）：
     *   `page_settings.xml` 里三个 Button 的 `android:textColor` / `android:textStyle` **已删**，
     *   改前是「XML 写死一套 + 这里覆写一套」。此后
     *   **背景 + 字色 + 字重 + 抬升四件只有本函数一处主人**（唯一真相）。
     *
     * 抬升为什么在这里设：drawable 画不了阴影（t49 的 seg_sel.xml 文件头逐字说明），
     *   由宿主 View 消费 `elev_1` —— 而「哪一段是选中」只有本函数知道 ⟹ 只能在这里设。
     *
     * ⚠ themeFollow/Light/Dark 声明成 View, 强转 Button 只在这一处发生,
     *   把 XML 里的 <Button> 换成别的类型会 ClassCastException。
     */
    private fun paintThemeSeg() {
        val pairs = listOf(themeFollow to "follow", themeLight to "light", themeDark to "dark")
        val ink = attrColor("colorInk")
        val inkSub = attrColor("colorSub")
        val elev1 = resources.getDimension(R.dimen.elev_1)
        for ((v, m) in pairs) {
            val btn = v as android.widget.Button
            val on = mode == m
            btn.setBackgroundResource(if (on) R.drawable.seg_sel else R.drawable.seg_unsel)
            // 常态 = 选中 ink / 未选 inkSub；**按压两者都转 ink**（方向 B 的按压语汇，见上）
            val rest = if (on) ink else inkSub
            btn.setTextColor(android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
                intArrayOf(ink, rest)))
            btn.setTypeface(if (on) resources.getFont(R.font.inter_semibold) else null,
                            android.graphics.Typeface.NORMAL)  // t95/C-39：选中段字重按稿取 600（theme.css:522 fw-semibold + :189）；minSdk 26 ⟹ create(family,600,false) 属 API 28+ ⟹ 取 600 面字体资源 res/font/inter_semibold.ttf
            btn.elevation = if (on) elev1 else 0f
        }
    }

    private fun setMode(m: String) {
        mode = m
        try { // D10:写失败只降级为「本次会话内生效」,不崩
            prefs().edit().putString("theme", m).apply()
        } catch (_: Exception) {
        }
        recreate()
    }

    // t105/t102 的 `dirty_t`（保存按钮下面那个灰/绿的「● 未保存」）**已删**。
    // 理由：那是**第二个**未保存标记，与 heroSub 同一件事各画各的 —— 一个紫一个灰。
    // 稿 L670-671 只有一处 `heroSub`：`over?'其余结果全部归零' : saved?'已保存':'● 未保存'`。
    // **同一件事只留一个主人。**（与 t118「两套真相」同一个病。）
    // 保存动作的真状态仍然要写：改为驱动 heroSub，而不是另开一个 view。
    /**
     * 保存按钮的两态样式：`on` = 「已保存」态（白底细边 + faint 字），`false` = 常态（主色实底 + 白字）。
     * ⚠ 2026-11-04：从 onCreate 里的局部函数提上来，因为 [idle] 也要用它把按钮复位。
     */
    private fun saveBtnSaved(on: Boolean) {
        if (!::saveBtn.isInitialized) return
        // ⚠⚠ 2026-11-04 用户报「按钮四个角有黑色」：真机上按钮的 elevation 阴影是按 **view 轮廓** 画的，
        //   而轮廓来自 background 的 outline；换过一次背景（已保存态 → GradientDrawable）后，
        //   某些机型上会留下一个**方角**轮廓 ⟹ 圆角之外露出四块深色三角。
        //   ⟹ 这里双重保险：① 显式给按钮一个**圆角**轮廓（10dp，与底图同档）；
        //     ② 关掉按钮自带的 elevation 动效（Theme.Material 的 Button 默认有 stateListAnimator）。
        saveBtn.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                val r = 10f * view.resources.displayMetrics.density
                outline.setRoundRect(0, 0, view.width, view.height, r)
            }
        }
        saveBtn.stateListAnimator = null
        saveBtn.elevation = 0f
        if (on) {
            val dm = saveBtn.resources.displayMetrics
            saveBtn.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 10 * dm.density
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke(Math.max(1, (1 * dm.density).toInt()), attrColor("colorBorder"))
            }
            saveBtn.setTextColor(attrColor("colorFaint"))
        } else {
            // ⚠ 2026-11-04：idle() 可能在 onCreate 还没捕获到常态底之前就被调用（TextWatcher）。
            //   那一刻 saveBtnBg0 仍是 null —— 直接写下去会把按钮刷成**透明底 + 透明字**（实测整块消失）。
            //   ⟹ 没捕获到就不要动它。
            val bg = saveBtnBg0 ?: return
            saveBtn.background = bg
            saveBtn.setTextColor(saveBtnInk0)
        }
    }

    private fun t105SetDirty(dirty: Boolean) {
        if (!::heroSub.isInitialized) return
        if (dirty) {
            heroSub.setTextColor(attrColor("colorPrimary"))
            heroSub.text = "● 未保存"          // 稿 L671 原文
        } else {
            // #107 稿 L670: `color: over ? ink3 : ac` —— 同一行只随 over 变灰, **不随 saved 变色**:
            // 「已保存」与「● 未保存」在稿里是同一个强调色。改前用的是「涨绿跌红」那支绿, 稿里没有这条语义。
            heroSub.setTextColor(attrColor("colorPrimary"))
            heroSub.text = "已保存"            // 稿 L671 原文，不带品种名
        }
    }

    /**
     * ⚠ 2026-10-29 t34 · **本函数已空转，保留作锚点**（用户裁定 · 方案 C：方向只作展示、不是按钮）。
     *
     * 原来它做两件事，现在都不需要了：
     *   ① 用 `seg_sel / seg_unsel` + `colorInk/colorSub` + BOLD/NORMAL + `elev_1` 把「做多」画成**药丸段**
     *      （r8 的 C-9 口径）；② 供 `sideLong.setOnClickListener` 重算后重画方向。
     * 「做多」现在是 **XML 静态状态徽标**（page_calc.xml：@drawable/side_badge_bg = colorTealBg + r999，
     *   字色 ?attr/colorTeal、字阶 fs_micro、600→BOLD），样式真值全在资源里 ⟹
     *   **运行期一律不许再覆写**：再调 setBackgroundResource(seg_sel) / setTextColor(ink) / elevation
     *   会把徽标打回药丸，正是用户这次要消掉的「丑」。
     *
     * 为什么保留而不删（本仓「0 调用点 ≠ 死代码」，同 `draftOnFill` / `recLoad` 先例）：
     *   做空段与 `sideShortMode` 整套公式仍在（t105..t341 大量引用，只是屏上恒为做多）。
     *   若日后恢复做空，这里就是「按方向重画」的唯一落点：补回 `seg_sel / seg_unsel` 两态 + 两个 item。
     *   ⚠ 恢复时必须同时补齐四处：page_calc.xml 的 side_short 节点 + `sideShort` 字段与 findViewById
     *     + 本函数的方向着色 + sideLong 的点击监听 —— 缺一即崩（详见 onCreate 里那段墓碑）。
     */
    private fun paintSide() {
        // 空实现：徽标样式真值在 res（drawable/side_badge_bg.xml + page_calc.xml 的 side_long）。
        // 这里刻意什么都不做 —— 任何 setBackground/setTextColor/setElevation 都会把徽标打回药丸。
    }

    private fun paintDbl() {
        if (!::dblBtn.isInitialized) return
        dblBtn.isChecked = dbl   // t104:Button 配色 -> Switch 勾选
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    // 2026-10-29 t36 · 开关轨道**颜色过渡**（用户反馈：「蓝色是突然消失的不是渐变」）
    //
    // 问题：`drawable/switch_track.xml` 是 `<selector>`，颜色由状态直接给出 ⟹ 平台只在状态变化时
    //   重读一次，**瞬时跳变**；而滑块的**位置**有平台动画 ⟹ 观感是「钮在滑、轨道早已换完色」。
    // 做法：切换的那一瞬间由 Kotlin 用 ValueAnimator + ArgbEvaluator **临时接管**轨道 drawable，
    //   逐帧 `dblBtn.track = GradientDrawable(插值底, 1dp 插值描边)`；动画一结束立刻
    //   `setTrackResource(R.drawable.switch_track)` 把轨道交回 selector ——
    //   **终态色与「开+按压」态仍由 selector 独家负责**（本文件不复制那三档色值）。
    //
    // ⚠ 动画开关必须尊重系统设置（本仓既有约定，见 motionEnabled / playPageAnim / animateSugHeight）：
    //   动画缩放 = 0（无障碍/开发者选项关动画）时**直接一帧到位**，不播、不留中间色。
    // ⚠ 快速连点（中途反向）：先 cancel 上一个 animator，再**从当前帧颜色起播**
    //   （dblTrackBg/dblTrackStroke 每帧记账），否则颜色会跳回起点。
    // ⚠ 时长/插值器沿用本仓「开关与切页」那一族：180ms + pgEaseOut（不新建插值器）。
    // ══════════════════════════════════════════════════════════════════════════════════════════

    /** 轨道两态终色（底, 描边）—— 与 `drawable/switch_track.xml` 的两个 item 逐 token 对齐，**只读 token、不写字面量**。 */
    private fun t36DblTrackTarget(on: Boolean): Pair<Int, Int> =
        if (on) attrColor("colorAccentFill") to attrColor("colorOnPrimary")
        else attrColor("colorTrackOff") to attrColor("colorControlOutline")

    /** 过渡期用的临时轨道：形状/圆角/描边宽度与 `switch_track.xml` 一致（胶囊 = 半高、1dp 描边）。 */
    private fun t36DblTrackDrawable(bg: Int, strokeColor: Int): android.graphics.drawable.GradientDrawable {
        val density = resources.displayMetrics.density
        val h = if (dblBtn.height > 0) dblBtn.height else (26 * density).toInt()   // 兜底 = layout 的 26dp
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = h / 2f
            setColor(bg)
            setStroke((1 * density).toInt().coerceAtLeast(1), strokeColor)
        }
    }

    private fun t36AnimateDblTrack(on: Boolean) {
        if (!::dblBtn.isInitialized) return
        dblTrackAnim?.cancel()
        dblTrackAnim = null                      // 先置空：被 cancel 的那条 onAnimationEnd 据此判定「已被接管」
        val (toBg, toStroke) = t36DblTrackTarget(on)
        if (!motionEnabled()) {
            // 系统关动画 ⟹ 一帧到位；终态与按压态都交回 selector（不残留任何中间色）。
            dblTrackBg = toBg
            dblTrackStroke = toStroke
            dblBtn.setTrackResource(R.drawable.switch_track)
            // ⚠ 2026-10-29 t36（实测踩到）：setTrackResource 只换 drawable 实例，**不会**把当前状态
            //   灌给它（drawableStateChanged 只在状态变化时触发）⟹ selector 停在默认 item = 关态灰轨，
            //   动画末尾会从蓝「啪」地跳回灰。必须显式补一次 setState。
            dblBtn.trackDrawable?.setState(dblBtn.drawableState)
            return
        }
        val (offBg, offStroke) = t36DblTrackTarget(!on)
        // 起点：中途反向时用**当前帧**颜色；否则用另一端终色（第一次切换时 dblTrackBg 还是 0）。
        val fromBg = if (dblTrackBg != 0) dblTrackBg else offBg
        val fromStroke = if (dblTrackStroke != 0) dblTrackStroke else offStroke
        val argb = android.animation.ArgbEvaluator()
        val anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180L
            interpolator = pgEaseOut
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                dblTrackBg = argb.evaluate(t, fromBg, toBg) as Int
                dblTrackStroke = argb.evaluate(t, fromStroke, toStroke) as Int
                dblBtn.setTrackDrawable(t36DblTrackDrawable(dblTrackBg, dblTrackStroke))
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (dblTrackAnim !== a) return       // 快速连点：已被下一次切换接管，不许还原
                    dblTrackAnim = null
                    dblTrackBg = toBg
                    dblTrackStroke = toStroke
                    dblBtn.setTrackResource(R.drawable.switch_track)   // 交回 selector（终态 + 按压态）
                    dblBtn.trackDrawable?.setState(dblBtn.drawableState)
                }
            })
        }
        dblTrackAnim = anim
        anim.start()
    }

    fun hideKeyboard(v: View) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(v.windowToken, 0)
        v.clearFocus()
    }

    // ⑥API<29写公共下载目录需运行时授权;授权回来后继续导出
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && pendingExport) {
            pendingExport = false
            if (grantResults.isNotEmpty() &&
                grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                exportCsv()
            } else {
                android.app.AlertDialog.Builder(this)
                    .setMessage("没有存储权限,无法导出到下载目录")
                    .setPositiveButton("确定", null).show()
            }
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("tab", tab)
        for ((k, e) in inp) out.putString("in_$k", e.text.toString())
        if (::levText.isInitialized) out.putString("in_L", levText.text.toString())
        // ⚠ "fee"/"mmr" **不再进 Bundle** —— 两个输入框已删（墓碑见 [feeInp] 声明处）。
        //   留着这两行 ⟹ 每次切后台/转屏都 UninitializedPropertyAccessException。
        out.putBoolean("dbl", dbl)
    }

    // v4§7 回前台取一次汇率(缓存6h内不动网;无轮询、无定时器、无后台常驻)
    // ⚠ 2026-10-27 起本函数**多了前台定时拉取**（用户裁定，见 [autoPullTick] 段注释）：
    //   onResume 开闸并排第一拍，onPause 关闸并撤掉排队中的 tick ⟹ 后台零请求。
    //   ⚠ 从后台回来时**若距上次拉取已超过一个周期就立刻补一拍**（否则回来要先看 5 分钟旧数据）；
    //     首次进入前台不补 —— 冷启动那条路 onCreate 本来就会取一轮，补一拍只会重复打一次。
    override fun onResume() {
        super.onResume()
        MktData.FX.ensure(this)
        autoPullOn = true
        uiHandler.removeCallbacks(autoPullTick)
        if (lastAutoPullAt == 0L) lastAutoPullAt = System.currentTimeMillis()
        val overdue = System.currentTimeMillis() - lastAutoPullAt >= AUTOPULL_MS
        uiHandler.postDelayed(autoPullTick, if (overdue) 0L else AUTOPULL_MS)
    }

    /**
     * 进后台：**立刻停表**，并移除已经排队但还没跑的那一拍。
     * 这是「app 在后台就不拉取」的唯一实现点 —— 没有第二处、也不需要再判断一次可见性。
     */
    override fun onPause() {
        super.onPause()
        autoPullOn = false
        uiHandler.removeCallbacks(autoPullTick)
    }

    // ---------- ⑦返回键分发(稿 OnBackPressedDispatcher 语义,内置同构微型版) ----------
    // androidx.activity 实现会要求 gradle.properties android.useAndroidX=true,
    // 该文件不在本任务 in-scope 清单内(契约校验拒绝),故在此内置等价分发器:
    // onBackPressed() → 首个 enabled 回调(handleOnBackPressed);无回调走系统默认 finish。
    open class OnBackPressedCallback(open val enabled: Boolean) {
        open fun handleOnBackPressed() {}
    }

    class OnBackPressedDispatcher {
        private val callbacks = mutableListOf<OnBackPressedCallback>()
        fun addCallback(owner: Any?, callback: OnBackPressedCallback) {
            callbacks.add(callback)
        }
        fun hasEnabledCallbacks(): Boolean = callbacks.any { it.enabled }
        fun handleOnBackPressed() {
            callbacks.lastOrNull { it.enabled }?.handleOnBackPressed()
        }
    }

    private val onBackPressedDispatcher = OnBackPressedDispatcher()

    // 稿⑦:回调常驻(在 onCreate 注册)——行情详情可见时拦回自选列表,根 Tab 走默认 finish()
    override fun onBackPressed() {
        if (onBackPressedDispatcher.hasEnabledCallbacks()) {
            onBackPressedDispatcher.handleOnBackPressed()
        } else {
            super.onBackPressed()
        }
    }

    /**
     * 2026-10-29 t33 · **参数格聚焦态改成稿的 `:focus-within` 行为**（函数名沿用旧名 t429EditWidth92，
     * 但它**已经不再改任何宽度** —— 见末尾「为什么保留旧名」）。
     *
     * 稿真值（`四屏-计算页.html` / `theme.css` §6.6，逐字）：
     *   `.calc-field, .calc-cell { background: bg-surface-2; border: 1px solid transparent; r-input }`
     *   `.calc-field:focus-within, .calc-cell:focus-within { border-color: accent; background: bg-surface }`
     *   ⟹ 获焦时**整格**（标签 + 输入体 + 单位一起）变白、外圈 1dp accent 描边；**布局不动、单位常驻**。
     *
     * App 等价实现（Android 没有 `:focus-within`：子 View 获焦不会把 state_focused 传给父容器）：
     *   · 输入体 `drawable/calc_input_bg` 两态**全透明** —— 面与框一律由格子容器承担；
     *   · 获焦时把**格子容器**（= `edit.parent`，那 6 个 `.calc-field` / `.calc-cell` 的 LinearLayout）
     *     的 background 换成 `drawable/calc_cell_bg_focus`（colorCard + 1dp colorAccentText + r10，
     *     即稿的 `bg-surface + accent + r-input`）；
     *   · 失焦换回 `drawable/calc_tile_bg`（surface2 + r10，稿的常态格）。
     *
     * ⚠ 旧行为（t429，2026-10-02）**整段删除**：获焦时「输入框压到 92dp + 标签吃富余 + 单位 GONE」。
     *   那是 v1 稿「编辑态 width:92 / 无单位」的近似，与 Oct-28 稿的 `:focus-within` 直接冲突：
     *   稿明确「整格亮起、布局不动、单位不隐藏」。删掉后：输入框**恒** 0dp + weight=1、
     *   标签**恒** wrap_content、单位常驻 ⟹ 长数字（如 151,440.00）在框内横向滚动（singleLine），
     *   既不会挤走标签/单位，也不会换行（回归项见 t33 报告的装机四图）。
     *
     * ⚠ 失焦的千分位格式化（t7，稿 L2201：12500 → 12,500.00）**必须留在这个 listener 里**：
     *   `setOnFocusChangeListener` 是**覆盖式**的，再挂第二个会把整格高亮顶掉 ⟹ 两件事同住一个 listener。
     *
     * ⚠ 为什么保留旧名 `t429EditWidth92`：全仓注释 / 报告 / 交接台账都用这个名字定位这段逻辑，
     *   改名会让历史引用集体失锚（本仓惯例：保留锚点 + 在注释里写明现状，而不是悄悄改名）。
     *   它现在只做两件事：**整格高亮**（容器背景两态切换）+ **失焦格式化**。
     */
    private fun t429EditWidth92(edit: EditText, dp: Int? = null) {
        val cell = edit.parent as? View ?: return
        edit.setOnFocusChangeListener { _, hasFocus ->
            // 稿的 :focus-within == 容器换装（整格变白 + 1dp accent），输入体自己不再画任何东西。
            cell.setBackgroundResource(
                if (hasFocus) R.drawable.calc_cell_bg_focus else R.drawable.calc_tile_bg)
            // t7：失焦时按稿 L2201 排成「补位 + 千分位」（12500 → 12,500.00、1 → 1.00）。
            if (!hasFocus) t7formatField(edit, dp)
        }
    }

    /**
     * 2026-10-29 t33 · 杠杆倍率格（`.calc-lev`）同样吃 `:focus-within` ——
     * 稿 `theme.css:600-601` 把 `.calc-lev` 与 `.calc-field/.calc-cell` 写进**同一条**聚焦规则。
     *
     * 它的结构与那 6 个参数格不同：`in_L` 的父级是「标签 + 输入 + ×」的行内 LinearLayout（无底），
     * **带面的是祖父**（杠杆格，background = calc_tile_bg）⟹ 这里显式上溯两层。
     * 上溯失败就什么都不做（不抛异常、不影响输入）；杠杆格没有 t7formatField（它的值由
     * LEV_MAX 夹取 + 数字键盘写入，不存在千分位格式化需求）。
     */
    private fun t33FocusLevCell(edit: EditText) {
        val cell = (edit.parent as? View)?.parent as? View ?: return
        edit.setOnFocusChangeListener { _, hasFocus ->
            cell.setBackgroundResource(
                if (hasFocus) R.drawable.calc_cell_bg_focus else R.drawable.calc_tile_bg)
        }
    }

    /**
     * 2026-10-29 t39 · 数值输入框的**长值自适应收缩**（把「500,001,100.00 被裁成 500,001,10(」修掉）。
     *
     * ⚠ 为什么 XML 的 autoSize 不够 —— 实测结论（不是推测，装机连拍量的）：
     *   本页 7 个数值 EditText 都是 `singleLine="true"`，可**横向滚动**；平台对这类 TextView 走
     *   `mHorizontallyScrolling` 分支（desired width = VERY_WIDE），autosize 引擎因此判定「永远放得下」
     *   ⟹ 从不收缩。实测：`35.00` 与 `500,001,100.00` 的字符步进都是 **17.8px/字符**（字号没变），
     *   长值右侧被裁掉。XML 里那组 autoSize 属性**保留**（对非滚动控件/将来平台有效、无副作用），
     *   真正生效的是这里。
     *
     * 做法：以 `fs_title`(16sp) 为起点、按 **0.5sp** 步长往下试，用 `Paint.measureText` 量当前文本；
     *   量出的宽度 ≤ 可用宽度（控件宽 − 左右 padding）即停。下限 **11sp**（= fs_micro）。
     *   短值第一次就通过 ⟹ 仍渲染在 fs_title，不会被无谓缩小。
     *   ⚠ 只改 `textSize`，不动 layout_width/weight（t10/t12 的栅格不动）。
     *   ⚠ 可用宽度里再扣 **4dp 呼吸位**：文本右对齐，扣掉之后左侧会留出 4dp 缝，
     *     长值才不会「数字紧贴标签」（用户原症状的一半就是这个贴）。
     */
    private fun t39FitText(edit: EditText) {
        val dm = resources.displayMetrics
        val gutter = 4 * dm.density
        val avail = (edit.width - edit.paddingLeft - edit.paddingRight - gutter).toFloat()
        if (avail <= 0f) return
        val text = edit.text?.toString().orEmpty()
        val maxPx = resources.getDimension(R.dimen.fs_title)
        // ⚠ 2026-10-29 队长裁定（采纳 t39 的建议①）：**下限按 px 恒定，不随系统字号放大**。
        //   改前 `applyDimension(SP, 11f)` = 11sp × fontScale ⟹ 系统字号 1.5 时下限也涨到 16.5sp，
        //   而格子宽度是 dp（不随字号变）⟹ 极长值在 1.3/1.5 下必然仍被裁
        //   （t39 实测：1.3 格宽 158px 显示「500,001,10」；1.5 格宽 132px 显示「500,001」）。
        //   现在下限 = `11 * density`（11dp 的 px 值，与 fontScale 无关）：
        //     · 短值仍从 fs_title 起量，**照旧随系统字号放大**（上限未动，可访问性意图保留）；
        //     · 只有放不下时才收缩，且止于 11dp 的物理字号 ⟹ 长值不再被裁。
        //   为什么不选「省略号 + 聚焦滚动」：数字被截断时省略号会被误读成另一个数，
        //   而未聚焦态无法滚动查看 ⟹ 信息实际丢失。宁可小一点，也要完整。
        val minPx = 11f * dm.density
        val stepPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 0.5f, dm)
        val paint = android.graphics.Paint(edit.paint)
        var size = maxPx
        while (size > minPx) {
            paint.textSize = size
            if (paint.measureText(text) <= avail) break
            size -= stepPx
        }
        if (size < minPx) size = minPx
        // 只在真的变了才 set：避免每帧 requestLayout（也会和 EditText 的光标/滚动打架）。
        if (kotlin.math.abs(edit.textSize - size) > 0.5f) {
            edit.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // ⚠⚠ 2026-10-03 **全局未捕获异常捕获**：把堆栈写进 files/crash.txt，
        //   同时照旧交给系统处理（弹窗 + 退出，行为不变）。
        //
        //   为什么要它：用户实报「在自选里连点多下交易品种，尤其比特币 → 闪退」，
        //   但我在模拟器上**复现不出来**（连点 BTC/MU/AAPL/000660 均正常）。
        //   没有堆栈就只能猜 —— 而我今天已经因为「猜」错了好几轮
        //   （先怪数据库缺失、再怪 symChanged、又怪 feeInp）。
        // ⟹ 落盘一份，用户下次复现后 adb pull 一下就是确定答案。
        //
        //   读取：root 后 `adb shell cat /data/data/cn.gridcalc.gridcalc/files/crash.txt`
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, ex ->
            try {
                val sb = StringBuilder()
                sb.append("时间: ").append(java.util.Date()).append("\n")
                sb.append("线程: ").append(th.name).append("\n\n")
                sb.append(ex.stackTraceToString())
                java.io.File(filesDir, "crash.txt").writeText(sb.toString())
            } catch (_: Throwable) {
            }
            prev?.uncaughtException(th, ex)
        }
        mode = loadMode()
        val actual = when (mode) {
            "dark" -> "dark"
            "light" -> "light"
            else -> {
                val night = (resources.configuration.uiMode and
                        Configuration.UI_MODE_NIGHT_MASK) ==
                        Configuration.UI_MODE_NIGHT_YES
                if (night) "dark" else "light"
            }
        }
        setTheme(if (actual == "dark") R.style.Theme_GridCalc_Dark
        else R.style.Theme_GridCalc_Light)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // ⚠ 2026-10-16 t59：原来这里挂了一个 `addOnLayoutChangeListener` 兜底触发 `syncTabBars()`
        //   （指示条按稿 44% 算宽，首次进入时 tab 还没测量）。**本轮药丸改由布局声明式给宽**
        //   （`layout_width="match_parent"` + 宿主 padding）⟹ 监听与 syncTabBars() 一并删除。
        //   两处老坑仍留在 git 历史里，别重犯：① `View.OnLayoutChangeListener` 的签名返回 **void**
        //   （t426 以为要返回 Int，那个 `else 0` 是死代码）；② `gradlew | Select-String` 管道的
        //   退出码**不能**当 gradle 的判据，要看 `e: ` 与 `BUILD FAILED`。

        // ⚠ t416 **删掉了**这一行原来的 t102FixNavLabels()。
        //   它在运行期把四个底栏标签覆盖成 note 档 12sp,而稿 L246 逐字是
        //   `fontSize:T.title` = **16sp**; activity_main.xml 本来就写的 fs_title(16sp),
        //   是这个覆盖把它压下去的(真机实测 label view 24.00×12.00dp,16sp 该是 32dp)。
        //   t102 当初的理由是「11sp 属于『第一眼像渲染坏了』那一档」——
        //   那是**迁移前**的一次 UI 打磨,比「按稿对齐」早。按稿优先。
        //   四个 label 的 id 与 findViewById 绑定都没动,只是不再被运行期改。
        edgeToEdge(actual != "dark")

        body = findViewById(R.id.body)
        val inf = LayoutInflater.from(this)
        // ⚠ 2026-10-05 **必须在两个 Panel 构造之前 attach**：
        //   `MktSuggest.favName` 是同步函数，自选行首帧就要读它。
        //   资产随 APK 分发，装载是同步读 raw，不联网、失败也只是空表（回落显示代码）。
        UsNames.attach(this)
        calcPage = inf.inflate(R.layout.page_calc, body, false)
        settingsPage = inf.inflate(R.layout.page_settings, body, false)
        mktPage = inf.inflate(R.layout.page_mkt, body, false)
        // ⚠⚠ 2026-10-05 「关于」版本号**从 PackageInfo 取**，不再写死（原写死 v4.1.1，
        //   而真机 App 已到 v4.5 —— 两份真相必然漂移）。发版只改 build.gradle.kts 一处。
        val aboutV = settingsPage.findViewById<android.widget.TextView>(R.id.about_version)
        // ⚠ 2026-10-06 关于页同时打出 versionCode（用户要求：设置界面做标记，
        //   否则几个手工包 versionName 全是 4.5，装的是哪个根本分不清）。
        //   versionName 仍是用户裁定的 4.5 不动；括号里的数字只用于对包。
        val pkgInfo = try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0L))
            } else { @Suppress("DEPRECATION") packageManager.getPackageInfo(packageName, 0) }
        } catch (e: Exception) { android.util.Log.w("LP", "about version failed: $e"); null }
        // ⚠ 2026-10-06 曾同时打出 versionCode（设置界面做标记，否则几个手工包分不清）。
        //   ⚠ 2026-10-13 用户裁定**移除 versionCode 括号**（「图片上的东西可以去掉了」）——
        //     版号体系已随 v4.6 升档（第二位逢十进一），关于页只留 versionName。
        val vn = pkgInfo?.versionName
        aboutV?.text = "网格罗盘" + (vn?.let { " v$it" } ?: "")
        mktPanel = MktPanel(this, mktPage)
        favPage = inf.inflate(R.layout.page_fav, body, false)
        favPanel = FavPanel(this, favPage, mktPanel) { s, disp ->
            mktPanel.setSym(s)
            // ⚠ 2026-10-03 用户裁定「把行情换成该交易品种的名字」：
            //   顶栏原��写死「行情」，现在显示该品种名（美光 / 比特币 / …）。
            //   ⚠ 这**偏离稿**：稿 v2.html:2062 是 `<AppBar title="行情"/>`，标题恒为「行情」。
            //     产品裁定，不是移植对齐。要回稿：删掉这一行即可（布局里写死的「行情」仍在）。
            mktPanel.setDispName(disp, s)
            // 稿L383 showMkt(){… window.scrollTo(0,0)}:打开行情详情时复位自选页滚动,
            // 深滚后进详情→返回,自选静止位回垫高位 top≥136(A9)。仅此详情往返路径复位;
            // Tab往返走 showTab,稿L366-373无复位,不新增任何 Tab 复位逻辑。
            (favPage as? android.widget.ScrollView)?.scrollTo(0, 0)
            // ⚠⚠ 2026-10-03 **用户裁定改**：去掉第三参 "fav"，导航高亮随内容一起走到「行情」。
            //   原话：「为什么还是有界面在行情，导航栏在自选的情况」。
            //
            //   改前 `showTab("mkt", ANIM_FROM_R, "fav")`：
            //     showTab 里 `tab = activeTab ?: name` ⟹ 页面按 name 换成行情页，
            //     而导航高亮被第三个参数**钉死在"fav"** ⟹ **内容与导航是两个状态**，
            //     屏上就是「行情的内容 + 自选的高亮」。用户报的就是这个。
            //
            //   稿子没有这个分裂：v2.html:2149 行情屏渲染的是 `<Tabs cur="quote"/>`，
            //   导航高亮**由当前渲染的屏决定**，没有第二套状态可钉。
            //   ⟹ 去掉第三参 = `tab = name` = 高亮跟内容，一致。
            //
            //   ⚠ 连带效果：从行情页按返回键回到自选时，**不会**再自动把高亮改回"fav"——
            //     高亮只在 showTab 被调用时更新，而返回走的也是 showTab("fav")，
            //     那时 tab 会被正确设成 "fav"。两条路径都统一由 showTab 决定，不再有例外。
            showTab("mkt", ANIM_FROM_R)
            (mktPage as? android.widget.ScrollView)?.scrollTo(0, 0)
            mktPanel.mkLoad()
        }
        mktPanel.onFavChanged = { favPanel.repaint() }
        // 点▾确认=提交距离窗口+重刷自选距离+收菜单(稿winGo;收菜单在MktPanel内)
        // ⚠⚠ 2026-10-12 用户裁定 B 改：回调从**无参全量**改成**带刚确认的品种**，
        //   这里只刷那一个品种 —— `refreshFavDist(only = setOf(sym))`。
        //   改前是 `refreshFavDist()`（全量、net=false）：那时自选页所有行共用**一个全局窗口**
        //   （MktPanel 的 DistWin），一确认则全部行的 FavDist key（含 n）同变 ⟹ 两级缓存/
        //   复用层全 miss ⟹ 8~10 个品种错峰各发一次请求 —— 用户报的「改了 TSLA 的窗口，
        //   退回自选又把全部品种取了一遍」。窗口改成每品种后（gridcalc_win_v1[s]），
        //   **只有**被确认那个品种的 key 会变 ⟹ 其余行根本进不了取数路径（缓存照旧命中）。
        //   sym 空 = 行情页没载入品种（saveWin 此时也早退、盘里什么都没变）→ 不刷、零请求。
        mktPanel.onDistCommitted = { sym ->
            if (sym.isNotEmpty()) favPanel.refreshFavDist(only = setOf(sym))
        }
        // 行情页每取到一批新 K 线 → 通知自选页（丢旧距离缓存 + 按这一批重算那一行）。
        // 2026-11-02 用户报「行情页拉到了，自选页还是旧值，得一个个点」：此前两屏之间没有数据事件，
        // 自选只能等自己下一轮全量抓取。这里零新增请求（复用层吃 LastChart）。
        mktPanel.onChartReady = { sym ->
            if (::favPanel.isInitialized) favPanel.onMarketChartReady(sym)
        }

        // ④ 启动时刷新一次 —— **已移到 onCreate 末尾**。
        //   ⚠ 2026-10-28 t12：原文写「见那里的 autoRefreshOnce()」—— 那个函数**已不存在**
        //   （设置页「启动时刷新一次」开关在 t4 整行移除后，「启动刷新」收敛为**恒执行一次**，
        //     入口就是 onCreate 末尾那段 `lastSymPersisted() → setSym + mkLoad`，没有独立函数名）。
        //   这里只改注释，零逻辑改动。
        //   （原来就放在这里，但它会同步走到 restoreCalc，而计算页的视图那时还没绑定。）
        // t201 ⑧:原本这里给 mkt_back(「‹」返回按钮)挂 setOnClickListener, 已随该按钮一起删掉 ——
        // 稿 L522 的 AppBar 里只有标题。系统返回键在下面 OnBackPressedDispatcher, 未动。
        // 稿⑦返回键OnBackPressedDispatcher:仅行情详情可见时拦回自选列表,根Tab默认finish()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 候选层是临时 UI，返回键先关它（连带软键盘），别越过它直接 finish()。
                // 2026-10-14 用户真机实测:候选可见时 back#1 被软键盘吃掉、back#2 直接退出 App。
                if (favPanel.onBackPressedSug()) return
                if (body.indexOfChild(mktPage) >= 0) showTab("fav", ANIM_FROM_L)
                else finish()
            }
        })

        tabFav = findViewById(R.id.tab_fav)

        tabMkt = findViewById(R.id.tab_mkt)
        tabCalc = findViewById(R.id.tab_calc)
        tabSetup = findViewById(R.id.tab_setup)
        tabCalcLabel = findViewById(R.id.tab_calc_label)
        tabSetupLabel = findViewById(R.id.tab_setup_label)
        tabFavLabel = findViewById(R.id.tab_fav_label)
        tabMktLabel = findViewById(R.id.tab_mkt_label)
        tabFavBar = findViewById(R.id.tab_fav_bar)
        tabMktBar = findViewById(R.id.tab_mkt_bar)
        tabCalcBar = findViewById(R.id.tab_calc_bar)
        tabSetupBar = findViewById(R.id.tab_setup_bar)
        // t130:补第 4 个 tab。稿 `go('quote')` 语义 = 进入**上次查看的那个品种**的行情页;
        // mktPage 是常驻 View,内部保留当前品种与周期,所以直接 showTab("mkt") 即是"上次那个"。
        // 首次启动(从未看过)时 mktPage 用它自己的默认品种,见 notes 的理由说明。
        tabMkt.setOnClickListener { showTab("mkt", ANIM_FROM_L) }
        tabCalc.setOnClickListener { showTab("calc") }
        tabSetup.setOnClickListener { showTab("setup") }
        tabFav.setOnClickListener { showTab("fav") }

        val ids = mapOf("C" to R.id.in_C, "Pl" to R.id.in_Pl,
            "Ph" to R.id.in_Ph, "Po" to R.id.in_Po, "N" to R.id.in_N,
            "q" to R.id.in_q)
        for ((k, id) in ids) {
            val e = calcPage.findViewById<EditText>(id)
            inp[k] = e
            // ⚠ 2026-10-02 t7：dp 逐字段照稿 v2.html:276-280 的 PDEF ——
            // cash/lo/hi/trig 是 dp=2，网格数量 N 是 dp=0（整数值），
            // 每格数量 q 传 -1：**只加千分位不取整**，理由见 t7formatField 的注释。
            // ⚠⚠⚠ 2026-10-03 **价格三字段（最低/最高/触发）改为 dp = -1，不再取两位小数**。
            //   起因是用户实测 1000SATS 网格：**0.00000957 被显示成 0.00**，
            //   随后报「触发价上方要有格子」—— 价格被抹平，整套计算全错。
            //   那个 `2` 是照稿 v2.html:276-280 的 PDEF 抄来的，
            //   而**稿的前提是价格为 4 位数级别**（黄金 6189、股票 150）。
            //   加密货币 8 位小数不在那个前提内。
            //
            // ⚠ 这是**同一个病第二次犯**：#100 那次已经在「每格数量」上踩过
            //   （q=0.001 被 Math.round 抹成 0，收益率从 +58.52% 变 +87780.48%），
            //   当时只给 q 一个特例，**没把价格字段一起治**。
            //   ⟹ 教训：格式化精度**不是逐字段特例**，是「数值量级」问题。
            //
            // dp=-1 的实现是 `%,.8f` 后去尾零：
            //    0.00000957 → "0.00000957" ✅   6189 → "6,189"（少了 .00，可接受）
            val dp = when (k) {
                "N" -> 0
                "q" -> -1
                // ⚠ 价格三字段也走 -1：见上方说明。**不要再改回 2。**
                "Pl", "Ph", "Po" -> -1
                else -> 2
            }
            t429EditWidth92(e, dp)
            t7formatField(e, dp)   // 首屏就排好：XML 预填的默认值也是「12,500.00 / 1.00」那一版
            // 2026-10-29 t39：长值自适应收缩（XML 的 autoSize 对本页单行可滚动的 EditText 不生效，见 t39FitText）。
            e.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { e.post { t39FitText(e) } }
            })
            e.post { t39FitText(e) }
            // t105「回车才算」四路触发,一次修全(此前只有拖滑杆能算,回车/IME ✓ 全不触发):
            //   ① **先算后收键盘**:原来 hideKeyboard(v) 在 onCalc() 之前,它内部会 v.clearFocus(),
            //      一旦抛异常 onCalc 永不执行 —— 真机实测就是这条(队长按 ✓ 出不来数)。
            //   ② **多 actionId**:不同输入法/数字键盘回车传的 actionId 不一(Gboard 数字盘会传
            //      UNSPECIFIED/NEXT),只认 IME_ACTION_DONE 会漏。
            //   ③ **setOnKeyListener 兜硬件回车**:硬件 KEYCODE_ENTER 不走 IME action 通道。
            //   ④ 整段 try/catch:任何异常都不能把"算账"吞掉。
            // 注意:**没有**恢复"输入即算"(v4 D2 已取消),只有显式触发才算。
            e.setOnEditorActionListener { v, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE ||
                    actionId == EditorInfo.IME_ACTION_UNSPECIFIED ||
                    actionId == EditorInfo.IME_ACTION_NEXT ||
                    actionId == EditorInfo.IME_ACTION_NONE) {
                    onCalc(userInitiated = true)   // t143:不再吞异常(t121 教训);出错要能看见
                    try { hideKeyboard(v) } catch (t: Throwable) { }
                    true
                } else false
            }
            e.setOnKeyListener { v, keyCode, ev ->
                if (keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                    ev?.action == android.view.KeyEvent.ACTION_DOWN) {
                    onCalc(userInitiated = true)   // t143:不再吞异常(t121 教训);出错要能看见
                    try { hideKeyboard(v) } catch (t: Throwable) { }
                    true
                } else false
            }
            // v4 D2:改任一参数 → 结果区立即清空 + 「保存数据」按钮回 disabled。
            // 改参数后若还留着上一次的结果,保存下去就是「参数与结果不自洽」的记录。
            // suppressReset 闸避开 restoreCalc 回填的 setText 自激。
            e.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (suppressReset) return // 程序写值,不触发
                    // ① 清零点:用户真实输入 → 退出回填态,联动恢复
                    restoreGuardSym = null
                    idle() // idle() 末尾已有 saveIdle(),按钮自动回 disabled
                }
            })
            // ⚠⚠ 2026-10-03 用户裁定「网格数量自动算但允许手改」。
            //   挂在这里（循环**内部**、inp[k] 已赋值之后）。
            //   ⚠⚠ 上一版挂在 for 循环**之前**，直接 `inp["N"]!!` NPE 崩在 onCreate ——
            //     那时 inp 还是空 Map，字段根本没填。Kotlin 的 `!!` 在这里救不了场：
            //     它只是把「顺序错了」换成「启动即崩」。**先填表，后挂监听。**
            if (k == "N") e.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e2: android.text.Editable?) {
                    if (suppressReset) return      // 程序写值 ⟹ 不是手改
                    val text = e2?.toString()?.trim().orEmpty()
                    if (text.isEmpty()) {
                        // 只有用户清空 N 才释放自动填充；下一次阈值/价格到位可再写入。
                        nManual = false
                        return
                    }
                    // 用户手填不依赖 stepThr 是否已到；否则先填 N 再等行情会被误判为自动态。
                    nManual = true
                    val key = normSym(curCalcSym())
                    if (key.isNotEmpty()) nManualSym = key
                }
            })
        }
        calcHeroBox = calcPage.findViewById(R.id.calc_hero_box)
        calcIdleHint = calcPage.findViewById(R.id.calc_idle_hint)
        calcOverline = calcPage.findViewById(R.id.calc_overline)
        statTilesTopline = calcPage.findViewById(R.id.stat_tiles_topline)
        calcOverlineTopline = calcPage.findViewById(R.id.calc_overline_topline)
        toggleRow = calcPage.findViewById(R.id.toggle_row)
        toggleRowTopline = calcPage.findViewById(R.id.toggle_row_topline)
        hero = calcPage.findViewById(R.id.hero)
        heroSub = calcPage.findViewById(R.id.hero_sub)
    // ⚠⚠⚠ 2026-10-05 **用户裁定：这一行小字不要**（真机截图圈出来的）。
    //   原屏上是：「收益率 +192.38% · 12卖2买 · 00700 已保存于 2026-10-06 10:20:31
    //   (原币 HKD · 汇率 7.8471)（点「计算」可重算)」。
  //   前半截**和下面四格是同一批数**（收益率、买格卖格都另有一格），
    //   后半截是保存时间与币种说明 —— 一屏里同一件事说两遍。
    //   ⟹ 整行 GONE，不占位。
    //   ⚠ **连带后果**：t105SetDirty 的「● 未保存 / 已保存」也挂在这行上（见该函数），
    //     它随之一起消失；**保存状态改由「保存数据」按钮自己承担** ——
    //     [saveIdle] 时 disabled、有新结果时 enabled，本就是那个按钮的职责。
    //     若之后要未保存提示，**恢复一个最短版本**，不要把这一整行长回来。
        heroSub.visibility = View.GONE
        statVals["liq"] = calcPage.findViewById(R.id.stat_liq)
        statLiqGap = calcPage.findViewById(R.id.stat_liq_gap)  // t43：格内副行
        statVals["roe"] = calcPage.findViewById(R.id.stat_roe)
        statVals["pos"] = calcPage.findViewById(R.id.stat_pos)
        statVals["sell"] = calcPage.findViewById(R.id.stat_sell)
        // t104:一行四格里的 买格·卖格,以及展开更多的 建仓成本/计价币种/美元汇率
        // ⚠ 2026-10-28 t4：**「见底权益」格已整块移除**（用户裁定），`stat_eqb` / `stat_eqb_unit`
        //   两个节点不再存在 ⟹ 这里不再登记 `statVals["eqb"]`。**别按旧注释把它加回来**：
        //   statVals 是 `MutableMap<String, TextView>`，findViewById 找不到时存进去的是 null，
        //   后面 `statVals.values.forEach { it.text = ... }` 会在 idle()/onCalc() 里直接 NPE。
        statVals["grids"] = calcPage.findViewById(R.id.stat_grids)
        statVals["cost"] = calcPage.findViewById(R.id.stat_cost)
        statVals["ccy"] = calcPage.findViewById(R.id.stat_ccy)
        // 稿 L632:「计价币种 / 美元汇率」合并为一行,值写进 stat_ccy(见 L385)。
        // stat_fx 这个 id 与它的绑定一并删除(t167),XML 侧那一行也删。
        // ⚠ 2026-10-03 lev_bar 与 side_short 两个节点已从 page_calc.xml 删除 ⟹
        //   这两行 findViewById 若留着会拿到 null，后面 setOnSeekBarChangeListener /
        //   setOnClickListener 会 NPE。**必须与布局改动成对删除。**
        levText = calcPage.findViewById(R.id.in_L)
        sideLong = calcPage.findViewById(R.id.side_long)
        moreToggle = calcPage.findViewById(R.id.more_toggle)
        moreRow = calcPage.findViewById(R.id.more_row)      // r8 C-4: 第一段容器(卡面/内边距/整段可点)
        moreChev = calcPage.findViewById(R.id.more_chev)    // r8 C-4: 8dp×8dp chevron
        detChev = calcPage.findViewById(R.id.det_chev)      // r8 C-4: 同上(网格明细段)
        moreBox = calcPage.findViewById(R.id.more_box)
        detToggle = calcPage.findViewById(R.id.det_toggle)
ladderNote = calcPage.findViewById(R.id.ladder_note)
        detBox = calcPage.findViewById(R.id.det_box)
        // ⚠ 2026-10-03 用户裁定：杠杆改填空（滑杆已删）。值的唯一入口就是这个输入框。
        //   范围 1..150 —— 原稿 Slider(max 20)，2026-10-03 用户裁定提高到 150
        //   （原话：「杠杠上限太少了，最高改为150」）。
        //   ⟹ 偏离稿，是产品裁定。上限只在这一处定义（LEV_MAX），下面越界改写都读它。
        levText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) {
                if (suppressReset) return                    // 程序回填只写值，末尾 post 统一计算
                val s = e?.toString()?.trim().orEmpty()
                if (s.isEmpty()) return                      // 中间态：不算，等下一个字符
                val d = s.toDoubleOrNull() ?: return
                if (d < 1.0 || d > LEV_MAX) {
                    // 越界立刻改写成夹取值（会再次触发本回调，第二次落在界内即停）
                    levText.setText(if (d < 1.0) "1" else LEV_MAX.toInt().toString())
                    levText.setSelection(levText.text.length)
                    return
                }
                onCalc(userInitiated = true)                 // 界内：只重算，不回写
            }
        })
        // ⚠ 这里不调 t341SetLev：输入框的值**已经**是用户敲进来的值，
        //   再回写一遍只会和 TextWatcher 绕。回车只负责收起键盘 + 收尾算一次。
        levText.setOnEditorActionListener { v, _, _ ->
            (v as? android.view.inputmethod.InputMethodManager)?.let { }
            v.clearFocus()
            onCalc(userInitiated = true)
            true
        }
        // ⚠ 2026-10-29 t33：杠杆格也吃稿的 `:focus-within`（theme.css:600-601 把 `.calc-lev`
        //   与 `.calc-field/.calc-cell` 写进同一条聚焦规则）。输入体已改全透明 ⟹ 不挂这段，
        //   杠杆格获焦时就没有任何视觉反馈了（旧行为是输入体自己画框，已随 t33 删除）。
        //   2026-10-29 t39：同一套长值收缩也挂到杠杆输入（48dp 固定宽，比半宽格更窄）。
        levText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { levText.post { t39FitText(levText) } }
        })
        levText.post { t39FitText(levText) }
        t33FocusLevCell(levText)
        // ⚠ 滑杆的 findViewById / setOnSeekBarChangeListener 已随节点删除一并移除。

        // 做多：**状态徽标，不是按钮**（2026-10-29 t34 · 用户裁定 · 方案 C）
        // ⚠ 2026-10-03 用户裁定删「做空」：side_short 节点已删 ⟹ 这里不能再绑它（会 NPE）。
        //   做空公式本身仍在，屏上只是进不去，默认恒为做多。
        // ⚠ 2026-10-29 用户裁定：方向只作展示，不再是按钮 ⟹ 本监听已删。
        //   做空已于 2026-10-03 删除、sideShortMode 恒 false ⟹ 这个监听点了只是重算，没有可感知效果。
        //   若日后要恢复做空：需同时恢复 page_calc.xml 的 side_short 节点 + sideShort 字段与 findViewById
        //   + paintSide 的方向着色 + 本监听，四处缺一即崩。
        //   ⚠ 同时把 page_calc.xml 的 side_long 从「徽标」改回「分段项」（背景/内边距/字色/字阶/可点性五项）。
        // ⚠ 初始化时也不再调 paintSide()：徽标样式真值在 XML/资源里，运行期覆写会把它打回药丸。
        // 展开更多 / 网格明细:两个折叠区
        // ⚠ 2026-10-19 r8 · C-4：两段改竖排后各有自己的 chevron（`more_chev` / `det_chev`）：
        //   **折叠 = 45°、展开 = -135°**（稿 `四屏-计算页.html:181` / `:184`）。旋转只能由宿主
        //   View 给（drawable 自己转不了）⟹ 与面板的生灭同批设置。
        //   容器 `more_row` 也挂同一段逻辑：稿 `.calc-collapse__btn { width:100% }` ⟹ 整段可点
        //   （`more_toggle` 自己仍是可点的 TextView，同一次触摸只会命中其中一个，不会双触发）。
        fun toggleMore() {
            val vis = moreBox.visibility != View.VISIBLE
            animateVisibility(moreBox, vis)
            if (::moreChev.isInitialized) moreChev.rotation = if (vis) -135f else 45f
            // t167:「收起」二字归 ladder_note 独占,more_toggle 只报自己的名字
            moreToggle.text = "展开更多"
        }
        moreToggle.setOnClickListener { toggleMore() }
        if (::moreRow.isInitialized) moreRow.setOnClickListener { toggleMore() }
        detToggle.setOnClickListener {
            val vis = detBox.visibility != View.VISIBLE
            animateVisibility(detBox, vis)
            if (::detChev.isInitialized) detChev.rotation = if (vis) -135f else 45f
        t229SyncLadderNote()
        }
        detSum = calcPage.findViewById(R.id.det_sum)
        rows = calcPage.findViewById(R.id.rows)
        // 稿③LINK爆仓上限卡:第二行灰字「目标上限 X」+×解除
        // (稿capX: LKcap=null;paintCap;去红;清错;calc())
        statLiqCard = calcPage.findViewById(R.id.stat_liq_card)
        // t9: 存下「爆仓价」格的**原始面**。必须在这里取 —— 本页有几处 `statLiqCard.background = null`
        //     （capX 解除上限、onCalc 成功路径、restoreCalc），那些是不可逆的，取晚了就只能存到 null。
        statLiqBg0 = statLiqCard.background
        statTiles = calcPage.findViewById(R.id.stat_tiles)
        statCap = calcPage.findViewById(R.id.stat_cap)
        statCapv = calcPage.findViewById(R.id.stat_capv)
        calcErr = calcPage.findViewById(R.id.calc_err)
        calcNote = calcPage.findViewById(R.id.calc_note)
        capX = calcPage.findViewById(R.id.capX)
        capX.setOnClickListener {
            lkCap = null
            paintCap()
            statLiqCard.background = null
            statVals["liq"]?.setTextColor(attrColor("colorInk"))
            showErr(null)
            onCalc(userInitiated = true)
        }

        dblBtn = calcPage.findViewById(R.id.dbl_btn)  // t104:文字按钮 -> Switch
        dblBtn.setOnCheckedChangeListener { _, on ->
            if (dbl == on) return@setOnCheckedChangeListener
            dbl = on
            paintDbl()
            // 2026-10-29 t36：轨道颜色跟着滑块一起过渡（终态与按压态仍由 switch_track selector 负责）。
            if (dblTrackReady) t36AnimateDblTrack(on)
            onCalc(userInitiated = true)
        }
        paintDbl()
        // ⚠ t36：初始化那次 paintDbl() 触发的 isChecked 变化**不播颜色过渡**（冷启动不该有一段淡入）。
        //   在首帧后再开门：否则恢复的 dbl=true 会让 App 一打开就演一遍「灰→蓝」。
        dblBtn.post {
            dblTrackBg = 0
            dblTrackStroke = 0
            dblBtn.setTrackResource(R.drawable.switch_track)   // 首帧由 selector 直接给终态
            dblBtn.trackDrawable?.setState(dblBtn.drawableState)
            dblTrackReady = true
        }
        // 稿§1.1 保存数据按钮:默认disabled,点击落库暂存结果,1.6s后文案复原
        saveBtn = calcPage.findViewById(R.id.save_btn)
        // ⚠ 2026-10-03「清空数据」按钮：用户要求「计算界面也要有个清空数据的功能，
        //   用来删除保存了的数据」。范围=**当前品种**、**无二次确认**（用户裁定）。
        //   ⚠ 只删当前品种是为了保住「按品种关联」那套能力 —— 删全部会把别的品种的参数一起清掉。
        // ⚠ 2026-10-05 保留句柄：[clearCurrentSymRecs] 的结果提示要写在**这个**按钮上。
        clearRecBtn = calcPage.findViewById(R.id.clear_rec_btn)
        clearRecBtn.setOnClickListener { clearCurrentSymRecs() }
        // 稿 L833 `const [st,setSt]=React.useState('ok')` ⟹ **首屏默认就是「有结果」**,
        // 不是空态。改前这里是 t169 的 `idle()`(冷启动没人打字,TextWatcher 不触发,
        // calc_idle_hint 停在 XML 默认的 gone), 于是首屏永远显示「输入参数后回车计算」。
        // 稿 L840 `useState(P0)` 让参数一上来就合法(t363 已把 P0 预填进 XML),
        // 所以这里**直接算一次**, 不是只切可见性。
        //
        // 位置在所有 findViewById 之后, onCalc() 依赖的字段都已经绑好; 前面也没有
        // 参数恢复会跟它抢(recLoad 读的是记录列表, restoreCalc 只在点记录时走)。
        //
        // ⚠ try/catch 不是防御性编程的装饰, 是**必须的**: onCalc() 读的是用户可见的
        // 输入框, 万一将来某个框的默认值被删掉, 它会抛 IllegalArgumentException,
        // 而这行在 onCreate 里 —— 未捕获就是**开不了机**。
        // 兜底的口径是「最差退回今天的空态」, 绝不能让首屏算账把整个 App 带走。
        try {
            onCalc()
        } catch (t: Throwable) {
            idle()
        }
        // 稿 L740-746:未保存 = 主色实底无边框;已保存 = 透明底 + 1px 边框 + 次级色。
        // 运行时构造而不是新加 drawable 文件:两态写在代码里,一眼看得见,也不动 res/drawable/**。
        // 还原用「原始背景」这个已解析好的 Drawable 实例,不在代码里复述资源名:
        // page_calc.xml 里 save_btn 的 android:background = @drawable/save_btn_bg(shape,10dp 圆角 = r-input),
        // 实色是 ?attr/colorPrimary(稿 L83 浅色 ac = oklch(0.495 0.165 292) = #6749B6)。
        // ⚠ 2026-11-04 恢复：用户要的就是「按钮自己变成状态文案」这一形态
        //   （原话：「我说的是要像这样字保存」——要的是『已保存 2 条』『已删除 2 条』那种显示）。
        //   上一版把它挪去状态行/Toast 是**理解错了**，这里按用户给的截图恢复：
        //   保存 → 按钮变「已保存 N 条」+ 白底细边；清空 → 按钮变「已删除 N 条」。1.6s 后复原。
        //   ⚠ 真正的要求是后半句：「要真的保存或删除才行」——落库/删库都已实测（见下）。
        // ⚠⚠ 2026-11-04 用户报「保存/删除之后按钮上的文案不符合逻辑」：
        //   改前点「保存数据」→ 按钮自己变成「已保存 N 条」并**换掉底色**（透明+细边），
        //   点「清空数据」→ 同理变成「已删除 N 条」。两个问题：
        //     ① **按钮的位置写的是状态**：按钮是「能点的动作」，变成状态后用户不知道还能不能再点
        //        （尤其换了底色，看着像被禁用）；
        //     ② 状态本来就有归属：稿 L670-671 的状态行（`heroSub`）写的正是「● 未保存 / 已保存」，
        //        由 [t105SetDirty] 维护 —— 那才是状态该出现的地方。
        //   ⟹ 现在：**按钮文案与样式恒定**（「保存数据」/「清空数据」），
        //     一次性结果用 Toast 报（「已保存 N 条」/「已删除 N 条」），持续状态走状态行。
        //   ⚠ 实测过「真的存/真的删」：点保存后 `gridcalc_records_v1` 里多一条 `{"sym":"XAUUSD"...}`，
        //     点清空后回到 `[]` —— 行为没变，这次只改反馈形式。
        saveBtnBg0 = saveBtn.background
        saveBtnInk0 = saveBtn.currentTextColor
        saveBtnSaved(false)
        // ⚠ 2026-11-04 用户要「按下又抬起的动画」。
        //   背景：上一轮为了消掉真机按钮四角的黑色三角，我把 Material 的 stateListAnimator 关了、
        //   elevation 归零 —— 顺带把按下时那点"抬起来"的手感也一起拿掉了。
        //   ⟹ 用**自己的缩放动画**补回来（不依赖 elevation，所以不会再引出阴影/方角轮廓）：
        //     按下 0.96 / 90ms 缓出；抬起回 1.0 / 160ms 带轻微回弹（Overshoot 1.1）。
        //   ⚠ 只做 setOnTouchListener 且 **return false**：不能吃掉事件，否则 OnClickListener 不触发。
        fun pressFx(v: android.view.View) {
            v.setOnTouchListener { view, ev ->
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN ->
                        view.animate().scaleX(0.96f).scaleY(0.96f)
                            .setDuration(90)
                            .setInterpolator(android.view.animation.DecelerateInterpolator())
                            .start()
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL ->
                        view.animate().scaleX(1f).scaleY(1f)
                            .setDuration(160)
                            .setInterpolator(android.view.animation.OvershootInterpolator(1.1f))
                            .start()
                }
                false
            }
        }
        pressFx(saveBtn)
        if (::clearRecBtn.isInitialized) pressFx(clearRecBtn)

        saveBtn.setOnClickListener {
            // 稿 L741 的 onClick 是**无条件**的 setSaved(true); App 早退时【静默返回】,
            // 用户以为按钮坏了。审计 B 节 #12 给了两个选项, 这里选「早退给可见反馈」
            // 而不是「假装保存成功」。
            //
            // ⚠⚠ **有意不照稿, 不要再"按稿补回去"**（2026-09-30 t376 钉在这里）：
            //     稿 L741 无条件置「已保存」, 但**没算过的时候根本没有记录可存** ——
            //     那个按钮文案是撒谎。撒谎比不反馈更糟。
            //     现状: 无结果时显示「先算一次再保存」, 1.6s 后复原（t1874 起的机制）。
            //     判据: 文案必须与**真实状态**一致, 不是与稿的原型一致。
            // 复用成功路径那套临时文案机制, 不新增机制、不编造稿里没有的措辞。
            val save = pendingRec ?: run {
                saveBtn.text = "先算一次再保存"
                uiHandler.postDelayed({
                    saveBtn.text = "保存数据"
                    saveBtnSaved(false)
                }, 1600)
                return@setOnClickListener
            }
            save()
            pendingRec = null
            // 稿 L741 逐字「已保存 N 条」——必须带条数,否则用户不知道自己存了什么。
            // ⚠⚠ 2026-11-04 用户第三次裁定：状态要**留在按钮里**，不许 1.6s 后自己掉回「保存数据」
            //   （原话：「我让你在里面显示已保存和已删除，你怎么又给改回去了」）。
            //   ⟹ 这里**不再 postDelayed 复原**；回到「保存数据」的唯一时机是 [idle]：
            //     用户改了参数 ⟹ 屏上结果与已存记录不再自洽 ⟹ 按钮必须重新变成可保存的动作。
            saveBtn.text = "已保存"
            saveBtnSaved(true)
            // t105:保存后清脏 → 状态行「已保存」
            t105SetDirty(false)
            // ⚠ 2026-11-04 用户裁定「肯定要弹回去啊」：按钮显示「已保存」1.6s 后**自动复位**为「保存数据」。
            //   恒驻会把按钮变成一块永久状态牌，之后用户根本点不动（看上去像已存过、不该再存）。
            //   复位条件再加一道 [idle]：万一定时器被别处打断，用户一改参数也会复位。
            uiHandler.postDelayed({
                if (saveBtn.text == "已保存") {
                    saveBtn.text = "保存数据"
                    saveBtnSaved(false)
                }
            }, 1600)
        }
        // ⚠⚠⚠ 2026-10-03 **设置页「数据」整块删除**（用户裁定：「把这个功能取消吧，我不需要了」）。
        //   删掉的是：已记录 N 条 / 占用大小 / 导出 CSV / 清空记录。
        //
        // 【为什么布局与接线必须成对删】
        //   接线是 `settingsPage.findViewById<Button>(R.id.rec_export)` —— **非空断言版**。
        //   节点从布局删掉后 findViewById 返回 null，直接在 onCreate 里 NPE。
        //   ⟹ 这是本文件第二次栽在这类地方（第一次是 calc 屏删 fee/mmr 行，忘了摘接线）。
        //
        // ⚠ **记录本身照旧在写**（保存链路没动），只是没有界面能看/导/清了。
        //
        // ⚠⚠ 无调用点因而删掉的（2026-10-03 清理，**逐个核实过调用数**）：
        //     recLoad()、clearRecords()
        //   ⚠ [exportCsv] **没有删** —— 它仍被 :2225 的 onRequestPermissionsResult 调用。
        //   ⚠ [recPaint] 仍在用（保存后的计数回显走它），未删。
        //   ⟹ 日后恢复界面：重新接 [exportCsv]，并**重写** recLoad / clearRecords。
        themeFollow = settingsPage.findViewById(R.id.theme_follow)
        themeLight = settingsPage.findViewById(R.id.theme_light)
        themeDark = settingsPage.findViewById(R.id.theme_dark)
        themeFollow.setOnClickListener { setMode("follow") }
        themeLight.setOnClickListener { setMode("light") }
        themeDark.setOnClickListener { setMode("dark") }
        paintThemeSeg()

        // ⚠⚠ 2026-10-28 t4 · **设置页「启动时刷新一次」整行移除**（用户裁定）：
        //   开关 `set_auto_sw` 已从 page_settings.xml 删掉 ⟹ 原先这里的
        //   findViewById<Switch> / isChecked 初始化 / setTrackDrawable /
        //   t211PaintSwitch 染色 / OnCheckedChangeListener / PREF_AUTO_REFRESH 写入
        //   全部**成对下线**（节点不存在时 findViewById 返回 null，非空断言版会直接 NPE）。
        //   行为收敛：启动刷新**恒为默认值「开」**，见 onCreate 末尾那次单次刷新。

        savedInstanceState?.let { b ->
            for ((k, e) in inp) b.getString("in_$k")?.let { e.setText(it) }
            b.getString("in_L")?.let { t341SetLev(it.toDoubleOrNull() ?: 1.0) }
            // ⚠ "fee"/"mmr" 不再从 Bundle 读（写入侧已删，见 onSaveInstanceState 处的墓碑）
            dbl = b.getBoolean("dbl", false)
            paintDbl()
        }
        watchKeyboard()
        // v4§7 汇率:App打开取一次;异步到达→行情页说明行+图表重画
        MktData.FX.onReady = {
            runOnUiThread {
                if (::mktPanel.isInitialized) mktPanel.fxArrived()
            }
        }
        MktData.FX.ensure(this)
        // v3.4起行情Tab已删:旧存档的mkt归一到fav
        val startTab = savedInstanceState?.getString("tab")?.takeIf {
            it == "fav" || it == "calc" || it == "setup"
        } ?: "fav"
        // ⚠⚠⚠ 2026-10-03 **必须在这里读回记录**，且必须在所有视图绑定之后。
        //   放早了：recPaint() 会碰 recCount/recSize，那两个 View 在「数据」区块删除后已不再绑定
        //   （虽已被 isInitialized 守卫，但 recs 读不到就永远空 —— 那才是关键）。
        //   放晚了：finishLoad 的回填会早于 recLoad 执行 ⟹ 第一次进来读不到自己的记录。
        recLoad()

        // ④ 启动时刷新一次(真的刷, 不是注释)。两道闸, 缺一不可:
        //   1) 记得住上次看的品种  2) 那个品种现在确实还在
        // ⛔ 首次安装没有"上次看的品种" ⟹ 什么都不做, 绝不让 mkLoad 显示「先输入品种」。
        // ⛔ 只在 onCreate 跑这一次。搬到 onResume 就变成"每次回前台都刷" = 轮询, 硬门禁止。
        // ⚠ 2026-10-28 t4：**原第三道闸「开关开着」已撤掉** —— 设置页那个「启动时刷新一次」
        //   Switch 整行移除（用户裁定），行为收敛为**恒等于默认值「开」**（Oct-28 设置页稿：
        //   「行为恒为默认启动刷新，不再需要暴露给用户」）。旧偏好键 auto_refresh_once 不再读，
        //   曾经把它关掉的设备也会在下次冷启动刷新一次；取数逻辑（mkLoad / setSym）本身一字未动。
        //
        // ⚠⚠⚠ 2026-10-03 **必须放在这里（onCreate 最末），不能更早**。
        //   原来它在 :2458，早于计算页视图绑定（:2671 `calcErr = findViewById(...)`）。
        //   而 mkLoad **命中缓存时是同步的** ⟹ renderStock → finishLoad → restoreCalc
        //   → showErr() 会立刻访问 calcErr ⟹ lateinit 未初始化 ⟹ **启动即闪退**。
        //   实测堆栈：UninitializedPropertyAccessException: lateinit property calcErr
        //             at ActivityThread.performLaunchActivity
        //
        //   ⚠ 触发它的是 :2463 的 `mktPanel.setSym(lastM)` ——
        //     setSym 会置 `fromFav = true`（本轮为「自选进入也回填」加的），
        //     于是这条**启动路径也被算作主动进入**，回填就此在绑定前开跑。
        //   ⟹ 两处改动叠加才炸：视图绑定顺序 + fromFav 语义。单看任一处都发现不了。
        val lastM = mktPanel.lastSymPersisted()
        if (lastM.isNotEmpty()) {
            mktPanel.setSym(lastM)
            mktPanel.mkLoad()
        }
        showTab(startTab)
        // ⚠ 2026-11-02：启动即跑**一拍数据引擎**（自选表 + 当前行情）。
        //   以前冷启动只取"上次看的那个品种"，自选其余行要等 5 分钟后那一拍才有数；
        //   现在第一拍就在启动时打出去，两屏从第一屏起读的是同一批数据。
        //   ⚠ 直接调 autoPull()（不用 autoPullTick）：那个 tick 在前台闸 autoPullOn=false 时
        //     会直接返回，而 onResume 还没跑到 —— 会白丢一拍。
        uiHandler.post { autoPull() }
    }
    /**
     * Static card views use the shared card style, but their parent-chain clipping
     * can suppress the downward elevation shadow on API 28+. Keep the spot-shadow
     * mapping in one runtime owner, matching MktPanel's card treatment.
     */
    private fun paintStaticCardShadows() {
        val cards = listOf(
            calcPage.findViewById<View>(R.id.calc_top_card),
            calcPage.findViewById<View>(R.id.calc_params_card),
            // ⚠ 2026-10-28 t4：四格区（保证金/触发价/网格数量/每格数量）本轮恢复成卡片，
            //   它的抬升与 spot shadow 染色走同一个运行期主人 —— 不登记就会只剩一个描边、
            //   浅色下没有 elev_1（父链 clipChildren 会吃掉向下阴影，这正是本函数存在的理由）。
            calcPage.findViewById<View>(R.id.calc_grid_card),
            calcPage.findViewById<View>(R.id.dbl_row),
            calcPage.findViewById<View>(R.id.calc_hero_box),
            // ⚠ 2026-10-29 t30：这里原有 `mktPage.findViewById<View>(R.id.mkt_metrics_card)` —— **已删**。
            //   行情屏按稿彻底去卡片化（稿 theme.css §6.1 的透明组含 .mk-metrics / .mk-sup / .mk-price …
            //   ⟹ background:transparent + border-radius:0 + box-shadow:none），本函数给它的
            //   12dp 圆角 outline + elev_1 + spot 阴影正是「指标块还浮着一张圆角卡」的来源（实测：
            //   其它四块已平，只有指标块还带一圈圆角阴影）。
            //   ⚠ 同屏其它屏（计算 / 设置）的卡片**照旧**走本函数，别把整段删了。
            settingsPage.findViewById<View>(R.id.settings_theme_card),
            settingsPage.findViewById<View>(R.id.settings_about_card)
        )
        val dark = resolve(mode) == "dark"
        val elevation = if (dark) 0f else resources.getDimension(R.dimen.elev_1)
        val radius = 12f * resources.displayMetrics.density
        for (card in cards) {
            card.post {
                var parent = card.parent
                while (parent is android.view.ViewGroup) {
                    parent.clipChildren = false
                    parent.clipToPadding = false
                    parent = parent.parent
                }
                card.outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, radius)
                    }
                }
                card.elevation = elevation
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    card.outlineAmbientShadowColor = Color.TRANSPARENT
                    card.outlineSpotShadowColor = if (dark) Color.TRANSPARENT else 0x40101828
                }
            }
        }
    }

}
// t102 第1步:字号只认 res/values/dimens.xml 的 4 档(30/21/16/12),
// 代码里不再出现裸数字字号 —— 迁移前散着 14 档、相邻常只差 1sp,眼睛建立不起主次。
// 注意除以 scaledDensity 而不是 density:TextView.textSize 的单位是 **sp**,要跟着用户字号设置缩放。
internal fun t102sp(ctx: android.content.Context, id: Int): Float =
    ctx.resources.getDimension(id) / ctx.resources.displayMetrics.scaledDensity

internal data class LiqLinePresentation(val support2: Double, val exceeded: Boolean)

internal fun secondSupportForLiqLine(supportsBelowCurrent: List<Double>): Double? =
    supportsBelowCurrent.getOrNull(1)

/** The visible line is a projection of current market support, independent of optional lkCap. */
internal fun liquidationLinePresentation(
    liqNow: Double?,
    marketSupport2: Double?
): LiqLinePresentation? {
    val support2 = marketSupport2 ?: return null
    val liquidation = liqNow ?: return null
    if (!support2.isFinite() || !liquidation.isFinite()) return null
    return LiqLinePresentation(support2, liquidation > support2)
}
