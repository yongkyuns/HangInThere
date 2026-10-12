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
    let samples: [WorkoutMotionSample]
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
    let reps: [WorkoutRepRecord]
    let backend: String?
    let modelRevision: Int?
    let schemaVersion: Int
    let lifecycleVersion: Int
    let trackingSide: ArmMeasurement.Side?
    let imageSize: ImageSize?
    let referenceEdge: BarSegment?
    var acceptedCount: Int { reps.filter { $0.acceptedAt != nil }.count }
}
