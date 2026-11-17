#pragma once
#include <cstdint>
namespace ebike {
// Requests cross cores; only the wheel task services them on the timer core.
class OtaWakeOutput {
 public:
    virtual ~OtaWakeOutput() = default;
    virtual bool request_ota_wake(uint16_t circumference_mm) = 0;
    virtual void cancel_ota_wake() = 0;
    virtual bool ota_wake_complete() const = 0;
};
constexpr uint32_t ota_wake_interval_us(uint16_t circumference_mm) {
    return uint32_t(circumference_mm) * 1800U; // 2 km/h, mm -> microseconds
}
} // namespace ebike
