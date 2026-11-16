#include <cassert>
#include <cstdint>
#include "ota_policy.hpp"

int main() {
    using namespace ebike;
    assert(!ota_stationary(9'999'999, 0, false));  // includes boot with no pulse
    assert(ota_stationary(10'000'000, 0, false));
    assert(!ota_stationary(20'000'000, 10'000'001, false));  // first raw edge
    assert(!ota_stationary(30'000'000, 10'000'001, true));
    assert(ota_stationary(30'000'000, 10'000'001, false));
    assert(ota_chunk_valid(0, 0, 100, 57));
    assert(ota_chunk_valid(57, 57, 100, 43));  // short final packet
    assert(!ota_chunk_valid(0, 57, 100, 43));  // duplicate / reordered
    assert(!ota_chunk_valid(57, 57, 100, 44));
    assert(!ota_chunk_valid(100, 100, 100, 0));
    assert(!ota_chunk_valid(UINT32_MAX, UINT32_MAX, 100, 2));  // overflow
}
