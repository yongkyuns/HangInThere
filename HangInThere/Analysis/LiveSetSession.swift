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

        var isInterruption: Bool { self != .manual }
    }

    private(set) var phase: Phase = .idle
    private(set) var endReason: EndReason?
    private(set) var counter = ExerciseCounter()
    private(set) var analyzedFrames = 0
    private(set) var usableFrames = 0
    private(set) var firstSourceSeconds: Double?
    private(set) var lastSourceSeconds: Double?

    var exercise: ExerciseCounter.Exercise { counter.exercise }
    var side: ArmMeasurement.Side { counter.side }
    var observedMovements: Int { counter.observedMovements }
    var trackingIssue: String? { counter.trackingIssue }

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
        analyzedFrames = 0
        usableFrames = 0
        firstSourceSeconds = nil
        lastSourceSeconds = nil
        endReason = nil
        phase = .running
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
        counter.consume(pose, referenceEdge: referenceEdge)
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
