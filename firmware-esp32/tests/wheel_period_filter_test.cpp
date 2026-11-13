#include <cassert>
#include "wheel_period_filter.hpp"
#include "speed_model.hpp"

using Filter = ebike::WheelPeriodFilter;
using Result = Filter::Result;

int main() {
    for (const uint32_t interval : {8'496'000U, 447'157U, 283'200U, 242'742U}) {
        Filter filter;
        assert(filter.accept(0) == Result::primed);
        for (int revolution = 1; revolution <= 20; ++revolution) {
            assert(filter.accept(static_cast<int64_t>(revolution) * interval) == Result::measured);
            assert(filter.interval_us() == interval);
            const auto plan = ebike::make_speed_plan(2360, 2220, filter.interval_us(), false);
            assert(plan.motor_speed_centi_kmh == plan.wheel_speed_centi_kmh);
            assert(plan.motor_interval_us == interval);
        }
    }

    Filter noise;
    assert(noise.accept(100'000) == Result::primed);
    for (const int64_t offset : {1'000, 5'000, 10'000, 29'999}) {
        assert(noise.accept(100'000 + offset) == Result::ignored);
    }
    assert(noise.accept(383'200) == Result::measured);
    assert(noise.interval_us() == 283'200);  // Still exactly 30 km/h.
    assert(noise.accept(380'000) == Result::ignored); // Out-of-order edge.
    assert(noise.interval_us() == 283'200);
    assert(noise.expired(1'883'201));
    assert(noise.accept(3'000'000) == Result::primed); // No bogus 3 km/h after a stop.
    assert(noise.interval_us() == 0);
    assert(noise.accept(3'283'200) == Result::measured);
    assert(noise.interval_us() == 283'200);

    noise.reset();
    assert(!noise.expired(100'000'000));
    assert(noise.accept(100'000'000) == Result::primed);
    assert(!noise.expired(108'496'000)); // Slow departure survives acquisition.
    assert(noise.accept(108'496'000) == Result::measured);
    assert(noise.interval_us() == 8'496'000);
    assert(noise.accept(124'000'001) == Result::primed); // Interval over 15 s.
    assert(noise.accept(124'283'201) == Result::measured);
    assert(noise.interval_us() == 283'200);
    assert(noise.accept(-1) == Result::ignored);

    Filter empty;
    assert(empty.accept(0) == Result::primed);
    assert(!empty.expired(15'000'000));
    assert(empty.expired(15'000'001));

    for (const auto circumference : {uint16_t{1000}, uint16_t{2360}, uint16_t{4000}}) {
        Filter capped;
        assert(capped.accept(0, circumference) == Result::primed);
        const uint64_t numerator = static_cast<uint64_t>(circumference) * 360000ULL;
        const int64_t shortest = (numerator + 9899) / 9900;
        assert(capped.accept(shortest - 1, circumference) == Result::ignored);
        assert(capped.interval_us() == 0);
        assert(capped.accept(shortest, circumference) == Result::measured);
    }

    Filter acceleration;
    assert(acceleration.accept(0) == Result::primed);
    assert(acceleration.accept(424'800) == Result::measured); // 20 km/h.
    // Replay the bogus 74.94 km/h interval seen in the real logs.
    assert(acceleration.accept(424'800 + 113'356) == Result::ignored);
    assert(acceleration.interval_us() == 424'800);
    const auto retained = ebike::make_speed_plan(2360, 2220, acceleration.interval_us(), false);
    assert(retained.motor_interval_us == 424'800);
    assert(retained.motor_speed_centi_kmh == 2000);
    assert(acceleration.accept(849'600) == Result::measured);
    assert(acceleration.interval_us() == 424'800);
    assert(acceleration.accept(849'600 + 386'181) == Result::measured); // 22 km/h allowed.
    const int64_t at22 = 849'600 + 386'181;
    assert(acceleration.accept(at22 + 283'200) == Result::ignored); // 30: +8 in 0.283 s.
    assert(acceleration.interval_us() == 386'181);
    assert(acceleration.accept(at22 + 849'600) == Result::measured); // Hard brake to 10.
    assert(acceleration.interval_us() == 849'600);

    Filter extremes;
    assert(extremes.accept(0) == Result::primed);
    assert(extremes.accept(283'200) == Result::measured);
    for (const int64_t spike : {47'771, 54'485, 113'356}) {
        assert(extremes.accept(283'200 + spike) == Result::ignored);
        assert(extremes.interval_us() == 283'200);
    }
    assert(extremes.accept(566'400) == Result::measured);
    assert(extremes.interval_us() == 283'200);
    assert(extremes.expired(2'066'401)); // Noise does not postpone stop forever.
    extremes.reset();
    assert(extremes.interval_us() == 0);
    assert(extremes.accept(3'000'000) == Result::primed);
    assert(extremes.accept(3'242'742) == Result::measured);
}
