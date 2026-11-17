#pragma once
#include <cstdint>
#include "esp_check.h"
using gpio_num_t = int;
constexpr int GPIO_MODE_OUTPUT_OD = 1, GPIO_PULLDOWN_DISABLE = 0, GPIO_PULLUP_DISABLE = 0, GPIO_INTR_DISABLE = 0;
struct gpio_config_t { uint64_t pin_bit_mask; int mode, pull_down_en, pull_up_en, intr_type; };
inline int gpio_set_level(int, int) { return 0; }
inline int gpio_config(const gpio_config_t*) { return 0; }
