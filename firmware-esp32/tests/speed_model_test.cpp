#include <cassert>

#include "speed_model.hpp"

int main() {
    // 2.360 m circumference. Values are the requested mapping examples.
    const auto at_31 = ebike::make_speed_plan(2360, 2220, 274000);
    assert(at_31.wheel_speed_centi_kmh == 3100);
    assert(at_31.motor_speed_centi_kmh == 2310);  // 23.10 km/h
    assert(at_31.motor_interval_us == 367792);

    const auto at_423 = ebike::make_speed_plan(2360, 2220, 200851);
    assert(at_423.wheel_speed_centi_kmh == 4230);
    assert(at_423.motor_speed_centi_kmh == 2423);  // 24.23 km/h
    assert(at_423.motor_interval_us == 350639);

    const auto at_542 = ebike::make_speed_plan(2360, 2220, 156752);
    assert(at_542.wheel_speed_centi_kmh == 5420);
    assert(at_542.motor_speed_centi_kmh == 2542);  // 25.42 km/h
    assert(at_542.motor_interval_us == 334225);

    const auto direct = ebike::make_speed_plan(2360, 2220, 386181);
    assert(!direct.simulated);
    assert(direct.motor_interval_us == 386181);
}
