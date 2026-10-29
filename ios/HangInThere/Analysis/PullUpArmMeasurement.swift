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
    // Match the shared movement owner's actual front/rear spatial resolution.
    // Narrow hands are valid; collapsed or crossed SDK chains are unavailable.
    static func hasDistinctFrontRearArms(in pose: PoseResult) -> Bool {
        guard pose.people.count == 1, let person = pose.people.first,
              person.landmarks.count <= 64,
              Set(person.landmarks.map(\.joint)).count == person.landmarks.count else { return false }
        let arms = PullUpArmMeasurement(pose: pose).arms.sorted { $0.wrist.x < $1.wrist.x }
        guard arms.count == 2,
              let leftElbow = person.landmark(.leftElbow)?.position,
              let rightElbow = person.landmark(.rightElbow)?.position else { return false }
        let floor = ArmMeasurement.minimumSegmentFraction * min(pose.imageSize.width, pose.imageSize.height)
        return arms[1].wrist.x-arms[0].wrist.x >= 2*floor
            && arms[1].shoulder.x-arms[0].shoulder.x >= floor
            && hypot(leftElbow.x-rightElbow.x, leftElbow.y-rightElbow.y) >= floor
    }
}
