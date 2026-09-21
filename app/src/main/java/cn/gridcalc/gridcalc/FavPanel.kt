package cn.gridcalc.gridcalc

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

// ---------- 自选收藏 · 存储层(对标稿子localStorage gc_fav) ----------

data class FavItem(val s: String, val t: String)

object FavStore {
    private const val PF = "gridcalc_fav"
    private const val KEY = "gc_fav"

    fun tag(t: String): String = when (t) {
        "crypto" -> "币"
        "gold" -> "黄金"
        "silver" -> "白银"
        "stock" -> "美股"
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

    private fun set(c: Context, a: List<FavItem>) {
        val j = JSONArray()
        for (it in a) j.put(JSONObject().put("s", it.s).put("t", it.t))
        prefs(c).edit().putString(KEY, j.toString()).apply()
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
}

// ---------- 自选页 · 面板接线(对标稿子page-fav/paintFav/favSearch) ----------
// 点行直达(行情页加载),长按600ms确认删除,搜索精确>前缀>包含排序,
// 已收藏剔除,＋后清场。

class FavPanel(private val act: MainActivity, page: View, private val onPick: (String) -> Unit) {

    private val searchInp: EditText = page.findViewById(R.id.fav_search)
    private val favList: LinearLayout = page.findViewById(R.id.fav_list)

    // 搜索结果浮层(对标稿子#favResults绝对定位浮层,不挤占列表;滚动条隐藏)
    private val resultsBox: LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(10f).toInt()
        setPadding(p, dp(4f).toInt(), p, dp(4f).toInt())
    }
    private var resPopup: PopupWindow? = null

    private val handler = Handler(Looper.getMainLooper())
    private var fsRunnable: Runnable? = null
    private var fsGen = 0

    private fun dp(v: Float): Float = act.resources.displayMetrics.density * v

    private fun note(t: String): TextView = TextView(act).apply {
        text = t
        setTextColor(act.attrColor("colorSub"))
        textSize = 12f
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    // 行容器:左pick(品种+角标)右可选＋;go非空时点行走go(结果浮层先收起再直达)
    private fun rowView(s: String, tag: String, add: (() -> Unit)?, go: (() -> Unit)? = null): View {
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = dp(2f).toInt()
            setPadding(0, dp(10f).toInt(), 0, dp(10f).toInt())
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
            textSize = 17f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val tg = TextView(act).apply {
            text = tag
            setTextColor(act.attrColor("colorSub"))
            textSize = 10f
            val p = dp(8f).toInt()
            setPadding(p, dp(2f).toInt(), p, dp(2f).toInt())
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.leftMargin = dp(10f).toInt()
            layoutParams = lp
        }
        pick.addView(sym)
        pick.addView(tg)
        row.addView(pick)
        if (add != null) {
            val ad = Button(act).apply {
                text = "＋"
                textSize = 14f
                setTextColor(act.attrColor("colorPrimary"))
                setBackgroundResource(R.drawable.btn_ghost)
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.leftMargin = dp(10f).toInt()
                layoutParams = lp
            }
            ad.setOnClickListener { add() }
            row.addView(ad)
        }
        pick.setOnClickListener { (go ?: { onPick(s) }).invoke() }
        return row
    }

    // 长按600ms确认删除(对标稿子touchstart 600ms+confirm,桌面右键同效)
    private fun armDelete(pick: View, s: String) {
        var lpT: Runnable? = null
        var fired = false
        pick.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    fired = false
                    lpT = Runnable {
                        fired = true
                        askDelete(s) { fired = false }
                    }.also { handler.postDelayed(it, 600) }
                    false
                }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_CANCEL -> {
                    lpT?.let { handler.removeCallbacks(it) }
                    lpT = null
                    false
                }
                MotionEvent.ACTION_UP -> {
                    lpT?.let { handler.removeCallbacks(it) }
                    lpT = null
                    if (fired) {
                        fired = false
                        true
                    } else false
                }
                else -> false
            }
        }
    }

    private fun askDelete(s: String, onDismiss: () -> Unit) {
        AlertDialog.Builder(act)
            .setMessage("删除自选 $s？")
            .setPositiveButton("删除") { _, _ ->
                FavStore.remove(act, s)
                repaint()
            }
            .setNegativeButton("取消", null)
            .setOnDismissListener { onDismiss() }
            .show()
    }

    fun repaint() {
        favList.removeAllViews()
        val a = FavStore.get(act)
        if (a.isEmpty()) {
            favList.addView(note("暂无自选，在行情页输入品种后点 ☆ 收藏"))
            return
        }
        for (it in a) {
            val row = rowView(it.s, FavStore.tag(it.t), null)
            // pick是行内第一个子View
            armDelete((row as LinearLayout).getChildAt(0), it.s)
            favList.addView(row)
        }
    }

    private fun hideResults() {
        // 世代+1:浮层关闭后旧异步回包一律丢弃(对标稿子FSGen++)
        fsGen++
        fsRunnable?.let { handler.removeCallbacks(it) }
        resPopup?.dismiss()
        resPopup = null
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
                onPick(it.s)
            })
            resultsBox.addView(row)
        }
        // 浮层锚定搜索框,高按内容量最高260dp(对标稿子max-height)
        resPopup?.dismiss()
        (resultsBox.parent as? ViewGroup)?.removeView(resultsBox)
        val scroll = ScrollView(act).apply {
            isVerticalScrollBarEnabled = false
            addView(resultsBox)
        }
        val w = searchInp.width
        if (w <= 0) return
        scroll.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val h = minOf(scroll.measuredHeight, dp(260f).toInt())
        if (h <= 0) return
        val pw = PopupWindow(scroll, w, h, false)
        pw.setBackgroundDrawable(
            act.resources.getDrawable(R.drawable.card_bg, act.theme))
        pw.elevation = dp(8f)
        pw.isOutsideTouchable = true
        resPopup = pw
        pw.showAsDropDown(searchInp, 0, dp(4f).toInt())
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
        searchInp.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = scheduleSearch()
        })
        // 失焦150ms后收浮层(对标稿子blur,让位于＋/点选)
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
    }
}
