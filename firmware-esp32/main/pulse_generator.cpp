#include "pulse_generator.hpp"

#include <algorithm>
#include <cstring>

#include "driver/gpio.h"
#include "esp_attr.h"
#include "esp_check.h"
#include "esp_log.h"
#include "freertos/task.h"
#include "hal/gpio_ll.h"
#include "soc/gpio_struct.h"
#include "telemetry_store.hpp"

namespace ebike {
namespace {
constexpr char kTag[] = "pulse";
constexpr uint64_t kEarliestTaskAlarmUs = 20;

inline void IRAM_ATTR set_contact_closed(bool closed) {
    // GPIO 6 controls the optocoupler/PhotoMOS LED. The external isolated output
    // is the only component connected to the controller's sensor terminals.
    gpio_ll_set_level(&GPIO, kMotorContactPin, closed ? 1 : 0);
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
    gpio_config_t gpio_config{};
    gpio_config.pin_bit_mask = 1ULL << kMotorContactPin;
    gpio_config.mode = GPIO_MODE_OUTPUT;
    gpio_config.pull_down_en = GPIO_PULLDOWN_DISABLE;
    gpio_config.pull_up_en = GPIO_PULLUP_DISABLE;
    gpio_config.intr_type = GPIO_INTR_DISABLE;
    ESP_ERROR_CHECK(gpio_configure(&gpio_config));
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

void PulseGenerator::emit_passthrough(uint32_t interval_us, uint16_t speed_centi_kmh) {
    const uint64_t now = now_us();
    portENTER_CRITICAL(&lock_);
    continuous_ = false;
    simulated_interval_us_ = 0;
    simulated_speed_centi_kmh_ = 0;
    if (!contact_closed_) {
        next_start_us_ = now + kEarliestTaskAlarmUs;
        armed_ = true;
    }
    const bool should_arm = !contact_closed_;
    const uint64_t alarm_at = next_start_us_;
    portEXIT_CRITICAL(&lock_);

    telemetry_.update_motor(interval_us, speed_centi_kmh, false);
    if (should_arm) {
        arm_from_task(alarm_at);
    }
}

void PulseGenerator::set_simulated_interval(uint32_t interval_us, uint16_t speed_centi_kmh) {
    if (interval_us <= kPulseWidthUs + 100) {
        return;
    }
    const uint64_t now = now_us();
    portENTER_CRITICAL(&lock_);
    continuous_ = true;
    simulated_interval_us_ = interval_us;
    simulated_speed_centi_kmh_ = speed_centi_kmh;
    if (!contact_closed_) {
        uint64_t candidate = last_start_us_ == 0 ? now + kEarliestTaskAlarmUs
                                                  : last_start_us_ + interval_us;
        if (candidate <= now + kEarliestTaskAlarmUs) {
            candidate = now + kEarliestTaskAlarmUs;
        }
        next_start_us_ = candidate;
        armed_ = true;
    }
    const bool should_arm = !contact_closed_;
    const uint64_t alarm_at = next_start_us_;
    portEXIT_CRITICAL(&lock_);

    telemetry_.update_motor(interval_us, speed_centi_kmh, true);
    if (should_arm) {
        arm_from_task(alarm_at);
    }
}

void PulseGenerator::stop() {
    portENTER_CRITICAL(&lock_);
    continuous_ = false;
    simulated_interval_us_ = 0;
    simulated_speed_centi_kmh_ = 0;
    if (!contact_closed_) {
        armed_ = false;
    }
    const bool should_disarm = !contact_closed_;
    portEXIT_CRITICAL(&lock_);

    if (should_disarm) {
        disarm_from_task();
    }
}

bool IRAM_ATTR PulseGenerator::on_alarm(gptimer_handle_t timer,
                                        const gptimer_alarm_event_data_t* event,
                                        void* context) {
    auto* self = static_cast<PulseGenerator*>(context);
    const uint64_t at_us = event->alarm_value;

    portENTER_CRITICAL_ISR(&self->lock_);
    if (!self->armed_) {
        portEXIT_CRITICAL_ISR(&self->lock_);
        return false;
    }

    if (!self->contact_closed_) {
        self->contact_closed_ = true;
        self->last_start_us_ = at_us;
        set_contact_closed(true);

        static gptimer_alarm_config_t close_alarm;
        close_alarm = make_alarm(at_us + kPulseWidthUs);
        gptimer_set_alarm_action(timer, &close_alarm);
        portEXIT_CRITICAL_ISR(&self->lock_);
        self->telemetry_.record_emitted_pulse_from_isr();
        return false;
    }

    self->contact_closed_ = false;
    set_contact_closed(false);
    if (self->continuous_ && self->simulated_interval_us_ > kPulseWidthUs) {
        self->next_start_us_ = self->last_start_us_ + self->simulated_interval_us_;
        if (self->next_start_us_ <= at_us + kEarliestTaskAlarmUs) {
            self->next_start_us_ = at_us + kEarliestTaskAlarmUs;
        }
        static gptimer_alarm_config_t next_alarm;
        next_alarm = make_alarm(self->next_start_us_);
        gptimer_set_alarm_action(timer, &next_alarm);
    } else {
        self->armed_ = false;
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
