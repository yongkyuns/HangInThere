import Foundation

// A countdown earns time only from consecutive, eligible analyzed frames.
// Camera/bar/grip checks belong to the caller; a timer alone cannot start a set.
struct HandsFreeSetStart: Equatable, Sendable {
    enum State: Equatable, Sendable {
        case waiting
        case countdown(Int)
        case started
    }
    static let countdownSeconds = 3.0
    static let maximumFrameGap = ExerciseCounter.maximumGapSeconds
    private(set) var state: State = .waiting
    private var beganAt: Double?
    private var lastFrameAt: Double?
    private var dipAnchor: DipSupportMeasurement.Anchor?

    mutating func reset() { self = Self() }

    static func isStartingPosition(pose: PoseResult, referenceEdge: BarSegment) -> Bool {
        let measurement = PullUpArmMeasurement(pose: pose)
        return measurement.isExtended && measurement.hasGrip(on: referenceEdge, imageSize: pose.imageSize)
    }

    // The same source-clock countdown is used for dips, with a frozen support
    // pose so moving hands or lowering during acquisition cannot earn time.
    mutating func observeDip(pose: PoseResult, side: ArmMeasurement.Side,
                            referenceEdge: BarSegment?, eligible: Bool, paused: Bool = false) -> Bool {
        guard !paused else { reset(); return false }
        guard state != .started else { return false }
        guard eligible, let referenceEdge,
              let sample = DipSupportMeasurement(pose: pose, side: side, referenceEdge: referenceEdge),
              sample.isStartingSupport else { reset(); return false }
        if let lastFrameAt, pose.timestamp.seconds - lastFrameAt > Self.maximumFrameGap { reset() }
        if let anchor = dipAnchor, !anchor.isStableStart(sample) { reset(); return false }
        if dipAnchor == nil { dipAnchor = DipSupportMeasurement.Anchor(sample) }
        guard dipAnchor != nil else { reset(); return false }
        return observe(timestamp: pose.timestamp.seconds, eligible: true, paused: paused)
    }

    // Returns true once, on the first fresh eligible frame after the countdown.
    mutating func observe(timestamp: Double, eligible: Bool, paused: Bool = false) -> Bool {
        guard !paused else { reset(); return false }
        guard state != .started else { return false }
        guard eligible, timestamp.isFinite else { reset(); return false }
        if let lastFrameAt {
            guard timestamp > lastFrameAt else { reset(); return false }
            if timestamp - lastFrameAt > Self.maximumFrameGap { reset() }
        }
        lastFrameAt = timestamp
        if beganAt == nil { beganAt = timestamp }
        let remaining = Self.countdownSeconds - (timestamp - (beganAt ?? timestamp))
        guard remaining > 0 else { state = .started; return true }
        state = .countdown(Int(ceil(remaining)))
        return false
    }
}
