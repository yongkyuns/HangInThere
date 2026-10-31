import Foundation
import WorkoutCore

// Athlete-provided setup, shared by review and coaching. Never inferred from demand.
struct LibraryContext: Codable, Equatable, Sendable {
    var intent = "ordinary"
    var assistance = "unknown"
    var assistanceLabel = ""
    var addedLoadKg: Double?
    var equipment = ""
    var cameraView = ""
    var variation = ""
    var effort: Int?
    var readiness = "unrecorded"
    var recoveryNote = ""
}

// DTOs mirror the versioned shared contract. Measurements and decisions are
// computed by WorkoutCore, never by these native presentation/storage models.
struct WorkoutMotionSample: Codable, Equatable, Sendable {
    let seconds: Double
    let leftDegrees: Double?
    let rightDegrees: Double?
    let shoulderHeight: Double?
    let usable: Bool
    let released: Bool
    var postureReleased: Bool? = nil
}
struct WorkoutAngleMetrics: Codable, Equatable, Sendable {
    let minimum: Double?
    let maximum: Double?
    let mean: Double?
    let observedSeconds: Double
    let coverage: Double?
}
struct WorkoutRepRecord: Codable, Equatable, Identifiable, Sendable {
    let id: String
    let setId: String
    let ordinal: Int
    let exercise: ExerciseCounter.Exercise
    let startedAt: Double
    let extremeAt: Double?
    let acceptedAt: Double?
    let returnedAt: Double?
    let closedAt: Double
    let closeReason: String
    let outcome: String
    let outboundSeconds: Double?
    let returnSeconds: Double?
    let left: WorkoutAngleMetrics
    let right: WorkoutAngleMetrics
    let shoulderExcursion: Double?
    let bilateralExtremeOffsetSeconds: Double?
    let usableDurationSeconds: Double
    let durationCoverage: Double?
    var samples: [WorkoutMotionSample]
    let curveTruncated: Bool
    let countPolicyVersion: Int
    let metricVersion: Int
    let acceptanceReason: String?
    let formVerification: String?
    var grip: WorkoutGripEstimate? = nil
    var countMethod: String? = nil
    var countConfirmedAt: Double? = nil
}
struct WorkoutSetRecord: Codable, Equatable, Identifiable, Sendable {
    let id: String
    let exercise: ExerciseCounter.Exercise
    let startedAt: Double?
    let endedAt: Double?
    let endReason: String
    var reps: [WorkoutRepRecord]
    let backend: String?
    let modelRevision: Int?
    let schemaVersion: Int
    let lifecycleVersion: Int
    let trackingSide: ArmMeasurement.Side?
    let imageSize: ImageSize?
    let referenceEdge: BarSegment?
    let trackedAtEpochMilliseconds: Int64?
    var activity: WorkoutActivity? = nil
    var grip: WorkoutGripRecord? = nil
    var trackedEndedAtEpochMilliseconds: Int64? = nil
    var countMethod: String? = nil
    var countPolicyVersion: Int? = nil
    var setGrip: WorkoutSetGripMetadata? = nil
    var boundaryPolicyVersion: Int? = nil
    var hasPractice: Bool { !reps.isEmpty || (activity?.observedSeconds ?? 0) > 0 }
    var progressInput: WorkoutSetRecord {
        var result = self
        result.reps = reps.map { rep in var summary = rep; summary.samples = []; return summary }
        result.grip = nil
        if var activity = result.activity { activity.phases = []; result.activity = activity }
        return result
    }
    var acceptedCount: Int { reps.filter { $0.acceptedAt != nil }.count }
}
struct WorkoutAfterSetReport: Codable, Equatable, Sendable {
    let title: String
    let measuredCompletedReps: Int
    let practiceAttempts: Int
    let observedHoldSeconds: Double?
    let observation: String
    let spokenCue: String
    let policyVersion: Int
    var goalTarget: Double? = nil
    var goalValue: Double? = nil
    var goalMetric: String? = nil
}

// A review projection of shared policy, never a persisted confidence/form score.
struct WorkoutAttemptAssessmentRecord: Codable, Equatable, Sendable {
    let status: String
    let title: String
    let explanation: String
    let reason: String?
    let durationCoverage: Double?
    let leftCoverage: Double?
    let rightCoverage: Double?
    let angleComparisonEligible: Bool
    let notes: [String]
    let policyVersion: Int
}

struct WorkoutPhaseSpan: Codable, Equatable, Identifiable, Sendable {
    let id: String
    let phase: String
    let startedAt: Double
    let endedAt: Double
    let observedSeconds: Double
    var observedStartedAt: Double? = nil
}
struct WorkoutPhaseTotal: Codable, Equatable, Sendable {
    let phase: String
    let observedSeconds: Double
}
struct WorkoutActivity: Codable, Equatable, Sendable {
    var phases: [WorkoutPhaseSpan]
    let totals: [WorkoutPhaseTotal]
    let longestHangSeconds: Double
    let longestSupportSeconds: Double
    let observedSeconds: Double
    let elapsedSeconds: Double
    let timelineTruncated: Bool
    let metricVersion: Int
}

// Immutable snapshot of common bilateral image-motion analysis; no SDK handles.
struct DipWorkoutSnapshot: Codable, Equatable, Sendable {
    struct Motion: Codable, Equatable, Sendable {
        struct Event: Codable, Equatable, Sendable {
            let outcome: String
            let sourceTime: PresentationTime
            let reason: String
        }
        let phase: String
        let imageCycles: Int
        let partialAttempts: Int
        let interruptedAttempts: Int
        let availability: String
        let progress: Double
        let event: Event?
        let policyVersion: Int
        var hasCurrentObservation: Bool {
            ["available", "ready", "acquiringMeasuredTop", "waitingForMeasuredTop", "waitingForBilateralTravel"].contains(availability)
        }
    }
    let motion: Motion
    let analysisRevision: Int
    let analyzedFrames: Int
    let usableFrames: Int
    let endReason: String?
    // Optional for cached snapshots from before shared observation guidance.
    var observationGuidance: String? = nil
}

// Athlete-entered results; these never stand in for camera measurements.
struct LoggedWorkoutSet: Codable, Equatable, Identifiable, Sendable {
    var id = UUID().uuidString
    var exercise: String
    var reps: Int
    var load: Double?
    var unit = "lb"
    var method = "entry"
    var startedAtEpochMilliseconds: Int64?
    var endedAtEpochMilliseconds: Int64?
    var loadLabel: String { load.map { "\($0.formatted(.number.precision(.fractionLength(0...2)))) \(unit)" } ?? "—" }
}
struct LoggedWorkout: Codable, Equatable, Identifiable, Sendable {
    var id = UUID().uuidString
    var epochMilliseconds = Int64(Date().timeIntervalSince1970 * 1000)
    var sets: [LoggedWorkoutSet] = []
    var updatedAtEpochMilliseconds = Int64(Date().timeIntervalSince1970 * 1000)
    var date: Date { Date(timeIntervalSince1970: Double(epochMilliseconds) / 1000) }
    var reps: Int { sets.reduce(0) { $0 + $1.reps } }
    var exercises: [String] { sets.reduce(into: []) { if !$0.contains($1.exercise) { $0.append($1.exercise) } } }
    static func exerciseKey(_ name: String) -> String { WorkoutLogBridge().exerciseKey(name: name) }
    static func cameraExercise(_ name: String) -> ExerciseCounter.Exercise? {
        WorkoutLogBridge().cameraExercise(name: name).flatMap(ExerciseCounter.Exercise.init(rawValue:))
    }
    static func lastSet(for exercise: String, history: [LoggedWorkout]) -> LoggedWorkoutSet? {
        let result = try! WorkoutLogBridge().lastSetJson(exercise: exercise, historyJson: json(history))
        return result.map { try! JSONDecoder().decode(LoggedWorkoutSet.self, from: Data($0.utf8)) }
    }
    func prefill(for exercise: String, history: [LoggedWorkout]) -> LoggedWorkoutSet? {
        let result = try! WorkoutLogBridge().prefillJson(exercise: exercise, currentJson: Self.json(self), historyJson: Self.json(history))
        return result.map { try! JSONDecoder().decode(LoggedWorkoutSet.self, from: Data($0.utf8)) }
    }
    func restBefore(_ set: LoggedWorkoutSet) -> Double? {
        try! WorkoutLogBridge().restBefore(logJson: Self.json(self), setId: set.id)?.doubleValue
    }
    static func validSet(exercise: String, reps: Int?, load: Double?, unit: String) -> Bool {
        WorkoutLogBridge().validSet(exercise: exercise, reps: reps.flatMap(Int32.init(exactly:)).map { KotlinInt(int: $0) },
            load: load.map { KotlinDouble(double: $0) }, unit: unit)
    }
    static func convertLoad(_ value: Double, from: String, to: String) -> Double {
        try! WorkoutLogBridge().convertLoad(value: value, from: from, to: to)
    }
    static var suggestedExercises: [String] { WorkoutLogBridge().suggestedExercises() }
    static func progress(history: [LoggedWorkout], exercise: String) -> LoggedWorkoutProgress {
        let result = try! WorkoutLogBridge().progressJson(historyJson: json(history), exercise: exercise)
        return try! JSONDecoder().decode(LoggedWorkoutProgress.self, from: Data(result.utf8))
    }
    private static func json<T: Encodable>(_ value: T) -> String { String(decoding: try! JSONEncoder().encode(value), as: UTF8.self) }
}
struct LoggedWorkoutProgress: Decodable, Sendable {
    struct Point: Decodable, Identifiable, Sendable { let id: String; let epochMilliseconds: Int64; let reps: Int
        var date: Date { Date(timeIntervalSince1970: Double(epochMilliseconds) / 1000) }
    }
    let setCount: Int
    let reps: Int
    let bestReps: Int
    let workouts: [Point]
    let recentSets: [LoggedWorkoutSet]
}
