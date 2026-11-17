#pragma once
#include <cstdint>
namespace ebike::status_led {
constexpr uint32_t update_color(bool first_half) { return first_half ? 0x180600 : 0x100010; }
}
