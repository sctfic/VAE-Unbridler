# Mechanical density export only; the artwork is generated with ImageGen.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$projectRoot = Split-Path $PSScriptRoot -Parent
$sourcePath = Join-Path $projectRoot 'design/icon-candidates/03-guidon-relief-v3.png'
$sourceImage = [System.Drawing.Image]::FromFile($sourcePath)
function Export-Icon([string]$destination, [int]$size, [double]$fraction = 1.0) {
    [System.IO.Directory]::CreateDirectory((Split-Path $destination -Parent)) | Out-Null
    $bitmap = New-Object System.Drawing.Bitmap($size, $size)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.Clear([System.Drawing.Color]::FromArgb(255, 0, 16, 38))
        $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $extent = [int][Math]::Round($size * $fraction)
        $offset = [int][Math]::Round(($size - $extent) / 2)
        $graphics.DrawImage($sourceImage, $offset, $offset, $extent, $extent)
        $bitmap.Save($destination, [System.Drawing.Imaging.ImageFormat]::Png)
    } finally { $graphics.Dispose(); $bitmap.Dispose() }
}
try {
    $densities = @{mdpi=48; hdpi=72; xhdpi=96; xxhdpi=144; xxxhdpi=192}
    foreach ($density in $densities.Keys) {
        $folder = Join-Path $projectRoot "android-app/app/src/main/res/mipmap-$density"
        Export-Icon (Join-Path $folder 'ic_launcher.png') $densities[$density]
        # 66 of 108 dp keeps the important artwork inside adaptive masks.
        Export-Icon (Join-Path $folder 'ic_launcher_foreground.png') ([int]($densities[$density] * 2.25)) (66.0/108.0)
    }
    foreach ($size in @(32, 48, 64, 96, 128, 192, 256, 512)) {
        Export-Icon (Join-Path $projectRoot "design/icons/ebikecockpit-$size.png") $size
    }
} finally { $sourceImage.Dispose() }
