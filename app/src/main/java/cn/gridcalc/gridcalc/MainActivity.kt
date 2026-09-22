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

class MainActivity : Activity() {

    private lateinit var body: FrameLayout
    private lateinit var calcPage: View
    private lateinit var settingsPage: View
    private lateinit var mktPage: View
    private lateinit var mktPanel: MktPanel
    private lateinit var favPage: View
    private lateinit var favPanel: FavPanel
    private lateinit var tabFav: LinearLayout
    private lateinit var tabCalc: LinearLayout
    private lateinit var tabSetup: LinearLayout
    private lateinit var tabCalcIcon: ImageView
    private lateinit var tabCalcLabel: TextView
    private lateinit var tabSetupIcon: ImageView
    private lateinit var tabSetupLabel: TextView
    private lateinit var tabFavIcon: ImageView
    private lateinit var tabFavLabel: TextView

    private val inp = mutableMapOf<String, EditText>()
    private lateinit var feeInp: EditText
    private lateinit var mmrInp: EditText
    private lateinit var dblBtn: Button
    private var dbl = false
    private lateinit var hero: TextView
    private lateinit var heroSub: TextView
    private val statVals = mutableMapOf<String, TextView>()
    private lateinit var detSum: TextView
    private lateinit var rows: LinearLayout
    private lateinit var themeFollow: View
    private lateinit var themeLight: View
    private lateinit var themeDark: View

    private var tab = "fav"
    private var mode = "follow"

    companion object {
        const val ANIM_TAB = 0
        const val ANIM_FROM_R = 1
        const val ANIM_FROM_L = 2
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

    private fun prefs() = getSharedPreferences("gridcalc", Context.MODE_PRIVATE)

    private fun loadMode(): String {
        val m = prefs().getString("theme", "follow") ?: "follow"
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

    // ---------- 计算 ----------

    data class CalcResult(
        val m: Int, val b: Int, val lines: Int, val q0: Double,
        val cost0: Double, val sellT: Double, val net: Double,
        val roe: Double, val liq: Double?, val eqBottom: Double
    )

    private fun gridLines(pl: Double, ph: Double, n: Int): List<Double> {
        val r = (ph / pl).pow(1.0 / n)
        return (0..n).map { pl * r.pow(it.toDouble()) }
    }

    private fun calcGrid(
        c: Double, pl: Double, ph: Double, po: Double, n: Int, q: Double,
        fee: Double, mmr: Double, dbl: Boolean
    ): CalcResult {
        val eps = 1e-9
        val lines = gridLines(pl, ph, n)
        val sells = lines.filter { it > po + eps }
        val buys = lines.filter { it < po - eps }
        val m = sells.size
        val bc = buys.size
        if (m < 1 || bc < 1) throw IllegalArgumentException("触发价必须在最低价和最高价之间,且上下都要有格子")
        val sumS = sells.sum()
        val sumB = buys.sum()
        // 加倍建仓:初始持仓翻倍,卖出总额多出一份到顶全平(M·q·Ph)
        val q0 = if (dbl) 2 * m * q else m * q
        val cost0 = q0 * po
        val buyFee = cost0 * fee
        val sellT = if (dbl) q * sumS + m * q * ph else q * sumS
        val net = sellT - cost0 - buyFee - sellT * fee
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
                feesPaid += q * pb * fee
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
        val eqBottom = c + qf * (pl - avgF) - buyFee - sumB * q * fee
        return CalcResult(m, bc, n + 1, q0, cost0, sellT, net, net / c * 100.0,
            found, eqBottom)
    }

    private fun getNum(e: EditText, name: String, isInt: Boolean = false): Double {
        val t = e.text.toString().trim()
        if (t.isEmpty()) throw IllegalArgumentException("${name}还没有填")
        val v = t.toDoubleOrNull() ?: throw IllegalArgumentException("${name}不是有效数字")
        return if (isInt) v.toInt().toDouble() else v
    }

    private fun fmt(v: Double, d: Int = 2): String = "%,.${d}f".format(v)

    private fun idle() {
        hero.setTextColor(attrColor("colorInk"))
        hero.text = "--"
        heroSub.setTextColor(attrColor("colorSub"))
        heroSub.text = "输入参数后回车计算"
        statVals.values.forEach { it.text = "--" }
        detSum.setTextColor(attrColor("colorSub"))
        detSum.text = "明细会在计算后显示"
        rows.removeAllViews()
    }

    private fun onCalc() {
        try {
            val c = getNum(inp["C"]!!, "总投入")
            getNum(inp["L"]!!, "杠杆倍率").let { if (it < 1) throw IllegalArgumentException("杠杆倍率必须≥1") }
            val pl = getNum(inp["Pl"]!!, "最低价")
            val ph = getNum(inp["Ph"]!!, "最高价")
            val po = getNum(inp["Po"]!!, "触发价")
            val n = getNum(inp["N"]!!, "网格数量", true).toInt()
            val q = getNum(inp["q"]!!, "每格数量")
            if (c <= 0) throw IllegalArgumentException("总投入必须大于0")
            if (pl <= 0) throw IllegalArgumentException("最低价必须大于0")
            if (ph <= pl) throw IllegalArgumentException("最高价必须大于最低价")
            if (!(po > pl && po < ph)) throw IllegalArgumentException("触发价必须在最低价和最高价之间")
            if (n < 1) throw IllegalArgumentException("网格数量必须≥1")
            if (q <= 0) throw IllegalArgumentException("每格数量必须大于0")
            val fee = getNum(feeInp, "手续费率") / 100.0
            if (fee < 0) throw IllegalArgumentException("手续费率不能为负")
            val mmr = getNum(mmrInp, "维持保证金率") / 100.0
            if (mmr < 0) throw IllegalArgumentException("维持保证金率不能为负")

            val r = calcGrid(c, pl, ph, po, n, q, fee, mmr, dbl)
            val teal = attrColor("colorTeal")
            val red = attrColor("colorRed")
            val ink = attrColor("colorInk")
            val sub = attrColor("colorSub")
            hero.setTextColor(if (r.net >= 0) teal else red)
            hero.text = "%+.2f USDT".format(r.net)
            heroSub.setTextColor(sub)
            heroSub.text = "收益率 %+.2f%% · %d卖%d买 · 见底权益约%.2fU".format(
                r.roe, r.m, r.b, r.eqBottom)
            statVals["liq"]!!.text = r.liq?.let { fmt(it) } ?: "永不爆仓"
            statVals["roe"]!!.text = "%+.2f%%".format(r.roe)
            statVals["pos"]!!.text = "%.4f".format(r.q0)
            statVals["sell"]!!.text = fmt(r.sellT)
            detSum.setTextColor(ink)
            detSum.text = "买%d/卖%d/线%d · 持仓%.6f · 成本%.2f%s".format(
                r.b, r.m, r.lines, r.q0, r.cost0, if (dbl) " · 加倍建仓" else "")

            val buyFee = r.cost0 * fee
            var cumSell = 0.0
            rows.removeAllViews()
            rows.addView(makeRow("#", "网格价", null, "累计净收益", sub, false, true))
            var zebra = false
            val sorted = gridLines(pl, ph, n).filter {
                it < po - 1e-9 || it > po + 1e-9
            }.sorted()
            sorted.forEachIndexed { i, p ->
                if (p < po) {
                    rows.addView(makeRow(
                        "${i + 1}", fmt(p), "买入|buy", fmt(q * p),
                        sub, zebra, false))
                } else {
                    val amt = q * p
                    cumSell += amt
                    val cum = cumSell - r.cost0 - buyFee - cumSell * fee
                    rows.addView(makeRow(
                        "${i + 1}", fmt(p), "卖出|sell",
                        "%+.2f".format(cum),
                        if (cum >= 0) teal else red, zebra, false))
                }
                zebra = !zebra
            }
            if (dbl) {
                rows.addView(makeRow(
                    "顶", fmt(ph), "全平|sell",
                    "%+.2f".format(r.net),
                    if (r.net >= 0) teal else red, zebra, false))
            }
        } catch (e: Exception) {
            val msg = e.message ?: "出错"
            if ("还没有填" in msg) {
                idle()
                return
            }
            val red = attrColor("colorRed")
            hero.setTextColor(red)
            hero.text = "出错"
            heroSub.setTextColor(red)
            heroSub.text = msg
            statVals.values.forEach { it.text = "--" }
        }
    }

    private fun makeRow(
        idx: String, price: String, pill: String?, cum: String,
        cumColor: Int, zebra: Boolean, header: Boolean
    ): View {
        val v = LayoutInflater.from(this).inflate(R.layout.row_detail, rows, false)
        val tc = if (header) attrColor("colorFaint") else attrColor("colorInk")
        v.findViewById<TextView>(R.id.row_idx).apply {
            text = idx
            if (!header) setTextColor(tc)
        }
        v.findViewById<TextView>(R.id.row_price).apply {
            text = price
            if (!header) setTextColor(tc)
        }
        val dir = v.findViewById<TextView>(R.id.row_dir)
        if (pill == null) {
            dir.text = ""
        } else {
            val parts = pill.split("|")
            dir.text = parts[0]
            if (parts[1] == "buy") {
                dir.setBackgroundResource(R.drawable.pill_buy)
                dir.setTextColor(attrColor("colorTeal"))
            } else {
                dir.setBackgroundResource(R.drawable.pill_sell)
                dir.setTextColor(attrColor("colorPrimary"))
            }
        }
        v.findViewById<TextView>(R.id.row_cum).apply {
            text = cum
            setTextColor(cumColor)
        }
        if (zebra) v.setBackgroundColor(attrColor("colorSurf2"))
        return v
    }

    // ---------- 界面 ----------

    // 打孔屏适配(对标稿子viewport-fit=cover+safe-area-inset):edge-to-edge,
    // 状态栏/导航栏透明,内容区按systemBars insets垫高;浅色主题配深色状态栏图标。
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
            body.setPadding(0, top, 0, 0)
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
        body.removeAllViews()
        val v = when (name) {
            "mkt" -> mktPage
            "setup" -> settingsPage
            "fav" -> favPage
            else -> calcPage
        }
        body.addView(v)
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

    private fun paintTabs() {
        val primary = attrColor("colorPrimary")
        val sub = attrColor("colorSub")
        tabCalcIcon.setColorFilter(if (tab == "calc") primary else sub)
        tabCalcLabel.setTextColor(if (tab == "calc") primary else sub)
        tabSetupIcon.setColorFilter(if (tab == "setup") primary else sub)
        tabSetupLabel.setTextColor(if (tab == "setup") primary else sub)
        tabFavIcon.setColorFilter(if (tab == "fav") primary else sub)
        tabFavLabel.setTextColor(if (tab == "fav") primary else sub)
    }

    private fun paintThemeSeg() {
        val pairs = listOf(themeFollow to "follow", themeLight to "light", themeDark to "dark")
        val onPrimary = attrColor("colorOnPrimary")
        val ink = attrColor("colorInk")
        for ((btn, m) in pairs) {
            if (mode == m) {
                btn.setBackgroundResource(R.drawable.btn_primary)
                (btn as android.widget.Button).setTextColor(onPrimary)
            } else {
                btn.setBackgroundResource(R.drawable.btn_ghost)
                (btn as android.widget.Button).setTextColor(ink)
            }
        }
    }

    private fun setMode(m: String) {
        mode = m
        prefs().edit().putString("theme", m).apply()
        recreate()
    }

    private fun paintDbl() {
        val onPrimary = attrColor("colorOnPrimary")
        val ink = attrColor("colorInk")
        if (dbl) {
            dblBtn.setBackgroundResource(R.drawable.btn_primary)
            dblBtn.setTextColor(onPrimary)
        } else {
            dblBtn.setBackgroundResource(R.drawable.btn_ghost)
            dblBtn.setTextColor(ink)
        }
    }

    fun hideKeyboard(v: View) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(v.windowToken, 0)
        v.clearFocus()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("tab", tab)
        for ((k, e) in inp) out.putString("in_$k", e.text.toString())
        out.putString("fee", feeInp.text.toString())
        out.putString("mmr", mmrInp.text.toString())
        out.putBoolean("dbl", dbl)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
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
        edgeToEdge(actual != "dark")

        body = findViewById(R.id.body)
        val inf = LayoutInflater.from(this)
        calcPage = inf.inflate(R.layout.page_calc, body, false)
        settingsPage = inf.inflate(R.layout.page_settings, body, false)
        mktPage = inf.inflate(R.layout.page_mkt, body, false)
        mktPanel = MktPanel(this, mktPage)
        favPage = inf.inflate(R.layout.page_fav, body, false)
        favPanel = FavPanel(this, favPage, mktPanel) { s ->
            mktPanel.setSym(s)
            showTab("mkt", ANIM_FROM_R, "fav")
            (mktPage as? android.widget.ScrollView)?.scrollTo(0, 0)
            mktPanel.mkLoad()
        }
        mktPanel.onFavChanged = { favPanel.repaint() }
        // 点▾确认=提交距离窗口+重刷自选距离+收菜单(稿winGo;收菜单在MktPanel内)
        mktPanel.onDistCommitted = { favPanel.refreshFavDist() }
        mktPage.findViewById<Button>(R.id.mkt_back).setOnClickListener {
            showTab("fav", ANIM_FROM_L)
        }

        tabFav = findViewById(R.id.tab_fav)

        tabCalc = findViewById(R.id.tab_calc)
        tabSetup = findViewById(R.id.tab_setup)
        tabCalcIcon = findViewById(R.id.tab_calc_icon)
        tabCalcLabel = findViewById(R.id.tab_calc_label)
        tabSetupIcon = findViewById(R.id.tab_setup_icon)
        tabSetupLabel = findViewById(R.id.tab_setup_label)
        tabFavIcon = findViewById(R.id.tab_fav_icon)
        tabFavLabel = findViewById(R.id.tab_fav_label)
        tabCalc.setOnClickListener { showTab("calc") }
        tabSetup.setOnClickListener { showTab("setup") }
        tabFav.setOnClickListener { showTab("fav") }

        val ids = mapOf("C" to R.id.in_C, "L" to R.id.in_L, "Pl" to R.id.in_Pl,
            "Ph" to R.id.in_Ph, "Po" to R.id.in_Po, "N" to R.id.in_N,
            "q" to R.id.in_q)
        for ((k, id) in ids) {
            val e = calcPage.findViewById<EditText>(id)
            inp[k] = e
            // 回车才算:输入即算已去掉,IME 回车触发并收键盘
            e.setOnEditorActionListener { v, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    hideKeyboard(v)
                    onCalc()
                    true
                } else false
            }
        }
        hero = calcPage.findViewById(R.id.hero)
        heroSub = calcPage.findViewById(R.id.hero_sub)
        statVals["liq"] = calcPage.findViewById(R.id.stat_liq)
        statVals["roe"] = calcPage.findViewById(R.id.stat_roe)
        statVals["pos"] = calcPage.findViewById(R.id.stat_pos)
        statVals["sell"] = calcPage.findViewById(R.id.stat_sell)
        detSum = calcPage.findViewById(R.id.det_sum)
        rows = calcPage.findViewById(R.id.rows)

        feeInp = settingsPage.findViewById(R.id.in_fee)
        mmrInp = settingsPage.findViewById(R.id.in_mmr)
        for (e in listOf(feeInp, mmrInp)) {
            e.setOnEditorActionListener { v, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    hideKeyboard(v)
                    onCalc()
                    true
                } else false
            }
        }
        dblBtn = calcPage.findViewById(R.id.dbl_btn)
        dblBtn.setOnClickListener {
            dbl = !dbl
            paintDbl()
            onCalc()
        }
        paintDbl()
        themeFollow = settingsPage.findViewById(R.id.theme_follow)
        themeLight = settingsPage.findViewById(R.id.theme_light)
        themeDark = settingsPage.findViewById(R.id.theme_dark)
        themeFollow.setOnClickListener { setMode("follow") }
        themeLight.setOnClickListener { setMode("light") }
        themeDark.setOnClickListener { setMode("dark") }
        paintThemeSeg()

        savedInstanceState?.let { b ->
            for ((k, e) in inp) b.getString("in_$k")?.let { e.setText(it) }
            b.getString("fee")?.let { feeInp.setText(it) }
            b.getString("mmr")?.let { mmrInp.setText(it) }
            dbl = b.getBoolean("dbl", false)
            paintDbl()
        }
        watchKeyboard()
        // v3.4起行情Tab已删:旧存档的mkt归一到fav
        val startTab = savedInstanceState?.getString("tab")?.takeIf {
            it == "fav" || it == "calc" || it == "setup"
        } ?: "fav"
        showTab(startTab)
    }
}
