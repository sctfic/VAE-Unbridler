#include "wheel_capture.hpp"

#include <algorithm>

#include "driver/gpio.h"
#include "esp_attr.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "speed_model.hpp"

namespace ebike {
namespace {
constexpr char kTag[] = "wheel";
constexpr int64_t kDebounceUs = 4000;
constexpr int64_t kMinimumIntervalUs = 30000;     // 283 km/h with a 2.36 m wheel
constexpr int64_t kMaximumIntervalUs = 15'000'000;
constexpr TickType_t kTaskWaitTicks = pdMS_TO_TICKS(100);
}  // namespace

WheelCapture::WheelCapture(ConfigStore& config, TelemetryStore& telemetry, PulseGenerator& pulse)
    : config_(config), telemetry_(telemetry), pulse_(pulse) {}

void WheelCapture::begin() {
    edge_queue_ = xQueueCreate(16, sizeof(int64_t));
    configASSERT(edge_queue_ != nullptr);

    gpio_config_t input{};
    input.pin_bit_mask = 1ULL << kWheelSensorPin;
    input.mode = GPIO_MODE_INPUT;
    input.pull_up_en = GPIO_PULLUP_ENABLE;
    input.pull_down_en = GPIO_PULLDOWN_DISABLE;
    // A reed switch connected to local GND produces a falling edge when it closes.
    input.intr_type = GPIO_INTR_NEGEDGE;
    ESP_ERROR_CHECK(gpio_config(&input));
    ESP_ERROR_CHECK(gpio_install_isr_service(ESP_INTR_FLAG_IRAM));
    ESP_ERROR_CHECK(gpio_isr_handler_add(static_cast<gpio_num_t>(kWheelSensorPin),
                                         &WheelCapture::gpio_isr, this));

    BaseType_t created = xTaskCreatePinnedToCore(&WheelCapture::task_entry, "wheel_rt", 4096, this,
                                                  configMAX_PRIORITIES - 2, nullptr, 1);
    configASSERT(created == pdPASS);
    ESP_LOGI(kTag, "GPIO %d ready on real-time core", kWheelSensorPin);
}

void IRAM_ATTR WheelCapture::gpio_isr(void* context) {
    auto* self = static_cast<WheelCapture*>(context);
    const int64_t timestamp_us = esp_timer_get_time();
    BaseType_t higher_priority_task_woken = pdFALSE;
    xQueueSendFromISR(self->edge_queue_, &timestamp_us, &higher_priority_task_woken);
    if (higher_priority_task_woken == pdTRUE) {
        portYIELD_FROM_ISR();
    }
}

void WheelCapture::task_entry(void* context) {
    static_cast<WheelCapture*>(context)->task();
}

void WheelCapture::task() {
    while (true) {
        const bool speed_mode = config_.speed_enabled();
        if (!speed_mode && telemetry_.snapshot().simulated_output) {
            // Cancel synthetic deadlines on the real-time core, never from BLE.
            pulse_.stop();
            telemetry_.update_motor(0, 0, false);
        }
        int64_t edge_us = 0;
        if (xQueueReceive(edge_queue_, &edge_us, kTaskWaitTicks) == pdTRUE) {
            handle_edge(edge_us);
            continue;
        }

        if (!stopped_ && last_edge_us_ != 0) {
            const int64_t silence_us = esp_timer_get_time() - last_edge_us_;
            const int64_t stop_after_us = std::max<int64_t>(1'500'000, last_interval_us_ * 3LL);
            if (silence_us > stop_after_us) {
                mark_stopped();
            }
        }
    }
}

void WheelCapture::handle_edge(int64_t edge_us) {
    if (last_edge_us_ == 0) {
        last_edge_us_ = edge_us;
        stopped_ = false;
        if (!config_.speed_enabled()) pulse_.emit_passthrough(0, 0);
        return;
    }

    const int64_t interval_us = edge_us - last_edge_us_;
    if (interval_us < kDebounceUs) {
        return;
    }
    last_edge_us_ = edge_us;
    if (interval_us < kMinimumIntervalUs || interval_us > kMaximumIntervalUs) {
        return;
    }

    last_interval_us_ = static_cast<uint32_t>(interval_us);
    ++revolutions_;
    stopped_ = false;

    const RuntimeConfig config = config_.snapshot();
    const SpeedPlan plan = make_speed_plan(config.circumference_mm, config.threshold_centi_kmh,
                                           last_interval_us_, config_.speed_enabled());
    telemetry_.update_wheel(last_interval_us_, plan.wheel_speed_centi_kmh, revolutions_,
                            config.circumference_mm);

    if (!plan.simulated) {
        pulse_.emit_passthrough(plan.motor_interval_us, plan.motor_speed_centi_kmh);
        return;
    }
    pulse_.set_simulated_interval(plan.motor_interval_us, plan.motor_speed_centi_kmh);
}

void WheelCapture::mark_stopped() {
    stopped_ = true;
    pulse_.stop();
    telemetry_.set_stopped(config_.snapshot().circumference_mm);
}

}  // namespace ebike
