#pragma once

#include "ebike_types.hpp"
#include "esp_attr.h"
#include "freertos/FreeRTOS.h"

namespace ebike {

class TelemetryStore {
  public:
    void update_wheel(uint32_t interval_us, uint16_t speed_centi_kmh, uint32_t revolutions,
                      uint16_t circumference_mm);
    void set_stopped(uint16_t circumference_mm);
    void set_circumference(uint16_t circumference_mm);
    void update_motor(uint32_t interval_us, uint16_t speed_centi_kmh, bool simulated);
    void record_emitted_pulse();
    void record_emitted_pulse_from_isr(uint64_t timestamp_us);
    void record_event(uint64_t timestamp_us, bool turbo, uint8_t type, uint8_t confirmation);
    bool pop_event(BleEventPayload& payload, uint8_t& type);
    void record_telemetry_drop();
    void record_wheel_edge_from_isr(int64_t timestamp_us);
    void wheel_task_heartbeat(int64_t timestamp_us);
    void record_sensor_edge_from_isr(uint32_t interval_us, bool valid, bool dropped);
    void record_rejected_edge();
    TelemetrySnapshot snapshot() const;

  private:
    mutable portMUX_TYPE lock_ = portMUX_INITIALIZER_UNLOCKED;
    TelemetrySnapshot state_{};
    struct Event { BleEventPayload payload; uint8_t type; };
    Event events_[16]{};
    unsigned read_ = 0, write_ = 0, count_ = 0;
};

}  // namespace ebike
