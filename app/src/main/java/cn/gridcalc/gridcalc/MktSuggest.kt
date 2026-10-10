package cn.gridcalc.gridcalc

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

// ---------- 品种联想 · 数据层 ----------
// 对标稿子symList:内置表即时出结果,异步合并Yahoo搜索与OKX/Bybit现货表,
// 前缀优先稳定排序,最多6条,调用方凭seq丢弃过期结果。

data class SugItem(val s: String, val tag: String, val pre: Boolean)

object MktSuggest {

    private val TOPC = listOf("BTCUSDT")
    private val TOPM = listOf("XAUUSD" to "黄金", "XAGUSD" to "白银")
    // ⚠ 2026-10-07 GEV 快捷入口（用户点名：没有GEV）—— GEV 是美股，走股票腿。
    // ⚠ 同日用户裁定「不要外汇了，美债就只保留TLT」：
    //   TOPF（EURUSD/USDJPY/GBPUSD）**整条删除**；TOPB 收敛为**只有 TLT**（ETF→stock 腿）。
    //   US10Y 这类收益率指数不再提供任何入口，连同 CNNAME 里「十年期美债」的别名一并删除。
    private val TOPB = listOf("TLT")
    private val TOPS = listOf("AAPL", "TSLA", "NVDA", "MSFT", "AMZN", "META",
        "GOOGL", "AMD", "COIN", "MSTR", "NFLX", "BABA", "TSM", "PLTR", "GEV",
        // ⚠⚠ 2026-10-05 **代码纠正 + 补全**（用户 2026-10-03 点名过一次，2026-10-05 两次纠正）：
        //   ① 迈威尔的代码是 **MRVL**。原来这里记的 `MVIS` 是 **MicroVision**（微型视觉），
        //      **是另一家公司** —— 实机搜 MRVL 一直「无匹配」，用户实报「股票不全」。
        //   ② 太空探索技术公司的代码是 **SPCX**。用户前后更正过三次：
        //      先说 SPAC → 再说 SPAX → 最后确认为 **SPCX**，并明确「**没有 SPAX 这个股票**」。
        //      ⟹ 我先前误加的 SPAX 已**整个删除**，一个字都不留（教训见 [CNNAME] 上方）。
        //   ③ ⚠ **CXMT 不是股票代码，是长鑫存储的搜索别名**（用户 2026-10-05 原话：
        //      「我说 CXMT 时，是说希望 CXMT 能够搜索到他，而不是让他成为股票代码并且显示」）。
        //      ⟹ CXMT **不进这里**，只作为别名放 [ENALIAS]，见那里的同名条目。
        //   · MRVL=迈威尔 Marvell Technology · SPCX=太空探索技术公司 SpaceX
        //     · XOM=埃克森美孚 · GE=通用电气
        "MRVL", "SPCX", "XOM", "GE",
        // A 股：sh688825 长鑫存储（科创板，用户 2026-10-03 给出；2026-10-05 更正为「长鑫存储」）
        "sh688825")

    // 稿子CNNAME原样照搬(中文名→代码),v3.7 增补 17 种大宗商品 *00Y
    private val CNNAME = listOf(
        "美光" to "MU", "苹果" to "AAPL", "特斯拉" to "TSLA", "英伟达" to "NVDA",
        "微软" to "MSFT", "亚马逊" to "AMZN", "谷歌" to "GOOGL", "超微" to "AMD",
        "台积电" to "TSM", "阿里" to "BABA", "奈飞" to "NFLX", "微策略" to "MSTR",
        "英特尔" to "INTC", "高通" to "QCOM", "博通" to "AVGO", "甲骨文" to "ORCL",
        "拼多多" to "PDD", "京东" to "JD", "理想" to "LI", "蔚来" to "NIO",
        "小鹏" to "XPEV", "摩根大通" to "JPM", "辉瑞" to "PFE", "可口可乐" to "KO",
        "麦当劳" to "MCD", "迪士尼" to "DIS", "贝宝" to "PYPL", "思科" to "CSCO",
        "比特币" to "BTCUSDT", "黄金" to "XAUUSD", "白银" to "XAGUSD",
        // ⚠ 2026-10-05 补中文名：**光有代码搜不到中文名**。
        //   TOPS 里的条目只能按代码搜（`s.startsWith(q)`），而 CNNAME 才是中文名入口
        //   （`k.contains(q)`）—— 没有这一行，搜「迈威尔」「长鑫」一律「无匹配」。
        //   ⚠ 长鑫的名字以用户 2026-10-05 的更正为准：**长鑫存储**（688825）。
        //   两个中文名都留，搜哪个都能命中同一个代码。
        "迈威尔" to "MRVL", "长鑫存储" to "sh688825", "长鑫科技" to "sh688825",
        // ⚠ 2026-10-07 中文名补齐：GEV（否则中文搜不到）。
        "GE Vernova" to "GEV", "通用电气韦诺瓦" to "GEV",
        // ⚠ 同日用户裁定：外汇别名**全删**；美债别名收敛到 TLT。
        //   原「十年期美债」「十年美债」指向 US10Y，而 US10Y 已随美债支持一并退役，故删除。
        "美债" to "TLT", "长期美债" to "TLT",
        "原油" to "CL00Y", "布伦特原油" to "B00Y", "天然气" to "NG00Y",
        "燃油" to "HO00Y", "汽油" to "RB00Y", "铜" to "HG00Y", "铂金" to "PL00Y",
        "钯金" to "PA00Y", "玉米" to "ZC00Y", "小麦" to "ZW00Y", "大豆" to "ZS00Y",
        "豆粕" to "ZM00Y", "豆油" to "ZL00Y", "燕麦" to "ZO00Y", "稻谷" to "ZR00Y",
        "棉花" to "CT00Y", "糖" to "SB00Y"
    )

    // 稿§5 港股14个/韩股11个 中英名称表(搜索:中文名 contains,代码 前缀/包含,
    // 与 CNNAME 合并供联想框与自选搜索框两处命中;大小写不敏感由调用方uppercase保证)
    private val HKNAME = listOf(
        "00700" to "腾讯", "09988" to "阿里巴巴", "03690" to "美团", "01810" to "小米",
        "09618" to "京东", "09868" to "小鹏", "00005" to "汇丰", "00981" to "中芯国际",
        "02318" to "中国平安", "01024" to "快手", "09633" to "农夫山泉", "09961" to "携程",
        "02015" to "理想汽车", "01211" to "比亚迪"
    )
    // ⚠⚠⚠ 2026-10-10 **韩股名称表 4 条错名纠正**（用户当天拍板「修」）。
    //
    // 【为什么这张表错了不能拖】中国大陆出口下 `yahooSearch()` 本身也是 **403**
    //   （scout t3 §1.1/§3-A 实测：Yahoo 已停服大陆，返回的是停服页）
    //   ⟹ 联想框里韩股的**名字只有这张本地表一个来源**，表里写错，用户就一直看到错名字。
    //
    // 【核对方法：两个独立源交叉验证】（scout t3 文档 `tools/韩股取数方案-20261010.md`
    //   §6 与附录；我在 t7 里又**独立重跑了一遍**两条源，逐条对上才改）
    //   · 东财 `f14`：`push2.eastmoney.com/api/qt/ulist.np/get`，`fields=f12,f13,f14`，
    //     secid 前缀 `177.` 一次请求拿全（实测 HTTP 200）；
    //   · Naver `stockName`：`m.stock.naver.com/api/stock/{code}/basic`（实测 HTTP 200）。
    //
    // 【改的是哪 4 条 —— 每条的「错在哪」也一并记下，免得后人以为只是措辞不同】
    //   · `005850` 原写「LG电子」→ 实为 **SL Corp / 에스엘**。
    //       东财 f14=`SL Corp`、Naver stockName=`에스엘`。
    //       ⚠ **LG电子 是 `066570`**（东财 f14=`LG电子`、Naver=`LG전자`）—— 张冠李戴。
    //   · `000270` 原写「SK Telecom」→ 实为 **Kia Corp / 기아（起亚）**。
    //       东财 f14=`Kia Corp`、Naver stockName=`기아`。
    //       ⚠ **SK Telecom 是 `017670`**（东财 f14=`SK电讯`、Naver=`SK텔레콤`）。
    //   · `000720` 原写「现代」→ 实为 **现代建设 / 현대건설**。
    //       东财 f14=`现代建设`、Naver stockName=`현대건설`。
    //       ⚠ 原写法**歧义**：现代汽车 是 `005380`，**本表第 4 条已经有了**，
    //         所以 000720 只能是「现代建设（Hyundai E&C）」，不可能是现代汽车。
    //   · `035900` 原写「韩华」→ 实为 **JYP娱乐 / JYP Ent.**。
    //       东财 f14=`JYP娱乐`、Naver stockName=`JYP Ent.`。
    //       ⚠ **韩华（集团）是 `000880`**（东财 f14=`韩华集团`、Naver=`한화`）。
    //
    // 【为什么名字里带韩文】本表的匹配是 `k.contains(q) || v.contains(q)`（见 [marketLocal]），
    //   名字进 v ⟹ 用户打韩文也能命中。这正是「本地表是大陆唯一名字来源」的应有含义：
    //   名字该是**韩国本地真名**，不是二手转写。英文名同时保留，方便按 `KIA`/`JYP` 这类英文搜。
    //   ⚠ 顺带修掉一个连带问题：搜 `KIA` 以前靠 ENALIAS 命中，而那条别名指错了公司
    //     （见下方 ENALIAS 的 2026-10-10 注释）；现在 KRNAME 里 000270 的名字含「起亚」，
    //     搜 `起亚` 直接命中，搜 `KIA` 走已修正的别名，两条路都落到 000270。
    private val KRNAME = listOf(
        "005930" to "三星电子", "000660" to "SK海力士", "035420" to "NAVER",
        "005380" to "现代汽车",
        // ⚠ 2026-10-10 原「SK Telecom」→ Kia Corp / 기아（起亚）；SK Telecom = 017670
        "000270" to "Kia Corp / 기아（起亚）",
        "035720" to "Kakao",
        "051910" to "LG化学", "005490" to "POSCO",
        // ⚠ 2026-10-10 原「现代」→ 现代建设 / 현대건설；现代汽车 = 005380（见上）
        "000720" to "现代建设 / 현대건설",
        // ⚠ 2026-10-10 原「LG电子」→ SL Corp / 에스엘；LG电子 = 066570
        "005850" to "SL Corp / 에스엘",
        // ⚠ 2026-10-10 原「韩华」→ JYP娱乐 / JYP Ent.；韩华 = 000880
        "035900" to "JYP娱乐 / JYP Ent."
    )
    // 稿§5 英文别名→上述代码(+商品别名→既有 *00Y 码),全部大写,前缀+包含双向匹配
    private val ENALIAS = listOf(
        "BYD" to "01211", "TENCENT" to "00700", "SAMSUNG" to "005930", "HYNIX" to "000660",
        "NAVER" to "035420", "HYUNDAI" to "005380",
        // ⚠⚠ 2026-10-10 **`KIA` 的目标代码纠错：`000240` → `000270`**（用户当天拍板「修」）。
        //   【错在哪】`000240` 是 **한국앤컴퍼니 / Hankook Tire Worldwide**（轮胎/控股公司），
        //     不是起亚 —— 搜 `KIA` 会命中一家完全无关的公司。
        //     起亚的真实代码是 **`000270`**（Kia Corp / 기아）。
        //   【核对】与 KRNAME 同一套双源：东财 f14（`177.000270`=`Kia Corp`、
        //     `177.000240`=`Hankook Tire Worldwide`）+ Naver stockName
        //     （`000270`=`기아`、`000240`=`한국앤컴퍼니`）；scout t3 文档 §6 同族问题表。
        //   ⚠ 两处**必须同时**对：KRNAME 的 000270 名字、和这条别名的目标 ——
        //     只改一处就会出现「搜 KIA 出一家、搜 기아 出另一家」。
        "KIA" to "000270", "XIAOMI" to "01810",
        "MEITUAN" to "03690", "HSBC" to "00005", "SMIC" to "00981", "PINGAN" to "02318",
        "LGCHEM" to "051910", "COPPER" to "HG00Y", "CU" to "HG00Y",
        "BRENT" to "B00Y", "BZ" to "B00Y",
        // ⚠⚠ 2026-10-05 **CXMT 只能是别名，永远不能当代码**。
        //   用户原话：「我说 CXMT 时，是说希望 **CXMT 能够搜索到他**，
        //   而不是让他成为股票代码并且显示」。
        //   ⟹ CXMT 不进 [TOPS]（那是"能被搜出来的股票代码"表），只放这里。
        //   效果：搜 `CXMT` → 联想行显示 `sh688825 / 长鑫存储`，CXMT 本身不作为代码出现。
        //   注：这条走 [marketLocal]，联想框与自选搜索框**共用**，两处都搜得到。
        "CXMT" to "sh688825"
    )

    private fun marketName(s: String): String =
        HKNAME.firstOrNull { it.first == s }?.second
            ?: KRNAME.firstOrNull { it.first == s }?.second
            ?: ""

    // 港韩+英文别名的本地命中(联想框与自选搜索框共用;去重交给调用方)
    private fun marketLocal(q: String): List<SugItem> {
        val items = mutableListOf<SugItem>()
        for ((k, v) in HKNAME + KRNAME) {
            if (k.contains(q) || v.contains(q)) {
                if (items.none { it.s == k }) items.add(SugItem(k, v, k.startsWith(q)))
            }
        }
        for ((a, target) in ENALIAS) {
            if (a.contains(q) && items.none { it.s == target }) {
                items.add(SugItem(target, favName(target).ifEmpty { a }, a.startsWith(q)))
            }
        }
        return items
    }

    // 反查中文名(对标稿子favName):命中第一个值相等的键,无则查港韩表,再查 A 股运行时缓存,再无空串
    //
    // ⚠ 2026-10-05 A 股这一层是**新增**的：`sh688825` 本地表里查不到，
    //   早先返回空 ⟹ 自选行标题回落成原始代码，第二行也不再显示代码
    //   （`titleIsCode` 判据），于是那一行与港/美/币的排版规则对不上。
    //   名字由 [MktData.NameBook] 后台从腾讯行情查一次并落盘。
    fun favName(s: String): String =
        CNNAME.firstOrNull { it.second == s }?.first
            ?: marketName(s).ifEmpty { MktData.NameBook.of(s) }

    private val SPOT_QUOTES = setOf("USDT", "USD", "USDC")

    // 本地即时结果,顺序与稿子suggest()一致
    fun local(q: String): List<SugItem> {
        val items = mutableListOf<SugItem>()
        for ((k, v) in CNNAME) {
            if (k.contains(q)) items.add(SugItem(v, k, true))
        }
        for (f in TOPC) {
            if (f.startsWith(q)) items.add(SugItem(f, "币", true))
        }
        for ((s, tag) in TOPM) {
            if (s.startsWith(q) ||
                (q.length == 1 && "黄金白银".contains(q) && tag.contains(q))
            ) items.add(SugItem(s, tag, s.startsWith(q)))
        }
        // ⚠ 美债快捷入口（与TOPS同规则，前缀命中）；TOPB 已收敛为只有 TLT。
        for (s in TOPB) {
            if (s.startsWith(q)) items.add(SugItem(s, "美债", true))
        }
        for (s in TOPS) {
            // ⚠⚠ 2026-10-05 标签不再写死「美股」。TOPS 里混着 A 股（sh688825），
            //   写死 = 任何 A 股被搜出来都顶着「美股」徽标（2026-10-04 只给 Yahoo 兜底路径
            //   换成了 [typeTag]，本地表这条漏了 —— 实机搜 688825 得到 `sh688825 / 美股`）。
            if (s.startsWith(q)) items.add(SugItem(s, typeTag(s), true))
        }
        for ((k, v) in CNNAME) {
            if (v.startsWith(q) && items.none { it.s == v }) {
                items.add(SugItem(v, k, true))
            }
        }
        // 稿§5:港韩中英名+英文别名也进联想框
        for (it in marketLocal(q)) {
            if (items.none { x -> x.s == it.s }) items.add(it)
        }
        return sortTop(items)
    }

    fun sortTop(items: List<SugItem>): List<SugItem> =
        items.sortedWith(compareByDescending<SugItem> { it.pre }).take(6)

    // ---------- 异步源(后台线程调用) ----------

    @Volatile private var okCache: List<String>? = null
    @Volatile private var bbCache: List<String>? = null

    private fun okSymbols(): List<String> {
        okCache?.let { return it }
        val out = mutableListOf<String>()
        try {
            val data = JSONObject(MktData.get(
                "https://www.okx.com/api/v5/public/instruments?instType=SPOT"))
                .optJSONArray("data") ?: JSONArray()
            for (i in 0 until data.length()) {
                val id = data.optJSONObject(i)?.optString("instId", "") ?: ""
                val mm = Regex("^([A-Z0-9]+)-(USDT|USD|USDC)$").matchEntire(id)
                if (mm != null) out.add(mm.groupValues[1] + mm.groupValues[2])
            }
        } catch (_: Exception) {
        }
        okCache = out
        return out
    }

    private fun bbSymbols(): List<String> {
        bbCache?.let { return it }
        val out = mutableListOf<String>()
        try {
            val list = JSONObject(MktData.get(
                "https://api.bybit.com/v5/market/instruments-info?category=spot&limit=1000"))
                .optJSONObject("result")?.optJSONArray("list") ?: JSONArray()
            for (i in 0 until list.length()) {
                val s = list.optJSONObject(i)?.optString("symbol", "") ?: ""
                if (s.isNotEmpty()) out.add(s.uppercase(Locale.US))
            }
        } catch (_: Exception) {
        }
        bbCache = out
        return out
    }

    // Yahoo 缺名时的标签回退：**按品种类型给，不写死「美股」**。
// ⚠ 2026-10-04 实测：搜 `688825` 时联想行显示 `sh688825 / 美股` —— 名字来自 Yahoo，
    //   而 Yahoo 对这只票没给 shortname，于是落进写死的「美股」。
    //   标签写死 = 任何 Yahoo 不认识品种的都会被误标成美股（A 股首当其冲）。
    //   与 [FavPanel] 的 badge 词表保持一致：贵金/商品/港股/韩股/A股。
    private fun typeTag(sym: String): String = when (MktData.symType(sym)) {
        "hk" -> "港股"
        "kr" -> "韩股"
        "ashare" -> "A股"
        "gold", "silver" -> "贵金"
        "cmdty" -> "商品"
        "crypto" -> "币"
        else -> "美股"
    }

    private fun yahooSearch(q: String, quotesCount: Int = 6): List<SugItem> {
        val out = mutableListOf<SugItem>()
        try {
            val enc = URLEncoder.encode(q, "UTF-8")
            val j = JSONObject(MktData.get(
                "https://query2.finance.yahoo.com/v1/finance/search?q=$enc&quotesCount=$quotesCount&newsCount=0"))
            val quotes = j.optJSONArray("quotes") ?: JSONArray()
            for (i in 0 until quotes.length()) {
                val x = quotes.optJSONObject(i) ?: continue
                val sym = x.optString("symbol", "")
                if (sym.isEmpty()) continue
                // ⚠ 2026-10-07 演进（同日先「加外汇」、后由用户裁定「不要外汇了」）：
                //   ① CURRENCY **不再放行** —— 外汇品种从源头就不进结果，这是「不要外汇」的唯一 owner。
                //   ② INDEX 仍放行（恒生等指数要能搜），改用 isBond **精确**挡 US10Y/TNX 这类收益率指数：
                //      挡得准，又不会连带挡掉其他指数。
                //      而 TLT 是 ETF，isBond 不匹配它 —— 这正是「美债就只保留TLT」的实现。
                val qt = x.optString("quoteType", "")
                if (qt != "EQUITY" && qt != "ETF" && qt != "INDEX" && qt != "FUTURE" && qt != "CRYPTOCURRENCY") continue
                if (MktData.isBond(sym)) continue
                val sn = x.optString("shortname", "")
                out.add(SugItem(sym, if (sn.isEmpty()) typeTag(sym) else sn, sym.startsWith(q)))
            }
        } catch (_: Exception) {
        }
        return out
    }

    // BTC收敛终态(v3.3):BTC打头只出BTCUSDT,其余变体一律不收
    private fun converged(s: String): Boolean =
        !(s.startsWith("BTC") && s != "BTCUSDT")

    // 异步合并:OKX/Bybit现货表按base前缀过滤+Yahoo搜索,去重后排序截6条
    fun remote(q: String): List<SugItem> {
        val out = mutableListOf<SugItem>()
        if (q.isEmpty()) return out
        try {
            for (s in okSymbols()) {
                if (s.startsWith(q) && converged(s) &&
                    out.none { it.s == s } && out.size < 20
                ) {
                    out.add(SugItem(s, "OKX", true))
                }
            }
        } catch (_: Exception) {
        }
        try {
            for (s in bbSymbols()) {
                if (s.startsWith(q) && converged(s) &&
                    out.none { it.s == s } && out.size < 20
                ) {
                    out.add(SugItem(s, "Bybit", true))
                }
            }
        } catch (_: Exception) {
        }
        for (x in yahooSearch(q)) {
            if (!converged(x.s)) continue
            if (out.none { it.s == x.s }) out.add(x)
        }
        return out
    }

    // ---------- 自选搜索(对标稿子favSearch/paintFavResults) ----------
    // 本地即时:TOPC/TOPM/TOPS包含匹配+CNNAME中文包含/代码前缀,顺序与稿子一致
    fun favLocal(q: String): List<SugItem> {
        val items = mutableListOf<SugItem>()
        for (f in TOPC) {
            if (f.contains(q)) items.add(SugItem(f, "币", false))
        }
        for ((s, tag) in TOPM) {
            if (s.contains(q)) items.add(SugItem(s, tag, false))
        }
        // ⚠ 美债(TLT)同样进自选搜索框（与TOPS同规则，包含命中）。
        //   ⚠ 标签与 [local] 的 TOPB 循环**必须一致写"美债"**：改前这里用 typeTag(s)，
        //     而 TLT 是 ETF → symType 落 stock → 出"美股"，于是同一符号两个搜索框两个标签。
        //     搜「美债」还能命中(走 CNNAME 别名给"美债")，搜「TLT」却给"美股"，自相矛盾。
        for (s in TOPB) {
            if (s.contains(q)) items.add(SugItem(s, "美债", false))
        }
        for (s in TOPS) {
            // ⚠⚠ 2026-10-05 与 [local] 同一处修正：这里原来也写死「美股」。
            //   自选页那个搜索框走的正是 [favLocal]，所以只改 [local] 是不够的 ——
            //   实机搜 688825 仍得到 `sh688825 / 美股`，就是这一行干的。
            if (s.contains(q)) items.add(SugItem(s, typeTag(s), false))
        }
        for ((k, v) in CNNAME) {
            if (k.contains(q) && items.none { it.s == v }) {
                items.add(SugItem(v, k, false))
            }
        }
        for ((k, v) in CNNAME) {
            if (v.startsWith(q) && items.none { it.s == v }) {
                items.add(SugItem(v, k, false))
            }
        }
        // 稿§5:港韩中英名+英文别名也进自选搜索框
        for (it in marketLocal(q)) {
            if (items.none { x -> x.s == it.s }) items.add(it)
        }
        return items
    }

    // 全币种表(OKX+Bybit去重,懒缓存,tag统一"币",与稿子ensureCryptoEx一致)
    fun spotAll(): List<SugItem> {
        val out = mutableListOf<SugItem>()
        try {
            for (s in okSymbols()) {
                if (out.none { it.s == s }) out.add(SugItem(s, "币", false))
            }
        } catch (_: Exception) {
        }
        try {
            for (s in bbSymbols()) {
                if (out.none { it.s == s }) out.add(SugItem(s, "币", false))
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun yahooFav(q: String): List<SugItem> = yahooSearch(q, 8)

    // 精确>前缀>包含排序,已收藏剔除,最多12条(照稿子paintFavResults)
    fun rankFav(items: List<SugItem>, q: String, mine: Set<String>): List<SugItem> {
        fun rank(x: SugItem): Int = when {
            x.s == q -> 0
            x.s.startsWith(q) -> 1
            else -> 2
        }
        // 中文名命中的条目 s=代码、tag=中文名:tag参与过滤;
        // 英文别名命中按别名→目标代码直接放行(否则 s=00700 配 q=TENCENT 被丢)
        val aliasTarget = ENALIAS.firstOrNull {
            it.first == q || it.first.contains(q)
        }?.second
        return items.filter {
            (it.s.contains(q) || q.contains(it.s) || it.tag.contains(q) ||
                (aliasTarget != null && it.s == aliasTarget)) && it.s !in mine
        }
            .sortedWith(compareBy<SugItem> { rank(it) })
            .take(12)
    }
}
