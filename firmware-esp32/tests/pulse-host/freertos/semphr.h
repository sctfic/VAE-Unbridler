#pragma once
#include "freertos/FreeRTOS.h"
using SemaphoreHandle_t = void*;
inline void* xSemaphoreCreateBinary() { return reinterpret_cast<void*>(1); }
inline void xSemaphoreTake(void*, unsigned) {}
inline void xSemaphoreGive(void*) {}
inline void vSemaphoreDelete(void*) {}
