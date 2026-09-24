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
    private var sbTop = 0 // 状态栏inset:真融合后垫在各页根ScrollView上(静止位),换页即补
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

    // ---------- 行情→计算软联动(稿L393-414:LINK/PH) ----------
    private var lkPrev: Pair<Double, Double>? = null // 上次联动写入的一对支撑位(sup1,sup2)
    private var lkCap: Double? = null // 目标爆仓价上限(sup2;×解除后同对不再回写)
    private var lkPh: Double? = null // 上次由机构目标均价写入的最高价(同值不扰手改)
    private lateinit var statLiqCard: View // 爆仓卡(超上限画红框)
    private lateinit var statCap: View // 目标上限行(默认收起)
    private lateinit var statCapv: TextView
    private lateinit var capX: Button
    private lateinit var calcErr: TextView // 稿#err 报错行

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
        // 稿liqOf守卫(494):仅校验上方有格子——触发价低于最低档时下方买格可为0
        // (仓位全由触发价处建仓构成、均价=触发价;实测GRPO 1.237~1.345/14格/触发1.29→币安1.199,我们1.18)
        if (m < 1) throw IllegalArgumentException("触发价上方要有格子")
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
        // 稿resetIdle:清报错行+爆仓卡红框(.stat.bad remove),爆仓价值色回墨
        showErr(null)
        if (::statLiqCard.isInitialized) statLiqCard.background = null
        statVals["liq"]?.setTextColor(attrColor("colorInk"))
    }

    private fun onCalc() {
        showErr(null) // 稿calc()首行clear:清上一轮目标上限报错
        try {
            val c = getNum(inp["C"]!!, "总投入")
            val l = getNum(inp["L"]!!, "杠杆倍率")
            if (l < 1) throw IllegalArgumentException("杠杆倍率必须≥1")
            val pl = getNum(inp["Pl"]!!, "最低价")
            val ph = getNum(inp["Ph"]!!, "最高价")
            val po = getNum(inp["Po"]!!, "触发价")
            val n = getNum(inp["N"]!!, "网格数量", true).toInt()
            val q = getNum(inp["q"]!!, "每格数量")
            if (c <= 0) throw IllegalArgumentException("总投入必须大于0")
            if (pl <= 0) throw IllegalArgumentException("最低价必须大于0")
            if (ph <= pl) throw IllegalArgumentException("最高价必须大于最低价")
            // ④触发价允许低于最低档(下方买格为0,calcGrid仅校验上方);仅拦非正数与≥最高价
            if (!(po > 0 && po < ph)) throw IllegalArgumentException("触发价必须在最低价和最高价之间")
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
            // 稿451-454:目标上限硬约束——爆仓价超上限:其余结果全部归零(稿resetIdle),
            // 只亮爆仓价、爆仓卡变红、报错「爆仓价高于目标值」,不画网格明细
            val cap = lkCap
            if (cap != null && r.liq != null && r.liq > cap) {
                idle()
                statVals["liq"]!!.text = fmt(r.liq!!)
                statVals["liq"]!!.setTextColor(red)
                markLiqBad()
                showErr("爆仓价高于目标值")
                return
            }
            statLiqCard.background = null
            statVals["liq"]!!.setTextColor(ink)
            hero.setTextColor(if (r.net >= 0) teal else red)
            hero.text = "%+.2f USDT".format(r.net)
            heroSub.setTextColor(sub)
            heroSub.text = "收益率 %+.2f%% · %d卖%d买 · 见底权益约%.2fU".format(
                r.roe, r.m, r.b, r.eqBottom)
            // ④稿582:强平价≤0显示0.00(不再显示负数);null=永不爆仓
            statVals["liq"]!!.text = r.liq?.let { if (it <= 0) "0.00" else fmt(it) } ?: "永不爆仓"
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
            // ⑥每次成功计算追加一条记录(稿609 logRec位置:目标上限超标路径已提前return,不记录)
            logRec(r, pl, ph, n, c, l, po, q, fee, mmr)
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

    // ---------- ⑥计算记录(稿615-656 logRec/recCSV/recExport/recClear:localStorage→SharedPreferences) ----------
    // 表头27列与稿RHEAD逐列一致;档位线列竖线分隔全量;上限2000条(超限从头剔除)。
    // 范围缩小(用户指令):App每格数量一律手填→数量来源恒「手填」;自动算数量列保留但恒空(与稿导出schema一致便于合并)。
    private val RECCAP = 2000
    private var recs = mutableListOf<org.json.JSONObject>()
    private lateinit var recCount: TextView
    private var pendingExport = false
    private val recPref get() = getSharedPreferences("gridcalc_records_v1", Context.MODE_PRIVATE)

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
            o.put("Pl", pl); o.put("Ph", ph); o.put("N", n); o.put("g", "geo")
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
        "买格", "卖格", "手续费率", "维持保证金率", "档位线"
    )

    private fun csvCell(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    // 稿recCSV(636):27列一一对应;liq null→永不爆仓、≤0→0.00;档位线竖线全量
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
                numStr(r.optDouble("fee")), numStr(r.optDouble("mmr")), r.optString("lines")
            )
            sb.append(row.joinToString(",") { csvCell(it) })
        }
        return sb.toString()
    }

    // 稿recExport(643):UTF-8 BOM + 文件名 gridcalc_yyyyMMdd_HHmm.csv → 公共下载目录
    private fun exportCsv() {
        if (recs.isEmpty()) {
            android.app.AlertDialog.Builder(this)
                .setMessage("还没有记录：先在计算页点一次「计算」")
                .setPositiveButton("确定", null).show()
            return
        }
        val name = "gridcalc_" +
            java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US).format(java.util.Date()) +
            ".csv"
        val data = "\uFEFF" + recCsv()
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            var ok = false
            try {
                val v = android.content.ContentValues()
                v.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                v.put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/csv")
                v.put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOWNLOADS)
                v.put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                val uri = contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use {
                        it.write(data.toByteArray(Charsets.UTF_8))
                        it.flush()
                    }
                    v.clear()
                    v.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                    contentResolver.update(uri, v, null, null)
                    ok = true
                }
            } catch (e: Exception) {
                ok = false
            }
            finishExport(ok)
        } else {
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                pendingExport = true
                requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 1001)
                return
            }
            var ok = false
            try {
                val dir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                java.io.File(dir, name).writeBytes(data.toByteArray(Charsets.UTF_8))
                ok = true
            } catch (e: Exception) {
                ok = false
            }
            finishExport(ok)
        }
    }

    private fun finishExport(ok: Boolean) {
        android.app.AlertDialog.Builder(this)
            .setMessage(if (ok) "已导出 ${recs.size} 条记录" else "导出失败")
            .setPositiveButton("确定", null).show()
    }

    // 稿recClear(652):二次确认后清空
    private fun clearRecords() {
        if (recs.isEmpty()) {
            android.app.AlertDialog.Builder(this).setMessage("没有记录")
                .setPositiveButton("确定", null).show()
            return
        }
        android.app.AlertDialog.Builder(this)
            .setMessage("确定清空全部 ${recs.size} 条记录？清空后无法恢复。")
            .setPositiveButton("确定") { _, _ ->
                recs.clear()
                recSave()
            }
            .setNegativeButton("取消", null)
            .show()
    }

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

    // 稿paintCap(394-396):爆仓卡第二行灰字「目标上限 X」,无上限收起
    private fun paintCap() {
        val c = lkCap
        if (c == null) {
            statCap.visibility = View.GONE
            statCapv.text = ""
        } else {
            statCap.visibility = View.VISIBLE
            statCapv.text = "目标上限 " + fmt(c)
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

    // 稿linkCalc(397-404):支撑位≥2才联动;同一对支撑原样返回不覆盖手改,值变才覆盖;
    // sup1→计算页最低价(JS toPrecision(8)=jsPrec);sup2→记为目标爆仓价上限并paintCap
    fun linkCalc(sup: List<Double>) {
        if (sup.size < 2) return
        val s1 = sup[0]
        val s2 = sup[1]
        val prev = lkPrev
        if (prev != null && prev.first == s1 && prev.second == s2) return
        lkPrev = Pair(s1, s2)
        inp["Pl"]!!.setText(MktData.jsPrec(s1))
        lkCap = s2
        paintCap()
    }

    // 稿linkPhigh(406-414):Nasdaq机构目标均价>现价且>当前最低价才写入计算页最高价;
    // 最低价非数字(NaN)放行;同值(LKph门)不打扰手改
    fun linkPhigh(avg: Double, cur: Double) {
        if (!(avg > 0) || !(cur > 0)) return
        if (avg <= cur) return
        val pv = inp["Pl"]!!.text.toString().trim().toDoubleOrNull()
        if (pv != null && !(avg > pv)) return
        if (lkPh == avg) return
        lkPh = avg
        inp["Ph"]!!.setText(MktData.jsPrec(avg))
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
        out.putString("fee", feeInp.text.toString())
        out.putString("mmr", mmrInp.text.toString())
        out.putBoolean("dbl", dbl)
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
            // 稿L383 showMkt(){… window.scrollTo(0,0)}:打开行情详情时复位自选页滚动,
            // 深滚后进详情→返回,自选静止位回垫高位 top≥136(A9)。仅此详情往返路径复位;
            // Tab往返走 showTab,稿L366-373无复位,不新增任何 Tab 复位逻辑。
            (favPage as? android.widget.ScrollView)?.scrollTo(0, 0)
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
        // 稿⑦返回键OnBackPressedDispatcher:仅行情详情可见时拦回自选列表,根Tab默认finish()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (body.indexOfChild(mktPage) >= 0) showTab("fav", ANIM_FROM_L)
                else finish()
            }
        })

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
        // 稿③LINK爆仓上限卡:第二行灰字「目标上限 X」+×解除
        // (稿capX: LKcap=null;paintCap;去红;清错;calc())
        statLiqCard = calcPage.findViewById(R.id.stat_liq_card)
        statCap = calcPage.findViewById(R.id.stat_cap)
        statCapv = calcPage.findViewById(R.id.stat_capv)
        calcErr = calcPage.findViewById(R.id.calc_err)
        capX = calcPage.findViewById(R.id.capX)
        capX.setOnClickListener {
            lkCap = null
            paintCap()
            statLiqCard.background = null
            statVals["liq"]?.setTextColor(attrColor("colorInk"))
            showErr(null)
            onCalc()
        }

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
        // ⑥设置页「数据」卡片:计数+导出CSV+清空记录(稿recLoad/recPaint/recExport/recClear)
        recCount = settingsPage.findViewById(R.id.rec_count)
        settingsPage.findViewById<Button>(R.id.rec_export).setOnClickListener { exportCsv() }
        settingsPage.findViewById<Button>(R.id.rec_clear).setOnClickListener { clearRecords() }
        recLoad()
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
