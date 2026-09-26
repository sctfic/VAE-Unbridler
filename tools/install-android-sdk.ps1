param(
    [string]$SdkRoot = 'C:\Users\Alban\AppData\Local\Android\Sdk'
)

$ErrorActionPreference = 'Stop'
$archiveUrl = 'https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip'
$expectedHash = '90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a'
$downloadDirectory = Join-Path $PSScriptRoot '.downloads'
$archivePath = Join-Path $downloadDirectory 'commandlinetools-win-latest.zip'
$toolsRoot = Join-Path $SdkRoot 'cmdline-tools\latest'
$sdkManager = Join-Path $toolsRoot 'bin\sdkmanager.bat'

New-Item -ItemType Directory -Path $downloadDirectory -Force | Out-Null
if (-not (Test-Path -LiteralPath $archivePath) -or
    (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedHash) {
    curl.exe -L --fail --retry 3 --output $archivePath $archiveUrl
    if ($LASTEXITCODE -ne 0) { throw 'Android command-line tools download failed.' }
}
if ((Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedHash) {
    throw 'Android command-line tools checksum mismatch.'
}

if (-not (Test-Path -LiteralPath $sdkManager)) {
    New-Item -ItemType Directory -Path $toolsRoot -Force | Out-Null
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($archivePath)
    try {
        foreach ($entry in $archive.Entries) {
            if (-not $entry.FullName.StartsWith('cmdline-tools/')) { continue }
            $relativePath = $entry.FullName.Substring('cmdline-tools/'.Length).Replace('/', '\')
            if ([string]::IsNullOrWhiteSpace($relativePath)) { continue }
            $destination = Join-Path $toolsRoot $relativePath
            if ([string]::IsNullOrEmpty($entry.Name)) {
                New-Item -ItemType Directory -Path $destination -Force | Out-Null
            } else {
                New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
                [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $destination, $true)
            }
        }
    } finally {
        $archive.Dispose()
    }
}

$javaHome = 'C:\Users\Alban\.tools\jdk-17.0.20.1+1'
$env:JAVA_HOME = $javaHome
$env:ANDROID_HOME = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot
[Environment]::SetEnvironmentVariable('ANDROID_HOME', $SdkRoot, 'User')
[Environment]::SetEnvironmentVariable('ANDROID_SDK_ROOT', $SdkRoot, 'User')

$licenseAnswers = ((1..20 | ForEach-Object { 'y' }) -join "`n") + "`n"
$licenseAnswers | & $sdkManager --sdk_root=$SdkRoot --licenses
if ($LASTEXITCODE -ne 0) { throw 'Android SDK license setup failed.' }

& $sdkManager --sdk_root=$SdkRoot 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0'
if ($LASTEXITCODE -ne 0) { throw 'Android SDK package installation failed.' }

Write-Output "Android SDK ready: $SdkRoot"
