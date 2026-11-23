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
    int64_t last_wheel_edge_us = 0;
    uint64_t accepted_wheel_us = 0;
    uint64_t emitted_motor_us = 0;
    bool motor_timestamp_valid = false;
    uint8_t confirmation = 0;
    int64_t wheel_task_heartbeat_us = 0;
    uint32_t wheel_edges = 0;
    bool wheel_moving = false;
    bool simulated_output = false;
    uint32_t wheel_interval_us = 0;
    uint32_t motor_interval_us = 0;
    uint16_t wheel_speed_centi_kmh = 0;
    uint16_t motor_speed_centi_kmh = 0;
    uint32_t wheel_revolutions = 0;
    uint16_t circumference_mm = kDefaultCircumferenceMm;
};

#pragma pack(push, 1)
struct BleEventPayload {
    uint64_t session;
    uint64_t timestamp_us;
    uint64_t wheel_timestamp_us;
    uint64_t motor_timestamp_us;
    uint32_t wheel_interval_us;
    uint32_t motor_interval_us;
    uint32_t revolutions;
    uint16_t circumference_mm;
    uint8_t flags;
    uint8_t confirmation;
};
struct BleEventPacket {
    uint8_t version = 3;
    uint8_t type = 0;
    uint16_t sequence = 0;
    BleEventPayload data{};
};
struct BleConfigPacket {
    uint8_t protocol_version;
    uint8_t flags;
    uint16_t circumference_mm;
    uint16_t threshold_centi_kmh;
    uint16_t reserved;
};

#pragma pack(pop)

static_assert(sizeof(BleEventPayload) == 48, "Event payload layout");
static_assert(sizeof(BleEventPacket) == 52, "ATT MTU 55 required");

inline BleEventPayload make_event(const TelemetrySnapshot& s, uint64_t at, bool turbo) {
    return {0, at, s.accepted_wheel_us, s.emitted_motor_us, s.wheel_interval_us,
        s.motor_interval_us, s.wheel_revolutions, s.circumference_mm,
        static_cast<uint8_t>((s.wheel_moving ? 1 : 0) | (s.simulated_output ? 2 : 0) |
            (turbo ? 4 : 0) | (s.motor_timestamp_valid ? 8 : 0)), s.confirmation};
}

static_assert(sizeof(BleConfigPacket) == 8, "BLE config layout changed");
}  // namespace ebike
