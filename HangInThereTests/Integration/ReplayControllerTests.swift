import AVFoundation
import CoreGraphics
import Testing
@testable import HangInThere

@MainActor @Suite(.serialized)
struct ReplayControllerTests {
    @Test func pauseResumeAndRestartDoNotLoseConsumedFrames() async throws {
        let url = try await VideoTestSupport.makeVideo()
        defer { try? FileManager.default.removeItem(at: url) }
        let model = ReplayController(estimator: TestPoseEstimator())
        defer { model.close() }
        model.open(url)
        try await wait { model.phase == .paused || model.phase == .failed }
        #expect(model.phase == .paused)
        #expect(model.displayedFrames == 1)
        model.play()
        // Cancel while the next decode/inference may be in flight.
        try await Task.sleep(for: .milliseconds(10))
        model.pause()
        let countAtPause = model.displayedFrames
        try await Task.sleep(for: .milliseconds(200))
        #expect(model.displayedFrames == countAtPause)
        model.play()
        try await wait { model.phase == .finished || model.phase == .failed }
        #expect(model.phase == .finished)
        #expect(model.displayedFrames == 4)
        model.restart()
        try await wait { model.phase == .paused || model.phase == .failed }
        #expect(model.phase == .paused)
        #expect(model.frame?.pose.timestamp.seconds == 0)
        #expect(model.displayedFrames == 1)
    }

    @Test func replacingSourceCannotPublishAnOldPortraitFrame() async throws {
        let portrait = try await VideoTestSupport.makeVideo(transform: CGAffineTransform(a: 0, b: 1, c: -1, d: 0, tx: 96, ty: 0))
        let landscape = try await VideoTestSupport.makeVideo()
        defer {
            try? FileManager.default.removeItem(at: portrait)
            try? FileManager.default.removeItem(at: landscape)
        }
        let model = ReplayController(estimator: TestPoseEstimator())
        defer { model.close() }
        model.open(portrait)
        model.open(landscape)
        try await wait { model.phase == .paused || model.phase == .failed }
        #expect(model.phase == .paused)
        #expect(model.frame?.image.width == 160)
        #expect(model.frame?.image.height == 96)
        #expect(model.sourceName == landscape.lastPathComponent)
    }

    @Test func invalidFileIsReportedAndAnotherImportCanRecover() async throws {
        let url = try await VideoTestSupport.makeVideo()
        defer { try? FileManager.default.removeItem(at: url) }
        let model = ReplayController(estimator: TestPoseEstimator())
        defer { model.close() }
        model.open(FileManager.default.temporaryDirectory.appendingPathComponent("missing-\(UUID().uuidString).mp4"))
        try await wait { model.phase == .failed }
        #expect(model.errorMessage != nil)
        model.open(url)
        try await wait { model.phase == .paused || model.phase == .failed }
        #expect(model.phase == .paused)
        #expect(model.errorMessage == nil)
        #expect(model.failureReport == nil)
    }

    @Test func closeDuringReplayCannotPublishAStaleFrame() async throws {
        let url = try await VideoTestSupport.makeVideo()
        defer { try? FileManager.default.removeItem(at: url) }
        let model = ReplayController(estimator: TestPoseEstimator())
        defer { model.close() }
        model.open(url)
        try await wait { model.phase == .paused || model.phase == .failed }
        #expect(model.phase == .paused)
        model.play()
        try await Task.sleep(for: .milliseconds(10))
        model.close()
        try await Task.sleep(for: .milliseconds(200))
        #expect(model.phase == .idle)
        #expect(model.frame == nil)
        #expect(model.sourceName == nil)
        #expect(model.displayedFrames == 0)
    }

    @Test func firstInferenceFailureIsNotASuccessfulEmptyFrame() async throws {
        let url = try await VideoTestSupport.makeVideo()
        defer { try? FileManager.default.removeItem(at: url) }
        let model = ReplayController(estimator: TestPoseEstimator(failAtOrAfter: 0))
        defer { model.close() }
        // Closing and importing again must retain the dependency, not construct
        // a different backend or silently turn an inference failure into success.
        for _ in 0..<2 {
            model.open(url)
            try await wait { model.phase == .paused || model.phase == .failed }
            try #require(model.phase == .failed)
            #expect(model.errorMessage == TestPoseEstimator.failureMessage)
            #expect(model.frame == nil)
            #expect(model.displayedFrames == 0)
            #expect(!model.canPlay)
            let report = try #require(model.failureReport)
            #expect(report.contains("Successfully displayed frames: 0"))
            #expect(!report.contains("Last successful pose:"))
            #expect(!report.contains(url.lastPathComponent))
            #expect(!report.contains(TestPoseEstimator.failureMessage))
            model.close()
            #expect(model.failureReport == nil)
        }
    }

    @Test func playbackInferenceFailurePreservesLastFrameAndImportCanRecover() async throws {
        let url = try await VideoTestSupport.makeVideo()
        defer { try? FileManager.default.removeItem(at: url) }
        let model = ReplayController(estimator: TestPoseEstimator(failAtOrAfter: 0.1))
        defer { model.close() }
        for _ in 0..<2 {
            model.open(url)
            try await wait { model.phase == .paused || model.phase == .failed }
            try #require(model.phase == .paused)
            #expect(model.errorMessage == nil)
            #expect(model.failureReport == nil)
            #expect(model.frame?.pose.backend == TestPoseEstimator.backend)
            model.play()
            try await wait { model.phase == .finished || model.phase == .failed }
            try #require(model.phase == .failed)
            #expect(model.errorMessage == TestPoseEstimator.failureMessage)
            #expect(model.displayedFrames == 1)
            #expect(model.frame?.pose.timestamp.seconds == 0)
            let report = try #require(model.failureReport)
            #expect(report.contains("Successfully displayed frames: 1"))
            #expect(report.contains("Last successful pose: \(TestPoseEstimator.backend)"))
            #expect(!report.contains(url.lastPathComponent))
            model.restart()
            try await wait { model.phase == .paused || model.phase == .failed }
            #expect(model.phase == .paused)
            #expect(model.failureReport == nil)
        }
    }

    @Test func sharedFailureDetailsDoNotExportPrivateErrorInformation() throws {
        let privateName = "private-athlete-recording.mp4"
        let privatePath = "/private/user-recordings/" + privateName
        let error = NSError(domain: "com.apple.Vision", code: 9, userInfo: [
            NSLocalizedDescriptionKey: "Could not process \(privatePath)",
            NSFilePathErrorKey: privatePath,
            NSUnderlyingErrorKey: NSError(domain: "private-nested-error", code: 2,
                                          userInfo: [NSLocalizedDescriptionKey: privateName])
        ])
        let model = ReplayController(estimator: TestPoseEstimator())
        model.reportImportFailure(error)
        let report = try #require(model.failureReport)
        #expect(report.contains("Error domain: com.apple.Vision; code: 9"))
        #expect(report.contains("Operation: file selection"))
        #expect(!report.contains(privateName))
        #expect(!report.contains(privatePath))
        #expect(!report.contains("private-nested-error"))
        #expect(model.errorMessage == error.localizedDescription)
        model.close()
        #expect(model.failureReport == nil)
    }

    @Test func counterUsesDisplayedFramesAndSurvivesPauseWithoutDuplicates() async throws {
        let times = [0,15,30,45,60,75].map { CMTime(value: $0, timescale: 100) }
        let url = try await VideoTestSupport.makeVideo(timestamps: times)
        defer { try? FileManager.default.removeItem(at: url) }
        let model = ReplayController(estimator: CountingTestEstimator())
        defer { model.close() }
        model.open(url)
        try await wait { model.phase == .paused || model.phase == .failed }
        try #require(model.phase == .paused)
        #expect(model.counter.observedMovements == 0)
        model.play()
        try await wait { model.counter.observedMovements == 1 || model.phase == .failed }
        try #require(model.phase != .failed)
        model.pause()
        let displayed = model.displayedFrames
        let count = model.counter.observedMovements
        try await Task.sleep(for: .milliseconds(250))
        #expect(model.displayedFrames == displayed)
        #expect(model.counter.observedMovements == count)
        model.play()
        try await wait { model.phase == .finished || model.phase == .failed }
        #expect(model.phase == .finished)
        #expect(model.counter.observedMovements == 1)
        #expect(model.counter.phase == .finished)
        #expect(model.displayedFrames == times.count)
        model.restart()
        try await wait { model.phase == .paused || model.phase == .failed }
        #expect(model.counter.observedMovements == 0)
        #expect(model.counter.lastEvent == nil)
        #expect(model.frame?.pose.timestamp.seconds == 0)
        model.configureCounting(exercise: .dip, side: .right)
        try await wait { model.phase == .paused || model.phase == .failed }
        #expect(model.counter.exercise == .dip)
        #expect(model.counter.side == .right)
        #expect(model.counter.observedMovements == 0)
        model.close()
        #expect(model.counter.phase == .seekingStart)
        #expect(model.counter.lastEvent == nil)
    }

    @Test func inferenceFailureInterruptsAnActiveAttemptAndReimportClearsIt() async throws {
        let times = [0,15,30,45,60].map { CMTime(value: $0, timescale: 100) }
        let url = try await VideoTestSupport.makeVideo(timestamps: times)
        defer { try? FileManager.default.removeItem(at: url) }
        let model = ReplayController(estimator: CountingTestEstimator(failAt: 0.45))
        defer { model.close() }
        model.open(url)
        try await wait { model.phase == .paused || model.phase == .failed }
        model.play()
        try await wait { model.phase == .finished || model.phase == .failed }
        #expect(model.phase == .failed)
        #expect(model.counter.interruptedAttempts == 1)
        #expect(model.counter.observedMovements == 0)
        #expect(model.counter.lastEvent?.reason == "inferenceFailure")
        #expect(model.frame?.pose.timestamp.seconds == 0.3)
        model.open(url)
        try await wait { model.phase == .paused || model.phase == .failed }
        #expect(model.counter.interruptedAttempts == 0)
        #expect(model.counter.lastEvent == nil)
    }

    private func wait(until predicate: () -> Bool) async throws {
        let deadline = ContinuousClock.now.advanced(by: .seconds(60))
        while !predicate() {
            guard ContinuousClock.now < deadline else { throw FixtureError.failed("Replay state transition timed out.") }
            try await Task.sleep(for: .milliseconds(10))
        }
    }
}

// Synthetic arm observations over actual decoded quadrant-video timestamps.
// These tests qualify controller/counter wiring, not a pose model or rep accuracy.
private struct CountingTestEstimator: PoseEstimator {
    var failAt: Double? = nil
    func estimate(image: CGImage, timestamp: PresentationTime) throws -> PoseResult {
        let time = timestamp.seconds
        if let failAt, time >= failAt { throw FixtureError.failed("Counting test inference failure") }
        let base = ExerciseCounterTests.pose(time, degrees: time >= 0.3 && time < 0.6 ? 80 : 170)
        let scale = min(Double(image.width), Double(image.height)) / 1000
        return PoseResult(timestamp: timestamp,
            imageSize: ImageSize(width: Double(image.width), height: Double(image.height)),
            people: base.people.map { person in
                PoseObservation(landmarks: person.landmarks.map {
                    Landmark(joint: $0.joint, position: Point2D(x: $0.position.x * scale, y: $0.position.y * scale), confidence: $0.confidence)
                })
            }, backend: "analytic counting test (not Vision)", requestRevision: 0)
    }
}
