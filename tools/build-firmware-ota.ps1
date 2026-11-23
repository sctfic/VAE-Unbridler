param([switch]$Flash, [string]$Port)
$ErrorActionPreference = 'Stop'
if (!$env:IDF_PATH) { throw 'Open ESP-IDF PowerShell or load ESP-IDF export.ps1 first.' }
if ($Flash -and !$Port) { throw 'Specify -Port when using -Flash.' }
$root = Split-Path -Parent $PSScriptRoot
$project = Join-Path $root 'firmware-esp32'
$build = Join-Path $project 'build/ota-v1'
$config = Join-Path $project 'build/ota-v1-sdkconfig'
$idf = Join-Path $env:IDF_PATH 'tools/idf.py'
# Apply the release size profile to existing configurations too. SDK defaults
# alone only affect a fresh build; preserve every other local SDK option.
if (Test-Path -LiteralPath $config) {
    $sdkText = [IO.File]::ReadAllText($config)
    $sdkText = [regex]::Replace($sdkText, '(?m)^CONFIG_COMPILER_OPTIMIZATION_(DEBUG|PERF|NONE)=y\r?$', '# CONFIG_COMPILER_OPTIMIZATION_$1 is not set')
    $sdkText = [regex]::Replace($sdkText, '(?m)^# CONFIG_COMPILER_OPTIMIZATION_SIZE is not set\r?$', 'CONFIG_COMPILER_OPTIMIZATION_SIZE=y')
    if ($sdkText -notmatch '(?m)^CONFIG_COMPILER_OPTIMIZATION_SIZE=y\r?$') { $sdkText += "`nCONFIG_COMPILER_OPTIMIZATION_SIZE=y`n" }
    # Pulse edges must continue from IRAM while the OTA task writes flash.
    $sdkText = [regex]::Replace($sdkText, '(?m)^# CONFIG_GPTIMER_ISR_IRAM_SAFE is not set\r?$', 'CONFIG_GPTIMER_ISR_IRAM_SAFE=y')
    if ($sdkText -notmatch '(?m)^CONFIG_GPTIMER_ISR_IRAM_SAFE=y\r?$') { $sdkText += "`nCONFIG_GPTIMER_ISR_IRAM_SAFE=y`n" }
    # Remove diagnostic output while keeping assertions and ESP_ERROR_CHECK active.
    # Keep ROM log defaults: disabling them would permanently burn an eFuse.
    $silentChoices = @{
        'CONFIG_LOG_DEFAULT_LEVEL_' = 'CONFIG_LOG_DEFAULT_LEVEL_NONE'
        'CONFIG_LOG_MAXIMUM_' = 'CONFIG_LOG_MAXIMUM_EQUALS_DEFAULT'
        'CONFIG_BT_NIMBLE_LOG_LEVEL_' = 'CONFIG_BT_NIMBLE_LOG_LEVEL_NONE'
        'CONFIG_BOOTLOADER_LOG_LEVEL_' = 'CONFIG_BOOTLOADER_LOG_LEVEL_NONE'
        'CONFIG_BOOT_ROM_LOG_' = 'CONFIG_BOOT_ROM_LOG_ALWAYS_ON'
        'CONFIG_ESP_CONSOLE_' = 'CONFIG_ESP_CONSOLE_NONE'
        'CONFIG_ESP_SYSTEM_PANIC_' = 'CONFIG_ESP_SYSTEM_PANIC_SILENT_REBOOT'
        'CONFIG_COMPILER_OPTIMIZATION_ASSERTIONS_' = 'CONFIG_COMPILER_OPTIMIZATION_ASSERTIONS_SILENT'
    }
    foreach ($choice in $silentChoices.GetEnumerator()) {
        $sdkText = [regex]::Replace($sdkText, '(?m)^(' + $choice.Key + '[A-Z_]+)=y\r?$', '# $1 is not set')
        $sdkText += "`n$($choice.Value)=y`n"
    }
    $sdkText += "`nCONFIG_ESP_CONSOLE_SECONDARY_NONE=y`nCONFIG_COMPILER_OPTIMIZATION_CHECKS_SILENT=y`n"
    [IO.File]::WriteAllText($config, $sdkText, [Text.UTF8Encoding]::new($false))
}

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
