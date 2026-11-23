#pragma once
#include <array>
#include <cstdint>
#include <cstddef>
#include <algorithm>
namespace ebike {
constexpr size_t kOtaStreamWindow = 16384; // includes queued and in-flight flash bytes
class OtaStreamBuffer {
 public:
    enum class Result { accepted, duplicate, gap, full, invalid };
    void reset() { head_ = count_ = 0; next_ = 0; }
    Result append(uint32_t offset, const uint8_t* data, size_t length, uint32_t committed, uint32_t total) {
        if (!length || length > 508 || offset > total || length > total - offset || committed > next_) return Result::invalid;
        if (offset < next_) return offset + length <= next_ ? Result::duplicate : Result::gap;
        if (offset > next_) return Result::gap;
        if (next_ - committed + length > kOtaStreamWindow || count_ + length > bytes_.size()) return Result::full;
        for (size_t i = 0; i < length; ++i) bytes_[(head_ + count_ + i) % bytes_.size()] = data[i];
        count_ += length; next_ += static_cast<uint32_t>(length); return Result::accepted;
    }
    size_t drain(uint8_t* out, size_t capacity) {
        const auto n = std::min(count_, capacity);
        for (size_t i = 0; i < n; ++i) out[i] = bytes_[(head_ + i) % bytes_.size()];
        head_ = (head_ + n) % bytes_.size(); count_ -= n; return n;
    }
    size_t pending() const { return count_; }
    uint32_t accepted() const { return next_; }
 private:
    std::array<uint8_t, kOtaStreamWindow> bytes_{};
    size_t head_ = 0, count_ = 0;
    uint32_t next_ = 0;
};
} // namespace ebike
