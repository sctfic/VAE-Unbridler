#pragma once
#include <cstdint>

#define EBIKE_FIRMWARE_VERSION_CODE 3
#define EBIKE_STRINGIFY_INNER(value) #value
#define EBIKE_STRINGIFY(value) EBIKE_STRINGIFY_INNER(value)
namespace ebike {
constexpr uint16_t kHardwareRevision = 0x0100; // V1.0 open-drain hardware
constexpr uint32_t kFirmwareVersionCode = EBIKE_FIRMWARE_VERSION_CODE;
constexpr char kFirmwareOtaIdentity[] = "EBIKE-OTA-CODE:" EBIKE_STRINGIFY(EBIKE_FIRMWARE_VERSION_CODE) ":END";
}  // namespace ebike
