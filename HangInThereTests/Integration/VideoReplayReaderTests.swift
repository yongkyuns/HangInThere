import AVFoundation
import Testing
@testable import HangInThere

@Suite(.serialized)
struct VideoReplayReaderTests {
    @Test func decodedFramesKeepActualVariableRateTimestamps() async throws {
        let url = try await VideoTestSupport.makeVideo()
        defer { try? FileManager.default.removeItem(at: url) }
        let reader = VideoReplayReader()
        _ = try await reader.open(url)
        var seconds: [Double] = []
        while let frame = try await reader.nextFrame() {
            seconds.append(frame.pose.timestamp.seconds)
            #expect(frame.image.width == 160 && frame.image.height == 96)
            #expect(frame.pose.imageSize == ImageSize(width: 160, height: 96))
            #expect(frame.pose.people.isEmpty, "Four solid quadrants must not fabricate a human skeleton.")
        }
        await reader.close()
        #expect(seconds.count == VideoTestSupport.timestamps.count)
        for (actual, expected) in zip(seconds, VideoTestSupport.timestamps) {
            #expect(abs(actual - expected.seconds) < 1e-6)
        }
    }

    @Test func actualPortraitPixelsAreOrientedOnceAndMatchPoseDimensions() async throws {
        let url = try await VideoTestSupport.makeVideo(transform: CGAffineTransform(a: 0, b: 1, c: -1, d: 0, tx: 96, ty: 0))
        defer { try? FileManager.default.removeItem(at: url) }
        let reader = VideoReplayReader()
        _ = try await reader.open(url)
        let next = try await reader.nextFrame()
        let frame = try #require(next)
        #expect(frame.image.width == 96 && frame.image.height == 160)
        #expect(frame.pose.imageSize == ImageSize(width: 96, height: 160))
        let topLeft = VideoTestSupport.rgb(frame.image, normalizedTopLeft: Point2D(x: 0.25, y: 0.25))
        let topRight = VideoTestSupport.rgb(frame.image, normalizedTopLeft: Point2D(x: 0.75, y: 0.25))
        #expect(topLeft[2] > 180 && topLeft[0] < 50 && topLeft[1] < 50) // blue
        #expect(topRight[0] > 180 && topRight[1] < 50 && topRight[2] < 50) // red
        await reader.close()
    }

    @Test func rewindRestoresFirstFrameAndClosePreventsFurtherReads() async throws {
        let url = try await VideoTestSupport.makeVideo()
        defer { try? FileManager.default.removeItem(at: url) }
        let reader = VideoReplayReader()
        _ = try await reader.open(url)
        _ = try await reader.nextFrame()
        let second = try await reader.nextFrame()
        #expect(second?.pose.timestamp.seconds == 0.1)
        _ = try await reader.rewind()
        let first = try await reader.nextFrame()
        #expect(first?.pose.timestamp.seconds == 0)
        await reader.close()
        do {
            _ = try await reader.nextFrame()
            Issue.record("Reading after close unexpectedly succeeded.")
        } catch ReplayError.notOpen {
            // Expected lifecycle error.
        }
    }

    @Test func replacingVideoTransfersFreshDecoderGeometryAndTimeline() async throws {
        let landscape = try await VideoTestSupport.makeVideo()
        defer { try? FileManager.default.removeItem(at: landscape) }
        let portrait = try await VideoTestSupport.makeVideo(
            transform: CGAffineTransform(a: 0, b: 1, c: -1, d: 0, tx: 96, ty: 0))
        defer { try? FileManager.default.removeItem(at: portrait) }
        let reader = VideoReplayReader()
        _ = try await reader.open(landscape)
        _ = try await reader.nextFrame()
        let oldSecond = try await reader.nextFrame()
        #expect(oldSecond?.pose.timestamp.seconds == 0.1)

        _ = try await reader.open(portrait)
        let newFirst = try await reader.nextFrame()
        let frame = try #require(newFirst)
        #expect(frame.pose.timestamp.seconds == 0)
        #expect(frame.image.width == 96 && frame.image.height == 160)
        #expect(frame.pose.imageSize == ImageSize(width: 96, height: 160))
        await reader.close()
        // Import ownership must never remove the caller's original files.
        #expect(FileManager.default.fileExists(atPath: landscape.path))
        #expect(FileManager.default.fileExists(atPath: portrait.path))
    }

    @Test func failedPreparationLeavesReaderClosedAndAllowsRecovery() async throws {
        let corrupt = FileManager.default.temporaryDirectory
            .appendingPathComponent("invalid-\(UUID().uuidString).mp4")
        try Data("not a video".utf8).write(to: corrupt)
        defer { try? FileManager.default.removeItem(at: corrupt) }
        let valid = try await VideoTestSupport.makeVideo()
        defer { try? FileManager.default.removeItem(at: valid) }
        let reader = VideoReplayReader()
        do {
            _ = try await reader.open(corrupt)
            Issue.record("Invalid video unexpectedly opened.")
        } catch {
            // AVFoundation's precise error varies by decoder/OS.
        }
        do {
            _ = try await reader.nextFrame()
            Issue.record("Failed preparation left a readable decoder installed.")
        } catch ReplayError.notOpen {
            // No partial setup may escape to the next read.
        }
        _ = try await reader.open(valid)
        let first = try await reader.nextFrame()
        #expect(first?.pose.timestamp.seconds == 0)
        #expect(first?.pose.imageSize == ImageSize(width: 160, height: 96))
        await reader.close()
    }
}
