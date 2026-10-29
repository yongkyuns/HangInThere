#pragma once

#include <cstdint>
#include <memory>
#include <functional>
#include <exception>

struct AVFrame;

namespace hanginthere {
class HdrCancelled : public std::exception {};
// Owns a duplicated, seekable descriptor and one decoded/filter-output frame.
// Calls are serialized by the replay owner; no pixels or source paths are logged.
class HdrDecoder {
public:
    HdrDecoder(int fd, int64_t offset, int64_t length, int expectedTransfer, const std::function<bool()>& cancelled = {});
    ~HdrDecoder();
    HdrDecoder(const HdrDecoder&) = delete;
    HdrDecoder& operator=(const HdrDecoder&) = delete;
    const AVFrame* next(const std::function<bool()>& cancelled = {});
    int64_t presentationTimeUs() const;
private:
    struct State;
    std::unique_ptr<State> state;
};
}
