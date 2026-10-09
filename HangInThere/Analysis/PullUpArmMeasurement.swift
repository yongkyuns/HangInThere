import Foundation

// Independent current-frame chains; never mix athletes or borrow a missing joint.
struct PullUpArmMeasurement: Sendable {
    struct Arm: Sendable {
        let side: ArmMeasurement.Side
        let estimate: ArmMeasurement.Estimate
        let shoulder: Point2D
        let wrist: Point2D
    }
    let arms: [Arm]
    let unavailableReason: ArmMeasurement.UnavailableReason?

    init(pose: PoseResult) {
        let measurements = ArmMeasurement.Side.allCases.map { ArmMeasurement(pose: pose, side: $0) }
        arms = measurements.compactMap { measurement in
            guard let estimate = measurement.estimate,
                  let shoulder = pose.people.first?.landmark(measurement.side.joints[0])?.position,
                  let wrist = pose.people.first?.landmark(measurement.side.joints[2])?.position else { return nil }
            return Arm(side: measurement.side, estimate: estimate, shoulder: shoulder, wrist: wrist)
        }
        unavailableReason = arms.isEmpty ? measurements.first?.unavailableReason : nil
    }
    var isExtended: Bool {
        !arms.isEmpty && arms.allSatisfy { $0.estimate.elbowDegrees >= ExerciseCounter.extendedDegrees }
    }
    func hasGrip(on edge: BarSegment, imageSize: ImageSize) -> Bool {
        guard !arms.isEmpty, edge.isValid, imageSize.isValid else { return false }
        let short = min(imageSize.width, imageSize.height)
        return arms.allSatisfy {
            $0.shoulder.y - $0.wrist.y > short * 0.08
                && edge.distance(to: $0.wrist) <= short * 0.085
                && edge.midpoint.y <= $0.wrist.y + short * 0.015
        }
    }
}
