#include "ota_service.hpp"

#include <cstring>
#include "firmware_version.hpp"
#include "ota_policy.hpp"
#include "esp_app_desc.h"
#include "esp_system.h"
#include "esp_timer.h"

namespace ebike {
namespace {
uint32_t u32(const uint8_t* data) {
    return uint32_t(data[0]) | (uint32_t(data[1]) << 8) |
           (uint32_t(data[2]) << 16) | (uint32_t(data[3]) << 24);
}
uint8_t slot(const esp_partition_t* partition) {
    return partition ? static_cast<uint8_t>(partition->subtype - ESP_PARTITION_SUBTYPE_APP_OTA_0) : 0xff;
}
struct Guard {
    explicit Guard(SemaphoreHandle_t mutex) : mutex(mutex) { xSemaphoreTake(mutex, portMAX_DELAY); }
    ~Guard() { xSemaphoreGive(mutex); }
    SemaphoreHandle_t mutex;
};
}  // namespace

OtaService::OtaService(ConfigStore& config, TelemetryStore& telemetry) : config_(config), telemetry_(telemetry) {}

void OtaService::begin() {
    mutex_ = xSemaphoreCreateMutex();
    configASSERT(mutex_);
    mbedtls_sha256_init(&hash_);
    target_ = esp_ota_get_next_update_partition(nullptr);
    const auto* running = esp_ota_get_running_partition();
    status_.slot = slot(running);
    status_.target_slot = slot(target_);
    status_.capacity = target_ ? target_->size : 0;
    status_.version_code = kFirmwareVersionCode;
    status_.reserved = kHardwareRevision;
    std::strncpy(status_.version, esp_app_get_description()->version, sizeof(status_.version) - 1);
}

bool OtaService::stationary() const {
    const auto motion = telemetry_.snapshot();
    return ota_stationary(esp_timer_get_time(), motion.last_wheel_edge_us, motion.wheel_moving);
}

bool OtaService::safe_session() const {
    return stationary() && telemetry_.snapshot().wheel_edges == start_edges_;
}

OtaStatus OtaService::status() {
    Guard guard(mutex_);
    esp_ota_img_states_t image_state = ESP_OTA_IMG_UNDEFINED;
    const esp_err_t result = esp_ota_get_state_partition(esp_ota_get_running_partition(), &image_state);
    status_.flags = (target_ ? 1 : 0) | (stationary() ? 2 : 0) |
                    ((result == ESP_OK && image_state == ESP_OTA_IMG_VALID) ? 4 : 0);
    return status_;
}

bool OtaService::busy() {
    Guard guard(mutex_);
    return status_.state >= 1 && status_.state <= 3;
}

void OtaService::fail(uint8_t error) {
    if (handle_) { esp_ota_abort(handle_); handle_ = 0; }
    if (status_.state == 3) {
        // Activation was selected but motion was detected before restart.
        ESP_ERROR_CHECK(esp_ota_set_boot_partition(esp_ota_get_running_partition()));
        ESP_ERROR_CHECK(esp_ota_mark_app_valid_cancel_rollback());
    }
    mbedtls_sha256_free(&hash_);
    mbedtls_sha256_init(&hash_);
    status_.state = 4;
    status_.error = error;
    reboot_at_us_ = 0;
}

bool OtaService::control(const uint8_t* data, size_t length) {
    Guard guard(mutex_);
    if (!length) return false;
    if (data[0] == 3 && length == 1) { fail(6); return true; }
    if (data[0] == 1 && length == 41 && (status_.state == 0 || status_.state == 4)) {
        esp_ota_img_states_t state;
        if (esp_ota_get_state_partition(esp_ota_get_running_partition(), &state) != ESP_OK ||
            state != ESP_OTA_IMG_VALID) { fail(3); return false; }
        start_edges_ = telemetry_.snapshot().wheel_edges;
        if (!safe_session()) { fail(1); return false; }
        const uint32_t size = u32(data + 1);
        if (!target_ || !size || size > target_->size || u32(data + 37) <= kFirmwareVersionCode) {
            fail(2); return false;
        }
        config_.set_speed_enabled(false);
        status_.size = size;
        status_.received = 0;
        status_.error = 0;
        std::memcpy(expected_hash_, data + 5, 32);
        if (mbedtls_sha256_starts(&hash_, 0) != 0 ||
            esp_ota_begin(target_, OTA_WITH_SEQUENTIAL_WRITES, &handle_) != ESP_OK) {
            fail(3); return false;
        }
        status_.state = 1;
        last_activity_us_ = esp_timer_get_time();
        if (!safe_session()) { fail(1); return false; }
        return true;
    }
    if (!safe_session() && status_.state >= 1 && status_.state <= 3) { fail(1); return false; }
    if (data[0] == 2 && length == 1 && status_.state == 1) {
        uint8_t actual[32]{};
        if (status_.received != status_.size) { fail(2); return false; }
        if (mbedtls_sha256_finish(&hash_, actual) != 0 || std::memcmp(actual, expected_hash_, 32) != 0) {
            fail(4); return false;
        }
        const esp_err_t result = esp_ota_end(handle_);
        handle_ = 0;  // esp_ota_end frees the handle on failure too.
        esp_app_desc_t description{};
        if (result != ESP_OK || esp_ota_get_partition_description(target_, &description) != ESP_OK ||
            std::strncmp(description.project_name, esp_app_get_description()->project_name, sizeof(description.project_name)) != 0) {
            fail(3); return false;
        }
        if (!safe_session()) { fail(1); return false; }
        status_.state = 2;
        last_activity_us_ = esp_timer_get_time();
        return true;
    }
    if (data[0] == 4 && length == 1 && status_.state == 2) {
        if (esp_ota_set_boot_partition(target_) != ESP_OK) { fail(3); return false; }
        status_.state = 3;
        reboot_at_us_ = esp_timer_get_time() + 1'500'000;
        return true;
    }
    fail(2);
    return false;
}

bool OtaService::write(const uint8_t* data, size_t length) {
    Guard guard(mutex_);
    if (status_.state != 1) return false;
    if (!safe_session()) { fail(1); return false; }
    if (length <= 4 || length > 244 || !ota_chunk_valid(u32(data), status_.received, status_.size, static_cast<uint32_t>(length - 4))) {
        fail(2); return false;
    }
    if (esp_ota_write(handle_, data + 4, length - 4) != ESP_OK ||
        mbedtls_sha256_update(&hash_, data + 4, length - 4) != 0) { fail(3); return false; }
    status_.received += static_cast<uint32_t>(length - 4);
    last_activity_us_ = esp_timer_get_time();
    if (!safe_session()) { fail(1); return false; }
    return true;
}

void OtaService::disconnected() {
    Guard guard(mutex_);
    if (status_.state == 1 || status_.state == 2) fail(6);
}

void OtaService::tick(bool ble_ready) {
    Guard guard(mutex_);
    const int64_t now = esp_timer_get_time();
    const bool healthy = ble_ready && now - telemetry_.snapshot().wheel_task_heartbeat_us < 500'000;
    if (!healthy) healthy_since_us_ = 0;
    if (healthy) {
        if (!healthy_since_us_) healthy_since_us_ = now;
        if (now - healthy_since_us_ >= 10'000'000) {
            // Called only after NVS, the pulse timer, capture task and NimBLE initialized.
            esp_ota_img_states_t state = ESP_OTA_IMG_UNDEFINED;
            if (esp_ota_get_state_partition(esp_ota_get_running_partition(), &state) == ESP_OK &&
                state == ESP_OTA_IMG_PENDING_VERIFY) ESP_ERROR_CHECK(esp_ota_mark_app_valid_cancel_rollback());
            // The first USB install has no OTA state yet; mark it valid as the fallback.
            if (esp_ota_get_state_partition(esp_ota_get_running_partition(), &state) == ESP_ERR_NOT_FOUND ||
                state == ESP_OTA_IMG_UNDEFINED) {
                ESP_ERROR_CHECK(esp_ota_set_boot_partition(esp_ota_get_running_partition()));
                ESP_ERROR_CHECK(esp_ota_mark_app_valid_cancel_rollback());
            }
        }
    }
    if (status_.state < 1 || status_.state > 3) return;
    if (!safe_session()) { fail(1); return; }
    if (status_.state == 3 && now >= reboot_at_us_) { esp_restart(); }
    if (status_.state != 3 && now - last_activity_us_ > 30'000'000) fail(5);
}
}  // namespace ebike
