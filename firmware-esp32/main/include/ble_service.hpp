#pragma once

#include <cstdint>
#include <atomic>

#include "config_store.hpp"
#include "telemetry_store.hpp"
#include "ota_service.hpp"
#include "freertos/task.h"

struct ble_gatt_access_ctxt;
struct ble_gap_event;

namespace ebike {
class BleService {
  public:
    BleService(ConfigStore& config, TelemetryStore& telemetry, OtaWakeOutput& wake);
    void begin();

  private:
    static void host_task(void* context);
    static void notify_task(void* context);
    static void flash_task(void* context);
    void update_link_priority();
    void send_progress();
    TaskHandle_t flash_task_handle_ = nullptr;
    std::atomic<bool> progress_subscribed_{false};
    std::atomic<uint32_t> progress_epoch_{0};
    uint32_t ack_epoch_ = 0;
    OtaProgress last_progress_{};
    std::atomic<uint16_t> interval_units_{0};
    std::atomic<uint8_t> tx_phy_{1}, rx_phy_{1};
    int64_t last_priority_request_us_ = 0;
    unsigned priority_attempts_ = 0;
    static void on_sync();
    static int gap_event(ble_gap_event* event, void* context);
    static int gatt_access(uint16_t connection_handle, uint16_t attribute_handle,
                           ble_gatt_access_ctxt* context, void* argument);

    void register_gatt();
    void start_advertising();
    void send_telemetry();
    void send_event(BleEventPayload payload, uint8_t type);
    int read_config(ble_gatt_access_ctxt* context);
    int write_config(ble_gatt_access_ctxt* context);

    static BleService* instance_;
    ConfigStore& config_;
    TelemetryStore& telemetry_;
    OtaService ota_;
    std::atomic<bool> ble_ready_{false};
    uint16_t ota_control_handle_ = 0;
    uint16_t ota_data_handle_ = 0;
    uint16_t events_handle_ = 0;
    std::atomic<bool> events_subscribed_{false};
    bool initial_state_sent_ = false;
    std::atomic<uint32_t> stream_epoch_{0};
    uint32_t sent_epoch_ = 0;
    uint64_t session_ = 0;
    int64_t last_presence_us_ = 0;
    uint16_t config_handle_ = 0;
    volatile uint16_t connection_handle_ = 0xffff;
    uint16_t sequence_ = 0;
    uint8_t address_type_ = 0;
    bool priority_active_ = false;
};
}  // namespace ebike
