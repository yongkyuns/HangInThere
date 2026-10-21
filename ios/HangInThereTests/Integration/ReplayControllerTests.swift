import AVFoundation
import CoreGraphics
import os
import Testing
@testable import HangInThere

@MainActor @Suite(.serialized)
struct ReplayControllerTests {
    @Test func switchingExerciseUpdatesGripInferenceOnTheReusedReader() async throws {
        let url = try await VideoTestSupport.makeVideo()
        defer { try? FileManager.default.removeItem(at: url) }
        let observer = RecordingGripObserver()
        let model = ReplayController(estimator: TestPoseEstimator(), gripObserverFactory: { _ in observer })
        defer { model.close() }
        model.open(url)
        try await wait { model.phase == .paused || model.phase == .failed }
        try #require(model.phase == .paused)
        #expect(observer.enabledAtObservation == [true])
        model.configureCounting(exercise: .dip, side: .left)
        try await wait { model.phase == .paused || model.phase == .failed }
        try #require(model.phase == .paused)
        #expect(observer.enabledAtObservation == [true, false])
        model.configureCounting(exercise: .pullUp, side: .left)
        try await wait { model.phase == .paused || model.phase == .failed }
        try #require(model.phase == .paused)
        #expect(observer.enabledAtObservation == [true, false, true])
    }

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
        let model = ReplayController(estimator: CountingTestEstimator(), sceneWorker: ControlledSceneWorker())
        defer { model.close() }
        model.open(url)
        try await wait { model.phase == .paused || model.phase == .failed }
        try #require(model.phase == .paused)
        #expect(model.counter.observedMovements == 0)
        try await confirmCountingBar(model)
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
        #expect(model.movementTimes == [0.45])
        #expect(model.trackingCoverage == 1)
        #expect(model.displayedFrames == times.count)
        model.restart()
        try await wait { model.phase == .paused || model.phase == .failed }
        #expect(model.counter.observedMovements == 0)
        #expect(model.counter.lastEvent == nil)
        #expect(model.movementTimes.isEmpty)
        #expect(model.trackingCoverage == 1)
        #expect(model.frame?.pose.timestamp.seconds == 0)
        #expect(model.currentBar != nil, "Restart of the same fixed-camera source preserves the confirmed reference.")
        model.configureCounting(exercise: .dip, side: .right)
        try await wait { model.phase == .paused || model.phase == .failed }
        #expect(model.counter.exercise == .dip)
        #expect(model.counter.side == .right)
        #expect(model.currentBar == nil, "Exercise/arm policy changes invalidate the apparatus role.")
        #expect(model.counter.observedMovements == 0)
        model.close()
        #expect(model.counter.phase == .seekingStart)
        #expect(model.counter.lastEvent == nil)
    }

    @Test func trackingCoverageExcludesUnusableAnalyzedFrames() async throws {
        let times = [0,15,30,45,60,75].map { CMTime(value: $0, timescale: 100) }
        let url = try await VideoTestSupport.makeVideo(timestamps: times)
        defer { try? FileManager.default.removeItem(at: url) }
        let model = ReplayController(estimator: CountingTestEstimator(lowConfidenceAt: 0.45), sceneWorker: ControlledSceneWorker())
        defer { model.close() }
        model.open(url)
        try await wait { model.phase == .paused || model.phase == .failed }
        try await confirmCountingBar(model)
        model.play()
        try await wait { model.phase == .finished || model.phase == .failed }
        #expect(model.phase == .finished)
        #expect(model.countingFrames == times.count)
        #expect(model.analyzableFrames == times.count - 1)
        #expect(abs((model.trackingCoverage ?? -1) - (5.0 / 6.0)) < 1e-9)
    }

    @Test func inferenceFailureInterruptsAnActiveAttemptAndReimportClearsIt() async throws {
        let times = [0,15,30,45,60].map { CMTime(value: $0, timescale: 100) }
        let url = try await VideoTestSupport.makeVideo(timestamps: times)
        defer { try? FileManager.default.removeItem(at: url) }
        let model = ReplayController(estimator: CountingTestEstimator(failAt: 0.45), sceneWorker: ControlledSceneWorker())
        defer { model.close() }
        model.open(url)
        try await wait { model.phase == .paused || model.phase == .failed }
        try await confirmCountingBar(model)
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

    @Test(arguments: [0.6, 0.9])
    func interruptionPreservesCompletedPrefixButNeverCreditsAnUnreturnedAcceptance(failAt: Double) async throws {
        let times = [0,15,30,45,60,75,90].map { CMTime(value: $0, timescale: 100) }
        let url = try await VideoTestSupport.makeVideo(timestamps: times)
        defer { try? FileManager.default.removeItem(at: url) }
        #if os(macOS)
        let clock = PublicReplayClock()
        let trace: ReplayTrace.Handler? = clock.observe
        #else
        let trace: ReplayTrace.Handler? = nil
        #endif
        let model = ReplayController(estimator: CountingTestEstimator(failAt: failAt), trace: trace, sceneWorker: ControlledSceneWorker())
        defer { model.close() }
        model.open(url)
        try await wait { model.phase == .paused || model.phase == .failed }
        try await confirmCountingBar(model)
        model.play()
        try await wait { model.phase == .finished || model.phase == .failed }
        #expect(model.phase == .failed)
        #expect(model.counter.events.filter { $0.outcome == .movement }.map(\.sourceSeconds) == [0.45])
        let expectedCompleted = failAt == 0.9 ? 1 : 0
        #expect(model.analysisRecord?.completedCount == expectedCompleted)
        #expect(model.repAnalysis.filter(\.isCompleteForAnalytics).count == expectedCompleted)
        #expect(model.displayedFrames == (failAt == 0.9 ? 6 : 4))
        #expect(model.repAnalysis.filter(\.isCompleteForAnalytics).allSatisfy {
            ($0.returnedAt ?? .infinity) <= (model.frame?.pose.timestamp.seconds ?? -.infinity)
        })
        #if os(macOS)
        // Exercise the actual public-runner snapshot with native decoded
        // frames, production counter and shipping common analytics.
        let progress = PublicReplayMeasurements(model: model, expected: times.map(\.seconds),
            observed: clock.values, finished: false, mediaVerified: true)
        #expect(progress.status == "interrupted")
        #expect(progress.frames == model.displayedFrames)
        #expect(progress.acceptanceSeconds == [0.45])
        #expect(progress.completedSeconds.count == expectedCompleted)
        #expect(!progress.sourceClockVerified)
        #endif
    }

    #if os(macOS)
    @Test func publicReplaySnapshotReadsClosedReturnsWithoutFinishingAnActiveReview() async throws {
        let times = [0,15,30,45,60,75,90,105,120].map { CMTime(value: $0, timescale: 100) }
        let url = try await VideoTestSupport.makeVideo(timestamps: times)
        defer { try? FileManager.default.removeItem(at: url) }
        let clock = PublicReplayClock()
        let model = ReplayController(estimator: CountingTestEstimator(), trace: clock.observe, sceneWorker: ControlledSceneWorker())
        defer { model.close() }
        model.open(url)
        try await wait { model.phase == .paused || model.phase == .failed }
        try await confirmCountingBar(model)
        model.play()
        try await wait { (model.counter.observedMovements == 1 && model.counter.phase == .ready)
            || model.phase == .finished || model.phase == .failed }
        model.pause()
        try #require(model.phase == .paused)
        #expect(model.analysisRecord == nil)
        let phase = model.counter.phase
        let progress = PublicReplayMeasurements(model: model, expected: times.map(\.seconds),
            observed: clock.values, finished: false, mediaVerified: true)
        #expect(progress.status == "interrupted" && !progress.sourceClockVerified)
        #expect(progress.acceptanceSeconds == [0.45])
        #expect(progress.completedSeconds.count == 1)
        #expect(progress.frames == model.displayedFrames && progress.frames < times.count)
        #expect(model.analysisRecord == nil && model.counter.phase == phase)
    }
    #endif

    @Test(arguments: [false, true])
    func cameraMovementInterruptsAnUnreturnedAcceptanceAndLatchesThroughEOF(scale: Bool) async throws {
        let times = [0,15,30,45,60,75,90].map { CMTime(value: $0, timescale: 100) }
        let url = try await VideoTestSupport.makeVideo(timestamps: times)
        defer { try? FileManager.default.removeItem(at: url) }
        let movement: ControlledSceneWorker.Response = scale ? .scaled : .shifted
        let worker = ControlledSceneWorker(responses: [.stable, movement, movement, .stable])
        let evidence = RecordingSceneTrace()
        let model = ReplayController(estimator: CountingTestEstimator(), sceneWorker: worker, sceneTrace: evidence.observe)
        defer { model.close() }
        model.open(url)
        try await wait { model.phase == .paused || model.phase == .failed }
        try await confirmCountingBar(model)
        model.play()
        try await wait { model.phase == .finished || model.phase == .failed }
        let reason = scale ? "sceneScaled" : "sceneShifted"
        #expect(model.phase == .finished && model.displayedFrames == times.count)
        #expect(model.currentBar != nil, "Keep the authored bar separate from counting qualification.")
        #expect(model.cameraTrackingIssue == reason)
        #expect(model.counter.observedMovements == 1, "The pre-confirmation peak remains an observed excursion.")
        #expect(model.counter.interruptedAttempts == 0, "The peak already closed the provisional counter event.")
        #expect(model.repAnalysis.filter { $0.closeReason == reason }.count == 1,
                "The still-unreturned analytics attempt is interrupted exactly once.")
        #expect(model.analysisRecord?.completedCount == 0, "Camera interruption cannot become a completed analytics rep.")
        #expect(model.analysisRecord?.endReason == reason)
        #expect(evidence.events.map(\.sourceSeconds) == [0, 0.3, 0.6])
        #expect(evidence.events.allSatisfy { $0.calibrationSeconds == 0 })
        #expect(evidence.events.map(\.stateAfter) == ["stable", "stable", "moved"])
        #expect(evidence.events.last?.movementKind == (scale ? "scale" : "translation"))
    }

    @Test func unavailableRegistrationRevokesEarlierEvidenceWithoutLosingSourceFrames() async throws {
        let times = [0,15,30,45,60,75].map { CMTime(value: $0, timescale: 100) }
        let url = try await VideoTestSupport.makeVideo(timestamps: times)
        defer { try? FileManager.default.removeItem(at: url) }
        let worker = ControlledSceneWorker(responses: [.stable, .unavailable, .unavailable])
        let evidence = RecordingSceneTrace()
        let model = ReplayController(estimator: CountingTestEstimator(), sceneWorker: worker, sceneTrace: evidence.observe)
        defer { model.close() }
        model.open(url)
        try await wait { model.phase == .paused || model.phase == .failed }
        try await confirmCountingBar(model)
        model.play()
        try await wait { model.phase == .finished || model.phase == .failed }
        #expect(model.phase == .finished && model.displayedFrames == times.count)
        #expect(model.cameraTrackingIssue == "cameraSceneUnavailable")
        #expect(model.counter.observedMovements == 0)
        #expect(model.analysisRecord?.completedCount == 0)
        #expect(model.countingFrames == times.count && model.analyzableFrames == 2)
        #expect(evidence.events.map(\.sourceSeconds) == [0, 0.3, 0.6])
        #expect(evidence.events.dropFirst().allSatisfy { $0.measurement == nil && $0.stateAfter == "calibrating" })
    }

    @Test func cameraMovementInterruptsAnOutboundCounterAttemptBeforeAcceptance() async throws {
        let times = [0,15,30,45,60,75,90].map { CMTime(value: $0, timescale: 100) }
        let url = try await VideoTestSupport.makeVideo(timestamps: times)
        defer { try? FileManager.default.removeItem(at: url) }
        let worker = ControlledSceneWorker(responses: [.stable, .shifted, .shifted])
        let model = ReplayController(estimator: CountingTestEstimator(peakAt: 0.6, returnAt: 0.9), sceneWorker: worker)
        defer { model.close() }
        model.open(url)
        try await wait { model.phase == .paused || model.phase == .failed }
        try await confirmCountingBar(model)
        model.play()
        try await wait { model.phase == .finished || model.phase == .failed }
        #expect(model.counter.observedMovements == 0 && model.counter.interruptedAttempts == 1)
        #expect(model.counter.events.filter { $0.reason == "sceneShifted" }.count == 1)
        #expect(model.analysisRecord?.completedCount == 0)
        #expect(model.displayedFrames == times.count)
    }

    @Test func pauseDuringRegistrationRetriesThePendingFrameAndDiscardsTheOldResult() async throws {
        let times = [0,15,30,45,60,75].map { CMTime(value: $0, timescale: 100) }
        let url = try await VideoTestSupport.makeVideo(timestamps: times)
        defer { try? FileManager.default.removeItem(at: url) }
        let worker = ControlledSceneWorker(responses: [.stable, .unavailable, .stable, .stable], blockedRequest: 2)
        let model = ReplayController(estimator: CountingTestEstimator(), sceneWorker: worker)
        defer { model.close(); Task { await worker.release() } }
        model.open(url)
        try await wait { model.phase == .paused || model.phase == .failed }
        try await confirmCountingBar(model)
        model.play()
        try await waitForScene(worker, requests: 2)
        model.pause()
        let displayed = model.displayedFrames
        await worker.release()
        try await Task.sleep(for: .milliseconds(30))
        #expect(model.displayedFrames == displayed)
        model.play()
        try await wait { model.phase == .finished || model.phase == .failed }
        #expect(model.displayedFrames == times.count)
        #expect(await worker.requests == 4, "The canceled measurement cannot advance the source sampling clock.")
        #expect(model.counter.observedMovements == 1 && model.analysisRecord?.completedCount == 1)
        #expect(model.cameraTrackingIssue == nil)
    }

    @Test func replacingSourceDuringRegistrationCannotPublishOldCameraEvidence() async throws {
        let times = [0,15,30,45,60,75].map { CMTime(value: $0, timescale: 100) }
        let first = try await VideoTestSupport.makeVideo(timestamps: times)
        let second = try await VideoTestSupport.makeVideo(width: 192, height: 128)
        defer { try? FileManager.default.removeItem(at: first); try? FileManager.default.removeItem(at: second) }
        let worker = ControlledSceneWorker(responses: [.stable, .shifted], blockedRequest: 2)
        let model = ReplayController(estimator: CountingTestEstimator(), sceneWorker: worker)
        defer { model.close(); Task { await worker.release() } }
        model.open(first)
        try await wait { model.phase == .paused || model.phase == .failed }
        try await confirmCountingBar(model)
        model.play()
        try await waitForScene(worker, requests: 2)
        model.open(second)
        await worker.release()
        try await wait { model.phase == .paused || model.phase == .failed }
        #expect(model.phase == .paused && model.frame?.image.width == 192)
        #expect(model.currentBar == nil && model.cameraTrackingIssue == nil)
        #expect(model.displayedFrames == 1 && model.counter.observedMovements == 0)
    }

    @Test func clearingSetupDuringInitialRewindRegistrationCannotStrandLoading() async throws {
        let url = try await VideoTestSupport.makeVideo()
        defer { try? FileManager.default.removeItem(at: url) }
        let worker = ControlledSceneWorker(blockedRequest: 1)
        let model = ReplayController(estimator: CountingTestEstimator(), sceneWorker: worker)
        defer { model.close(); Task { await worker.release() } }
        model.open(url)
        try await wait { model.phase == .paused || model.phase == .failed }
        let setup = try #require(model.beginBarSetup())
        let bar = ConfirmedBar(role: model.barRole, method: .manualEdge,
            referenceEdge: .init(a: .init(x: 16, y: 48), b: .init(x: 144, y: 48)), oppositeEdge: nil,
            imageSize: setup.frame.pose.imageSize, sourceTime: setup.frame.pose.timestamp)
        #expect(model.confirmBar(bar, for: setup))
        try await waitForScene(worker, requests: 1)
        #expect(model.phase == .loading)
        model.clearBar()
        await worker.release()
        try await wait { model.phase == .paused || model.phase == .failed }
        #expect(model.phase == .paused && model.displayedFrames == 1)
        #expect(model.currentBar == nil && model.cameraTrackingIssue == nil)
        model.play()
        try await wait { model.phase == .finished || model.phase == .failed }
        #expect(model.displayedFrames == VideoTestSupport.timestamps.count)
    }

    @Test func clearingSetupDuringRegistrationCannotRestoreItsReference() async throws {
        let times = [0,15,30,45,60,75].map { CMTime(value: $0, timescale: 100) }
        let url = try await VideoTestSupport.makeVideo(timestamps: times)
        defer { try? FileManager.default.removeItem(at: url) }
        let worker = ControlledSceneWorker(blockedRequest: 2)
        let model = ReplayController(estimator: CountingTestEstimator(), sceneWorker: worker)
        defer { model.close(); Task { await worker.release() } }
        model.open(url)
        try await wait { model.phase == .paused || model.phase == .failed }
        try await confirmCountingBar(model)
        model.play()
        try await waitForScene(worker, requests: 2)
        model.clearBar()
        await worker.release()
        model.play()
        try await wait { model.phase == .finished || model.phase == .failed }
        #expect(model.currentBar == nil && model.cameraTrackingIssue == nil)
        #expect(model.counter.observedMovements == 0 && model.displayedFrames == times.count)
    }

    @Test func laterManualCalibrationKeepsItsExactPixelsWhenRewinding() async throws {
        let times = [0,15,30,45,60,75].map { CMTime(value: $0, timescale: 100) }
        let url = try await VideoTestSupport.makeVideo(timestamps: times, cyclingQuadrants: true)
        defer { try? FileManager.default.removeItem(at: url) }
        let worker = ControlledSceneWorker()
        let model = ReplayController(estimator: CountingTestEstimator(), sceneWorker: worker)
        defer { model.close() }
        model.open(url)
        try await wait { model.phase == .paused || model.phase == .failed }
        model.play()
        try await wait { (model.frame?.pose.timestamp.seconds ?? 0) >= 0.3 || model.phase == .failed }
        model.pause()
        let setup = try #require(model.frame)
        let color = VideoTestSupport.rgb(setup.image, normalizedTopLeft: .init(x: 0.25, y: 0.25))
        try await confirmCountingBar(model)
        model.play()
        try await wait { model.phase == .finished || model.phase == .failed }
        let colors = await worker.referenceColors
        #expect(!colors.isEmpty && colors.allSatisfy { $0 == color })
        #expect(await worker.currentColors.first != color, "The first rewound frame must not become the calibration image.")
        #expect(model.counter.observedMovements == 1 && model.analysisRecord?.completedCount == 1)
    }

    @Test func replacingManualSetupDiscardsItsInFlightSceneObservation() async throws {
        let times = [0,15,30,45,60,75].map { CMTime(value: $0, timescale: 100) }
        let url = try await VideoTestSupport.makeVideo(timestamps: times, cyclingQuadrants: true)
        defer { try? FileManager.default.removeItem(at: url) }
        let worker = ControlledSceneWorker(responses: [.stable, .unavailable], blockedRequest: 2)
        let model = ReplayController(estimator: CountingTestEstimator(), sceneWorker: worker)
        defer { model.close(); Task { await worker.release() } }
        model.open(url)
        try await wait { model.phase == .paused || model.phase == .failed }
        try await confirmCountingBar(model)
        model.play()
        try await waitForScene(worker, requests: 2)
        let setup = try #require(model.beginBarSetup())
        let color = VideoTestSupport.rgb(setup.frame.image, normalizedTopLeft: .init(x: 0.25, y: 0.25))
        let bar = ConfirmedBar(role: model.barRole, method: .manualEdge,
            referenceEdge: .init(a: .init(x: 16, y: 48), b: .init(x: 144, y: 48)), oppositeEdge: nil,
            imageSize: setup.frame.pose.imageSize, sourceTime: setup.frame.pose.timestamp)
        #expect(model.confirmBar(bar, for: setup))
        await worker.release()
        try await wait { model.phase == .paused || model.phase == .failed }
        model.play()
        try await wait { model.phase == .finished || model.phase == .failed }
        let colors = await worker.referenceColors
        #expect(colors.count >= 3 && colors.dropFirst(2).allSatisfy { $0 == color })
        #expect(model.currentBar == bar && model.cameraTrackingIssue == nil)
        #expect(model.displayedFrames == times.count && model.analysisRecord?.completedCount == 1)
    }

    private func waitForScene(_ worker: ControlledSceneWorker, requests: Int) async throws {
        let deadline = ContinuousClock.now.advanced(by: .seconds(5))
        while await worker.requests < requests {
            guard ContinuousClock.now < deadline else { throw FixtureError.failed("Scene request timed out") }
            try await Task.sleep(for: .milliseconds(5))
        }
    }

    private func confirmCountingBar(_ model: ReplayController) async throws {
        let setup = try #require(model.beginBarSetup())
        let size = setup.frame.pose.imageSize
        let y = size.height / 2
        let bar = ConfirmedBar(role: model.barRole, method: .manualEdge,
            referenceEdge: BarSegment(a: Point2D(x: size.width * 0.1, y: y),
                                      b: Point2D(x: size.width * 0.9, y: y)),
            oppositeEdge: nil, imageSize: size, sourceTime: setup.frame.pose.timestamp)
        #expect(model.confirmBar(bar, for: setup))
        try await wait { model.phase == .paused || model.phase == .failed }
        try #require(model.phase == .paused)
        #expect(model.currentBar != nil)
    }

    private func wait(until predicate: () -> Bool) async throws {
        let deadline = ContinuousClock.now.advanced(by: .seconds(60))
        while !predicate() {
            guard ContinuousClock.now < deadline else { throw FixtureError.failed("Replay state transition timed out.") }
            try await Task.sleep(for: .milliseconds(10))
        }
    }
}

// Synthetic scene evidence isolates controller/FFI lifecycle tests from the
// native registration algorithm, which has separate real-pixel integration tests.
private final class RecordingSceneTrace: Sendable {
    private let storage = OSAllocatedUnfairLock(initialState: [ReplaySceneTrace]())
    func observe(_ event: ReplaySceneTrace) { storage.withLock { $0.append(event) } }
    var events: [ReplaySceneTrace] { storage.withLock { $0 } }
}

actor ControlledSceneWorker: StaticSceneRegistering {
    enum Response: Equatable, Sendable { case stable, shifted, scaled, unavailable }
    private let responses: [Response]
    private let blockedRequest: Int?
    private var continuation: CheckedContinuation<Void, Never>?
    private(set) var requests = 0
    private(set) var referenceColors: [[UInt8]] = []
    private(set) var currentColors: [[UInt8]] = []
    init(responses: [Response] = [], blockedRequest: Int? = nil) {
        self.responses = responses; self.blockedRequest = blockedRequest
    }
    func release() { continuation?.resume(); continuation = nil }
    func measure(reference: StaticSceneRegistrationReference, image: CGImage) async throws -> StaticSceneRegistrationMeasurement {
        requests += 1
        let number = requests
        referenceColors.append(VideoTestSupport.rgb(reference.image, normalizedTopLeft: .init(x: 0.25, y: 0.25)))
        currentColors.append(VideoTestSupport.rgb(image, normalizedTopLeft: .init(x: 0.25, y: 0.25)))
        if number == blockedRequest { await withCheckedContinuation { continuation = $0 } }
        let response = number <= responses.count ? responses[number - 1] : .stable
        if response == .unavailable { throw FixtureError.failed("Unavailable scene registration") }
        return .init(translations: Array(repeating: .init(dxPixels: response == .shifted ? 20 : 0, dyPixels: 0), count: 4),
                     globalScaleFraction: response == .scaled ? 0.1 : 0)
    }
}

private final class RecordingGripObserver: GripObservationScheduling {
    private struct State: Sendable {
        var enabled = true
        var observations: [Bool] = []
    }
    private let state = OSAllocatedUnfairLock(initialState: State())
    var enabledAtObservation: [Bool] { state.withLock { $0.observations } }
    func observe(image: CGImage, pose: PoseResult) -> WorkoutGripObservation? {
        state.withLock { $0.observations.append($0.enabled) }
        return nil
    }
    func reset() {}
    func setEnabled(_ enabled: Bool) { state.withLock { $0.enabled = enabled } }
}

// Synthetic arm observations over actual decoded quadrant-video timestamps.
// These tests qualify controller/counter wiring, not a pose model or rep accuracy.
private struct CountingTestEstimator: PoseEstimator {
    var failAt: Double? = nil
    var lowConfidenceAt: Double? = nil
    var peakAt = 0.3
    var returnAt = 0.6

    func estimate(image: CGImage, timestamp: PresentationTime) throws -> PoseResult {
        let time = timestamp.seconds
        if let failAt, time >= failAt { throw FixtureError.failed("Counting test inference failure") }
        let confidence = lowConfidenceAt.map { abs(time - $0) < 1e-9 } == true ? 0.1 : 1
        let base = ExerciseCounterTests.pose(
            time,
            degrees: time >= 0.3 && time < peakAt ? 120 : time >= peakAt && time < returnAt ? 80 : 170,
            confidence: confidence
        )
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
