import Foundation

// Robustly interprets static-scene image registration.
// Corner translations protect lateral image alignment; one full-frame homography
// supplies an independent scale signal for toward/away or zoom-like change.
struct StaticSceneStability: Sendable {
    struct PatchTranslation: Equatable, Sendable {
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

    enum MovementKind: String, Equatable, Sendable {
        case translation
        case scale
    }

    static let movementThresholdFraction = 0.008
    static let consensusToleranceFraction = 0.006
    static let scaleThresholdFraction = 0.012
    static let movementDwellSeconds = 0.25
    static let minimumConsensusPatches = 2
    static let maximumRegistrationPatches = 4
    static let maximumObservationGapSeconds = 0.8
    static let maximumFutureSkewSeconds = 0.05

    private(set) var state: State = .uncalibrated
    private(set) var movementKind: MovementKind?
    private(set) var latestShiftFraction: Double?
    private(set) var latestScaleFraction: Double?
    private(set) var latestConsensusPatches = 0
    private(set) var latestScaleMeasurementAvailable = false
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
        latestShiftFraction = nil
        latestScaleFraction = nil
        latestConsensusPatches = 0
        latestScaleMeasurementAvailable = false
        maximumShiftFraction = 0
        maximumScaleFraction = 0
        movementKind = nil
        state = .calibrating
        return true
    }

    mutating func calibrate(imageShortSide: Double) -> Bool {
        calibrate(imageSize: ImageSize(width: imageShortSide, height: imageShortSide))
    }

    mutating func reset() {
        self = Self()
    }

    func isReady(at timestamp: Double?) -> Bool {
        guard state.allowsLiveSet, let timestamp, timestamp.isFinite,
              let lastTimestamp else { return false }
        return lastTimestamp <= timestamp + Self.maximumFutureSkewSeconds
            && timestamp - lastTimestamp <= Self.maximumObservationGapSeconds
    }

    func qualifiedReference(_ reference: BarSegment?, at timestamp: Double) -> BarSegment? {
        isReady(at: timestamp) ? reference : nil
    }

    @discardableResult
    mutating func observe(
        translations: [PatchTranslation],
        globalScaleFraction: Double?,
        timestamp: Double
    ) -> State {
        guard state == .calibrating || state == .stable,
              let imageShortSide
        else { return state }

        guard timestamp.isFinite, lastTimestamp == nil || timestamp > lastTimestamp! else {
            invalidateObservation()
            return state
        }
        if let lastTimestamp, timestamp - lastTimestamp > Self.maximumObservationGapSeconds {
            invalidateObservation()
        }

        lastTimestamp = timestamp

        let translation = translationConsensus(
            translations,
            imageShortSide: imageShortSide
        )
        latestConsensusPatches = translation.count
        latestShiftFraction = translation.fraction
        if let fraction = translation.fraction {
            maximumShiftFraction = max(maximumShiftFraction, fraction)
        }

        let scale = sanitizedScale(globalScaleFraction)
        latestScaleMeasurementAvailable = scale != nil
        latestScaleFraction = scale
        if let scale {
            maximumScaleFraction = max(maximumScaleFraction, scale)
        }

        if translation.fraction == nil || translation.count < Self.minimumConsensusPatches || scale == nil {
            state = .calibrating
        }

        let translationStable = translation.count >= Self.minimumConsensusPatches
            && (translation.fraction ?? .infinity) < Self.movementThresholdFraction
        let scaleStable = scale.map { $0 < Self.scaleThresholdFraction } ?? false

        if state == .calibrating, translationStable, scaleStable {
            state = .stable
        }

        updateTranslationThreshold(
            translation.fraction,
            consensusCount: translation.count,
            timestamp: timestamp
        )
        if state != .moved {
            updateScaleThreshold(scale, timestamp: timestamp)
        }

        return state
    }

    private mutating func invalidateObservation() {
        state = .calibrating
        latestShiftFraction = nil
        latestScaleFraction = nil
        latestConsensusPatches = 0
        latestScaleMeasurementAvailable = false
        translationOverThresholdSince = nil
        scaleOverThresholdSince = nil
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
        timestamp: Double
    ) {
        guard let fraction else {
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
        _ translations: [PatchTranslation],
        imageShortSide: Double
    ) -> (fraction: Double?, count: Int) {
        // Match the shared policy for live capture and the Swift setup oracle. Native
        // registration has four corners, so all subsets are bounded. A global
        // median can lose two agreeing corners between two unrelated outliers.
        guard translations.count <= Self.maximumRegistrationPatches else { return (nil, 0) }
        let finite = translations.filter(\.isFinite)
        guard finite.count >= Self.minimumConsensusPatches else { return (nil, finite.count) }
        let tolerance = Self.consensusToleranceFraction * imageShortSide
        var bestSize = 0
        var best: (x: Double, y: Double)?
        var ambiguous = false
        for mask in 1..<(1 << finite.count) {
            let count = mask.nonzeroBitCount
            guard count >= Self.minimumConsensusPatches, count >= bestSize else { continue }
            let subset = finite.enumerated().compactMap { index, patch in
                mask & (1 << index) != 0 ? patch : nil
            }
            let x = median(subset.map(\.dxPixels)), y = median(subset.map(\.dyPixels))
            guard subset.allSatisfy({ hypot($0.dxPixels - x, $0.dyPixels - y) <= tolerance }) else { continue }
            if count > bestSize {
                bestSize = count; best = (x, y); ambiguous = false
            } else { ambiguous = true }
        }
        guard !ambiguous, let best else { return (nil, 0) }
        let fraction = hypot(best.x, best.y) / imageShortSide
        guard fraction.isFinite else { return (nil, bestSize) }
        return (fraction, bestSize)
    }

    private func sanitizedScale(_ value: Double?) -> Double? {
        guard let value, value.isFinite, value >= 0 else { return nil }
        return value
    }

    private func median(_ values: [Double]) -> Double {
        let sorted = values.sorted()
        let middle = sorted.count / 2
        if sorted.count.isMultiple(of: 2) {
            return sorted[middle - 1] / 2 + sorted[middle] / 2
        }
        return sorted[middle]
    }
}
