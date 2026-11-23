#include <cassert>
#include <vector>
#include "ota_stream_buffer.hpp"
using Buffer = ebike::OtaStreamBuffer;
using Result = Buffer::Result;
int main() {
    Buffer b; uint8_t bytes[240]{}; uint8_t out[4096]{};
    assert(b.append(0, bytes, 240, 0, 10000) == Result::accepted);
    assert(b.append(0, bytes, 240, 0, 10000) == Result::duplicate);
    assert(b.append(480, bytes, 240, 0, 10000) == Result::gap);
    assert(b.append(120, bytes, 240, 0, 10000) == Result::gap);
    for (unsigned i = 1; i < 16; ++i) assert(b.append(i*240, bytes, 240, 0, 10000) == Result::accepted);
    assert(b.append(3840, bytes, 240, 0, 10000) == Result::full);
    assert(b.drain(out, 1280) == 1280);
    // RAM freed by a flash worker is NOT an ACK until flash has committed it.
    assert(b.append(3840, bytes, 240, 0, 10000) == Result::full);
    assert(b.append(3840, bytes, 240, 1280, 10000) == Result::accepted);
    assert(b.append(UINT32_MAX, bytes, 1, 1280, 10000) == Result::invalid);
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
