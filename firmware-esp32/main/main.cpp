#include "ble_service.hpp"
#include "config_store.hpp"
#include "pulse_generator.hpp"
#include "telemetry_store.hpp"
#include "wheel_capture.hpp"
#include "status_led.hpp"

#include "esp_log.h"
#include "nvs_flash.h"

extern "C" void app_main(void) {
    static ebike::ConfigStore config;
    static ebike::TelemetryStore telemetry;
    ebike::status_led::begin(telemetry);
    const esp_err_t nvs_result = nvs_flash_init();
    if (nvs_result != ESP_OK) {
        ESP_LOGE("main", "NVS initialization failed: %s", esp_err_to_name(nvs_result));
        ebike::status_led::fault();
        return;
    }
    static ebike::PulseGenerator pulse(telemetry);
    static ebike::WheelCapture wheel(config, telemetry, pulse);
    static ebike::BleService ble(config, telemetry);

    config.begin();
    pulse.begin();
    wheel.begin();
    ble.begin();

    ESP_LOGI("main", "E-Bike firmware ready: GPIO5 wheel, GPIO6 isolated motor contact");
}
