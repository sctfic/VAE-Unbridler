#include "config_store.hpp"

#include "esp_log.h"
#include "nvs.h"
#include "nvs_flash.h"

namespace ebike {
namespace {
constexpr char kTag[] = "config";
constexpr char kNamespace[] = "ebike";
constexpr char kConfigKey[] = "runtime";

struct PersistedConfig {
    uint32_t magic;
    RuntimeConfig config;
    uint32_t checksum;
};

constexpr uint32_t kMagic = 0x4542494B;  // EBIK

uint32_t checksum(const RuntimeConfig& config) {
    uint32_t value = 2166136261u;
    const auto* bytes = reinterpret_cast<const uint8_t*>(&config);
    for (size_t i = 0; i < sizeof(config); ++i) {
        value = (value ^ bytes[i]) * 16777619u;
    }
    return value;
}
}  // namespace

void ConfigStore::begin() {
    PersistedConfig stored{};
    size_t size = sizeof(stored);
    nvs_handle_t handle;
    if (nvs_open(kNamespace, NVS_READONLY, &handle) == ESP_OK) {
        const esp_err_t result = nvs_get_blob(handle, kConfigKey, &stored, &size);
        nvs_close(handle);
        if (result == ESP_OK && size == sizeof(stored) && stored.magic == kMagic &&
            stored.checksum == checksum(stored.config) && is_valid(stored.config)) {
            portENTER_CRITICAL(&lock_);
            config_ = stored.config;
            portEXIT_CRITICAL(&lock_);
            ESP_LOGI(kTag, "Loaded circumference: %u mm", config_.circumference_mm);
            return;
        }
    }
    ESP_LOGI(kTag, "Using default circumference: %u mm", config_.circumference_mm);
}

RuntimeConfig ConfigStore::snapshot() const {
    portENTER_CRITICAL(&lock_);
    const RuntimeConfig copy = config_;
    portEXIT_CRITICAL(&lock_);
    return copy;
}

bool ConfigStore::update(const RuntimeConfig& config) {
    if (!is_valid(config)) {
        return false;
    }
    portENTER_CRITICAL(&lock_);
    config_ = config;
    portEXIT_CRITICAL(&lock_);
    persist(config);
    return true;
}

bool ConfigStore::is_valid(const RuntimeConfig& config) const {
    return config.circumference_mm >= 1000 && config.circumference_mm <= 4000 &&
           config.threshold_centi_kmh >= 500 && config.threshold_centi_kmh <= 5000;
}

void ConfigStore::persist(const RuntimeConfig& config) {
    PersistedConfig stored{};
    stored.magic = kMagic;
    stored.config = config;
    stored.checksum = checksum(config);
    nvs_handle_t handle;
    if (nvs_open(kNamespace, NVS_READWRITE, &handle) != ESP_OK) {
        ESP_LOGE(kTag, "Unable to open NVS");
        return;
    }
    const esp_err_t write_result = nvs_set_blob(handle, kConfigKey, &stored, sizeof(stored));
    const esp_err_t commit_result = write_result == ESP_OK ? nvs_commit(handle) : write_result;
    nvs_close(handle);
    if (commit_result != ESP_OK) {
        ESP_LOGE(kTag, "Unable to persist configuration: %s", esp_err_to_name(commit_result));
    }
}

}  // namespace ebike
