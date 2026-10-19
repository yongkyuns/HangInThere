import CoreGraphics
import Foundation
import ObservedRailPixels
import Vision
import WorkoutCore

// These are observed finite image boundaries. They never become a straight
// ConfirmedBar, a body joint, or evidence of mechanical load/anatomical depth.
struct DipRailCandidate: Codable, Equatable, Sendable {
    struct Curve: Codable, Equatable, Sendable { let points: [Point2D] }
    let firstContour: Int
    let secondContour: Int
    let firstBoundary: Curve
    let secondBoundary: Curve
    let supportArcPixels: Double
    let minimumWidthPixels: Double
    let maximumWidthPixels: Double
    let contactDistancePixels: Double
}
struct DipRailReference: Codable, Equatable, Sendable {
    struct Context: Codable, Equatable, Sendable {
        let bodyBackend: String; let bodyRevision: Int; let handModel: String; let handBackend: String
    }
    let left: DipRailCandidate
    let right: DipRailCandidate
    let hands: [Point2D]
    let imageSize: ImageSize
    let sourceTime: PresentationTime
    let generation: Int64
    let context: Context
}
struct DipRailAcquisitionResult: Decodable, Sendable {
    struct Diagnostic: Decodable, Sendable {
        let status: String
        let observations: Int
        let stableSeconds: Double
    }
    let diagnostic: Diagnostic
    let reference: DipRailReference?
}
struct DipFrameObservation: Sendable {
    // Includes fresh hand MCPs on the unchanged primary body pose. Consumers
    // retain the originating image with this result and its source generation.
    let pose: PoseResult
    let generation: Int64
    let acquisition: DipRailAcquisitionResult
    let extractionFailures: [String]
}

protocol DipHandEstimating: Sendable {
    func observe(image: CGImage, pose: PoseResult) throws -> WorkoutGripObservation
}

struct VisionDipHandEstimator: DipHandEstimating {
    func observe(image: CGImage, pose: PoseResult) throws -> WorkoutGripObservation {
        let request = VNDetectHumanHandPoseRequest()
        request.revision = VNDetectHumanHandPoseRequestRevision1
        request.maximumHandCount = 2
        try VNImageRequestHandler(cgImage: image, orientation: .up, options: [:]).perform([request])
        // The native request returns all joints, but this geometry contract only
        // consumes three independent MCP observations. Missing points stay absent.
        let names: [(VNHumanHandPoseObservation.JointName, String)] = [
            (.indexMCP, "indexMCP"), (.middleMCP, "middleMCP"), (.littleMCP, "pinkyMCP")
        ]
        let hands = try (request.results ?? []).map { hand in
            let points = try names.compactMap { joint, name -> WorkoutGripHandPoint? in
                let point = try hand.recognizedPoint(joint)
                return WorkoutGripHandPoint(joint: name,
                    position: Point2D(x: point.location.x * Double(image.width),
                                      y: (1 - point.location.y) * Double(image.height)),
                    world: nil, score: Double(point.confidence))
            }
            return WorkoutGripHandObservation(points: points, sdkHandedness: nil, sdkHandednessScore: nil)
        }
        let joints: Set<PoseJoint> = [.leftShoulder, .rightShoulder, .leftWrist, .rightWrist]
        let tracked = (pose.people.count == 1 ? pose.people[0].landmarks : [])
            .filter { joints.contains($0.joint) }
            .map { WorkoutGripTrackedPoint(joint: $0.joint.rawValue, position: $0.position, score: $0.confidence) }
        return WorkoutGripObservation(seconds: pose.timestamp.seconds, imageSize: pose.imageSize,
            trackedBody: tracked, body: [], hands: hands,
            modelId: "VNDetectHumanHandPoseRequest-revision1", backend: "Apple Vision hand pose")
    }
}

// One serial native owner holds all source state. Blocking SDK and C++ calls use
// a dedicated Dispatch executor; no UI work, cached hand results or frame queue.
// The caller captures generation before enqueueing work, and discards results
// when its own source/playback epoch changes while awaiting this actor.
actor VisionDipObservationWorker {
    private nonisolated let executor = NativeFrameExecutor(label: "dev.hanginthere.dip-observations")
    nonisolated var unownedExecutor: UnownedSerialExecutor { executor.asUnownedSerialExecutor() }
    private let hands: any DipHandEstimating
    private let acquisition = CurvedDipRailAcquisitionBridge()
    private let fitter = ObservedRailFitterBridge()
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init(hands: any DipHandEstimating = VisionDipHandEstimator()) { self.hands = hands }
    var generation: Int64 { acquisition.generation }
    @discardableResult func resetSource() -> Int64 { acquisition.resetSource(); return generation }
    func invalidateEvidence() { acquisition.invalidateEvidence() }

    private struct Search: Decodable {
        let hands: [Point2D]
        let regions: [BarRegion]
    }
    private struct Contour: Encodable { let points: [Point2D]; let darkOnLight = true }
    private struct Fit: Encodable {
        let contours: [Contour]; let region: BarRegion; let imageSize: ImageSize; let grip: Point2D
    }
    private struct AcquisitionInput: Encodable {
        let pose: PoseResult; let candidates: [[DipRailCandidate]]; let generation: Int64
    }
    enum ObservationError: Error { case incompatibleImage, obsoleteGeneration }

    func observe(image: CGImage, pose original: PoseResult, generation expected: Int64) throws -> DipFrameObservation {
        try Task.checkCancellation()
        guard expected == generation else { throw ObservationError.obsoleteGeneration }
        var completed = false
        // Any thrown current-frame processing/transport failure revokes prior
        // dwell, including an encoding failure before observeJson is reached.
        defer { if !completed && expected == generation { acquisition.invalidateEvidence() } }
        guard original.imageSize == ImageSize(width: Double(image.width), height: Double(image.height)) else {
            acquisition.invalidateEvidence()
            throw ObservationError.incompatibleImage
        }
        var pose = original
        // A failed current request must revoke old evidence, even when the input
        // primary frame already carries a cached observation from another worker.
        pose.gripObservation = nil
        var failures: [String] = []
        do { pose.gripObservation = try hands.observe(image: image, pose: pose) }
        catch { failures.append("handInferenceFailed") }
        var candidates: [[DipRailCandidate]] = [[], []]
        if let json = try acquisition.searchJson(input: encode(pose)) {
            let search = try decoder.decode(Search.self, from: Data(json.utf8))
            guard search.hands.count == 2, search.regions.count == 2 else {
                acquisition.invalidateEvidence(); throw ObservationError.incompatibleImage
            }
            for side in 0..<2 {
                try Task.checkCancellation()
                let region = search.regions[side]
                do {
                    let rect = CGRect(x: region.minX, y: region.minY,
                        width: region.maxX - region.minX, height: region.maxY - region.minY)
                    let contours = try ObservedRailPixels.contours(image: image, region: rect).map {
                        Contour(points: $0.points.map { Point2D(x: $0.x, y: $0.y) })
                    }
                    // Fitting uses the same integral ROI as native extraction.
                    let crop = rect.integral
                    let integralRegion = BarRegion(Point2D(x: crop.minX, y: crop.minY), Point2D(x: crop.maxX, y: crop.maxY))
                    let fitted = try fitter.fitJson(input: encode(Fit(contours: contours, region: integralRegion,
                        imageSize: pose.imageSize, grip: search.hands[side])))
                    candidates[side] = try decoder.decode([DipRailCandidate].self, from: Data(fitted.utf8))
                } catch { failures.append("railExtractionFailed:\(side)") }
            }
        }
        try Task.checkCancellation()
        let result = try acquisition.observeJson(input: encode(AcquisitionInput(pose: pose,
            candidates: candidates, generation: expected)))
        let observation = DipFrameObservation(pose: pose, generation: expected,
            acquisition: try decoder.decode(DipRailAcquisitionResult.self, from: Data(result.utf8)),
            extractionFailures: failures)
        completed = true
        return observation
    }
    private func encode<T: Encodable>(_ value: T) throws -> String {
        String(decoding: try encoder.encode(value), as: UTF8.self)
    }
}
