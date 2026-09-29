import Foundation

// Provisional movement counting, NOT exercise/form acceptance. One user-selected
// anatomical arm, one confirmed fixed apparatus edge, and source PTS only.
// Pull-ups retain the strict arm-angle policy. Dips use the fixed rail as the
// primary cycle coordinate because projected elbow extension is view-dependent.
struct ExerciseCounter: Sendable {
    enum Exercise: String, CaseIterable, Codable, Sendable {
        case pullUp, dip
        var title: String { self == .pullUp ? "Pull-up" : "Parallel-bar dip" }
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
        let phase: Phase
        let observedMovements: Int
        let partialAttempts: Int
        let interruptedAttempts: Int
        let trackingIssue: String?
        let formVerification: String
    }

    // Policy v3 keeps the independently confirmed fixed bar/rail reference.
    // Pull-ups retain policy-v2 absolute arm gates. Dips use a relative cycle
    // anchored at a visually supported top position: shoulder-to-rail travel is
    // the primary phase signal, while elbow angle only establishes that the arm
    // is support-like and that a real bend occurred. This is movement counting,
    // not lockout/depth/form grading.
    static let policyVersion = 3
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

    let exercise: Exercise
    let side: ArmMeasurement.Side
    private(set) var phase: Phase = .seekingStart
    private(set) var observedMovements = 0
    private(set) var partialAttempts = 0
    private(set) var interruptedAttempts = 0
    private(set) var trackingIssue: String?
    private(set) var lastEvent: Event?
    private(set) var events: [Event] = []
    private var lastTime: Double?
    private var anchor: Sample?
    private var dipAnchor: DipSample?
    private var dipTopCandidate: DipSample?
    private var dipLastGeometryTime: Double?
    private enum Endpoint { case extended, bent }
    private var endpoint: Endpoint?
    private var endpointSince: Double?
    private var activeAttempt = false

    private struct Sample: Sendable {
        let degrees: Double
        let shoulderToBarPixels: Double
        let imageShortSide: Double
    }

    private struct DipSample: Sendable {
        let degrees: Double?
        let shoulderToBarPixels: Double
        let imageShortSide: Double
    }

    init(exercise: Exercise = .pullUp, side: ArmMeasurement.Side = .left) {
        self.exercise = exercise
        self.side = side
    }

    var summary: Summary {
        Summary(policyVersion: Self.policyVersion, exercise: exercise, side: side,
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
        guard phase != .finished else { return nil }
        let time = pose.timestamp.seconds
        guard time.isFinite else { return interrupt(reason: "invalidTimestamp") }
        if let lastTime, time <= lastTime {
            return interrupt(reason: "nonIncreasingTimestamp")
        }
        let previousTime = lastTime
        lastTime = time
        var event: Event?
        if let previousTime, time - previousTime > Self.maximumGapSeconds {
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
        at time: Double
    ) -> Event? {
        let measurement = ArmMeasurement(pose: pose, side: side)
        guard let estimate = measurement.estimate else {
            return interrupt(reason: measurement.unavailableReason?.rawValue ?? "unusableArm")
        }
        let shoulderJoint = side.joints[0]
        guard let shoulder = pose.people.first?.landmark(shoulderJoint)?.position else {
            return interrupt(reason: "missingJoint")
        }
        let distance = referenceEdge.perpendicularDistance(to: shoulder)
        let shortSide = min(pose.imageSize.width, pose.imageSize.height)
        guard distance.isFinite, shortSide.isFinite, shortSide > 0 else {
            return interrupt(reason: "invalidBarGeometry")
        }
        let sample = Sample(degrees: estimate.elbowDegrees,
                            shoulderToBarPixels: distance,
                            imageShortSide: shortSide)
        trackingIssue = nil

        switch phase {
        case .seekingStart:
            if sustained(sample.degrees >= Self.extendedDegrees ? .extended : nil, at: time) {
                arm(sample)
            }
        case .ready:
            if sample.degrees >= Self.extendedDegrees {
                anchor = sample
            } else if sample.degrees < Self.departureDegrees {
                activeAttempt = true
                phase = .outbound
                endpointSince = nil
                _ = sustained(reachedBentEndpoint(sample) ? .bent : nil, at: time)
            }
        case .outbound:
            if sample.degrees >= Self.extendedDegrees {
                if sustained(.extended, at: time) {
                    let event = record(.partial, at: time, reason: "returnedBeforeBentEndpoint")
                    arm(sample)
                    return event
                }
            } else if sustained(reachedBentEndpoint(sample) ? .bent : nil, at: time) {
                phase = .returning
                endpointSince = nil
                let event = record(.movement, at: time,
                                   reason: "barReferencedTop;chinClearanceNotMeasured")
                activeAttempt = false
                return event
            }
        case .returning:
            if sustained(sample.degrees >= Self.extendedDegrees ? .extended : nil, at: time) {
                arm(sample)
            }
        case .finished:
            break
        }
        return nil
    }

    private mutating func consumeDip(
        _ pose: PoseResult,
        referenceEdge: BarSegment,
        at time: Double
    ) -> Event? {
        guard pose.people.count <= 1 else {
            return interrupt(reason: "multiplePeople")
        }
        guard let person = pose.people.first else {
            return dipObservationUnavailable(reason: "noPerson", at: time)
        }
        let shoulderJoint = side.joints[0]
        guard let shoulder = person.landmark(shoulderJoint)?.position else {
            return dipObservationUnavailable(reason: "missingJoint", at: time)
        }

        let distance = referenceEdge.perpendicularDistance(to: shoulder)
        let shortSide = min(pose.imageSize.width, pose.imageSize.height)
        guard distance.isFinite, shortSide.isFinite, shortSide > 0 else {
            return interrupt(reason: "invalidBarGeometry")
        }

        let measurement = ArmMeasurement(pose: pose, side: side)
        let sample = DipSample(
            degrees: measurement.estimate?.elbowDegrees,
            shoulderToBarPixels: distance,
            imageShortSide: shortSide
        )
        dipLastGeometryTime = time
        trackingIssue = measurement.unavailableReason?.rawValue

        switch phase {
        case .seekingStart:
            guard isDipSupportLike(sample) else {
                dipTopCandidate = nil
                _ = sustained(nil, at: time)
                return nil
            }
            if dipTopCandidate == nil ||
                sample.shoulderToBarPixels > dipTopCandidate!.shoulderToBarPixels {
                dipTopCandidate = sample
            }
            if sustained(.extended, at: time) {
                armDip(dipTopCandidate ?? sample)
            }

        case .ready:
            guard let anchor = dipAnchor else {
                return interrupt(reason: "missingDipAnchor")
            }
            if isDipSupportLike(sample) {
                if sample.shoulderToBarPixels > anchor.shoulderToBarPixels,
                   sample.degrees != nil {
                    dipAnchor = sample
                }
                _ = sustained(nil, at: time)
                return nil
            }
            if dipHasDeparted(sample, from: anchor) {
                activeAttempt = true
                phase = .outbound
                endpoint = nil
                endpointSince = nil
                dipTopCandidate = nil
                _ = sustained(dipReachedBentEndpoint(sample, from: anchor) ? .bent : nil, at: time)
            }

        case .outbound:
            guard let anchor = dipAnchor else {
                return interrupt(reason: "missingDipAnchor")
            }
            if dipReturnEvidence(sample, to: anchor) {
                if sustained(.extended, at: time) {
                    let event = record(.partial, at: time, reason: "returnedBeforeBentEndpoint")
                    armDip(dipTopCandidate ?? anchor)
                    return event
                }
            } else if sustained(dipReachedBentEndpoint(sample, from: anchor) ? .bent : nil, at: time) {
                phase = .returning
                endpoint = nil
                endpointSince = nil
                dipTopCandidate = nil
            }

        case .returning:
            guard let anchor = dipAnchor else {
                return interrupt(reason: "missingDipAnchor")
            }
            if dipReturnEvidence(sample, to: anchor) {
                if sustained(.extended, at: time) {
                    let event = record(.movement, at: time,
                                       reason: "barReferencedCycle;dipDepthAndFormNotQualified")
                    armDip(dipTopCandidate ?? anchor)
                    return event
                }
            } else {
                _ = sustained(nil, at: time)
            }

        case .finished:
            break
        }
        return nil
    }

    private func reachedBentEndpoint(_ sample: Sample) -> Bool {
        guard let anchor else { return false }
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

    private func isDipSupportLike(_ sample: DipSample) -> Bool {
        guard let degrees = sample.degrees else { return false }
        return degrees >= Self.minimumDipSupportDegrees
    }

    private func dipHasDeparted(_ sample: DipSample, from anchor: DipSample) -> Bool {
        guard let degrees = sample.degrees, let anchorDegrees = anchor.degrees else { return false }
        let required = requiredTravel(
            anchorDistance: anchor.shoulderToBarPixels,
            imageShortSide: anchor.imageShortSide
        )
        let travel = anchor.shoulderToBarPixels - sample.shoulderToBarPixels
        return travel >= Self.dipDepartureTravelFraction * required &&
            anchorDegrees - degrees >= Self.minimumDipDepartureDegrees
    }

    private func dipReachedBentEndpoint(_ sample: DipSample, from anchor: DipSample) -> Bool {
        guard let degrees = sample.degrees, let anchorDegrees = anchor.degrees else { return false }
        let required = requiredTravel(
            anchorDistance: anchor.shoulderToBarPixels,
            imageShortSide: anchor.imageShortSide
        )
        let travel = anchor.shoulderToBarPixels - sample.shoulderToBarPixels
        return travel >= required &&
            anchorDegrees - degrees >= Self.minimumDipBendExcursionDegrees
    }

    private mutating func dipReturnEvidence(_ sample: DipSample, to anchor: DipSample) -> Bool {
        let required = requiredTravel(
            anchorDistance: anchor.shoulderToBarPixels,
            imageShortSide: anchor.imageShortSide
        )
        let recovered = anchor.shoulderToBarPixels - sample.shoulderToBarPixels <=
            Self.dipReturnTravelFraction * required
        guard recovered else {
            dipTopCandidate = nil
            return false
        }

        if isDipSupportLike(sample) {
            if dipTopCandidate == nil ||
                sample.shoulderToBarPixels > dipTopCandidate!.shoulderToBarPixels {
                dipTopCandidate = sample
            }
            return true
        }

        // Once a valid support-like top sample has been seen, a following frame
        // may sustain the rail endpoint even if elbow/wrist confidence drops.
        // No arm sample is fabricated; only the independently observed shoulder-
        // to-rail geometry is used to complete endpoint dwell.
        return sample.degrees == nil && dipTopCandidate != nil
    }

    private mutating func dipObservationUnavailable(reason: String, at time: Double) -> Event? {
        trackingIssue = reason
        endpoint = nil
        endpointSince = nil
        dipTopCandidate = nil

        guard let lastGeometry = dipLastGeometryTime else { return nil }
        guard time - lastGeometry > Self.maximumDipObservationOutageSeconds else { return nil }
        return interrupt(reason: reason)
    }

    private mutating func sustained(_ next: Endpoint?, at time: Double) -> Bool {
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
        return time - since >= Self.endpointDwellSeconds
    }

    private mutating func arm(_ sample: Sample) {
        phase = .ready
        anchor = sample
        endpoint = nil
        endpointSince = nil
        activeAttempt = false
    }

    private mutating func armDip(_ sample: DipSample) {
        phase = .ready
        dipAnchor = sample
        dipTopCandidate = nil
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
        anchor = nil
        dipAnchor = nil
        dipTopCandidate = nil
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
