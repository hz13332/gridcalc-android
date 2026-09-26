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
import android.view.LayoutInflater
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
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
    private val tfW: Button = page.findViewById(R.id.mkt_tf_w)
    private val tfM: Button = page.findViewById(R.id.mkt_tf_m)
    private val tfQ: Button = page.findViewById(R.id.mkt_tf_q)
    private val status: TextView = page.findViewById(R.id.mkt_status)
    private val ohlc: TextView = page.findViewById(R.id.mkt_ohlc)
    private val leg: TextView = page.findViewById(R.id.mkt_leg)
    private val srcNote: TextView = page.findViewById(R.id.mkt_srcnote)
    private val price: TextView = page.findViewById(R.id.mkt_price)
    private val tgt: TextView = page.findViewById(R.id.mkt_tgt)
    // v4§7 美元口径说明行(数据源下方;汇率异步到达后重画)
    private val fxRow: TextView = page.findViewById(R.id.mkt_fx)
    private val chart: MktView = page.findViewById(R.id.mkt_chart)
    private val favBtn: Button = page.findViewById(R.id.mkt_fav)
    private val dock: LinearLayout = page.findViewById(R.id.mkt_input_dock)
    private val searchBtn: View = page.findViewById(R.id.mkt_search)
    private val row2: LinearLayout = page.findViewById(R.id.mkt_row2)
    private val winBtn: Button = page.findViewById(R.id.mkt_win)
    private var winPop: PopupWindow? = null

    // 自选变更(收藏/删除)时通知自选页重绘;由MainActivity接线
    var onFavChanged: (() -> Unit)? = null
    // 点▾确认提交距离窗口后,通知自选页重刷距离(稿winGo:commit+refresh+收菜单)
    var onDistCommitted: (() -> Unit)? = null

    // 稿§4 根数范围 KMIN=15/KMAX=100,越界clamp
    private val KMIN = 15
    private val KMAX = 100
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

    // D4:取数收尾(成功画完图、或已确定失败)之后才回填保存值,保证保存值赢过 linkCalc 的支撑联动
    private fun finishLoad(seq: Int) {
        if (seq != reqSeq) return
        if (!wantRestore) return
        wantRestore = false
        act.restoreCalc(lastSym)
    }

    // ---------- 品种联想下拉(对标稿子symList) ----------
    private val sugHandler = Handler(Looper.getMainLooper())
    private var sugRunnable: Runnable? = null
    private var sugSeq = 0
    private var sugItems: List<SugItem> = emptyList()
    private var sugSel = -1
    private var sugPopup: PopupWindow? = null
    private var sugAdapter: SugAdapter? = null

    private inner class SugAdapter : BaseAdapter() {
        var sel = -1
        override fun getCount(): Int = sugItems.size
        override fun getItem(p: Int): SugItem = sugItems[p]
        override fun getItemId(p: Int): Long = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
            val v = cv ?: LayoutInflater.from(act)
                .inflate(R.layout.row_suggest, parent, false)
            val it = sugItems[p]
            val onPrimary = act.attrColor("colorOnPrimary")
            val ink = act.attrColor("colorInk")
            val sub = act.attrColor("colorSub")
            v.findViewById<TextView>(R.id.sug_sym).apply {
                text = it.s
                setTextColor(if (p == sel) onPrimary else ink)
            }
            v.findViewById<TextView>(R.id.sug_tag).apply {
                text = it.tag
                setTextColor(if (p == sel) onPrimary else sub)
            }
            v.setBackgroundResource(
                if (p == sel) R.drawable.btn_primary
                else android.R.color.transparent)
            return v
        }
    }

    private fun dp(v: Float): Float = act.resources.displayMetrics.density * v

    private fun showSug(items: List<SugItem>, seq: Int) {
        if (seq != sugSeq) return
        if (items.isEmpty()) {
            hideSug()
            return
        }
        sugItems = items
        sugSel = -1
        val ad = sugAdapter
        if (ad == null || sugPopup == null) {
            val lv = ListView(act)
            val na = SugAdapter()
            sugAdapter = na
            lv.adapter = na
            lv.setOnItemClickListener { _, _, p, _ -> pickSug(sugItems[p]) }
            val pw = PopupWindow(lv, symInp.width,
                ViewGroup.LayoutParams.WRAP_CONTENT, false)
            pw.setBackgroundDrawable(
                act.resources.getDrawable(R.drawable.card_bg, act.theme))
            pw.elevation = dp(8f)
            pw.isOutsideTouchable = true
            sugPopup = pw
            na.sel = -1
            na.notifyDataSetChanged()
            pw.showAsDropDown(symInp, 0, dp(4f).toInt())
        } else {
            ad.sel = -1
            ad.notifyDataSetChanged()
            if (sugPopup?.isShowing != true) {
                sugPopup?.showAsDropDown(symInp, 0, dp(4f).toInt())
            }
        }
    }

    private fun hideSug() {
        // 防重弹:查看/回车/选词后旧异步回包一律丢弃
        sugSeq++
        sugRunnable?.let { sugHandler.removeCallbacks(it) }
        sugPopup?.dismiss()
    }

    private fun paintSugSel() {
        sugAdapter?.let {
            it.sel = sugSel
            it.notifyDataSetChanged()
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

    fun setSym(s: String) {
        symInp.setText(s)
        symInp.setSelection(s.length)
        paintStar()
    }

    // 自选距离键用(对标稿子favKey读tfSeg/kcount)
    fun curTf(): String = tf

    // 稿§4根数:范围15≤n≤100(KMIN/KMAX),越界clamp不报错;空值回落该品种已确认默认
    fun curN(): Int {
        val v = kcountInp.text.toString().trim().toDoubleOrNull()
            ?.let { Math.round(it).toInt() }
        return (v ?: confirmedN()).coerceIn(KMIN, KMAX)
    }

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

    // 离开行情页(返回键/‹返回/切Tab,由showTab统一钩):丢弃未确认临时值,
    // 输入框+周期还原成该品种已确认默认(不发请求,纯状态还原)
    fun onLeave() {
        if (lastSym.isEmpty()) return
        applyWinDefaults()
    }

    // ▾确认菜单(照稿winPop):与箭头等宽/等高/同右缘,高41文字居中;
    // 点确认=提交窗口+重刷自选距离+收菜单;点菜单外部收起(outsideTouchable)
    private fun toggleWinPop() {
        val wasShowing = winPop?.isShowing == true
        winPop?.dismiss()
        if (wasShowing) return
        val go = Button(act).apply {
            text = "确认"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(act.attrColor("colorInk"))
            setBackgroundResource(android.R.color.transparent)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(41f).toInt())
            setOnClickListener {
                // 稿§4:▾确认才把(周期+根数)写成该品种已确认默认;输入框归一到clamp值
                kcountInp.setText(curN().toString())
                commitDistWin()
                saveWin()
                onDistCommitted?.invoke()
                winPop?.dismiss()
            }
        }
        val pw = PopupWindow(go, winBtn.width, dp(41f).toInt(), false).apply {
            setBackgroundDrawable(act.resources.getDrawable(R.drawable.card_bg, act.theme))
            elevation = dp(8f)
            isOutsideTouchable = true
            isFocusable = true
        }
        winPop = pw
        pw.showAsDropDown(winBtn, 0, dp(4f).toInt())
    }

    // 行情页同栅格(照稿第二行注释):间隙单边4dp(稿gap:4px,相邻不可双边各4dp),
    // k=(W-8dp)/3取整、余数补最后键;周期行三键等宽k/k/末键、高41;
    // 第二行输入框=周+月同宽(2k+4dp)、▾=末键宽(W-2k-8dp),与季键同宽同右缘;
    // 仅在数值变化时改layoutParams,避免layout回环
    private fun syncGrid() {
        val w = row2.width
        if (w <= 0) return
        val g = dp(4f).toInt()
        val k = (w - 2 * g) / 3
        val last = w - 2 * k - 2 * g
        if (k <= 0 || last <= 0) return
        for ((i, b) in listOf(tfW, tfM, tfQ).withIndex()) {
            val lp = b.layoutParams as LinearLayout.LayoutParams
            val bw = if (i == 2) last else k
            val rm = if (i == 2) 0 else g
            if (lp.width != bw || lp.weight != 0f ||
                lp.leftMargin != 0 || lp.rightMargin != rm
            ) {
                lp.width = bw
                lp.weight = 0f
                lp.leftMargin = 0
                lp.rightMargin = rm
                b.layoutParams = lp
            }
        }
        val kw = 2 * k + g
        val klp = kcountInp.layoutParams as LinearLayout.LayoutParams
        if (klp.width != kw || klp.weight != 0f) {
            klp.width = kw
            klp.weight = 0f
            kcountInp.layoutParams = klp
        }
        val wlp = winBtn.layoutParams as LinearLayout.LayoutParams
        if (wlp.width != last || wlp.weight != 0f) {
            wlp.width = last
            wlp.weight = 0f
            winBtn.layoutParams = wlp
        }
    }

    // 回车/查看:下拉开着且有选中行→用选中项,否则按输入框文字直接加载
    private fun pickOrLoad() {
        val pw = sugPopup
        if (pw?.isShowing == true && sugSel >= 0 && sugSel < sugItems.size) {
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
        paintTf()
        // 稿mktSearch(1155):点🔍展开原隐藏品种输入坞并聚焦品种输入框(只展不收,同稿display='')
        searchBtn.setOnClickListener {
            dock.visibility = View.VISIBLE
            symInp.requestFocus()
            symInp.post {
                val imm = act.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as? android.view.inputmethod.InputMethodManager
                imm?.showSoftInput(
                    symInp, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }
        goBtn.setOnClickListener { pickOrLoad() }
        favBtn.setOnClickListener { toggleFav() }
        paintStar()
        // ▾确认菜单 + 同栅格宽度(稿:第二行与周期行同栅格,布局完成后按实际宽计算)
        winBtn.setOnClickListener { toggleWinPop() }
        row2.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncGrid() }
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
            val open = sugPopup?.isShowing == true && sugItems.isNotEmpty()
            if (event.action == KeyEvent.ACTION_DOWN && open &&
                (keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                    keyCode == KeyEvent.KEYCODE_DPAD_UP)
            ) {
                sugSel = if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    minOf(sugItems.size - 1, sugSel + 1)
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
            }
        })
        kcountInp.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                done(v) { if (symInp.text.toString().trim().isNotEmpty()) mkLoad() }
                true
            } else false
        }
        // K数变更即重算(对标稿子change事件:失焦提交时,有品种就直接加载)
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
            ohlc.text = info.ohlc
            ohlc.setTextColor(if (info.chgUp) upC else dnC)
            // 图例(照稿子v3.3+定稿结构):第1行=绿标第一支撑+第二支撑(并排同行),
            // 第2行=根数·来源独占一行,其余支撑/十字行跟后竖排;
            // POC/VA只算不显示;状态行报第一支撑价,无则暂无支撑
            val sup = info.supShow
            // 稿renderMK(764):supShow(现价之下支撑)→linkCalc 软联动(计算页最低价/目标爆仓价上限)
            act.linkCalc(sup)
            val legText = SpannableStringBuilder()
            if (sup.isNotEmpty()) {
                val markStart = legText.length
                legText.append("■ 支撑 " + MktData.mkFmt(sup[0]))
                legText.setSpan(
                    ForegroundColorSpan(Color.parseColor("#3DDC84")),
                    markStart, markStart + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (sup.size > 1) {
                    legText.append("  第二支撑 " + MktData.mkFmt(sup[1]))
                }
                legText.append("\n")
            }
            legText.append(info.count.toString() + "根 · " + lastSrc)
            if (info.legRow.isNotEmpty()) legText.append("\n" + info.legRow)
            if (sup.size > 2) legText.append("\n支撑 " +
                sup.slice(2..minOf(3, sup.size - 1))
                    .joinToString(" / ") { MktData.mkFmt(it) })
            leg.text = legText
            // 稿:成功态不显示"支撑xxx"状态文字(mktstatus默认display:none)
            mkStatus("", false)
        }
    }

    private fun paintTf() {
        val onPrimary = act.attrColor("colorOnPrimary")
        val ink = act.attrColor("colorInk")
        for ((b, t) in listOf(tfW to "W", tfM to "M", tfQ to "Q")) {
            if (tf == t) {
                b.setBackgroundResource(R.drawable.btn_primary)
                b.setTextColor(onPrimary)
            } else {
                b.setBackgroundResource(R.drawable.btn_ghost)
                b.setTextColor(ink)
            }
        }
    }

    // 稿mkStatus:mktstatus 仅 err 时 display:block,成功/拉取中一律收起
    private fun mkStatus(t: String, err: Boolean) {
        if (err && t.isNotEmpty()) {
            status.visibility = View.VISIBLE
            status.text = t
            status.setTextColor(act.attrColor("colorRed"))
        } else {
            status.visibility = View.GONE
        }
    }

    fun mkLoad() {
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
        // 稿§4:换品种→载入该品种已确认默认(周期+根数,互不串);同品种刷新保留敲过的值
        if (symChanged) applyWinDefaults()
        // 根数:空→该品种已确认默认;越界→clamp(15..100),不报错不崩(稿KMIN/KMAX)
        val n = curN()
        // 稿MK.mkt:cmdty用CNNAME中文名(有则"中文名 代码",无则代码),其余按类型前缀
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
        wantRestore = symChanged
        val seq = ++reqSeq
        if (type == "stock" || type == "hk" || type == "kr") {
            // 港/韩与美同走股票串行腿(稿§6);绝不落到下方商品(gold/silver/cmdty)兜底
            loadStockParallel(s, n, mkt, seq)
            return
        }
        if (type == "crypto") {
            loadCryptoParallel(s, n, mkt, seq)
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
        mkStatus("拉取中…", false)
        Thread {
            try {
                val vs = MktData.fetchMetals(ysym, tf, n)
                MktData.FX.fxify(vs, ysym) // 入表前折算(商品/金银=美元,此处为统一收口的空操作)
                val ag = MktData.aggregate(vs)
                act.runOnUiThread {
                    if (seq != reqSeq) return@runOnUiThread
                    renderAgg(ag, mkt, seq)
                }
            } catch (_: Exception) {
                act.runOnUiThread {
                    if (seq != reqSeq) return@runOnUiThread
                    mkStatus("行情拉取失败(网络或品种名不对)", true)
                    finishLoad(seq) // D4:失败也要消费标记,否则下次同品种不再回填
                }
            }
        }.start()
    }

    private fun renderAgg(ag: Agg, mkt: String, seq: Int) {
        if (seq != reqSeq) return
        lastKs = ag.ks
        lastSrc = ag.src
        lastMkt = mkt
        chart.setData(ag.ks)
        // 稿D2(843) srcNote=纯日期区间:去掉「 · 来源(初步·来源)」后缀;
        // 来源仍由图例「N根·源」承载(金银/商品=东财期货)
        srcNote.text = MktData.fmtDT(ag.ks.first().t) + "~" +
            MktData.fmtDT(ag.ks.last().t)
        // 稿mkhead(291)只保留30px现价;mkid品种名/mkChg涨跌徽标全树零命中,无需再删
        price.text = MktData.mkFmt(ag.ks.lastOrNull()?.c)
        paintFx()
        // D4:onInfo(linkCalc/linkPhigh)已经跑完,此刻再回填=保存值最终赢
        finishLoad(seq)
        paintTgt(ag, seq)
    }

    // v4§7 说明行三态:美元报价 / 1 USD=X CUR(汇率日期) / 暂时没取到保持原币
    private fun paintFx() {
        if (lastSym.isEmpty()) return
        fxRow.visibility = View.VISIBLE
        fxRow.text = MktData.FX.fxText(lastSym)
    }

    // 汇率异步到达:说明行立即重画;当前正显示的品种按新缓存键重载
    // (同汇率→缓存命中只重画不发请求;汇率变了→键变→重新抓并按新汇率折算;输入框已被改过则只重画不代载)
    fun fxArrived() {
        if (lastSym.isEmpty()) return
        paintFx()
        if (symInp.text.toString().trim().uppercase(Locale.US) == lastSym) mkLoad()
    }

    // 稿TGT:美股详情在图例行下显示机构目标均价(Nasdaq聚合);
    // 非股/拉取失败/已切换品种(seq过期)一律隐藏,行样式照稿.tgtRow(12sp colorSub)
    private fun paintTgt(ag: Agg, seq: Int) {
        if (lastType != "stock" || seq != reqSeq) {
            tgt.visibility = View.GONE
            return
        }
        val cur = ag.ks.lastOrNull()?.c ?: 0.0
        val sym = lastSym
        Thread {
            val r = MktData.fetchTgt(sym)
            act.runOnUiThread {
                if (seq != reqSeq || lastType != "stock") return@runOnUiThread
                if (r == null || cur <= 0) {
                    tgt.visibility = View.GONE
                    return@runOnUiThread
                }
                val pct = (r.avg - cur) / cur * 100
                tgt.text = "机构目标均价 " + MktData.mkFmt(r.avg) + "（" + r.n + "家 · " +
                    (if (pct >= 0) "高于" else "低于") + "当前价 " +
                    String.format(Locale.US, "%.2f", Math.abs(pct)) + "%）"
                tgt.visibility = View.VISIBLE
                // 稿PH(406-414):机构目标均价>现价且>当前最低价才写计算页最高价(同值不扰手改)
                act.linkPhigh(r.avg, cur)
            }
        }.start()
    }

    // ---------- 股票三源并行(Nasdaq+YahooDaily+东方财富)+首家先画 ----------
    private fun renderStock(v: VendorKs, mkt: String, seq: Int, prelim: String?) {
        if (seq != reqSeq) return
        prelimTag = prelim
        renderAgg(MktData.aggregate(listOf(v)), mkt, seq)
    }

    private fun loadStockParallel(s: String, n: Int, mkt: String, seq: Int) {
        MktData.MktCache.get(MktData.MktCache.key(s, tf, n))?.let { vs ->
            renderStock(MktData.pickStockWinner(vs), mkt, seq, null)
            return
        }
        mkStatus("拉取中…", false)
        // 稿§6:串行 东财→Nasdaq→腾讯→Yahoo,命中即停(常态只发1个请求);
        // 美/港/韩同走 fetchStocks,汇率折算(入表前)与缓存键带汇率沿用v4§7;
        // 全部失败才报「行情拉取失败」
        Thread {
            var res: List<VendorKs>? = null
            try {
                res = MktData.fetchStocks(s, tf, n)
            } catch (_: Exception) {
            }
            val out = res
            act.runOnUiThread {
                if (seq != reqSeq) return@runOnUiThread
                if (out == null || out.isEmpty()) {
                    mkStatus("行情拉取失败(网络或品种名不对)", true)
                    finishLoad(seq) // D4
                    return@runOnUiThread
                }
                MktData.FX.fxify(out, s)
                MktData.MktCache.put(MktData.MktCache.key(s, tf, n), out)
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

    private fun loadCryptoParallel(s: String, n: Int, mkt: String, seq: Int) {
        MktData.MktCache.get(MktData.MktCache.key(s, tf, n))?.let { vs ->
            renderCrypto(vs, mkt, seq, null)
            return
        }
        val legs = MktData.cryptoLegs(s)
        mkStatus("拉取中…", false)
        Thread {
            for (leg in legs) {
                if (seq != reqSeq) return@Thread
                var hit: VendorKs? = null
                try {
                    hit = MktData.fetchCryptoLeg(leg, s, tf, n)
                } catch (_: Exception) {
                }
                val v = hit
                if (v == null) continue // 本腿失败才发下一腿
                act.runOnUiThread {
                    if (seq != reqSeq) return@runOnUiThread
                    // 折算收口在入表前(v4§7),与股票腿同一位置
                    MktData.FX.fxify(listOf(v), s)
                    MktData.MktCache.put(MktData.MktCache.key(s, tf, n), listOf(v))
                    renderCrypto(listOf(v), mkt, seq, null) // 命中即停
                }
                return@Thread
            }
            act.runOnUiThread {
                if (seq != reqSeq) return@runOnUiThread
                mkStatus("行情拉取失败(网络或品种名不对)", true)
                finishLoad(seq) // D4
            }
        }.start()
    }
}
