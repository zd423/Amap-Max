# ============================================================
# AMapPlus Android Companion - CI build script
# Used by GitHub Actions (windows-latest).
# Mirrors build.ps1 but resolves JDK / Android SDK from env vars.
#
# Requires env:
#   JAVA_HOME     (set by actions/setup-java)
#   ANDROID_HOME  (set by android-actions/setup-android)
# ============================================================

$ErrorActionPreference = 'Stop'

function Check-Last {
    param([string]$Name)
    if ($LASTEXITCODE -ne 0) { throw "$Name failed (exit $LASTEXITCODE)" }
}

$root       = $PSScriptRoot
$buildDir   = Join-Path $root 'build'
$genDir     = Join-Path $buildDir 'gen'
$classesDir = Join-Path $buildDir 'classes'
$dexDir     = Join-Path $buildDir 'dex'
$resDir     = Join-Path $root 'app\src\main\res'
$manifest   = Join-Path $root 'app\src\main\AndroidManifest.xml'
$keystore   = Join-Path $root 'ArcfoxS5.keystore'

if (-not $env:JAVA_HOME)    { throw 'JAVA_HOME not set' }
if (-not $env:ANDROID_HOME) { throw 'ANDROID_HOME not set' }

$androidJar = Join-Path $env:ANDROID_HOME 'platforms\android-31\android.jar'
$buildTools = Join-Path $env:ANDROID_HOME 'build-tools\34.0.0'
$aapt      = Join-Path $buildTools 'aapt.exe'
$d8Jar     = Join-Path $buildTools 'lib\d8.jar'
$zipalign  = Join-Path $buildTools 'zipalign.exe'
$apksigner = Join-Path $buildTools 'apksigner.bat'
$proguardRules = Join-Path $root 'proguard-rules.pro'

$ts = Get-Date -Format 'yyyyMMdd_HHmm'
$outApk = Join-Path $root "package\AMapMax_ci_${ts}.apk"

# ------------------------------------------------------------
# Clean
# ------------------------------------------------------------
Write-Host 'Cleaning...'
Remove-Item $buildDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $genDir, $classesDir, $dexDir, (Split-Path $outApk -Parent) | Out-Null

# BuildConfig.java
$buildConfigPkg = Join-Path $genDir 'com\autonavi\companion\BuildConfig.java'
New-Item -ItemType Directory -Force -Path (Split-Path $buildConfigPkg -Parent) | Out-Null
$noBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllLines($buildConfigPkg, @(
    'package com.autonavi.companion;',
    '',
    '/** Auto-generated - DO NOT EDIT */',
    'public final class BuildConfig {',
    "    public static final String BUILD_TIME = `"$ts`";",
    '    private BuildConfig() {}',
    '}'
), $noBom)

# ------------------------------------------------------------
# aapt -> R.java
# ------------------------------------------------------------
Write-Host 'aapt (R.java)...'
& $aapt package -f -m -J $genDir -M $manifest -S $resDir -I $androidJar
Check-Last 'aapt R.java'

# ------------------------------------------------------------
# javac
# ------------------------------------------------------------
Write-Host 'javac...'
$srcFiles = @()
Get-ChildItem -Recurse -File (Join-Path $root 'app\src\main\java') -Filter '*.java' | ForEach-Object { $srcFiles += $_.FullName }
Get-ChildItem -Recurse -File $genDir -Filter '*.java' | ForEach-Object { $srcFiles += $_.FullName }
$filesLine = ($srcFiles | ForEach-Object { '"' + $_ + '"' }) -join ' '
$javacBin  = Join-Path $env:JAVA_HOME 'bin'
$javacCmd = '"' + (Join-Path $javacBin 'javac.exe') + '" --release 8 -encoding UTF-8 -classpath "' + $androidJar + '" -d "' + $classesDir + '" ' + $filesLine
Write-Host "  javac source files: $($srcFiles.Count)"
& cmd.exe /c $javacCmd 2>&1 | Out-Host
if ($LASTEXITCODE -ne 0) { throw "javac failed (exit $LASTEXITCODE)" }

$expected = Get-ChildItem -Recurse -File $classesDir -Filter '*.class' | Measure-Object | Select-Object -ExpandProperty Count
if ($expected -eq 0) { throw 'javac produced no class files' }
Write-Host "  ($expected class files generated)"

# ------------------------------------------------------------
# R8 -> classes.dex
# ------------------------------------------------------------
Write-Host 'R8 (minify + dex)...'
$classFiles = Get-ChildItem -Recurse -File $classesDir -Filter '*.class' | ForEach-Object { $_.FullName }
$javaBin = Join-Path $env:JAVA_HOME 'bin'
$r8Cmd = '"' + (Join-Path $javaBin 'java.exe') + '" -cp "' + $d8Jar + '" com.android.tools.r8.R8 --release --pg-conf "' + $proguardRules + '" --lib "' + $androidJar + '" --output "' + $dexDir + '" ' + (($classFiles | ForEach-Object { '"' + $_ + '"' }) -join ' ')
& cmd.exe /c $r8Cmd 2>&1 | Out-Host
if ($LASTEXITCODE -ne 0) { throw "R8 failed (exit $LASTEXITCODE)" }

$dexFile = Join-Path $dexDir 'classes.dex'
if (-not (Test-Path $dexFile)) { throw 'classes.dex not found after R8' }
Write-Host "  dex: $((Get-Item $dexFile).Length) bytes"

# ------------------------------------------------------------
# aapt package + add dex
# ------------------------------------------------------------
Write-Host 'aapt (package APK)...'
$UnsignedApk = Join-Path $buildDir 'unsigned.apk'
& $aapt package -f -M $manifest -S $resDir -I $androidJar -F $UnsignedApk
Check-Last 'aapt package'
Push-Location $dexDir
& $aapt add $UnsignedApk 'classes.dex'
Pop-Location
Check-Last 'aapt add classes.dex'

# ------------------------------------------------------------
# zipalign + sign
# ------------------------------------------------------------
Write-Host 'zipalign...'
& $zipalign -f 4 $UnsignedApk $outApk
Check-Last 'zipalign'

Write-Host 'apksigner (sign)...'
& $apksigner sign --ks $keystore --ks-key-alias platform --ks-pass pass:android --key-pass pass:android $outApk
Check-Last 'apksigner'

Write-Host "Done: $outApk"
