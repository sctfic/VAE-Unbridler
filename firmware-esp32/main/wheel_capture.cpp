#include "wheel_capture.hpp"

#include "driver/gpio.h"
#include "esp_attr.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "speed_model.hpp"

namespace ebike {
namespace {
constexpr char kTag[] = "wheel";
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
    const bool interval_valid = self->last_isr_edge_us_ >= 0;
    const int64_t raw_interval = interval_valid ? timestamp_us - self->last_isr_edge_us_ : 0;
    const uint32_t interval_us = raw_interval > UINT32_MAX ? UINT32_MAX :
        static_cast<uint32_t>(raw_interval);
    self->last_isr_edge_us_ = timestamp_us;
    BaseType_t higher_priority_task_woken = pdFALSE;
    const BaseType_t queued = xQueueSendFromISR(self->edge_queue_, &timestamp_us,
                                                &higher_priority_task_woken);
    self->telemetry_.record_sensor_edge_from_isr(interval_us, interval_valid, queued != pdTRUE);
    if (higher_priority_task_woken == pdTRUE) {
        portYIELD_FROM_ISR();
    }
}

void WheelCapture::task_entry(void* context) {
    static_cast<WheelCapture*>(context)->task();
}

void WheelCapture::task() {
    bool previous_speed_mode = config_.speed_enabled();
    while (true) {
        const bool speed_mode = config_.speed_enabled();
        if (speed_mode != previous_speed_mode) {
            previous_speed_mode = speed_mode;
            // Recompute the retained cadence on the real-time core, never in BLE.
            // Standard also needs a continuous cadence while bad input is rejected.
            const RuntimeConfig config = config_.snapshot();
            const auto plan = make_speed_plan(config.circumference_mm, config.threshold_centi_kmh,
                                              period_filter_.interval_us(), speed_mode);
            if (plan.motor_interval_us != 0) {
                pulse_.set_output_interval(plan.motor_interval_us, plan.motor_speed_centi_kmh,
                                            plan.simulated);
            } else {
                pulse_.stop();
                telemetry_.update_motor(0, 0, false);
            }
        }
        int64_t edge_us = 0;
        if (xQueueReceive(edge_queue_, &edge_us, kTaskWaitTicks) == pdTRUE) {
            handle_edge(edge_us);
        }

        // Check even when the queue contains rejected electrical noise.
        if (period_filter_.expired(esp_timer_get_time())) {
            mark_stopped();
        }
    }
}

void WheelCapture::handle_edge(int64_t edge_us) {
    const RuntimeConfig config = config_.snapshot();
    const auto result = period_filter_.accept(edge_us, config.circumference_mm);
    if (result == WheelPeriodFilter::Result::ignored) {
        telemetry_.record_rejected_edge();
        return;
    }
    if (result == WheelPeriodFilter::Result::primed) {
        pulse_.stop();
        telemetry_.set_stopped(config_.snapshot().circumference_mm);
        if (!config_.speed_enabled()) pulse_.emit_passthrough(0, 0);
        return;
    }

    const uint32_t interval_us = period_filter_.interval_us();
    ++revolutions_;

    const SpeedPlan plan = make_speed_plan(config.circumference_mm, config.threshold_centi_kmh,
                                           interval_us, config_.speed_enabled());
    telemetry_.update_wheel(interval_us, plan.wheel_speed_centi_kmh, revolutions_,
                            config.circumference_mm);

    // Keep emitting the last validated cadence through rejected sensor edges.
    // Rejections never trigger an immediate extra contact pulse.
    pulse_.set_output_interval(plan.motor_interval_us, plan.motor_speed_centi_kmh, plan.simulated);
}

void WheelCapture::mark_stopped() {
    period_filter_.reset();
    pulse_.stop();
    telemetry_.set_stopped(config_.snapshot().circumference_mm);
}

}  // namespace ebike
