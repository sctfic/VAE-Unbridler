#include <cassert>
#include <vector>
#include "pulse_generator.hpp"
#include "telemetry_store.hpp"

struct FakeTimer {
    uint64_t now = 100000, alarm = 0;
    bool armed = false;
    gptimer_event_callbacks_t callbacks{};
    void* context = nullptr;
} timer;
std::vector<uint64_t> rising;
bool closed = false;
int64_t esp_timer_get_time() { return timer.now + 7000000; } // distinct timer epochs
void host_contact(bool value) {
    if (value) { assert(!closed); rising.push_back(timer.now); }
    closed = value;
}
int gptimer_new_timer(const gptimer_config_t*, gptimer_handle_t* result) { *result = &timer; return 0; }
int gptimer_register_event_callbacks(gptimer_handle_t, const gptimer_event_callbacks_t* cb, void* ctx) {
    timer.callbacks = *cb; timer.context = ctx; return 0;
}
int gptimer_set_alarm_action(gptimer_handle_t, const gptimer_alarm_config_t* alarm) {
    timer.armed = alarm != nullptr; timer.alarm = alarm ? alarm->alarm_count : 0; return 0;
}
int gptimer_get_raw_count(gptimer_handle_t, uint64_t* now) { *now = timer.now; return 0; }
void fire(uint64_t late = 0) {
    assert(timer.armed); assert(timer.alarm >= timer.now);
    gptimer_alarm_event_data_t event{timer.alarm}; timer.now = timer.alarm + late;
    timer.callbacks.on_alarm(&timer, &event, timer.context);
}
int main() {
    ebike::TelemetryStore telemetry;
    ebike::PulseGenerator pulse(telemetry);
    pulse.begin(); assert(!closed && rising.empty());
    pulse.retain_until(esp_timer_get_time() + 1500000);
    pulse.set_output_interval(400000, 2124, false);
    fire(); assert(closed); const auto first = timer.now;
    assert(telemetry.snapshot().emitted_motor_us == first + 7000000);
    // Changing cadence during the 2 ms contact must not truncate or duplicate it.
    pulse.set_output_interval(300000, 2832, false);
    fire(); assert(!closed && timer.now == first + 2000);
    assert(timer.alarm == first + 300000);
    const auto unchanged_alarm = timer.alarm;
    pulse.set_output_interval(300000, 2832, false);
    assert(timer.alarm == unchanged_alarm);
    // A late ISR anchors the NEXT beat to the actual GPIO edge.
    fire(7000); const auto second = timer.now; fire();
    assert(timer.alarm == second + 300000);
    // Mode/cadence changes keep one continuous phase reference.
    timer.now += 10000; pulse.set_output_interval(400000, 2124, true);
    assert(timer.alarm == second + 400000);
    fire(); fire();
    for (unsigned i = 1; i < rising.size(); ++i) assert(rising[i] - rising[i-1] >= 300000);
    // Rejected/pending input does not call the scheduler; guard stops autonomously.
    while (timer.armed) fire();
    const auto count = rising.size();
    assert(!closed && rising.back() <= 1600000);
    // Resume after a stop: one pulse, never replay all overdue periods.
    timer.now += 1000000;
    pulse.retain_until(esp_timer_get_time() + 1500000);
    const auto resumed = timer.now;
    pulse.set_output_interval(300000, 2832, false);
    assert(timer.alarm == resumed + 20);
    fire(); assert(rising.size() == count + 1);
    pulse.stop(); assert(closed); fire(); assert(!closed && !timer.armed);
    // Ring overflow counts loss without overwriting captured wheel timestamps.
    telemetry.set_circumference(2200);
    for (unsigned i = 0; i < 17; ++i) telemetry.record_event(i + 1, true, 1, 1);
    assert(telemetry.snapshot().telemetry_drops == 1);
    ebike::BleEventPayload p{}; uint8_t type;
    for (unsigned i = 0; i < 16; ++i) {
        assert(telemetry.pop_event(p, type)); assert(type == 1); assert(p.circumference_mm == 2200);
        assert(p.timestamp_us == i + 1 && p.wheel_timestamp_us == i + 1);
    }
    assert(!telemetry.pop_event(p, type));
}
