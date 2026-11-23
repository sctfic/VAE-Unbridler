#include <cassert>
#include <vector>
#include "ota_stream_buffer.hpp"
using Buffer = ebike::OtaStreamBuffer;
using Result = Buffer::Result;
int main() {
    Buffer b; uint8_t bytes[508]{}; uint8_t out[4096]{};
    constexpr auto window = ebike::kOtaStreamWindow;
    assert(b.append(0, bytes, 508, 0, 100000) == Result::accepted);
    assert(b.append(0, bytes, 508, 0, 100000) == Result::duplicate);
    assert(b.append(1016, bytes, 508, 0, 100000) == Result::gap);
    assert(b.append(250, bytes, 508, 0, 100000) == Result::gap);
    uint32_t fill = 508;
    while (fill < window) {
        const auto n = std::min<size_t>(508, window - fill);
        assert(b.append(fill, bytes, n, 0, 100000) == Result::accepted); fill += static_cast<uint32_t>(n);
    }
    assert(b.append(window, bytes, 508, 0, 100000) == Result::full);
    assert(b.drain(out, 1280) == 1280);
    // Draining RAM does not grant credit before the flash write commits.
    assert(b.append(window, bytes, 508, 0, 100000) == Result::full);
    assert(b.append(window, bytes, 508, 1280, 100000) == Result::accepted);
    assert(b.append(UINT32_MAX, bytes, 1, 1280, 100000) == Result::invalid);
    b.reset();
    std::vector<uint8_t> expected, actual;
    uint32_t accepted = 0, committed = 0;
    for (unsigned i = 0; i < 100; ++i) {
        const unsigned n = 100 + i % 141;
        for (unsigned j = 0; j < n; ++j) bytes[j] = uint8_t(i + j);
        assert(b.append(accepted, bytes, n, committed, 100000) == Result::accepted);
        accepted += n; expected.insert(expected.end(), bytes, bytes+n);
        if (i % 4 == 3) {
            auto count = b.drain(out, sizeof(out)); committed += uint32_t(count);
            actual.insert(actual.end(), out, out+count);
        }
    }
    assert(expected == actual && b.pending() == 0);
}
