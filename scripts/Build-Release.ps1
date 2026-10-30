<#
.SYNOPSIS
    Builds the files people download, into dist\: the Windows app as a zip and the phone app as a signed APK.

.DESCRIPTION
    Windows: publishes the desktop app and the server it starts into one folder, self-contained for 64-bit Windows (the
    PC needs no .NET installed), and zips it as dist\MobileController-Windows-x64.zip. People extract it and run
    MobileController.exe.

    Android: builds the release APK as dist\MobileController.apk, signed with the key that android\keystore.properties
    names. Phones refuse to install an unsigned APK, so the script stops when that file is missing (README, "Making a
    release").

    Needs the .NET 10 SDK and, for the APK, a JDK 17 or newer and the Android SDK. Android Studio includes both; the
    script uses Android Studio's when JAVA_HOME and ANDROID_HOME are not set.

.PARAMETER Version
    The version to give both apps, as major.minor.patch (for example 1.2.0). The release workflow passes its tag.
    Leave it out to keep the versions in the project files.

.PARAMETER SkipWindows
    Builds only the APK.

.PARAMETER SkipAndroid
    Builds only the Windows zip.

.EXAMPLE
    # From the repository root:
    powershell -ExecutionPolicy Bypass -File scripts\Build-Release.ps1 -Version 1.0.0
    powershell -ExecutionPolicy Bypass -File scripts\Build-Release.ps1 -SkipAndroid
#>
[CmdletBinding()]
param(
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string]$Version,
    [switch]$SkipWindows,
    [switch]$SkipAndroid
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$dist = Join-Path $root 'dist'
New-Item -ItemType Directory -Force $dist | Out-Null

if (-not $SkipWindows) {
    $app = Join-Path $dist 'MobileController-Windows-x64'
    if (Test-Path $app) { Remove-Item $app -Recurse -Force }
    $dotnetVersion = if ($Version) { @("-p:Version=$Version") } else { @() }
    # The desktop app starts MobileController.Server.exe from its own folder, so both are published into one.
    foreach ($project in 'MobileController.Desktop', 'MobileController.Server') {
        dotnet publish (Join-Path $root "server\src\$project") -c Release --self-contained -o $app @dotnetVersion
        if ($LASTEXITCODE) { throw "Publishing $project failed." }
    }
    Compress-Archive -Path (Join-Path $app '*') -DestinationPath "$app.zip" -Force
    Write-Host "Windows app: $app.zip" -ForegroundColor Green
}

if (-not $SkipAndroid) {
    $android = Join-Path $root 'android'
    if (-not (Test-Path (Join-Path $android 'keystore.properties'))) {
        throw 'android\keystore.properties is missing, and phones refuse to install an unsigned APK. Create the signing key first (README, "Making a release").'
    }
    # Fall back on Android Studio's JDK and SDK when nothing else points Gradle at one.
    $studioJdk = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
    if (-not $env:JAVA_HOME -and -not (Get-Command java -ErrorAction SilentlyContinue) -and (Test-Path $studioJdk)) {
        $env:JAVA_HOME = $studioJdk
    }
    $studioSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    if (-not $env:ANDROID_HOME -and -not (Test-Path (Join-Path $android 'local.properties')) -and (Test-Path $studioSdk)) {
        $env:ANDROID_HOME = $studioSdk
    }
    $gradleVersion = if ($Version) { @("-PreleaseVersion=$Version") } else { @() }
    & (Join-Path $android 'gradlew.bat') -p $android :app:assembleRelease @gradleVersion
    if ($LASTEXITCODE) { throw 'Building the APK failed.' }
    $apk = Join-Path $dist 'MobileController.apk'
    Copy-Item (Join-Path $android 'app\build\outputs\apk\release\app-release.apk') $apk -Force
    Write-Host "Phone app:   $apk" -ForegroundColor Green
}
