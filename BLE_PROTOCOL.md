# E-Bike BLE protocol v1

The ESP32 is a BLE GATT peripheral named `E-Bike RT`. UUIDs are stable and little-endian binary payloads are used to avoid parsing latency.

| Item | UUID | Access |
|---|---|---|
| Service | `a6f50000-9b8a-4d9f-a0da-9e9d873d1101` | — |
| Telemetry | `a6f50001-9b8a-4d9f-a0da-9e9d873d1101` | Read + Notify |
| Configuration | `a6f50002-9b8a-4d9f-a0da-9e9d873d1101` | Read + Write |
| Sensor diagnostics (optional) | `a6f50003-9b8a-4d9f-a0da-9e9d873d1101` | Read + Notify |

## Telemetry (`32` bytes)

```text
u8  protocol_version       // 1
u8  flags                  // bit 0: moving, bit 1: simulated output, bit 2: Turbo enabled, bit 3: mode control supported
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

## Optional sensor diagnostics (`20` bytes, since app 0.4.18)

```text
u8  protocol_version       // 1
u8  flags                  // bit 0: raw interval valid; bit 1: 99 km/h cap; bit 2: +15 km/h/s gate; bit 3: retained cadence
u16 sequence               // same as the paired telemetry notification
u32 sensor_interval_us     // latest interval between ALL falling GPIO 5 edges
u32 sensor_edges           // all GPIO ISR edges, including rejected/dropped edges
u32 rejected_edges         // edges rejected by the task's period filter
u32 dropped_edges          // edges lost because the task queue was full
```

The raw interval is measured in the GPIO ISR before filtering, including bounce
and edges that fail to enter the task queue. It saturates at `0xffffffff` after a
gap longer than about 71 minutes. Counts wrap modulo 2^32 and reset on ESP32 boot.
Rejected/dropped counters count different causes; the first edge used to acquire
a new period is neither a rejected edge nor a completed wheel revolution.

Notifications are snapshots every 200 ms, using the same state snapshot and
sequence as the associated telemetry packet. This is the latest raw interval,
not a lossless trace of every edge. If `sensor_edges` stays unchanged, a repeated
interval is the previous measurement, not a new pulse. Stop does not erase these
diagnostics. Android writes `SENSOR ... rawIntervalUs=...` lines in the existing
private diagnostic journal; `BLE ... wheelUs=... motorUs=...` records the retained
wheel interval and the commanded motor interval in microseconds.

The existing 32-byte telemetry packet is unchanged. Older apps ignore this
characteristic; newer apps continue to work with older firmware and record
`SENSOR diagnostics=unavailable`. Raw intervals require both updates and a new
BLE service discovery after flashing.

## Configuration (`8` bytes)

```text
u8  protocol_version       // 1
u8  flags                  // 0: preserve mode (legacy), 2: Standard, 3: Turbo; other values rejected
u16 circumference_mm       // accepted range: 1000..4000 mm
u16 threshold_centi_kmh    // default 2220 = 22.20 km/h
u16 reserved
```

Wheel settings are stored in NVS. The mode is RAM-only: Standard on every ESP32 boot,
preserved across Bluetooth disconnects. Configuration reads return flags 2 or 3.
Telemetry bit 3 identifies firmware with mode control; never send mode commands to
legacy firmware. The app displays the mode reported in telemetry, not an optimistic
local toggle. Turbo may be enabled without simulation being active (stopped or below
threshold). A mode change recomputes the motor period from the last valid wheel
period on the real-time task within its 100 ms polling period; a contact already
closed finishes its normal 2 ms pulse. Both modes retain a continuous motor cadence
through rejected input edges, until a new valid period or the normal stop timeout.
Standard reports simulated-output=false even while retaining this cadence. Periods
above 99 km/h and positive accelerations above 15 km/h per second are rejected;
decelerations are not limited by the acceleration check. A newly acquired period
has no previous speed for the acceleration check. Timing must be verified
on a bench after flashing; the UI acknowledgement reports the requested firmware mode.
