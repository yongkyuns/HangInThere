import Foundation

// Current-frame support evidence in upright image pixels. A confirmed rail is
// still required; wrist observations locate the grip, not an invented bar line.
struct DipSupportMeasurement: Sendable {
    static let startingDegrees = 140.0
    let shoulder: Point2D
    let wrist: Point2D
    let degrees: Double?
    let armLength: Double?
    let otherDegrees: Double?

    init?(pose: PoseResult, side: ArmMeasurement.Side, referenceEdge: BarSegment) {
        guard pose.imageSize.isValid, referenceEdge.isValid, pose.people.count == 1,
              [referenceEdge.a, referenceEdge.b].allSatisfy({
                  (0...pose.imageSize.width).contains($0.x) && (0...pose.imageSize.height).contains($0.y)
              }) else { return nil }
        let person = pose.people[0]
        func point(_ joint: PoseJoint) -> Point2D? {
            let matches = person.landmarks.filter { $0.joint == joint }
            guard matches.count == 1, let landmark = matches.first, landmark.isVisible(),
                  (0...pose.imageSize.width).contains(landmark.position.x),
                  (0...pose.imageSize.height).contains(landmark.position.y) else { return nil }
            return landmark.position
        }
        guard let shoulder = point(side.joints[0]), let wrist = point(side.joints[2]),
              referenceEdge.distance(to: wrist) <= 0.12 * min(pose.imageSize.width, pose.imageSize.height)
        else { return nil }
        let arm = ArmMeasurement(pose: pose, side: side).estimate
        self.shoulder = shoulder
        self.wrist = wrist
        degrees = arm?.elbowDegrees
        armLength = arm.map { $0.upperArmPixels + $0.forearmPixels }
        let other: ArmMeasurement.Side = side == .left ? .right : .left
        otherDegrees = ArmMeasurement(pose: pose, side: other).estimate?.elbowDegrees
    }

    var isStartingSupport: Bool {
        // Analytic/SDK angles may round an exact boundary by a few ULPs.
        let roundingTolerance = 1e-6
        guard let degrees, degrees + roundingTolerance >= Self.startingDegrees, let armLength,
              wrist.y - shoulder.y >= 0.35 * armLength else { return false }
        return otherDegrees.map { $0 + roundingTolerance >= Self.startingDegrees } ?? true
    }

    struct Anchor: Equatable, Sendable {
        let wrist: Point2D
        let shoulder: Point2D
        let axis: Point2D
        let armLength: Double

        init?(_ sample: DipSupportMeasurement) {
            guard let armLength = sample.armLength, armLength > 0 else { return nil }
            let dx = sample.shoulder.x - sample.wrist.x, dy = sample.shoulder.y - sample.wrist.y
            let span = hypot(dx, dy)
            guard span >= 0.35 * armLength else { return nil }
            wrist = sample.wrist
            shoulder = sample.shoulder
            axis = Point2D(x: dx / span, y: dy / span)
            self.armLength = armLength
        }

        func height(of shoulder: Point2D) -> Double {
            (shoulder.x - wrist.x) * axis.x + (shoulder.y - wrist.y) * axis.y
        }
        func retainsGrip(_ sample: DipSupportMeasurement) -> Bool {
            hypot(sample.wrist.x - wrist.x, sample.wrist.y - wrist.y) <= 0.12 * armLength
        }
        func isStableStart(_ sample: DipSupportMeasurement) -> Bool {
            sample.isStartingSupport && retainsGrip(sample)
                && hypot(sample.shoulder.x - shoulder.x, sample.shoulder.y - shoulder.y) <= 0.08 * armLength
        }
    }
}
