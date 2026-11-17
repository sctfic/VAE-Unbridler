#pragma once
#include <cstdint>

namespace ebike {
// Wheel periods ONLY. No transformed motor interval may enter this policy.
class TurboConfirmation {
 public:
    uint32_t accept(int64_t at, uint32_t interval, uint16_t circumference, bool turbo) {
        const uint64_t n = uint64_t(circumference) * 360000;
        const uint64_t speed = n / interval;
        if (!turbo || speed < 1800) reset();
        else if (speed > 2000) active_ = true;
        if (!active_) return interval;
        uint32_t result = 0;
        confirmed_ = false;
        if (pending_ && at > pending_at_) {
            const uint64_t previous = n / pending_;
            const uint64_t difference = speed > previous ? speed - previous : previous - speed;
            if (difference * 1000000 <= 1500 * uint64_t(at - pending_at_)) {
                result = pending_;
                confirmed_ = true;
            }
        }
        pending_ = interval; pending_at_ = at;
        return result;
    }
    void reject() { pending_ = 0; confirmed_ = false; }
    void reset() { active_ = false; reject(); }
    uint8_t state() const { return !active_ ? 0 : confirmed_ ? 2 : 1; }
 private:
    bool active_ = false, confirmed_ = false;
    uint32_t pending_ = 0;
    int64_t pending_at_ = 0;
};
} // namespace ebike
