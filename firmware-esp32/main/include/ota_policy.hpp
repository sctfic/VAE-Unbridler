#pragma once
#include <cstdint>

namespace ebike {
constexpr int64_t kOtaQuietUs = 10'000'000;
inline bool ota_stationary(int64_t now_us, int64_t last_edge_us, bool moving) {
    return !moving && now_us >= kOtaQuietUs && now_us - last_edge_us >= kOtaQuietUs;
}
inline bool ota_chunk_valid(uint32_t offset, uint32_t received, uint32_t size, uint32_t length) {
    return length > 0 && received <= size && offset == received && length <= size - received;
}
}  // namespace ebike
