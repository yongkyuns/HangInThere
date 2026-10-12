import Foundation
import WorkoutCore

// Native persistence and the shared-framework adapter belong to the app layer.
// Analysis remains framework-free for Swift policy/replay tools on Linux/macOS.

struct WorkoutGoalRecord: Codable, Equatable, Sendable {
    let exercise: ExerciseCounter.Exercise
    let metric: String
    let target: Double
    let createdAtEpochMilliseconds: Int64?
}
struct WorkoutMilestoneRecord: Codable, Equatable, Identifiable, Sendable {
    let id: String
    let title: String
    let achieved: Bool
    let achievedAtEpochMilliseconds: Int64?
}
struct WorkoutProgressPoint: Codable, Equatable, Identifiable, Sendable {
    let id: String
    let trackedAtEpochMilliseconds: Int64
    let holdSeconds: Double?
    let completedReps: Int
    let elbowExcursionDegrees: Double?
}
struct WorkoutProgressEvidence: Codable, Equatable, Sendable {
    let id: String
    let exercise: ExerciseCounter.Exercise
    let trackedAtEpochMilliseconds: Int64?
    let endReason: String
    let hasPractice: Bool
    let holdSeconds: Double?
    let totalHoldSeconds: Double?
    let elbowExcursionDegrees: Double?
    let completedCount: Int
    let incompleteAttempts: Int
    let attemptCount: Int
}
struct WorkoutProgressReport: Codable, Equatable, Sendable {
    let exercise: ExerciseCounter.Exercise
    let holdTitle: String
    let bestHoldSeconds: Double?
    let latestHoldSeconds: Double?
    let totalHoldSeconds: Double?
    let partialAttempts: Int
    let completedReps: Int
    let bestElbowExcursionDegrees: Double?
    let goal: WorkoutGoalRecord
    let goalBest: Double?
    let goalFraction: Double?
    let goalAchieved: Bool
    let coaching: String
    let milestones: [WorkoutMilestoneRecord]
    let trend: [WorkoutProgressPoint]
    let policyVersion: Int
}

/// Goals remain fixed until a deliberate edit. Shared policy validates targets
/// and calculates reports. Native persistence never changes camera/session state.
@MainActor final class WorkoutGoalStore {
    static let shared = WorkoutGoalStore()
    private let defaults: UserDefaults
    init(defaults: UserDefaults = .standard) { self.defaults = defaults }
    func report(sets: [WorkoutSetRecord], exercise: ExerciseCounter.Exercise) throws -> WorkoutProgressReport {
        let bridge = WorkoutProgressBridge()
        let evidence = try sets.map { record in
            let input = String(decoding: try JSONEncoder().encode(record.progressInput), as: UTF8.self)
            return try JSONDecoder().decode(WorkoutProgressEvidence.self, from: Data(try bridge.evidenceJson(recordJson: input).utf8))
        }
        return try report(evidence: evidence, exercise: exercise)
    }
    func report(evidence: [WorkoutProgressEvidence], exercise: ExerciseCounter.Exercise) throws -> WorkoutProgressReport {
        let history = String(decoding: try JSONEncoder().encode(evidence), as: UTF8.self)
        let key = "workoutGoal.\(exercise.rawValue)"
        var goal = defaults.string(forKey: key)
        let bridge = WorkoutProgressBridge()
        if goal == nil {
            let initial = try decode(bridge.reportEvidenceJson(evidenceJson: history, exercise: exercise.rawValue, goalJson: nil))
            goal = try bridge.goalJson(exercise: exercise.rawValue, metric: initial.goal.metric, target: initial.goal.target,
                createdAtEpochMilliseconds: Int64(Date().timeIntervalSince1970 * 1000))
            defaults.set(goal, forKey: key)
        }
        return try decode(bridge.reportEvidenceJson(evidenceJson: history, exercise: exercise.rawValue, goalJson: goal))
    }
    func setGoal(exercise: ExerciseCounter.Exercise, metric: String, target: Double) throws {
        let value = try WorkoutProgressBridge().goalJson(exercise: exercise.rawValue, metric: metric, target: target,
            createdAtEpochMilliseconds: Int64(Date().timeIntervalSince1970 * 1000))
        defaults.set(value, forKey: "workoutGoal.\(exercise.rawValue)")
    }
    private func decode(_ json: String) throws -> WorkoutProgressReport {
        try JSONDecoder().decode(WorkoutProgressReport.self, from: Data(json.utf8))
    }
}
