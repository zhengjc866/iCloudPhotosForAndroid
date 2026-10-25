<#
.SYNOPSIS
    One-shot Android build for this machine's self-contained toolchain.

.DESCRIPTION
    Configures JAVA_HOME / ANDROID_HOME / GRADLE_USER_HOME for the toolchain
    installed under <repo>\.toolchain, then runs a Gradle task against the
    given project root. Prints the resulting APK path(s) when present.

    Everything lives under the repo: the machine's user profile directory
    (~/.gradle, %TEMP%) is not writable for JVM processes in this environment,
    so the Gradle cache, Android user home and temp files are redirected into
    .toolchain\ as well.

.PARAMETER Task
    Gradle task name(s). Default: assembleDebug.

.PARAMETER ProjectRoot
    Android project root (directory containing settings.gradle[.kts]).
    Default: the repo root (parent of this script's scripts\ directory).

.PARAMETER NoDaemon
    Pass --no-daemon to Gradle (slower, but leaves no background process).

.EXAMPLE
    pwsh -File scripts\build.ps1
    # -> .toolchain\probe\app\build\outputs\apk\debug\app-debug.apk

.EXAMPLE
    pwsh -File scripts\build.ps1 -Task assembleRelease
    pwsh -File scripts\build.ps1 -Task clean,assembleDebug -ProjectRoot .toolchain\probe
#>
[CmdletBinding()]
param(
    [string[]]$Task = @('assembleDebug'),
    [string]$ProjectRoot,
    [switch]$NoDaemon
)

$ErrorActionPreference = 'Stop'

$RepoRoot = Split-Path -Parent $PSScriptRoot
if (-not $ProjectRoot) { $ProjectRoot = $RepoRoot }
$ProjectRoot = (Resolve-Path -LiteralPath $ProjectRoot).Path

$Toolchain   = Join-Path $RepoRoot '.toolchain'
$JdkHome     = Join-Path $Toolchain 'jdk'
$GradleHome  = Join-Path $Toolchain 'gradle'
$AndroidSdk  = Join-Path $Toolchain 'android-sdk'
$GradleUser  = Join-Path $Toolchain 'gradle-home'
$AndroidUser = Join-Path $Toolchain 'android-user-home'
$TmpDir      = Join-Path $Toolchain 'tmp'

foreach ($p in @($JdkHome, $GradleHome, $AndroidSdk)) {
    if (-not (Test-Path -LiteralPath $p)) {
        throw "Toolchain component missing: $p. Run the toolchain install first."
    }
}
foreach ($p in @($GradleUser, $AndroidUser, $TmpDir)) {
    New-Item -ItemType Directory -Force -Path $p | Out-Null
}

# --- environment (process-local only; system env vars are left untouched) ---
$env:JAVA_HOME        = $JdkHome
$env:ANDROID_HOME     = $AndroidSdk
$env:ANDROID_SDK_ROOT = $AndroidSdk
$env:ANDROID_USER_HOME = $AndroidUser
$env:GRADLE_USER_HOME = $GradleUser
$env:TMP  = $TmpDir
$env:TEMP = $TmpDir
$env:PATH = "$JdkHome\bin;$AndroidSdk\platform-tools;$AndroidSdk\cmdline-tools\latest\bin;$env:PATH"

# Every JVM launched by this build (Gradle daemon, Kotlin daemon, workers)
# must keep its temp files inside the workspace: %TEMP% is not writable here.
$env:JAVA_TOOL_OPTIONS = "-Djava.io.tmpdir=$TmpDir"
$env:GRADLE_OPTS       = "-Djava.io.tmpdir=$TmpDir"

$GradleBat = Join-Path $GradleHome 'bin\gradle.bat'
if (-not (Test-Path -LiteralPath $GradleBat)) { throw "gradle.bat not found: $GradleBat" }

Write-Host "JAVA_HOME   : $env:JAVA_HOME"
Write-Host "ANDROID_HOME: $env:ANDROID_HOME"
Write-Host "GRADLE_USER_HOME: $env:GRADLE_USER_HOME"
Write-Host "ProjectRoot : $ProjectRoot"
Write-Host "Task        : $($Task -join ' ')"
Write-Host ''

# A .cmd wrapper (and `powershell -File`) passes "-Task clean,assembleDebug" as
# one literal string, so split on commas/semicolons/whitespace into real
# Gradle task names / CLI options.
$taskArgs = @($Task | ForEach-Object { ([string]$_).Trim() -split '[,;\s]+' } | Where-Object { $_ })

$gradleArgs = @('-p', $ProjectRoot, '--console=plain')
if ($NoDaemon) { $gradleArgs += '--no-daemon' }
$gradleArgs += $taskArgs

$sw = [System.Diagnostics.Stopwatch]::StartNew()
& $GradleBat @gradleArgs
$code = $LASTEXITCODE
$sw.Stop()

if ($code -ne 0) {
    throw "Gradle failed with exit code $code (task: $($Task -join ' '), elapsed $([math]::Round($sw.Elapsed.TotalSeconds,1))s)"
}

Write-Host ''
Write-Host "Gradle OK in $([math]::Round($sw.Elapsed.TotalSeconds,1))s"
Write-Host ''

$apks = @(Get-ChildItem -Path (Join-Path $ProjectRoot '*\build\outputs\apk') -Recurse -Filter '*.apk' -ErrorAction SilentlyContinue)
$apks = @($apks | Sort-Object LastWriteTime -Descending)
if ($apks.Count -eq 0) {
    Write-Output 'APK: (none found - requested task did not produce an APK)'
} else {
    foreach ($a in $apks) {
        Write-Output "APK: $($a.FullName) ($([math]::Round($a.Length/1KB,1)) KB)"
    }
}
