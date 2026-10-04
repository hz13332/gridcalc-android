# 网格交易收益计算器 - 安卓原生版

原生 Kotlin + XML 实现,计算逻辑与旧 Kivy 版一致:等比网格,路径法爆仓价。

## 功能

- 计算页:7 个参数输入,输完即算;结果 Hero + 2×2 指标;网格明细(买卖徽章/累计净收益/斑马纹)
- 设置页:外观三档(跟随系统/浅色/深色,自动跟手机夜间模式)、手续费率、维持保证金率
- 底部 计算/设置 Tab,离线运行,不联网

## 拿 APK(不用自己装环境)

1. 打开仓库的 Actions 页面,等 Build APK 跑完(约 5-10 分钟)。
2. 在 Artifacts 里下载 GridCalc-apk,或直接去 Releases 按版本号下载(`v2.0` 这种)。
3. 传到手机上点安装即可。注意:每次 CI 打包签名都不同,覆盖安装会失败,先卸载旧版再装。

## 自己构建

```bash
# 需要 JDK 17 + Android SDK(platform-33, build-tools)
./gradlew assembleDebug
# 生成的包在 app/build/outputs/apk/debug/
```

## 版本规则

- 版本号唯一来源:`app/build.gradle.kts` 的 `versionName`,Release 名即 `v{versionName}`。
- 小版本(小数点后)自动递增,大版本(小数点前)由维护者指定。

## 默认参数说明

- 手续费率默认 0.05%,维持保证金率默认 100%。
- MMR=100% 时爆仓价恒等于触发价(加杠杆后保证金永远小于 100% 名义价值),符合全仓模式逻辑。
