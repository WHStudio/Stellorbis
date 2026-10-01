# build.ps1 - Stellorbis (StarRing Ultra 2026) custom app build pipeline (no Gradle).
#
# Purpose: build a signed, installable APK on a machine without Android Studio /
#          Gradle, using only SDK build-tools: aapt2 + javac + d8 + zipalign + apksigner.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File build.ps1            # normal build (keeps debug info)
#   powershell -ExecutionPolicy Bypass -File build.ps1 -Release   # release build (d8 --release)
#
# Optional environment variables:
#   ANDROID_SDK_HOME   SDK root (must contain platforms\android-34 and build-tools\34.0.0)
#   JAVA_HOME          JDK 17 root (must contain bin\javac.exe)
#
# NOTE: keep this file ASCII-only. Windows PowerShell 5.1 reads BOM-less files using
#       the system ANSI code page (GBK here), which mangles non-ASCII text and can even
#       swallow the following line. English-only keeps the script encoding-proof.

param(
    [switch]$Release
)

$ErrorActionPreference = "Stop"

# ---------- paths ----------
$ProjectRoot = $PSScriptRoot
$AppDir      = Join-Path $ProjectRoot "app"
$SrcDir      = Join-Path $AppDir "src\main\java"
$ResDir      = Join-Path $AppDir "src\main\res"
$Manifest    = Join-Path $AppDir "src\main\AndroidManifest.xml"
$BuildDir    = Join-Path $ProjectRoot "build"
$DistDir     = Join-Path $ProjectRoot "dist"
$KeyStoreDir = Join-Path $ProjectRoot "keystore"

$Sdk = if ($env:ANDROID_SDK_HOME) { $env:ANDROID_SDK_HOME } else { Join-Path $env:USERPROFILE ".apk-tools\android-sdk" }
$Jdk = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { Join-Path $env:USERPROFILE ".apk-tools\jdk-tmp\jdk-17.0.20.1+1" }
$Bt  = Join-Path $Sdk "build-tools\34.0.0"

$PlatformJar = Join-Path $Sdk "platforms\android-34\android.jar"
$JavaExe     = Join-Path $Jdk "bin\java.exe"
$JavacExe    = Join-Path $Jdk "bin\javac.exe"
$JarExe      = Join-Path $Jdk "bin\jar.exe"
$KeytoolExe  = Join-Path $Jdk "bin\keytool.exe"
$Aapt2       = Join-Path $Bt "aapt2.exe"
$Zipalign    = Join-Path $Bt "zipalign.exe"
$D8Jar       = Join-Path $Bt "lib\d8.jar"
$ApksignerJar= Join-Path $Bt "lib\apksigner.jar"

foreach ($f in @($PlatformJar, $JavaExe, $JavacExe, $Aapt2, $Zipalign, $D8Jar, $ApksignerJar)) {
    if (-not (Test-Path $f)) { throw "missing build tool: $f" }
}

if (Test-Path $BuildDir) { Remove-Item $BuildDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path "$BuildDir\obj", "$BuildDir\dex", "$BuildDir\gen" | Out-Null
New-Item -ItemType Directory -Force -Path $DistDir | Out-Null

# ---------- 1. aapt2 compile resources ----------
Write-Host "== 1/6 aapt2 compile resources =="
& $Aapt2 compile --dir $ResDir -o "$BuildDir\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

# ---------- 2. aapt2 link (resources + manifest, also generates R.java) ----------
Write-Host "== 2/6 aapt2 link resources + manifest =="
$UnsignedApk = "$BuildDir\app-unsigned.apk"
& $Aapt2 link -o $UnsignedApk `
    -I $PlatformJar `
    --manifest $Manifest `
    --java "$BuildDir\gen" `
    --min-sdk-version 23 --target-sdk-version 34 `
    --version-code 261001 --version-name "26.10.01" `
    "$BuildDir\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

# ---------- 3. javac (sources + generated R.java) ----------
Write-Host "== 3/6 javac compile java sources =="
$classes = Get-ChildItem $SrcDir -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$classes += Get-ChildItem "$BuildDir\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$srcListFile = "$BuildDir\sources.txt"
Set-Content $srcListFile ($classes -join "`n") -Encoding ASCII

& $JavacExe -source 1.8 -target 1.8 -encoding UTF-8 `
    -bootclasspath $PlatformJar `
    -d "$BuildDir\obj" `
    "@$srcListFile"
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

# ---------- 4. d8 -> classes.dex ----------
# Release mode uses "d8 --release": strips debug info (line numbers / local variable
# tables), producing a smaller dex. It does NOT shrink or obfuscate, so the reflective
# call to BluetoothDevice.createRfcommSocket in SppManager keeps working.
if ($Release) { $d8Mode = "--release" } else { $d8Mode = "--debug" }
Write-Host "== 4/6 d8 -> classes.dex ($d8Mode) =="
$classFiles = Get-ChildItem "$BuildDir\obj" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
$classListFile = "$BuildDir\classes.txt"
Set-Content $classListFile ($classFiles -join "`n") -Encoding ASCII

& $JavaExe -cp $D8Jar com.android.tools.r8.D8 `
    $d8Mode --min-api 23 --lib $PlatformJar `
    --output "$BuildDir\dex" `
    "@$classListFile"
if (-not (Test-Path "$BuildDir\dex\classes.dex")) { throw "d8 failed to produce classes.dex" }

& $JarExe uf $UnsignedApk -C "$BuildDir\dex" classes.dex
if ($LASTEXITCODE -ne 0) { throw "failed to merge classes.dex" }

# ---------- 5. zipalign ----------
Write-Host "== 5/6 zipalign =="
$AlignedApk = "$BuildDir\app-aligned.apk"
& $Zipalign -f 4 $UnsignedApk $AlignedApk
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

# ---------- 6. sign ----------
Write-Host "== 6/6 apksigner sign =="
$Keystore = Join-Path $KeyStoreDir "debug.keystore"
if (-not (Test-Path $Keystore)) {
    New-Item -ItemType Directory -Force -Path $KeyStoreDir | Out-Null
    & $KeytoolExe -genkeypair -keystore $Keystore -alias ringultra2026 `
        -keyalg RSA -keysize 2048 -validity 10950 `
        -storepass stellorbis2026 -keypass stellorbis2026 `
        -dname "CN=Stellorbis, OU=Stellorbis, O=Stellorbis, C=CN"
    if ($LASTEXITCODE -ne 0) { throw "failed to generate debug signing key" }
}

$OutName = if ($Release) { "Stellorbis-v26.10.01-release.apk" } else { "Stellorbis-v26.10.01.apk" }
$OutApk = Join-Path $DistDir $OutName
& $JavaExe -cp $ApksignerJar com.android.apksigner.ApkSignerTool `
    sign --ks $Keystore --ks-pass pass:stellorbis2026 --out $OutApk $AlignedApk
if ($LASTEXITCODE -ne 0) { throw "apksigner sign failed" }

& $JavaExe -cp $ApksignerJar com.android.apksigner.ApkSignerTool verify --print-certs $OutApk
if ($LASTEXITCODE -ne 0) { throw "signature verification failed" }

# ---------- delivery: SHA-256 ----------
$Hash = (Get-FileHash -Algorithm SHA256 $OutApk).Hash.ToLower()
$HashFile = "$OutApk.sha256.txt"
Set-Content $HashFile ("{0}  {1}" -f $Hash, $OutName) -Encoding ASCII

Write-Host ""
Write-Host "BUILD OK: $OutApk" -ForegroundColor Green
Write-Host ("size: {0:N1} KB" -f ((Get-Item $OutApk).Length / 1KB))
Write-Host ("sha256: {0}" -f $Hash)
Write-Host "checksum file: $HashFile"
