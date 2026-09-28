import Foundation

// Robustly interprets multiple image-registration patches around the frame.
// Registration itself is platform-specific; this policy remains deterministic
// and framework-free so thresholds/consensus behavior are directly testable.
struct StaticSceneStability: Sendable {
    struct PatchMotion: Equatable, Sendable {
        let dxPixels: Double?
        let dyPixels: Double?
        let scaleFraction: Double?

        init(
            dxPixels: Double? = nil,
            dyPixels: Double? = nil,
            scaleFraction: Double? = nil
        ) {
            self.dxPixels = dxPixels
            self.dyPixels = dyPixels
            self.scaleFraction = scaleFraction
        }

        var hasTranslation: Bool {
            guard let dxPixels, let dyPixels else { return false }
            return dxPixels.isFinite && dyPixels.isFinite
        }

        var hasScale: Bool {
            guard let scaleFraction else { return false }
            return scaleFraction.isFinite && scaleFraction >= 0
        }
    }

    enum State: String, Equatable, Sendable {
        case uncalibrated
        case calibrating
        case stable
        case moved

        var allowsLiveSet: Bool { self == .stable }
    }

    enum MovementKind: String, Equatable, Sendable {
        case translation
        case scale
    }

    static let movementThresholdFraction = 0.008
    static let consensusToleranceFraction = 0.006
    static let scaleThresholdFraction = 0.012
    static let scaleConsensusToleranceFraction = 0.008
    static let movementDwellSeconds = 0.25
    static let minimumConsensusPatches = 2
    static let minimumScaleConsensusPatches = 3

    private(set) var state: State = .uncalibrated
    private(set) var movementKind: MovementKind?
    private(set) var latestShiftFraction: Double?
    private(set) var latestScaleFraction: Double?
    private(set) var latestConsensusPatches = 0
    private(set) var latestScaleConsensusPatches = 0
    private(set) var maximumShiftFraction = 0.0
    private(set) var maximumScaleFraction = 0.0

    private var imageShortSide: Double?
    private var translationOverThresholdSince: Double?
    private var scaleOverThresholdSince: Double?
    private var lastTimestamp: Double?

    mutating func calibrate(imageSize: ImageSize) -> Bool {
        guard imageSize.isValid else {
            reset()
            return false
        }
        imageShortSide = min(imageSize.width, imageSize.height)
        translationOverThresholdSince = nil
        scaleOverThresholdSince = nil
        lastTimestamp = nil
        latestShiftFraction = 0
        latestScaleFraction = 0
        latestConsensusPatches = 0
        latestScaleConsensusPatches = 0
        maximumShiftFraction = 0
        maximumScaleFraction = 0
        movementKind = nil
        state = .calibrating
        return true
    }

    // Retained for focused square-geometry tests.
    mutating func calibrate(imageShortSide: Double) -> Bool {
        calibrate(imageSize: ImageSize(width: imageShortSide, height: imageShortSide))
    }

    mutating func reset() {
        self = Self()
    }

    @discardableResult
    mutating func observe(_ motions: [PatchMotion], timestamp: Double) -> State {
        guard state == .calibrating || state == .stable,
              let imageShortSide,
              timestamp.isFinite,
              lastTimestamp == nil || timestamp > lastTimestamp!
        else { return state }

        lastTimestamp = timestamp

        let translation = translationConsensus(
            motions.filter(\.hasTranslation),
            imageShortSide: imageShortSide
        )
        latestConsensusPatches = translation.count
        if let fraction = translation.fraction {
            latestShiftFraction = fraction
            maximumShiftFraction = max(maximumShiftFraction, fraction)
        }

        let scale = scaleConsensus(motions.filter(\.hasScale))
        latestScaleConsensusPatches = scale.count
        if let fraction = scale.fraction {
            latestScaleFraction = fraction
            maximumScaleFraction = max(maximumScaleFraction, fraction)
        }

        let translationStable = translation.count >= Self.minimumConsensusPatches
            && (translation.fraction ?? .infinity) < Self.movementThresholdFraction
        let scaleStable = scale.count >= Self.minimumScaleConsensusPatches
            && (scale.fraction ?? .infinity) < Self.scaleThresholdFraction

        if state == .calibrating, translationStable, scaleStable {
            state = .stable
        }

        updateTranslationThreshold(
            translation.fraction,
            consensusCount: translation.count,
            timestamp: timestamp
        )
        if state != .moved {
            updateScaleThreshold(
                scale.fraction,
                consensusCount: scale.count,
                timestamp: timestamp
            )
        }

        return state
    }

    private mutating func updateTranslationThreshold(
        _ fraction: Double?,
        consensusCount: Int,
        timestamp: Double
    ) {
        guard consensusCount >= Self.minimumConsensusPatches,
              let fraction
        else {
            translationOverThresholdSince = nil
            return
        }

        if fraction >= Self.movementThresholdFraction {
            if let since = translationOverThresholdSince {
                if timestamp - since >= Self.movementDwellSeconds {
                    movementKind = .translation
                    state = .moved
                }
            } else {
                translationOverThresholdSince = timestamp
            }
        } else {
            translationOverThresholdSince = nil
        }
    }

    private mutating func updateScaleThreshold(
        _ fraction: Double?,
        consensusCount: Int,
        timestamp: Double
    ) {
        guard consensusCount >= Self.minimumScaleConsensusPatches,
              let fraction
        else {
            scaleOverThresholdSince = nil
            return
        }

        if fraction >= Self.scaleThresholdFraction {
            if let since = scaleOverThresholdSince {
                if timestamp - since >= Self.movementDwellSeconds {
                    movementKind = .scale
                    state = .moved
                }
            } else {
                scaleOverThresholdSince = timestamp
            }
        } else {
            scaleOverThresholdSince = nil
        }
    }

    private func translationConsensus(
        _ motions: [PatchMotion],
        imageShortSide: Double
    ) -> (fraction: Double?, count: Int) {
        let dx = motions.compactMap(\.dxPixels)
        let dy = motions.compactMap(\.dyPixels)
        guard dx.count == motions.count, dy.count == motions.count,
              motions.count >= Self.minimumConsensusPatches
        else {
            return (nil, motions.count)
        }

        let medianX = median(dx)
        let medianY = median(dy)
        let tolerance = Self.consensusToleranceFraction * imageShortSide
        let inliers = motions.filter { motion in
            guard let x = motion.dxPixels, let y = motion.dyPixels else { return false }
            return hypot(x - medianX, y - medianY) <= tolerance
        }
        guard inliers.count >= Self.minimumConsensusPatches else {
            return (nil, inliers.count)
        }

        let consensusX = median(inliers.compactMap(\.dxPixels))
        let consensusY = median(inliers.compactMap(\.dyPixels))
        let shift = hypot(consensusX, consensusY) / imageShortSide
        guard shift.isFinite else { return (nil, inliers.count) }
        return (shift, inliers.count)
    }

    private func scaleConsensus(
        _ motions: [PatchMotion]
    ) -> (fraction: Double?, count: Int) {
        let values = motions.compactMap(\.scaleFraction)
        guard values.count >= Self.minimumScaleConsensusPatches else {
            return (nil, values.count)
        }

        let center = median(values)
        let inliers = values.filter {
            abs($0 - center) <= Self.scaleConsensusToleranceFraction
        }
        guard inliers.count >= Self.minimumScaleConsensusPatches else {
            return (nil, inliers.count)
        }

        let fraction = median(inliers)
        return (fraction.isFinite ? fraction : nil, inliers.count)
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
