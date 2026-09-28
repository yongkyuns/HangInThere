import Foundation

// Robustly interprets multiple image-registration patches around the frame.
// Registration itself is platform-specific; this policy remains deterministic
// and framework-free so thresholds/consensus behavior are directly testable.
struct StaticSceneStability: Sendable {
    struct PatchShift: Equatable, Sendable {
        let dxPixels: Double
        let dyPixels: Double
        let centerXFraction: Double
        let centerYFraction: Double

        init(
            dxPixels: Double,
            dyPixels: Double,
            centerXFraction: Double = 0.5,
            centerYFraction: Double = 0.5
        ) {
            self.dxPixels = dxPixels
            self.dyPixels = dyPixels
            self.centerXFraction = centerXFraction
            self.centerYFraction = centerYFraction
        }

        var isFinite: Bool {
            dxPixels.isFinite
                && dyPixels.isFinite
                && centerXFraction.isFinite
                && centerYFraction.isFinite
                && (0...1).contains(centerXFraction)
                && (0...1).contains(centerYFraction)
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
    static let scaleConsensusToleranceFraction = 0.006
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

    private var imageWidth: Double?
    private var imageHeight: Double?
    private var imageShortSide: Double?
    private var translationOverThresholdSince: Double?
    private var scaleOverThresholdSince: Double?
    private var lastTimestamp: Double?

    mutating func calibrate(imageSize: ImageSize) -> Bool {
        guard imageSize.isValid else {
            reset()
            return false
        }
        imageWidth = imageSize.width
        imageHeight = imageSize.height
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

    // Retained for focused unit tests with square synthetic geometry.
    mutating func calibrate(imageShortSide: Double) -> Bool {
        calibrate(imageSize: ImageSize(width: imageShortSide, height: imageShortSide))
    }

    mutating func reset() {
        self = Self()
    }

    @discardableResult
    mutating func observe(_ shifts: [PatchShift], timestamp: Double) -> State {
        guard state == .calibrating || state == .stable,
              let imageWidth,
              let imageHeight,
              let imageShortSide,
              timestamp.isFinite,
              lastTimestamp == nil || timestamp > lastTimestamp!
        else { return state }

        lastTimestamp = timestamp
        let finite = shifts.filter(\.isFinite)
        guard finite.count >= Self.minimumConsensusPatches else {
            latestConsensusPatches = 0
            latestScaleConsensusPatches = 0
            return state
        }

        let medianX = median(finite.map(\.dxPixels))
        let medianY = median(finite.map(\.dyPixels))

        let translation = translationConsensus(
            finite,
            medianX: medianX,
            medianY: medianY,
            imageShortSide: imageShortSide
        )
        latestConsensusPatches = translation.count
        if let shift = translation.fraction {
            latestShiftFraction = shift
            maximumShiftFraction = max(maximumShiftFraction, shift)
        }

        let scale = scaleConsensus(
            finite,
            medianX: medianX,
            medianY: medianY,
            imageWidth: imageWidth,
            imageHeight: imageHeight
        )
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
        _ shifts: [PatchShift],
        medianX: Double,
        medianY: Double,
        imageShortSide: Double
    ) -> (fraction: Double?, count: Int) {
        let tolerance = Self.consensusToleranceFraction * imageShortSide
        let inliers = shifts.filter {
            hypot($0.dxPixels - medianX, $0.dyPixels - medianY) <= tolerance
        }
        guard inliers.count >= Self.minimumConsensusPatches else {
            return (nil, inliers.count)
        }

        let consensusX = median(inliers.map(\.dxPixels))
        let consensusY = median(inliers.map(\.dyPixels))
        let shift = hypot(consensusX, consensusY) / imageShortSide
        guard shift.isFinite else { return (nil, inliers.count) }
        return (shift, inliers.count)
    }

    private func scaleConsensus(
        _ shifts: [PatchShift],
        medianX: Double,
        medianY: Double,
        imageWidth: Double,
        imageHeight: Double
    ) -> (fraction: Double?, count: Int) {
        let candidates = shifts.compactMap { shift -> Double? in
            let rx = (shift.centerXFraction - 0.5) * imageWidth
            let ry = (shift.centerYFraction - 0.5) * imageHeight
            let radiusSquared = rx * rx + ry * ry
            guard radiusSquared.isFinite, radiusSquared > 1 else { return nil }

            let residualX = shift.dxPixels - medianX
            let residualY = shift.dyPixels - medianY
            let fraction = (residualX * rx + residualY * ry) / radiusSquared
            return fraction.isFinite ? fraction : nil
        }

        guard candidates.count >= Self.minimumScaleConsensusPatches else {
            return (nil, candidates.count)
        }

        let center = median(candidates)
        let inliers = candidates.filter {
            abs($0 - center) <= Self.scaleConsensusToleranceFraction
        }
        guard inliers.count >= Self.minimumScaleConsensusPatches else {
            return (nil, inliers.count)
        }

        let fraction = abs(median(inliers))
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
