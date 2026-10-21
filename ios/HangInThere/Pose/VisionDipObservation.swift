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
    var acquisitionPolicyVersion: Int? = nil
}
struct DipRailAcquisitionResult: Decodable, Sendable {
    struct Diagnostic: Codable, Sendable {
        let status: String
        let observations: Int
        let stableSeconds: Double
        var policyVersion: Int? = nil
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
    var candidates: [[DipRailCandidate]] = []
}

protocol DipHandEstimating: Sendable {
    func observe(image: CGImage, pose: PoseResult) throws -> WorkoutGripObservation
}

protocol DipRailPixelExtracting: Sendable {
    func contours(image: CGImage, region: CGRect) throws -> [[Point2D]]
}
struct NativeDipRailPixels: DipRailPixelExtracting {
    func contours(image: CGImage, region: CGRect) throws -> [[Point2D]] {
        try ObservedRailPixels.contours(image: image, region: region).map { contour in
            contour.points.map { Point2D(x: $0.x, y: $0.y) }
        }
    }
}

enum DipHandObservationFormat: Sendable {
    case gripMCPs, wristAndGripMCPs
    var modelSuffix: String {
        switch self {
        case .gripMCPs: ""
        case .wristAndGripMCPs: "-wrist_mcp_v1"
        }
    }
}

protocol DipHandPixelDetecting: Sendable {
    var observationFormat: DipHandObservationFormat { get }
    // Points are local pixels in the actual supplied image. The estimator owns
    // crop translation and source association; the detector supplies no body.
    func detect(image: CGImage, maximumHands: Int) throws -> [WorkoutGripHandObservation]
}

struct VisionDipHandPixelDetector: DipHandPixelDetecting {
    // Existing acquisition keeps its original three-point input. Independent
    // correspondence owners can request the SDK's actual wrist too; this does
    // not move the MCPs or supply a missing primary body wrist.
    let observationFormat: DipHandObservationFormat
    init(format: DipHandObservationFormat = .gripMCPs) { observationFormat = format }

    func detect(image: CGImage, maximumHands: Int) throws -> [WorkoutGripHandObservation] {
        let request = VNDetectHumanHandPoseRequest()
        request.revision = VNDetectHumanHandPoseRequestRevision1
        request.maximumHandCount = maximumHands
        try VNImageRequestHandler(cgImage: image, orientation: .up, options: [:]).perform([request])
        var names: [(VNHumanHandPoseObservation.JointName, String)] = [
            (.indexMCP, "indexMCP"), (.middleMCP, "middleMCP"), (.littleMCP, "pinkyMCP")
        ]
        if case .wristAndGripMCPs = observationFormat { names.insert((.wrist, "wrist"), at: 0) }
        return try (request.results ?? []).map { hand in
            let points = try names.map { joint, name in
                let point = try hand.recognizedPoint(joint)
                return WorkoutGripHandPoint(joint: name,
                    position: Point2D(x: point.location.x * Double(image.width),
                        y: (1 - point.location.y) * Double(image.height)), world: nil, score: Double(point.confidence))
            }
            return WorkoutGripHandObservation(points: points, sdkHandedness: nil, sdkHandednessScore: nil)
        }
    }
}

struct VisionDipHandEstimator: DipHandEstimating {
    private let usesCrops: Bool
    private let detector: any DipHandPixelDetecting
    init(usesCrops: Bool = true, detector: any DipHandPixelDetecting = VisionDipHandPixelDetector()) {
        self.usesCrops = usesCrops; self.detector = detector
    }

    func observe(image: CGImage, pose: PoseResult) throws -> WorkoutGripObservation {
        let joints: Set<PoseJoint> = [.leftShoulder, .rightShoulder, .leftWrist, .rightWrist]
        let tracked = (pose.people.count == 1 ? pose.people[0].landmarks : [])
            .filter { joints.contains($0.joint) }
            .map { WorkoutGripTrackedPoint(joint: $0.joint.rawValue, position: $0.position, score: $0.confidence) }
        func observation(_ hands: [WorkoutGripHandObservation]) -> WorkoutGripObservation {
            WorkoutGripObservation(seconds: pose.timestamp.seconds, imageSize: pose.imageSize,
                trackedBody: tracked, body: [], hands: hands,
                modelId: (usesCrops ? "VNDetectHumanHandPoseRequest-revision1-crops_v3" : "VNDetectHumanHandPoseRequest-revision1") + detector.observationFormat.modelSuffix,
                backend: "Apple Vision hand pose")
        }
        let full = try detect(image: image, maximumHands: 2)
        guard usesCrops else { return observation(full) }
        let encoder = JSONEncoder()
        let validation = CurvedDipRailAcquisitionBridge()
        func accepts(_ hands: [WorkoutGripHandObservation]) throws -> Bool {
            var current = pose; current.gripObservation = observation(hands)
            if case .wristAndGripMCPs = detector.observationFormat {
                // Crop recovery still seeks both hands. Its correspondence
                // must use the requested measured joint roles, rather than
                // rejecting good full-frame wrists for a large knuckle offset.
                guard hands.count == 2 else { return false }
                struct Frame: Encodable {
                    let pose: PoseResult
                    let handSourceTime: PresentationTime
                    let correspondence = "observedWrist"
                }
                let input = Frame(pose: current, handSourceTime: pose.timestamp)
                return try ObservedGripAssociationBridge().associateJson(
                    input: String(decoding: encoder.encode(input), as: UTF8.self)) != nil
            }
            return try validation.searchJson(input: String(decoding: encoder.encode(current), as: UTF8.self)) != nil
        }
        if try accepts(full) { return observation(full) }
        // Reuse the common, bounded wrist crop policy on this exact original
        // primary pose. A crop is framing only: all three MCPs and their scores
        // still come from a fresh native request. No second body model is used.
        var current = pose; current.gripObservation = nil
        let requestsJSON = try WorkoutGripInferenceBridge().handRequestsJson(
            poseJson: String(decoding: encoder.encode(current), as: UTF8.self))
        let requests = try JSONDecoder().decode([WorkoutGripCropRequest].self, from: Data(requestsJSON.utf8))
        var candidates = full
        for request in requests.prefix(2) {
            for region in request.regions.prefix(2) {
                guard let crop = image.cropping(to: CGRect(x: region.left, y: region.top,
                    width: region.width, height: region.height)) else { continue }
                candidates += try detect(image: crop, maximumHands: 1,
                    offset: Point2D(x: Double(region.left), y: Double(region.top)), region: region)
                // Fixed inference order, independent of motion, rails or count.
                // Common geometry owns freshness, scores and unique two-wrist
                // association, including rejecting two crops of the same hand.
                for i in candidates.indices {
                    for j in candidates.indices where j > i {
                        let pair = [candidates[i], candidates[j]]
                        if try accepts(pair) { return observation(pair) }
                    }
                }
            }
        }
        return observation(full)
    }

    private func detect(image: CGImage, maximumHands: Int, offset: Point2D = Point2D(x: 0, y: 0),
                        region: WorkoutGripRegion? = nil) throws -> [WorkoutGripHandObservation] {
        try detector.detect(image: image, maximumHands: maximumHands).map { hand in
            WorkoutGripHandObservation(points: hand.points.map { point in
                WorkoutGripHandPoint(joint: point.joint,
                    position: Point2D(x: offset.x + point.position.x, y: offset.y + point.position.y),
                    world: point.world, score: point.score)
            }, sdkHandedness: hand.sdkHandedness, sdkHandednessScore: hand.sdkHandednessScore, sourceRegion: region)
        }
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
    private let pixels: any DipRailPixelExtracting
    private let acquisition = CurvedDipRailAcquisitionBridge()
    private let fitter = ObservedRailFitterBridge()
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init(hands: any DipHandEstimating = VisionDipHandEstimator(), pixels: any DipRailPixelExtracting = NativeDipRailPixels()) {
        self.hands = hands; self.pixels = pixels
    }
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
                    let contours = try pixels.contours(image: image, region: rect).map { Contour(points: $0) }
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
        // Thrown SDK/geometry/complexity faults revoke dwell. Only a successful
        // current extraction with no proposal may enter acquisition's pause.
        if !failures.isEmpty { acquisition.invalidateEvidence() }
        let result = try acquisition.observeJson(input: encode(AcquisitionInput(pose: pose,
            candidates: candidates, generation: expected)))
        let observation = DipFrameObservation(pose: pose, generation: expected,
            acquisition: try decoder.decode(DipRailAcquisitionResult.self, from: Data(result.utf8)),
            extractionFailures: failures, candidates: candidates)
        completed = true
        return observation
    }
    private func encode<T: Encodable>(_ value: T) throws -> String {
        String(decoding: try encoder.encode(value), as: UTF8.self)
    }
}
