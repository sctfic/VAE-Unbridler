param(
    [Parameter(Mandatory=$true)][string]$Notes,
    [string]$PackageDir = 'firmware-esp32/build/ota',
    [switch]$Publish
)
$ErrorActionPreference = 'Stop'
$repo = 'sctfic/VAE-Unbridler'
$sha = (git rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Git HEAD unavailable' }
if (git status --porcelain) { throw 'Commit changes before publishing' }
$manifestFile = Get-Item -LiteralPath (Join-Path $PackageDir 'ebike-esp32s3-hw-v1-ota.json')
$binaryFile = Get-Item -LiteralPath (Join-Path $PackageDir 'ebike-esp32s3-hw-v1.bin')
$manifest = Get-Content -LiteralPath $manifestFile.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
if ($manifest.version -notmatch '^\d+\.\d+\.\d+$' -or $manifest.version_code -lt 1 -or
    $manifest.schema -ne 1 -or $manifest.target -ne 'ebike-esp32s3-hw-v1' -or $manifest.ota_protocol -ne 1 -or $manifest.hardware_revision -ne 'V1.0') {
    throw 'Incompatible OTA manifest'
}
$tag = "esp32-hw-v1-v$($manifest.version)"
if ($manifest.download_url -ne "https://github.com/$repo/releases/download/$tag/ebike-esp32s3-hw-v1.bin" -or
    $manifest.size -ne $binaryFile.Length -or $binaryFile.Length -gt 0x1e0000 -or
    $manifest.sha256 -cne (Get-FileHash -LiteralPath $binaryFile.FullName -Algorithm SHA256).Hash.ToLowerInvariant()) {
    throw 'Binary size, URL or SHA-256 does not match the manifest'
}
# Repackage into a temporary directory and compare metadata derived from the actual image.
$verificationDir = Join-Path ([IO.Path]::GetTempPath()) ("ebike-ota-" + [guid]::NewGuid().ToString('N'))
try {
    python tools/package-firmware.py --binary $binaryFile.FullName --output $verificationDir
    if ($LASTEXITCODE -ne 0) { throw 'Firmware image validation failed' }
    $verified = Get-Content -LiteralPath (Join-Path $verificationDir 'ebike-esp32s3-hw-v1-ota.json') -Raw | ConvertFrom-Json
    if ($verified.version -ne $manifest.version -or $verified.version_code -ne $manifest.version_code) { throw 'Compiled firmware identity mismatch' }
} finally {
    # Only remove the unique directory created above, directly inside the temp directory.
    $resolvedVerification = [IO.Path]::GetFullPath($verificationDir)
    $expectedParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
    if ([IO.Path]::GetDirectoryName($resolvedVerification) -ne $expectedParent) { throw 'Unsafe temporary path' }
    if (Test-Path -LiteralPath $resolvedVerification) { Remove-Item -LiteralPath $resolvedVerification -Recurse -Force }
}
$credential = @{}
$headers = @{}
try {
    # Keep credentials in memory; never print the credential-helper response or headers.
    $lines = "protocol=https`nhost=github.com`n`n" | git credential fill
    if ($LASTEXITCODE -ne 0) { throw 'GitHub credentials unavailable' }
    foreach ($line in $lines) {
        $parts = $line -split '=', 2
        if ($parts.Length -eq 2) { $credential[$parts[0]] = $parts[1] }
    }
    if (!$credential['password']) { throw 'GitHub credentials unavailable' }
    $headers = @{ Authorization = "Bearer $($credential['password'])"; Accept = 'application/vnd.github+json'; 'User-Agent' = 'E-BikeCockpit-release' }
    $api = "https://api.github.com/repos/$repo/releases"
    # OTA version codes must be strictly increasing across published firmware releases.
    $existing = Invoke-RestMethod "$api`?per_page=100" -Headers $headers
    foreach ($item in $existing) {
        if ($item.draft -or $item.prerelease -or $item.tag_name -notmatch '^esp32-hw-v1-v\d+\.\d+\.\d+$') { continue }
        $oldAsset = @($item.assets | Where-Object name -eq 'ebike-esp32s3-hw-v1-ota.json')
        if ($oldAsset.Count -eq 1) {
            # Download public metadata without forwarding the authentication token.
            $old = Invoke-RestMethod $oldAsset[0].browser_download_url
            if ($old.version_code -ge $manifest.version_code) { throw 'OTA version code must exceed every published firmware code' }
        }
    }
    $release = $null
    try { $release = Invoke-RestMethod "$api/tags/$tag" -Headers $headers }
    catch { if ([int]$_.Exception.Response.StatusCode -ne 404) { throw 'Unable to inspect release' } }
    if ($release -and !$release.draft) { throw 'Release already published; refusing to overwrite' }
    if (!$release) {
        $payload = @{ tag_name=$tag; target_commitish=$sha; name="ESP32 $($manifest.version)";
            body=(Get-Content -LiteralPath $Notes -Raw -Encoding UTF8); draft=$true; prerelease=$false } | ConvertTo-Json
        $release = Invoke-RestMethod $api -Method Post -Headers $headers -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($payload))
    }
    if ($release.target_commitish -ne $sha) { throw 'Draft targets another commit' }
    foreach ($file in @($binaryFile, $manifestFile)) {
        if (@($release.assets | Where-Object name -eq $file.Name).Count) { throw 'Asset exists; inspect the draft before retrying' }
        $upload = ($release.upload_url -split '\{')[0] + '?name=' + [uri]::EscapeDataString($file.Name)
        $asset = Invoke-RestMethod $upload -Method Post -Headers $headers -ContentType 'application/octet-stream' -InFile $file.FullName
        if ($asset.state -ne 'uploaded' -or $asset.size -ne $file.Length) { throw 'Asset verification failed; release remains draft' }
    }
    if ($Publish) {
        $release = Invoke-RestMethod "$api/$($release.id)" -Method Patch -Headers $headers -ContentType 'application/json' -Body '{"draft":false,"prerelease":false}'
    }
    Write-Output $release.html_url
    if (!$Publish) { Write-Output 'Release remains a draft; publish after bench validation.' }
} finally { $headers.Clear(); $credential.Clear(); $lines = $null }
