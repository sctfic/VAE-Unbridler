#include "wheel_capture.hpp"

#include "driver/gpio.h"
#include "esp_attr.h"
#include "esp_timer.h"
#include "speed_model.hpp"

namespace ebike {
namespace {
constexpr TickType_t kTaskWaitTicks = pdMS_TO_TICKS(20);
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
}

void IRAM_ATTR WheelCapture::gpio_isr(void* context) {
    auto* self = static_cast<WheelCapture*>(context);
    const int64_t timestamp_us = esp_timer_get_time();
    self->pulse_.cancel_ota_wake_from_isr();
    self->telemetry_.record_wheel_edge_from_isr(timestamp_us);
    BaseType_t higher_priority_task_woken = pdFALSE;
    xQueueSendFromISR(self->edge_queue_, &timestamp_us,
                                                &higher_priority_task_woken);
    if (higher_priority_task_woken == pdTRUE) {
        portYIELD_FROM_ISR();
    }
}

void WheelCapture::task_entry(void* context) {
    static_cast<WheelCapture*>(context)->task();
}

void WheelCapture::task() {
    bool previous_speed_mode = config_.speed_enabled();
    RuntimeConfig previous_config = config_.snapshot();
    while (true) {
        pulse_.service_ota_wake();
        telemetry_.wheel_task_heartbeat(esp_timer_get_time());
        const bool speed_mode = config_.speed_enabled();
        const RuntimeConfig current = config_.snapshot();
        if (period_filter_.expired(esp_timer_get_time())) mark_stopped();
        if (speed_mode != previous_speed_mode || current.circumference_mm != previous_config.circumference_mm ||
            current.threshold_centi_kmh != previous_config.threshold_centi_kmh) {
            const bool config_changed = current.circumference_mm != previous_config.circumference_mm ||
                current.threshold_centi_kmh != previous_config.threshold_centi_kmh;
            confirmation_.reset();
            telemetry_.set_circumference(current.circumference_mm);
            // Entering Turbo at speed keeps the last cadence until a fresh pair.
            // Returning Standard applies the retained physical wheel period.
            if (!speed_mode || config_changed) {
                const auto plan = make_speed_plan(current.circumference_mm, current.threshold_centi_kmh,
                                                  period_filter_.interval_us(), speed_mode);
                if (plan.motor_interval_us) pulse_.set_output_interval(plan.motor_interval_us,
                    plan.motor_speed_centi_kmh, plan.simulated);
            }
            previous_speed_mode = speed_mode;
            previous_config = current;
            telemetry_.record_event(esp_timer_get_time(), speed_mode, 2, confirmation_.state());
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
    const bool turbo = config_.speed_enabled();
    const auto result = period_filter_.accept(edge_us, config.circumference_mm);
    if (result == WheelPeriodFilter::Result::ignored) {
        confirmation_.reject();

        telemetry_.record_event(edge_us, turbo, 2, confirmation_.state());
        return;
    }
    if (result == WheelPeriodFilter::Result::primed) {
        pulse_.stop();
        telemetry_.set_stopped(config_.snapshot().circumference_mm);
        confirmation_.reset();
        telemetry_.record_event(edge_us, turbo, 2, 0);
        return;
    }

    const uint32_t interval_us = period_filter_.interval_us();
    ++revolutions_;

    const SpeedPlan plan = make_speed_plan(config.circumference_mm, config.threshold_centi_kmh,
                                           interval_us, turbo);
    telemetry_.update_wheel(interval_us, plan.wheel_speed_centi_kmh, revolutions_,
                            config.circumference_mm);

    pulse_.retain_until(period_filter_.stop_deadline_us());
    const uint32_t confirmed = confirmation_.accept(edge_us, interval_us, config.circumference_mm,
                                                     turbo);
    if (confirmed) {
        const auto command = make_speed_plan(config.circumference_mm, config.threshold_centi_kmh,
                                              confirmed, turbo);
        pulse_.set_output_interval(command.motor_interval_us, command.motor_speed_centi_kmh, command.simulated);
    }

    telemetry_.record_event(edge_us, turbo, 1, confirmation_.state());
}

void WheelCapture::mark_stopped() {
    period_filter_.reset();
    confirmation_.reset();
    pulse_.stop();
    telemetry_.set_stopped(config_.snapshot().circumference_mm);
    telemetry_.record_event(esp_timer_get_time(), config_.speed_enabled(), 2, 0);
}
}  // namespace ebike
