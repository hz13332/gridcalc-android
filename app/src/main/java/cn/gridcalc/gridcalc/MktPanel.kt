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

// ---------- 自选距离窗口冻结(照稿 var DISTTF=null, DISTN=null) ----------
// 未提交时 distWin() 跟随行情页周期控件+根数;点▾"确认"后 commitDistWin() 冻结,
// 直到下次确认。行情 K 线加载(mkLoad)不读 DistWin,仍实时跟随控件。
object DistWin {
    @Volatile var tf: String? = null
    @Volatile var n: Int = 0

    fun commit(curTf: String, curN: Int) {
        tf = curTf
        n = curN
    }
}

// ---------- 行情 VPVR · 面板接线 ----------
// 照搬设计稿 mkLoad/renderMK 的文字侧:品种+K数+周/月/季分段+查看,
// loading/error态,OHLC条,图例,来源注。拉取放后台线程,结果回主线程。

class MktPanel(private val act: MainActivity, page: View) {

    private val symInp: EditText = page.findViewById(R.id.mkt_sym)
    private val kcountInp: EditText = page.findViewById(R.id.mkt_kcount)
    private val goBtn: Button = page.findViewById(R.id.mkt_go)
    // 【F-2b · 2026-10-01】周期三键 **W / M / Q**。
    //
    // ⚠️ 「Q 季线」与「M 月线」**数据来源不同**，别把它们当成同一档：
    //   · 新浪腿  kt = "quarter"          → **源原生季线**
    //   · 其余腿  取月线(东财 klt=103)后 `toQuarterly` **三月合成**
    // 合成出来的是**真正的季 K 线**（开=季首月开、高=三月最高、低=三月最低、
    // 收=季末月收、量=求和），**不是**「拿月线冒充季线」那种静默降级。
    // 详见 MktData.fetchEastmoney 里的 klt 注释（含「兜底 ≠ 等价」那段）。
    //
    // 用户 2026-10-01 裁定：**保留季线**。
    // 稿子删季线是因为腾讯 `klt=quarter` 返回空 —— 那是**稿子的源限制**，不是 App 的缺陷；
    // 拿「稿子做不到」当「App 该砍」正是我们要拆的那类静默降级。
    //
    // 年线：App **没有**（无 tfY，MktData 也无 "Y" 分支）。用户未要求，**未擅自添加**。
    private val tfW: Button = page.findViewById(R.id.mkt_tf_w)
    private val tfM: Button = page.findViewById(R.id.mkt_tf_m)
    private val tfQ: Button = page.findViewById(R.id.mkt_tf_q)
    private val status: TextView = page.findViewById(R.id.mkt_status)
    // t106 ④ 涨跌额 / ⑥ 交易所·时点
    // ⚠️ 2026-10-02 用户裁定「这两个不要」:OHLC 四格 + Vol 已从 page_mkt.xml 整体删除,
    //   十个字段 oC/hC/lC/cC/labO·labH·labL·labC/volLab/volT 随之作废。
    //   **只删显示层** —— MktInfo 的 o/h/l/c/vol 字段与取数链路原样保留,K 线图仍在用同一批数。
    private val chg: TextView = page.findViewById(R.id.mkt_chg)
    private val venueT: TextView = page.findViewById(R.id.mkt_venue)
    private val timeT: TextView = page.findViewById(R.id.mkt_time)
    // 【F-2c】数量滑杆 kBar 已删（连同布局里的 mkt_kbar 节点）。根数入口现在**只有** kcountInp。
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
    private val stepT: TextView = page.findViewById(R.id.mkt_stept)
    @Volatile var stepTv: Double = 0.0
    @Volatile private var stepSym: String = ""
    // t108 (2) 日期范围独占行已删(稿里没有),srcNote 视图随之移除
    private val winBtn: Button = page.findViewById(R.id.mkt_win)  // t107 (3) gone on screen, logic kept
    private val titleTx: TextView = page.findViewById(R.id.mkt_title)   // 稿 L522: 顶栏标题恒为「行情」(布局里写死), 不显示品种名
    private val searchTx: TextView = page.findViewById(R.id.mkt_searchtx) // t252 稿 L527: 描边盒右侧的「搜索/收起」
    // t252 稿 L526: 描边盒左侧的品种名。值来自 symInp —— 它是品种的唯一真源(MktPanel L45)。
    private val symName: TextView = page.findViewById(R.id.mkt_symname)

    // ⚠ 2026-10-02 照稿补：价格块右上第三行「实时/收盘价 · 源名」（稿 v2.html:2126-2128）。
    //   实时走决策紫 colorPrimary，收盘/未取到走第三档灰 colorFaint。
    private val srcName: TextView = page.findViewById(R.id.mkt_srcname)
    private val price: TextView = page.findViewById(R.id.mkt_price)
    private val tgt: TextView = page.findViewById(R.id.mkt_tgt)
    // v4§7 美元口径说明行(数据源下方;汇率异步到达后重画)
    // t106 ⑩「美元口径」行已整条删除(稿里没有,是说明性文字)
    private val chart: MktView = page.findViewById(R.id.mkt_chart)
    private val favBtn: Button = page.findViewById(R.id.mkt_fav)
    private val dock: LinearLayout = page.findViewById(R.id.mkt_input_dock)
    private val searchBtn: View = page.findViewById(R.id.mkt_search)
    private val row2: LinearLayout = page.findViewById(R.id.mkt_row2)

    private var winPop: PopupWindow? = null

    // 自选变更(收藏/删除)时通知自选页重绘;由MainActivity接线
    var onFavChanged: (() -> Unit)? = null
    // 点▾确认提交距离窗口后,通知自选页重刷距离(稿winGo:commit+refresh+收菜单)
    var onDistCommitted: (() -> Unit)? = null

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
    //   `kcountInp.setOnFocusChangeListener { !hasFocus -> mkLoad() }`（失焦提交即重算）。
    //   这条在删滑杆之前就存在，所以根数仍可改、仍生效。
    //   注意 **afterTextChanged 只同步上红、不发请求**（见下）—— 那是刻意的：
    //   若在每次击键都 mkLoad()，打「120」会连发三次请求，正是「并发惊群」那一族的病。
    //   触发只挂在**失焦**这一个事件上，一次编辑一次请求。
    private var tf = "M"
    private var reqSeq = 0
    private var lastKs: List<KLine> = emptyList()
    private var lastSrc = ""
    private var lastMkt = ""
    private var lastType = ""
    private var lastSym = ""
    // 首家先画注记:非空时状态行显示"POC x（初步·源）",终画前清掉
    private var prelimTag: String? = null
    // v4§1/D4:换品种后「取数完成再回填」的待办标记(取数成功/失败两条路都会消费)
    private var wantRestore = false
    // ⚠ 2026-10-03 「自选主动进入」标记，语义见 [setSym] 的说明。消费后立即清零。
    private var fromFav = false

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
                leftMargin = dp(9.5f).toInt()                   // 稿 L535 gap 10
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
        val s = symInp.text.toString().trim().uppercase(Locale.US)
        favBtn.text = if (s.isNotEmpty() && FavStore.isFav(act, s)) "★" else "☆"
    }

    private fun toggleFav() {
        val s = symInp.text.toString().trim().uppercase(Locale.US)
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

    /** ⚠ 2026-10-03 用户裁定「把行情换成该交易品种的名字」：顶栏显示品种名，不再恒为「行情」。
     *
     * 传入的是**空串时回退显示代码** —— 搜索结果那条路径拿不到中文名
     * （MktSuggest 的项只有 s + tag，tag 是「美股/币/韩股」这类市场标签）。
     *
     * ⚠ 这**偏离稿**：稿 v2.html:2062 是 `<AppBar title="行情"/>`，标题恒为「行情」。
     *   产品裁定，不是移植对齐。
     * ⚠ 只改标题文字，**不碰返回键、不碰 URL 语义**：‹返回仍回自选页。
     */
    fun setDispName(n: String, symFallback: String) {
        dispName = n
        titleTx.text = n.ifBlank { symFallback }
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
        symInp.setText(s)
        symInp.setSelection(s.length)
        paintStar()
        // ④ 「启动时刷新一次」要有个对象才能刷: 记住上次看的品种, 下次冷启动才有东西可刷。
        // 不记的话每次冷启动 symInp 都是空的, 开关就几乎永不触发 —— 那还是骗人。
        // 值必须与 MainActivity 的 prefs()(字面量 "gridcalc") 及 PREF_LASTM 逐字相等,
        // 由 fix_auto_refresh_repair.py 的断言守着 —— 改一边另一边会被拦下。
        try { act.getSharedPreferences("gridcalc", android.content.Context.MODE_PRIVATE)
            .edit().putString("mkt_last_sym", s).apply() } catch (_: Exception) {}
    }

    /** ④ 启动时取回上次看的品种; 没有就返回空串(首次安装, 什么都还没看过)。 */
    fun lastSymPersisted(): String = try {
        act.getSharedPreferences("gridcalc", android.content.Context.MODE_PRIVATE)
            .getString("mkt_last_sym", "") ?: ""
    } catch (_: Exception) { "" }

    // 自选距离键用(对标稿子favKey读tfSeg/kcount)
    fun curTf(): String = tf

    // 稿§4根数:范围15≤n≤100(KMIN/KMAX),越界clamp不报错;空值回落该品种已确认默认
    fun curN(): Int = (rawN() ?: confirmedN()).coerceIn(KMIN, KMAX)

    // 稿§4 根数**原始值**:读输入框原文、round 之后返回;空/非数字返回 null(=沿用已确认默认)。
    // 为什么必须单独留这个:判下限**不能**用 curN()——curN() 已 coerceIn(KMIN,KMAX),
    // 用户填 10 会被它变成 15,`raw < KMIN` 于是永远不成立,提示就永远不会出现。
    // 这正是 v4 移植漏掉「提示并中止」那一半的原因(规格只写了 clamp 那半句)。
    private fun rawN(): Int? =
        kcountInp.text.toString().trim().toDoubleOrNull()?.let { Math.round(it).toInt() }

    private fun confirmedN(): Int = loadWin().second

    // 自选距离窗口(照稿distWin):已提交返回冻结值,未提交跟随周期控件+当前根数
    fun distWin(): Pair<String, Int> =
        DistWin.tf?.let { it to DistWin.n } ?: (tf to curN())

    // 点▾确认(照稿commitDistWin):按当前周期控件+根数冻结窗口
    fun commitDistWin() {
        DistWin.commit(tf, curN())
    }

    // ---------- 稿§4 根数/周期按品种默认(gridcalc_win_v1) ----------
    // ▾确认才落盘;回车/失焦只重画不回填;离开行情页丢弃未确认值;
    // 换品种各显各的默认;周期按钮与确认同源(都读这一个tf+输入框)
    private val winPref
        get() = act.getSharedPreferences("gridcalc_win_v1",
            android.content.Context.MODE_PRIVATE)

    private fun loadWin(): Pair<String, Int> {
        try {
            val s = winPref.getString(lastSym, null)
            if (!s.isNullOrEmpty()) {
                val p = s.split("|")
                val wtf = p[0]
                val nv = p.getOrNull(1)?.toIntOrNull()
                if ((wtf == "W" || wtf == "M" || wtf == "Q") && nv != null) {
                    return wtf to nv.coerceIn(KMIN, KMAX)
                }
            }
        } catch (_: Exception) {
        }
        return "M" to 20 // 未确认过:初始默认20(稿§4示例)
    }

    private fun saveWin() {
        if (lastSym.isEmpty()) return
        try {
            winPref.edit().putString(lastSym, tf + "|" + curN()).apply()
        } catch (_: Exception) {
        }
    }

    // 换品种载入默认:周期按钮与根数输入框同源显示该品种已确认值
    private fun applyWinDefaults() {
        val (wtf, wn) = loadWin()
        tf = wtf
        paintTf()
        val typed = kcountInp.text.toString().trim()
        if (typed != wn.toString()) kcountInp.setText(wn.toString())
    }

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
    fun onLeave() {
        if (lastSym.isEmpty()) return
        applyWinDefaults()
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
        kcountInp.setText(n.toString())
        commitDistWin()
        saveWin()
        onDistCommitted?.invoke()
        android.widget.Toast.makeText(act, "已保存：$tfName · $n 根",
            android.widget.Toast.LENGTH_SHORT).show()
    }

    // ── 2026-09-30 整段删除: syncGrid() ──
    //   它原先做什么: 在 row2 每次布局后按 row2.width 把「周/月/季三键 + ▾」均分成
    //   k/k/末键, 再给 kcountInp 与 winBtn 写死宽度 —— 整套建立在一个**错前提**上:
    //   「行情页存在周期三键栅格行」。稿里没有这条规则。稿 L544 的 Seg 是 inline-flex、
    //   按内容收窄; #55/#56 之后 mkt_row2(page_mkt.xml)是竖向的数量块, 里面只有
    //   「标签+值」那一行 + mkt_kbar + gone 的 mkt_win, **一个周期键都没有**。
    //   为什么删: 三键那段在 t405 已经摘掉, 剩下的 kcountInp / winBtn 两段是同一套
    //   死假设的遗物。kcountInp 那段会把 XML 已改对的 wrap_content 按回 ~2/3 行宽
    //   (数字浮在行内, 违反稿 L546 的 space-between), 已在 S11 摘掉; winBtn 那段
    //   改的是 gone 视图, 视觉零影响。留着一半错前提的代码, 下一个读到它的人
    //   还得重新推一遍「这函数现在还管什么」—— 删掉比留着省事。
    //   它最坏的一次: 那段 tfW/tfM/tfQ 改宽把 XML 里已经改对的 wrap_content 又按成
    //   三等分, 于是「源码对、APK 对、真机量出来是旧的」, 是这一轮最难查的现象。
    //   ⚠ row2(mkt_row2)这个字段**故意留着**: 它是 id 与 findViewById 的活绑定,
    //   硬约束「所有现有 id 与 findViewById 绑定不许断」, 不因删函数就摘掉。
    //   ⚠ mkt_win 视图与它的点击/弹窗逻辑(toggleWinPop)也**一律没动**, t107 ③ 的
    //   判断仍然成立: 视图 gone 但逻辑留着, 将来若恢复入口不必重建。

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
        val q = symInp.text.toString().trim().uppercase(Locale.US)
        if (q.isEmpty()) {
            hideSug()
            return
        }
        val r = Runnable {
            val seq = ++sugSeq
            val local = MktSuggest.local(q)
            showSug(local, seq)
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
        // ▾确认菜单(t107 ③ 已 gone, 逻辑留着)。原先按实际宽算的那套栅格代码已随 syncGrid() 删除。
        winBtn.setOnClickListener { toggleWinPop() }
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
        kcountInp.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                done(v) { if (symInp.text.toString().trim().isNotEmpty()) mkLoad() }
                true
            } else false
        }
        // 【F-2c】数量滑杆已删。K数变更即重算靠**失焦提交**（setOnFocusChangeListener，见下）。
        // afterTextChanged 这里**只同步上红、不发请求** —— 每次击键都 mkLoad() 会连发多次请求，
        // 属于「并发惊群」那一族的病。触发只挂在失焦这**一个**事件上：一次编辑，一次请求。
        kcountInp.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { t106SyncKCount() }
        })
        // ⚠⚠ 2026-10-03 用户裁定：「输入框也没有**光标最右功能**（无论怎么点，光标都会出现在最右方）」。
        //   ⟹ **后半句是用户要的行为，不是问题描述**。我第一轮把它读反了，
        //     当成「问题=光标总在右边」，于是去缩盒子的内边距/最小宽度 —— **方向整个反了**，
        //     而且顺带把 minWidth 从 56dp 缩到 39dp、又按稿改回 66.8dp，来回两轮都没碰到这件事。
        //   用户要的是：**点盒子任意位置，光标一律落到末尾**（像「跳到末尾」型输入框）。
        //
        //   为什么默认行为不满足：`mkt_kcount` 是数字输入、只 2~3 位，
        //   盒子里两位数之间本来就有可点空隙，点进去光标就停在两位中间（真机截图证实）。
        //
        //   实现：`setOnClickListener` 在默认 touch 处理**之后**触发，
        //   把选区强制推到 text.length —— 每次点击都覆盖掉默认落点。
        //   ⚠ 返回 false 的 onTouch 不需要；这里用 onClick 已足够且不会吞掉输入法行为。
        kcountInp.setOnClickListener {
            val n = kcountInp.text?.length ?: 0
            if (kcountInp.selectionStart != n || kcountInp.selectionEnd != n) {
                kcountInp.setSelection(n)
            }
        }
        t106SyncKCount()
        kcountInp.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && symInp.text.toString().trim().isNotEmpty()) mkLoad()
        }
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
            act.linkCalc(sup)
            // t106 ⑪ 支撑图例行删掉(稿里没有,信息已由线上标签片承担),
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
            //   info.count 由 MktView 构造时取的是 setData() 收到的**列表长度**(MktView.kt:84-85 `all = ks`
            //   → :457 `count = n`),而 renderAgg 已按 curN() 切过一道 ⟹ 报的就是用户实际看到的那几根。
            //   取数档位(FETCH_N=240)不会出现在这一行里。
            //   用户填 100 但源只回 20 根时,这里**照实写「20 根」**,不谎报 100。
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
            t103PaintStepT(symInp.text?.toString()?.trim()?.uppercase(Locale.US) ?: "")
            // 稿:成功态不显示"支撑xxx"状态文字(mktstatus默认display:none)
            // ⚠ t322: 这里**只能收 mkt_status, 不能走 mkStatus("")**。
            //   mkStatus 的 else 分支会连 stateline 一起收掉, 而 stateline 此刻
            //   装的正是上面 L543-545 刚写的 ok 态「· N 根…已更新」⟹ 成功态整行消失。
            //   t321 加 #77 的时候踩了这个: 两个 view 语义重叠, 收错了那一个。
            status.visibility = View.GONE
        }
    }

    // t102 第3步:源状态行。**只读 MktData.lastLegs,不改任何取数逻辑、不新增定时器。**
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
 *   全是**上一个品种**的；右上角写着「实时 · 百度股市通」（股票源）配黄金标题。
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
        try {
            price.text = "—"
            chg.text = ""
            srcName.text = ""
            srcName.setTextColor(act.attrColor("colorFaint"))
            tgt.visibility = View.GONE
            tgt.text = ""
            sup1.text = ""
            sup2.text = ""
            supBlock.visibility = View.GONE
            chart.clear()
        } catch (_: Throwable) {
        }
    }

    private fun t123PaintNoData() {
        // ⚠ 2026-10-03 用户裁定「图里的东西不要了」⟹ 本行(● 源 · N 根 · 展开)整行不再上屏。
        //   这里原本是 srcRow.visibility = View.VISIBLE,正是它把布局里那个 GONE 又顶回可见,
        //   只改 XML 删不掉 —— 运行期会被重新点亮。下面照样写文案/圆点(数据还在),
        //   但**不再改可见性**,让布局的 GONE 说了算。
        srcDot.setBackgroundResource(R.drawable.srcdot_off)
        srcMain.text = "暂无可用源"
        srcMore.text = if (legDetail.visibility == View.VISIBLE) "收起" else "展开"
        // 同一时刻价格块右上那行也要跟着说「未取到」——不许留上一轮的「实时 · 源名」残留。
        srcName.text = "未取到"
        srcName.setTextColor(act.attrColor("colorFaint"))
        // t31 收尾: 这里原来调一个状态点自检函数(整函数已删) —— mktpage 加的自检,
        // 真机用 logcat 按 tag 核对「空文案时状态点绝不能是实心」; t6 验完即删。
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


    private fun t102PaintSourceRow(count: Int) {
        val legs = MktData.lastLegs
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
        val hitVendor = legs.firstOrNull { it.hit }?.let { MktData.vendorName(it.name) }
        srcName.text = when {
            hitVendor != null -> "实时 · " + hitVendor
            count > 0 -> "收盘价 · " + (hitVendor ?: "未决")
            else -> "未取到"
        }
        srcName.setTextColor(act.attrColor(if (hitVendor != null) "colorPrimary" else "colorFaint"))
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
                t118Ago(MktData.lastLegsAt)
        } else if (MktData.lastLegsAt > 0L) {
            // 没取到但有留底 —— 如实说"这是上次的",不点任何源名
            srcDot.setBackgroundResource(R.drawable.srcdot_off)
            srcMain.text = "显示上次的数据 · " + count + " 根 · " + t118Ago(MktData.lastLegsAt)
        } else {
            srcDot.setBackgroundResource(R.drawable.srcdot_off)
            srcMain.text = "暂无可用源"
        }
        srcMore.text = if (legDetail.visibility == View.VISIBLE) "收起" else "展开"
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
    private fun t103PaintStepT(sym: String) {
        if (sym.isEmpty()) { stepTv = 0.0; stepSym = ""; stepT.visibility = View.GONE; return }
        // 稿:1188 —— 换品种先清零,防旧品种阈值参与新区间
        if (stepSym != sym) { stepSym = sym; stepTv = 0.0; stepT.visibility = View.GONE }
        // 同一个品种的阈值已经拿到且已在屏上 ⟹ 不再重复取(MktData 侧本就有 5 分钟缓存,
        // 这里省掉的是「每次重绘都过一次异步 + 主线程回调」的开销)
        if (stepTv > 0.0 && stepT.visibility == View.VISIBLE) return
        if (stepInFlight) return
        stepInFlight = true
        MktData.stepThresholdAsync(sym) { t ->
            // ⚠️ stepThresholdAsync 的回调在**后台线程**(MktData 里起的是 Thread),
            // 碰 View 会抛 CalledFromWrongThreadException —— 必须整段切回主线程。
            act.runOnUiThread {
                stepInFlight = false
                if (stepSym != sym) return@runOnUiThread   // 异步回来时品种已换
                val wantOn = t != null && t > 0
                // 幂等:值和可见性都没变 ⟹ 什么都不做,更不发回调(否则上面的死循环会回来)
                if (wantOn && stepTv == t && stepT.visibility == View.VISIBLE) return@runOnUiThread
                if (!wantOn && stepTv == 0.0 && stepT.visibility == View.GONE) return@runOnUiThread
                if (wantOn) {
                    stepTv = t!!
                    stepT.text = "步长阈值 " + MktData.mkFmt(t * 100.0) + "%"
                    stepT.visibility = View.VISIBLE
                } else {
                    stepTv = 0.0
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
        val onPrimary = act.attrColor("colorOnPrimary")
        val ink2 = act.attrColor("colorSub")
        for ((b, t) in listOf(tfW to "W", tfM to "M", tfQ to "Q")) {
            if (tf == t) {
                b.setBackgroundResource(R.drawable.seg_sel)
                b.setTextColor(onPrimary)
                b.setTypeface(null, android.graphics.Typeface.BOLD)   // 稿 600
            } else {
                b.setBackgroundResource(R.drawable.seg_unsel)
                b.setTextColor(ink2)                                   // 稿 var(--ink2)
                b.setTypeface(null, android.graphics.Typeface.NORMAL)  // 稿 400
            }
        }
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
     *  down 那句带缓存时刻, 与自选页 L447 的缓存横幅同源(MktData.lastLegsAt + t118Ago),
     *  没有缓存时照实只说前半句 —— 稿那句的"展示…缓存数据"在无缓存时不成立, 不能照抄。
     *  ⛔ 认不出来的 message 一律走【原样透传】, 不硬套任何一个稿文案 ——
     *     把一条"诚实但不精确"的信息说成"精确但可能不实", 比前者坏。 */
    private fun t210FailText(msg: String?, sym: String? = null): String = when {
        // 稿 L517 kr: ['err', '韩股：腾讯只回 1 根 · 东财不可达 · 两腿皆无 → 本品种无源']。
        // 这句话对**韩股这一条链路**是事实陈述, 不是猜: MktData.stockLegs 里韩股的腿
        // 写死为 [腾讯, 东财], 且同处注释记着「腾讯韩股接口实测只回 1 根, 过不了 prep 的 ≥3 根门槛」。
        // 所以韩股两腿皆无时能确定地报这一句; 其它品种不适用(它们的腿是 [百度, 腾讯])。
        sym != null && MktData.symType(sym.trim().uppercase(Locale.US)) == "kr" ->
            "韩股：腾讯只回 1 根 · 东财不可达 · 两腿皆无 → 本品种无源"
        msg == null -> "行情拉取失败(网络或品种名不对)"
        msg.contains("都没有该品种数据") -> "无此品种 · 源正常但没有这个代码，请检查拼写"
        msg.contains("都不可达") -> {
            val at = MktData.lastLegsAt
            if (at > 0L) "各行情源均不可达 · 展示 " + hhmm(at) + " 缓存数据（" + t118Ago(at) + "）"
            else "各行情源均不可达"
        }
        else -> msg
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
        // 真机上「百度股市通 / 腾讯 竞速中…」紫字印在现价正上方 —— 位置与稿相反。
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

    fun mkLoad() {
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
        val s = symInp.text.toString()
        if (s.trim().isEmpty()) {
            mkStatus("先输入品种", true)
            return
        }
        val type = MktData.symType(s)
        lastType = type
        val su = s.trim().uppercase(Locale.US)
        val symChanged = su != lastSym
        lastSym = su
        // ⚠ 2026-10-03 顶栏显示品种名：**换品种时清掉上一品种的名字**，
        //   否则从「美光」切到 BTC 后标题还挂着「美光」。
        //   清空后本次 mkLoad 里标题会回退成代码（除非紧接着有 setDispName 送来名字）。
        if (symChanged) { dispName = ""; titleTx.text = su }
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
            "hk" -> "港股 $su"
            "kr" -> "韩股 $su"
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
        if (symChanged) clearForNewSymbol()
        // ⚠⚠ 2026-10-03 步长阈值改为**按当前品种**取（用户报「大量品种的网格数量没有自动计算」）。
        //   原先 stepThr 只由 loadStepThr 写一次、取的是 mkt_last_sym 那**一个**品种，
        //   于是算别的品种时它属于别人或为 null ⟹ autoGridN 恒返回 null ⟹ 永远不自动算。
        //   详见 MainActivity.stepThrFor 的注释。
        if (symChanged) act.stepThrFor(su)
        val seq = ++reqSeq
        if (type == "stock" || type == "hk" || type == "kr") {
            // 港/韩与美同走股票串行腿(稿§6);绝不落到下方商品(gold/silver/cmdty)兜底
            loadStockParallel(s, fetchN, mkt, seq)
            return
        }
        if (type == "crypto") {
            loadCryptoParallel(s, fetchN, mkt, seq)
            return
        }
        // 贵金属/商品东财单源(稿ysym:gold→GC=F、silver→SI=F、cmdty→原码*00Y,
        // 经EMID映射到101.GC00Y/101.SI00Y与17种商品*00Y),Yahoo贵金属线已删;
        // K线用当前控件值加载,不受距离窗口冻结影响
        val ysym = when (type) {
            "gold" -> "GC=F"
            "silver" -> "SI=F"
            else -> su
        }
        mkStatus("百度股市通 / 腾讯 竞速中 · 失败腿不回退重试", false)
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
        val ckM = MktData.MktCache.key(ysym, tf, fetchN)
        MktData.MktCache.get(ckM)?.let { vs ->
            renderAgg(MktData.aggregate(vs), mkt, seq)
            return   // ← 命中内存缓存，与另两条路径同：不再发后台请求
        }
        var hadSavedM = false
        MktData.MktSave.saved(ckM)?.let { sv ->
            hadSavedM = true
            // 留底已是美元价(_u=1)，**不再 fxify** —— 二次折算会让价格越刷越小。
            renderAgg(MktData.aggregate(sv), mkt, seq)
            mkStatus("东财 拉取中 · 失败不回退重试", false)
        }
        if (!hadSavedM) mkStatus("东财 拉取中 · 失败不回退重试", false)
        Thread {
            try {
                val vs = MktData.fetchMetals(ysym, tf, fetchN)
                // 【F-2a · 修 B-1】第三条消费点，同样按返回值把关。
                // 贵金属/商品本身是美元，fxify 恒返回 true；这里是为了让「三条消费点一律把关」
                // 成为**结构上不可绕过**的规则，而不是靠人记住这一条是例外。
                if (!MktData.FX.fxify(vs, ysym)) {
                    act.runOnUiThread {
                        if (seq != reqSeq) return@runOnUiThread
                        mkStatus("汇率未取到 · " + curSym() + " 不出 K 线（避免用原币当美元）", true)
                        t123PaintNoData()
                        finishLoad(seq)
                    }
                    return@Thread
                }
                MktData.MktCache.put(ckM, vs)
                MktData.MktSave.store(ckM, vs) // 稿:1422-1423
                val ag = MktData.aggregate(vs)
                act.runOnUiThread {
                    if (seq != reqSeq) return@runOnUiThread
                    renderAgg(ag, mkt, seq)
                }
            } catch (e: Exception) {
                act.runOnUiThread {
                    if (seq != reqSeq) return@runOnUiThread
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
                    } else {
                        // ⑩ 不再把异常整个丢掉: MktData 已经分好了 none/down, 这里翻成稿的原文
                        mkStatus(t210FailText(e.message, curSym()), true)
                    }
                    finishLoad(seq) // D4:失败也要消费标记,否则下次同品种不再回填
                }
            }
        }.start()
    }

    private fun renderAgg(ag: Agg, mkt: String, seq: Int) {
        if (seq != reqSeq) return
        // ── t44【F-2c③】**显示侧**分离:画最后 curN() 根 ─────────────────────────
        // 取数是按 MktData.FETCH_N 定档拿回来的(可能 240 根),显示只画用户填的那个数。
        // 在这里统一切,三条腿(renderStock / renderCrypto / 商品贵金属)都经过 renderAgg,
        // 所以**一处**就覆盖全部,不在各腿里各切一次(那会漏)。
        //
        // ⚠️ 用户填的数 > 实际可用根数时(如只有 20 根却填 100):
        //    takeLast 返回**全部可用**的 20 根,不多不少不少画。
        //    这不是静默截断 —— 状态行那行「N 根…已更新」读的是
        //    MktInfo.count = 传给 chart.setData() 的**列表长度**(见 MktView.setData → onInfo),
        //    所以屏上会**照实写「20 根」**而不是谎报 100。要改显示根数,改上面的输入框即可。
        val show = ag.ks.takeLast(curN())
        lastKs = show
        lastSrc = ag.src
        lastMkt = mkt
        chart.setData(show)
        // t108 (2) 日期范围独占行已删(稿 2-quote 没有这一行)。
        // 原来在这里写 srcNote.text = "起始~结束" 的代码一并移除。
        // 来源信息仍由底部状态行与源状态行承载,不丢。
        // 稿mkhead(291)只保留30px现价;mkid品种名/mkChg涨跌徽标全树零命中,无需再删
        price.text = MktData.mkFmt(show.lastOrNull()?.c)
        paintFx()
        // D4:onInfo(linkCalc/linkPhigh)已经跑完,此刻再回填=保存值最终赢
        finishLoad(seq)
        paintTgt(ag, seq)
    }

    // t106 ④ 涨跌额独立行 + ⑥ 交易所/时点。
    // ⚠️ 2026-10-02:函数原名 t106PaintOhlc,OHLC+Vol 整行删除后已名不副实,改名 t106PaintMeta。
    //   info.o 仍要读(涨跌额要用),info.h/l/c/vol 在本屏已无显示用途;
    //   **字段本身全部保留** —— K 线图取数走 MktView/MktData,不经过本函数。
    private fun t106PaintMeta(info: MktInfo) {
        val ink = act.attrColor("colorInk")
        val col = if (info.chgUp) act.attrColor("colorTeal") else act.attrColor("colorRed")
        // ④ 涨跌额:现价下独立一行
        if (info.o > 0) {
            chg.text = "%+.2f (%+.2f%%)".format(info.c - info.o, (info.c - info.o) / info.o * 100.0)
            chg.setTextColor(col)
        } else { chg.text = ""; chg.setTextColor(ink) }
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
        //   连带作废的坑:t341 记过「竞速赢新浪腿时 vol 整条缺失」,当时用「—」显式说明;
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
        val isUs = ty != "crypto" && ty != "hk" && ty != "kr" && ty != "gold" &&
            ty != "silver" && ty != "cmdty"
        val phase = if (isUs) when (MktData.marketPhase()) {
            "open" -> "开盘"; "pre" -> "盘前"; "post" -> "盘后"; else -> "收盘"
        } else if (ty == "hk" || ty == "kr") t122Phase(ty) else "收盘"
        // ⚠ 2026-10-02 用户裁定删掉行情页顶部的品种搜索框后，**品种代码原本只出现在那一处**，
        //   直接删掉会让「现在看的是哪个品种」在屏上消失。这里把代码补进价格块右上角，
        //   与现行稿一致：稿 v2.html:2117 逐字 `{shortSym(S_)}{…' · 行情中'}`，即 **代码 + 时段**。
        //   改前这里是纯市场类别（「美股 · 盘前」），不含代码。
        //   shortSym 对齐稿：币种去掉 USDT 后缀（BTCUSDT → BTC）；其余原样（App 内部
        //   港股/韩股本就不带 .HK/.KS 后缀，见 MktSuggest）。
        val showSym = if (sym.endsWith("USDT")) sym.dropLast(4) else sym
        venueT.text = when {
            sym.isEmpty() -> ""
            crypto -> showSym + " · 实时"
            else -> showSym + " · " + phase
        }
        // t292 稿 L566 的 GMT-4 是**美股那一个例子**(AAPL), 稿没规定其他市场的时区。
        // 改前这里是 else 一律 GMT-4, 于是 crypto/gold/silver/cmdty 全被按成美东时区 ——
        // 真机 t291 截图 BTCUSDT 就是 `20:00:00 GMT-4`, 而它是 24 小时市场, 没有美东收盘。
        // 顺带说清: t122 那句「非美股品种的时钟按其本地时区」在 t292 之前是**假的**,
        // 因为 MktData.marketClock 内部把 tz 覆盖掉了(t292 一并修)。
        // gold/silver/cmdty 各家结算时区不同, **编不出来, 就不写时区** ——
        // 这是 t122 自己定的规矩(L891-892「拿不准一律说『收盘』,不编」), 不是新规矩。
        val tz = when (ty) {
            "hk" -> "GMT+8"
            "kr" -> "GMT+9"
            "crypto" -> "UTC"          // 24h 市场, 日线按 UTC 结算, 这是事实
            "gold", "silver", "cmdty" -> ""   // 拿不准, 不编
            else -> "GMT-4"            // 美股(stock)
        }
        timeT.text = if (tz.isEmpty()) hhmm(info.tEnd) else MktData.marketClock(info.tEnd, tz)
    }

    /** t122:亚洲市场时点。港股/韩股的交易时段与美股不同,这里按其本地时区粗判;
     *  拿不准一律说「收盘」,**不编**。真正的开闭市状态应由交易所日历给,本轮不引入。 */
    private fun t122Phase(ty: String): String {
        val tz = java.util.TimeZone.getTimeZone(if (ty == "kr") "GMT+9" else "GMT+8")
        val cal = java.util.Calendar.getInstance(tz)
        val dow = cal.get(java.util.Calendar.DAY_OF_WEEK)
        val min = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        val weekend = dow == java.util.Calendar.SATURDAY || dow == java.util.Calendar.SUNDAY
        val lo = if (ty == "kr") 9 * 60 else 9 * 60 + 30      // 09:00 / 09:30 开盘
        val hi = if (ty == "kr") 15 * 60 + 30 else 16 * 60     // 15:30 / 16:00 收盘
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
        kcountInp.setTextColor(act.attrColor(if (raw != null && raw < KMIN) "colorRed" else "colorInk"))
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
        if (symInp.text.toString().trim().uppercase(Locale.US) == lastSym) mkLoad()
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
        val kind = MktData.symType(sym)   // crypto / cmdty / gold / silver / ...
        /** 品种是否还是当前显示的那个。**两道守卫缺一不可**，见下方墓碑。 */
        fun stillSame(): Boolean =
            seq == reqSeq && symInp.text.toString().trim().uppercase(Locale.US) == sym
        // 步长阈值那条腿可能还没跑完，先等它把日线缓存写进来再算预测价
        val run = { ->
            Thread {
                val v: Double? = if (isStock) MktData.fetchTgt(sym)?.avg else MktData.predTarget(sym)
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
                    tgt.text = tgtLabel(kind) + " " + MktData.mkFmt(v)
                    tgt.visibility = View.VISIBLE
                    // 稿PH(406-414):预测价>现价且>当前最低价才写计算页最高价(同值不扰手改)
                    act.linkPhigh(v, cur)
                }
            }.start()
        }
        if (!isStock && MktData.predTarget(sym) == null) {
            // 日线缓存还没就绪 ⟹ 等一拍再试一次（只等一次，不轮询）
            act.runOnUiThread {
                act.getWindow().getDecorView().postDelayed({ run() }, 2500)
            }
            return
        }
        run()
    }

    /** 行首标签按品种类型走，避免把「历史最高×1.1」也叫成「机构目标均价」。 */
    private fun tgtLabel(kind: String): String = when (kind) {
        "crypto" -> "预测价(最高×1.2)"
        "cmdty", "gold", "silver" -> "预测价(最高×1.1)"
        else -> "机构目标均价"
    }

    // ---------- 股票三源并行(Nasdaq+YahooDaily+东方财富)+首家先画 ----------
    private fun renderStock(v: VendorKs, mkt: String, seq: Int, prelim: String?) {
        if (seq != reqSeq) return
        prelimTag = prelim
        renderAgg(MktData.aggregate(listOf(v)), mkt, seq)
    }

    // t44:形参由 `n` 改名 `fetchN` —— 它收到的是 **mkLoad 的取数档位 MktData.FETCH_N(240)**，
    // 不是用户填的根数。改名前它叫 `n`,与同屏的 curN() 同名,极易被误当成显示数再取一次。
    private fun loadStockParallel(s: String, fetchN: Int, mkt: String, seq: Int) {
        val ck = MktData.MktCache.key(s, tf, fetchN)
        MktData.MktCache.get(ck)?.let { vs ->
            renderStock(MktData.pickStockWinner(vs), mkt, seq, null)
            return
        }
        // 稿源状态行:有留底时:没有新鲜的、但上次抓过(关掉App也在)→
        // 先把上次的画出来顶着 + 状态行显示「拉取中 · 同一源不重试」,不闪空/不闪无源;
        // ③不管有没有留底,后台照样抓,抓到就覆盖(稿:1413-1414)。
        // 留底已是美元价(_u=1/usd=true),**不再 fxify**——二次折算会让价格越刷越小。
        var hadSaved = false
        MktData.MktSave.saved(ck)?.let { sv ->
            hadSaved = true
            renderStock(MktData.pickStockWinner(sv), mkt, seq, null)
            mkStatus("百度股市通 / 腾讯 竞速中 · 失败腿不回退重试", false)
        }
        if (!hadSaved) mkStatus("百度股市通 / 腾讯 竞速中 · 失败腿不回退重试", false)
        // v4.2/t97:股票K线只用东方财富单源(用户要求「不切源」,不再回退 Nasdaq/腾讯/Yahoo),
        // 失败文案由 fetchStocks 按「抛异常=东财不可达 / 返回空=没有该品种数据」两类如实给出;
        // 这里**透传**它,不再一律盖成泛化的「行情拉取失败(网络或品种名不对)」——
        // 那句会让用户以为是自己填错了品种。美/港/韩同走 fetchStocks;
        // 汇率折算(入表前)与缓存键带汇率沿用 v4§7。
        Thread {
            var res: List<VendorKs>? = null
            var why: String? = null
            try {
                res = MktData.fetchStocks(s, tf, fetchN)
            } catch (e: Exception) {
                why = e.message
            }
            val out = res
            val err = why
            act.runOnUiThread {
                if (seq != reqSeq) return@runOnUiThread
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
        t123PaintNoData()   // t123:失败也必须重画源状态行,否则停在布局默认的实心点 + 空文案
                    finishLoad(seq) // D4
                    return@runOnUiThread
                }
                // 【F-2a · 修 B-1】折算失败 ⟹ **不给 K 线**，走既有的「无源」态。
                // 改前 fxify 是静默 return，这里拿不到任何信号，会把原币 K 线
                // 缓存下来并画上屏（港股支撑位/y轴/支撑线全在原币上，状态行却什么都不说）。
                if (!MktData.FX.fxify(out, s)) {
                    // **不进缓存、不落盘、不渲染** —— 未折算的值绝不能继续往下走。
                    mkStatus("汇率未取到 · " + curSym() + " 不出 K 线（避免用原币当美元）", true)
                    t123PaintNoData()
                    finishLoad(seq)
                    return@runOnUiThread
                }
                MktData.MktCache.put(ck, out)
                MktData.MktSave.store(ck, out) // 稿 原稿(已被 B-标杆迁移.html 取代):抓成功后内存+落盘都写
                renderStock(MktData.pickStockWinner(out), mkt, seq, null)
            }
        }.start()
    }

    // ---------- 币多腿:单线程按 cryptoLegs() 顺序**串行**,命中即停(稿L856-863) ----------
    // 与 loadStockParallel 同构:一条腿命中即 renderCrypto + return,后续腿不再发请求,
    // 不再发多余请求浪费大等待时间;BTCUSDT 的 legs 只有 BN 一条,行为不变。
    private fun renderCrypto(vs: List<VendorKs>, mkt: String, seq: Int, prelim: String?) {
        if (seq != reqSeq) return
        prelimTag = prelim
        renderAgg(MktData.aggregate(vs), mkt, seq)
    }

    // 同上:形参是**取数档位**,不是用户填的根数。
    private fun loadCryptoParallel(s: String, fetchN: Int, mkt: String, seq: Int) {
        val ck = MktData.MktCache.key(s, tf, fetchN)
        MktData.MktCache.get(ck)?.let { vs ->
            renderCrypto(vs, mkt, seq, null)
            return
        }
        // 稿源状态行:有留底时(同 loadStockParallel):先画留底顶着 + 「拉取中 · 同一源不重试」
        var hadSaved = false
        MktData.MktSave.saved(ck)?.let { sv ->
            hadSaved = true
            renderCrypto(sv, mkt, seq, null)
            mkStatus("百度股市通 / 腾讯 竞速中 · 失败腿不回退重试", false)
        }
        if (!hadSaved) mkStatus("百度股市通 / 腾讯 竞速中 · 失败腿不回退重试", false)
        val legs = MktData.cryptoLegs(s)
        // ⚠⚠⚠ 2026-10-03 **竞速改替补**（用户裁定：「取消竞速模式，采用替补模式」）。
        //   改前：所有腿并行发，`got.compareAndSet` 让**先回来的赢**，其余一律丢弃。
        //   ⟹ 赢的是**网络最快的那条**，不是**数据最全的那条**。
        //     黄金那条就是被这个坑掉的：新浪响应快但 `volume` 恒为 0，
        //     抢在东财前面赢 ⟹ VPVR 整片空白，且**谁赢不确定** ⟹ 「第一次有、第二次没了」。
        //   现在：**按 cryptoLegs 的顺序依次试**，第一条成功的即胜出，
        //   后面的腿**根本不发请求**。结果确定，不受网络快慢影响。
        //   ⚠ 代价：前面的腿失败要等它自己的超时才轮到下一条（cryptoLegs 通常 3~4 条）。
        //     这是「确定」换「可能更慢」，与用户裁定一致。
        //   ⚠ 顺序即优先级：cryptoLegs 的排列从此**决定**谁优先，不再是「谁快用谁」。
        Thread {
            var hit: VendorKs? = null
            var hitLeg = ""
            val legStates = mutableListOf<MktData.LegInfo>()
            for (leg in legs) {
                if (seq != reqSeq) return@Thread
                var v: VendorKs? = null
                var threw = false
                try {
                    v = MktData.fetchCryptoLeg(leg, s, tf, fetchN)
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
                if (seq != reqSeq) return@runOnUiThread
                if (v != null) {
                    // 【F-2a · 修 B-1】同上：折算失败就不出 K 线。加密腿当前是 USD（fxify 直接 true），
                    // 但**规则不能靠「今天是美元」来兜底** —— 哪天多一条非美元腿，
                    // 这里就会重演 B-1。所以三条消费点一律按返回值把关。
                    if (!MktData.FX.fxify(listOf(v), s)) {
                        mkStatus("汇率未取到 · " + curSym() + " 不出 K 线（避免用原币当美元）", true)
                        t123PaintNoData()
                        finishLoad(seq)
                        return@runOnUiThread
                    }
                    MktData.MktCache.put(ck, listOf(v))
                    MktData.MktSave.store(ck, listOf(v))
                    MktData.publishFavLegs(
                        listOf(MktData.LegInfo(legName, hit = true, failed = false, skipped = false)))
                    // t324 稿 L318 的「· 42 根」: 这个闭包里只有 legName/v,
                    // info 是 onInfo 的参数、在别的作用域, 这里用 loadCryptoParallel 的 **取数档位**。
                    // t44:本值语义是「**取到**的根数」,故用 fetchN(240),不是用户填的显示数。
                    // ⚠️ 跨屏提示:它被 **FavPanel** 读去拼自选屏源行的「· N 根」(FavPanel.kt:450-451),
                    //   所以改根数后**自选屏那一行的 N 会跟着变成取数档位**。
                    MktData.lastLegsCount = fetchN
                    renderCrypto(listOf(v), mkt, seq, null) // 命中即停
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
                }
                finishLoad(seq)
            }
        }.start()
    }
}
