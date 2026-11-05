package cn.gridcalc.gridcalc

import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import java.util.Locale

// ═══════════════ 自选距离窗口：**每品种**（2026-10-12 用户裁定 B 改） ═══════════════
//
// ⚠⚠ 墓碑 · 2026-10-12：本文件顶部原先是一个 `object DistWin` —— **进程内存态的全局
//   冻结窗**（照稿 `var DISTTF=null, DISTN=null`）：点「确认」由 commitDistWin() 冻结成
//   一个值，未确认时 distWin() 跟随行情页周期控件+根数；自选页**所有行共用这一个窗口**。
//   行情 K 线加载(mkLoad)从来不读它（这是 2026-10-03 起就成立的事实，本次未改）。
//
// 【为什么删 —— 用户 2026-10-12 裁定 B 改，原话】
//   「就算我保存了某个品种的窗口周期数据，那也不应该只获取那一个嘛？」「我从行情界面
//    退到自选界面，又再次自动取数值了」。
//   全局窗口造成两件事（每一环都在代码里，不是推测）：
//   ① 改**任一**品种的窗口 → 全局 tf/n 一变 → 自选**全部**行的 FavDist key
//      （`symType|SYM|tf|n`，FavDist.kt:65）同时变 → 5 分钟新鲜缓存全 miss；
//      而那时接线还是 `onDistCommitted → refreshFavDist()`（MainActivity，**全量、无 only**）
//      ⟹ 8~10 个品种错峰各打一次 ⟹ 用户看到「改了 TSLA 的窗口，退回自选又把全部品种取了一遍」。
//   ② 屏上每行显示的窗口与该品种**自己存的** `gridcalc_win_v1[s]` 不一致（设备实测已有
//      TSLA=M|31、IBM=M|32 各自的记录，却全按全局那一个算）—— 数据结构本来就按品种存，
//      只是屏上口径没用它。
//
// 【本次修法】自选页一律读**该品种自己的**窗口：入口 [distWin(sym)] =
//   `gridcalc_win_v1[sym]`（唯一真源；无记录/记录非法回落 `("M" to 20)`，与行情页
//   loadWin() 同一口径）。点「确认」只刷刚确认的那一个品种（见 [onDistCommitted]）。
//
// 【随之失去存在理由的全局机制 —— 逐个取舍，一并删干净，不许留死代码】
//   · `object DistWin` + `commitDistWin()`：唯一读者是**无参** `distWin()`（自选页的
//     repaint / refreshFavDist 两处）。自选页改读每品种后零读者；mkLoad 不读它（见上）。
//   · `distWin()`（无参版）：调用点只有 FavPanel 那两处，均改为 `distWin(品种)`；
//     无参版无调用点即删（留着就是死代码）。
//   · `restoreDistWinOnce()` / `winRestoreTried`：它是给「全局窗口冷启动归零」治病的
//     （症状：冷启动直看自选页，DistWin 空 + 输入框停在布局默认 20 ⟹ 自选页按 20 根
//     查距离，用户看到「过了一段时间又变回来了」）。现在自选页**直接读磁盘真源**
//     gridcalc_win_v1[s]，冷启动本来就取各品种自己的窗口 ⟹ 病灶不存在，恢复失去理由。
//     它顺带的「回填行情页控件」也另有同源路径，不会因此打开「真覆盖」缺口：
//       - mkLoad() 载入品种时 `if (symChanged) applyWinDefaults()`（本文件 ~:1409）；
//         进程内 lastSym 初值必为空 ⟹ 本进程**第一次**载入恒为 symChanged ⟹ 必回填；
//       - saveWin() 在 lastSym 空时早退（~:490）—— 行情页没载入品种时**写不进盘**；
//         想写就必须先载入，而载入那一下就回填了控件；
//       - 启动刷新（2026-10-28 之前是设置页的「启动时刷新一次」开关、默认开；
//         **该开关与 PREF_AUTO_REFRESH 已于 2026-10-28 t4 移除，现在每次冷启动恒执行一次**）
//         启动即 setSym + mkLoad ⟹ applyWinDefaults，控件同样是盘里的值。
//     ⟹ 残余差异只剩一条**极窄且纯展示**的路径：**没有任何「上次看的品种」**（首次安装、
//        或清过 last-sym）+ 冷启动先进自选页 + 再直接点行情 Tab（**不载入品种**）——
//        那时控件显示布局默认 20，而不是「最后确认过的那个品种」的窗口。没有品种就没有
//        「该品种的窗口」可言，且该状态下点「确认」写不进盘（saveWin 早退），**无数据后果**。
//   · `gridcalc_win_confirmed_sym` 指针（`winConfirmedSymKey`）+ `persistConfirmedSym()`
//     + `confirmedSymOnDisk()`：指针的**唯一读者**就是 restoreDistWinOnce（确认制语义：
//     只在点确认时写、只存品种名、绝不存窗口值）。读者没了 ⟹ 写者一并删。
//     ⚠ 磁盘上历史遗留的 `gridcalc_win_confirmed_sym` 键**全工程零读方**：不迁移、
//       不新增写入（再写一次就多一份真源的风险）；`gridcalc_win_v1` 仍是唯一真源。
//
// ⚠⚠ **行情页自己的窗口三件套一字未动**（2026-10-03 三次裁定的净结果）：
//   ① 控件显示 applyWinDefaults()/paintTf()；② 点「确认」落盘 saveWin()；
//   ③ 离开行情页 onLeave() 还原。本次只删上面那批**全局**机制，三件套逐字保留。

// ---------- 行情 VPVR · 面板接线 ----------
// 照搬设计稿 mkLoad/renderMK 的文字侧:品种+K数+周/月/季分段+查看,
// loading/error态,价格块,来源注。拉取放后台线程,结果回主线程。
//   ⚠ 2026-10-28：原注释里的「OHLC 条 / 图例」两块已按方向-B 收口从本页删除。

class MktPanel(private val act: MainActivity, page: View) {

    private val symInp: EditText = page.findViewById(R.id.mkt_sym)
    // ⚠⚠ 2026-11-04 用户裁定「填数字 → 加减控制」：输入框换成步进器（− / 读数 / +）。
    //   读数不可编辑 ⟹ 根数的唯一真源从「输入框文本」改成 **kVal 这个 Int**，
    //   屏上的 mkt_kval 只是它的渲染（[renderK]），两者不会打架。
    private val kMinus: Button = page.findViewById(R.id.mkt_kminus)
    private val kPlus: Button = page.findViewById(R.id.mkt_kplus)
    private val kValT: TextView = page.findViewById(R.id.mkt_kval)

    /** 当前根数（未确认态）。初值 20，进入页面时由 [applyWinDefaults] 覆盖为该品种已确认值。 */
    private var kVal: Int = 20
    private val goBtn: Button = page.findViewById(R.id.mkt_go)
    // 【F-2b · 2026-10-01】周期三键 **W / M / Q**。
    //
    // ⚠️ 「Q 季线」与「M 月线」**数据来源不同**，别把它们当成同一档：
    //   · 其余腿  取月线(东财 klt=103)后 `toQuarterly` **三月合成**
    // 合成出来的是**真正的季 K 线**（开=季首月开、高=三月最高、低=三月最低、
    // 收=季末月收、量=求和），**不是**「拿月线冒充季线」那种静默降级。
    // 详见 MktData.fetchEastmoney 里的 klt 注释（含「兜底 ≠ 等价」那段）。
    //
    // 用户 2026-10-01 裁定：**保留季线**。
    // 拿「稿子做不到」当「App 该砍」正是我们要拆的那类静默降级。
    //
    // 年线：App **没有**（无 tfY，MktData 也无 "Y" 分支）。用户未要求，**未擅自添加**。
    private val tfW: Button = page.findViewById(R.id.mkt_tf_w)
    private val tfM: Button = page.findViewById(R.id.mkt_tf_m)
    private val tfQ: Button = page.findViewById(R.id.mkt_tf_q)

    /** ⚠ 2026-11-04：周期轨道改为自绘药丸的 [SegTrack]（线性布局子类，单测的 LinearLayout 契约不变）。 */
    private val tfRow: SegTrack = page.findViewById(R.id.mkt_tf_row)

    /** 首次 paintTf 不许有入场动画（用户没有触发过任何动作）。 */
    private var tfPillPlaced = false
    private val status: TextView = page.findViewById(R.id.mkt_status)
    // t106 ④ 涨跌额 / ⑥ 交易所·时点
    // ⚠️ 2026-10-02 用户裁定「这两个不要」:OHLC 四格 + Vol 已从 page_mkt.xml 整体删除,
    //   十个字段 oC/hC/lC/cC/labO·labH·labL·labC/volLab/volT 随之作废。
    //   **只删显示层** —— MktInfo 的 o/h/l/c/vol 字段与取数链路原样保留,K 线图仍在用同一批数。
    private val chg: TextView = page.findViewById(R.id.mkt_chg)
    private val venueT: TextView = page.findViewById(R.id.mkt_venue)
    private val timeT: TextView = page.findViewById(R.id.mkt_time)
    // 【F-2c】数量滑杆 kBar 已删（连同布局里的 mkt_kbar 节点）。
    //   ⚠ 2026-11-04：数量**输入框**也已删，根数入口现在**只有**步进器（− / +）与「确认」。
    private val leg: TextView = page.findViewById(R.id.mkt_leg)
    // 2026-10-03 新增:图表下方的支撑价位块(替代原先那两根空分隔线)。
    // 取的是 MktView 已经算好的 info.supShow,与图上标签片**同一个数**,不会两处打架。
    // 排版:上下发丝线 + 「支撑」小节标题 + 标签左/数值右两行,详见 page_mkt.xml 注释。
    private val supBlock: LinearLayout = page.findViewById(R.id.mkt_supblock)
    private val supRow2: LinearLayout = page.findViewById(R.id.mkt_suprow2)
    private val sup1: TextView = page.findViewById(R.id.mkt_sup1)
    private val sup2: TextView = page.findViewById(R.id.mkt_sup2)
    // t102 第3步:源状态行三件套
    private val srcRow: LinearLayout = page.findViewById(R.id.mkt_srcrow)
    private val srcDot: TextView = page.findViewById(R.id.mkt_srcdot)
    private val srcMain: TextView = page.findViewById(R.id.mkt_srcmain)
    private val srcMore: TextView = page.findViewById(R.id.mkt_srcmore)
    private val legDetail: TextView = page.findViewById(R.id.mkt_legdetail)
    /** #33 稿 L582 StateLine 的成功态宿主(稿 L291 前导符号恒为默认档 ·)。 */
    private val stateline: TextView = page.findViewById(R.id.mkt_stateline)
    /** B-34 稿 L291: 前导符独立成段(那个 flexShrink:0 的 span), 与 stateline 之间 gap 8。 */
    private val statelineLead: TextView = page.findViewById(R.id.mkt_stateline_lead)
    // t103:步长阈值行 + 当前阈值(供计算页 linkN 用)
    // ⚠ 2026-10-29 t30：id `mkt_stept` / `mkt_tgt` 现在落在**行容器**（LinearLayout）上，
    //   因为指标项改成了「label 左 / value 右」两段；文本写到 _v / _l 两个新 id。
    //   这样原有 4 + 5 处 `tgt.visibility` / `stepT.visibility` 判据一行都不用改。
    private val stepT: LinearLayout = page.findViewById(R.id.mkt_stept)
    private val stepTL: TextView = page.findViewById(R.id.mkt_stept_l)
    private val stepTV: TextView = page.findViewById(R.id.mkt_stept_v)
    @Volatile var stepTv: Double = 0.0
    @Volatile private var stepSym: String = ""
    // t108 (2) 日期范围独占行已删(稿里没有),srcNote 视图随之移除
    private val winBtn: Button = page.findViewById(R.id.mkt_win)  // 「确认」按钮。
    // ⚠ 墓碑 · 2026-10-12：旧注释写「t107 (3) gone on screen」，**已过期** —— 该按钮现在是
    //   **可见的「确认」**（page_mkt.xml:394-407，无 visibility=gone）。t107 时代它是「▾ 下拉」、
    //   被外层容器挤成 0 高静默消失，那份历史见 page_mkt.xml 的墓碑段。

    // ⚠⚠ 墓碑 · 2026-10-28（方向-B 收口）· **手动拆股 · 前复权 的 UI 已整体删除**。
    //   随 page_mkt.xml 一起下线 8 个绑定：mkt_split_head / mkt_split_sw / mkt_split_box /
    //   mkt_split_list / mkt_split_date / mkt_split_ratio / mkt_split_add / mkt_split_note，
    //   以及开关的 setOnCheckedChangeListener、添加按钮的 click、条目渲染与提示行逻辑。
    //   任务要求「不留空壳 findViewById」⟹ 上一轮那种「节点保留 + 恒 GONE + 早退」的空壳已清掉。
    //   ⚠ 功能侧（前复权换算）在 MktData：`SplitBook.apply` 已是空操作，取数与缓存逻辑未动。
    // ⚠⚠ 2026-11-04 用户裁定：**页标题「行情」整块删除**（底部 Tab 栏已经有「行情」，
    //   页内再写一遍是重复，且它上面还压着一片空白）。稿同步删掉 `.mk-bar` 那一行。
    //   ⟹ 原字段 `titleTx = findViewById(R.id.mkt_title)` 与节点一起下线（不留空壳）。
    //   ⚠ 品种标识不丢：价格卡右上的 `mkt_venue`（`{中文名} {代码} · {时段}`，`t106PaintMeta` 写）承担。
    private val searchTx: TextView = page.findViewById(R.id.mkt_searchtx) // t252 稿 L527: 描边盒右侧的「搜索/收起」
    // t252 稿 L526: 描边盒左侧的品种名。值来自 symInp —— 它是品种的唯一真源(MktPanel L45)。
    private val symName: TextView = page.findViewById(R.id.mkt_symname)

    // ⚠ 2026-10-02 照稿补：价格块右上第三行「实时/收盘价 · 源名」（稿 v2.html:2126-2128）。
    //   实时走决策紫 colorPrimary，收盘/未取到走第三档灰 colorFaint。
    private val srcName: TextView = page.findViewById(R.id.mkt_srcname)
    private val price: TextView = page.findViewById(R.id.mkt_price)
    private val tgt: LinearLayout = page.findViewById(R.id.mkt_tgt)
    private val tgtL: TextView = page.findViewById(R.id.mkt_tgt_l)
    private val tgtV: TextView = page.findViewById(R.id.mkt_tgt_v)
    /** r8 B-8：「指标」小节标题（稿 `四屏-行情页.html:427` 的 `.mk-sec`）；r9 起它是**整卡容器内的卡内元素**，
     *  **恒可见**（卡头恒在），不再承担任何底色 / 圆角 / 抬升。 */
    private val secMetrics: TextView = page.findViewById(R.id.mkt_sec_metrics)
    /** r9（2026-10-20）：指标卡 = **独立整卡**容器（稿 `.mk-metrics`，theme.css:428-438 整卡组）——
     *  底色 / 四角 12dp 圆角 / 抬升**全部归它**，卡内三行只是卡内容。 */
    private val metricsCard: LinearLayout = page.findViewById(R.id.mkt_metrics_card)

    // ── r8 B-1（2026-10-19）· K 线卡头带 ──────────────────────────────────────────
    //   稿 `四屏-行情页.html:322-336`：左「K 线」+「{周期} · {N} 根」。
    //   ⚠ 2026-10-28 方向-B 收口：右侧 `−` / `+` / `Reset` 缩放三键**已整块删除**
    //     （连同 zoomOut / zoomIn / zoomReset 三个绑定与 click 监听），卡头只剩左侧读数。
    //   ⚠ `blkCard` 是**卡片容器**：卡片底/圆角/抬升的落点由 `chart` 移到它（见 paintCardSlices）。
    private val blkCard: LinearLayout = page.findViewById(R.id.mkt_blk_card)
    /** 卡头中段读数：`ks.size`（**实际画出来的根数**，经 MktInfo.count 带回），不是保存的窗口值。 */
    private val blkhM: TextView = page.findViewById(R.id.mkt_blkh_m)

    // ⚠⚠ 墓碑 · 2026-10-28（方向-B 收口）· **OHLC 明细表整卡已删** ⟹ 本节字段与状态随之下线：
    //   ohlcCard / ohlcM / ohlcBody / ohHeadT·O·H·L·C（5 列表头）/ ohSortCol·ohSortDir（排序状态）/
    //   ohRows·ohLastFlag（表体数据）/ ohBaseNames·ohA11y（表头字形与读屏语义）。
    //   配套渲染函数（paintOhlc / rebuildOhRows / paintOhHead / onOhSort / ohHeads / ohNum /
    //   paintOhlcNextFrame）与 init 里的表头接线一并删除 —— 任务要求「不留空壳 findViewById」。
    //   ⚠ 只删显示层：MktInfo 的 o/h/l/c/vol 与取数链路原样保留（K 线图仍用同一批数）。
    // v4§7 美元口径说明行(数据源下方;汇率异步到达后重画)
    // t106 ⑩「美元口径」行已整条删除(稿里没有,是说明性文字)
    private val chart: MktView = page.findViewById(R.id.mkt_chart)
    private val favBtn: Button = page.findViewById(R.id.mkt_fav)
    private val dock: LinearLayout = page.findViewById(R.id.mkt_input_dock)
    private val searchBtn: View = page.findViewById(R.id.mkt_search)
    private val row2: LinearLayout = page.findViewById(R.id.mkt_row2)

    // ⚠ 2026-10-16 t52 · 方向 B 卡片（§2.4-3.2 / §6.1b）：
    //   控制卡 = **一整张** mkt_period_row（周期 + 数量 + 确认）。
    //   ⚠ 2026-10-28：拆股行/编辑区删除后分片语言作废 ⟹ 片间线 mkt_card_divider 一并删除，
    //     控制卡四角全圆；价格卡 / K 线卡是两张**独立卡**。底色与圆角在运行期刷（见 paintCardSlices）。
    private val periodRow: LinearLayout = page.findViewById(R.id.mkt_period_row)
    private val priceCard: LinearLayout = page.findViewById(R.id.mkt_price_card)

    private var winPop: PopupWindow? = null

    // 自选变更(收藏/删除)时通知自选页重绘;由MainActivity接线
    var onFavChanged: (() -> Unit)? = null
    // 点「确认」提交距离窗口后,通知自选页刷新**刚确认的那一个品种**(稿winGo:commit+refresh+收菜单)
    // ⚠ 2026-10-12 裁定 B 改：回调从**无参全量**改成**带品种**（= 点确认那一刻正在看的
    //   lastSym）。窗口改成每品种之后，改谁就只有谁的 FavDist key 会变 ⟹ 只刷那一个品种，
    //   其余行 key 纹丝不动（新鲜缓存照旧命中、零请求）。改前的无参全量刷新配着全局窗口，
    //   正是「改一个 → 全部重取」的病灶（墓碑见文件头 2026-10-12 段）。
    var onDistCommitted: ((String) -> Unit)? = null

    /**
     * 某一轮行情数据**已落到 `MktData.LastChart`**（= 绘制的就是这一批）时通知自选页。
     * 2026-11-02 用户报「行情页拉到新数据，自选页还是旧值」：此前行情页取完就结束，
     * 自选页只能等自己下一轮全量抓取，5 分钟缓存还会把新数据挡在外面。
     * ⚠ 只在 `renderAgg` 成功路径里、`LastChart.put` **之后**触发；不新增任何网络请求。
     */
    var onChartReady: ((String) -> Unit)? = null

    // 稿§4 根数范围 KMIN=15/KMAX=100,越界clamp
    private val KMIN = 15
    private val KMAX = 100
    // 【F-2c · 2026-10-01】删掉数量**滑杆**（`mkt_kbar`）。
    //
    // 用户原话：「我说了不要滑动条了非常的碍事」。同一条指标上并存
    // 一个滑杆和一个输入框，是两个入口指同一个数 —— 本来就该只留可填写那个。
    //
    // ⚠️ 稿 S5 L550 `<Slider val={bars} min={5} max={100}>` 的**下限 5** 与
    //   「K 线根数下限 KMIN=15」是**两件不同的事**，别混：
    //   稿 L507 `const few=bars<15` + L510 文案「K线数量至少填 15」只管拒绝提示与上红，
    //   ⟹ **KMIN 仍是 15，一律不动**。
    //   被删掉的只有滑杆专用的 `KBAR_MIN=5`（它仅 3 处引用，全是滑杆专属：max 换算、
    //   progress 回填、反算 position）—— 随滑杆一并删除。
    //
    // ⚠️ **删滑杆不会让输入框变成死控件**：本文件另有一路已存在的触发 ——
    //   （原「输入框失焦即重算」随 2026-11-04 换成步进器一并作废：提交只走「确认」。）
    //   这条在删滑杆之前就存在，所以根数仍可改、仍生效。
    //   注意 **afterTextChanged 只同步上红、不发请求**（见下）—— 那是刻意的：
    //   若在每次击键都 mkLoad()，打「120」会连发三次请求，正是「并发惊群」那一族的病。
    //   触发只挂在**失焦**这一个事件上，一次编辑一次请求。
    private var tf = "M"
    @Volatile private var reqSeq = 0
    /**
     * 屏上这批 K 线所属的**请求批次代号**（= 把它画上屏那次 mkLoad 的 [reqSeq] 快照）。
     *
     * 【唯一写者】[renderAgg] 里 `chart.setData(show)` **之前**那一行 —— 必须在 setData 之前写：
     *   setData → invalidate → onDraw → `chart.onInfo` 会立刻读它，把「这批数据是谁请求的」
     *   一起交给计算页的联动（`act.linkCalc(sup, shownSeq)`）。
     * 【为什么需要它】清空计算页时记下的屏障只允许拒「清空前那一批」的联动回调；
     *   清空后**重新进入同品种**会产生**新批次**（mkLoad 里 reqSeq 自增），那一轮必须放行。
     *   没有批次代号就只能按品种判断 ⟹ 「清空后同品种再进来」被永久误挡
     *   （第 3 轮 blocker F-SAME-SYM-CLEAR-NEW-GEN）。
     */
    private var shownSeq = 0
    private var lastKs: List<KLine> = emptyList()
    private var lastSrc = ""
    private var lastMkt = ""
    private var lastType = ""
    @Volatile private var lastSym = ""
    // 首家先画注记:非空时状态行显示"POC x（初步·源）",终画前清掉
    private var prelimTag: String? = null
    // v4§1/D4:换品种后「取数完成再回填」的待办标记(取数成功/失败两条路都会消费)
    private var wantRestore = false

    /**
     * 前台定时器专用入口：必须绕过内存 K 线缓存，走 MktData.acquire(refresh=true) 真正刷新。
     * 仍先画磁盘留底，再由新批次覆盖；普通用户切品种/周期/根数仍按 mkLoad() 的缓存语义。
     */
    fun mkLoadQuiet() {
        mkLoad(forceRefresh = true)
    }
    // ⚠ 2026-10-03 「自选主动进入」标记，语义见 [setSym] 的说明。消费后立即清零。
    private var fromFav = false
    /** True after leaving with no confirmed local batch; consumed on re-attach. */
    private var needsConfirmedReload = false
    /** Suppresses auxiliary threshold requests during local confirmed-batch replay. */
    private var localReplay = false
    /** Current historical loader identity; preserved across tab detach/attach. */
    private var requestInFlight = false
    private var inFlightSeq = 0
    private var inFlightTf = ""
    private var inFlightN = 0

    // D4:取数收尾(成功画完图、或已确定失败)之后才回填保存值,保证保存值赢过 linkCalc 的支撑联动
    //
    // ⚠⚠⚠ 2026-10-03 **回填必须推到下一帧，不能在这里同步跑**。
    //
    // 【崩溃堆栈（App 内捕获器抓到的，用户已复现）】
    // ```
    // java.lang.NullPointerException: Attempt to read from field 'int android.view.View.mViewFlags'
    //     on a null object reference in method 'void android.view.ViewGroup.dispatchDraw(Canvas)'
    //   at android.view.ViewGroup.dispatchDraw(ViewGroup.java:4289)
    //   ... ×6 层 drawChild/dispatchDraw ... → ScrollView.draw → ThreadedRenderer
    // ```
    // **栈里一个 App 帧都没有** —— ViewGroup 正被绘制时，它的子 View 数组失效了。
    //
    // 【为什么是这里】`finishLoad` 由 `renderStock/renderCrypto/renderAgg` 调用，
    //   那正是「刚画完行情图」的回调 —— **正处于布局/绘制这一轮的中间**。
    //   `restoreCalc` 里做了大量视图改动：
    //       rows.removeAllViews()   ← 移除子 View
    //       detSum.text / hero.text / heroSub.text
    //       showErr(null)          ← 改 visibility
    //       statVals[...].text      ← 计算页那一整排
    //   在绘制途中改这些 ⟹ RenderThread 手上那份子 View 数组当场作废 ⟹ dispatchDraw 读 null。
    //
    // 【触发条件必须先有记录】没有保存记录时 restoreCalc 走 `rec == null` 提前 return，
    //   一个视图都不碰 ⟹ 不崩。**这解释了用户说的「先保存、再进别的品种才崩」。**
    //
    // 【修法】post 到下一帧，等这一轮的绘制彻底走完再改视图。
    //   ⟹ 不是给 repaint 加防重入（那治不了根），而是**不在绘制回调里改视图**。
    private fun finishLoad(seq: Int) {
        if (seq != reqSeq) return
        if (seq == inFlightSeq) requestInFlight = false
        if (!wantRestore) return
        wantRestore = false
        val sym = lastSym
        // ⚠ post 而非直接调：见上方说明。
        act.window?.decorView?.post { if (seq == reqSeq) act.restoreCalc(sym) }
    }

    // ---------- 品种联想下拉(对标稿子symList) ----------
    private val sugHandler = Handler(Looper.getMainLooper())
    private var sugRunnable: Runnable? = null
    private var sugSeq = 0
    private var sugItems: List<SugItem> = emptyList()
    private var sugSel = -1

    private fun dp(v: Float): Float = act.resources.displayMetrics.density * v

    // #53 稿 L529-541: 展开后是**竖向候选列表**(一行一条: 代码 + 名称), 不是一行输入坞。
    // 稿 L530 那个盒子(marginTop 10 / border 1px / radius 8 / overflow hidden)
    // 直接由 page_mkt.xml 的 mkt_input_dock 承担; 这里只把**真实联想**的结果按稿的行形状铺进去。
    // ⚠ 稿 L531-533 那三条是演示数据 —— 沿用真实联想(与改前同一份 MktSuggest 结果与排序), 不写死。
    //   盒内没有滚动条, 真实联想可能几十条 ⟹ 只截断**显示条数**, 内容与顺序一个不改。
    private val SUG_MAX = 8
    // 元素是 LinearLayout(ViewGroup): paintSugSel 要读它的 childCount/getChildAt,
    // 那两个方法挂在 ViewGroup 上, 声明成 View 会编译不过。
    private val sugRows = ArrayList<ViewGroup>()

    private fun clearSugRows() {
        for (v in sugRows) dock.removeView(v)
        sugRows.clear()
    }

    private fun showSug(items: List<SugItem>, seq: Int) {
        if (seq != sugSeq) return
        clearSugRows()
        if (items.isEmpty()) {
            hideSug()
            return
        }
        sugItems = items
        sugSel = -1
        val shown = items.take(SUG_MAX)
        val p13 = dp(12.4f).toInt()
        for ((i, sug) in shown.withIndex()) {
            val last = i == shown.size - 1
            val row = LinearLayout(act)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(p13, p13, p13, p13)          // 稿 L535 padding 13
            // 稿 L535 borderBottom: 前面的行有, 末行没有(盒子已描边, 末行再画会多一道)
            if (!last) row.setBackgroundResource(R.drawable.fav_row_line)
            row.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            val symTv = TextView(act)
            symTv.text = sug.s
            symTv.textSize = t102sp(act, R.dimen.fs_title)        // 稿 L536 T.title
            symTv.setTypeface(null, android.graphics.Typeface.BOLD)   // 稿 L536 600
            symTv.setTextColor(act.attrColor("colorInk"))
            symTv.maxLines = 1
            val nameTv = TextView(act)
            nameTv.text = sug.tag
            nameTv.textSize = t102sp(act, R.dimen.fs_note)        // 稿 L537 T.note
            nameTv.setTextColor(act.attrColor("colorSub"))
            nameTv.maxLines = 1
            nameTv.ellipsize = android.text.TextUtils.TruncateAt.END
            nameTv.layoutParams = LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(10f).toInt()                   // 稿 L535 gap 10
            }
            row.addView(symTv)
            row.addView(nameTv)
            row.setOnClickListener { pickSug(sug) }
            dock.addView(row)
            sugRows.add(row)
        }
    }

    private fun hideSug() {
        // 防重弹:查看/回车/选词后旧异步回包一律丢弃
        sugSeq++
        sugRunnable?.let { sugHandler.removeCallbacks(it) }
        clearSugRows()
    }

    // 选中行(物理键盘 DPAD 上下键): 实底 + 反白字, 与改前浮层里的选中样式同源。
    // ⚠ 2026-10-16 t52 · F-5 同族站点（交接 §5.1-① 第 3 处）在方向 B 里的口径：
    //   底 = `btn_primary`，t49 已把它换成 **colorAccentFill**（浅 #2A5BD7 / 深 #3D6BE0）
    //   ⟹ 白字压它 浅 **5.87:1** / 深 **4.80:1** ✓，两套都过 AA 4.5。
    //   **不改共享 token colorOnPrimary**（全库 9 处活引用），只在**本绘制处**取它 ——
    //   这正是「on-accent 口径」的落法：换的是**底**（colorPrimary → colorAccentFill），
    //   字色仍是白。改前那套（底 = 深色 colorPrimary #987DEF + 白字 3.22:1）已被 t49 的
    //   token 层换掉，这里**不需要**再分支主题。
    private fun paintSugSel() {
        for (i in sugRows.indices) {
            val v = sugRows[i]
            val on = i == sugSel
            v.setBackgroundResource(
                if (on) R.drawable.btn_primary
                else if (i == sugRows.size - 1) android.R.color.transparent
                else R.drawable.fav_row_line)
            for (j in 0 until v.childCount) {
                val tv = v.getChildAt(j) as? TextView ?: continue
                tv.setTextColor(act.attrColor(
                    if (on) "colorOnPrimary" else if (j == 0) "colorInk" else "colorSub"))
            }
        }
    }

    private fun pickSug(it: SugItem) {
        symInp.setText(it.s)
        symInp.setSelection(it.s.length)
        paintStar()
        hideSug()
        mkLoad()
    }

    // ---------- 自选收藏(对标稿子favBtn) ----------
    fun paintStar() {
        val s = MktData.canonicalSym(symInp.text.toString())
        favBtn.text = if (s.isNotEmpty() && FavStore.isFav(act, s)) "★" else "☆"
    }

    private fun toggleFav() {
        val s = MktData.canonicalSym(symInp.text.toString())
        if (s.isEmpty()) return
        if (FavStore.isFav(act, s)) FavStore.remove(act, s)
        else FavStore.add(act, s, MktData.symType(s))
        paintStar()
        onFavChanged?.invoke()
    }

    // ⑥记录字段(稿MK.sym/MK.typ/mkCurPx):App无标记价→现价取最后一根收盘
    fun curSym(): String = lastSym
    fun curTyp(): String = MktData.symType(lastSym)
    fun curPx(): Double? = lastKs.lastOrNull()?.c

    /**
     * 已发出的最大请求批次代号（只读，计算页清空时取屏障用）。
     *
     * 屏障 = 清空那一刻本值。行情回调携带**它自己那一批**的代号：
     * `批次代号 <= 屏障` ⟹ 清空前那一批（含清空时正在飞的那次请求）⟹ 拒；
     * 清空后重新进入同品种的 mkLoad 会拿到更大的代号 ⟹ 放行（Pl/Ph 回填、自动网格恢复）。
     * ⚠ 只是**读取**代号：本函数不推进它，也不解除任何阻断。
     */
    fun reqSeqNow(): Int = reqSeq

    /** UI-thread identity gate for a loader's immutable symbol/sequence snapshot. */
    private fun loadCurrent(loadSym: String, loadSeq: Int): Boolean =
        loadSeq == reqSeq &&
            MktData.canonicalSym(symInp.text.toString()) == loadSym &&
            lastSym == loadSym

    /** ⚠⚠ 2026-11-04 用户裁定：**页标题「行情」整块删除**（底栏已有同名 tab，页内重复且白占顶部空白）。
     *   下面这些「标题恒为『行情』/ 标题回到 XML 默认值」的描述**只描述当时**；
     *   现状是**本页没有标题节点**（`mkt_title` 与 `titleTx` 已一并下线），品种标识由 `mkt_venue` 承担。
     *
     * ⚠ 2026-10-03 用户裁定「把行情换成该交易品种的名字」：顶栏显示品种名，不再恒为「行情」。
     *
     * 传入的是**空串时回退显示代码** —— 搜索结果那条路径拿不到中文名
     * （MktSuggest 的项只有 s + tag，tag 是「美股/币/韩股」这类市场标签）。
     *
     * ⚠⚠ r8 B-9（2026-10-19）· **本函数不再写顶栏标题**。
     *   沿革：2026-10-03 曾按用户裁定把品种名写进顶栏（当时的实况是「标题 = 品种名/代码」）；
     *   而本文件 `titleTx` 声明处的注释、稿 `四屏-行情页.html:244-247` 的 `<AppBar title="行情"/>`
     *   都写着「标题恒为『行情』」⟹ 三处互相矛盾（报告 §4-R5 实测 dump `text='PLTR'`、墨迹高 11.64dp = 16sp）。
     *   r8 按稿收口：**删掉这里的 `titleTx.text = …`**（连同 [mkLoad] 里 `symChanged` 那处），
     *   标题回到布局 XML 的默认值「行情」，字阶按稿升到 `fs_data`(20sp)。
     *   ⟹ 品种标识**没有丢**：价格卡右上的 `mkt_venue`（`{代码} · {时段}`，由 [t106PaintMeta] 写）承担，
     *     与稿一一对应；顶栏只剩标题（稿 :243-247 逐字）。
     * ⚠ `symFallback` 形参**保留**：调用点 `MainActivity.kt:2808` 仍按两参调用它（那不在本组 inScope）；
     *   本函数现只记 `dispName`。`dispName` 因此变成**只写不读**的字段（登记：删除它要一起动调用点，
     *   属另一轮/另一组的活；本轮只把注释与实现对齐，不做跨文件清理）。
     */
    fun setDispName(n: String, symFallback: String) {
        @Suppress("UNUSED_PARAMETER")
        val ignored = symFallback
        dispName = n
    }

    /** 自选行带来的中文名。空串 ⟹ 标题回退显示代码。 */
    private var dispName: String = ""

    /**
 * 从**自选列表点进来**（用户主动「打开这个品种」）。
 *
 * ⚠⚠⚠ 2026-10-03 **新增的语义：这是「显式进入」，与「刷新」不同**。
 * 用户实报「我点了保存，但是计算出来的结果在我二次点进去的时候并没有出来」。
 *
 * 【根因】回填的触发条件写的是 `wantRestore = symChanged`，而
 *   `symChanged = su != lastSym` 判的是「**和上次载入的品种不同**」。
 *   用户的动作是「**再次进入同一个品种**」—— 此刻 `lastSym` 还是上一个品种，
 *   于是 `symChanged = false` ⟹ 回填根本不触发。
 * ⟹ **同一件事，第二次进恰好被自己的判据挡住了。**
 *
 * 【为什么不改成 `wantRestore = true` 无条件】
 *   `mkLoad()` 有 7 个调用点，其中包含**敲品种名、失焦、确认**——
 *   用户正在填计算页参数时敲一下就重载，无条件回填会把他正在敲的字冲掉。
 * ⟹ 必须区分：**主动进入**（回填）vs **刷新/编辑触发**（不回填）。
 *   [fromFav] 就是这个区分的唯一开关。
 */
fun setSym(s: String) {
        fromFav = true          // ← 显式进入，允许回填
        // The explicit favourite-entry mkLoad owns this re-entry; avoid a duplicate attach reload.
        needsConfirmedReload = false
        val canonical = MktData.canonicalSym(s)
        symInp.setText(canonical)
        symInp.setSelection(canonical.length)
        paintStar()
        // ④ 「启动时刷新一次」要有个对象才能刷: 记住上次看的品种, 下次冷启动才有东西可刷。
        // 不记的话每次冷启动 symInp 都是空的, 开关就几乎永不触发 —— 那还是骗人。
        persistLastSym(canonical)
    }

    /**
     * ④ 记住「行情屏最后看过的品种」= SP `"gridcalc"` 里的键 `"mkt_last_sym"`。
     *
     * 这两个字面量必须与 MainActivity 的 `prefs()` 及 `PREF_LASTM` **逐字相等**
     * (由归档的 fix_auto_refresh_repair.py 断言守着)—— 改一边另一边就会对不上。
     * ⚠ 2026-10-12 起确认时也会补记一次(见 [toggleWinPop])：确认那一刻正在看的就是这个品种，
     *   「启动时刷新」与按品种取的步长阈值都该记住它。
     * ⚠⚠ 2026-10-12(t4) 它一度被改成**冷启动恢复的查表 key**；同日用户裁定 B 改后，
     *   「冷启动恢复」这条链（restoreDistWinOnce + 「最后确认过的品种」指针
     *   gridcalc_win_confirmed_sym）**整条删除** —— 自选页改读每品种自己的
     *   gridcalc_win_v1[s]，不再需要任何"全局窗口的恢复"。取舍全文见**文件头 2026-10-12 墓碑**。
     *   ⟹ 本键现在只剩两个用途：「启动时刷新」与按品种取的步长阈值（与确认无关，未动）。
     */
    private fun persistLastSym(s: String) {
        val canonical = MktData.canonicalSym(s)
        if (canonical.isEmpty()) return
        try { act.getSharedPreferences("gridcalc", android.content.Context.MODE_PRIVATE)
            .edit().putString("mkt_last_sym", canonical).apply() } catch (_: Exception) {}
    }

    /** ④ 启动时取回上次看的品种; 没有就返回空串(首次安装, 什么都还没看过)。 */
    fun lastSymPersisted(): String = try {
        MktData.canonicalSym(act.getSharedPreferences("gridcalc", android.content.Context.MODE_PRIVATE)
            .getString("mkt_last_sym", "") ?: "")
    } catch (_: Exception) { "" }

    // ── 墓碑 · 2026-10-12（裁定 B 改）：「最后**确认过**的品种」指针已整块删除 ──
    //   原物 = `winConfirmedSymKey`("gridcalc_win_confirmed_sym") + `persistConfirmedSym()`
    //   + `confirmedSymOnDisk()`。**唯一读者**是 restoreDistWinOnce（冷启动恢复全局窗口），
    //   后者连同全局 DistWin 一起删 ⟹ 写者一并删，不留死代码。
    //   取舍全文见**文件头 2026-10-12 墓碑**；`gridcalc_win_v1` 仍是窗口的唯一真源。
    //   磁盘上遗留的该键零读方，不迁移、不新增写入。

    // 自选距离键用(对标稿子favKey读tfSeg/kcount)
    fun curTf(): String = tf

    // 稿§4根数:范围15≤n≤100(KMIN/KMAX),越界clamp不报错;空值回落该品种已确认默认
    fun curN(): Int = (rawN() ?: confirmedN()).coerceIn(KMIN, KMAX)

    // 稿§4 根数**原始值**：步进器把值约束在 KMIN..KMAX 内，所以这里恒等于 [kVal]。
    // ⚠ 保留这个间接层是**故意的**：`raw < KMIN` 那条「提示并中止」分支仍在（见 loadNow），
    //   将来若允许键盘直输/长按越界，这里就是唯一需要改的地方；删掉它会让下游的
    //   「rawN 可能 < KMIN」前提无声失效。
    private fun rawN(): Int? = kVal

    private fun confirmedN(): Int = loadWin().second

    // 自选页「距支撑」用的**该品种自己的**窗口（2026-10-12 用户裁定 B 改：每品种）。
    //
    //   · 读 `gridcalc_win_v1[sym]`（唯一真源，由行情页点「确认」的 saveWin() 写入）；
    //   · 无记录 / 记录非法 → `("M" to 20)` —— 与行情页 loadWin() **同一个回落口径**；
    //   · **不看**行情页周期控件、不看任何全局冻结值：那套全局机制已删（文件头 2026-10-12 墓碑）。
    //
    // 改前这里是**无参** `distWin()`：`DistWin.tf?.let{...} ?: (tf to curN())` —— 一个全局窗
    // 被自选页所有行共用，于是改任一品种的窗口就让**全部**行的 FavDist key（含 n）一起变、
    // 缓存全 miss、全量重取（见文件头墓碑 ①）。改后每行各取各的，互不影响。
    //
    // ⚠ 调用点（两处，都在 FavPanel）：repaint() 的**每行**、refreshFavDist() 的**每个品种**。
    // ⚠ 纯读、无副作用、不发任何请求；SP 读的是已载入内存的 map，逐行调用开销可忽略。
    fun distWin(sym: String): Pair<String, Int> = loadWinOrNull(sym) ?: ("M" to 20)

    // ---------- 稿§4 根数/周期按品种默认(gridcalc_win_v1) ----------
    // ▾确认才落盘;回车/失焦只重画不回填;离开行情页丢弃未确认值;
    // 换品种各显各的默认;周期按钮与确认同源(都读这一个tf+输入框)
    private val winPref
        get() = act.getSharedPreferences("gridcalc_win_v1",
            android.content.Context.MODE_PRIVATE)

    /**
     * 读 `gridcalc_win_v1[sym]` 里该品种**已确认**的窗口。
     *
     * @return 合法记录 → `周期 to 根数`(根数已夹到 KMIN..KMAX);
     *   **无记录 / 周期不是 W·M·Q / 根数不是整数 → null**(不猜、不造,交给调用方决定回落值)。
     *   2026-10-12 从 loadWin() 拆出来:冷启动恢复要能区分「确认过 20」和「从没确认过」——
     *   后者绝不能被当成合法值去冻结,否则等于凭空造了一次确认。
     */
    private fun loadWinOrNull(sym: String): Pair<String, Int>? {
        val canonical = MktData.canonicalSym(sym)
        if (canonical.isEmpty()) return null
        try {
            var keyUsed = canonical
            var raw = winPref.getString(canonical, null)
            // Canonical wins when both records exist. Legacy aliases are read only as
            // compatibility and migrated immediately so future writes have one key.
            if (raw.isNullOrEmpty() && canonical == "NG00Y") {
                for (legacy in MktData.NATGAS_ALIASES) {
                    val legacyRaw = winPref.getString(legacy, null)
                    if (!legacyRaw.isNullOrEmpty()) {
                        raw = legacyRaw
                        keyUsed = legacy
                        break
                    }
                }
                if (!raw.isNullOrEmpty()) {
                    winPref.edit().putString(canonical, raw).remove(keyUsed).apply()
                }
            }
            if (!raw.isNullOrEmpty()) {
                val p = raw.split("|")
                val wtf = p[0]
                val nv = p.getOrNull(1)?.toIntOrNull()
                if ((wtf == "W" || wtf == "M" || wtf == "Q") && nv != null) {
                    return wtf to nv.coerceIn(KMIN, KMAX)
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun loadWin(): Pair<String, Int> =
        loadWinOrNull(lastSym) ?: ("M" to 20) // 未确认过:初始默认20(稿§4示例)

    private fun saveWin() {
        val canonical = MktData.canonicalSym(lastSym)
        if (canonical.isEmpty()) return
        try {
            val e = winPref.edit().putString(canonical, tf + "|" + curN())
            if (canonical == "NG00Y") {
                for (legacy in MktData.NATGAS_ALIASES) e.remove(legacy)
            }
            e.apply()
        } catch (_: Exception) {
        }
    }

    // 换品种载入默认:周期按钮与根数步进器同源显示该品种已确认值
    private fun applyWinDefaults() {
        val (wtf, wn) = loadWin()
        tf = wtf
        paintTf()
        setKVal(wn)
    }

    // ---------- 根数步进器（2026-11-04 用户裁定「填数字 → 加减控制」）----------
    // 设计要点：
    //   · 值只在 KMIN..KMAX 内游走，到边界就把对应键禁用（不是静默 clamp，用户看得见「到顶了」）；
    //   · 单击 ±1；长按 320ms 后每 60ms 连发，抬手即停（连续调参不必点几十下）；
    //   · 每次变化只更新读数与按键态，**不自动发请求** —— 提交仍走「确认」（用户 2026-10-03 的裁定）。
    private fun setKVal(n: Int) {
        val v = n.coerceIn(KMIN, KMAX)
        if (v == kVal) return
        kVal = v
        renderK()
        scheduleKApply()
    }

    // ⚠⚠ 2026-11-04 用户报「k线图没有变化」的第二半：光让「确认」重画还不够 ——
    //   步进器每点一下就变一个值，用户预期是**图跟着变**，而不是点 20 下再点确认。
    //   这里做**防抖即时应用**（400ms 内连续点击只发一次请求）：
    //     · 图立即按新根数重画（所见即所得）；
    //     · 盘上**仍不写**（`gridcalc_win_v1` 只在「确认」时保存）——「不确认就不存」的契约不变；
    //     · 不拉确认的后果仍是老的：离开页面由 onLeave() 还原成上次确认值。
    private val kApplyHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val kApplyRunnable = Runnable { if (lastSym.isNotEmpty()) mkLoad() }

    private fun scheduleKApply() {
        kApplyHandler.removeCallbacks(kApplyRunnable)
        kApplyHandler.postDelayed(kApplyRunnable, 400)
    }

    private fun stepK(dir: Int) = setKVal(kVal + dir)

    private fun renderK() {
        kValT.text = kVal.toString()
        kMinus.isEnabled = kVal > KMIN
        kPlus.isEnabled = kVal < KMAX
        val ink = act.attrColor("colorInk")
        val faint = act.attrColor("colorFaint")
        kMinus.setTextColor(if (kMinus.isEnabled) ink else faint)
        kPlus.setTextColor(if (kPlus.isEnabled) ink else faint)
        t106SyncKCount()
    }

    /** 按下：缩到 .94 + 320ms 后开始连发；抬手：回弹并把「是否已连发」折算成一次单击。 */
    private fun wireStepButton(b: Button, dir: Int) {
        val ui = android.os.Handler(android.os.Looper.getMainLooper())
        var held = false
        var repeated = false
        var tick: Runnable? = null
        fun stop() {
            tick?.let { ui.removeCallbacks(it) }
            tick = null
        }
        // 无障碍/键盘激活走 performClick（触屏路径由下面的 onTouch 自己处理，不会重复计数）
        b.setOnClickListener { stepK(dir) }
        b.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    held = true
                    repeated = false
                    v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90).start()
                    val r = object : Runnable {
                        override fun run() {
                            if (!held) return
                            repeated = true
                            stepK(dir)
                            if (kVal != if (dir > 0) KMAX else KMIN) ui.postDelayed(this, 60)
                        }
                    }
                    tick = r
                    ui.postDelayed(r, 320)
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    held = false
                    stop()
                    v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                    if (e.actionMasked == android.view.MotionEvent.ACTION_UP && !repeated) stepK(dir)
                }
            }
            true
        }
    }

    // ══════ 墓碑 · 2026-10-12 用户裁定 B 改：「冷启动恢复已确认窗口」整段删除 ══════
    //
    // 【它原本治什么】同日早些时候用户报「我点了特斯拉的确认周期……我点了确认之后就变成
    //   60% 多了。只不过为什么，过了一段时间又变回来了，像是被覆盖了式的」「每次待取数的
    //   时候就会出现这种问题」——「待取数」就是冷启动那一轮刷新。
    //   机制链条（逐环都在本文件）：① 全局 DistWin 是进程内存态 object、不落盘，被杀即归零；
    //   ② 无参 distWin() 未冻结时读行情页周期控件+根数输入框；③ 输入框初值是 page_mkt.xml
    //   写死的 `android:text="20"`；④ 把 gridcalc_win_v1[lastSym] 回填进控件的
    //   applyWinDefaults() 只在 mkLoad 的 `if (symChanged)` 处跑 ⟹ 冷启动直看自选页时
    //   窗口 =(M,20) ⟹ key 从 `stock|TSLA|M|31` 变成 `stock|TSLA|M|20` ——
    //   **不是值被覆盖，是窗口换了把钥匙**（31 根那次的值在它自己的 key 下没丢）。
    //   当时的修法 = 首次 distWin() 时一次性恢复（restoreDistWinOnce + winRestoreTried），
    //   并把查表 key 从 mkt_last_sym 换成「最后确认过的品种」指针 gridcalc_win_confirmed_sym
    //   （修 t3 验证员报的 F1：点开 005930 不确认 → 指针被覆盖 → 查不到记录 → 回落 20）。
    //
    // 【为什么现在删 —— 病灶本身没了，不是修法过时】2026-10-12 裁定 B 改后，自选页**每行**
    //   直接读磁盘真源 gridcalc_win_v1[sym]（见 [distWin(sym)]），冷启动天然就是各品种自己
    //   的窗口：既不经过全局 DistWin，也不经过行情页控件 ⟹ 「恢复」没有对象了。
    //   它顺带的「回填行情页控件」另有同源路径，**不会**因此打开「真覆盖」缺口 —— 三条论证
    //   + 一条残余的纯展示差异写在**文件头 2026-10-12 墓碑**里。
    //   连带删除（读者没了就删写者，不留死代码）：winRestoreTried、restoreDistWinOnce()、
    //   persistConfirmedSym()、confirmedSymOnDisk()、winConfirmedSymKey。
    //   ⚠ 磁盘遗留键 gridcalc_win_confirmed_sym 现在全工程零读方：不迁移、不新增写入。
    //   ⚠ 只读本地 SharedPreferences、不新增任何网络请求这一点，改前改后一致。

    // 离开行情页(返回键/‹返回/切Tab,由showTab统一钩)。
    //
    // ⚠⚠ 2026-10-03 **两次裁定来回**：
    //   ① 先改成「离开即落盘」(所见即所存) —— 因为 t107 移除 ▾ 下拉后
    //      **屏上再无任何入口能调到 saveWin()**，而这里还在主动丢弃，必丢。
    //   ② 用户随后要「确认键 + 二次确认」⟹ **本轮退回确认制**。
    //
    //   ⟹ 现在：**不确认就不存**，离开时还原成上次确认过的值。
    //   ⟹ 保存入口 = mkt_win「确认」按钮 → 二次确认对话框 → 确定才写盘。
    //
    // ⚠ **「自动存」与「确认键」是两套互斥语义**，别同时开着：
    //     都开 = 确认键形同虚设（反正离开就存了）。
    //   要回自动存：把这一行改回 saveWin()，并把 mkt_win 按钮再次隐藏，两处成对。
    /**
     * Repaint the last confirmed window from same-period local data only.
     * Returns false when a normal mkLoad is needed after re-attach.
     */
    private fun replayConfirmedBatch(): Boolean {
        if (lastSym.isEmpty()) return false
        val loadTf = tf
        val loadSym = lastSym
        val loadSeq = reqSeq
        val type = lastType
        val loadKey = when (type) {
            "gold", "silver", "cmdty" -> MktData.fetchKeyOf(loadSym)
            else -> loadSym
        }
        val ck = MktData.MktCache.key(loadKey, loadTf, MktData.FETCH_N)
        var fromSaved = false
        val vs = MktData.MktCache.get(ck) ?: MktData.MktSave.saved(ck)?.also { fromSaved = true }
            ?: return false
        if (fromSaved) {
            t2SrcSaved = true
            t2SrcSavedAgeMs = MktData.MktSave.savedAgeMs(ck)
        } else {
            t2SrcFresh()
        }
        localReplay = true
        try {
            when (type) {
                "stock", "kr" ->
                    renderAgg(
                        MktData.aggregate(listOf(MktData.pickStockWinner(vs)), loadKey),
                        lastMkt, loadSeq, loadSym, loadTf, allowAuxRequests = false)
                "crypto" ->
                    renderAgg(
                        MktData.aggregate(vs, loadKey),
                        lastMkt, loadSeq, loadSym, loadTf, allowAuxRequests = false)
                else ->
                    renderAgg(
                        MktData.aggregate(vs, loadKey),
                        lastMkt, loadSeq, loadSym, loadTf, allowAuxRequests = false)
            }
        } finally {
            localReplay = false
        }
        return t2ShownTf == MktData.tfName(loadTf) && lastKs.isNotEmpty()
    }

    fun onLeave() {
        if (lastSym.isEmpty()) return
        val (confirmedTf, confirmedN) = loadWin()
        val cleanWindow = tf == confirmedTf &&
            kVal == confirmedN
        val confirmedRequestInFlight = cleanWindow && requestInFlight &&
            inFlightSeq == reqSeq && inFlightTf == confirmedTf && inFlightN == confirmedN
        val screenAlreadyConfirmed = cleanWindow &&
            t2ShownTf == MktData.tfName(confirmedTf) && t2ShownCount == confirmedN
        // A clean confirmed request may finish while the page is detached; keep its
        // generation alive instead of forcing a duplicate fetch on re-entry.
        if (confirmedRequestInFlight || screenAlreadyConfirmed) {
            applyWinDefaults()
            lastTf = tf
            needsConfirmedReload = false
            return
        }
        // Dirty controls or a stale screen invalidate the old generation, then replay
        // the confirmed window locally or defer one normal mkLoad until re-attach.
        reqSeq++
        requestInFlight = false
        applyWinDefaults()
        lastTf = tf
        needsConfirmedReload = !replayConfirmedBatch()
    }

    // 「确认」按钮 → **直接落盘**，不弹任何对话框。
    //
    // ⚠⚠ 2026-10-03 用户在这一件事上改了**三次口**，最终要的是现在这个：
    //   ① 「为什么没有确认键，调好的周期怎么保存，一退出去数据又没了」
    //      ⟹ 我改成"离开即自动落盘" —— **错**，他要的是那个键。
    //   ② 「要确认键继续二次确认」
    //      ⟹ 我加了系统 AlertDialog 二次确认 —— 还是错。
    //   ③ 「怎么还弹这个页面，太丑了」 +「点完确定直接保存就可以了」
    //      ⟹ **确认键要留，对话框不要**：点一下就存。
    //
    // ⟹ 三轮下来的净结论：**显式确认 + 立即生效**，不要中间层。
    //   （系统 AlertDialog 也不只是丑的问题：它是另一套控件、另一套字体与配色，
    //     与本 App「无卡片 + 发丝线分区 + Inter」的整体语汇是脱节的。）
    //
    // ⚠ onLeave() 保持 applyWinDefaults()：**不点确认就不存**，离开会还原。
    //   「自动存」与「确认键」互斥，别同时开着；要回自动存就把这里和 onLeave 一起改。
    private fun toggleWinPop() {
        val n = curN()
        val tfName = MktData.tfName(tf)
        // 稿§4：确认才把(周期+根数)写成该品种已确认默认；输入框归一到 clamp 值
        setKVal(n)
        // ⚠ 2026-10-12 裁定 B 改：这里原有两句 `commitDistWin()`（把窗口冻结进**全局** DistWin）
        //   与 `persistConfirmedSym()`（记「最后确认过的品种」，供冷启动恢复查表）——
        //   两者都只服务**全局窗口**，自选页改读每品种后一并删除（取舍见文件头 2026-10-12 墓碑）。
        //   ⟹ 落盘只剩下面这一处 saveWin() → `gridcalc_win_v1[lastSym]`，唯一真源，一字未动。
        saveWin()
        // ④ 2026-10-12(t1)：确认那一刻正在看的就是这个品种，「上次看的品种」补记一次
        //   （服务「启动时刷新」/按品种的步长阈值，**不是**恢复查表 key —— 那条链已删）。
        persistLastSym(lastSym)
        // ⚠ 带上「刚确认的品种」：自选页窗口已改每品种，只有它的 key 会变 ⟹ 只刷它一行
        //   （回调接线见 MainActivity:2754 附近 → refreshFavDist(only = setOf(sym))）。
        //   lastSym 空（行情页没载入品种，saveWin 也早退、盘里没变）→ 交给接收方判空后跳过。
        onDistCommitted?.invoke(lastSym)
        // ⚠⚠ 2026-11-04 **补上「确认即重画」**（用户报「k线图没有变化」）：
        //   改前本函数只落盘、不取数 —— 屏上的 K 线是靠**输入框失焦**那条隐式路径刷新的
        //   （`kcountInp.setOnFocusChangeListener { !hasFocus -> mkLoad() }`）。
        //   2026-11-04 输入框换成步进器后，那条隐式路径整体消失 ⟹ 点「确认」只存盘、图不动。
        //   实测证据：prefs 已写 `STX=M|22`，屏上仍是「月线 · 17 根」、源行还是「Nasdaq · 2 分钟前」。
        //   ⟹ 确认的语义本就该是「存盘 **且** 按新窗口重画」，这里显式补一次 mkLoad()。
        //   ⚠ 只在已载入品种时刷（lastSym 空时 saveWin 也早退，没有可画的东西）。
        if (lastSym.isNotEmpty()) mkLoad()
        android.widget.Toast.makeText(act, "已保存：$tfName · $n 根",
            android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * 一行布局（2026-10-29 t28）下的控制行收口。
     *
     * 稿 `四屏-行情页.html:91-99`：`.mk-ctl { display:flex; align-items:center;
     * justify-content:space-between; gap: sp-2; padding: sp-2 sp-1 }` —— 周期段与数量组
     * 在**同一行**，周期段是 `inline-flex`（内容宽），数量组是 `.mk-cnt { display:flex; gap: sp-1 }`。
     *
     * ⟹ 本函数**不再分配任何宽度**。三处宽度已全部回到 XML 声明：
     *   · `mkt_tf_row` 与三个 `mkt_tf_w/m/q`：`wrap_content`（内容宽，同稿 inline-flex）；
     *   · `mkt_kstep`（2026-11-04 取代 `mkt_kcount` 输入框）：轨道 36dp，键 32dp×2、读数 minWidth 44dp；
     *   · `mkt_win`：`wrap_content` + `minWidth=48dp` + `singleLine`（2026-10-27「确认被挤成竖排两字」的防回归）。
     *
     * ⚠ 别把宽度分配加回来：运行期写回 `lp.width` 会把 `wrap_content` 覆盖掉，那正是
     *   2026-10-29 用户看到的「药丸被均分铺满整行」。要度量请读 `measuredWidth`（只读）。
     * ⚠ 保留本函数是因为 `init{}` 的 `post{}` 与 `MktPeriodLayoutTest` 的 bridge 仍在调用它；
     *   它也仍然守着「确认键永远可用、可点」这条状态契约。
     */
    private fun syncGrid() {
        // A measured row must never leave the action as a zero-width/disabled child.
        winBtn.isEnabled = true
        winBtn.isClickable = true
        // 旧实现开头是 `val w = row2.width; if (w <= 0) return` —— 那是「等 row2 量完再算三列栅格」
        // 的前置。栅格没了，这个早退分支与它的依赖一起删除（一行布局的内容宽与行宽无关）。
    }

    // 回车/查看:下拉开着且有选中行→用选中项,否则按输入框文字直接加载
    private fun pickOrLoad() {
        // #53: 候选是页内行了(不再是浮层), 有没有候选读 sugRows 即可 ——
        //   行收干净后即便 sugSel 还留着旧值, 也老实按输入框文字直接加载。
        if (sugRows.isNotEmpty() && sugSel >= 0 && sugSel < sugItems.size) {
            pickSug(sugItems[sugSel])
        } else {
            hideSug()
            mkLoad()
        }
    }

    private fun scheduleSug() {
        sugRunnable?.let { sugHandler.removeCallbacks(it) }
        val q = MktData.canonicalSym(symInp.text.toString())
        if (q.isEmpty()) {
            hideSug()
            return
        }
        val r = Runnable {
            val seq = ++sugSeq
            val local = MktSuggest.local(q)
            showSug(local, seq)
            // ⚠ 2026-11-03 t11（t3 finding 3）：已下线市场在本地/远端都必然空 ⟹ 下拉一藏就完事
            //   的话用户只会以为"没反应"。直接在这一行说清「不支持…」，并且**不发远端请求**
            //   （MktSuggest.remote 对 unsupported 本来就是空，这里连调用都省掉）。
            if (MktData.symType(q) == "unsupported") {
                paintUnsupported(q)
                return@Runnable
            }
            Thread {
                val remote = MktSuggest.remote(q)
                if (seq != sugSeq) return@Thread
                val merged = MktSuggest.sortTop(
                    local + remote.filter { x -> local.none { it.s == x.s } })
                act.runOnUiThread { showSug(merged, seq) }
            }.start()
        }
        sugRunnable = r
        sugHandler.postDelayed(r, 300)
    }

    init {
        MktData.MktSave.attach(act) // 落盘留底需要 applicationContext(稿:1353-1355)
        // ⚠ 2026-10-04 手动拆股簿也要预载进内存：`aggregate` 是静态入口、没有 Context，
        //   只能靠 ensure() 提前读一次（与 FX.ensure 同一形状）。
        MktData.SplitBook.ensure(act)
        // ⚠ 2026-10-29 t30：这里原调 `paintCardSlices()`（t3 删拆股时把它从 paintSplitUi 接管过来，
        //   为的是保住五块卡的底色与抬升）。**按稿去卡片化后它就是要拆的东西** ⟹ 调用已删，
        //   函数本体与依据见本类「卡片分片体系整体下线」那段墓碑。别再调回来。
        // ⚠ 2026-10-03 **源状态行的展开/收起点击监听整块删除**。
        //   它挂在 srcRow 上，而 srcRow 已随「源状态行」按用户裁定删除（见 page_mkt.xml 墓碑）。
        //   配套的 syncLegRows/paintLegRows 一并删除 —— 后者是 dispatchDraw 闪退的元凶，
        //   详细说明见本类中 [legRowHost] 原位置留下的注释。
        paintTf()
        // 稿mktSearch(1155):点入口展开原隐藏品种输入坞并聚焦品种输入框(只展不收,同稿display='')
        // t110:入口已从 🔍 图标换成「搜索」文字,**两个入口共用同一个 t110OpenDock()**。
        // 以前那个绑在「搜索」上的函数是 `searchTx.performClick()` 调自己的空壳,点了没反应 —— 已删除。
        searchBtn.setOnClickListener { t110OpenDock() }
        searchTx.setOnClickListener { t110OpenDock() }
        goBtn.setOnClickListener { pickOrLoad() }
        favBtn.setOnClickListener { toggleFav() }
        paintStar()
        // ▾确认按钮：宽度由 row2 的三列网格在布局完成后统一计算。
        winBtn.setOnClickListener { toggleWinPop() }
        row2.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncGrid() }
        row2.post { syncGrid() }
        periodRow.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                if (lastSym.isEmpty()) return
                // ⚠⚠ 2026-11-02 用户报「数量没点确认就切页，回来时输入框回退了、行情图没回退」。
                //   旧逻辑只在 `needsConfirmedReload`（= 离开时本地回放失败）时才重载，
                //   而回放**成功**时画面已经按未确认的根数重绘过 ⟹ 输入框回退了、图还是旧的。
                //   ⟹ 这里加一条**与已确认窗口比对**的闸：屏上画的不是已确认的(周期,根数)就重载一次。
                //   比对用 t2ShownTf/t2ShownCount（= 屏上这批数据的真实身份），不看输入框。
                val (ctf, cn) = loadWin()
                val mismatch = t2ShownTf != MktData.tfName(ctf) || t2ShownCount != cn
                if (!needsConfirmedReload && !mismatch) return
                needsConfirmedReload = false
                t2MarkStale()
                mkLoad()
            }
            override fun onViewDetachedFromWindow(v: View) = Unit
        })
        // ⚠ 2026-10-28：K 线缩放三键（zoomOut/zoomIn/zoomReset）的 click 监听与 OHLC 表头
        //   三态排序的接线**随控件一并删除**，这里不再留任何空接线。
        val done = { v: View, after: () -> Unit ->
            act.hideKeyboard(v)
            after()
        }
        symInp.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                done(v) { pickOrLoad() }
                true
            } else false
        }
        // 上下键移动选中(物理键盘/DPAD),回车沿用pickOrLoad
        symInp.setOnKeyListener { _, keyCode, event ->
            val open = sugRows.isNotEmpty() && sugItems.isNotEmpty()
            if (event.action == KeyEvent.ACTION_DOWN && open &&
                (keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                    keyCode == KeyEvent.KEYCODE_DPAD_UP)
            ) {
                sugSel = if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    minOf(minOf(sugItems.size, SUG_MAX) - 1, sugSel + 1)
                } else {
                    maxOf(0, sugSel - 1)
                }
                paintSugSel()
                true
            } else if (event.action == KeyEvent.ACTION_DOWN &&
                keyCode == KeyEvent.KEYCODE_ENTER
            ) {
                pickOrLoad()
                true
            } else false
        }
        symInp.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                scheduleSug()
                paintStar()
                // t253 稿 L526: 描边盒左侧显示品种名。symInp 是品种的唯一真源(L45),
                // 所以这里同步即可, 不必另建监听器。
                // t252 漏了这一步 —— 绑定了 view 却从没赋过值, 真机恒显「—」。
                val t = symInp.text?.toString()?.trim().orEmpty()
                symName.text = if (t.isEmpty()) "—" else t
            }
        })
        // ⚠ 2026-11-04：原 `kcountInp.setOnEditorActionListener`（数字键盘「完成」提交）随输入框删除；
        //   步进器没有 IME 路径，提交仍走「确认」。
        // ⚠⚠ 2026-10-03 用户裁定「输入框没有光标最右功能」那一整段（选中文末/失焦提交）
        //   随输入框一并作废 —— 2026-11-04 输入框已换成步进器，没有光标这回事了。
        //   步进器的提交路径见下方 wireStepButton / renderK。
        t106SyncKCount()
        wireStepButton(kMinus, -1)
        wireStepButton(kPlus, +1)
        renderK()
        val tfClick = { t: String ->
            tf = t
            paintTf()
            if (symInp.text.toString().trim().isNotEmpty()) mkLoad()
        }
        tfW.setOnClickListener { tfClick("W") }
        tfM.setOnClickListener { tfClick("M") }
        tfQ.setOnClickListener { tfClick("Q") }
        chart.onInfo = { info ->
            val upC = act.attrColor("colorTeal")
            val dnC = act.attrColor("colorRed")
            // t106 ④ 涨跌额 / ⑥ 交易所·时点(原 t106PaintOhlc,OHLC+Vol 删除后改名)
            t106PaintMeta(info)
        t108PaintTitle(info)
            // 稿 renderMK(764):supShow(现价之下支撑)→linkCalc 软联动(计算页最低价/目标爆仓价上限)
            val sup = info.supShow
            // ⚠ 2026-10-26 **带上这批数据的批次代号**：onInfo 由 onDraw 触发，重绘（缩放/十字光标）
            //   也会再来一次 —— 计算页清空后必须能认出这是「清空前那一批」而拒掉；
            //   清空后重新进入同品种的新批次则必须放行。代号来自 [shownSeq]（renderAgg 在
            //   chart.setData 前写入的唯一写者），不是"回调发生时的当前 reqSeq"。
            act.linkCalc(sup, shownSeq)
            // t106 ⑪ 支撑图例行删掉(稿里没有,信息已由线上标签片承担),
            // ⚠ 2026-10-28 方向-B 复核：本屏**没有任何图例说明条**（K 线下方也不补）——
            //   稿里的「实心▲涨 / 实心▼跌 / 实线支撑 / 虚线现价 / 1屏≤500根」一律不上屏。
            //      底部改成 ⑫ 状态行「· N 根月线已更新」
            // #33 稿 L581-582: 状态与来源是【两个兄弟元素】,不再拼进同一个 TextView。
            //   状态 → stateline,带稿 L291 的前导符号「·」(成功态恒为默认档)
            //   来源/光标读数 → leg,空则 GONE
            // ⚠ 2026-10-03 用户裁定「图里的东西不要了」⟹ 状态行(· N 根月线已更新)不再上屏。
            //   删掉这里的 visibility = View.VISIBLE,让布局的 GONE 说了算;文案照写。
            statelineLead.text = "·"   // 稿 L291 默认档
            // ⚠ 这里必须用字符串模板: 改前是 `"· " + info.count + …`, String 是接收者;
            //   把它去掉之后 `Int + String` 在 Kotlin 里**没有这个重载**, 编译直接报错。
            //   「函数可不可解析, 编译器才是判据」—— 断言当时没覆盖到这一行的类型。
            // t44【F-2c③】**这行「N 根」= 画出来的根数,不是取到的根数**,语义与改动前一致:
            //   ⚠⚠ 2026-10-10 **口径更正（t1 改了语义，此处当时未同步）**：
            //   info.count 现在 = **MktView 真正画出来的根数** = `vp.ks.size`
            //   （MktView 里 `val ks = vp.ks` → `val n = ks.size` → onInfo 的 `count = n`），
            //   而 `vp.ks` 是 MktData.profileOf **过滤后**的那一份 ——
            //   2026-10-12 起只剩 clean 非法柱过滤（尺度带已按用户裁定选 A 删除，
            //   见 MktData.profileOf 的墓碑注释），**一根合法柱子都不剔**。
            //   ⟹ 口径仍是"画出来的根数"（用户看到几根就报几根）；
            //     取数档位(FETCH_N=240)照样不会出现在这一行里。
            //   用户填 100 但源只回 20 根时,这里**照实写「20 根」**,不谎报 100。
            // ⚠ 墓碑 · 2026-10-12（t3 当日加入、当日作废）：这里原在 hidden > 0 时给
            //   本行拼一句「·N 根中 M 根跨尺度已隐藏」，配套的 Profile.hidden → MktInfo.hidden
            //   透传链随尺度带一并删除（用户裁定选 A：删尺度带、不加任何自动判定）。
            //   现在没有「被隐藏的根」这个概念 —— 干净柱一根不剔，也就无话可拼。
            stateline.text = "${info.count} 根${MktData.tfName(curTf())}已更新"
            stateline.setTextColor(act.attrColor("colorFaint"))
            if (info.legRow.isNotEmpty()) {
                leg.visibility = View.VISIBLE
                leg.text = info.legRow
            } else {
                leg.visibility = View.GONE
            }
            // B-33 稿 L515  nosup: 数据已到(onInfo 已跑完 = 取数成功),
            // 但腰斩法在这个周期没命中支撑(info.supShow 为空)。
            // 稿 L913 要求「数据到了但无支撑」是与源不可达/无此品种/根数不足
            // 并列的独立状态, 各有独立文案 —— 之前它被静默吞成和成功态一样。
            // tone=warn ⟹ 稿 L288 走 var(--ac) 紫 ⟹ mkStatus 传 err=false。
            // mkt_status 显示这条时, t279 的 stateline 会自动收起(同一时刻不出现
            // 两套真相), 稿 L582 的 StateLine 在非 ok 态也不该显示「N 根已更新」。
            if (sup.isEmpty()) mkStatus("—无支撑 · 数据已到，但该周期算不出支撑位（腰斩法未命中）", false)
            t102PaintSourceRow(info.count)
            t103PaintStepT(MktData.canonicalSym(symInp.text?.toString() ?: ""))
            // 稿:成功态不显示"支撑xxx"状态文字(mktstatus默认display:none)
            // ⚠ t322: 这里**只能收 mkt_status, 不能走 mkStatus("")**。
            //   mkStatus 的 else 分支会连 stateline 一起收掉, 而 stateline 此刻
            //   装的正是上面 L543-545 刚写的 ok 态「· N 根…已更新」⟹ 成功态整行消失。
            //   t321 加 #77 的时候踩了这个: 两个 view 语义重叠, 收错了那一个。
            status.visibility = View.GONE
        }
    }

    // ══════════════ 手动拆股 · 前复权（UI）· 2026-10-28 已整块删除 ══════════════
    //
    // 原有的三件职责（显示当前品种条目 / 解析用户输入的日期与比例 / 写回 MktData.SplitBook）
    // 随 UI 一并下线：表单控件、条目渲染、提示行、reloadAfterSplit 全部删除。
    // MktData 侧的 SplitBook 仍是空操作（2026-10-27 裁定「功能也不要」），取数与缓存逻辑未动。
    // ⚠ 要恢复：得同时把 page_mkt.xml 的 mkt_split_* 节点、本节绑定与监听整体捡回来 ——
    //   别再试图「只加一个开关」。

    // ══════════════════════════════════════════════════════════════════════════
    // ⚠⚠ 2026-10-29 t30 · **卡片分片体系整体下线**（用户裁定「按稿彻底去卡片化」）。
    //
    // 这个位置原有三个函数，已**连同两处调用（init{} 与 mkLoad 的 symChanged 分支）一并删除**：
    //   · cardBg(view, top, bottom) —— 造 GradientDrawable：底 = colorCard + 逐角 12dp 圆角；
    //   · paintCardSlices()         —— 运行期把它挂到 控制行 / 现价块 / K 线块 / 指标块 / 支撑块，
    //                                  再分别抬升（elev_row / elev_1），并把 chart 与指标卡三行的背景清空；
    //   · isDarkNow()               —— 只被 paintCardSlices 的 elevCtrl 判据用过（唯一调用点随之失效）。
    //
    // 依据：稿 `theme.css` §6.1 的透明组逐字包含 `.mk-blk, .mk-price, .mk-sw, .mk-metrics,
    //   .mk-sup, .mk-ohlc, .mk-tblwrap { background: transparent; border-radius: 0; box-shadow: none; }`
    //   ⟹ 行情屏**本来就没有卡**（2026-10-29 t29 人眼核时发现 App 仍是 5 张白卡，是本轮移植漏项）。
    //   边界改由**面层阶梯 + 控件自身**表达（稿 §2.4-3.2），**不加发丝线**（稿 `.mk-hair { display:none }`）。
    //
    // ⚠ 别把这段恢复回来：要加回卡面必须先改稿（真值源），否则闸 2 / 闸 4 会同时判红。
    // ⚠ 深色下 elev_* 原本也都是 0（values-night）⟹ 这次删除对深色**只去掉 colorCard 底色**。
    // ⚠ `isDarkNow()` 若日后别处要用，重写一个即可（原来是 `(resources.configuration.uiMode and
    //    UI_MODE_NIGHT_MASK) == UI_MODE_NIGHT_YES` 的一行实现），别为它把本节整块捡回来。
    // ══════════════════════════════════════════════════════════════════════════

    /** r8 B-1：卡头中段读数 = **实际画出来的根数**（`MktInfo.count` = `vp.ks.size`，不是保存的窗口值）。 */
    private fun paintBlkh(n: Int) {
        val t = MktData.tfName(tf) + " · " + n + " 根"
        // ⚠ 值相同就不写：本函数在 onInfo 里被**每帧**调用（十字光标拖动时也调），
        //   无条件 setText 会让 TextView 每帧走一次 relayout 检查。
        if (blkhM.text.toString() != t) blkhM.text = t
    }

    // t102 第3步:源状态行。**只读 MktData.legsOf(curSym())(2026-10-12 t2 起按品种取),
    // 不改任何取数逻辑、不新增定时器。**
    // handoff §0.2:红线只表示跌,所以异常用**空心环**、正常用**实心点**——形状编码,色盲与暗光都可用。
    /** t118:由值实际产生的时刻算相对时间(秒/分/时),不用硬编码。 */
    private fun t118Ago(at: Long): String {
        if (at <= 0L) return ""
        val d = (System.currentTimeMillis() - at).coerceAtLeast(0L) / 1000
        return when {
            d < 60 -> "${d} 秒前"
            d < 3600 -> "${d / 60} 分钟前"
            else -> "${d / 3600} 小时前"
        }
    }

    /**
     * t123:无数据 / 取数中 / 失败 —— 统一画成 **空心点 + 一句人话**。
     * 圆点与文案**出自同一个函数**,结构上不可能再出现「实心点 + 空文案」。
     */
    /**
 * ⚠⚠⚠ 2026-10-03 **换品种时整屏清空行情区**（用户实报：「行情页面和交易品种会串，
 * 在没有拉取到行情数据的情况下」）。
 *
 * 【症状】屏上标题写着 `XAUUSD`，但价格 84,988 / 预测价值 114,637 / 支撑 59,225.18
 *
 * 【根因】`t123PaintNoData()` 只清两样东西：
 * ```
 * srcDot.setBackgroundResource(srcdot_off)
 * srcMain.text = "暂无可用源"
 * srcName.text = "未取到"      // ← 只动了右上角那一行小字
 * ```
 *   **价格、K 线、VPVR 分布、支撑位、预测价值，一个都没清。**
 *   ⟹ 取数失败（或走留底分支）时，上一品种的画面原样留在屏上，只有一个小标签变了。
 *   ⟹ 标题是在 `symChanged` 时直接改的（`titleTx.text = su`），所以**标题总是新的、数据总是旧的**。
 *
 * 【修法】换品种即刻清空整块，等新数据到了再画。
 *   ⟹ 不在失败分支里补清 —— 那太晚（用户会先看到一屏错数据）；
 *     也不依赖 `t123PaintNoData()` —— 它的语义是「源状态变了」，不是「品种变了」。
 *
 * ⚠ 清空范围里 `chart` 也必须清：不清的话 VPVR 分布会跟着换品种继续留在图上。
 */
private fun clearForNewSymbol() {
        clearQuoteBlock(clearSource = true)
        // ⚠ 2026-10-15 t2：换品种时**必须**把「实时」标记也清掉 —— 它是按品种存的，
        //   留着会让新品种在第一笔数据回来之前就挂上「实时 · Nasdaq」（这正是本任务
        //   要消灭的那类"界面替数据说话"）。
        try { MktData.clearLiveQuote(lastSym) } catch (_: Throwable) {}
        // ⚠⚠ 2026-10-15 t6（评审 F1）：**屏上这批数据的周期也一起清**。
        //   换品种已经把整块行情清空了（上面那行），屏上不再有任何数据 ⟹ 再指着"上一周期"
        //   就是编。清掉之后 `t2MarkStale` 也不会打标注（它的判据就是这里清空的这个变量）。
        t2ShownTf = ""
        t2ShownAt = 0L
        t2ShownCount = 0
        t2ShownSaved = false
        t2SrcSaved = false
        t2ShownAgeMs = 0L
        t2SrcSavedAgeMs = 0L
        t2Snap = null
        t2Stale = 0
    }

    // ══════════════════ t2：切周期失败时「旧数据 + 明确标注」══════════════════
    //
    // ⚠⚠⚠ 2026-10-15 **用户裁定（经队长转达，覆盖 t1 备选列表）**：
    //   「失败态 = **保留旧数据但明确标注**（不选清空）：取不到本周期数据时，行情区保留
    //    上一周期的图/价/涨跌/支撑/目标价/步长，但整块必须被明确标注为"不是本周期的数据"：
    //    至少写清**哪个周期 + 多久之前**（例：「上一周期（周线）数据 · 3 分钟前」），
    //    并保证屏上任何数值都能被读成"非本周期"（A1–A3 按"可读成非本周期"落地，
    //    而不是靠清屏）。」
    //
    // 【为什么这样也能满足 A1–A3】原来的病根不是"旧数据留在屏上"，而是**屏上没有任何
    //   一句话说明它是旧的** —— 用户点周线→季线，看到的是上一周期的图与数值，却读不出
    //   "这不是本周期"。所以判据是「**可读成非本周期**」，不是「必须清空」。
    //   清空是 t1 给的备选之一，用户明确没选它（旧数据有信息价值，抹掉反而更糟）。
    //
    // 【标注位置】`mkt_srcname`（价格块右上第三行，`page_mkt.xml:677-684`，
    //   **无 visibility 属性 ⟹ 恒可见**；实测 uiautomator dump 里在，而 stateline /
    //   srcrow / mkt_status / mkt_legdetail 全是 `gone`、dump 里节点都不存在）。
    //   即 t1 的「最小可行方案」，也是本任务选的这一种（**没有**点亮 stateline ——
    //   用户 2026-10-03「状态行不上屏」的裁定仍然有效）。
    //
    // 【三个状态】`t2Stale`：
    //   0 = 屏上就是本周期的数据（正常）
    //   1 = 本轮取数还没回来，屏上仍是**上一个周期**的（用户点下去**立刻**就该看到这句）
    //   2 = 本轮取数**失败**，屏上仍是上一个周期的（停在这句上，不再变）
    /**
     * 本轮请求的周期（`mkLoad` 写入，只用于判「切没切周期」`tfChanged`）。
     * ⚠ **它不代表屏上是什么** —— 用户刚点周线那一刻，它是 `W` 而屏上还是月线的图。
     *   要"屏上那个周期"请读 [t2ShownTf]。两个概念混用会把标注写反（实测踩过）。
     */
    private var lastTf = ""
    /**
     * ⚠⚠⚠ 2026-10-15 t6（评审 t4 第 1 条）**「屏上数据的周期」独立成一个变量**。
     *
     * 【为什么要独立】改前这个值是从 `lastTf`（请求周期）**派生**的：
     *   `t2TakeDisplayedTf()` 在 `mkLoad` 里把 `lastTf` 抄进 `t2ShownTf`，抄完 `lastTf` 立刻
     *   被改成新请求的周期。于是它只在"切周期那一刻"是对的 —— 之后 `lastTf` 再变（用户连点
     *   两个周期、或换品种后又点周期），它**不会**跟着变，屏上换了一批数据它也不更新。
     *   ⟹ 现在**只有一个写者**：`renderAgg` 成功画完时写（屏上真的是这批了）；
     *      `clearForNewSymbol` 里清空（屏上已经没数据了）。派生那一步整个删掉。
     */
    private var t2ShownTf = ""
    /** 屏上这批行情是**什么时候**画上去的（毫秒）；失败态要拿它算「多久之前」。 */
    private var t2ShownAt = 0L
    /** 屏上这批行情画出来的根数。撤标注时重画源名要用它（别拿 0 去重画，那会打成「未取到」）。 */
    private var t2ShownCount = 0
    /**
     * 屏上这批数据**是不是落盘留底**（`MktSave.saved`）画上去的。
     *
     * ⚠⚠⚠ 2026-10-16 t8（队长把 t6 §7.0 的观察收进本轮）：判据是**同一条** ——
     *   标注不许断言屏上并不存在的东西。
     *
     * 【为什么要分开说】`MktSave.saved` **只认同一周期**（用户 2026-10-09 裁定）⟹
     *   「断网冷启动 / 本轮没取到、拿留底顶着」时，屏上其实是**本次请求那个周期**的数据，
     *   而改前标注一律写「上一周期（X）数据」—— **把本周期说成了别的周期**。
     *   与 F1 是同一类错，只是换了触发路径（F1 是"名字取错变量"，这条是"名字选错措辞"）。
     *   ⟹ 留底 ⟹ 「**上次取到的（X）数据**」；真跨周期 ⟹ 「上一周期（X）数据」。
     *   ⚠ 两条都满足用户裁定要的"写清**哪个周期** + **多久之前**"，只是把"这份数据是哪来的"
     *     也说准了 —— 用户原话要的是"读得出它不是本周期的"，而不是必须写"上一周期"四个字。
     */
    private var t2ShownSaved = false
    /**
     * **本轮渲染**的数据来源：true = 落盘留底（`MktSave.saved`）。
     * ⚠ 取数侧每轮开头复位（[t2SrcReset]），否则上一轮的留底会把下一轮的真取数也说成"上次取到的"。
     */
    private var t2SrcSaved = false
    /**
     * 屏上这份数据的**真实年龄**（毫秒，来自 `MktSave.savedAgeMs`）—— 只有留底路径有值。
     *
     * ⚠⚠ 2026-10-16 t12（评审 t9 的 **R2-1**）：改前「多久之前」取 `t2ShownAt`（画上屏那一刻），
     *   留底路径上"静置 75s 后冷启动"会写成「刚刚」，而数据已 ~95s 旧 ⟹ 冒充新鲜。
     *   现在留底路径说**数据本身的年龄**（见 [t2OldNote]）。
     */
    private var t2ShownAgeMs = 0L
    /** **本轮渲染**的数据年龄（毫秒）；只有留底路径会置位。见 [t2ShownAgeMs]。 */
    private var t2SrcSavedAgeMs = 0L
    /**
     * **本轮渲染读一次的那份报价快照**（`renderAgg` 开头取一次）。
     *
     * ⚠⚠⚠ 2026-10-15 t6（评审 t4 第 2 条）：改前 `price`（`renderAgg`）、`chg`
     *   （`t106PaintMeta`）、源名（`t102PaintSourceRow`）**各自**调一次
     *   `MktData.liveQuoteOf()`。三次读之间报价可能过期（`fresh()` 是**随时间变**的判据）
     *   ⟹ 同一屏上出现"价是实时价、标签写收盘价"这类**同屏不同源**。
     *   ⟹ 现在一轮渲染只读一次，存这里，三处都读这个快照。
     */
    private var t2Snap: MktData.LiveQuote? = null
    /** 见上方三态说明。 */
    private var t2Stale = 0

    /**
     * 价格块右上那行的**唯一口径**：源名 + 「实时 / 收盘价」（t10 / 评审 N2）。
     *
     * @param q      **本轮那份报价快照**（`t2Snap`）。
     * @param live   这一轮**有没有命中腿**（"真取到了数"）。
     *
     * ⚠⚠⚠ 2026-10-16 t12（评审 t5 的 **R5，回归**）**口径按市场分开** —— 这是本函数最容易改坏的地方：
     *
     *   · **Nasdaq（美股）**：`live` 只说明"那条腿回了数"，**不说明数据是哪一刻的** ——
     *     美股 K 线来自 `historical`，它**只回已完成交易日** ⟹ 现价天生是 T-1 收盘。
     *     ⟹ 必须看**快照** `q.realtime` 才配写「实时」（否则「收盘价 · Nasdaq · <日期>」）。
     *   · **其余市场（港/韩/A股/币/贵金属/商品）**：它们是**盘中 / 24h** 源，腿回的就是当时的价，
     *     **没有** `/info` 这种"另一个接口才给实时价"的问题；而 `LIVE` 的写者限定了
     *     `symType=="stock"` ⟹ 这些品种的 `q` **恒为 null**。
     *     ⟹ **命中腿即实时**（用户实报反例：BTCUSDT 屏上 `82,231.02 / -1392.57 (-1.67%)`
     *       配着「收盘价 · Binance · 09/30」—— 币安 24h 源被写成了收盘价，还挂了个 09/30 的日期）。
     *
     * ⚠ t10 的 N2 修法把 `lq = t2Snap` 用到了**所有**品种上，于是非美股全被推去「收盘价」那一支 ——
     *   那就是 R5 这条回归。现在只对 Nasdaq 走快照闸，其余走命中腿口径。
     */
    private fun t2QuoteLabel(vendor: String, q: MktData.LiveQuote?, live: Boolean): String {
        val isNq = vendor == "Nasdaq"
        if (isNq && q != null && q.realtime) {
            // 带上**成交时刻**（`12:22 ET`）：命中缓存那一轮不发请求，报价是上一次取数当时的那批 ——
            // 印出时刻，用户自己看得出它有多新（见 MktData.liveClock）。
            val ck = MktData.liveClock(q.tradeTs)
            return "实时 · " + vendor + (if (ck.isEmpty()) "" else " · " + ck)
        }
        // 非 Nasdaq：命中腿就是当时的价（盘中/24h 源），照旧写「实时」
        if (!isNq && live) return "实时 · " + vendor
        // ⚠ 退化时把日期补上（路线乙：必须带日期）。先认快照里的成交日；
        //   没有快照（/info 压根没取到）就用**屏上这批 K 线末根的日期**（t2DataDate，数据里就有）；
        //   连它都没有才不带日期 —— 宁可少说，不编。
        val d = if (isNq) q?.tradeDay?.takeIf { it.isNotEmpty() } ?: t2DataDate() else t2DataDate()
        return "收盘价 · " + vendor + (if (d.isEmpty()) "" else " · " + d)
    }

    /**
     * 屏上这批数据**真实日期的 `MM/dd`**，给「收盘价 · Nasdaq · <日期>」补日期用。
     *
     * ⚠ 2026-10-15 t6（评审 F4）：路线乙（如实标收盘价）**要求带日期**，而改前那一段只在
     *   `/info` **取到了**的时候才有日期（`lq.tradeDay`）—— 恰恰与"取不到实时价"的语境相反。
     *   ⟹ 退化时改从**屏上这批数据**里取：`lastKs` 是 `renderAgg` 画上去的那一份（数据里本来就有，不是编的）。
     *
     * ⚠⚠⚠ 2026-10-16 t12（评审 t5 的 **R5b**）：**K 线的 `t` 是"桶起点"，不是数据日期** ——
     *   月线的桶起点是当月 1 号，`2026-10-01T00:00Z` 换算到美东（GMT-4）**退成 09/30**，
     *   而屏上那份数据的真实日期是 **10/14**。改前直接把桶起点当日期 ⟹ 印出 `09/30`（读起来像 9 月的旧数据）。
     *   ⟹ 现在**按周期把桶起点还原成桶内真实日期**：
     *     日线/周线（桶起点在桶内）→ 直接用；**月线 +2 个月**；**季线 +3 个月**（都落在桶的最后一个月）。
     *   ⚠ 只对**屏上这个周期**做还原（`t2ShownTf` 在 `renderAgg` 里写的就是 `tf`），
     *     周期不明时按日线处理（不编）。
     *   ⚠ 时区用美东（GMT-4）：美股 K 线的日期口径就是交易所日，与 `/info` 的
     *     `lastTradeTimestamp` 同一口径（实测两者同月同日）。
     *   ⚠ 取不到（屏上还没数据 / t 为 0）返回空串 —— 宁可少说，不编。
     */
    private fun t2DataDate(): String {
        val t = lastKs.lastOrNull()?.t ?: return ""
        if (t <= 0L) return ""
        // ⚠⚠ 2026-10-16 t12（R5b）**必须先在 UTC 里还原，再换美东显示** ——
        //   桶起点是按 **UTC** 定的（`MktData.resampleDaily`：`set(y, m, 1)` 用的是 UTC 日历），
        //   而 `2026-10-01T00:00Z` 在美东（GMT-4）是 **09/30 20:00** ⟹ 直接按 GMT-4 格式化会**退一天**
        //   （月桶退到上个月 = 09/30，季桶退到上个季度末）。这就是改前印出 09/30 的机制。
        //   改法：在 UTC 里读桶，**把桶推进到桶内最后一天**，再换 GMT-4 显示：
        //     日线/周线（桶起点在桶内）→ 当天/当周（周桶起点是周一，+6 天=周日，仍是那一周）；
        //     **月线 → 当月末**（`2026-10-01` → `2026-10-31`）；**季线 → 当季末**（→ `2026-12-31`）。
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val cal = java.util.Calendar.getInstance(utc)
        cal.timeInMillis = t
        when (t2ShownTf) {
            "月线" -> cal.add(java.util.Calendar.MONTH, 1)
            "季线" -> cal.add(java.util.Calendar.MONTH, 3)
            "周线" -> cal.add(java.util.Calendar.DAY_OF_MONTH, 6)
        }
        cal.add(java.util.Calendar.DAY_OF_MONTH, -1)   // 桶的**最后一天**（月/季桶的起点是 1 号）
        // ⚠⚠ 2026-11-05 这里**必须按 UTC 格式化**：上面的 cal 已经在 UTC 里把桶推到
        //   「桶内最后一天 00:00Z」，再按 GMT-4 显示就会退到前一天（实测 11/30 → 11/29），
        //   而这一行正是「收盘价 · 源名 · <日期>」的日期来源。桶本身是 UTC 定的，显示也用 UTC。
        val f = java.text.SimpleDateFormat("MM/dd", Locale.US)
        f.timeZone = utc
        return f.format(cal.time)
    }

    /**
     * 「现价 / 涨跌 / 源名」是否**同源**（评审 t4 第 2 条要求的回归断言）。
     *
     * 【判据】屏上价格必须等于本轮快照里的 `price`（同一份 `/info` JSON 的 `lastSalePrice`）。
     *   ⚠ 只查「本该是实时价」的那一支（`realtime`）——降级态下价格**本来就该**是
     *     historical 末根，拿快照去比才是错的。
     *   ⚠ 用**已画在屏上的文本**（`price.text`）反解，而不是拿局部变量 —— 断的就是"屏上那个数"。
     *   ⚠ 一条 `Log.w`，不是异常、不拦渲染：这是**可观测信号**（用户报障时按 `MktQuote` 抓）。
     */
    private fun t2AssertSameSource() {
        val q = t2Snap ?: return
        if (!q.realtime) return
        val shown = price.text?.toString()?.replace(",", "")?.toDoubleOrNull() ?: return
        if (kotlin.math.abs(shown - q.price) > 0.005) {
            android.util.Log.w("MktQuote", "同源断言不成立: 屏上=" + price.text +
                " 快照=" + q.price + " ts=" + q.tradeTs + " sym=" + lastSym)
        }
    }

    /**
     * ⚠ 2026-10-27 用户裁定：价格卡右上那行**不再写「实时 / 收盘价」**，改写
     * **「源名 · 多久之前」**（例：`Nasdaq · 3 分钟前`）。
     *   · 同批改动：自选行移除了「旧 N 分钟前」（见 `FavPanel.flagOf`）——
     *     数据新鲜度现在**只在这一处**显示。
     *   · 年龄由 [t118Ago] 从**本批腿的产生时刻**算（`legsAt`），不是"画上屏那一刻"；
     *     拿不到时刻就只写源名，**不写"刚刚"**（没有时刻就是不知道，不编）。
     *   · [t2QuoteLabel] 保留但已无调用点：它是「实时 / 收盘价 + 成交时刻 / 日期」那套口径的
     *     唯一实现，日后要恢复"实时"字样直接换回来即可。
     */
    /**
     * 源名行给的是**这个价的时刻**，不是这一轮取数的时刻。
     *
     * ⚠⚠ 2026-11-05 用户实报：「标签显示刚抓取到数据，但是价格并未更新」。
     *   病根就在这里 —— 改前一行恒为 `源名 · N 秒前`，而 N 是 **legs 的取数时刻**：
     *   收盘后每刷新一次它都重新报「刚刚」，可屏上那个价是最近一个交易日的收盘价（不再变）。
     *   标签替数据说了"我很新"，正是本项目反复要消灭的那类谎。
     *   ⟹ 股票（Nasdaq）：盘中写**成交时刻**（`12:22 ET`），收盘写**成交日 + 收盘**
     *     （`11/04 收盘`）；非股票维持原样（盘中/24h 源，命中腿就是当时的价）。
     */
    private fun t2PriceLabel(vendor: String, legsAt: Long): String {
        val q = t2Snap
        if (vendor == "Nasdaq") {
            if (q != null && q.realtime) {
                val ck = MktData.liveClock(q.tradeTs)
                return vendor + (if (ck.isEmpty()) "" else " · " + ck)
            }
            val d = q?.tradeDay?.takeIf { it.isNotEmpty() } ?: t2DataDate()
            return vendor + (if (d.isEmpty()) "" else " · " + d + " 收盘")
        }
        return t2AgeLabel(vendor, t118Ago(legsAt))
    }

    private fun t2AgeLabel(vendor: String, age: String): String =
        if (age.isEmpty()) vendor else "$vendor · $age"

    /** 「3 分钟前 / 2 小时前 / 刚刚」——只说得出事实的粒度，不编。 */
    private fun t2Ago(ms: Long): String {
        val d = System.currentTimeMillis() - ms
        if (ms <= 0L || d < 0L) return "刚刚"
        return when {
            d < 60_000L -> "刚刚"
            d < 3_600_000L -> "${d / 60_000} 分钟前"
            d < 86_400_000L -> "${d / 3_600_000} 小时前"
            else -> "${d / 86_400_000} 天前"
        }
    }

    /** 那句标注：「上一周期（周线）数据 · 3 分钟前」/「上次取到的（周线）数据 · 3 分钟前」。
     *  ⚠ 顺序刻意——**周期在前**，截断也读得出。
     *
     *  ⚠⚠⚠ 2026-10-16 t12（评审 t9 的 **R2-1**，队长按用户裁定①「写清多久之前」纳入）：
     *    「多久之前」**必须说数据本身的年龄**，不能说"画上屏那一刻"。
     *    改前用的是 `t2ShownAt`（`renderAgg` 把数据画上去的时刻）⟹ 留底路径上，
     *    「静置 75s 后冷启动、画上屏」会写成「**刚刚**」，而那份留底数据已经 ~95s 旧 ——
     *    用户按这行字会以为数据是新鲜的（正是本任务要消灭的"冒充当前"）。
     *    ⟹ 留底路径改用 `MktSave.savedAgeMs` 的**真实数据年龄**（`t2ShownAgeMs`，见 [t2SrcReset]）。
     *    ⚠ 真取数路径**照旧**用 `t2ShownAt`：数据就是这一轮刚拿回来的，两个口径此时等价。
     */
    private fun t2OldNote(): String {
        val nm = t2ShownTf
        val age = if (t2ShownSaved) {
            // 留底：说数据的真实年龄（savedAgeMs 是 MktSave 记的落盘时间，读出来不是编的）
            if (t2ShownAgeMs > 0L) " · " + t2AgoOfAge(t2ShownAgeMs) else ""
        } else if (t2ShownAt > 0L) {
            " · " + t2Ago(t2ShownAt)
        } else ""
        if (nm.isEmpty()) return "屏上为旧数据" + age
        // ⚠ 留底 = **同一周期**（MktSave.saved 只认同周期）⟹ 不能说成"上一周期"（见 t2ShownSaved）
        return if (t2ShownSaved) "上次取到的（" + nm + "）数据" + age
        else "上一周期（" + nm + "）数据" + age
    }

    /** 把**已过去的毫秒数**说成人话（与 [t2Ago] 同一粒度，只是入参是年龄而不是时刻）。 */
    private fun t2AgoOfAge(ageMs: Long): String {
        if (ageMs <= 0L) return "刚刚"
        return when {
            ageMs < 60_000L -> "刚刚"
            ageMs < 3_600_000L -> "${ageMs / 60_000} 分钟前"
            ageMs < 86_400_000L -> "${ageMs / 3_600_000} 小时前"
            else -> "${ageMs / 86_400_000} 天前"
        }
    }

    /**
     * 取数侧每轮开头复位「本轮数据来源」标记（`mkLoad` 里调一次）。
     * ⚠ 不复位的话上一轮的留底会把下一轮的真取数也说成"上次取到的"。
     * ⚠ t12：年龄标记一起复位（见 [t2SrcSavedAgeMs]）。
     */
    private fun t2SrcReset() {
        t2SrcSaved = false
        t2SrcSavedAgeMs = 0L
    }

    /** A fresh network result supersedes an earlier same-cycle disk fallback. */
    private fun t2SrcFresh() {
        t2SrcSaved = false
        t2SrcSavedAgeMs = 0L
    }

    /** 取数中 / 失败：把「屏上是上一个周期的数据」写进唯一可见的那一行。 */
    private fun t2MarkStale(failed: Boolean = false) {
        // ⚠⚠ 2026-10-16 t10（评审 t7 的 N1）**空屏不许沉默**。
        //   改前这里在屏上还没数据时直接 `return` —— 那条路径**一句文案都不写**，
        //   用户点了周期、断网、屏上是空的，`mkt_srcname` 还留着上一轮的「实时 · Nasdaq」，
        //   或者干脆是空节点（t7 的受控复现：`mkt_srcname` 读出来是空）。
        //   ⟹ 现在**照样走标注**，只是文案按"屏上有没有数据"分两支：
        //     屏上有数据 → 「上一周期（X）数据 · N 分钟前」（可读成非本周期）；
        //     屏上**没有**数据 → 「未取到」/「取数中」，**不带周期名**
        //       （屏上什么都没有 ⟹ 没有"哪个周期"可说，带周期名就是编 —— t3 的 O3）。
        //   ⚠ `t2Stale` **无条件先置位**：置位后 [t2ClearStale] 才会在成功那一轮把它撤掉；
        //     提前 return 会让状态停在"正常"，下一轮的成功渲染就不撤了。
        t2Stale = if (failed) 2 else 1
        t102PaintSourceRow(0)
    }

    /** 取数成功：屏上已经是本周期的了，标注必须撤掉（否则又变成新的骗人）。 */
    private fun t2ClearStale() {
        if (t2Stale == 0) return
        t2Stale = 0
        // ⚠ 用**屏上实际的根数**重画，不能用 0 —— 那会走到 `t123PaintNoData()`（写成「未取到」）
        t102PaintSourceRow(t2ShownCount)
    }

    /**
     * 清空行情块（价格/涨跌/交易所·时点/步长/目标价/支撑/图）。
     *
     * @param clearSource 是否连价格块右上那行源名一起清（换品种时清；t2 起没有别处用它）
     *
     * ⚠ 这个函数是 `clearForNewSymbol()` 的**全体**，2026-10-15 t2 从它里面原样提取
     *   （两处各写一份迟早漏 —— 见下方 2026-10-05 的补丁记录：当年就是"只清价格/涨跌/
     *   来源/目标价/支撑/图表"，漏了交易所·时点与步长阈值两行）。
     *   ⚠ t2 一度用它实现「切周期也清屏」，**已按用户裁定撤掉**（改为保留+标注，见上方）。
     */
    private fun clearQuoteBlock(clearSource: Boolean) {
        try {
            price.text = "—"
            chg.text = ""
            // ⚠⚠⚠ 2026-10-05 补三处（用户实机截图：打开一个**取不到数**的品种后标题已经换了，
            //   屏上却还挂着 **TSLA · 开盘 / 12:00:00 GMT-4** 和 **步长阈值 4.17%**）。
            //   原来这个函数只清价格/涨跌/来源/目标价/支撑/图表 —— 「交易所·盘口」「时间」
            //   这两行小字和步长阈值行**都漏了**，于是换到一个取不到数的品种时，
            //   上一个品种的残留原样留着，看起来就像新品种的数据。
            //   后果不止观感：步长阈值是计算页**自动算网格数量**的输入，
            //   残留会用上一个品种的阈值去算新品种的网格数。
            venueT.text = ""
            timeT.text = ""
            stepTv = 0.0
            stepSym = ""
            stepT.visibility = View.GONE
            if (clearSource) {
                srcName.text = ""
                srcName.setTextColor(act.attrColor("colorFaint"))
            }
            tgt.visibility = View.GONE
            tgtL.text = ""
            tgtV.text = ""
            sup1.text = ""
            sup2.text = ""
            supBlock.visibility = View.GONE
            chart.clear()
            // ⚠ 2026-10-28：图上已经清空 ⟹ 卡头读数必须一起回 0（否则新品种还没取到数时，
            //   卡头挂着上一个品种的「N 根」）。OHLC 表体随整卡删除，不再有「回 5 行 —」这一步。
            paintBlkh(0)
            paintBlkh(0)
        } catch (e: Throwable) {
            // ⚠⚠ 2026-10-15 t6（评审 F3）**不许再空吞**。改前这里 `catch (_: Throwable) {}`：
            //   一旦哪个 View 是 null，屏上会**静默**留下上一个品种的价格/支撑/图 ——
            //   正是本任务要消灭的"界面不告诉你发生了什么"。现在至少留一条可抓的日志。
            //   ⚠ 仍然**不抛出**：清屏失败不该让 App 崩（这层防御是 2026-10-03 加的，
            //     当时的语境是换品种清屏，崩了比留着旧数据更糟）。
            android.util.Log.w("MktUI", "clearQuoteBlock 失败（屏上可能有残留）", e)
        }
    }

    /**
     * 无数据态：源状态行打空心点 + 「暂无可用源」，价格块右上那行写「未取到」。
     *
     * ⚠ 2026-10-15 t6（评审 F6）：**形参 `more` 与它的分支已删**。
     *   t2 一度加过 `more: String = ""`（想把失败原因拼进 `srcName`），但改成
     *   「保留旧数据 + 标注」之后**一个调用方都没传过** —— 全仓 5 处调用点都是
     *   `t123PaintNoData()`，那个分支永远走不到。零调用方的形参就是死代码，删掉；
     *   失败态的文案现在由 [t2MarkStale] → [t102PaintSourceRow] 的 `t2Stale` 分支统一给。
     */
    private fun t123PaintNoData() {
        // ⚠ 2026-10-03 用户裁定「图里的东西不要了」⟹ 本行(● 源 · N 根 · 展开)整行不再上屏。
        //   这里原本是 srcRow.visibility = View.VISIBLE,正是它把布局里那个 GONE 又顶回可见,
        //   只改 XML 删不掉 —— 运行期会被重新点亮。下面照样写文案/圆点(数据还在),
        //   但**不再改可见性**,让布局的 GONE 说了算。
        srcDot.setBackgroundResource(R.drawable.srcdot_off)
        srcMain.text = "暂无可用源"
        srcMore.text = if (legDetail.visibility == View.VISIBLE) "收起" else "展开"
        // 同一时刻价格块右上那行也要跟着说「未取到」——不许留上一轮的「实时 · 源名」残留。
        // ⚠ 2026-10-15 t2：全屏唯一可见的 mkt_* 文本承载位就是这一行
        //   （`mkt_srcname`，page_mkt.xml:677-684 无 visibility 属性；实测 uiautomator
        //   dump 里在，而 stateline / srcrow / mkt_status / legdetail 全是 `gone`、dump 里
        //   节点都不存在）⟹ 失败原因只能写在这里（最小侵入，不动布局）。
        srcName.text = "未取到"
        srcName.setTextColor(act.attrColor("colorFaint"))
        // t31 收尾: 这里原来调一个状态点自检函数(整函数已删) —— mktpage 加的自检,
        // 真机用 logcat 按 tag 核对「空文案时状态点绝不能是实心」; t6 验完即删。
    }

    /**
     * 已下线市场（港股 00700 / A 股 sh688825）的**可见**提示。
     *
     * 【为什么要单独一支】2026-11-03 t11（t3 实测）：取数入口虽然抛了 [MktData.unsupportedText]，
     *   但那条文案被 [mkStatus] 写进页底 `stateline` —— 它在布局里是 `gone`，
     *   uiautomator dump 里连节点都没有；屏上只剩 [t123PaintNoData] 的「未取到」，
     *   用户读不出"这个市场不支持"，只会当成源挂了、反复重试。
     * ⟹ 改写到全屏唯一可见的 `mkt_srcname`（[srcName]），颜色按错误档（colorRed）；
     *   隐藏的源名/页底行同步写同一句（**不新增布局、不改结构、不改可见性**）。
     * ⚠ 文案唯一来源是 [MktData.unsupportedText]：这里不另写措辞，免得两处说岔。
     */
    private fun paintUnsupported(su: String) {
        val msg = MktData.unsupportedText(su)
        srcName.text = msg
        srcName.setTextColor(act.attrColor("colorRed"))
        srcDot.setBackgroundResource(R.drawable.srcdot_off)
        srcMain.text = msg
        mkStatus(msg, true)
    }

    /** 状态点是否实心。判据必须看背景 drawable,不能看文本 —— 圆点已按 t393 的结论
     *  换成 srcdot_on/off; 系统字体里那两个圆点字形是两套独立字形,取不到环的描边粗细。
     *  记的是一条硬纪律: Drawable 的 `==` 比的是**引用**不是内容, 同一个 drawable 资源
     *  被两个 View 各 get 一次会得到两个对象, 直接比永远为 false ⟹ 必须比 constantState。
     *  **保留原因**: 本函数是这条判据在产物里**唯一的活体示例**, 删了这条知识就没了
     *  (零收益: Kotlin 编译器不发 UNUSED_FUNCTION, 删它不省任何编译输出)。
     *  ⚠ **当前无调用者** —— 唯一调用它的自检函数已于 t31 删除。**这不是缺陷, 别当死代码删掉**,
     *  也别以为它在跑。真要用这条判据, 照上面那段写。 */
    private fun isSolidDot(): Boolean = srcDot.background?.constantState ==
        act.getDrawable(R.drawable.srcdot_on)?.constantState

    // 稿 L328-340:逐腿明细是**每腿一个独立行**(flex + alignItems:baseline + gap 10 +
    //   padding '11px 16px 11px 32px' + 每行 borderTop 1px),**不是单 TextView 用换行符拼**。
    //   自选屏 t324 已按稿改成独立行, 行情屏原先是单 TextView 换行拼接 —— 同一规则漏改。
    // ⚠ mkt_legdetail 的 **id 与控件类型(TextView)都没动**:硬约束「控件类型不许换」,
    //   Kotlin 侧是 `private val legDetail: TextView`, 换成 LinearLayout 会 ClassCastException。
    //   所以独立行由运行期插在 mkt_legdetail **之后** —— 它继续当「详情是否展开」的唯一
    // ⚠⚠ 2026-10-03 删除 [legRowHost]/[legRowViews]/[syncLegRows]/[paintLegRows] 整块。
    //   崩溃元凶：paintLegRows 用「预先算好的下标」往父容器插行
    //   （host.addView(row, at + legRowViews.size)），在 ScrollView 绘制途中
    //   导致 ViewGroup.dispatchDraw 读到空子 View：
    //   NullPointerException: ...android.view.View.mViewFlags... on a null object
    //   at android.view.ViewGroup.dispatchDraw(ViewGroup.java:4289)
    //   （二分法确认：停掉本函数即不复现）。源状态行本就已按用户裁定删除，
    //   这套行插入机制是它的残留物 —— 一起删干净，不留空壳。


    /** 「多久之前」的自刷 token：见 [t102PaintSourceRow] 末尾。 */
    private var srcAgeTick = 0

    private fun t102PaintSourceRow(count: Int) {
        // ⚠⚠ 2026-10-12 t2：**按当前品种取腿**（`MktData.legsOf(curSym())`），
        //   不再读原来那个**全局不分品种**的 lastLegs。完整病根见 MktData.legsBySym
        //   上方那段墓碑（原全局 lastLegs 的现象/机制/修法）：
        //   全局 lastLegs **不分品种**、每次取数整体覆盖 —— 走**缓存命中**（不调 fetchStocks、
        //   不发布新腿）或**取数未回**时，这里读到的是**上一个品种**的腿。
        //   用户实测：先看韩股（Naver 兜底命中）→ 切 TSLA → 源名行显示「实时 · Naver」，
        //   而 fetchNaver 全仓只在 kr 腿上、TSLA 根本没投过它。
        //   ⟹ 品种不匹配时 legs 为空 → 走下方 t123PaintNoData() 显示「未取到」，
        //      **不显示别人的腿**；韩股自身发布时品种一致，显示逐字不变。
        val legs = MktData.legsOf(curSym())
        val legsAt = MktData.legsAtOf(curSym())   // 同批记账：不是当前品种的时刻一律 0
        // ⚠⚠⚠ 2026-10-15 t2 **这一段必须排在所有早退之前**（用户裁定：失败态 = 保留旧数据
        //   但明确标注；标注位置就是这一行 `mkt_srcname`）。原函数在 `count<=0 || legs.isEmpty()`
        //   时直接 `t123PaintNoData(); return`，那条路径会把"屏上是上一周期的数据"这句
        //   一并吞掉 —— 恰恰是取数没回来/失败时最需要它的场合。
        //   三态说明见 [t2Stale]；0 = 正常，1 = 取数中，2 = 失败。
        if (t2Stale != 0) {
            // ⚠ t10（评审 N1）：屏上**没有**数据时不带周期名（屏上什么都没有，没有"哪个周期"可说）
            srcName.text = if (t2ShownTf.isEmpty()) {
                if (t2Stale == 1) "取数中" else "未取到"
            } else {
                (if (t2Stale == 1) "取数中，" else "未取到 · ") + t2OldNote()
            }
            // 灰档：这句说的是"屏上的东西不是本周期的"，不是"数据是新的"
            srcName.setTextColor(act.attrColor("colorFaint"))
            return
        }
        // t123 不变量 —— 一根都没有时,不许打实心点,也不许留空文案。
        // 真因:t102PaintSourceRow 原来**只在成功路径被调**,无数据时整行根本不重画,
        // 圆点停在**布局默认的实心态**、文案停在**布局默认值空** —— 最坏的情况被打成"正常"。
        if (count <= 0 || legs.isEmpty()) { t123PaintNoData(); return }
        // ⚠ 2026-10-03 同 t123PaintNoData:源状态行按用户裁定不再上屏,
        //   删掉这里的 visibility = View.VISIBLE(布局里已是 GONE),否则运行期会重新点亮。
        // ⚠ 2026-10-02：价格块右上那一行「实时 · 源名」在这里同步。
        //   稿把「这个价是谁给的、是不是实时」放在**价格旁边**（v2.html:2126-2128），
        //   改前 App 只有下面那条源状态行，右侧价格旁边是空的。
        //   实时 = 本次真取到（有命中腿）⟹ 决策紫；否则「收盘价 · 源名」或「未取到」⟹ 第三档灰。
        //
        // ⚠⚠⚠ 2026-10-15 t2 **「实时」二字必须由数据本身挣来**（用户实报：美股右上角写
        //   「实时 · Nasdaq」，现价却是 10/14 的收盘）。
        //   改前这里的判据是「本次有没有命中腿」—— 命中的是**腿**（一个源名），
        //   它**不说明数据是哪一刻的**。美股 K 线来自 Nasdaq `historical`，它只回已完成
        //   交易日 ⟹ 屏上那个价天生是 T-1 收盘，而这里照样写「实时」。
        //   现在对 Nasdaq 追加一道**数据口径闸**：本次取数同时拿到了同域 `/info` 的
        //   当日实时报价（`isRealTime` + 成交日是美东今天/昨天）才写「实时 · Nasdaq」；
        //   否则如实写「收盘价 · Nasdaq · <数据日期>」。
        //   ⚠ 拿不到实时价**不算取数失败**（K 线照画），只是这行降级 —— 不许因为
        //     一个标签去把整块行情判死。
        // ⚠⚠ 2026-10-15 t6（评审 F2）**报价只从本轮快照读**（`t2Snap`，`renderAgg` 开头
        //   读一次），不再在这里**另读一次** `liveQuoteOf` —— 两次读之间报价可能过期
        //   （`fresh()` 随时间变），会出现"价是实时价、标签写收盘价"的同屏不同源。
        val hitVendor = legs.firstOrNull { it.hit }?.let { MktData.vendorName(it.name) }
        val lq = t2Snap
        // ⚠⚠⚠ 2026-10-16 t12（评审 t5 的 **R5，回归**）：`lq` 只喂给 **Nasdaq** 那一支。
        //   `t2Snap` 只有美股会被填充（`LIVE` 的写者限 `symType=="stock"`），
        //   港/韩/A股/币/贵金属/商品的快照**恒为 null** —— 而它们是**盘中 / 24h** 源，
        //   命中腿回的就是当时的价。t10 把快照闸用到了所有品种上，于是币安 24h 源被写成
        //   「收盘价 · Binance · 09/30」（用户实报反例），那就是这条回归。
        //   ⟹ 口径按市场分开：**只有 Nasdaq 走快照闸**，其余「命中腿即实时」（见 [t2QuoteLabel]）。
        srcName.text = when {
            // 有命中腿：按**命中的那条腿**报源名，再按该市场的口径定「实时 / 收盘价」
            hitVendor != null -> t2PriceLabel(hitVendor, legsAt)
            // ⚠⚠⚠ 2026-10-16 t10（评审 t7 的 N2）**这一支也必须读同一份 `t2Snap` 定口径**。
            //   改前写的是 `"收盘价 · " + (hitVendor ?: "未决")` —— 两个毛病：
            //     ① 口径没定：**只要没有命中腿就一律说"收盘价"**，而那一轮完全可能拿到了
            //        当日实时价（缓存命中那一轮就是这样：不发请求、但有 5 分钟内的报价）；
            //     ② `hitVendor` 在这里**恒为 null**（这个分支的条件就是它 == null），
            //        所以屏上永远印着内部术语 **「未决」** —— 用户看不懂，也不是事实。
            //   ⟹ 现在用与现价/涨跌**同一份快照**：`realtime` ⟹ 「实时 · Nasdaq · <成交时刻>」，
            //     否则「收盘价 · Nasdaq · <日期>」（日期用 `t2DataDate()`，与 F4 同一条）。
            //   ⚠ 「源名」取 `legs` 里**本品种真实腿链**的名字（`MktData.legNames`）——
            //     腿链再改，文案自动跟随；拿不到腿名才退成"未取到"（不编源名）。
            else -> {
                val nm = runCatching { MktData.legNames(curSym()).firstOrNull() }.getOrNull()
                // ⚠ 2026-11-05 同一口径：这里也必须走 [t2PriceLabel]。改前用取数年龄，
                //   于是「有腿名但没有命中腿」这一支仍会把收盘价标成「Nasdaq · 33 秒前」。
                if (nm.isNullOrEmpty()) "未取到" else t2PriceLabel(nm, legsAt)
            }
        }
        // ⚠ 2026-10-27 文案里已经没有「实时」二字，颜色改按**数据是否真实时**判（判据与改前同源）：
        //   非 Nasdaq 命中腿即实时；Nasdaq 只认快照 `realtime`（口径说明见 [t2QuoteLabel]）。
        val liveNow = if (hitVendor != null && MktData.symType(curSym()) != "stock") true
                      else lq?.realtime == true
        srcName.setTextColor(act.attrColor(if (liveNow) "colorPrimary" else "colorFaint"))
        // ⚠ 2026-10-15 t6（评审 F2 的回归断言）：屏上价格与这份快照必须同源
        t2AssertSameSource()
        // t118:这一行**只说用户需要知道的事**:数据从哪来、有多新、多少根。
        // 竞速内部状态(有没有某条腿命中)是实现细节,**禁止上屏**。
        // 四种情形,圆点形状与文字**必须一致**:
        //   本次真取到 -> 实心点  源名 · N 根 · N 秒前
        //   无本次命中 -> 空心点  显示上次的数据(不点源名:源名对留底无意义)
        val hitLegs = legs.filter { it.hit }
        val hitName = hitLegs.firstOrNull()?.name
        if (hitName != null) {
            srcDot.setBackgroundResource(R.drawable.srcdot_on)
            srcMain.text = MktData.vendorName(hitName) + " · " + count + " 根 · " +
                t118Ago(legsAt)
        } else if (legsAt > 0L) {
            // 没取到但有留底 —— 如实说"这是上次的",不点任何源名
            srcDot.setBackgroundResource(R.drawable.srcdot_off)
            srcMain.text = "显示上次的数据 · " + count + " 根 · " + t118Ago(legsAt)
        } else if (legs.isNotEmpty()) {
            // 留底确实有数据，但旧格式没保存产生时刻：不要显示「0 秒前」或「暂无源」。
            srcDot.setBackgroundResource(R.drawable.srcdot_off)
            srcMain.text = "显示上次的数据 · " + count + " 根 · 时间未知"
        } else {
            srcDot.setBackgroundResource(R.drawable.srcdot_off)
            srcMain.text = "暂无可用源"
        }
        srcMore.text = if (legDetail.visibility == View.VISIBLE) "收起" else "展开"
        // ⚠ 2026-10-27 **相对时间必须自己走**：这一行只在取数/回填时重画，
        //   照字面写上去就"冻住"了 —— 屏上「13 秒前」十分钟后还是「13 秒前」，
        //   那是新的骗人（本次改动的初衷正是不许冒充新鲜度）。
        //   ⟹ 每 30s 自刷一次；每次绘制递增 token，旧 tick 自动失效（不累积定时器）。
        //     离开行情页（isShown=false）就不再续链，回来时由正常渲染重新起链。
        val tick = ++srcAgeTick
        srcName.postDelayed({
            if (tick == srcAgeTick && srcName.isShown) t102PaintSourceRow(t2ShownCount)
        }, 30_000L)
        // t31 收尾: 这里原来调一个状态点自检函数(整函数已删) —— mktpage 加的自检,
        // 真机用 logcat 按 tag 核对「空文案时状态点绝不能是实心」; t6 验完即删。
        // ⚠ 2026-10-03 paintLegRows 调用点已删（函数本身与源状态行一起移除，见上方 init 处的墓碑）
    }

    /** t110 (2) 「搜索」文字入口与图标共用同一套展开逻辑(只展不收,同稿)。 */
    private fun t110OpenDock() {
        // 稿 L524: `onClick={()=>setQopen(!qopen)}` —— 是**切换**。
        // 改前这里只有 `dock.visibility = VISIBLE` + 文案写死「收起」,
        // 于是点第二下毫无反应（真 bug，A.3 复核 S3）。
        if (dock.visibility == View.VISIBLE) {
            // ── 收起这一支 ──
            dock.visibility = View.GONE
            // 稿 L527: 文案随展开态切换, 不是永远「搜索」。
            searchTx.text = "搜索"
            val imm2 = act.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as? android.view.inputmethod.InputMethodManager
            imm2?.hideSoftInputFromWindow(symInp.windowToken, 0)
            return
        }
        dock.visibility = View.VISIBLE
        searchTx.text = "收起"
        symInp.requestFocus()
        symInp.post {
            val imm = act.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(symInp, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun t102Cd(ms: Long): String {
        if (ms <= 0) return "已过"
        val s = ms / 1000
        return "${s / 60} 分 ${s % 60} 秒"
    }

    /** t103 稿 paintStepT(:1183-1190):任何品种算得出就显示「步长阈值 X%」,算不出隐藏。 */
    // ⚠⚠ 2026-10-03 加**幂等闸**。起因:实测 logcat 里这函数 10 秒被调 **106 次**,
    //   而且回调里无条件 setText + t103StepCb?.invoke() —— 值没变也照跑,
    //   回调触发重绘 → onInfo → 再调本函数 ⟹ **死循环**,主线程被占死,
    //   屏上「步长阈值」那一行迟迟不参与布局(uiautomator 抓到的是旧状态)。
    //   现在:值与可见性都没变 ⟹ 直接 return,不碰 View、不发回调。
    private var stepInFlight = false
    /** 幂等闸用的：当前显示的原因文案，避免同一句反复重画。 */
    private var stepWhyShown: String = ""
    private fun t103PaintStepT(sym: String) {
        if (localReplay) return
    /** 幂等闸用的：当前显示的原因文案，避免同一句反复重画。 */
        // 稿:1188 —— 换品种先清零,防旧品种阈值参与新区间
        if (stepSym != sym) { stepSym = sym; stepTv = 0.0; stepT.visibility = View.GONE }
        // 同一个品种的阈值已经拿到且已在屏上 ⟹ 不再重复取(MktData 侧本就有 5 分钟缓存,
        // 这里省掉的是「每次重绘都过一次异步 + 主线程回调」的开销)
        if (stepTv > 0.0 && stepT.visibility == View.VISIBLE) return
        if (stepInFlight) return
        stepInFlight = true
        MktData.stepThresholdAsync(sym) { r ->
            // ⚠️ stepThresholdAsync 的回调在**后台线程**(MktData 里起的是 Thread),
            // 碰 View 会抛 CalledFromWrongThreadException —— 必须整段切回主线程。
            act.runOnUiThread {
                stepInFlight = false
                if (stepSym != sym) return@runOnUiThread   // 异步回来时品种已换
          val t = r.v
        val why = r.why
       val wantOn = t != null && t > 0
        // ⚠ 2026-10-05 幂等闸必须**把 why 算进去**：改前这一条只看「没值 + 不可见」就早退，
                if (wantOn) {
         // 于是「要显示原因」的那一次也被当成「什么都没变」挡掉了 ⟹ 原因永远画不出来。
    if (!wantOn && why.isEmpty() && stepTv == 0.0 && stepT.visibility == View.GONE) return@runOnUiThread
      if (!wantOn && why == stepWhyShown && stepTv == 0.0 && stepT.visibility == View.VISIBLE) return@runOnUiThread
                    stepTv = t!!
                    stepTL.text = "步长阈值"
                    stepTV.text = MktData.mkFmt(t * 100.0) + "%"
                    stepTV.setTextColor(act.attrColor("colorInk"))
                    // ⚠ 上一次可能走的是「原因」分支（把标签染成灰了）⟹ 正常值回来时必须把标签色复位。
                    stepTL.setTextColor(act.attrColor("colorSub"))
                    stepT.visibility = View.VISIBLE
                } else if (why.isNotEmpty()) {
       // ⚠⚠⚠ 2026-10-05 没算出来时**把原因显示出来**，不再无声隐藏那一行。
    // 用户真机原话：「我不知道它出现的慢，还是**压根就没有拉取**」。
   // 改前 else 支只做 GONE ⟹「正在取/网络不通/日线不够」在屏上长得一模一样。
  stepTv = 0.0
         stepWhyShown = why
       // ⚠「没算出来的原因」是**一整句话**（如「日线不够」），不是数值 ⟹ 放左侧标签位、
       //   右侧数值位清空（行高由标签撑）；墨色仍走原来的灰（9AA0A6）。
       stepTL.text = "步长阈值 " + why
       stepTV.text = ""
       stepTL.setTextColor(android.content.res.ColorStateList.valueOf(0xFF9AA0A6.toInt()))
       stepT.visibility = View.VISIBLE
   } else {
          stepTv = 0.0
 stepWhyShown = ""
 stepT.visibility = View.GONE
  }
                t103StepCb?.invoke()
            }
        }
    }

    /** 计算页监听:阈值一变就重算格数 */
    private var t103StepCb: (() -> Unit)? = null
    fun onStepT(cb: () -> Unit) { t103StepCb = cb }
    private fun paintTf() {
        // 稿 L261 逐字:
        //   background: val===o[0] ? 'var(--ac)' : 'transparent'
        //   color:      val===o[0] ? ACC : 'var(--ink2)'
        //   fontWeight: val===o[0] ? 600 : 400
        // 改前未选用的是 colorInk(第一档墨色) —— 稿要的是第二档 ink2(A.3 复核 S4③);
        // 而且**字重根本没在这里设**, 是写死在 XML 的 mkt_tf_m 上
        // ⟹ 选周线时「月线」仍然是粗的(A.3 复核 S4④)。
        // 所以现在**每次都重设**字重, 不依赖 XML 的初始值。
        val ink = act.attrColor("colorInk")
        val ink2 = act.attrColor("colorSub")
        // ⚠⚠ 2026-10-16 t52 · **F-5 同族站点（交接 §5.1-① 第 1 处）在方向 B 里的收口方式变了**：
        //   改前：选中段底 = colorPrimary 实底 ⟹ 字色按 onFill 口径取（深色要深墨，白字只有 3.22:1）。
        //   方向 B §2.4-3.6：选中段 = **抬起的药丸**（t49 已把 seg_sel 换成 colorSurfaceRaised + r999，
        //   **明确「不占主色实底」**）⟹ 该站点**不再需要 on-accent 取值**，字色必须是 **colorInk**
        //   （压浅色药丸 #FFFFFF = 18.32:1、压深色药丸 #272E38 = 13.4:1）。
        //   ⚠ 若沿用改前的 colorOnPrimary（白）：浅色下白字压白药丸 = **1.00:1 完全不可读** ——
        //     这是本次移植里最容易被漏掉的一处，drawable 换了而 paint 没换就会静默变白。
        //   on-accent 口径留给**真正的主色实底**：mkt_go（查看）、paintSugSel（候选选中行）
        //   —— 那两处仍是 accentFill 底 + 白字（浅 5.87 / 深 4.80）；第三处「拆股 thumb」已随
        //   手动拆股整行于 2026-10-28 删除。
        // ⚠ 2026-10-16 t52 · **§5.2-B8 按压态**：方向 B 把分段器的按压反馈定成**字色**
        //   （theme.css §6.4 末行 `:active { color: ink }`）。
        // ⚠⚠ 2026-11-04 用户裁定「设计一些他的动画」后，按压反馈改为**缩放 + 字色**两条：
        //   缩放由 [SegTrack.attachPress] 负责（0.97 / 90ms 按下、140ms 回弹），
        //   字色仍由本函数的中选/未选切换负责 ⟹ 不再需要独立的 pressed 档 ColorStateList。
        // ⚠⚠ 2026-11-04 改造：选中态从「按钮自己换底」改为「轨道上的一颗共享药丸」——
        //   药丸是同一个对象在三个位置间移动，所以能做出连续位移（稿原先零动画，本轮为用户新设计）。
        //   · 底：由 `tfRow`（SegTrack）自绘，按钮底一律透明；
        //   · 字：换色 150ms 交叉（字重不可插值，同步切换粗体）；
        //   · 位移：180ms / cubic-bezier(0.2,0.8,0.2,1)（稿 --dur-2 / --ease）。
        //   首次绘制不带动画（没有用户动作就不该有入场动画）。
        val idx = when (tf) { "W" -> 0; "Q" -> 2; else -> 1 }
        val dark = (act.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        // 药丸配色 + 阴影：浅色有 elev-1（自绘，模器也能看见），深色无（稿 --elev-1: none）。
        tfRow.setPillColors(
            pill = act.attrColor("colorSurfaceRaised"),
            shadow = 0x1A101828,          // rgba(16,24,40,~.10) ≈ 稿 elev-1 合成值
            withShadow = !dark,
        )
        tfRow.attachPress(listOf(tfW, tfM, tfQ))
        tfRow.moveTo(idx, animate = tfPillPlaced)
        tfRow.animateTextColors(listOf(tfW, tfM, tfQ), idx, ink, ink2)
        tfPillPlaced = true
    }

    // t174:【非错误一律隐藏】与稿相反 —— 稿的源状态行是「写文本 + err 时加红」,
    // 三态(有值/无源/拉取中)本来就一直显示,只有文本为空才收起。
    // 之前这一版把非错误全 GONE,于是「拉取中」永远看不见,逼出了下面那个特判后门。
    // 颜色与自选页同源:err 中性墨色,非 err 决策色;涨跌红只留给价格本身。
    /** ⑩ 把 MktData 已经区分好的失败原因, 换成稿 L514/L516 的原文。
     *
     *  MktData.fetchMetals L905-L908 早就分好了两类, 这里只做翻译, 不重新判断:
     *      win.anyNoData = true   → 源有响应但没有这个代码  → 稿 L514 none
     *      否则                   → 各源都连不上            → 稿 L516 down
     *  down 那句带缓存时刻, 与自选页 L447 的缓存横幅同源(MktData.legsAtOf(本品种) + t118Ago),
     *  没有缓存时照实只说前半句 —— 稿那句的"展示…缓存数据"在无缓存时不成立, 不能照抄。
     *  ⛔ 认不出来的 message 一律走【原样透传】, 不硬套任何一个稿文案 ——
     *     把一条"诚实但不精确"的信息说成"精确但可能不实", 比前者坏。
     *
     *  ⚠⚠⚠ 2026-10-10 **韩股写死那句：收窄判据（t9）+ 按新腿链改写文案（t12）**。
     *
     *  【改前的病】下面那句韩股文案原来是**无条件**的：只要 `symType=="kr"` 就先匹配、先返回，
     *   把 MktData 传来的 message 整个吃掉 —— 包括 2026-10-10 t6 新增的第三类
     *   「限流冷却中，已跳过（…），当前无兜底源」。两者是**两回事**：
     *     · 第三类 = 「我们这轮**没问**东财」（它被 10 分钟硬熔断 / 20s 软冷却跳过）⟹ **可恢复**，
     *       正确动作是等几十秒再试；
     *     · 写死那句 = 「东财 · Naver **两条腿都问了、都取不到**」⟹ 读起来像品种/代码有问题。
     *   把前者说成后者，用户会去反复检查代码拼写 —— 这正是用户此前实报过的误判形状。
     *
     *   2026-10-10 事实变了：东财实测一直好（HTTP 200 / 240 根），t6 又把「熔断键只有源名 ⟹
     *   黄金那边东财超时把韩股东财腿一起熔断」的跨链连坐修掉，并补了第三类文案。
     *   ⟹ 继续**无条件**断言「两腿皆无」会掩盖真实状态。
     *
     *   真到兜底那一刻，屏上会指着一个**根本不在链上**的源名，用户与后来的人都会读错。
     *   ⟹ 现文案 =「韩股：东财 · Naver 两腿皆无 → 本品种无源」。
     *   ⚠ **耦合规则（下次改腿链必须一起改这里）**：本句点名的源必须与 `MktData.stockLegs`
     *     的 kr 分支**逐字一致**；那两行一旦变了（换源 / 加腿 / 减腿），这句就同步 ——
     *     这正是 t12 存在的理由，别再让文案追不上腿链。
     *
     *  【判据（写清，不靠猜）】写死那句只在「**真的两腿皆无**」时用 —— 即 MktData 报的是
     *   **前两类**（`msg == null` / 含「都没有该品种数据」/ 含「都不可达」）；这三者都意味着
     *   「腿**都投了**，都失败或都返回空」。第三类则意味着「有腿压根没投」，必须原样透传。
     *   ⚠ t6 的第三类文案**刻意不含**那两个子串（见 MktData.allDeadText 的注释），
     *     所以「先判第三类、再判前两类」不会互相误伤。
     *   ⚠ 连带：韩股遇到**认不出来的** message 也从「硬套写死句」改成**原样透传** ——
     *     这正是本函数上面那条 ⛔ 原则要求的（该分支对韩股当前不可达：fetchStocks 只抛这三类）。
     *   ⚠ 非韩股品种**逐字不变**：新增的第一条分支对它们本来就落在 `else -> msg`（同一个字符串）。
     */
    private fun t210FailText(msg: String?, sym: String? = null): String {
        val su = sym?.let { MktData.canonicalSym(it) }
        val isKr = su != null && MktData.symType(su) == "kr"
        // 「真的两腿皆无」：msg==null，或 MktData 报的是前两类（都投了、都失败/都空）
        val bothTried = msg == null || msg.contains("都没有该品种数据") || msg.contains("都不可达")
        return when {
            // ⚠ 2026-10-10 **第三类优先透传**（本次变更的主目的）：t6 的「限流冷却中，已跳过…」
            //   对**所有**市场一视同仁地原样透传 —— 韩股不再被下面那句写死文案盖掉。
            msg != null && msg.contains("限流冷却中") -> msg
            // 韩股那句（稿 L517）：**只在「腿都投了、都没成」时**才用（判据见上面）。
            // ⚠⚠⚠ 2026-10-15 t6：源名**不再写死**，改从 kr 腿链现取（MktData.legNames）。
            //   写死那句**已经烂过一次**，就是本任务要根治的病：2026-10-13 韩股腿由
            //   [东财|kr, Naver|kr] 收成 [Naver|kr]，而这里还写着「东财 · Naver 两腿皆无」
            //   —— 点了一条**已经不在链上**的源，还把单腿说成两腿。
            //   ⟹ 现在「几个源、哪几个源」全由腿链决定，改链不用回来改文案。
            //   （isKr 成立时 su 必非空；空串只可能来自不可达分支，腿名会退化成 stock 那条。）
            isKr && bothTried -> {
                val names = MktData.legNames(su ?: "")
                "韩股：" + names.joinToString(" · ") +
                    (if (names.size > 1) " 两腿皆无" else " 单腿皆无") + " → 本品种无源"
            }
            msg == null -> "行情拉取失败(网络或品种名不对)"
            msg.contains("都没有该品种数据") -> "无此品种 · 源正常但没有这个代码，请检查拼写"
            msg.contains("都不可达") -> {
                // ⚠ 2026-10-12 t2：缓存时刻按**本品种**取（legsAtOf）——
                //   别的品种那批腿的产生时刻对本品种毫无意义，报出去就是编时间；
                //   不匹配 → at=0 → 只说「均不可达」，不带时刻（宁可少说，不说错）。
                val at = MktData.legsAtOf(sym ?: curSym())
                if (at > 0L) "各行情源均不可达 · 展示 " + hhmm(at) + " 缓存数据（" + t118Ago(at) + "）"
                else "各行情源均不可达"
            }
            else -> msg
        }
    }

    private fun hhmm(at: Long): String {
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = at
        return String.format("%02d:%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY),
            c.get(java.util.Calendar.MINUTE), c.get(java.util.Calendar.SECOND))
    }

    private fun mkStatus(t: String, err: Boolean) {
        // #33: 走 mkStatus 就说明进入七态里的非成功档,成功态那行要让位(否则两套真相同屏)。
        // t321 #77 稿 L286-295 / L582: 七态**全都**写在页底那个 StateLine(stateline)上。
        // 改前写的是 status(mkt_status), 而它挂在价格块【上方】(page_mkt.xml:265-273),
        // mkt_status 全工程零外部引用(只有本函数 4 处), 所以留着不用、不删 view。
        if (t.isNotEmpty()) {
            // ⚠ 2026-10-03 同上:状态行按用户裁定不再上屏,去掉 visibility = View.VISIBLE。
            //   ⚠ 连带后果:七态里的**错误/警告**提示(err/warn)原本也只写在这一行,
            //   现在用户看不到「源不可达 / 拉取中」这类提示了。这是删除的已知代价,不是遗漏。
            //     若要恢复,把这行加回来即可,文案与颜色逻辑都在。
            // 稿 L291 leading: err ? '!' : warn ? '▲' : '·'
            statelineLead.text = if (err) "!" else "▲"   // 稿 L291 err/warn 两档
            stateline.text = t
            // 稿 L288  c = err ? ink : warn ? ac : ink3
            stateline.setTextColor(act.attrColor(if (err) "colorInk" else "colorPrimary"))
            status.visibility = View.GONE
        } else {
            stateline.visibility = View.GONE
            status.visibility = View.GONE
        }
    }

    fun mkLoad(forceRefresh: Boolean = false) {
        // ---- 根数下限校验:整段 mkLoad 的**第一件事**(稿「K线数量至少填」下限校验 ----
        //   const count=Math.round(parseFloat($('kcount').value));
        //   if(!(count>=KMIN)){mkStatus('K线数量至少填'+KMIN,true);return;}
        //   const n=Math.min(KMAX,count);                                       // >100 静默夹取
        //
        // 判据用 rawN()(输入框原始值 round 后),不用 curN()——后者已 clamp(KMIN,KMAX),
        // 用户填 10 会被它变成 15,`raw < KMIN` 于是永远不成立、提示永不出现。
        //
        // 为什么必须放在最前面(t99 ④):**非法值时只改状态行,一律不动数据卡**。
        // 已加载的价格行/OHLC/图表/来源标注全部原样保留 —— 打错一个根数不该把
        // 辛苦加载出来的图抹掉。放在任何状态改动之前,非法路径就是纯粹"写一行红字"。
        // >KMAX 仍照稿静默夹到 100、**不提示**;空/非数字沿用该品种已确认默认、也不提示。
        val rawN = rawN()
        if (rawN != null && rawN < KMIN) {
            // t201 ⑨ 按稿 L510 逐字补全。原先只有「K线数量至少填15」,
            // 缺了: 「填」后的半角空格、全角逗号、当前根数、「根 —— 已拒绝计算支撑」整段。
            // 根数用 rawN(用户填的原始值), 不用 curN —— 后者已 clamp, 14 会变成 15。
            mkStatus("K线数量至少填 " + KMIN + "，当前 " + rawN + " 根 —— 已拒绝计算支撑", true)
            return // 中止本次加载:不发任何K线请求、不改图表、不写支撑
        }
        hideSug()
        val su = MktData.canonicalSym(symInp.text.toString())
        if (su.isEmpty()) {
            mkStatus("先输入品种", true)
            return
        }
        // All downstream request/cache/persistence paths use this canonical UI boundary.
        val type = MktData.symType(su)
        lastType = type
        val symChanged = su != lastSym
        // ⚠⚠⚠ 2026-10-15 t2 **周期切换也要认出来**（用户实报「切周线/月线/季线毫无反应」）。
        //   改前 mkLoad 只认 `symChanged`，周期变了没有任何动作：
        //   取数失败时价格/涨跌/支撑/目标/步长/时段全是**上一周期**的，屏上却没有一个字说明。
        //   判据取 `tf`（与 MktCache 键里的那一段同一个变量），必须在这里取 ——
        //   下面的 `applyWinDefaults()` 在换品种时**会把 tf 覆盖成该品种的默认周期**，
        //   放到它后面判就会把"换品种顺带换了周期"漏掉。
        val tfChanged = tf != lastTf
        // ⚠ 2026-10-15 t6（评审 F1）：这里**不再**做 `t2TakeDisplayedTf()` 那一步抄写 ——
        //   「屏上数据的周期」现在由 `renderAgg` 成功时写、`clearForNewSymbol` 里清，
        //   与 `lastTf`（请求周期）彻底分开。两个概念混用会把标注写反（实测踩过）。
        // ⚠ 2026-10-16 t8：同一时刻复位「本轮数据来源」标记（见 [t2SrcReset]）——
        //   不清的话上一轮的留底会把下一轮的真取数也说成"上次取到的"。
        t2SrcReset()
        lastTf = tf
        lastSym = su
        // ⚠ 2026-10-03 顶栏显示品种名：**换品种时清掉上一品种的名字**，
        //   否则从「美光」切到 BTC 后标题还挂着「美光」。
        //   清空后本次 mkLoad 里标题**不会**再被改写（2026-11-04 起本页已无标题节点，见 [setDispName]）。
        // ⚠ r8 B-9：这里原来那句 `titleTx.text = su` **已删**（改前它把品种代码写进顶栏，与稿
        //   `四屏-行情页.html:244-247` 的 `<AppBar title="行情"/>` 相反）。保留下来的只有 `dispName` 的清空。
        if (symChanged) { dispName = "" }
        // 稿§4:换品种→载入该品种已确认默认(周期+根数,互不串);同品种刷新保留敲过的值
        if (symChanged) applyWinDefaults()
        // 根数:空→该品种已确认默认;越界→clamp(15..100),不报错不崩(稿KMIN/KMAX)
        // ⚠️ t44:原先这里有 `val n = curN()`,分离后**它在本函数内已无任何使用者** ——
        //   取数改用下面的 fetchN,显示侧改在 renderAgg 里直接调 curN()。
        //   留一个没人读的局部变量正是 t4 点名的死代码病,故删。
        //   显示数仍由 curN() 现算,语义与删前一致(读的都是输入框的 clamp 后值)。
        // 稿MK.mkt:cmdty用CNNAME中文名(有则"中文名 代码",无则代码),其余按类型前缀
        // ── t44【F-2c③ 取数与显示分离】────────────────────────────────────────
        // 取数用 fetchN(定档),显示用 n(用户填的)。
        // 改前二者是同一个 `n`: 取数取 n 根、缓存键也用 n。后果是**改根数必然换键、
        // 必然重新发请求**——「数量」控件只是个显示旋钮,改大改小都要回网一次。
        // 分开后:键不再随 n 变 ⟹ 改 20→100 命中同一份缓存 ⟹ 不重取,只是多画几根。
        // ⚠️ fetchN 刻意**不随用户填的值走**:取少了他再改大就得重取,那就等于没分离。
        val fetchN = MktData.FETCH_N
        val mkt = when (type) {
            "cmdty" -> MktSuggest.favName(su).let { if (it.isEmpty()) su else it + " " + su }
            "crypto" -> "币 $su"
            "gold" -> "黄金 $su"
            "silver" -> "白银 $su"
            "kr" -> "韩股 $su"
            // ⚠ 2026-11-03 港股/A股已下线：给**明确提示**（MktData.unsupportedText），不许冒充「美股」。
            "unsupported" -> MktData.unsupportedText(su)
            else -> "美股 $su"
        }
        prelimTag = null
        // v4§1 + D4:restoreCalc 必须在**取数之后**调(稿L1427最后调,保存值赢),
        // 否则 chart.setData→onInfo→linkCalc 会用支撑位覆盖刚回填的最低价/目标上限。
        // 这里只置标记,实际调用在 renderAgg/失败分支末尾的 finishLoad()
        //
        // ⚠⚠ 2026-10-03 条件从 `symChanged` 改为 `symChanged || fromFav`。
        //   原写法判的是「和上次载入的品种不同」，于是**再次进入同一品种时不回填** ——
        //   正是用户实报的「二次点进去没有出来」。理由与取舍见 [setSym] 的说明：
        //   仍**不能**改成无条件，因为 mkLoad 还被敲击/失焦/确认触发，无条件会冲掉正在填的参数。
        wantRestore = symChanged || fromFav
        fromFav = false        // 消费即清，避免下一次刷新也回填
        // ⚠⚠ 2026-10-03 换品种先清空计算页输入（原因与顺序见 MainActivity.clearCalcInputs 的注释）。
        //   必须在这里（取数之前）：放到 restoreCalc 里清会把 linkCalc 刚填的价位一起抹掉。
        if (symChanged) act.clearCalcInputs()
        // ⚠⚠ 2026-10-03 换品种同时清空行情区，避免「标题是新的、数据是上一个品种的」。
        //   原因与范围见 [clearForNewSymbol] 的注释。
        // ⚠⚠ 2026-10-15 t2 **切周期：保留旧数据 + 立刻标注**（用户裁定，见 [t2Stale]）。
        //   不清屏、不重画图 —— 只把「屏上是上一个周期的数据」写进唯一可见的那一行，
        //   用户点下去**当场**就能读到，不必等 10–20s 后失败回调。
        //   （换品种走上面那支，清空是 2026-10-03 的裁定，两件事不冲突。）
        if (symChanged) {
            clearForNewSymbol()
        } else if (tfChanged) {
            // ⚠ 2026-10-15 t6（评审 F1）：判据在 `t2MarkStale` 内部（它读 t2ShownTf = 屏上那个周期），
            //   这里不再传/抄任何"屏上周期"的局部变量 —— 那个抄写正是 F1 要拆掉的耦合。
            t2MarkStale()
        }
        // ⚠ 2026-10-29 t30：这里原是 `if (symChanged) paintCardSlices()`（换品种重刷卡面）。
        //   去卡片化后没有卡面要刷 ⟹ 调用已删（`cardBg` / `isDarkNow` / `paintCardSlices` 一并下线）。
        //   ⚠ 换品种该刷的其它东西（t2MarkStale / stepThrFor / 图表）都不在本行，未受影响。
        // ⚠⚠ 2026-10-03 步长阈值改为**按当前品种**取（用户报「大量品种的网格数量没有自动计算」）。
        //   原先 stepThr 只由 loadStepThr 写一次、取的是 mkt_last_sym 那**一个**品种，
        //   于是算别的品种时它属于别人或为 null ⟹ autoGridN 恒返回 null ⟹ 永远不自动算。
        //   详见 MainActivity.stepThrFor 的注释。
        if (symChanged) act.stepThrFor(su)
        val seq = ++reqSeq
        requestInFlight = true
        inFlightSeq = seq
        inFlightTf = tf
        inFlightN = curN()
        // ⚠⚠ 2026-11-03 t11（t3 实测）：已下线市场必须**可见地说**，不能只说「未取到」。
        //   改前它走 loadStockParallel → fetchStocks 抛 unsupportedText → 被 mkStatus 写进
        //   页底 stateline —— 而 stateline 在布局里是 `gone`，屏上读不到（t3 的 dump 里只有
        //   `mkt_srcname=未取到`）。⟹ 这里**不走取数**，直接把文案写到全屏唯一可见的
        //   `mkt_srcname`（见 [paintUnsupported]），并且不发任何请求。
        if (type == "unsupported") {
            paintUnsupported(su)
            finishLoad(seq)          // 复位 requestInFlight（seq == inFlightSeq 才生效）
            return
        }
        // ⚠ 绝不落入下方商品兜底（ashare 漏分支是同类事故）。
        // ⚠ 2026-11-03：这份白名单只剩「美股 / 韩股」两种 —— 已下线市场在上面就返回了。
        if (type == "stock" || type == "kr") {
            loadStockParallel(su, fetchN, mkt, seq, forceRefresh)
            return
        }
        if (type == "crypto") {
            loadCryptoParallel(su, fetchN, mkt, seq, forceRefresh)
            return
        }
        // 贵金属/商品东财单源(稿ysym:gold→GC=F、silver→SI=F、cmdty→原码*00Y,
        // K线用当前控件值加载,不受距离窗口冻结影响
        // ⚠ 2026-10-12 t2：这条映射**收敛到 MktData.fetchKeyOf**（原来这里是同一个 when 的副本）。
        //   值逐字等价：`symType` 内部先 trim+uppercase ⟹ symType(s)==symType(su)；
        //   gold/silver 两支与原 when 相同，其余支返回规范化原码（su 本就已规范化）。
        //   为什么必须收敛：MktData.legsOf 比对腿批次品种时要用**同一张**映射
        //   （屏上 XAUUSD ↔ 投出去的 GC=F）—— 两处各写一份 when 迟早漂移，
        //   漂移的后果是黄金自己的源名行被自己的别名判成「不是当前品种」。
        val ysym = MktData.fetchKeyOf(su)
        val loadTf = tf
        val loadSym = su
        val loadSeq = seq
        val loadKey = ysym
        // ⚠ 2026-10-15 t6：源名**从真实腿链派生**（贵金属/商品 = 东财单源，见 MktData.metalLegs）。
        mkStatus(MktData.legsText(loadSym), false)
        t123PaintNoData()   // t123:取数中不先打实心点
        // ⚠⚠⚠ 2026-10-03 **贵金属补上缓存回底**（用户裁定「补」）。
        //   起因是用户一句「为什么拉不到不能用之前的数据呢」——
        //   这条路径**只写不读**：[MktSave.store] 存了，可失败时直接跳 catch 报错，
        //   **从不回头看有没有旧数据**。而贵金属现在只有东财一个源（用户裁定的），
        //   东财一限流 ⟹ 黄金**彻底空白**，哪怕上次刚存过。
        //   股票(loadStockParallel)与加密(loadCryptoParallel)两条路径**都有**这道回底，
        //   只有贵金属漏了 —— 三条路径里的唯一缺口。
        //
        //   照搬那两条的现成模式，不发明新机制、不加新文案：
        //     ① 先读内存缓存 → 有就直接画，**连后台请求都不发**（与另两条一致）
        //     ② 没有则读磁盘留底 → 先画出来顶着，状态行维持「拉取中」
        //     ③ 后台照抓，抓到覆盖并 store；抓不到则保留留底 + 标出缓存时间
        //
        //   ⚠ 键用 **ysym**（GC=F / SI=F / 原码），不是屏幕上的显示名 ——
        //     fetchMetals 收的是 ysym，MktSave.store 存的也是 ysym 的键，
        //     用 su 会读不到自己写的那份。
        val ckM = MktData.MktCache.key(loadKey, loadTf, fetchN)
        if (!forceRefresh) {
            MktData.MktCache.get(ckM)?.let { vs ->
                val cacheAt = MktData.MktCache.atOf(ckM)
                MktData.publishCachedLegs(loadKey, vs, if (cacheAt > 0L) cacheAt else -1L)
                renderAgg(MktData.aggregate(vs, loadKey), mkt, loadSeq, loadSym, loadTf)
                return   // 普通加载命中内存缓存就复用；定时刷新会跳过此早退
            }
        }
        var hadSavedM = false
        MktData.MktSave.saved(ckM)?.let { sv ->
            hadSavedM = true
            // ⚠ t8：标出"这批是留底"（只认同周期 ⟹ 标注要写「上次取到的」，不是「上一周期」）
            t2SrcSaved = true
            // ⚠ t12（R2-1）：记下**数据真实年龄**（不是画上屏那一刻）
            val savedAgeMs = MktData.MktSave.savedAgeMs(ckM)
            t2SrcSavedAgeMs = savedAgeMs
            // 留底已是美元价(_u=1)，**不再 fxify** —— 二次折算会让价格越刷越小。
            // savedAgeMs=-1 表示老留底没有时间戳，传未知哨兵，绝不算成 now+1 ⟹「0 秒前」。
            val savedAt = if (savedAgeMs >= 0L) System.currentTimeMillis() - savedAgeMs else -1L
            MktData.publishCachedLegs(loadKey, sv, savedAt)
            renderAgg(MktData.aggregate(sv, loadKey), mkt, loadSeq, loadSym, loadTf)
            mkStatus(MktData.legsText(loadSym), false)   // t6:源名从真实腿链派生（同上）
        }
        if (!hadSavedM) mkStatus(MktData.legsText(loadSym), false)
        Thread {
            try {
                // ⚠ 2026-11-02 改走唯一取数口（同股票路径）：取数 + 折算 + 缓存/留底都在 acquire 里。
                val vs = MktData.acquire(loadKey, loadTf, refresh = true)
                if (vs.isEmpty()) throw IllegalStateException("null")
                if (loadSeq != reqSeq || lastSym != loadSym) return@Thread
                val ag = MktData.aggregate(vs, loadKey)
                act.runOnUiThread {
                    if (!loadCurrent(loadSym, loadSeq)) return@runOnUiThread
                    t2SrcFresh()
                    renderAgg(ag, mkt, loadSeq, loadSym, loadTf)
                }
            } catch (e: Exception) {
                act.runOnUiThread {
                    if (!loadCurrent(loadSym, loadSeq)) return@runOnUiThread
                    // ⚠⚠ 2026-10-03 **有留底就不报错**（用户裁定「补」缓存回底）。
                    //   贵金属只有东财一个源，东财一限流就是「彻底无源」；
                    //   但上一次成功的数据还躺在 MktSave 里 —— 那就画它，
                    //   并如实标出这是缓存、缓存多久（时间戳是 MktSave 存的，读出来不是编的）。
                    //   **与股票/加密两条路径同款文案，不新增说法。**
                    if (hadSavedM) {
                        val ageMs = MktData.MktSave.savedAgeMs(ckM)
                        mkStatus("各行情源均不可达 · 展示缓存数据" +
                            (if (ageMs > 0) "（" + t118Ago(System.currentTimeMillis() - ageMs) + "）" else ""),
                            true)
                        // ⚠ 这里**不要**再调 t123PaintNoData()：它会把留底画好的图抹掉。
                        //   改前这段无条件报错时会走 t123PaintNoData，那是"没有图"的语境；
                        //   现在有图（留底），抹掉反而更糟。
                        // The fallback is useful, but it is not the requested fresh batch.
                        t2MarkStale(true)
                    } else {
                        // ⑩ 不再把异常整个丢掉: MktData 已经分好了 none/down, 这里翻成稿的原文
                        mkStatus(t210FailText(e.message, curSym()), true)
                        // ⚠ 2026-10-15 t2：保留旧数据 + 标注「上一周期（X）数据 · N 分钟前」（同 loadStockParallel）
                        t2MarkStale(true)
                    }
                    finishLoad(loadSeq) // D4:失败也要消费标记,否则下次同品种不再回填
                }
            }
        }.start()
    }

    private fun renderAgg(
        ag: Agg,
        mkt: String,
        seq: Int,
        loadSym: String,
        loadTf: String,
        allowAuxRequests: Boolean = true,
    ) {
        // Render only the immutable loader identity that produced this aggregate.
        if (!loadCurrent(loadSym, seq) || loadTf != tf) return
        // ── t44【F-2c③】**显示侧**分离:画最后 curN() 根 ─────────────────────────
        // 取数是按 MktData.FETCH_N 定档拿回来的(可能 240 根),显示只画用户填的那个数。
        // 在这里统一切,三条腿(renderStock / renderCrypto / 商品贵金属)都经过 renderAgg,
        // 所以**一处**就覆盖全部,不在各腿里各切一次(那会漏)。
        //
        // ⚠️ 用户填的数 > 实际可用根数时(如只有 20 根却填 100):
        //    takeLast 返回**全部可用**的 20 根,不多不少不少画。
        //    这不是静默截断 —— 状态行那行「N 根…已更新」读的是
        //    ⚠⚠ 2026-10-10 **口径更正（t1 改了语义，此处当时未同步）**：
        //    MktInfo.count = **MktView 真正画出来的根数**（`vp.ks.size`，见 MktView.kt:233/395 → onInfo），
        //    而 `vp.ks` 是 MktData.profileOf **过滤后**的那一份；改前这里写的是
        //    「传给 chart.setData() 的列表长度」= **未过滤**的 `show`，t1 之后两者可能不等
        //    （跨尺度的拆股柱不上屏、不计入这个 N）。
        //    ⟹ 所以屏上会**照实写「20 根」**而不是谎报 100；若这 20 根里还有跨尺度柱被过滤，
        //      写的会是**更小的那个真实上屏数**（不是 20）。要改显示根数,改上面的输入框即可。
        val show = ag.ks.takeLast(curN())
        lastKs = show
        lastSrc = ag.src
        lastMkt = mkt
        // ⚠⚠⚠ 2026-10-15 t6（评审 F2）**一轮渲染只读一次报价快照**。
        //   位置必须在 `chart.setData()` **之前**：`setData` 会同步触发 `onInfo` →
        //   `t102PaintSourceRow`（源名）与 `t106PaintMeta`（涨跌），它们都要用这份快照。
        //   改前三处各自在不同时刻调 `liveQuoteOf()`，而 `fresh()` 是**随时间变**的判据
        //   ⟹ 同一屏上可能出现"价是实时价、标签写收盘价"这种同屏不同源。
        // ⚠⚠ **这一轮没拿到新报价时，保留上一轮那份快照**（不要清空）：清空的后果实测到了 ——
        //   现价仍是上一轮写下的**实时价**（`price.text` 没人改），而涨跌与源名退回
        //   「historical 序列首尾差 / 收盘价」⟹ **又是同屏不同源**，而且是**最坏的一种**
        //   （价与涨跌不同刻）。保留旧快照时，价/涨跌/源名**三处仍来自同一份**，
        //   源名还带着那份报价的成交时刻（`12:57 PM ET`）—— 用户读得出它是什么时候的。
        //   ⚠ 换品种时 `clearForNewSymbol` 会把它清掉（那是另一个品种的价，绝不能留）。
        MktData.liveQuoteOf(loadSym)?.let { t2Snap = it }

        // ⚠ 2026-10-07 自选复用：把刚画完的K线发布给FavDist（同批同口径，免得自选再打一次API还算出另一个支撑）。
        try { MktData.LastChart.put(loadSym, loadTf, show) } catch (_: Exception) {}
        // ⚠ 2026-11-02：数据落地后**通知自选页**（丢弃它的旧距离缓存并按这一批重算）。
        //   放在 LastChart.put 之后、任何渲染之前就是「数据已就绪」的确切时刻；
        //   用 loadSym（本轮取数的品种），不是 curSym()（用户可能已经又换了品种）。
        try { onChartReady?.invoke(loadSym) } catch (_: Exception) {}
        // ⚠ 2026-10-28 缩放三键删除后，r8 B-1 的「数据重载后自动 Reset 缩放」**随之作废**
        //   （`chart.resetZoom()` 的调用点已删，viewBox 缩放不再上屏）。
        // ⚠ 2026-10-26「屏上这批数据」的批次代号**唯一写者**：必须在 chart.setData 之前写，
        //   onInfo（→ linkCalc）要拿它当这批数据的身份交回计算页；写到 setData 之后就会
        //   让下一次重绘把上一批说成新批次。本行之外不许再写 shownSeq。
        shownSeq = seq
        chart.setData(show)
        // t108 (2) 日期范围独占行已删(稿 2-quote 没有这一行)。
        // 原来在这里写 srcNote.text = "起始~结束" 的代码一并移除。
        // 来源信息仍由底部状态行与源状态行承载,不丢。
        // 稿mkhead(291)只保留30px现价;mkid品种名/mkChg涨跌徽标全树零命中,无需再删
        // ⚠⚠⚠ 2026-10-15 t2 **现价改取同域 `/info` 的当日实时价**（用户实报「美股写实时、
        //   现价却是上一交易日收盘」）。`show.lastOrNull()?.c` 是 Nasdaq `historical` 的末根，
        //   而 historical **只回已完成交易日** ⟹ 那永远是 T-1 收盘（实测屏上 200.97 =
        //   10/14 收盘，同一时刻 `/info` 是 198.9x）。
        //   ⚠ 拿不到实时价就**照旧画 historical 末根**（K 线图必须照常出来），
        //     只是源名会如实写「收盘价 · Nasdaq · <日期>」—— 数值与标签永远同一口径。
        // ⚠⚠ t6：**现价与涨跌必须一起写、一起从同一份快照来**。
        //   实测踩过：`setData` 里那次 `t106PaintMeta` 用了快照（写实时涨跌），
        //   而这里随后只按"快照是不是 realtime"重写价格 —— 一旦快照过期，
        //   就出现"价是实时价 + 涨跌是 historical 首尾差"的同屏不同源。
        //   ⟹ 现在：源名由 `onInfo` 那次画（同一份快照），**价与涨跌在这里一起收口**。
        val live = t2Snap?.takeIf { it.dayOk }
        price.text = MktData.mkFmt(live?.price ?: show.lastOrNull()?.c)
        if (live != null) {
            chg.text = "%+.2f (%+.2f%%)".format(live.netChg, live.pct)
            chg.setTextColor(act.attrColor(if (live.netChg >= 0) "colorTeal" else "colorRed"))
        }
        paintFx()
        // D4:onInfo(linkCalc/linkPhigh)已经跑完,此刻再回填=保存值最终赢
        finishLoad(seq)
        if (allowAuxRequests) paintTgt(ag, seq)
        // ⚠ 2026-10-15 t2 **画完了就把「上一周期」的标注撤掉**（用户裁定：失败态才保留+标注；
        //   成功态屏上就是本周期的数据，标注留着又变成新的骗人）。
        //   ⚠ 位置必须在 `chart.setData()` **之后**：onInfo 里那次 t102PaintSourceRow 还是
        //     带标注的（那一刻屏上确实还没换成本周期），这里收尾才清。
        // ⚠⚠ 2026-10-15 t6（评审 F1）：**这里是「屏上数据的周期」唯一的写者** ——
        //   只有真画上去了才写，且与 `lastTf`（请求周期）无关。
        t2ShownTf = MktData.tfName(loadTf)
        t2ShownAt = System.currentTimeMillis()
        t2ShownCount = show.size
        // ⚠ t8：把"这批数据是哪来的"一起记下来 —— 标注靠它区分「上次取到的」与「上一周期」
        t2ShownSaved = t2SrcSaved
        // ⚠ t12（R2-1）：留底路径把**数据真实年龄**也记下来（说"多久之前"用它，不用画上屏那一刻）
        t2ShownAgeMs = if (t2SrcSaved) t2SrcSavedAgeMs else 0L
        t2ClearStale()
    }

    // t106 ④ 涨跌额独立行 + ⑥ 交易所/时点。
    // ⚠️ 2026-10-02:函数原名 t106PaintOhlc,OHLC+Vol 整行删除后已名不副实,改名 t106PaintMeta。
    //   info.o 仍要读(涨跌额要用),info.h/l/c/vol 在本屏已无显示用途;
    //   **字段本身全部保留** —— K 线图取数走 MktView/MktData,不经过本函数。
    private fun t106PaintMeta(info: MktInfo) {
        val ink = act.attrColor("colorInk")
        // ⚠ r8 B-1 / B-11（2026-10-19）· 本函数由 `MktView.onDraw` 里的 `onInfo` 调用（**绘制途中**）
        //   ⟹ 这里只能做**文本/可见性**这类轻改动，**不许**改视图树（OHLC 表体的下一帧装配已随卡片删除）。
        //   · 卡头中段读数 = info.count = **实际画出来的根数**（vp.ks.size），无数据时是 0；
        paintBlkh(info.count)
        // ⚠⚠⚠ 2026-10-15 t2 **美股涨跌必须跟现价同一个口径**（用户实报：屏上现价是
        //   10/14 收盘 200.97，涨跌却写 +10.88 (+5.72%) —— 那是 historical 序列**首尾差**
        //   （200.97 − 190.09），不是「今天涨了多少」）。
        //   现在现价走同域 `/info` 的当日实时价，涨跌就取**同一个接口的 netChange /
        //   percentageChange**（它们本来就是相对上一交易日收盘算的，与"实时"同源同刻）。
        //   ⚠ 非实时（降级成收盘价）时**不取**它 —— 那时屏上价格是 historical 末根，
        //     而 netChange 仍是"上一交易日 vs 上上交易日"的口径，两者不同源，混用就是新的骗人。
        //   ⚠⚠ 2026-10-15 t6（评审 F2）：报价**只读本轮快照**（`t2Snap`，`renderAgg` 开头读一次），
        //     不再在这里另读一次 `liveQuoteOf` —— 与 `price` / 源名 共用同一份，天然同源。
        val live = t2Snap?.takeIf { it.dayOk }
        val col = when {
            live != null -> if (live.netChg >= 0) act.attrColor("colorTeal") else act.attrColor("colorRed")
            info.chgUp -> act.attrColor("colorTeal")
            else -> act.attrColor("colorRed")
        }
        // ④ 涨跌额:现价下独立一行
        if (live != null) {
            chg.text = "%+.2f (%+.2f%%)".format(live.netChg, live.pct)
            chg.setTextColor(col)
        } else if (info.o > 0) {
            chg.text = "%+.2f (%+.2f%%)".format(info.c - info.o, (info.c - info.o) / info.o * 100.0)
            chg.setTextColor(col)
        } else { chg.text = ""; chg.setTextColor(ink) }
        // ⚠ 2026-10-15 t6（评审 F2 的回归断言）：涨跌也是从**同一份快照**来的，
        //   这里再断言一次「屏上价格 ↔ 快照」同源（源名那条在 t102PaintSourceRow 里）。
        t2AssertSameSource()
        // 2026-10-03 图表下方的支撑价位块。
        //   supShow[0] = 第一支撑(MktData 从高价往低价扫,见 MktView.kt:338-342),
        //   与图上标签片同名同数 —— MktView.kt:390 的 names = ["第一支撑","第二支撑"]。
        //   本屏无支撑时整块必须 GONE:留空容器就是一截空白,正是这轮要清掉的东西。
        //   只有一条支撑时第二行也 GONE,不摆空位。
        val sup = info.supShow
        when {
            sup.isEmpty() -> supBlock.visibility = android.view.View.GONE
            else -> {
                supBlock.visibility = android.view.View.VISIBLE
                sup1.text = MktData.mkFmt(sup[0])
                if (sup.size >= 2) {
                    supRow2.visibility = android.view.View.VISIBLE
                    sup2.text = MktData.mkFmt(sup[1])
                } else {
                    supRow2.visibility = android.view.View.GONE
                }
            }
        }
        // ⑤ Vol 已于 2026-10-02 随整行删除(见 page_mkt.xml 墓碑)。
        //   既然整行不要了,那个缺字段场景不再有显示面,info.vol 字段本身保留即可。
        // ⑥ 交易所 · 收盘时点
        val sym = lastSym
        val crypto = sym.isNotEmpty() && MktData.symType(sym) == "crypto"
        // t122:交易所名**由品种所属市场推导**,不再硬编码 NASDAQ。
        // 原实现只有 crypto 分支区分交易所,其余一律 NASDAQ —— 稿里 `NASDAQ · 收盘`
        // 是**给 AAPL 这一个例子画的**,被当成了通则(与今天前五个「骗人」同一形状)。
        // 硬性要求:拿不准就写市场类别,那才是诚实的。
        val ty = if (sym.isEmpty()) "" else MktData.symType(sym)
        // 时区:只有美股用 GMT-4 的 marketPhase;亚洲市场不能拿美股时区冒充
        // ⚠ 2026-11-03：港股/A股已下线 ⟹ 非美股的亚洲市场只剩韩股（KRW/KST）。
        val isUs = ty != "crypto" && ty != "kr" && ty != "gold" &&
            ty != "silver" && ty != "cmdty" && ty != "unsupported"
        val phase = if (isUs) when (MktData.marketPhase()) {
            "open" -> "开盘"; "pre" -> "盘前"; "post" -> "盘后"; else -> "收盘"
        } else if (ty == "kr") t122Phase(ty) else "收盘"
        // ⚠ 2026-10-02 用户裁定删掉行情页顶部的品种搜索框后，**品种代码原本只出现在那一处**，
        //   直接删掉会让「现在看的是哪个品种」在屏上消失。这里把代码补进价格块右上角，
        //   与现行稿一致：稿 v2.html:2117 逐字 `{shortSym(S_)}{…' · 行情中'}`，即 **代码 + 时段**。
        //   改前这里是纯市场类别（「美股 · 盘前」），不含代码。
        //   shortSym 对齐稿：币种去掉 USDT 后缀（BTCUSDT → BTC）；其余原样（App 内部
        //   港股/韩股本就不带 .HK/.KS 后缀，见 MktSuggest）。
        val showSym = if (sym.endsWith("USDT")) sym.dropLast(4) else sym
        // ⚠⚠ 2026-11-03 t14（t9 实测：「行情页整页不见中文名」）：**身份行加中文名**。
        //   格式 = 「中文名 + 现有代码位 + 时段」，如 `希捷科技 STX · 盘前` / `比特币 BTC · 实时`
        //   / `黄金 XAUUSD · 收盘` / `SK海力士 000660 · 收盘`。
        //   ⚠ 代码位口径**一字不动**：crypto 仍去报价币（BTCUSDT → BTC），其余原样；
        //     不为此改 curSym / canonicalSym 或去报价币逻辑。
        //   ⚠ 名字唯一来源是 [MktSuggest.favName]（= UsNames.zhOf：us_dir → names_extra → 空）；
        //     名字为空的正股（如 DNUT/SGP/ARM）**退回现状**（只显示代码），
        //     绝不出现空串、双空格或占位符。
        //   ⚠ 页面大标题仍是「行情」（稿定）；这里只动这一行的文本，不新增节点、不改字号/布局。
        val nameZh = if (sym.isEmpty()) "" else MktSuggest.favName(sym)
        val head = if (nameZh.isEmpty()) showSym else nameZh + " " + showSym
        venueT.text = when {
            sym.isEmpty() -> ""
            crypto -> head + " · 实时"
            else -> head + " · " + phase
        }
        // t292 稿 L566 的 GMT-4 是**美股那一个例子**(AAPL), 稿没规定其他市场的时区。
        // 改前这里是 else 一律 GMT-4, 于是 crypto/gold/silver/cmdty 全被按成美东时区 ——
        // 真机 t291 截图 BTCUSDT 就是 `20:00:00 GMT-4`, 而它是 24 小时市场, 没有美东收盘。
        // 顺带说清: t122 那句「非美股品种的时钟按其本地时区」在 t292 之前是**假的**,
        // 因为 MktData.marketClock 内部把 tz 覆盖掉了(t292 一并修)。
        // gold/silver/cmdty 各家结算时区不同, **编不出来, 就不写时区** ——
        // 这是 t122 自己定的规矩(L891-892「拿不准一律说『收盘』,不编」), 不是新规矩。
        // ⚠ 2026-10-27 用户裁定：**这一行「收盘时点」不再上屏**（原 `mkt_time`，灰色小字）。
        //   理由：黄金/商品那一路恒显示 `00:00:00`，看了也不知道指什么；美股虽然给的是
        //   20:00:00 GMT-4，但同一块里 `{代码} · 收盘` 已经说了盘口状态，这一行是重复。
        //   ⚠ 节点与绑定**保留**（page_mkt.xml 里改成 visibility=gone）：删节点会让
        //     `findViewById(R.id.mkt_time)` 拿到 null 并 NPE —— 本仓既有教训。
        //   ⚠ 连带作废的是下面那张 tz 表（hk/ashare/kr/crypto/美股/gold），它只喂这一行；
        //     日后若要恢复「收盘时点」，把 tz 表与本行一起捡回来，口径依据见上方 t291/t292 记录。
    }

    /** t122:亚洲市场时点。**只剩韩股**（港股/A股已下线，2026-11-03）；
     *  拿不准一律说「收盘」,**不编**。真正的开闭市状态应由交易所日历给,本轮不引入。 */
    private fun t122Phase(ty: String): String {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("GMT+9"))
        val dow = cal.get(java.util.Calendar.DAY_OF_WEEK)
        val min = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        val weekend = dow == java.util.Calendar.SATURDAY || dow == java.util.Calendar.SUNDAY
        val lo = 9 * 60          // KRX 09:00 开盘
        val hi = 15 * 60 + 30    // KRX 15:30 收盘
        return when {
            weekend -> "休市"
            min in lo until hi -> "开盘"
            min < lo -> "盘前"
            else -> "收盘"
        }
    }

    // 【F-2c】原名 t106SyncKBar —— 随滑杆删除而**摘掉前半段（滑杆进度回填）**，
    // 只保留后半段「few 上红」。改名是因为「SyncKBar」里的 Kbar 已不存在了。
    //
    // ⚠️ **为什么不能整个函数删掉**：前半段管滑杆，后半段管**输入框在 raw<KMIN 时变红** ——
    //   那是 `few` 的上红显示（#57 稿 L548：color 用 few?'var(--dn)':'var(--ink)'），
    //   与「拒绝计算支撑」同属一条 already-aligned 的行为。整个删会连带删掉它。
    //   现在输入框是**唯一**入口，本函数是它的**唯一**上红通路，删了就没有了。
    private fun t106SyncKCount() {
        // #57 稿 L548: few = bars<15。稿的 var(--dn) 就是本盘的跌色,App 侧对应 colorRed(涨绿跌红不许翻转)。
        // 判据用 rawN() 而不是 curN():mkLoad 的拒绝门槛判的也是 rawN(),
        // 两者必须**同时**成立,免得出现「已经提示拒绝计算支撑、值却还是黑的」。
        // 空/非数字 = 沿用该品种已确认默认(必 >= KMIN),按【不是不足】处理,不上红。
        val raw = rawN()
        kValT.setTextColor(act.attrColor(if (raw != null && raw < KMIN) "colorRed" else "colorInk"))
    }

    /** t108 (1) 顶部标题显示品种名(稿:右上「搜索」文字入口) */


    private fun t108PaintTitle(info: MktInfo) {
        val sym = lastSym
        // 稿 L522 <AppBar title="行情"/>: 标题恒为「行情」, 不随品种变。
        // 品种名按稿放在 AppBar 下方那个独立块(L523-542), 那一整块还没搬(见 page_mkt.xml
        // L50-51 的自认注释) ⟹ 这里【不再】往顶栏写品种名, 免得回到改之前的样子。
    }

    // ⚠️ 墓碑 · t106Vol(成交量量级记法 30 M / 3 B / 175 K)已于 2026-10-02 随 Vol 整行删除。
    //   该行是屏幕上唯一的成交量显示面,删掉后本函数无任何调用点,一并作废。
    //   info.vol 字段本身仍在 MktInfo 里保留 —— 取数链路不动,只是不再显示。

    // t106 ⑩ 美元口径说明行整条删除(稿里没有)。保留空实现,
    // 因为 FX 的取数入口(汇率/原币)不能跟着 UI 一起删,计价语义仍被计算页与导出用。
    @Suppress("UNUSED_PARAMETER")
    private fun paintFx() {
    }

    // 汇率异步到达:说明行立即重画;当前正显示的品种按新缓存键重载
    // (同汇率→缓存命中只重画不发请求;汇率变了→键变→重新抓并按新汇率折算;输入框已被改过则只重画不代载)
    fun fxArrived() {
        if (lastSym.isEmpty()) return
        paintFx()
        if (MktData.canonicalSym(symInp.text.toString()) == lastSym) mkLoad()
    }

    // TGT 预测价行。⚠⚠ 2026-10-03 用户加了新规则，**这里是三条来源**：
    //   股票           → Nasdaq 机构目标均价（fetchTgt，稿 L406-414 的老行为）
    //   大宗商品/金银  → 历史最高价 × 1.10（MktData.predTarget）
    //   加密货币       → 历史最高价 × 1.20（同上）
    // 用户原话：「大宗商品的预测价为历史最高价的110%，加密货币的预测价为历史最高价的120%」
    //
    // ⚠ 改前这一行是 `if (lastType != "stock") 隐藏`，所以**黄金/币永远不显示** ——
    //   而用户要的恰恰是给它们一个价。Nasdaq 对 XAUUSD/GC=F 都返回 null（实测），
    //   所以不能靠放宽 lastType 了事，必须另给算法。
    // ⚠ 非股的两条依赖 dailyKsCached()，那是**步长阈值那次取数**顺手缓存的。
    //   若阈值因任何原因没跑（例如它在 onInfo 之后才触发），这里会拿到 null → 本行不显示。
    //   这条耦合是有意的：不值为一行为同一份数据再打一轮接口。
    private fun paintTgt(ag: Agg, seq: Int) {
        if (seq != reqSeq) { tgt.visibility = View.GONE; return }
        val cur = ag.ks.lastOrNull()?.c ?: 0.0
        val sym = lastSym
        val isStock = lastType == "stock"
        //   现在只有美股走 [MktData.fetchTgt]（Nasdaq 官方 analyst/targetprice，NYSE 票同样有）。
        val kind = MktData.symType(sym)   // crypto / cmdty / gold / silver / ...
        /** 品种是否还是当前显示的那个。**两道守卫缺一不可**，见下方墓碑。 */
        fun stillSame(): Boolean =
            seq == reqSeq && MktData.canonicalSym(symInp.text.toString()) == sym
        // 步长阈值那条腿可能还没跑完，先等它把日线缓存写进来再算预测价
        val run = { ->
            Thread {
                // ⚠ 2026-10-08 用户报「机构预测价是不是没保存，每次点进行情都要重新拉一次」——**属实**：
                //   当时 `fetchTgt`（美股）零缓存，每次进行情页各打一次请求；而币/商品走的
                //   `predTarget` 自带 MktSave.predSave 按天缓存 —— 同一个「预测价」，两类品种两种待遇。
                //   修法：给股票腿补上**同一份**按天缓存（一个品种一个预测价），日界取同一天。
                //   ⚠ 不把 predTarget 再包一层：它内部已是同一份缓存，再包等于重复查询。
                val ck = MktData.canonicalSym(sym)
                val day = java.time.LocalDate.now().toString()
                val prevDay = java.time.LocalDate.now().minusDays(1).toString()
                // ⚠⚠ r8 B-14（2026-10-19）· **股票腿补「查前一天」兜底**。
                //   病（与 `predTarget` 当年同一个）：当天缓存 miss 就整行消失 —— 用户 2026-10-08 报的
                //   「隔日缓存态下机构目标均价不渲染」正是这条。`MktData.predTarget`（`:2725-2731`）
                //   早就做了「当天 miss → 先查昨天缓存」的兜底，本条只是把**同一个口径**搬到股票腿
                //   （`MktSave.predLoad` 的载荷是 `当天|值|前一天|值`，`dayGet` 两段/四段都认 ⟹
                //    传 `prevDay` 查的正是那份顺移进来的旧值）。
                //   ⚠ 2026-11-04 用户裁定：「机构预测价旁边不需要显示前一日的字样」——
                //     尾注去掉；**兜底保留在取数失败之后**，不整行消失。
                //   ⚠⚠ 同日第二裁定（用户问「为什么要查前一天缓存，查不到吗」）· **顺序修正**：
                //     旧顺序是「今天缓存 → 昨天缓存 → 联网」，而 `dayGet` 只认当天/前一天两档 ⟹
                //     只要昨天有值就恒命中、**联网那步永远走不到**，屏幕整日显示旧目标价
                //     （这正是「（前一日）」标注存在的原因）。
                //     现顺序：**今天缓存 → 联网取 → 昨天缓存兜底**（同一天不重复请求的诉求不变）。
                //   ⚠ 两条都 miss 时**保持 GONE**（下游 `v == null` 分支），**不许编造值**。
                val v: Double? = if (isStock) {
                    MktData.MktSave.predLoad(ck, day)
                        ?: run {
                            val t = MktData.fetchTgt(sym)?.avg
                            if (t != null && t > 0) {
                                MktData.MktSave.predSave(ck, day, t)
                                t
                            } else {
                                MktData.MktSave.predLoad(ck, prevDay)
                            }
                        }
                } else if (lastType == "kr") {
                    // 2026-11-04 用户裁定：韩股目标价改**真取数**（Naver consensusInfo.priceTargetMean）。
                    //   手写快照已删除 ⟹ 取数失败时不再有快照兜底，只剩「昨天缓存」这一层。
                    //   缓存口径与股票腿一致：当天缓存 → 取数 → 昨天缓存。
                    MktData.MktSave.predLoad(ck, day)
                        ?: run {
                            val tv = MktData.fetchTgtKr(ck)?.avg
                            if (tv != null && tv > 0) MktData.MktSave.predSave(ck, day, tv)
                            tv ?: MktData.MktSave.predLoad(ck, prevDay)
                        }
                } else MktData.predTarget(sym)
                act.runOnUiThread {
                    // ⚠⚠ 2026-10-03 用户报「预测价混了策略」——**实测确认是这个串台**：
                    //   屏上是 01211(港股)，mkt_tgt 却写着 `预测价(最高×1.2) 115,045.76`，
                    //   那个数正是 BTCUSDT 的预测价(115045.76 = 95871.47 × 1.2)。
                    //   成因:下面那个 **2500ms 延迟重试**的闭包**捕获了当时的 sym**，
                    //   而回调里只有 `seq != reqSeq` 一道守卫。
                    //   从自选进 BTCUSDT 时排了这个延迟任务；之后切到 01211，
                    //   **若 reqSeq 没被 bump**(走 Tab 栏进行情页不经 mkLoad 这条路就会漏),
                    //   守卫放行 ⟹ 把 BTC 的值写到 01211 的屏上。
                    // ⟹ 补第二道守卫:直接比对输入框里的品种。
                    //   seq 是**请求序号**，不是品种身份；两条路径不一定都会 bump 它，
                    //   所以不能只靠 seq。**两道都要留。**
                    if (!stillSame()) return@runOnUiThread
                    if (v == null || cur <= 0) {
                        tgt.visibility = View.GONE
                        return@runOnUiThread
                    }
                    // t107 ⑦ 只留值，不留解释性文字
                    // ⚠ 2026-10-13 用户裁定：韩股目标价遇汇率过期时**旧汇率照折 + 行尾注明日期**
                    //   （rateOf 返回的是本地留底的旧值，照折不藏；注明让用户知道这个数折在旧汇率上）。
                    // ⚠ 2026-10-15 t2：尾注判据收进 FX.staleNote —— **兜底汇率**与「旧汇率」
                    //   是两种不同的不新鲜，文案必须分开（原来只判 rateStale()，
                    //   兜底值会被写成「（旧汇率 ）」：dateStr 为空，括号里是空的）。
                    val staleNote = if (lastType == "kr") MktData.FX.staleNote("KRW") else ""
                    // t30：label 左 / value 右（稿 .mk-metric）。尾注（旧汇率）跟着**数值**走。
                    // ⚠ 2026-11-04 用户裁定：原「（前一日）」尾注已移除；旧汇率 staleNote 保留。
                    tgtL.text = tgtLabel(kind)
                    tgtV.text = MktData.mkFmt(v) + staleNote
                    tgt.visibility = View.VISIBLE
                    // 稿PH(406-414):预测价>现价且>当前最低价才写计算页最高价(同值不扰手改)
                    // ⚠ 2026-10-26 带上 seq：本闭包属于 paintTgt(seq) 那一批请求（seq 在入口
                    //   已按 reqSeq 校验过），它可能是清空前排队、清空后才落地的回调 ——
                    //   计算页要靠这个代号把「旧批次」与「清空后重新进入的新批次」分开。
                    act.linkPhigh(v, cur, seq)
                }
            }.start()
        }
        // ⚠ 2026-10-07 BTC修正：判据不得在UI线程同步调predTarget（内含stepCloses网络，主线程必抛NetworkOnMainThread→恒null→恒延迟→旧串台复活）。
        //   改为无条件run()一次（后台取，stillSame双守卫），缓存未就绪则run内兜底取；延迟重试只排一次且同样双守卫。
        run()
        act.runOnUiThread {
            act.getWindow().getDecorView().postDelayed({
                if (tgt.visibility != View.VISIBLE && stillSame()) run()
            }, 2500)
        }
    }

    /** 行首标签按品种类型走，避免把「历史最高×1.1」也叫成「机构目标均价」。 */
    private fun tgtLabel(kind: String): String = when (kind) {
        "crypto" -> "预测价(最高×1.2)"
        "cmdty", "gold", "silver" -> "预测价(最高×1.1)"
        else -> "机构目标均价"
    }

    // ---------- 股票单腿渲染（Nasdaq；韩股 Naver 同走本函数） ----------
    private fun renderStock(
        v: VendorKs,
        mkt: String,
        seq: Int,
        prelim: String?,
        loadSym: String,
        loadTf: String,
        loadKey: String,
    ) {
        if (!loadCurrent(loadSym, seq) || loadTf != tf) return
        prelimTag = prelim
        renderAgg(MktData.aggregate(listOf(v), loadKey), mkt, seq, loadSym, loadTf)
    }

    /**
     * 2026-10-15 t2：**只刷实时价那两行**（缓存命中路径用，见 `loadStockParallel`）。
     *
     * ⚠ 它不取 K 线、不动缓存、不换源 —— 只是把同域 `/info` 那一个小请求补上，
     *   拿到就重画「现价」与「源名/涨跌」三处（`price` / `chg` / `t102PaintSourceRow`）。
     *   ⚠ 拿不到什么都不做：屏上维持「收盘价 · Nasdaq · <日期>」，与数据同口径。
     *   ⚠ 图、支撑、VPVR、目标价、步长**一个像素都不动**。
     *
     * @param cnt 屏上当前画出来的根数（调用方已算好，避免在这里再 aggregate 一次）
     */
    private fun t2RefreshLive(
        s: String,
        seq: Int,
        cnt: Int,
        loadSym: String,
    ) {
        MktData.refreshLiveQuote(s) { q ->
            // ⚠⚠ 2026-10-15 t6（评审 F2/F3）**回调必须 post 到 UI 线程**。
            //   `MktData.refreshLiveQuote` 是在自己的后台 Thread 里回调的（见它的实现），
            //   改前这里**直接在后台线程 setText** —— 属于"从非 UI 线程碰 View"，
            //   轻则丢帧/不刷新，重则 CalledFromWrongThreadException。
            //   改前那句判断也不是没有：它只是把"序号不对"当早退，没管线程。
            act.runOnUiThread {
                if (!loadCurrent(loadSym, seq)) return@runOnUiThread
                // ⚠⚠ 2026-11-05 判据从 `realtime` 改成 `dayOk`（最近交易日）。
                //   改前收盘后这里直接早退 ⟹ 快照不落地（`t2Snap` 仍是 null）⟹
                //   价与涨跌一起退回「historical 末根 开→收」，美股实测涨跌写成月线涨跌
                //   （MRVL -16.58%，同日真实日涨跌 -7.08%）。见 MktData.LiveQuote.dayOk。

                if (!q.dayOk) return@runOnUiThread
                // ⚠ 这一轮渲染的报价快照也一起更新：现价/涨跌/源名三处必须仍是**同一份**
                //   （否则源名走新报价、涨跌走旧快照，又回到同屏不同源）
                t2Snap = q
                price.text = MktData.mkFmt(q.price)
                chg.text = "%+.2f (%+.2f%%)".format(q.netChg, q.pct)
                chg.setTextColor(act.attrColor(if (q.netChg >= 0) "colorTeal" else "colorRed"))
                t102PaintSourceRow(cnt)
            }
        }
    }

    // t44:形参由 `n` 改名 `fetchN` —— 它收到的是 **mkLoad 的取数档位 MktData.FETCH_N(240)**，
    // 不是用户填的根数。改名前它叫 `n`,与同屏的 curN() 同名,极易被误当成显示数再取一次。
    private fun loadStockParallel(
        s: String, fetchN: Int, mkt: String, seq: Int, forceRefresh: Boolean = false
    ) {
        val loadTf = tf
        val loadSym = MktData.canonicalSym(s)
        val loadSeq = seq
        val loadKey = loadSym
        val ck = MktData.MktCache.key(loadKey, loadTf, fetchN)
        if (!forceRefresh) MktData.MktCache.get(ck)?.let { vs ->
            val win = MktData.pickStockWinner(vs)
            // 缓存命中也要把「这批数据来自哪个源」记进账本，否则源名行只能显示「未取到」。
            MktData.MktCache.atOf(ck).let { cacheAt ->
                MktData.publishCachedLegs(loadKey, vs, if (cacheAt > 0L) cacheAt else -1L)
            }
            renderStock(win, mkt, loadSeq, null, loadSym, loadTf, loadKey)
            // ⚠ 2026-10-15 t2：缓存命中**不发取数请求**，但实时价照样要刷 ——
            //   否则 3–5 分钟那个窗口里屏上会拿旧价配「收盘价」（见 MktData.refreshLiveQuote）。
            //   只是那一个小请求（~1.2 KB），K 线/缓存/腿链一概不动。
            //   根数用与 renderAgg 同一个口径（aggregate 后的 ks.size），免得两处算法漂移。
            //
            // ⚠⚠⚠ 2026-10-16 t10 / **R2：这是刻意的行为变化，别当 bug 改回去**（评审 t7 已确认保留）。
            //   现状 = 「缓存命中那一轮**不再**主动重刷报价」：
            //     · `refreshLiveQuote` 内部先查 `fresh()`（5 分钟内已有报价就直接返回，不发请求）；
            //     · 屏上那份报价因此**最长可以 5 分钟旧**（与 `MktCache.TTL` 同值）。
            //   为什么这样是对的：
            //     ① 少发请求（用户明确要求"不要频繁打源"）；
            //     ② 屏上**印着成交时刻**（「实时 · Nasdaq · 12:22 PM ET」）⟹ 它是"**可读出的旧**"，
            //        不是"冒充当前"—— 这与本任务要消灭的「无依据的实时」是两回事；
            //     ③ 现价/涨跌/源名三处都读**同一份**快照（`t2Snap`）⟹ 不会同屏不同源。
            //   ⛔ 不要"顺手"把它改成每轮都重刷：那会把 28 品种的自选页口径拉回每轮多打一次，
            //     且与 t7 的 R2 裁定相反。
            t2RefreshLive(loadKey, loadSeq, MktData.aggregate(listOf(win), loadKey).ks.size, loadSym)
            return
        }
        // 稿源状态行:有留底时:没有新鲜的、但上次抓过(关掉App也在)→
        // 先把上次的画出来顶着 + 状态行显示「拉取中 · 同一源不重试」,不闪空/不闪无源;
        // ③不管有没有留底,后台照样抓,抓到就覆盖(稿:1413-1414)。
        // 留底已是美元价(_u=1/usd=true),**不再 fxify**——二次折算会让价格越刷越小。
        var hadSaved = false
        // ⚠⚠⚠ 2026-10-15 t6 **源名从真实腿链派生**（MktData.legsText ⟸ legNames ⟸ stockLegs 的分支）。
        //   2026-11-03 数据源收敛后腿链只剩「美股 Nasdaq / 韩股 Naver」，文案照样自动跟随。
        //   顺带清掉原来那个**已无使用者**的 `val t = MktData.symType(s)`（编译警告点名的那个）。
        //   ⚠ 与 t210FailText 的「限流冷却中…」同源：**取数与文案都只看腿链一处**，改链不用回来改文案。
        val legsTxt = MktData.legsText(s)
        MktData.MktSave.saved(ck)?.let { sv ->
            hadSaved = true
            // ⚠ t8：标出"这批是留底"（只认同周期 ⟹ 标注要写「上次取到的」，不是「上一周期」）
            t2SrcSaved = true
            // ⚠ t12（R2-1）：记下**数据真实年龄**（不是画上屏那一刻）
            val savedAgeMs = MktData.MktSave.savedAgeMs(ck)
            t2SrcSavedAgeMs = savedAgeMs
            // 留底同样要有账可查；缺少旧时间戳时传未知值，不能用 now-(-1) 伪造新鲜度。
            val savedAt = if (savedAgeMs >= 0L) System.currentTimeMillis() - savedAgeMs else -1L
            MktData.publishCachedLegs(loadKey, sv, savedAt)
            renderStock(MktData.pickStockWinner(sv), mkt, loadSeq, null, loadSym, loadTf, loadKey)
            mkStatus(legsTxt, false)
        }
        if (!hadSaved) mkStatus(legsTxt, false)
        // 失败文案由 fetchStocks 按「抛异常=东财不可达 / 返回空=没有该品种数据」两类如实给出;
        // 这里**透传**它,不再一律盖成泛化的「行情拉取失败(网络或品种名不对)」——
        // 那句会让用户以为是自己填错了品种。美/港/韩同走 fetchStocks;
        // 汇率折算(入表前)与缓存键带汇率沿用 v4§7。
        Thread {
            var res: List<VendorKs>? = null
            var why: String? = null
            var fxBad = false
            try {
                // ⚠ 2026-11-02 **改走唯一取数口** [MktData.acquire]（用户裁定：一个软件不能有两个
                //   行情拉取口）。它内部负责 fetchStocks + 汇率折算 + MktCache/MktSave 写入；
                //   本页只保留「先画留底、再拿新数据重绘」的显示逻辑。
                //   ⚠ `wantLive = true` 仍然只有行情页传（自选页不显示实时价，也不许当第三个写者）。
                //   ⚠ `stillCurrent` 序号闸照旧透传。
                res = MktData.acquire(
                    loadKey, loadTf, refresh = true,
                    wantLive = true, stillCurrent = { loadSeq == reqSeq })
            } catch (_: MktData.FxMissing) {
                fxBad = true
            } catch (e: Exception) {
                why = e.message
            }
            val out = res
            val err = why
            act.runOnUiThread {
                if (!loadCurrent(loadSym, loadSeq)) return@runOnUiThread
                if (fxBad) {
                    // 折算失败 ⟹ 不给 K 线，走既有的「无源」态（未折算的值绝不能上屏/入缓存）。
                    mkStatus("汇率未取到 · " + curSym() + " 不出 K 线（避免用原币当美元）", true)
                    t2MarkStale(true)
                    finishLoad(loadSeq)
                    return@runOnUiThread
                }
                if (out == null || out.isEmpty()) {
                    // 稿 原稿(已被 B-标杆迁移.html 取代):`if(!got.length){if(!fst&&!sv)throw ...;return;}`
                    // —— **有留底就不报错**,继续用留底顶着,状态行维持「拉取中 · 同一源不重试」。
                    // 报错只在"既没新数据、又从没抓过"时才有意义。
                    // 稿 L516 down: '各行情源均不可达 · 展示 09:31:04 缓存数据（3 小时前）'。
                    // t302: 这里**取数已经结束了**, 不是还在竞速 —— 改前仍写「竞速中」,
                    // 用户看到一个永远转不出来的进度。稿把这种情况归 down 态, 并要求
                    // 标明缓存多久前(MktSave 已存了时间戳, 那个数是读出来的不是编的)。
                    if (hadSaved) {
                        val ageMs = MktData.MktSave.savedAgeMs(ck)
                        mkStatus("各行情源均不可达 · 展示缓存数据" +
                            (if (ageMs > 0) "（" + t118Ago(System.currentTimeMillis() - ageMs) + "）" else ""),
                            true)
                    }
                    else mkStatus(err ?: "行情拉取失败(网络或品种名不对)", true)
        // ⚠ 2026-10-15 t2 **失败必须可见 + 不许把上一周期的数当本周期**（用户实报「切周期毫无反应」）。
        //   全屏唯一可见的 mkt_* 文本承载位就是 `mkt_srcname`（page_mkt.xml:677-684 无
        //   visibility 属性，实测 uiautomator dump 里在；而 stateline / srcrow / mkt_status /
        //   mkt_legdetail 全是 `gone`、dump 里节点都不存在）。所以失败原因写在这里，
        //   **不动布局、不点亮任何被用户 2026-10-03 裁定隐藏的行**。
        //   ⚠ 用户裁定：**保留旧数据**（不清屏），但整块必须被标注成「上一周期（X）数据 · N 分钟前」
        //     —— 见 [t2Stale]。所以这里调 t2MarkStale(true)，**不再调 t123PaintNoData()**
        //     （那句只说「未取到」，读不出屏上是什么；而且用户明确没选清空）。
        //   ⚠ 这一轮没拿到价，实时标记必须撤掉 —— 否则「未取到」与「实时 · Nasdaq」同屏打架。
        MktData.clearLiveQuote(loadSym)
        t2MarkStale(true)
                    finishLoad(loadSeq) // D4
                    return@runOnUiThread
                }
                // 【F-2a · 修 B-1】折算失败已被 acquire 以 FxMissing 报上来了（见上面的 fxBad 分支），
                // 走到这里说明数据已折算、且已由 acquire 写入 MktCache/MktSave ⟹ 直接画。
                if (!loadCurrent(loadSym, loadSeq)) return@runOnUiThread
                t2SrcFresh()
                renderStock(MktData.pickStockWinner(out), mkt, loadSeq, null, loadSym, loadTf, loadKey)
            }
        }.start()
    }

    // ---------- 币多腿:单线程按 cryptoLegs() 顺序**串行**,命中即停(稿L856-863) ----------
    // 与 loadStockParallel 同构:一条腿命中即 renderCrypto + return,后续腿不再发请求,
    // 不再发多余请求浪费大等待时间;BTCUSDT 的 legs 只有 BN 一条,行为不变。
    private fun renderCrypto(
        vs: List<VendorKs>,
        mkt: String,
        seq: Int,
        prelim: String?,
        loadSym: String,
        loadTf: String,
        loadKey: String,
    ) {
        if (!loadCurrent(loadSym, seq) || loadTf != tf) return
        prelimTag = prelim
        renderAgg(MktData.aggregate(vs, loadKey), mkt, seq, loadSym, loadTf)
    }

    // 同上:形参是**取数档位**,不是用户填的根数。
    private fun loadCryptoParallel(
        s: String, fetchN: Int, mkt: String, seq: Int, forceRefresh: Boolean = false
    ) {
        val loadTf = tf
        val loadSym = MktData.canonicalSym(s)
        val loadSeq = seq
        val loadKey = loadSym
        val ck = MktData.MktCache.key(loadKey, loadTf, fetchN)
        if (!forceRefresh) MktData.MktCache.get(ck)?.let { vs ->
            // 缓存命中也要把来源记进账本，否则源名行显示「未取到」（用户实报 BTC）。
            MktData.MktCache.atOf(ck).let { cacheAt ->
                MktData.publishCachedLegs(loadKey, vs, if (cacheAt > 0L) cacheAt else -1L)
            }
            renderCrypto(vs, mkt, loadSeq, null, loadSym, loadTf, loadKey)
            return
        }
        // 稿源状态行:有留底时(同 loadStockParallel):先画留底顶着 + 「拉取中 · 同一源不重试」
        var hadSaved = false
        // ⚠ 2026-10-15 t6：币腿的源名同样**从真实腿链派生**（MktData.cryptoLegs）——
        // ⚠ 2026-10-15 第二轮：cryptoLegs 已收成**币安单源**（用户裁定「行情数据源改为一家」）。
        val legsTxtC = MktData.legsText(s)
        MktData.MktSave.saved(ck)?.let { sv ->
            hadSaved = true
            // ⚠ t8：标出"这批是留底"（只认同周期 ⟹ 标注要写「上次取到的」，不是「上一周期」）
            t2SrcSaved = true
            // ⚠ t12（R2-1）：记下**数据真实年龄**（不是画上屏那一刻）
            val savedAgeMs = MktData.MktSave.savedAgeMs(ck)
            t2SrcSavedAgeMs = savedAgeMs
            // 留底也要有账可查；缺少旧时间戳时传未知值，不能用 now-(-1) 伪造新鲜度。
            val savedAt = if (savedAgeMs >= 0L) System.currentTimeMillis() - savedAgeMs else -1L
            MktData.publishCachedLegs(loadKey, sv, savedAt)
            renderCrypto(sv, mkt, loadSeq, null, loadSym, loadTf, loadKey)
            mkStatus(legsTxtC, false)
        }
        if (!hadSaved) mkStatus(legsTxtC, false)
        val legs = MktData.cryptoLegs(loadKey)
        // ⚠⚠⚠ 2026-10-03 **竞速改替补**（用户裁定：「取消竞速模式，采用替补模式」）。
        //   改前：所有腿并行发，`got.compareAndSet` 让**先回来的赢**，其余一律丢弃。
        //   ⟹ 赢的是**网络最快的那条**，不是**数据最全的那条**。
        //     抢在东财前面赢 ⟹ VPVR 整片空白，且**谁赢不确定** ⟹ 「第一次有、第二次没了」。
        //   现在：**按 cryptoLegs 的顺序依次试**，第一条成功的即胜出，
        //   后面的腿**根本不发请求**。结果确定，不受网络快慢影响。
        //   ⚠ 代价：前面的腿失败要等它自己的超时才轮到下一条（2026-10-15 起币腿只剩 BN 一条）。
        //     这是「确定」换「可能更慢」，与用户裁定一致。
        //   ⚠ 顺序即优先级：cryptoLegs 的排列从此**决定**谁优先，不再是「谁快用谁」。
        Thread {
            var hit: VendorKs? = null
            var hitLeg = ""
            val legStates = mutableListOf<MktData.LegInfo>()
            for (leg in legs) {
                if (loadSeq != reqSeq) return@Thread
                var v: VendorKs? = null
                var threw = false
                try {
                    v = MktData.fetchCryptoLeg(leg, loadKey, loadTf, fetchN)
                } catch (_: Exception) {
                    threw = true
                }
                val gotLeg = v
                if (gotLeg != null) {
                    legStates.add(MktData.LegInfo(leg, hit = true, failed = false, skipped = false))
                    hit = gotLeg; hitLeg = leg
                    // 剩下的腿没试，如实标 skipped
                    for (rest in legs) if (rest != leg && !legStates.any { it.name == rest }) {
                        legStates.add(MktData.LegInfo(rest, hit = false, failed = false, skipped = true))
                    }
                    break   // ← 替补模式：赢面即止
                }
                legStates.add(MktData.LegInfo(leg, hit = false, failed = threw, skipped = false))
            }
            val v = hit
            val legName = hitLeg
            val states = legStates.toList()
            act.runOnUiThread {
                if (!loadCurrent(loadSym, loadSeq)) return@runOnUiThread
                if (v != null) {
                    // 【F-2a · 修 B-1】同上：折算失败就不出 K 线。加密腿当前是 USD（fxify 直接 true），
                    // 但**规则不能靠「今天是美元」来兜底** —— 哪天多一条非美元腿，
                    // 这里就会重演 B-1。所以三条消费点一律按返回值把关。
                    if (!MktData.FX.fxify(listOf(v), loadKey)) {
                        mkStatus("汇率未取到 · " + curSym() + " 不出 K 线（避免用原币当美元）", true)
                        // Keep any visible batch, but make its cycle/cache provenance explicit.
                        t2MarkStale(true)
                        finishLoad(loadSeq)
                        return@runOnUiThread
                    }
                    if (!loadCurrent(loadSym, loadSeq)) return@runOnUiThread
                    MktData.MktCache.put(ck, listOf(v))
                    MktData.MktSave.store(ck, listOf(v))
                    MktData.publishFavLegs(
                        loadKey,
                        listOf(MktData.LegInfo(legName, hit = true, failed = false, skipped = false)))
                    // ⚠ 墓碑 · 2026-10-12：这里曾写 `MktData.lastLegsCount = fetchN`（t324 为稿 L318 的「· 42 根」），
                    //   已随 t2 删除 —— 该字段**零读取点**（屏上「· N 根」t45 就去掉了），而旧注释还声称
                    //   「被 FavPanel 读去拼「· N 根」」，注释与代码矛盾。需要根数时的正确来源是 legsOf 那批的 info.count。
                    t2SrcFresh()
                    renderCrypto(listOf(v), mkt, loadSeq, null, loadSym, loadTf, loadKey) // 命中即停
                } else {
                    // 全灭：与改前同款收尾（留底顶着 / 泛化失败文案）
                    // 稿 L516 down: '各行情源均不可达 · 展示 09:31:04 缓存数据（3 小时前）'。
                    // t302: 这里**取数已经结束了**, 不是还在竞速 —— 改前仍写「竞速中」,
                    // 用户看到一个永远转不出来的进度。稿把这种情况归 down 态, 并要求
                    // 标明缓存多久前(MktSave 已存了时间戳, 那个数是读出来的不是编的)。
                    if (hadSaved) {
                        val ageMs = MktData.MktSave.savedAgeMs(ck)
                        mkStatus("各行情源均不可达 · 展示缓存数据" +
                            (if (ageMs > 0) "（" + t118Ago(System.currentTimeMillis() - ageMs) + "）" else ""),
                            true)
                    }
                    // ⑩ 这里的泛化文案【不能照稿细分】，原因写在下面，免得下次有人"顺手改一下"：
                    //   逐腿试，每条腿自己的失败原因（源没这个代码 / 源连不上）在循环内部就各自被吃掉了。
                    //   走到这一行只掌握"没有哪条腿命中"，既拿不到 none 也拿不到 down。
                    // ⛔ 绝不能在这里硬套一个稿文案，那会把"源正常但没这个代码"也说成"连不上"。
                    else mkStatus("行情拉取失败(网络或品种名不对)", true)
                    // ⚠ 2026-10-15 t2：与 loadStockParallel 的失败分支**同一句话同一判据** ——
                    //   保留旧数据 + 标注「上一周期（X）数据 · N 分钟前」（见 [t2Stale]）。
                    MktData.clearLiveQuote(loadKey)
                    t2MarkStale(true)
                }
                finishLoad(loadSeq)
            }
        }.start()
    }
}
