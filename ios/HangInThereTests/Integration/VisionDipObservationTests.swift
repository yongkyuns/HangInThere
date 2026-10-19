import CoreGraphics
import Foundation
import Testing
@testable import HangInThere

struct VisionDipObservationTests {
    @Test func blankSourceDoesNotInventHandsOrSupports() async throws {
        let image = try image()
        let pose = frame()
        let observation = try await VisionDipObservationWorker().observe(image: image, pose: pose, generation: 0)
        #expect(observation.pose.people == pose.people)
        #expect(observation.pose.timestamp == pose.timestamp)
        #expect(observation.pose.backend == pose.backend)
        #expect(observation.pose.gripObservation?.hands.isEmpty == true)
        #expect(observation.acquisition.reference == nil)
        #expect(observation.acquisition.diagnostic.status == "unavailable")
        #expect(observation.extractionFailures.isEmpty)
    }

    @Test func cachedHandsAreReplacedAndCannotEarnCurrentEvidence() async throws {
        let image = try image()
        var pose = frame()
        let stale = hands(pose: pose, seconds: -1)
        pose.gripObservation = stale
        let worker = VisionDipObservationWorker(hands: SuppliedHands(value: stale))
        let observation = try await worker.observe(image: image, pose: pose, generation: 0)
        #expect(observation.pose.people == pose.people)
        #expect(observation.acquisition.reference == nil)
        #expect(observation.acquisition.diagnostic.status == "unavailable")
        #expect(observation.extractionFailures.isEmpty)
    }

    @Test func failedCurrentHandsRevokeCachedObservation() async throws {
        let image = try image()
        var pose = frame()
        pose.gripObservation = hands(pose: pose, seconds: 0)
        let observation = try await VisionDipObservationWorker(hands: FailingHands())
            .observe(image: image, pose: pose, generation: 0)
        #expect(observation.pose.gripObservation == nil)
        #expect(observation.pose.people == pose.people)
        #expect(observation.extractionFailures == ["handInferenceFailed"])
        #expect(observation.acquisition.reference == nil)
    }

    @Test func resetRejectsOldSourceButPermitsNewClockEpoch() async throws {
        let image = try image()
        let worker = VisionDipObservationWorker()
        let newGeneration = await worker.resetSource()
        #expect(newGeneration == 1)
        await #expect(throws: VisionDipObservationWorker.ObservationError.obsoleteGeneration) {
            try await worker.observe(image: image, pose: frame(), generation: 0)
        }
        let current = try await worker.observe(image: image, pose: frame(), generation: newGeneration)
        #expect(current.generation == newGeneration)
        #expect(current.acquisition.diagnostic.status == "unavailable")
    }

    @Test func sourceImageMismatchInvalidatesGeneration() async throws {
        let worker = VisionDipObservationWorker()
        let small = try image(width: 120, height: 120)
        await #expect(throws: VisionDipObservationWorker.ObservationError.incompatibleImage) {
            try await worker.observe(image: small, pose: frame(), generation: 0)
        }
        #expect(await worker.generation == 1)
    }

    @Test func nativePixelExtractionAcquiresTwoFiniteCurvedSupports() async throws {
        let worker = VisionDipObservationWorker(hands: CurrentHands())
        let pixels = try railImage()
        var last: DipFrameObservation?
        for index in 0..<4 {
            let pose = timedFrame(index * 300)
            last = try await worker.observe(image: pixels, pose: pose, generation: 0)
        }
        let reference = try #require(last?.acquisition.reference)
        #expect(reference.left.firstBoundary.points.count > 2)
        #expect(reference.right.firstBoundary.points.count > 2)
        #expect(reference.sourceTime == timedFrame(900).timestamp)
        #expect(last?.extractionFailures.isEmpty == true)
    }

    @Test func nonFiniteCurrentHandsInvalidateEarlierAcquisitionDwell() async throws {
        let worker = VisionDipObservationWorker(hands: CurrentHands(invalidAt: 0.9))
        let pixels = try railImage()
        for index in 0..<3 {
            let observation = try await worker.observe(image: pixels, pose: timedFrame(index * 300), generation: 0)
            #expect(observation.acquisition.reference == nil)
            #expect(observation.acquisition.diagnostic.observations == index + 1)
        }
        do {
            _ = try await worker.observe(image: pixels, pose: timedFrame(900), generation: 0)
            Issue.record("Non-finite current hand coordinates must fail transport.")
        } catch is EncodingError { }
        let generation = await worker.generation
        #expect(generation == 1)
        let next = try await worker.observe(image: pixels, pose: timedFrame(1200), generation: generation)
        #expect(next.acquisition.reference == nil)
        #expect(next.acquisition.diagnostic.observations == 1)
    }

    private struct CurrentHands: DipHandEstimating {
        var invalidAt: Double? = nil
        func observe(image: CGImage, pose: PoseResult) -> WorkoutGripObservation {
            let fixture = VisionDipObservationTests()
            let value = fixture.hands(pose: pose, seconds: pose.timestamp.seconds)
            guard pose.timestamp.seconds == invalidAt else { return value }
            let bad = WorkoutGripHandObservation(points: [WorkoutGripHandPoint(joint: "indexMCP",
                position: Point2D(x: .nan, y: 640), world: nil, score: 0.9)], sdkHandedness: nil, sdkHandednessScore: nil)
            return WorkoutGripObservation(seconds: value.seconds, imageSize: value.imageSize, trackedBody: value.trackedBody,
                body: [], hands: [bad], modelId: value.modelId, backend: value.backend)
        }
    }
    private func timedFrame(_ ticks: Int) -> PoseResult {
        let original = frame()
        return PoseResult(timestamp: PresentationTime(value: Int64(ticks), timescale: 1000), imageSize: original.imageSize,
            people: original.people, backend: original.backend, requestRevision: original.requestRevision)
    }
    private func railImage() throws -> CGImage {
        let context = try #require(CGContext(data: nil, width: 720, height: 1280, bitsPerComponent: 8,
            bytesPerRow: 720 * 4, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
        context.setFillColor(CGColor(gray: 1, alpha: 1)); context.fill(CGRect(x: 0, y: 0, width: 720, height: 1280))
        context.translateBy(x: 0, y: 1280); context.scaleBy(x: 1, y: -1)
        context.setStrokeColor(CGColor(gray: 0, alpha: 1)); context.setLineWidth(8)
        for handX in [200.0, 500.0] {
            context.beginPath()
            for index in 0...60 {
                let angle = -0.6 + Double(index) * 1.2 / 60
                let point = CGPoint(x: handX - 50 + 64 * cos(angle), y: 700 + 64 * sin(angle))
                if index == 0 { context.move(to: point) } else { context.addLine(to: point) }
            }
            context.strokePath()
        }
        return try #require(context.makeImage())
    }

    private struct SuppliedHands: DipHandEstimating {
        let value: WorkoutGripObservation
        func observe(image: CGImage, pose: PoseResult) -> WorkoutGripObservation { value }
    }
    private struct FailingHands: DipHandEstimating {
        struct Failure: Error {}
        func observe(image: CGImage, pose: PoseResult) throws -> WorkoutGripObservation { throw Failure() }
    }
    private func hands(pose: PoseResult, seconds: Double) -> WorkoutGripObservation {
        let tracked = pose.people[0].landmarks.filter { $0.joint != .leftElbow && $0.joint != .rightElbow }
            .map { WorkoutGripTrackedPoint(joint: $0.joint.rawValue, position: $0.position, score: $0.confidence) }
        return WorkoutGripObservation(seconds: seconds, imageSize: pose.imageSize, trackedBody: tracked,
            body: [], hands: [200.0, 500.0].map { x in
                WorkoutGripHandObservation(points: ["indexMCP", "middleMCP", "pinkyMCP"].map {
                    WorkoutGripHandPoint(joint: $0, position: Point2D(x: x, y: 640), world: nil, score: 0.9)
                }, sdkHandedness: nil, sdkHandednessScore: nil)
            }, modelId: "authored-hand", backend: "authored-hand")
    }
    private func frame() -> PoseResult {
        let points = zip([PoseJoint.leftShoulder, .leftElbow, .leftWrist,
                          .rightShoulder, .rightElbow, .rightWrist],
                         [(200.0, 200.0), (200, 400), (200, 600), (500, 200), (500, 400), (500, 600)])
            .map { Landmark(joint: $0.0, position: Point2D(x: $0.1.0, y: $0.1.1), confidence: 0.9) }
        return PoseResult(timestamp: PresentationTime(value: 0, timescale: 1000), imageSize: ImageSize(width: 720, height: 1280),
                          people: [PoseObservation(landmarks: points)], backend: "authored-body", requestRevision: 1)
    }
    private func image(width: Int = 720, height: Int = 1280) throws -> CGImage {
        let context = try #require(CGContext(data: nil, width: width, height: height, bitsPerComponent: 8,
            bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
        context.setFillColor(CGColor(gray: 0.5, alpha: 1)); context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        return try #require(context.makeImage())
    }
}
