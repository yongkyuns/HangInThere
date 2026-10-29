import Foundation
import WorkoutCore

/// Authored schema/ABI check. No images, SDK inference, load or form labels.
enum EstimatedBodyGeometryBridgeCheck {
    static func run() throws -> (checks: Int, p95: Double) {
        let joints: [PoseJoint] = [.leftShoulder, .leftElbow, .leftWrist, .leftHip]
        let rootHalf = sqrt(0.5)
        let positions = [Point3D(x: 0, y: 0, z: 0), Point3D(x: 0, y: 0.4 * rootHalf, z: 0.4 * rootHalf),
                         Point3D(x: 0, y: 0.8 * rootHalf, z: 0), Point3D(x: 0, y: 1, z: 0)]
        let body = PoseObservation(landmarks: joints.map { Landmark(joint: $0, position: .init(x: 500, y: 500), confidence: 0.9) },
            estimatedWorldLandmarks: zip(joints, positions).map { EstimatedWorldLandmark(joint: $0, position: $1, confidence: 0.8) })
        var source = PoseResult(timestamp: .init(value: 123, timescale: 100), imageSize: .init(width: 1000, height: 1000),
            people: [body], backend: "authored estimated world ABI", requestRevision: 1,
            estimatedWorldConvention: EstimatedBodyGeometry.shared.convention)
        let data = try JSONEncoder().encode(source)
        let decoded = try JSONDecoder().decode(PoseResult.self, from: data)
        precondition(decoded == source)
        var old = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        old.removeValue(forKey: "estimatedWorldConvention")
        var oldBody = (old["people"] as! [[String: Any]])[0]
        oldBody.removeValue(forKey: "estimatedWorldLandmarks"); old["people"] = [oldBody]
        let legacy = try JSONDecoder().decode(PoseResult.self, from: JSONSerialization.data(withJSONObject: old))
        precondition(legacy.estimatedWorldConvention == nil && legacy.people[0].estimatedWorldLandmarks == nil)

        // The production framework owns all measurement math. Native code only
        // constructs the typed same-frame payload; it never creates missing z.
        let mapping: [PoseJoint: WorkoutCore.PoseJoint] = [.leftShoulder: .leftshoulder,
            .leftElbow: .leftelbow, .leftWrist: .leftwrist, .leftHip: .lefthip]
        func core(_ pose: PoseResult) -> WorkoutCore.PoseResult {
            WorkoutCore.PoseResult(timestamp: .init(value: pose.timestamp.value, timescale: pose.timestamp.timescale),
                imageSize: .init(width: pose.imageSize.width, height: pose.imageSize.height),
                people: pose.people.map { person in WorkoutCore.PoseObservation(landmarks: person.landmarks.map {
                    WorkoutCore.Landmark(joint: mapping[$0.joint]!, position: .init(x: $0.position.x, y: $0.position.y), confidence: $0.confidence)
                }, estimatedWorldLandmarks: person.estimatedWorldLandmarks?.map {
                    WorkoutCore.EstimatedWorldLandmark(joint: mapping[$0.joint]!,
                        position: .init(x: $0.position.x, y: $0.position.y, z: $0.position.z), confidence: $0.confidence)
                }) }, backend: pose.backend, requestRevision: Int32(pose.requestRevision), gripObservation: nil,
                estimatedWorldConvention: pose.estimatedWorldConvention)
        }
        guard let measurement = EstimatedBodyGeometry.shared.measure(pose: core(decoded), side: .left) else { fatalError("Missing authored world geometry") }
        precondition(abs(measurement.elbowDegrees - 90) < 1e-10 && abs(measurement.upperArmToTorsoDegrees - 45) < 1e-10)
        precondition(abs(measurement.armLength - 0.8) < 1e-10 && abs(measurement.torsoLength - 1) < 1e-10)
        precondition(abs(measurement.hipToWristDistance - (1 - 0.8 * rootHalf)) < 1e-10)
        precondition(abs(measurement.shoulderToWristAlongTorso - 0.8 * rootHalf) < 1e-10)
        precondition(EstimatedBodyGeometry.shared.measure(pose: core(legacy), side: .left) == nil)
        precondition(EstimatedBodyGeometry.shared.measure(pose: core(decoded), side: .right) == nil)
        source.estimatedWorldConvention = "unsupported"
        precondition(EstimatedBodyGeometry.shared.measure(pose: core(source), side: .left) == nil)
        let payload = core(decoded)
        var latencies: [Double] = []
        for _ in 0..<2000 {
            let started = ContinuousClock.now
            let result = EstimatedBodyGeometry.shared.measure(pose: payload, side: .left)
            let elapsed = started.duration(to: .now).components
            latencies.append(Double(elapsed.seconds) * 1000 + Double(elapsed.attoseconds) / 1e15)
            precondition(result?.elbowDegrees == measurement.elbowDegrees)
        }
        latencies.sort()
        return (11, latencies[Int(Double(latencies.count - 1) * 0.95)])
    }
}
