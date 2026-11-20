# Build / install the native Android app.
# Usage:
#   .\build.ps1
#   .\build.ps1 -Install
#   .\build.ps1 -Install -Device 127.0.0.1:5555
param(
    [switch]$Install,
    [string]$Device = ""
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'

if (-not (Test-Path (Join-Path $root 'local.properties'))) {
    Copy-Item (Join-Path $root 'local.properties.example') (Join-Path $root 'local.properties')
}

Push-Location $root
try {
    & (Join-Path $root 'gradlew.bat') assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "gradle assembleDebug failed (exit $LASTEXITCODE)" }
} finally {
    Pop-Location
}

if (-not (Test-Path $apk)) { throw "APK not found: $apk" }
Write-Host ("[+] " + $apk + " " + (Get-Item $apk).Length + " bytes")

if ($Install) {
    $adb = if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe' } else { 'adb' }
    if ($Device -ne '') { & $adb -s $Device install -r $apk } else { & $adb install -r $apk }
    if ($LASTEXITCODE -ne 0) { throw "adb install failed (exit $LASTEXITCODE)" }
}