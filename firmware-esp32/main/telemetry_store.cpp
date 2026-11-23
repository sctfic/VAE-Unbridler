#include "telemetry_store.hpp"

namespace ebike {
void TelemetryStore::wheel_task_heartbeat(int64_t timestamp_us) {
    portENTER_CRITICAL(&lock_);
    state_.wheel_task_heartbeat_us = timestamp_us;
    portEXIT_CRITICAL(&lock_);
}

void IRAM_ATTR TelemetryStore::record_wheel_edge_from_isr(int64_t timestamp_us) {
    portENTER_CRITICAL_ISR(&lock_);
    state_.last_wheel_edge_us = timestamp_us;
    ++state_.wheel_edges;
    portEXIT_CRITICAL_ISR(&lock_);
}

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

void TelemetryStore::set_circumference(uint16_t circumference_mm) {
    portENTER_CRITICAL(&lock_);
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

void IRAM_ATTR TelemetryStore::record_emitted_pulse_from_isr(uint64_t timestamp_us) {
    portENTER_CRITICAL_ISR(&lock_);
    state_.emitted_motor_us = timestamp_us;
    state_.motor_timestamp_valid = true;
    portEXIT_CRITICAL_ISR(&lock_);
}

void TelemetryStore::record_event(uint64_t at, bool turbo, uint8_t type, uint8_t confirmation) {
    portENTER_CRITICAL(&lock_);
    if (type == 1) state_.accepted_wheel_us = at;
    state_.confirmation = confirmation;
    if (count_ < 16) {
        events_[write_] = {make_event(state_, at, turbo), type};
        write_ = (write_ + 1) % 16; ++count_;
    }
    portEXIT_CRITICAL(&lock_);
}
bool TelemetryStore::pop_event(BleEventPayload& payload, uint8_t& type) {
    portENTER_CRITICAL(&lock_);
    const bool available = count_ != 0;
    if (available) {
        payload = events_[read_].payload; type = events_[read_].type;
        read_ = (read_ + 1) % 16; --count_;
    }
    portEXIT_CRITICAL(&lock_);
    return available;
}

TelemetrySnapshot TelemetryStore::snapshot() const {
    portENTER_CRITICAL(&lock_);
    const TelemetrySnapshot copy = state_;
    portEXIT_CRITICAL(&lock_);
    return copy;
}
}  // namespace ebike
