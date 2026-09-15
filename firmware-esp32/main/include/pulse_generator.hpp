#pragma once

#include <cstdint>

#include "driver/gptimer.h"
#include "ebike_types.hpp"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"

namespace ebike {

class TelemetryStore;

// Drives GPIO 6 only as a logic signal for an isolated dry-contact interface.
// The generator owns all motor-contact timing; BLE never calls it directly.
class PulseGenerator {
  public:
    explicit PulseGenerator(TelemetryStore& telemetry);

    void begin();
    void emit_passthrough(uint32_t interval_us, uint16_t speed_centi_kmh);
    void set_simulated_interval(uint32_t interval_us, uint16_t speed_centi_kmh);
    void stop();

  private:
    static bool IRAM_ATTR on_alarm(gptimer_handle_t timer,
                                   const gptimer_alarm_event_data_t* event, void* context);
    static void timer_init_task(void* context);
    void initialize_on_current_core();
    void arm_from_task(uint64_t alarm_at_us);
    void disarm_from_task();
    uint64_t now_us() const;

    TelemetryStore& telemetry_;
    gptimer_handle_t timer_ = nullptr;
    SemaphoreHandle_t ready_ = nullptr;
    portMUX_TYPE lock_ = portMUX_INITIALIZER_UNLOCKED;

    volatile bool contact_closed_ = false;
    volatile bool continuous_ = false;
    volatile bool armed_ = false;
    volatile uint64_t next_start_us_ = 0;
    volatile uint64_t last_start_us_ = 0;
    volatile uint32_t simulated_interval_us_ = 0;
    volatile uint16_t simulated_speed_centi_kmh_ = 0;
};

}  // namespace ebike
