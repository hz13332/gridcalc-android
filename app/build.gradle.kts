plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // 闸 2（Paparazzi 截图回归）—— 版本说明见根 build.gradle.kts
    id("app.cash.paparazzi")
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
    // 2026-11-05 修：调用点传的 key 自带 `gc.` 前缀（"gc.storePassword"），直接拼会得到
    //   GC_GC_STOREPASSWORD —— 永远对不上；本机有 local.properties 掩盖了这一点，
    //   CI 里没有该文件就变成构建期「缺少签名口令」。先剥前缀再拼。
    val envName = "GC_" + key.removePrefix("gc.").replace(".", "_").uppercase()
    System.getenv(envName)?.let { return it }
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
    // 2026-10-21：33 → 34 —— Paparazzi 1.3.4 的 layoutlib 是 API 34（compileSdk 33 会被拦）
    compileSdk = 34

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
        // V4.5-40506（2026-10-13 手工包）：
        //          VPVR 支撑链「死路续扫」：收口峰下无腰斩时不再终止整条链，续扫更深的峰
        //            （AMD 月线截图案例 [13]→[13,0]，MU/NFLX/NVDA/TSLA 实测逐位不变）
        //          韩股腿主次对调 [Naver, 东财]（用户裁定「韩股就用 Naver」；连发 20/20 零限流）
        // V4.5-40507（2026-10-13 手工包）：
        //          名字四连修：CNNAME 删安谋/ARM控股（ARM 无中文名，产品裁定）、
        //            补 XOM=埃克森美孚 / SPCX=太空探索技术公司、「阿里」→「阿里巴巴」；
        //            行情标题改"名字 代码"同屏（修「XOM 英文名顶掉代码」）
        // V4.5-40508（2026-10-13 手工包）：TOPS↔CNNAME 全表扫描补漏——
        //          补 META=Meta平台 / GE=通用电气；TOPS 补 17 个有中文名却搜不到的代码
        //          （MU/INTC/QCOM/AVGO/ORCL/JPM/PFE/KO/MCD/DIS/PYPL/CSCO/PDD/JD/LI/NIO/XPEV）
        // V4.5-40509（2026-10-13 手工包）：删 CNNAME「国际商业机器」键（用户裁定「就叫 IBM」）；
        //          自选行徽标旁代码**恒显**；长名缩字号（≥8字×0.6）；
        //          SPCX 显示名改「SpaceX」；AMD 键纠错「超微」→「超威半导体」；
        //          自选行点字进行情页修复（tooltip 抢触摸）；屏底计数行加「· 共 N 个品种」
        // V4.5-40510（2026-10-13 手工包）：自选行点击修复二弹——stripClickable 补剥
        //          isLongClickable（tooltip 把收益率/收益比格子变长可点、吞触摸，40509 只剥了 clickable）
        // V4.5-40511（2026-10-13 手工包）：韩股手动共识目标价（SK海力士/三星电子，Naver 快照，
        //          入库折美元；汇率取不到则不显示该行）
        // V4.5-40512（2026-10-13 手工包）：韩股目标价汇率过期时旧汇率照折+行尾注明日期（不藏行）
        // V4.5-40513（2026-10-13 手工包）：美股腿换 [东财,腾讯]（东财 fqt=1 全量分红前复权，
        //          与市面深复权图同口径，实测 TLT 对上 72.52/129.96）；时间轴跨年强制显示年月
        // V4.6-40515（2026-10-13 手工包）：LRCX 显示名调序为「泛林集团」（用户更正；三键全保留）
        // V4.6-40516（2026-10-13 手工包）：删 CNNAME「拉姆研究」键；「Meta平台」→「Meta」
        // V4.6-40517（2026-10-13 手工包）：自选行点击修复三弹——40510 剥离锚误用 fname.parent
        //          （只是品种列），距支撑/收益率/收益比三列从未被剥；改剥 root 第一个子块(content)整块
        // V4.6-40518（2026-10-13 手工包）：自选行点击修复四弹——拔掉收益率/收益比格子的
        //          tooltip 触发源（三版剥离均无效，悬浮说明让位整行点击；信息计算页同源可见）
        val ciRun = (findProperty("ciBuildNumber") as String?)?.toIntOrNull()
        // V4.6-41000（2026-10-15 手工包）：**跳号到 41000** —— 用户手机上已装的包 versionCode
        //   高于 40518（405xx 手工包序列在别处发过更高的号），Android 按 versionCode 判降级、
        //   拒绝覆盖安装（用户实报「版号怎么还降低了，怎么安装」）。
        //   versionName 仍是用户裁定的 4.6，**没降**；versionCode 用户不可见，只管覆盖安装。
        //   ⚠ 跳到 41000 而不是 40519：40519 仍可能盖不过手机上已有的号，一次跳够，
        //     保证盖住任何 405xx/406xx-409xx 手工包。CI 的 30000+run 要 run>11000 才追平（远期再收）。
        versionCode = if (ciRun != null) 30000 + ciRun else 50500
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
        // V4.6（2026-10-13 用户裁定升版：累计 13 个手工包按「第二位逢十进一」升档）：
        //          VPVR 支撑链「死路续扫」；韩股主腿 Naver（东财兜底）；美股腿 [东财,腾讯]
        //            （fqt=1 全量分红前复权，与市面深复权图同口径）；时间轴跨年显示年月；
        //            名字标准（IBM/SpaceX/超威半导体/埃克森美孚/阿里巴巴全名、删安谋、
        //            英文法定名不作标题、徽标代码恒显、长名缩字号）；自选行全行可点
        //            （tooltip 抢触摸两弹修复）；屏底计数「共 N 个品种」；韩股手动共识目标价
        //            （SK海力士/三星，折美元，断供旧汇率+注明）；关于页移除 versionCode 括号
        // V5.0（2026-10-27 用户裁定「版号改为 v5」）：
        //          ① 清空数据**真清零**：输入/结果/回填闸/联动缓存/自动网格态全套撤掉，
        //             没有保存记录时按钮也生效；清空后从收藏重进同一品种会重新回填并自动计算
        //             （4 轮评审修出来的异步时序：旧回调按批次代号拒、新批次放行）
        //          ② 第二支撑：腰斩命中的柱之后**走到量能谷底那一根**才是支撑柱（用户裁定）
        //          ③ 自动网格数量由 floor 改 **ceil**（末格不超过最低价，不再"少一格"）
        //          ④ 天然气代码统一：NG=F/NG → **NG00Y**，贯穿 symType / secid / 内存缓存 / 落盘 / 步长
        //          ⑤ 美国代码扩容：补 BAC（美国银行）等，目录外仍靠 Yahoo（大陆常 403）
        //          ⑥ 自选行**去掉「旧 N 分钟前」**；行情页价格卡右上改「源名 · 多久之前」，每 30s 自刷
        //          ⑦ 行情页去掉「收盘时点」灰行、去掉「OHLC 明细」整卡
        //          ⑧ 支撑块排版向「指标」卡对齐（容器内缩 / 标题 12sp / 行距 / 第二支撑改灰）
        //          ⑨ **前台每 5 分钟拉取一次**行情；进后台立即停表（无 WorkManager/无后台服务）
        // V5.1（2026-11-02 用户裁定升到 5.1）：数据链路与支撑判定的一轮收口
        //          ① 支撑：腰斩后沿量能递减段走到最低档（小幅抖动不算打破），第二支撑的峰
        //             改为「第一段爬升的第一个局部高点」，不再漂到底部量墙（SK海力士 318→574）
        //          ② 取数收成**唯一入口** `MktData.acquire`（取数/折算/缓存/留底一处完成），
        //             自选页只读不算取；行情页每拿到一批数据自动通知自选重算那一行
        //          ③ 前台刷新与页面无关：每 5 分钟补齐整张自选表 + 当前行情，冷启动即打一拍
        //          ④ 源名行修「有数据却写未取到」：缓存/留底命中也登记来源（含真实时刻）
        //          ⑤ 美股目录改用交易所官方（nasdaq/nyse/amex 分所合并），中文名参考东财名称表
        //          ⑥ 前台定时刷新真正绕过缓存取新批次；失败保留旧源时间，不再把缓存重算冒充新抓取
        //          ⑦ 爆仓价下方的“爆仓线”副行显示第二支撑/目标上限；主爆仓价仍按模型计算，越线标红
        // V5.5（2026-11-05 用户裁定「把版号改为 5.5」）：行情与计算的一轮口径收口
        //          ① 美股报价改「最近交易日即用」：修 mmddOf 日号未补零导致每月 1–9 号整份
        //             /info 报价被丢弃（价与涨跌退回 K 线末根，实测 MRVL 日涨跌写成 -16.58%）；
        //             价/涨跌判据由 realtime 改 dayOk，源名行对股票改报价时刻 / 「MM/dd 收盘」
        //          ② 行情页视觉：删重复页标题、数量步进器、K 线图 226→282dp、图表边距 4dp、
        //             K 线区右界 338→358、Y 轴改对数、VPVR 档位按价格定位、行标签 14sp、节标题 16sp
        //          ③ 支撑价改取 VPVR 档位**下沿**，并新增 Profile.supIdx 让高亮档与支撑价同源
        //          ④ 计算页：保存/清空结果显示为按钮内「已保存」/「已删除」（1.6s 弹回）、
        //             按钮加按下/抬起缩放动效、去掉方角阴影
        //          ⑤ 爆仓价数字与红框统一按屏上「爆仓线」判定：未超过 = 绿字无框，超过 = 红字红框
        versionName = "5.5"
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
    // 2026-10-21 闸 2（Paparazzi）：单测跑在 JVM 上，不用真机 / 不用模拟器
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // 闸 2（Paparazzi）：截图回归跑在 JVM 单测上；Paparazzi 运行时由插件自动带入
    testImplementation("junit:junit:4.13.2")
}
