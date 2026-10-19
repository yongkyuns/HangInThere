import CoreGraphics
import Foundation
import Testing
import os
import class WorkoutCore.WorkoutGripInferenceBridge
@testable import HangInThere

struct VisionDipObservationTests {
    @Test func pixelBackendFailureRevokesDwellInsteadOfPausingAnEmptyProposal() async throws {
        let worker = VisionDipObservationWorker(hands: CurrentHands(), pixels: FailingPixels())
        let pixels = try railImage()
        for index in 0..<3 {
            _ = try await worker.observe(image: pixels, pose: timedFrame(index * 300), generation: 0)
        }
        let failed = try await worker.observe(image: pixels, pose: timedFrame(900), generation: 0)
        #expect(failed.extractionFailures == ["railExtractionFailed:0"])
        #expect(failed.acquisition.reference == nil)
        #expect(failed.acquisition.diagnostic.observations == 0)
        let generation = await worker.generation
        #expect(generation == 1)
        for index in 4..<7 {
            let next = try await worker.observe(image: pixels, pose: timedFrame(index * 300), generation: generation)
            #expect(next.acquisition.reference == nil)
        }
        let acquired = try await worker.observe(image: pixels, pose: timedFrame(2100), generation: generation)
        #expect(acquired.acquisition.reference != nil)
    }

    @Test func validFreshFullFrameHandsSkipEveryCrop() throws {
        let pose = frame()
        let original = hands(pose: pose, seconds: pose.timestamp.seconds).hands
        let detector = PixelDetector(outputs: [original])
        let observed = try VisionDipHandEstimator(detector: detector).observe(image: image(), pose: pose)
        #expect(detector.calls == [[720, 1280, 2]])
        #expect(observed.hands == original)
        #expect(observed.seconds == pose.timestamp.seconds)
        #expect(observed.body.isEmpty)
    }

    @Test(arguments: [false, true])
    func aMissingHandUsesFreshBoundedCropsAndMapsPixelsBackOnce(includesWrist: Bool) throws {
        let pose = frame()
        let base = hands(pose: pose, seconds: pose.timestamp.seconds).hands
        let original = includesWrist ? handsWithWrists(base, pose: pose) : base
        let requests = try JSONDecoder().decode([WorkoutGripCropRequest].self, from: Data(
            WorkoutGripInferenceBridge().handRequestsJson(poseJson: String(decoding: JSONEncoder().encode(pose), as: UTF8.self)).utf8))
        let region = try #require(requests.first { $0.side == "right" }?.regions.first)
        let cropped = WorkoutGripHandObservation(points: original[1].points.map { point in
            WorkoutGripHandPoint(joint: point.joint,
                position: Point2D(x: point.position.x - Double(region.left), y: point.position.y - Double(region.top)),
                world: nil, score: point.score)
        }, sdkHandedness: nil, sdkHandednessScore: nil)
        let detector = PixelDetector(outputs: [[original[0]], [], [], [cropped]],
            format: includesWrist ? .wristAndGripMCPs : .gripMCPs)
        let observed = try VisionDipHandEstimator(detector: detector).observe(image: image(), pose: pose)
        #expect(detector.calls.count == 4)
        #expect(detector.calls.last == [region.width, region.height, 1])
        #expect(observed.hands[0] == original[0])
        #expect(observed.hands[1].points == original[1].points)
        #expect(observed.hands[1].sourceRegion == region)
        #expect(observed.trackedBody == hands(pose: pose, seconds: pose.timestamp.seconds).trackedBody)
        #expect(observed.seconds == pose.timestamp.seconds)
    }

    @Test func explicitObservedWristFormatPreservesPointsAndHasItsOwnSourceContext() throws {
        let pose = frame()
        let original = hands(pose: pose, seconds: pose.timestamp.seconds).hands
        let complete = handsWithWrists(original, pose: pose)
        let legacy = try VisionDipHandEstimator(usesCrops: false, detector: PixelDetector(outputs: [original]))
            .observe(image: image(), pose: pose)
        let current = try VisionDipHandEstimator(usesCrops: false,
            detector: PixelDetector(outputs: [complete], format: .wristAndGripMCPs))
            .observe(image: image(), pose: pose)
        #expect(legacy.modelId == "VNDetectHumanHandPoseRequest-revision1")
        #expect(current.modelId == "VNDetectHumanHandPoseRequest-revision1-wrist_mcp_v1")
        #expect(current.hands == complete)
        #expect(current.trackedBody == legacy.trackedBody)
        #expect(current.seconds == legacy.seconds)
        #expect(current.hands.allSatisfy { $0.points.count == 4 })
        #expect(current.hands.map { $0.points.filter { $0.joint != "wrist" } } == original.map(\.points))
    }

    @Test func observedWristDetectorDoesNotInventPointsOnBlankPixels() throws {
        let result = try VisionDipHandPixelDetector(format: .wristAndGripMCPs)
            .detect(image: image(), maximumHands: 2)
        #expect(result.isEmpty)
    }

    @Test func fullObservedWristsSkipCropsDespiteALargeKnuckleOffset() throws {
        let pose = frame()
        let original = hands(pose: pose, seconds: pose.timestamp.seconds).hands.map { value in
            WorkoutGripHandObservation(points: value.points.map { p in
                WorkoutGripHandPoint(joint: p.joint, position: Point2D(x: p.position.x, y: p.position.y + 60),
                    world: p.world, score: p.score)
            }, sdkHandedness: nil, sdkHandednessScore: nil)
        }
        let complete = handsWithWrists(original, pose: pose)
        let detector = PixelDetector(outputs: [complete], format: .wristAndGripMCPs)
        let result = try VisionDipHandEstimator(detector: detector).observe(image: image(), pose: pose)
        #expect(detector.calls == [[720, 1280, 2]])
        #expect(result.hands == complete)
        #expect(result.modelId == "VNDetectHumanHandPoseRequest-revision1-crops_v3-wrist_mcp_v1")
    }

    @Test func repeatedCropsOfOneHandCannotSubstituteForTheMissingOtherHand() throws {
        let pose = frame()
        let left = hands(pose: pose, seconds: pose.timestamp.seconds).hands[0]
        let detector = PixelDetector(outputs: [[left], [left], [left], [left], [left]])
        let observed = try VisionDipHandEstimator(detector: detector).observe(image: image(), pose: pose)
        #expect(observed.hands == [left])
        #expect(detector.calls.count == 5)
        #expect(detector.calls.dropFirst().allSatisfy { $0[2] == 1 })
    }

    @Test func cameraMovementRevokesThePublishedReferenceEvenAtSourceEnd() async throws {
        let worker = VisionDipWorkout(observer: VisionDipObservationWorker(hands: CurrentHands()),
            cameraWorker: ControlledSceneWorker(responses: [.stable, .shifted, .shifted]))
        let pixels = try railImage()
        var last: DipWorkoutFrameResult?
        for index in 0..<6 {
            last = try await worker.process(ProcessedFrame(image: pixels, pose: timedFrame(index * 300), processingMilliseconds: 0))
            if index == 3 { #expect(last?.support != nil) }
        }
        #expect(last?.camera == "moved")
        #expect(last?.support == nil)
        #expect(last?.snapshot.motion.availability == "cameraMoved")
        let record = try await worker.finish(reason: "endOfInput")
        #expect(record.completedCount == 0)
        #expect(record.reps.isEmpty)
    }

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

    private func handsWithWrists(_ hands: [WorkoutGripHandObservation], pose: PoseResult) -> [WorkoutGripHandObservation] {
        zip(hands, [PoseJoint.leftWrist, .rightWrist]).map { hand, joint in
            let wrist = pose.people[0].landmark(joint)!
            return WorkoutGripHandObservation(points: hand.points + [WorkoutGripHandPoint(joint: "wrist",
                position: wrist.position, world: nil, score: wrist.confidence)], sdkHandedness: hand.sdkHandedness,
                sdkHandednessScore: hand.sdkHandednessScore, sourceRegion: hand.sourceRegion)
        }
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
    private final class PixelDetector: DipHandPixelDetecting {
        let observationFormat: DipHandObservationFormat
        private let outputs: [[WorkoutGripHandObservation]]
        private let state = OSAllocatedUnfairLock(initialState: [[Int]]())
        init(outputs: [[WorkoutGripHandObservation]], format: DipHandObservationFormat = .gripMCPs) {
            self.outputs = outputs; observationFormat = format
        }
        var calls: [[Int]] { state.withLock { $0 } }
        func detect(image: CGImage, maximumHands: Int) -> [WorkoutGripHandObservation] {
            state.withLock { calls in
                let index = calls.count
                calls.append([image.width, image.height, maximumHands])
                return index < outputs.count ? outputs[index] : []
            }
        }
    }
    private final class FailingPixels: DipRailPixelExtracting {
        private let calls = OSAllocatedUnfairLock(initialState: 0)
        struct Failure: Error {}
        func contours(image: CGImage, region: CGRect) throws -> [[Point2D]] {
            let call = calls.withLock { $0 += 1; return $0 }
            if call == 7 { throw Failure() }
            return try NativeDipRailPixels().contours(image: image, region: region)
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
