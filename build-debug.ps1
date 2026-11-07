# AmapNavi-Reborn build script (Windows, PowerShell 5+)
# Purpose: assemble debug APK for Android 4.4.2 (API 19) armeabi-v7a
#
# WHY an ASCII mirror?
#   AGP 7.4.2's mergeDebugResources crashes with "!directory.isDirectory"
#   when the project lives under a non-ASCII path. Mirror to a pure-ASCII
#   temp location and build there — this is a known workaround verified on
#   this repo. The android.overridePathCheck=true flag only silences the
#   *warning*; the AAPT2 merge step is the one that actually barfs.
#
# WHY env vars instead of properties?
#   gradle.properties and local.properties are parsed via Java's Properties
#   class using the system default encoding (GBK on Chinese Windows), so any
#   non-ASCII absolute path written there is corrupted before Gradle sees it.
#   Inject JAVA_HOME / ANDROID_HOME / ANDROID_SDK_ROOT / PATH via the shell
#   environment — that always round-trips through Unicode correctly.

param(
    [string]$ProjectRoot = $(if ($PSScriptRoot) { $PSScriptRoot } else { "E:\汽车\库斯途升级破解\AmapNavi-Reborn" }),
    [string]$MirrorDir   = "$env:TEMP\amapnavi-build",
    [string]$JdkHome     = "E:\汽车\sdk_tools\jdk-17\jdk-17.0.2",
    [string]$AndroidSdk  = "D:\android\sdk",
    [string]$GradleBin   = "D:\gradle\gradle-7.5\bin\gradle.bat",
    [string]$GradleUserHome = "D:\gradle\gradle-home"
)

$ErrorActionPreference = "Stop"
$Stopwatch = [System.Diagnostics.Stopwatch]::StartNew()

Write-Host "==> AmapNavi-Reborn debug build" -ForegroundColor Cyan
Write-Host "    project     : $ProjectRoot"
Write-Host "    mirror      : $MirrorDir"
Write-Host "    JDK         : $JdkHome"
Write-Host "    Android SDK : $AndroidSdk"
Write-Host "    Gradle      : $GradleBin"

# --- 0. validate prereqs ---
foreach ($p in @($ProjectRoot, $JdkHome, $AndroidSdk, $GradleBin)) {
    if (-not (Test-Path $p)) { Write-Error "missing: $p" }
}

# --- 1. mirror source tree to pure-ASCII temp location ---
Write-Host "`n==> mirroring source -> $MirrorDir" -ForegroundColor Cyan
if (Test-Path $MirrorDir) { Remove-Item -Recurse -Force $MirrorDir }
New-Item -ItemType Directory -Path "$MirrorDir" | Out-Null

# app/src and app build files
New-Item -ItemType Directory -Force -Path "$MirrorDir\app" | Out-Null
Copy-Item "$ProjectRoot\app\build.gradle" "$MirrorDir\app\" -Force
if (Test-Path "$ProjectRoot\app\proguard-rules.pro") {
    Copy-Item "$ProjectRoot\app\proguard-rules.pro" "$MirrorDir\app\" -Force
}
robocopy "$ProjectRoot\app\src" "$MirrorDir\app\src" /E /PURGE /NFL /NDL /NJH /NJS | Out-Null
# root build files
robocopy $ProjectRoot $MirrorDir "build.gradle" "settings.gradle" "gradle.properties" "gradlew" "gradlew.bat" "AndroidManifest.xml" /NFL /NDL /NJH /NJS | Out-Null
# gradle wrapper properties (ASCII content only)
New-Item -ItemType Directory -Force -Path "$MirrorDir\gradle\wrapper" | Out-Null
if (Test-Path "$ProjectRoot\gradle\wrapper\gradle-wrapper.properties") {
    Copy-Item "$ProjectRoot\gradle\wrapper\gradle-wrapper.properties" "$MirrorDir\gradle\wrapper\" -Force
}
# local.properties: keep it ASCII-only, Gradle must NOT see non-ASCII here
Set-Content -Path "$MirrorDir\local.properties" `
    -Value "# SDK path resolved from ANDROID_HOME env var" `
    -Encoding ASCII

Write-Host "    done (robocopy exit=$LASTEXITCODE)"

# --- 2. inject env vars (Unicode-safe, bypasses GBK .properties issue) ---
$env:JAVA_HOME        = $JdkHome
$env:ANDROID_HOME     = $AndroidSdk
$env:ANDROID_SDK_ROOT = $AndroidSdk
$env:GRADLE_USER_HOME = $GradleUserHome
$env:PATH             = "$JdkHome\bin;$AndroidSdk\platform-tools;$env:PATH"

Write-Host "`n==> JAVA_HOME     = $env:JAVA_HOME"
Write-Host "==> ANDROID_HOME  = $env:ANDROID_HOME"
Write-Host "==> java -version :"
& java -version 2>&1 | ForEach-Object { Write-Host "    $_" }

# --- 3. assemble debug ---
Write-Host "`n==> running gradle assembleDebug" -ForegroundColor Cyan
Push-Location $MirrorDir
try {
    & $GradleBin --no-daemon --console=plain assembleDebug
    $gradlerc = $LASTEXITCODE
} finally {
    Pop-Location
}

if ($gradlerc -ne 0) {
    Write-Error "gradle failed with exit code $gradlerc (mirror dir kept at $MirrorDir for debugging)"
}

# --- 4. archive APK back into project dist/ ---
$apkInMirror = "$MirrorDir\app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apkInMirror)) {
    Write-Error "APK not found after build: $apkInMirror"
}

$distDir = "$ProjectRoot\dist"
New-Item -ItemType Directory -Force -Path $distDir | Out-Null
$apkOut = "$distDir\AmapNavi-Reborn-1.4.0-custo-debug.apk"
Copy-Item $apkInMirror $apkOut -Force

$Stopwatch.Stop()
$info = Get-Item $apkOut
Write-Host "`n==> SUCCESS  " -ForegroundColor Green -NoNewline
Write-Host ("{0:N0} KB, built in {1:N1}s -> {2}" -f ($info.Length/1KB), $Stopwatch.Elapsed.TotalSeconds, $apkOut)

# optional: show aapt badging
$aapt = "$AndroidSdk\build-tools\30.0.3\aapt.exe"
if (Test-Path $aapt) {
    Write-Host "    --- aapt dump badging (key lines) ---"
    & $aapt dump badging $apkOut 2>$null `
        | Select-String -Pattern "package:|sdkVersion:|targetSdkVersion:|native-code:|application-label:|launchable-activity:" `
        | ForEach-Object { Write-Host "    $_" }
}

Write-Host "`n    adb install -r `"$apkOut`"" -ForegroundColor Gray
