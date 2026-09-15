#pragma once

#include <cstdint>

#include "config_store.hpp"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "pulse_generator.hpp"
#include "telemetry_store.hpp"

namespace ebike {

class WheelCapture {
  public:
    WheelCapture(ConfigStore& config, TelemetryStore& telemetry, PulseGenerator& pulse);
    void begin();

  private:
    static void IRAM_ATTR gpio_isr(void* context);
    static void task_entry(void* context);
    void task();
    void handle_edge(int64_t edge_us);
    void mark_stopped();

    ConfigStore& config_;
    TelemetryStore& telemetry_;
    PulseGenerator& pulse_;
    QueueHandle_t edge_queue_ = nullptr;
    int64_t last_edge_us_ = 0;
    uint32_t last_interval_us_ = 0;
    uint32_t revolutions_ = 0;
    bool stopped_ = true;
};

}  // namespace ebike
