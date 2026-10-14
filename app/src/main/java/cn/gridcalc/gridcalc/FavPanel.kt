package cn.gridcalc.gridcalc

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.SuperscriptSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

// ---------- 自选收藏 · 存储层(对标稿子localStorage gc_fav) ----------

data class FavItem(val s: String, val t: String)

// 稿§2/§3:行的记录侧元数据(该品种最新一条),重绘装配、刷新时按需重算收益比
private data class RowMeta(val gain: Double?, val net: Double?, val gts: String)

object FavStore {
    private const val PF = "gridcalc_fav"
    private const val KEY = "gc_fav"
    private const val SORTKEY = "gc_favsort"

    /**
     * 屏上显示的符号：去掉 A 股的 `sh`/`sz` **取数前缀**。
     * 港/美/币原样返回 —— 它们的内部符号本来就与屏上一致
     * （港股那行显示 `00700` 而不是 `hk00700`）。
     * ⚠ 只影响**显示**，取数仍用原始符号 —— 拆掉前缀会让 `symType` 认不出 A 股。
     */
    fun displaySym(s: String): String {
        val t = s.trim()
        return if (Regex("^(SH|SZ)\\d{6}$", RegexOption.IGNORE_CASE).matches(t)) t.drop(2) else t
    }

    fun tag(t: String): String = when (t) {
        "crypto" -> "币"
        // ⚠ t8 照稿 A1②：这里喂的是**徽章**，稿的徽章用词是「贵金」。
        //   稿 v2.html:133 `{sym:'XAUUSD', badge:'贵金', …}` 逐字；
        //   稿 v2.html:145 的注释也写明「新稿用词 `贵金`（截断词），不是「贵金属」」。
        //   「贵金属」三字在稿里只出现在 SRC 表的 `ty`（品种类型），**徽章字段用的是截短的「贵金」**
        //   —— 本函数渲染的正是徽章，故本条改为「贵金」。
        // 旧注引「稿 L117: XAUUSD: {ty:'贵金属' …}」指向的是 SRC 表的 ty 那一处，与徽章是两回事，已改对。
        // 「黄金」这三字在本仓还有两处(MktSuggest 的品种名表与搜索别名), 刻意不动:
        // 动了用户打「黄金」就搜不到 XAUUSD 了。那两处是【检索用词】, 不是显示用词。
        "gold" -> "贵金"
        "silver" -> "贵金"
        "stock" -> "美股"
        // ⚠⚠⚠ 2026-10-04 **补 A 股**。原来 [MktData.symType] 已经会返回 "ashare"，
        //   但这里没有对应分支 ⟹ 落到 `else -> t` ⟹ 徽章**原样显示 `ashare`**（实测）。
        //   这就是「加了枚举值、漏改穷举分支」那类自伤：类型认出来了，展示没跟上。
        "ashare" -> "A股"
        "cmdty" -> "商品"
        "hk" -> "港股"
        "kr" -> "韩股"
        else -> t
    }

    private fun prefs(c: Context) =
        c.getSharedPreferences(PF, Context.MODE_PRIVATE)

    fun get(c: Context): MutableList<FavItem> {
        val out = mutableListOf<FavItem>()
        try {
            val a = JSONArray(prefs(c).getString(KEY, "[]") ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val s = o.optString("s", "")
                if (s.isNotEmpty()) out.add(FavItem(s, o.optString("t", "")))
            }
        } catch (_: Exception) {
        }
        return out
    }

    // v4 D10:SP 写全程 try/catch,失败降级为「本次会话内生效」,不崩
    private fun set(c: Context, a: List<FavItem>) {
        try {
            val j = JSONArray()
            for (it in a) j.put(JSONObject().put("s", it.s).put("t", it.t))
            prefs(c).edit().putString(KEY, j.toString()).apply()
        } catch (_: Exception) {
        }
    }

    fun isFav(c: Context, s: String): Boolean = get(c).any { it.s == s }

    fun add(c: Context, s: String, t: String) {
        val a = get(c)
        if (a.none { it.s == s }) {
            a.add(FavItem(s, t))
            set(c, a)
        }
    }

    fun remove(c: Context, s: String) {
        set(c, get(c).filter { it.s != s })
    }

    // 排序模式(稿§2):三键互斥 `dist|gain|ratio` × `asc|desc`;
    // 稿 L352-361 的排序头只有 {k, asc} 两态, 稿全文 923 行**没有 'off'**; L417-418 的比较器也永远执行。
    // ⟹ 本函数**任何出口都不再返回 dist:off**(旧值/坏值/异常一律 dist:desc),
    //    否则下面那个「dir==off 就整段跳过排序」的分支会被坏值误触发。
    fun getSort(c: Context): String {
        // v4 D10:SP 读 try/catch,坏值/异常一律回落 dist:desc(不再是 off)
        val v = try {
            // 稿 L834 useState({k:'dist', asc:false}) ⟹ 进页即按距支撑降序。
            // 顺带把旧版存下的 "off" 和 "dist:off" 都迁成 "dist:desc": 不迁的话, 用过旧版的人
            // 升级后仍然是"不排序", 稿的行为对他根本不生效 —— 改了一半等于没改。
            // 依据: 稿 L352-361 的排序头是 {k, asc} 两态, 全文没有 'off' 这个状态;
            // t241 之后 cycleSort 也只写 $col:asc / $col:desc。
            // 但光迁旧值不够 —— 异常与坏值同样会落到下面的兜底, 所以三个出口一起收敛。
            prefs(c).getString(SORTKEY, "dist:desc")
                ?.let { if (it == "off" || it == "dist:off") "dist:desc" else it }
        } catch (_: Exception) {
            null
        } ?: "off"
        if (v.contains(':')) {
            val p = v.split(":")
            if (p.size == 2 &&
                (p[0] == "dist" || p[0] == "gain" || p[0] == "ratio") &&
                (p[1] == "asc" || p[1] == "desc")
            ) return v
            return "dist:desc"
        }
        return when (v) {
            "asc" -> "dist:asc"
            "desc" -> "dist:desc"
            else -> "dist:desc"
        }
    }

    fun setSort(c: Context, v: String) {
        try { // v4 D10:写失败降级为内存态,不崩
            prefs(c).edit().putString(SORTKEY, v).apply()
        } catch (_: Exception) {
        }
    }
}

// ---------- 自选页 · 面板接线(对标稿子page-fav/paintFav/favSearch/favDist) ----------
// 点行直达(行情页加载),**左滑删除**,搜索精确>前缀>包含排序,
// 已收藏剔除,＋后清场;行右距离列(距第一支撑pct+现价+涨跌色块),右上排序键循环。
//
// ⚠️ **2026-10-01 订正 + 实施（t45）**
//   本块原写「删除交互 = 长按 600ms」并标着「对标稿子 touchstart 600ms」——
//   **那句在 2026-09-30 用户裁定后已过期**：稿把删除改成了**左滑**
//   （稿 WatchRow：`SWIPE_W=72` / `TH=24` / `SLOP=8`，注释原文「取代长按 600ms」）。
//   2026-10-01 用户再次裁定「改成左滑」 ⟹ **App 已按裁定改成左滑**（见 `armSwipe()`），
//   `armDelete()` 与它的 600ms 定时器**已一并删除，不是并存**（并存 = 两个手势打架）。
//
//   （另：本块提到的「搜索精确>前缀>包含排序、已收藏剔除、上限 12」是 **App 侧更细**的一套；
//     稿的 `suggest()` 只有两档、上限 5、且**不**剔除。本轮迁移方向是**稿向 App 看齐**，
//     所以这几处**不构成缺口**，别去「修」成稿那样。）

class FavPanel(
    private val act: MainActivity,
    page: View,
    private val mkt: MktPanel,
    /** ⚠ 2026-10-03 用户裁定「把行情换成该交易品种的名字」⟹ 回调多带一个**显示名**。
     *  改前只有 (String)，行情页拿到的只有代码（MU），拿不到「美光」，
     *  顶栏只能一直写死「行情」。
     *  ⚠ 这个名字**不是 FavItem.t** —— t 存的是「美股/币/韩股」市场标签；
     *    中文名走 MktSuggest.favName(sym)（见 :1273 调用处）。
     *  ⚠ 三处调用点(:684 / :1273 / :1644)都已改成双参，漏一处就编译不过。 */
    private val onPick: (String, String) -> Unit
) {

    private val searchInp: EditText = page.findViewById(R.id.fav_search)
    // ⚠ 2026-10-06 容器换回 LinearLayout（见 page_fav.xml 同日注释）：
    //   嵌在 ScrollView 里的 RecyclerView 只量出一屏高度，12 个品种只画 6 个，
    //   剩下根本不创建。点击保证靠监听挂法（行根监听 + 子控件不接管），与容器无关，
    //   换回来之后全部保留。取数线一律不动。
    private val favList: LinearLayout = page.findViewById(R.id.fav_list)
    // t121 修必崩 NPE(原 FavPanel.kt:203)。
    //
    // 原 bug：t112 我把 favSrcRoot 存成了 `fav_srcrow` **自己**，然后在
    // t111WireSrcRow 里用 `root.findViewById(R.id.fav_srcdetail)` 去找展开明细。
    // 但 page_fav.xml 里 fav_srcdetail(:87) 与 fav_srcrow(:50) 是**兄弟**节点，
    // 而 View.findViewById **只在自身子树里搜** —— 于是 detail 拿到 null，
    // 点「展开」时 :203 解引用直接 NPE 崩溃。
    // （讽刺的是同一段里唯一写了 `?: return` 的 row 反而没事，没写保护的两个崩了。）
    //
    // 修法：**四个 view 一次性从 page 取出存成字段**，各自显式判空；
    // 不再用「拿 row 当根再往下找兄弟」这种写法。**没有用 try/catch 吞异常。**
    // t131 按稿 ScreenList 的四态视图
    private val favListV: View? = page.findViewById(R.id.fav_list)

    // t201 ⑦ 【这里原来有个 t131ErrAgeNow(), 已按稿删掉】
    // 删的理由: 它照抄的是稿 L449 的 r={{...r, age:'旧 3 小时'}}, 而稿的 WatchRow
    //   (L375/L378)只读 r.stale 与 r.miss, **从不读 r.age** —— 那是句死数据。
    //   稿在 err 态下渲染出来的行【本来就没有「旧」标记】, App 却逐行加了一个,
    //   于是真机上出现「旧 3 秒前」这种自相矛盾的说法(3 秒的数据并不旧)。
    // 行上的真 stale 标记【没有动】, 见本文件 dc.text = if (old) ... 那处(对应稿 L375/L378)。
    // 年龄信息也没丢: 错误头那句「以下为 HH:MM:SS 的缓存数据(N 前)。同一源不重试。」仍在,
    //   稿就是只在横幅上说一次、不逐行重复。

    /** t131 四态的当前值。行渲染要靠它决定是否加「旧 N 小时」。 */
    @Volatile private var t131State: String = "ok"
    private val favEmptyV: View? = page.findViewById(R.id.fav_empty)
    private val favSkelV: View? = page.findViewById(R.id.fav_skel)
    private val favErrHeadV: View? = page.findViewById(R.id.fav_errhead)
    private val favErrHeadDV: android.widget.TextView? = page.findViewById(R.id.fav_errhead_d)
    private val favStateLineV: View? = page.findViewById(R.id.fav_stateline)
    private val favSlIconV: android.widget.TextView? = page.findViewById(R.id.fav_sl_icon)
    private val favSlTxtV: android.widget.TextView? = page.findViewById(R.id.fav_sl_txt)
    // t8 照稿 A1③：屏底「距支撑 实时 N / 无源 N」计数行（稿 v2.html:1641-1644）。
    private val favCountV: android.widget.TextView? = page.findViewById(R.id.fav_count)
    // t8 照稿 A1·副标「N 个品种 · 全部美元口径」（见 paintFavSubtitle 的注释）。
    private val favSubtitleV: android.widget.TextView? = page.findViewById(R.id.fav_subtitle)

    private val favSrcRowV: View? = page.findViewById(R.id.fav_srcrow)
    private val favSrcDotV: android.widget.TextView? = page.findViewById(R.id.fav_srcdot)
    // t126:副标 + 右上 4 状态芯片 + 脚注。规格 1.1/1.2/1.6
    // t128 (3) 汇总条三格(规格 1.2)
    /**
     * t128:行装配处**顺手**把汇总条要的两个数列产出来(与列表同源,不另读一遍)。
     * `Row` 是装配函数内的局部类,不能做字段类型,所以只存数值。
     * 语义:distPct 只放**有效有限**的距支撑(小数,0.0312 = 3.12%);
     *      ratio 只放**有效有限且 > 0** 的收益比。
     */
    private val favSrcMainV: android.widget.TextView? = page.findViewById(R.id.fav_srcmain)
    private val favSrcMoreV: android.widget.TextView? = page.findViewById(R.id.fav_srcmore)
    // t324 #9: 从 TextView 改成 LinearLayout —— 逐腿明细要每腿一个独立行(稿 L328-340)
    private val favSrcDetailV: android.widget.LinearLayout? = page.findViewById(R.id.fav_srcdetail)
    // 稿§2 三列头:距支撑104dp/收益率76dp/收益比62dp,与行内三格逐字对齐
    private val sortDist: Button = page.findViewById(R.id.fav_sort_dist)
    private val sortGain: Button = page.findViewById(R.id.fav_sort_gain)
    private val sortRatio: Button = page.findViewById(R.id.fav_sort_ratio)

    init {
        // 距离持久化层启动即挂上applicationContext,首帧repaint就能读到上次成功值
        FavDist.attach(act)
    }

    // 搜索候选内联容器(2026-10-06 起取代 PopupWindow 浮层,见 page_fav.xml fav_sug_box)。
    // 同一窗口、不抢焦点,键盘不会被收。内容超 248dp 时内部滚动(口径沿用原来浮层的)。
    private val sugBox: LinearLayout = page.findViewById(R.id.fav_sug_box)
    private val resultsBox: LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(9.5f).toInt()
        setPadding(p, dp(3.8f).toInt(), p, dp(3.8f).toInt())
    }
    // ⚠⚠ 2026-10-11 **用户报「自选搜索界面的候选列表无法滑动」**，根因就在下面这个手势拦截。
    //   候选列表本身一直是能滚的（高度 248dp 封顶，见 showResults），问题是**手势到不了它**：
    //   整页根布局是 ScrollView（page_fav.xml:3），本 ScrollView 与它**同方向**嵌在里面，
    //   外层在 ACTION_MOVE 越过 touchSlop 时由 onInterceptTouchEvent 把整串手势抢走
    //   ⟹ 内层收不到 MOVE，候选列表一动不动，滚的却是整页。
    //   修法：本 ScrollView 在 ACTION_DOWN 时对**父链**调
    //   `requestDisallowInterceptTouchEvent(true)`（沿父链上抛，根 ScrollView 的
    //   onInterceptTouchEvent 会先看这个标记 ⟹ 它让路），UP/CANCEL 时还回去。
    //   ⚠ 挂在 dispatchTouchEvent 而**不是** onTouchEvent：候选行（rowView）的 pick/＋
    //     是 clickable，ACTION_DOWN 会被子 View 吃掉，onTouchEvent 根本收不到 DOWN，
    //     标记就永远设不上（这正是「看着像没修」的坑）。dispatchTouchEvent 是每个手势
    //     必经的入口；本重写 super 一回来就设标记，**早于任何 MOVE** —— 而父链的拦截判定
    //     只在 MOVE 上才看这个标记（DOWN 一进来 ViewGroup 就把标记清了，DOWN 之后设依然有效）。
    //     且只动标记、不改任何子 View 的点击/选择路径。
    //   ✅ 2026-10-12 实机复验（emulator-5554，本 APK 由工作树现装）：
    //     · query=A（候选多于 4 行：AAPL/AMZN/AMD/ARM/ASML/AMAT/XAU*，末行被框底裁掉；
    //       框高被 248dp 封顶 = 682px @440dpi）框内上拖 ⟹ 内层滚动（ARM→首行、
    //       露出 ASML/AMAT），fav_search/fav_sug_box 坐标一动不动 = 整页没滚；
    //       下拖可回顶。框外（自选列表区）上拖 ⟹ 整页滚，框随页上移，整页滚动不回归。
    //     · query=AAPL（只 1 行，框高 204px < 248dp）框内上拖 ⟹ canScrollVertically 为假、
    //       不设标记，整页照常滚 —— 「装得下就不抢手势」成立。
    //     · 点候选行左半 ⟹ 照常直达行情页；点＋ ⟹ 照常加进自选（测完已删，未留残留）。
    //   ⚠ 只在**内容真的超了 248dp**（canScrollVertically 为真）时才抢手势：
    //     候选本来就装得下时没有可滚的东西，那时让外层照常滚整页，不制造「拖了没反应」。
    //   ⚠ 与 MktView.onTouchEvent(:169) 是同一套写法，不是新发明。
    private val sugScroll: ScrollView = object : ScrollView(act) {
        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            val handled = super.dispatchTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> if (canScrollVertically(1) || canScrollVertically(-1)) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    parent?.requestDisallowInterceptTouchEvent(false)
            }
            return handled
        }
    }.apply {
        isVerticalScrollBarEnabled = false
        addView(resultsBox)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var fsRunnable: Runnable? = null
    private var fsGen = 0

    // ⚠ 2026-10-06 RecyclerView 化：逐行 View 引用不再用三张 map 存，
    //   改由 Adapter 持有一份数据 + ViewHolder 持有一行控件。
    //   旧 maps（capsules/ratios/metas）随手拼 LinearLayout 时代一起退役 ——
    //   它们是「整块 removeAllViews 重建后靠符号反查控件」的补丁，RecyclerView 下不需要。
    private data class FavRow(
        val it: FavItem,
        var d: DistR?,
        val m: RowMeta,
        var ratio: Double?
    )

    // ⚠ 2026-10-06 容器换回 LinearLayout 后的行管理（见 favList 处注释）：
    //   行壳照样建一次挂进 favList，用“符号→行壳”表做逐条更新；
    //   点击挂法、确认条、不重建语义全部保留，只是触发点从 Adapter 回到面板。
    private val shells = mutableMapOf<String, RowShell>()
    private var rowData: List<FavRow> = emptyList()
    /** 当前展开行内确认条的品种；null = 都没展开。同时只展开一行。 */
    private var confirmSym: String? = null


    /** 切换确认条：只重绑新旧两行。 */
    private fun setConfirm(s: String?) {
        if (confirmSym == s) return
        val old = confirmSym
        confirmSym = s
        old?.let { o -> shells[o]?.let { sh -> rowData.find { it.it.s == o }?.let { r ->
            bindRowShell(sh, r, pendingSyms.contains(o), false) } } }
        s?.let { n -> shells[n]?.let { sh -> rowData.find { it.it.s == n }?.let { r ->
            bindRowShell(sh, r, pendingSyms.contains(n), true) } } }
    }


    /** 全量换数据：重建所有行（品种增删/排序变化/刷新完成时）。LinearLayout 对全部子项无条件布局，行数再多也不会截断。 */
    private fun submitRows(newRows: List<FavRow>) {
        rowData = newRows
        if (confirmSym != null && rowData.none { it.it.s == confirmSym }) confirmSym = null
        shells.clear()
        favList.removeAllViews()
        for (r in newRows) {
            val sh = createRowShell()
            sh.root.setOnClickListener {
                val s = sh.boundSym ?: return@setOnClickListener
                hideResults()
                onPick(s, MktSuggest.favName(s))
            }
            sh.root.setOnLongClickListener {
                val s = sh.boundSym ?: return@setOnLongClickListener false
                setConfirm(s)
                true
            }
            bindRowShell(sh, r, pendingSyms.contains(r.it.s), confirmSym == r.it.s)
            shells[r.it.s] = sh
            favList.addView(sh.root)
        }
    }


    /** 逐条更新：只重绑该行，不碰其他行、不重建列表。 */
    private fun updateDist(s: String, d: DistR?) {
        val r = rowData.find { it.it.s == s } ?: return
        r.d = d
        val distPct = if (d != null && d.ok) d.pct else null
        r.ratio = ratioOf(distPct, r.m)
        shells[s]?.let { sh -> bindRowShell(sh, r, pendingSyms.contains(s), confirmSym == s) }
    }
    private var hidden = true
    private var distSeq = 0

    /**
     * **正在取数的品种集合**（t35 移植「待取数」第四态用）。
     *
     * 为什么需要它：原来一条**正在取数**的行和一个**真的取不到**的行，
     * 在 flagOf 看来都是 `d == null` ⟹ 都显示「—无源」。但这两件事相反：
     * 前者是「**还没取到**」，后者是「**没有**」。把还没取到说成没有，是**误报**，
     * 而且还会被当成缺源**标红** —— 用户 2026-09-30 的裁定原文正是「**绝不标红**」。
     *
     * ⚠️ **本集合只记录状态，不改变取数行为**：写入点只有两处
     *    （发起前 add、回到 UI 线程 remove），**不引入节流、不改并发模型**。
     *    `FavPanel.kt:1070-1095`「每品种一个 Thread 同时起、无节流无上限」那个惊群问题
     *    **本条按队长裁定只报不改**，它是下一条的起点。
     *    用 synchronizedSet 而不是 ConcurrentHashMap.newKeySet：后者要 import，
     *    这里宁可不加 import 也不动文件头的 import 区。
     */
    private val pendingSyms = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private fun dp(v: Float): Float = act.resources.displayMetrics.density * v

    // t102 第0步:字号从散落的魔法数收成 res/values/dimens.xml 常量。
    // 这一步只做「搬家」,数值逐一等于迁移前的原值,所以渲染逐像素不变。
    // 第1步起再把 dimens 的值收敛成 hero30 / data21 / title16 / note12 四档。
    // t102 第0步:字号从散落的魔法数收成 res/values/dimens.xml 常量;第1步起全 App 只剩 4 档。
    // 除以 scaledDensity 而不是 density:TextView.textSize 的单位是 **sp**,要跟着用户字号设置缩放。
    private fun TS(id: Int): Float = t102sp(act, id)

    // t102 第2步:三列宽**与表头共用同一份 dimen**(handoff §5.1-2)。
    // 迁移前是「表头 XML 写死 96/66/58 + 行内 Kotlin 写死 96/66/58」两套值,注释还写着 104/76/62,
    // 已经漂过一次;现在两边都引 col_dist/col_roi/col_ratio,改一处全局生效。
    private val density: Float get() = act.resources.displayMetrics.density

    // 稿.favrow .fb-* 徽标配色(固定hex,不随主题)
    /**
     * t111 (1) 自选页源状态行。**复用行情页同一套数据**(MktData.legsOf(行情页当前品种) / srcBadAt),
     * 不另写一份取数、**不新增任何请求** —— 只是把最近一次行情页的取数结果也显示在自选页。
     * 形状编码不变:实心点=正常,空心环=异常;颜色是 colorSub 灰圈,**不变紫**。
     */
    private fun t111WireSrcRow() {
        // t121:全部改用字段引用 + 显式判空。不再用「拿一个 view 当根再往下找兄弟」的写法。
        val row = favSrcRowV
        val detail = favSrcDetailV
        val more = favSrcMoreV
        if (row == null || detail == null || more == null) return   // 少一个就不绑,绝不半绑
        row.setOnClickListener {
            val vis = detail.visibility != View.VISIBLE
            detail.visibility = if (vis) View.VISIBLE else View.GONE
            more.text = if (vis) "收起" else "展开"
        }
    }

    /** t112:自选页重绘链路里**真正调用**源状态行。
     *  上一轮只写了函数却没在任何地方调它 —— 这是「冷启动不出现」的真凶(不是 lastLegs 空)。 */
    /** t114:由**值实际产生的时间戳**算相对时间。分钟/小时分级,不用硬编码。 */
    private fun t114Ago(at: Long): String {
        val d = (System.currentTimeMillis() - at).coerceAtLeast(0L) / 1000
        return when {
            d < 60 -> "${d} 秒前"
            d < 3600 -> "${d / 60} 分钟前"
            else -> "${d / 3600} 小时前"
        }
    }

    /**
     * t129:旧的独立源状态行**恢复显示** —— 稿的 ScreenList 里有 SourceLine,
     * 且 t114–t123 的诚实性成果(●/○ 与文案同源、无数据绝不打 ●、
     * 时间指值实际产生时刻)必须保留。t128 我把它挪到芯片上,本轮撤回。
     */

    /**
     * t131:按稿 ScreenList(L415-457)实现四态。
     *
     * **状态判定依据(由真实情况推导,不是演示控件)**:
     * | st | 依据 |
     * |---|---|
     * | `empty` | 自选列表为空(一个品种都没有) |
     * | `err`   | 自选非空 **且** `MktData.legsOf(行情页当前品种)` 非空 **且** 无一条 `hit`(源全挂/本次全失败) |
     * | `load`  | 自选非空 **且** 正在取数(legs 为空 = 还没取到) |
     * | `ok`    | 其余 |
     *
     * 🔴 **空数据 ≠ 源不可达(与 t123 不变量并存,不混)**:
     * 「某个品种没有距支撑/收益比」属于**数据缺失**,在 `ok` 下**用「—」表示**;
     * 只有**整条取数链路一条都没成功**才进 `err`。两者依据不同,不互相触发。
     *
     * **时间诚实性**:`HH:MM:SS` 与「N 小时前」都取 `MktData.legsAtOf(行情页当前品种)`
     * —— **该值实际产生的时刻**(且必须是本品种那批的时刻),不是当前时刻。
     */
    private fun t131PaintState(loading: Boolean) {
        val n = try { FavStore.get(act).map { it.s }.distinct().size } catch (_: Exception) { 0 }
        // ⚠ 2026-10-12 t2：**按品种取腿**（MktData.legsOf），不再直接读全局 lastLegs。
        //   自选页没有自己的「当前品种」，它的源状态本就**复用行情页最近一次取数**
        //   （t111 注释原话），所以口径取行情页当前品种 `mkt.curSym()`；
        //   品种不匹配 → 空 → 状态机视同「这批腿不存在」，**不拿别的品种的腿定 err/ok**。
        //   病根（原全局 lastLegs 不分品种、整体覆盖）与修法详见 MktData.legsBySym 的注释。
        val legs = MktData.legsOf(mkt.curSym())
        val hit = legs.any { it.hit }
        // t132:按队长裁定重写。关键是 `MktData.fetching` ——
        // lastLegs 是「上一次**已完成**竞速的结果」,取数途中它挂的仍是上一次的,
        // 所以**没有 fetching 就分不出「正在取」和「取完了全挂」**。
        val fetching = MktData.fetching
        val anyHit = legs.any { it.hit }
        // 「有腿确实被投过」= 至少一条 skipped==false。
        // 全 skipped 是「一条都没试」(被 10 分钟失败记忆排除),**不是源全挂**。
        val anyDispatched = legs.any { !it.skipped }
        val st = when {
            fetching -> "load"                                            // ① 取数中
            n <= 0 -> "empty"                                             // ② 自选为空
            !fetching && anyHit -> "ok"                                   // ③ 完成且有命中
            !fetching && legs.isNotEmpty() && !anyHit && anyDispatched -> "err"  // ④ 完成且全灭
            else -> "ok"                                                  // ⑤ 其余(含全 skipped)
        }
        fun show(v: View?, on: Boolean) { v?.visibility = if (on) View.VISIBLE else View.GONE }
        show(favEmptyV, st == "empty")
        // ⚠⚠⚠ 2026-10-05 **取消骨架屏**（用户裁定：「能否取消，直接展示旧值就可以了，
        //   然后等待条件更新，不需要全部加载出来再更新」）。
        //   骨架屏整块退出列表显示，恒隐藏；下面的 favList 也恒可见 ——
        //   这两处**合起来**才是「取数期间一行业务数据都没有、点/长按落空」的真凶。
        //   行的值本来就取 FavDist.display(..., cached(...)) 的旧值，进页第一帧即出行；
        //   refreshFavDist 逐品种刷新，回调里逐行 paintDist ⟹ 逐条更新照旧。
        //   ⚠ 状态归属的修复原样保留（fetching 仍取 pendingSyms、[st] 仍照旧算、
        //     repaint 末尾仍无条件求值）—— 只是不再用状态机去**遮蔽**列表。
        show(favSkelV, false)
        // ⚠⚠⚠ 2026-10-03 用户裁定：**源状态行整块删除**。
        //   原话（早先一轮）：「图里的东西不要了」——点名要删的就是它和「· N 根月线已更新」。
        //   本轮原话：「为什么有多了这么多东西，**我有说要添加嘛？**」
        //
        //   下面这两个 show() 原来把 fav_errhead / fav_srcrow 从 XML 的 gone 拉成 VISIBLE，
        //   于是屏上多出：① 顶部一行「○ —无源 / 展开」 ② 底部整块
        //      「行情源不可达 / 以下为 X 的缓存数据(0 秒前)。同一源不重试。
        //        ! 源不可达 ≠ 无此品种 ≠ 无支撑 —— 三种原因分别提示，不合并」
        //   **用户从没要求过这些。** 它是 t102「照稿补源状态行」那一轮的产物，
        //   之后我一直在**围着它讨论措辞和熔断策略**，等于默认了它的存在。
        //
        // ⟹ 改成**无条件 GONE**。诚实性（逐腿失败原因）改由各行自己的「—无源」承担 ——
        //   那本来就是用户看得懂的信号；这一大块解释性文字是**第二套说法**。
        //   ⚠ 与 MktPanel 行情屏的源状态行是**两处**，那一处本轮未动；
        //     若也要删，说一声，两处一起改。
        show(favErrHeadV, false)   // 恒隐藏
        // 稿 L449:err 态**错误头下面照样列出行**,每行 age='旧 3 小时'。
        // 错误头没了，列表照常显示。
        // ⚠⚠ 2026-10-05 同上裁定：**列表恒可见**。改前是 `ok`/`err` 才 VISIBLE，
        //   落在 `load`（自选在途取数）时整块 GONE —— 那是「骨架屏顶掉列表」的另一半。
        //   ⚠ 空态不受影响：st=="empty" 时本容器没有子 View（height=wrap_content ⟹ 0 高、
        //   不占位），fav_empty 是它的**兄弟**节点，位置与改前一致。
        favListV?.visibility = View.VISIBLE
        // ⚠ 2026-10-02 照稿：自选屏的**源状态行只在非 ok 态出现**。
        //   稿的 ScreenList 里没有 SourceLine，只有 {st!=='ok' && <StateLine/>}（v2.html:1654）。
        //   现状是 paintSrcRow() 一有腿就 VISIBLE，于是正常态也多出「● 东财 · 展开」一行 ——
        //   这正是用户嫌"屏上多出来的字"的那一类噪音，且与稿不一致。
        //   ⚠ 但**不能删这个能力**：err/load 态它仍要报逐腿真实失败原因（t114-t123 的诚实性成果）。
        //   故只改「什么时候出现」，不改它出现时说什么。
        val prevState = t131State          // ⚠ 必须在赋值**之前**取，见下
        t131State = st
        show(favSrcRowV, false)   // 恒隐藏（同上，源状态行整块删除）
        // ④ StateLine:仅 st != 'ok'(稿 L453)
        // ⚠⚠⚠ 2026-10-03 **状态行整块删除**（用户裁定，这是第三次）。
        //   原话：「我说了无数遍了，我没有要要这个，为什么又显示了？」
        //   触发它的那句是：「源不可达 ≠ 无此品种 ≠ 无支撑 —— 三种原因分别提示，不合并」
        //
        // 【为什么这次必须删干净，而不是再藏一次】
        // 上一次我只把 **favSrcRowV（● 东财 · 展开 那一行）** 设成恒 GONE，
        // **漏了 favStateLineV（④ StateLine）** —— 它是另一组节点、另一处 `show()`，
        // 于是非 ok 态照样显示。
        // ⟹ 教训：这类"删"要按**节点**清，不是按"看起来是同一块"清。
        //   一个功能区往往横跨多个 id，逐个 hide 必然漏。
        //
        // 【这句文案为什么尤其该删】
        // 它不是状态提示，是**把 App 的内部设计说明念给用户听**
        //（"三种原因分别提示，不合并" —— 这是写给开发者的自述，用户不需要）。
        // 属于「解释性文字」，用户从未要求，且已明确拒绝两次以上。
        //
        // ⚠ 保留 [st] 的计算与 [t131State] 的状态机：它还驱动 fav_errhead 与
        //   距支撑计数的口径，删的是**显示**，不是**判定**。
        show(favStateLineV, false)   // 恒隐藏 —— 源状态行 + 状态行，整块删除
        show(favErrHeadV, false)     // 同上：错误头（「以下为 XX:XX:XX 的缓存数据…」）一并删
        // ⚠ 下面这些**仍然在给已隐藏的 TextView 赋文案**。
        //   留着是因为 favSlIconV / favSlTxtV / favErrHeadDV 仍被别处引用
        //   （见本文件字段声明处的引用表），改赋值会牵连 findViewById 链。
        //   **视图恒 GONE ⟹ 赋什么都不会出现在屏上**，所以不再逐句删。
        //   ⚠ 但**不要再把 show(...) 改回去** —— 用户已三次拒绝这块文字。
        val isErr = st == "err"
        // 稿 L288:err=中性墨色(--ink);warn=决策紫(--ac)。**涨跌红只留给价格本身**。
        val c = act.attrColor(if (isErr) "colorInk" else "colorPrimary")
        favSlIconV?.setTextColor(c)
        favSlTxtV?.setTextColor(c)
        favSlIconV?.text = if (isErr) "!" else "▲"     // 稿 L290
        favSlTxtV?.text = when (st) {                    // 稿 L453-455,逐字
            "err" -> "源不可达 ≠ 无此品种 ≠ 无支撑 —— 三种原因分别提示，不合并"
            "empty" -> "空态是合法结果，不是错误"
            else -> "拉取中 · 同一源不重试"
        }
        if (isErr) {
            // ⚠ 2026-10-12 t2：缓存时刻同样按品种取（不是当前品种的批次 → 0 → 不报别人的时刻）。
            val at = MktData.legsAtOf(mkt.curSym())
            val hh = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(at))
            // t173:同上,三档而不是整小时。稿 L447 的括号里是年龄,不是「0 小时」。
            val ago = t114Ago(at)
            favErrHeadDV?.text = "以下为 $hh 的缓存数据（$ago）。同一源不重试。"
        }
        // 状态变了就要重画行 —— 否则行上的「旧 N 小时」和错误头的时刻对不上。
        // repaint() 不会回调 t112CallSrcRow(),所以这里不会递归。
        if (prevState != t131State) repaint()
    }

    fun t112CallSrcRow() {
        paintSrcRow()
        t131PaintState(loading = false)   // t131 四态; t132 判定改用 MktData.fetching
    }

    /* t8 照稿 A1·副标：「N 个品种 · 全部美元口径」。N 取**真实自选数**,不写死。
     *
     * ⚠⚠ 改前这里是一段**空注释**，说的是「今天曾被误删(理由"解释性文字")，此稿证明它该在」——
     *   那句「此稿证明」引的是**旧一行**的稿；**现行稿恰恰相反**，见下一段。
     *
     *   稿 v2.html:1600-1607 逐字（墓碑）:
     *     「原先这里有个 AppBar「自选」+「9 个品种 · 全部美元口径」副标，已于 2026-09-30 按用户裁定
     *       整块删除（底部导航栏第一项已写着「自选」，重复是噪音；且「9 个品种」是写死的，
     *       品种增删后不会跟着变）… 但 App 侧确实有这条副标… 本轮**没有**加回来：
     *       用户正在抱怨屏上多出来的字，此时新增区块必然再被投诉。**要不要补，等用户拍板**。」
     *
     *   ⟹ 现行稿的状态是「**没有**这条副标」，稿把它挂成「等用户拍板」。
     *     **2026-10-02 用户裁定「完全照稿子改」** = 拍板补上 ⟹ 本条落地。
     *   ⚠ 稿墓碑点名的两个删除理由，本条各自都已处理:
     *     ① 「9 个品种 写死」 ⟹ N 取 FavStore.get(act).size，品种增删自动跟着变;
     *     ② 「与导航栏『自选』重复」⟹ 副标只有「N 个品种 · 全部美元口径」，**不再重复标题文字**。
     *   ⚠ 排版是**重建**的（稿没留下样式）：12px(T.note) / --ink2，见 page_fav.xml 同处注释。
     *
     * ⚠⚠ 本函数**当前没有任何调用点**（t8 2026-10-02 captain 裁定「代码备着但不要接进 repaint」），
     *   page_fav.xml 的 fav_subtitle 同时是 visibility="gone" ⟹ 屏上不出现、布局与改前逐像素相同。
     *   用户拍板后：① 去掉那条 visibility；② 在 repaint() 的 `val a = FavStore.get(act)` 之后
     *   补 `paintFavSubtitle(a.size)`。两处都已在本文件与 page_fav.xml 写明。
     */
    @Suppress("unused")
    private fun paintFavSubtitle(n: Int) {
        favSubtitleV?.text = "$n 个品种 · 全部美元口径"
    }

    /** t8 照稿 A1③：屏底「距支撑 实时 N / 无源 N · 待算 M」计数行。
     *
     * 稿 v2.html:1643 逐字: `距支撑 实时 {liveN} / 无源 {noneN}{waitN?' · 待算 '+waitN:''}`
     * 稿的三个数（v2.html:1593-1597）:
     *   liveN = list.filter(r=>r.distAt>0 && r.dist!=null).length   ← 本轮实算过 且 拿到值
     *   noneN = list.filter(r=>r.distAt>0 && r.dist==null).length   ← 本轮实算过 但没拿到值
     *   waitN = FAVQ.q.length                                       ← 还没开始的（=「待算」）
     *
     * App 侧对照（每一项都指到行上**看得见的**那个状态，不另造判据）:
     *   liveN ⟸ `d != null && d.ok && !isStale(d)`
     *         d.ok = 本轮管线出了值（FavDist.display 的 fresh.ok||fresh.soft 那一支）
     *         !isStale = 不是 SP 落盘的旧值（isStale 用 60s 阈值，与行上「旧 N 前」同一判据）
     *   noneN ⟸ `!pending && (d == null || !d.ok)`
     *         正是 flagOf 打「—无源」的那一批
     *   waitN ⟸ pendingSyms.size
     *         正在抓的那批；稿的 FAVQ.q 就是这个集合。
     *
     * ⚠ 三者**互斥**，合计 = 行数。稿的注释（v2.html:1595-1597）专门记过一次重复计数的事故
     *   （「实测出现过 实时4+无源1+计算中6 = 11 > 9 行」），所以 pending 必须先判、
     *   不能同时被算进 noneN。
     */
    private fun paintCountLine(live: Int, noneN: Int, wait: Int, total: Int) {
        val tv = favCountV ?: return
        // ⚠ 2026-10-13 用户要求数量行旁显示自选总品种数。第一版用三态求和当总数 —— **错**：
        //   「旧 N 前」的 stale 行既不计实时、也不计无源/待算（三态判据里没有它的位置），
        //   实测 9 行显示「共 8」。改为调用方传**真实行数 rows.size**，不再从三态推。
        tv.text = "距支撑 实时 $live / 无源 $noneN" +
            (if (wait > 0) " · 待算 $wait" else "") +
            (if (total > 0) " · 共 $total 个品种" else "")
        // 空列表时也照常显示 0/0：稿把这一行**常驻**渲染（v2.html:1642 不在 st!=='ok' 条件里）。
        tv.visibility = View.VISIBLE
    }

    fun paintSrcRow() {
        // ⚠⚠ 2026-10-03 用户裁定：**源状态行整块删除**（原话「我有说要添加嘛？」）。
        //   本函数原本负责画「● 源名 / 展开 / 逐腿明细」那一行。
        //   现在**什么都不画**：无条件把这一行与它的展开明细都设为 GONE。
        //
        //   ⚠ 为什么保留空实现而不是删函数：调用点有多处（repaint / t112CallSrcRow / …），
        //     删函数要连带改每一处调用；而留着空实现让那些路径继续跑、什么也不做，风险更低。
        //     ⚠ 若日后要恢复：把下面三行去掉，并恢复 t131PaintState 里那两个 show(...) 的条件。
        favSrcRowV?.visibility = View.GONE
        favSrcDetailV?.visibility = View.GONE
        return
        @Suppress("UNREACHABLE_CODE")
        t111WireSrcRow()
        val row = favSrcRowV
        val dot = favSrcDotV
        val main = favSrcMainV
        val more = favSrcMoreV
        val detail = favSrcDetailV
        // t121:任一缺失就不画这行(而不是画一半然后在点击时崩)
        if (row == null || dot == null || main == null || more == null || detail == null) return
        // ⚠ 2026-10-12 t2：按品种取腿（口径同 t131PaintState：行情页当前品种）。
        //   不匹配 → 空 → 下一行整行 GONE，**绝不把上一个品种的腿画进聚合行**。
        //   （本函数当前在上方 `return` 之后属不可达代码，改它是为：源状态行一旦按
        //     注释恢复，读取口径从第一天起就是对的，不再复刻「TSLA 显示 Naver」。）
        val legs = MktData.legsOf(mkt.curSym())
        if (legs.isEmpty()) { row.visibility = View.GONE; return }
        if (t131State != "ok") row.visibility = View.VISIBLE
        // t114 A:形状编码必须诚实。raceOk 里 hit=true 只在**本次真实取到**时置位
        // (MktData.kt:619-621 v = prep(fn().first()) 非空才置),所以:
        //   有命中腿 = 本次真实取到 -> 实心圆
        //   无命中腿 = 失败/冷却/跳过 -> 空心环（t393 起是 drawable，不再是字形）
        // **绝不给非本次取到的值发实心点**。诚实优先于美观。
        val hitLegs = legs.filter { it.hit }
        // #5 稿 L322-324: 8px 圆点, 常态实心 / 异常「透明底 + 2px 描边」。
        // 改前是 12sp 的字形 ●/○ —— 真机实测墨迹只有 16px（稿 8px 折合本机 22px）,
        // 而且环的描边粗细**根本拿不到**: 系统字体里 ● 与 ○ 是两套独立字形,
        // 不是同一个图形的实心/空心两态。t393。
        // ⚠ dot 的类型仍是 TextView, 这里只是换 background —— **没有换控件**。
        dot.setBackgroundResource(
            if (hitLegs.isNotEmpty()) R.drawable.srcdot_on else R.drawable.srcdot_off)
        // t112 聚合行:只留「主用源 · N 秒前」两段,**不加解释**
        // t114 B:**删掉 ?: legs.firstOrNull() 的 fallback**。没有腿命中就不许打任何源名 ——
        // 拿列表第一腿顶上去是**编造**(那正是「● 东财」的来源)。
        // t114 C:ago 来自 lastLegsAt(值实际产生的时刻),**不再硬编码「刚刚」**。
        // t116:聚合行**不许点名单一源**。一行聚合数据来自 N 个品种/N 个源/不同抓取时刻,
        // 根本没有"主用源"——点任何一腿名都是错的(上一轮显示「● 新浪」而屏幕上是股票,就是这个问题)。
        // 源名属于**单个品种**,属于「展开」里的逐品种明细,不属于聚合行。
        // t324 稿 L315-318 的三态:
        //   nosrc  → `—无源 · ${S.ty} 两腿皆不可用`
        //   failed → `${bad} 不可达 · 已切 ${win} · …`
        //   else   → `${win} · 42 根`
        val anyBad = legs.any { it.failed || it.skipped }
        if (hitLegs.isEmpty()) {
            // 稿的 ${S.ty} 是**该品种**的类型, 而 lastLegs 是跨品种聚合的, 拿不到「这一腿属于谁」
            // ⟹ 报「—无源」而不编一个品种类型出来(宁可少说, 不说错)。
            main.text = "—无源"
        } else {
            // 腿名必须过 vendorName(): LegInfo.name 存的是竞速 pool 的**内部键**(币是
            // BN/GT/OK/BB/KU/MX, 美股港股混着「百度」「腾讯」「TX」, A股是「新浪」「东财」),
            // 直接显示用户会看见 BN / OK / TX 这种看不懂的东西。稿 L111-118 的 legs 写的
            // 全是友好显示名, 稿 L334 原样渲染 {l} ⟹ 这里也必须是友好名。
            // 口径与行情页 srcMain(MktPanel 同一处)一致, 两边共用 vendorName 一张表。
            val win = MktData.vendorName(hitLegs.first().name)
            // ⚠️ **2026-10-01 t45：这里原本拼「 · N 根」，N 取自 MktData.lastLegsCount，现已去掉。**
            //   原因：**那个数已经不再是「画出来的根数」**。t44【F-2c③】把 lastLegsCount
            //   的语义改成了**取数档位 fetchN = 240**（它的本职是限速计数，不该动），
            //   于是这一行会显示「· 240 根」，而用户在行情屏实际看到的是 **20 根** ——
            //   **界面说着一件与事实不符的事**，正是今天铲了一整天的那一类。
            //
            //   **为什么不能就地换成正确的数：数据流缺一环，不是替换变量能解决的。**
            //   · `MktData.lastLegs` 是 List<LegInfo>，而 LegInfo 只有 name/hit/failed/skipped
            //     四个字段（MktData.kt:584-589），**不带根数**；
            //   · 自选屏自己那份距离结果 DistR 也不带根数（FavDist.kt:15-23 只有
            //     ok/pct/price/chg/why/soft/ts）；
            //   · 真正「画出来的根数」在 MktPanel.renderAgg 的**局部** info.count
            //     （MktPanel.kt:564-569，已 takeLast(curN()) 切过一道，报的就是用户看到的那几根），
            //     **从未发布给自选屏**。
            //   ⟹ 正确修法是让行情屏把「画出来的根数」发布出来，那是 F-2c③ 的文件，不在本条范围。
            //
            //   在那之前按本文件自己的老原则办：**宁可少说，不说错**（同上方 hitLegs.isEmpty() 分支）。
            //   一旦行情屏发布了这个数，把「 · N 根」加回来即可。
            main.text = win
        }
        // 稿 L325  failed||nosrc → var(--ink) + 600, 否则 var(--ink2) + 400
        val degraded = hitLegs.isEmpty() || anyBad
        main.setTextColor(act.attrColor(if (degraded) "colorInk" else "colorSub"))
        main.setTypeface(null, if (degraded) android.graphics.Typeface.BOLD
                             else android.graphics.Typeface.NORMAL)
        more.text = if (detail.visibility == View.VISIBLE) "收起" else "展开"
        // t324 #9 稿 L328-340: 逐腿明细是**每腿一个独立行**, 不是单 TextView 用 \n 拼。
        //   行: flex / alignItems baseline / gap 10 / padding '11px 16px 11px 32px' / borderTop 1px
        //   腿名 T.title(16) var(--ink);  状态 T.note(12)
        //     isBad → var(--ink) + 600 ;  isWin → var(--ac) ;  else → var(--ink3)
        //   文案 isBad?`不可达 · 冷却 ${cool}` : isWin?`本次命中` : `未参与`
        //
        // ⚠ **稿 L336 还有第四支 `thin → 1 根 · 不足`，App 没有，这里也没写。**
        //   旧注释把它列进了三态的链条里，读起来像「已实现」——
        //   于是 a4_04 与 audit-bc 各自独立读到这行，都判成「稿 L336 那条不用改」，
        //   **一份撒谎的注释让一个真缺口被跳过了两次**。现改成实话。
        //   为什么不补功能（三条理由，audit-bc 独立判断，队长认可）：
        //     ① MktData.LegInfo 只有 name/hit/failed/skipped 四个字段，
        //        `cooldownMs()` 是从 SRCBAD 时间戳**派生**的、不是腿自己的返回值；
        //        要判「这腿只回了 1 根」必须动 raceOk / fetchCryptoLeg 的**返回契约** ——
        //        那是取数链路，不是文案层。
        //     ② 稿 L330 的判据是 `l==='腾讯' && nosrc`，**按腿名硬编码的演示态**；
        //        App 的腿表里「腾讯」在美股/港股/韩股都出现，通用化会在其它市场说错话。
        //     ③ 稿的 nosrc 分支是「—无源」(L315)，thin 只出现在 nosrc 展开明细里；
        //        App 无源时整块报「—无源」，已覆盖主要诉求。
        //   ⟹ 记为已知取舍，**不要**为了让注释好看而去补一个没有数据支撑的状态。
        detail.removeAllViews()
        // 稿 L333 padding '11px 16px 11px 32px' ⟹ 上下 11 / 右 16 / 左 32
        // ⚠ dp 是**本类的成员函数**(L230), 不是 act 的 —— 写成 act.dp 编译不过(t325 实测)。
        val PAD_R = dp(15.3f).toInt()
        val PAD_I = dp(10.5f).toInt()
        val PAD_TL = dp(30.5f).toInt()
        for (l in legs) {
            val cool = try { l.cooldownMs() } catch (_: Throwable) { 0L }
            val isBad = l.failed || l.skipped
            val isWin = l.hit
            val cell = android.widget.LinearLayout(act).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.BOTTOM
                setPadding(PAD_TL, PAD_I, PAD_R, PAD_I)
                background = act.getDrawable(R.drawable.fav_leg_line)   // borderTop 1px
            }
            val nm = android.widget.TextView(act).apply {
                // 同上: LegInfo.name 是内部键, 必须过 vendorName() 才是友好显示名。
                // 口径与上面源状态行、与行情页逐腿行三处一致。
                text = MktData.vendorName(l.name)
                textSize = TS(R.dimen.fs_title)                          // 稿 L334 T.title
                setTextColor(act.attrColor("colorInk"))
                maxLines = 1
            }
            val st = android.widget.TextView(act).apply {
                text = when {
                    isBad -> "不可达 · 冷却 " + (cool / 60000).coerceAtLeast(1) + " 分钟"
                    isWin -> "本次命中"
                    else -> "未参与"
                }
                textSize = TS(R.dimen.fs_note)                           // 稿 L335 T.note
                setTextColor(act.attrColor(
                    if (isBad) "colorInk" else if (isWin) "colorPrimary" else "colorFaint"))
                if (isBad) setTypeface(null, android.graphics.Typeface.BOLD)
                maxLines = 1
                // 稿 L335: `marginLeft:'auto', textAlign:'right'` —— 状态贴最右。
                // Android **没有 margin-left 的 auto**（CSS 的是「独吞剩余」，
                // 而 layout_weight 是「平分」剩余，两者不能互换），
                // 等效做法是：状态格 0dp + weight=1 吃掉全部富余，再用 gravity=END 贴右缘。
                // 改前是 WRAP_CONTENT ⟹ 状态紧跟在腿名后面，真机右侧一大片空白
                // （状态在 x=250-382，x=400 之后零墨迹），A.2 复核 #9。
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dp(9.5f).toInt()                      // 稿 L333 gap 10
                }
                gravity = android.view.Gravity.END                    // 稿 L335 textAlign:'right'
            }
            cell.addView(nm)
            cell.addView(st)
            detail.addView(cell)
        }
    }

    private fun note(t: String): TextView = TextView(act).apply {
        text = t
        setTextColor(act.attrColor("colorSub"))
        textSize = TS(R.dimen.fs_note)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    // 行容器:左pick(品种+角标)右可选＋;go非空时点行走go(结果浮层先收起再直达)
    private fun rowView(s: String, tag: String, add: (() -> Unit)?, go: (() -> Unit)? = null, disp: String = ""): View {
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = dp(1.9f).toInt()
            setPadding(0, dp(9.5f).toInt(), 0, dp(9.5f).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val pick = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            isClickable = true
            isFocusable = true
        }
        val sym = TextView(act).apply {
            text = s
            setTextColor(act.attrColor("colorInk"))
            textSize = TS(R.dimen.fs_title)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val tg = TextView(act).apply {
            text = tag
            setTextColor(act.attrColor("colorSub"))
            textSize = TS(R.dimen.fs_note)
            val p = dp(7.6f).toInt()
            setPadding(p, dp(1.9f).toInt(), p, dp(1.9f).toInt())
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.leftMargin = dp(9.5f).toInt()
            layoutParams = lp
        }
        pick.addView(sym)
        pick.addView(tg)
        row.addView(pick)
        if (add != null) {
            val ad = Button(act).apply {
                text = "＋"
                /* ♿ 无障碍修复（**非测试专用**；可被自动化定位只是副产品）：
                   这是一个纯图标按钮（U+FF0B 全角加号），没有无障碍标签时
                   TalkBack 用户只会听到「未加标签的按钮」，无从知道它是「加进自选」。
                   取行代码而非字面量，同一列表里每个 + 读起来才知道加的是哪个品种。 */
                contentDescription = "添加到自选：" + s
                textSize = TS(R.dimen.fs_title)
                setTextColor(act.attrColor("colorPrimary"))
                setBackgroundResource(R.drawable.btn_ghost)
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.leftMargin = dp(9.5f).toInt()
                layoutParams = lp
            }
            ad.setOnClickListener { add() }
            row.addView(ad)
        }
        pick.setOnClickListener { (go ?: { onPick(s, disp) }).invoke() }
        return row
    }

    // ---------- 左滑删除层退役（2026-10-06，随 RecyclerView 化一起拆掉）----------
    // 用户 2026-10-01 先裁定「改成左滑」取代长按，又在 2026-10-05 裁定改回
    // 「长按 → 悬浮菜单 → 删除」。侧滑手势与「点行进行情」共用点击通道，
    // 在真机触摸噪声下互相抢事件（「只有名字和收益率能点进去」正源于此）。
    // RecyclerView 下删除入口只有一处：itemView 长按 → askDelete 菜单。
    // armSwipe/swipedSym/swipeW 四件套与行内的 del 层、wrap 容器一并删除。
    // askDelete 弹窗一并退役（2026-10-06 用户选定 C 版行内确认）：
    // 删除入口只有一处 —— itemView 长按展开确认条 → [删除]。

    /** 稿 L395: 零值渲染 "0.00%" **不加 + 号** —— "+0.00%" 是 App 自己的加法。 */
    private fun fmtPct(v: Double): String =
        (if (v > 0) "+" else "") + String.format(Locale.US, "%.2f", v) + "%"

    /** 稿 L374 zero = !none && Math.abs(dist)<1e-9 */
    private fun isZero(d: DistR?): Boolean = d != null && d.ok && Math.abs(d.pct) < 1e-9

    /** 稿 L375-376 stale/weak 同源。阈值沿用 paintDist 里既有的 60s(t116), 不擅自改。 */
    private fun isStale(d: DistR?): Boolean =
        d != null && d.ok && d.ts > 0L && System.currentTimeMillis() - d.ts > 60_000L

    /**
     * t295 稿 L377 到 L380 的 flag 四态, 抽成 flagOf(文字) + paintFlag(画字)。
     * **两处同源**: flagTv 画不画得出, 与行高走 11 还是 16(稿 L384)读的是同一个判据;
     * 合成两套判据就会出现「画出来了但行没变高」这种自相矛盾。
     *
     * ⚠ paintFlag 必须在 dc.tag 挂好之后调用 ——
     * 改前这段逻辑写在 paintDist 里, 而 paintDist 在 dc.tag = flagTv **之前**就被调用,
     * `dc.tag as? TextView` 恒为 null ⟹ 整段是死代码, t173 起 flag 一次都没画出来过。
     */
    // 稿 L377 逐字: none → 「—无源」, **没有**第二种 none 文案。
    // 改前是「有 why 就用 why」(FavDist 的 —超时 / —无支撑, 见 FavDist.kt:165/241/249),
    // 那是 App 自己更细的分类, 稿里没有; 本轮以稿为准。
    // why 并没有丢: 源状态行那一句仍按真实失败原因逐字报(见 paintSrcRow)。
    //
    // ⚠️ **pending 第四态「待取数」是稿子新增（t35 移植, 2026-09-30 用户裁定）**：
    //   原裁定：「新增行显示「— + 待取数」，**绝不标红**」。
    //   App 原来只有三态 ⟹ **一条正在取数的行会显示「—无源」**，
    //   而那不是「没源」是「**还没取到**」—— 语义完全相反，而且它被当成缺源标红，
    //   属于**误报**。所以必须把「还没取到」和「真的没有」分开。
    //   **放在 none 之前**：pending 时 d 必然是 null，若排在 none 之后就永远走不到。
    //   ⚠️ 顺序敏感：**stale 必须先于 zero**（稿 L378 在 L379 之前）。
    //     这会让 dist≈0 的行显示「旧 N 小时前」而不是「支撑上线」——
    //     **这是照 App/稿的代价、不是退化**：一行不可能同时既是「陈旧」又是「支撑线上」。
    private fun flagOf(d: DistR?, pending: Boolean = false): String = when {
        pending           -> "待取数"
        d == null || !d.ok -> "—无源"
        isStale(d)        -> "旧 " + t114Ago(d.ts)                      // 稿 L378
        isZero(d)         -> "支撑线上"                                // 稿 L379
        else              -> ""
    }

    private fun paintFlag(dc: TextView, d: DistR?, pending: Boolean = false) {
        val fv = dc.tag as? TextView ?: return
        val txt = flagOf(d, pending)
        fv.text = txt
        fv.visibility = if (txt.isEmpty()) View.GONE else View.VISIBLE
    }

    // 胶囊dc(照稿.i.dist):ok且pct>=0绿#0aa182=现价在第一支撑上方,
    // pct<0红#f23645=跌破(取价格上方最近支撑,负值);min-width104/pad9×12/r9/白字17粗;
    // 无数据'…'或失败why为纯文本15px ink无底色
    /** @param pending 该行是否**正在取数**（t35 移植的「待取数」第四态）。
     *  传下去只为让 flagOf/paintFlag 知道「这是还没取到，不是没有」，
     *  **不改变这里的任何取值/配色分支** —— 避免第四态顺带改掉既有渲染。 */
    private fun paintDist(dc: TextView, d: DistR?, pending: Boolean = false): TextView {
        if (d == null || !d.ok) {
            // 稿 v2.html:1119 无值文案「—无源」(该 flag 由 paintFlag 画在第二行)
            // + v2.html:1214-1216 以 --ink3 渲染。
            dc.text = "—"
            // ⚠ 稿 v2.html:1195 `color: blankD ? 'var(--ink3)' : distColor(r.dist)` ——
            //   **blankD 这一档必须显式给色**。改前这里**一次都没设过颜色**, dc 是刚 new 出来的
            //   TextView, 于是沿用主题默认 textColor(比 --ink3 深), 与同行的「—」(colorFaint) 不同深浅。
            dc.setTextColor(act.attrColor("colorFaint"))
            paintFlag(dc, d, pending)
            return dc
        }
        if (d != null && d.ok) {
            // 过期标记走**行内「旧 N 分/时前」**(t173 起;稿 stale 行同样标「旧 …」),
            // **不再把"·旧值"塞进气泡**——那会把 96px 的胶囊撑到换行(稿 :164 注释原话)。
            // 旧值整体弱化仍保留(稿 :167 .dist.stale:opacity .55),不让它冒充实时。
            // t116:凡不是**本轮**取到的值(FavDist 走 SP 落盘,见 FavDist.kt:12/:118)一律标记为「旧」。
        // 原来只有 >24h(STALE_MS)才弱化,于是 3 小时前的 SP 值会以**完全正常的面貌**出现——
        // 这就是 SK海力士 +31.22% 那个数的来历:它**绕过了 raceOk 的诚实 hit=true 置位**。
        // 阈值收紧到 60s:本次取到的值 ts 就是本轮,必然 < 60s。
        val old = d.ts > 0 && System.currentTimeMillis() - d.ts > 60_000L
            val base = fmtPct(d.pct)
            // t173 缺陷 B:上标小「旧」改成【行内】(稿 L371 / L378 stale 标「旧 6 小时前」)。
            // 只带「旧」不带年龄是另一个骗法:看不出旧了多久。
            // 稿 L397-399: 数字一行, flag 另起一行(flagTv)。
            // 改前是 base + " " + t114Ago(...), 挤在一个 maxLines=1 的 TextView 里 ⟹ 被截断。
            dc.text = base
            // 稿 L377 到 L380 flag 四态: none 走上面那个 early return;
            // stale 带「旧」前缀 —— 只写「6 小时前」看不出是"旧了"还是"刚更新";
            // zero 标「支撑线上」。
            paintFlag(dc, d, pending)   // 稿 L378 前缀 / L379 零值, 逻辑集中在 paintFlag
            // ⚠⚠ 稿**没有对 stale 行用 alpha**。全稿 `opacity` 只出现 3 处, 且没有一处落在自选行:
            //   v2.html:14 `.segsw:active{opacity:.6}`（按压态）
            //   · v2.html:50 假状态栏的信号格 SVG
            //   · v2.html:1485 搜索候选行「已在自选」时的那一行。
            // ⚠ 稿把 stale 的提示**只放在第二行的 flag 文字**里, 三格数字本身不降色:
            //   v2.html:1117-1122 `stampTxt = ... Date.now()-valAt>STALE_MS ? '旧 '+agoTxt(valAt) : ...`
            //   在 v2.html:1214-1216 以 --ink3 渲染;
            //   而 v2.html:1194 / 1198 / 1202 三格的 color 判据里**压根没有 stale 这一项**。
            //   ⟹ App 的 old→colorFaint 是 t116 的【有意偏离】(旧值不许冒充本轮), 不是照稿,
            //   改动前那句「弱化只通过各格的 color 走 ink3」引的是**已被推翻的旧稿**, 已删。
            // 墓碑: 旧稿那条 `.dist.stale{opacity:.55}` 在**现行 v2 里已整体不存在**
            //   (grep `opacity` 只剩上面 3 处, grep `trough` 零命中) —— 不要把它加回来。
            // 改前这里是 `alpha = 0.55f`, 结果距支撑变 #A9ACB2 而同行另外三格是 #686C74 ——
            //   同行深浅不一（t389）。
            dc.alpha = 1f
            // t110 ② 距支撑改成**纯数字 21sp**,不再塞进绿色药丸。
            // 涨跌语义由**颜色**承担(涨绿跌红),不是由底色块承担。
            // t117:「旧」角标**只留一处** —— 上面的 insert(base.length,"旧")(t102 稿做法)。
            // t116 我又追加了一段文本,两处都加 → 一行印两次「旧」。已删掉追加,只走上标。
            // 旧值字色仍弱化,不拿涨跌色假装是本次的涨跌。
            // ⚠ 2026-10-07 用户裁定「不喜欢获取到实时信息才变色」：新旧值同色，时效只看第二行flag文字。
            //   旧值弱化整段删除（t116偏离的colorFaint），新值旧值都走同一套符号配色；无值仍走colorFaint。
            {
                // ⚠ 配色 = 稿 v2.html:964 逐字:
                //     const distColor = d => d>0 ? 'var(--ac)' : d<0 ? 'var(--ink3)' : 'var(--ink)';
                //   稿 v2.html:961-963 的口径原话:「距支撑不是涨跌，是离支撑还有多远」
                //   「距支撑 正 = 主色紫 / 负 = 次级灰白」
                //   ⟹ 正 = 紫, 负 = 灰白, 零 = 墨; **负值不染跌红**（红只属于涨跌, 见 v2.html:2551）。
                //   token 映射见 res/values/attrs.xml 对照表:
                //     --ac→colorPrimary · --ink→colorInk · --ink3→colorFaint。
                //   改前是 `pct>=0 ? colorInk : colorRed`: 正值少了紫、**负值染了跌红**,
                //   那条来自已被推翻的旧稿（正墨负红），本轮按 v2.html:964 补齐。
                dc.setTextColor(
                    when {
                        d.pct > 0 -> act.attrColor("colorPrimary")
                        d.pct < 0 -> act.attrColor("colorFaint")
                        else -> act.attrColor("colorInk")
                    })
            }
            dc.textSize = TS(R.dimen.fs_data)
            dc.setTypeface(dc.typeface, Typeface.BOLD)
            // ⚠ 删掉 minWidth=96dp 这个写死值: 它是"数字+时效挤一行"时代的遗产,
            //   那个数还会和 dimens.xml 的 col_dist=112dp 打架(审计点过)。
            //   列宽现在只由 cellLp 的 weight 档决定(稿 L348 的 1.2fr), 单一真相。
            dc.maxLines = 1
            dc.setPadding(0, dp(8.6f).toInt(), 0, dp(8.6f).toInt())
            dc.background = null      // t110:去掉圆角药丸底色
        } else {
            // t260 稿 L397-399 / t170 基线: 无值时【值那一格留空】, why 走下面那条 flag 行。
            // 原来这里 dc.text = d.why + fs_data(21sp) + BOLD, 把值格整个占了,
            // 真机看着像个巨大的标题 —— 而稿要的是"数字一行、flag 另起一行"。
            // 三态(—无源/—无支撑/—超时)的值本身没动, 只是换了它该在的位置。
            dc.text = ""
            dc.setTextColor(act.attrColor("colorFaint"))
            dc.textSize = TS(R.dimen.fs_title)
            dc.alpha = 1f
            dc.setTypeface(dc.typeface, Typeface.NORMAL)
            dc.background = null
            dc.minWidth = 0
            dc.maxLines = 1
            dc.setPadding(0, 0, 0, 0)
            // 走到这里说明没有值, 所以 flagTv 那条第二行归 why 用(与上面的时效互斥)
            paintFlag(dc, d, pending)   // 稿 L372 无源态; why 的具体文案已在 paintFlag 里优先
        }
        return dc
    }
    /**
     * 稿 v2.html:1210 第二行的**徽章**逐字是 `{r.badge || r.ty}` —— 恒为**市场类型**。
     *
     * ⚠ 2026-10-02 改：原实现在 `t=="kr"` 时返回中文名，那是**标题还没改成中文名之前**
     *   的补偿（那时标题=代码，KR 的中文名只能塞进徽章位）。现在标题恒为中文名，
     *   韩股中文名已在标题行，徽章再放一遍就是重复 ⟹ 恒返回市场类型，照稿。
     */
    private fun favSubLine(t: String, cn: String): String = FavStore.tag(t)

    /* t8 照稿 A1④ 列宽 —— 稿 v2.html:952 逐字:
     *     const GRIDR='88px 88px 76px 56px';   // 品种 | 距支撑(主角列) | 收益率 | 收益比
     *   稿 v2.html:953-956 原话:「四列按内容实测宽度定死，余量交给
     *   justify-content:space-between 均分到三条列间隙（用 minmax(0,1fr) 会让品种列
     *   吞掉全部余量，中间空出一个大洞）」、「合计 308，框内 380，余 72px → 间隙各 24px」。
     *
     *   ⟹ 忠实移植 = **四列定死 + 三条间隙均分余量**。
     *     LinearLayout 没有 space-between，用**三个 0dp / weight=1 的间隔 View**等价：
     *     固定宽先占位，余量只会被分给这三个 spacer，各得 余量/3 —— 正是 space-between。
     *   本机实测: (392.73 − 32 − 308) / 3 = **17.58dp/条**（t1 实测旧值 10dp，偏小 43%）。
     *
     *   墓碑 · t336 的 `92px 1.2fr 0.88fr 0.88fr` 权重方案本轮**整体下线**：
     *     它引的是稿的**旧一行**（旧注释写作「稿 L348」），现行稿是 v2.html:952 的定死四列。
     *   权重列在这里也**用不了**：四列定死 ⟹ 余量必须全部进间隙，
     *     权重列会把余量吃掉 —— 那正是稿注释里警告的「让某一列吞掉全部余量，中间空出大洞」。
     *
     *   ⚠ col_gap(10dp) 在本结构里**不再参与**。稿的间隙是 space-between 算出来的，不是定值。
     *     见交付报告：验收文字「列间隙落到 10dp 档」与稿的 space-between 冲突，本条**按稿**。
     *
     * ⚠⚠ **必须是【类成员】，不能放回 favRowView 里**（2026-10-02 构建 e:978 实锤）：
     *   上一版我把它们声明成 favRowView 内部的局部 val/局部 fun，而 `val fcol` 的
     *   `layoutParams = fixedColLp(COL_NAME)` 在**声明之前**就用了它们。
     *   **Kotlin 的局部声明不提升**（类成员才可以前向引用）⟹ 报 Unresolved reference。
     *   提到类成员后一次解决五处调用，且列宽常量只有**一份** —— 不选「在 fcol 那儿再抄一份」，
     *   那是两份真相，正是 t336 当年权重方案散架的同一个坑。
     */
    private val COL_NAME = 88f    // 品种
    private val COL_DIST = 88f    // 距支撑（主角列）
    private val COL_GAIN = 76f    // 收益率
    private val COL_RATIO = 56f   // 收益比

    private fun fixedColLp(w: Float) =
        LinearLayout.LayoutParams(dp(w).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)

    /** space-between 的一条「间隙」：0 宽 + weight 1，三条平分余量。 */
    private fun gapView() = View(act).apply {
        layoutParams = LinearLayout.LayoutParams(0, 1).apply { weight = 1f }
    }

    // 自选行雪球式版式(照稿):行>pick>[名称列fcol(名称19px/700+fbadge徽标+代码),
    // 距离列rq>dc胶囊];点行直达行情,**长按删**(2026-10-01 用户裁定长按菜单,取代左滑600ms;
    // 2026-10-06 侧滑删除层整体退役,见下)。
    // ⚠ 2026-10-06 RecyclerView 化:建壳一次、绑定多次,点/长按只挂 itemView。
    //   手拼 LinearLayout 时代,行内 TextView 的样式(autosize/tooltip/ellipsize)会悄悄
    //   把触摸吃掉,「只有名字和收益率能点进行情」就是这么来的;加上 armSwipe 的手势
    //   状态机共用点击通道,修一处漏一处。RecyclerView 下一行就是一个 ViewHolder,
    //   子控件全部不可点击不可获焦,触摸只到 itemView —— 这一类问题从结构上消失。
    //   取数线(FavDist/熔断/缓存/refreshFavDist/排序/空态/计数行)一律不动,只换显示层。
    private class RowShell(
        val root: LinearLayout,
        val fname: TextView,
        val bd: TextView,
        val codeTv: TextView,
        val fsub: LinearLayout,
        val dc: TextView,
        val gc: TextView,
        val rc: TextView,
        // C 版行内确认条(2026-10-06 用户选定):平时 GONE,长按才展开。
        val strip: LinearLayout,
        val stripTxt: TextView,
        val stripCancel: TextView,
        val stripDel: TextView
    ) {
        var boundSym: String? = null
    }

    /** 建行壳:只搭结构、不填数据(数据走 bindRowShell)。子控件全部剥掉点击与焦点。 */
    private fun createRowShell(): RowShell {
        // 稿 L384 行容器: padding: flag ? '11px 16px' : '16px' + borderBottom '1px solid var(line)'。
        // 首帧默认按无 flag 的 16dp 建,bind 时按实际 flag 重算(见 bindRowShell)。
        val ph = dp(15.3f).toInt()
        // 行内容(横向四列)。外层再包一个纵向 root,下面挂行内确认条。
        val content = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // ⚠ 2026-10-06 内边距只能有一处：行高与横向内缩**全部**由外层 root 的
            //   setPadding(ph, pv) 承担（见 bindRowShell），这里必须全部归零。
            //   换容器时这里留了横向 ph，加上 bind 里 root 的 ph ⟹ 横向也 double 了，
            //   列全被往中间挤 —— 这就是"原来排版好好的，换完组件全挤在一起"。
            //   纵向归零是上一轮修的，横向是这一轮补的，两次才算完。
            // ⚠ 2026-10-06 线也只能有一处：borderBottom 搬到外层 root 上（见下），
            //   这里不再画线。线画在没有内边距的内层底边 ⟹ 字下面几乎贴线、
            //   线下面却是下一行的整段 pv —— 用户原话「上方的留白根本就没有」。
            //   线跟着有内边距的外层走，上下才是同一把尺子。
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
            // ⚠ 2026-10-06 这里必须 NOT clickable：点击/长按的唯一主人是外层 root。
            //   content 若 clickable=true 会把触摸就地吃掉（它自己又没挂监听），
            //   事件到不了 root ⟹ 点行没反应。这正是之前「只有某几格能点」同一病根，
            //   换容器时把旧 row 的 clickable 原样搬了过来，亲手复活了它。
            isClickable = false
            isFocusable = false
        }
        val pick = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            isClickable = false
            isFocusable = false
        }
        val fcol = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            // t8 照稿 A1④:首列定死 88(v2.html:952)。
            layoutParams = fixedColLp(COL_NAME)
            isClickable = false
            isFocusable = false
        }
        val fname = TextView(act).apply {
            setTypeface(typeface, Typeface.BOLD)
            // 稿 index.html:165-179:品种名/代码/徽标一律 nowrap + 超长省略号。
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            isClickable = false
            isFocusable = false
        }
        fcol.addView(fname)
        // 第二行容器(照稿 v2.html:1208): 徽标 + 代码(+右端时间戳) 同一行,充满品种列。
        val fsub = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
            p.topMargin = dp(3.8f).toInt()          // 稿 v2.html:1208 marginTop:4
            // ⚠ 2026-10-06 撤掉上一轮加在这里的 bottomMargin 4dp。
            //   那一刀是为了"徽标不贴线"临时垫的，但只垫了下面 ⟹ 上下不对称，
            //   用户实机报"分隔线上下留白不对称"。行底呼吸本来就由整行 pv 承担，
            //   这里再垫就是第二套说法。回到稿原样：只有 topMargin。
            layoutParams = p
            isClickable = false
            isFocusable = false
        }
        // ⚠ t8 照稿 A1②:第二行的品类徽章 —— **带 1px 中性描边的药丸**(稿 v2.html:1209-1210),
        //   12px / --ink2 / radius 4 / padding 1·4,无彩度。
        val bd = TextView(act).apply {
            setTextColor(act.attrColor("colorSub"))      // 稿 --ink2
            textSize = TS(R.dimen.fs_note)
            maxLines = 1
            setPadding(dp(3.8f).toInt(), dp(1f).toInt(), dp(3.8f).toInt(), dp(1f).toInt())
            background = act.getDrawable(R.drawable.fav_badge)
            isClickable = false
            isFocusable = false
        }
        fsub.addView(bd)
        val codeTv = TextView(act).apply {
            // ⚠ 2026-10-05 **A 股显示去前缀的代码**(`688825` 而非 `sh688825`,
            //   `sh`/`sz` 是取数用的市场前缀,不该出现在屏上;港股同理显示 `00700`)。
            setTextColor(act.attrColor("colorSub"))       // 稿 --ink2
            textSize = TS(R.dimen.fs_note)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                // ⚠ 2026-10-06 gap 由稿的 6 加到 8,有意偏离。
                //   用户真机截图：徽标药丸的右边框压到了代码首字母(MSTR 的 M)。
                //   根因是单位错配——徽标内边距与这个间距是 dp(**不跟**系统字号)，
                //   而徽标字与代码字是 sp(**跟随**系统字号)：模拟器(1.0 倍字号)量着正好，
                //   真机字号调大后两边向中间挤，5.7dp 就不够了。8dp 在 1.0 倍下只多一点气口，
                //   在大字号下保住不重叠。另加 ellipsize：超长代码截断，不许顶出列宽。
                marginStart = dp(8f).toInt()
            }
            isClickable = false
            isFocusable = false
        }
        fsub.addView(codeTv)
        fcol.addView(fsub)
        // t171 悬案E:时效画在【距支撑这一列下面】,dc 包一层纵向容器。
        val dcol = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            // ⚠ t354 稿 v2.html:1193 右对齐(原居中是 App 自己的发挥,与稿相反)。
            gravity = Gravity.END or Gravity.CENTER_HORIZONTAL
            isClickable = false
            isFocusable = false
        }
        val dc = TextView(act).apply {
            isClickable = false
            isFocusable = false
        }
        // 稿 L397-399: 时效 flag 另起一行(12sp, marginTop 4)。挂在 dc.tag 上,
        // bind 走 paintDist 只拿得到 dc,不引入第二个 map,免得两份真相。
        val flagTv = TextView(act).apply {
            textSize = TS(R.dimen.fs_note)
            maxLines = 1
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(3.8f).toInt()
            }
            isClickable = false
            isFocusable = false
        }
        dc.tag = flagTv
        dcol.addView(dc)
        dcol.addView(flagTv)
        dcol.layoutParams = fixedColLp(COL_DIST)
        val gc = TextView(act).apply {
            textSize = TS(R.dimen.fs_title)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = fixedColLp(COL_GAIN)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            isClickable = false
            isFocusable = false
        }
        val rc = TextView(act).apply {
            textSize = TS(R.dimen.fs_title)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            layoutParams = fixedColLp(COL_RATIO)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            isClickable = false
            isFocusable = false
        }
        // t8 照稿 A1④:四列定死 + 三条 space-between 间隙,**顺序必须与稿 GRIDR 一致**。
        pick.addView(fcol)
        pick.addView(gapView()); pick.addView(dcol)
        pick.addView(gapView()); pick.addView(gc)
        pick.addView(gapView()); pick.addView(rc)
        content.addView(pick)
        // C 版行内确认条:平时 GONE,长按才展开。
        // 「删除 {代码} {中文名}? [取消] [删除]」,眼睛不离开这一行。
        val stripTxt = TextView(act).apply {
            textSize = TS(R.dimen.fs_title)
            setTextColor(act.attrColor("colorInk"))
            layoutParams = LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            isClickable = false
            isFocusable = false
        }
        val stripCancel = TextView(act).apply {
            text = "取消"
            textSize = TS(R.dimen.fs_title)
            setTextColor(act.attrColor("colorSub"))
            setPadding(dp(12f).toInt(), dp(8f).toInt(), dp(4f).toInt(), dp(8f).toInt())
        }
        val stripDel = TextView(act).apply {
            text = "删除"
            textSize = TS(R.dimen.fs_title)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(act.attrColor("colorRed"))
            gravity = Gravity.CENTER
            setPadding(dp(16f).toInt(), dp(7f).toInt(), dp(16f).toInt(), dp(7f).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(6f)
                setStroke(dp(1f).toInt(), act.attrColor("colorRed"))
                setColor(Color.TRANSPARENT)
            }
        }
        val strip = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ph, dp(10f).toInt(), ph, dp(10f).toInt())
            // ⚠ 2026-10-06 底线只画在 root 上（见下），这里不画 ——
            //   否则确认条展开时一行变两条线。
            //   默认隐藏（bind 里按 showConfirm 重算，这里只是初始值）。
            visibility = View.GONE
            // 条体本身吞掉点击(别冒泡到整行跳行情),两个按钮各自接管。
            isClickable = true
            setOnClickListener { }
            addView(stripTxt)
            addView(stripCancel)
            addView(stripDel)
        }
        // itemView 根:纵向 = 行内容 + 确认条。
        // ⚠ 2026-10-06 borderBottom 通栏线挂在这里（稿 L384:每一行下面都有一条）。
        //   线必须跟着**有内边距**的容器走 —— 之前挂在无内边距的 content 底边，
        //   字下面几乎贴线、线下面却是下一行的整段 pv，上下两把尺子。
        //   现在线的上下都是同一套 pv，对称。
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = act.getDrawable(R.drawable.fav_row_line)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
            isClickable = true
            isFocusable = true
            addView(content)
            addView(strip)
        }
        // 兜底:内容区所有后代都不接管点击与焦点(上面逐个设过,这里再扫一遍,
        // 以后加新格子也不会漏)。确认条的两个按钮除外 —— 它们就是要点的。
        stripClickable(content)
        return RowShell(root, fname, bd, codeTv, fsub, dc, gc, rc,
            strip, stripTxt, stripCancel, stripDel)
    }

    /** 递归剥掉一棵子树里所有 View 的点击/长按/焦点接管(不含根自己)。
     *  ⚠ 2026-10-13 补 isLongClickable=false：tooltipText 会自动把格子置为长可点，
     *    长可点即使 clickable=false 也吞整次触摸（判长按）⟹ 收益率/收益比格子点不动。
     *    根自己的长按删除不受影响（stripClickable 从来不动根）。代价：tooltip 悬浮说明失效。 */
    private fun stripClickable(root: View) {
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val c = root.getChildAt(i)
                c.isClickable = false
                c.isLongClickable = false
                c.isFocusable = false
                c.isFocusableInTouchMode = false
                stripClickable(c)
            }
        }
    }

    /** 绑数据:原来 favRowView 里所有与 f/d/m 有关的赋值,原样搬到这里。 */
    private fun bindRowShell(sh: RowShell, r: FavRow, pending: Boolean, showConfirm: Boolean) {
        sh.boundSym = r.it.s
        // 行高:flag 判据与 flagTv 同源 ⟹ 「flag 画出来了」与「行高跟着变了」不可能脱节。
        val flagged = flagOf(r.d, pending).isNotEmpty()
        val pv = dp(if (flagged) 11f else 16f).toInt()
        val ph = dp(15.3f).toInt()
        sh.root.setPadding(ph, pv, ph, pv)
        // 标题:有中文名用中文名,没有(币类 shortSym 即代码)才回落代码本身。
        val cnName = MktSuggest.favName(r.it.s)
        sh.fname.text = if (cnName.isNotEmpty() && cnName != r.it.s) cnName else r.it.s
        // ⚠ 2026-10-07 同上：标题恒墨，不再按stale置灰（时效只看flag行）。
        sh.fname.setTextColor(act.attrColor("colorInk"))
        // ⚠ 2026-10-13 用户裁定「字太多就缩小字号」——名字过长**缩字号**，不截断、不缩名。
        //   分档同「距支撑」列先例（确定性算术；autosize 在本真机闪退、已永久弃用）：
        //   ≤5 字照常；6~7 字 ×0.85；≥8 字 ×0.75（太空探索技术公司 8 字完整显示）。
        sh.fname.textSize = TS(R.dimen.fs_title).let { base ->
            when ((sh.fname.text?.length ?: 0)) {
                in 0..5 -> base
                6, 7 -> base * 0.85f
                else -> base * 0.60f   // 0.75 实测仍截（太空探索技术公司 8 字）；0.6×=9.6sp 装得下
            }
        }
        sh.fname.setTypeface(sh.fname.typeface, Typeface.BOLD)
        // 第二行:徽标 + 代码。⚠ 2026-10-13 用户裁定「必须有，不允许省」——
        //   原「标题已是代码就不重复显示」规则**废除**（BTC/ARM/IBM 这类标题即代码的行
        //   也恒显徽标+代码；代价是代码在标题与徽标行各出现一次，用户明确接受）。
        val cn = MktSuggest.favName(r.it.s)
        sh.bd.text = favSubLine(r.it.t, cn)
        sh.codeTv.visibility = View.VISIBLE
        sh.codeTv.text = FavStore.displaySym(r.it.s)
        // 距支撑:paintDist 写值(含 flag),再按长度分档降字号防 '%' 被列宽裁掉。
        // ⚠ 2026-10-05 真机裁字教训:autosize 在这台真机上闪退,已永久弃用;
        //   改用确定性写法,纯算术不可能崩。
        // ⚠ 2026-10-06 教训2:第一版只按 7 字符算 86%,NFLX 的 `+1138.58%`(9 字符)
        //   照样被裁 —— 分档必须覆盖最长情形。列宽约 219px(见 create 处注释)：
        //   7 字≈224px→86%≈193px ✔；9 字≈288px→需≤219px即≤76%，取 72% 留余量；
        //   ≥10 字（如 `-12345.67%`）取 60%，再长就不是正常数值了。
        paintDist(sh.dc, r.d, pending)
        paintFlag(sh.dc, r.d, pending)
        // ⚠ 2026-10-07 用户裁定「统一把字都缩小，不要再剪掉单位」：整体先×0.88，再按长度分档，
        //   9字以上保证%不被列宽裁掉；宁可全小，不许单个%被吃掉。
        sh.dc.textSize = TS(R.dimen.fs_data).let { base ->
            val small = base * 0.88f
            when ((sh.dc.text?.length ?: 0)) {
                in 0..6 -> small
                7, 8 -> small * 0.86f
                9 -> small * 0.72f
                else -> small * 0.60f
            }
        }
        sh.dc.letterSpacing = -0.02f
        sh.dc.gravity = Gravity.CENTER
        sh.dc.maxLines = 1
        sh.dc.setPadding(0, dp(8.6f).toInt(), 0, dp(8.6f).toInt())
        sh.dc.background = null
        // ⚠ 2026-10-07 用户裁定「我需要紫色」—— 距支撑语义色落到**最后一次写入**。
        //   原先语义色只在 paintDist 里设一次，而 paintDist 位于本函数**开头**；
        //   实测那一次不生效（埋点：want=#FF6749B6 → after=#B3FFFFFF，被主题默认压过）。
        //   这里在对 sh.dc 的全部操作之后重新落色，保证它是最终写者。
        //   口径照稿 v2.html:964：正=紫 colorPrimary / 负=灰白 colorFaint / 零=墨 colorInk。
        r.d?.takeIf { it.ok }?.let { dd ->
            sh.dc.setTextColor(
                when {
                    dd.pct > 0 -> act.attrColor("colorPrimary")
                    dd.pct < 0 -> act.attrColor("colorFaint")
                    else -> act.attrColor("colorInk")
                })
        }
        // 收益率:⚠ 2026-10-03 用户裁定恒定绿色,不再按正负分色(距支撑列仍按正负分色)。
        // 无值/过期仍弱化 —— 「没有数据」与「有数据但恒绿」是两回事。
        val roi = r.m.gain
        sh.gc.text = roi?.let { fmtPct(it) } ?: "—"
        // ⚠ 2026-10-07 同上：收益率恒绿（有值即绿），不再按stale置灰；无值才灰。
        sh.gc.setTextColor(
            when {
                roi == null -> act.attrColor("colorFaint")
                else -> act.attrColor("colorTeal")
            })
        // ⚠⚠ 2026-10-13 **悬浮说明与整行点击二选一，点击赢**（用户裁定「点一行任何地方都该跳」）。
        //   收益率格子的 tooltip 是吞点击的触发源：三版剥离（空剥/剥品种列/剥整块 content）实测
        //   全部无效（子代理两轮回归：有数据两列均 ✗、对照点名 ✓、坐标复核两次）——tooltip 的
        //   接管不在 clickable/longClickable 这两个可剥的旗子上。空态（无 tooltip）能点是铁证。
        //   ⟹ 不再设 tooltip（信息在计算页同源可见）；同时显式清 rc 的（paintRatioCell 若设过）。
        //   保留下方 stripClickable 作皮带（不再承担本修复的成败）。
        sh.gc.tooltipText = null
        // ⚠ 2026-10-07 与距支撑同幅缩小（统一小一号，避免% / ×被裁）。
        sh.gc.textSize = TS(R.dimen.fs_title) * 0.88f
        sh.rc.textSize = TS(R.dimen.fs_title) * 0.88f
        // 收益比。
        paintRatioCell(sh.rc, r.d, r.m)
        sh.rc.tooltipText = null // 2026-10-13 同上：清 tooltip，别让它吞整行点击
        // ⚠ 2026-10-13 用户实报「点字进不去，只有点空白处才能进行情页」。
        //   根因：收益比/收益率格子设了 tooltipText（见上），Android 会自动把该 View 置为
        //   **longClickable** ⟹ 它接管整次触摸去判长按 ⟹ 行根的点击监听收不到。建行时那次
        //   stripClickable 是**一次性**的、且发生在设 tooltip **之前**，管不住后来打开的接管。
        //   修法：**绑定时**再剥一次内容区所有后代（确认条两个按钮在 strip 里、不在 content 下，不受影响）。
        //   定位：⚠⚠ 2026-10-13 二次翻车复盘——`sh.fname.parent` 只是「品种」列的容器，
        //   距支撑/收益率/收益比在 content 里的**其它列容器**，不是它的子孙 ⟹ 从没被剥到：
        //   空态无 tooltip 不长可点（能点），有数据 gainTip 设 tooltip → 长可点复活 → 吞 tap
        //   （子代理实测两列均 ✗、对照点名 ✓）。改为 root 的第一个子孩子 == content 整块剥。
        (sh.root.getChildAt(0) as? android.view.View)?.let { stripClickable(it) }
        // C 版行内确认条:展开时显示「删除 {代码} {中文名}? [取消] [删除]」。
        sh.strip.visibility = if (showConfirm) View.VISIBLE else View.GONE
        if (showConfirm) {
            val cn2 = MktSuggest.favName(r.it.s)
            val disp2 = if (cn2.isNotEmpty() && cn2 != r.it.s) cn2 else r.it.s
            sh.stripTxt.text = "删除 ${FavStore.displaySym(r.it.s)} $disp2？"
            sh.stripCancel.setOnClickListener { setConfirm(null) }
            sh.stripDel.setOnClickListener {
                setConfirm(null)
                FavStore.remove(act, r.it.s)
                repaint()
            }
        }
    }


    fun repaint() {
        val a = FavStore.get(act)
        // ⚠ 副标**故意不接线**（t8 2026-10-02 captain 裁定）：稿 v2.html:1600-1607 的墓碑说这条
        //   副标已被用户删掉、「要不要补等用户拍板」，与本次「完全照稿子改」指向相反 ⟹ 只有用户能定。
        //   paintFavSubtitle() 的实现已备好（见上），**等用户拍板后**：
        //     ① page_fav.xml 的 fav_subtitle 把 visibility 由 gone 改回默认；
        //     ② 在这一行补 `paintFavSubtitle(a.size)`。
        //   现在不接线 ⟹ 屏上不出现这条副标，布局与改前逐像素相同（该 View 是 gone，不占高）。
        if (a.isEmpty()) {
            // t217:空列表时【照样走 t131PaintState】, 让专用空态视图 fav_empty 显示出来。
            // 改前这里直接 return, 把 t131PaintState 一起跳过 —— 而 fav_empty 只在
            // t131PaintState 的 show(favEmptyV, st=="empty") 里被打开, 于是它永远 GONE。
            // 删掉原句的三个理由:
            //   ① 它不是稿的文案(稿 L429-430);
            //   ② page_fav.xml 的 fav_empty 已经是稿的原文, 两句并存会重复;
            //   ③ 它提到「☆ 收藏」, 而稿里 ★☆收藏/toggleFav 零命中, App 也没这个按钮。
            t131PaintState(loading = false)
            // t8 照稿 A1③：空态下这一行**照常显示**（稿 v2.html:1642 是常驻渲染，不在 st!=='ok' 里），
            // 读作「距支撑 实时 0 / 无源 0」—— 用户问「为什么都没更新」，0/0 就是答案。
            paintCountLine(live = 0, noneN = 0, wait = 0, total = 0)
            return
        }
        // ⚠ 2026-10-12 用户裁定 B 改：这里原有 `val (tf, n) = mkt.distWin()` —— **一个全局窗口**
        //   在循环外取一次、遍历全部行。它就是两件事的病灶（详见 MktPanel 文件头 2026-10-12 墓碑）：
        //     ① 改任一品种的窗口 → 全局 tf/n 变 → **全部**行的 FavDist key(含 n) 变 →
        //        缓存/复用层全 miss → 全量重取；
        //     ② 每行显示的窗口与该品种自己存的 gridcalc_win_v1[s] 不一致（设备实测 TSLA=M|31、
        //        IBM=M|32 各自的记录，屏上却全按全局那一个算）。
        //   ⟹ 改成**逐行**取（见下方 rows 循环内的 mkt.distWin(f.s)），一个行一个口径，互不影响。
        //     本函数只做**展示**（FavDist.shown = 缓存/落盘读取），不发任何请求。

        // ⚠ 2026-10-05 **补中文名**。自选行的排版规则是「中文名 · 徽章 · 代码」，
        //   本地表（MktSuggest）里没有 A 股 ⟹ `favName` 返回空，标题回落成原始代码
        //   `sh688825`，第二行又因为「标题已是代码」而不再显示代码 ——
        //   屏上那一行就成了「原始代码 + A股 + 空」，与其他品种的规则对不上。
        //   这里把「查不到名字」的符号整批交给 NameBook（腾讯行情，一次一批、落盘缓存），
        //   到了再 repaint 一次。**先出旧样子、名字到了再刷新**，不阻塞首帧。
        MktData.NameBook.ensure(act, a.map { it.s }) { repaint() }
        data class Row(val it: FavItem, val d: DistR?, val m: RowMeta, val ratio: Double?)
        // 冷启动/断网首帧:display()兜底沿用持久化旧值,不先画无源;
        // 记录侧取该品种最新一条(latestRecOf,追加语义下读取取最新)
        val rows = a.map { f ->
        // t128:存一份给汇总条(与列表同源)
        // ⚠ 2026-10-09 改走 FavDist.shown()：原先与 onShow 判「缺不缺值」的 FavDist.hasValue()
        //   **同一份判据**（cached 的 ok/soft → 落盘 lastOk 旧值），结构上保证
        //   「屏上显示什么」与「判它缺不缺值」不会再分叉（缺陷 A 的根因就是这两处分叉）。
        //   ⚠ 2026-10-10：onShow 那条判据（连同 hasValue 的唯一调用点）已随"切页不刷"删掉，
        //     这里继续走 shown() 的理由不变——它就是"屏上显示什么"的定义，与取数策略无关。
            // ⚠ 2026-10-12 裁定 B 改：**这一行用它自己的窗口** —— gridcalc_win_v1[s]（唯一真源），
            //   无记录回落 ("M" to 20)。原来这里是循环外一个全局 mkt.distWin()，全部行共用。
            val (tf, n) = mkt.distWin(f.s)
            val d = FavDist.shown(f.s, tf, n)
            val rec = act.latestRecOf(f.s)
            val roe0 = rec?.optDouble("roe", Double.NaN) ?: Double.NaN
            val net0 = rec?.optDouble("net", Double.NaN) ?: Double.NaN
            val m = RowMeta(
                if (roe0.isFinite()) roe0 else null,
                if (net0.isFinite()) net0 else null,
                rec?.optString("t") ?: "")
            val distPct = if (d != null && d.ok) d.pct else null
            Row(f, d, m, ratioOf(distPct, m))
        }
        // 稿§2 排序:三键互斥取当前列;无值行恒沉底(升序降序都沉底)
        val sort = FavStore.getSort(act)
        val col = sort.substringBefore(':')
        val dir = sort.substringAfter(':')
        // dir=="off" 这条分支现在【恒不成立】。结论没变, 但**当初给的理由已经不是全部成因**:
        // 真正让它恒不成立的是 getSort 的三个出口(p[1] 白名单 / 判定失败 return /
        // 裸值 when 的 else)一起收敛到 dist:desc, 白名单里也没有 off 了 ——
        // 不只是「旧值被迁走」那一件事。
        // 留着它是**语义兜底**(坏值不至于让整段排序被跳过), 不是「保险」;
        // 真要删得连排序一起动, 那是另一件事, 本轮不做。
        val sorted = if (dir == "off") rows else {
            val has = { r: Row ->
                when (col) {
                    "gain" -> r.m.gain != null
                    "ratio" -> r.ratio != null
                    else -> r.d != null && r.d.ok
                }
            }
            val key = { r: Row ->
                when (col) {
                    "gain" -> r.m.gain ?: 0.0
                    "ratio" -> r.ratio ?: 0.0
                    else -> Math.abs(r.d?.pct ?: 0.0)
                }
            }
            val with = rows.filter { has(it) }
            val without = rows.filter { !has(it) }
            val sw = if (dir == "asc") with.sortedBy { key(it) }
            else with.sortedByDescending { key(it) }
            sw + without
        }
        // t8 照稿 A1③：屏底计数行。三个数互斥（判据见 paintCountLine 的注释）。
        val liveCnt = rows.count { it.d != null && it.d.ok && !isStale(it.d) }
        val pendCnt = rows.count { pendingSyms.contains(it.it.s) }
        val noneCnt = rows.count {
            !pendingSyms.contains(it.it.s) && (it.d == null || !it.d.ok)
        }
        paintCountLine(live = liveCnt, noneN = noneCnt, wait = pendCnt, total = rows.size)
        // RecyclerView 化:整单换数据,不再逐行 addView(也不再有 capsules 反查表)。
        // 稿 L384 行自带 borderBottom 的规则不变 —— 它在行壳里,见 createRowShell。
        submitRows(sorted.map { FavRow(it.it, it.d, it.m, it.ratio) })
    }

    // 距离刷新(照稿 refreshFavDist):**逐品种**读它自己的 distWin(s) 窗;到一个补一个(行内逐条补数)。
    // **t99 ②:删掉 5 秒重试环**。稿(可搜索:「重试环」)写得很直白:
    //   「刷新时机:只在"App 被点开/回到前台"时刷一次,**不做任何定时轮询**」
    // 稿里 FAVRetry 只在 :1641 被 clearTimeout 清除、从不排下一次;App 原先在
    // finishRefresh 里 handler.postDelayed(r2, 5000) 且 bad>0 就无限重试、无上限无退避。
    // 这不是洁癖:东财从 12:21 到 14:45 连续不通,那 2 小时 24 分里这个环打了约 8600 次、
    // 每次必然失败——"明知不通还猛打"与"串行命中即停"是同一个原则问题。用户已拍板按稿删。
    //
    // 保留的刷新入口(删了环也不能变成"再也不刷新"):
    //   onShow()       切回自选页 → 见 onShow：**只在进程内首次进入**真抓一轮(net=true，全量)；
    //                  之后切页**不发任何取数请求**（2026-10-10 用户裁定：切页不刷，
    //                  原"按缺值补刷 only=缺失集合"那条路径已删）
    //   cycleSort       → 全量(用户显式动作「点列头」,本来就该整表重刷)
    //   MainActivity.onDistCommitted → **only = 刚确认的那一个品种**(2026-10-12 裁定 B 改;
    //                  改前是无参全量,配合全局窗口 = 改一个就全量重取,见 MktPanel 文件头墓碑)
    //   add 成功后     只 repaint（新行没有值就顶着空/留底，等用户下一次显式动作）
    //   MainActivity  回前台**不**调 onShow（onResume 只 ensure 汇率）⟹ 回前台不刷，无定时轮询
    // bg=后台补抓轮(本会话已成功的 key 直接复用现值、不再发请求,幂等)
    // net=真抓轮(冷启动首次进自选的那一轮,**绕过全部复用层真的发网络请求**,见 FavDist.forceFetch)
    // ⚠ 2026-10-10 起 onShow 不再传 only；**2026-10-12 裁定 B 改起，only 有了在用的调用点**
    //   —— onDistCommitted 传 `setOf(刚确认的品种)`，只刷那一行（见上方「保留的刷新入口」）。
    fun refreshFavDist(bg: Boolean = false, only: Set<String>? = null, net: Boolean = false) {
        // ⚠ 2026-10-12 裁定 B 改：删掉循环外那句 `val (tf, n) = mkt.distWin()`（全局窗口）。
        //   现在窗口**逐品种**在 for 循环里取（见下方 `mkt.distWin(s)`）—— 每个品种只受它
        //   自己的 gridcalc_win_v1[s] 影响，别人确认窗口动不到它的 key。
        // ⚠⚠⚠ 2026-10-08 用户真机 **28 个品种**（截图 `实时 28 / 无源 1`）——**请求量爆炸的真正源头**。
        //   ⚠ 2026-10-10 补记：下面【修法】里那句「onShow → 传缺失集合」**已作废** ——
        //     用户当天裁定切页不刷，onShow 那条调用整个删了（见 onShow ③ 的墓碑注释）。
        //     only 参数本身与下面这段修法对**其它调用点**仍然成立，故保留原文不改。
        //
        // 【症状】用户报「大宗商品老是无源」「那我只能慢慢刷新了」。
        // 【机制】onShow 的判据是「**有没有任何一行**缺值」(anyMissing)，而只要有任何一行缺失
        //   （哪怕只有那条一直失败的商品），就调 refreshFavDist() ⟹ **刷全部 28 个**。
        //   28 品种 × 1~2 腿 ≈ **40~50 条请求**一次性打出去 ⟹ 必撞东财限流
        //   ⟹ 又制造出新的失败行 ⟹ 下次切页又是全量 ⟹ **自激死循环**。
        //   用户看到的「慢慢刷新」其实是这个环在反复打。
        //
        // 【为什么这是实现偏离裁定，不是设计】用户 2026-10-05 的原话是
        //   「有数据的行切页不刷，**但一个值都没有的行要补**」——**补的是那一行**。
        //   实现把"要不要刷"正确接到了 anyMissing，却把"**刷谁**"落成了全量。
        //   一句话：判据对了，作用域错了。
        //
        // 【修法】加 only 参数：只补真正缺的那些行。
        //   · onShow → 传缺失集合（2026-10-10 裁定切页不刷，该路径已删）
        //   · 点列头 cycleSort → 仍传 null（保持原有**全量**语义，用户显式动作、本就该整表刷）
        //   · 确认窗口 onDistCommitted → 传 `setOf(sym)`（2026-10-12 裁定 B 改：**只刷那一个品种**）
        //     ⚠ 2026-10-10 曾记「only 已无在用的调用点」——**自 2026-10-12 起不再是**，
        //       它现在是「改一个只重算一行」的唯一入口，删掉就等于把选择权也删了。
        //   ⚠ 错峰索引用**过滤后**的 syms（下方 staggerIdx），这样只补 1 行时不会白等
        //     "它在全集里排第 7" 的那 4 秒。
        val all = FavStore.get(act).map { it.s }.distinct()
        val syms = if (only == null) all else all.filter { it in only }
        if (syms.isEmpty()) return
        val seq = ++distSeq
        val total = syms.size
        val done = AtomicInteger(0)
        val bad = AtomicInteger(0)
        for (s in syms) {
            // 「待取数」第四态的写入点之一（t35）。
            // ⚠⚠ 2026-10-03 **这里原来确实是惊群**（上面「不加 sleep / 无节流无上限」那条旧注释已作废）：
            //   起因是实测出来的：东财**限流**。判别实验（同一 URL 连打）——
            //       第1次 空响应 → 等 45s → 第2次 40根 ✅ → 立刻第3次 空响应 ❌
            //   自选屏一次刷 5 个品种、每品种 2 条腿 ≈ 10+ 请求**同时**打出去 ⟹ 必撞限流。
            //   表现：撞上的那个品种显示「—无源」。黄金稳定中招（排最后，
            //         且它的新浪腿返回 [] 白打一次），美光/海力士/比亚迪侥幸没撞上。
            // ⟹ 改成**错峰启动**：每个品种的 Thread 起来后先 sleep 一个按序号递增的间隔再取数。
            //   ⚠ 用 Thread.sleep 错峰而不是排队，**刻意为之**：保持每品种独立、
            //     失败隔离、互不阻塞的原有形状，只改「什么时候开始打」。
            //     若改成串行队列，一个慢品种会拖住后面所有 —— 那是更大的行为变更。
            //   ⚠ 300ms 是**实测限流窗口的保守取值**，不是精确值（45s 冷却对应的是密集连打）。
            //   ⚠ 只错峰**开始**，不限并发上限 —— 品种极多时总时长会线性变长。
            //     自选通常十几条以内可接受；上百条得换成真正的令牌桶。
            //
            // ⚠⚠ 2026-10-08 **按品种数自适应**（用户真机自选 **28 个品种**，截图实测
            //   `实时 28 / 无源 1`）。原值恒 300ms 是按"十几条以内"估的，28 条时：
            //   · 错峰总窗口只有 28×300ms ≈ 8.4s，而请求总量 28 品种 × 1~2 腿 ≈ 40~50 条
            //     ⟹ 平均仍达 ~5 条/秒，**正是东财限流窗口的密集区**
            //     （MktData 侧实测记录：连打第 1 次就空响应、要等 45s 才恢复）；
            //   · 撞上的那条显示「—无源」，而**从没成功过的品种**没有任何留底可兜
            //     ⟹ 这才是用户那条布伦特原油 `B00Y` 的处境。
            //   超过 20 条就把间隔加倍：牺牲的是总时长（用户已接受"慢慢刷新"），
            //   换来的是**单位时间请求量减半**，直接降低撞限流的概率。
            //   ⚠ 这不是把 300ms 调大这么简单 —— 十几条以内**必须保持原 300ms**，
            //     否则就是拿常见场景的体验去补极端场景的坑。
            // ⚠ 2026-10-12 裁定 B 改：**每个品种进循环时定死它自己的窗口**（gridcalc_win_v1[s]，
            //   无记录回落 ("M" to 20)）。下面 key / cached / lastOk / forceFetch / fullSup /
            //   lightPx / display **七处同用这一份 (tf,n)** ⟹ 换窗口只换这一个品种的 key。
            //   改前这份 (tf,n) 是循环外的全局值：一变，**所有**行的 key 同时变 → 新鲜缓存
            //   全 miss → 全量重取（用户报的「改了 TSLA 的窗口，退回自选又把全部品种取了一遍」）。
            val (tf, n) = mkt.distWin(s)
            val staggerMs = if (syms.size > 20) 600L else 300L
            val staggerIdx = syms.indexOf(s).coerceAtLeast(0)
            pendingSyms.add(s)
            Thread {
                if (staggerMs > 0 && staggerIdx > 0) {
                    try { Thread.sleep(staggerIdx * staggerMs) } catch (_: InterruptedException) { }
                }
                var r: DistR? = null
                val k = FavDist.key(s, tf, n)
                val shown = FavDist.cached(s, tf, n) ?: FavDist.lastOk(s, tf, n)
                if (!net && bg && FavDist.doneOk(k) && shown != null) {
                    r = shown
                } else {
                    try {
                        // 稿§5 两条取数路径:后台环=fullSup重抓支撑;首帧=lightPx轻量刷价
                        // ⚠ 2026-10-09 **第三条:真抓轮(net)** —— 用户裁定「冷启动首次那轮选
                        //   留底先上屏 + 后台错峰真抓一轮覆盖」。forceFetch 绕过
                        //   LastChart/MktCache/MktSave 与本文件 5 分钟新鲜缓存，对每个品种
                        //   **真的发请求**（改前这三级一命中就直接返回，一个请求都不发）。
                        //   失败时下面的 display() 会回落 lastOk() 留底旧值，屏上不会空。
                        r = when {
                            net -> FavDist.forceFetch(s, tf, n)
                            bg -> FavDist.fullSup(s, tf, n)
                            else -> FavDist.lightPx(s, tf, n)
                        }
                    } catch (_: Exception) {
                    }
                }
                // 稿970:无支撑(soft,数据已到)≠拉取失败,不进5秒重试环
                if (r == null || (!r.ok && !r.soft)) bad.incrementAndGet()
                act.runOnUiThread {
                    // 「待取数」的移除点（t35）。**必须在最前面、且在 seq/hidden 早退之前**：
                    // 否则被丢弃的旧世代（seq 不匹配）会把一个**已经不再取**的品种
                    // 永远留在 pendingSyms 里 ⟹ 那行永远显示「待取数」不消失。
                    pendingSyms.remove(s)
                    if (seq != distSeq || hidden) return@runOnUiThread
                    val disp = FavDist.display(s, tf, n, r)
                    // 按符号找到行,只重绑该行(不碰其他行、不重建列表)。
                    updateDist(s, disp)
        t112CallSrcRow()
                    if (done.incrementAndGet() == total) finishRefresh(bad.get())
                }
            }.start()
        }
    }

    private fun finishRefresh(bad: Int) {
        // t99 ②:原先这里是 handler.postDelayed(r2, 5000) 的 5 秒重试环,已按稿删除
        // (稿 :1665「不做任何定时轮询」)。**不排下一次**——失败就停在失败态,
        // 等下一次用户驱动的入口(切回自选页/回前台/加自选)再来刷。
        // 刷新完成后**一律重排重绘**。这里原来写的是「排序激活(非off)时才重排」, 并给那条
        // 条件安了一个**稿依据**(说稿里有个排序开关可判非 off) —— 我核过: 稿 923 行全文
        // 的 off 零命中, 稿里根本没有这个状态, 那个依据是不存在的, 已删(原句也一并删掉,
        // 不留引用, 免得下一个 grep 它的人当成还活着的稿构造)。
        // 真实语义: getSort 三个出口已收敛, 永不返回 off ⟹ 原来那个条件**恒真**,
        // 于是每次刷新后都 repaint, 死条件随之删掉。
        // 队长裁定保留该行为: ①「刷新完本来就该重绘」更朴素, 不必拿排序状态当开关;
        // ② 省掉一个死分支路径更短; ③ 稿里对应的就是刷新后 paintFav 那条路径。
        // ⚠ 这是**行为变过的, 不是 bug, 别修回来**: 改前只有排序非 off 时才重排。
        //   敢留的依据: off 已不可达, 恒真与改前的默认路径(dist:desc)是同一条, 没有新
        //   行为进入任何可达路径; 且 finishRefresh 每轮只调一次, repaint 无动画, 不会闪。
        repaint()
    }

    // 进自选页(稿showTab('fav')→refreshFavDist):重绘;取数**只在进程内首次进入**那一次。
    // ⚠⚠⚠ 2026-10-10 **用户裁定：切页不刷**。原话：「每次到自选页面就要自动刷新数据，
    //   明明只是随便切一下页面而已。」⟹ 只有进程内首次进入才真抓一轮（下面 ②）；
    //   此后切回本页**一个取数请求都不发**（下面 ③ 的位置原本是"按缺值补刷"那条路径，已删）。
    //   这是对 2026-10-05「没有获取到的，可以在限流措施内继续刷，但是只刷新他」的**取代**，
    //   不是回退到更早的全量刷：两条都不再要了。
    //   ① 与 ② 保持原样；刷新入口只剩 cycleSort（全量）/ MainActivity.onDistCommitted
    //     （only = 刚确认的品种，2026-10-12 裁定 B 改）两处用户显式动作。
    fun onShow() {
        hidden = false
        // ① **留底先上屏**：repaint 走 FavDist.shown()（cached 的 ok/soft → 落盘 lastOk 旧值），
        //    不等网络 ⟹ 冷启动/断网也不会先画一屏空行。
        repaint()
        // ⚠ 2026-10-10：这里原有 `val (tf, n) = mkt.distWin()`，只为下面 ③ 的
        //   `FavDist.hasValue(s, tf, n)` 提供窗口参数；③ 删掉后 tf/n 再无读者
        //   （编译器报 "Variable 'tf' is never used"），一并删。distWin() 是纯 getter
        //   （读窗设置，不取数、无副作用），不再调用它**不改变任何行为**。
        //   ⚠ 2026-10-12 裁定 B 改补记：那个**无参** distWin() 连同全局 DistWin 一起删了
        //     （现在是 `distWin(品种)`，见 MktPanel 文件头墓碑）——本处本来就没读者，不受影响。
        val syms = FavStore.get(act).map { it.s }.distinct()
        if (syms.isEmpty()) return

        // ② ⚠⚠⚠ 2026-10-09 **进程内首次进入自选：真抓一轮**。两句裁定拼起来才是这一段的语义：
        //    ①「只在进程内首次进入自选刷新一次」⟹ 只有"首次"这一次；
        //    ②「冷启动首次那轮选留底先上屏 + 后台错峰真抓一轮覆盖」⟹ 这一轮是**真抓**。
        //    （2026-10-05 那句「没有获取到的，可以在限流措施内继续刷，但是只刷新他」
        //      已被 2026-10-10 裁定取代，**不再是本段的依据**，见 ③。）
        //    · 一轮 = **全量**（这是唯一允许全量的一处）；
        //    · 真抓 = net=true ⟹ FavDist.forceFetch，绕过 LastChart/MktCache/MktSave
        //      与 5 分钟新鲜缓存，对每个品种真的发网络请求（改前复用层一命中就返回，
        //      所谓"刷新"只是拿缓存重算一遍）；
        //    · 失败的行由回调里的 display() 回落 lastOk() 留底旧值顶屏。
        //    ⚠ 本分支**语义未变**（2026-10-10 只删了下面 ③）：仍是"首次、全量、真抓"。
        //      唯一少掉的是原来在这里顺手写的一次 `lastMissFetchAt[s] = now` —— 那是给
        //      已删的 ③ 限流闸用的记账，首轮自身的行为与它无关，删掉不改变首轮。
        if (!firstRoundDone) {
            firstRoundDone = true
            refreshFavDist(net = true)
            return
        }

        // ③ 【原"按缺值补刷"路径，2026-10-10 已删】——切页到此为止，不发任何请求。
        //
        // 删掉的是什么：原先这里按 `!FavDist.hasValue(s, tf, n) &&
        //   now - lastMissFetchAt[s] >= MISS_REFETCH_GAP_MS` 求出 missing 集合，
        //   再 `refreshFavDist(only = missing)` 去补那 1~2 行。
        //
        // 【为什么删】它挂着 60s 限流闸，**看着**不像"每次切页都刷"，但自选里只要有
        //   1~2 个**永远取不到数**的品种（韩股、布伦特原油 B00Y 这类从没成功过的），
        //   它们的 `hasValue` 恒为 false ⟹ 过了 60s 再切页必然命中 missing 又打一轮。
        //   用户看到的就是「每次到自选页面就要自动刷新数据」——他 2026-10-10 的裁定
        //   正是针对这个观感：**随便切一下页面不该触发任何取数**。
        //   限流闸能压低请求量，但压不掉"切页会发请求"这件事本身。
        //
        // 【连带的死代码】`lastMissFetchAt` 与 `MISS_REFETCH_GAP_MS` 只服务这条路径，
        //   已随它一起删（companion 里留了墓碑注释）。原来在 ② 之前声明的
        //   `val now = System.currentTimeMillis()` 与 `val (tf, n) = mkt.distWin()`
        //   也只剩这条路径在用，一并删（见上面两处 ⚠）。
        //
        // 【还活着的刷新入口】cycleSort（用户点列头）→ 全量，**未动**；
        //   MainActivity.onDistCommitted（用户提交距离窗口）→ **only = 刚确认的那一个品种**
        //   （2026-10-12 裁定 B 改；改前是无参全量，那时全局窗口一变就等于全部重取）；
        //   加自选后走 mktPanel.onFavChanged → 只 repaint（本来就不取数），**未动**。
        //   ⚠ 别再往 onShow 里加取数：那正是 2026-10-10 裁定要拿掉的东西。
    }

    // 离开自选页(稿else clearTimeout(FAVRetry)):停表清计时器,在途旧结果作废
    fun onHide() {
        hidden = true
        distSeq++
        // t35:离开自选页时清空「待取数」集合。
        // 理由与 distSeq++ 同源 —— 在途线程的 runOnUiThread 回调会因 hidden 直接早退,
        // 那些品种**再也不会走到 pendingSyms.remove** ⟹ 不清就会留下一批永远
        // 显示「待取数」的行,下次进来也不消失。
        pendingSyms.clear()
        /* 补清搜索框（captain 2026-10-01 授权）。理由不只是「看着莫名其妙」：
           残留的查询词会让**联想浮层的开关状态对不上人** —— 框里明明有字，
           浮层却是收起的，人会以为搜索坏了。（net-verify 验证 F-2a 时就被这一点绕过一次。）
           原来只有两处会清：:1346「删除确认」后、:1450 按 ESC 后；**离开本页从不清**，
           且 EditText 的 saved instance state 会在重建时把文字恢复回来。 */
        searchInp.setText("")
        // t99 ②:5 秒重试环已删,这里原是清 retry 的收尾,现在不再需要(对齐稿 :1641 只清 FAVRetry)
    }

    // 列头(照稿.favhead .favhead):三列结构**完全一致**——列名 + 等宽箭头槽,
    // 稿 L360-361 逐字: 未选中 ink3/400 + 箭头 ⇅;  选中 ink/600 + 箭头 --ac 且是 ▲ 或 ▼。
    // 三列同一字号(原 ratio 11sp 是为迁就旧的 62dp 窄列)。
    // 排序是**两态**（稿 L358 `set({k,asc:act?!sort.asc:false})`）, 实现在 cycleSort。
    // ⚠ 这里曾写着「排序状态机仍是三态(App 私货), 本轮不动状态机」——
    //   那段描述**与本文件的实现不符**（同文件 cycleSort 上方已写对两态）,
    //   真机连点三列也确认是两态。第三态早已删除, 这段注释是残留（t389 清）。
    private fun paintSortHead(b: Button, label: String, col: String) {
        val cur = FavStore.getSort(act)
        val active = cur.startsWith("$col:")
        val dir = cur.substringAfter(':', "off")
        val faint = act.attrColor("colorFaint")
        val ac = act.attrColor("colorPrimary")
        val glyph = if (active) (if (dir == "asc") "▲" else "▼") else "⇅"
        val labelColor = if (active) act.attrColor("colorInk") else faint
        val arrowColor = if (active) ac else faint
        val span = SpannableString("$label $glyph")
        val base = label.length + 1
        span.setSpan(ForegroundColorSpan(labelColor), 0, base, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (active) span.setSpan(StyleSpan(Typeface.BOLD), 0, base, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        span.setSpan(ForegroundColorSpan(arrowColor), base, base + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        b.text = span
        b.setTextColor(labelColor)
        b.textSize = TS(R.dimen.fs_note)
    }

    private fun paintSortHeads() {
        paintSortHead(sortDist, "距支撑", "dist")
        paintSortHead(sortGain, "收益率", "gain")
        paintSortHead(sortRatio, "收益比", "ratio")
    }

    // 稿 L358 两态: 同列 → 升降互换; 换列 → 一律从降序起。
    // ⚠ 原注释描述的是**一个三态状态机**（升序/降序/关），但本函数实现与稿都是两态,
    //   稿里也没有 ratio 特判。那段描述是早期版本的残留（t389 清）。
    private fun cycleSort(col: String) {
        val cur = FavStore.getSort(act)
        // 稿 L358: set({k, asc: act ? !sort.asc : false})
        //   同列 → 升降互换;  换列 → 一律从降序起。
        //   ⟹ 两态, 没有"关"; 也没有 ratio 特判(稿里根本没有)。
        val nx = if (cur.startsWith("$col:")) {
            if (cur.substringAfter(':') == "asc") "$col:desc" else "$col:asc"
        } else {
            "$col:desc"
        }
        FavStore.setSort(act, nx)
        paintSortHeads()
        repaint()
        // ⚠ 2026-10-12 裁定 B 改**不动这里**：点列头仍是**全量** refreshFavDist()（用户显式
        //   动作、整表重排，本来就该全刷）。变的只是每个品种各用各的窗口（循环内 distWin(s)）
        //   —— 排序键（dist/gain/ratio）与两态状态机、无值沉底规则逐字未改。
        refreshFavDist()
    }

    // 压缩格式(稿§2):|v|>=1e6→M(2位) >=1e5→K(1位) >=1e3→千分位整数 <1000→两位小数

    // 稿 v2.html:967 逐字: const fmtRatio = v => v==null ? '—' : v.toFixed(2)+' ×';
    // ⟹ **两位小数, 任何量级都是**, 且 × 前留一个空格（空格由调用处 paintRatioCell 拼）。
    // 改前走 fmtCompact: |v|>=1e3 → 千分位整数 / >=1e5 → 一位 K / >=1e6 → 两位 M,
    // 于是 86.13 被写成 86.1、1234.56 被写成 1.23K —— 稿里这两种写法都不存在。
    // fmtCompact **本身不动**: 净收益那一行(规格§2「净收益 +86.13 USD」)还要用它。
    private fun fmtRatio(v: Double): String = String.format(Locale.US, "%.2f", v)

    // 稿§2 收益率列悬浮:收益率+净收益(USD)+保存时间
    // D7:净收益按规格§2逐字示例「净收益 +86.13 USD」用两位小数千分位(与 MainActivity.fmt 同款),
    // **不复用 fmtCompact**(会把 86.13 四舍五入成 86.1 / 86.13K,与规格示例不符)
    private fun gainTip(m: RowMeta): String? {
        val parts = mutableListOf<String>()
        m.gain?.let { parts.add("收益率 " + fmtPct(it)) }
        m.net?.let {
            parts.add("净收益 " + (if (it >= 0) "+" else "-") +
                String.format(Locale.US, "%,.2f", Math.abs(it)) + " USD")
        }
        if (m.gts.isNotEmpty()) parts.add(m.gts)
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }

    // 稿§3 收益比 = 收益率(%) ÷ |距支撑|(%) —— **两个操作数必须同为百分数**。
    // 缺任一项(无记录/距支撑无源/距支撑=0)→null。
    //
    // ⚠️ **2026-10-01 移除「老记录只有金额时降级用 net」的分支**（t35 队长裁定）。
    //   改前是 `m.gain ?: m.net ?: return null`，而：
    //     gain = 收益率(%)，net = 净收益金额(USD)
    //   拿 USD 去除以 |dist|(%) 得 **USD/%**，与用 gain 时的 **%/%** ——
    //   **两个量纲混在同一个「×」里**，屏上完全看不出来，但它是**假数字**。
    //   立场与 MktData.kt:920 一致：**宁可空，不可换**。
    //   gain 缺失 ⟹ 整体返回 null，行上显示「—」；老记录仍可在悬浮提示里看它的净收益金额
    //   （见 gainTip，它没有混进这个「×」）。
    private fun ratioOf(distPct: Double?, m: RowMeta): Double? {
        if (distPct == null || distPct == 0.0) return null
        val g = m.gain ?: return null
        return g / Math.abs(distPct)
    }

    private fun paintRatioCell(rc: TextView, d: DistR?, m: RowMeta) {
        val distPct = if (d != null && d.ok) d.pct else null
        // 稿 v2.html:1203 逐字: color: (blankD||ratio==null) ? 'var(--ink3)' : pnlColor(ratio)
        //   稿 v2.html:1203 的文字逐字: {blankD?'—':fmtRatio(ratio)}, 而 fmtRatio(null)='—'
        //   ⟹ **zero 必须先判**, 否则 ratioOf 拿 0 当除数会算出 Infinity/NaN。
        val r = if (isZero(d)) null else ratioOf(distPct, m)
        // ⚠ 稿这一档的判据是 **none||zero**（v2.html:1203 的 `blankD||ratio==null`）,
        //   **没有 stale**; stale 行照常出 `0.63×` 这类值。
        // 改前这里多了一条 `|| isStale(d)`, 于是每条 stale 行的收益比都被抹成「—」,
        // 而同行的收益率、代码、距支撑都照常有值 —— 同行自相矛盾（t389）。
        if (r == null) {
            // 上面那一支已对齐稿: 空值 = 文字「—」+ 颜色 --ink3 = colorFaint。
            rc.text = "—"
            rc.setTextColor(act.attrColor("colorFaint"))
            rc.tooltipText = null
            return
        }
        rc.text = fmtRatio(r) + "×"
        // ⚠ 配色（2026-10-03 **用户裁定改绿**，与收益率列同一裁定）
        //   ⟹ **恒定绿色**。
        //
        // 【推翻的是下面这段旧依据】
        //   稿 v2.html:965 `pnlColor = v=>v>0?'--up':v<0?'--dn':'--ink'`，落点 v2.html:1203
        //   ⟹ 正绿 / 负红 / 零墨。本轮曾据此补齐负值那一档（改前是恒 colorSub 中灰）。
        //   **现在按用户裁定改回恒绿。**
        //   ⚠ 旧注里「此处用红不违反『红只属于涨跌』」的论证（sign(ratio) ≡ sign(收益率)）
        //     **已不适用** —— 那一列现在根本不再出现红色。
        //
        // ⚠ 无值/过期仍走 colorFaint：「没有数据」与「有数据但恒绿」是两回事。
        // ⚠ 距支撑那一列**仍按正负分色**，未受影响（用户只点名了收益率与收益比）。
        // ⚠ 2026-10-07 同上：收益比恒绿（有值即绿），不再按stale置灰。
        rc.setTextColor(act.attrColor("colorTeal"))
        // D6:距支撑取绝对值——跌破支撑时 distPct 为负,公式本身用 |距支撑|,
        // 提示里若写「÷ 距支撑 -2.50%」会与「收益比 = 收益率 ÷ |距支撑|%」自相矛盾
        val distTxt = String.format(Locale.US, "%.2f", Math.abs(distPct!!))
        rc.tooltipText = "收益比 ${fmtRatio(r)} = " +
            // ⚠️ 改前这里是 `(gain==null && net!=null) ? "净收益(老记录降级)…" : "收益率 …"` ——
            //   ratioOf 移除了 net 降级后，这个分支**永远走不到**，留着就是一处
            //   「文案描述一个已不存在的行为」。现在只有一条路，且 gain 必非空
            //   （ratioOf 返回非 null ⟹ gain != null）。
            "收益率 ${fmtPct(m.gain!!)}" +
            " ÷ 距支撑 $distTxt%"
    }

    private fun hideResults() {
        // 世代+1:关闭后旧异步回包一律丢弃(对标稿子FSGen++)
        fsGen++
        fsRunnable?.let { handler.removeCallbacks(it) }
        sugBox.visibility = View.GONE
    }

    private fun showResults(items: List<SugItem>, q: String) {
        resultsBox.removeAllViews()
        val mine = FavStore.get(act).map { it.s }.toSet()
        val show = MktSuggest.rankFav(items, q, mine)
        if (show.isEmpty()) {
            resultsBox.addView(note("无匹配"))
        }
        for (it in show) {
            val row = rowView(it.s, it.tag, {
                if (FavStore.isFav(act, it.s)) return@rowView
                FavStore.add(act, it.s, MktData.symType(it.s))
                searchInp.setText("")
                hideResults()
                repaint()
            }, {
                hideResults()
                // ⚠ 搜索结果这条路径**拿不到中文名**（MktSuggest 的项只有 s + tag，
                //   tag 是「美股/币/韩股」这类市场标签，不是品种名），故 disp 传空串，
                //   由行情页回退显示代码。收藏行那条(:1273)才有真正的 f.t。
                onPick(it.s, "")
            }, "")
            resultsBox.addView(row)
        }
        // 内联展示:内容已在上面换好,直接量高度(最高 248dp,沿用原来浮层的口径),
        // 超了内部滚动。⚠ 2026-10-06 起不再建 PopupWindow ——
        // 可获焦浮层 show/dismiss 会抢窗口焦点、收软键盘,复用与延后恢复都只是事后补救,
        // 真机上照样掉。内联容器与输入框同属一个窗口,不产生新的窗口焦点,
        // 键盘没有被收的理由;无障碍树里也天然可达。
        val w = searchInp.width
        if (w <= 0) return
        sugScroll.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val h = minOf(sugScroll.measuredHeight, dp(248f).toInt())
        if (h <= 0) return
        val lp = sugScroll.layoutParams as? LinearLayout.LayoutParams
            ?: LinearLayout.LayoutParams(w, h)
        lp.width = w
        lp.height = h
        sugScroll.layoutParams = lp
        if (sugScroll.parent == null) sugBox.addView(sugScroll)
        sugBox.visibility = View.VISIBLE
    }

    private fun scheduleSearch() {
        fsRunnable?.let { handler.removeCallbacks(it) }
        val q = searchInp.text.toString().trim().uppercase(Locale.US)
        if (q.isEmpty()) {
            hideResults()
            return
        }
        val r = Runnable {
            val gen = ++fsGen
            val local = MktSuggest.favLocal(q)
            showResults(local, q)
            Thread {
                val more = mutableListOf<SugItem>()
                try {
                    for (e in MktSuggest.spotAll()) {
                        if (e.s.contains(q) && more.none { it.s == e.s }) {
                            more.add(e)
                        }
                    }
                } catch (_: Exception) {
                }
                if (gen != fsGen) return@Thread
                val withSpot = local + more.filter { x -> local.none { it.s == x.s } }
                act.runOnUiThread {
                    if (gen != fsGen) return@runOnUiThread
                    showResults(withSpot, q)
                }
                try {
                    for (x in MktSuggest.yahooFav(q)) {
                        if (more.none { it.s == x.s } &&
                            withSpot.none { it.s == x.s }
                        ) {
                            more.add(x)
                        }
                    }
                } catch (_: Exception) {
                }
                if (gen != fsGen) return@Thread
                val all = withSpot + more.filter { x -> withSpot.none { it.s == x.s } }
                act.runOnUiThread {
                    if (gen != fsGen) return@runOnUiThread
                    showResults(all, q)
                }
            }.start()
        }
        fsRunnable = r
        handler.postDelayed(r, 250)
    }

    init {
        paintSortHeads()
        // 三键互斥, 都走 cycleSort 的两态（稿 L358）; 无 ratio 特判。
        sortDist.setOnClickListener { cycleSort("dist") }
        sortGain.setOnClickListener { cycleSort("gain") }
        sortRatio.setOnClickListener { cycleSort("ratio") }
        searchInp.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = scheduleSearch()
        })
        // 失焦150ms后收候选(对标稿子blur,让位于＋/点选)。
        // ⚠ 2026-10-06 内联化之后判据回到最简:输入框没焦点才收。
        //   PopupWindow 时代需要同时看「浮层自己有没有焦点」,是因为浮层 show 会抢焦点、
        //   输入框失焦是正常状态;内联容器不产生新的窗口焦点,不存在这个问题。
        searchInp.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                handler.postDelayed({
                    if (!searchInp.hasFocus()) hideResults()
                }, 150)
            }
        }
        searchInp.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                keyCode == KeyEvent.KEYCODE_ESCAPE
            ) {
                searchInp.setText("")
                hideResults()
                act.hideKeyboard(searchInp)
                true
            } else false
        }
        repaint()
        // ⚠ 2026-10-09 **这里不再发起刷新**。本面板在 MainActivity.onCreate 里就被构造
        //   （早于 showTab），若在此刷一轮，则**进程一启动就打一轮** —— 而用户裁定的是
        //   「只在**进程内首次进入自选**刷新一次」。首轮真抓已收进 onShow()（见那里的注释②）：
        //   起在自选页时 showTab(startTab) 立刻就会调 onShow()，不会漏刷；
        //   起在别的页时那一轮**本来就不该打**（28 品种 × 1~2 腿，白撞东财限流）。
    }

    companion object {
        // ⚠ 2026-10-10 这里原有两个成员，**已删**（用户当天裁定「切页不刷」的连带清理）：
        //   · `MISS_REFETCH_GAP_MS`（60s 限流闸）—— 只被 onShow ③「按缺值补刷」那条路径读；
        //   · `lastMissFetchAt`（每品种最近一次补刷尝试时刻）—— 只被那条路径读写。
        //   路径删了，两者都没有任何引用点 ⟹ 按"不留死代码"清掉。删它们**不影响**首轮真抓
        //   （onShow ②）与其它刷新入口：那两个成员从没参与过那些路径。
        //   ⚠ 别因为"看着像限流保护"就加回来 —— 切页已经不发请求，没有需要限流的对象了。
        //   裁定原文与完整理由见 onShow() ③ 的墓碑注释。

        /**
         * **进程内**是否已经刷过冷启动首轮（见 onShow ②）。
         * ⚠ 必须是**进程级**（companion）而不是实例字段：转屏/主题变化会重建 Activity，
         *   实例字段会把「每进程一次」变成「每次重建一次」。
         */
        @Volatile private var firstRoundDone = false
    }
}
