import AVFoundation
import CryptoKit
import Testing
@testable import HangInThere

@Suite(.serialized)
struct VideoReplayReaderTests {
    @Test(arguments: [false, true])
    func rawFixturesRoundTripFractionalPTSAndAllQuadrants(portrait: Bool) async throws {
        let times = [0, 1, 3, 7].map { CMTime(value: $0, timescale: 30) }
        let transform = portrait ? CGAffineTransform(a: 0, b: 1, c: -1, d: 0, tx: 192, ty: 0) : .identity
        let url = try await VideoTestSupport.makeVideo(transform: transform, timestamps: times, width: 320, height: 192)
        defer { try? FileManager.default.removeItem(at: url) }
        let asset = AVURLAsset(url: url)
        let track = try #require(try await asset.loadTracks(withMediaType: .video).first)
        let format = try #require(try await track.load(.formatDescriptions).first)
        #expect(CMFormatDescriptionGetMediaSubType(format) == kCVPixelFormatType_32BGRA)
        try await checkFrames(url, times: times.map(\.seconds), width: portrait ? 192 : 320,
                              height: portrait ? 320 : 192,
                              colors: portrait ? [blue, red, yellow, green] : [red, green, blue, yellow])
    }

    @Test func pinnedH264DecodesVFRAndPortraitPixelsWithoutVision() async throws {
        let url = try VideoTestSupport.fixtureResource("decoder/quadrants-h264-vfr-portrait.mp4")
        let metadataURL = try VideoTestSupport.fixtureResource("decoder/quadrants-h264-vfr-portrait.json")
        let metadata = try JSONDecoder().decode(DecoderFixture.self, from: Data(contentsOf: metadataURL))
        let bytes = try Data(contentsOf: url)
        let digest = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
        try #require(digest == metadata.videoSHA256, "The committed compressed fixture must match its integrity pin.")
        try #require(metadata.framePTSSeconds == [0, 0.1, 0.23, 0.41])
        let asset = AVURLAsset(url: url)
        let track = try #require(try await asset.loadTracks(withMediaType: .video).first)
        let format = try #require(try await track.load(.formatDescriptions).first)
        #expect(CMFormatDescriptionGetMediaSubType(format) == kCMVideoCodecType_H264)
        // The committed file has a counterclockwise 90-degree display matrix.
        try await checkFrames(url, times: metadata.framePTSSeconds, width: 96, height: 160,
                              colors: [green, yellow, red, blue])
    }

    @Test func decodedFramesKeepActualVariableRateTimestamps() async throws {
        let url = try await VideoTestSupport.makeVideo()
        defer { try? FileManager.default.removeItem(at: url) }
        let reader = VideoReplayReader(estimator: TestPoseEstimator())
        _ = try await reader.open(url)
        var seconds: [Double] = []
        while let frame = try await reader.nextFrame() {
            seconds.append(frame.pose.timestamp.seconds)
            #expect(frame.image.width == 160 && frame.image.height == 96)
            #expect(frame.pose.imageSize == ImageSize(width: 160, height: 96))
            let expected = try TestPoseEstimator().estimate(
                image: frame.image, timestamp: frame.pose.timestamp)
            #expect(frame.pose == expected, "Forward the estimator result unchanged, not an empty fallback.")
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
        let reader = VideoReplayReader(estimator: TestPoseEstimator())
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
        let reader = VideoReplayReader(estimator: TestPoseEstimator())
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
        let reader = VideoReplayReader(estimator: TestPoseEstimator())
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
        let reader = VideoReplayReader(estimator: TestPoseEstimator())
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

    @Test func inferenceFailurePropagatesAndReopeningStartsWithFreshTime() async throws {
        let url = try await VideoTestSupport.makeVideo()
        defer { try? FileManager.default.removeItem(at: url) }
        let reader = VideoReplayReader(estimator: TestPoseEstimator(failAtOrAfter: 0.1))
        for _ in 0..<2 {
            _ = try await reader.open(url)
            let first = try await reader.nextFrame()
            #expect(first?.pose.timestamp.seconds == 0)
            #expect(first?.pose.backend == TestPoseEstimator.backend)
            do {
                _ = try await reader.nextFrame()
                Issue.record("Inference failure must throw, not yield nil or a successful empty pose.")
            } catch FixtureError.failed(let message) {
                #expect(message == TestPoseEstimator.failureMessage)
            }
            await reader.close()
        }
    }

    private var red: [UInt8] { [240, 0, 0] }
    private var green: [UInt8] { [0, 240, 0] }
    private var blue: [UInt8] { [0, 0, 240] }
    private var yellow: [UInt8] { [240, 240, 0] }

    private func checkFrames(_ url: URL, times: [Double], width: Int, height: Int,
                             colors: [[UInt8]]) async throws {
        let reader = VideoReplayReader(estimator: TestPoseEstimator())
        _ = try await reader.open(url)
        var actualTimes: [Double] = []
        while let frame = try await reader.nextFrame() {
            actualTimes.append(frame.pose.timestamp.seconds)
            #expect(frame.image.width == width && frame.image.height == height)
            #expect(frame.pose.imageSize == ImageSize(width: Double(width), height: Double(height)))
            let expectedPose = try TestPoseEstimator().estimate(image: frame.image, timestamp: frame.pose.timestamp)
            #expect(frame.pose == expectedPose)
            let points = [Point2D(x: 0.25, y: 0.25), Point2D(x: 0.75, y: 0.25),
                          Point2D(x: 0.25, y: 0.75), Point2D(x: 0.75, y: 0.75)]
            for (point, expected) in zip(points, colors) {
                let actual = VideoTestSupport.rgb(frame.image, normalizedTopLeft: point)
                #expect(zip(actual, expected).allSatisfy { abs(Int($0.0) - Int($0.1)) < 50 },
                        "Unexpected quadrant pixels at \(point): \(actual), expected \(expected).")
            }
        }
        await reader.close()
        #expect(actualTimes.count == times.count)
        for (actual, expected) in zip(actualTimes, times) {
            #expect(abs(actual - expected) < 1e-6)
        }
    }

}

private struct DecoderFixture: Decodable {
    let videoSHA256: String
    let framePTSSeconds: [Double]
    enum CodingKeys: String, CodingKey {
        case videoSHA256 = "video_sha256", framePTSSeconds = "frame_pts_seconds"
    }
}
