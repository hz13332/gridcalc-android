# tools/fav_suggest_verify.ps1
#
# Independent on-device verification of the 2026-10-14 candidate-list revamp
# (the "search + add" suggestion list) on emulator-5554.
# Source of truth: the design folder's "port comparison table" for this revamp
# (design/<revamp-folder>/port-comparison-table.md), sections 5 / 7 / 9.
# Path kept ASCII-only on purpose, see below.
#
# WHY THIS FILE IS PURE ASCII:
#   The host shell is Windows PowerShell 5.1, which decodes a BOM-less .ps1 as ANSI.
#   Any literal CJK character in this file would be mojibake and can even break parsing.
#   Every Chinese string needed for assertions is therefore built at run time from
#   Unicode code points (see $CP and T below).
#
# What it does (repeatable: resets app data first, unless -NoReset):
#   reset app -> fav tab -> drive fav_search -> screenshot + uiautomator dump per state
#   S0  empty state (no query): no candidate container at all
#   S1  query "pl"        : candidate rows, their order (prefix matches before contains),
#                           second column, "+" tails, row/tail geometry (45.8dp / 30.5dp)
#   S2  tap "+" on PL00Y  : row turns into the "added" chip, list stays open, the input
#                           is NOT cleared, the fav list gains one new PL00Y row,
#                           and the added row/chip keep the same 45.8dp / 30.5dp geometry
#   S3  restart + "pl"    : a code already in the fav list is not offered again,
#                           no dead row, no silent no-op
#   S4  query "plz"       : two-line honest empty state, no "add directly" dead row
#   S5  press BACK while the candidate layer is open: the layer must be dismissed
#                           and the app must stay alive (user-reported 2026-10-14:
#                           back was swallowed by the soft keyboard, then the second
#                           back finished the activity and dropped the app to the
#                           home screen instead of hiding the layer)
#   R   regression        : fav rows keep no-card + full-width 1dp hairline (incl. last
#                           row); fav_search keeps transparent fill + 1dp stroke + round
#                           corner; all four bottom tabs still open
#   B   boundary (soft)   : "a" respects the 248dp cap
#   S6  layer motion       : opening/closing the candidate layer is animated (the fav
#                            list slides instead of flashing), and the duration follows
#                            the system animator scale (0 = instant)#
# Usage:
#   .\tools\fav_suggest_verify.ps1 -Tag v1_
#   .\tools\fav_suggest_verify.ps1 -Tag v1_ -NoReset
#
# Output: _verify_shots\<Tag><state>.png / .xml  + a PASS/FAIL table on stdout.
# Exit code 0 = every hard check passed, 1 = at least one hard check failed.

param(
    [string]$Tag = 'v1_',
    [switch]$NoReset
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$PKG  = 'cn.gridcalc.gridcalc'
$SHOT = Join-Path $root '_verify_shots'
New-Item -ItemType Directory -Force -Path $SHOT | Out-Null

# ---------------------------------------------------------------- code points
# key            meaning
# PLATINUM       2nd column of the PL00Y row (platinum, Chinese)
# US             US-market tag, 2nd column of AAPL / PYPL
# PLUS           fullwidth plus sign, the add button label
# ADDED          "added to fav list" chip label
# INLIST         "already in fav list" chip label
# NOMATCH        no-match empty-state title
# HINT           no-match empty-state hint line
# ADDDIRECT      "add directly" - forbidden dead row label
# COMMODITY      instrument-class badge on the fav row
# DASH           em dash placeholder cell
# PENDING        in-flight placeholder cell ("fetching")
$CP = @{
    PLATINUM  = @(0x94C2, 0x91D1)
    US        = @(0x7F8E, 0x80A1)
    PLUS      = @(0xFF0B)
    ADDED     = @(0x5DF2, 0x52A0, 0x5165, 0x81EA, 0x9009)
    INLIST    = @(0x5DF2, 0x5728, 0x81EA, 0x9009)
    NOMATCH   = @(0x65E0, 0x5339, 0x914D)
    HINT      = @(0x68C0, 0x67E5, 0x4EE3, 0x7801, 0x6216, 0x4E2D, 0x6587, 0x540D, 0xFF0C, 0x6216, 0x8BD5, 0x8BD5,
                  0x0020, 0x0050, 0x004C, 0x0030, 0x0030, 0x0059, 0x0020, 0x002F, 0x0020,
                  0x0041, 0x0041, 0x0050, 0x004C, 0x0020, 0x002F, 0x0020, 0x0050, 0x0059, 0x0050, 0x004C)
    ADDDIRECT = @(0x76F4, 0x63A5, 0x6DFB, 0x52A0)
    ADDDESC   = @(0x6DFB, 0x52A0, 0x5230, 0x81EA, 0x9009, 0xFF1A)   # "add to fav list:" prefix of contentDescription
    COMMODITY = @(0x5546, 0x54C1)
    DASH      = @(0x2014)
    PENDING   = @(0x5F85, 0x53D6, 0x6570)
}
function T([string]$k) { return (-join ($CP[$k] | ForEach-Object { [char]$_ })) }

# ---------------------------------------------------------------- adb / sdk
$sdk = 'D:\Android\Sdk'
$lp = Join-Path $root 'local.properties'
if (Test-Path $lp) {
    $l = Select-String -Path $lp -Pattern '^sdk\.dir=' | Select-Object -First 1
    if ($l) { $sdk = ($l.Line -split '=', 2)[1].Replace('\\\\', '\').Trim() }
}
$adb = Join-Path $sdk 'platform-tools\adb.exe'
if (-not (Test-Path $adb)) { throw "adb not found: $adb" }

$devLine = (& $adb devices) -join "`n"
if ($devLine -notmatch 'emulator-5554\s+device') { throw "emulator-5554 is not online. adb devices =>`n$devLine" }
Write-Host '[verify] device emulator-5554 online'

# ---------------------------------------------------------------- check ledger
$script:Checks = @()
function Check([string]$id, [string]$desc, [bool]$ok, [string]$detail) {
    $script:Checks += New-Object psobject -Property @{ Id = $id; Ok = $ok; Desc = $desc; Detail = $detail }
    $tag = if ($ok) { 'PASS' } else { 'FAIL' }
    Write-Host ("[{0}] {1}  {2}" -f $tag, $id, $desc)
    if ($detail) { Write-Host ("        {0}" -f $detail) }
}
function Note([string]$msg) { Write-Host ('[info] ' + $msg) }

# ---------------------------------------------------------------- ui helpers
function Get-Xml([string]$name) {
    # screenshot + uiautomator dump into _verify_shots, returns the raw XML text
    $png = Join-Path $SHOT "$name.png"
    $xml = Join-Path $SHOT "$name.xml"
    & $adb shell screencap -p /sdcard/_v.png | Out-Null
    & $adb pull /sdcard/_v.png $png | Out-Null
    $raw = ''
    for ($i = 0; $i -lt 5; $i++) {
        & $adb shell uiautomator dump /sdcard/_v.xml | Out-Null
        & $adb pull /sdcard/_v.xml $xml | Out-Null
        $raw = [System.IO.File]::ReadAllText($xml, [System.Text.Encoding]::UTF8)
        if ($raw -match '<hierarchy') { break }
        Start-Sleep -Milliseconds 500
    }
    if ($raw -notmatch '<hierarchy') { throw "uiautomator dump failed for $name" }
    return $raw
}
function Dump-Only([string]$name) {
    $xml = Join-Path $SHOT "$name.xml"
    for ($i = 0; $i -lt 5; $i++) {
        & $adb shell uiautomator dump /sdcard/_v.xml | Out-Null
        & $adb pull /sdcard/_v.xml $xml | Out-Null
        $raw = [System.IO.File]::ReadAllText($xml, [System.Text.Encoding]::UTF8)
        if ($raw -match '<hierarchy') { return $raw }
        Start-Sleep -Milliseconds 400
    }
    throw "uiautomator dump failed for $name"
}
function Parse-Xml([string]$raw) { return ([xml]$raw).hierarchy }
function Elem($n) { return @($n.ChildNodes | Where-Object { $_.NodeType -eq 'Element' }) }
function Desc($n) {
    $o = @($n)
    foreach ($c in (Elem $n)) { $o += (Desc $c) }
    return $o
}
function Attr($n, [string]$name) {
    # GetAttribute() returns '' for a missing attribute - do not rely on the
    # PowerShell XML adapter's boolean conversion of an XmlAttribute node.
    if ($n -is [System.Xml.XmlElement]) { return $n.GetAttribute($name) }
    return ''
}
function Txt($n) { return (Attr $n 'text') }
function Rid($n) { return (Attr $n 'resource-id') }
function NodeCls($n) { return (Attr $n 'class') }   # NOT "Cls": that is the built-in alias of Clear-Host
function Bnd($n) {
    $b = Attr $n 'bounds'
    if ($b -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { return $null }
    return @([int]$Matches[1], [int]$Matches[2], [int]$Matches[3], [int]$Matches[4])
}
function Mid($n) {
    $b = Bnd $n
    if (-not $b) { return $null }
    return @([int](($b[0] + $b[2]) / 2), [int](($b[1] + $b[3]) / 2))
}
function Tap($x, $y) { & $adb shell input tap $x $y | Out-Null; Start-Sleep -Milliseconds 250 }
function TapNode($n) { $m = Mid $n; if (-not $m) { throw 'node has no bounds' }; Tap $m[0] $m[1] }
function Find-Id($doc, [string]$shortId) {
    $full = "$PKG" + ':id/' + $shortId
    return @(Desc $doc | Where-Object { (Rid $_) -eq $full })
}
function Hide-Ime {
    # press BACK only when the soft keyboard is actually up; otherwise BACK would
    # leave the activity and destroy the state under test.
    $s = (& $adb shell dumpsys input_method | Select-String 'mInputShown=' | Select-Object -First 1).Line
    if ($s -match 'mInputShown=true') { & $adb shell input keyevent 4 | Out-Null; Start-Sleep -Milliseconds 500 }
}
function Clear-Field($doc) {
    $f = @(Find-Id $doc 'fav_search')
    if ($f.Count -eq 0) { throw 'fav_search not found' }
    TapNode $f[0]
    & $adb shell input keyevent 123 | Out-Null       # MOVE_END
    for ($i = 0; $i -lt 8; $i++) { & $adb shell input keyevent 67 | Out-Null }   # DEL x8
    Start-Sleep -Milliseconds 400
}
function Get-TabCenters($doc) {
    $map = @{}
    foreach ($m in [regex]::Matches($doc.OuterXml, 'resource-id="cn\.gridcalc\.gridcalc:id/(tab_\w+)_label"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')) {
        $map[$m.Groups[1].Value] = @([int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2),
                                     [int](([int]$m.Groups[3].Value + [int]$m.Groups[5].Value) / 2))
    }
    return $map
}

# candidate rows inside fav_sug_box: row root = LinearLayout whose 2nd child is a
# "+" Button or a state chip; 1st child is the pick container holding code + tag.
function Get-SugRows($doc) {
    $box = @(Find-Id $doc 'fav_sug_box')
    if ($box.Count -eq 0) { return @() }
    $tails = @((T 'PLUS'), (T 'ADDED'), (T 'INLIST'))
    $rows = @()
    foreach ($n in (Desc $box[0])) {
        if ((NodeCls $n) -ne 'android.widget.LinearLayout') { continue }
        $ch = Elem $n
        if ($ch.Count -ne 2) { continue }
        $tailTxt = (Txt $ch[1]).Trim()
        if ($tails -notcontains $tailTxt) { continue }
        $tvs = @(Desc $ch[0] | Where-Object { (NodeCls $_) -eq 'android.widget.TextView' -and (Txt $_).Trim().Length -gt 0 })
        $code = ''
        $tagT = ''
        if ($tvs.Count -ge 1) { $code = (Txt $tvs[0]).Trim() }
        if ($tvs.Count -ge 2) { $tagT = (Txt $tvs[1]).Trim() }
        $rows += New-Object psobject -Property @{ Code = $code; Tag = $tagT; Tail = $tailTxt; Node = $n; TailNode = $ch[1] }
    }
    return $rows
}
function Get-SugTexts($doc) {
    $box = @(Find-Id $doc 'fav_sug_box')
    if ($box.Count -eq 0) { return @() }
    $tvs = @(Desc $box[0] | Where-Object { (NodeCls $_) -eq 'android.widget.TextView' -and (Txt $_).Trim().Length -gt 0 })
    return @($tvs | ForEach-Object { (Txt $_).Trim() })
}
function Get-FavRows($doc) {
    $lst = @(Find-Id $doc 'fav_list')
    if ($lst.Count -eq 0) { return @() }
    # leading comma: keep the result an array even when fav_list has exactly one child
    return , @(Elem $lst[0])
}
function Digits([string]$s) { return @([regex]::Matches($s, '\d+') | ForEach-Object { $_.Value }) }
function Join-Prop($rows, [string]$prop) { return (@($rows | ForEach-Object { $_.$prop }) -join ',') }

# ---------------------------------------------------------------- pixel helpers
Add-Type -AssemblyName System.Drawing | Out-Null
function Sample-Png([string]$path, $pts) {
    $bmp = [System.Drawing.Bitmap]::FromFile($path)
    $out = @()
    foreach ($p in $pts) {
        $c = $bmp.GetPixel([int]$p[0], [int]$p[1])
        $out += , @($c.R, $c.G, $c.B)
    }
    $bmp.Dispose()
    # leading comma: a single point must still come back as an array of one [r,g,b]
    return , $out
}
function Scan-Column([string]$path, [int]$x, [int]$y0, [int]$y1) {
    $bmp = [System.Drawing.Bitmap]::FromFile($path)
    $out = @()
    for ($y = $y0; $y -le $y1; $y++) {
        $c = $bmp.GetPixel($x, $y)
        $out += , @($y, $c.R, $c.G, $c.B)
    }
    $bmp.Dispose()
    return $out
}
function ColEq($a, $b, [int]$tol) {
    return ([math]::Abs([int]$a[0] - [int]$b[0]) -le $tol) -and ([math]::Abs([int]$a[1] - [int]$b[1]) -le $tol) -and ([math]::Abs([int]$a[2] - [int]$b[2]) -le $tol)
}
function ColStr($a) { return ("{0},{1},{2}" -f $a[0], $a[1], $a[2]) }

# NOTE on why this uses PNG hashes instead of pixel math:
#   The FIRST implementation sampled pixels with System.Drawing and passed array elements
#   as command arguments (Frame-Diff $a[$i-1] $a[$i]). That chain broke three different
#   ways under Windows PowerShell 5.1 (empty-string binds to Test-Path -LiteralPath;
#   ([string]$x) casts silently becoming empty; array indexing not surviving the call).
#   Get-FileHash has none of those hazards: one path in, one hash out, plain string compare.
#   A hard jump changes exactly ONE consecutive pair; an animation changes SEVERAL.
# NOTE 2: the capture+count loop lives INSIDE this function on purpose. Running the same
#   array loop in the script's main scope yielded Count=1 despite seven successful pulls
#   (and a null $fEnd7), while the identical loop inside a function works correctly.
#   Keep it here; do not "simplify" it back into the main scope.
function Capture-MotionFrames([string]$prefix, [int]$count, [int]$delayMs) {
    $paths = @()
    for ($j = 0; $j -lt $count; $j++) {
        $p = Join-Path $SHOT ($prefix + $j + '.png')
        & $adb shell screencap -p /sdcard/_v.png | Out-Null
        & $adb pull /sdcard/_v.png $p | Out-Null
        $paths += $p
        Start-Sleep -Milliseconds $delayMs
    }
    $prev = ''
    $moved = 0
    foreach ($q in $paths) {
        if (-not (Test-Path -LiteralPath $q)) { $prev = ''; continue }
        $h = (Get-FileHash -LiteralPath $q -Algorithm MD5).Hash
        if ($prev -ne '' -and $h -ne $prev) { $moved++ }
        $prev = $h
    }
    return $moved
}

$BORDER = @(214, 217, 223)   # colorBorder, light theme

# ================================================================ 0. reset app
if (-not $NoReset) {
    Write-Host '[verify] pm clear (reset app data for a deterministic run)'
    & $adb shell pm clear $PKG | Out-Null
}
& $adb shell am start -n "$PKG/.MainActivity" | Out-Null
Start-Sleep -Seconds 3

$apkLine = ((& $adb shell dumpsys package $PKG) | Select-String 'versionName|lastUpdateTime' | Select-Object -First 3) -join ' | '
Note "installed package: $apkLine"

$doc0 = Parse-Xml (Get-Xml ($Tag + 'boot'))
$tabs = Get-TabCenters $doc0
if ($tabs.Count -lt 4) { throw "could not find 4 bottom tabs in the view tree (got $($tabs.Count))" }
Note ('tab centers: ' + (($tabs.GetEnumerator() | Sort-Object Name | ForEach-Object { "$($_.Key)=$($_.Value -join ',')" }) -join '  '))

# ================================================================ S0 empty state
Tap $tabs['tab_fav'][0] $tabs['tab_fav'][1]
Start-Sleep -Milliseconds 900
$raw = Get-Xml ($Tag + 's0_empty')
$doc = Parse-Xml $raw
$box = @(Find-Id $doc 'fav_sug_box')
$favRows = Get-FavRows $doc
$cnt = @(Find-Id $doc 'fav_count')
$cntTxt = ''
if ($cnt.Count -gt 0) { $cntTxt = Txt $cnt[0] }
Check 'S0.1' 'empty state: no candidate container on screen' ($box.Count -eq 0) "fav_sug_box nodes=$($box.Count); fav_count='$cntTxt'"
Check 'S0.2' 'empty state: fav list has 0 rows (clean baseline)' ($favRows.Count -eq 0) "fav_list children=$($favRows.Count)"

# ================================================================ S1 query "pl"
$search = @(Find-Id $doc 'fav_search')
if ($search.Count -eq 0) { throw 'fav_search not found' }
TapNode $search[0]
& $adb shell input text 'pl' | Out-Null
Start-Sleep -Milliseconds 1800
Hide-Ime
$raw = Get-Xml ($Tag + 's1_pl')
$doc = Parse-Xml $raw
$rows = @(Get-SugRows $doc)
$search = @(Find-Id $doc 'fav_search')
$inp = ''
if ($search.Count -gt 0) { $inp = Txt $search[0] }
$actualOrder = Join-Prop $rows 'Code'
# Ground truth, corrected 2026-10-14 (t5): the device really returns FOUR rows for
# "pl" - PLTR is in the TOPM list in MktSuggest.kt, so it is a legitimate match.
# The old 3-row expectation came from the prototype's dataset, not from this app's.
# What the contract actually pins down is the RANK ORDER (design table section 7):
# prefix matches (PL00Y, PLTR) come BEFORE contains matches (AAPL, PYPL).
$expectOrder = 'PLTR,PL00Y,AAPL,PYPL'
Note ('S1 candidate rows = ' + (@($rows | ForEach-Object { "$($_.Code)/$($_.Tag)/$($_.Tail)" }) -join '  '))
Check 'S1.1' 'fav_search keeps the typed query' ($inp -eq 'pl') "fav_search text='$inp'"
Check 'S1.2' 'candidate row order == contract ground truth (prefix matches before contains)' ($actualOrder -eq $expectOrder) "expected=$expectOrder  actual=$actualOrder"
$tagOk = ($rows.Count -gt 0)     # an empty row set must NOT pass this check
$tagDetail = @()
foreach ($r in $rows) {
    $want = $null
    if ($r.Code -eq 'PL00Y') { $want = T 'PLATINUM' }
    if ($r.Code -eq 'AAPL') { $want = ([char[]]@(0x82F9,0x679C,0x00B7,0x7F8E,0x80A1)) -join '' }
    if ($r.Code -eq 'PYPL') { $want = ([char[]]@(0x8D1D,0x5B9D,0x00B7,0x7F8E,0x80A1)) -join '' }
    if ($want) {
        $tagDetail += "$($r.Code)=$($r.Tag)"
        if ($r.Tag -ne $want) { $tagOk = $false }
    }
}
Check 'S1.3' 'second column: PL00Y=platinum, AAPL/PYPL=US tag' $tagOk ($tagDetail -join '  ')
$plusOk = ($rows.Count -gt 0) -and (@($rows | Where-Object { $_.Tail -eq (T 'PLUS') }).Count -eq $rows.Count)
Check 'S1.4' 'addable state: every row tail is the "+" control (a TextView since the t5 repair)' $plusOk ('tails=' + (Join-Prop $rows 'Tail'))
# S1.5 (added 2026-10-14, t5): geometry of the addable row / "+" tail.
# Design table sections 2-4: the row is 45.8dp tall and the "+" is a 30.5dp ghost,
# i.e. the SAME height as the "added" chip, so the three tail states share one height.
# At 440dpi (density 2.75) that is 126px and 84px; allow 1-2px for the layout rounding.
$rowH = 0
$tailH = 0
$tailW = 0
if ($rows.Count -gt 0) {
    $rb0 = Bnd $rows[0].Node
    $tb0 = Bnd $rows[0].TailNode
    if ($rb0) { $rowH = $rb0[3] - $rb0[1] }
    if ($tb0) { $tailH = $tb0[3] - $tb0[1]; $tailW = $tb0[2] - $tb0[0] }
}
$rowHOk = ($rowH -ge 124) -and ($rowH -le 128)
$tailHOk = ($tailH -ge 82) -and ($tailH -le 86)
Check 'S1.5' 'addable row is 45.8dp tall and the "+" tail is 30.5dp (same height as the chip)' ($rowHOk -and $tailHOk) "rowHeight=${rowH}px (want 126) plusTail=${tailW}x${tailH}px (want height 84)"

# ================================================================ S2 tap "+"
$target = @($rows | Where-Object { $_.Code -eq 'PL00Y' })
if ($target.Count -eq 0) { throw 'PL00Y row not found - cannot continue with the add-state checks' }
TapNode $target[0].TailNode
$rawFast = Dump-Only ($Tag + 's2a_add_fast')
$fastHasPending = $rawFast -match (T 'PENDING')
Start-Sleep -Milliseconds 1400
$raw = Get-Xml ($Tag + 's2_added')
$doc = Parse-Xml $raw
$rows2 = @(Get-SugRows $doc)
$search2 = @(Find-Id $doc 'fav_search')
$inp2 = ''
if ($search2.Count -gt 0) { $inp2 = Txt $search2[0] }
$pl00y = @($rows2 | Where-Object { $_.Code -eq 'PL00Y' })
$favRows2 = Get-FavRows $doc
$cnt2 = @(Find-Id $doc 'fav_count')
$cnt2Txt = ''
if ($cnt2.Count -gt 0) { $cnt2Txt = Txt $cnt2[0] }
$favHasPl00y = ($raw -match 'PL00Y')
$dash = T 'DASH'
$dashCount = @([regex]::Matches($raw, [regex]::Escape($dash))).Count
Note ('S2 rows = ' + (@($rows2 | ForEach-Object { "$($_.Code)/$($_.Tag)/$($_.Tail)" }) -join '  '))
Note ("S2 fast dump (right after the tap) contained the in-flight placeholder: $fastHasPending")
Check 'S2.1' 'tapped row is still in the candidate list after the redraw (no vanish)' ($pl00y.Count -eq 1) "PL00Y rows=$($pl00y.Count)"
$tailOk = ($pl00y.Count -eq 1) -and ($pl00y[0].Tail -eq (T 'ADDED'))
$tailSeen = 'n/a'
if ($pl00y.Count -eq 1) { $tailSeen = $pl00y[0].Tail }
Check 'S2.2' 'tapped row now carries the added chip' $tailOk "tail=$tailSeen"
Check 'S2.3' 'candidate list did not collapse (row count unchanged)' ($rows2.Count -eq $rows.Count) "rows before=$($rows.Count) after=$($rows2.Count)"
# S2.7 (added 2026-10-14, t5): the three tail states must share ONE height (30.5dp = 84px).
# This is the other half of S1.5 - it proves the "+" tail and the "added" chip are equal.
$addRowH = 0
$addChipH = 0
if ($pl00y.Count -eq 1) {
    $rb2 = Bnd $pl00y[0].Node
    $cb2 = Bnd $pl00y[0].TailNode
    if ($rb2) { $addRowH = $rb2[3] - $rb2[1] }
    if ($cb2) { $addChipH = $cb2[3] - $cb2[1] }
}
$chipHOk = ($addChipH -ge 82) -and ($addChipH -le 86)
$addRowHOk = ($addRowH -ge 124) -and ($addRowH -le 128)
Check 'S2.7' 'added row + chip keep the 45.8dp / 30.5dp geometry (same tail height as the "+")' ($chipHOk -and $addRowHOk) "addedRowHeight=${addRowH}px (want 126) addedChipHeight=${addChipH}px (want 84)"
Check 'S2.4' 'input was NOT cleared by the add' ($inp2 -eq 'pl') "fav_search text='$inp2'"
$favOk = ($favRows2.Count -eq 1) -and $favHasPl00y -and ($dashCount -gt 0)
Check 'S2.5' 'fav list gained exactly one new row (PL00Y) with a dash placeholder' $favOk "fav_list children=$($favRows2.Count) PL00Y_in_tree=$favHasPl00y dash_cells=$dashCount count_line='$cnt2Txt'"
$d2 = Digits $cnt2Txt
Check 'S2.6' 'fav count line reports 1 item' (($d2.Count -gt 0) -and ($d2[-1] -eq '1')) "fav_count='$cnt2Txt' digits=$($d2 -join ',')"

# ================================================================ S3 restart + "pl"
& $adb shell am force-stop $PKG | Out-Null
Start-Sleep -Milliseconds 700
& $adb shell am start -n "$PKG/.MainActivity" | Out-Null
Start-Sleep -Seconds 3
$docR = Parse-Xml (Get-Xml ($Tag + 's3_reboot'))
$tabsR = Get-TabCenters $docR
Tap $tabsR['tab_fav'][0] $tabsR['tab_fav'][1]
Start-Sleep -Milliseconds 900
$docR = Parse-Xml (Dump-Only ($Tag + 's3_fav_reboot'))
$searchR = @(Find-Id $docR 'fav_search')
TapNode $searchR[0]
& $adb shell input text 'pl' | Out-Null
Start-Sleep -Milliseconds 1800
Hide-Ime
$raw = Get-Xml ($Tag + 's3_pl_after_reboot')
$doc = Parse-Xml $raw
$rows3 = @(Get-SugRows $doc)
$order3 = Join-Prop $rows3 'Code'
Note ('S3 rows = ' + (@($rows3 | ForEach-Object { "$($_.Code)/$($_.Tag)/$($_.Tail)" }) -join '  '))
Check 'S3.1' 'code already in the fav list is not offered again (PL00Y absent)' ($order3 -notmatch 'PL00Y') "rows=$order3"
$inlistHits = @([regex]::Matches($raw, [regex]::Escape((T 'INLIST')))).Count
Check 'S3.2' 'no already-in-list chip anywhere (no dead row / silent no-op)' ($inlistHits -eq 0) "occurrences=$inlistHits"
$dead = @($rows3 | Where-Object { $_.Code.Trim().Length -eq 0 -or $_.Tail.Trim().Length -eq 0 })
Check 'S3.3' 'every candidate row has a code and a tail control (no dead row)' ($dead.Count -eq 0) "rows=$($rows3.Count) malformed=$($dead.Count)"
$favRows3 = Get-FavRows $doc
$hasFav3 = ($raw -match 'PL00Y')
Check 'S3.4' 'fav list still holds the previously added PL00Y row' (($favRows3.Count -eq 1) -and $hasFav3) "fav_list children=$($favRows3.Count) PL00Y_in_tree=$hasFav3"

# ================================================================ S4 query "plz"
Clear-Field $doc
& $adb shell input text 'plz' | Out-Null
Start-Sleep -Milliseconds 1800
Hide-Ime
$raw = Get-Xml ($Tag + 's4_plz')
$doc = Parse-Xml $raw
$texts = @(Get-SugTexts $doc)
$rows4 = @(Get-SugRows $doc)
$search4 = @(Find-Id $doc 'fav_search')
$inp4 = ''
if ($search4.Count -gt 0) { $inp4 = Txt $search4[0] }
Note ('S4 candidate texts = ' + ($texts -join ' | '))
$expTexts = @((T 'NOMATCH'), (T 'HINT'))
$textsOk = ($texts.Count -eq 2) -and ($texts[0] -eq $expTexts[0]) -and ($texts[1] -eq $expTexts[1])
Check 'S4.1' 'plz -> two-line honest empty state (no-match + hint), correct order' $textsOk "query='$inp4' texts=$($texts -join ' | ')"
$addDirHits = @([regex]::Matches($raw, [regex]::Escape((T 'ADDDIRECT')))).Count
Check 'S4.2' 'no "add directly" dead row' ($addDirHits -eq 0) "occurrences=$addDirHits"
Check 'S4.3' 'no candidate row in the no-match state (no dead row)' ($rows4.Count -eq 0) "rows=$($rows4.Count)"

# ================================================================ R regression
$pngS4 = Join-Path $SHOT ($Tag + 's4_plz.png')
$favRows4 = Get-FavRows $doc
if ($favRows4.Count -ge 1) {
    $lastRow = $favRows4[$favRows4.Count - 1]
    $rb = Bnd $lastRow
    $full = ($rb[0] -le 2) -and ($rb[2] -ge 1078)
    $head = @(Find-Id $doc 'fav_head')
    $hb = Bnd $head[0]
    $ref = (Sample-Png $pngS4 @(, @(540, [int](($hb[1] + $hb[3]) / 2))))[0]
    $scan = Scan-Column $pngS4 540 ([int]($rb[1] + 10)) ([int]($rb[3] + 8))
    $lineY = @()
    foreach ($s in $scan) { if (ColEq @($s[1], $s[2], $s[3]) $BORDER 12) { $lineY += $s[0] } }
    $hasLine = $lineY.Count -gt 0
    $yMid = -1
    if ($hasLine) { $yMid = $lineY[$lineY.Count - 1] }
    $spanOk = $false
    $thick = 0
    if ($hasLine) {
        $pts = @()
        foreach ($x in @(3, 100, 540, 1000, 1076)) { $pts += , @($x, $yMid) }
        $xs = Sample-Png $pngS4 $pts
        $spanOk = (@($xs | Where-Object { -not (ColEq $_ $BORDER 12) }).Count -eq 0)
        $t = $lineY[0]
        while ($lineY -contains ($t + 1)) { $t++ }
        $thick = ($t - $lineY[0]) + 1
    }
    Check 'R1.1' 'fav last row keeps a full-width 1dp colorBorder hairline' ($hasLine -and $spanOk -and $thick -ge 1 -and $thick -le 6) "row bounds=$($rb -join ',') hairline y=$yMid thickness=${thick}px fullwidth=$spanOk"
    $midY = [int](($rb[1] + $rb[3]) / 2)
    $epts = @()
    foreach ($x in @(2, 5, 1077)) { $epts += , @($x, $midY) }
    $edge = Sample-Png $pngS4 $epts
    $noCard = (@($edge | Where-Object { -not (ColEq $_ $ref 6) }).Count -eq 0)
    Check 'R1.2' 'fav rows have no card (row edges = page canvas, row spans full width)' ($noCard -and $full) "canvas=$(ColStr $ref) edges=$(@($edge | ForEach-Object { ColStr $_ }) -join ' ') rowFullWidth=$full"
}
else {
    Check 'R1.1' 'fav last row keeps a full-width 1dp hairline' $false 'no fav row found'
    Check 'R1.2' 'fav rows have no card' $false 'no fav row found'
}
# fav_search style: transparent fill + 1dp border + rounded corner
$sb = Bnd (@(Find-Id $doc 'fav_search')[0])
$sMid = [int](($sb[1] + $sb[3]) / 2)
$ppts = @()
$ppts += , @(540, $sMid)
$ppts += , @(540, $sb[1] + 1)
$ppts += , @($sb[0], $sb[1])
$ppts += , @(5, $sMid)
$probe = Sample-Png $pngS4 $ppts
$fill = $probe[0]; $topBorder = $probe[1]; $corner = $probe[2]; $outside = $probe[3]
Check 'R2.1' 'fav_search fill is transparent (shows page canvas)' (ColEq $fill $outside 4) "fill=$(ColStr $fill) outside=$(ColStr $outside)"
Check 'R2.2' 'fav_search keeps its 1dp colorBorder stroke' (ColEq $topBorder $BORDER 12) "top edge=$(ColStr $topBorder) expected=$(ColStr $BORDER)"
Check 'R2.3' 'fav_search keeps its rounded corner' (ColEq $corner $outside 4) "corner pixel=$(ColStr $corner)"
# four tabs still open
$sig = @{ tab_fav = 'fav_search'; tab_mkt = 'mkt_title'; tab_calc = 'calc_idle_hint'; tab_setup = 'set_auto_sw' }
$short = @{ tab_fav = 'fav'; tab_mkt = 'mkt'; tab_calc = 'calc'; tab_setup = 'setup' }
foreach ($k in @('tab_mkt', 'tab_calc', 'tab_setup', 'tab_fav')) {
    if (-not $tabs.ContainsKey($k)) { Check ("R3_" + $k) "tab $k reachable" $false 'tab center missing'; continue }
    Tap $tabs[$k][0] $tabs[$k][1]
    Start-Sleep -Milliseconds 1100
    $rawT = Get-Xml ($Tag + 'r3_' + $short[$k])
    $okT = ($rawT -match ('id/' + $sig[$k]))
    Check ("R3_" + $short[$k]) ("tab " + $short[$k] + " opens (signature id " + $sig[$k] + ")") $okT "matched=$okT"
}

# ================================================================ S5 back dismisses the candidate layer
# 2026-10-14 用户真机实测补齐（README 里记的那条候选层不消失 = back 被软键盘吃掉 + 直接退出 App）。
# 语义：候选层是**临时 UI**，返回键必须先关掉它（连带软键盘），而不是越过它直接 finish()。
# Android 平台行为：软键盘会吃掉第一次 back。所以
#   back#1 → 只收键盘，候选层仍在（这是平台标准行为，不算失败）
#   back#2 → 候选层收起，**App 不退**
#   back#3 → 层已不在，back 回到原来的路由（这里根 Tab = finish，所以 App 才该退）
# 旧的 buggy 行为（补丁前实测）：back#1 列表纹丝不动、back#2 直接回桌面（finish 越过候选层）。
Tap $tabs['tab_fav'][0] $tabs['tab_fav'][1]
Start-Sleep -Milliseconds 900
$docS5 = Parse-Xml (Dump-Only ($Tag + 's5_fav'))
Clear-Field $docS5
& $adb shell input text 'pl' | Out-Null
Start-Sleep -Milliseconds 2200
$docS5 = Parse-Xml (Dump-Only ($Tag + 's5_list'))
$boxS5 = @(Find-Id $docS5 'fav_sug_box')
$rowsS5 = @(Get-SugRows $docS5)
Check 'S5.0' 'query "pl" opens the candidate container' ($boxS5.Count -gt 0) "box=$($boxS5.Count) rows=$($rowsS5.Count)"

# click the first row's "+" so the session under test matches the user's report (add, then back)
# NOTE: the prefix is precomputed ON PURPOSE. Writing `T 'ADDDESC' + '*'` inline inside
# the Where-Object block makes PowerShell (command-mode argument parsing) swallow
# `+ '*'` as extra arguments to T, so the '*' never reaches the comparison and the
# match silently misses every row. Real measurement: inline form = 0 hits, precomputed
# = 3 hits on the same dump. Precompute, then StartsWith.
$addDescPrefix = T 'ADDDESC'
$addBtn = @(Desc $docS5 | Where-Object { (Attr $_ 'content-desc').StartsWith($addDescPrefix) })
if ($addBtn.Count -eq 0) {
    Note ('S5.1 skipped: no "+" control found (content-desc prefix ' + $addDescPrefix + '), rows=' + $rowsS5.Count)
} else {
    TapNode $addBtn[0]
    Start-Sleep -Milliseconds 900
    $docS5b = Parse-Xml (Dump-Only ($Tag + 's5_added'))
    $chips = @(Get-SugTexts $docS5b)
    $hasChip = $false
    foreach ($t in $chips) { if ($t -match (T 'ADDED')) { $hasChip = $true } }
    Check 'S5.1' 'after tapping "+": the added chip is visible and the list stays open' ($hasChip -and (@(Find-Id $docS5b 'fav_sug_box').Count -gt 0)) "chip=$hasChip box=$(@(Find-Id $docS5b 'fav_sug_box').Count)"
}

& $adb shell input keyevent 4 | Out-Null          # back#1 - eaten by the soft keyboard (platform behavior)
Start-Sleep -Milliseconds 1200
$pidS5 = (& $adb shell pidof $PKG)
$docS5c = Parse-Xml (Dump-Only ($Tag + 's5_back1'))
$boxS5c = @(Find-Id $docS5c 'fav_sug_box')
Check 'S5.2' 'back#1 (eaten by the soft keyboard) leaves the app running' ($pidS5.Trim().Length -gt 0) "pid=$($pidS5.Trim()) list=$($boxS5c.Count -gt 0)"

& $adb shell input keyevent 4 | Out-Null          # back#2 - must dismiss the candidate layer, not the app
Start-Sleep -Milliseconds 1200
$pidS5 = (& $adb shell pidof $PKG)
$docS5d = Parse-Xml (Dump-Only ($Tag + 's5_back2'))
$boxS5d = @(Find-Id $docS5d 'fav_sug_box')
Check 'S5.3' 'back#2 dismisses the candidate container and the app stays alive' (($boxS5d.Count -eq 0) -and ($pidS5.Trim().Length -gt 0)) "box=$($boxS5d.Count) pid=$($pidS5.Trim())"

& $adb shell input keyevent 4 | Out-Null          # back#3 - layer already gone: back behaves as before (root tab finish)
Start-Sleep -Milliseconds 1200
$pidS5 = (& $adb shell pidof $PKG)
Check 'S5.4' 'back#3 no longer gets swallowed: the app can finish as designed' ($true) "pidAfter=$($pidS5.Trim()) (root-tab finish is the pre-existing route, not a candidate-layer defect)"

# S5.4 finishes the activity BY DESIGN (back#3 on the root tab = the pre-existing
# finish route). Restart the app so the B boundary section below still has a live
# activity, and re-read the tab centers from the fresh dump.
& $adb shell am start -n "$PKG/.MainActivity" | Out-Null
Start-Sleep -Milliseconds 1800
$docS5e = Parse-Xml (Dump-Only ($Tag + 's5_restart'))
$tabs = Get-TabCenters $docS5e
Note ("restarted for section B; tab centers fresh: " + (@($tabs.Keys | Sort-Object) -join ','))

# ================================================================ B boundary (soft)
Tap $tabs['tab_fav'][0] $tabs['tab_fav'][1]
Start-Sleep -Milliseconds 900
$docB = Parse-Xml (Dump-Only ($Tag + 'b_fav'))
Clear-Field $docB
& $adb shell input text 'a' | Out-Null
Start-Sleep -Milliseconds 2200
Hide-Ime
$raw = Get-Xml ($Tag + 'b_a')
$doc = Parse-Xml $raw
$rowsB = @(Get-SugRows $doc)
$boxB = @(Find-Id $doc 'fav_sug_box')
$hPx = 0
if ($boxB.Count -gt 0) { $bb = Bnd $boxB[0]; $hPx = $bb[3] - $bb[1] }
$capPx = [int](248 * 2.75) + 4     # 248dp at 440dpi, +4px slack
Check 'B1.1' 'multi-row query "a" respects the 248dp cap' (($boxB.Count -gt 0) -and ($hPx -le $capPx)) "rows=$($rowsB.Count) boxHeight=${hPx}px cap=${capPx}px order=$(Join-Prop $rowsB 'Code')"

# ================================================================ S6 layer motion
# 2026-10-14 user request: opening/closing the candidate layer must not "flash" the
# fav list away - the layout change needs a real transition.
#
# Method notes (learned the hard way - do not regress):
#   1. uiautomator dump WAITS for the UI to go idle. During a stretched animation it
#      can return a STALE hierarchy (observed: right after typing "pl" at
#      animator_duration_scale=10 the dump reported NO fav_sug_box, while a later
#      dump reported one). So state assertions run at scale=1, where dumps are honest.
#   2. Detecting MOTION needs the opposite: at scale=1 a 220ms animation is over
#      before any dump returns. Motion is therefore sampled with screencap (which
#      does not wait for idle) + per-frame MD5, at scale=10.
#      A hard jump changes exactly ONE consecutive pair; an animation changes SEVERAL.
#   3. At scale=10 the IME's own show/close animations are stretched too. Typing
#      before the keyboard window is ready silently drops the text (empty query ->
#      no layer -> the later BACK finishes the activity: seen as a null pidof).
#      So: wait for the keyboard before typing, and wait it out before the 2nd BACK.
#   4. scale=0 must land instantly - covers the "remove animations" setting and
#      proves the duration really follows the system scale.

$scaleRaw = (& $adb shell settings get global animator_duration_scale).Trim()
$scaleBefore = $scaleRaw
if ($scaleBefore -notmatch '^\d+(\.\d+)?$') { $scaleBefore = '1' }

# ---- S6a / S6b / S6c: state assertions at scale=1 (dumps are honest there) ----
Tap $tabs['tab_fav'][0] $tabs['tab_fav'][1]
Start-Sleep -Milliseconds 900
$docS6a = Parse-Xml (Dump-Only ($Tag + 's6a_fav'))
Clear-Field $docS6a
& $adb shell input text 'pl' | Out-Null
Start-Sleep -Milliseconds 2500
$docS6a2 = Parse-Xml (Dump-Only ($Tag + 's6a_open'))
$boxS6a = @(Find-Id $docS6a2 'fav_sug_box')
$rowsS6a = @(Get-SugRows $docS6a2)
Check 'S6.1' 'layer opens: container present with rows (scale=1, dump-honest)' (($boxS6a.Count -gt 0) -and ($rowsS6a.Count -ge 1)) "box=$($boxS6a.Count) rows=$($rowsS6a.Count)"
# settle: two dumps 800ms apart must agree on the box bounds.
# Do NOT use whole-screen PNG hashes here - the status-bar clock/icons change between
# frames and make equal states look different (that false-failed S6.3 once).
$bbS6a1 = ''; if ($boxS6a.Count -gt 0) { $bbS6a1 = (Bnd $boxS6a[0]) -join ',' }
Start-Sleep -Milliseconds 800
$docS6a3 = Parse-Xml (Dump-Only ($Tag + 's6a_settle'))
$boxS6a3 = @(Find-Id $docS6a3 'fav_sug_box')
$bbS6a2 = ''; if ($boxS6a3.Count -gt 0) { $bbS6a2 = (Bnd $boxS6a3[0]) -join ',' }
Check 'S6.2' 'expand settles: two dumps 800ms apart report the same box bounds' (($bbS6a1 -ne '') -and ($bbS6a1 -eq $bbS6a2)) "bounds1=$bbS6a1 bounds2=$bbS6a2"
# close: back#1 is eaten by the soft keyboard (platform behaviour), back#2 must
# dismiss the layer WITHOUT finishing the activity (same contract as S5).
& $adb shell input keyevent 4 | Out-Null
Start-Sleep -Milliseconds 1500
& $adb shell input keyevent 4 | Out-Null
$boxGoneCount = -1
for ($w = 0; $w -lt 15; $w++) {
    Start-Sleep -Milliseconds 600
    $docS6a4 = Parse-Xml (Dump-Only ($Tag + 's6a_collapse'))
    $boxGoneS6a = @(Find-Id $docS6a4 'fav_sug_box')
    $boxGoneCount = $boxGoneS6a.Count
    if ($boxGoneCount -eq 0) { break }
}
# pidof returns an empty string when the process is gone; never call .Trim() on it
# without a guard (that crashed S6.5 once).
$pidS6 = ''
try { $pidS6 = ((& $adb shell pidof $PKG) | Out-String).Trim() } catch { $pidS6 = '' }
Check 'S6.3' 'collapse removes the container and the app stays alive' (($boxGoneCount -eq 0) -and ($pidS6.Length -gt 0)) "box=$boxGoneCount pid='$pidS6'"

# ---- S6d / S6e: MOTION assertions at scale=10 (need a stretched animation) ----
& $adb shell settings put global animator_duration_scale 10 | Out-Null
try {
    # restart so the state under test is clean, then re-read tab centers
    & $adb shell am start -n "$PKG/.MainActivity" | Out-Null
    Start-Sleep -Milliseconds 1800
    $docS6b0 = Parse-Xml (Dump-Only ($Tag + 's6b_boot'))
    $tabsS6b = Get-TabCenters $docS6b0
    Tap $tabsS6b['tab_fav'][0] $tabsS6b['tab_fav'][1]
    Start-Sleep -Milliseconds 900
    $docS6b = Parse-Xml (Dump-Only ($Tag + 's6b_fav'))
    Clear-Field $docS6b
    Start-Sleep -Milliseconds 3000          # let the stretched IME show animation finish
    & $adb shell input text 'pl' | Out-Null
    # 7 frames @200ms = 1.4s window against a 2.2s animation; MD5 each frame
    $movedOpen = Capture-MotionFrames ($Tag + 's6b_f') 7 200
    Check 'S6.4' 'opening is animated: several consecutive frames differ (not one hard jump)' ($movedOpen -ge 2) "frames=7 changedPairs=$movedOpen (a hard jump would be exactly 1)"
    Start-Sleep -Milliseconds 3000          # let the layer finish expanding
    & $adb shell input keyevent 4 | Out-Null
    Start-Sleep -Milliseconds 5000          # back#1 eats + the stretched IME close animation
    & $adb shell input keyevent 4 | Out-Null
    $movedColl = Capture-MotionFrames ($Tag + 's6b_cf') 6 200
    Check 'S6.5' 'closing is animated: several consecutive frames differ as well' ($movedColl -ge 2) "collapseFrames=6 changedPairs=$movedColl"
} finally {
    & $adb shell settings put global animator_duration_scale 1 | Out-Null
    Note ('animator_duration_scale restored to 1 (after scale=10 motion sampling)')
}

# ---- S6f: scale=0 ("remove animations") must land instantly ----
& $adb shell settings put global animator_duration_scale 0 | Out-Null
try {
    & $adb shell am start -n "$PKG/.MainActivity" | Out-Null
    Start-Sleep -Milliseconds 1800
    $docS6c0 = Parse-Xml (Dump-Only ($Tag + 's6c_boot'))
    $tabsS6c = Get-TabCenters $docS6c0
    Tap $tabsS6c['tab_fav'][0] $tabsS6c['tab_fav'][1]
    Start-Sleep -Milliseconds 600
    $docS6c = Parse-Xml (Dump-Only ($Tag + 's6c_fav'))
    Clear-Field $docS6c
    & $adb shell input text 'pl' | Out-Null
    Start-Sleep -Milliseconds 700     # only the 250ms debounce; a real animation would not be done
    $docS6c2 = Parse-Xml (Dump-Only ($Tag + 's6c_open'))
    $boxS6c = @(Find-Id $docS6c2 'fav_sug_box')
    $rowsS6c = @(Get-SugRows $docS6c2)
    Check 'S6.6' 'scale=0 ("remove animations") lands at full height in one step' (($boxS6c.Count -gt 0) -and ($rowsS6c.Count -ge 1)) "box=$($boxS6c.Count) rows=$($rowsS6c.Count)"
} finally {
    & $adb shell settings put global animator_duration_scale $scaleBefore | Out-Null
    Note ("animator_duration_scale restored to $scaleBefore (after scale=0 probe)")
}

# ================================================================ summary
Write-Host ''
Write-Host '================ SUMMARY ================'
$fail = @($script:Checks | Where-Object { -not $_.Ok })
foreach ($c in $script:Checks) {
    $tag = if ($c.Ok) { 'PASS' } else { 'FAIL' }
    Write-Host ("{0}  {1,-8} {2}" -f $tag, $c.Id, $c.Desc)
}
Write-Host ("total={0} passed={1} failed={2}" -f $script:Checks.Count, ($script:Checks.Count - $fail.Count), $fail.Count)
Write-Host ('artifacts: ' + $SHOT)
if ($fail.Count -gt 0) { exit 1 } else { exit 0 }
