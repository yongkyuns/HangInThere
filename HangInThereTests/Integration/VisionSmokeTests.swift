import Foundation
import ImageIO
import Testing
@testable import HangInThere

@Suite(.serialized)
struct VisionSmokeTests {
    // Keep the former decoder test's negative-model assertion on REAL Vision.
    // Mechanical replay tests use a sentinel estimator and cannot qualify this.
    @Test func solidQuadrantVideoDoesNotFabricatePeople() async throws {
        let url = try await VideoTestSupport.makeVideo()
        defer { try? FileManager.default.removeItem(at: url) }
        let reader = VideoReplayReader()
        _ = try await reader.open(url)
        var frames = 0
        while let frame = try await reader.nextFrame() {
            #expect(frame.pose.backend == "Apple Vision 2D")
            #expect(frame.pose.requestRevision == 1)
            #expect(frame.pose.people.isEmpty, "Four solid quadrants must not fabricate a human skeleton.")
            frames += 1
        }
        await reader.close()
        #expect(frames == VideoTestSupport.timestamps.count)
    }

    @MainActor @Test func defaultControllerKeepsRealVisionAfterCloseAndReimport() async throws {
        let url = try VideoTestSupport.resource("pullup-smoke.mp4")
        let model = ReplayController()
        defer { model.close() }
        for _ in 0..<2 {
            model.open(url)
            let deadline = ContinuousClock.now.advanced(by: .seconds(60))
            while model.phase == .loading {
                guard ContinuousClock.now < deadline else {
                    throw FixtureError.failed("Real-Vision controller startup timed out.")
                }
                try await Task.sleep(for: .milliseconds(10))
            }
            try #require(model.phase == .paused, "Real Vision startup failed: \(model.errorMessage ?? "unknown error")")
            let frame = try #require(model.frame)
            #expect(frame.pose.backend == "Apple Vision 2D")
            #expect(frame.pose.requestRevision == 1)
            #expect(VideoTestSupport.hasVisibleArm(frame.pose))
            #expect(model.displayedFrames == 1)
            model.close()
        }
    }

    @MainActor @Test func realWorkoutFixtureRunsThroughVisionCountingAndResults() async throws {
        let videoURL = try VideoTestSupport.resource("pullup-smoke.mp4")
        let specificationURL = try VideoTestSupport.fixtureResource("source.json")
        let specification = try JSONDecoder().decode(
            WorkoutDemoSpecification.self,
            from: Data(contentsOf: specificationURL)
        )
        let demo = specification.demoExpectation
        let model = ReplayController()
        defer { model.close() }

        #expect(demo.exercise == .pullUp)
        #expect(demo.side == .left)

        model.open(videoURL)
        try await waitUntilNotLoading(model)
        try #require(
            model.phase == .paused,
            "Real workout demo could not start Apple Vision: \(model.errorMessage ?? "unknown error")"
        )
        let initialFrame = try #require(model.frame)
        #expect(initialFrame.pose.backend == "Apple Vision 2D")
        #expect(initialFrame.pose.requestRevision == 1)
        #expect(ArmMeasurement(pose: initialFrame.pose, side: demo.side).estimate != nil)
        #expect(
            LiveFramingAssessment(pose: initialFrame.pose, side: demo.side).state == .ready,
            "The reviewed real workout frame should satisfy the same live selected-arm readiness policy."
        )

        let setup = try #require(model.beginBarSetup())
        try #require(demo.barReferenceEdge.count == 4, "Demo bar edge must contain x1, y1, x2, y2.")
        let edge = BarSegment(
            a: Point2D(x: demo.barReferenceEdge[0], y: demo.barReferenceEdge[1]),
            b: Point2D(x: demo.barReferenceEdge[2], y: demo.barReferenceEdge[3])
        )
        let bar = ConfirmedBar(
            role: .pullUpGrip,
            method: .manualEdge,
            referenceEdge: edge,
            oppositeEdge: nil,
            imageSize: setup.frame.pose.imageSize,
            sourceTime: setup.frame.pose.timestamp
        )
        #expect(bar.isValid)
        #expect(model.confirmBar(bar, for: setup))
        try await waitUntilNotLoading(model)
        try #require(model.phase == .paused)
        #expect(model.currentBar == bar)

        model.play()
        try await waitUntilFinishedOrFailed(model)
        try #require(
            model.phase == .finished,
            "Real workout demo failed during analysis: \(model.errorMessage ?? "unknown error")"
        )

        #expect(model.counter.observedMovements == demo.expectedObservedMovements)
        #expect(model.counter.partialAttempts == 0)
        #expect(model.counter.interruptedAttempts == 0)
        #expect(model.movementTimes.count == demo.expectedObservedMovements)
        if let movementTime = model.movementTimes.first {
            #expect(abs(movementTime - demo.expectedMovementTimeSeconds) <= 0.2)
        }
        if let coverage = model.trackingCoverage {
            #expect(abs(coverage - demo.expectedTrackingCoverage) < 1e-9)
        } else {
            Issue.record("Real workout demo produced no tracking-coverage denominator.")
        }
        #expect(model.progress == 1)

        print(
            "[Workout demo] backend=Apple Vision 2D; movements=\(model.counter.observedMovements); " +
            "movementTimes=\(model.movementTimes); trackingCoverage=\(model.trackingCoverage ?? -1); " +
            "movement-only, not form qualification"
        )
    }

    @Test func realHumanImageProducesAnArmChain() throws {
        let url = try VideoTestSupport.resource("pullup-smoke.png")
        let source = try #require(CGImageSourceCreateWithURL(url as CFURL, nil))
        let image = try #require(CGImageSourceCreateImageAtIndex(source, 0, nil))
        let result = try VisionPoseEstimator().estimate(image: image, timestamp: PresentationTime(value: 19, timescale: 10))
        #expect(result.requestRevision == 1)
        #expect(VideoTestSupport.hasVisibleArm(result), "A real human fixture must produce shoulder/elbow/wrist observations, not just a successful request.")
        print("[Vision smoke] \(ProcessInfo.processInfo.operatingSystemVersionString); revision=\(result.requestRevision); people=\(result.people.count)")
    }

    @Test func realVideoUsesTheAppReaderAndMatchesDecodedSourceTimestamps() async throws {
        let url = try VideoTestSupport.resource("pullup-smoke.mp4")
        let metadataURL = try VideoTestSupport.resource("prepared.json")
        let metadata = try JSONDecoder().decode(PreparedFixture.self, from: Data(contentsOf: metadataURL))
        let reader = VideoReplayReader()
        _ = try await reader.open(url)
        var timestamps: [Double] = []
        var framesWithArm = 0
        var checkpointRootY: [Int: Double] = [:]
        var counter = ExerciseCounter()
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        while let frame = try await reader.nextFrame() {
            counter.consume(frame.pose)
            timestamps.append(frame.pose.timestamp.seconds)
            if VideoTestSupport.hasVisibleArm(frame.pose) { framesWithArm += 1 }
            #expect(frame.pose.imageSize == ImageSize(width: Double(frame.image.width), height: Double(frame.image.height)))
            #expect(frame.pose.requestRevision == 1)
            let index = timestamps.count - 1
            if [10, 24, 36].contains(index) {
                // Independently inspected hang / peak / returned-hang frames.
                // A broad directional check, not anatomical or rep ground truth.
                #expect(frame.pose.people.count == 1, "The reviewed motion checkpoints contain one athlete.")
                if let root = frame.pose.people.first?.landmark(.root, minimumConfidence: 0.2) {
                    checkpointRootY[index] = root.position.y / frame.pose.imageSize.height
                } else {
                    Issue.record("Missing root at reviewed motion checkpoint \(index).")
                }
            }
            // Only this pinned, public-source fixture is logged. These are model
            // predictions for inspection, never independent ground-truth labels.
            let sample = PoseSmokeSample(videoSHA256: metadata.videoSHA256,
                                         frameIndex: timestamps.count - 1, pose: frame.pose)
            print("[Pose sample] \(String(decoding: try encoder.encode(sample), as: UTF8.self))")
        }
        await reader.close()
        counter.finish()
        print("[Movement diagnostic] \(String(decoding: try encoder.encode(counter.summary), as: UTF8.self))")
        #expect(timestamps.count == metadata.framePTSSeconds.count)
        #expect(timestamps.count == 40, "The prepared 4-second, 10-FPS fixture must not silently change length.")
        for (actual, expected) in zip(timestamps, metadata.framePTSSeconds) {
            #expect(abs(actual - expected) < 1e-6)
        }
        #expect(framesWithArm > 0, "Decoding a video without real pose extraction is not a model smoke test.")
        let hang = try #require(checkpointRootY[10])
        let peak = try #require(checkpointRootY[24])
        let returnedHang = try #require(checkpointRootY[36])
        #expect(hang - peak > 0.10, "The reviewed ascent must move the inferred body root upward.")
        #expect(returnedHang - peak > 0.10, "The reviewed descent must return the inferred body root downward.")
        print("[Video smoke] frames=\(timestamps.count); armFrames=\(framesWithArm); derivativeSHA256=\(metadata.videoSHA256); not a counting/accuracy benchmark")
    }

    @MainActor
    private func waitUntilNotLoading(_ model: ReplayController) async throws {
        let deadline = ContinuousClock.now.advanced(by: .seconds(60))
        while model.phase == .loading {
            guard ContinuousClock.now < deadline else {
                throw FixtureError.failed("Real workout demo startup timed out.")
            }
            try await Task.sleep(for: .milliseconds(10))
        }
    }

    @MainActor
    private func waitUntilFinishedOrFailed(_ model: ReplayController) async throws {
        let deadline = ContinuousClock.now.advanced(by: .seconds(60))
        while model.phase != .finished && model.phase != .failed {
            guard ContinuousClock.now < deadline else {
                throw FixtureError.failed("Real workout demo analysis timed out.")
            }
            try await Task.sleep(for: .milliseconds(10))
        }
    }
}

private struct PreparedFixture: Decodable {
    let framePTSSeconds: [Double]
    let videoSHA256: String

    enum CodingKeys: String, CodingKey {
        case framePTSSeconds = "frame_pts_seconds"
        case videoSHA256 = "video_sha256"
    }
}

private struct WorkoutDemoSpecification: Decodable {
    struct DemoExpectation: Decodable {
        let exercise: ExerciseCounter.Exercise
        let side: ArmMeasurement.Side
        let barReferenceEdge: [Double]
        let expectedObservedMovements: Int
        let expectedMovementTimeSeconds: Double
        let expectedTrackingCoverage: Double

        enum CodingKeys: String, CodingKey {
            case exercise, side
            case barReferenceEdge = "bar_reference_edge"
            case expectedObservedMovements = "expected_observed_movements"
            case expectedMovementTimeSeconds = "expected_movement_time_seconds"
            case expectedTrackingCoverage = "expected_tracking_coverage"
        }
    }

    let demoExpectation: DemoExpectation

    enum CodingKeys: String, CodingKey {
        case demoExpectation = "demo_expectation"
    }
}

private struct PoseSmokeSample: Encodable {
    let videoSHA256: String
    let frameIndex: Int
    let pose: PoseResult
}

