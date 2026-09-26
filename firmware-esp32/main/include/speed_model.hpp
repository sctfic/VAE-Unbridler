#pragma once

#include <cstdint>

#include "ebike_types.hpp"

namespace ebike {

struct SpeedPlan {
    uint16_t wheel_speed_centi_kmh = 0;
    uint16_t motor_speed_centi_kmh = 0;
    uint32_t motor_interval_us = 0;
    bool simulated = false;
};

// All computation stays in integer centi-km/h and microseconds. This avoids
// floating-point jitter in the task that supplies the hardware timer.
inline SpeedPlan make_speed_plan(uint16_t circumference_mm, uint16_t threshold_centi_kmh,
                                 uint32_t wheel_interval_us, bool speed_enabled = true) {
    SpeedPlan plan{};
    if (wheel_interval_us == 0) return plan;
    const uint64_t numerator = static_cast<uint64_t>(circumference_mm) * 360000ULL;
    const uint64_t wheel_speed = numerator / wheel_interval_us;
    plan.wheel_speed_centi_kmh = static_cast<uint16_t>(wheel_speed > 65535 ? 65535 : wheel_speed);
    if (!speed_enabled || plan.wheel_speed_centi_kmh <= threshold_centi_kmh) {
        plan.motor_speed_centi_kmh = plan.wheel_speed_centi_kmh;
        plan.motor_interval_us = wheel_interval_us;
        return plan;
    }

    plan.simulated = true;
    plan.motor_speed_centi_kmh = static_cast<uint16_t>(2000 + plan.wheel_speed_centi_kmh / 10);
    plan.motor_interval_us = static_cast<uint32_t>(numerator / plan.motor_speed_centi_kmh);
    return plan;
}

}  // namespace ebike
