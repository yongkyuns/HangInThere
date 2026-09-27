import Foundation

// Per-frame image-plane estimates, not rep endpoints or anatomical 3D angles.
// No history is kept here: an unavailable frame cannot reuse an earlier angle.
struct ArmMeasurement: Codable, Equatable, Sendable {
    enum Side: String, CaseIterable, Codable, Sendable {
        case left, right

        var joints: [PoseJoint] {
            switch self {
            case .left: [.leftShoulder, .leftElbow, .leftWrist]
            case .right: [.rightShoulder, .rightElbow, .rightWrist]
            }
        }
    }

    enum UnavailableReason: String, Codable, Sendable {
        case noPerson, multiplePeople, invalidImageSize
        case missingJoint, duplicateJoint, invalidJoint, lowConfidence
        case shortProjectedSegment
    }

    struct Estimate: Codable, Equatable, Sendable {
        let elbowDegrees: Double
        let upperArmPixels: Double
        let forearmPixels: Double
        // Minimum SDK score in this arm chain, not a calibrated probability.
        let minimumJointConfidence: Double
    }

    // Versioned diagnostic policy. The segment floor is relative to the image's
    // short side so resizing alone cannot change availability. These are initial
    // numerical-quality gates, not calibrated exercise-validity thresholds.
    static let policyVersion = 1
    static let confidenceThreshold = 0.3
    static let minimumSegmentFraction = 0.02

    let side: Side
    let estimate: Estimate?
    let unavailableReason: UnavailableReason?

    init(pose: PoseResult, side: Side) {
        self.side = side
        let result = Self.evaluate(pose, side: side)
        estimate = result.estimate
        unavailableReason = result.reason
    }

    private static func evaluate(_ pose: PoseResult, side: Side)
        -> (estimate: Estimate?, reason: UnavailableReason?) {
        guard pose.imageSize.isValid else { return (nil, .invalidImageSize) }
        guard !pose.people.isEmpty else { return (nil, .noPerson) }
        // Do not select the person or the opposite arm that produces a nicer
        // angle. Athlete identity/near-side continuity is a separate next step.
        guard pose.people.count == 1 else { return (nil, .multiplePeople) }
        var chain: [Landmark] = []
        for joint in side.joints {
            let points = pose.people[0].landmarks.filter { $0.joint == joint }
            guard let point = points.first else { return (nil, .missingJoint) }
            guard points.count == 1 else { return (nil, .duplicateJoint) }
            guard point.position.isFinite, point.confidence.isFinite,
                  (0...1).contains(point.confidence),
                  (0...pose.imageSize.width).contains(point.position.x),
                  (0...pose.imageSize.height).contains(point.position.y)
            else { return (nil, .invalidJoint) }
            guard point.confidence >= confidenceThreshold else { return (nil, .lowConfidence) }
            chain.append(point)
        }
        let s = chain[0].position, e = chain[1].position, w = chain[2].position
        let upper = hypot(s.x - e.x, s.y - e.y)
        let forearm = hypot(w.x - e.x, w.y - e.y)
        let floor = minimumSegmentFraction * min(pose.imageSize.width, pose.imageSize.height)
        guard upper >= floor, forearm >= floor,
              let degrees = PoseGeometry.elbowAngle(shoulder: s, elbow: e, wrist: w)
        else { return (nil, .shortProjectedSegment) }
        return (Estimate(elbowDegrees: degrees, upperArmPixels: upper,
                         forearmPixels: forearm,
                         minimumJointConfidence: chain.map(\.confidence).min()!), nil)
    }
}
