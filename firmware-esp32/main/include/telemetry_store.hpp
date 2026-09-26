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
    void update_motor(uint32_t interval_us, uint16_t speed_centi_kmh, bool simulated);
    void record_emitted_pulse();
    void record_emitted_pulse_from_isr();
    TelemetrySnapshot snapshot() const;

  private:
    mutable portMUX_TYPE lock_ = portMUX_INITIALIZER_UNLOCKED;
    TelemetrySnapshot state_{};
};

}  // namespace ebike
