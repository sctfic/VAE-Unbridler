$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$config = Get-Content (Join-Path $repoRoot 'android-app/app/build.gradle.kts') -Raw
if ($config -notmatch 'versionName = "([0-9.]+)"') { throw 'Version unavailable' }
$version = $Matches[1]
$source = Join-Path $repoRoot 'android-app/app/build/outputs/apk/release/app-release.apk'
$folder = (Resolve-Path (Join-Path $repoRoot 'releases')).Path
$destination = Join-Path $folder "E-BikeCockpit-$version.apk"
Copy-Item -LiteralPath $source -Destination $destination
Get-ChildItem -LiteralPath $folder -Filter '*.apk' -File | Where-Object FullName -ne $destination | ForEach-Object {
    if ($_.DirectoryName -ne $folder) { throw 'APK outside releases directory' }
    Remove-Item -LiteralPath $_.FullName
}
$hash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
"$hash  E-BikeCockpit-$version.apk" | Set-Content -LiteralPath (Join-Path $folder 'SHA256SUMS.txt') -Encoding utf8
Write-Output $destination
