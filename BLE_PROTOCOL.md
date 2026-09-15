# E-Bike BLE protocol v1

The ESP32 is a BLE GATT peripheral named `E-Bike RT`. UUIDs are stable and little-endian binary payloads are used to avoid parsing latency.

| Item | UUID | Access |
|---|---|---|
| Service | `a6f50000-9b8a-4d9f-a0da-9e9d873d1101` | — |
| Telemetry | `a6f50001-9b8a-4d9f-a0da-9e9d873d1101` | Read + Notify |
| Configuration | `a6f50002-9b8a-4d9f-a0da-9e9d873d1101` | Read + Write |

## Telemetry (`32` bytes)

```text
u8  protocol_version       // 1
u8  flags                  // bit 0: wheel moving, bit 1: simulated output active
u16 sequence
u32 uptime_ms
u32 wheel_interval_us      // 0 when stopped / unknown
u32 motor_interval_us      // interval of the emitted contact pulses
u16 wheel_speed_centi_kmh
u16 motor_speed_centi_kmh
u32 wheel_revolutions
u32 emitted_pulses
u16 circumference_mm
u16 reserved
```

## Configuration (`8` bytes)

```text
u8  protocol_version       // 1
u8  flags                  // reserved, must be 0
u16 circumference_mm       // accepted range: 1000..4000 mm
u16 threshold_centi_kmh    // default 2220 = 22.20 km/h
u16 reserved
```

The firmware stores accepted configuration in NVS and echoes it in telemetry. A client must read the configuration back after a write.
