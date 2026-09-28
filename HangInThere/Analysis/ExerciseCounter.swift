import Foundation

// Provisional movement counting, NOT exercise/form acceptance. One user-selected
// anatomical arm, one confirmed fixed apparatus edge, and source PTS only.
// No temporal smoothing, inferred samples, or pose-derived bar geometry.
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

    // Policy v2 replaces wrist-drift/projected-arm-length continuity with one
    // independently confirmed fixed bar/rail edge. Thresholds are generic
    // engineering gates, not calibrated exercise-validity criteria.
    static let policyVersion = 2
    static let extendedDegrees = 155.0
    static let departureDegrees = 140.0
    static let bentDegrees = 100.0
    static let endpointDwellSeconds = 0.12
    static let maximumGapSeconds = 0.35
    static let minimumBarDistanceReductionFraction = 0.20
    static let minimumTravelImageFraction = 0.04

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
    private enum Endpoint { case extended, bent }
    private var endpoint: Endpoint?
    private var endpointSince: Double?
    private var activeAttempt = false

    private struct Sample: Sendable {
        let degrees: Double
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
    // reference. Such streams cannot count under policy v2.
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

        let measurement = ArmMeasurement(pose: pose, side: side)
        guard let estimate = measurement.estimate else {
            return interrupt(reason: measurement.unavailableReason?.rawValue ?? "unusableArm") ?? event
        }
        // ArmMeasurement established unique, visible, bounded shoulder/elbow/wrist
        // evidence. The bar reference is independent of those joints.
        let shoulderJoint = side.joints[0]
        guard let shoulder = pose.people.first?.landmark(shoulderJoint)?.position else {
            return interrupt(reason: "missingJoint") ?? event
        }
        let distance = referenceEdge.perpendicularDistance(to: shoulder)
        let shortSide = min(pose.imageSize.width, pose.imageSize.height)
        guard distance.isFinite, shortSide.isFinite, shortSide > 0 else {
            return interrupt(reason: "invalidBarGeometry") ?? event
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
                    event = record(.partial, at: time, reason: "returnedBeforeBentEndpoint")
                    arm(sample)
                }
            } else if sustained(reachedBentEndpoint(sample) ? .bent : nil, at: time) {
                phase = .returning
                endpointSince = nil
                if exercise == .pullUp {
                    event = record(.movement, at: time, reason: "barReferencedTop;chinClearanceNotMeasured")
                    activeAttempt = false
                }
            }
        case .returning:
            if sustained(sample.degrees >= Self.extendedDegrees ? .extended : nil, at: time) {
                if exercise == .dip {
                    event = record(.movement, at: time, reason: "barReferencedCycle;dipDepthAndFormNotQualified")
                }
                arm(sample)
            }
        case .finished: break
        }
        return event
    }

    private func reachedBentEndpoint(_ sample: Sample) -> Bool {
        guard let anchor else { return false }
        // For supported views, both pull-up ascent and dip descent bring the
        // selected shoulder closer to the fixed gripping bar/rail image line.
        // The image-size floor prevents an almost-zero starting distance from
        // creating a trivial endpoint threshold.
        let required = max(Self.minimumBarDistanceReductionFraction * anchor.shoulderToBarPixels,
                           Self.minimumTravelImageFraction * anchor.imageShortSide)
        let travelTowardBar = anchor.shoulderToBarPixels - sample.shoulderToBarPixels
        return sample.degrees <= Self.bentDegrees && travelTowardBar >= required
    }

    private mutating func sustained(_ next: Endpoint?, at time: Double) -> Bool {
        guard let next else { endpoint = nil; endpointSince = nil; return false }
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
        endpointSince = nil
        activeAttempt = false
    }

    @discardableResult
    mutating func interrupt(reason: String = "inferenceFailure") -> Event? {
        guard phase != .finished else { return nil }
        let event = activeAttempt ? record(.interrupted, at: lastTime ?? 0, reason: reason) : nil
        phase = .seekingStart
        activeAttempt = false
        anchor = nil
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