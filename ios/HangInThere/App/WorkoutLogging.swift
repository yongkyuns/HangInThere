import Foundation
import WorkoutCore

extension Notification.Name { static let workoutLogChanged = Notification.Name("dev.hanginthere.workout-log-changed") }

extension WorkoutRecordStore {
    func workoutLogs() throws -> [LoggedWorkout] {
        struct State: Decodable { var workoutLogs: [LoggedWorkout] = [] }
        return try JSONDecoder().decode(State.self, from: Data(libraryState().utf8)).workoutLogs
            .sorted { $0.epochMilliseconds > $1.epochMilliseconds }
    }
    @discardableResult func saveWorkoutLog(_ value: LoggedWorkout, updatingTimestamp: Bool = false) throws -> LoggedWorkout {
        let command: String
        if updatingTimestamp {
            command = try WorkoutLogBridge().saveCommandJson(logJson: String(decoding: JSONEncoder().encode(value), as: UTF8.self),
                now: Int64(Date().timeIntervalSince1970 * 1000))
        } else {
            command = String(decoding: try JSONSerialization.data(withJSONObject: ["action": "saveWorkoutLog",
                "workoutLog": JSONSerialization.jsonObject(with: JSONEncoder().encode(value)), "atEpochMilliseconds": value.updatedAtEpochMilliseconds]), as: UTF8.self)
        }
        _ = try libraryCommand(command)
        struct Saved: Decodable { let workoutLog: LoggedWorkout }
        let next = try JSONDecoder().decode(Saved.self, from: Data(command.utf8)).workoutLog
        NotificationCenter.default.post(name: .workoutLogChanged, object: nil)
        return next
    }
    func deleteWorkoutLog(id: String) throws {
        let command: [String: Any] = ["action": "deleteWorkoutLog", "id": id, "atEpochMilliseconds": Int64(Date().timeIntervalSince1970 * 1000)]
        _ = try libraryCommand(String(decoding: JSONSerialization.data(withJSONObject: command), as: UTF8.self))
        NotificationCenter.default.post(name: .workoutLogChanged, object: nil)
    }
    func linkCameraSet(_ record: WorkoutSetRecord, to log: LoggedWorkout, load: Double? = nil, unit: String = "lb") throws {
        try save(record)
        if try !workoutLogs().contains(where: { $0.id == log.id }) { try saveWorkoutLog(log) }
        let command = try WorkoutLogBridge().cameraLinkCommandJson(stateJson: libraryState(), setId: record.id, workoutId: log.id,
            load: load.map { KotlinDouble(double: $0) }, unit: unit, now: Int64(Date().timeIntervalSince1970 * 1000))
        _ = try libraryCommand(command)
        NotificationCenter.default.post(name: .workoutLogChanged, object: nil)
    }
    func workoutSummary(id: String) throws -> JourneyWorkoutSummary? {
        let json = try WorkoutJourneyBridge().workoutJson(stateJson: libraryState(), workoutId: id,
            nowEpochMilliseconds: Int64(Date().timeIntervalSince1970 * 1000))
        return try JSONDecoder().decode(JourneyWorkoutSummary?.self, from: Data(json.utf8))
    }
}
