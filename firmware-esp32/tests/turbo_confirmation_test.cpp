#include <cassert>
#include <initializer_list>
#include "turbo_confirmation.hpp"
#include "pulse_schedule.hpp"
int main() {
    ebike::TurboConfirmation c;
    // Standard never delays, including high speed.
    assert(c.accept(0, 283200, 2360, false) == 283200);
    assert(c.state() == 0);
    // >20 enters; one lookahead on wheel periods, not Turbo motor periods.
    assert(c.accept(283200, 283200, 2360, true) == 0);
    assert(c.state() == 1);
    assert(c.accept(566400, 283200, 2360, true) == 283200);
    assert(c.state() == 2);
    // An invalid next edge cannot confirm the pending measurement.
    c.reject();
    assert(c.accept(849600, 283200, 2360, true) == 0);
    assert(c.accept(1132800, 283200, 2360, true) == 283200);
    // Braking is valid physically, but must not confirm an incoherent faster P.
    assert(c.accept(1579957, 447157, 2360, true) == 0); // 19 km/h, hysteresis holds
    assert(c.state() == 1);
    assert(c.accept(2027114, 447157, 2360, true) == 447157);
    assert(c.accept(2527114, 500000, 2360, true) == 500000); // <18 exits immediately
    assert(c.state() == 0);
    assert(c.accept(2974271, 447157, 2360, true) == 447157); // 19 remains inactive
    assert(c.accept(3354271, 380000, 2360, true) == 0);
    assert(c.accept(3734271, 380000, 2360, false) == 380000); // mode change
    c.reset();
    assert(c.accept(0, 8496000, 2360, true) == 8496000); // slow start, no delay

    c.reset();
    assert(c.accept(0, 424800, 2360, true) == 424800); // exactly 20: inactive
    assert(c.accept(424000, 424000, 2360, true) == 0); // just above 20
    assert(c.accept(848800, 424800, 2360, true) == 424000); // exactly 20: active
    c.accept(1320800, 472000, 2360, true); // exactly 18: still active
    assert(c.state() != 0);
    assert(c.accept(1793800, 473000, 2360, true) == 473000); // below 18: exits
    assert(c.state() == 0);

    // Cadence changes always reference the actual last rising edge.
    using ebike::next_pulse_start;
    assert(next_pulse_start(100000, 110000, 400000) == 500000);
    assert(next_pulse_start(100000, 120000, 300000) == 400000);
    assert(next_pulse_start(100000, 400000, 300000) == 400020); // one beat, then anchor the next to its actual edge
    assert(next_pulse_start(0, 100000, 300000) == 100020);
    uint64_t last = 100000, now = last;
    for (const uint32_t interval : {400000U, 300000U, 500000U, 283200U}) {
        auto next = next_pulse_start(last, now + 10000, interval);
        assert(next - last == interval);
        last = now = next;
    }
}
