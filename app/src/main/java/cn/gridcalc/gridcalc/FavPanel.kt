package cn.gridcalc.gridcalc

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
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

    fun tag(t: String): String = when (t) {
        "crypto" -> "币"
        "gold" -> "黄金"
        "silver" -> "白银"
        "stock" -> "美股"
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

    // 排序模式(稿§2):三键互斥 `dist|gain|ratio : asc|desc|off`;
    // 旧格式 off/asc/desc 兼容读成 dist:off/asc/desc,写回一律新格式
    fun getSort(c: Context): String {
        // v4 D10:SP 读 try/catch,坏值/异常一律回落 dist:off
        val v = try {
            prefs(c).getString(SORTKEY, "off")
        } catch (_: Exception) {
            null
        } ?: "off"
        if (v.contains(':')) {
            val p = v.split(":")
            if (p.size == 2 &&
                (p[0] == "dist" || p[0] == "gain" || p[0] == "ratio") &&
                (p[1] == "asc" || p[1] == "desc" || p[1] == "off")
            ) return v
            return "dist:off"
        }
        return when (v) {
            "asc" -> "dist:asc"
            "desc" -> "dist:desc"
            else -> "dist:off"
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
// 点行直达(行情页加载),长按600ms确认删除,搜索精确>前缀>包含排序,
// 已收藏剔除,＋后清场;行右距离列(距第一支撑pct+现价+涨跌色块),右上排序键循环。

class FavPanel(
    private val act: MainActivity,
    page: View,
    private val mkt: MktPanel,
    private val onPick: (String) -> Unit
) {

    private val searchInp: EditText = page.findViewById(R.id.fav_search)
    private val favList: LinearLayout = page.findViewById(R.id.fav_list)
    // 稿§2 三列头:距支撑104dp/收益率76dp/收益比62dp,与行内三格逐字对齐
    private val sortDist: Button = page.findViewById(R.id.fav_sort_dist)
    private val sortGain: Button = page.findViewById(R.id.fav_sort_gain)
    private val sortRatio: Button = page.findViewById(R.id.fav_sort_ratio)

    init {
        // 距离持久化层启动即挂上applicationContext,首帧repaint就能读到上次成功值
        FavDist.attach(act)
    }

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

    // 距离重试环(稿FAVRetry):行→胶囊映射用于行内逐条补数;
    // hidden=离开自选页即停表清计时器,distSeq 递增丢弃在途旧结果
    private val capsules = mutableMapOf<String, TextView>()
    // 稿§2 收益比列与行元数据映射(重绘清空,刷新按行重画)
    private val ratios = mutableMapOf<String, TextView>()
    private val metas = mutableMapOf<String, RowMeta>()
    private var retry: Runnable? = null
    private var hidden = true
    private var distSeq = 0

    private fun dp(v: Float): Float = act.resources.displayMetrics.density * v

    // 稿.favrow .fb-* 徽标配色(固定hex,不随主题)
    private fun badgeColor(t: String): Int = when (t) {
        "crypto" -> Color.parseColor("#f5a623")
        "gold" -> Color.parseColor("#b8860b")
        "silver" -> Color.parseColor("#7a8a99")
        "cmdty" -> Color.parseColor("#8e6bd8")
        else -> Color.parseColor("#1e80ff") // stock及未标类型
    }

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

    // 长按600ms确认删除(对标稿子touchstart 600ms+confirm,桌面右键同效);
    // MOVE按touch slop容差判"真移动"才取消——手指微抖/合成swipe的原地MOVE不该杀长按
    private fun armDelete(pick: View, s: String) {
        var lpT: Runnable? = null
        var fired = false
        var dx = 0f
        var dy = 0f
        val slop = android.view.ViewConfiguration.get(act).scaledTouchSlop
        pick.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    fired = false
                    dx = e.x
                    dy = e.y
                    lpT = Runnable {
                        fired = true
                        askDelete(s) { fired = false }
                    }.also { handler.postDelayed(it, 600) }
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val ddx = e.x - dx
                    val ddy = e.y - dy
                    if (ddx * ddx + ddy * ddy > (slop * slop).toFloat()) {
                        lpT?.let { handler.removeCallbacks(it) }
                        lpT = null
                    }
                    false
                }
                MotionEvent.ACTION_CANCEL -> {
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

    private fun fmtPct(v: Double): String =
        (if (v >= 0) "+" else "") + String.format(Locale.US, "%.2f", v) + "%"

    // 胶囊dc(照稿.i.dist):ok且pct>=0绿#0aa182=现价在第一支撑上方,
    // pct<0红#f23645=跌破(取价格上方最近支撑,负值);min-width104/pad9×12/r9/白字17粗;
    // 无数据'…'或失败why为纯文本15px ink无底色
    private fun paintDist(dc: TextView, d: DistR?): TextView {
        if (d != null && d.ok) {
            // 沿用旧值(时间明显陈旧)弱提示:超24小时带「·旧值」,防止误读为实时
            dc.text = fmtPct(d.pct) +
                if (d.ts > 0 && System.currentTimeMillis() - d.ts > FavDist.STALE_MS) "·旧值" else ""
            dc.setTextColor(Color.WHITE)
            dc.textSize = 17f
            dc.setTypeface(dc.typeface, Typeface.BOLD)
            dc.minWidth = dp(104f).toInt()
            dc.setPadding(dp(12f).toInt(), dp(9f).toInt(),
                dp(12f).toInt(), dp(9f).toInt())
            dc.background = GradientDrawable().apply {
                setColor(Color.parseColor(if (d.pct >= 0) "#0aa182" else "#f23645"))
                cornerRadius = dp(9f)
            }
        } else {
            dc.text = if (d == null) "…" else d.why
            dc.setTextColor(act.attrColor("colorInk"))
            dc.textSize = 15f
            dc.setTypeface(dc.typeface, Typeface.BOLD)
            dc.background = null
            dc.minWidth = 0
            dc.setPadding(0, 0, 0, 0)
        }
        return dc
    }

    // 自选行雪球式版式(照稿):行>pick>[名称列fcol(名称19px/700+fbadge徽标+代码),
    // 距离列rq>dc胶囊];点行直达行情,长按600ms删除
    private fun favRowView(f: FavItem, d: DistR?, m: RowMeta): View {
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2f).toInt(), dp(14f).toInt(),
                dp(2f).toInt(), dp(14f).toInt())
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
        val cn = MktSuggest.favName(f.s)
        val fcol = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val fname = TextView(act).apply {
            text = if (cn.isEmpty()) f.s else cn
            setTextColor(act.attrColor("colorInk"))
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
        }
        fcol.addView(fname)
        // 代码行(照稿):fsub恒在、徽标恒渲染,仅代码在favName非空时带
        val fsub = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
            p.topMargin = dp(3f).toInt()
            layoutParams = p
        }
        val bd = TextView(act).apply {
            text = FavStore.tag(f.t)
            setTextColor(Color.WHITE)
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(badgeColor(f.t))
                cornerRadius = dp(4f)
            }
            setPadding(dp(5f).toInt(), dp(1f).toInt(),
                dp(5f).toInt(), dp(1f).toInt())
        }
        fsub.addView(bd)
        if (cn.isNotEmpty()) {
            val code = TextView(act).apply {
                text = f.s
                setTextColor(act.attrColor("colorSub"))
                textSize = 13f
                val p = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
                p.leftMargin = dp(6f).toInt()
                layoutParams = p
            }
            fsub.addView(code)
        }
        fcol.addView(fsub)
        // 稿§2 右三格:距支撑104dp/收益率76dp/收益比62dp,固定格宽与列头逐字对齐
        val rq = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val dc = paintDist(TextView(act), d)
        dc.layoutParams = LinearLayout.LayoutParams(
            dp(104f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        dc.gravity = Gravity.CENTER
        rq.addView(dc)
        val gc = TextView(act).apply {
            textSize = 14f
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setTextColor(act.attrColor("colorInk"))
            layoutParams = LinearLayout.LayoutParams(
                dp(76f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            text = m.gain?.let { fmtPct(it) } ?: "—"
            gainTip(m)?.let { tooltipText = it } // 悬浮:收益率·净收益USD·保存时间
        }
        rq.addView(gc)
        val rc = TextView(act).apply {
            textSize = 14f
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                dp(62f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        paintRatioCell(rc, d, m)
        rq.addView(rc)
        pick.addView(fcol)
        pick.addView(rq)
        row.addView(pick)
        pick.setOnClickListener { onPick(f.s) }
        armDelete(pick, f.s)
        capsules[f.s] = dc
        ratios[f.s] = rc
        metas[f.s] = m
        return row
    }

    fun repaint() {
        favList.removeAllViews()
        capsules.clear()
        ratios.clear()
        metas.clear()
        val a = FavStore.get(act)
        if (a.isEmpty()) {
            favList.addView(note("暂无自选，在行情页输入品种后点 ☆ 收藏"))
            return
        }
        // 距离窗口读distWin:已提交冻结/未提交跟随周期控件+根数(稿favKey=distWin)
        val (tf, n) = mkt.distWin()
        data class Row(val it: FavItem, val d: DistR?, val m: RowMeta, val ratio: Double?)
        // 冷启动/断网首帧:display()兜底沿用持久化旧值,不先画无源;
        // 记录侧取该品种最新一条(latestRecOf,追加语义下读取取最新)
        val rows = a.map { f ->
            val d = FavDist.display(f.s, tf, n, FavDist.cached(f.s, tf, n))
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
        for (r in sorted) favList.addView(favRowView(r.it, r.d, r.m))
    }

    // 距离刷新+重试环(照稿refreshFavDist):读distWin窗;到一个补一个(行内逐条补数);
    // 全到齐后有失败且仍在自选页→5秒自动重拉;成功TTL5分钟/失败TTL5秒见FavDist。
    // bg=后台补抓轮(finishRefresh的5s重试走此路):本会话已成功的key直接复用现值、
    // 不再发请求(幂等),只补从未成功的行,直到全部成功环自停
    fun refreshFavDist(bg: Boolean = false) {
        retry?.let { handler.removeCallbacks(it) }
        retry = null
        val (tf, n) = mkt.distWin()
        val syms = FavStore.get(act).map { it.s }.distinct()
        if (syms.isEmpty()) return
        val seq = ++distSeq
        val total = syms.size
        val done = AtomicInteger(0)
        val bad = AtomicInteger(0)
        for (s in syms) {
            Thread {
                var r: DistR? = null
                val k = FavDist.key(s, tf, n)
                val shown = FavDist.cached(s, tf, n) ?: FavDist.lastOk(s, tf, n)
                if (bg && FavDist.doneOk(k) && shown != null) {
                    r = shown
                } else {
                    try {
                        // 稿§5 两条取数路径:后台环=fullSup重抓支撑;首帧=lightPx轻量刷价
                        r = if (bg) FavDist.fullSup(s, tf, n) else FavDist.lightPx(s, tf, n)
                    } catch (_: Exception) {
                    }
                }
                // 稿970:无支撑(soft,数据已到)≠拉取失败,不进5秒重试环
                if (r == null || (!r.ok && !r.soft)) bad.incrementAndGet()
                act.runOnUiThread {
                    if (seq != distSeq || hidden) return@runOnUiThread
                    val disp = FavDist.display(s, tf, n, r)
                    capsules[s]?.let { paintDist(it, disp) }
                    // 距支撑到达后收益比随新值重画(稿:说明行+三列同步)
                    ratios[s]?.let { rc -> metas[s]?.let { m -> paintRatioCell(rc, disp, m) } }
                    if (done.incrementAndGet() == total) finishRefresh(bad.get())
                }
            }.start()
        }
    }

    private fun finishRefresh(bad: Int) {
        // 稿:有失败&&列表非空&&仍在自选页 → FAVRetry=5s后重拉;
        // 重拉走bg补抓轮:已成功行幂等跳过,只补未成功行直到全齐
        if (bad > 0 && FavStore.get(act).isNotEmpty() && !hidden) {
            val r2 = Runnable { refreshFavDist(bg = true) }
            retry = r2
            handler.postDelayed(r2, 5000)
        }
        // 排序激活(非off)时按新到值重排(稿order!=='off'→paintFav)
        if (FavStore.getSort(act).substringAfter(':') != "off") repaint()
    }

    // 进自选页(稿showTab('fav')→refreshFavDist):重绘+重刷距离
    fun onShow() {
        hidden = false
        repaint()
        refreshFavDist()
    }

    // 离开自选页(稿else clearTimeout(FAVRetry)):停表清计时器,在途旧结果作废
    fun onHide() {
        hidden = true
        distSeq++
        retry?.let { handler.removeCallbacks(it) }
        retry = null
    }

    // 列头(照稿.favhead):"列名 ▲▼"双箭头常显;激活列名墨色+方向箭头主色,
    // 未激活#c2c6cf;12sp,三列宽104/76/62与行格对齐(见XML)
    private fun paintSortHead(b: Button, label: String, col: String) {
        val cur = FavStore.getSort(act)
        val active = cur.startsWith("$col:")
        val dir = cur.substringAfter(':', "off")
        val span = SpannableString("$label ▲▼")
        val gray = Color.parseColor("#c2c6cf")
        val up = if (active && dir == "asc") act.attrColor("colorPrimary") else gray
        val dn = if (active && dir == "desc") act.attrColor("colorPrimary") else gray
        val base = label.length + 1
        span.setSpan(ForegroundColorSpan(up), base, base + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        span.setSpan(ForegroundColorSpan(dn), base + 1, base + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        b.text = span
        b.setTextColor(if (active) act.attrColor("colorInk") else act.attrColor("colorSub"))
        b.textSize = if (col == "ratio") 11f else 12f // 62dp窄列11sp防裁切
    }

    private fun paintSortHeads() {
        paintSortHead(sortDist, "距支撑", "dist")
        paintSortHead(sortGain, "收益率", "gain")
        paintSortHead(sortRatio, "收益比", "ratio")
    }

    // 稿§2 循环:升→降→关;互斥(点谁谁生效);收益比首击即降序,另两列首击升序
    private fun cycleSort(col: String) {
        val cur = FavStore.getSort(act)
        val nx = if (cur.startsWith("$col:")) {
            when (cur.substringAfter(':')) {
                "asc" -> "$col:desc"
                "desc" -> "$col:off"
                else -> if (col == "ratio") "$col:desc" else "$col:asc"
            }
        } else {
            if (col == "ratio") "$col:desc" else "$col:asc"
        }
        FavStore.setSort(act, nx)
        paintSortHeads()
        repaint()
        refreshFavDist()
    }

    // 压缩格式(稿§2):|v|>=1e6→M(2位) >=1e5→K(1位) >=1e3→千分位整数 <1000→两位小数
    private fun fmtCompact(v: Double): String {
        val a = Math.abs(v)
        return when {
            a >= 1e6 -> String.format(Locale.US, "%.2fM", v / 1e6)
            a >= 1e5 -> String.format(Locale.US, "%.1fK", v / 1e3)
            a >= 1e3 -> String.format(Locale.US, "%,.0f", v)
            else -> String.format(Locale.US, "%.2f", v)
        }
    }

    private fun fmtRatio(v: Double): String = fmtCompact(v)

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

    // 稿§3 收益比 = 收益率(%) ÷ |距支撑|(%) —— 用收益率不能用金额(本金会放大比值);
    // 缺任一项(无记录/距支撑无源/距支撑=0)→null;老记录只有金额时降级用净收益(提示标明)
    private fun ratioOf(distPct: Double?, m: RowMeta): Double? {
        if (distPct == null || distPct == 0.0) return null
        val g = m.gain ?: m.net ?: return null
        return g / Math.abs(distPct)
    }

    private fun paintRatioCell(rc: TextView, d: DistR?, m: RowMeta) {
        val distPct = if (d != null && d.ok) d.pct else null
        val r = ratioOf(distPct, m)
        if (r == null) {
            rc.text = "—"
            rc.setTextColor(act.attrColor("colorInk"))
            rc.tooltipText = null
            return
        }
        rc.text = fmtRatio(r)
        // 正绿负红
        rc.setTextColor(if (r >= 0) act.attrColor("colorTeal") else act.attrColor("colorRed"))
        // D6:距支撑取绝对值——跌破支撑时 distPct 为负,公式本身用 |距支撑|,
        // 提示里若写「÷ 距支撑 -2.50%」会与「收益比 = 收益率 ÷ |距支撑|%」自相矛盾
        val distTxt = String.format(Locale.US, "%.2f", Math.abs(distPct!!))
        rc.tooltipText = "收益比 ${fmtRatio(r)} = " +
            (if (m.gain == null && m.net != null) "净收益(老记录降级) ${fmtCompact(m.net)}"
            else "收益率 ${fmtPct(m.gain!!)}") +
            " ÷ 距支撑 $distTxt%"
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
        paintSortHeads()
        // 稿§2 三键互斥:距支撑/收益率 首击升序→降序→关;收益比 首击即降序
        sortDist.setOnClickListener { cycleSort("dist") }
        sortGain.setOnClickListener { cycleSort("gain") }
        sortRatio.setOnClickListener { cycleSort("ratio") }
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
        refreshFavDist()
    }
}
