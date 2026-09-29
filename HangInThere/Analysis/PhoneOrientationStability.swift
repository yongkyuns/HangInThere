import Foundation

// Detects rotational phone movement after live bar calibration.
// This does not detect pure translation; optical/background checks remain separate.
// Thresholds are provisional engineering gates pending physical-device qualification.
struct PhoneOrientationStability: Sendable {
    struct Quaternion: Equatable, Sendable {
        let x: Double
        let y: Double
        let z: Double
        let w: Double

        init?(x: Double, y: Double, z: Double, w: Double) {
            guard [x, y, z, w].allSatisfy(\.isFinite) else { return nil }
            let norm = sqrt(x * x + y * y + z * z + w * w)
            guard norm.isFinite, norm > 1e-9 else { return nil }
            self.x = x / norm
            self.y = y / norm
            self.z = z / norm
            self.w = w / norm
        }

        func angularDistanceDegrees(to other: Self) -> Double {
            let dot = abs(x * other.x + y * other.y + z * other.z + w * other.w)
            let clamped = min(1.0, max(0.0, dot))
            return 2 * acos(clamped) * 180 / .pi
        }
    }

    enum State: String, Equatable, Sendable {
        case unavailable
        case uncalibrated
        case stable
        case moved

        var allowsLiveSet: Bool { self == .stable }
    }

    static let movementThresholdDegrees = 1.5
    static let movementDwellSeconds = 0.25

    private(set) var state: State = .uncalibrated
    private(set) var latestDeltaDegrees: Double?
    private(set) var maximumDeltaDegrees = 0.0

    private var baseline: Quaternion?
    private var overThresholdSince: Double?
    private var lastTimestamp: Double?

    mutating func markUnavailable() {
        self = Self()
        state = .unavailable
    }

    mutating func reset() {
        self = Self()
    }

    @discardableResult
    mutating func calibrate(_ attitude: Quaternion?, timestamp: Double) -> Bool {
        guard let attitude, timestamp.isFinite else {
            reset()
            return false
        }
        baseline = attitude
        lastTimestamp = timestamp
        overThresholdSince = nil
        latestDeltaDegrees = 0
        maximumDeltaDegrees = 0
        state = .stable
        return true
    }

    @discardableResult
    mutating func observe(_ attitude: Quaternion?, timestamp: Double) -> State {
        guard state == .stable, let baseline, let attitude, timestamp.isFinite else {
            return state
        }
        guard lastTimestamp == nil || timestamp > lastTimestamp! else {
            return state
        }
        lastTimestamp = timestamp

        let delta = baseline.angularDistanceDegrees(to: attitude)
        guard delta.isFinite else { return state }
        latestDeltaDegrees = delta
        maximumDeltaDegrees = max(maximumDeltaDegrees, delta)

        if delta >= Self.movementThresholdDegrees {
            if let since = overThresholdSince {
                if timestamp - since >= Self.movementDwellSeconds {
                    state = .moved
                }
            } else {
                overThresholdSince = timestamp
            }
        } else {
            overThresholdSince = nil
        }
        return state
    }
}
