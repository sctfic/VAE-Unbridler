#pragma once
#include "freertos/FreeRTOS.h"
using BaseType_t = int;
constexpr int pdPASS = 1, configMAX_PRIORITIES = 25;
inline int xTaskCreatePinnedToCore(void (*entry)(void*), const char*, unsigned, void* ctx, int, void*, int) {
    entry(ctx); return pdPASS;
}
inline void vTaskDelete(void*) {}
