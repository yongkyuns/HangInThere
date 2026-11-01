import Foundation
import Testing
@testable import HangInThere

struct WorkoutLoggingTests {
    @Test func loggedCountsSeedATargetWithoutClaimingMeasuredProgression() {
        let now = Date()
        let log = LoggedWorkout(epochMilliseconds: Int64(now.addingTimeInterval(-86400).timeIntervalSince1970 * 1000),
            sets: [.init(exercise: "Pull-ups", reps: 8, load: 10)])
        let target = WorkoutCoach.recommendation(exercise: .pullUp, profile: .init(), history: [], loggedHistory: [log], now: now)
        #expect(target.reps == 8); #expect(target.source == nil); #expect(target.reason.contains("logged"))
    }
    @Test func prefillUsesLatestMatchingExerciseAndPreservesUnits() {
        let old = LoggedWorkout(epochMilliseconds: 1000, sets: [.init(exercise: "Bench press", reps: 4, load: 165)])
        let recent = LoggedWorkout(epochMilliseconds: 2000, sets: [.init(exercise: "Bench press", reps: 5, load: 75, unit: "kg"), .init(exercise: "EZ curls", reps: 7, load: 75.9)])
        let result = LoggedWorkout.lastSet(for: " bench PRESS ", history: [old, recent])
        #expect(result?.reps == 5); #expect(result?.unit == "kg"); #expect(result?.load == 75)
        #expect(LoggedWorkout.lastSet(for: "Rows", history: [old, recent]) == nil)
        #expect(LoggedWorkout.cameraExercise("Bench press") == nil)
        #expect(LoggedWorkout.cameraExercise("Pull-ups") == .pullUp)
    }
    @Test func onlyTimedSetsEstablishRestAcrossAlternatingExercises() {
        let first = LoggedWorkoutSet(exercise: "Bench press", reps: 5, load: 165, method: "tap", startedAtEpochMilliseconds: 1000, endedAtEpochMilliseconds: 11_000)
        let second = LoggedWorkoutSet(exercise: "EZ curls", reps: 7, load: 75.9, method: "tap", startedAtEpochMilliseconds: 251_000, endedAtEpochMilliseconds: 260_000)
        let typed = LoggedWorkoutSet(exercise: "Bench press", reps: 5, load: 165)
        let log = LoggedWorkout(sets: [first, second, typed])
        #expect(log.restBefore(first) == nil); #expect(log.restBefore(second) == 240)
        #expect(log.restBefore(typed) == nil); #expect(log.reps == 17)
    }
    @Test func logsSurviveReloadEditAndBackupWithoutBecomingMeasuredReps() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = WorkoutRecordStore(directory: root)
        var log = LoggedWorkout(sets: [.init(exercise: "Bench press", reps: 5, load: 165), .init(exercise: "EZ curls", reps: 7, load: 75.9)])
        try await store.saveWorkoutLog(log)
        let reloaded = try await WorkoutRecordStore(directory: root).workoutLogs()
        #expect(reloaded == [log])
        let summary = try #require(try await store.workoutSummary(id: log.id))
        #expect(summary.completedReps == 0); #expect(summary.totalReps == 12); #expect(summary.setCount == 2)
        log.sets[0].reps = 6; log.updatedAtEpochMilliseconds += 1
        try await store.saveWorkoutLog(log)
        let backup = try await store.libraryExport()
        let restored = WorkoutRecordStore(directory: root.appendingPathComponent("restored"))
        try await restored.libraryRestore(backup)
        #expect(try await restored.workoutLogs() == [log])
        try await restored.deleteWorkoutLog(id: log.id)
        try await restored.libraryRestore(backup)
        #expect(try await restored.workoutLogs().isEmpty)
    }
    @Test func activityGoalAndSummarySurviveNativeStorageAndManualEdits() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = WorkoutRecordStore(directory: root)
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        var log = LoggedWorkout(epochMilliseconds: now, sets: [.init(exercise: "Push-ups", reps: 5)])
        try await store.saveWorkoutLog(log)
        let command: [String: Any] = ["action": "activityGoal", "activityGoal": ["exercise": "Push-ups", "target": 10, "period": "week"], "atEpochMilliseconds": now]
        _ = try await store.libraryCommand(String(decoding: JSONSerialization.data(withJSONObject: command), as: UTF8.self))
        struct Goal: Decodable { let remaining: Int; let achieved: Bool }
        struct Activity: Decodable { let goals: [Goal] }
        struct Dashboard: Decodable { let activity: Activity }
        func report() async throws -> Activity {
            let json = try await store.libraryDashboard(key: nil, cursor: nil, now: now + 1000, offset: 0)
            return try JSONDecoder().decode(Dashboard.self, from: Data(json.utf8)).activity
        }
        #expect(try await report().goals.first?.remaining == 5)
        log.sets[0].reps = 10; log.updatedAtEpochMilliseconds += 1
        try await store.saveWorkoutLog(log)
        #expect(try await report().goals.first?.achieved == true)
    }
}
