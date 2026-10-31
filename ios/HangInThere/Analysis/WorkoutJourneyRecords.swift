import Foundation

// Read-only projections of WorkoutCore presentation policy; never persisted as
// replacement measurements. Color, layout and navigation remain native.
struct JourneyQuality: Codable, Equatable, Sendable {
    let level: String
    let title: String
    let explanation: String
}
struct JourneyCoaching: Codable, Equatable, Sendable {
    let title: String
    let message: String
}
struct JourneyRepSummary: Codable, Equatable, Identifiable, Sendable {
    let id: String
    let ordinal: Int
    let status: String
    let outwardSeconds: Double?
    let returnSeconds: Double?
    let quality: JourneyQuality
    let totalSeconds: Double?
}
struct JourneySetSummary: Codable, Equatable, Identifiable, Sendable {
    let id: String
    let sourceSetIds: [String]
    let exercise: ExerciseCounter.Exercise
    let epochMilliseconds: Int64?
    let completedReps: Int
    let reportedReps: Int
    let practiceAttempts: Int
    let holdSeconds: Double?
    let outwardSeconds: Double?
    let returnSeconds: Double?
    let excursionDegrees: Double?
    let coverage: Double?
    let quality: JourneyQuality
    let coaching: JourneyCoaching
    let reps: [JourneyRepSummary]
}
struct JourneyWorkoutSummary: Codable, Equatable, Identifiable, Sendable {
    let id: String
    let epochMilliseconds: Int64?
    let completedReps: Int
    let setCount: Int
    let exercises: [ExerciseCounter.Exercise]
    let quality: JourneyQuality
    let sets: [JourneySetSummary]
    var workoutLog: LoggedWorkout? = nil
    var totalReps: Int { completedReps + (workoutLog?.reps ?? 0) }
    var exerciseNames: [String] {
        (exercises.map(\.title) + (workoutLog?.exercises ?? [])).reduce(into: []) { if !$0.contains($1) { $0.append($1) } }
    }
}
struct JourneyPracticeDay: Codable, Equatable, Identifiable, Sendable {
    let day: Int64
    let workoutIds: [String]
    let completedReps: Int
    let quality: JourneyQuality?
    var loggedReps: Int? = nil
    var totalReps: Int { completedReps + (loggedReps ?? 0) }
    var id: Int64 { day }
}
struct JourneyOverview: Codable, Equatable, Sendable {
    let workouts: [JourneyWorkoutSummary]
    let days: [JourneyPracticeDay]
    let practiceDays: Int
    let completedReps: Int
    let workoutCount: Int
    let coaching: JourneyCoaching
    let policyVersion: Int
    let nextCursor: JourneyCursor?
    var loggedReps: Int? = nil
    var totalReps: Int { completedReps + (loggedReps ?? 0) }
}
struct JourneyCursor: Codable, Equatable, Sendable {
    let afterId: String
    let revision: Int64
    let day: Int64?
}
struct JourneyPage: Codable, Equatable, Sendable {
    let workouts: [JourneyWorkoutSummary]
    let nextCursor: JourneyCursor?
}
