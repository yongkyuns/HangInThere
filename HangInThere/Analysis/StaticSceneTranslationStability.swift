import Foundation

// Robustly interprets multiple image-registration patches around the frame.
// Registration itself is platform-specific; this policy remains deterministic
// and framework-free so thresholds/consensus behavior are directly testable.
struct StaticSceneTranslationStability: Sendable {
    struct PatchShift: Equatable, Sendable {
        let dxPixels: Double
        let dyPixels: Double

        var isFinite: Bool { dxPixels.isFinite && dyPixels.isFinite }
    }

    enum State: String, Equatable, Sendable {
        case uncalibrated
        case calibrating
        case stable
        case moved

        var allowsLiveSet: Bool { self == .stable }
    }

    static let movementThresholdFraction = 0.008
    static let consensusToleranceFraction = 0.006
    static let movementDwellSeconds = 0.25
    static let minimumConsensusPatches = 2

    private(set) var state: State = .uncalibrated
    private(set) var latestShiftFraction: Double?
    private(set) var latestConsensusPatches = 0
    private(set) var maximumShiftFraction = 0.0

    private var imageShortSide: Double?
    private var overThresholdSince: Double?
    private var lastTimestamp: Double?

    mutating func calibrate(imageShortSide: Double) -> Bool {
        guard imageShortSide.isFinite, imageShortSide > 0 else {
            reset()
            return false
        }
        self.imageShortSide = imageShortSide
        overThresholdSince = nil
        lastTimestamp = nil
        latestShiftFraction = 0
        latestConsensusPatches = 0
        maximumShiftFraction = 0
        state = .calibrating
        return true
    }

    mutating func reset() {
        self = Self()
    }

    @discardableResult
    mutating func observe(_ shifts: [PatchShift], timestamp: Double) -> State {
        guard state == .calibrating || state == .stable,
              let imageShortSide,
              timestamp.isFinite,
              lastTimestamp == nil || timestamp > lastTimestamp!
        else { return state }

        lastTimestamp = timestamp
        let finite = shifts.filter(\.isFinite)
        guard finite.count >= Self.minimumConsensusPatches else {
            latestConsensusPatches = 0
            return state
        }

        let medianX = median(finite.map(\.dxPixels))
        let medianY = median(finite.map(\.dyPixels))
        let tolerance = Self.consensusToleranceFraction * imageShortSide
        let inliers = finite.filter {
            hypot($0.dxPixels - medianX, $0.dyPixels - medianY) <= tolerance
        }

        guard inliers.count >= Self.minimumConsensusPatches else {
            latestConsensusPatches = inliers.count
            return state
        }

        let consensusX = median(inliers.map(\.dxPixels))
        let consensusY = median(inliers.map(\.dyPixels))
        let shift = hypot(consensusX, consensusY) / imageShortSide
        guard shift.isFinite else { return state }

        latestConsensusPatches = inliers.count
        latestShiftFraction = shift
        if state == .calibrating, shift < Self.movementThresholdFraction {
            state = .stable
        }
        maximumShiftFraction = max(maximumShiftFraction, shift)

        if shift >= Self.movementThresholdFraction {
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

    private func median(_ values: [Double]) -> Double {
        let sorted = values.sorted()
        let middle = sorted.count / 2
        if sorted.count.isMultiple(of: 2) {
            return (sorted[middle - 1] + sorted[middle]) / 2
        }
        return sorted[middle]
    }
}
