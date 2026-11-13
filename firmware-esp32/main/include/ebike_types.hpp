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
