plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    // 2026-10-21 闸 2（Paparazzi 截图回归）：版本钉 1.3.4 ——
    // Gradle 8.7 / Kotlin 1.9.24 / AGP 8.5.2 全部对得上；
    // 其 layoutlib = Iguana 2023.2.1 = **API 34** ⟹ app 的 compileSdk 必须 34。
    // 不选 1.3.5（要 Gradle 8.10.2，与 wrapper 不符）、不选 2.0.0-alpha（AGP/Kotlin 都要动）。
    id("app.cash.paparazzi") version "1.3.4" apply false
}

// 中文工程路径兼容：Gradle/JVM 的 @argfile 在 Windows 上会把中文构建路径
// 按系统编码写出、再按 UTF-8 读取，导致 Paparazzi 测试运行期找不到测试类。
// 将所有项目的构建产物集中到纯 ASCII 路径；gradlew.bat 默认把它放进工作区的 .gc-build-cache/gradle，
// GRIDCALC_BUILD_ROOT 可供 CI/本机覆盖；未通过 wrapper 启动时直接报错，避免偷偷回落到 D:\ 根目录。
val asciiBuildRoot = (
    System.getenv("GRIDCALC_BUILD_ROOT")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: error("GRIDCALC_BUILD_ROOT is unset; invoke gradlew.bat so it can choose the workspace ASCII short path")
).trimEnd('/', '\\')

allprojects {
    val leaf = if (this == rootProject) "root" else name
    layout.buildDirectory.set(file("$asciiBuildRoot/$leaf"))
    logger.lifecycle("[ascii-build] $path -> ${layout.buildDirectory.get()}")
}
