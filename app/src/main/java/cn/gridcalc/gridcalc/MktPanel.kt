package cn.gridcalc.gridcalc

import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
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

    private var tf = "W"
    private var reqSeq = 0
    private var lastKs: List<KLine> = emptyList()
    private var lastSrc = ""
    private var lastMkt = ""

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
        hideSug()
        mkLoad()
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
            override fun afterTextChanged(s: Editable?) = scheduleSug()
        })
        kcountInp.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                done(v) { if (symInp.text.toString().trim().isNotEmpty()) mkLoad() }
                true
            } else false
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
            val base = "POC " + MktData.mkFmt(info.poc) +
                " · VA " + MktData.mkFmt(info.val_) + "~" + MktData.mkFmt(info.vah) +
                " · " + info.count + "根 · " + lastSrc
            leg.text = if (info.legRow.isEmpty()) base else base + "\n" + info.legRow
            mkStatus("POC " + MktData.mkFmt(info.poc), false)
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
        mkStatus("拉取中…", false)
        val seq = ++reqSeq
        Thread {
            try {
                val vs = when (type) {
                    "crypto" -> MktData.fetchCrypto(s, tf, n)
                    "gold" -> MktData.fetchStock("GC=F", tf, n)
                    "silver" -> MktData.fetchStock("SI=F", tf, n)
                    else -> MktData.fetchNasdaq(s, tf, n)
                }
                val ag = MktData.aggregate(vs)
                val mkt = when (type) {
                    "crypto" -> "币 "
                    "gold" -> "黄金 "
                    "silver" -> "白银 "
                    else -> "美股 "
                } + s.trim().uppercase(Locale.US)
                act.runOnUiThread {
                    if (seq != reqSeq) return@runOnUiThread
                    lastKs = ag.ks
                    lastSrc = ag.src
                    lastMkt = mkt
                    chart.setData(ag.ks)
                    srcNote.text = mkt + " · " + ag.src + " · 可见 " + ag.ks.size +
                        " 根 · " + MktData.fmtDT(ag.ks.first().t) + "~" +
                        MktData.fmtDT(ag.ks.last().t)
                }
            } catch (_: Exception) {
                act.runOnUiThread {
                    if (seq != reqSeq) return@runOnUiThread
                    mkStatus("行情拉取失败(网络或品种名不对)", true)
                }
            }
        }.start()
    }
}
