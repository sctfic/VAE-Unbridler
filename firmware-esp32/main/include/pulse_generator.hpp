#pragma once

#include <cstdint>

#include "driver/gptimer.h"
#include "esp_attr.h"
#include "ebike_types.hpp"
#include "ota_wake_output.hpp"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"

namespace ebike {

class TelemetryStore;

// GPIO 6 is an active-low open-drain contact for a compatible 3.3 V input.
// The generator owns all motor-contact timing; BLE never calls it directly.
class PulseGenerator : public OtaWakeOutput {
  public:
    explicit PulseGenerator(TelemetryStore& telemetry);

    void begin();
    void emit_passthrough(uint32_t interval_us, uint16_t speed_centi_kmh);
    void set_output_interval(uint32_t interval_us, uint16_t speed_centi_kmh, bool simulated);
    void stop();
    void retain_until(int64_t esp_deadline_us);
    bool request_ota_wake(uint16_t circumference_mm) override;
    void cancel_ota_wake() override;
    bool ota_wake_complete() const override;
    void service_ota_wake(); // wheel task, core 1 only
    void IRAM_ATTR cancel_ota_wake_from_isr();

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
    mutable portMUX_TYPE lock_ = portMUX_INITIALIZER_UNLOCKED;

    bool wake_owned_ = false;
    bool wake_started_ = false;
    bool wake_cancelled_ = false;
    bool wake_reported_ = false;
    uint8_t wake_remaining_ = 0;
    uint32_t wake_interval_us_ = 0;
    volatile bool contact_closed_ = false;
    volatile bool continuous_ = false;
    volatile bool armed_ = false;
    volatile uint64_t next_start_us_ = 0;
    volatile uint64_t last_start_us_ = 0;
    volatile uint64_t expires_us_ = 0;
    volatile uint32_t simulated_interval_us_ = 0;
    volatile uint16_t simulated_speed_centi_kmh_ = 0;
};

}  // namespace ebike
