#include "status_led.hpp"

#include <atomic>

#include "driver/gpio.h"
#include "ebike_types.hpp"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

namespace ebike::status_led {
namespace {
std::atomic<bool> bluetooth{false}, failed{false};

void task(void*) {
    bool previous = false;
    while (true) {
        const bool fault_phase = (esp_timer_get_time() / 250000) % 2 == 0;
        const bool lit = failed.load() ? fault_phase : bluetooth.load();
        if (lit != previous) {
            gpio_set_level(static_cast<gpio_num_t>(kStatusLedPin), lit ? 1 : 0);
            previous = lit;
        }
        vTaskDelay(pdMS_TO_TICKS(50));
    }
}
}  // namespace

void begin(TelemetryStore&) {
    gpio_config_t output{};
    output.pin_bit_mask = 1ULL << kStatusLedPin;
    output.mode = GPIO_MODE_OUTPUT;
    output.pull_down_en = GPIO_PULLDOWN_DISABLE;
    output.pull_up_en = GPIO_PULLUP_DISABLE;
    output.intr_type = GPIO_INTR_DISABLE;
    ESP_ERROR_CHECK(gpio_config(&output));
    gpio_set_level(static_cast<gpio_num_t>(kStatusLedPin), 0);

    if (xTaskCreatePinnedToCore(task, "status_led", 2048, nullptr, 2, nullptr, 0) != pdPASS) {
        ESP_LOGE("status_led", "Unable to create LED task");
    }
}

void ready() {}
void connected(bool value) { bluetooth.store(value); }
void fault() { failed.store(true); }
}
