package cn.gridcalc.gridcalc

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.MathContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// ---------- 行情 VPVR · 数据层(零外部依赖,HttpURLConnection+org.json) ----------
// 逻辑照搬设计稿:币三家现货按报价成交额qv选量最大一家,股走Nasdaq,
// 金银走Yahoo期货(GC=F/SI=F),
// tf映射 W:1w/1W/W M:1M/1M/M Q:取1M后三月合成季,
// limit按K数,Q按3倍截100,Yahoo按根数倒推range,
// Nasdaq按天数倒推fromdate后日线重采样,≥1家即画。

data class KLine(
    val t: Long, val o: Double, val h: Double,
    val l: Double, val c: Double, val v: Double, val qv: Double,
    // v4§7 美元口径:判重标记打在每根K线上(首画/终画共享同批对象,打数组上会二次折算、价格越刷越小)
    var usd: Boolean = false,
    /**
     * ⚠⚠⚠ 2026-10-10 **拆股（前复权）口径判重标记** —— 与上面的 [usd] 同构、同理由：
     *   标记打在**每一根** K 线上，因为同一批对象会在多个层之间按引用共享
     *   （MktCache / LastChart / MktPanel 的 lastKs），打在数组上就会二次变换。
     *
     * 【用户报的现象】「取支撑的数据很奇怪。每次取完数据之后，就和之前保存的数据相差很大。」
     *
     * 【根因：同一批 K 线被**复权两次**，而四条取数路径的复权次数不一致】
     *   全项目只有 [aggregate] 会调 [SplitBook.apply]，但四条路径喂给它的东西口径不同：
     *     · 网络路径（FavDist.fetchAndDerive 第 4 级）：喂 fetchStocks 的**原始**K 线 → 复权 1 次 ✅
     *     · MktCache.cachedFor / MktSave.saved：喂的也是**复权之前**的原始 K 线
     *       （写盘顺序 `FX.fxify` → `MktCache.put` → `MktSave.store` → `renderStock(aggregate(...))`）
     *       → 复权 1 次 ✅
     *     · **LastChart**：MktPanel.renderAgg 放进去的是 `aggregate()` **之后**的 `show`
     *       → FavDist 再 aggregate 一次 = **复权 2 次** ❌
     *   而 [SplitBook.apply] **不是幂等的**（它按比例除价、乘量，再来一次就再除一次），
     *   于是同一品种同一周期，走 LastChart 与走网络/缓存会算出**不同的支撑位**。
     *
     * 【为什么复权两次数值真的会跳 —— 差异不会被"自动判定"约掉】
     *   ⚠ 墓碑 · 2026-10-12 用户裁定选 A：profileOf 里的尺度带 [现价/3, 现价×3] 已整段
     *   删除（裁定原文见 [profileOf] 的墓碑注释）。下面这组 2026-10-10 的离线数值是
     *   **尺度带还在时**量到的，保留作历史记录；「带内集合」指的已是一个**不存在的**
     *   过滤器，别拿它推导当前行为。
     *   拆股日落在窗口**之内**时只有一部分柱子被除，序列成了两段尺度；再除一次会把
     *   前一段再除一次 ⟹ 数值就跳。离线实测（22 根拆股前 + 18 根拆股后、窗口取最后
     *   20 根，**当时尺度带在场**）：
     *     r=3  → 复权 1 次 sup=0.606250%，复权 2 次 sup=2.554861%
     *     r=4  → 0.752083% vs 1.289583%
     *     r=5  → 0.897917% vs 1.289583%
     *     r=10 → 1.435417% vs 1.289583%
     *   ⟹ 这就是「复用一次、真抓一次，数值就跳一下」。
     *
     * 【修法：判重标记，而不是改调用方】[SplitBook.apply] 出口给每根打上本标记；
     *   入口若发现**整批**都已标记就直接原样返回。这样：
     *   · 同一批数据不论被 aggregate 几次，复权**恰好生效一次** ⟹ 四条路径必然同值；
     *   · 各调用方**一个字都不用改**（网络/缓存/LastChart 全走同一条 aggregate）；
     *   · 与 [usd] 的修法同构，读代码的人一眼能看出是同一类问题。
     *   ⚠ **不引入任何网络请求**（用户 2026-10-10：「抓取行情信息不要频繁打，不要被限流」）：
     *     标记只在内存对象上，落盘格式不变（[MktSave.saved] 读回来一律 adj=false，
     *     因为盘里躺的本来就是复权前的数据 —— 这与它硬写 `usd = true` 的理由正好相反）。
     */
    var adj: Boolean = false
)

data class VendorKs(val v: String, var k: List<KLine>)

data class Agg(val ks: List<KLine>, val src: String)

data class Profile(
    val rv: DoubleArray, val ru: DoubleArray, val rd: DoubleArray,
    val vaUp: Int, val vaDn: Int,
    val lo: Double, val hi: Double,
    val poc: Double, val vah: Double, val val_: Double,
    val sup: List<Double>,
    /**
     * ⚠⚠⚠ 2026-10-10 **本 Profile 的「那一份 K 线」** —— `lo/hi`、`rv/ru/rd`、`sup`
     *   全部由**它**算出，绘制侧也必须画**它**。调用方（MktView）拿到的原始列表**不是**这一份。
     *
     * 【用户报的现象 · 2026-10-10 真机截图】行情页 K 线有柱子画到绘图区上/下边界之外。
     *   用户原话：「k线会掉出行情图显示的范围之外」「行情的上下没有变价」。
     *
     * 【根因 · 绘制集合与量程集合**不同源**】
     *   · `profileOf()` 先做一级过滤（clean 非法柱；2026-10-12 起**只剩这一级**，见下），
     *     然后**只拿过滤后的 ks** 算 lo/hi（见本文件 profileOf 里的 lo/hi 循环）；
     *   · 而 MktView.onDraw 画的是 `setData()` 收到的**未过滤**列表，`y()` 又按
     *     `vp.lo/vp.hi` 线性映射且**不做钳位** ⟹ 混进来的脏柱（l≤0 那种）照样被画，
     *     y 坐标直接落到绘图区之外。
     *
     * 【方案 (a)：把过滤后的集合随 Profile 一起交出去，绘制侧画这一份】
     *   理由：① 过滤逻辑**只存在一处**（就在 profileOf 里），绘制层不复制、不重算，
     *     本文件已有 `onFill()` 两份拷贝的血泪注释，不允许再出现第二份；
     *   ② ⚠ 墓碑 · 2026-10-12 用户裁定选 A：本条原写「跨尺度柱本来就该剔除 ——
     *     它与现价不在同一个价格尺度上……这正是 profileOf 当初加尺度带的原因」，
     *     该理由随尺度带**一并作废**：按绝对价格猜「这根是不是拆股前的」就是自动判定
     *     拆股，用户已裁定不做（裁定原文见 [profileOf]）。现在 ks = clean，只挡脏数据，
     *     **一根合法柱子都不剔**；
     *   ③ 未采用「保留全部柱子 + y() 钳位」：钳位只是把越界柱**贴在边界上画**，
     *     既没消除「两侧又不同源了」这个 bug，又会把它藏起来
     *     （下次再犯就查不出来），而同源是**结构性**保证：lo = min(l)、hi = max(h)
     *     就是这一份的极值 ⟹ y() 对画出来的每一根恒有 y ∈ [绘图区上沿, 下沿]。
     *
     * ⚠ 本字段**不引入任何网络请求**（用户 2026-10-10：「抓取行情信息不要频繁打，不要被限流」）：
     *   它只是把已经算过的那一份列表多带一个引用，绘制层照旧只消费取回来的数据。
     * ⚠ 退化分支（有效柱 < 3）也遵守同一条：`ks` 与 lo/hi 必须来自**同一份** clean。
     */
    val ks: List<KLine> = emptyList()
    /*
     * ⚠ 墓碑 · 2026-10-12 —— 这里原是 `hidden: Int`（「被尺度带整根剔除、没上屏的根数」，
     *   t3 于 2026-10-12 为统计剔除数而加，同日随用户裁定选 A 作废）。
     *   它经 MktView 透传成 MktInfo.hidden、再在 MktPanel 拼成「·N 根中 M 根跨尺度已隐藏」；
     *   尺度带一删，被藏的根数恒为 0，整条 Profile.hidden → MktInfo.hidden → MktPanel
     *   拼接链**全部删除**，不留死代码。裁定原文见 [profileOf] 的墓碑注释。
     */
) {
    val pocRow: Int get() {
        var bi = 0
        for (i in rv.indices) if (rv[i] > rv[bi]) bi = i
        return bi
    }
}

object MktData {

    private const val UA = "Mozilla/5.0 (Linux; Android 13) GridCalc/2.2"

    /**
     * t44【F-2c③ 取数与显示分离】**取数档位**。
     *
     * 240 = `aggregate()` 既有的显示天花板（见本文件 `aggregate()` 里的 `takeLast(240)`）。
     * ⚠️ **240 是「天花板」，不是「承诺」**：取 240 根回来，`aggregate()` 原样吐 240；
     * 取更多回来也会被那道 `takeLast(240)` 截掉。所以本常量**故意等于** 240 而不是更大 ——
     * 取一个比它大的数只会让调用方以为取到了那么多。
     *
     * 为什么不抬那道 240：它同时是 `FavDist` 距支撑链路的取数窗口，而那段正在被别人改；
     * 而且用户可填的最大根数是 100（`MktPanel.KMAX`，SPEC 钉死的 15~100），
     * 240 对当前需求绰绰有余 —— 要的是「**改数量不必重取**」，不是「取更多」。
     *
     * 职责划分：**取数**按本常量取满，**显示**按用户填的 `curN()` 切最后 N 根（见 MktPanel.renderAgg）。
     * 两者分开后，改根数不再改缓存键、也不再重新发请求。
     */
    const val FETCH_N = 240

    fun symType(s: String): String {
        // ⚠ 2026-10-07 Yahoo搜出来的EURUSD=X这类后缀先剥掉再判，否则掉进stock拿百度查，恒无源。
        var u = (s ?: "").trim().uppercase(Locale.US)
        if (u.endsWith("=X")) u = u.dropLast(2)
        if (u.isEmpty()) return ""
        // 稿序:*00Y 优先识别为商品(金银期货码 GC00Y/SI00Y 也归商品线)
        if (Regex("00Y$").containsMatchIn(u)) return "cmdty"
        if (u.contains("XAU") || u.contains("GOLD") || u.startsWith("GC=F")) return "gold"
        if (u.contains("XAG") || u.contains("SILVER") || u.startsWith("SI=F")) return "silver"
        // 稿§5:6位数字→韩股(东财177),5位(4位待补零)→港股(东财116)。
        // 顺序保持 商品→金银→6位韩→5位港→币对→美股;纯数字不会命中币对分支,放币对前安全
        // ⚠ 2026-10-03 **新增 A 股识别**（用户给出 sh688825 = 长鑫存储，科创板）。
        //   【为什么要加】原来**根本没有 A 股分支**：`^\d{6}$` 不匹配 "sh688825"（带前缀），
        //   `^\d{4,5}$` 也不匹配，于是它一路穿到最后的美股兜底 ⟹ 拿美股接口查 A 股代码，恒空。
        //   所以「A 股找不到」不是缺源，是**根本没路由过去**。
        //   放在纯数字两条**之前**无害（那些带 ^$ 锚点，本来就匹配不到带前缀的串）。
        if (Regex("^(SH|SZ)\\d{6}$", RegexOption.IGNORE_CASE).matches(u)) return "ashare"
        if (Regex("^\\d{6}$").matches(u)) return "kr"
        if (Regex("^\\d{4,5}$").matches(u)) return "hk"
        // ⚠ 2026-10-07 用户裁定「不要外汇了，美债就只保留TLT」—— forex/bond 两个 symType 取值**整体退役**。
        //   原来这里必须排在 crypto 之前判，是为了把它们路由到 Yahoo 专属腿；现在两类都不支持了。
        //   · 外汇：MktSuggest.yahooSearch 的 quoteType 不再放行 CURRENCY，从源头就不进结果。
        //     硬搜 EURUSD 只会得到「无匹配」，**不会**掉进下面的 crypto 分支白打一次币安。
        //   · 美债：isBond 在同处精确挡 US10Y/TNX 系；TLT 是 ETF，走最后的 stock 腿，
        //     本来就不需要 bond 这个类型。
        if (u.endsWith("USDT") || u.endsWith("USD") || u.endsWith("USDC") ||
            u.endsWith("BTC") || u.endsWith("ETH") ||
            u.contains("/") || u.contains("-")
        ) return "crypto"
        return "stock"
    }

    /**
     * ⚠ 2026-10-07 **职责变更：由「支持」改为「识别以排除」**（用户裁定「美债就只保留TLT」）。
     *   symType 不再返回 "bond"，这个函数只在 MktSuggest 的 Yahoo 准入处被调用，
     *   用来把**收益率指数**（US10Y/TNX/^FVX…）从搜索结果里挡掉。
     *   ⚠ **TLT 不匹配本函数**（既不在 setOf 里、也不是 `US\d+Y` 形态），
     *     所以 TLT 不会被挡，照旧走 stock 腿 —— 这就是「美债只保留 TLT」。
     *   ⚠ 只挡 INDEX 里的收益率指数、不整体去掉 quoteType 的 INDEX：
     *     恒生等普通指数还要能搜，整体去挡会误伤它们。
     */
    fun isBond(u: String): Boolean {
        if (u in setOf("US10Y","US30Y","US2Y","US5Y","TNX","FVX","TYX","IRX","^TNX","^FVX","^TYX","^IRX","UST10Y","UST2Y")) return true
        if (Regex("^US\\d+Y$").matches(u)) return true
        return false
    }
    /**
     * ⚠ 2026-10-07 **已退役**（用户：「不要外汇了，美债就只保留TLT」）。
     *   连同 `yahooForexBondSym` 的 `=X` 后缀 / `^TNX` 前缀映射、`fetchForexBond` 取数腿一起删除——
     *   它们只服务 symType 的 forex/bond 两个取值，而那两个取值本身已经不存在。
     *   TLT 是 ETF，走 stock 腿即可，不需要专属 Yahoo 映射。
     */
    /**
     * ⚠ 2026-10-07 **行情屏最后一次画出来的K线（跨屏共享，不再为自选重复取数）**。
     *   用户原话：「行情页面都有支撑线的数据，直接计算一下，不要去api拉取，显示在自选不就好了」。
     *   MktPanel.renderAgg写入（已fxify+已复权+与屏上同批对象语义一致的拷贝引用）；
     *   FavDist优先读它，命中则连MktCache/MktSave都不查，更不发网络请求。
     *   只保留最近一个品种（自选逐行刷时每行都会触发一次写入竞争？不：写只发生在用户打开行情页时，
     *   自选刷新只读不写，无竞争）。TTL 30分钟，过期视为无。
     */
    object LastChart {
        @Volatile var sym: String = ""
        @Volatile var tf: String = ""
        @Volatile var ks: List<KLine> = emptyList()
        @Volatile var at: Long = 0L
        private const val TTL_MS = 30 * 60 * 1000L
        fun put(s: String, tf0: String, k: List<KLine>) {
            sym = s.trim().uppercase(Locale.US); tf = tf0; ks = k; at = System.currentTimeMillis()
        }
        /**
         * ⚠⚠ 2026-10-09 **必须校验周期**（用户裁定：距支撑的旧数据复用**只认同一周期**）。
         *   [put] 一直把 tf 存着，而这里**从来不看它** ⟹ 这是一条周期盲的复用：
         *   行情页画的是哪个周期、自选距离窗口要的是哪个周期，两者可以不同
         *   （distWin 未提交时跟随行情页周期控件、提交后可冻结成另一个周期），
         *   拿另一个周期的K线算出来的距支撑，就是用户报的「每次重算和之前存的相差很大」。
         *   ⟹ 周期对不上就当**没有**，让调用方走下一级（MktCache → MktSave → 真抓）。
         *   ⚠ 根数 n **不在这里卡**（本函数本来就不收 n）—— 根数由下游 takeLast(n) 归一，
         *     那是 2026-10-08 修「老是无源」时正确的放宽，必须保留。
         */
        fun get(s: String, tf0: String): List<KLine>? {
            if (ks.isEmpty() || at <= 0) return null
            if (sym != s.trim().uppercase(Locale.US)) return null
            if (tf != tf0) return null
            if (System.currentTimeMillis() - at > TTL_MS) return null
            return ks
        }
    }

    private fun numD(x: Any?): Double {
        val v = when (x) {
            null, JSONObject.NULL -> Double.NaN
            is Number -> x.toDouble()
            else -> x.toString().toDoubleOrNull() ?: Double.NaN
        }
        return if (v.isNaN()) 0.0 else v
    }

    private fun arrD(a: JSONArray, i: Int): Double =
        if (i < a.length()) numD(if (a.isNull(i)) null else a.get(i)) else 0.0

    private fun arrL(a: JSONArray, i: Int): Long =
        if (i < a.length()) a.optString(i, "0").toDoubleOrNull()?.toLong() ?: 0L else 0L

    // ══════════════════════════════════════════════════════════════════════════════════
    // ⚠⚠⚠ 2026-10-10 **全项目唯一的出网闸**（用户硬约束：「抓取行情信息不要频繁打，不要被限流」）。
    //
    // 【为什么闸必须在这里、而且只有这一道】
    //   本文件的 `get()` 是**全项目唯一的 HTTP 出口**（全仓 `openConnection` 只有一处，就在下面
    //   几行的 get() 里；MktSuggest 的三处搜索也走 `MktData.get`）。而限流是按**出口 IP** 算的，
    //   与"哪条链、哪个市场"无关 ⟹ 闸只能加在出口上、按 host 计；改前那些限流手段是**分散**的
    //   （FavPanel 每品种错峰 300/600ms、FavDist 三级复用、本文件的软/硬熔断）。
    //   FavPanel 那条注释自己就写着「上百条得换成真正的令牌桶」（FavPanel.kt:1557）—— 这就是那道闸。
    //
    // 【三项，全部只回答"什么时候允许发"；"发什么/超时多少/失败怎么判"一个字都没改】
    //   ① 同 host 最小发送间隔 GAP_*：并发调用**预约发车时刻**（不是各自 sleep 一个固定值），
    //      ⟹ 同一 host 的长期发送速率 ≤ 1/间隔。
    //   ② 全局并发上限 MAX_INFLIGHT：同时打开的连接数封顶。
    //   ③ 失败/空响应冷却 COOL_MS：该 host 进入冷却窗，窗内每个调用**最多**等 COOL_WAIT_MAX_MS
    //      （有界惩罚；为什么不让它吃掉整个等待预算，见 enter() 里的注释）。
    //
    // 【参数依据（全部来自本仓已有实测，不新编）】
    //   · 东财 push2his 有反爬、出网 IP 常被限流（:885）；判别实验：**第 1 次空响应 → 等 45s →
    //     第 2 次 40 根 ✅ → 立刻第 3 次空 ❌**（:894 与 FavPanel.kt:1546-1547 是同一个实验）。
    //   · 自选真机 **28 个品种 × 1~2 腿 ≈ 40~50 条请求**；FavPanel 错峰总窗口 28×300ms≈8.4s
    //     ⟹ 平均 **~5 条/秒**，注释原话「正是东财限流窗口的密集区」（FavPanel.kt:1559-1563）。
    //   · 故东财取 **400ms（≤2.5 条/秒）= 那个"5 条/秒密集区"的一半** —— 与 FavPanel 在 >20 品种时
    //     把错峰 300→600ms 是**同一个减半判据**。
    //   · 其它 host 取 **250ms（≤4 条/秒）**：没有任何实测限流记录，这个值只用来挡"同一瞬间多线程
    //     撞同一 host"；正常路径（一个品种一次请求）根本碰不到它。
    //   · 并发上限 **4**：FavPanel 记下的故障形状是「一次刷 5 个品种、每品种 2 条腿 ≈ 10+ 请求
    //     **同时**打出去 ⟹ 必撞限流」（FavPanel.kt:1548-1550）⟹ 4 严格小于那个形状。
    //   · 冷却 **20s**：与 [SRCBAD_SOFT_MS] **同值同源**（都是"限流几十秒就恢复"的口径；
    //     45s 那次实验说明 20s 内重试没有意义）。
    //
    // 【闸只推迟，绝不吞请求、绝不伪造失败】
    //   `pool = if (rest.isNotEmpty()) rest else legs` 的语义是「全被熔断时仍然全试」
    //   （见 fetchStocks）。闸若在出口吞掉请求，等于把「限流」变成「无源」—— 比限流更坏；
    //   伪造一个空响应则等于改了「失败怎么判」，越界。所以冷却只体现为**发车时刻被推后**，
    //   真正"停打这条腿"仍由既有的软熔断（20s 跳腿）负责 —— 两者分工不重叠。
    //
    // 【等待有上限，且绝不碰主线程】
    //   · 上限 = min(5s, max(1s, 调用方**自己的 HTTP 预算** timeoutMs 的一半))：
    //     - 上限 5s 的依据：它必须**显著小于**调用方自己的耐心 —— raceOk 的总预算是 timeout+8000
    //       （东财腿 ≥18s）、HTTP 超时本身 8~15s、FavDist 的失败标签阈值是 9s ⟹ 5s 之内都还
    //       轮不到"闸制造超时"；同时它又**足够大**到让限流真的生效（12 条同 host 并发、
    //       每条 1.5s 服务时，排空需要 ~4.5s —— 上限太小就等于 fail-open，见下）。
    //     - 取"调用方预算的一半"而不是固定值：调用方自己只肯等 6s 时，闸不该独占 5s。
    //     - 下限 1s：给最小间隔至少留出一次生效的机会。
    //   · 超上限就**放行**（fail-open）：宁可放行一次，也不要让闸自己变成新的故障源
    //     （极端洪峰下闸退化为"尽力而为"，但绝不挂住、绝不改失败判定）。
    //   · 主线程直接放行：get() 的调用点应全在后台线程（已逐个核对：FavPanel 每品种一个 Thread、
    //     行情页三条腿都在 Thread、MktSuggest 的搜索在 Thread、NameBook 自带 Thread），
    //     这里再设一道防线 —— 真被主线程调到也绝不让 UI 卡住。
    // ══════════════════════════════════════════════════════════════════════════════════
    private object HttpGate {
        private const val GAP_DEFAULT_MS = 250L
        private const val GAP_EM_MS = 400L
        private const val MAX_INFLIGHT = 4
        private const val COOL_MS = 20_000L
        /** 冷却窗内**每个调用**最多等多久（有界惩罚）。 */
        private const val COOL_WAIT_MAX_MS = 1500L

        private val lock = Any()
        /** host → 下一次允许发车的时刻（①的预约） */
        private val nextAt = HashMap<String, Long>()
        /** host → 冷却到期时刻（③失败/空响应） */
        private val coolUntil = HashMap<String, Long>()
        private val permits = java.util.concurrent.Semaphore(MAX_INFLIGHT)

        private fun gapOf(host: String): Long =
            if (host.endsWith("eastmoney.com")) GAP_EM_MS else GAP_DEFAULT_MS

        /** 主线程探测。⚠ 纯 JVM 下（离线诊断脚手架）Looper 是 android.jar 的 stub 会抛 —— 按"不是主线程"处理。 */
        private fun onMainThread(): Boolean = try {
            android.os.Looper.myLooper() === android.os.Looper.getMainLooper()
        } catch (_: Throwable) {
            false
        }

        /**
         * 过闸。返回"是否持有并发许可"（供 [exit] 归还）。
         * deadline = 等待的**绝对上限**（毫秒时间戳）；到点即放行，不抛异常。
         */
        fun enter(host: String, deadline: Long): Boolean {
            if (onMainThread()) return false
            // ① 预约发车时刻（并发调用各自拿到不同时刻 ⟹ 长期速率 ≤ 1/间隔）
            // ③ 冷却**只贡献有界的一小段等待**（COOL_WAIT_MAX_MS）：
            //    ⚠⚠ 为什么不能让它吃掉整个预算：冷却窗有 20s，若窗内每个调用都等到预算上限（5s），
            //    一次失败就会把整页自选（跨市场共用同一 host）拖成「每行多等 5s」——
            //    那等于用一个限流问题换一个性能问题。1.5s 足以打散"立刻重打"的洪峰，
            //    而真正"这条腿停打 20s"仍由既有的软熔断（srcSoftSkip）负责，分工不重叠。
            val now = System.currentTimeMillis()
            val sendAt: Long
            synchronized(lock) {
                val coolAt = min(coolUntil[host] ?: 0L, now + COOL_WAIT_MAX_MS)
                val at = max(now, max(nextAt[host] ?: 0L, coolAt))
                nextAt[host] = at + gapOf(host)
                sendAt = at
            }
            if (sendAt > now) sleepUntil(min(sendAt, deadline))
            // ② 全局并发上限（同样有上限，超时即放行）
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return false
            return try {
                permits.tryAcquire(left, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        }

        fun exit(held: Boolean) {
            if (held) permits.release()
        }

        /** ③ 冷却：记下该 host 的冷却到期时刻（只往后推，不提前）。 */
        fun cool(host: String) {
            if (host.isEmpty()) return
            synchronized(lock) {
                coolUntil[host] = max(coolUntil[host] ?: 0L, System.currentTimeMillis() + COOL_MS)
            }
        }

        private fun sleepUntil(t: Long) {
            val ms = t - System.currentTimeMillis()
            if (ms <= 0) return
            try {
                Thread.sleep(ms)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    internal fun get(
        url: String, accept: String? = null, ua: String = UA, timeoutMs: Int = 0,
        origin: String? = null, referer: String? = null,
        // ⚠ 2026-10-05 **可指定字符集**。腾讯行情 `qt.gtimg.cn` 返回的是 **GBK**，
        //   早先这里写死 UTF-8 ⟹ 中文名会整串乱码（实测 curl 出来是 `???οƼ?`）。
        charset: java.nio.charset.Charset = Charsets.UTF_8
    ): String {
        // ⚠⚠ 2026-10-10 **唯一出网闸：先过闸，再发请求**（只推迟发车时刻，不改任何请求内容）。
        //   预算 = 调用方自己 HTTP 预算的 1/4（至少 600ms），见 HttpGate 顶部注释。
        val host = runCatching { URL(url).host ?: "" }.getOrDefault("")
        // 闸的总等待上限：min(5s, max(1s, 调用方 HTTP 预算的一半)) —— 依据见 HttpGate 顶部注释
        val gateBudget = min(5000L, max(1000L, (if (timeoutMs > 0) timeoutMs else 12000) / 2L))
        val held = HttpGate.enter(host, System.currentTimeMillis() + gateBudget)
        try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = if (timeoutMs > 0) timeoutMs else 12000
                readTimeout = if (timeoutMs > 0) timeoutMs else 15000
                setRequestProperty("User-Agent", ua)
                if (accept != null) setRequestProperty("Accept", accept)
                // v4§7:汇率源(frankfurter/er-api)必须显式带Origin,否则网关把CORS头回成null
                if (origin != null) setRequestProperty("Origin", origin)
                // 稿:936/1004 腾讯/新浪要求带 Referer,不给会 403
                if (referer != null) setRequestProperty("Referer", referer)
                instanceFollowRedirects = true
            }
            try {
                val code = c.responseCode
                if (code !in 200..299) throw Exception("http $code")
                return c.inputStream.bufferedReader(charset).readText()
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            // ③ 失败冷却（连接失败/超时/HTTP 非 2xx）。**空响应那条不在这里** ——
            //   它由 srcSoftMark() 这条既有事件驱动（见那里的注释），不重复造表。
            HttpGate.cool(host)
            throw e
        } finally {
            HttpGate.exit(held)
        }
    }

    // ---------- 机构目标均价(稿TGT:Nasdaq官方分析师接口聚合数) ----------
    // 成功缓5min/失败缓30s;均价=priceTarget,家数=buy+hold+sell;空数据也按成功缓
    data class Tgt(val avg: Double, val n: Int)
    private val tgtLock = Any()
    private val tgtMap = mutableMapOf<String, Triple<Tgt?, Long, Boolean>>()
    fun fetchTgt(sym: String): Tgt? {
        val key = sym.trim().uppercase(Locale.US)
        if (key.isEmpty()) return null
        synchronized(tgtLock) {
            tgtMap[key]?.let { (r, t, ok) ->
                if (System.currentTimeMillis() - t < (if (ok) 300_000L else 30_000L)) return r
            }
        }
        var r: Tgt? = null
        var ok = false
        try {
            val s = get("https://api.nasdaq.com/api/analyst/$key/targetprice", null, UA, 10000)
            val j = JSONObject(s)
            ok = true
            val co = j.optJSONObject("data")?.optJSONObject("consensusOverview")
            val avg = co?.optDouble("priceTarget", 0.0) ?: 0.0
            if (co != null && avg > 0) {
                val n = co.optInt("buy", 0) + co.optInt("hold", 0) + co.optInt("sell", 0)
                if (n > 0) r = Tgt(avg, n)
            }
        } catch (_: Exception) {
            ok = false
        }
        synchronized(tgtLock) { tgtMap[key] = Triple(r, System.currentTimeMillis(), ok) }
        return r
    }

    /**
     * ⚠⚠⚠ 2026-10-03 **港股机构目标价 —— etnet(经济通)源**。
     *
     * 【为什么需要它】美股走 [fetchTgt] 的纳斯达克接口；港股那边**纳斯达克不认**
     *   （实测 0700 / 0700.HK / hk0700 / 700 / TCEHY 五种写法全 `status=400`，
     *     同一接口查 AAPL 正常返回 334.9 ⟹ 接口本身是通的，是它没有港股的库）。
     *   逐个试过的其余源也全挂：新浪港股 F10（Service not valid）、腾讯港股（404）、
     *   雪球（400）、富途（404 + 前端渲染）、AAStocks（0 字节）、同花顺（0 字节）。
     * ⟹ **etnet 是唯一实测通了的一个**，而且它是 akshare `stock_hk_profit_forecast_et`
     *   用的同一个 URL —— **有人验证过的接口，不是猜出来的路径**。
     *
     * 【URL】`https://www.etnet.com.hk/www/sc/stocks/realtime/quote_profit.php?code={去零代码}`
     *   00700 → code=700。**服务端渲染的纯 HTML**（实测 123KB），不是 JS 拼的 ⟹ 可直接解析。
     *
     * 【只取目标价，不取评级】用户明确：「我不需要评级，我只需要美股那样的机构目标价」。
     *   ⟹ 页面上的「平均评级 1.94」「综合 N 份证券商报告」**一律不解析**。
     *
     * 【表结构（2026-10-04 实测 00700 的页面，126KB 简化）】目标价表是**固定 9 列**的数据行：
     * ```
     * 财年 | 纯利(百万) | 每股盈利 | 每股派息 | 证券商 | 评级 | 目标价(港元) | (空) | 更新日期
     * 2026 | 223,319.00 | 2,930.00 | 472.00 | 美银   | 买入 | 780.00 |  | 17/08/2026
     * 2026 | 266,299.00 | 2,910.00 | 562.00 | 瑞银   | 买入 | 770.00 |  | 20/08/2026
     * ```
     *   ⚠ **2027 / 2028 块的第 7 列全是 `--`**（那两个块只给盈利预测，不给目标价）。
     *   ⟹ 「第 7 列能不能解析成数字」就是数据行的天然判据，
     *     也让「只取有价的那个财年」自动成立，**不存在跨财年混合**。
     *   页面**不给平均值**，所以取逐家的**算术平均**（与纳斯达克 consensusOverview.priceTarget
     *   的口径一致：都是分析师目标价的均值）。
     *
     * 【币种】etnet 给的是**港元**，纳斯达克给的是**美元**。
     *   ⟹ 这里返回**已按当前汇率折成美元**的值，与屏幕其余数字同量纲；
     *     **屏上因此不必标「港元」**（标了反而会和旁边 USD 数字不同量纲）。
     *   ⚠ 折算这一步必须写明，别让下一个读代码的人以为是直接抓来的港元数字。
     *
     * @param n 返回的 [Tgt.n] 是**参与平均的家数**（不是报告总数）。
     */
    // ⚠ 2026-10-13 用户裁定：韩股两只**手动录入**的共识目标价（Naver 컨센서스，用户截图提供，
    //   与 Nasdaq/etnet 源不可比对——Naver 反爬闸死、三轮探针留档 tools/naver_tgt1~3_out.txt）。
    //   原值韩元，**入库即按汇率折美元**（FX.rateOf("KRW")，1 USD = X KRW；与 fetchTgtEtnet
    //   港元折美元同规矩：屏上其余数字都是美元量纲，不折就是串量纲）。
    //   ① 000660 SK海力士 3,257,727 KRW（Naver 快照 2026-10-12）
    //   ② 005930 三星电子   495,227 KRW（Naver 快照 2026-10-13，页面注明「最近3个月中权券商均值」）
    //   ⚠ 静态快照、**不自动更新**：过期改这张表；汇率取不到 ⟹ null（该行不显示），不拿未折算韩元冒充美元。
    private val KR_TGT_KRW = mapOf("000660" to 3_257_727.0, "005930" to 495_227.0)
    fun fetchTgtKrManual(krCode: String): Tgt? {
        if (!Regex("^\\d{6}$").matches(krCode)) return null
        val krw = KR_TGT_KRW[krCode.trim().uppercase(Locale.US)] ?: return null
        val r = FX.rateOf("KRW") ?: return null
        if (!(r > 0)) return null
        return Tgt(krw / r, 1)
    }

    fun fetchTgtEtnet(hkCode: String): Tgt? {
        // 只收 5 位以内的纯数字港股代码（00001/00700/09999）；带 sh/sz 前缀的走 A 股，不归这里
        val code = hkCode.trim()
        if (!Regex("^\\d{1,5}$").matches(code)) return null
        val bare = code.trimStart('0').ifEmpty { "0" }     // 00700 → 700（etnet 用去零写法）
        val html = try {
            get(
                "https://www.etnet.com.hk/www/sc/stocks/realtime/quote_profit.php?code=$bare",
                ua = uaRnd(), timeoutMs = 12000, referer = "https://www.etnet.com.hk/"
            )
        } catch (_: Exception) {
            return null
        }
        if (html.isEmpty()) return null

        val at = html.indexOf("目标价")
        if (at < 0) return null                      // 没这个表头（可能已改版或该股无覆盖）

        // ⚠⚠⚠ 2026-10-04 **解析方式整体换掉**：从「日期锚点 + 向前回看数字」改成**按表格行取列**。
        //
        // 【为什么必须换】原写法 `(\d+\.\d+)\s*(</?[^>]*>)*\s*$` 要求目标价与日期之间**只能是标签**，
        //   而 etnet 那一行实际是：
        //     <td>780.00</td><td width="60">&nbsp;</td><td>17/08/2026</td>
        //                            ^^^^^^ HTML 实体不是标签 ⟹ 锚定断裂
        //   实测：同一天页面里有 **24 个日期锚点、成功取值 0 个** ⟹ 整条功能恒空
        //   （表现：港股屏上「机构目标均价」那一行整行缺失）。
        //   ⚠ 这**不是源挂了**：同一次实测页面 126KB、简体中文、目标价表完整（17 家券商）。
        //
        // 【另一个坑】截表必须**截到本表的 `</table>` 为止**。只按「表头往后 N 字符」切会溢出到
        //   下一张表，把「去年度业绩表现」那张误认成目标价表（Python 复刻时先错了一版）。
        val tblStart = html.lastIndexOf("<table", at)
        val tblEnd = html.indexOf("</table>", at)
        if (tblStart < 0 || tblEnd <= tblStart) return null
        val seg = html.substring(tblStart, tblEnd)

        // 这三条在 60+ 行上反复用，提到循环外建一次
        val reTr = Regex("<tr[^>]*>(.*?)</tr>", RegexOption.DOT_MATCHES_ALL)
        val reTd = Regex("<td[^>]*>(.*?)</td>", RegexOption.DOT_MATCHES_ALL)
        val reTag = Regex("<[^>]+>")
        val reYear = Regex("^\\d{4}$")

        val seen = HashSet<String>()       // 同一家券商只算一次（防将来页面改版后重复行）
        val vals = mutableListOf<Double>()
        for (tr in reTr.findAll(seg)) {
            // ⚠ 必须**先按 <td> 切、再去标签**：反过来的话 </td><td> 被剥掉后列边界就没了。
            // ⚠ `Regex.replace` 没有单参重载，必须给 replacement（去标签就是替换成空串）。
            val cells = reTd.findAll(tr.groupValues[1])
                .map { m -> reTag.replace(m.groupValues[1], "").replace("&nbsp;", " ").trim() }
                .toList()
            if (cells.size < 7) continue
            if (!reYear.matches(cells[0])) continue              // 第 1 列必须是财年
            val p = cells[6].replace(",", "").toDoubleOrNull() ?: continue   // `--` 自然落在这
            if (!(p > 0)) continue
            val broker = cells[4]
            if (broker.isBlank() || !seen.add(broker)) continue
            vals.add(p)
        }
        // 少于 3 家不敢称共识：页面改版或只剩一两行时，宁可不显示，也不给一个单点数字
        if (vals.size < 3) return null
        val avgHkd = vals.sum() / vals.size
        if (!(avgHkd > 0)) return null

        // 港元 → 美元（取不到汇率就返回 null，不拿未折算的数字糊弄）
        // ⚠⚠⚠ 2026-10-04 **这里原先写的是 `avgHkd * rate`，方向反了**。
        //   [FX] 的口径是「**1 USD = rate 外币**」（`fxText` 屏上就写「1 USD = 7.848 HKD」，
        //   `keyRate` 的注释也写着「原生价→美元 = ÷X」）⟹ 外币折美元是**除**。
        //   写成乘法的后果：661.47 港元被算成 661.47×7.848 = **5,190.63**（实测屏上就是这个数）。
        //   ⚠ 它一直没被发现，是因为上一行 `vals` 恒空（解析 0 条）⟹ **这行从没被执行过**。
        //   「没被执行过的代码不一定是错的，但一定是没被验过的」—— 这次是后者被前者掩盖。
        val rate = FX.rateOf("HKD") ?: return null
        if (!(rate > 0)) return null
        return Tgt(avgHkd / rate, vals.size)
    }

    // 币:base/quote拆分(照稿子,quote兜底USDT)
    fun splitBaseQuote(sym: String): Pair<String, String> {
        val clean = sym.uppercase(Locale.US).replace(Regex("[^A-Z]"), "")
        var base = clean
        var quote = "USDT"
        for (q in listOf("USDT", "USDC", "USD")) {
            if (clean.endsWith(q)) {
                base = clean.dropLast(q.length).ifEmpty { "BTC" }
                quote = q
                break
            }
        }
        if (base.isEmpty()) base = "BTC"
        return base to quote
    }

    // 币分支参战腿:BTC钉死币安单源,其余base走GT/OK/BB/KU/MX
    // (稿§6:Gate提到第2位——BN在前时顺序[BN,GT,OK,…];BN在USD报价时跳过,
    // 唯BTCUSD裸接口已验真货保留)+BTC限定GK
    const val PIN = "BN"
    fun cryptoLegs(sym: String): List<String> {
        val (base, quote) = splitBaseQuote(sym)
        if (base == "BTC") return listOf("BN")
        val legs = mutableListOf("GT", "OK", "BB", "KU", "MX")
        if (quote != "USD") legs.add(0, "BN")
        return legs
    }

    // 单腿拉取(含prep:Q走toQuarterly但GK除外,按t正序截最近n根,不足3根抛错)
    // iv下标:BN0/OK1/BB2/GT3/KU4/MX5,超时单腿10秒
    fun fetchCryptoLeg(v: String, sym: String, tf: String, count: Int, timeoutMs: Int = 10000): VendorKs {
        val (base, quote) = splitBaseQuote(sym)
        val n = max(5, min(100, count))
        val mq = if (tf == "Q") min(n * 3, 100) else n
        val iv = when (tf) {
            "W" -> arrayOf("1w", "1W", "W", "7d", "1week", "1W")
            else -> arrayOf("1M", "1M", "M", "30d", "1month", "1M")
        }
        val moz = "Mozilla/5.0"
        val ks: List<KLine> = when (v) {
            "BN" -> {
                if (quote == "USD" && base != "BTC") throw Exception("skip USD")
                val a = JSONArray(get(
                    "https://data-api.binance.vision/api/v3/klines?symbol=$base$quote&interval=${iv[0]}&limit=$mq",
                    timeoutMs = timeoutMs))
                (0 until a.length()).map { i ->
                    val k = a.getJSONArray(i)
                    KLine(arrL(k, 0), arrD(k, 1), arrD(k, 2), arrD(k, 3),
                        arrD(k, 4), arrD(k, 5), arrD(k, 7))
                }
            }
            "OK" -> {
                val data = JSONObject(get(
                    "https://www.okx.com/api/v5/market/candles?instId=$base-$quote&bar=${iv[1]}&limit=$mq",
                    timeoutMs = timeoutMs))
                    .optJSONArray("data") ?: JSONArray()
                (0 until data.length()).map { i ->
                    val k = data.getJSONArray(i)
                    KLine(arrL(k, 0), arrD(k, 1), arrD(k, 2), arrD(k, 3),
                        arrD(k, 4), arrD(k, 5), arrD(k, 7))
                }
            }
            "BB" -> {
                val list = JSONObject(get(
                    "https://api.bybit.com/v5/market/kline?category=spot&symbol=$base$quote&interval=${iv[2]}&limit=$mq",
                    timeoutMs = timeoutMs))
                    .optJSONObject("result")?.optJSONArray("list") ?: JSONArray()
                (0 until list.length()).map { i ->
                    val k = list.getJSONArray(i)
                    KLine(arrL(k, 0), arrD(k, 1), arrD(k, 2), arrD(k, 3),
                        arrD(k, 4), arrD(k, 5), arrD(k, 6))
                }
            }
            "GT" -> {
                // 行[t秒,qv,close,high,low,open,baseVol]
                val a = JSONArray(get(
                    "https://api.gateio.ws/api/v4/spot/candlesticks?currency_pair=${base}_${quote}&interval=${iv[3]}&limit=$mq",
                    ua = moz, timeoutMs = timeoutMs))
                (0 until a.length()).map { i ->
                    val k = a.getJSONArray(i)
                    KLine(arrL(k, 0) * 1000, arrD(k, 5), arrD(k, 3), arrD(k, 4),
                        arrD(k, 2), arrD(k, 6), arrD(k, 1))
                }
            }
            "KU" -> {
                // 行[t秒,o,c,h,l,vol,turnover],无limit参数(最多1500,靠slice截)
                val data = JSONObject(get(
                    "https://api.kucoin.com/api/v1/market/candles?symbol=$base-$quote&type=${iv[4]}",
                    ua = moz, timeoutMs = timeoutMs))
                    .optJSONArray("data") ?: JSONArray()
                (0 until data.length()).map { i ->
                    val k = data.getJSONArray(i)
                    KLine(arrL(k, 0) * 1000, arrD(k, 1), arrD(k, 3), arrD(k, 4),
                        arrD(k, 2), arrD(k, 5), arrD(k, 6))
                }
            }
            "MX" -> {
                // Binance同格式,t为毫秒,interval大写
                val a = JSONArray(get(
                    "https://api.mexc.com/api/v3/klines?symbol=$base$quote&interval=${iv[5]}&limit=$mq",
                    ua = moz, timeoutMs = timeoutMs))
                (0 until a.length()).map { i ->
                    val k = a.getJSONArray(i)
                    KLine(arrL(k, 0), arrD(k, 1), arrD(k, 2), arrD(k, 3),
                        arrD(k, 4), arrD(k, 5), arrD(k, 7))
                }
            }
            "GK" -> return fetchGecko(base, tf, n, timeoutMs).first()
            else -> throw Exception("unknown leg $v")
        }
        if (ks.size < 3) throw Exception("行情拉取失败(网络或品种名不对)")
        val qk = if (tf == "Q" && v != "GK") toQuarterly(ks) else ks
        val disp = qk.sortedBy { it.t }.takeLast(n)
        if (disp.size < 3) throw Exception("行情拉取失败(网络或品种名不对)")
        return VendorKs(v, disp)
    }

    // CoinGecko(BTC限定):ohlc取[t,o,h,l,c]+market_chart按UTC日期对齐量,
    // 日线经resample+取最近n,qv直接取和
    fun fetchGecko(base: String, tf: String, count: Int, timeoutMs: Int = 10000): List<VendorKs> {
        if (base != "BTC") throw Exception("gecko BTC限定")
        val n = max(5, min(100, count))
        val per = when (tf) { "M" -> 31; "Q" -> 92; else -> 7 }
        val days = min(365, Math.ceil(n * per * 1.3).toInt())
        val moz = "Mozilla/5.0"
        val o = JSONArray(get(
            "https://api.coingecko.com/api/v3/coins/bitcoin/ohlc?vs_currency=usd&days=$days",
            ua = moz, timeoutMs = timeoutMs))
        val m = JSONObject(get(
            "https://api.coingecko.com/api/v3/coins/bitcoin/market_chart?vs_currency=usd&days=$days&interval=daily",
            ua = moz, timeoutMs = timeoutMs))
        if (o.length() == 0) throw Exception("行情拉取失败(网络或品种名不对)")
        val vols = m.optJSONArray("total_volumes") ?: JSONArray()
        val vmap = HashMap<String, Double>()
        for (i in 0 until vols.length()) {
            val e = vols.optJSONArray(i) ?: continue
            vmap[utcDate(e.optLong(0, 0))] = e.optDouble(1, 0.0)
        }
        val ks = mutableListOf<KLine>()
        for (i in 0 until o.length()) {
            val k = o.optJSONArray(i) ?: continue
            val t = k.optLong(0, 0)
            val h = k.optDouble(2, 0.0)
            if (!(h > 0)) continue
            ks.add(KLine(t, k.optDouble(1, 0.0), h, k.optDouble(3, 0.0),
                k.optDouble(4, 0.0), vmap[utcDate(t)] ?: 0.0, 0.0))
        }
        val rs = resampleDaily(ks, tf).takeLast(n)
        if (rs.size < 3) throw Exception("行情拉取失败(网络或品种名不对)")
        return listOf(VendorKs("GK", rs.map { it.copy(qv = it.v) }))
    }

    private fun utcDate(t: Long): String {
        val d = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            .apply { timeInMillis = t }
        return "%04d-%02d-%02d".format(d.get(Calendar.YEAR),
            d.get(Calendar.MONTH) + 1, d.get(Calendar.DAY_OF_MONTH))
    }

    /**
     * 季线聚合：**委托给 [resampleDaily] 的日历分桶**（`"Q"`），不再按位置切三根。
     *
     * ## t46 修的缺陷（判定书 `redesign/draft_nv_q季线异常.md`，实证取自 emulator 缓存 `gridcalc_mk_v1.xml`，全程零行情请求）
     *
     * 旧实现 `while (i + 2 < ks.size) { a=ks[i]; b=ks[i+1]; c=ks[i+2]; i += 3 }` 三个问题：
     * 1. **按位置切**，完全不看日历 —— 输入只要不是恰好从季度首月开始，每组都跨季度；
     * 2. **尾组静默丢弃** —— `i + 2 < ks.size` 让末组不满 3 根时整组消失；
     * 3. **在已是季线的输入上二次聚合** —— 百度腿 [fetchBaidu] 已用 `ktype=quarter` 取回**源季线**，
     *    而 `fetchStocks`/`fetchMetals` 的 `prep`（本文件 :720 / :931）又对它做一次 ⟹ 一个季度被塞进三个季度。
     *
     * 实测后果（00700 港股）：季线只有 **6 根**、间隔恒为 **273 天（9 个月）**、月份序列
     * `12→9→6→3→12→9`；日历季线间隔必然 ≈91 天 ⟹ 这 6 根只能是从季线序列「每取第 3 根」拼出来的。
     * 且 `while` 丢弃尾组使**最后一根停在 2026-03，而月线已在 2026-09 —— 落后两个季度**。
     *
     * ## 为什么复用 resampleDaily 而不是新写一份
     *
     * 同文件 [resampleDaily] 的 `"Q"` 分支（`set(y, m / 3 * 3, 1)`）**已经是本文件 W/M 一直在用的
     * 正确日历分桶**。修之前同一个 `when` 里 W/M 走它、Q 走本函数的位置切分
     * （新浪腿 :1010-1012），是最刺眼的不一致。修法是**收口**而不是再添一套。
     *
     * ## 副作用逐条核实（不是「大概没问题」，是逐行对着 resampleDaily 核过）
     *
     * | 项 | 旧实现 | 新实现（resampleDaily） | 判定 |
     * |---|---|---|---|
     * | H | `max(a.h, b.h, c.h)` | `max(e.h, k.h)`（:394） | 同义 |
     * | L | `min(a.l, b.l, c.l)` | `min(e.l, k.l)`（:394） | 同义 |
     * | O / C | 首根 o / 末根 c | 新桶取首根 o（:396）、每次覆盖为当根 c（:394） | 同义 |
     * | v | 求和 | `e.v + k.v`（:394） | 同义 |
     * | **尾组** | `i+2 < size` ⟹ **丢** | 每个非空桶都出一根 ⟹ **末季必在** | **这就是修掉的 bug ③** |
     * | **t** | `a.t`（组内第一根原样） | 桶键 `y, m/3*3, 1` = **季首 00:00 UTC** | **规范化，不是缺陷**，见下 |
     * | **qv** | `a.qv + b.qv + c.qv` | 新桶 qv=0.0（:396）、合并时不动 qv（:394）⟹ 保留首根 qv | 语义变化，见下 |
     * | usd 标记 | 新建对象，**不带** usd | 同样新建对象、同样不带 usd | **无变化** |
     * | 排序 | **不排序**，依赖调用方输入顺序 | 收尾 `sortedBy { it.t }`（:399） | 严格更好 |
     *
     * - **t 规范化为什么是好事**：各腿原本给的是**季末日期**（百度 `ktype=quarter`），改后统一成**季首日期**。
     *   `prep`（:720/:931）这层调用**必须保留** —— 它就是跨腿的日期规范化点。删掉它，
     *   百度腿给季末、东财/新浪腿给季首，而 `aggregate()` 是**按 qv 竞速任取一腿** ⟹
     *   图上日期轴会随「哪腿赢了」变。**那不是去掉冗余，是拆掉规范化。**
     *   改后该层对已是季线的输入**严格幂等**（单根桶 o/h/l/c/v 原样，只有 t 被规范化）。
     * - **qv 语义变化为什么可接受**：qv 只喂 [aggregate] 的选腿排序（`sumOf { numD(it.qv) }`），量纲是成交额。
     *   「一个季度的成交额」比「三个季度的成交额之和」更贴合该指标的语义。
     *   ⚠️ **连带可见变化**：旧实现下腾讯腿 240 根日线 → 4~5 个季 → 再被切 1 组 → `prep` 的
     *   `kk.size < 3` 判负 ⟹ **腾讯腿对 Q 一直是死的**（缓存里 00700 只有 `v:"百度"`，正好印证）。
     *   修完它会复活 ⟹ **季线图例的源名可能从「百度」变成「腾讯」**（取决于 qv 竞速）。
     *   这是死腿复活，不是回归。
     *
     * ## 影响面
     *
     * ⚠️ **只影响 `tf == "Q"`**：全部 5 个调用点 —— 币 :271 / stocks :720 / 东财 :921 /
     * metals :931 / 新浪 :1012 —— 都在 `tf == "Q"` 判据内（新浪那条是 `when` 的 `"Q"` 分支），
     * 周线与月线路径根本走不到本函数 ⟹ **W/M 逐位不变**。
     *
     * ⚠️ **刻意没改的两处**（t42 曾建议，此处不采纳，理由见函数外注释与报备）：
     * `fetchTencent` 的 `per` 映射（`"Q"` 落 `else -> "day"`）—— 该腿的季度聚合由
     * `resampleDaily(out, tf)` 负责、本就正确，改 `per` 要赌腾讯接口支不支持 `quarter`，
     * 不支持就会把这条腿直接改成失源，而**零行情请求的硬红线让我无法预先实测**；
     * 以及 `fetchStocks`/`fetchMetals` 的 `prep` 层 —— 见上面「t 规范化为什么是好事」。
     */
    fun toQuarterly(ks: List<KLine>): List<KLine> = resampleDaily(ks, "Q")

    // Yahoo日级(query1 YI映射,range按根数倒推,取最近n根,失败抛错,单路timeoutMs超时)
    // ⚠️ 保留但**已不在 fetchStocks 腿链里**(用户要求股票K线只用东财、不切源);
    // 配套的 yahooSym() 港/韩后缀映射一并保留,恢复兜底时成对可用。
    fun fetchYahooDaily(sym: String, tf: String, count: Int, timeoutMs: Int = 10000): List<VendorKs> {
        val n = max(5, min(FETCH_N, count))
        // ⚠ 2026-10-07 补"D"日线档（stepCloses传"D"要日线；原先掉进else=周线，阈值/预测窗口全错）。
        val per = when (tf) { "D" -> 1; "M" -> 31; "Q" -> 92; else -> 7 }
        val need = n * per * 1.5
        val ranges = listOf("3mo" to 90, "6mo" to 180, "1y" to 365, "2y" to 730, "5y" to 1825)
        var rg = "5y"
        for ((r, d) in ranges) {
            if (need <= d) {
                rg = r
                break
            }
        }
        val iv = when (tf) { "D" -> "1d"; "M" -> "1mo"; "Q" -> "3mo"; else -> "1wk" }
        val enc = URLEncoder.encode(sym.trim(), "UTF-8")
        // Yahoo chart接口认浏览器UA,自带GridCalc UA会被401,此处用Mozilla/5.0
        val j = JSONObject(get(
            "https://query1.finance.yahoo.com/v8/finance/chart/$enc?interval=$iv&range=$rg",
            ua = "Mozilla/5.0", timeoutMs = timeoutMs))
        val r = j.optJSONObject("chart")?.optJSONArray("result")?.optJSONObject(0)
            ?: throw Exception("行情拉取失败(网络或品种名不对)")
        val ts = r.optJSONArray("timestamp") ?: throw Exception("行情拉取失败(网络或品种名不对)")
        val q = r.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)
            ?: throw Exception("行情拉取失败(网络或品种名不对)")
        val oo = q.optJSONArray("open"); val hh = q.optJSONArray("high")
        val ll = q.optJSONArray("low"); val cc = q.optJSONArray("close")
        val vv = q.optJSONArray("volume")
        val out = mutableListOf<KLine>()
        for (i in 0 until ts.length()) {
            val o = oo?.optDouble(i, Double.NaN) ?: Double.NaN
            if (o.isNaN()) continue
            out.add(KLine(ts.optLong(i, 0) * 1000, o,
                hh?.optDouble(i, o) ?: o, ll?.optDouble(i, o) ?: o,
                cc?.optDouble(i, o) ?: o, vv?.optDouble(i, 0.0) ?: 0.0, 0.0))
        }
        if (out.size < 3) throw Exception("行情拉取失败(网络或品种名不对)")
        return listOf(VendorKs("YH", out.takeLast(n)))
    }

    // 日线按周/月/季重采样(周一为周首,自然月/季,UTC)
    fun resampleDaily(ks: List<KLine>, tf: String): List<KLine> {
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val bucket = LinkedHashMap<Long, KLine>()
        for (k in ks) {
            val d = Calendar.getInstance(utc).apply { timeInMillis = k.t }
            val y = d.get(Calendar.YEAR)
            val m = d.get(Calendar.MONTH)
            val kk = Calendar.getInstance(utc).apply {
                clear()
                when (tf) {
                    "M" -> set(y, m, 1)
                    "Q" -> set(y, m / 3 * 3, 1)
                    else -> {
                        val jsDay = d.get(Calendar.DAY_OF_WEEK) - 1 // 日=0..六=6
                        val day = (jsDay + 6) % 7 // 周一起点偏移
                        set(y, m, d.get(Calendar.DAY_OF_MONTH) - day)
                    }
                }
            }.timeInMillis
            val e = bucket[kk]
            if (e != null) {
                bucket[kk] = e.copy(h = max(e.h, k.h), l = min(e.l, k.l), c = k.c, v = e.v + k.v)
            } else {
                bucket[kk] = KLine(kk, k.o, k.h, k.l, k.c, k.v, 0.0)
            }
        }
        return bucket.values.sortedBy { it.t }
    }

    private fun parseNasdaqDate(s: String): Long {
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val fmts = listOf(
            java.text.SimpleDateFormat("MM/dd/yyyy", Locale.US),
            java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US),
            java.text.SimpleDateFormat("MMM dd, yyyy", Locale.US)
        )
        for (f in fmts) {
            f.timeZone = utc
            f.isLenient = false
            try {
                return f.parse(s.trim())?.time ?: 0L
            } catch (_: Exception) {
            }
        }
        return 0L
    }

    // ⚠️ 保留但**已不在 fetchStocks 腿链里**(用户要求股票K线只用东财、不切源)。
    // 不删的理由:纳斯达克腿仍被 fetchTgt(机构目标均价)那条独立路径间接需要同一份网络层,
    // 且港股/韩股将来若要恢复兜底,重写这一腿成本高、易再踩 D1 那种 us 前缀的坑。
    fun fetchNasdaq(sym: String, tf: String, count: Int, timeoutMs: Int = 10000): List<VendorKs> {
        val n = max(5, min(FETCH_N, count))
        val per = when (tf) { "M" -> 40; "Q" -> 120; else -> 9 }
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val from = Calendar.getInstance(utc).apply {
            add(Calendar.DAY_OF_YEAR, -(n * per))
        }
        val df = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = utc
        }
        val fromdate = df.format(from.time)
        // assetclass=stocks 只收正股:SPY/QQQ 等 Arca ETF 回 code1001 Symbol not exists。
        // 先按 stocks 取,回包没有可用行再按 etf 取同一接口——类别由服务端回包判定,
        // 不按代码名单写死;正股首腿即中,行为与耗时不变。详情页与自选距离共用本函数。
        var daily = nasdaqDaily(sym, fromdate, "stocks", timeoutMs)
        if (daily.isEmpty()) daily = nasdaqDaily(sym, fromdate, "etf", timeoutMs)
        daily.sortBy { it.t }
        val ks = resampleDaily(daily, tf)
        if (ks.size < 3) throw Exception("行情拉取失败(网络或品种名不对)")
        return listOf(VendorKs("NQ", ks.takeLast(n)))
    }

    // nasdaq historical 单一 assetclass 取回日线行;网络/格式失败返回空列表,
    // 由 fetchNasdaq 决定是否换 etf 类重试(两轮都空才在调用方处按原口径报错)
    private fun nasdaqDaily(
        sym: String, fromdate: String, assetClass: String, timeoutMs: Int
    ): MutableList<KLine> {
        val out = mutableListOf<KLine>()
        try {
            val enc = URLEncoder.encode(sym.trim().uppercase(Locale.US), "UTF-8")
            val j = JSONObject(get(
                "https://api.nasdaq.com/api/quote/$enc/historical?assetclass=$assetClass&fromdate=$fromdate&limit=9999",
                accept = "application/json", ua = "Mozilla/5.0", timeoutMs = timeoutMs))
            val rows = j.optJSONObject("data")?.optJSONObject("tradesTable")?.optJSONArray("rows")
                ?: JSONArray()
            fun num(x: Any?): Double {
                val v = x?.toString()?.replace("$", "")?.replace(",", "")?.toDoubleOrNull()
                return v ?: 0.0
            }
            for (i in 0 until rows.length()) {
                val r = rows.optJSONObject(i) ?: continue
                val t = parseNasdaqDate(r.optString("date", ""))
                val h = num(r.opt("high"))
                if (t == 0L || !(h > 0)) continue
                out.add(KLine(t, num(r.opt("open")), h, num(r.opt("low")),
                    num(r.opt("close")), num(r.opt("volume")), 0.0))
            }
        } catch (_: Exception) {
        }
        return out
    }

    // 稿§6 腾讯腿(东财/Nasdaq不可达的兜底):美股 us+代码+后缀依次 .OQ/.N/.AR/.P,
    // 港股 hk+5位补零;字段顺序 [日期,开,收,高,低,量] 与东财(开,高,低,收)不同——
    // 收在第3位别搞反;取回日线后按 tf 重采样;少于3根视为无效换下一个key;
    // 来源标签「腾讯」(vendorName TX)
    // D1:美股 key 必须带 `us` 市场前缀(稿L857 usAAPL.OQ),漏了 `us` 整条腿对美股100%失效
    // ⚠️ 保留但**已不在 fetchStocks 腿链里**(用户要求股票K线只用东财、不切源);
    // 港/韩腿与上面 D1 的 us 前缀修法都是现成资产,将来恢复兜底直接接回,不必重写重踩坑。
    fun fetchTencent(sym: String, tf: String, count: Int, timeoutMs: Int = 9000, maxBars: Int = FETCH_N): List<VendorKs> {
        val u = sym.trim().uppercase(Locale.US)
        // 稿(可搜索:「hk+5位补零」):5位→hk+5位补零;6位→kr+6位（⚠ 括号里那句「韩股目前只有腾讯能取」
        //   是 2026-10-10 **之前**的事实：t8 起 kr 的腿链是 [东财, Naver]，本函数**已不再被韩股调用**
        //   （调用点只剩 A 股 / 港股 / 美股，见 stockLegs）；kr 这档映射保留，仅作按代码形态兜底）;
        // 其余→us+代码+交易所后缀(.OQ纳斯达克/.N纽交所/.AR/.P美股Arca)
        //
        // ⚠⚠⚠ 2026-10-04 **补 A 股**：`sh`/`sz` 前缀 6 位 → 原样小写交给腾讯。
        // 【实测 2026-10-04】`sh688825`（长鑫存储）
        //     month → 3 根（2011-01 那种老代码不该出现，只因该股 2026-07-27 才上市）
        //     day   → 47 根，首 2026-07-27 收 49.00
        //   ⟹ **腾讯是本仓当前唯一能出 A 股 K 线的源**：百度 `code_type` 只有 us/hk，
        //   东财在本机不可达（push2his / push2 实测全空）。
        //   「3 根/47 根看着太少」不是取数失败 —— 是新股真的只有这么多，
        //   `fetchStocks` 的 ≥3 根门槛刚好放行。
        // ⚠ 腾讯 key 用**小写**前缀（`sh688825`），传 `SH688825` 取不到。
        val cands = when {
            Regex("^(SH|SZ)\\d{6}$").matches(u) -> listOf(sym.trim().lowercase(Locale.US))
            Regex("^\\d{5}$").matches(u) -> listOf("hk" + u)
            Regex("^\\d{6}$").matches(u) -> listOf("kr" + u)
            else -> listOf("us$u.OQ", "us$u.N", "us$u.AR", "us$u.P")
        }
        val n = max(5, min(maxBars, count))
        val per = when (tf) { "W" -> "week"; "M" -> "month"; else -> "day" }
        for (key in cands) {
            try {
                val s = get(
                    "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=$key,$per,,,$n,qfq",
                    accept = "application/json", ua = uaRnd(), timeoutMs = timeoutMs,
                    referer = "https://gu.qq.com/")
                val d = JSONObject(s).optJSONObject("data") ?: continue
                // 稿:937-938 取 data 下的第一个 key(回传 key 可能与请求 key 不完全一致)
                val dk = d.keys().let { if (it.hasNext()) it.next() else "" }
                if (dk.isEmpty()) continue
                val arr = d.optJSONObject(dk) ?: continue
                val rows = arr.optJSONArray("qfq$per") ?: arr.optJSONArray(per) ?: continue
                val out = mutableListOf<KLine>()
                for (i in 0 until rows.length()) {
                    val a = rows.optJSONArray(i) ?: continue
                    if (a.length() < 6) continue
                    val t = parseNasdaqDate(a.optString(0).trim().take(10))
                    val o = a.optDouble(1, 0.0)
                    val c = a.optDouble(2, 0.0) // 收=第3位(腾讯序:日期,开,收,高,低,量 —— 别搞反)
                    val h = a.optDouble(3, 0.0)
                    val l = a.optDouble(4, 0.0)
                    val v = a.optDouble(5, 0.0)
                    if (t == 0L || !(h > 0)) continue
                    out.add(KLine(t, o, h, l, c, v, 0.0))
                }
                if (out.size < 3) continue
                out.sortBy { it.t }
                val ks = resampleDaily(out, tf).takeLast(n)
                if (ks.size < 3) continue
                return listOf(VendorKs("TX", ks))
            } catch (_: Exception) {
            }
        }
        return emptyList()
    }

    /**
     * ⚠⚠⚠ 2026-10-10 **韩股腿：Naver**（用户当天拍板「走 C + B」，本条是 **B**）；
     *   **2026-10-13 起升为主腿** —— 用户裁定「韩股就用 Naver」，`stockLegs` kr 分支对调为
     *   `[Naver, 东财]`，东财降为兜底。稳定性实测见 `stockLegs` kr 分支（连发 20/20 零限流）。
     *
     * 【为什么要有它 —— 腾讯这条腿占着位置却从不产出】
     *   `fetchTencent` 对韩股 `kr005850` 的 **month 与 day 都只回 1 根**
     *   （scout t3 §3 实测：`proxy.finance.qq.com`、`/appstock/app/kline/kline` 同样 1 根）
     *   ⟹ 永远过不了 `prep` 的 **≥3 根**门槛 ⟹ 东财一失败，韩股就是**假无源**
     *   （"各源都不可达"其实是"唯一的替补从来没产出过"）。
     *
     * 【路线 A 已被实测否决，别回头做】恢复 Yahoo `.KS` 兜底腿：中国大陆出口 **403**
     *   （Yahoo 自 2021-11-01 起停服大陆，返回的是停服页）。scout t3 §3-A。
     *
     * 【请求成本：零增量（这是本方案成立的前提）】
     *   `raceOk` 是**顺序替补**：前一条腿 `prep` 成功即 `break`，后面的腿**根本不发请求**。
     *   ⟹ 2026-10-13 起方向对调（Naver 在前）：**Naver 成功时东财一个请求都不发**；
     *     下面这句「东财成功时 Naver 一个请求都不发」是 t8 当时（[东财, Naver]）的方向，
     *     保留作历史 —— 两个时代的**零增量性质完全相同**，只是主次互换。
     *     Naver 失败/被熔断跳过时，那 1 次请求兜底到东财，仍是**等量替换**。
     *   ⟹ 每品种**最多 2 次**（与改前一致），符合用户 2026-10-10 的硬上限。
     *   ⛔ **绝不要"叠加"成 `[Naver, 东财, 腾讯]`**：失败路径会变成 3 次（+50%）。
     *     腾讯从来不在 kr 的腿上 —— 见 `stockLegs` 的 kr 分支。
     *   （历史原文：东财成功时 Naver 一个请求都不发（成功路径与改前完全相同）；
     *     东财失败/被熔断跳过时，那 1 次请求本来也是花在必死的腾讯腿上，这里是等量替换。）
     *
     * 【可达性】`api.finance.naver.com/siseJson.naver` 从**中国大陆出口**（广州电信
     *   218.19.7.150）HTTP 200，**不需要 Referer**，普通浏览器 UA 即可（scout t3 §3-B 实测）。
     *   日/周/月三档都可用，**有成交量**（VPVR 不会像新浪贵金属那样空白）。
     *
     * 【返回格式 —— 三个坑，改这里的人必读】（原始返回见 scout t3 §7）
     *   ① **不是合法 JSON**：单引号包裹、`\r\n\t` 缩进、韩文表头
     *      （`[['날짜','시가','고가','저가','종가','거래량','외국인소진율'], ["20261008", ...]]`）
     *      ⟹ `JSONObject(s)` **会抛异常**，只能用正则/逐行解析（本函数用正则）。
     *   ② ⚠⚠ **列序是标准 OHLC —— 与东财/腾讯都不同，极易搞反**：
     *        日期, 开, 高, 低, 收, 量      ← 本函数（Naver）
     *        日期, 开, 收, 高, 低, 量      ← 东财/腾讯
     *      所以下面取值是 o=p[2], h=p[3], l=p[4], c=p[5]；照抄 fetchTencent 的
     *      `c=a[2], h=a[3], l=a[4]` 会把**高低收整组错位**（图上不是变形，是错的）。
     *   ③ **日期是 `yyyyMMdd`**（`20261008`）⟹ 既有的 `parseNasdaqDate` 只认
     *      `MM/dd/yyyy`、`yyyy-MM-dd`、`MMM dd, yyyy`，**认不出来**（返回 0 ⟹ 整批被丢）。
     *      本函数另用 `SimpleDateFormat("yyyyMMdd")`。
     *
     * 【周期映射】`day/week/month` → 既有 `D/W/M`；**Q 不在这里合成** ——
     *   本函数对 Q 传 `timeframe=month`，由调用方 `prep` 里的 `toQuarterly` 三月合成，
     *   与东财腿（klt=103 月线 → toQuarterly）**同一条既有路径**，不新增第二套合成。
     *   ⚠ Naver 的 week/month 是**源原生的周/月 K 线**（实测：周线末根 `20261008` 量 181450、
     *     月线末根 `20261008` 量 344898，与日线 66602 不同 ⟹ 确实是周期聚合值），
     *     所以**不需要** `resampleDaily`（那是给"只有日线、要自己重采样"的腿用的，如腾讯）。
     *
     * 【收盘价口径差异（本方案唯一的代价，用户已知情拍板）】
     *   Naver 取的是**当天最后一笔（盘后/替代交易所时段）价格**：
     *     `m.stock.naver.com/api/stock/005850/basic` → `closePrice:"50,000"`、
     *     `localTradedAt:"2026-10-08T20:34:33+09:00"`（**20:34 KST**，远在 15:30 之后）。
     *   而东财/腾讯/Yahoo 取的是 **KRX 正规交易时段收盘竞价价（15:30 KST）**
     *     （⚠ 这里是**收盘价口径的对照**，不是腿链：腾讯早已不在 kr 的腿上；
     *       腿链：2026-10-10 t8 起 [东财, Naver] → **2026-10-13 起 [Naver, 东财]**（用户裁定
     *       「韩股就用 Naver」）；列腾讯/Yahoo 是因为当时的三源实测拿它们做过对照）
     *     （Yahoo `meta.regularMarketTime = 1791441002` = 2026-10-08 15:30 KST，三源同值 50300）。
     *   ⟹ **O/H/L 与东财逐位一致，C 系统性不同**：实测差 **0.1%~0.9%**
     *     （005850 最大 **0.71%**，005930 最大 **0.92%**，8/8 个交易日都不同）；
     *     成交量口径也差约 **2%**（东财 65310 vs Naver 66602，东财偏小）。
     *   ⟹ 这是**口径差，不是 bug**。2026-10-13 起 Naver 升为**主腿**（用户知情裁定
     *     「韩股就用 Naver」）⟹ 上面这个 0.1%~0.9% 的差从此**常态可见**；
     *     改前它是兜底路径差，只在东财失败时才看得到。
     *     屏上来源名会显示 `Naver`（见 [vendorName] 的 `"NV"`），**不新增任何 UI 元素**。
     *
     * 【失败即"这条腿没有"】解析不到 ≥3 根就返回 `emptyList()` ⟹ `raceOk` 按既有口径
     *   判 `noData` 并给该腿记 20s 软熔断，文案走 t6 的三类体系，本函数不自己造文案。
     *   ⚠ 实测边界（都是 HTTP 200）：单日窗返回 1 行、未来/越界窗返回 **0 行**、
     *     非韩股符号（`AAPL`）返回 **0 行** —— 三种都自然落进 `emptyList()`，不抛不崩。
     */
    fun fetchNaver(sym: String, tf: String, count: Int, timeoutMs: Int = 8000): List<VendorKs> {
        val code = sym.trim().uppercase(Locale.US)
        // 只接韩股（6 位数字）。别的市场有自己的腿链，别让这条腿被误投。
        if (!Regex("^\\d{6}$").matches(code)) return emptyList()
        val n = max(5, min(FETCH_N, count))
        // 周期 → Naver 的 timeframe（Q 用月线，合成交给调用方 prep 的 toQuarterly）
        val period = when (tf) {
            "W" -> "week"
            "M", "Q" -> "month"
            else -> "day"
        }
        // 取数窗口：Naver 只收 startTime/endTime（没有根数参数），所以按周期反推日历天数再 takeLast(n)。
        // ⚠ 系数是**反推**不是精确值：日线一年约 244 个交易日，故 1 根日线 ≈ 2.5 日历日；
        //   周线 1 根 ≈ 9 日历日（含休市）、月线 1 根 ≈ 35 日历日。都往上取整，宁可多取。
        val perBarDays = when (period) {
            "week" -> 9
            "month" -> 35
            else -> 3
        }
        val sdf = java.text.SimpleDateFormat("yyyyMMdd", Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val now = System.currentTimeMillis()
        val endTime = sdf.format(java.util.Date(now))
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = now
        cal.add(java.util.Calendar.DAY_OF_YEAR, -perBarDays * n)
        val startTime = sdf.format(cal.time)
        val u = "https://api.finance.naver.com/siseJson.naver?symbol=" + code +
            "&requestType=1&startTime=" + startTime + "&endTime=" + endTime +
            "&timeframe=" + period
        // ⚠ 用 uaRnd()（既有实现）而不是写死 UA：与其它腿同一套反限流约定。
        val s = get(u, null, uaRnd(), timeoutMs)
        // 正则逐行取值。列序见上方坑②：日期, 开, 高, 低, 收, 量（**标准 OHLC**）。
        val re = Regex(
            "\\[\\s*\"(\\d{8})\"\\s*,\\s*([-0-9.]+)\\s*,\\s*([-0-9.]+)\\s*," +
                "\\s*([-0-9.]+)\\s*,\\s*([-0-9.]+)\\s*,\\s*([-0-9.]+)"
        )
        val out = mutableListOf<KLine>()
        for (m in re.findAll(s)) {
            val t = parseNaverDate(m.groupValues[1])
            val o = m.groupValues[2].toDoubleOrNull() ?: 0.0
            val h = m.groupValues[3].toDoubleOrNull() ?: 0.0
            val l = m.groupValues[4].toDoubleOrNull() ?: 0.0
            val c = m.groupValues[5].toDoubleOrNull() ?: 0.0
            val v = m.groupValues[6].toDoubleOrNull() ?: 0.0
            if (t == 0L || !(h > 0)) continue
            out.add(KLine(t, o, h, l, c, v, 0.0))
        }
        if (out.size < 3) return emptyList()
        out.sortBy { it.t }
        val ks = out.takeLast(n)
        if (ks.size < 3) return emptyList()
        return listOf(VendorKs("NV", ks))
    }

    /**
     * ⚠ 2026-10-10 Naver 的日期是 **`yyyyMMdd`**（`20261008`），既有的 [parseNasdaqDate]
     *   只认 `MM/dd/yyyy`、`yyyy-MM-dd`、`MMM dd, yyyy` ⟹ 认不出来会返回 0 ⟹ 整批被丢。
     *   与 [parseNasdaqDate] 同一套约定：UTC、`isLenient=false`、解析失败返回 0（**不抛**）。
     *   ⚠ 用 UTC 而不是 KST：本仓所有 K 线日期都按"日期标签"处理（见 SplitBook.apply 的注释
     *     「数据源的 t 是日期不是时刻」），保持一致才能让跨日/跨除权日的比较不出错。
     */
    private fun parseNaverDate(s: String): Long {
        return try {
            val f = java.text.SimpleDateFormat("yyyyMMdd", Locale.US)
            f.timeZone = java.util.TimeZone.getTimeZone("UTC")
            f.isLenient = false
            f.parse(s.trim())?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    // Yahoo 符号按市场加后缀(港 .HK / 韩 .KS,美股原样)
    private fun yahooSym(sym: String): String {
        val u = sym.trim().uppercase(Locale.US)
        return when (symType(u)) {
            "hk" -> "$u.HK"
            "kr" -> "$u.KS"
            else -> u
        }
    }

    // 东方财富 K 线超时(ms)。**勿再下调**:
    //   v3.7.2(d3c6bc9)调东财不传超时 → 走 fetchEastmoney 默认 10000ms,真机一直正常;
    //   v4 重构时把 fetchStocks 的腿链写成 fetchEastmoney(..., 6000),真机上东财常 >6s,
    //   于是每腿都被砍掉、直接落到 Nasdaq(其自身约 4.9s),表现为「慢 + 源一直显示纳斯达克」。
    //   用户反馈:「之前的东方财富一直都行…你更新到 v4.1 才一直拉取的是纳斯达克」。
    //   同一份代码、同一个网络,只有超时变了 —— 根因就是 6000。
    const val EM_TIMEOUT_MS = 10000

    // 内部标记:请求**成功返回了**,但没有该品种的 K 线(正常响应里 data 为空 / 不足 3 根)。
    // 与之相对,网络失败/超时/HTTP 非 2xx 是 get() 直接抛出的普通异常。
    // 用**类型**而不是文案来区分这两类,文案可能被各调用方改写、不可靠;
    // 异常 message 保持原样,不影响既有调用方(fetchMetals 那条链自己 catch 后换成泛化文案)。
    internal class EmNoDataException(msg: String) : Exception(msg)

    // 股票 K 线**只用东方财富单源**(美/港/韩共用)。用户明确要求「不切源」:
    // 宁可显示无源,也不要静默换成纳斯达克/腾讯/Yahoo —— 静默换源会让用户
    // 以为看到的是同一条数据,实际口径已变(源名会显示在图例注记里)。故此处不再做腿链。
    //
    // 失败文案分两类,**不混成一句**,免得用户误以为是自己填错了品种:
    //   ① 抛异常(连接失败/超时/HTTP 非 2xx —— get() 在这三处都会抛)→「东方财富不可达」= 网络或对方限流,请稍后重试;
    //   ② 正常返回但列表为空(响应成功、只是没有这个品种的 K 线)→「东方财富没有该品种数据」= 品种代码可能不对。
    // 判据就是「抛异常 vs 返回空」,不靠猜。
    // ---------- 稿:950-961 反限流:随机UA + 失败源短时记忆 ----------
    // 随机UA(开源项目通用做法):固定 UA 容易被识别成爬虫。
    private val UAS = arrayOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36 Edg/122.0.0.0",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.3 Safari/605.1.15",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.3 Mobile/15E148 Safari/604.1"
    )
    fun uaRnd(): String = UAS[(Math.random() * UAS.size).toInt()]

    // 东财 push2his 有反爬(社区项目原话:"该接口有反爬,出网 IP 常被限流"),猛打只会把临时故障拖成长时间故障
    private val SRCBAD = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val SRCBAD_MS = 10 * 60 * 1000L

    /**
     * ⚠⚠⚠ 2026-10-10 **熔断键 = 「源 × 市场」**（键长这样：`东财|kr`）。
     *
     * 【为什么必须隔离（scout t3 实证：tools/韩股取数方案-20261010.md §2.4）】
     *   改前键就是腿名字符串（`"东财"`），而**贵金属链 / 步长链 / 韩股链共用这一个键**：
     *   黄金那边东财超时一次 ⟹ `srcBadMark("东财")` ⟹ **10 分钟内韩股的东财腿一起被熔断**
     *   （反之亦然）。韩股的替补腿腾讯对韩股**恒返回 1 根**、永远过不了 prep 的 ≥3 根门槛
     *   ⟹「东财被连坐 → 只剩腾讯 → 腾讯必死 → 全灭」，而文案还报成「各行情源都没有该品种数据」。
     *   ⚠ 上面这两行写的是**当时（2026-10-10 之前）**的链路：t8 起 kr 的第二腿已由腾讯换成 Naver
     *     （`stockLegs` kr 分支 = `[东财|kr, Naver|kr]`）⟹ 现在「只剩一条必死腿」那种形状不再来自腾讯；
     *     本段论证的**隔离理由**（跨链连坐）与判据不变。
     *   ⚠ 这个连坐**不是**"限流保护"，是故障串台：东财对韩股一直是好的（实测 HTTP 200 / 240 根）。
     *
     * 【改法】只把**键**变细，表还是那两张表（SRCBAD / SRCBAD_SOFT），
     *   `srcBadMark` / `srcBadSkip` / `srcBadAt` 的签名与语义一个字没改 —— 不新建第二套熔断表。
     *   腿名带上市场后，`raceOk` 里的 `srcBadMark(nm)` 与各链的
     *   `filter { !srcBadSkip(it.first) && !softSrcSkip(it.first) }` **一行都不用动**，隔离自动生效。
     *   UI 侧一律过 [vendorName]（它会剥掉 `|市场` 后缀），与 FavPanel.kt:650 那句
     *   「腿名必须过 vendorName()」的既有约定一致。
     *
     * ⚠ 与 [HttpGate] 的键**刻意不同**：闸按 **host**（限流是出口 IP 级的，与市场无关），
     *   熔断按 **源×市场**（连坐是 bug，要隔离）。两者目的不同，别合并成一张。
     */
    private fun srcKey(vendor: String, market: String): String =
        vendor + "|" + (if (market.isEmpty()) "stock" else market)

    /**
     * ⚠⚠ 2026-10-03 **空响应短熔断**（用户裁定「两条一起」中的 A 条）。
     *
     * 病根：限流返回的是**空**，不是异常。而 srcBadMark 只在 `catch` 分支被调用 ⟹
     *   空响应永远不记进 SRCBAD ⟹ 下次照打照空 ⟹ 「—无源」永不恢复，也没有任何退避。
     *   实测判别实验：同一 URL 第1次空 → 等45s → 第2次40根 ✅ → 立刻第3次空 ❌。
     *
     * ⟹ 空响应现在也记一次**短**熔断，让后续请求在冷却期内跳过这一条腿，
     *   把额度让给还没试的腿/品种。
     *
     * ⚠⚠ **为什么是独立的表、而不是直接写 SRCBAD**：
     *   SRCBAD 的冷却是 10 分钟（那是给「网络不可达」准备的）。
     *   限流只要几十秒就恢复 —— 用 10 分钟会把源长时间钉死，代价远大于收益。
     *   共用一个表就只能二选一，所以另开一张 SRCBAD_SOFT，冷却 20s。
     *   ⚠ srcBadSkip 只认 SRCBAD，**本表不被它读**；读取发生在本文件内
     *   fetchStocks/fetchMetals 各腿的 `filter` 处（见 softSrcSkip）。
     *   ⚠ fetchCrypto 走 FavDist 自己的腿循环、不读本表（旧注释里那句把它算进来是不准的）。
     *   ⚠⚠ 2026-10-10 **取数入口审计（t6，逐条核对过，不是推断）**：全项目 `raceOk` 只有**三个**
     *     调用点 —— fetchStocks ✅ / fetchMetals ✅（这两处本来就 filter），**stepCloses 原来漏接
     *     ⟹ 本次已接上**（见那里的注释）。其余取数路径（fetchTgt / FX / NameBook / MktSuggest /
     *     fetchCryptoLeg / fetchSina[死代码]）**不走 raceOk、本来就不进这两张表**，不是漏接。
     *     ⚠ 另外新增的**出网闸** [HttpGate] 在 `get()` 出口，与这两张表无关：
     *     所有出网请求都过它（全仓 `openConnection` 只有 get() 一处），**没有旁路**。
     */
    private val SRCBAD_SOFT = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val SRCBAD_SOFT_MS = 20 * 1000L

    /**
     * ⚠ 2026-10-10 源名 → host 的路由常量。**不是第二张熔断表**：它没有状态、没有时间戳，
     *   只把既有腿名映射到 host，用途只有一个 —— 把 [srcSoftMark]（既有的"空响应"事件）
     *   落到 host 上，喂给 [HttpGate] 的冷却（③）。表本身仍只有 SRCBAD / SRCBAD_SOFT 两张。
     *   ⚠ 只列**有 K 线腿、且会被 raceOk 标软熔断**的源；腾讯的名称补抓走 qt.gtimg.cn
     *     （NameBook 自带 MISS_COOLDOWN 冷却），这里记的是它的 K 线 host。
     */
    private val SRC_HOST = mapOf(
        "东财" to "push2his.eastmoney.com",
        "百度" to "finance.pae.baidu.com",
        "腾讯" to "web.ifzq.gtimg.cn",
        "新浪" to "stock.finance.sina.com.cn",
        "Nasdaq" to "api.nasdaq.com",
        "Yahoo" to "query1.finance.yahoo.com",
        // ⚠ 2026-10-10 韩股兜底腿（fetchNaver）。不列这里的后果：Naver 空响应时
        //   `srcSoftMark` 拿不到 host ⟹ 闸的冷却不生效（`?.let` 静默跳过），
        //   熔断仍按 SRCBAD_SOFT 走，只是少了一层出网保护。
        "Naver" to "api.finance.naver.com"
    )

    private fun srcSoftMark(name: String) {
        SRCBAD_SOFT[name] = System.currentTimeMillis()
        // ⚠⚠ 2026-10-10 **空响应这条既有事件同时喂给闸的冷却**（"与 softSrcSkip 呼应、不重复造表"）：
        //   状态仍只存在 SRCBAD_SOFT 这一张表里；闸只是把**同一个事件**落到 host 上，
        //   让"刚刚空过"的那个出口 IP 在冷却窗内把发车时刻往后推（见 HttpGate 的 ③）。
        SRC_HOST[name.substringBefore('|')]?.let { HttpGate.cool(it) }
    }

    /**
     * ⚠ 2026-10-10 该腿当前还剩多久冷却（硬 10 分钟 / 软 20s 取较大者），0 = 没有冷却。
     *   只**读**既有的两张表，用于把「限流冷却中，已跳过」这句文案说具体（还有几秒），
     *   不新增任何状态。
     */
    private fun srcCoolLeftMs(name: String): Long {
        val now = System.currentTimeMillis()
        val hard = SRCBAD[name]?.let { (SRCBAD_MS - (now - it)).coerceAtLeast(0L) } ?: 0L
        val soft = SRCBAD_SOFT[name]?.let { (SRCBAD_SOFT_MS - (now - it)).coerceAtLeast(0L) } ?: 0L
        return max(hard, soft)
    }
    /** 限流软熔断：冷却 20s 内跳��这一条腿。与 [srcBadSkip] 的 10 分钟硬熔断并存。 */
    private fun softSrcSkip(name: String): Boolean {
        val t = SRCBAD_SOFT[name] ?: return false
        return System.currentTimeMillis() - t < SRCBAD_SOFT_MS
    }

    fun srcBadMark(name: String) { SRCBAD[name] = System.currentTimeMillis() }
    fun srcBadSkip(name: String): Boolean {
        val t = SRCBAD[name] ?: return false
        return System.currentTimeMillis() - t < SRCBAD_MS
    }
    /** t102 第3步:取某源最近一次失败的时刻,用于源状态行算"冷却中 N 分 M 秒" */
    fun srcBadAt(name: String): Long? = SRCBAD[name]
    fun srcClear() { SRCBAD.clear() }

    /** 逐腿状态,只给 UI 看。**t102 第3步:只加字段透传,不改取数行为。** */
    class LegInfo(
        val name: String,
        val hit: Boolean,      // 本次竞速命中
        val failed: Boolean,   // 投了但失败(网络/限流)
        val skipped: Boolean   // 没投:10 分钟失败记忆把它排除了(handoff 要求可见)
    ) {
        /** 失败源冷却剩余毫秒(没投或刚失败时有效) */
        fun cooldownMs(now: Long = System.currentTimeMillis()): Long {
            if (!failed && !skipped) return 0L
            val t = srcBadAt(name) ?: return 0L
            return (SRCBAD_MS - (now - t)).coerceAtLeast(0L)
        }
    }

    /** 竞速结果:赢家 + 各腿的失败性质(抛异常=网络/限流;返回空=源正常但没这个品种) + 逐腿明细 */
    private class Race(
        val win: VendorKs?,
        val anyNetFail: Boolean,
        val anyNoData: Boolean,
        val legs: List<LegInfo> = emptyList()
    )

    /**
     * t102 第3步:**每个品种**最近一次竞速的逐腿结果,供源状态行渲染。
     * handoff §5.4 的病根是"机制有了、界面没有"——用户不知道是哪个源挂了。
     * 这里只**透传** SRCBAD/raceOk 已经算出来的东西,不新增任何取数逻辑、不新增任何定时器。
     *
     * ⚠⚠⚠ 2026-10-12 **跨品种串显示**（用户当天实测截图，t2 修）：
     *   【现象】行情页切到美股 TSLA，价格块右上显示「实时 · **Naver**」——
     *     Naver 只在 **kr 腿**上（stockLegs 的 kr 分支 = [东财|kr, Naver|kr]，
     *     `fetchNaver` 全仓只有那一处调用），TSLA 这一轮根本没投过它。
     *   【机制】改前这里是**全局一个** `lastLegs`（**不分品种**），`publishLegs` 每次取数
     *     **整体覆盖**。用户先看韩股（Naver 兜底命中）→ 切到 TSLA → TSLA 走的是
     *     loadStockParallel 的**内存/落盘缓存命中**分支（MktCache.get / MktSave.saved 早退，
     *     **不调 fetchStocks** ⟹ 这一轮**不发布新腿**）→ onInfo 照常调 t102PaintSourceRow
     *     读腿，读到的却是**上一个品种**那批 → 串显示。TSLA 取数**还没回**时同理：
     *     旧腿一直挂在全局上。
     *   【修法】**按品种记账**：批次按品种 key 存进 [legsBySym]，读取一律走 [legsOf] /
     *     [legsAtOf] —— 只会拿到**该品种自己**最近那批；本进程没为它发过布 ⟹ 空 ⟹
     *     行情页走 t123PaintNoData 显示「未取到」，**不显示别人的腿**。
     *     ⚠ **不改取数 / 竞速 / 熔断**：publishLegs/publishFavLegs 只是各多一个 sym 形参
     *       （由它写进 map），raceOk 的替补顺序与 srcBad/软熔断一行未动。
     *     ⚠ 不留公开的「全局最新一批」出口：留了就一定有人绕过品种直接读 —— 那正是本次的入口。
     *
     * 【规模】键 = 本进程看过的品种数（进程内存态，杀进程即清）；每条只是几个 LegInfo 的
     *   小列表，自选页一轮刷新按自选数写入，无失控增长。
     */
    /** 一批腿的载体：腿 + 产生时刻（t114 时间诚实性：`at` 是**值实际产生**的时刻，非当前时刻）。 */
    private class LegBatch(val legs: List<LegInfo>, val at: Long)

    /**
     * 按品种存的腿批次（键的口径见 [batchOf]）。
     * 取数线程写、UI 线程读 ⟹ 必须是并发容器：`get` 原子，不存在"读到一半"。
     */
    private val legsBySym =
        java.util.concurrent.ConcurrentHashMap<String, LegBatch>()

    /** 品种规范化：与 mkLoad 写 lastSym 的口径一致（trim + 大写），两侧都过它才可比。 */
    private fun normSym(s: String): String = s.trim().uppercase(Locale.US)

    /**
     * 屏幕品种 → **实际投出去的取数键**（gold→`GC=F`、silver→`SI=F`，其余 = 规范化原码）。
     *
     * 【为什么腿的品种记账需要它】贵金属链投的是**映射后的码**：用户屏上是 `XAUUSD`，
     * `mkLoad` 传给 `fetchMetals` 的是 `ysym = GC=F` ⟹ 发布出来的腿批次挂在 `GC=F` 名下；
     * 只按屏幕名查会查不到 ⟹ **黄金自己的源名行反而变成「未取到」**（那样修就是新造回归）。
     * ⟹ 查询时屏幕名与取数键**两者任一命中**即算本品种（见 [batchOf]）。
     *
     * ⚠ **唯一定义处**：MktPanel.mkLoad 的 `ysym` 也调它 —— 两处各写一份 `when` 必然漂移。
     *   （等价性：`symType` 内部先 trim+uppercase ⟹ `symType(s) == symType(normSym(s))`，
     *     所以拿 `su` 算与原先 mkLoad 拿 `type` 算逐字相同。）
     */
    fun fetchKeyOf(sym: String): String {
        val u = normSym(sym)
        return when (symType(u)) {
            "gold" -> "GC=F"
            "silver" -> "SI=F"
            else -> u
        }
    }

    /** 该品种批次的读取：先试规范化屏幕名，再试取数键（贵金属别名，见 [fetchKeyOf]）。 */
    private fun batchOf(sym: String): LegBatch? {
        val u = normSym(sym)
        return legsBySym[u] ?: legsBySym[fetchKeyOf(sym)]
    }

    /**
     * 读**指定品种**最近一次竞速的腿。
     * @return 该品种自己的批次；**本进程没为它发过布 → emptyList()**
     *   （调用方据此走「无腿 / 未取到」，绝不显示上一个品种的腿）。
     */
    fun legsOf(sym: String): List<LegInfo> = batchOf(sym)?.legs ?: emptyList()

    /** 同 [legsOf]：本品种没有批次 → 0L（读取侧据此不报别人的产生时刻）。 */
    fun legsAtOf(sym: String): Long = batchOf(sym)?.at ?: 0L

    /**
     * ⚠ 墓碑 · 2026-10-12 **`lastLegsCount` 已删除**（修「TSLA 显示 Naver」那轮随 t2 清理）。
     *   它曾是 t324 为稿 L318 的 `{win} · 42 根` 存的根数，由 MktPanel 在 publishFavLegs 旁写入。
     *   删除理由：**全工程零读取点**（屏上「· N 根」早在 t45 就已去掉，见 FavPanel 那段注释），
     *   而 MktPanel 侧的旧注释还声称「被 FavPanel 读去拼「· N 根」」—— 注释与代码互相矛盾，留着只会误导。
     *   需要根数时的正确来源是 [legsOf] 那批的 `info.count`。
     */

    /**
     * t132:竞速进行中。
     *
     * 为什么需要它:腿账本([legsBySym],2026-10-12 起按品种存)的语义是「**上一次已完成**竞速的
     * 结果」(`publishLegs` 只在竞速**结束后**调用),所以**取数途中该品种名下挂的仍是上一次的结果**。
     * t131 我用 `legs.isNotEmpty() && !hit` 判 `err`,于是**取数中途把上一次失败
     * 误报成"刚刚全挂了"**。这个标志把「正在取」和「取完了且全挂」彻底分开。
     *
     * 🔴 复位在 **finally** 里 —— 异常路径也必须复位,否则会永远卡在 load。
     */
    @Volatile var fetching: Boolean = false
        private set

    /**
 * ⚠⚠⚠ 2026-10-03 **竞速改替补**（用户裁定：「取消竞速模式，采用替补模式」）。
 *
 * 【为什么必须改】
 * 改前是**并行竞速**：所有腿同时发，**谁先回谁赢**。
 * 这带来一个真实故障（用户实报「第二次点进黄金，VPVR 就消失了」）：
 *   黄金的新浪腿 `volume` 字段**恒为 "0"**（GC/SI 各 2590 条，volume>0 为 0 条），
 *   而它**响应比东财快** ⟹ 经常抢在东财之前赢 ⟹ K线成交量全 0 ⟹ **VPVR 整片空白**。
 *   而且**谁赢不确定** ⟹ 表现为「第一次有、第二次没了」，看起来像缓存坏了，实际是竞速抖动。
 *
 * 竞速的隐含前提是「各腿数据等价」—— 这条不成立：
 * 有的腿给的价格全、有的腿给的成交量是 0，**先到不等于更好**。
 *
 * 【现在的语义】**顺序替补**：按 pool 顺序**依次**试，
 *   第一条 `prep` 成功的腿即胜出，后面的腿**根本不发请求**（标 skipped）。
 * ⟹ 结果**确定**：列表里第一条能用的永远赢，不受网络快慢影响。
 * ⟹ 代价：慢源排在后面时要等前面的失败才轮到（每腿各自有 HTTP 超时，天然有界）。
 *
 * 【保留不变】
 *   · 返回类型 Race(winner, netFail, noData, legs) 不变 ⟹ **三个调用点一行都不用改**。
 *   · 硬/软熔断标记照打（srcBadMark / srcSoftMark），熔断过的腿在进 pool 前已被滤掉。
 *   · LegInfo 的 hit / failed / skipped 语义不变，publishLegs 的文案逻辑不受影响。
 *   · waitMs 仍作为**总预算**：超预算就不再试下一条腿，直接用当前结果收场。
 */
private fun raceOk(
        pool: List<Pair<String, () -> List<VendorKs>>>,
        prep: (VendorKs?) -> VendorKs?,
        waitMs: Long
    ): Race {
        if (pool.isEmpty()) return Race(null, false, false)
        val info = java.util.concurrent.ConcurrentHashMap<String, LegInfo>()
        for ((nm, _) in pool) info[nm] = LegInfo(nm, hit = false, failed = false, skipped = false)
        val deadline = System.currentTimeMillis() + waitMs
        var netFail = false
        var noData = false
        var winner: VendorKs? = null
        val tried = mutableListOf<String>()
        for ((nm, fn) in pool) {
            if (System.currentTimeMillis() >= deadline && tried.isNotEmpty()) {
                // 总预算耗尽：剩下的腿没试，如实标 skipped，不假装它们失败
                info[nm] = LegInfo(nm, hit = false, failed = false, skipped = true)
                continue
            }
            tried.add(nm)
            var v: VendorKs? = null
            var threw = false
            try { v = prep(fn().firstOrNull()) } catch (_: Exception) { srcBadMark(nm); threw = true }
            // ⚠ 空响应也软熔断（限流）。抛异常走 10 分钟硬熔断；空响应只走 20s 软熔断。
            if (v == null && !threw) srcSoftMark(nm)
            if (threw) netFail = true else if (v == null) noData = true
            if (v != null) {
                info[nm] = LegInfo(nm, hit = true, failed = false, skipped = false)
                winner = v
                break   // ← 替补模式：**赢面即止**，后面的腿不发请求
            }
            info[nm] = LegInfo(nm, hit = false, failed = threw, skipped = false)
        }
        // 胜出之后没轮到的那几条，如实标 skipped
        val hitAny = winner != null
        for ((nm, _) in pool) {
            val cur = info[nm] ?: continue
            if (cur.skipped || cur.hit || cur.failed) continue
            if (!hitAny && tried.contains(nm)) continue   // 试过但没成，failed 已在上面记
            info[nm] = LegInfo(nm, hit = false, failed = false, skipped = true)
        }
        return Race(winner, netFail, noData, info.values.toList())
    }

    /**
     * ⚠⚠⚠ 2026-10-10 **失败文案第三类**（前两类：源不可达 / 没有该品种数据）。
     *
     * 【为什么必须补这一类】前两类的判据都是「**投出去的腿**的结果」（抛异常 / 返回空）。
     *   而韩股那个场景里主力腿**根本没投**：东财被（当时还没按市场隔离的）熔断键挡在 pool 之外
     *   ⟹ `rest` 只剩腾讯 ⟹ 腾讯对韩股恒 1 根、必死 ⟹ `win == null && anyNoData == true`
     *   （⚠ 这一段是**当时**的链路：t8 起 kr 第二腿已是 Naver，不再是腾讯 —— 链路形状变了，
     *     但「有腿没投」这个判据与下面第三类文案都不变）
     *   ⟹ 报成「各行情源都没有该品种数据」——**把"我们没问到"说成"你没这个品种"**，
     *   用户会去反复检查代码拼写（scout t3 实测结论：tools/韩股取数方案-20261010.md §2.4）。
     *
     * 【判据（可指认，不靠猜）】`legs.size > pool.size` ⟺ **有腿压根没投**（被 10 分钟硬熔断
     *   或 20s 软冷却滤掉）。此时前两类都不成立 ⟹ **先判第三类**。
     *   ⚠ 反过来：`pool == legs`（"全被熔断也全试"的回退）时**不报**第三类 —— 那几腿是**真投了**，
     *   它们的结果就该由前两类如实表达。
     *   ⚠ 文案里刻意**不含**「都没有该品种数据」「都不可达」这两个子串：MktPanel.t210FailText
     *   按 `contains` 分档，含了就会被翻成"无此品种/各源不可达"，又绕回错的那一句；
     *   不含则走它的 `else -> msg` 原样透传（该函数注释原话：「认不出来的 message 一律原样透传」）。
     */
    private fun allDeadText(
        legs: List<Pair<String, () -> List<VendorKs>>>,
        pool: List<Pair<String, () -> List<VendorKs>>>,
        anyNoData: Boolean
    ): String {
        val tried = pool.map { it.first }.toSet()
        val notTried = legs.map { it.first }.filter { it !in tried }
        if (notTried.isNotEmpty()) {
            val left = notTried.maxOf { srcCoolLeftMs(it) } / 1000
            val names = notTried.joinToString("、") { vendorName(it) }
            return "限流冷却中，已跳过（" + names +
                (if (left > 0) "，约 ${left}s 后恢复" else "") + "），当前无兜底源"
        }
        return if (anyNoData) "各行情源都没有该品种数据" else "各行情源都不可达,请稍后重试"
    }

    /**
     * t112:自选页取数链(FavDist)也发布腿信息。
     * 原来 publishLegs 是 private 且只在行情页那条链上调用,导致冷启动直接进自选页时
     * 腿表是空的、源状态行整行隐藏 —— **用户打开 App 第一眼没有源信息**。
     * 这里给出公开入口,让自选页**不依赖行情页**也能显示源状态行。**同一套机制,不是第二套。**
     *
     * ⚠ 2026-10-12 t2 起多一个 [sym]：这批腿**记到该品种名下**（写进 [legsBySym]）。
     *   没有它就等于又造一个"无主"的批次 —— 那正是「TSLA 显示 Naver」的病根。
     */
    fun publishFavLegs(sym: String, rows: List<LegInfo>) {
        legsBySym[normSym(sym)] = LegBatch(rows, System.currentTimeMillis())
        fetching = false
    }

    /**
     * t102 第3步:把逐腿状态发布给 UI。
     * `all` 是这一品种**本可以投的全部腿**,`pool` 是实际投出去的(被 SRCBAD 记忆排除的算 skipped)。
     * 只透传,不改变竞速行为。
     *
     * ⚠ 2026-10-12 t2：多一个 [sym]（这批腿记到谁名下，见 [legsBySym]）。
     *   **取数 / 竞速 / 熔断一行未动** —— 这里只是把结果落进按品种的账本。
     */
    private fun publishLegs(
        sym: String,
        all: List<Pair<String, () -> List<VendorKs>>>,
        pool: List<Pair<String, () -> List<VendorKs>>>,
        r: Race
    ) {
        val byName = r.legs.associateBy { it.name }
        val rows = all.map { (nm, _) ->
            byName[nm] ?: LegInfo(nm, hit = false, failed = false, skipped = true)
        }
        legsBySym[normSym(sym)] = LegBatch(rows, System.currentTimeMillis())
    }
    // 稿:1027-1034 stockLegs:美股/港股→[百度,腾讯];韩股→[东财, Naver];商品不走这里
    // ⚠ 韩股那半句**已不是稿的原文**：稿是 [腾讯, 东财]；本仓先改成 [东财, 腾讯]（2026-10-07），
    //   2026-10-10 t8 又换成 [东财, Naver]（腾讯对韩股恒 1 根、占着位置却不产出）。详见 kr 分支上方那段。
    // ⚠ 2026-10-07 外汇/美债的 Yahoo 专属腿已**整体退役**（用户：不要外汇，美债只留 TLT）。
    //   TLT 是 ETF → 落到最后的 stock 腿，与其他美股同路由，不需要专腿。
    private fun stockLegs(sym: String, tf: String, count: Int, maxBars: Int = FETCH_N): List<Pair<String, () -> List<VendorKs>>> {
        val ty = symType(sym.trim().uppercase(Locale.US))
        if (ty == "cmdty" || ty == "gold" || ty == "silver") return emptyList()
        // 稿(可搜索:「韩股 = [腾讯, 东财]」)的原始顺序是 [腾讯, 东财]；本仓先后改成
        //   [东财, 腾讯]（2026-10-07）→ [东财, Naver]（2026-10-10 t8）→
        //   **[Naver, 东财]（2026-10-13 用户裁定「韩股就用 Naver」，主次对调）**。
        // ⚠⚠⚠ 2026-10-13 **主腿 腾讯/东财 → Naver**（用户当天裁定；稳定性实测见下）。
        //   【为什么换】用户实报韩股行情慢；本机实测东财韩股 0.16~0.26s、Naver 0.17~0.33s
        //     **两端都不慢** ⟹ 慢源在「手机到东财」的路由/抖动上（单看本机无法定位手机端），
        //     但顺序替补意味着韩股**每次先付东财的往返**，东财一抖就整屏等满超时。
        //     把实测同样快、且连发 20/20 不限流的 Naver 放前面，把这条不确定路径挪到兜底位。
        //   【稳定性实测(2026-10-13, tools/naver_soak1.py)】2 品种 × 日/月 × 5 连发 =
        //     20/20 HTTP 200、行数全对（日 26 根/月 13 根），avg 0.39s、max 1.23s、零限流。
        //   【为什么 Naver】`api.finance.naver.com/siseJson.naver` 从**中国大陆出口**实测
        //     HTTP 200、不需 Referer、日/周/月三档齐全、**有成交量**（scout t3 §3-B）。
        //     路线 A（恢复 Yahoo `.KS`）已被实测否决：大陆出口 403（Yahoo 停服大陆），别回头做。
        //   【成本】**零增量**不变：`raceOk` 顺序替补、赢面即止 ⟹ Naver 成功时东财一个请求都不发；
        //     东财失败时那 1 次本来也花在必死的腾讯腿上 ⟹ 每品种**仍是最多 2 次**（硬上限）。
        //     ⛔ 绝不要叠加成 [Naver, 东财, 腾讯]（失败路径变 3 次，用户硬上限是 2 次）。
        //   【口径代价 · 从「兜底可见」升级为「常态可见」】Naver 的**收盘价是盘后价**（20:34 KST），
        //     东财/腾讯是 15:30 KRX 收盘竞价价 ⟹ 现价/涨跌与东财口径系统性差 0.1%~0.9%、
        //     成交量差约 2%；O/H/L 逐位一致。完整说明与实测数字见 `fetchNaver` 的注释。
        //     来源名显示 `Naver`（既有 "NV" 映射，零 UI 改动）。不满意可回退：对调两行即回到
        //     2026-10-10 的 [东财, Naver]，注释历史都在。
        //   ⚠ 腾讯腿**没删**：它仍是 A 股/港股/美股的腿（见下方分支），只是不再占韩股第二腿。
        if (ty == "kr") return listOf(
            srcKey("Naver", "kr") to { fetchNaver(sym, tf, count) },
            srcKey("东财", "kr") to { fetchEastmoney(sym, tf, count, EM_TIMEOUT_MS) }
        )
        // ⚠⚠⚠ 2026-10-04 **A 股单腿走腾讯**。
        // 【为什么不竞速】`fetchBaidu` 的白名单是 `ctype != "hk" && ctype != "stock" → return emptyList()`，
        //   本来就不接 ashare；硬把它塞进腿链只会让**每次**都白打一次百度。
        //   而且 A 股只有一条腿，竞速没有意义 —— 单腿直接省掉一次请求。
        if (ty == "ashare") return listOf(
            srcKey("腾讯", "ashare") to { fetchTencent(sym, tf, count, 8000, maxBars) }
        )
        // ⚠⚠ 2026-10-13 用户裁定「对齐全量分红前复权」：美股腿 **腾讯 → 东财**（首腿）。
        //   【实测依据 tools/em_tlt_probe1_out.txt】东财 fqt=1 = 深分红前复权：TLT 2023-10 低
        //     72.527 对上用户参照图的 72.52、2021-12 高 130.007 对上 129.96、现价 78.05 与腾讯
        //     一致（前复权不改现价）；而腾讯 fqkline 的 qfq 历史偏浅（同月 82.42/146.94）
        //     ⟹ 「哪个月最低」排序都被改写。坑位保持**最多 2 次**硬上限：东财失败才投腾讯。
        //   ⚠ 百度退出美股腿：与腾讯同属浅复权，且 2 腿上限必须让位（百度曾为首腿，历史见 git）。
        //   ⚠ 连带：K线/支撑/腰斩/步长随深复权序列一次性下移，属预期行为不是回归。
        if (ty == "stock") return listOf(
            srcKey("东财", ty) to { fetchEastmoney(sym, tf, count, EM_TIMEOUT_MS) },
            srcKey("腾讯", ty) to { fetchTencent(sym, tf, count, 8000, maxBars) }
        )
        return listOf(
            srcKey("百度", ty) to { fetchBaidu(sym, tf, count, maxBars) },
            srcKey("腾讯", ty) to { fetchTencent(sym, tf, count, 8000, maxBars) }
        )
    }

    // 稿:1033-1052 fetchStocks(多源竞速版)
    fun fetchStocks(sym: String, tf: String, count: Int): List<VendorKs> {
        val n = max(5, min(FETCH_N, count))
        val prep = fun(v: VendorKs?): VendorKs? {
            if (v == null || v.k.size < 3) return null
            var kk = if (tf == "Q") toQuarterly(v.k) else v.k
            kk = kk.sortedBy { it.t }.takeLast(n)
            return if (kk.size < 3) null else VendorKs(v.v, kk)
        }
        val legs = stockLegs(sym, tf, count)
        if (legs.isEmpty()) throw Exception("该品种没有可用行情源")
        // 反限流:刚失败的源短时不再投。**第一腿永远保留**——否则两源都被标失败时会一腿都不发、
        // 彻底取不到数而且没有任何东西来"解毒";记忆只用来排除次要源。
        val rest = legs.filter { !srcBadSkip(it.first) && !softSrcSkip(it.first) }
        // ⚠ 2026-10-07 泛化修无源：全被软熔断时只保第一腿会把“已知弱腿在前”的品种判死
        //   （⚠⚠ **例子里的韩股那半句是历史，别当现状读**：2026-10-10 之前 kr 的腿是
        //     [东财, 腾讯]、腾讯对韩股恒 1 根；2026-10-10 t8 已把第二腿换成 [东财, Naver]
        //     （见 stockLegs 的 kr 分支）⟹ 这个例子不再描述当前链路，**但它论证的规则不变**；
        //     同句「美股百度偶发限流时ORCL即中招」**仍然成立**，保留不动）。
        //   回退必须试全部腿，硬熔断 legs 仍受 srcBadSkip 保护。
        val pool = if (rest.isNotEmpty()) rest else legs
        // t132:竞速前置 fetching;try/finally 保证**异常路径也复位**
        fetching = true
        val win: Race
        try {
            win = raceOk(pool, prep, EM_TIMEOUT_MS + 8000L)
            publishLegs(sym, legs, pool, win)
        } finally {
            fetching = false
        }
        if (win.win != null) return listOf(win.win)
        // 全灭：三类文案**一处给**（allDeadText）—— 先判「有腿压根没投」的限流冷却，
        // 再按既有口径分「没有该品种数据 / 源不可达」。判据与理由见 allDeadText 的注释。
        throw Exception(allDeadText(legs, pool, win.anyNoData))
    }

    // 稿:962-988 fetchBaidu(百度股市通:美股 us / 港股 hk,同一接口只换 code_type)
    // marketData 是**扁平字符串**(';'分行、','分列),不是JSON数组 —— 按 keys 的**列名**取值,别按位置
    fun fetchBaidu(sym: String, tf: String, count: Int, maxBars: Int = FETCH_N): List<VendorKs> {
        val code = sym.trim().uppercase(Locale.US)
        if (!Regex("^\\d{5}$").matches(code) && !Regex("^\\d{6}$").matches(code) &&
            !Regex("^[A-Z.]+$").matches(code)) return emptyList()
        val ctype = symType(code)
        if (ctype != "hk" && ctype != "stock") return emptyList() // kr/cmdty/gold/silver 不收录
        val n = max(5, min(maxBars, count))
        val kt = when (tf) { "W" -> "week"; "M" -> "month"; "Q" -> "quarter"; else -> "day" }
        val u = "https://finance.pae.baidu.com/vapi/v1/getquotation?srcid=5353&all=1&code=" +
            URLEncoder.encode(code, "UTF-8") +
            "&group=quotation_kline_ab&query=" + URLEncoder.encode(code, "UTF-8") +
            "&code_type=" + (if (ctype == "hk") "hk" else "us") +
            "&ktype=$kt&end_time=20500000&count=$n"
        val j = JSONObject(get(u, null, uaRnd(), 8000))
        val md = j.optJSONObject("Result")?.optJSONObject("newMarketData") ?: return emptyList()
        val keys = md.optJSONArray("keys") ?: return emptyList()
        val raw = md.optString("marketData", "")
        if (raw.isEmpty() || !raw.contains(';')) return emptyList()
        val ix = HashMap<String, Int>()
        for (i in 0 until keys.length()) ix[keys.optString(i)] = i
        val oi = ix["open"] ?: return emptyList()
        val hi = ix["high"] ?: return emptyList()
        val li = ix["low"] ?: return emptyList()
        val ci = ix["close"] ?: return emptyList()
        val ti = ix["timestamp"] ?: ix["time"] ?: return emptyList()
        val vi = ix["volume"]
        val ai = ix["amount"]
        val out = mutableListOf<KLine>()
        for (r in raw.split(';')) {
            if (r.isEmpty()) continue
            val c = r.split(',')
            if (c.size <= maxOf(oi, hi, li, ci, ti)) continue
            // 时间列两种形态:keys 里的 'time' 是日期字符串(如 2025-01-21)走日期解析;
            // 'timestamp' 是**秒级** epoch(约 1.7e9),直接当毫秒会渲染成 1970-1-21。
            // 用量级判别:小于 1e12 一律当秒补 *1000。
            val t = if (ix.containsKey("time") && ix["time"] == ti) {
                parseNasdaqDate(c[ti])
            } else {
                val num = c[ti].toDoubleOrNull() ?: 0.0
                if (num <= 0.0) 0L
                else if (num < 1e12) (num * 1000.0).toLong() else num.toLong()
            }
            val h = c[hi].toDoubleOrNull() ?: continue
            if (t <= 0L || h <= 0.0) continue
            out.add(KLine(t,
                c[oi].toDoubleOrNull() ?: 0.0, h,
                c[li].toDoubleOrNull() ?: 0.0, c[ci].toDoubleOrNull() ?: 0.0,
                (vi?.let { c[it].toDoubleOrNull() } ?: 0.0) ?: 0.0,
                (ai?.let { c[it].toDoubleOrNull() } ?: 0.0) ?: 0.0))
        }
        val k = out.sortedBy { it.t }
        return if (k.size >= 3) listOf(VendorKs("百度", k)) else emptyList()
    }


    // 东货行情 secid 映射(照稿 EMID):金银 101.GC00Y/101.SI00Y + 17 种大宗商品 *00Y,
    // 未命中映射按 105.<代码> 走美股。代码原样大写(含数字/=,不再剥字符)。
    private val EMID = mapOf(
        // ⚠⚠ 2026-10-03 补现货贵金属(用户「要显示步长阈值和机构预测价」的下半场)。
        // 为什么原来没有:表里只有 "GC=F"(COMEX 期金)。而 App 的品种输入框里写的是
        // **XAUUSD**(ANDA 现货)。emSecid("XAUUSD") 查不到表 → 走 105/106/107 美股探测 →
        // 金当然探测不到 → 兜底返回 **"105.XAUUSD"** → push2his 返回空数组 →
        // stepCloses 的 prep 判 `size >= 201` 不成立 → 返回 null → **步长阈值整条隐藏**。
        // 实测(2026-10-03): secid=105.XAUUSD → 无数据;secid=101.GC00Y → **240 根日线,一根不少**。
        //   fetchMetals / fetchEastmoney 都靠这张表拿黄金,补上后两条腿同时通。
        //
        // ⚠⚠ **代价必须说清**:101.GC00Y 是 **COMEX 期金**,XAUUSD 是 **ANDA 现货**,
        //   稿 v2.html:575-576 明写这两个是「两个价格基准」,规则 4「**不得互相顶替**」。
        //   补这条映射 = **正式把期金登记成现货**。
        //   为什么还是做了:用户连续两轮要这个数;而东财**没有** XAUUSD 现货的 secid,
        //   118.AU9999(沪金现货)是人民币每克(量纲差 200 倍,接不上),新浪腿不给满 201 根。
        //   即:**没有别的路**。这是产品裁定,不是移植对齐。
        // ⚠ 若日后拿到了真·现货源,先删这两行,不要让两套基准并存。
        "XAUUSD" to "101.GC00Y", "XAGUSD" to "101.SI00Y",
        "GC=F" to "101.GC00Y", "SI=F" to "101.SI00Y", "CL00Y" to "102.CL00Y",
        "B00Y" to "112.B00Y", "NG00Y" to "102.NG00Y", "HO00Y" to "102.HO00Y",
        "RB00Y" to "102.RB00Y", "HG00Y" to "101.HG00Y", "PL00Y" to "102.PL00Y",
        "PA00Y" to "102.PA00Y", "ZC00Y" to "103.ZC00Y", "ZW00Y" to "103.ZW00Y",
        "ZS00Y" to "103.ZS00Y", "ZM00Y" to "103.ZM00Y", "ZL00Y" to "103.ZL00Y",
        "ZO00Y" to "103.ZO00Y", "ZR00Y" to "103.ZR00Y", "CT00Y" to "108.CT00Y",
        "SB00Y" to "108.SB00Y"
    )

    // 稿emSecid(730-740):美股按交易所取 secid:105=纳斯达克 106=纽交所 107=美股Arca;
    // 不写死105(纽交所ORCL/IBM/VISA等105查不到→整路无源),轻量报价接口探一次、命中即缓存;
    // 全不中回落旧值105.<code>让调用方照常报无源。全树唯一 secid 拼接点收口于此。
    private val EMMKT = java.util.concurrent.ConcurrentHashMap<String, String>()

    internal fun emSecid(code: String): String {
        EMID[code]?.let { return it }
        EMMKT[code]?.let { return it }
        // 稿§5:4/5位→只试116(港股),6位→只试177(韩股),不进105/106/107美股探测;
        // 4位先补零到5位;探测失败也只用116/177(绝不回落105.),调用方照常报无源
        val shaped = if (Regex("^\\d{1,5}$").matches(code)) code.padStart(5, '0') else code
        val fixed = when {
            Regex("^\\d{5}$").matches(shaped) -> "116"
            Regex("^\\d{6}$").matches(shaped) -> "177"
            else -> null
        }
        if (fixed != null) {
            val sid = "$fixed.$shaped"
            try {
                val j = JSONObject(get(
                    "https://push2.eastmoney.com/api/qt/stock/get?secid=$sid&fields=f57" +
                        "&ut=fa5fd1943c7b386f172d6893dbfba10b",
                    timeoutMs = EM_TIMEOUT_MS))
                val d = j.optJSONObject("data")
                if (d != null && d.optString("f57").isNotEmpty()) {
                    EMMKT[code] = sid
                    return sid
                }
            } catch (_: Exception) {
            }
            return sid
        }
        for (m in listOf("105", "106", "107")) {
            val sid = "$m.$code"
            try {
                val j = JSONObject(get(
                    "https://push2.eastmoney.com/api/qt/stock/get?secid=$sid&fields=f57" +
                        "&ut=fa5fd1943c7b386f172d6893dbfba10b",
                    timeoutMs = EM_TIMEOUT_MS))
                val d = j.optJSONObject("data")
                if (d != null && d.optString("f57").isNotEmpty()) {
                    EMMKT[code] = sid
                    return sid
                }
            } catch (e: Exception) {
            }
        }
        return "105.$code"
    }

    // 东方财富push2his(secid按EMID:商品/贵金属期货,未命中走emSecid美股探测):行=date,
    // open,close,high,low,volume,amount(oc在hl前);Q取3倍月数供toQuarterly合成
    // (照搬,mq上限300);v取份额成交量f56(与NQ/YH同股数口径直接比),qv取金额f57
    fun fetchEastmoney(sym: String, tf: String, count: Int, timeoutMs: Int = 10000): List<VendorKs> {
        val code = sym.trim().uppercase(Locale.US)
        val secid = emSecid(code) // ⑤收口:EMID优先→105/106/107探测并缓存(稿emSecid)
        val n = max(5, min(FETCH_N, count))
        val mq = if (tf == "Q") min(n * 3, 300) else n
        // 【F-2b · 2026-10-01】把周期映射**写明确**，让下一个人不会误判「Q 被当成 M」是静默降级。
        //
        // ⚠️ 这里**曾经**被写成 `if (tf=="W") "102" else "103"`，看上去像「Q 静默映射月线」。
        //   核实后确认：**不是**。季线是「**取月线 → 本函数末尾 toQuarterly 三月合成**」，
        //   见下方 `val ks = if (tf == "Q") toQuarterly(out) else out`。
        //   这与稿子侧币安年线的「真合成」同属一类：**屏上写的是季线，数据粒度确实是季线**，
        //   只是底层取月线再聚合 —— 定义上就是同一根季 K 线。
        //   （稿子删季线是因为**腾讯** `klt=quarter` 返回空，那是稿子的**源限制**，不是本源的缺陷。
        //     用户 2026-10-01 裁定：**保留季线**。）
        //
        // 「兜底 ≠ 等价」——**这句是这个注释存在的理由**：
        //   下面的 else 是**兜底**，不是等价。它兜的是**历史遗留周期码**
        //   （老用户 SharedPreferences 可能存着早已下线的值，见 MktPanel.loadWin()）。
        //   落到月线是「有个能用的结果」，**不等于「那本来就是月线」**。
        //   将来若真下线某个周期，这里必须同步改，否则又是一次静默降级。
        //
        //   **不要用 throw**：老用户的持久化值会让未捕获异常炸在 loadWin() 那条
        //   **没有被 try 包住**的路径上（MktPanel.kt:309），直接把整屏打白。
        val klt = when (tf) {
            "D" -> "101"   // 日线：源原生。⚠ 2026-10-03 补这一档，原因见下方墓碑
            "W" -> "102"   // 周线：源原生
            "M" -> "103"   // 月线：源原生
            "Q" -> "103"   // 季线：**取月线后由本函数末尾 toQuarterly 三月合成 —— 这不是静默降级**
            else -> "103"  // 兜底（历史遗留周期码），见上「兜底 ≠ 等价」
        }
        // ⚠⚠ 墓碑 2026-10-03 —— 补 "D" 分支，因为改前**根本没有日线这一档**。
        //   而 stepCloses() 传进来的正是 "D"(它要的是**日线**收盘，见 :1140 附近)。
        //   ⟹ "D" 一路落到 `else -> "103"` = **月线**，
        //     于是「过去 200 **日**涨跌幅」实际是拿 240 根**月线**算的。
        //     月线波动天然大于日线 ⟹ 屏上「步长阈值 7.97%」，
        //     而真实日线口径实测是 **3.24%**(同为 101.GC00Y 的 klt=101 240 根)。
        //   ⟹ 这正是本段上方亲口警告的「**兜底 ≠ 等价**」：
        //     兜底给了个「能用的结果」，**不等于那本来就是日线**；
        //     而且**全程无日志**，只能靠屏上数值对不上才察觉。
        //   东财 klt 码：101=日 102=周 103=月。
        // ⚠ 这类漏档是**结构性**隐患：任何新调用方传一个 when 没覆盖的 tf，
        //   都会静默拿到月线。以后新增周期**必须**先在这里加分支，别靠 else 兜。
        val j = JSONObject(get(
            "https://push2his.eastmoney.com/api/qt/stock/kline/get?secid=$secid&klt=$klt&fqt=1&lmt=$mq&end=20500000" +
                "&fields1=f1,f2,f3,f4,f5,f6,f7,f8&fields2=f51,f52,f53,f54,f55,f56,f57,f58" +
                "&ut=f057cbcbce2a86e2866ab8877db1d059&forcect=1",
            timeoutMs = timeoutMs))
        val klines = j.optJSONObject("data")?.optJSONArray("klines") ?: JSONArray()
        val out = mutableListOf<KLine>()
        for (i in 0 until klines.length()) {
            val p = klines.optString(i, "").split(",")
            if (p.size < 7) continue
            val t = parseNasdaqDate(p[0].trim().take(10))
            val o = p[1].toDoubleOrNull() ?: 0.0
            val c = p[2].toDoubleOrNull() ?: 0.0
            val h = p[3].toDoubleOrNull() ?: 0.0
            val l = p[4].toDoubleOrNull() ?: 0.0
            val v = p[5].toDoubleOrNull() ?: 0.0
            val amt = p[6].toDoubleOrNull() ?: 0.0
            if (t == 0L || !(h > 0)) continue
            out.add(KLine(t, o, h, l, c, v, amt))
        }
        out.sortBy { it.t }
        if (out.size < 3) throw EmNoDataException("行情拉取失败(网络或品种名不对)")
        val ks = if (tf == "Q") toQuarterly(out) else out
        return listOf(VendorKs("ED", ks.takeLast(n)))
    }

    // 贵金属/大宗商品单源:只走东财期货(稿 fetchMetals 线路 101.GC00Y/101.SI00Y
    // 与 17 种商品 *00Y),Yahoo 贵金属线路已按稿删除;Q 合成/截根由 fetchEastmoney 承担
    fun fetchMetals(sym: String, tf: String, count: Int, timeoutMs: Int = 10000): List<VendorKs> {
        val n = max(5, min(FETCH_N, count))
        val prep = fun(v: VendorKs?): VendorKs? {
            if (v == null || v.k.size < 3) return null
            var kk = if (tf == "Q") toQuarterly(v.k) else v.k
            kk = kk.sortedBy { it.t }.takeLast(n)
            return if (kk.size < 3) null else VendorKs(v.v, kk)
        }
        // ⚠⚠⚠ 2026-10-03 用户最终裁定：「我不是说新浪不要了嘛…没有 vpvr 源，没有任何意义。删掉他」
        //
        // 【用户判据，很明确】**没有成交量（VPVR）的源没有意义。**
        // 新浪外盘期货接口 `volume` 恒为 "0"（GC/SI 实测各 2589 条，volume>0 为 0 条），
        // `position` 持仓量同样恒为 0 ⟹ 它的 VPVR 永远是空的。
        // ⟹ 「有 K线但没有剖面」在用户眼里等于**残缺**，不值得作为备用。
        //
        // 【我上一轮自作主张把它加回来了，那是错的】
        // 上一轮我把它降为「备用第二腿」，理由是「替补模式下它只兜底、不顶掉东财」。
        // 但用户要的是**删掉**，不是**降级** —— 一个出不了 VPVR 的源，
        // 哪怕排最后一位，它的每次命中都是一次「屏上少一块」。
        // **降级不等于解决问题，只是把问题挪到不显眼的地方。**
        //
        // ⟹ 贵金属现在**只有东财一个源**。这是用户裁定的直接后果，他应该知道：
        //   东财不可达/限流 ⟹ 黄金白银**彻底无源**，没有退路。
        //
        // ⚠⚠ 已实测、**不可用**的其它备选（2026-10-03，别再重复试）：
        //   百度 XAUUSD / GC.F / hf_GC / AU0 / SI.F → keys 含 volume 但 **0 行**，不收录外盘
        //   腾讯 hf_GC / hf_XAU            → {"code":0,"msg":"param error","data":[]}
        //   腾讯 usXAUUSD.OQ / usGCF.OQ      → {"month":[],"qt":{...null}}，空数组
        //   Yahoo GC=F / SI=F              → 403
        //   Stooq gc.f / si.f              → JS 挑战，返回 HTML 不是 CSV
        // ⟹ 「从百度/腾讯分一个源给贵金属」在**当前可达的接口下做不到**，
        //   不是我没找，是这两家**根本不收录外盘贵金属**。
        val legs = listOf<Pair<String, () -> List<VendorKs>>>(
            // ⚠ 2026-10-10 键带市场：贵金属链的东财与韩股/步长链的东财**不再连坐**（见 srcKey）
            srcKey("东财", symType(sym.trim().uppercase(Locale.US))) to {
                fetchEastmoney(sym, tf, count, timeoutMs)
            }
        )
        val rest = legs.filter { !srcBadSkip(it.first) && !softSrcSkip(it.first) }
        val pool = if (rest.isNotEmpty()) rest else legs // 同 fetchStocks:全软熔断也全试，不只保第一腿
        // t132:竞速前置 fetching;try/finally 保证**异常路径也复位**
        fetching = true
        val win: Race
        try {
            win = raceOk(pool, prep, timeoutMs.toLong() + 8000L)
            publishLegs(sym, legs, pool, win)
        } finally {
            fetching = false
        }
        if (win.win != null) return listOf(win.win)
        // ⚠ 2026-10-10 文案三类**一处给**（与 fetchStocks 同源，不各写一份）
        throw Exception(allDeadText(legs, pool, win.anyNoData))
    }

    // 稿:989-1024 fetchSina:美股全历史 / 国内期货(CU0铜等);**只有日线**,周月季由日线合成
    // 返回的JSONP前面带 /*<script>...*/ 壳,必须剥掉
    // 新浪外盘代码映射:只收**实测有数据的**;hf_PL / hf_RB 实测返回空,不映射——映射错了会静默出NaN
    private val SINA_FUT = mapOf(
        "HG00Y" to "CAD", "CL00Y" to "CL", "B00Y" to "OIL", "GC=F" to "GC",
        "SI=F" to "SI", "NG00Y" to "NG", "ZS00Y" to "S", "CT00Y" to "C", "SB00Y" to "SB"
    )

    fun fetchSina(sym: String, tf: String, count: Int): List<VendorKs> {
        val code = sym.trim().uppercase(Locale.US)
        val ty = symType(code)
        val n = max(5, min(FETCH_N, count))
        val u = when (ty) {
            "cmdty", "gold", "silver" -> {
                val s = SINA_FUT[code] ?: return emptyList() // 没实测过的映射 → 老实不投,交给东财
                "https://stock2.finance.sina.com.cn/futures/api/jsonp.php/var%20_sx=/GlobalFuturesService.getGlobalFuturesDailyKLine?symbol=$s"
            }
            "stock" -> {
                if (!Regex("^[A-Z.]{1,8}$").matches(code)) return emptyList()
                "https://stock.finance.sina.com.cn/usstock/api/jsonp.php/var%20_sx=/US_MinKService.getDailyK?symbol=" +
                    URLEncoder.encode(code, "UTF-8")
            }
            else -> return emptyList() // 港股新浪接口已废,韩股不收录
        }
        val txt = get(u, null, uaRnd(), 9000, null, "https://finance.sina.com.cn")
        val i = txt.indexOf('(')
        val j = txt.lastIndexOf(')')
        if (i < 0 || j <= i) return emptyList()
        val arr = try { JSONArray(txt.substring(i + 1, j)) } catch (_: Exception) { return emptyList() }
        // 新浪**两种字段名**:国内期货(d/o/h/l/c/v/a)与外盘(date/open/high/low/close/volume)
        // ——只认一种会静默出 NaN
        val out = mutableListOf<KLine>()
        for (r in 0 until arr.length()) {
            val o = arr.optJSONObject(r) ?: continue
            fun g(a: String, b: String): String {
                val x = if (o.has(a) && !o.isNull(a)) o.optString(a) else o.optString(b)
                return if (x.isNullOrEmpty() || x == "0.000000000000000" || x == "null") "" else x
            }
            if (g("d", "date").isEmpty()) continue
            val t = parseNasdaqDate(g("d", "date"))
            val h = g("h", "high").toDoubleOrNull()
            val c = g("c", "close").toDoubleOrNull()
            if (t <= 0L || h == null || h <= 0.0 || c == null) continue
            out.add(KLine(t,
                g("o", "open").toDoubleOrNull() ?: 0.0, h,
                g("l", "low").toDoubleOrNull() ?: 0.0, c,
                g("v", "volume").toDoubleOrNull() ?: 0.0,
                g("a", "position").toDoubleOrNull() ?: 0.0))
        }
        val k0 = out.sortedBy { it.t }
        if (k0.size < 3) return emptyList()
        var kk = when (tf) {
            "W" -> resampleDaily(k0, "W")
            "M" -> resampleDaily(k0, "M")
            "Q" -> toQuarterly(k0)
            else -> k0
        }
        kk = kk.takeLast(n)
        return if (kk.size >= 3) listOf(VendorKs("新浪", kk)) else emptyList()
    }

    // ==================================================================
    // t103 步长阈值 → 网格数量自动计算（稿可搜索:「步长阈值」三段推导）
    // 用户原话：「获取过去 200 日的日涨跌幅，把涨跌幅从高到低排序，第 20 个就是网格之间的差值」
    // ==================================================================

    /** 步长阈值:过去 200 交易日的 |单日涨跌幅| 降序第 21 名。
     *
     * ⚠⚠ 2026-10-03 用户口述纠正了算法(原话照录)：
     *   「过去200日k线的涨跌幅，以绝对值的形式计算，然后从高到低排序，**第21个就是步长阈值**」
     * 改前是 `return (a[20] + a[21]) / 2.0` —— 取第 21、22 名的**中点**，用户没要求过中点。
     * ⟹ 改为 `return a[20]`（降序第 21 名，0-based 索引 20）。
     *
     * 实测对照（101.GC00Y，klt=101，240 根日线，2026-10-03）：
     *   降序第 20 名 a[19] = 3.26%
     *   降序第 21 名 a[20] = 3.24%   ← 现在的口径
     *   降序第 22 名 a[21] = 2.96%
     *   改前中点 (a[20]+a[21])/2 = 3.10%
     */
    fun stepThreshold(closes: List<Double>?): Double? {
        if (closes == null || closes.size < 201) return null      // 稿:1128
        val c = closes.takeLast(201)                              // 稿:1129 c.slice(-201)
        val a = ArrayList<Double>(200)
        for (i in 1 until c.size) {                               // 稿:1130
            val p = c[i - 1]
            if (p <= 0.0) return null
            a.add(kotlin.math.abs(c[i] / p - 1.0))
        }
        a.sortDescending()                                        // 稿:1131
        if (a[20] <= 0.0) return null                             // 稿:1132
        return a[20]                                              // 用户口述:第 21 个,非中点
    }

    private const val STEP_BARS = 260

    /**
     * 阈值专用日线收盘(**不受图表周期影响**)。需 ≥201 个收盘。
     *
     * ⚠️ **刻意偏离稿**：稿走「东财 → 股票补 Nasdaq → Yahoo」(index.html:1146-1166)，
     * 而本 App 的取数是**顺序替补腿**(百度/腾讯/新浪/东财)。
     *   ⚠⚠ 2026-10-10 **更正**：此处原写「早已是竞速制」——那是 2026-10-03 之前的旧事实。
     *     用户 2026-10-03 已裁定「**取消竞速模式，采用替补模式**」，`raceOk` 现为
     *     **顺序替补、赢面即止**（见 raceOk 的 doc 里那条「竞速改替补」）。
     *     ⚠ 结论不受影响：本段的论证靠的是「腿不止一条」，不是「腿在竞速」。
     * 东财自 2026-09-27 12:21 起
     * 对本机出口不通已逾 5 小时——**若照稿只走东财，这个功能在用户当前网络下 100% 不可用**。
     * 故这里**复用 App 现役替补腿**，只把周期换成日线。
     * **不新增任何数据源**：币走 BN(Binance，本就是现役腿)；其余走与 fetchStocks/fetchMetals
     * 完全相同的腿集合。
     */
    fun stepCloses(sym: String): List<Double>? {
        val code = sym.trim().uppercase(Locale.US)
        if (code.isEmpty()) return null
        val ty = symType(code)
        if (ty == "crypto") {
            // 稿:1140 Binance 现货日线 limit=260,取收盘 k[4]
            return try {
                val rows = JSONArray(
                    get("https://data-api.binance.vision/api/v3/klines?symbol=" +
                        URLEncoder.encode(code, "UTF-8") + "&interval=1d&limit=260",
                        null, uaRnd(), 9000))
                val cs = ArrayList<Double>(rows.length())
                for (i in 0 until rows.length()) {
                    val k = rows.optJSONArray(i) ?: continue
                    val v = k.optDouble(4, 0.0)
                    if (v > 0.0) cs.add(v)
                }
                if (cs.size < 201) return null
                // ⚠⚠ 2026-10-03 补写 KSKD。**改前币安这条分支是裸 return，KSKD 永远填不上**，
                //   于是 predTarget() 的 dailyKsCached() 对加密货币恒为 null →
                //   「预测价(最高×1.2)」整行不显示（用户报「且没有预测值」）。
                //   与下面竞速腿那处写 KSKD 是同一件事，这里只是**漏了这个分支**。
                //   Binance 的 k[2]=high，这里顺手一并收下，预测价直接用它取最高价。
                val kk = ArrayList<KLine>(rows.length())
                for (i in 0 until rows.length()) {
                    val k = rows.optJSONArray(i) ?: continue
                    val o = k.optDouble(1, 0.0); val hi = k.optDouble(2, 0.0)
                    val lo = k.optDouble(3, 0.0); val cl = k.optDouble(4, 0.0)
                    val vv = k.optDouble(5, 0.0); val tt = k.optLong(0, 0L)
                    if (cl > 0.0 && tt > 0L) kk.add(KLine(tt, o, hi, lo, cl, vv, 0.0))
                }
                try { KSKD[stepKey(code)] = kk } catch (_: Exception) {}
                cs
            } catch (_: Exception) { null }
        }
        // 其它:复用现役竞速腿,周期固定日线,条数 260(要 ≥201 才有 200 个日涨跌幅)
        val legs: List<Pair<String, () -> List<VendorKs>>> = try {
            stockLegs(code, "D", STEP_BARS, STEP_BARS)
        } catch (_: Exception) { emptyList() }
        val all = if (legs.isEmpty()) {
            if (ty == "cmdty" || ty == "gold" || ty == "silver")
                // ⚠⚠⚠ 2026-10-03 用户最终裁定：新浪**彻底删除**（贵金属 + 这里的步长阈值都不留）。
                //   判据同 fetchMetals：**没有成交量（VPVR）的源没有意义**。
                //   ⚠ 2026-10-10 键带市场（srcKey），且与 fetchMetals 的贵金属键**故意同名**：
                //     同源同市场就该共享熔断；与韩股/股票链的东财键隔开（见 srcKey 的注释）。
                listOf(srcKey("东财", ty) to { fetchEastmoney(code, "D", STEP_BARS, EM_TIMEOUT_MS) })
            else emptyList()
        } else legs
        if (all.isEmpty()) return null
        // ⚠⚠ 2026-10-10 **接上熔断过滤**：本函数原来**漏接**，正是 SRCBAD_SOFT 注释里点名的
        //   "别的取数入口"。口径与 fetchStocks/fetchMetals 逐字相同 —— 刚失败/刚空响应的腿
        //   本轮不再白打一次；全被熔断时仍按"全试"回退（同一份语义，不另立规则）。
        //   依据：用户硬约束「不要频繁打」，而这条链在行情页每换一次品种都会走一趟。
        val rest = all.filter { !srcBadSkip(it.first) && !softSrcSkip(it.first) }
        val pool = if (rest.isNotEmpty()) rest else all
        if (pool.isEmpty()) return null
        // raceOk 的 prep 收 VendorKs,这里借它当"收盘数组的信封"用:不改动 raceOk 本身,
        // 只是把 ≥201 根收盘装进去,赢家取出后再拆信封。
        //
        // ⚠⚠⚠ 2026-10-05 顺带记一笔**各腿最多拿到多少根**。
        //   为什么要记：prep 对「不足 201 根」也返回 null，于是 raceOk 的 `win == null`
        //   同时表达了两种完全不同的结果 ——「一条腿都没成」与「拿到了但根数不够」。
        //   本函数用 **null / 空列表** 把这两者分开（见下），否则上层没法决定
        //   该不该在 30 秒后重试。用户原话：「我不知道它出现的慢，还是压根就没有拉取」。
        var bestBars = 0
        val prep = fun(v: VendorKs?): VendorKs? {
            val cs = v?.k?.map { it.c } ?: return null
            if (cs.size > bestBars) bestBars = cs.size
            return if (cs.size >= 201) v else null
        }
        val r = raceOk(pool, prep, EM_TIMEOUT_MS + 8000L)
        val ks = r.win?.k
        // ⚠ 数据到手但不够 → 返回**空列表而非 null**：null 保留给「压根没取到」。
        if (ks == null) return if (bestBars > 0) emptyList() else null
        if (ks.size < 201) return emptyList()
        // ⚠ 2026-10-03：把整根 K 线顺手**缓存 10 分钟**，供预测价复用。
        //   预测价(大宗×1.10 / 币×1.20)取的是**最高价**，和步长阈值取收盘是同一次网络往返，
        //   不缓存就得为一行字再打一轮接口。10 分钟只是同一屏内的复用，不改变「每天算一次」的语义。
        try { KSKD[stepKey(code)] = ks } catch (_: Exception) {}
        return ks.map { it.c }
    }

    private val KSKD = java.util.concurrent.ConcurrentHashMap<String, List<KLine>>()

    /** 近 STEP_BARS 根**日线**的整根 K 线(带 10 分钟内存缓存)。预测价取最高价要用。 */
    private fun dailyKsCached(code: String): List<KLine>? {
        val k = KSKD[stepKey(code)] ?: return null
        // KLine 不带时间戳，用一个并行的取数时刻表判 TTL 太重；
        // 这里直接信任「同一次 stepCompute 刚写入」这一事实，超时由下一次 stepCloses 覆盖。
        return k
    }

    // 缓存:成功 5min / 失败 30s / 挂起去重(稿:1170-1182)。
    // **用时间戳判据,不起任何后台任务、不新增定时器**。
    private val STEPTD = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val STEPPEND = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private class StepT(val r: Double?, val t: Long, val ok: Boolean)

    private fun stepKey(sym: String): String {
        val code = sym.trim().uppercase(Locale.US)
        return symType(code) + ":" + code
    }

    /**
     * ⚠⚠⚠ 2026-10-05 **步长阈值的取数结果必须带 why**（用户在真机上发现的原话：
     *   「步长阈值的拉取规则是怎样的，**我不知道它出现的慢，还是压根就没有拉取**。
     *     总是没有。但有时候又有。」）
     *
     * 改前回调只给一个 `Double?`：有值就画、null 就**整行隐藏**（t103PaintStepT 的 else 支）。
     * 于是「正在取」「网络不通」「日线不够」「源返回空」四件事在屏上长得**一模一样**——
     * 全是那行不见了。屏上不显示原因，用户就只能靠猜，我也就只能靠猜。
     *
     * @param v   阈值（0.0417 = 4.17%）；拿不到时为 null
     * @param why 原因文案；**空串 = 一切正常**。非空时界面应当显示它，而不是隐藏那一行。
     */
class StepR(val v: Double?, val why: String) {
        companion object {
        fun ok(v: Double) = StepR(v, "")
        /** 没取到（断网 / 限断 / 所有腿都失败）—— 这是**会好**的一类，值得短 TTL 重试。 */
        fun noSource() = StepR(null, "—无源")
        /** 数据到了但根数不够 —— 这是**稳定结论**，重试也没用，24 小时不用再问。 */
        fun tooFew(bars: Int) = StepR(null, "—日线 $bars 根，不足 201")
        fun pending() = StepR(null, "—取数中")
    }
    }

    /**
     * ⚠⚠⚠ 2026-10-05 **区分「压根没取到」与「取到了但根数不够」**。
     *
     * 【改前的病】本函数无论算出 null 还是有数，一律 `StepT(v, now, ok = true)`，
     *   而 ok=true 的 TTL 是 **24 小时**（STEP_TTL_OK）。于是：
     *     · 断网/被限流 → null 被当成「成功结论」存 24 小时 → **当天再也不会重试**
     *     · 「不足 201 根」     → 同上
     *   两件本质相反的事共用一个 ok=true。这就是用户说的「**总是没有，有时候又有**」：
     *   每天只有一次机会，那一次碰巧通不通决定了这个品种当天有没有阈值。
     *
     * 【改后】
     *   · `closes == null`（一条腿都没成）→ **ok=false，30 秒 TTL** ⟹ 允许重试
     *   · `closes != null` 但不足 201 根    → **ok=true，24 小时**  ⟹ 结论稳定，不白打接口
     *
     * 【限流纪律】没有新增源、没有提高并发，只是把「什么时候允许再问一次」改对。
     */
    private fun stepCompute(code: String): StepR {
        return try {
            val cs = stepCloses(code)
            val v = stepThreshold(cs)
            val got = cs != null && cs.size >= 201
            // ⚠ 只有**数据真的到手**（含"到手了但不够"）才按 24 小时缓存；
            //   一条腿都没成必须 ok=false，让 30 秒失败 TTL 生效。
            STEPTD[stepKey(code)] = StepT(v, System.currentTimeMillis(), cs != null)
            when {
                got -> StepR.ok(v!!)
                cs != null -> StepR.tooFew(cs.size)
                else -> StepR.noSource()
            }
        } catch (_: Exception) {
            STEPTD[stepKey(code)] = StepT(null, System.currentTimeMillis(), false)  // 稿:1179
            StepR.noSource()
        }
    }

    /** 同步取(带缓存与在途去重)。 */
    fun fetchStepT(sym: String): Double? {
        val code = sym.trim().uppercase(Locale.US)
        if (code.isEmpty()) return null
        val day = stepDay()
        MktSave.stepLoad(code, day)?.let { return it }            // ① 落盘:当天算过就不再算
        val key = stepKey(code)
        val h = STEPTD[key] as? StepT
        val now = System.currentTimeMillis()
        // ⚠ 2026-10-03 用户裁定「每天只更新一次」：成功 TTL 由 5 分钟改为 **24 小时**。
        //   改前 300_000ms(5 分) 意味着每 5 分钟重算一次；而 stepCloses 是**竞速**，
        //   新浪/东财的收盘不完全一致 ⟹ 同一个交易日里屏上数值会无缘无故变好几次。
        if (h != null && now - h.t < (if (h.ok) STEP_TTL_OK else 30_000L)) return h.r
        return stepCompute(code).v
    }

    /** 阈值「一天」的键。用本地日历日；跨天即失效，下一次调用自然重算。 */
    private fun stepDay(): String = java.time.LocalDate.now().toString()
    private const val STEP_TTL_OK = 24L * 60 * 60 * 1000

    /** 异步取:UI 用。挂起去重(稿:1175 同样的语义:在途只发一次)。
     *
     *  ⚠⚠⚠ 2026-10-05 **旧值先上、后台再刷**（用户原话：「一天只要获取一次，然后保存用一天
     *  不就可以了吗？而且如果暂时没有读取出来，也可以用旧值代替啊」）。
     *
     *  【改前】只有「当天有值」或「内存 TTL 命中」才回；两者都没有就把 `null` 抛给 UI
     *  ⟹ 当天首次取数失败(断网/限流)时，屏上就是「没有阈值」。2026-10-05 实测三个品种同时消失。
     *
     *  【改后】stale-while-revalidate：
     *  ① 当天有值 ⟹ 直接回（不变，这就是「一天取一次、存着用一天」）。
     *  ② 当天没有 ⟹ **先回最近一次成功的旧值**（[MktSave.stepLoadLast]），屏上立刻有数；
     *  ③ 后台仍按当天口径去取今天的值，取到再回调一次覆盖。
     *  ④ **取失败时回旧值而不是 null** —— 这一条是关键：原来失败路径给 null，
     *     调用方会把屏上已有的数抹掉，那正是用户看到的「阈值没了」。
     *
     *  【不改取数强度】只多了一次读 SharedPreferences；没有新增源、没有并发、没有改熔断。
     */
    fun stepThresholdAsync(sym: String, cb: (StepR) -> Unit) {
        val code = sym.trim().uppercase(Locale.US)
        if (code.isEmpty()) { cb(StepR.noSource()); return }
        val day = stepDay()
        MktSave.stepLoad(code, day)?.let { cb(StepR.ok(it)); return }   // ① 当天已算过:直接回,连内存都不碰
        val key = stepKey(code)
        // ② 最近一次成功的值（可能是上周的）。全流程**只当兜底**，永远让位给当天的数。
        val stale = MktSave.stepLoadLast(code)
        if (STEPPEND.containsKey(key)) { cb(if (stale != null) StepR.ok(stale) else StepR.pending()); return }   // 在途:不重复发
        val h = STEPTD[key] as? StepT
        val now = System.currentTimeMillis()
        // ⚠ 内存里记着「当天取过但没算出来」时(h.r == null)，也要回退到旧值而不是 null。
        if (h != null && now - h.t < (if (h.ok) STEP_TTL_OK else 30_000L)) {
            cb(if (h.r != null) StepR.ok(h.r) else if (stale != null) StepR.ok(stale) else StepR.pending()); return
        }
        cb(if (stale != null) StepR.ok(stale) else StepR.pending())   // ③ 先把旧值放上屏，没有就告诉界面"正在取"
        STEPPEND[key] = true
        Thread {
            val r = stepCompute(code)
            STEPPEND.remove(key)
            if (r.v != null) MktSave.stepSave(code, day, r.v)
            // ⚠ ④ 取不到就回旧值，**不是回 null** —— 别把屏上已经有的数抹掉。
  cb(if (r.v != null) r else if (stale != null) StepR.ok(stale) else r)
        }.start()
    }

    // ⚠⚠ 2026-10-03 **新品功能**（用户裁定，不是移植稿里的东西）：
    //   用户原话：「大宗商品的预测价为历史最高价的110%，加密货币的预测价为历史最高价的120%」
    //
    //   适用范围：crypto ×1.20；cmdty / gold / silver ×1.10。
    //   **股票不走这条** —— 股票仍取 Nasdaq 机构目标均价（fetchTgt，见上）。
    //   基准 = **全时段盘中最高**（不是屏上那 20 根，也不是近一年那 260 根日线）。
    //
    // ⚠⚠ 2026-10-07 **窗口口径已更换（用户裁定，覆盖 10-03 的实现）**：
    //   原实现 = 近 STEP_BARS(260) 根**日线**的**收盘**最高；实测 BTC 屏上 107,520.31
    //   反推基数 89,600.26，而用户给出的历史最高是 **126,199**（2025 年）——
    //   落在 260 天窗口**之外**，怎么算都对不上。这是**口径不符**，不是算错；
    //   上一版注释自己也写着「若用户要全时段历史最高，**这里要改**」。
    //   现改为 **全时段盘中最高**，取数走 **月线**（见 [allTimeHigh]）。
    fun predTarget(sym: String): Double? {
        val code = sym.trim().uppercase(Locale.US)
        if (code.isEmpty()) return null
        // ⚠ 2026-10-07 BTC修正：用**收盘最高**而非影线最高（交易所坏tick的上影线会把×1.2吹上天，博通/腾讯失真同源）。
        //   ⚠ 同日后续裁定：预测价的「历史最高」改为**盘中最高**（见下）——那条是为防坏tick，
        //     这里是为对齐用户给的 126,199；**倍率不受影响**：crypto 仍 ×1.20，商品仍 ×1.10。
        //   股票仍走机构目标价，不进这条。
        val mul = when (symType(code)) {
            "crypto" -> 1.20
            "cmdty", "gold", "silver" -> 1.10
            else -> return null                       // 股票等走 fetchTgt
        }
        val day = java.time.LocalDate.now().toString()
        MktSave.predLoad(code, day)?.let { return it }
        // ⚠⚠ 2026-10-07 **口径更换（用户裁定）**：历史最高价 = **全时段盘中最高**，改用**月线**取全史。
        //   详见 [allTimeHigh]（为什么不拉日线）。
        val hi = allTimeHigh(code) ?: return null
        if (hi <= 0.0) return null
        val v = hi * mul
        MktSave.predSave(code, day, v)
        return v
    }

    /**
     * ⚠⚠ 2026-10-07 **全时段盘中最高价**（用户裁定；替代原来的「近 260 根日线收盘最高」）。
     *
     * 【为什么不用日线】用户原话：「没有必要拉日线，难道只有日线才可以获得全历史最高价格的数据嘛？」
     *   —— 他是对的。月线的 `high` 就是该月的最高价，于是
     *   **全史最高 = 所有月线 high 的最大值**，与逐根日线取 max **数学上完全等价**，
     *   而请求数量级下降：
     *     · BTC 全史 ≈6,400 根日线 → 币安单次最多 1000 根，**要分页 7 次**
     *     · 同期月线 ≈114 根 → **1 次请求拉完**（limit=500 已远超覆盖）
     *   商品同理：东财月线 240 根 = 20 年，走现有 `fetchMetals(..., "M", FETCH_N)`。
     *
     * 【为什么改回 h（影线）】2026-10-07 早些那次换成收盘价，是防交易所坏 tick 把 ×1.2 吹上天；
     *   现在用户明确要的就是**盘中最高 126,199**，故按裁定取 `h`。币安是主流动性来源，坏 tick 风险可控。
     *
     * 【请求预算】结果由 [MktSave.predSave] 按「品种|日期」缓存，**每个品种每天只算一次**，
     *   与 [stepThreshold] 同一条「一天一取」的纪律，不构成突发拉取。
     *
     * @return 全时段盘中最高价；取不到返回 null（调用方 predTarget 也就不出这一行，不编数）
     */
    private fun allTimeHigh(sym: String): Double? {
        val code = sym.trim().uppercase(Locale.US)
        if (code.isEmpty()) return null
        val ty = symType(code)
        try {
            // ── 加密货币：币安现货月线（interval=1M），一次拉全，无分页 ──
            if (ty == "crypto") {
                val rows = JSONArray(get(
                    "https://data-api.binance.vision/api/v3/klines?symbol=" +
                        URLEncoder.encode(code, "UTF-8") + "&interval=1M&limit=500",
                    null, uaRnd(), 9000))
                var best = 0.0
                for (i in 0 until rows.length()) {
                    val k = rows.optJSONArray(i) ?: continue
                    val h = k.optDouble(2, 0.0)        // k[2] = high
                    if (h > best) best = h
                }
                return if (best > 0.0) best else null
            }
            // ── 商品/其余：复用现役腿的**月线**（不新增任何数据源） ──
            //   fetchMetals 内部 takeLast(FETCH_N=240) → 月线 240 根 = 20 年，覆盖商品上市至今的高点。
            val vs = when (ty) {
                "gold" -> fetchMetals("GC=F", "M", FETCH_N)
                "silver" -> fetchMetals("SI=F", "M", FETCH_N)
                "cmdty" -> fetchMetals(code, "M", FETCH_N)
                else -> fetchStocks(code, "M", FETCH_N)
            }
            var best = 0.0
            for (v in vs) for (r in v.k) if (r.h > best) best = r.h
            return if (best > 0.0) best else null
        } catch (_: Exception) {
            // ⚠ 2026-10-08 **月线取全史失败时的回退**。实测日志：
            //   `fetchMetals FAIL sym=GC=F tf=M count=240 anyNoData=false legs=[东财=fail]`
            //   —— 东财月线腿（klt=103）会与同源的日线请求**并发打架**，间歇性失败。
            //   失败的后果是**预测价整行消失**（predTarget 拿不到 hi 就 return null），
            //   比"窗口不完全等于全时段"严重得多。
            // 【回退口径】用**已落盘的留底**（不发任何新请求）算 ATH：
            //   正是用户原话「已经保存了数据，这不就是计算一下子的事情吗」。
            //   ⚠ 诚实标注：落盘是日线档（FETCH_N=240 根 ≈ 一年），
            //   回退时窗口**不等于全时段**，若真 ATH 落在窗口外会偏低；
            //   但这只在月线失败时发生，且始终优于整行无数。
            //   ⚠ 2026-10-09 `MktSave.saved()` 起**校验周期**（复用只认同一周期）：
            //     这里的键写死 "D"，所以只有**日线档**留底能兜住 —— 与上面那句
            //     「落盘是日线档」的口径终于一致了（改前会把别的周期也算进来，却仍按日线标注）。
            val saved = runCatching {
                MktSave.saved(MktCache.key(code, "D", FETCH_N))
            }.getOrNull()
            if (saved != null && saved.isNotEmpty()) {
                val ks = MktData.aggregate(saved, code).ks
                val best = ks.mapNotNull { if (it.h > 0) it.h else null }.maxOrNull()
                if (best != null && best > 0.0) return best
            }
            return null
        }
    }

    // ===== t106 (6) exchange/time (pure display, no fetch, no new requests) =====
    fun tfName(tf: String): String = when (tf) {"W" -> "周线"; "Q" -> "季线"; "D" -> "日线"; else -> "月线" }
    fun marketPhase(now: Long = System.currentTimeMillis()): String {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("GMT-4"))
        val min = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        val dow = cal.get(java.util.Calendar.DAY_OF_WEEK)
        return when {
            dow == java.util.Calendar.SATURDAY || dow == java.util.Calendar.SUNDAY -> "closed"
            min < 570 -> "pre"
            min < 960 -> "open"
            min < 1140 -> "post"
            else -> "closed"
        }
    }
    /** t122:tz 可选。不传保持原样(GMT-4 美股),传了就按该时区格式化。
     *  港股/韩股的 GMT-4 时钟是**错的** —— 那是拿美股时区冒充亚洲市场。 */
    fun marketClock(tEnd: Long, tz: String = "GMT-4"): String {
        if (tEnd <= 0L) return ""
        val f = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
        // t292 稿 L566 的 GMT-4 是【美股那一个例子】, 不是通则。
        // 改前这里第二行把刚设好的 tz 立刻覆盖成 GMT-4, 后缀也写死 ——
        // **tz 参数从来没生效过**, MktPanel.kt:887 那句「非美股按本地时区」的注释
        // 因此一直是假的。t292 删掉覆盖, 后缀改用传入值。
        // (注: 上面 L1113 那行旧注释「港股/韩股的 GMT-4 时钟是错的」当年就写了,
        //  但一直没修 —— 注释说对了, 代码没跟上。)
        f.timeZone = java.util.TimeZone.getTimeZone(tz)
        return f.format(java.util.Date(tEnd * 1000)) + " " + tz
    }

    fun vendorName(v: String): String {
        // ⚠⚠ 2026-10-10 腿名现在是「源|市场」的**内部键**（见 srcKey），上屏前必须剥掉 `|市场`。
        //   调用点：MktPanel:964/980、FavPanel:655/721（FavPanel:650 那句「腿名必须过 vendorName()」
        //   就是这条既有约定）、以及 allDeadText 的文案。
        //   ⚠ 对"非腿名"（"EM"/"百度"/"BN" 这类 VendorKs.v）无副作用：它们本来就不含 '|'。
        val base = v.substringBefore('|')
        return when (base) {
            "BN" -> "Binance"
            "OK" -> "OKX"
            "BB" -> "Bybit"
            "GT" -> "Gate.io"
            "KU" -> "KuCoin"
            "腾讯", "TX" -> "腾讯"
            // 稿 t100 裁定:显示「百度股市通」而不是「百度」——用户看到「百度」会以为是搜索引擎,
            // 而这个标签的全部意义就是"别让用户以为数据来自东财",含糊就白标了
            "百度" -> "百度股市通"
            "新浪" -> "新浪"
            "MX" -> "MEXC"
            "GK" -> "CoinGecko"
            "YH" -> "Yahoo"
            "NQ" -> "Nasdaq"
            // ⚠ 2026-10-10 韩股兜底腿（`fetchNaver`）。不加这一档也能跑（`else -> base` 会原样
            //   显示 "NV"），但那行会显示一个内部代号 —— 用户看不懂，也违反本函数存在的理由。
            "NV" -> "Naver"
            "ED" -> "东方财富"
            "TX" -> "腾讯"
            else -> base
        }
    }

    // 股票多源按份额成交量选优(股数口径,可直接比Σv)
    fun pickStockWinner(vs: List<VendorKs>): VendorKs =
        vs.maxByOrNull { v -> v.k.sumOf { it.v } }!!

    // 行情多源内存缓存5分钟,key=品种|周期|根数
    object MktCache {
        private data class E(val vs: List<VendorKs>, val at: Long)
        private const val TTL = 5 * 60 * 1000L
        private val map = LinkedHashMap<String, E>()

        // v4§7:缓存键必须带汇率——汇率变了键就变,强制重新抓,不沿用旧美元价
        //
        // ⚠⚠⚠ 2026-10-04 **手动拆股刻意不进这个键**，我一度加上去，又撤了：
        //   缓存里躺的是**复权之前**的数据 —— 写盘顺序是 `FX.fxify` → `MktCache.put` →
        //   `renderStock(aggregate(...))`，而拆股/前复权是在 `aggregate` 里现算的。
        //   ⟹ 缓存内容与拆股**无关**，键也不该含它。
        //   带了会怎样：用户每改一条拆股就换一个键 ⟹ 缓存与留底全部作废 ⟹ **强制重发网络请求**。
        //   那不只是慢，是在拿限流换一条本来不需要重取的数据（用户明确要求别把源打爆）。
        fun key(sym: String, tf: String, n: Int): String =
            sym.trim().uppercase(Locale.US) + "|" + tf + "|" + n + "@" + FX.keyRate(sym)

        @Synchronized
        fun get(k: String): List<VendorKs>? {
            val e = map[k] ?: return null
            if (System.currentTimeMillis() - e.at > TTL) {
                map.remove(k)
                return null
            }
            return e.vs
        }

        @Synchronized
        fun put(k: String, vs: List<VendorKs>) {
            map[k] = E(vs, System.currentTimeMillis())
            // ⚠⚠ 2026-10-08 容量 20 → **40**（用户真机自选 **28 个品种**）。
            //   改成对证据：真机截图 28 行、模拟器 6 行 —— 旧上限 20 **比用户的品种数还小**，
            //   于是每刷一轮就有 8 个品种被 FIFO 挤掉，下一轮只能回落去读落盘（MktSave MAX=40）。
            //   不是"无源"的直接原因（落盘能兜），但它**白白废掉了内存复用**、放大了重拉量，
            //   而重拉量正是撞东财限流的那条因果链的第一环。
            //   ⚠ 与 MktSave.MAX(40) 取齐：两层的容量不一致本身就是个坑。
            //   内存量级：40 品种 × 240 根 × 7 字段 ≈ 0.5MB，可接受。
            while (map.size > 40) {
                map.remove(map.keys.first())
            }
        }

        /**
         * ⚠ 2026-10-07 **按品种取缓存（忽略根数/汇率尾缀差异）**。
         *   自选distWin的n（如20）与行情取数档位FETCH_N（240）永远对不上全键，
         *   精确get恒miss ⟹ 每个品种在自选被迫重抓一次（ORCL/005930无源的放大器）。
         *   本函数按`SYM|`前缀扫描，过期条目跳过。
         *
         * ⚠⚠ 2026-10-09 **删除「同tf找不到就返回任一新鲜条目」的跨周期兜底**（用户裁定：
         *   距支撑的旧数据复用**只认同一周期**）。原实现里 `fallback` 那一支会把**另一个周期**的
         *   K线交出去，而下游只有 `takeLast(n)` 能把**根数**归一，**周期归不了一**
         *   ⟹ 拿周线算日线窗口的距支撑，算出来的支撑本来就在另一个周期上；
         *   自选列表里本来就并存不同周期（周期默认**按品种**存，见 MktPanel 的 winPref），
         *   同一行因此在两个数之间跳 —— 这就是用户报的「每次重算和之前存的相差很大」。
         *   ⟹ 周期对不上就当**没有**，让调用方走下一级（MktSave → 真抓）。
         *   ⚠ 根数 n 的放宽**保留**：本函数仍不比对 n（下游 takeLast(n) 会归一），
         *     那是 2026-10-08 修「老是无源」时正确的放宽，不许一起删掉。
         */
        @Synchronized
        fun cachedFor(sym: String, tf: String): List<VendorKs>? {
            val u = sym.trim().uppercase(Locale.US)
            if (u.isEmpty()) return null
            val now = System.currentTimeMillis()
            for ((k, e) in map) {
                if (!k.startsWith(u + "|")) continue
                if (now - e.at > TTL) continue
                if (e.vs.isEmpty()) continue
                // 键形如 SYM|tf|n@rate：第二段就是周期，不一致一律跳过（不再回落任一新鲜条目）
                if (k.substringAfter("|").substringBefore("|") != tf) continue
                return e.vs
            }
            return null
        }
    }

    // ---------- 行情页落盘留底(稿可搜索:「落盘留底」) ----------
    // 稿有、App 原先完全没有:内存 MktCache 一死(杀进程/改根数换键)就没了,
    // 东财一断行情页就白屏。用户明确要求"不切源",所以"拿上次抓到的旧值顶着"是唯一可用兜底。
    //
    // 与稿同构的四点:
    //   ① 键 gridcalc_mk_v1,上限 40 个品种,解析失败兜底空对象(稿:1354-1355)
    //   ② 每根压到 t/o/h/l/c/v,qv 置 0(稿:1358)
    //   ③ _u=1 表示这根已是美元价、别再折 —— App 对应 KLine.usd(稿:1358 尾注释)
    //   ④ 新数据进来删掉同品种旧留底;超 40 按最旧丢(稿:1360-1367)
    // 读写全程 try/catch:存不下就算了,绝不能因为留底影响正常取数(稿:1369 同款兜底)。
    object MktSave {
        private const val SP = "gridcalc_mk_v1"
        private const val MAX = 40
        private val lock = Any()
        private var app: Context? = null

        fun attach(c: Context) {
            if (app == null) app = c.applicationContext
        }

        // 键形如 AAPL|M|20@1.0,品种取第一段(稿 distSym(ck)),**周期取第二段**。
        // ⚠ 2026-10-09 tf 就在键的第二段 ⟹ store()/saved() 都能直接从收到的 ck 里取周期，
        //   **无需改函数签名**（改签名会牵动 MktPanel 三处调用点，而那不在本轮范围内）。
        private fun symOf(ck: String) = ck.substringBefore('|').trim().uppercase(Locale.US)
        private fun tfOf(ck: String): String = ck.split('|').getOrNull(1)?.trim() ?: ""

        // ⚠ 2026-10-03 用户裁定：「这个阈值**每天只更新一次**」。
        //   为什么必须**落盘**而不能只加内存 TTL:
        //   stepCloses 是**新浪/东财竞速**，两家收盘不完全一致 ⟹ 同一屏重算可能得不同值。
        //   只做内存 24h 的话，**杀掉进程再开**照样重算一次，用户会看到数值无故跳一次。
        //   落盘后：同一天内不论重启几次、不论谁赢竞速，屏上永远是同一个数。
        private const val SP_STEP = "gridcalc_step_v1"
        // ⚠⚠ 2026-10-07 **缓存键升版 v1→v2**：口径换了（近260根收盘最高 → 全时段盘中最高），
        //   而 predLoad 按「品种|日期」存，今天已经把错值 107520.312 写进 v1 —— 不换键它会一直
        //   顶着新代码压住屏，要等明天才重算。换键 = 旧值自动作废、当天立即重算。
        private const val SP_PRED = "gridcalc_pred_v2"

        fun stepSave(sym: String, day: String, v: Double) = dayPut(SP_STEP, sym, day, v)
        fun stepLoad(sym: String, day: String): Double? = dayGet(SP_STEP, sym, day)

        /**
     * ⚠⚠⚠ 2026-10-05 **最近一次成功算出来的值**（不限当天）—— 用户原话：
     *   「如果暂时没有读取出来，也可以用旧值代替啊」。
     *
     * 【为什么必须有】改前每个品种只存**一条** `日期|值`，被当天覆盖 ⟹ 当天取数失败时
     *   **旧值已经没了**，屏上只能显示「无」。2026-10-05 实测正是这样：限流导致
     *   SPCX/MRVL/TSLA 的阈值同时消失，而不是退回昨天的数。
     *
     * 【存法】payload 从 `日期|值` 变成 `日期|值|[上一条日期|上一条值]`，
     *   写入时把旧记录顺移到第三段。**只多留一条**（不是历史全存）——
     *   步长阈值一天只变一次，一条旧值已经够用，占用与原来同量级。
     *   兼容：老格式只有两段，照样能读出当前值（第三段缺失时旧值回退不到，属正常）。
     */
        fun stepLoadLast(sym: String): Double? = lastGet(SP_STEP, sym)

        /** 只取 payload 的第一段值，不管是哪天的。 */
        private fun lastGet(sp: String, sym: String): Double? {
            return try {
                val a = app ?: return null
                val s = a.getSharedPreferences(sp, Context.MODE_PRIVATE)
                    .getString(sym.uppercase(Locale.US), null) ?: return null
                val p = s.split('|')
                if (p.size < 2) return null
                p[1].toDoubleOrNull()
            } catch (_: Exception) { null }
        }

        fun predSave(sym: String, day: String, v: Double) = dayPut(SP_PRED, sym, day, v)
        fun predLoad(sym: String, day: String): Double? = dayGet(SP_PRED, sym, day)

        /**
     * 写入：把上一条顺移成"旧值"（第三段），新值写进前两段。
         * 跨天写入时旧值才有意义；同日重算不移动（省一次写）。
         */
        private fun dayPut(sp: String, sym: String, day: String, v: Double) {
            try {
                val a = app ?: return
                val k = sym.uppercase(Locale.US)
                val ed = a.getSharedPreferences(sp, Context.MODE_PRIVATE).edit()
                val old = try {
                    a.getSharedPreferences(sp, Context.MODE_PRIVATE).getString(k, null)
                } catch (_: Exception) { null }
                val payload = if (old != null) {
                    val p = old.split('|')
                    // 只有「旧的确实是另一天」才值得留下，否则沿用旧记录里的第三段
                    val prev = if (p.size >= 2 && p[0] != day) "${p[0]}|${p[1]}"
                    else if (p.size >= 4) "${p[2]}|${p[3]}"
                    else null
                    if (prev != null) "$day|$v|$prev" else "$day|$v"
                } else "$day|$v"
                ed.putString(k, payload).apply()
            } catch (_: Exception) {
            }
        }

        /** 只认**当天**那条；跨天即失效 ⟹ 下一次调用自然重算，这就是「每天更新一次」。 */
        private fun dayGet(sp: String, sym: String, day: String): Double? {
            return try {
                val a = app ?: return null
                val s = a.getSharedPreferences(sp, Context.MODE_PRIVATE)
                    .getString(sym.uppercase(Locale.US), null) ?: return null
                val p = s.split('|')
                if (p.size != 2 || p[0] != day) return null
                p[1].toDoubleOrNull()
            } catch (_: Exception) { null }
        }

        private fun readAll(): JSONObject {
            return try {
                val a = app ?: return JSONObject()
                val s = a.getSharedPreferences(SP, Context.MODE_PRIVATE).getString("all", null)
                if (s.isNullOrEmpty()) JSONObject() else JSONObject(s)
            } catch (_: Exception) {
                JSONObject() // 稿:1355 解析失败兜底 {};损坏内容一律当"没有留底",不崩
            }
        }

        private fun writeAll(o: JSONObject) {
            try {
                app?.getSharedPreferences(SP, Context.MODE_PRIVATE)
                    ?.edit()?.putString("all", o.toString())?.apply()
            } catch (_: Exception) {
            }
        }

        private fun toJson(vs: List<VendorKs>): JSONArray {
            val out = JSONArray()
            for (v in vs) {
                if (v.k.isEmpty()) continue
                val arr = JSONArray()
                for (k in v.k) {
                    arr.put(JSONObject().apply {
                        put("t", k.t); put("o", k.o); put("h", k.h)
                        put("l", k.l); put("c", k.c); put("v", k.v)
                        put("qv", 0)          // 稿:1358 qv 置 0
                        put("_u", 1)          // 稿:_u=1 已是美元价,别再折
                    })
                }
                out.put(JSONObject().apply { put("v", v.v); put("k", arr) })
            }
            return out
        }

        /** 取某品种的留底。**按品种取**而非按完整键——完整键含根数/汇率,
         *  改一次根数就换键,按完整键取会让"换根数后仍能兜底"失效(验收第2条)。
         *
         *  ⚠⚠ 2026-10-09 **周期必须校验**（用户裁定：距支撑的旧数据复用**只认同一周期**）。
         *   留底是**某一周期**的一批K线，而下游只有 `takeLast(n)` 能把**根数**归一，周期归不了一；
         *   键里本来就有 tf（第二段），只是从前没往盘里写、读回来也无从校验。
         *   ⟹ 条目 tf 与请求 tf 不一致一律当**没有留底**，让调用方真抓一次；
         *     **旧条目没有 tf 字段**（`optString` 回落 ""）同样视为不匹配 —— 不崩、也绝不
         *     拿它当同周期数据用。该品种会在下一次成功后带上 tf。
         */
        fun saved(ck: String): List<VendorKs>? {
            return try {
                val sym = symOf(ck)
                val tf = tfOf(ck)
                val o = readAll()
                val e = o.optJSONObject(sym) ?: return null
                if (e.optString("tf", "") != tf) return null
                val arr = e.optJSONArray("vs")
                if (arr == null || arr.length() == 0) return null
                val out = mutableListOf<VendorKs>()
                for (i in 0 until arr.length()) {
                    val vo = arr.optJSONObject(i) ?: continue
                    val ka = vo.optJSONArray("k") ?: continue
                    val ks = mutableListOf<KLine>()
                    for (j in 0 until ka.length()) {
                        val oo = ka.optJSONObject(j) ?: continue
                        ks.add(KLine(
                            t = oo.optLong("t"), o = oo.optDouble("o"), h = oo.optDouble("h"),
                            l = oo.optDouble("l"), c = oo.optDouble("c"), v = oo.optDouble("v"),
                            qv = 0.0, usd = true // _u=1 → usd=true,读回渲染绝不二次折算(v4§7)
                        ))
                    }
                    if (ks.isNotEmpty()) out.add(VendorKs(vo.optString("v"), ks))
                }
                if (out.isEmpty()) null else out
            } catch (_: Exception) {
                null
            }
        }

        /** 抓成功后写入留底(稿 mkStore) */
        fun store(ck: String, vs: List<VendorKs>) {
            try {
                val sym = symOf(ck)
                // ⚠⚠ 2026-10-09 **周期随留底一起存**（用户裁定：复用只认同一周期）。
                //   改前只按品种存、周期被丢掉 ⟹ 读回来时无从校验，自选/行情两屏
                //   可以拿另一个周期的K线算出另一个支撑（用户报「数值差很大」的根因之一）。
                //   tf 从收到的 ck 第二段直接取，**函数签名不变**。
                //   旧条目没有这个字段 ⟹ saved() 里视为不匹配（该品种重抓一次，之后带上 tf）。
                val tf = tfOf(ck)
                val arr = toJson(vs)
                if (arr.length() == 0) return
                synchronized(lock) {
                    val o = readAll()
                    // 稿:1360-1362 新数据进来就删掉同品种旧留底(App 的键只按品种存,同键即覆盖;
                    // 这里仍显式删一次,保证结构与稿一致、也清掉历史遗留的其它键)
                    for (k in o.keys().asSequence().toList()) if (symOf(k) == sym) o.remove(k)
                    o.put(sym, JSONObject().apply {
                        put("vs", arr); put("tf", tf); put("t", System.currentTimeMillis())
                    })
                    // 稿:1364-1367 只留 40 条,超了按最旧的丢
                    val ks = o.keys().asSequence().toList()
                    if (ks.size > MAX) {
                        val byT = ks.sortedBy { o.optJSONObject(it)?.optLong("t") ?: 0L }
                        for (k in byT.take(ks.size - MAX)) o.remove(k)
                    }
                    writeAll(o)
                }
            } catch (_: Exception) {
            }
        }
        /**
         * t302 缓存年龄(毫秒)。稿 L516 的「展示 … 缓存数据（3 小时前）」要这个数。
         * store 里已经 put("t", System.currentTimeMillis()) 了, 这里只读不写。
         * ⚠ 2026-10-09 与 [saved] 用**同一条周期校验**：不是这一周期的留底，年龄也谈不上是它的。
         *   三个调用点(MktPanel)都只在 `saved()` 已经命中的分支里调它 ⟹ 可达到的行为不变。
         */
        fun savedAgeMs(ck: String): Long {
            return try {
                val e = readAll().optJSONObject(symOf(ck)) ?: return -1L
                if (e.optString("tf", "") != tfOf(ck)) return -1L
                val t = e.optLong("t", 0L)
                if (t > 0L) (System.currentTimeMillis() - t).coerceAtLeast(0L) else -1L
            } catch (_: Exception) { -1L }
        }

    }

    // ---------- v4§8 1%价格精度:d = max(0, 2 - floor(log10 v)) ----------
    // 个位价保留必要小数(6.77→6.77),三位数以上自动取整;6个价格出口统一走这里
    fun quantD(v: Double): Int {
        if (!(v > 0) || !v.isFinite()) return 2
        return Math.max(0, 2 - Math.floor(Math.log10(v)).toInt())
    }

    fun quantPx(v: Double): String =
        String.format(Locale.US, "%.${quantD(v)}f", v)

    fun quantCeil(v: Double): String {
        val d = quantD(v)
        if (d == 0) return Math.ceil(v).toLong().toString()
        val f = Math.pow(10.0, d.toDouble())
        return String.format(Locale.US, "%.${d}f", Math.ceil(v * f) / f)
    }

    // 明细行价格显示:千分位 + quant精度
    fun fmtQ(v: Double): String =
        String.format(Locale.US, "%,.${quantD(v)}f", v)

    fun fmtCeilQ(v: Double): String {
        val d = quantD(v)
        val x = if (d == 0) Math.ceil(v)
        else { val f = Math.pow(10.0, d.toDouble()); Math.ceil(v * f) / f }
        return String.format(Locale.US, "%,.${d}f", x)
    }

    // ---------- v4§7 美元口径:汇率层(1 USD = X 外币,原生价→美元 = ÷X) ----------
    object FX {
        // 必须显式带Origin(部分网关据此放行CORS头)
        const val ORIGIN = "https://gridcalc.app"
        const val TTL = 6 * 60 * 60 * 1000L // 6小时缓存
        private const val PF = "gridcalc_fx_v1"
        private const val PKEY = "gc_fx"

        @Volatile var rates = HashMap<String, Double>()
        @Volatile var dateStr = ""
        @Volatile var ts = 0L
        @Volatile var loaded = false
        @Volatile var inflight = false
        // 汇率异步到达回调(主线程重画说明行+图表);由MainActivity接线
        var onReady: (() -> Unit)? = null

        // 币种按代码形状判断(4/5位数字=港股HKD,6位=韩股KRW,sh/sz前缀=A股CNY,其余美元)
        // ⚠⚠⚠ 2026-10-04 **A 股接进汇率层**。原来这行注释写着「A股CNY路由未做」——
        //   那是 A 股还没有源时的权宜之计。A 股接通腾讯源之后不补，就会把
        //   **人民币价格当美元显示**，量纲错得无声无息。
        //   实测 `sh688825` 走腾讯月线收 53.97，是人民币；不换算就会屏上写「USD 53.97」。
        //   （对照：港股 00700 屏上显示 53.68 也是美元，是 FX 正常折算后的结果。）
        fun curOf(sym: String): String {
            val u = sym.trim().uppercase(Locale.US)
            if (Regex("^(SH|SZ)\\d{6}$").matches(u)) return "CNY"   // A股：CNY（必须排在 5/6 位数字之前）
            if (Regex("^\\d{4}$").matches(u) || Regex("^\\d{5}$").matches(u)) return "HKD"
            if (Regex("^\\d{6}$").matches(u)) return "KRW"
            return "USD"
        }

        fun rateOf(cur: String): Double? = if (cur == "USD") 1.0 else rates[cur]
        // ⚠ 2026-10-13 用户裁定：汇率断供时**用旧汇率照折 + 行尾注明**（不藏行、不静默）。
        //   staleness 与 ensure() 的 TTL 同一判据；rateDate = 最近一次成功拉取的日期（fxText 同源）。
        fun rateStale(): Boolean = loaded && (System.currentTimeMillis() - ts >= TTL)
        fun rateDate(): String = dateStr

        // 缓存键尾缀:美元=1,无汇率=0,有汇率用去尾零十进制串(7.844/1355.05)
        fun keyRate(sym: String): String {
            val r = rateOf(curOf(sym)) ?: 0.0
            if (!(r > 0)) return "0"
            return java.math.BigDecimal(r).stripTrailingZeros().toPlainString()
        }

        private fun fmtRate(r: Double): String =
            String.format(Locale.US, "%,.4f", r).trimEnd('0').trimEnd('.')

        // 行情页说明行三态:美元报价/有汇率(带日期)/取不到保持原币
        fun fxText(sym: String): String {
            val cur = curOf(sym)
            if (cur == "USD") return "美元口径(该品种以美元报价)"
            val r = rateOf(cur)
            if (r == null || !(r > 0)) return "美元口径:暂时没取到 $cur 汇率,价格仍按原币显示"
            return "美元口径:1 USD = ${fmtRate(r)} $cur(汇率 $dateStr)"
        }

        private fun pref(c: android.content.Context): android.content.SharedPreferences? =
            try {
                c.getSharedPreferences(PF, android.content.Context.MODE_PRIVATE)
            } catch (_: Exception) {
                null // 写失败降级为内存态,不崩
            }

        // 只在App打开/回前台时调一次:缓存新鲜则不动网,过期/缺失则发起一次请求(无轮询无定时器)
        fun ensure(c: android.content.Context) {
            try {
                if (!loaded) {
                    val s = pref(c)?.getString(PKEY, null)
                    if (!s.isNullOrEmpty()) {
                        val j = JSONObject(s)
                        val m = HashMap<String, Double>()
                        for (k in listOf("HKD", "KRW", "CNY")) {
                            val v = j.optDouble(k, 0.0)
                            if (v > 0) m[k] = v
                        }
                        if (m.isNotEmpty()) {
                            rates = m
                            dateStr = j.optString("date", "")
                            ts = j.optLong("ts", 0L)
                            loaded = true
                        }
                    }
                }
            } catch (_: Exception) {
            }
            if (loaded && System.currentTimeMillis() - ts < TTL) return
            if (inflight) return
            inflight = true
            Thread {
                try {
                    val (m, d) = fetch()
                    if (m.isNotEmpty()) {
                        rates = HashMap(m)
                        if (d.isNotEmpty()) dateStr = d
                        ts = System.currentTimeMillis()
                        loaded = true
                        try {
                            val o = JSONObject()
                            for ((k, v) in m) o.put(k, v)
                            o.put("date", dateStr)
                            o.put("ts", ts)
                            pref(c)?.edit()?.putString(PKEY, o.toString())?.apply()
                        } catch (_: Exception) {
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    inflight = false
                    // 无论成败都回调:成功→按新汇率重画;失败→说明行切到「暂时没取到/沿用旧缓存」
                    onReady?.invoke()
                }
            }.start()
        }

        private fun parse(s: String, date: String): Pair<Map<String, Double>, String> {
            val j = JSONObject(s)
            val rr = j.optJSONObject("rates") ?: return Pair(emptyMap(), "")
            val m = mutableMapOf<String, Double>()
            for (k in listOf("HKD", "KRW", "CNY")) {
                val v = rr.optDouble(k, 0.0)
                if (v > 0) m[k] = v
            }
            val dt = date.ifEmpty { j.optString("date", "") }
            return if (m.isNotEmpty()) Pair(m, dt) else Pair(emptyMap(), "")
        }

        private fun fetch(): Pair<Map<String, Double>, String> {
            // 主源:frankfurter(ECB数据)
            try {
                val r = parse(
                    get("https://api.frankfurter.app/latest?from=USD&to=HKD,KRW,CNY",
                        "application/json", UA, 6000, ORIGIN), "")
                if (r.first.isNotEmpty()) return r
            } catch (_: Exception) {
            }
            // 备源:open.er-api
            try {
                val s = get("https://open.er-api.com/v6/latest/USD",
                    "application/json", UA, 6000, ORIGIN)
                val j = JSONObject(s)
                val d = try {
                    val raw = j.optString("time_last_update_utc", "")
                    java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(
                        java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
                            .parse(raw) ?: java.util.Date())
                } catch (_: Exception) {
                    ""
                }
                val r = parse(s, d)
                if (r.first.isNotEmpty()) return r
            } catch (_: Exception) {
            }
            return Pair(emptyMap(), "")
        }

        // 【F-2a · 2026-10-01 修 B-1】**拿不到汇率就明确失败，绝不静默放行未折算的值。**
        //
        // 改前是 `val r = rateOf(cur) ?: return` + `if (!(r > 0)) return` —— 拿不到汇率时
        // **静默 return**，调用方拿不到任何信号，照旧把**原币** K 线缓存下来并画上屏：
        //   MktPanel.kt:1199 fxify → :1200 MktCache.put → :1201 MktSave.store → :1202 renderStock
        // 于是港股/韩股的支撑位、y 轴、支撑线全在原币上，而状态行一个字都不说。
        // 这就是 t24 评审判定的阻断项 B-1，机制定位到本行。
        //
        // 现在返回 Boolean：**true = 已折算或本来就是美元；false = 没折成，调用方必须走「无源」**。
        // 折算成功的语义没变：判重仍在每根 K 线上（首画/终画/缓存共享同批对象只折一次）；
        // 缓存仍存**已折算**的美元价 —— 这是安全的，因为缓存键 `MktCache.key` 里带了
        // `@FX.keyRate(sym)`，汇率变则键变，会重新抓新对象再折，不会返回过期值。
        fun fxify(vs: List<VendorKs>, sym: String): Boolean {
            val cur = curOf(sym)
            if (cur == "USD") return true            // 本来就是美元，无需折算
            val r = rateOf(cur)
            if (r == null || !(r > 0)) {
                // **没有汇率 ⟹ 明确告诉调用方失败**。这里不折、也不假装成功。
                return false
            }
            for (vk in vs) {
                if (vk.k.all { it.usd }) continue
                vk.k = vk.k.map { k ->
                    if (k.usd) k
                    else k.copy(
                        o = k.o / r, h = k.h / r, l = k.l / r, c = k.c / r, usd = true)
                }
            }
            return true
        }
    }

    // 终画选优:PIN源(BN)有≥3根直接钉死,否则按报价成交额qv选优(照稿子)
    fun aggregate(vs: List<VendorKs>): Agg = aggregate(vs, "")

    /**
     * [sym] 只用于取该品种的**手动拆股**；传空串即不做任何复权。
     *
     * ⚠⚠⚠ 2026-10-05 **不再有任何自动判定**（用户裁定：「你不要自己判定了，
     *   我说了我手动输入数据。把自动复权填写数据的功能取消。」）。
     *   原启发式 forwardAdjust 与配套（SPLIT_CANDIDATES / SPLIT_HI_K /
     *   residualJump / estimateRatio）**整块删除** —— 本文件里已不存在任何
     *   「看数据猜拆股比」的代码，也就没有「猜错了悄悄生效」的可能。
     *   ⟹ 开关关 = 原样显示；开关开 = 按用户录的 (日期, 比例) 算。**没有第三种行为。**
     */
    fun aggregate(vs: List<VendorKs>, sym: String): Agg {
        val win = vs.find { it.v == PIN && it.k.size >= 3 }
            ?: vs.maxByOrNull { v -> v.k.sumOf { numD(it.qv) } }!!
        // ⚠ 先**复权**再 takeLast(240)：顺序不能反。
        //   复权要在整段历史上做（除权日可能在被截掉的老段里），截完再复权就只剩一半信息。
        val sorted = win.k.sortedBy { it.t }
        return Agg(SplitBook.apply(sorted, sym).takeLast(240), vendorName(win.v))
    }

    // ══════════════ 手动拆股（前复权）══════════════
    //
    // 【为什么只做手动】从**未复权 OHLC 反推拆股比**在信息上欠定 ——
    //   3:1 拆股与「真跌了 3 倍」产出**逐位相同**的数据；
    //   实测合法涨幅上��� 2.23（TSLA 季线 2019-12→2020-03）与最小拆股比 3.01 只隔 1.35 倍，
    //   任何阈值都会在正常上涨上开火。
    //   **缺的那条信息（哪天、几拆几）数据里根本没有，只能由人给。**
    //
    // ⚠ 2026-10-05 用户进一步裁定：「不要自己判定了，把自动复权填写数据的功能取消」。
    //   ⟹ 本对象**只做一件事**：把用户给的 (日期, 比例) 变成价格因子。
    //     没有检测、没有猜测、没有兜底启发式 —— 屏上看到的价格要么是源的原始值，
    //     要么就是用户自己填出来的，**不存在第三种来源**。

    /** 一次拆股。[date] 是除权日，形如 `2022-08-25`；[ratio] 是拆股比，3.0 表示 3:1。 */
    data class Split(val date: String, val ratio: Double) {
        /** 屏上显示：比例用「N:1」写法；反向拆股(0.1)照样如实显示，不藏。 */
        override fun toString(): String =
            "$date  " + (if (ratio >= 1.0) {
                val r = if (ratio == Math.floor(ratio)) ratio.toInt().toString() else ratio.toString()
                "$r:1"
            } else {
                "1:" + Math.round(1.0 / ratio)
            })
    }

    private data class Entry(var on: Boolean = false, val splits: MutableList<Split> = mutableListOf())

    object SplitBook {
        private const val SP = "gridcalc_splits_v1"
        private const val PKEY = "splits"

        /** 内存态。[aggregate] 是静态入口、没有 Context，只能靠 [ensure] 预载。 */
        @Volatile private var data = HashMap<String, Entry>()
        private val lock = Any()

        private fun norm(sym: String) = sym.trim().uppercase(Locale.US)

        private fun entry(sym: String): Entry = synchronized(lock) {
            data.getOrPut(norm(sym)) { Entry() }
        }

        // ---------- 持久化（读写全程 try/catch：存不下就算了，绝不因它崩）----------

        fun ensure(c: android.content.Context) {
            try {
                val s = c.getSharedPreferences(SP, android.content.Context.MODE_PRIVATE)
                    .getString(PKEY, null) ?: return
                val jo = JSONObject(s)
                val m = HashMap<String, Entry>()
                for (sym in jo.keys()) {
                    val o = jo.optJSONObject(sym) ?: continue
                    val e = Entry()
                    e.on = o.optBoolean("on", false)
                    val arr = o.optJSONArray("splits") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val x = arr.optJSONObject(i) ?: continue
                        val d = x.optString("d", "")
                        val r = x.optDouble("r", 0.0)
                        if (d.isNotEmpty() && r > 0) e.splits.add(Split(d, r))
                    }
                    e.splits.sortBy { it.date }
                    m[sym] = e
                }
                data = m
            } catch (_: Exception) {
            }
        }

        private fun persist(c: android.content.Context) {
            try {
                val jo = JSONObject()
                synchronized(lock) {
                    for ((k, e) in data) {
                        val o = JSONObject()
                        o.put("on", e.on)
                        val arr = JSONArray()
                        for (s in e.splits) {
                            val x = JSONObject()
                            x.put("d", s.date); x.put("r", s.ratio)
                            arr.put(x)
                        }
                        o.put("splits", arr)
                        jo.put(k, o)
                    }
                }
                c.getSharedPreferences(SP, android.content.Context.MODE_PRIVATE)
                    .edit()?.putString(PKEY, jo.toString())?.apply()
            } catch (_: Exception) {
            }
        }

        // ---------- 读 ----------

        fun list(sym: String): List<Split> = synchronized(lock) { data[norm(sym)]?.splits?.toList() } ?: emptyList()

        fun enabled(sym: String): Boolean = synchronized(lock) { data[norm(sym)]?.on } ?: false

        // ---------- 写 ----------

        fun setEnabled(c: android.content.Context, sym: String, on: Boolean) {
            entry(sym).on = on
            persist(c)
        }

        /** 覆盖式写入（UI 的增/删都走它）。同一天重复录入按**后者覆盖**处理。 */
        fun put(c: android.content.Context, sym: String, list: List<Split>) {
            val e = entry(sym)
            e.splits.clear()
            val byDate = LinkedHashMap<String, Double>()
            for (s in list) {
                if (s.date.isBlank() || !(s.ratio > 0)) continue
                byDate[s.date] = s.ratio
            }
            e.splits.addAll(byDate.map { Split(it.key, it.value) })
            e.splits.sortBy { it.date }
            persist(c)
        }

        // ---------- 输入解析 ----------

        /**
         * 解析用户输入的一行。支持：
         *   `2022-08-25 3`   `2022-08-25 3:1`   `1:10`   `20220825 5`   `2022/08/25 5`
         * 返回 null 表示**这一行没看懂** —— UI 据此提示，不猜、不吞。
         */
        fun parseLine(line: String): Split? {
            val parts = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.size < 2) return null
            val d = parts[0].trim().replace('/', '-').replace(Regex("^(\\d{4})-(\\d{2})-(\\d)$"), "$1-$2-0$3")
            if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(d)) return null
            val r = parseRatio(parts[1]) ?: return null
            return Split(d, r)
        }

        /** `3` / `3:1` / `1:10` / `5:4` → 拆股比。写错就 null，不做默认。 */
        fun parseRatio(t: String): Double? {
            val s = t.trim().removeSuffix("倍")
            if (s.isEmpty()) return null
            if (!s.contains(':')) {
                val v = s.toDoubleOrNull() ?: return null
                return if (v > 0 && v <= 100) v else null
            }
            val ab = s.split(':')
            if (ab.size != 2) return null
            val a = ab[0].trim().toDoubleOrNull() ?: return null
            val b = ab[1].trim().toDoubleOrNull() ?: return null
            if (!(a > 0) || !(b > 0)) return null
            val r = a / b
            return if (r > 0 && r <= 100) r else null
        }

        // ---------- 应用 ----------

        private fun dayKey(t: Long): String =
            java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date(t))

        /**
         * 跨除权日的那一根 —— 用户裁定：
         * **「如果除权日在月中，而不是在月末最后一天，那除权价就直接按照月收盘价计算。」**
         *
         * 【为什么月末收盘价能当基准】它是那一期的**最后一个价**，而除权日就落在这一期之内
         *   ⟹ 收盘必然**已经在拆股之后**。不需要跟任何邻居比，也不需要猜。
         *   实测 TSLA 2022-08-30（3:1 在 8/25）：
         *     c = 275.61                                   ← 基准，拆股后
         *     o/c = 903.83/275.61 = 3.28  出带 → 开在拆股前 → ÷3 = 301.28
         *     h/c = 944.00/275.61 = 3.42  出带 → 高在拆股前 → ÷3 = 314.67
         *     l/c = 271.81/275.61 = 0.99  在带 → 低已是新价 → 不动
         *     → [301.28, 314.67, 271.81, 275.61]
         *
         * 【带 [0.5, 2.2]】一根柱子内部 o/h/l 与 c 的比值天然有界（高/收、低/收通常 <1.2），
         *   出带只说明**尺度不对**。要排除 1/3=0.33、1/5=0.20 与 3、5 这些拆股尺度，
         *   同时容纳真实月涨幅（实测最大 +98% = 1.98）。
         *
         * ⚠ 拆股比**只来自用户录入**，这里不估、不吸附、不回验。
         *   2026-10-05 用户裁定「不要自己判定了」之后，本文件里已不存在任何
         *   「看数据猜拆股比」的代码；这一段是**唯一**的跨线处理，且不含量。
         */
        private fun crossBar(b: KLine, r: Double): KLine {
            if (!(b.c > 0.0)) return b
            fun pre(f: Double): Boolean {
                if (!(f > 0.0)) return false
                val k = f / b.c
                return k < 0.5 || k > 2.2
            }
            return b.copy(
                o = if (pre(b.o)) b.o / r else b.o,
                h = if (pre(b.h)) b.h / r else b.h,
                l = if (pre(b.l)) b.l / r else b.l,
                c = b.c)                                   // 收盘是基准，不动
        }

        /**
         * 前复权。
         * - **除权日之前**的柱子：整体除以比例，成交量按同比例放大。
         * - **除权日当天那根**：不动（那天开盘就已经是新价）。
         * - **含除权日的那一根**（拆股在月/季中）：走 [crossBar]，以月末收盘价为基准。
         *
         * ⚠ 边界用「日期字符串比大小」（ISO 字典序 == 时间序），不碰时区 ——
         *   数据源的 t 是「日期」不是「时刻」，任何本地时区换算都会把跨日判断挪错一天。
         */
        fun apply(ks: List<KLine>, sym: String): List<KLine> {
            // ⚠⚠⚠ 2026-10-10 **幂等闸：整批已经复权过就原样返回，绝不复权第二次**。
            //   用户报「每次取完数据之后，就和之前保存的数据相差很大」的根因就在这里：
            //   MktPanel.renderAgg 把 `aggregate()` **之后**的 show 放进 LastChart，
            //   而 FavDist 的复用路径对它**又** aggregate 一次 ⟹ 同一批 K 线复权两次。
            //   网络路径与 MktCache/MktSave 两条路径喂的都是复权**之前**的数据，只复权一次
            //   ⟹ 走 LastChart 的那一次刷新与其它路径算出**不同的支撑位**。
            //   （完整口径对照与离线实测数值见 KLine.adj 的注释。）
            //   ⚠ 判据是「**整批**都带标记」（all）：只要有一根没复权过，说明这批不是本函数
            //     的产物，照常按比例处理；本函数出口会把**每一根**都打上标记，所以正常
            //     情况下要么全 false（原始）、要么全 true（已复权），不存在半批状态。
            if (ks.isNotEmpty() && ks.all { it.adj }) return ks
            val sp = list(sym)
            if (!enabled(sym) || sp.isEmpty()) return ks
            // ⚠⚠ 出口必须把**整批**都标上 [KLine.adj]，包括**没有被改动的那些根**。
            //   踩过的坑：只给「被除过的柱子」打标记时，除权日**之后**的那些根仍是 false
            //   ⟹ 上面那句 `ks.all { it.adj }` 永远为假 ⟹ 幂等闸形同虚设
            //   （离线实测：40 根里只有 23 根被标记，第二次 aggregate 照样把价格再除一遍）。
            //   语义：本函数返回的**每一根**都是「已按当前拆股簿对齐过」的 —— 除权日之后的根
            //   本来就该是原样，所以"原样"也是本函数的产物，同样要标记。
            //   ⚠ 因此它只在本函数的出口打标记（`out` 是 `ArrayList(ks)` 的**新列表**，
            //     不会把标记写回调用方手里的原始数据）。
            val out = ArrayList(ks)
            for (s in sp.sortedBy { it.date }) {
                var i = 0
                while (i < out.size && dayKey(out[i].t) < s.date) i++
                // ⟹ i = 第一根标签 ≥ 除权日 的柱子。
                //
                // ⚠⚠ **跨界柱要往前让一根**，不能只认 out[i]。
                //   源给月度柱的标签与真实月末**可能差一两天**（实测 TSLA 五拆一在
                //   2020-08-31，而百度把那根标成 `2020-08-30`）。于是除权日会落进
                //   「标签 < 除权日」那一侧，那根若被整根除，**收盘价会被多除一次**：
                //     2020-08 原 [1449.20 2318.49 440.11 498.32]，close 498.32 是**真实的拆股后价**
                //     整根 ÷5 ⟹ 99.66，再被 2022 那次 ÷3 ⟹ **33.22**（错 5 倍）
                //     正确值应是 498.32 ÷3 = **166.11**
                //   ⟹ 整根除只做到 [0, i-2]，**最后两根都走 [crossBar]**：
                //     真跨界的那根按月末收盘价定标；不是的那根各字段比值都在带内，原样返回。
                val end = maxOf(0, i - 1)                  // 整根除的右开界
                for (j in 0 until end) {
                    val k = out[j]
                    out[j] = k.copy(o = k.o / s.ratio, h = k.h / s.ratio,
                        l = k.l / s.ratio, c = k.c / s.ratio,
                        v = k.v * s.ratio, qv = k.qv * s.ratio, adj = true)
                }
                if (i - 1 >= 0) {
                    // 倒数第二根（标签 < 除权日 的最后一根）先按 [crossBar] 对齐一次：
                    //   **对齐后毫无变化** ⟹ 它整根都在拆股前，按比例除
                    //   对齐后有变化     ⟹ 它就是跨界柱（标签比真实月末早了一两天），保留对齐结果
                    // 这一条不是「判定」，是用户那条规则（「按月收盘价算」）的直接后果：
                    // 拆股比仍然只来自用户录入，这里没有引入任何新的猜测。
                    val x = crossBar(out[i - 1], s.ratio)
                    if (x == out[i - 1]) {
                        val k = out[i - 1]
                        out[i - 1] = k.copy(o = k.o / s.ratio, h = k.h / s.ratio,
                            l = k.l / s.ratio, c = k.c / s.ratio,
                            v = k.v * s.ratio, qv = k.qv * s.ratio, adj = true)
                    } else {
                        out[i - 1] = x.copy(adj = true)
                    }
                }
                if (i < out.size) out[i] = crossBar(out[i], s.ratio).copy(adj = true)
            }
            // ⚠ 收尾把整批补标一遍（见上方「出口必须把整批都标上」）：
            //   除权日之后的那些根在上面三个分支里都没被碰到，它们的 o/h/l/c 就该是原样，
            //   但**仍然要带标记**，否则幂等闸不成立。
            for (j in out.indices) if (!out[j].adj) out[j] = out[j].copy(adj = true)
            return out
        }
    }

    // ---------- 品种中文名 ----------
    //
    // 【为什么需要】自选行按「**中文名** · 徽章 · 代码」排（见 FavPanel 的 fname/fsub）。
    //   港/韩/美/币的名字都在 [MktSuggest] 的本地表里，**A 股没有**——
    //   `favName("sh688825")` 返回空，标题就回落成原始代码 `sh688825`，
    //   第二行因为「标题已是代码」而不再重复显示代码（见 `titleIsCode`）。
    //   ⟹ 屏上那一行就成了「原始代码 + A股 + 空代码」，与其他品种的规则对不上。
    //   用户裁定：「我给你代码是让你去搜它的名字叫什么、它的代码是什么，
    //   你直接把这个给贴上去了。有没有看其他股票展示的规则是什么？」
    //
    // 【数据源】腾讯行情 `qt.gtimg.cn/q=`，**一次可查多个代码**（逗号分隔），
    //   返回 `v_sh688825="51~长鑫存储~688825~54.79~..."`，第 2 个 `~` 之间就是中文名。
    //   ⚠ **GBK 编码**，必须显式指定字符集（见 [get] 的 charset 参数）。
    //   选它而不是百度/东财：腾讯是本仓 A 股 K 线已在用的源，同一个域名不新增可达性风险；
    //   而东财在本机实测不可达。
    //
    // 【限流】一次请求查一批（上限 30 个），结果**落盘缓存**，命中就不再发请求。
    object NameBook {
        private const val SP = "gridcalc_names_v1"
        private const val PKEY = "names"
        private const val BATCH = 30
        private val lock = Any()
        @Volatile private var map = HashMap<String, String>()
        @Volatile private var inflight = false

        private fun norm(sym: String) = sym.trim().lowercase(java.util.Locale.US)

        /** 本地查，不发网络。UI 同步路径用它。 */
        fun of(sym: String): String = synchronized(lock) { map[norm(sym)] } ?: ""

        fun attach(c: android.content.Context) {
            try {
                val s = c.getSharedPreferences(SP, android.content.Context.MODE_PRIVATE)
                    .getString(PKEY, null) ?: return
                val jo = JSONObject(s)
                val m = HashMap<String, String>()
                for (k in jo.keys()) m[norm(k)] = jo.optString(k, "")
                map = m
            } catch (_: Exception) {
            }
        }

        private fun persist(c: android.content.Context) {
            try {
                val jo = JSONObject()
                synchronized(lock) { for ((k, v) in map) if (v.isNotBlank()) jo.put(k, v) }
                c.getSharedPreferences(SP, android.content.Context.MODE_PRIVATE)
                    .edit()?.putString(PKEY, jo.toString())?.apply()
            } catch (_: Exception) {
            }
        }

        /**
         * 取名熔断：查不到名字的批次 → 冷却到期时刻。
         *
         * ⚠⚠⚠ 2026-10-05 **这个熔断是修「点行没反应」的另一半，不可删**。
         *   实测：腾讯对美股裸代码（TSLA / MU / BTC）回的是 `v_pv_none_match="1"` ——
         *   一个名字都取不到，于是 `miss` **永远**是同一批。而 `finally` 又无条件回调 [done]
         *   ⟹ repaint → ensure → 取名(秒失败) → done → repaint，实测 **~33ms 一圈、30 秒 1151 次**
         *   ⟹ `favList.removeAllViews()` 每秒重建约 30 遍
         *   ⟹ 手指按下时那一行已经被换掉，抬手收到 ACTION_CANCEL
         *   ⟹ **点击命中率实测 1/8**，用户实报「点好几次才进去」「长按没反应」。
         *   同一轮循环还顺带解释了「空闲时仍 ~9fps 常驻渲染」与「uiautomator 永远
         *   could not get idle state」——那两条都不是显示层的问题，是这个循环在烧。
         *
         * 冷却时长沿用本文件既有的失败记忆口径（srcBadMark / srcSoftMark 都是分钟级），
         * 不是自创新数值。
         */
        private val missCooldown = HashMap<String, Long>()
        private const val MISS_COOLDOWN_MS = 10 * 60_000L

        /**
         * 给「本地表里查不到名字」的符号补名。**整批一次请求**，后台线程跑。
         * [done] 在主线程回调，供 UI 刷新（结果异步到达，不刷新就看不到名字）。
         */
        fun ensure(c: android.content.Context, syms: List<String>, done: (() -> Unit)? = null) {
            val miss = syms.filter { it.isNotBlank() && of(it).isEmpty() }
                .map { it.trim() }.distinct().take(BATCH)
            // ⚠ miss 为空时**直接返回、不回调** —— [done] 的语义是「有东西变了」。
            //   无条件回调会让「repaint → ensure → done → repaint」绕成死循环。
            if (miss.isEmpty()) return
            // ⚠ 同理：**这一批已经问过、且冷却没过 ⟹ 不再发请求，也不回调**。
            //   「明知取不到还反复打」与「明知不通还猛打」是同一个原则问题。
            val batch = miss.joinToString(",")
            val now = System.currentTimeMillis()
            synchronized(lock) {
                if (now < (missCooldown[batch] ?: 0L)) return
                missCooldown[batch] = now + MISS_COOLDOWN_MS
            }
            if (inflight) return
            inflight = true
            var gained = false          // ← 本轮**真的补到名字**了吗
            Thread {
                try {
                    val url = "https://qt.gtimg.cn/q=" + miss.joinToString(",")
                    val txt = get(url, ua = uaRnd(), timeoutMs = 8000,
                        referer = "https://gu.qq.com/", charset = java.nio.charset.Charset.forName("GBK"))
                    synchronized(lock) {
                        for (line in txt.split(';')) {
                            val a = line.indexOf('=')
                            if (a < 0) continue
                            val key = line.substring(0, a).trim().removePrefix("v_").trim()
                            if (key.isEmpty()) continue
                            // "51~长鑫存储~688825~..." ⟹ 取第 2 段
                            val f = line.substring(a + 1).trim().trim('"').split('~')
                            val nm = if (f.size > 1) f[1].trim() else ""
                            // ⚠ `v_pv_none_match="1";` 拆出来只有 ["1"]，f.size==1 ⟹ nm 为空，
                            //   **这里就挡住了**。不需要再对第 3 段做校验 ——
                            //   正常返回里第 3 段就是代码本身（v_tsla="51~特斯拉~TSLA~…"），
                            //   拿它跟键比大小写会**把正常数据也判成无效**（我第一版就栽在这）。
                            if (nm.isEmpty()) continue
                            map[norm(key)] = nm
                            gained = true
                        }
                    }
                    if (gained) persist(c)
                } catch (_: Exception) {
                } finally {
                    inflight = false
                    // ⚠⚠ **只有真的补到了名字才回调**（[done] 的语义是「有东西变了」）。
                    //   改前这里是**无条件**回调 —— 失败也回调，而 repaint 是 [done] 的实现
                    //   ⟹ 取不到名字的批次会把两段代码焊成一个无限循环（见上面 MISS_COOLDOLD 注释）。
                    if (gained && done != null) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post(done)
                    }
                }
            }.start()
        }
    }

    fun profileOf(ksRaw: List<KLine>): Profile {
        // ⚠ 2026-10-07 **脏根过滤（真机实证：NFLX自选行+1532.43%）**。
        //   各腿只拦了h<=0的行，一根l为负/零的脏行漏网 ⟹ lo被拉成负数 ⟹ 支撑算出负值 ⟹ 距支撑>100%。
        //   修在profileOf（自选+行情唯一的支撑生产者），全品种一次收敛；调用方看到的就是无支撑/少支撑，不编数。
        val clean = ksRaw.filter { k ->
            k.o.isFinite() && k.h.isFinite() && k.l.isFinite() && k.c.isFinite() &&
                k.o > 0 && k.h > 0 && k.l > 0 && k.c > 0 && k.h >= k.l
        }
        if (clean.size < 3) {
            // ⚠⚠ 2026-10-10 **退化分支也必须同源**（详见 data class Profile.ks 的注释）。
            //   不足 3 根算不出剖面/支撑（rv/sup 照旧退化成空），但**量程仍要从这一份算**。
            //   改前这里写死 `lo=0.0/hi=1.0`，而调用方手里是**未过滤**的原始列表 ⟹
            //   只要原始列表里混了脏柱把 clean 打到 2 根，用户报的「K 线掉出绘图区」
            //   就必然复现：现价 500 的股票被 0..1 的量程映射到 y≈负几千，一根都看不见。
            //   ⟹ 现在把 clean 连同**由它算出的** lo/hi 一起交出去，同源在两条出口上都成立。
            var lo0 = Double.POSITIVE_INFINITY
            var hi0 = Double.NEGATIVE_INFINITY
            clean.forEach { c -> lo0 = min(lo0, c.l); hi0 = max(hi0, c.h) }
            val has = clean.isNotEmpty()
            return Profile(DoubleArray(24), DoubleArray(24), DoubleArray(24),
                0, 0, if (has) lo0 else 0.0, if (has) hi0 else 1.0,
                0.0, 0.0, 0.0, emptyList(), clean)
        }
        // ⚠⚠⚠ 2026-10-12 **尺度带过滤已删除（用户裁定选 A：删、不加任何自动判定）**。
        //   本函数现在只剩上面那一道 clean 脏柱过滤：下面的 `ks` 就是 clean 本身，
        //   lo/hi/rv/ru/rd/sup 全部由它算 —— **20 根合法 K 线一根都不剔**。
        //
        // 【删掉的是什么（墓碑，不要抄回来）】2026-10-07 为修「NFLX 月线距支撑 +1117%」
        //   引入的尺度带 [现价/3, 现价×3]：以最后一根收盘价为锚，要求每根 K 线**整根**落带内，
        //   带外的整根剔除；配套还有「被剔了几根」的计数字段（Profile.hidden → MktInfo.hidden
        //   → MktPanel 状态行拼接），同日一并删除。它自称「不是猜拆股比」（2026-10-07 注释原话），
        //   但按绝对价格猜的正是「这一根是不是拆股前的」—— 那就是自动判定拆股。
        //
        // 【用户裁定（两条，原文都在本文件里）】
        //   ① 2026-10-05：「你不要自己判定了，我说了我手动输入数据。把自动复权填写数据的
        //      功能取消。」（见 [aggregate] 的注释；「拆股比只来自用户录入」见 [SplitBook] 的注释）
        //   ② 2026-10-12：选 **A** —— 删尺度带、**不加任何自动判定**。连「跳变检测」
        //      （按相邻比值判拆股）也被当场否决：那同样是自己判定。
        //
        // 【为什么在 profileOf 而不是 FavDist】这是自选与行情**唯一**的支撑生产者。
        //   放在这里，两屏的支撑必然同源同值 —— 用户此前实报过「屏上看着没破、背地里算成破了」，
        //   那次就是两条取数路径各算一份。此处改一处，两屏一起收敛。
        //
        // 【为什么数据上可分也不做自动判定 · 两个真实样本】
        //   · MU 月线 20 根（2025-03 ~ 2026-10，20/20 有成交量）连续 84 → 1255（15 倍），
        //     **最大相邻收盘比值仅 1.88x** —— 无拆股的连续大涨，尺度带却把它剔得只剩 7 根
        //     （用户 2026-10-11 实测质问「数量 20，屏幕上怎么只画 7 根」）；
        //   · NFLX 月线 2025-10-31 收 1118.86 → 2025-11-28 收 107.58 = **10.40x**，
        //     那是真正的 10:1 拆股断崖（源不做复权，记成 −90.38%）。
        //   1.88x 与 10.40x 在数据上确实可分 —— 但「判据由谁定、判错了算谁的」正是裁定本身：
        //   用户要的是「拆股比只来自用户录入」，所以这里不做任何判定，哪怕代价明确。
        //
        // 【已知代价（2026-10-12 随选 A 一并接受）】未填拆股表的拆股票，拆股前的柱子
        //   会按原始价位参与剖面 ⟹ 支撑可能落在拆股前的价位上、距支撑百分比可能很夸张。
        //   · 自选页有兜底：FavDist.sane() 把 >200% 的残值判为坏数据丢弃重算（防线在 FavDist.kt）；
        //   · **行情页没有兜底** —— 屏上就照算出来的画，这是用户接受的；
        //   · 填了拆股表的品种不受影响：[SplitBook.apply] 前复权后 20 根自然同尺度（本次一行未动）。
        //
        // ⚠⚠ 下面这一行 `val ks` 就是**全项目唯一的「上屏集合」**：
        //   lo/hi、rv/ru/rd、sup 由它算，末尾又把它原样装进 Profile.ks 交给绘制侧。
        //   ⟹ 过滤（只剩脏柱这一级）**只此一处**（绘制层 MktView 一个字都不复制，见 Profile.ks 的注释）。
        val ks = clean
        val rows = 24
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        ks.forEach { c ->
            lo = min(lo, c.l)
            hi = max(hi, c.h)
        }
        val w = (hi - lo) / rows
        val ww = if (w == 0.0) 1.0 else w
        val rv = DoubleArray(rows); val ru = DoubleArray(rows); val rd = DoubleArray(rows)
        fun add(s0: Double, s1: Double, vol: Double, up: Boolean) {
            if (!(vol > 0) || !(s1 > s0)) return
            val a = max(0, floor((s0 - lo) / ww).toInt())
            val b = min(rows - 1, floor((s1 - lo) / ww).toInt())
            for (i in a..b) {
                val rl = lo + ww * i
                val rh = lo + ww * (i + 1)
                val ov = max(0.0, min(rh, s1) - max(rl, s0))
                if (ov > 0) {
                    val q = vol * ov / (s1 - s0)
                    rv[i] += q
                    if (up) ru[i] += q else rd[i] += q
                }
            }
        }
        ks.forEach { c ->
            val bTop = max(c.o, c.c)
            val bBot = min(c.o, c.c)
            val grn = c.c >= c.o
            val tw = c.h - bTop
            val bw = bBot - c.l
            val bd = bTop - bBot
            val den = 2 * tw + 2 * bw + bd
            if (!(den > 0)) return@forEach
            add(bBot, bTop, bd * c.v / den, grn)
            val twv = 2 * tw * c.v / den
            val bwv = 2 * bw * c.v / den
            add(bTop, c.h, twv / 2, true)
            add(bTop, c.h, twv / 2, false)
            add(c.l, bBot, bwv / 2, true)
            add(c.l, bBot, bwv / 2, false)
        }
        var poc = 0
        for (i in rv.indices) if (rv[i] > rv[poc]) poc = i
        var up = poc
        var dn = poc
        var sum = rv[poc]
        val t70 = rv.sum() * 0.7
        var g = 0
        while (sum < t70 && g++ < 200) {
            val vu = if (up + 1 < rows) rv[up + 1] else -1.0
            val vd = if (dn - 1 >= 0) rv[dn - 1] else -1.0
            if (vu < 0 && vd < 0) break
            if (vu >= vd) sum += rv[++up] else sum += rv[--dn]
        }
        // 支撑位(照稿子v3.3):首沿从高价行往低价行扫,行量相对运行最高腰斩即第一支撑;
        // 后继沿:抛弃旧峰,下方须先出现爬升再取新峰,峰下腰斩为下一支撑;
        // 守卫防越界,行0可入选。返回行中点价数组(现价过滤由调用方做)。
        // ⚠ 2026-10-13 用户裁定「死路续扫」,见下方循环内注释:第二支撑由「第二次腰斩
        //   落在哪」决定,峰只是腰斩的参照物;收口后峰下无腰斩 ⟹ 续扫更深的峰,
        //   而不是终止整条链。
        val supRows = mutableListOf<Int>()
        var smax = -1.0
        var b = -1
        for (i in rows - 1 downTo 0) {
            if (rv[i] > smax) smax = rv[i]
            else if (smax > 0 && rv[i] <= smax * 0.5) {
                b = i
                break
            }
        }
        var gi = 0
        while (b >= 0 && gi++ < rows) {
            supRows.add(b)
            var m2 = -1.0
            var m2row = -1
            var climbed = false
            var prev = rv[b]
            var nxt = -1
            for (i in b - 1 downTo 0) {
                if (rv[i] > prev) climbed = true
                prev = rv[i]
                if (climbed && rv[i] > m2) {
                    m2 = rv[i]
                    m2row = i
                } else if (climbed && m2row >= 0 && rv[i] < m2 &&
                    (i == 0 || rv[i - 1] <= rv[i])
                ) {
                    // 稿740死角修复:climb后第一个显著局部峰收口(MU月线30:732.49/334.51)
                    //
                    // ⚠⚠ 2026-10-13 **用户裁定「死路续扫」**:收口后先在这个峰下找腰斩;
                    //   **找不到(空手而归)不再终止整条链**,继续往下找下一个(更深)的峰再试。
                    //   【语义(用户原话口径)】第二支撑由「第二次腰斩落在哪」决定,峰只是
                    //   腰斩的参照物:最高峰上方若有次峰、且次峰能腰斩出一根柱,就用次峰的
                    //   腰斩;最高峰若是底部量墙、墙下无物、且没有任何次峰能腰斩出结果 ⟹
                    //   不显示第二支撑(用户接受这个空档)。
                    // 【实测(腾讯月线30,tools/vpvr_sim2.py)】AMD月线截图 [13]→[13,0]
                    //   (最下面那根柱子拿到第二支撑线);MU [13,5] 732.86/335.04 不变;
                    //   NFLX [19,13,9] 不变;NVDA/TSLA 不变;AMD n=30 多补一条深位第二支撑。
                    // 【为什么不是「显著性门槛」】曾试过给收口加 m2>=K*全场最高 的门槛,
                    //   实测把 MU 23% 量的好峰、NFLX 2% 量的好峰一并拦掉(坏峰9%夹在两者
                    //   之间,任何比例阈值切不开),第二支撑整体归零 ⟹ 当场否决。
                    for (j in m2row - 1 downTo 0) {
                        if (rv[j] <= m2 * 0.5) {
                            nxt = j
                            break
                        }
                    }
                    if (nxt >= 0) break   // 峰下腰斩命中 → 第二(三…)支撑
                    // 空手而归 → 不 break,继续下行找更深的峰(死路续扫)
                }
            }
            if (m2row < 0) break
            if (nxt < 0) {
                // 收口从未触发(如一路走到底)时,仍按运行最高峰收一次尾
                for (i in m2row - 1 downTo 0) {
                    if (rv[i] <= m2 * 0.5) {
                        nxt = i
                        break
                    }
                }
            }
            b = nxt
        }
        return Profile(rv, ru, rd, up, dn, lo, hi,
            lo + ww * (poc + 0.5), lo + ww * (up + 1), lo + ww * dn,
            supRows.map { lo + ww * (it + 0.5) }, ks)
    }

    // ---------- 联动写值格式(稿 JS x.toPrecision(8) 等价) ----------
    // 8位有效数字、去尾随零、不用科学计数;与稿 linkCalc/linkPhigh 写入计算页的值同格式
    fun jsPrec(x: Double): String =
        BigDecimal.valueOf(x).round(MathContext(8)).stripTrailingZeros().toPlainString()

    // ---------- 格式化(照搬稿子) ----------

    fun mkFmt(v: Double?): String {
        if (v == null || v.isNaN()) return "--"
        val a = abs(v)
        return when {
            a >= 1000 -> "%,.2f".format(v)
            a >= 1 -> "%.2f".format(v)
            else -> "%.4f".format(v)
        }
    }

    fun mkVol(v: Double?): String {
        if (v == null || v.isNaN()) return "--"
        val a = abs(v)
        return when {
            a >= 1e9 -> "%.2fB".format(v / 1e9)
            a >= 1e6 -> "%.2fM".format(v / 1e6)
            a >= 1e3 -> "%.2fK".format(v / 1e3)
            else -> "%.2f".format(v)
        }
    }

    private fun cal(t: Long): Calendar =
        Calendar.getInstance().apply { timeInMillis = t }

    fun fmtDT(t: Long): String {
        val d = cal(t)
        return "${d.get(Calendar.YEAR)}-${d.get(Calendar.MONTH) + 1}-${d.get(Calendar.DAY_OF_MONTH)}"
    }

    fun fmtMD(t: Long): String {
        val d = cal(t)
        return "${d.get(Calendar.MONTH) + 1}-${d.get(Calendar.DAY_OF_MONTH)}"
    }

    /**
     * t319: 带年份的「YY-M」。月线/季线跨年时, fmtMD 会把 2023-02 和 2024-02
     * 都印成 "2-1", 两个刻度看起来一模一样(见 t318_geom.png)。
     * 只在**同一条轴上出现重复刻度**时才用这个, 日常单年序列仍用 fmtMD, 不啰嗦。
     */
    fun fmtYM(t: Long): String {
        val d = cal(t)
        return "%02d-%d".format(d.get(Calendar.YEAR) % 100, d.get(Calendar.MONTH) + 1)
    }
}
