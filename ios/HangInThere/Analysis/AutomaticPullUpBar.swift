import Foundation

// Pose supplies a search area and grip association, never the reference line.
// Only finite edges observed by the contour detector can become a fixed bar.
// Controlled-camera acquisition uses observed geometry and is not a trained model.
struct AutomaticPullUpBar: Sendable {
    enum State: Sendable {
        case lookingForGrip, findingEdge, ambiguous, holding, locked
    }
    struct Grip: Sendable {
        let left: Point2D
        let right: Point2D
        let region: BarRegion
    }
    static let samplingInterval = 0.3
    // v2 compares each wrist with the local observed line, not its midpoint.
    static let selectionPolicyVersion = 2
    static let gripAssessmentVersion = 2
    private(set) var state: State = .lookingForGrip
    private var stability = AutomaticReferenceStability()
    var referenceStabilityDiagnostic: AutomaticReferenceStability.Diagnostic? {
        state == .holding || state == .locked ? stability.diagnostic : nil
    }

    mutating func reset() { self = Self() }

    static func grip(in pose: PoseResult) -> Grip? {
        guard pose.imageSize.isValid, pose.timestamp.seconds.isFinite,
              pose.people.count == 1,
              ArmMeasurement(pose: pose, side: .left).estimate != nil,
              ArmMeasurement(pose: pose, side: .right).estimate != nil else { return nil }
        let person = pose.people[0]
        let joints: [PoseJoint] = [.leftWrist, .rightWrist, .leftShoulder, .rightShoulder]
        let points = joints.compactMap { person.landmark($0, minimumConfidence: 0.5)?.position }
        guard points.count == 4 else { return nil }
        let l = points[0], r = points[1], short = min(pose.imageSize.width, pose.imageSize.height)
        guard points[2].y - l.y > short * 0.08,
              points[3].y - r.y > short * 0.08,
              abs(l.x - r.x) >= 2 * ArmMeasurement.minimumSegmentFraction * short,
              PullUpArmMeasurement.hasDistinctFrontRearArms(in: pose),
              abs(l.y - r.y) <= short * 0.10 else { return nil }
        let region = BarRegion(
            Point2D(x: max(0, min(l.x, r.x) - short * 0.12),
                    y: max(0, min(l.y, r.y) - max(12, short * 0.12))),
            Point2D(x: min(pose.imageSize.width, max(l.x, r.x) + short * 0.12),
                    y: min(pose.imageSize.height, max(l.y, r.y) + max(6, short * 0.04)))
        )
        guard region.isValid(in: pose.imageSize) else { return nil }
        return Grip(left: l, right: r, region: region)
    }

    // Thin parallel edges can be the two sides of one bar. Choose the upper
    // observed edge; separate plausible bars remain ambiguous, regardless of score.
    static func select(candidates: [BarLineCandidate], grip: Grip, size: ImageSize) -> BarSegment? {
        let short = min(size.width, size.height)
        let span = abs(grip.left.x - grip.right.x)
        var eligible: [BarSegment] = []
        let maximumSlope = tan(15.0 * Double.pi / 180.0)
        for candidate in candidates {
            let ordered = candidate.edge.ordered
            let value = ConfirmedBar(role: .pullUpGrip, method: .automaticContours,
                                     referenceEdge: ordered, oppositeEdge: nil,
                                     imageSize: size, sourceTime: .init(value: 0, timescale: 1))
            guard value.isValid, ordered.length >= span * 0.65,
                  abs(ordered.b.y - ordered.a.y) <= (ordered.b.x - ordered.a.x) * maximumSlope else { continue }
            let nearHands = [grip.left, grip.right].allSatisfy { wrist in
                let signed = ((ordered.b.x-ordered.a.x)*(wrist.y-ordered.a.y)
                    - (ordered.b.y-ordered.a.y)*(wrist.x-ordered.a.x)) / ordered.length
                return ordered.distance(to: wrist) <= short * 0.085
                    && signed >= -short * 0.015
            }
            if nearHands { eligible.append(ordered) }
        }
        eligible.sort { $0.midpoint.y < $1.midpoint.y }
        guard let upper = eligible.first,
              eligible.allSatisfy({ sameLine(upper, $0, tolerance: short * 0.03) }) else { return nil }
        return upper.ordered
    }

    mutating func observe(pose: PoseResult, candidates: [BarLineCandidate]) -> ConfirmedBar? {
        guard state != .locked else { return nil }
        guard let grip = Self.grip(in: pose) else {
            reset(); return nil
        }
        guard let edge = Self.select(candidates: candidates, grip: grip, size: pose.imageSize) else {
            reset(); state = candidates.isEmpty ? .findingEdge : .ambiguous; return nil
        }
        state = .holding
        guard stability.observe(edges: [edge], grips: [grip.left, grip.right], pose: pose) else { return nil }
        state = .locked
        return ConfirmedBar(role: .pullUpGrip, method: .automaticContours,
                            referenceEdge: edge, oppositeEdge: nil,
                            imageSize: pose.imageSize, sourceTime: pose.timestamp)
    }

    private static func sameLine(_ p: BarSegment, _ q: BarSegment, tolerance: Double) -> Bool {
        let p = p.ordered, q = q.ordered
        guard p.isValid, q.isValid else { return false }
        let dot = ((p.b.x-p.a.x)*(q.b.x-q.a.x) + (p.b.y-p.a.y)*(q.b.y-q.a.y)) / (p.length*q.length)
        let overlap = min(p.b.x, q.b.x) - max(p.a.x, q.a.x)
        return dot >= cos(3 * .pi / 180)
            && overlap >= min(p.b.x-p.a.x, q.b.x-q.a.x) * 0.65
            && p.perpendicularDistance(to: q.a) <= tolerance
            && p.perpendicularDistance(to: q.b) <= tolerance
            && q.perpendicularDistance(to: p.a) <= tolerance
            && q.perpendicularDistance(to: p.b) <= tolerance
    }
}
