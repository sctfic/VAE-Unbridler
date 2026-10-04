param(
    [Parameter(Mandatory=$true)][string]$Tag,
    [Parameter(Mandatory=$true)][string]$Apk,
    [Parameter(Mandatory=$true)][string]$Notes
)
$ErrorActionPreference = 'Stop'
$repo = 'sctfic/VAE-Unbridler'
$sha = (git rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Git HEAD unavailable' }
if (git status --porcelain) { throw 'Commit changes before publishing' }
$apkFile = Get-Item -LiteralPath $Apk
$body = Get-Content -LiteralPath $Notes -Raw -Encoding UTF8
# Keep credentials in memory only; never print headers or credential-helper output.
$credential = @{}
$lines = "protocol=https`nhost=github.com`n`n" | git credential fill
if ($LASTEXITCODE -ne 0) { throw 'GitHub credentials unavailable' }
foreach ($line in $lines) {
    $parts = $line -split '=', 2
    if ($parts.Length -eq 2) { $credential[$parts[0]] = $parts[1] }
}
if (!$credential['password']) { throw 'GitHub credentials unavailable' }
$headers = @{ Authorization = "Bearer $($credential['password'])"; Accept = 'application/vnd.github+json'; 'User-Agent' = 'E-BikeCockpit-release' }
$api = "https://api.github.com/repos/$repo/releases"
$release = $null
try { $release = Invoke-RestMethod "$api/tags/$Tag" -Headers $headers }
catch { if ([int]$_.Exception.Response.StatusCode -ne 404) { throw 'Unable to inspect GitHub release' } }
if ($release -and !$release.draft) { throw 'Published release already exists; refusing to overwrite' }
if (!$release) {
    $payload = @{ tag_name=$Tag; target_commitish=$sha; name="E-BikeCockpit $Tag"; body=$body; draft=$true; prerelease=$true } | ConvertTo-Json
    $release = Invoke-RestMethod $api -Method Post -Headers $headers -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($payload))
}
if ($release.target_commitish -ne $sha) { throw 'Draft targets a different commit' }
$asset = @($release.assets | Where-Object name -eq $apkFile.Name)
if ($asset.Count) { throw 'Draft already contains this APK; inspect before retrying' }
$upload = ($release.upload_url -split '\{')[0] + '?name=' + [uri]::EscapeDataString($apkFile.Name)
$asset = Invoke-RestMethod $upload -Method Post -Headers $headers -ContentType 'application/vnd.android.package-archive' -InFile $apkFile.FullName
if ($asset.state -ne 'uploaded' -or $asset.size -ne $apkFile.Length) { throw 'APK upload verification failed; release remains draft' }
$release = Invoke-RestMethod "$api/$($release.id)" -Method Patch -Headers $headers -ContentType 'application/json' -Body '{"draft":false}'
# Verify the public download before removing any older APK.
$verificationFile = Join-Path ([IO.Path]::GetTempPath()) ("ebike-release-" + [guid]::NewGuid() + ".apk")
try {
    Invoke-WebRequest $asset.browser_download_url -OutFile $verificationFile -MaximumRetryCount 3 -RetryIntervalSec 5
    if ((Get-FileHash -LiteralPath $verificationFile -Algorithm SHA256).Hash -ne
        (Get-FileHash -LiteralPath $apkFile.FullName -Algorithm SHA256).Hash) {
        throw 'Published APK hash mismatch; older APKs retained'
    }
} finally {
    if (Test-Path -LiteralPath $verificationFile) { Remove-Item -LiteralPath $verificationFile }
}
# Retain only the newly published APK; keep old tags and release notes.
$page = 1
$oldAssets = @()
do {
    $batch = Invoke-RestMethod "$api`?per_page=100&page=$page" -Headers $headers
    foreach ($entry in $batch) {
        if ($entry.id -eq $release.id -or $entry.draft) { continue }
        $oldAssets += @($entry.assets | Where-Object { $_.name -match '^E-Bike.*\.apk$' })
    }
    $page++
} while ($batch.Count -eq 100)
foreach ($oldAsset in $oldAssets) {
    Invoke-RestMethod "$api/assets/$($oldAsset.id)" -Method Delete -Headers $headers | Out-Null
}
Write-Output $release.html_url
Write-Output $asset.browser_download_url
$headers.Clear(); $credential.Clear(); $lines = $null
