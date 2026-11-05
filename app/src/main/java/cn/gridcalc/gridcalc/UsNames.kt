package cn.gridcalc.gridcalc

import android.content.Context
import java.util.Locale

// ---------- 美股·内置离线目录（含中文名）----------
//
// ⚠ 2026-11-03 用户裁定：「中文名的问题，你提前汉化，存在程序里面就可以了」
//     fetchUsNames）随本轮数据源收敛整体删除，这里是唯一来源。
//
// 【资产】`res/raw/us_dir.tsv`（由 t7 从 **Nasdaq screener 单源**生成）：
//   · 目录来源 = Nasdaq 官方 screener 一次 download（**已含纽交所**：IBM/DELL/BAC/CVX/JPM/
//     NOW/GLW/XOM 全在；ETF（SPY/TLT/QQQ/VOO/DIA）天然不出现）⟹ 不需要第二个目录源；
//   · 只收**普通股**：资产侧已按结构字段筛掉 ETF / 测试代码 / 权证/权利/单位/票据/债券/优先股
//     —— **本文件不做运行时黑名单**，也**不维护任何手写代码名单**
//     （用户明确否掉"白名单那一套"：名单会烂、会缺、会和目录漂移）；
//   · 每行**三列**、TAB 分隔：`代码 \t 中文名 \t 英文名`，
//     例如 `STX\t希捷科技\tSeagate Technology Holdings PLC`。
//     ⚠ screener 不提供交易所 ⟹ 资产里**没有**交易所列（也没有任何代码依赖它）。
//     ⚠ 中文名可能为空（东财映射缺失）⟹ 显示名回落**英文名**，这是 captain 明确的口径。
object UsNames {

    /** 一条内置记录：代码 + 中文名（可能为空）+ 英文名。 */
    private data class Row(val code: String, val zh: String, val en: String) {
        /** 上屏/检索用的名字：中文名优先，缺了回落英文名。 */
        val name: String get() = if (zh.isNotEmpty()) zh else en
    }

    private var byCode: Map<String, Row> = emptyMap()
    private var rows: List<Row> = emptyList()

    /** 非美股（加密/大宗/金银/韩股）中文名：`res/raw/names_extra.tsv`，只有中文名。 */
    private var extraRows: List<Row> = emptyList()
    private var extraByCode: Map<String, String> = emptyMap()

    /** 资产是否已装好（诊断/单测用）。 */
    internal fun isLoaded(): Boolean = rows.isNotEmpty()

    /** 表内条目数（诊断用）。 */
    internal fun count(): Int = rows.size

    /** 非美股中文名表条目数（诊断用）。 */
    internal fun extraCount(): Int = extraRows.size

    /** 非美股名表是否已装好（诊断/单测用）。 */
    internal fun hasExtra(): Boolean = extraRows.isNotEmpty()

    /**
     * 进程启动时装一次（`MainActivity.onCreate` 里、两个 Panel 构造**之前**）。
     *
     * ⚠ 必须是同步装载：`MktSuggest.favName` 是同步函数，自选行**首帧**就要读它；
     *   改成后台装载，第一屏的中文名会全部回落成代码（那是可见的错，不是"稍后自愈"）。
     *   两份资产合计 ~420KB，`bufferedReader().lineSequence()` 一趟扫完，无 JSON 解析开销。
     * ⚠ 重复调用无副作用；读失败只留空表（搜索仍可用 Nasdaq 远端），**绝不因此崩进程**。
     * ⚠ 两份资产各自独立 try：非美股名表缺失不影响美股目录（反之亦然）。
     */
    fun attach(c: Context) {
        if (rows.isNotEmpty()) return
        try {
            c.resources.openRawResource(R.raw.us_dir).bufferedReader().use { br ->
                load(br.lineSequence())
            }
        } catch (_: Exception) {
            android.util.Log.w("UsNames", "内置美股目录装载失败")
        }
        try {
            c.resources.openRawResource(R.raw.names_extra).bufferedReader().use { br ->
                loadExtra(br.lineSequence())
            }
        } catch (_: Exception) {
            android.util.Log.w("UsNames", "非美股中文名表装载失败")
        }
    }

    /**
     * 解析并装载（[attach] 与单测共用；单测直接喂资产文本行，不需要 Android Context）。
     * 非法行（空行 / 无 TAB / 空代码）一律跳过，不抛异常。
     *
     * 【列规则】资产是**三列** `代码 \t 中文名 \t 英文名`：
     *   · 名字取**末尾两列**（倒数第二 = 中文名、倒数第一 = 英文名）⟹ 三列直接对上；
     *   · 只有两列时整段当名字（中文名），英文名留空；
     *   · 列数**少于两列**或名字全空的行直接丢弃；
     *   · ⚠ 不假设中间有交易所列（screener 不提供它），也不把任何中间列当名字。
     */
    internal fun load(lines: Sequence<String>) {
        val map = HashMap<String, Row>(1 shl 14)
        val list = ArrayList<Row>(1 shl 14)
        for (raw in lines) {
            val line = raw.trimEnd('\r')
            if (line.isEmpty()) continue
            val parts = line.split('\t')
            if (parts.size < 2) continue
            val code = parts[0].trim().uppercase(Locale.US)
            if (code.isEmpty()) continue
            val zh: String
            val en: String
            if (parts.size >= 3) {
                zh = parts[parts.size - 2].trim()
                en = parts[parts.size - 1].trim()
            } else {
                zh = parts[1].trim()
                en = ""
            }
            if (zh.isEmpty() && en.isEmpty()) continue
            if (map.put(code, Row(code, zh, en)) == null) list.add(Row(code, zh, en))
        }
        byCode = map
        rows = list
    }

    /**
     * 解析非美股中文名表（`res/raw/names_extra.tsv`，两列 `symbol \t zh`）。
     *
     * 这些品种**不在**美股目录里（加密/大宗/金银/韩股），所以单独一张表：
     *   · 只有中文名，没有英文名 ⟹ [Row.en] 恒空，`name` 就是中文名；
     *   · 两列格式；列数不足 2 或中文名为空的行直接丢弃（没有名字的行对显示/检索无意义）；
     *   · 与美股目录分开存：[search] 合并两者，[zhOf] 按「美股目录 → 本表」回落。
     */
    internal fun loadExtra(lines: Sequence<String>) {
        val list = ArrayList<Row>(64)
        val map = HashMap<String, String>(64)
        for (raw in lines) {
            val line = raw.trimEnd('\r')
            if (line.isEmpty()) continue
            val parts = line.split('\t')
            if (parts.size < 2) continue
            val code = parts[0].trim().uppercase(Locale.US)
            val zh = parts[1].trim()
            if (code.isEmpty() || zh.isEmpty()) continue
            if (map.put(code, zh) == null) list.add(Row(code, zh, ""))
        }
        extraRows = list
        extraByCode = map
    }

    /** 代码 → 中文名；没有中文名 → 空串（调用方回落显示代码或英文名，见各调用点）。 */
    fun cnOf(code: String): String {
        if (code.isEmpty()) return ""
        return byCode[code.trim().uppercase(Locale.US)]?.zh.orEmpty()
    }

    /**
     * 代码 → **最优中文名**：美股目录（us_dir.tsv）优先，其次非美股名表（names_extra.tsv）。
     *
     * ⚠ 这就是 `MktSuggest.favName` 的口径：自选行标题、行情页品种名都走这里；
     *   两张表都没有才返回空串，由调用方回落显示代码（不硬译、不联网取名）。
     */
    fun zhOf(code: String): String {
        if (code.isEmpty()) return ""
        val c = code.trim().uppercase(Locale.US)
        val us = byCode[c]?.zh
        if (!us.isNullOrEmpty()) return us
        return extraByCode[c].orEmpty()
    }

    /**
     * 该代码是否在**美股目录**（`us_dir.tsv`）内。
     *
     * ⚠ 与 [search] 的区别：search **两张表都扫**（美股目录 + 非美股中文名表），
     *   所以不能拿它当"美股普通股"的准入判据 —— 例如把 TLT 以「美债」写进 names_extra，
     *   search 能命中，但 TLT 并不是 us_dir 里的普通股。
     * ⟹ 目录准入（[MktSuggest.usDirHas]）只认这个函数：**只查 us_dir**。
     */
    internal fun hasUs(code: String): Boolean {
        if (code.isEmpty()) return false
        return byCode.containsKey(code.trim().uppercase(Locale.US))
    }

    /** 代码 → 英文名（仅诊断/检索用；上屏仍以中文名为准）。 */
    internal fun enOf(code: String): String {
        if (code.isEmpty()) return ""
        return byCode[code.trim().uppercase(Locale.US)]?.en.orEmpty()
    }

    /**
     * 本地检索：代码相等/前缀、中文名或英文名前缀/包含；**美股目录与非美股名表一起查**。
     *
     * @param query 已由调用方 trim + 大写（[MktSuggest] 的 queryOf）；这里再兜一次底。
     * @return 按「代码精确 > 代码前缀 > 名字前缀 > 代码包含 > 名字包含」稳定排序、至多 [limit] 条；
     *         同码只留一条（美股目录优先）；`pre = true` 只给**代码打头**的命中。
     *         `tag` = 名字（中文名优先，美股缺中文名时回落英文名）—— **绝不返回空 tag**。
     * ⚠ 纯本地：两张表都是随 APK 分发的 raw 资源，**不发起任何网络请求**。
     */
    fun search(query: String, limit: Int = 12): List<SugItem> {
        val q = query.trim().uppercase(Locale.US)
        if (q.isEmpty() || limit <= 0) return emptyList()
        if (rows.isEmpty() && extraRows.isEmpty()) return emptyList()
        val scored = ArrayList<Pair<Int, Row>>(16)
        fun rank(r: Row): Int = when {
            r.code == q -> 0
            r.code.startsWith(q) -> 1
            r.name.startsWith(q, ignoreCase = true) -> 2
            r.code.contains(q) -> 3
            r.name.contains(q, ignoreCase = true) -> 4
            else -> -1
        }
        for (r in rows) {
            val k = rank(r)
            if (k >= 0) scored.add(k to r)
        }
        for (r in extraRows) {
            val k = rank(r)
            if (k >= 0) scored.add(k to r)
        }
        if (scored.isEmpty()) return emptyList()
        val out = ArrayList<SugItem>(limit)
        val seen = HashSet<String>(limit * 2)
        for ((k, r) in scored.sortedBy { it.first }) {
            if (!seen.add(r.code)) continue
            out.add(SugItem(r.code, r.name, k <= 1))
            if (out.size >= limit) break
        }
        return out
    }
}
