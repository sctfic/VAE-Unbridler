#pragma once

#include <cstdint>

namespace ebike {

constexpr int kWheelSensorPin = 5;
constexpr int kMotorContactPin = 6;
constexpr int kStatusLedPin = 21;

constexpr uint16_t kProtocolVersion = 1;
constexpr uint16_t kDefaultCircumferenceMm = 2360;
constexpr uint16_t kDefaultThresholdCentiKmh = 2220;
constexpr uint32_t kPulseWidthUs = 2000;

struct RuntimeConfig {
    uint16_t circumference_mm = kDefaultCircumferenceMm;
    uint16_t threshold_centi_kmh = kDefaultThresholdCentiKmh;
};

struct TelemetrySnapshot {
    bool wheel_moving = false;
    bool simulated_output = false;
    uint32_t wheel_interval_us = 0;
    uint32_t motor_interval_us = 0;
    uint16_t wheel_speed_centi_kmh = 0;
    uint16_t motor_speed_centi_kmh = 0;
    uint32_t wheel_revolutions = 0;
    uint32_t emitted_pulses = 0;
    uint16_t circumference_mm = kDefaultCircumferenceMm;
};

#pragma pack(push, 1)
struct BleTelemetryPacket {
    uint8_t protocol_version;
    uint8_t flags;
    uint16_t sequence;
    uint32_t uptime_ms;
    uint32_t wheel_interval_us;
    uint32_t motor_interval_us;
    uint16_t wheel_speed_centi_kmh;
    uint16_t motor_speed_centi_kmh;
    uint32_t wheel_revolutions;
    uint32_t emitted_pulses;
    uint16_t circumference_mm;
    uint16_t reserved;
};

struct BleConfigPacket {
    uint8_t protocol_version;
    uint8_t flags;
    uint16_t circumference_mm;
    uint16_t threshold_centi_kmh;
    uint16_t reserved;
};
#pragma pack(pop)

static_assert(sizeof(BleTelemetryPacket) == 32, "BLE telemetry layout changed");
static_assert(sizeof(BleConfigPacket) == 8, "BLE config layout changed");

}  // namespace ebike
