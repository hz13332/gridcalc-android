package cn.gridcalc.gridcalc

import java.io.File

/**
 * 单测用的资产装载器（**不是产品代码**）。
 *
 * 两份内置资产随 APK 分发，产品里由 `UsNames.attach(Context)` 读：
 *   · `res/raw/us_dir.tsv`      —— 美股目录（symbol \t zh \t en）
 *   · `res/raw/names_extra.tsv` —— 非美股中文名（symbol \t zh）
 * JVM 单测没有 Context ⟹ 这里直接从**磁盘上的同名资产**读同一份文本，
 * 走的是产品同一条解析/检索路径（[UsNames.load] / [UsNames.loadExtra] / [UsNames.search]）。
 *
 * 工作目录：Gradle 的 `testDebugUnitTest` 以**模块目录**（`app/`）为 cwd；
 * 另兼容从仓库根运行的情形。找不到资产就当测试失败（**不静默跳过**）。
 */
internal object TestAssets {

    private val roots = listOf(
        "src/main/res/raw",
        "app/src/main/res/raw",
        "../app/src/main/res/raw",
        "gridcalc-native/app/src/main/res/raw"
    )

    fun assetFile(name: String): File? =
        roots.map { File(it, name) }.firstOrNull { it.isFile }

    /** 重装美股目录（[name] 默认 us_dir.tsv）；返回是否装好。**每次调用都重装**（见下）。 */
    fun loadUsDir(name: String = "us_dir.tsv"): Boolean {
        val f = assetFile(name) ?: return false
        // ⚠ 每次都重装：UsNames 是单例，某些用例会往里灌合成样例（列格式回归），
        //   不重装就会把"样例表"漏给下一个测试类。重装 ~5.8k 行 ~几十毫秒，值得。
        UsNames.load(f.readText().lineSequence())
        return UsNames.isLoaded()
    }

    /** 装载非美股中文名表；返回是否装好。同样每次重装。 */
    fun loadNamesExtra(): Boolean {
        val f = assetFile("names_extra.tsv") ?: return false
        UsNames.loadExtra(f.readText().lineSequence())
        return UsNames.hasExtra()
    }

    /** 两份一起装（大多数用例需要）。 */
    fun loadAll(): Boolean = loadUsDir() && loadNamesExtra()
}
