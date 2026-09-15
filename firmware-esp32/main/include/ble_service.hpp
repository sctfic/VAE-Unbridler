#pragma once

#include <cstdint>

#include "config_store.hpp"
#include "telemetry_store.hpp"

struct ble_gatt_access_ctxt;
struct ble_gap_event;

namespace ebike {

class BleService {
  public:
    BleService(ConfigStore& config, TelemetryStore& telemetry);
    void begin();

  private:
    static void host_task(void* context);
    static void notify_task(void* context);
    static void on_sync();
    static int gap_event(ble_gap_event* event, void* context);
    static int gatt_access(uint16_t connection_handle, uint16_t attribute_handle,
                           ble_gatt_access_ctxt* context, void* argument);

    void register_gatt();
    void start_advertising();
    void send_telemetry();
    int read_telemetry(ble_gatt_access_ctxt* context);
    int read_config(ble_gatt_access_ctxt* context);
    int write_config(ble_gatt_access_ctxt* context);

    static BleService* instance_;
    ConfigStore& config_;
    TelemetryStore& telemetry_;
    uint16_t telemetry_handle_ = 0;
    uint16_t config_handle_ = 0;
    volatile uint16_t connection_handle_ = 0xffff;
    uint16_t sequence_ = 0;
    uint8_t address_type_ = 0;
};

}  // namespace ebike
