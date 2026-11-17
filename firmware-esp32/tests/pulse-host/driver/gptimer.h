#pragma once
#include <cstdint>
#include "esp_check.h"
struct FakeTimer;
using gptimer_handle_t = FakeTimer*;
struct gptimer_alarm_event_data_t { uint64_t alarm_value; };
struct gptimer_alarm_config_t { uint64_t reload_count, alarm_count; struct { bool auto_reload_on_alarm; } flags; };
struct gptimer_config_t { int clk_src, direction; uint32_t resolution_hz; };
struct gptimer_event_callbacks_t { bool (*on_alarm)(gptimer_handle_t, const gptimer_alarm_event_data_t*, void*); };
constexpr int GPTIMER_CLK_SRC_DEFAULT = 0, GPTIMER_COUNT_UP = 0;
int gptimer_new_timer(const gptimer_config_t*, gptimer_handle_t*);
int gptimer_register_event_callbacks(gptimer_handle_t, const gptimer_event_callbacks_t*, void*);
int gptimer_set_alarm_action(gptimer_handle_t, const gptimer_alarm_config_t*);
int gptimer_get_raw_count(gptimer_handle_t, uint64_t*);
inline int gptimer_enable(gptimer_handle_t) { return 0; }
inline int gptimer_start(gptimer_handle_t) { return 0; }
