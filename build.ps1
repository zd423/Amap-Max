# ============================================================
# AMapPlus Android Companion Build Script
# ============================================================
# Requires: JDK 8+, Android SDK, PowerShell 5.1+
# Output: E:\Project\AMap\AMap\package\AMap_yy_dd_HH_mm.apk
# ============================================================

$ErrorActionPreference = 'Stop'

$env:JAVA_HOME    = 'D:\Env\jdk11'
$env:ANDROID_HOME = 'D:\Env\android-sdk'
$env:PATH = $env:JAVA_HOME + '\bin;' + $env:PATH
$ANDROID_SDK_ROOT = $env:ANDROID_HOME

# Check-Last function (must be at top)
function Check-Last {
    param([string]$Name)
    if ($LASTEXITCODE -ne 0) { throw "$Name failed (exit $LASTEXITCODE)" }
}

# Paths
$root        = $PSScriptRoot
$buildDir    = Join-Path $root 'build'
$genDir      = Join-Path $buildDir 'gen'
$classesDir  = Join-Path $buildDir 'classes'
$dexDir      = Join-Path $buildDir 'dex'
$resDir      = Join-Path $root 'app\src\main\res'
$manifest    = Join-Path $root 'app\src\main\AndroidManifest.xml'
$keystore    = Join-Path $root 'ArcfoxS5.keystore'
$tmpDir      = Join-Path $env:LOCALAPPDATA 'Temp'

$androidJar  = 'D:\Env\android-sdk\platforms\android-31\android.jar'
$buildTools  = 'D:\Env\android-sdk\build-tools\34.0.0'
$aapt        = Join-Path $buildTools 'aapt.exe'
$d8          = Join-Path $buildTools 'd8.bat'
$zipalign    = Join-Path $buildTools 'zipalign.exe'
$apksigner   = Join-Path $buildTools 'apksigner.bat'

# APK output name
$ts = Get-Date -Format "yy_MM_dd_HH_mm"
$outApk = "E:\Project\AMap\AMap\package\AMap_$ts.apk"

# ============================================================
# Clean
# ============================================================
Write-Host 'Cleaning...'
Remove-Item $buildDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $genDir, $classesDir, $dexDir, (Split-Path $outApk -Parent) | Out-Null

# ============================================================
# aapt -> R.java
# ============================================================
Write-Host 'aapt (R.java)...'
& $aapt package -f -m -J $genDir -M $manifest -S $resDir -I $androidJar
Check-Last 'aapt R.java'

# ============================================================
# javac
# ============================================================
Write-Host 'javac...'

# Collect all .java source files
$srcFiles = @()
Get-ChildItem -Recurse -File (Join-Path $root 'app\src\main\java') -Filter '*.java' | ForEach-Object { $srcFiles += $_.FullName }
Get-ChildItem -Recurse -File $genDir -Filter '*.java' | ForEach-Object { $srcFiles += $_.FullName }

# Join into one line (space-separated) for .bat file
$filesLine = $srcFiles -join ' '
Write-Host "  source files: $($srcFiles.Count), filesLine length: $($filesLine.Length)"

# JDK 17 requires --release 8 (cannot use -source 8 -target 8)
# Use single-quote string concatenation to avoid double-quote escaping issues
$javacBin   = Join-Path $env:JAVA_HOME 'bin'
$javacCmd = '"' + (Join-Path $javacBin 'javac.exe') + '" --release 8 -encoding UTF-8 -classpath "' + $androidJar + '" -d "' + $classesDir + '" ' + $filesLine
Write-Host "  javacCmd length: $($javacCmd.Length)"

# Write temp .bat file (ASCII encoding to avoid UTF-8 BOM issues)
$javacBatLines = @(
    '@echo off',
    $javacCmd
)
[System.IO.File]::WriteAllLines((Join-Path $tmpDir '_javac.bat'), $javacBatLines, [System.Text.Encoding]::ASCII)
Write-Host "  javac .bat written, executing..."

# Start-Process + file redirection to avoid PowerShell capturing javac stderr
$proc = Start-Process `
    -FilePath 'cmd.exe' `
    -ArgumentList '/c', (Join-Path $tmpDir '_javac.bat') `
    -Wait -NoNewWindow -PassThru `
    -RedirectStandardError (Join-Path $tmpDir 'javac_err.txt') `
    -RedirectStandardOutput (Join-Path $tmpDir 'javac_out.txt')

$err = Get-Content (Join-Path $tmpDir 'javac_err.txt') -Raw
$out = Get-Content (Join-Path $tmpDir 'javac_out.txt') -Raw
if ($out) { Write-Host "  javac stdout: $out" }
if ($err) { Write-Host "  javac stderr: $err" }

if ($proc.ExitCode -ne 0) { throw "javac failed with exit $($proc.ExitCode)" }

# Check if .class files were generated
$expected = Get-ChildItem -Recurse -File $classesDir -Filter '*.class' | Measure-Object | Select-Object -ExpandProperty Count
if ($expected -eq 0) { throw "javac produced no class files" }
Write-Host "  ($expected class files generated)"

# ============================================================
# d8 -> classes.dex
# ============================================================
Write-Host 'd8...'
$classFiles = Get-ChildItem -Recurse -File $classesDir -Filter '*.class' | ForEach-Object { $_.FullName }
Write-Host "  class files: $($classFiles.Count)"
& $d8 --lib $androidJar --output $dexDir $classFiles
Check-Last 'd8'

# ============================================================
# aapt -> .apk (unsigned)
# ============================================================
Write-Host 'aapt (package APK)...'
$UnsignedApk = Join-Path $buildDir 'unsigned.apk'
& $aapt package -f -M $manifest -S $resDir -I $androidJar -F $UnsignedApk
Check-Last 'aapt package'

# Add classes.dex to APK (change to dexDir so aapt uses correct entry name)
$dexFile = Join-Path $dexDir 'classes.dex'
if (-not (Test-Path $dexFile)) { throw "classes.dex not found at $dexFile" }
Push-Location $dexDir
& $aapt add $UnsignedApk 'classes.dex'
Pop-Location
Check-Last 'aapt add classes.dex'

# ============================================================
# zipalign
# ============================================================
Write-Host 'zipalign...'
& $zipalign -f -v 4 $UnsignedApk $outApk | Out-Null
Check-Last 'zipalign'

# ============================================================
# apksigner
# ============================================================
Write-Host 'apksigner (sign)...'
& $apksigner sign --ks $keystore --ks-key-alias platform --ks-pass pass:android --key-pass pass:android $outApk
Check-Last 'apksigner'
# Remove auto-generated .idsig file (idsig not supported by this version)
$idsigFile = "$outApk.idsig"
if (Test-Path $idsigFile) { Remove-Item $idsigFile -Force }

Write-Host "Done: $outApk"
