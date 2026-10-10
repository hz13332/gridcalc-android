plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * ⚠⚠⚠ 2026-10-03 **签名口令读取**（用户裁定：把密钥与口令移出仓库）。
 *
 * 【为什么定义在文件顶层】写在 `android { }` 块内会编译失败 ——
 *   那个作用域里 `java` 解析到 **Gradle 的 java 扩展**，不是 `java.util` 包，
 *   于是 `java.util.Properties()` 报 `Unresolved reference: util`（实测踩过）。
 *   顶层无此遮蔽。
 *
 * 【读取顺序】环境变量 `GC_<KEY>` → `local.properties` 的 `<key>`
 *   ⟹ 优先环境变量：CI 直接注入，本地与 CI 同一把钥匙，签名一致才能覆盖安装。
 *   ⟹ `local.properties` 已在 .gitignore 内，是本地开发的落点。
 *   ⟹ 两者都无 → 报错，**不静默回落到明文**（静默回落等于没改）。
 */
fun secret(key: String): String? {
    System.getenv("GC_" + key.replace(".", "_").uppercase())?.let { return it }
    val lp = rootProject.file("local.properties")
    if (!lp.exists()) return null
    // ⚠ 手写解析而不是 java.util.Properties —— Kotlin DSL 里 `java` 会被解析到
    //   Gradle 的 java 扩展，`java.util.Properties()` 编译期报 `Unresolved reference: util`
    //   （顶层与 android{} 内都试过，都不行）。手写只有三行，且不引入任何 import。
    for (raw in lp.readLines()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue
        val i = line.indexOf('=')
        if (i <= 0) continue
        if (line.substring(0, i).trim() != key) continue
        return line.substring(i + 1).trim().replace("\\:", ":").replace("\\=", "=")
    }
    return null
}

android {
    namespace = "cn.gridcalc.gridcalc"
    compileSdk = 33

    defaultConfig {
        applicationId = "cn.gridcalc.gridcalc"
        minSdk = 26
        targetSdk = 33
        // 版本号规则(用户定):主版本由用户定;第三位由队长按次递增;第二位逢十进一。
        // V4.1.1 = 东财单源修复 + 根数15下限提示 + 行情页落盘留底 + 自选三列排版 + 多源竞速反限流
        // V4.2   = 本轮：保存/恢复按品种打通（recLoad 复位 + 换品种清空 + 回填防时序崩溃）
        //          行情区换品种整屏清空（不再串上一品种的价格/支撑/VPVR）
        //          源状态行整块删除（paintLegRows 导致的 dispatchDraw 闪退）
        //          收益率/收益比恒绿（用户裁定）；竞速改替补；手续费吃单0.04%/挂单0% 拆分
        //          维持保证金率改按杠杆查表八档阶梯取值（用户提供的币安实测值）
        // CI 传入 -PciBuildNumber 后 versionCode = 30000+run，保证单调递增可覆盖安装
        // ⚠ 2026-10-05 本地默认值跟到 40500（对齐 versionName 4.5）。发版正式包走 CI，
        //   那里 versionCode = 30000+run，本地这个值只在手工 assembleRelease 时生效。
        // ⚠ 2026-10-06 手工包 versionCode 每次递增（40501/40502…），versionName 保持用户裁定的 4.5。
        //   之前几个手工包 versionCode 全是 40500，装没装上、装的是哪个根本分不清 ——
        //   好几次"没变化"其实是装了旧包。versionCode 用户不可见，不违反 4.5 裁定。
        val ciRun = (findProperty("ciBuildNumber") as String?)?.toIntOrNull()
        versionCode = if (ciRun != null) 30000 + ciRun else 40505
        // ⚠ **版本号唯一来源**：Release 名与 tag 都是 v{versionName}（见 .github/workflows/build-apk.yml）。
        //   已发布序列：4.1 → 4.2.1 → 4.3 → 4.3.1 / 4.3.2 / 4.3.3 → 4.4 ⟹ 本次发 **4.5**。
        //   2026-10-05 用户裁定升到 4.5（4.4 与已发布的包重号，同版本名下两个不同包难追溯）。
        //   V4.5   = 本轮：
        //          自选页**取消骨架屏**（fav_skel 恒隐藏、列表恒可见），冷启动首帧即出行（实测 +927ms）
        //          步长阈值**旧值先上、后台再刷**：当天取不到就退回最近一次成功的值，不再显示「无」
        //          自选搜索候选表弹出时**把输入焦点与软键盘请回来**（focusable PopupWindow 会抢焦点）
        //          换品种清屏补漏：步长阈值行 / 「品种·盘口·时间」两行小字（曾残留上一品种的数据）
        //          符号表纠正：迈威尔 MVIS→**MRVL**；太空探索 SPAC→**SPCX**（无 SPAX 这个股票）
        //          CXMT 降为**搜索别名**指向 sh688825，不再作为代码显示
        //          清空数据的提示改写到清空按钮自己身上；回填记录后不再显示「● 未保存」
        versionName = "4.5"
    }

    // ⚠⚠⚠ 2026-10-03 **签名口令移出源码，改走环境变量**（用户裁定，从仓库移除密钥）。
    //
    // 【为什么要改】改前是明文写死的 storePassword / keyPassword，
    // 而 `keystore/gridcalc.jks` 当时**也已被 git 跟踪**。
    // ⟹ 推公开仓库 = 任何人都能拿这把钥匙签 APK 冒充你更新用户设备。
    //   旧注释里那句「keystore 与口令随仓库公开，仅适用于自用分发」是**承认**这件事，
    //   而用户要的是「从仓库移除」。
    // ⚠ 口令值本身**不写在本文件任何位置（包括注释）** —— 注释同样会进仓库。
    //
    // 【读取顺序】环境变量 → local.properties（已被 .gitignore 忽略）
    //   ⟹ 优先环境变量：CI 里直接注入，本地/CI 同一把钥匙，签名一致才能覆盖安装。
    //   ⟹ 两者都没有时**明确报错**，不静默回落到明文 —— 静默回落等于没改。
    val gcStorePw: String =
        secret("gc.storePassword")
            ?: error(
                "\n⚠ 缺少签名口令。请设置环境变量 GC_STORE_PASSWORD / GC_KEY_PASSWORD / GC_KEY_ALIAS，" +
                    "\n  或在 local.properties 写 gc.storePassword=… / gc.keyPassword=… / gc.keyAlias=…"
            )
    val gcKeyPw: String =
        secret("gc.keyPassword") ?: error("\n⚠ 缺少 keyPassword。同 GC_STORE_PASSWORD 的两种提供方式。")
    val gcKeyAlias: String = secret("gc.keyAlias") ?: "gridcalc"

    // 固定签名:本地与 CI 共用同一把钥匙，同签名才能覆盖安装免卸载
    // ⚠ keystore 文件本身也应**不进仓库**（见 .gitignore）；本机/构建机各自持有同一把。
    // ⚠ 若旧密钥已进过 git 历史，仅靠 .gitignore 不足以消除 —— 需重写历史或换新密钥。
    signingConfigs {
        create("shared") {
            storeFile = file(
                System.getenv("GC_KEYSTORE_PATH") ?: "../keystore/gridcalc.jks"
            )
            storeType = System.getenv("GC_KEYSTORE_TYPE") ?: "jks"
        storePassword = gcStorePw
            keyAlias = gcKeyAlias
            keyPassword = gcKeyPw
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
}
