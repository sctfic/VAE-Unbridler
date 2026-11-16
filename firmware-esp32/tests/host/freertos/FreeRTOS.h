#pragma once
#include <cassert>
using portMUX_TYPE = int;
#define portMUX_INITIALIZER_UNLOCKED 0
#define portMAX_DELAY 0xffffffff
#define configASSERT(value) assert(value)
#define portENTER_CRITICAL(lock) ((void)(lock))
#define portEXIT_CRITICAL(lock) ((void)(lock))
#define portENTER_CRITICAL_ISR(lock) ((void)(lock))
#define portEXIT_CRITICAL_ISR(lock) ((void)(lock))
