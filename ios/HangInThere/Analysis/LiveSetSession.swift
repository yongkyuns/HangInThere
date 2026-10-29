import Foundation

struct LiveSetSession: Sendable {
    enum Phase: String, Equatable, Sendable {
        case idle
        case running
        case finished
    }

    enum EndReason: String, Equatable, Sendable {
        case manual
        case appInactive
        case cameraInterrupted
        case cameraFailure
        case setupInvalidated
        case phoneMoved
        case sceneShifted
        case sceneScaled
        case disengaged
        case trackingLost

        var isInterruption: Bool { self != .manual && self != .disengaged }
    }

    private(set) var phase: Phase = .idle
    private(set) var endReason: EndReason?
    private(set) var counter = ExerciseCounter()
    private(set) var analyzedFrames = 0
    private(set) var usableFrames = 0
    private(set) var firstSourceSeconds: Double?
    private(set) var lastSourceSeconds: Double?
    private(set) var bodyDipSnapshot: BodyDipWorkoutSnapshot?
    private(set) var bodyPullUpSnapshot: BodyPullUpWorkoutSnapshot?
    private var bodyDipMovementTimes: [Double] = []
    private var bodyPullUpMovementTimes: [Double] = []

    var exercise: ExerciseCounter.Exercise { counter.exercise }
    var side: ArmMeasurement.Side { counter.side }
    var observedMovements: Int { bodyPullUpSnapshot?.motion.cycles ?? bodyDipSnapshot?.motion.cycles ?? counter.observedMovements }
    var partialAttempts: Int { bodyPullUpSnapshot?.motion.partialAttempts ?? bodyDipSnapshot?.motion.partialAttempts ?? counter.partialAttempts }
    var interruptedAttempts: Int { bodyPullUpSnapshot?.motion.interruptedAttempts ?? bodyDipSnapshot?.motion.interruptedAttempts ?? counter.interruptedAttempts }
    var analyzedFrameCount: Int { analyzedFrames }
    var usableTrackingFrameCount: Int { usableFrames }
    var trackingIssue: String? {
        if let bodyPullUpSnapshot { return bodyPullUpSnapshot.motion.observationUsable ? nil : bodyPullUpSnapshot.motion.reason }
        if let bodyDipSnapshot { return bodyDipSnapshot.motion.observationUsable ? nil : bodyDipSnapshot.motion.reason }
        return counter.trackingIssue
    }
    var motionPhase: ExerciseCounter.Phase {
        if let bodyPullUpSnapshot {
            if phase == .finished { return .finished }
            switch bodyPullUpSnapshot.motion.phase {
            case "ready": return .ready
            case "outbound": return .outbound
            case "returning": return .returning
            default: return .seekingStart
            }
        }
        guard let bodyDipSnapshot else { return counter.phase }
        if phase == .finished { return .finished }
        switch bodyDipSnapshot.motion.phase {
        case "ready": return .ready
        case "outbound": return .outbound
        case "returning": return .returning
        default: return .seekingStart
        }
    }

    var firstSourceTimestampSeconds: Double? { firstSourceSeconds }
    var lastSourceTimestampSeconds: Double? { lastSourceSeconds }

    var durationSeconds: Double {
        guard let firstSourceSeconds, let lastSourceSeconds else { return 0 }
        return max(0, lastSourceSeconds - firstSourceSeconds)
    }

    var trackingCoverage: Double? {
        guard analyzedFrames > 0 else { return nil }
        return Double(usableFrames) / Double(analyzedFrames)
    }

    var movementTimes: [Double] {
        guard let firstSourceSeconds else { return [] }
        if bodyPullUpSnapshot != nil { return bodyPullUpMovementTimes.map { max(0, $0-firstSourceSeconds) } }
        if bodyDipSnapshot != nil { return bodyDipMovementTimes.map { max(0, $0-firstSourceSeconds) } }
        return counter.events.compactMap { event in
            guard event.outcome == .movement else { return nil }
            return max(0, event.sourceSeconds - firstSourceSeconds)
        }
    }

    mutating func start(
        exercise: ExerciseCounter.Exercise,
        side: ArmMeasurement.Side
    ) {
        counter = ExerciseCounter(exercise: exercise, side: side)
        bodyDipSnapshot = nil; bodyDipMovementTimes = []
        bodyPullUpSnapshot = nil; bodyPullUpMovementTimes = []
        analyzedFrames = 0
        usableFrames = 0
        firstSourceSeconds = nil
        lastSourceSeconds = nil
        endReason = nil
        phase = .running
    }

    /// The shared owner has already consumed this source frame. This transport
    /// updates native presentation only; it never runs the legacy dip counter.
    mutating func beginBodyDip(_ snapshot: BodyDipWorkoutSnapshot, side: ArmMeasurement.Side, startedAt: Double?) {
        start(exercise: .dip, side: side)
        firstSourceSeconds = startedAt
        consumeBodyDip(snapshot)
    }
    mutating func consumeBodyDip(_ snapshot: BodyDipWorkoutSnapshot) {
        guard phase == .running, exercise == .dip else { return }
        bodyDipSnapshot = snapshot
        analyzedFrames = snapshot.analyzedFrames; usableFrames = snapshot.usableFrames
        if snapshot.motion.sourceAccepted, let seconds = snapshot.motion.seconds, seconds.isFinite {
            if firstSourceSeconds == nil { firstSourceSeconds = seconds }
            lastSourceSeconds = max(lastSourceSeconds ?? seconds, seconds)
        }
        if let event = snapshot.motion.event, event.outcome == "imageCycle",
           bodyDipMovementTimes.last.map({ event.seconds > $0 }) ?? true {
            bodyDipMovementTimes.append(event.seconds)
        }
    }

    mutating func beginBodyPullUp(_ snapshot: BodyPullUpWorkoutSnapshot, side: ArmMeasurement.Side,
                                 startedAt: Double?, completedTimes: [Double]) {
        start(exercise: .pullUp, side: side)
        firstSourceSeconds = startedAt
        bodyPullUpMovementTimes = completedTimes
        consumeBodyPullUp(snapshot)
    }
    mutating func consumeBodyPullUp(_ snapshot: BodyPullUpWorkoutSnapshot) {
        guard phase == .running, exercise == .pullUp else { return }
        bodyPullUpSnapshot = snapshot
        analyzedFrames = snapshot.analyzedFrames; usableFrames = snapshot.usableFrames
        if snapshot.motion.sourceAccepted, let seconds = snapshot.motion.seconds, seconds.isFinite {
            if firstSourceSeconds == nil { firstSourceSeconds = seconds }
            lastSourceSeconds = max(lastSourceSeconds ?? seconds, seconds)
        }
        for event in snapshot.motion.events ?? snapshot.motion.event.map({ [$0] }) ?? [] where event.outcome == "imageCycle" {
            let observedReturn = event.observedCompletion ?? event.seconds
            if bodyPullUpMovementTimes.last.map({ observedReturn > $0 }) ?? true {
                bodyPullUpMovementTimes.append(observedReturn)
            }
        }
    }

    mutating func consume(_ pose: PoseResult, referenceEdge: BarSegment?) {
        guard phase == .running else { return }

        let time = pose.timestamp.seconds
        if time.isFinite {
            if firstSourceSeconds == nil {
                firstSourceSeconds = time
            }
            lastSourceSeconds = time
        }

        analyzedFrames += 1
        if referenceEdge == nil {
            counter.interrupt(reason: "barReferenceUnavailable")
        } else {
            counter.consume(pose, referenceEdge: referenceEdge)
        }
        if referenceEdge != nil, counter.trackingIssue == nil {
            usableFrames += 1
        }
    }

    mutating func interrupt(reason: String) {
        guard phase == .running else { return }
        counter.interrupt(reason: reason)
    }

    mutating func finish(reason: EndReason = .manual) {
        guard phase == .running else { return }
        counter.finish()
        endReason = reason
        phase = .finished
    }

    mutating func interruptAndFinish(reason: String, endReason: EndReason) {
        guard phase == .running else { return }
        counter.interrupt(reason: reason)
        counter.finish()
        self.endReason = endReason
        phase = .finished
    }

    mutating func prepareNextSet() {
        guard phase == .finished else { return }
        let exercise = counter.exercise
        let side = counter.side
        self = Self()
        counter = ExerciseCounter(exercise: exercise, side: side)
    }

    mutating func reset(
        exercise: ExerciseCounter.Exercise,
        side: ArmMeasurement.Side
    ) {
        self = Self()
        counter = ExerciseCounter(exercise: exercise, side: side)
    }
}
