#include "telemetry_store.hpp"

namespace ebike {

void TelemetryStore::update_wheel(uint32_t interval_us, uint16_t speed_centi_kmh,
                                  uint32_t revolutions, uint16_t circumference_mm) {
    portENTER_CRITICAL(&lock_);
    state_.wheel_moving = true;
    state_.wheel_interval_us = interval_us;
    state_.wheel_speed_centi_kmh = speed_centi_kmh;
    state_.wheel_revolutions = revolutions;
    state_.circumference_mm = circumference_mm;
    portEXIT_CRITICAL(&lock_);
}

void TelemetryStore::set_stopped(uint16_t circumference_mm) {
    portENTER_CRITICAL(&lock_);
    state_.wheel_moving = false;
    state_.simulated_output = false;
    state_.wheel_interval_us = 0;
    state_.motor_interval_us = 0;
    state_.wheel_speed_centi_kmh = 0;
    state_.motor_speed_centi_kmh = 0;
    state_.circumference_mm = circumference_mm;
    portEXIT_CRITICAL(&lock_);
}

void TelemetryStore::update_motor(uint32_t interval_us, uint16_t speed_centi_kmh,
                                  bool simulated) {
    portENTER_CRITICAL(&lock_);
    state_.motor_interval_us = interval_us;
    state_.motor_speed_centi_kmh = speed_centi_kmh;
    state_.simulated_output = simulated;
    portEXIT_CRITICAL(&lock_);
}

void TelemetryStore::record_emitted_pulse() {
    portENTER_CRITICAL(&lock_);
    ++state_.emitted_pulses;
    portEXIT_CRITICAL(&lock_);
}

void IRAM_ATTR TelemetryStore::record_emitted_pulse_from_isr() {
    portENTER_CRITICAL_ISR(&lock_);
    ++state_.emitted_pulses;
    portEXIT_CRITICAL_ISR(&lock_);
}

TelemetrySnapshot TelemetryStore::snapshot() const {
    portENTER_CRITICAL(&lock_);
    const TelemetrySnapshot copy = state_;
    portEXIT_CRITICAL(&lock_);
    return copy;
}

}  // namespace ebike
