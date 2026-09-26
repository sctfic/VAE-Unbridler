param(
    [string]$ToolsDirectory = 'C:\Users\Alban\.tools',
    [int]$Connections = 16
)

$ErrorActionPreference = 'Stop'
$downloadUrl = 'https://edgedl.me.gvt1.com/android/studio/ide-zips/2026.1.4.7/android-studio-quail4-windows.zip'
$expectedLength = 1493383287L
$expectedHash = 'fabc885015bd67182f4f49da8e3efbb3b60038b2dfda632cf2d29363ffb1e04e'
$downloadDirectory = Join-Path $ToolsDirectory 'android-studio-quail4-download'
$archivePath = Join-Path $downloadDirectory 'android-studio-quail4-windows.zip'
$studioDirectory = Join-Path $ToolsDirectory 'android-studio'
$studioExecutable = Join-Path $studioDirectory 'bin\studio64.exe'

if (Test-Path -LiteralPath $studioDirectory) {
    throw "The target already exists; it will not be overwritten: $studioDirectory"
}
New-Item -ItemType Directory -Path $downloadDirectory -Force | Out-Null
$partLength = [long][math]::Ceiling($expectedLength / $Connections)
$downloads = @()
for ($index = 0; $index -lt $Connections; $index++) {
    $startByte = $index * $partLength
    $endByte = [math]::Min($expectedLength - 1, $startByte + $partLength - 1)
    $partPath = Join-Path $downloadDirectory ('part-{0:D2}.bin' -f $index)
    $logPath = Join-Path $downloadDirectory ('part-{0:D2}.log' -f $index)
    $requiredLength = $endByte - $startByte + 1
    if ((Test-Path -LiteralPath $partPath) -and (Get-Item -LiteralPath $partPath).Length -eq $requiredLength) {
        $downloads += [pscustomobject]@{ Process = $null; Path = $partPath; Length = $requiredLength; Log = $logPath }
        continue
    }
    $arguments = @('-L', '--fail', '--retry', '3', '--silent', '--show-error', '--range', "$startByte-$endByte", '--output', $partPath, $downloadUrl)
    $process = Start-Process -FilePath 'curl.exe' -ArgumentList $arguments -WindowStyle Hidden -PassThru -RedirectStandardError $logPath
    $downloads += [pscustomobject]@{ Process = $process; Path = $partPath; Length = $requiredLength; Log = $logPath }
}

while (@($downloads | Where-Object { $_.Process -and -not $_.Process.HasExited }).Count -gt 0) {
    $receivedBytes = 0L
    foreach ($download in $downloads) {
        if (Test-Path -LiteralPath $download.Path) { $receivedBytes += (Get-Item -LiteralPath $download.Path).Length }
    }
    Write-Output ('Download: {0:N1}% ({1:N0} MB / {2:N0} MB)' -f (100 * $receivedBytes / $expectedLength), ($receivedBytes / 1MB), ($expectedLength / 1MB))
    Start-Sleep -Seconds 10
}
foreach ($download in $downloads) {
    if ($download.Process) {
        $download.Process.WaitForExit()
        if ($download.Process.ExitCode -ne 0) {
            throw "Download failed: $(Get-Content -LiteralPath $download.Log -Raw)"
        }
    }
    if ((Get-Item -LiteralPath $download.Path).Length -ne $download.Length) {
        throw "Incorrect part size: $($download.Path)"
    }
}

Write-Output 'Combining downloaded parts...'
$archiveStream = [IO.File]::Create($archivePath)
try {
    foreach ($download in $downloads) {
        $partStream = [IO.File]::OpenRead($download.Path)
        try { $partStream.CopyTo($archiveStream) } finally { $partStream.Dispose() }
    }
} finally { $archiveStream.Dispose() }

Write-Output 'Verifying the official SHA-256 checksum...'
if ((Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedHash) {
    throw 'Checksum mismatch. Android Studio will not be installed.'
}
Write-Output 'Extracting Android Studio...'
Expand-Archive -LiteralPath $archivePath -DestinationPath $ToolsDirectory
if (-not (Test-Path -LiteralPath $studioExecutable)) { throw 'The Android Studio executable is missing.' }

$shortcutShell = New-Object -ComObject WScript.Shell
$shortcutDirectories = @(
    [Environment]::GetFolderPath('Desktop'),
    [Environment]::GetFolderPath('Programs')
)
foreach ($shortcutDirectory in $shortcutDirectories) {
    $shortcutPath = Join-Path $shortcutDirectory 'Android Studio.lnk'
    if (-not (Test-Path -LiteralPath $shortcutPath)) {
        $shortcut = $shortcutShell.CreateShortcut($shortcutPath)
        $shortcut.TargetPath = $studioExecutable
        $shortcut.WorkingDirectory = $studioDirectory
        $shortcut.Description = 'Android Studio Quail 4'
        $shortcut.IconLocation = "$studioExecutable,0"
        $shortcut.Save()
    }
}
Write-Output "Android Studio installed: $studioExecutable"
Write-Output 'Desktop and Start menu shortcuts are ready.'
