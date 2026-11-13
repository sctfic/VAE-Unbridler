#pragma once

#include <algorithm>
#include <cstdint>

namespace ebike {

// Task-side timing only: rejected edges must never move the revolution anchor.
class WheelPeriodFilter {
  public:
    enum class Result { ignored, primed, measured };
    static constexpr int64_t kMinimumIntervalUs = 30'000;
    static constexpr int64_t kMaximumIntervalUs = 15'000'000;
    static constexpr uint32_t kMaximumSpeedCentiKmh = 9900;
    static constexpr uint32_t kMaximumAccelerationCentiKmhPerSecond = 1500;

    Result accept(int64_t edge_us, uint16_t circumference_mm = 2360) {
        if (edge_us < 0) return Result::ignored;
        if (last_edge_us_ >= 0) {
            const int64_t interval = edge_us - last_edge_us_;
            if (interval < kMinimumIntervalUs) return Result::ignored;
            if (interval <= kMaximumIntervalUs && !expired(edge_us)) {
                const uint64_t numerator = static_cast<uint64_t>(circumference_mm) * 360000ULL;
                if (numerator > static_cast<uint64_t>(interval) * kMaximumSpeedCentiKmh) {
                    return Result::ignored;
                }
                if (interval_us_ != 0) {
                    const uint64_t speed = numerator / interval;
                    const uint64_t previous_speed = numerator / interval_us_;
                    // Only positive acceleration is limited; braking is accepted.
                    if (speed > previous_speed &&
                        (speed - previous_speed) * 1'000'000ULL >
                            kMaximumAccelerationCentiKmhPerSecond * static_cast<uint64_t>(interval)) {
                        return Result::ignored;
                    }
                }
                last_edge_us_ = edge_us;
                interval_us_ = static_cast<uint32_t>(interval);
                return Result::measured;
            }
        }
        // First edge, or first edge after a gap: acquire a fresh pair rather
        // than reporting the stopped time as a very slow wheel revolution.
        last_edge_us_ = edge_us;
        interval_us_ = 0;
        return Result::primed;
    }

    bool expired(int64_t now_us) const {
        // Before the second edge, allow a complete slow revolution (1 km/h
        // takes 8.5 s with the default wheel), including after a restart.
        const int64_t timeout = interval_us_ == 0 ? kMaximumIntervalUs :
            std::max<int64_t>(1'500'000, static_cast<int64_t>(interval_us_) * 3);
        return last_edge_us_ >= 0 && now_us - last_edge_us_ > timeout;
    }

    uint32_t interval_us() const { return interval_us_; }
    void reset() { last_edge_us_ = -1; interval_us_ = 0; }

  private:
    int64_t last_edge_us_ = -1;
    uint32_t interval_us_ = 0;
};

}  // namespace ebike
