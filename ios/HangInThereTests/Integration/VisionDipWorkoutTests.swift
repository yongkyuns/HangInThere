import CoreGraphics
import Foundation
import Testing
@testable import HangInThere

struct VisionDipWorkoutTests {
    @MainActor @Test func replayPauseResumesTheConsumedNativeTransactionExactlyOnce() async throws {
        let url = try await VideoTestSupport.makeVideo()
        defer { try? FileManager.default.removeItem(at: url) }
        let observer = GatedObserver(gatedAt: 0.05)
        let model = ReplayController(estimator: TestPoseEstimator(), dipProcessorFactory: { VisionDipWorkout(observer: observer) })
        defer { model.close() }
        model.configureCounting(exercise: .dip, side: .left)
        model.open(url)
        try await wait { model.phase == .paused || model.phase == .failed }
        try #require(model.phase == .paused)
        model.play()
        await observer.waitUntilBlocked()
        model.pause()
        let displayed = model.displayedFrames
        await observer.release()
        model.play()
        try await wait { model.phase == .finished || model.phase == .failed }
        #expect(model.phase == .finished)
        #expect(displayed == 1)
        #expect(model.displayedFrames == 4)
        #expect(await observer.calls == 4)
        #expect(model.analysisRecord?.countMethod == "bilateralDipImageMotion")
        #expect(model.currentBar == nil)
    }

    @MainActor @Test func replacingReplayWhileNativeWorkRunsCannotPublishItsFrame() async throws {
        let url = try await VideoTestSupport.makeVideo()
        defer { try? FileManager.default.removeItem(at: url) }
        let observer = GatedObserver(gatedAt: 0.05)
        let model = ReplayController(estimator: TestPoseEstimator(), dipProcessorFactory: { VisionDipWorkout(observer: observer) })
        defer { model.close() }
        model.configureCounting(exercise: .dip, side: .left)
        model.open(url)
        try await wait { model.phase == .paused || model.phase == .failed }
        model.play()
        await observer.waitUntilBlocked()
        model.open(url)
        await observer.release()
        try await wait { model.phase == .paused || model.phase == .failed }
        #expect(model.phase == .paused)
        #expect(model.displayedFrames == 1)
        #expect(model.frame?.pose.timestamp.seconds == 0)
        #expect(model.dipSnapshot?.motion.availability != "sourceRegression")
        #expect(model.observedMovements == 0)
        #expect(model.analysisRecord == nil)
    }

    @MainActor private func wait(_ done: () -> Bool) async throws {
        let deadline = ContinuousClock.now.advanced(by: .seconds(10))
        while !done() {
            try #require(ContinuousClock.now < deadline)
            try await Task.sleep(for: .milliseconds(10))
        }
    }

    @Test func cancelledPresentationStillCommitsAndResumeReusesTheExactFrame() async throws {
        let observer = GatedObserver()
        let processor = VisionDipWorkout(observer: observer)
        let frame = try frame()
        let presentation = Task { try await processor.process(frame) }
        await observer.waitUntilStarted()
        presentation.cancel()
        await observer.release()
        let original = try await presentation.value
        let resumed = try await processor.process(frame)
        #expect(original.snapshot == resumed.snapshot)
        #expect(await observer.calls == 1)
        #expect(resumed.frame.image === frame.image)
        #expect(resumed.frame.pose == frame.pose)
    }

    @Test func identicalInFlightFrameCoalescesAndDifferentFrameIsRejected() async throws {
        let observer = GatedObserver()
        let processor = VisionDipWorkout(observer: observer)
        let first = try frame()
        let one = Task { try await processor.process(first) }
        await observer.waitUntilStarted()
        let duplicate = Task { try await processor.process(first) }
        await #expect(throws: VisionDipWorkout.ProcessingError.overlappingFrame) {
            try await processor.process(frame(ticks: 200))
        }
        await observer.release()
        let a = try await one.value
        let b = try await duplicate.value
        #expect(a.snapshot == b.snapshot)
        #expect(await observer.calls == 1)
    }

    @Test func sourcesOwnIndependentClocksAndFinishedInstancesStayClosed() async throws {
        let firstObserver = GatedObserver(released: true)
        let secondObserver = GatedObserver(released: true)
        let first = VisionDipWorkout(setId: "first", observer: firstObserver)
        let second = VisionDipWorkout(setId: "second", observer: secondObserver)
        _ = try await first.process(frame(ticks: 2000))
        let zero = try await second.process(frame())
        #expect(zero.snapshot.motion.availability != "sourceRegression")
        let record = try await first.finish(reason: "sourceEnded")
        #expect(record.id == "first")
        #expect(record.countMethod == "bilateralDipImageMotion")
        #expect(record.reps.isEmpty)
        #expect(try await first.finish(reason: "manual") == record)
        await #expect(throws: VisionDipWorkout.ProcessingError.finishedSource) {
            try await first.process(frame(ticks: 2200))
        }
    }

    @Test func finishWaitsForTheOwnedTransactionAndFencesLaterFrames() async throws {
        let observer = GatedObserver()
        let processor = VisionDipWorkout(observer: observer)
        let presentation = Task { try await processor.process(frame()) }
        await observer.waitUntilStarted()
        let closing = Task { try await processor.finish(reason: "manual") }
        await observer.release()
        _ = try await presentation.value
        let record = try await closing.value
        #expect(record.endReason == "manual")
        await #expect(throws: VisionDipWorkout.ProcessingError.finishedSource) {
            try await processor.process(frame(ticks: 200))
        }
    }

    private func frame(ticks: Int64 = 0) throws -> ProcessedFrame {
        let context = try #require(CGContext(data: nil, width: 96, height: 96, bitsPerComponent: 8,
            bytesPerRow: 384, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
        let image = try #require(context.makeImage())
        return ProcessedFrame(image: image, pose: PoseResult(timestamp: PresentationTime(value: ticks, timescale: 1000),
            imageSize: ImageSize(width: 96, height: 96), people: [], backend: "authored", requestRevision: 1),
            processingMilliseconds: 0)
    }

    private actor GatedObserver: DipFrameObserving {
        var generation: Int64 { 0 }
        private(set) var calls = 0
        private var released: Bool
        private let gatedAt: Double
        private var started: CheckedContinuation<Void, Never>?
        private var blocked: CheckedContinuation<Void, Never>?
        private var resume: CheckedContinuation<Void, Never>?
        init(released: Bool = false, gatedAt: Double = 0) { self.released = released; self.gatedAt = gatedAt }
        func waitUntilStarted() async {
            if calls > 0 { return }
            await withCheckedContinuation { started = $0 }
        }
        func release() { released = true; resume?.resume(); resume = nil }
        func waitUntilBlocked() async {
            if resume != nil { return }
            await withCheckedContinuation { blocked = $0 }
        }
        func invalidateEvidence() {}
        func observe(image: CGImage, pose: PoseResult, generation: Int64) async throws -> DipFrameObservation {
            calls += 1; started?.resume(); started = nil
            if !released, pose.timestamp.seconds >= gatedAt {
                await withCheckedContinuation { resume = $0; blocked?.resume(); blocked = nil }
            }
            try Task.checkCancellation()
            return DipFrameObservation(pose: pose, generation: generation,
                acquisition: DipRailAcquisitionResult(diagnostic: .init(status: "unavailable", observations: 0, stableSeconds: 0),
                    reference: nil), extractionFailures: [])
        }
    }
}
