#pragma once
#include <cstdint>
namespace ebike {
// At most one overdue beat: never replay missed alarms. Actual rising edges
// (not programmed alarms) are the only phase reference.
inline uint64_t next_pulse_start(uint64_t last, uint64_t now, uint32_t interval) {
    if (!last) return now + 20;
    const uint64_t candidate = last + interval;
    return candidate > now + 20 ? candidate : now + 20;
}
} // namespace ebike
