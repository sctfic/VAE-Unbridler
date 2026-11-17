# Protocole BLE : événements v2 (firmware 1.0.3 / Android 0.4.24)

Le protocole courant est décrit dans [BLE_EVENTS_V2.md](BLE_EVENTS_V2.md).
Les mesures utilisent `a6f50007-9b8a-4d9f-a0da-9e9d873d1101` (Notify),
en quatre fragments de 20 octets, y compris avec ATT MTU 23.
Configuration, lecture de version et OTA gardent leurs UUID et formats existants.
Les notifications périodiques 0001/0003 sont supprimées sur le nouveau firmware.
La lecture 0001 porte la version 2 pour être explicitement refusée par les
anciens décodeurs. Android détecte 0007 avant de s’abonner ; sans 0007, il peut
encore lire les télémétries v1 d’un ancien firmware (MTU >=35), sans inventer
d’horodatages d’impulsions manquants. Le mode d’estimation entre impulsions
nécessite le firmware v2.

Le reste de ce document décrit **l’ancien protocole v1**, conservé pour référence.

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

## Firmware version (optional read)

Characteristic `a6f50004-9b8a-4d9f-a0da-9e9d873d1101` is read-only. Its payload is the firmware application version from `esp_app_get_description()->version`, encoded as UTF-8 without a trailing NUL (for example `1.0.1`). Android reads it once per connection, after notification subscriptions and before configuration writes. Older firmware without this characteristic remains compatible and displays an unavailable version.

## OTA extension v1 (hardware V1.0, firmware 1.0.2+)

Existing telemetry/configuration layouts and UUIDs remain unchanged. Legacy
firmware does not expose the OTA characteristics and requires USB provisioning.
Android requests MTU 247, requires at least MTU 51 for OTA, serializes GATT
requests and waits for every write response. No write-without-response is used.

Read OTA control (`48` bytes, little-endian):

```text
u8  ota_protocol           // 1
u8  state                  // 0 idle, 1 receiving, 2 verified, 3 rebooting, 4 error
u8  error                  // 0 none, 1 motion, 2 format/order/size, 3 flash/image,
                           // 4 SHA-256 mismatch, 5 inactivity timeout, 6 cancelled/disconnected
u8  flags                  // bit 0 supported, bit 1 stationary >=10s, bit 2 boot confirmed
u32 received
u32 image_size
u32 installed_version_code
u8  running_slot           // 0 or 1
u8  next_slot              // 1 or 0
u16 hardware_revision      // 0x0100 = hardware V1.0
u32 next_slot_capacity
char installed_version[24] // NUL-padded UTF-8
```

Write OTA control:

- `01 + u32 size + u8 sha256[32] + u32 version_code` (`41` bytes): begin;
  requires confirmed running firmware, increasing code, size within the inactive
  slot and ten seconds without raw wheel edges. Sets Standard mode.
- `02` (`1` byte): finish; requires exact size and SHA-256; validates ESP-IDF
  image/chip/project. Does not yet change the boot partition.
- `04` (`1` byte): activate a verified image; rechecks movement, selects the
  new boot partition and restarts after 1.5 seconds. Movement before restart
  restores the running partition and cancels activation.
- `03` (`1` byte): abort, close the OTA handle and discard the session.

Write OTA data: `u32 offset + u8 bytes[1..240]`; the total ATT value must fit
`MTU - 3`. The application uses payloads of `min(MTU - 7, 240)` bytes. Offsets
must exactly equal `received`, with no gaps, overlaps or data beyond declared
size. An ATT write response acknowledges bytes written, not boot success.
Status is also read every approximately 16 KiB and after each control stage.

The server cancels an active session on any raw wheel edge, on disconnect
before activation or after 30 seconds without data. New attempts start at offset
zero. Configuration writes are rejected while OTA is receiving/verified/rebooting.
After restart, read status again and verify version/code, opposite slot and boot
confirmation. See [OTA.md](OTA.md) for boot validation, rollback and publication.

OTA UUIDs: control `a6f50005-9b8a-4d9f-a0da-9e9d873d1101`; data `a6f50006-9b8a-4d9f-a0da-9e9d873d1101`. Diagnostics/version UUIDs 03/04 are preserved.
