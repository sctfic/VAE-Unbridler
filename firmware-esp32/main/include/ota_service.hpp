#pragma once

#include <cstddef>
#include <cstdint>
#include "config_store.hpp"
#include "telemetry_store.hpp"
#include "ota_wake_output.hpp"
#include "ota_stream_buffer.hpp"
#include "esp_ota_ops.h"
#include "freertos/semphr.h"
#include "mbedtls/sha256.h"

namespace ebike {
#pragma pack(push, 1)
struct OtaStatus {
    uint8_t protocol = 1;
    uint8_t state = 0;  // 0 idle, 1 receiving, 2 verified, 3 rebooting, 4 error, 5 wake/preparing
    uint8_t error = 0;  // 1 motion, 2 format, 3 flash, 4 hash, 5 timeout, 6 cancelled
    uint8_t flags = 0;  // bit 0 OTA supported, bit 1 stationary, bit 2 boot confirmed, bit 3 wake supported, bit 4 wake complete, bit 5 fast stream + concurrent wake
    uint32_t received = 0; // persisted and hashed, excludes queued/in-flight bytes
    uint32_t size = 0;
    uint32_t version_code = 0;
    uint8_t slot = 0xff;
    uint8_t target_slot = 0xff;
    uint16_t reserved = 0;
    uint32_t capacity = 0;
    char version[24]{};
};
#pragma pack(pop)
static_assert(sizeof(OtaStatus) == 48);

class OtaService {
  public:
    OtaService(ConfigStore& config, TelemetryStore& telemetry, OtaWakeOutput& wake);
    void begin();
    OtaStatus status();
    bool control(const uint8_t* data, size_t size);
    bool write(const uint8_t* data, size_t size);
    void tick(bool ble_ready);
    void disconnected();
    bool busy();
    void process_pending(); // dedicated flash task; never called by NimBLE

  private:
    bool stationary() const;
    bool safe_session() const;
    bool active() const;
    void fail(uint8_t error);
    OtaWakeOutput& wake_;
    ConfigStore& config_;
    TelemetryStore& telemetry_;
    SemaphoreHandle_t mutex_ = nullptr;
    OtaStatus status_{};
    OtaStreamBuffer stream_;
    bool writing_ = false;
    const esp_partition_t* target_ = nullptr;
    esp_ota_handle_t handle_ = 0;
    mbedtls_sha256_context hash_{};
    uint8_t expected_hash_[32]{};
    uint32_t start_edges_ = 0;
    int64_t last_activity_us_ = 0;
    int64_t reboot_at_us_ = 0;
    int64_t healthy_since_us_ = 0;
};
}  // namespace ebike
