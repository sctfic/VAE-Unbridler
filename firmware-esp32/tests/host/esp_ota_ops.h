#pragma once
#include <cassert>
#include <cstddef>
#include <cstdint>
#include "esp_app_desc.h"
using esp_err_t = int;
constexpr esp_err_t ESP_OK = 0, ESP_FAIL = -1, ESP_ERR_NOT_FOUND = 1;
constexpr int ESP_PARTITION_SUBTYPE_APP_OTA_0 = 16;
constexpr size_t OTA_WITH_SEQUENTIAL_WRITES = 0xfffffffe;
using esp_ota_handle_t = unsigned;
enum esp_ota_img_states_t { ESP_OTA_IMG_UNDEFINED, ESP_OTA_IMG_NEW, ESP_OTA_IMG_PENDING_VERIFY, ESP_OTA_IMG_VALID };
struct esp_partition_t { int subtype; uint32_t size; };
#define ESP_ERROR_CHECK(value) assert((value) == ESP_OK)
const esp_partition_t* esp_ota_get_next_update_partition(const esp_partition_t*);
const esp_partition_t* esp_ota_get_running_partition();
esp_err_t esp_ota_get_state_partition(const esp_partition_t*, esp_ota_img_states_t*);
esp_err_t esp_ota_set_boot_partition(const esp_partition_t*);
esp_err_t esp_ota_mark_app_valid_cancel_rollback();
esp_err_t esp_ota_begin(const esp_partition_t*, size_t, esp_ota_handle_t*);
esp_err_t esp_ota_write(esp_ota_handle_t, const void*, size_t);
esp_err_t esp_ota_abort(esp_ota_handle_t);
esp_err_t esp_ota_end(esp_ota_handle_t);
esp_err_t esp_ota_get_partition_description(const esp_partition_t*, esp_app_desc_t*);
