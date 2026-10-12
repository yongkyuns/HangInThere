import Foundation

// DTOs mirror the versioned shared contract. Measurements and decisions are
// computed by WorkoutCore, never by these native presentation/storage models.
struct WorkoutMotionSample: Codable, Equatable, Sendable {
    let seconds: Double
    let leftDegrees: Double?
    let rightDegrees: Double?
    let shoulderHeight: Double?
    let usable: Bool
    let released: Bool
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
    var hasPractice: Bool { !reps.isEmpty || (activity?.observedSeconds ?? 0) > 0 }
    var progressInput: WorkoutSetRecord {
        var result = self
        result.reps = reps.map { rep in var summary = rep; summary.samples = []; return summary }
        if var activity = result.activity { activity.phases = []; result.activity = activity }
        return result
    }
    var acceptedCount: Int { reps.filter { $0.acceptedAt != nil }.count }
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
