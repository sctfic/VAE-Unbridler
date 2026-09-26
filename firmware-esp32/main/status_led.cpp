#include "status_led.hpp"

#include <atomic>
#include "driver/rmt_tx.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "telemetry_store.hpp"

namespace ebike::status_led {
namespace {
std::atomic<bool> initialized{false}, bluetooth{false}, failed{false};
constexpr uint32_t kPurple = 0x100010, kBlue = 0x000018;
constexpr uint32_t kYellow = 0x101000, kGreen = 0x001800;
constexpr uint32_t kOrange = 0x180600, kRed = 0x180000;

void task(void* context) {
    auto& telemetry = *static_cast<TelemetryStore*>(context);
    rmt_channel_handle_t channel = nullptr;
    rmt_encoder_handle_t encoder = nullptr;
    rmt_tx_channel_config_t config{};
    config.gpio_num = static_cast<gpio_num_t>(kStatusLedPin);
    config.clk_src = RMT_CLK_SRC_DEFAULT;
    config.resolution_hz = 10000000;
    config.mem_block_symbols = 64;
    config.trans_queue_depth = 1;
    const rmt_copy_encoder_config_t copy_config{};
    esp_err_t result = rmt_new_tx_channel(&config, &channel);
    if (result == ESP_OK) result = rmt_new_copy_encoder(&copy_config, &encoder);
    if (result == ESP_OK) result = rmt_enable(channel);
    if (result != ESP_OK) {
        ESP_LOGE("status_led", "LED initialization failed: %s", esp_err_to_name(result));
        if (encoder) rmt_del_encoder(encoder);
        if (channel) rmt_del_channel(channel);
        vTaskDelete(nullptr);
        return;
    }

    const int64_t started = esp_timer_get_time();
    uint32_t previous = UINT32_MAX;
    // Buffer lives for the entire task, including any pending RMT transaction.
    rmt_symbol_word_t symbols[25]{};
    while (true) {
        const int64_t elapsed = esp_timer_get_time() - started;
        const bool first_half = (elapsed / 500000) % 2 == 0;
        const auto state = telemetry.snapshot();
        uint32_t color;
        if (failed.load()) color = first_half ? kRed : 0;
        else if (!initialized.load() || elapsed < 1000000) color = kPurple;
        else if (bluetooth.load()) {
            color = !state.wheel_moving || first_half ? kBlue :
                (state.simulated_output ? kOrange : kGreen);
        } else if (state.wheel_moving) {
            color = state.simulated_output ? kOrange : kGreen;
        } else color = first_half ? kYellow : 0;

        if (color != previous) {
            // WS2812: GRB, most significant bit first, 0.4/0.8 us pulses.
            const uint32_t grb = ((color & 0x00ff00) << 8) |
                                 ((color & 0xff0000) >> 8) | (color & 0xff);
            for (int bit = 0; bit < 24; ++bit) {
                const bool one = (grb & (1UL << (23 - bit))) != 0;
                symbols[bit].level0 = 1;
                symbols[bit].duration0 = one ? 8 : 4;
                symbols[bit].level1 = 0;
                symbols[bit].duration1 = one ? 4 : 8;
            }
            // 300 us reset/latch, supports longer-reset WS2812 revisions.
            symbols[24].level0 = symbols[24].level1 = 0;
            symbols[24].duration0 = symbols[24].duration1 = 1500;
            const rmt_transmit_config_t transmit{};
            result = rmt_transmit(channel, encoder, symbols, sizeof(symbols), &transmit);
            if (result == ESP_OK) result = rmt_tx_wait_all_done(channel, -1);
            if (result != ESP_OK) {
                ESP_LOGE("status_led", "LED transmission failed: %s", esp_err_to_name(result));
                vTaskDelete(nullptr);
                return;
            }
            previous = color;
        }
        vTaskDelay(pdMS_TO_TICKS(50));
    }
}
}

void begin(TelemetryStore& telemetry) {
    if (xTaskCreatePinnedToCore(task, "status_led", 3072, &telemetry, 2, nullptr, 0) != pdPASS) {
        ESP_LOGE("status_led", "Unable to create LED task");
    }
}
void ready() { initialized.store(true); }
void connected(bool value) { bluetooth.store(value); }
void fault() { failed.store(true); }
}
