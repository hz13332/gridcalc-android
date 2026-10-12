# tools/build-install-verify.ps1
#
# 「Android Studio 口径」构建 → 装机 → 取证 管线（2026-10-08 用户选定方案 B）
#
# 为什么叫 AS 口径：
#   Android Studio 点 ▶ Run 时，跑的就是工程自己的 Gradle wrapper（gradle-8.7）+
#   AS 的 Gradle JDK（.idea/gradle.xml 的 #GRADLE_LOCAL_JAVA_HOME）。AS 是 GUI 外壳，
#   Gradle 才是引擎。本脚本刻意复用**同一条** wrapper 与**同一个** JDK，所以产出与
#   AS 构建等价；差别只在于没人点按钮。
#   本机没有 GUI 自动化通道（pywinauto/uiautomation/pyautogui 均未安装），
#   所以 AS 的 Design 布局预览（layoutlib 渲染）无法由脚本产出 —— 那一项仍需人工在
#   AS 里切 Design 页签。验收证据由本脚本用「真机截图 + uiautomator 视图树」代替，
#   它与 AS 的 Layout Inspector 看到的是同一份视图层级。
#
# 用法：
#   .\tools\build-install-verify.ps1                          # debug 构建 + 装机 + 抓「设置/计算」
#   .\tools\build-install-verify.ps1 -Variant release          # 出 release 包
#   .\tools\build-install-verify.ps1 -Capture 自选,行情,计算,设置
#   .\tools\build-install-verify.ps1 -NoInstall                # 只构建
#   .\tools\build-install-verify.ps1 -SkipBuild                # 不构建，只装机+抓图
#
# 产物：
#   APK   app/build/outputs/apk/<variant>/app-<variant>.apk
#   证据  _verify_shots/<Tag>NN_<页面>.png / .xml
#
param(
    [ValidateSet('debug', 'release')][string]$Variant = 'debug',
    [switch]$SkipBuild,
    [switch]$NoInstall,
    [string[]]$Capture = @('设置', '计算'),
    [string]$Tag = ''
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$PAGE_TO_TAB = @{
    '自选' = 'tab_fav'
    '行情' = 'tab_mkt'
    '计算' = 'tab_calc'
    '设置' = 'tab_setup'
}
$PKG = 'cn.gridcalc.gridcalc'

function Say($m) { Write-Host "[as-pipeline] $m" }

# ---- 1. 解析 AS 的 Gradle JDK（跟着 .gradle/config.properties 走，用户改了也跟得上）----
$cfg = Join-Path $root '.gradle\config.properties'
if (Test-Path $cfg) {
    $line = Select-String -Path $cfg -Pattern '^java\.home=' | Select-Object -First 1
    if ($line) {
        $jh = ($line.Line -split '=', 2)[1].Replace('\:', ':').Replace('\\', '\')
        if (Test-Path (Join-Path $jh 'bin\java.exe')) {
            $env:JAVA_HOME = $jh
            Say "Gradle JDK = $jh  (来自 .gradle\config.properties，即 AS 的 #GRADLE_LOCAL_JAVA_HOME)"
        } else {
            Say "警告：config.properties 指向的 JDK 不存在（$jh），退回系统 JAVA_HOME"
        }
    }
} else {
    Say "警告：没有 .gradle\config.properties，退回系统 JAVA_HOME"
}

# ---- 2. 解析 SDK / adb（跟 local.properties 的 sdk.dir 走）----
$sdk = 'D:\Android\Sdk'
$lp = Join-Path $root 'local.properties'
if (Test-Path $lp) {
    $l = Select-String -Path $lp -Pattern '^sdk\.dir=' | Select-Object -First 1
    if ($l) { $sdk = ($l.Line -split '=', 2)[1].Replace('\\\\', '\').Trim() }
}
$adb = Join-Path $sdk 'platform-tools\adb.exe'
Say "SDK = $sdk"

# ---- 3. 构建（工程自己的 wrapper = AS 的引擎）----
if (-not $SkipBuild) {
    $task = ':app:assemble' + $Variant.Substring(0, 1).ToUpper() + $Variant.Substring(1)
    Say "构建：.\gradlew.bat $task --console=plain"
    & (Join-Path $root 'gradlew.bat') $task --console=plain
    if ($LASTEXITCODE -ne 0) { throw "构建失败（exit $LASTEXITCODE）" }
}

$apk = Join-Path $root "app\build\outputs\apk\$Variant\app-$Variant.apk"
if (-not (Test-Path $apk)) { throw "找不到 APK：$apk" }
$apkItem = Get-Item $apk
Say "APK = $($apkItem.FullName)"
Say "      $($apkItem.LastWriteTime)  $([math]::Round($apkItem.Length/1KB)) KB"

if ($NoInstall) { Say '跳过装机与取证（-NoInstall）'; exit 0 }

if (-not (Test-Path $adb)) { throw "找不到 adb：$adb" }

# ---- 4. 装机 + 启动 ----
Say '检查设备…'
$devices = & $adb devices | Select-String 'device$'
if (-not $devices) { throw '没有已连接的设备/模拟器（adb devices 为空）' }
Say "设备：$($devices -join ' / ')"

Say '安装…'
& $adb install -r $apk | ForEach-Object { Say "  $_" }
if ($LASTEXITCODE -ne 0) { throw "安装失败（exit $LASTEXITCODE）" }

Say '启动 MainActivity…'
& $adb shell am start -n "$PKG/.MainActivity" | Out-Null
Start-Sleep -Milliseconds 1500

# ---- 5. 取证：按 tab 逐个截图 + 视图树 ----
$shotDir = Join-Path $root '_verify_shots'
New-Item -ItemType Directory -Force -Path $shotDir | Out-Null

function Get-TabCenters {
    param([string]$Adb)
    $remote = '/sdcard/_ui_tabs.xml'
    & $Adb shell uiautomator dump $remote | Out-Null
    $tmp = Join-Path $env:TEMP '_ui_tabs.xml'
    & $Adb pull $remote $tmp | Out-Null
    $raw = [System.IO.File]::ReadAllText($tmp, [System.Text.Encoding]::UTF8)
    $map = @{}
    foreach ($m in [regex]::Matches($raw, 'resource-id="cn\.gridcalc\.gridcalc:id/(tab_\w+)_label"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')) {
        $cx = ([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2
        $cy = ([int]$m.Groups[3].Value + [int]$m.Groups[5].Value) / 2
        $map[$m.Groups[1].Value] = @([int]$cx, [int]$cy)
    }
    return $map
}

$tabs = Get-TabCenters -Adb $adb
if ($tabs.Count -eq 0) { throw '没能从视图树里找到底部 tab（tab_*_label）' }
Say "tab 坐标：$((($tabs.GetEnumerator() | Sort-Object Name | ForEach-Object { "$($_.Key)=$($_.Value -join ',')" }) -join '  '))"

$i = 0
$made = @()
foreach ($page in $Capture) {
    $i++
    if (-not $PAGE_TO_TAB.ContainsKey($page)) { Say "跳过未知页面「$page」"; continue }
    $tabId = $PAGE_TO_TAB[$page]
    if (-not $tabs.ContainsKey($tabId)) { Say "跳过「$page」：视图树里没有 $tabId"; continue }

    $xy = $tabs[$tabId]
    & $adb shell input tap $xy[0] $xy[1] | Out-Null
    Start-Sleep -Milliseconds 900

    $base = '{0}{1:d2}_{2}' -f $Tag, $i, $page
    $png = Join-Path $shotDir "$base.png"
    $xml = Join-Path $shotDir "$base.xml"

    & $adb shell screencap -p /sdcard/_shot.png | Out-Null
    & $adb pull /sdcard/_shot.png $png | Out-Null
    & $adb shell uiautomator dump /sdcard/_ui.xml | Out-Null
    & $adb pull /sdcard/_ui.xml $xml | Out-Null

    $made += [pscustomobject]@{
        页面   = $page
        截图   = $png
        视图树 = $xml
        大小   = (Get-Item $png).Length
    }
    Say "已抓「$page」→ $base.png / $base.xml"
}
& $adb shell rm -f /sdcard/_shot.png /sdcard/_ui.xml /sdcard/_ui_tabs.xml | Out-Null

Say ''
Say '===== 结果 ====='
$made | Format-Table -AutoSize

# 快速自检：视图树里还有没有页面级标题节点
$titleIds = 'set_title|calc_title|fav_title|mkt_title'
foreach ($r in $made) {
    $raw = [System.IO.File]::ReadAllText($r.视图树, [System.Text.Encoding]::UTF8)
    $hits = [regex]::Matches($raw, "resource-id=`"cn\.gridcalc\.gridcalc:id/($titleIds)`"") |
        ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique
    $verdict = if ($hits) { ($hits -join ',') } else { '(无页面级标题节点)' }
    Say ("{0,-6} {1}" -f $r.页面, $verdict)
}
