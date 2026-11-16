#include "ble_service.hpp"
#include "config_store.hpp"
#include "pulse_generator.hpp"
#include "telemetry_store.hpp"
#include "wheel_capture.hpp"
#include "status_led.hpp"
#include "firmware_version.hpp"

#include "esp_log.h"
#include "esp_ota_ops.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "nvs_flash.h"

namespace {
void boot_guard(void*) {
    // Independent of NimBLE: initialization may fail before its notifier starts.
    vTaskDelay(pdMS_TO_TICKS(45'000));
    esp_ota_img_states_t state;
    if (esp_ota_get_state_partition(esp_ota_get_running_partition(), &state) == ESP_OK &&
        state == ESP_OTA_IMG_PENDING_VERIFY) {
        ESP_ERROR_CHECK(esp_ota_mark_app_invalid_rollback_and_reboot());
    }
    vTaskDelete(nullptr);
}
}  // namespace

extern "C" void app_main(void) {
    const BaseType_t guard_started = xTaskCreate(&boot_guard, "ota_boot_guard", 3072, nullptr, 3, nullptr);
    configASSERT(guard_started == pdPASS);
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

    ESP_LOGI("main", "Firmware identity: %s", ebike::kFirmwareOtaIdentity);

    ESP_LOGI("main", "E-Bike firmware ready: GPIO5 wheel, GPIO6 open-drain motor signal (hardware V1.0)");
}
