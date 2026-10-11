import AVFoundation
import CryptoKit
import Testing
@testable import HangInThere

@Suite(.serialized)
struct VideoReplayReaderTests {
    @MainActor @Test func nativeFrameStagesLeaveThePresentationActor() async throws {
        let url = try await VideoTestSupport.makeVideo()
        defer { try? FileManager.default.removeItem(at: url) }
        let (events, continuation) = AsyncStream.makeStream(of: Bool.self)
        let reader = VideoReplayReader(estimator: TestPoseEstimator(), trace: { event in
            continuation.yield(Thread.isMainThread)
        })
        _ = try await reader.open(url)
        var timestamps: [Double] = []
        while let frame = try await reader.nextFrame() { timestamps.append(frame.pose.timestamp.seconds) }
        await reader.close()
        continuation.finish()
        var stages: [Bool] = []
        for await isMain in events { stages.append(isMain) }
        #expect(!stages.isEmpty && stages.allSatisfy { !$0 },
                "Native context creation, preparation, decode, render and inference must leave the presentation actor.")
        #expect(timestamps == VideoTestSupport.timestamps.map(\.seconds))
    }

    @Test func stageTraceKeepsSourceTimeAndClosesThrownInferenceSpan() async throws {
        let url = try await VideoTestSupport.makeVideo()
        defer { try? FileManager.default.removeItem(at: url) }
        let (events, continuation) = AsyncStream.makeStream(of: ReplayTrace.self)
        let reader = VideoReplayReader(estimator: TestPoseEstimator(failAtOrAfter: 0.1), trace: {
            continuation.yield($0)
        })
        _ = try await reader.open(url)
        let first = try await reader.nextFrame()
        #expect(first?.pose.timestamp.seconds == 0)
        do {
            _ = try await reader.nextFrame()
            Issue.record("Tracing must preserve the estimator's thrown failure.")
        } catch FixtureError.failed(let message) {
            #expect(message == TestPoseEstimator.failureMessage)
        }
        await reader.close()
        continuation.finish()
        var recorded: [ReplayTrace] = []
        for await event in events { recorded.append(event) }
        #expect(recorded.map(\.stage) == [.preparation, .preparation,
                .decoding, .decoding, .geometry, .geometry, .context, .context,
                .rendering, .rendering, .estimation, .estimation,
                .decoding, .decoding, .geometry, .geometry,
                .rendering, .rendering, .estimation, .estimation],
                "Create the context lazily on first render, then reuse it on later frames.")
        for index in stride(from: 0, to: recorded.count - 1, by: 2) {
            let begin = recorded[index], end = recorded[index + 1]
            #expect(begin.stage == end.stage && begin.sourceSeconds == end.sourceSeconds)
            #expect(begin.boundary == .began && begin.milliseconds == nil)
            #expect(end.boundary == .ended)
            let elapsed = try #require(end.milliseconds)
            #expect(elapsed.isFinite && elapsed >= 0)
        }
        #expect(recorded.filter { $0.stage == .estimation && $0.boundary == .ended }.map(\.sourceSeconds) == [0, 0.1])
    }

    @Test(arguments: [nil, .decoding, .geometry, .context, .rendering, .estimation] as [ReplayTrace.Stage?])
    func discardedRequestsStopBetweenStagesWithoutCorruptingTheTimeline(after stage: ReplayTrace.Stage?) async throws {
        let url = try await VideoTestSupport.makeVideo()
        defer { try? FileManager.default.removeItem(at: url) }
        let discard = ReplayFrameDiscardSignal()
        let (events, continuation) = AsyncStream.makeStream(of: ReplayTrace.self)
        let reader = VideoReplayReader(estimator: TestPoseEstimator(), trace: { event in
            continuation.yield(event)
            if event.boundary == .ended && event.stage == stage { discard.discard() }
        })
        _ = try await reader.open(url)
        if stage == nil { discard.discard() }
        do {
            _ = try await reader.nextFrame(discard: discard)
            Issue.record("A discarded frame request must throw instead of returning obsolete pixels.")
        } catch is CancellationError {
            // Source disposal intentionally abandons this consumed frame.
        }
        // A new request can continue the reader. No native read took place if
        // disposal preceded decoding; otherwise exactly one frame was consumed.
        let next = try await reader.nextFrame()
        let frame = try #require(next)
        #expect(frame.pose.timestamp.seconds == (stage == nil ? 0 : 0.1))
        try checkFrame(frame, width: 160, height: 96, colors: [red, green, blue, yellow])
        await reader.close()
        continuation.finish()
        var recorded: [ReplayTrace.Stage] = []
        for await event in events where event.boundary == .began { recorded.append(event.stage) }
        let nativeStages: [ReplayTrace.Stage] = [.decoding, .geometry, .context, .rendering, .estimation]
        let stop = stage.flatMap { nativeStages.firstIndex(of: $0) }
        let abandonedStages = stop.map { Array(nativeStages.prefix($0 + 1)) } ?? []
        let continuedStages = abandonedStages.contains(.context)
            ? nativeStages.filter { $0 != .context } : nativeStages
        #expect(recorded == [.preparation] + abandonedStages + continuedStages,
                "Discard must prevent every native stage after its checkpoint.")
    }

    @Test func cancelledPauseTaskStillReturnsItsConsumedFrameWithoutDisposal() async throws {
        let url = try await VideoTestSupport.makeVideo()
        defer { try? FileManager.default.removeItem(at: url) }
        let reader = VideoReplayReader(estimator: TestPoseEstimator())
        _ = try await reader.open(url)
        let discard = ReplayFrameDiscardSignal()
        let task = Task {
            // Cancel only this task, exactly as pause does. Disposal is a
            // separate signal and must not be inferred from Task.isCancelled.
            withUnsafeCurrentTask { $0?.cancel() }
            return try await reader.nextFrame(discard: discard)
        }
        let next = try await task.value
        let frame = try #require(next)
        #expect(task.isCancelled)
        #expect(frame.pose.timestamp.seconds == 0)
        try checkFrame(frame, width: 160, height: 96, colors: [red, green, blue, yellow])
        await reader.close()
    }

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

    @Test(arguments: [false, true])
    func rawFixturesPreserveMixedTimebasesAndNonzeroFirstPTS(nonzeroStart: Bool) async throws {
        let times = nonzeroStart
            ? [CMTime(value: 1, timescale: 3), CMTime(value: 1, timescale: 2), CMTime(value: 3, timescale: 4)]
            : [CMTime(value: 0, timescale: 100), CMTime(value: 1, timescale: 30),
               CMTime(value: 7, timescale: 100), CMTime(value: 11, timescale: 60)]
        let url = try await VideoTestSupport.makeVideo(timestamps: times)
        defer { try? FileManager.default.removeItem(at: url) }
        try await checkFrames(url, times: times.map(\.seconds), width: 160, height: 96,
                              colors: [red, green, blue, yellow])
    }

    @Test func rawFixtureBytesAreDeterministicAndRetainEveryFrame() async throws {
        let first = try await VideoTestSupport.makeVideo(cyclingQuadrants: true)
        let second = try await VideoTestSupport.makeVideo(cyclingQuadrants: true)
        defer { try? FileManager.default.removeItem(at: first); try? FileManager.default.removeItem(at: second) }
        #expect(try Data(contentsOf: first) == Data(contentsOf: second))
        let reader = VideoReplayReader(estimator: TestPoseEstimator())
        _ = try await reader.open(first)
        var index = 0
        while let frame = try await reader.nextFrame() {
            #expect(index < VideoTestSupport.timestamps.count)
            let colors = [red, green, blue, yellow]
            let shifted = (0..<4).map { colors[($0 + index) % 4] }
            try checkFrame(frame, width: 160, height: 96, colors: shifted)
            index += 1
        }
        await reader.close()
        #expect(index == VideoTestSupport.timestamps.count)
    }

    @Test func rawFixtureRejectsInvalidGeometryOrTimeline() async throws {
        for times in [[], [.invalid], [CMTime(value: -1, timescale: 100)],
                      [.zero, .zero], [CMTime(value: 2, timescale: 100), CMTime(value: 1, timescale: 100)]] as [[CMTime]] {
            do {
                let url = try await VideoTestSupport.makeVideo(timestamps: times)
                try? FileManager.default.removeItem(at: url)
                Issue.record("Invalid fixture timestamps must be rejected before writing samples.")
            } catch FixtureError.failed { }
        }
        do {
            let url = try await VideoTestSupport.makeVideo(width: 0)
            try? FileManager.default.removeItem(at: url)
            Issue.record("Invalid fixture dimensions must be rejected.")
        } catch FixtureError.failed { }
    }

    @Test func rawFixtureRejectsOversizedWorkBeforeAllocatingPixels() async throws {
        let cases = [(65_535, 65_535, 1), (1, 1, 513), (1024, 1024, 17)]
        for (width, height, count) in cases {
            do {
                let times = (0..<count).map { CMTime(value: Int64($0), timescale: 30) }
                let url = try await VideoTestSupport.makeVideo(timestamps: times, width: width, height: height)
                try? FileManager.default.removeItem(at: url)
                Issue.record("Oversized fixture work must fail before allocating pixels or sample tables.")
            } catch FixtureError.failed { }
        }
    }

    @Test func cancelledFixtureRequestDoesNotReturnAFile() async throws {
        let request = Task {
            withUnsafeCurrentTask { $0?.cancel() }
            return try await VideoTestSupport.makeVideo()
        }
        do {
            let url = try await request.value
            try? FileManager.default.removeItem(at: url)
            Issue.record("A cancelled fixture request must throw CancellationError.")
        } catch is CancellationError { }
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

    @Test(arguments: [false, true])
    func retainedFramesKeepChangingPixelsAcrossEarlyRewindEOFAndClose(portrait: Bool) async throws {
        let times = [0, 1, 3, 7].map { CMTime(value: $0, timescale: 30) }
        let transform = portrait ? CGAffineTransform(a: 0, b: 1, c: -1, d: 0, tx: 192, ty: 0) : .identity
        let url = try await VideoTestSupport.makeVideo(
            transform: transform, timestamps: times, width: 320, height: 192, cyclingQuadrants: true)
        defer { try? FileManager.default.removeItem(at: url) }
        let reader = VideoReplayReader(estimator: TestPoseEstimator())
        _ = try await reader.open(url)
        var retained: [(frame: ProcessedFrame, sourceIndex: Int)] = []
        // Automatic setup rewinds before EOF while the UI retains prior images.
        for index in 0..<3 {
            let next = try await reader.nextFrame()
            retained.append((try #require(next), index))
        }
        for _ in 0..<2 {
            _ = try await reader.rewind()
            var count = 0
            while let frame = try await reader.nextFrame() {
                retained.append((frame, count))
                count += 1
            }
            #expect(count == times.count, "Each rewind must read the entire source timeline.")
        }
        await reader.close()
        #expect(retained.count == 3 + 2 * times.count)
        let palette = [red, green, blue, yellow]
        let cornerIndices = portrait ? [2, 0, 3, 1] : [0, 1, 2, 3]
        for (frame, index) in retained {
            let sourceIndex = try #require(times.indices.contains(index) ? index : nil)
            #expect(frame.pose.timestamp.seconds == times[sourceIndex].seconds)
            try checkFrame(frame, width: portrait ? 192 : 320, height: portrait ? 320 : 192,
                       colors: cornerIndices.map { palette[($0 + sourceIndex) % palette.count] })
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
            try checkFrame(frame, width: width, height: height, colors: colors)
        }
        await reader.close()
        #expect(actualTimes.count == times.count)
        for (actual, expected) in zip(actualTimes, times) {
            #expect(abs(actual - expected) < 1e-6)
        }
    }

    private func checkFrame(_ frame: ProcessedFrame, width: Int, height: Int, colors: [[UInt8]]) throws {
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

}

private struct DecoderFixture: Decodable {
    let videoSHA256: String
    let framePTSSeconds: [Double]
    enum CodingKeys: String, CodingKey {
        case videoSHA256 = "video_sha256", framePTSSeconds = "frame_pts_seconds"
    }
}
