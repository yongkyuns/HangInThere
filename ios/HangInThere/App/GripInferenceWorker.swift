#if os(iOS)
import CoreGraphics
import CryptoKit
import Foundation
import MediaPipeTasksVision
import UIKit
import WorkoutCore
import os

struct GripHandInferenceResult: Sendable {
    let hands: [WorkoutGripHandObservation]
    let modelCalls: Int
}

/// SDK objects belong exclusively to this actor. No SDK handle crosses isolation.
actor GripInferenceWorker {
    private nonisolated let executor = NativeFrameExecutor(label: "dev.hanginthere.grip", qos: .utility)
    nonisolated var unownedExecutor: UnownedSerialExecutor { executor.asUnownedSerialExecutor() }
    private var hand: HandLandmarker?
    private var body: PoseLandmarker?
    private var unavailable = false
    private let regions = WorkoutGripInferenceBridge()
    static let modelId = "mediapipe_hand_full_v1_pose_lite_v1"
    private func load() throws {
        guard hand == nil, !unavailable else { return }
        func model(_ name: String, hash: String) throws -> String {
            guard let url = Bundle.main.url(forResource: name, withExtension: "task") else { throw CocoaError(.fileNoSuchFile) }
            let data = try Data(contentsOf: url, options: .mappedIfSafe)
            guard SHA256.hash(data: data).map({ String(format: "%02x", $0) }).joined() == hash else { throw CocoaError(.fileReadCorruptFile) }
            return url.path
        }
        let handOptions = HandLandmarkerOptions()
        handOptions.baseOptions.modelAssetPath = try model("hand_landmarker", hash: "fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1")
        handOptions.runningMode = .image; handOptions.numHands = 1
        handOptions.minHandDetectionConfidence = 0.5; handOptions.minHandPresenceConfidence = 0.5
        let bodyOptions = PoseLandmarkerOptions()
        bodyOptions.baseOptions.modelAssetPath = try model("pose_landmarker_lite", hash: "59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a")
        bodyOptions.runningMode = .image; bodyOptions.numPoses = 2
        bodyOptions.minPoseDetectionConfidence = 0.5; bodyOptions.minPosePresenceConfidence = 0.5
        let loadedHand = try HandLandmarker(options: handOptions)
        let loadedBody = try PoseLandmarker(options: bodyOptions)
        hand = loadedHand; body = loadedBody
    }
    func estimate(image: CGImage, pose: PoseResult) -> WorkoutGripObservation {
        let tracked = pose.people.count == 1 ? pose.people[0].landmarks.compactMap { point -> WorkoutGripTrackedPoint? in
            guard Self.bodyJoints.contains(where: { $0.0 == point.joint.rawValue }) else { return nil }
            return WorkoutGripTrackedPoint(joint: point.joint.rawValue, position: point.position, score: point.confidence)
        } : []
        var result = WorkoutGripObservation(seconds: pose.timestamp.seconds, imageSize: pose.imageSize, trackedBody: tracked,
            body: [], hands: [], modelId: Self.modelId, backend: "MediaPipe iOS 0.10.21 CPU IMAGE crops_v\(regions.cropPolicyVersion) body_preflight_v\(regions.bodyPreflightVersion)")
        guard (4...6).contains(tracked.count), !Task.isCancelled else { result.failure = "bodyTrackingUnavailable"; return result }
        do {
            try load()
            try Task.checkCancellation()
            guard let body else { result.failure = "gripModelUnavailable"; return result }
            let bodyResult = try body.detect(image: MPImage(uiImage: UIImage(cgImage: image)))
            guard bodyResult.landmarks.count == 1, bodyResult.worldLandmarks.count == 1,
                  bodyResult.landmarks[0].count == 33, bodyResult.worldLandmarks[0].count == 33 else {
                result.failure = "bodyFrameUnavailable"; return result
            }
            let bodyPoints = Self.bodyJoints.map { (name, index) in
                let p = bodyResult.landmarks[0][index]; let w = bodyResult.worldLandmarks[0][index]
                return WorkoutGripBodyPoint(joint: name, position: Point2D(x: Double(p.x) * pose.imageSize.width, y: Double(p.y) * pose.imageSize.height),
                    world: WorkoutGripVector3(x: Double(w.x), y: Double(w.y), z: Double(w.z)),
                    score: min(p.visibility?.doubleValue ?? 0, p.presence?.doubleValue ?? 0))
            }
            let context = WorkoutGripObservation(seconds: result.seconds,imageSize: result.imageSize,trackedBody: tracked,
                body: bodyPoints,hands: [],modelId: result.modelId,backend: result.backend)
            let hands = try estimateHands(image: image, pose: pose, context: context).hands
            return WorkoutGripObservation(seconds: context.seconds,imageSize: context.imageSize,trackedBody: tracked,
                body: bodyPoints,hands: hands,modelId: context.modelId,backend: context.backend)
        } catch {
            if hand == nil || body == nil { unavailable = true }
            result.failure = "gripInferenceUnavailable"
            return result
        }
    }
    /// Also exercised on authored blank pixels by native tests. SDK ownership
    /// stays on this actor; a fallback never produces a second hand for a wrist.
    func estimateHands(image: CGImage, pose: PoseResult, context: WorkoutGripObservation) throws -> GripHandInferenceResult {
        try load()
        guard let hand else { throw CocoaError(.fileReadUnknown) }
        var source = pose; source.gripObservation = nil
        let contextJSON = String(decoding: try JSONEncoder().encode(context),as: UTF8.self)
        let regionJSON = try regions.handStageRequestsJson(poseJson: String(decoding: JSONEncoder().encode(source), as: UTF8.self),observationJson: contextJSON)
        let requests = try JSONDecoder().decode([WorkoutGripCropRequest].self, from: Data(regionJSON.utf8))
        var hands: [WorkoutGripHandObservation] = []
        var modelCalls = 0
        for request in requests.prefix(2) {
            for region in request.regions.prefix(2) {
                try Task.checkCancellation()
                guard let crop = image.cropping(to: CGRect(x: region.left, y: region.top, width: region.width, height: region.height)) else { continue }
                modelCalls += 1
                let detected = try hand.detect(image: MPImage(uiImage: UIImage(cgImage: crop)))
                var candidateHands: [WorkoutGripHandObservation] = []
                for (i, points) in detected.landmarks.enumerated() where points.count == 21 && i < detected.worldLandmarks.count && detected.worldLandmarks[i].count == 21 {
                    let side = detected.handedness[safe: i]?.first
                    let landmarks = Self.handJoints.map { (name, index) in
                        let p = points[index]; let w = detected.worldLandmarks[i][index]
                        return WorkoutGripHandPoint(joint: name, position: Point2D(x: Double(region.left) + Double(p.x) * Double(region.width), y: Double(region.top) + Double(p.y) * Double(region.height)),
                            world: WorkoutGripVector3(x: Double(w.x), y: Double(w.y), z: Double(w.z)), score: nil)
                    }
                    candidateHands.append(WorkoutGripHandObservation(points: landmarks, sdkHandedness: side?.categoryName, sdkHandednessScore: side.map { Double($0.score) }, sourceRegion: region))
                }
                if let candidate = try candidateHands.first(where: {
                    try regions.hasHandIdentityJson(observationJson: contextJSON,handJson: String(decoding: JSONEncoder().encode($0),as: UTF8.self),expectedSide: request.side)
                }) {
                    hands.append(candidate)
                    break
                }
            }
        }
        return GripHandInferenceResult(hands: hands,modelCalls: modelCalls)
    }
    private static let bodyJoints = [("leftShoulder",11),("rightShoulder",12),("leftHip",23),("rightHip",24),("leftWrist",15),("rightWrist",16)]
    private static let handJoints = [("wrist",0),("indexMCP",5),("middleMCP",9),("pinkyMCP",17),("thumbCMC",1),("thumbTip",4)]
}
private extension Array {
    subscript(safe index: Int) -> Element? { indices.contains(index) ? self[index] : nil }
}

/// Synchronous camera delegate bridge. At most one task/image is retained; source
/// cadence and latest evidence are locked values, never a queue of camera frames.
final class GripInferenceScheduler: GripObservationScheduling {
    private struct State { var busy = false; var last: Double?; var latest: WorkoutGripObservation?; var generation = 0; var task: Task<Void, Never>?; var enabled = true }
    private let state: OSAllocatedUnfairLock<State>
    init(enabled: Bool = true) { state = OSAllocatedUnfairLock(initialState: State(enabled: enabled)) }
    private let worker = GripInferenceWorker()
    deinit { state.withLock { $0.task?.cancel() } }
    func setEnabled(_ enabled: Bool) {
        state.withLock { value in
            if value.enabled != enabled {
                value.enabled = enabled; value.generation += 1; value.latest = nil; value.last = nil; value.task?.cancel()
            }
        }
    }
    func reset() { state.withLock { $0.generation += 1; $0.latest = nil; $0.last = nil; $0.task?.cancel() } }
    func observe(image: CGImage, pose: PoseResult) -> WorkoutGripObservation? {
        let time = pose.timestamp.seconds
        let action = state.withLock { state -> (Bool, Int, WorkoutGripObservation?) in
            guard state.enabled else { return (false, state.generation, nil) }
            if state.last.map({ time < $0 }) == true { state.generation += 1; state.last = nil; state.latest = nil }
            let latest = state.latest.flatMap { $0.imageSize == pose.imageSize && time >= $0.seconds && time - $0.seconds <= 0.5 ? $0 : nil }
            let due = !state.busy && time.isFinite && pose.people.count == 1 && (state.last.map { time - $0 >= 0.25 } ?? true)
            if due { state.busy = true; state.last = time }
            return (due, state.generation, latest)
        }
        if action.0 {
            let task = Task(priority: .utility) { [worker, state] in
                let result = await worker.estimate(image: image, pose: pose)
                state.withLock { value in
                    value.busy = false; value.task = nil
                    if value.generation == action.1 { value.latest = result }
                }
            }
            state.withLock { $0.task = task }
        }
        return action.2
    }
}
#endif
