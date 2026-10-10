#include "HdrDecoder.h"
#include <array>
#include <cstdio>
#include <cstdlib>
#include <exception>
#include <fcntl.h>
#include <string>
#include <unistd.h>
#include <vector>
extern "C" {
#include <libavutil/frame.h>
}

void require(bool value, const char* message) {
    if (!value) { std::fprintf(stderr, "%s\n", message); std::exit(1); }
}
int main(int argc, char** argv) {
    require(argc == 2, "Expected authored fixture directory");
    const std::array<int64_t, 6> pts = {0, 110000, 230000, 400000, 710000, 960000};
    for (const auto& name : {"hlg", "pq"}) {
        const int transfer = std::string(name) == "hlg" ? 18 : 16;
        const std::array<int, 3> luma = std::string(name) == "hlg" ? std::array<int, 3>{16, 123, 235} : std::array<int, 3>{16, 147, 255};
        const std::string path = std::string(argv[1]) + "/" + name + ".mp4";
        int original = open(path.c_str(), O_RDONLY); require(original >= 0, "Authored fixture missing");
        const auto size = lseek(original, 0, SEEK_END);
        // Simulate an AssetFileDescriptor whose video is embedded in a larger file.
        char temporary[] = "/tmp/hanginthere-hdr-test-XXXXXX";
        int fd = mkstemp(temporary); require(fd >= 0, "Test descriptor creation failed");
        unlink(temporary);
        std::vector<uint8_t> bytes(static_cast<size_t>(size));
        require(pread(original, bytes.data(), bytes.size(), 0) == size, "Test fixture read failed"); close(original);
        const std::array<uint8_t, 37> padding = {};
        require(write(fd, padding.data(), padding.size()) == static_cast<ssize_t>(padding.size()), "Test padding write failed");
        require(write(fd, bytes.data(), bytes.size()) == size, "Test media write failed");
        require(write(fd, padding.data(), padding.size()) == static_cast<ssize_t>(padding.size()), "Test suffix write failed");
        for (int repeat = 0; repeat < 2; ++repeat) {
            hanginthere::HdrDecoder decoder(fd, padding.size(), size, transfer);
            for (const auto expected : pts) {
                const auto* frame = decoder.next(); require(frame, "HDR decoder dropped a delayed frame");
                require(decoder.presentationTimeUs() == expected, "HDR decoder changed actual source PTS");
                require(frame->width == 128 && frame->height == 96, "HDR output dimensions changed");
                const std::array<int, 3> x = {20, 64, 108};
                for (size_t i = 0; i < x.size(); ++i) {
                    const int value = frame->data[0][48 * frame->linesize[0] + x[i]];
                    require(std::abs(value - luma[i]) <= 1, "HDR tone mapping differs from independent CLI reference");
                }
                require(frame->data[1][24 * frame->linesize[1] + 32] == 128 && frame->data[2][24 * frame->linesize[2] + 32] == 128,
                        "Neutral HDR chroma changed");
            }
            require(!decoder.next() && !decoder.next(), "HDR EOS published duplicate/extra frames");
        }
        bool rejected = false;
        {
            hanginthere::HdrDecoder cancelled(fd, padding.size(), size, transfer);
            int checks = 0;
            require(!cancelled.next([&] { return ++checks >= 4; }) && checks >= 4, "In-flight packet cancellation failed");
        }
        try { hanginthere::HdrDecoder cancelled(fd, padding.size(), size, transfer, [] { return true; }); }
        catch (const hanginthere::HdrCancelled&) { rejected = true; }
        require(rejected, "Cancelled container opening continued");
        rejected = false;
        try { hanginthere::HdrDecoder wrong(fd, padding.size(), size, transfer == 18 ? 16 : 18); wrong.next(); }
        catch (const std::exception&) { rejected = true; }
        require(rejected, "HDR transfer mismatch was accepted");
        rejected = false;
        try { hanginthere::HdrDecoder truncated(fd, padding.size(), size / 2, transfer); truncated.next(); }
        catch (const std::exception&) { rejected = true; }
        require(rejected, "Truncated HDR container was accepted");
        rejected = false;
        size_t sample = 0;
        for (size_t i = 200; i + 32 < bytes.size(); ++i) {
            if (bytes[i] == 'h' && bytes[i + 1] == 'e' && bytes[i + 2] == 'v' && bytes[i + 3] == '1') { sample = i; break; }
        }
        require(sample > 0 && bytes[sample + 28] == 0 && bytes[sample + 29] == 128, "Authored sample entry layout changed");
        // Keep small CSD unchanged: probing would bypass this oversized header.
        const std::array<uint8_t, 4> oversized = {0x3e, 0x80, 0x27, 0x10};
        require(pwrite(fd, oversized.data(), oversized.size(), padding.size() + sample + 28) == 4, "Oversized test header write failed");
        try { hanginthere::HdrDecoder invalid(fd, padding.size(), size, transfer); }
        catch (const std::exception& error) { rejected = std::string(error.what()) == "Unsupported HDR dimensions"; }
        require(rejected, "Oversized header reached decoder probing/allocation");
        close(fd);
    }
    {
        int fd = open((std::string(argv[1]) + "/color-hlg.mp4").c_str(), O_RDONLY);
        require(fd >= 0, "Colored HDR fixture missing");
        hanginthere::HdrDecoder decoder(fd, 0, -1, 18); close(fd);
        const std::array<std::array<int, 3>, 3> reference = {{{57, 124, 218}, {118, 72, 61}, {45, 205, 109}}};
        const std::array<int, 3> x = {200, 640, 1080};
        for (const auto expected : pts) {
            const auto* frame = decoder.next(); require(frame, "Resized color frame missing");
            require(frame->width == 1280 && frame->height == 76 && decoder.presentationTimeUs() == expected, "HDR resize or PTS changed");
            for (int plane = 0; plane < 3; ++plane) for (size_t i = 0; i < x.size(); ++i) {
                const int divisor = plane ? 2 : 1;
                require(std::abs(frame->data[plane][38 / divisor * frame->linesize[plane] + x[i] / divisor] - reference[i][plane]) <= 1,
                        "Chromatic HDR conversion differs from independent CLI reference");
            }
        }
        require(!decoder.next(), "Resized HDR EOS changed");
    }
    std::puts("HLG/PQ reference pixels, VFR/B-frame drain, rewind, descriptor bounds and rejection checks passed");
}
