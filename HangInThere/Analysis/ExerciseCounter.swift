import Foundation

// Provisional movement counting, NOT exercise/form acceptance. One user-selected
// anatomical arm, fixed camera, source PTS only. No smoothing or inferred samples.
// A confirmed bar can supply independent contact/reference evidence, but is not
// interpreted as proof of physical contact or valid exercise form.
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
        let referenceMode: String
        let formVerification: String
    }

    // Fixed engineering thresholds. The bar-relative change is architectural:
    // no threshold below was fitted to the real-video temporal labels.
    static let policyVersion = 2
    static let extendedDegrees = 155.0
    static let departureDegrees = 140.0
    static let bentDegrees = 100.0
    static let endpointDwellSeconds = 0.12
    static let maximumGapSeconds = 0.35
    static let minimumTravelArmLengths = 0.20
    static let maximumWristBarOffsetChangeArmLengths = 0.25

    let exercise: Exercise
    let side: ArmMeasurement.Side
    private(set) var phase: Phase = .seekingStart
    private(set) var observedMovements = 0
    private(set) var partialAttempts = 0
    private(set) var interruptedAttempts = 0
    private(set) var trackingIssue: String?
    private(set) var lastEvent: Event?
    private var lastTime: Double?
    private var anchor: Sample?
    private var usedConfirmedBar = false
    private enum Endpoint { case extended, bent }
    private var endpoint: Endpoint?
    private var endpointSince: Double?
    private var activeAttempt = false

    private struct Sample: Sendable {
        let degrees: Double
        let shoulderReferenceY: Double
        let armLength: Double
        let size: ImageSize
        let barEdge: BarSegment?
        let wristBarOffset: Double?
    }

    init(exercise: Exercise = .pullUp, side: ArmMeasurement.Side = .left) {
        self.exercise = exercise
        self.side = side
    }

    var summary: Summary {
        Summary(policyVersion: Self.policyVersion, exercise: exercise, side: side,
                phase: phase, observedMovements: observedMovements,
                partialAttempts: partialAttempts, interruptedAttempts: interruptedAttempts,
                trackingIssue: trackingIssue,
                referenceMode: usedConfirmedBar ? "confirmedBar" : "fixedCameraOnly",
                formVerification: "unverified")
    }

    mutating func reset() { self = Self(exercise: exercise, side: side) }

    @discardableResult
    mutating func consume(_ pose: PoseResult, bar: ConfirmedBar? = nil) -> Event? {
        guard phase != .finished else { return nil }
        let time = pose.timestamp.seconds
        guard time.isFinite else { return interrupt(reason: "invalidTimestamp") }
        if let lastTime, time <= lastTime {
            // Do not rewind the clock or count repeated delivery of one frame.
            return interrupt(reason: "nonIncreasingTimestamp")
        }
        let previousTime = lastTime
        lastTime = time
        var event: Event?
        if let previousTime, time - previousTime > Self.maximumGapSeconds {
            event = interrupt(reason: "sourceTimeGap")
        }

        if let bar {
            guard bar.isValid, bar.role == expectedBarRole, bar.imageSize == pose.imageSize else {
                return interrupt(reason: "barReferenceMismatch") ?? event
            }
            usedConfirmedBar = true
        }

        let measurement = ArmMeasurement(pose: pose, side: side)
        guard let estimate = measurement.estimate else {
            return interrupt(reason: measurement.unavailableReason?.rawValue ?? "unusableArm") ?? event
        }
        // ArmMeasurement established uniqueness, visibility, image bounds and
        // segment lengths. Never take the other arm when the selected one fails.
        let joints = side.joints
        let shoulder = pose.people[0].landmark(joints[0])!.position
        let wrist = pose.people[0].landmark(joints[2])!.position
        let referenceY = shoulder.y - (bar?.referenceEdge.midpoint.y ?? 0)
        let wristBarOffset = bar?.referenceEdge.signedImageNormalDistance(to: wrist)
        guard wristBarOffset?.isFinite != false else {
            return interrupt(reason: "barReferenceMismatch") ?? event
        }
        let sample = Sample(
            degrees: estimate.elbowDegrees,
            shoulderReferenceY: referenceY,
            armLength: estimate.upperArmPixels + estimate.forearmPixels,
            size: pose.imageSize,
            barEdge: bar?.referenceEdge,
            wristBarOffset: wristBarOffset
        )
        trackingIssue = nil

        if let anchor, phase == .outbound || phase == .returning || phase == .ready {
            guard sample.size == anchor.size else {
                return interrupt(reason: "imageGeometryChanged") ?? event
            }
            guard sample.barEdge == anchor.barEdge else {
                return interrupt(reason: "barReferenceChanged") ?? event
            }
            if let startOffset = anchor.wristBarOffset, let currentOffset = sample.wristBarOffset {
                let change = abs(currentOffset - startOffset) / anchor.armLength
                guard change <= Self.maximumWristBarOffsetChangeArmLengths else {
                    return interrupt(reason: "barContactDiscontinuity") ?? event
                }
            }
        }

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
                // The first bent observation starts the dwell; never completes
                // a transition by itself, regardless of how large the jump is.
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
                    event = record(.movement, at: time,
                                   reason: sample.barEdge == nil
                                     ? "chinAndBarNotMeasured"
                                     : "chinClearanceNotMeasured")
                    activeAttempt = false
                }
            }
        case .returning:
            if sustained(sample.degrees >= Self.extendedDegrees ? .extended : nil, at: time) {
                if exercise == .dip {
                    event = record(.movement, at: time, reason: "dipDepthAndFormNotQualified")
                }
                arm(sample)
            }
        case .finished: break
        }
        return event
    }

    private var expectedBarRole: ConfirmedBar.Role {
        if exercise == .pullUp { return .pullUpGrip }
        return side == .left ? .leftDipRail : .rightDipRail
    }

    private func reachedBentEndpoint(_ sample: Sample) -> Bool {
        guard let anchor else { return false }
        // With a fixed camera, body travel is measured against a fixed image
        // reference rather than the moving wrist. If a bar is present, both
        // samples use its fixed midpoint; without one this reduces to screen Y.
        let start = anchor.shoulderReferenceY
        let current = sample.shoulderReferenceY
        let travel = (exercise == .pullUp ? start - current : current - start) / anchor.armLength
        return sample.degrees <= Self.bentDegrees && travel >= Self.minimumTravelArmLengths
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
        return event
    }
}
