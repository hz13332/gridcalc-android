package cn.gridcalc.gridcalc

import android.view.View
import android.view.ViewGroup
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
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.TextView
import java.util.Locale

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
    private val chart: MktView = page.findViewById(R.id.mkt_chart)
    private val favBtn: Button = page.findViewById(R.id.mkt_fav)

    // 自选变更(收藏/删除)时通知自选页重绘;由MainActivity接线
    var onFavChanged: (() -> Unit)? = null

    private var tf = "M"
    private var reqSeq = 0
    private var lastKs: List<KLine> = emptyList()
    private var lastSrc = ""
    private var lastMkt = ""
    // 首家先画注记:非空时状态行显示"POC x（初步·源）",终画前清掉
    private var prelimTag: String? = null

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

    fun setSym(s: String) {
        symInp.setText(s)
        symInp.setSelection(s.length)
        paintStar()
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
        goBtn.setOnClickListener { pickOrLoad() }
        favBtn.setOnClickListener { toggleFav() }
        paintStar()
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
            mkStatus((if (sup.isNotEmpty()) "支撑 " + MktData.mkFmt(sup[0])
            else "暂无支撑") + (prelimTag ?: ""), false)
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

    private fun mkStatus(t: String, err: Boolean) {
        status.text = t
        status.setTextColor(act.attrColor(if (err) "colorRed" else "colorSub"))
    }

    fun mkLoad() {
        hideSug()
        val s = symInp.text.toString()
        if (s.trim().isEmpty()) {
            mkStatus("先输入品种", true)
            return
        }
        val count = kcountInp.text.toString().trim().toDoubleOrNull()
        if (count == null || !(count >= 5)) {
            mkStatus("K线数量至少填5", true)
            return
        }
        val n = minOf(100, count.toInt())
        val type = MktData.symType(s)
        val mkt = when (type) {
            "crypto" -> "币 "
            "gold" -> "黄金 "
            "silver" -> "白银 "
            else -> "美股 "
        } + s.trim().uppercase(Locale.US)
        prelimTag = null
        val seq = ++reqSeq
        if (type == "stock") {
            loadStockParallel(s, n, mkt, seq)
            return
        }
        if (type == "crypto") {
            loadCryptoParallel(s, n, mkt, seq)
            return
        }
        mkStatus("拉取中…", false)
        Thread {
            try {
                val vs = if (type == "gold") MktData.fetchYahooDaily("GC=F", tf, n)
                else MktData.fetchYahooDaily("SI=F", tf, n)
                val ag = MktData.aggregate(vs)
                act.runOnUiThread {
                    if (seq != reqSeq) return@runOnUiThread
                    renderAgg(ag, mkt, seq)
                }
            } catch (_: Exception) {
                act.runOnUiThread {
                    if (seq != reqSeq) return@runOnUiThread
                    mkStatus("行情拉取失败(网络或品种名不对)", true)
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
        srcNote.text = mkt + " · " + ag.src + " · 可见 " + ag.ks.size +
            " 根 · " + MktData.fmtDT(ag.ks.first().t) + "~" +
            MktData.fmtDT(ag.ks.last().t)
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
        val lock = Any()
        val total = 3
        val got = mutableListOf<VendorKs>()
        var done = 0
        var stage = 0 // 0未画 1已初画 2已终画
        fun onArrival(vs: List<VendorKs>?) {
            var toRender: Pair<VendorKs, String?>? = null
            var fail = false
            var toCache: List<VendorKs>? = null
            synchronized(lock) {
                if (seq != reqSeq) return
                if (vs != null && vs.isNotEmpty()) got.addAll(vs)
                done++
                if (done >= total && got.isNotEmpty()) {
                    stage = 2
                    toRender = MktData.pickStockWinner(got) to null
                } else if (got.isNotEmpty() && stage == 0) {
                    stage = 1
                    toRender = got[0] to
                        ("（初步·" + MktData.vendorName(got[0].v) + "）")
                }
                if (done >= total && got.isNotEmpty()) toCache = got.toList()
                fail = done >= total && got.isEmpty()
            }
            toCache?.let { MktData.MktCache.put(MktData.MktCache.key(s, tf, n), it) }
            if (fail) {
                act.runOnUiThread {
                    if (seq != reqSeq) return@runOnUiThread
                    mkStatus("行情拉取失败(网络或品种名不对)", true)
                }
                return
            }
            toRender?.let { (v, tag) ->
                act.runOnUiThread { renderStock(v, mkt, seq, tag) }
            }
        }
        Thread {
            try {
                onArrival(MktData.fetchNasdaq(s, tf, n))
            } catch (_: Exception) {
                onArrival(null)
            }
        }.start()
        Thread {
            try {
                onArrival(MktData.fetchYahooDaily(s, tf, n))
            } catch (_: Exception) {
                onArrival(null)
            }
        }.start()
        Thread {
            try {
                onArrival(MktData.fetchEastmoney(s, tf, n))
            } catch (_: Exception) {
                onArrival(null)
            }
        }.start()
    }

    // ---------- 币多腿并行(BN/OK/BB/GT/KU/MX/BTC限定GK)+首家先画 ----------
    // 到齐按报价成交额qv优胜重算(aggregate),与稿子first/all一致
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
        val lock = Any()
        val total = legs.size
        val got = mutableListOf<VendorKs>()
        var done = 0
        var stage = 0 // 0未画 1已初画 2已终画
        fun onArrival(v: VendorKs?) {
            var toRender: Pair<List<VendorKs>, String?>? = null
            var fail = false
            var toCache: List<VendorKs>? = null
            synchronized(lock) {
                if (seq != reqSeq) return
                if (v != null) got.add(v)
                done++
                if (done >= total && got.isNotEmpty()) {
                    stage = 2
                    toRender = got.toList() to null
                } else if (got.isNotEmpty() && stage == 0) {
                    stage = 1
                    toRender = listOf(got[0]) to
                        ("（初步·" + MktData.vendorName(got[0].v) + "）")
                }
                if (done >= total && got.isNotEmpty()) toCache = got.toList()
                fail = done >= total && got.isEmpty()
            }
            toCache?.let { MktData.MktCache.put(MktData.MktCache.key(s, tf, n), it) }
            if (fail) {
                act.runOnUiThread {
                    if (seq != reqSeq) return@runOnUiThread
                    mkStatus("行情拉取失败(网络或品种名不对)", true)
                }
                return
            }
            toRender?.let { (vs, tag) ->
                act.runOnUiThread { renderCrypto(vs, mkt, seq, tag) }
            }
        }
        for (leg in legs) {
            Thread {
                try {
                    onArrival(MktData.fetchCryptoLeg(leg, s, tf, n))
                } catch (_: Exception) {
                    onArrival(null)
                }
            }.start()
        }
    }
}
