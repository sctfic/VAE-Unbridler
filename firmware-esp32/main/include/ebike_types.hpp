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
    uint32_t telemetry_drops = 0;
    int64_t wheel_task_heartbeat_us = 0;
    uint32_t wheel_edges = 0;
    bool sensor_interval_valid = false;
    uint32_t sensor_interval_us = 0;
    uint32_t sensor_edges = 0;
    uint32_t rejected_edges = 0;
    uint32_t dropped_edges = 0;
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
struct BleEventPayload {
    uint64_t session;
    uint64_t timestamp_us;
    uint64_t wheel_timestamp_us;
    uint64_t motor_timestamp_us;
    uint32_t wheel_interval_us;
    uint32_t motor_interval_us;
    uint32_t revolutions;
    uint32_t emitted;
    uint32_t rejected;
    uint32_t dropped;
    uint16_t circumference_mm;
    uint8_t flags;
    uint8_t confirmation;
    uint32_t telemetry_drops;
};
struct BleEventFragment {
    uint8_t version = 2;
    uint8_t type_index = 0;
    uint16_t sequence = 0;
    uint8_t data[16]{};
};
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

// Optional characteristic: preserves the legacy 32-byte telemetry layout.
struct BleSensorDiagnosticsPacket {
    uint8_t protocol_version;
    uint8_t flags;  // bit 0: raw interval valid; bits 1..3: cap/acceleration/hold available
    uint16_t sequence;
    uint32_t sensor_interval_us;
    uint32_t sensor_edges;
    uint32_t rejected_edges;
    uint32_t dropped_edges;
};
#pragma pack(pop)

static_assert(sizeof(BleEventPayload) == 64, "Event payload layout");
static_assert(sizeof(BleEventFragment) == 20, "ATT MTU 23 required");

inline BleEventPayload make_event(const TelemetrySnapshot& s, uint64_t at, bool turbo) {
    return {0, at, s.accepted_wheel_us, s.emitted_motor_us, s.wheel_interval_us,
        s.motor_interval_us, s.wheel_revolutions, s.emitted_pulses, s.rejected_edges,
        s.dropped_edges, s.circumference_mm,
        static_cast<uint8_t>((s.wheel_moving ? 1 : 0) | (s.simulated_output ? 2 : 0) |
            (turbo ? 4 : 0) | 8 | (s.motor_timestamp_valid ? 16 : 0)), s.confirmation, s.telemetry_drops};
}

static_assert(sizeof(BleTelemetryPacket) == 32, "BLE telemetry layout changed");
static_assert(sizeof(BleConfigPacket) == 8, "BLE config layout changed");
static_assert(sizeof(BleSensorDiagnosticsPacket) == 20, "BLE sensor diagnostics layout changed");

inline BleSensorDiagnosticsPacket make_sensor_diagnostics(const TelemetrySnapshot& state,
                                                           uint16_t sequence) {
    return {static_cast<uint8_t>(kProtocolVersion),
            static_cast<uint8_t>(0x0e | (state.sensor_interval_valid ? 1 : 0)), sequence,
            state.sensor_interval_us, state.sensor_edges, state.rejected_edges, state.dropped_edges};
}

}  // namespace ebike
