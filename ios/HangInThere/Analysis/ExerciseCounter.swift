import Foundation

// Provisional movement counting, NOT exercise/form acceptance. Pull-ups use both
// usable arms; dips use the confirmed selected grip and available arm evidence.
// Fixed apparatus reference and source PTS. Dip motion follows a frozen
// grip-to-shoulder axis, independent of the rail silhouette direction.
struct ExerciseCounter: Sendable {
    enum Exercise: String, CaseIterable, Codable, Sendable {
        case pullUp, dip, pushUp
        static let barExercises: [Self] = [.pullUp, .dip]
        var title: String { switch self { case .pullUp: "Pull-up"; case .dip: "Parallel-bar dip"; case .pushUp: "Push-up" } }
    }
    enum Phase: String, Codable, Sendable {
        case seekingStart, ready, outbound, returning, finished
        var title: String {
            switch self {
            case .seekingStart: "Show extended starting position"
            case .ready: "Ready"
            case .outbound: "Moving toward bent-arm endpoint"
            case .returning: "Return to extended position"
            case .finished: "Sequence finished"
            }
        }
    }
    enum Outcome: String, Codable, Sendable { case movement, partial, interrupted }
    struct Event: Codable, Equatable, Sendable {
        let outcome: Outcome
        let sourceSeconds: Double
        let reason: String
    }
    struct Summary: Codable, Equatable, Sendable {
        let policyVersion: Int
        let exercise: Exercise
        let side: ArmMeasurement.Side
        // Optional for decoding historical reports written before policy v7.
        let armTracking: String?
        let phase: Phase
        let observedMovements: Int
        let partialAttempts: Int
        let interruptedAttempts: Int
        let trackingIssue: String?
        let formVerification: String
    }

    // Policy v9 retains the raw counter endpoints; shared analytics now reject
    // pull-up completion after positive release during set-boundary dwell.
    // Dips use a fixed grip-to-shoulder
    // axis and arm-scaled travel, require grip continuity and return near the
    // acquired top, and check the opposite arm when it has a usable baseline.
    // These are provisional movement counts, not anatomical form/depth grading.
    static let policyVersion = 9
    static let extendedDegrees = 155.0
    static let departureDegrees = 140.0
    static let bentDegrees = 100.0
    static let endpointDwellSeconds = 0.12
    static let maximumGapSeconds = 0.35
    static let maximumDipObservationOutageSeconds = 0.75
    static let minimumBarDistanceReductionFraction = 0.20
    static let minimumTravelImageFraction = 0.04

    // Dip-specific *relative* movement gates. 120 degrees is deliberately only
    // a loose "support-like, not deeply bent" sanity check; it is not a lockout
    // criterion. The actual cycle uses excursion from the acquired top anchor.
    static let minimumDipSupportDegrees = 120.0
    static let minimumDipDepartureDegrees = 15.0
    static let minimumDipBendExcursionDegrees = 30.0
    static let dipDepartureTravelFraction = 0.50
    static let dipReturnTravelFraction = 0.50
    static let minimumDipTravelArmFraction = 0.18
    static let dipTopToleranceArmFraction = 0.08

    let exercise: Exercise
    let side: ArmMeasurement.Side
    private(set) var phase: Phase = .seekingStart
    private(set) var observedMovements = 0
    private(set) var partialAttempts = 0
    private(set) var interruptedAttempts = 0
    private(set) var trackingIssue: String?
    private(set) var lastEvent: Event?
    private(set) var events: [Event] = []
    private var lastTimestamp: PresentationTime?
    private var lastTime: Double? { lastTimestamp?.seconds }
    private var pullUpAnchors: [ArmMeasurement.Side: Sample] = [:]
    private var pullUpExtendedSince: [ArmMeasurement.Side: PresentationTime] = [:]
    private var pullUpEndpointWitnesses: Set<ArmMeasurement.Side> = []
    private var pullUpSamples: [ArmMeasurement.Side: Sample] = [:]
    private var dipSupportAxis: DipSupportMeasurement.Anchor?
    private var dipSample: DipSample?
    private var dipAnchor: DipSample?
    private var dipTopCandidate: DipSample?
    // This can only invalidate an attempt; it never supplies return/count dwell.
    private var dipUnconfirmedReturns = DipWitnesses()
    private var dipOutboundLows = DipWitnesses()
    private var dipBottom: DipSample?
    private var dipLastGeometryTime: PresentationTime?
    private var dipSupportCandidateSince: PresentationTime?
    private enum Endpoint { case extended, bent }
    private var endpoint: Endpoint?
    private var endpointSince: PresentationTime?
    private var endpointElapsedSeconds: Double {
        guard let lastTimestamp, let endpointSince else { return 0 }
        return lastTimestamp.elapsedSeconds(since: endpointSince)
    }
    private var activeAttempt = false

    private struct Sample: Sendable {
        let degrees: Double
        let shoulderToBarPixels: Double
        let imageShortSide: Double
    }

    private struct DipSample: Sendable {
        let degrees: Double?
        let supportHeightPixels: Double
        let imageShortSide: Double
        let otherDegrees: Double?
    }
    // A bounded frontier preserves actual samples. Caller-supplied evidence
    // keys never combine coordinates from different observations.
    private struct DipWitnesses: Sendable {
        private struct Entry: Sendable {
            let sample: DipSample
            let height: Double
            let angle: Double
        }
        private var entries: [Entry] = []
        func contains(where predicate: (DipSample) -> Bool) -> Bool { entries.contains { predicate($0.sample) } }
        mutating func clear() { entries.removeAll(keepingCapacity: true) }
        mutating func retain(_ sample: DipSample, height: Double, angle: Double) -> Bool {
            if entries.contains(where: { $0.height >= height && $0.angle >= angle }) { return true }
            entries.removeAll { $0.height <= height && $0.angle <= angle }
            guard entries.count < 64 else { return false }
            entries.append(Entry(sample: sample, height: height, angle: angle))
            return true
        }
    }

    init(exercise: Exercise = .pullUp, side: ArmMeasurement.Side = .left) {
        self.exercise = exercise
        self.side = side
    }

    var armTracking: String { exercise == .pullUp ? "bothUsableArms" : "selectedGripWithAvailableArms" }

    // Read the same current samples, per-arm anchors and source-time dwell used
    // for counting. Progress never advances on a display timer or stale pose.
    var pullUpProgress: PullUpProgress? {
        guard exercise == .pullUp else { return nil }
        let number = phase == .returning || phase == .finished
            ? observedMovements : observedMovements + 1
        func snapshot(_ stage: PullUpProgress.Stage, _ cue: PullUpProgress.Cue,
                      fraction: Double = 0, reset: Double = 0) -> PullUpProgress {
            PullUpProgress(stage: stage, repNumber: number, fraction: fraction,
                           resetFraction: reset, cue: cue, trackedArmCount: pullUpSamples.count)
        }
        if phase == .finished { return snapshot(.finished, .finished) }
        if let trackingIssue {
            let cue: PullUpProgress.Cue
            switch trackingIssue {
            case "barReferenceUnavailable": cue = .setBar
            case "armBaselineUnavailable", "sourceTimeGap", "nonIncreasingTimestamp", "invalidTimestamp":
                cue = .reacquireHang
            default: cue = .showArms
            }
            return snapshot(.paused, cue)
        }
        guard !pullUpSamples.isEmpty else { return snapshot(.startingHang, .hang) }
        let dwell = PullUpProgress.fraction(
            endpointElapsedSeconds / Self.endpointDwellSeconds
        )
        let extensionFraction = pullUpSamples.values.map {
            PullUpProgress.fraction(($0.degrees - Self.bentDegrees)
                / (Self.extendedDegrees - Self.bentDegrees))
        }.min() ?? 0
        let reset = min(0.99, 0.9 * extensionFraction + (endpoint == .extended ? 0.1 * dwell : 0))
        if phase == .seekingStart { return snapshot(.startingHang, .hang, reset: reset) }
        if phase == .returning { return snapshot(.counted, .lower, fraction: 1, reset: reset) }
        if phase == .outbound, endpoint == .extended {
            return snapshot(.startingHang, .hang, reset: reset)
        }

        var elbowFraction = 1.0
        var travelFraction = 1.0
        for (side, sample) in pullUpSamples {
            guard let anchor = pullUpAnchors[side] else { return snapshot(.startingHang, .reacquireHang) }
            elbowFraction = min(elbowFraction, PullUpProgress.fraction(
                (Self.extendedDegrees - sample.degrees) / (Self.extendedDegrees - Self.bentDegrees)
            ))
            travelFraction = min(travelFraction, PullUpProgress.fraction(
                (anchor.shoulderToBarPixels - sample.shoulderToBarPixels)
                    / requiredTravel(anchorDistance: anchor.shoulderToBarPixels,
                                     imageShortSide: anchor.imageShortSide)
            ))
        }
        let geometry = min(elbowFraction, travelFraction)
        // Reserve the last 10% for endpoint dwell; green/100% requires an event.
        let fraction = min(0.99, 0.9 * geometry + (endpoint == .bent ? 0.1 * dwell : 0))
        if phase == .ready, pullUpSamples.values.allSatisfy({ $0.degrees >= Self.extendedDegrees }) {
            return snapshot(.ready, .pull)
        }
        if endpoint == .bent { return snapshot(.holdingTop, .hold, fraction: fraction) }
        return snapshot(.pulling, travelFraction < elbowFraction ? .pullHigher : .finishPull,
                        fraction: fraction)
    }

    // Shares the current fixed-axis samples, anchors, endpoint witnesses and
    // source-time dwell with consumeDip. This does not alter counting policy.
    var dipProgress: DipProgress? {
        guard exercise == .dip else { return nil }
        let armCount = dipSample.map { ($0.degrees == nil ? 0 : 1) + ($0.otherDegrees == nil ? 0 : 1) } ?? 0
        func snapshot(_ stage: DipProgress.Stage, _ cue: DipProgress.Cue,
                      fraction: Double = 0, number: Int? = nil) -> DipProgress {
            DipProgress(stage: stage, repNumber: number ?? observedMovements + 1,
                        fraction: fraction, cue: cue, trackedArmCount: armCount)
        }
        if phase == .finished { return snapshot(.finished, .finished, number: observedMovements) }
        if let trackingIssue {
            let cue: DipProgress.Cue
            switch trackingIssue {
            case "barReferenceUnavailable": cue = .setRail
            case "dipGripMoved": cue = .reestablishGrip
            case "dipSupportNeeded", "dipArmBaselineUnavailable", "returnNotSustained", "dipReturnUnresolved", "sourceTimeGap", "nonIncreasingTimestamp", "invalidTimestamp":
                cue = .reacquireSupport
            default: cue = .showArms
            }
            return snapshot(.paused, cue)
        }
        // A briefly hidden elbow may sustain a top that was already witnessed.
        // The counter can legitimately emit a movement on that frame; its event
        // takes precedence over unavailable instantaneous angle progress.
        if phase == .ready, let event = lastEvent, event.outcome == .movement,
           event.sourceSeconds == lastTime {
            return snapshot(.counted, .readyNext, fraction: 1, number: observedMovements)
        }
        guard phase != .seekingStart, let sample = dipSample, let anchor = dipAnchor,
              let degrees = sample.degrees, let topDegrees = anchor.degrees else {
            return snapshot(.startingSupport, .support)
        }
        let dwell = normalizedProgress(
            endpointElapsedSeconds / Self.endpointDwellSeconds
        )
        let required = requiredDipTravel(anchor)
        let descentTravel = normalizedProgress((anchor.supportHeightPixels - sample.supportHeightPixels) / required)
        func bendFraction(_ angle: Double, top: Double) -> Double {
            let target = max(Self.bentDegrees, top - Self.minimumDipBendExcursionDegrees)
            return normalizedProgress((top - angle) / (top - target))
        }
        var bend = bendFraction(degrees, top: topDegrees)
        if let other = sample.otherDegrees, let otherTop = anchor.otherDegrees {
            bend = min(bend, bendFraction(other, top: otherTop))
        }

        let tolerance = dipTopTolerance(sample)
        if phase == .ready {
            // The counter immediately rearms after a dip. Retain a brief earned
            // confirmation only while still at the top, never into a new descent.
            if let event = lastEvent, event.outcome == .movement,
               let time = lastTime, time - event.sourceSeconds <= DipProgress.countConfirmationSeconds,
               anchor.supportHeightPixels - sample.supportHeightPixels <= tolerance,
               degrees >= max(Self.minimumDipSupportDegrees, topDegrees - 20) {
                return snapshot(.counted, .readyNext, fraction: 1, number: observedMovements)
            }
            if descentTravel == 0 || bend == 0 { return snapshot(.ready, .lower) }
        }
        if phase == .returning, let bottom = dipBottom, let bottomDegrees = bottom.degrees {
            let targetHeight = max(anchor.supportHeightPixels - tolerance,
                                   bottom.supportHeightPixels + Self.dipReturnTravelFraction * required)
            let recovery = normalizedProgress((sample.supportHeightPixels - bottom.supportHeightPixels)
                                              / (targetHeight - bottom.supportHeightPixels))
            func extensionFraction(_ angle: Double, bottom: Double, top: Double) -> Double {
                let target = max(Self.minimumDipSupportDegrees, top - 20)
                return normalizedProgress((angle - bottom) / max(1, target - bottom))
            }
            var extensionProgress = extensionFraction(degrees, bottom: bottomDegrees, top: topDegrees)
            if let other = sample.otherDegrees, let otherTop = anchor.otherDegrees {
                extensionProgress = min(extensionProgress, extensionFraction(other,
                    bottom: bottom.otherDegrees ?? min(Self.bentDegrees, otherTop - 30), top: otherTop))
            }
            let geometry = min(recovery, extensionProgress)
            let fraction = min(0.99, 0.5 + 0.4 * geometry + (endpoint == .extended ? 0.09 * dwell : 0))
            if endpoint == .extended { return snapshot(.holdingTop, .hold, fraction: fraction) }
            return snapshot(.rising, recovery <= extensionProgress ? .rise : .extendArms, fraction: fraction)
        }
        if phase == .outbound, endpoint == .extended {
            // A return without a witnessed bottom is a partial attempt, not
            // second-half progress toward a counted cycle.
            return snapshot(.startingSupport, .support)
        }
        let fraction = min(0.49, 0.45 * min(descentTravel, bend) + (endpoint == .bent ? 0.05 * dwell : 0))
        if endpoint == .bent { return snapshot(.holdingBottom, .hold, fraction: fraction) }
        return snapshot(.lowering, descentTravel < bend ? .lowerFurther : .bendArms, fraction: fraction)
    }

    var summary: Summary {
        Summary(policyVersion: Self.policyVersion, exercise: exercise, side: side,
                armTracking: armTracking,
                phase: phase, observedMovements: observedMovements,
                partialAttempts: partialAttempts, interruptedAttempts: interruptedAttempts,
                trackingIssue: trackingIssue, formVerification: "unverified")
    }

    mutating func reset() { self = Self(exercise: exercise, side: side) }

    // Kept for saved-pose diagnostics that intentionally have no apparatus
    // reference. Such streams cannot count.
    @discardableResult
    mutating func consume(_ pose: PoseResult) -> Event? {
        consume(pose, referenceEdge: nil)
    }

    @discardableResult
    mutating func consume(_ pose: PoseResult, referenceEdge: BarSegment?) -> Event? {
        guard exercise != .pushUp else { interrupt(reason: "unsupportedExercise"); return nil }
        guard phase != .finished else { return nil }
        let time = pose.timestamp
        guard time.seconds.isFinite else { return interrupt(reason: "invalidTimestamp") }
        // Source-tick subtraction keeps exact dwell/gap boundaries and avoids
        // aliasing distinct frames at a large absolute source origin.
        let gap = lastTimestamp.map { time.elapsedSeconds(since: $0) }
        if let gap, !gap.isFinite { return interrupt(reason: "invalidTimestamp") }
        if let gap, gap <= 0 {
            return interrupt(reason: "nonIncreasingTimestamp")
        }
        lastTimestamp = time
        var event: Event?
        if let gap, gap > Self.maximumGapSeconds {
            event = interrupt(reason: "sourceTimeGap")
        }

        guard let referenceEdge, referenceEdge.isValid,
              pose.imageSize.isValid,
              (0...pose.imageSize.width).contains(referenceEdge.a.x),
              (0...pose.imageSize.height).contains(referenceEdge.a.y),
              (0...pose.imageSize.width).contains(referenceEdge.b.x),
              (0...pose.imageSize.height).contains(referenceEdge.b.y) else {
            return interrupt(reason: "barReferenceUnavailable") ?? event
        }

        if exercise == .dip {
            return consumeDip(pose, referenceEdge: referenceEdge, at: time) ?? event
        }
        return consumePullUp(pose, referenceEdge: referenceEdge, at: time) ?? event
    }

    private mutating func consumePullUp(
        _ pose: PoseResult,
        referenceEdge: BarSegment,
        at time: PresentationTime
    ) -> Event? {
        let measurement = PullUpArmMeasurement(pose: pose)
        guard !measurement.arms.isEmpty else {
            return interrupt(reason: measurement.unavailableReason?.rawValue ?? "unusableArm")
        }
        let shortSide = min(pose.imageSize.width, pose.imageSize.height)
        var samples: [ArmMeasurement.Side: Sample] = [:]
        for arm in measurement.arms {
            let distance = referenceEdge.perpendicularDistance(to: arm.shoulder)
            guard distance.isFinite, shortSide.isFinite, shortSide > 0 else {
                return interrupt(reason: "invalidBarGeometry")
            }
            samples[arm.side] = Sample(degrees: arm.estimate.elbowDegrees,
                                      shoulderToBarPixels: distance, imageShortSide: shortSide)
        }
        let witnesses = Set(samples.keys)
        pullUpSamples = samples
        let extended = samples.values.allSatisfy { $0.degrees >= Self.extendedDegrees }
        for side in ArmMeasurement.Side.allCases {
            if let sample = samples[side], sample.degrees >= Self.extendedDegrees {
                if pullUpExtendedSince[side] == nil { pullUpExtendedSince[side] = time }
            } else { pullUpExtendedSince[side] = nil }
        }
        // Each arm establishes its own extended-hang baseline. A newly visible
        // bent arm cannot borrow the other shoulder's distance to fabricate travel.
        if extended || phase == .seekingStart || phase == .ready || phase == .returning {
            for (side, sample) in samples {
                if let since = pullUpExtendedSince[side], time.elapsedSeconds(since: since) >= Self.endpointDwellSeconds {
                    pullUpAnchors[side] = sample
                }
            }
        }
        if phase != .seekingStart && !extended && samples.keys.contains(where: { pullUpAnchors[$0] == nil }) {
            return interrupt(reason: "armBaselineUnavailable")
        }
        trackingIssue = nil
        let bent = samples.allSatisfy { side, sample in reachedBentEndpoint(sample, side: side) }

        switch phase {
        case .seekingStart:
            if sustainedPullUp(extended ? .extended : nil, witnesses: witnesses, at: time) {
                armPullUp()
            }
        case .ready:
            if !extended && samples.values.allSatisfy({ $0.degrees < Self.departureDegrees }) {
                activeAttempt = true
                phase = .outbound
                endpointSince = nil
                _ = sustainedPullUp(bent ? .bent : nil, witnesses: witnesses, at: time)
            }
        case .outbound:
            if extended {
                if sustainedPullUp(.extended, witnesses: witnesses, at: time) {
                    let event = record(.partial, at: time.seconds, reason: "returnedBeforeBentEndpoint")
                    armPullUp()
                    return event
                }
            } else if sustainedPullUp(bent ? .bent : nil, witnesses: witnesses, at: time) {
                phase = .returning
                endpointSince = nil
                let event = record(.movement, at: time.seconds,
                                   reason: "barReferencedTop;chinClearanceNotMeasured")
                activeAttempt = false
                return event
            }
        case .returning:
            if sustainedPullUp(extended ? .extended : nil, witnesses: witnesses, at: time) {
                armPullUp()
            }
        case .finished:
            break
        }
        return nil
    }

    // Dwell needs at least one continuously observed arm. Alternating disjoint
    // left/right endpoint glimpses cannot be stitched into a sustained endpoint.
    private mutating func sustainedPullUp(
        _ next: Endpoint?, witnesses: Set<ArmMeasurement.Side>, at time: PresentationTime
    ) -> Bool {
        if next == nil {
            pullUpEndpointWitnesses = []
        } else if next != endpoint || endpointSince == nil {
            pullUpEndpointWitnesses = witnesses
        } else {
            let continuous = pullUpEndpointWitnesses.intersection(witnesses)
            if continuous.isEmpty {
                endpointSince = nil
                pullUpEndpointWitnesses = witnesses
            } else { pullUpEndpointWitnesses = continuous }
        }
        return sustained(next, at: time)
    }

    private mutating func consumeDip(
        _ pose: PoseResult,
        referenceEdge: BarSegment,
        at time: PresentationTime
    ) -> Event? {
        guard pose.people.count <= 1 else {
            return interrupt(reason: "multiplePeople")
        }
        guard !pose.people.isEmpty else {
            return dipObservationUnavailable(reason: "noPerson", at: time)
        }
        guard let support = DipSupportMeasurement(pose: pose, side: side, referenceEdge: referenceEdge) else {
            return dipObservationUnavailable(reason: "dipSupportUnclear", at: time)
        }
        // Visible shoulders/wrists can outlast the initial elbow witness. Once
        // its allowed outage expires, reacquire extension and a fresh grip axis
        // instead of leaving an expired candidate unable to arm indefinitely.
        if phase == .seekingStart, let since = dipSupportCandidateSince,
           time.elapsedSeconds(since: since) > Self.maximumDipObservationOutageSeconds {
            interrupt(reason: "dipSupportNeeded")
        }
        if dipSupportAxis == nil {
            guard support.isStartingSupport, let axis = DipSupportMeasurement.Anchor(support) else {
                trackingIssue = "dipSupportNeeded"
                return nil
            }
            dipSupportAxis = axis
        }
        guard let axis = dipSupportAxis, axis.retainsGrip(support) else {
            return interrupt(reason: "dipGripMoved")
        }
        let sample = DipSample(
            degrees: support.degrees,
            supportHeightPixels: axis.height(of: support.shoulder),
            imageShortSide: min(pose.imageSize.width, pose.imageSize.height),
            otherDegrees: support.otherDegrees
        )
        dipSample = sample
        dipLastGeometryTime = time
        trackingIssue = support.degrees == nil ? "lowConfidence" : nil
        if sample.otherDegrees != nil {
            if phase == .seekingStart, dipTopCandidate?.otherDegrees == nil {
                // A new arm cannot borrow time from an earlier single-arm hold.
                guard support.isStartingSupport else {
                    return interrupt(reason: "dipArmBaselineUnavailable")
                }
                dipTopCandidate = nil
                dipSupportCandidateSince = nil
            } else if phase != .seekingStart, dipAnchor?.otherDegrees == nil {
                // Its first bottom/return observation is not a top baseline.
                let interrupted = interrupt(reason: "dipArmBaselineUnavailable")
                // Interruption guarantees seekingStart. One re-entry can seed
                // fresh support from this actual frame, never restore a cycle.
                _ = consumeDip(pose, referenceEdge: referenceEdge, at: time)
                return interrupted
            }
        }
        if (phase == .outbound || phase == .returning),
           dipUnconfirmedReturns.contains(where: { dipHasDeparted(sample, from: $0) }) {
            return interrupt(reason: "returnNotSustained")
        }

        switch phase {
        case .seekingStart:
            guard isDipSupportLike(sample) else {
                // A short confidence-only hole must not erase a previously
                // observed support endpoint. A valid bent-arm observation does.
                if sample.degrees != nil {
                    dipTopCandidate = nil
                    dipSupportCandidateSince = nil
                }
                _ = sustained(nil, at: time)
                return nil
            }
            dipTopCandidate = mergedDipTopCandidate(dipTopCandidate, with: sample)
            if dipSupportCandidateSince == nil {
                dipSupportCandidateSince = time
            } else if let since = dipSupportCandidateSince,
                      time.elapsedSeconds(since: since) >= Self.endpointDwellSeconds,
                      time.elapsedSeconds(since: since) <= Self.maximumDipObservationOutageSeconds {
                armDip(dipTopCandidate ?? sample)
            }

        case .ready:
            guard var anchor = dipAnchor else {
                return interrupt(reason: "missingDipAnchor")
            }

            // Improve the witnessed top while waiting, but never follow a
            // gradual descent downward. armDip establishes a fresh local top
            // after each completed cycle; grip and support axis stay fixed.
            if let degrees = sample.degrees, degrees >= Self.extendedDegrees {
                anchor = DipSample(degrees: maxDipDegrees(anchor.degrees, degrees),
                    supportHeightPixels: max(anchor.supportHeightPixels, sample.supportHeightPixels),
                    imageShortSide: sample.imageShortSide,
                    otherDegrees: maxDipDegrees(anchor.otherDegrees, sample.otherDegrees))
                dipAnchor = anchor
            } else if sample.supportHeightPixels > anchor.supportHeightPixels {
                anchor = DipSample(
                    degrees: maxDipDegrees(anchor.degrees, sample.degrees),
                    supportHeightPixels: sample.supportHeightPixels,
                    imageShortSide: sample.imageShortSide,
                    otherDegrees: maxDipDegrees(anchor.otherDegrees, sample.otherDegrees)
                )
                dipAnchor = anchor
            } else if isDipSupportLike(sample),
                      let degrees = sample.degrees,
                      let anchorDegrees = anchor.degrees,
                      degrees > anchorDegrees {
                anchor = DipSample(
                    degrees: degrees,
                    supportHeightPixels: anchor.supportHeightPixels,
                    imageShortSide: anchor.imageShortSide,
                    otherDegrees: maxDipDegrees(anchor.otherDegrees, sample.otherDegrees)
                )
                dipAnchor = anchor
            }

            // Support-like is an acquisition sanity check. In a frontal view,
            // a real descent can retain an obtuse projected elbow; departure
            // must follow the acquired angle and shoulder-travel baseline.
            if dipHasDeparted(sample, from: anchor) {
                activeAttempt = true
                phase = .outbound
                endpoint = nil
                endpointSince = nil
                dipTopCandidate = nil
                dipBottom = nil
                dipOutboundLows.clear()
                if !retainDipWitness(sample, upper: false) { return interrupt(reason: "dipReturnUnresolved") }
                _ = sustained(dipReachedBentEndpoint(sample, from: anchor) ? .bent : nil, at: time)
            }

        case .outbound:
            guard let anchor = dipAnchor else {
                return interrupt(reason: "missingDipAnchor")
            }
            if dipReturnToAnchorEvidence(sample, to: anchor) {
                if sustained(.extended, at: time) {
                    let event = record(.partial, at: time.seconds, reason: "returnedBeforeBentEndpoint")
                    armDip(dipTopCandidate ?? anchor)
                    return event
                }
                // The departure/partial-return height gates touch. Require
                // actual ascent before treating a plateau as return evidence.
                if dipOutboundLows.contains(where: { dipHasDeparted($0, from: sample) }), !retainDipWitness(sample) {
                    return interrupt(reason: "dipReturnUnresolved")
                }
            } else if sustained(dipReachedBentEndpoint(sample, from: anchor) ? .bent : nil, at: time) {
                phase = .returning
                dipBottom = sample
                dipOutboundLows.clear()
                endpoint = nil
                endpointSince = nil
                dipTopCandidate = nil
            }
            if phase == .outbound, !retainDipWitness(sample, upper: false) { return interrupt(reason: "dipReturnUnresolved") }

        case .returning:
            guard let anchor = dipAnchor, var bottom = dipBottom else {
                return interrupt(reason: "missingDipCycleAnchor")
            }
            if sample.degrees != nil,
               sample.supportHeightPixels < bottom.supportHeightPixels {
                dipBottom = sample
                bottom = sample
            }
            if dipReturnFromBottomEvidence(sample, from: bottom, cycleAnchor: anchor) {
                if sustained(.extended, at: time) {
                    let event = record(.movement, at: time.seconds,
                                       reason: "barReferencedCycle;dipDepthAndFormNotQualified")
                    armDip(dipTopCandidate ?? sample)
                    return event
                }
                if !retainDipWitness(sample) { return interrupt(reason: "dipReturnUnresolved") }
            } else {
                _ = sustained(nil, at: time)
            }

        case .finished:
            break
        }
        return nil
    }

    private func reachedBentEndpoint(_ sample: Sample, side: ArmMeasurement.Side) -> Bool {
        guard let anchor = pullUpAnchors[side] else { return false }
        let required = requiredTravel(
            anchorDistance: anchor.shoulderToBarPixels,
            imageShortSide: anchor.imageShortSide
        )
        let travelTowardBar = anchor.shoulderToBarPixels - sample.shoulderToBarPixels
        return sample.degrees <= Self.bentDegrees && travelTowardBar >= required
    }

    private func requiredTravel(anchorDistance: Double, imageShortSide: Double) -> Double {
        max(Self.minimumBarDistanceReductionFraction * anchorDistance,
            Self.minimumTravelImageFraction * imageShortSide)
    }

    private func requiredDipTravel(_ sample: DipSample) -> Double {
        max(Self.minimumDipTravelArmFraction * (dipSupportAxis?.armLength ?? 0),
            0.02 * sample.imageShortSide)
    }

    private func maxDipDegrees(_ a: Double?, _ b: Double?) -> Double? {
        switch (a, b) {
        case let (a?, b?): max(a, b)
        case let (a?, nil): a
        case let (nil, b?): b
        case (nil, nil): nil
        }
    }

    private func mergedDipTopCandidate(_ current: DipSample?, with sample: DipSample) -> DipSample {
        guard let current else { return sample }
        return DipSample(
            degrees: maxDipDegrees(current.degrees, sample.degrees),
            supportHeightPixels: max(current.supportHeightPixels, sample.supportHeightPixels),
            imageShortSide: sample.imageShortSide,
            otherDegrees: maxDipDegrees(current.otherDegrees, sample.otherDegrees)
        )
    }

    private func isDipSupportLike(_ sample: DipSample) -> Bool {
        guard let degrees = sample.degrees else { return false }
        return degrees >= Self.minimumDipSupportDegrees
    }

    private func dipHasDeparted(_ sample: DipSample, from anchor: DipSample) -> Bool {
        guard let degrees = sample.degrees, let anchorDegrees = anchor.degrees else { return false }
        let required = requiredDipTravel(anchor)
        let travel = anchor.supportHeightPixels - sample.supportHeightPixels
        return travel >= Self.dipDepartureTravelFraction * required &&
            anchorDegrees - degrees >= Self.minimumDipDepartureDegrees
    }

    // Both histories can only invalidate attempts. They cannot supply dwell or
    // replace the confirmed bottom; missing angles cannot create witnesses.
    private mutating func retainDipWitness(_ sample: DipSample, upper: Bool = true) -> Bool {
        guard let degrees = sample.degrees else { return true }
        if upper {
            let height = sample.supportHeightPixels - Self.dipDepartureTravelFraction * requiredDipTravel(sample)
            return dipUnconfirmedReturns.retain(sample, height: height, angle: degrees)
        }
        return dipOutboundLows.retain(sample, height: -sample.supportHeightPixels, angle: -degrees)
    }

    private func dipReachedBentEndpoint(_ sample: DipSample, from anchor: DipSample) -> Bool {
        guard let degrees = sample.degrees, let anchorDegrees = anchor.degrees else { return false }
        let required = requiredDipTravel(anchor)
        let travel = anchor.supportHeightPixels - sample.supportHeightPixels
        let otherBent: Bool
        if let other = sample.otherDegrees, let otherTop = anchor.otherDegrees {
            otherBent = other <= Self.bentDegrees || otherTop - other >= Self.minimumDipBendExcursionDegrees
        } else { otherBent = true }
        return otherBent && travel >= required &&
            (degrees <= Self.bentDegrees ||
             anchorDegrees - degrees >= Self.minimumDipBendExcursionDegrees)
    }

    private mutating func dipReturnToAnchorEvidence(_ sample: DipSample, to anchor: DipSample) -> Bool {
        let required = requiredDipTravel(anchor)
        let recovered = anchor.supportHeightPixels - sample.supportHeightPixels <=
            Self.dipReturnTravelFraction * required
        guard recovered else {
            dipTopCandidate = nil
            return false
        }

        if isDipSupportLike(sample) {
            dipTopCandidate = mergedDipTopCandidate(dipTopCandidate, with: sample)
            return true
        }

        // Once valid top-arm evidence exists, a following frame may sustain the
        // endpoint using independently observed shoulder/support geometry while
        // preserving—not fabricating—the last valid elbow evidence.
        if sample.degrees == nil, dipTopCandidate != nil {
            dipTopCandidate = mergedDipTopCandidate(dipTopCandidate, with: sample)
            return true
        }
        // Visible flexion contradicts the prior top; a later missing elbow
        // cannot reuse that witness to complete a new endpoint dwell.
        dipTopCandidate = nil
        return false
    }

    private mutating func dipReturnFromBottomEvidence(
        _ sample: DipSample,
        from bottom: DipSample,
        cycleAnchor: DipSample
    ) -> Bool {
        let tolerance = dipTopTolerance(sample)
        guard cycleAnchor.supportHeightPixels - sample.supportHeightPixels <= tolerance,
              sample.supportHeightPixels - bottom.supportHeightPixels >=
                Self.dipReturnTravelFraction * requiredDipTravel(cycleAnchor) else {
            dipTopCandidate = nil
            return false
        }
        if let other = sample.otherDegrees, let otherTop = cycleAnchor.otherDegrees,
           other < max(Self.minimumDipSupportDegrees, otherTop - 20) {
            dipTopCandidate = nil
            return false
        }
        if let degrees = sample.degrees, let topDegrees = cycleAnchor.degrees,
           degrees >= max(Self.minimumDipSupportDegrees, topDegrees - 20) {
            dipTopCandidate = mergedDipTopCandidate(dipTopCandidate, with: sample)
            return true
        }
        // A reliable shoulder and retained wrist grip may sustain a top already
        // witnessed with actual arm extension; missing angles cannot acquire it.
        if sample.degrees == nil, dipTopCandidate != nil {
            dipTopCandidate = mergedDipTopCandidate(dipTopCandidate, with: sample)
            return true
        }
        dipTopCandidate = nil
        return false
    }

    private mutating func dipObservationUnavailable(reason: String, at time: PresentationTime) -> Event? {
        trackingIssue = reason
        dipSample = nil

        // Missing frames never contribute dwell time, but a short outage should
        // not erase already observed top geometry/arm evidence. Long outages
        // still force a fresh start and record an active interruption.
        endpoint = nil
        endpointSince = nil
        guard let lastGeometry = dipLastGeometryTime else { return nil }
        guard time.elapsedSeconds(since: lastGeometry) > Self.maximumDipObservationOutageSeconds else { return nil }
        dipTopCandidate = nil
        dipSupportCandidateSince = nil
        return interrupt(reason: reason)
    }

    private func dipTopTolerance(_ sample: DipSample) -> Double {
        max(Self.dipTopToleranceArmFraction * (dipSupportAxis?.armLength ?? 0),
            0.01 * sample.imageShortSide)
    }

    private mutating func sustained(_ next: Endpoint?, at time: PresentationTime) -> Bool {
        guard let next else {
            endpoint = nil
            endpointSince = nil
            return false
        }
        guard endpoint == next, let since = endpointSince else {
            endpoint = next
            endpointSince = time
            return false
        }
        return time.elapsedSeconds(since: since) >= Self.endpointDwellSeconds
    }

    private mutating func armPullUp() {
        phase = .ready
        endpoint = nil
        endpointSince = nil
        pullUpEndpointWitnesses = []
        activeAttempt = false
    }

    private mutating func armDip(_ sample: DipSample) {
        phase = .ready
        dipAnchor = sample
        dipTopCandidate = nil
        dipUnconfirmedReturns.clear()
        dipOutboundLows.clear()
        dipBottom = nil
        dipSupportCandidateSince = nil
        endpoint = nil
        endpointSince = nil
        activeAttempt = false
        trackingIssue = nil
    }

    @discardableResult
    mutating func interrupt(reason: String = "inferenceFailure") -> Event? {
        guard phase != .finished else { return nil }
        let event = activeAttempt ? record(.interrupted, at: lastTime ?? 0, reason: reason) : nil
        phase = .seekingStart
        activeAttempt = false
        pullUpAnchors = [:]
        pullUpExtendedSince = [:]
        pullUpEndpointWitnesses = []
        dipSupportAxis = nil
        dipSample = nil
        dipAnchor = nil
        pullUpSamples = [:]
        dipTopCandidate = nil
        dipUnconfirmedReturns.clear()
        dipOutboundLows.clear()
        dipBottom = nil
        dipSupportCandidateSince = nil
        dipLastGeometryTime = nil
        endpoint = nil
        endpointSince = nil
        trackingIssue = reason
        return event
    }

    @discardableResult
    mutating func finish() -> Event? {
        guard phase != .finished else { return nil }
        let event = interrupt(reason: "endOfInput")
        phase = .finished
        trackingIssue = nil
        return event
    }

    private mutating func record(_ outcome: Outcome, at time: Double, reason: String) -> Event {
        switch outcome {
        case .movement: observedMovements += 1
        case .partial: partialAttempts += 1
        case .interrupted: interruptedAttempts += 1
        }
        let event = Event(outcome: outcome, sourceSeconds: time, reason: reason)
        lastEvent = event
        events.append(event)
        return event
    }
}
