#include "ble_service.hpp"
#include "config_store.hpp"
#include "pulse_generator.hpp"
#include "telemetry_store.hpp"
#include "wheel_capture.hpp"

#include "esp_log.h"
#include "nvs_flash.h"

extern "C" void app_main(void) {
    ESP_ERROR_CHECK(nvs_flash_init());

    static ebike::ConfigStore config;
    static ebike::TelemetryStore telemetry;
    static ebike::PulseGenerator pulse(telemetry);
    static ebike::WheelCapture wheel(config, telemetry, pulse);
    static ebike::BleService ble(config, telemetry);

    config.begin();
    pulse.begin();
    wheel.begin();
    ble.begin();

    ESP_LOGI("main", "E-Bike firmware ready: GPIO5 wheel, GPIO6 isolated motor contact");
}
