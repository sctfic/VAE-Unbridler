#include "pulse_generator.hpp"

#include <algorithm>
#include <cstring>

#include "driver/gpio.h"
#include "esp_attr.h"
#include "esp_check.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "pulse_schedule.hpp"
#include "freertos/task.h"
#include "hal/gpio_ll.h"
#include "soc/gpio_struct.h"
#include "telemetry_store.hpp"

namespace ebike {
namespace {
constexpr char kTag[] = "pulse";
constexpr uint64_t kEarliestTaskAlarmUs = 20;

inline void IRAM_ATTR set_contact_closed(bool closed) {
    // Open-drain contact: sink to common signal ground when closed;
    // release to the controller's external 3.3 V pull-up when open.
    gpio_ll_set_level(&GPIO, kMotorContactPin, closed ? 0 : 1);
}

gptimer_alarm_config_t IRAM_ATTR make_alarm(uint64_t at_us) {
    gptimer_alarm_config_t config{};
    config.reload_count = 0;
    config.alarm_count = at_us;
    config.flags.auto_reload_on_alarm = false;
    return config;
}
}  // namespace

PulseGenerator::PulseGenerator(TelemetryStore& telemetry) : telemetry_(telemetry) {}

void PulseGenerator::begin() {
    ready_ = xSemaphoreCreateBinary();
    configASSERT(ready_ != nullptr);
    const BaseType_t created = xTaskCreatePinnedToCore(&PulseGenerator::timer_init_task, "pulse_timer_init",
                                                       3072, this, configMAX_PRIORITIES - 1, nullptr, 1);
    configASSERT(created == pdPASS);
    xSemaphoreTake(ready_, portMAX_DELAY);
    vSemaphoreDelete(ready_);
    ready_ = nullptr;
}

void PulseGenerator::timer_init_task(void* context) {
    auto* self = static_cast<PulseGenerator*>(context);
    self->initialize_on_current_core();
    xSemaphoreGive(self->ready_);
    vTaskDelete(nullptr);
}

void PulseGenerator::initialize_on_current_core() {
    // Preload the released state before enabling output, avoiding a false pulse.
    ESP_ERROR_CHECK(gpio_set_level(static_cast<gpio_num_t>(kMotorContactPin), 1));
    gpio_config_t output_config{};
    output_config.pin_bit_mask = 1ULL << kMotorContactPin;
    output_config.mode = GPIO_MODE_OUTPUT_OD;
    output_config.pull_down_en = GPIO_PULLDOWN_DISABLE;
    output_config.pull_up_en = GPIO_PULLUP_DISABLE;
    output_config.intr_type = GPIO_INTR_DISABLE;
    ESP_ERROR_CHECK(gpio_config(&output_config));
    set_contact_closed(false);

    gptimer_config_t timer_config{};
    timer_config.clk_src = GPTIMER_CLK_SRC_DEFAULT;
    timer_config.direction = GPTIMER_COUNT_UP;
    timer_config.resolution_hz = 1000 * 1000;  // one tick = one microsecond
    ESP_ERROR_CHECK(gptimer_new_timer(&timer_config, &timer_));

    gptimer_event_callbacks_t callbacks{};
    callbacks.on_alarm = &PulseGenerator::on_alarm;
    ESP_ERROR_CHECK(gptimer_register_event_callbacks(timer_, &callbacks, this));

    // GPTimer requires the first alarm action before it is enabled. This first
    // deadline is deliberately unreachable during normal operation.
    const gptimer_alarm_config_t initial_alarm = make_alarm(UINT64_MAX);
    ESP_ERROR_CHECK(gptimer_set_alarm_action(timer_, &initial_alarm));
    ESP_ERROR_CHECK(gptimer_enable(timer_));
    ESP_ERROR_CHECK(gptimer_start(timer_));
    ESP_LOGI(kTag, "GPTimer started at 1 MHz on real-time core, GPIO %d", kMotorContactPin);
}

bool PulseGenerator::request_ota_wake(uint16_t circumference_mm) {
    if (circumference_mm < 1000 || circumference_mm > 4000) return false;
    portENTER_CRITICAL(&lock_);
    const bool available = !continuous_ && !contact_closed_ && !wake_owned_;
    if (available) {
        wake_owned_ = true; wake_started_ = false; wake_cancelled_ = false;
        wake_reported_ = false; wake_remaining_ = 3;
        wake_interval_us_ = ota_wake_interval_us(circumference_mm);
    }
    portEXIT_CRITICAL(&lock_);
    return available;
}
void PulseGenerator::cancel_ota_wake() {
    portENTER_CRITICAL(&lock_);
    if (wake_owned_) wake_cancelled_ = true;
    portEXIT_CRITICAL(&lock_);
}
void IRAM_ATTR PulseGenerator::cancel_ota_wake_from_isr() {
    portENTER_CRITICAL_ISR(&lock_);
    if (wake_owned_) wake_cancelled_ = true;
    portEXIT_CRITICAL_ISR(&lock_);
}
bool PulseGenerator::ota_wake_complete() const {
    portENTER_CRITICAL(&lock_);
    const bool complete = wake_owned_ && wake_started_ && !wake_cancelled_ &&
        wake_remaining_ == 0 && !contact_closed_;
    portEXIT_CRITICAL(&lock_);
    return complete;
}
void PulseGenerator::service_ota_wake() {
    bool report = false, cancelled = false;
    uint32_t interval = 0;
    portENTER_CRITICAL(&lock_);
    if (wake_owned_ && wake_cancelled_) {
        wake_owned_ = false; wake_remaining_ = 0;
        continuous_ = false; expires_us_ = 0; simulated_interval_us_ = 0;
        if (!contact_closed_) { armed_ = false; disarm_from_task(); }
        report = cancelled = true;
    } else if (wake_owned_ && !wake_started_) {
        wake_started_ = true; continuous_ = true;
        interval = wake_interval_us_;
        simulated_interval_us_ = interval;
        simulated_speed_centi_kmh_ = 200;
        // Finite train, with an independent upper deadline if core 1 stalls.
        const auto now = now_us();
        expires_us_ = now + uint64_t(interval) * 3 + kPulseWidthUs;
        next_start_us_ = next_pulse_start(last_start_us_, now, interval);
        armed_ = true; arm_from_task(next_start_us_);
        report = true;
    } else if (wake_owned_ && wake_remaining_ == 0 && !contact_closed_ && !wake_reported_) {
        wake_reported_ = true; report = true;
    }
    portEXIT_CRITICAL(&lock_);
    if (report) {
        telemetry_.update_motor(interval, interval ? 200 : 0, false);
        ESP_LOGI(kTag, "OTA wake %s interval=%lu us", cancelled ? "cancelled" : interval ? "started" : "complete",
            (unsigned long)interval);
    }
}

void PulseGenerator::emit_passthrough(uint32_t interval_us, uint16_t speed_centi_kmh) {
    // Kept for source compatibility; every contact now uses the same scheduler.
    if (interval_us) set_output_interval(interval_us, speed_centi_kmh, false);
}

void PulseGenerator::retain_until(int64_t esp_deadline_us) {
    portENTER_CRITICAL(&lock_);
    const int64_t remaining = esp_deadline_us - esp_timer_get_time();
    expires_us_ = now_us() + static_cast<uint64_t>(std::max<int64_t>(0, remaining));
    portEXIT_CRITICAL(&lock_);
}

void PulseGenerator::set_output_interval(uint32_t interval_us, uint16_t speed_centi_kmh,
                                         bool simulated) {
    if (interval_us <= kPulseWidthUs + 100) return;
    portENTER_CRITICAL(&lock_);
    // Real wheel acquisition takes ownership again after any OTA cancellation.
    wake_owned_ = false; wake_remaining_ = 0;
    const bool changed = !continuous_ || simulated_interval_us_ != interval_us;
    continuous_ = true;
    simulated_interval_us_ = interval_us;
    simulated_speed_centi_kmh_ = speed_centi_kmh;
    if (!contact_closed_ && (changed || !armed_)) {
        next_start_us_ = next_pulse_start(last_start_us_, now_us(), interval_us);
        armed_ = true;
        // The timer ISR is on this core. Serialize alarm changes with its state.
        arm_from_task(next_start_us_);
    }
    portEXIT_CRITICAL(&lock_);
    telemetry_.update_motor(interval_us, speed_centi_kmh, simulated);
}

void PulseGenerator::stop() {
    portENTER_CRITICAL(&lock_);
    wake_owned_ = false; wake_remaining_ = 0; wake_cancelled_ = true;
    continuous_ = false;
    simulated_interval_us_ = 0;
    simulated_speed_centi_kmh_ = 0;
    expires_us_ = 0;
    if (!contact_closed_) {
        armed_ = false;
        disarm_from_task();
    }
    portEXIT_CRITICAL(&lock_);
}

bool IRAM_ATTR PulseGenerator::on_alarm(gptimer_handle_t timer,
                                        const gptimer_alarm_event_data_t* event,
                                        void* context) {
    auto* self = static_cast<PulseGenerator*>(context);
    (void)event;
    uint64_t at_us = 0;
    gptimer_get_raw_count(timer, &at_us); // actual ISR time, not the scheduled alarm

    portENTER_CRITICAL_ISR(&self->lock_);
    if (!self->armed_) {
        portEXIT_CRITICAL_ISR(&self->lock_);
        return false;
    }

    if (!self->contact_closed_) {
        if (!self->continuous_ || at_us > self->expires_us_ ||
            (self->wake_owned_ && (self->wake_cancelled_ || self->wake_remaining_ == 0))) {
            self->armed_ = false;
            gptimer_set_alarm_action(timer, nullptr);
            portEXIT_CRITICAL_ISR(&self->lock_);
            return false;
        }
        self->contact_closed_ = true;
        self->last_start_us_ = at_us;
        set_contact_closed(true);
        if (self->wake_owned_) --self->wake_remaining_;

        static gptimer_alarm_config_t close_alarm;
        close_alarm = make_alarm(at_us + kPulseWidthUs);
        gptimer_set_alarm_action(timer, &close_alarm);
        portEXIT_CRITICAL_ISR(&self->lock_);
        self->telemetry_.record_emitted_pulse_from_isr(esp_timer_get_time());
        return false;
    }

    self->contact_closed_ = false;
    set_contact_closed(false);
    if (self->continuous_ && at_us < self->expires_us_ && self->simulated_interval_us_ > kPulseWidthUs &&
        (!self->wake_owned_ || (!self->wake_cancelled_ && self->wake_remaining_ > 0))) {
        self->next_start_us_ = self->last_start_us_ + self->simulated_interval_us_;
        if (self->next_start_us_ <= at_us + kEarliestTaskAlarmUs) {
            self->next_start_us_ = at_us + kEarliestTaskAlarmUs;
        }
        static gptimer_alarm_config_t next_alarm;
        next_alarm = make_alarm(self->next_start_us_);
        gptimer_set_alarm_action(timer, &next_alarm);
    } else {
        self->armed_ = false;
        self->continuous_ = false;
        gptimer_set_alarm_action(timer, nullptr);
    }
    portEXIT_CRITICAL_ISR(&self->lock_);
    return false;
}

void PulseGenerator::arm_from_task(uint64_t alarm_at_us) {
    const gptimer_alarm_config_t alarm = make_alarm(alarm_at_us);
    const esp_err_t result = gptimer_set_alarm_action(timer_, &alarm);
    if (result != ESP_OK) {
        ESP_LOGE(kTag, "Unable to arm GPTimer: %s", esp_err_to_name(result));
    }
}

void PulseGenerator::disarm_from_task() {
    const esp_err_t result = gptimer_set_alarm_action(timer_, nullptr);
    if (result != ESP_OK) {
        ESP_LOGE(kTag, "Unable to disarm GPTimer: %s", esp_err_to_name(result));
    }
}

uint64_t PulseGenerator::now_us() const {
    uint64_t now = 0;
    ESP_ERROR_CHECK(gptimer_get_raw_count(timer_, &now));
    return now;
}

}  // namespace ebike
