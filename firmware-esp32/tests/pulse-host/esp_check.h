#pragma once
#include <cassert>
using esp_err_t = int;
constexpr esp_err_t ESP_OK = 0;
#define ESP_ERROR_CHECK(x) assert((x) == ESP_OK)
inline const char* esp_err_to_name(int) { return "host"; }
