param([string]$VcVars = 'C:/Program Files (x86)/Microsoft Visual Studio/2019/BuildTools/VC/Auxiliary/Build/vcvars64.bat')
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (!(Get-Command cl.exe -ErrorAction SilentlyContinue)) {
    if (!(Test-Path -LiteralPath $VcVars)) { throw 'Run from a Visual C++ developer shell or provide -VcVars.' }
    # Import the compiler environment; no filesystem changes are delegated to cmd.exe.
    $compilerEnvironment = & cmd.exe /d /c ('call "' + $VcVars.Replace('/', '\') + '" >nul && set')
    if ($LASTEXITCODE -ne 0) { throw 'Unable to load the Visual C++ environment.' }
    foreach ($line in $compilerEnvironment) {
        if ($line -match '^([^=]+)=(.*)$') { [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process') }
    }
}
$build = Join-Path $root 'firmware-esp32/build/host-tests'
New-Item -ItemType Directory -Force -Path $build | Out-Null
Push-Location $build
try {
    foreach ($name in @('speed_model', 'wheel_period_filter', 'turbo_confirmation', 'pulse_generator', 'ota_policy', 'ota_stream_buffer', 'ota_service')) {
        $arguments = @('/nologo', '/EHsc', '/std:c++17', '/W4', '/D_CRT_SECURE_NO_WARNINGS',
            "/I$root/firmware-esp32/tests/host", "/I$root/firmware-esp32/main/include",
            "$root/firmware-esp32/tests/${name}_test.cpp", "/Fe:${name}_test.exe")
        if ($name -eq 'pulse_generator') {
            $arguments = @("/I$root/firmware-esp32/tests/pulse-host") + $arguments + @(
                "$root/firmware-esp32/main/pulse_generator.cpp", "$root/firmware-esp32/main/telemetry_store.cpp")
        }
        if ($name -eq 'ota_service') { $arguments += @("$root/firmware-esp32/main/ota_service.cpp", "$root/firmware-esp32/main/telemetry_store.cpp", 'bcrypt.lib') }
        & cl.exe @arguments
        if ($LASTEXITCODE -ne 0) { throw "Host test compilation failed: $name" }
        & (Join-Path $build "${name}_test.exe")
        if ($LASTEXITCODE -ne 0) { throw "Host test failed: $name" }
        Write-Output "PASS $name"
    }
} finally { Pop-Location }
