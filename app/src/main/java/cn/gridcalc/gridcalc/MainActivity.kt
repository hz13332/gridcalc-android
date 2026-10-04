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
 *    引擎有硬门 `杠杆 × 维持率 ≥ 1 ⟹ 拒绝计算`。
 *    原先维持率写死 1%(0.01) ⟹ 杠杆必须 < 100 ⟹ 上一轮开放的 150 **够不着**，
 *    填 100 以上会被直接拒绝。
 * ⟹ 改为**由杠杆反推**（用户选定方案）：`mmr = 1/杠杆 × 0.9`，
 *    于是 `杠杆 × mmr ≡ 0.9 < 1` **恒成立**，杠杆填到 150 也算得出。
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

    // ④ 稿 L779 的 Switch。真接一个行为, 不是摆设(见 t202 脚本头的三件事)。
    private lateinit var autoSw: android.widget.Switch

    private var tab = "fav"
    private var mode = "follow"

    // ---------- 行情→计算软联动(稿L393-414:LINK/PH) ----------
    private var lkPrev: Pair<Double, Double>? = null // 上次联动写入的一对支撑位(sup1,sup2)
    private var lkCap: Double? = null // 目标爆仓价上限(sup2;×解除后同对不再回写)
    private var lkPh: Double? = null // 上次由机构目标均价写入的最高价(同值不扰手改)
    private lateinit var statLiqCard: View // 爆仓卡(超上限画红框)
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
            programWrite {
                for ((_, e) in inp) e.setText("")
                if (::levText.isInitialized) levText.setText("")
            }
        } catch (_: Throwable) {
        }
    }

    fun restoreCalc(sym: String) {
        try {
            val key = normSym(sym)
            val rec = recs.lastOrNull { normSym(it.optString("sym")) == key }
            if (rec == null) {
                // ③ 清零点:无记录品种**不得置位**,否则该品种的联动会被永久误挡
                restoreGuardSym = null
                idle()
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
            statVals["roe"]!!.text = (if (roe >= 0) "+" else "\u2212") + String.format("%.2f%%", Math.abs(roe))
            statVals["pos"]!!.text = "%.4f".format(rec.optDouble("pos"))
            statVals["sell"]!!.text = fmt(rec.optDouble("sell"))
            showErr(null)
            statLiqCard.background = null
            statVals["liq"]?.setTextColor(attrColor("colorInk"))
            saveIdle() // 稿§1.1 回填场景按钮保持disabled(没有新结果可存)
        } catch (e: Exception) {
            // ⚠⚠⚠ 2026-10-03 **删掉空 catch**。
            //   原来是 `catch (_: Exception) {}` —— 吞掉一切且不打日志。
            //   后果：回填链上任何一步抛异常，界面上只表现为「**什么都没发生**」，
            //   而日志里一条线索都没有。用户实报「二次点进去没有出来」，查了半天才定位到这里。
            // ⟹ 现在**记日志 + 在屏上报错**，让它可见。
            android.util.Log.e("RESTORE", "restoreCalc 失败 sym=" + normSym(sym), e)
            // 失败必须把闸放开，否则该品种的联动会被永久误挡（restoreGuardSym 已置位）
            restoreGuardSym = null
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

    /**
     * ④ 设置页开关配色, 稿 L266-272 / 稿 L869-875(Switch)逐条:
     *   开 = 轨道 var(ac) 决策紫**实心** + thumb = ACC(= onFill, 每套主题各一个值)
     *   关 = 轨道 var(line) 中性线色     + thumb var(ink3) 即 colorFaint
     *
     * B-29 改前: 开态轨道是 colorPrimary 压到 alpha 90(= 35% 不透明), 真机上是淡紫,
     * 不是稿的实心决策紫; 关态轨道也是同一条淡紫, 而稿 L268 的关态轨道是中性灰 var(line)。
     * 也就是说「开关打开 = 决策紫」这条硬规则只对了色相, 没对明度。
     *
     * t10 (2026-10-02) ③ 改 thumb: 开态原来取 `colorOnPrimary`, 而那个 token 两套主题
     * 都是**纯白**(themes.xml L31 / L66) ⟹ 深色下 thumb 渲染成 #FAFAFA。
     * 照稿改取 `draftOnFill()`: 深色 → 稿 L99 的 oklch(0.160 0.012 265) = **#0B0D13**。
     * **没有改 colorOnPrimary 本身** —— 它是 9 处共享 token, 理由见 draftOnFill() 的注释。
     */
    private fun t211PaintSwitch(sw: android.widget.Switch, on: Boolean) {
        sw.thumbTintList = android.content.res.ColorStateList.valueOf(
            if (on) draftOnFill() else attrColor("colorFaint"))
        // t10 (2026-10-02) ① **删掉 trackTintList**: 轨道两态改由 R.drawable.switch_track
        // 这个 selector 自带(根因见该文件头)。改前这里设的是不带 alpha 的实色 colorPrimary,
        // 真机却渲染成 ~30% 不透明的淡紫; 而同一 API 的 thumbTintList 是生效的
        // ⟹ 病在轨道 drawable, 只能在 drawable 侧修, 光调 tint 治不了。
        // 删掉它也是为了「同一件事只留一个主人」: 轨道色此后只有 switch_track.xml 一个来源,
        // 不会再出现 XML 与 Kotlin 各写一份、改一处忘一处的分叉。
        // 取法与本仓既有写法一致(FavPanel.kt:1582 / MktPanel.kt:392)。
    }

    private fun prefs() = getSharedPreferences("gridcalc", Context.MODE_PRIVATE)

    // ④ 「启动时刷新一次」用的两个键(与 prefs() 同一个 SP 文件)
    private val PREF_AUTO_REFRESH = "auto_refresh_once"
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
        //   ⚠ **无条件 drop**，不按格距判断（用户选定「就是这样」）：
        //     触发价正好落在某档上时，那一档既不在 sells 也不在 buys，drop 不会额外吃掉它。
        //   ⚠ 这一行直接决定建仓规模（q0 = m × q），是爆仓价最大的单一因子，别随手改。
        val sells = lines.filter { it > po + eps }.drop(1)
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


    /** 稿 L662 {st!=='idle'&& ( … )}: idle 时结果区整块不渲染。 */
    private fun t227SetResultArea(vis: Boolean) {
        val v = if (vis) View.VISIBLE else View.GONE
        if (::statTiles.isInitialized) statTiles.visibility = v
        // t28 B4: 四格的上边线与 statTiles 同一生灭(稿 L675 只在 !over 渲染四格)
        if (::statTilesTopline.isInitialized) statTilesTopline.visibility = v
        if (::moreToggle.isInitialized) moreToggle.visibility = v
        if (::detToggle.isInitialized) detToggle.visibility = v
        if (::ladderNote.isInitialized) ladderNote.visibility = v
        // t23: 折叠口那一行**本身**与它的上边线也要一起收 —— 见 page_calc.xml 同行注释。
        if (::toggleRow.isInitialized) toggleRow.visibility = v
        if (::toggleRowTopline.isInitialized) toggleRowTopline.visibility = v
        // t351 B-5 窄边界(子 agent 3f483577 报的): 上面只收 statTiles / 两个折叠口 / ladderNote,
        // **不收展开区本体** ⟹ 先点开「展开更多」或「网格明细」、再改参数回到 idle 时,
        // 折叠口已经收起、内容区却还 VISIBLE —— 与「结果区整块不显示」(稿 L662) 矛盾。
        if (::moreBox.isInitialized) moreBox.visibility = View.GONE
        if (::detBox.isInitialized) detBox.visibility = View.GONE
    }

    private fun idle() {
    t304Note(null)   // t304: 回到 idle 态就别再挂着改写提示
        // t141:稿 L662 st!=='idle' —— 未算时**整块不显示**,不是显示一个占位破折号
        // t143:这两个是 lateinit View,绑定在后面的 bindViews 块里;
        // idle() 会先于绑定被调用(TextWatcher),未初始化就访问会抛
        // UninitializedPropertyAccessException。**这里必须守卫**,
        // 否则异常被 L1334/L1342 的 catch(Throwable){} 吞掉,
        // 表现就是「回车按了没反应、logcat 什么都没有」—— t121 同款。
        if (::calcHeroBox.isInitialized) calcHeroBox.visibility = View.GONE
        if (::calcIdleHint.isInitialized) {
            calcIdleHint.visibility = View.VISIBLE
        } else {
            // t31 收尾: 原来这里有 Log.d/Log.w 两条(calcpage 为 #140 真机复验加的探针),
            // t6 验完(t6 三条硬门全过、17 PASS/0 FAIL)后删除。**守卫本身原样保留** ——
            // 它防的是 lateinit 未初始化时访问 calcIdleHint, 至今仍有效, 别顺手删。
        }
        if (::calcOverline.isInitialized) calcOverline.visibility = View.GONE
        if (::calcOverlineTopline.isInitialized) calcOverlineTopline.visibility = View.GONE
        // t153 ⑩:回到未算态要恢复显示(否则 over 之后改任一参数,四格一直是空的)
        // 改前这里是 VISIBLE —— 稿 L662 要求 idle 整块不渲染, 改走统一开关。
        t227SetResultArea(false)
        hero.setTextColor(attrColor("colorInk"))
        hero.text = ""
        heroSub.setTextColor(attrColor("colorPrimary"))
        heroSub.text = ""
        statVals.values.forEach { it.text = "—" }  // t119 ① 同上
        detSum.setTextColor(attrColor("colorSub"))
        detSum.text = "明细会在计算后显示"
        lastLines = -1   // t167:与 detSum 同处一块;无明细就不报行数
        t229SyncLadderNote()
        rows.removeAllViews()
        // 稿resetIdle:清报错行+爆仓卡红框(.stat.bad remove),爆仓价值色回墨
        showErr(null)
        if (::statLiqCard.isInitialized) statLiqCard.background = null
        statVals["liq"]?.setTextColor(attrColor("colorInk"))
        // §1.1 失败/清场场景:待存结果作废,按钮回disabled
        saveIdle()
    }

    /** 网格数量是否被你手改过。true ⟹ 不再用自动值覆盖。 */
    private var nManual = false

    /** 该品种的**日线**步长阈值（用户裁定「固定用日线阈值」）。取不到时为 null。 */
    private var stepThr: Double? = null

    /**
     * 自动网格数量（用户 2026-10-03 裁定）：
     *   等比网格，最高价 → 最低价，**格距 = 该品种日线步长阈值 s**
     *       r = 1 − s
     *       第 k 格 = 最高价 × r^k
     *       n = floor( ln(ph/pl) ÷ −ln(1−s) )
     *   取下整即用户说的「**步长阈值不足的一格的向下约**」——
     *   最后一格到不了最低价，剩下的空档不足一个步长。
     *
     * @return 取不到步长阈值、或参数不合法时返回 null（调用方保留用户敲的值，不硬算）
     */
    private fun autoGridN(pl: Double, ph: Double): Int? {
        val s = stepThr ?: return null
        if (s <= 0.0 || s >= 1.0) return null
        if (pl <= 0.0 || ph <= pl) return null
        val denom = -Math.log(1.0 - s)
        if (denom <= 0.0) return null
        val raw = Math.log(ph / pl) / denom
        val n = Math.floor(raw).toInt()
        // 网格数量下限 2（与下面 onCalc 的校验同口径：一格不成网格）
        return if (n >= 2) n else null
    }

    /** 取该品种的日线步长阈值，写进 [stepThr]，然后重算一次。
     *  ⚠ 用户裁定「**固定用日线阈值**」—— 不跟行情屏当前选的周/月/季走。
     *  ⚠ 品种取 **行情屏最后看过的那个**（mkt_last_sym，用户裁定第 1 点）。
     *    拿不到 / 币种无源 ⟹ stepThr 保持 null，网格数量退回用户敲的值，不硬算。
     */
    private fun loadStepThr() {
        val sym = try {
            getSharedPreferences("gridcalc", MODE_PRIVATE).getString("mkt_last_sym", "") ?: ""
        } catch (_: Exception) { "" }
        if (sym.isBlank()) { stepThr = null; return }
        MktData.stepThresholdAsync(sym) { t ->
            runOnUiThread {
                stepThr = t?.takeIf { it > 0 && it < 1.0 }
                if (stepThr != null && !nManual) onCalc()
            }
        }
    }

    /**
     * ⚠⚠⚠ 2026-10-03 **步长阈值改为按当前品种取**（用户报「大量交易品种的网格数量没有自动计算」）。
     *
     * 【根因】[autoGridN] 第一行就是 `val s = stepThr ?: return null`，
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
        if (sym.isBlank()) return
        val key = normSym(sym)
        if (stepThrSym == key && stepThr != null) return     // 这个品种已经有了
        MktData.stepThresholdAsync(sym) { t ->
            runOnUiThread {
                // ⚠ 异步回来时可能已经切到别的品种了 ⟹ 只在仍是同品种时写
                if (normSym(curCalcSym()) != key) return@runOnUiThread
                stepThr = t?.takeIf { it > 0 && it < 1.0 }
                stepThrSym = key
                if (stepThr != null && !nManual) onCalc()
            }
        }
    }

    /** 当前计算页对应的品种（用于异步回调判定，避免串台）。 */
    internal fun curCalcSym(): String =
        (if (::mktPanel.isInitialized) mktPanel.curSym() else "") ?: ""

    private var stepThrSym: String = ""

    private fun onCalc() {
        suppressReset = false // v4 D2/D9:真实计算清闸,之后的程序写值照常生效
        restoreGuardSym = null // ② 清零点:点「计算」= 用户明确要基于当前输入重算 → 退出回填态
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
            //       n = floor( ln(ph/pl) ÷ −ln(1−s) )   ← 下整 =「不足一格的向下约」
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
            val liqOverCap = cap != null && r.liq != null && r.liq > cap
            if (liqOverCap) {
                // 提醒行：沿用稿里那条 StateLine，只把文案换成「超过」而非「高于」——
                // 「高于目标值」在语义上也对，但用户说的是「超过爆仓价」，用用户的词。
                if (::calcOverline.isInitialized) calcOverline.visibility = View.VISIBLE
                if (::calcOverlineTopline.isInitialized) calcOverlineTopline.visibility = View.VISIBLE
                markLiqBad()
            }
            // ⚠ 旧行为（idle + 净收益「--」+ 其余结果全部归零 + 藏掉四格与折叠口 + return）
            //   已按用户裁定删除：爆仓价高于目标值是**结论**不是输入非法，归零等于把结论藏起来。
            //   完整理由见上方 liqOverCap 处的注释。
            statLiqCard.background = null
            statVals["liq"]!!.setTextColor(ink)
            // t141:值不带 USD(稿里 USD 在行内单位/见底权益副标);减号用 U+2212 真减号
            if (::calcHeroBox.isInitialized) calcHeroBox.visibility = View.VISIBLE
                t227SetResultArea(true)
if (::calcIdleHint.isInitialized) calcIdleHint.visibility = View.GONE
            // ⚠⚠⚠ 这里**必须条件隐藏** —— 原来是无条件 GONE，而上面 liqOverCap 刚把它
            //   设为 VISIBLE，于是提醒行亮一瞬就被抹掉，用户根本看不到。
            //   （我第一版改动漏了这个，结果自己造了个新 bug：提醒等于没有。）
            if (!liqOverCap) {
                if (::calcOverline.isInitialized) calcOverline.visibility = View.GONE
                if (::calcOverlineTopline.isInitialized) calcOverlineTopline.visibility = View.GONE
            }
            // ⚠ liqOverCap 时爆仓价数字染红（提醒）；否则回墨色
            statVals["liq"]?.setTextColor(if (liqOverCap) red else ink)
            hero.setTextColor(if (r.net >= 0) teal else red)
            hero.text = (if (r.net >= 0) "+" else "\u2212") + fmt(Math.abs(r.net))
            // t102 第4步:hero 副行原来写「收益率 X% · N卖M买 · 见底权益约 Y USD」,
            // 这三样**下方四格里已经全有了**(收益率/初始持仓/卖出总额)——同一信息在一屏出现两遍。
            // 全局硬规则:一屏只允许「用户要的值、状态、能点的操作」,重复一律删。
            // hero 副行现在只留身份提示;真正的值看下面四格。
            // t167 悬案 D:这两行是 t105SetDirty(true) 的逐字复制(同色同文案),
            // 在 onCalc 里绕过了那个已经收敛过的函数 —— 同一件事两个主人。改为调用它。
            t105SetDirty(true)
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
            statVals["eqb"]!!.text = t219fmt2(r.eqBottom)   // t316 #136 稿 L626 用 U+2212 真减号, 原 fmt() 是 ASCII 减号
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
            t105SetDirty(true)
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

    private fun showPanel(title: String, body: String, okText: String, onOk: () -> Unit) {
        val host = findViewById<android.view.ViewGroup>(android.R.id.content)
        val v = LayoutInflater.from(this).inflate(R.layout.panel_clear, host, false)
        v.findViewById<android.widget.TextView>(R.id.panel_title).text = title
        v.findViewById<android.widget.TextView>(R.id.panel_body).text = body
        v.findViewById<android.widget.TextView>(R.id.panel_ok).text = okText
        v.findViewById<android.view.View>(R.id.panel_mask).setOnClickListener {
            host.removeView(v)          // 点遮罩关闭(稿 L802)
        }
        v.findViewById<android.widget.TextView>(R.id.panel_cancel).setOnClickListener {
            host.removeView(v)          // 稿 L807 取消
        }
        v.findViewById<android.widget.TextView>(R.id.panel_ok).setOnClickListener {
            host.removeView(v)
            onOk()
        }
        host.addView(v)
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
        host.addView(ov)
        uiHandler.postDelayed({ if (ov.parent != null) host.removeView(ov) }, 3200)
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

    // 稿.stat.bad:爆仓卡红框(稿1px CSS边框≈density取整像素,圆角8dp)
    private fun markLiqBad() {
        statLiqCard.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.TRANSPARENT)
            setStroke((resources.displayMetrics.density + 0.5f).toInt().coerceAtLeast(1),
                attrColor("colorRed"))
            cornerRadius = resources.displayMetrics.density * 8f
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
    // 稿linkCalc(397-404):支撑位≥2才联动;同一对支撑原样返回不覆盖手改,值变才覆盖;
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

    fun linkCalc(sup: List<Double>) {
        if (sup.size < 2) return
        val s1 = sup[0]
        val s2 = sup[1]
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
    fun linkPhigh(avg: Double, cur: Double) {
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
        // 与 linkCalc 同理:写完按新最高价重算,净收益/四格才与屏上参数一致(稿联动即出结果)。
        // programWrite 已挡住 idle();这里补上「联动后刷新结果」,否则结果区停在旧最高价的口径。
        try { onCalc() } catch (t: Throwable) { /* 联动重算失败不致命 */ }
    }

    // ---------- v4§1 按品种记住计算参数并回填(数据=记录表,同品种只取最新一条) ----------
    // 网格模式:3.7.2无独立控件,记录值回填到状态(重算时继续写回该品种的模式)
    private var gridMode = "geo"

    // 品种归一化:大写 + 4位港股补零到5位(1211→01211)
    private fun normSym(s: String): String {
        val u = s.trim().uppercase(java.util.Locale.US)
        return if (Regex("^\\d{4}$").matches(u)) "0$u" else u
    }

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

    /**
     * t130:按稿 L245-247 重写。
     * 文字:active `fontWeight 600`;未选中 `400` + `--ink3`。
     * 顶条:active 主色;未选中透明。
     * ⚠️ App 的 attr 里**没有 `colorInk3`**,最接近的是 `colorFaint`(最浅文字色),
     *    故未选中用 `colorFaint` —— 语义对应稿的 `--ink3`。见 notes 的偏差说明。
     *
     * ⚠️ t41 修正:选中态字色**不是四个一律 `--ink`**,稿是**分 tab 的**。逐字:
     *   稿 `color: k==='list' ? (cur===k?'var(--ac)':'var(--ink3)')
     *                      : (cur===k?'var(--ink)'  :'var(--ink3)')`
     *   ⟹ **选中「自选」= 紫 `--ac`**(与它自己的顶条同色,表示「你在这」),
     *      行情/计算/设置选中 = `--ink`。
     * 改前这里对四个 tab 都给 `ink`,自选页选中态与稿不符。
     * ⚠️ 顶条本来就是主色、不动 —— 变的只有**文字**那一处。
     *
     * ⚠⚠ 2026-10-03 **用户裁定统一成黑**（原话：「为什么字是紫色的，其他的字都是黑色的」
     *   → 「统一成黑」）。现在四个 tab 选中态字色**一律 `colorInk`**，
     *   `primary` 只剩给**顶条**用（顶条本身四个 tab 都是主色，没动过）。
     *   ⟹ 这**偏离稿** v2.html:846-847 那个 `k==='list'` 三元分支。
     *   ⚠ 上方那段 t41 的说明**描述的是稿的行为、不是现在的 App 行为**，别照它改回去。
     *   ⚠ 回稿：把 `set(tabFavLabel, ..., "fav", ink)` 的 `ink` 换回 `primary` 一处即可。
     */
    private fun paintTabs() {
        val ink = attrColor("colorInk")
        val ink3 = attrColor("colorFaint")
        val primary = attrColor("colorPrimary")
        /** @param sel 选中态字色。稿对不同 tab 给不同值,见上方注释。 */
        fun set(l: TextView, b: View, key: String, sel: Int) {
            val on = tab == key
            l.setTextColor(if (on) sel else ink3)
            l.setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            b.setBackgroundColor(if (on) primary else android.graphics.Color.TRANSPARENT)
        }
        set(tabFavLabel, tabFavBar, "fav", ink)       // ⚠ 改前是 primary(紫)
        set(tabMktLabel, tabMktBar, "mkt", ink)
        set(tabCalcLabel, tabCalcBar, "calc", ink)
        set(tabSetupLabel, tabSetupBar, "setup", ink)
        syncTabBars()
    }

    /**
     * G5 · 底栏指示条按稿 L247 的百分比算宽，不写死 dp。
     *
     * 稿 L247 逐字：`left:'28%', right:'28%'` ⟹ 宽 = tab 宽 × (1−0.28−0.28) = **44%**。
     *
     * ⚠ 改前 XML 写死 `44dp`，并在注释里写「用 44% 宽居中等价实现」——**那句是假的**：
     *   本机屏宽 1080px/density440 = 392.727dp，tab = 392.727/4 = 98.1818dp，
     *   稿的 44% = **43.2000dp**，而写死的是 44dp ⟹ **大了 0.80dp（+1.85%）**。
     *   A.1 复核 agent 独立量到同一结论（bar 实测 44.00dp = 44.815%）。
     *
     * 为什么不用写死 43dp：Android 的 `layout_marginStart/End` **不支持百分比**，
     * 只能走「算」这条路。写死 43dp 只在本机这一档屏宽上成立，换机型又偏；
     * 按**实测 tab 容器宽 × 0.44** 才在任何屏宽上都对。
     *
     * 只在宽度真的变了时才 requestLayout（避免布局回环）。
     */
    /** tab 顶条(紫)与上方灰线的对齐说明 —— 本轮**没有任何上移量**，全靠改布局结构。
     * 几何事实:tabbar 容器 background = tabbar_top_line(layer-list: 1dp colorBorder, gravity=top)
     *   ⟹ 灰线在容器的 **y=0**；改前每个 tab 的 FrameLayout 带 paddingTop=12.4dp，
     *     而 bar 用 layout_gravity="top"（在 FrameLayout 里指**内容区**顶，即 padding 之内）
     *     ⟹ bar 落在 y=12.4dp，比灰线**低 12.4dp** —— 用户说「越来越往下」的原因。
     *
     * 最终做法（**不是**负偏移）:把 paddingTop 从 FrameLayout **挪到标签 TextView 上**
     *   （脚本 .tmp/fix_tabbar.py）⟹ FrameLayout 内容区顶 == 容器顶 == 灰线所在 y，
     *   bar 用 layout_gravity="top" 就**天然**落在灰线上。
     *
     * ⚠ 两条错路，别再试:
     *   ① 负 margin —— Android 不支持（本项目 fav_head 那次负 margin 静默不生效，
     *     uiautomator 仍照报正常 bounds，极易误判成生效了）。
     *   ② translationY="-13.6dp" —— 实测紫条**整个消失**、底部背景色也变了，
     *     截图不可信、无法定位。绘制偏移这条路不可靠。
     *
     * ⚠ 结构改动的连带约束:FrameLayout 的 paddingTop 与标签的 paddingTop **不能同时留**，
     *     否则 12.4 + 12.4 = 24.8dp，标签会比原来低一截。 */

    /**
     * 同步四个 tab 顶条(紫)的宽度与上移量。
     *
     * ⚠⚠ 2026-10-03 **用户裁定改语义**：顶条由「tab 宽 × 44% 居中」改为**整条铺满 tab 宽**。
     *   用户原话：「为什么行情上面的紫色条不一样，我需要覆盖在上划线的形式」
     *   改前 w = tabFav.width * 0.44f（照稿 L247 `left:'28%', right:'28%'`），屏上是一小截短紫条。
     *   ⟹ 改后 w = tabFav.width 全宽，且 activity_main.xml 里四个 bar 的
     *      layout_gravity 由 "top|center_horizontal" 改为 "top"（去掉居中，靠满宽铺）。
        //   ⚠ 这**偏离稿**：稿 L247 明确是 28%/28% 内缩。产品裁定，不是移植对齐。
     *      要回稿：把这里的全宽换回 tabFav.width * 0.44f、四个 gravity 改回
     *      "top|center_horizontal"、XML 宽度改回 42dp。
     *   ⚠ 上移量(TAB_BAR_LIFT_DP)同样偏离稿：稿的顶条本就在 tab 容器**内**，
     *      没有与上边框「重合」这回事。
     */
    // ⚠ 2026-10-03：上移**不再用 translationY**。试过 translationY="-13.6dp"，
     //   结果紫条整个消失、且底部背景色都变了（当时抓的截图不可信，无法定位）——
     //   绘制偏移这条路**不可靠，别再回头试**。
     //   现在改成把 paddingTop 从 tab 的 FrameLayout **挪到标签 TextView 上**
     //   （脚本 .tmp/fix_tabbar.py 干的），于是 FrameLayout 内容区顶 == 容器顶 == 灰线所在 y，
     //   bar 用 layout_gravity="top" 就**天然**落在灰线上，不需要任何负偏移。
     //   ⚠ Android 不支持负 margin（本项目 fav_head 那次已踩过：负 margin 静默不生效，
     //     uiautomator 还照报正常 bounds，极易误判为"生效了"）。
     // ⚠ 改结构的连带点：FrameLayout 的 paddingTop 与标签的 paddingTop **不能同时留**，
     //     否则 12.4 + 12.4 = 24.8dp，标签会比原来低一截。

    /** 同步四个 tab 顶条(紫)的宽度。 */
    private fun syncTabBars() {
        val w = tabFav.width
        if (w <= 0) return                      // 还没测量出来，等 OnLayoutChange 兜底
        for (b in listOf(tabFavBar, tabMktBar, tabCalcBar, tabSetupBar)) {
            val lp = b.layoutParams
            if (lp.width != w) { lp.width = w; b.layoutParams = lp }
        }
    }

    /**
     * 稿 L254-264 Seg 的两态, 稿 L260-261 逐字:
     *   选中 = 决策紫实底 + ACC 字 + 字重 600
     *   未选中 = 透明底 + ink2 字 + 字重 400
     *
     * B-29 改前用 btn_primary / btn_ghost: 那两个 drawable 各自带圆角 8,
     * btn_ghost 还另带 1px 描边, 再叠上 XML 里段间的 4dp 缝 —— 真机是三个并排小按钮。
     * 稿是**一个**容器描边 + borderRadius 8 + overflow:hidden 让三段相接(新 drawable
     * seg_frame / seg_sel / seg_unsel, 圆角与裁切由 page_settings.xml 的
     * background + clipToOutline 负责)。
     * ⚠ themeFollow/Light/Dark 声明成 View, 强转 Button 只在这一处发生,
     *   把 XML 里的 <Button> 换成别的类型会 ClassCastException。
     */
    private fun paintThemeSeg() {
        val pairs = listOf(themeFollow to "follow", themeLight to "light", themeDark to "dark")
        // t10 (2026-10-02) ② 照稿 L864 的 ACC: 稿的 ACC = THEMES[theme].v.onFill,
        //   浅 #fff / 深 oklch(0.160 0.012 265)=#0B0D13, **每套主题各一个值**(稿 L88 / L99)。
        // 改前这里取共享 token colorOnPrimary, 两套主题都是纯白 ⟹ 深色下「深色」二字
        //   是 #FFFFFF 压 #987DEF = 3.22:1, 不过 AA 4.5; 稿的深墨是 6.03:1。
        // ⚠ 只在这一处局部取 onFill, **没有改 colorOnPrimary**(9 处共享, 见 draftOnFill())。
        val onPrimary = draftOnFill()
        val ink2 = attrColor("colorSub")
        for ((v, m) in pairs) {
            val btn = v as android.widget.Button
            val on = mode == m
            btn.setBackgroundResource(if (on) R.drawable.seg_sel else R.drawable.seg_unsel)
            btn.setTextColor(if (on) onPrimary else ink2)
            btn.setTypeface(null, if (on) android.graphics.Typeface.BOLD
                              else android.graphics.Typeface.NORMAL)
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
     * t135 (1):方向改**填充分段**。按稿 L254-262 的 `Seg` 真实实现:
     *   容器:inline-flex + `border 1px --line` + `borderRadius 8` + `overflow hidden`
     *   段内:`padding sm?'7px 11px'` + `fontSize sm?T.note`
     *   选中:`background var(--ac)` + 字重 600
     *   未选中:透明底 + 字重 400
     *
     * t134 之前这里**只切 textColor**,所以是「纯紫字无底色」——
     * 那是 t134 卡住的原因(要改 Kotlin)。本轮加上 background 与字重。
     */
    private fun paintSide() {
        if (!::sideLong.isInitialized) return
        val primary = attrColor("colorPrimary")
        // ⚠ t11 (2026-10-02) 照稿改 D1：选中段文字原来取共享 token `colorOnPrimary`，
        // 而那个 token 两套主题都是纯白 ⟹ 深色下「做多/做空」选中段是白字压 #987DEF，
        // **只有 3.22:1，不过 AA 4.5**。与 t10 在设置屏 Seg 修的是**完全同一条缺陷**。
        // 照稿改取 `draftOnFill()`：深色 → 稿 L99 的 oklch(0.160 0.012 265) = **#0B0D13**
        // ⟹ 6.03:1 达标（稿 L97-98 原话：「紫底配白字在这里恰好选错了方向」）。
        // **没有改 colorOnPrimary 本身** —— 它是 9 处共享 token，理由见 draftOnFill() 注释。
        // 浅色走 draftOnFill 的 else 分支，值仍是 colorOnPrimary(#FFFFFF) ⟹ **浅色零变化**。
        val onPrimary = draftOnFill()
        val ink2 = attrColor("colorSub")
        val padH = (11 * resources.displayMetrics.density).toInt()
        val padV = (7 * resources.displayMetrics.density).toInt()
        fun seg(v: TextView, on: Boolean) {
            v.setTextColor(if (on) onPrimary else ink2)
            v.setTypeface(null, if (on) android.graphics.Typeface.BOLD
                              else android.graphics.Typeface.NORMAL)
            v.setPadding(padH, padV, padH, padV)
            // 稿 L256-264 逐字: 容器 `inline-flex` + `border:1px var(--line)`
            //   + `borderRadius:8` + `overflow:hidden`, **段内只有背景色**:
            //   `background: val===o[0] ? 'var(--ac)' : 'transparent'`, 没有描边没有圆角。
            // 所以段只画**一块纯色矩形**; 描边、圆角、以及「选中段内侧两角被切直」
            // 全部由容器的 seg_box + clipToOutline(= overflow:hidden) 负责。
            //
            // ⚠ 改前这里给**每一段**都画了 1px 描边 + 8dp 圆角, 而容器那层
            //   (seg_box) 当时因为 XML 标签提前闭合**一直没生效** —— 所以看着是对的。
            //   容器修好之后若不改这里, 就变成**两层描边 + 两套圆角**。
            //   这也解释了为什么上一轮的症状是「两个独立药丸中间有缝」(A.4 复核 #92)。
            v.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor(if (on) primary else android.graphics.Color.TRANSPARENT)
            }
        }
        seg(sideLong, !sideShortMode)
        // ⚠ 2026-10-03 用户裁定删「做空」：side_short 节点已不存在 ⟹ 这一行连同
        //   `sideShort` 字段一起删了。sideShortMode 与整套做空公式仍在，只是屏上进不去。
        //   ⚠ 若日后要把做空加回来：恢复 page_calc.xml 的 side_short TextView +
        //     MainActivity 的 `sideShort` 字段与 findViewById + 这一行 seg(...)，三处缺一即崩。
    }

    private fun paintDbl() {
        if (!::dblBtn.isInitialized) return
        dblBtn.isChecked = dbl   // t104:Button 配色 -> Switch 勾选
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
    override fun onResume() {
        super.onResume()
        MktData.FX.ensure(this)
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
     * #99 收尾 · 稿 L603 的编辑态宽 92px, 获焦时顺带收起该行单位。
     *
     * 稿 L592-614 NumField 逐字(t13 回稿核过的原文, 不是转述):
     *   L594  整行 onClick 调 setEditing(editing===k?null:k) —— 切编辑态是**点一下 toggle**
     *        一个 React state, 不是 focus 事件; L602 另有 onBlur 收起。
     *   L597  按 editing===k 三元切两套 DOM。
     *   L598  编辑态 = 只有一个 input 元素;
     *   L603  它的 style 里 width:92 / marginLeft:auto / textAlign:right / 圆角 6
     *        —— **编辑态没有单位**。
     *   L609  单位是**非编辑态**才出现的另一个 span(note 号字 + 3px 间距)。
     *
     * ⚠ **这里是近似, 不是同构, 别当成已经按稿实现了**:
     *   App 侧只有**一个常驻 EditText**, 没有稿那种「点开才变可写」的只读态,
     *   所以拿 focus 事件当稿的 editing: 获焦 = 进编辑态, 失焦 = 退出。
     *   代价与依据, 说清楚:
     *     - 稿没点之前那一行是只读 span; App 一直可写。这是 t105「回车才算」与
     *       软键盘流程成立的前提, 改成只读态是比这一条大得多的改动, 本轮不做。
     *     - 稿一次只编辑一个字段(editing 是个 key); App 靠系统焦点天然一次一个,
     *       点另一个框时前一个先失焦还原, 视觉结果与稿切换一致。
     *     - 92 是稿的 CSS px; 本页其余尺寸也是按 dp 一套口径取的, 这里同样按 dp。
     *
     * 行富余宽度的去处: 稿靠 marginLeft:auto 把输入框顶到右缘; Android 的
     * layout_marginStart 没有 auto, 所以获焦时把 weight 让给**行首标签**
     * (标签 0dp + weight 1), 失焦时原样还回输入框。标签文字左对齐, 加宽后视觉不变。
     *
     * 为什么非编辑态不收紧宽度: 常态要 8 行并排塞进两列, 撑满才排得下;
     * 92dp 只在获焦那一个框上生效, 也就是本条要修的那 6.25dp 缺口。
     *
     * 单位 TextView 在 page_calc.xml 里**没有 id**, 所以按**行内相邻位置**取
     * (本框的下一个兄弟), 不新增 id、不加 lateinit(t147 栽过 lateinit 崩)。
     * 「网格数量」那行本来就没有单位, 取不到就只调宽度, 不报错。
     * 两态几何是**硬编码的不变量**, 失焦时无条件写回常态, **不读也不信 XML 的当前值**:
     *   非编辑态: 本框 0dp + weight 1(撑满), 标签 wrap_content + weight 0
     *   编辑态:   本框 92dp + weight 0,     标签 0dp + weight 1(吃富余, 把框顶到右缘)
     * 理由(队长裁定): 「撑满」是**硬布局要求**不是偏好 —— 8 行要并排塞进两列。
     * 拿 XML 的当前值当还原基准, 等于把防线的地基交给最可能被人改错的那一处;
     * 在这里复述一次不是「把 XML 抄一遍」, 是**刻意的加固**: 即便有人误把
     * 92dp 写进 XML, 非编辑态也不会跟着坏。稿 L603 的 92 是**运行期编辑态**的宽度,
     * 本来就不该出现在 XML 里(page_calc.xml 那一侧另有一道注释警告)。
     */
    private fun t429EditWidth92(edit: EditText, dp: Int? = null) {
        val row = edit.parent as? android.view.ViewGroup ?: return
        val i = row.indexOfChild(edit)
        if (i <= 0) return
        val label = row.getChildAt(i - 1) as? TextView ?: return
        val unit = row.getChildAt(i + 1) as? TextView
        val eLp = edit.layoutParams as? LinearLayout.LayoutParams ?: return
        val lLp = label.layoutParams as? LinearLayout.LayoutParams ?: return
        val w92 = (92 * edit.resources.displayMetrics.density).toInt()
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
        edit.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                unit?.visibility = View.GONE
                eLp.width = w92
                eLp.weight = 0f
                lLp.width = 0
                lLp.weight = 1f
            } else {
                unit?.visibility = View.VISIBLE
                eLp.width = 0
                eLp.weight = 1f
                lLp.width = wrap
                lLp.weight = 0f
            }
            edit.layoutParams = eLp
            label.layoutParams = lLp
            // ⚠ 2026-10-02 t7：失焦时按稿 L2201 把显示排成「补位 + 千分位」
            // （12500 → 12,500.00、1 → 1.00）。**只能挂在这一个 listener 里** ——
            // setOnFocusChangeListener 是【覆盖式】的，再挂第二个会把上面那段
            // 编辑态宽度换算整个顶掉，那正是 t429 防了一晚上的事。
            if (!hasFocus) t7formatField(edit, dp)
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
        // 指示条按稿 L247 的 44% 算宽；首次进入时 tab 还没测量，
        // 故挂一个布局监听兜底触发一次 syncTabBars()（measure 完成后自然回调）。
        // ⚠ 这个 listener 的签名返回 **void**, 不是 Int:
        //   javap 'android.view.View$OnLayoutChangeListener' 读 platforms/android-36/android.jar,
        //   得到的是 `void onLayoutChange(View, int x8)`, 框架根本不取返回值。
        //   t426 在这里写的「lambda 必须返回 Int」是**错的**; 照那个说法写的
        //   `if (…) v.requestLayout() else 0`, 那个 `0` 是死代码
        //   (kotlinc 报 The expression is unused)。t427 按真实签名改成什么都不返回,
        //   顺带去掉那次多余的 requestLayout:
        //   syncTabBars() 真改了宽度时 setLayoutParams 自己会向上请求。
        //   (t426 那次编译事故与本签名无关; 老坑另算:
        //    `gradlew | Select-String` 管道的退出码不能当 gradle 的判据, 要看 `e: ` 与 BUILD FAILED。)
        findViewById<View>(R.id.tab_fav)?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            syncTabBars()
        }

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
        calcPage = inf.inflate(R.layout.page_calc, body, false)
        settingsPage = inf.inflate(R.layout.page_settings, body, false)
        mktPage = inf.inflate(R.layout.page_mkt, body, false)
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
        mktPanel.onDistCommitted = { favPanel.refreshFavDist() }

        // ④ 启动时刷新一次 —— **已移到 onCreate 末尾**，见那里的 autoRefreshOnce()。
        //   （原来就放在这里，但它会同步走到 restoreCalc，而计算页的视图那时还没绑定。）
        // t201 ⑧:原本这里给 mkt_back(「‹」返回按钮)挂 setOnClickListener, 已随该按钮一起删掉 ——
        // 稿 L522 的 AppBar 里只有标题。系统返回键在下面 OnBackPressedDispatcher, 未动。
        // 稿⑦返回键OnBackPressedDispatcher:仅行情详情可见时拦回自选列表,根Tab默认finish()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
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
                    onCalc()   // t143:不再吞异常(t121 教训);出错要能看见
                    try { hideKeyboard(v) } catch (t: Throwable) { }
                    true
                } else false
            }
            e.setOnKeyListener { v, keyCode, ev ->
                if (keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                    ev?.action == android.view.KeyEvent.ACTION_DOWN) {
                    onCalc()   // t143:不再吞异常(t121 教训);出错要能看见
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
                    if (stepThr == null) return     // 还没自动算 ⟹ 不判
                    nManual = true
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
        statVals["liq"] = calcPage.findViewById(R.id.stat_liq)
        statVals["roe"] = calcPage.findViewById(R.id.stat_roe)
        statVals["pos"] = calcPage.findViewById(R.id.stat_pos)
        statVals["sell"] = calcPage.findViewById(R.id.stat_sell)
        // t104:一行四格里的 买格·卖格 / 见底权益,以及展开更多的 建仓成本/计价币种/美元汇率
        statVals["grids"] = calcPage.findViewById(R.id.stat_grids)
        statVals["eqb"] = calcPage.findViewById(R.id.stat_eqb)
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
                val s = e?.toString()?.trim().orEmpty()
                if (s.isEmpty()) return                      // 中间态：不算，等下一个字符
                val d = s.toDoubleOrNull() ?: return
                if (d < 1.0 || d > LEV_MAX) {
                    // 越界立刻改写成夹取值（会再次触发本回调，第二次落在界内即停）
                    levText.setText(if (d < 1.0) "1" else LEV_MAX.toInt().toString())
                    levText.setSelection(levText.text.length)
                    return
                }
                onCalc()                                     // 界内：只重算，不回写
            }
        })
        // ⚠ 这里不调 t341SetLev：输入框的值**已经**是用户敲进来的值，
        //   再回写一遍只会和 TextWatcher 绕。回车只负责收起键盘 + 收尾算一次。
        levText.setOnEditorActionListener { v, _, _ ->
            (v as? android.view.inputmethod.InputMethodManager)?.let { }
            v.clearFocus()
            onCalc()
            true
        }
        // ⚠ 滑杆的 findViewById / setOnSeekBarChangeListener 已随节点删除一并移除。

        // 做多/做空:当前项高亮(稿:Segmented 两段)
        // ⚠ 2026-10-03 用户裁定删「做空」：side_short 节点已删 ⟹ 这里不能再绑它（会 NPE）。
        //   做空公式本身仍在，屏上只是进不去，默认恒为做多。
        sideLong.setOnClickListener { sideShortMode = false; paintSide(); onCalc() }
        paintSide()
        // 展开更多 / 网格明细:两个折叠区
        moreToggle.setOnClickListener {
            val vis = moreBox.visibility != View.VISIBLE
            moreBox.visibility = if (vis) View.VISIBLE else View.GONE
            // t167:「收起」二字归 ladder_note 独占,more_toggle 只报自己的名字
        moreToggle.text = "展开更多"
        }
        detToggle.setOnClickListener {
            val vis = detBox.visibility != View.VISIBLE
            detBox.visibility = if (vis) View.VISIBLE else View.GONE
        t229SyncLadderNote()
        }
        detSum = calcPage.findViewById(R.id.det_sum)
        rows = calcPage.findViewById(R.id.rows)
        // 稿③LINK爆仓上限卡:第二行灰字「目标上限 X」+×解除
        // (稿capX: LKcap=null;paintCap;去红;清错;calc())
        statLiqCard = calcPage.findViewById(R.id.stat_liq_card)
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
            onCalc()
        }

        dblBtn = calcPage.findViewById(R.id.dbl_btn)  // t104:文字按钮 -> Switch
        dblBtn.setOnCheckedChangeListener { _, on ->
            if (dbl == on) return@setOnCheckedChangeListener
            dbl = on
            paintDbl()
            onCalc()
        }
        paintDbl()
        // 稿§1.1 保存数据按钮:默认disabled,点击落库暂存结果,1.6s后文案复原
        saveBtn = calcPage.findViewById(R.id.save_btn)
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
        // page_calc.xml 里 save_btn 的 android:background = @drawable/save_btn_bg(shape,8dp 圆角),
        // 实色是 ?attr/colorPrimary(稿 L83 浅色 ac = oklch(0.495 0.165 292) = #6749B6)。
        val saveBtnBg0 = saveBtn.background
        val saveBtnInk0 = saveBtn.currentTextColor
        // t201 ⑫ 按稿 L741-746 去掉保存按钮的 disabled 态。
        // 删掉的四处(原行号): L106: saveBtn.isEnabled = false; L441: saveBtn.isEnabled = true; L1512: saveBtn.isEnabled = false; L1544: saveBtn.isEnabled = false
        // 理由: 稿 L741 的 onClick 无条件 setSaved(true), div 无 disabled 概念;
        //       按钮恒可点是稿的行为。两态文案(稿 L745)一条未删。
        fun saveBtnSaved(on: Boolean) {
            if (on) {
                val dm = saveBtn.resources.displayMetrics
                saveBtn.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 8 * dm.density
                    setColor(android.graphics.Color.TRANSPARENT)
                    setStroke(Math.max(1, (1 * dm.density).toInt()),
                        attrColor("colorBorder"))
                }
                saveBtn.setTextColor(attrColor("colorFaint"))
            } else {
                saveBtn.background = saveBtnBg0
                saveBtn.setTextColor(saveBtnInk0)
            }
        }
        saveBtnSaved(false)
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
            // (原来写死「已保存,继续计算后可再存」,既不含条数也不在稿里)
            saveBtn.text = "已保存 ${recs.size} 条"
            saveBtnSaved(true)
            // t105:保存后清脏 → 「● 已保存 · 品种」(只这两个字+品种,不加解释)
            t105SetDirty(false)
            uiHandler.postDelayed({
                saveBtn.text = "保存数据"
                saveBtnSaved(false)
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

        // ④ 稿 L777-L779:「启动时刷新一次」= 可拨的 Switch(on={auto} set={setAuto})
        // 用户裁定「功能要真的实现」⟹ 这个开关真的控制启动时的那一次取数:
        //   开 → onCreate 末尾调 mktPanel.mkLoad() 重新取一次记住的品种
        //   关 → 不自动取, 行情页沿用上次留下的数据(不报错、不空转)
        // 默认开, 对齐稿 L849 useState(true)。
        autoSw = settingsPage.findViewById<android.widget.Switch>(R.id.set_auto_sw)
        autoSw.isChecked = prefs().getBoolean(PREF_AUTO_REFRESH, true)
        // ④ 染成决策紫(稿 L779 的 Switch 与整个稿同一套配色)。平台 Switch 默认青绿,
        //   放在这个紫调界面里像外来件。取色用本类早就在用的 attrColor,
        //   所以【不新增 res/color 资源或目录】—— 上一轮拖这件事的理由是错的。
        // t10 (2026-10-02) ① 轨道换自绘 drawable(稿 L871 的 44x26 圆角胶囊):
        //   平台默认轨道即使 trackTintList 设成实色也只渲染出 ~30% 不透明的淡紫,
        //   而 thumbTintList 同 API 生效 ⟹ 病在 drawable 侧, 只能换轨道本身。
        //   绑定时设一次即可, 之后两态由 selector 的 state_checked 自己切, 不用每次重设。
        autoSw.setTrackDrawable(resources.getDrawable(R.drawable.switch_track, theme))
        t211PaintSwitch(autoSw, autoSw.isChecked)
        autoSw.setOnCheckedChangeListener { _: android.widget.CompoundButton, on: Boolean ->
            prefs().edit().putBoolean(PREF_AUTO_REFRESH, on).apply()
            t211PaintSwitch(autoSw, on)
        }

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

        // ④ 启动时刷新一次(真的刷, 不是注释)。三道闸, 缺一不可:
        //   1) 开关开着  2) 记得住上次看的品种  3) 那个品种现在确实还在
        // ⛔ 首次安装没有"上次看的品种" ⟹ 什么都不做, 绝不让 mkLoad 显示「先输入品种」。
        // ⛔ 只在 onCreate 跑这一次。搬到 onResume 就变成"每次回前台都刷" = 轮询, 硬门禁止。
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
        if (prefs().getBoolean(PREF_AUTO_REFRESH, true)) {
            val lastM = mktPanel.lastSymPersisted()
            if (lastM.isNotEmpty()) {
                mktPanel.setSym(lastM)
                mktPanel.mkLoad()
            }
        }
        showTab(startTab)
    }
}
// t102 第1步:字号只认 res/values/dimens.xml 的 4 档(30/21/16/12),
// 代码里不再出现裸数字字号 —— 迁移前散着 14 档、相邻常只差 1sp,眼睛建立不起主次。
// 注意除以 scaledDensity 而不是 density:TextView.textSize 的单位是 **sp**,要跟着用户字号设置缩放。
internal fun t102sp(ctx: android.content.Context, id: Int): Float =
    ctx.resources.getDimension(id) / ctx.resources.displayMetrics.scaledDensity
