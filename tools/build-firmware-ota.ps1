param([switch]$Flash, [string]$Port)
$ErrorActionPreference = 'Stop'
if (!$env:IDF_PATH) { throw 'Open ESP-IDF PowerShell or load ESP-IDF export.ps1 first.' }
if ($Flash -and !$Port) { throw 'Specify -Port when using -Flash.' }
$root = Split-Path -Parent $PSScriptRoot
$project = Join-Path $root 'firmware-esp32'
$build = Join-Path $project 'build/ota-v1'
$config = Join-Path $project 'build/ota-v1-sdkconfig'
$idf = Join-Path $env:IDF_PATH 'tools/idf.py'
# A separate configuration applies the OTA defaults even if the former single-app
# build has a 2MB sdkconfig. The old build and its local settings remain available.
$common = @('-C', $project, '-B', $build, '-D', "SDKCONFIG=$config")
python $idf @common build
if ($LASTEXITCODE -ne 0) { throw 'Hardware V1 OTA firmware build failed.' }
python (Join-Path $PSScriptRoot 'package-firmware.py') --binary (Join-Path $build 'ebike_firmware_hw_v1.bin') --output (Join-Path $project 'build/ota')
if ($LASTEXITCODE -ne 0) { throw 'OTA image verification/package failed.' }
if ($Flash) {
    # Flash only bootloader, partition table, OTA metadata and application, preserving NVS.
    python $idf @common -p $Port flash
    if ($LASTEXITCODE -ne 0) { throw 'USB flash failed.' }
}
