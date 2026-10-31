import XCTest
@testable import HangInThere

final class WorkoutCoachingTests: XCTestCase {
    private var calendar: Calendar {
        var result = Calendar(identifier: .gregorian); result.timeZone = TimeZone(identifier: "America/Toronto")!; return result
    }
    private var now: Date { WorkoutCoach.date("2026-10-29", calendar: calendar)!.addingTimeInterval(12 * 3600) }
    private func set(_ day: Int, _ ordinal: Int = 0, reps: Int = 5, key: String? = "same-setup", effort: Int? = 3,
                     coverage: Double = 1, range: Double? = 80, end: String = "manual", corrected: Bool = false, returnSeconds: Double = 1, grip: String? = "overhand:wide") -> WorkoutCoachSet {
        var context = LibraryContext(); context.effort = effort
        return WorkoutCoachSet(id: "set-\(day)-\(ordinal)", sourceSetId: "set-\(day)-\(ordinal)", exercise: .pullUp,
            measuredCompletedReps: reps, trackedAtEpochMilliseconds: Int64(now.addingTimeInterval(Double(-day * 86400 + ordinal)).timeIntervalSince1970 * 1000),
            comparisonKey: key, context: context, trackingCoverage: coverage, endReason: end, elbowExcursionDegrees: range,
            meanReturnSeconds: returnSeconds, exerciseCorrected: corrected, isMerged: false, countMethod: "overheadPullUpRelativeMotion", countPolicyVersion: 4, gripIdentity: grip)
    }
    private func recommend(_ history: [WorkoutCoachSet], goal: WorkoutCoachProfile.Goal = .moreReps) -> WorkoutCoachRecommendation {
        var profile = WorkoutCoachProfile(); profile.goal = goal
        return WorkoutCoach.recommendation(exercise: .pullUp, profile: profile, history: history, now: now, calendar: calendar)
    }
    func testColdStartAndUnreliableEvidenceNeverInventACapacity() {
        XCTAssertNil(recommend([]).reps)
        let bad = [set(1, coverage: 0.3, end: "trackingLost"), set(2, reps: 0), set(3, end: "trackingLost"), set(4, corrected: true), set(-1), set(61)]
        XCTAssertNil(recommend(bad).reps)
        XCTAssertEqual(recommend([]).sets, 2)
        XCTAssertEqual(recommend([set(1, coverage: 0.3)]).reps, 5, "Completed observations can seed a suggestion despite gaps elsewhere")
        XCTAssertEqual(recommend([set(1, coverage: 0.3), set(1, 1, coverage: 0.3), set(2), set(2, 1)]).reps, 5, "Sparse tracking must not establish progression")
    }
    func testProgressionNeedsTwoSeparateDaysAndComparableRange() {
        let repeatable = [set(1), set(1, 1), set(2), set(2, 1)]
        XCTAssertEqual(recommend(repeatable).reps, 6)
        XCTAssertEqual(recommend([set(1), set(1, 1), set(1, 2), set(1, 3)]).reps, 5)
        XCTAssertEqual(recommend([set(1), set(1, 1), set(2), set(2, 1, range: 40)]).reps, 5)
        XCTAssertEqual(recommend([set(1), set(1, 1), set(2), set(2, 1, effort: nil)]).reps, 5)
        XCTAssertEqual(recommend(repeatable, goal: .consistency).reps, 5)
        XCTAssertEqual(recommend([set(1), set(1, 1), set(2), set(2, 1, returnSeconds: 3)]).reps, 5)
    }
    func testUnknownOrDifferentSetupsDoNotShareAProgression() {
        XCTAssertEqual(recommend([set(1, key: nil), set(1, 1, reps: 9, key: nil), set(2), set(2, 1)]).reps, 9)
        XCTAssertEqual(recommend([set(1, reps: 3, key: "chin-up"), set(2, reps: 12, key: "pull-up")]).reps, 3)
        XCTAssertEqual(recommend([set(1), set(1, 1), set(2, reps: 12, grip: "underhand:narrow"), set(2, 1, reps: 12, grip: "underhand:narrow")]).reps, 5)
        XCTAssertEqual(recommend([set(1, reps: 3, grip: nil), set(2, reps: 12, grip: nil)]).reps, 3)
    }
    func testTooHardFeedbackChangesNextSetAndRest() {
        let result = recommend([set(1, reps: 5, effort: 9)])
        XCTAssertEqual(result.reps, 4)
        XCTAssertEqual(result.restSeconds, 180)
        XCTAssertNil(recommend([set(1, reps: 1, effort: 9)]).reps)
        let interrupted = recommend([set(0, reps: 0, effort: 9, coverage: 0.3, end: "trackingLost"), set(1), set(1, 1), set(2), set(2, 1)])
        XCTAssertEqual(interrupted.reps, 4, "Self-reported difficulty still matters when tracking fails")
        XCTAssertEqual(interrupted.restSeconds, 180)
    }
    func testRecordedDaysIncludeNonemptyManualLogsAlongsideCameraSets() {
        let yesterday = now.addingTimeInterval(-86400)
        let logged = LoggedWorkout(epochMilliseconds: Int64(yesterday.timeIntervalSince1970 * 1000),
            sets: [LoggedWorkoutSet(exercise: "Parallel-bar dip", reps: 5)])
        let empty = LoggedWorkout(epochMilliseconds: Int64(now.timeIntervalSince1970 * 1000))
        XCTAssertEqual(WorkoutCoach.recordedDays(history: [set(2)], loggedHistory: [logged, empty], calendar: calendar),
                       [WorkoutCoach.dayKey(yesterday, calendar: calendar), WorkoutCoach.dayKey(now.addingTimeInterval(-172800), calendar: calendar)])
        XCTAssertEqual(LoggedWorkout.cameraExercise(ExerciseCounter.Exercise.dip.title), .dip)
    }
    func testCalendarScheduleSurvivesDaylightSavingAndMovesOnlyChosenDay() throws {
        var profile = WorkoutCoachProfile(); profile.weekdays = [1, 3, 5]
        let start = try XCTUnwrap(WorkoutCoach.date("2026-10-31", calendar: calendar))
        let dates = WorkoutCoach.schedule(profile: profile, from: start, calendar: calendar)
        XCTAssertEqual(dates.prefix(3).map { WorkoutCoach.dayKey($0, calendar: calendar) }, ["2026-11-01", "2026-11-03", "2026-11-05"])
        profile.movedDays["2026-11-01"] = "2026-11-02"
        let moved = WorkoutCoach.schedule(profile: profile, from: start, calendar: calendar).map { WorkoutCoach.dayKey($0, calendar: calendar) }
        XCTAssertEqual(Array(moved.prefix(3)), ["2026-11-02", "2026-11-03", "2026-11-05"])
        profile = WorkoutCoach.move(profile, from: try XCTUnwrap(WorkoutCoach.date("2026-11-02", calendar: calendar)),
                                    to: try XCTUnwrap(WorkoutCoach.date("2026-11-04", calendar: calendar)), calendar: calendar)
        let movedAgain = WorkoutCoach.schedule(profile: profile, from: start, calendar: calendar).map { WorkoutCoach.dayKey($0, calendar: calendar) }
        XCTAssertFalse(movedAgain.contains("2026-11-02"))
        XCTAssertTrue(movedAgain.contains("2026-11-04"))
        XCTAssertNil(WorkoutCoach.date("2026-02-30", calendar: calendar))
    }
    @MainActor func testProfileReloadsAndEmptyAvailabilityIsRejected() throws {
        let name = "coach-test-\(UUID())"
        let suite = UserDefaults(suiteName: name)!
        defer { suite.removePersistentDomain(forName: name) }
        let store = WorkoutCoachPreferences(defaults: suite)
        XCTAssertNil(store.profile)
        var profile = WorkoutCoachProfile(); profile.weekdays = [3, 5]; profile.minutes = 15
        try store.save(profile)
        XCTAssertEqual(WorkoutCoachPreferences(defaults: suite).profile, profile)
        profile.weekdays = []
        XCTAssertThrowsError(try store.save(profile))
        XCTAssertEqual(store.profile?.weekdays, [3, 5])
    }
    func testFeedbackPreservesExistingReviewAndCannotResurrectDeletedSets() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = WorkoutRecordStore(directory: folder)
        let record = try JSONDecoder().decode(WorkoutSetRecord.self, from: Data("""
        {"id":"feedback-set","exercise":"pullUp","startedAt":0,"endedAt":10,"endReason":"manual","reps":[],"backend":"test","modelRevision":1,"schemaVersion":1,"lifecycleVersion":1}
        """.utf8))
        try await store.save(record)
        _ = try await store.libraryCommand("""
        {"action":"review","setId":"feedback-set","atEpochMilliseconds":1000,"correction":{"exercise":"dip","workoutId":"session","context":{"assistance":"band","assistanceLabel":"red","addedLoadKg":0,"equipment":"bar","cameraView":"front","variation":"underhand"}}}
        """)
        try await store.saveCoachingEffort(id: record.id, effort: .hard)
        let text = try await store.libraryCorrection(id: record.id)
        let object = try JSONSerialization.jsonObject(with: Data(try XCTUnwrap(text).utf8)) as! [String: Any]
        XCTAssertEqual(object["exercise"] as? String, "dip")
        XCTAssertEqual(object["workoutId"] as? String, "session")
        let context = object["context"] as! [String: Any]
        XCTAssertEqual(context["assistanceLabel"] as? String, "red")
        XCTAssertEqual(context["variation"] as? String, "underhand")
        XCTAssertEqual(context["effort"] as? Int, 9)
        _ = try await store.libraryCommand("{\"action\":\"delete\",\"setId\":\"feedback-set\",\"atEpochMilliseconds\":2000}")
        do { try await store.saveCoachingEffort(id: record.id, effort: .easy); XCTFail("Deleted evidence must stay deleted") } catch { }
    }
    func testSavedPlanRetainsTheCountingMethodAndDoesNotInventCalibrationTargets() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = WorkoutRecordStore(directory: folder)
        try await store.saveCoachPlan([recommend([])], date: now, calendar: calendar)
        var state = try await store.libraryState()
        var object = try JSONSerialization.jsonObject(with: Data(state.utf8)) as! [String: Any]
        XCTAssertTrue((object["plans"] as! [Any]).isEmpty)
        try await store.saveCoachPlan([recommend([set(1)])], date: now, calendar: calendar)
        state = try await store.libraryState(); object = try JSONSerialization.jsonObject(with: Data(state.utf8)) as! [String: Any]
        let plan = (object["plans"] as! [[String: Any]])[0]
        let item = (plan["items"] as! [[String: Any]])[0]
        XCTAssertEqual(item["countMethod"] as? String, "overheadPullUpRelativeMotion")
        XCTAssertEqual(item["countPolicyVersion"] as? Int, 4)
        var linked = plan; linked["workoutId"] = "existing-workout"
        _ = try await store.libraryCommand(String(decoding: JSONSerialization.data(withJSONObject:
            ["action": "plan", "plan": linked, "atEpochMilliseconds": Int64(now.timeIntervalSince1970 * 1000)]), as: UTF8.self))
        try await store.saveCoachPlan([recommend([set(1, reps: 20)])], date: now, calendar: calendar)
        state = try await store.libraryState(); object = try JSONSerialization.jsonObject(with: Data(state.utf8)) as! [String: Any]
        let preserved = (object["plans"] as! [[String: Any]])[0]
        XCTAssertEqual(preserved["workoutId"] as? String, "existing-workout")
        XCTAssertEqual((preserved["items"] as! [[String: Any]])[0]["target"] as? Int, 5)
        let transition = try XCTUnwrap(calendar.date(from: DateComponents(year: 2026, month: 11, day: 1, hour: 12)))
        try await store.saveCoachPlan([recommend([set(1)])], date: transition, calendar: calendar)
        state = try await store.libraryState(); object = try JSONSerialization.jsonObject(with: Data(state.utf8)) as! [String: Any]
        let dstPlan = try XCTUnwrap((object["plans"] as! [[String: Any]]).first { $0["id"] as? String == "coach-2026-11-01" })
        var utc = calendar; utc.timeZone = TimeZone(secondsFromGMT: 0)!
        let expectedDay = Int64(try XCTUnwrap(utc.date(from: DateComponents(year: 2026, month: 11, day: 1))).timeIntervalSince1970 / 86400)
        XCTAssertEqual(dstPlan["scheduledDay"] as? Int64, expectedDay)
    }
    func testGripCohortsRespectExistingHandAndWidthCorrections() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = WorkoutRecordStore(directory: folder)
        let metric: [String: Any] = ["minimum": 80, "maximum": 170, "observedSeconds": 2, "coverage": 1]
        let rep: [String: Any] = ["id": "grip-rep", "setId": "grip-set", "ordinal": 1, "exercise": "pullUp",
            "startedAt": 0, "extremeAt": 1, "acceptedAt": 1, "returnedAt": 2, "closedAt": 2, "closeReason": "returned", "outcome": "accepted",
            "left": metric, "right": metric, "usableDurationSeconds": 2, "samples": [], "curveTruncated": false, "countPolicyVersion": 8, "metricVersion": 1,
            "grip": ["left": "unknown", "right": "unknown", "width": "wide", "orientation": "overhand", "policyVersion": 4]]
        let data = try JSONSerialization.data(withJSONObject: ["id": "grip-set", "exercise": "pullUp", "startedAt": 0, "endedAt": 3,
            "endReason": "manual", "reps": [rep], "schemaVersion": 1, "lifecycleVersion": 1])
        let record = try JSONDecoder().decode(WorkoutSetRecord.self, from: data)
        let measured = try await store.coachingGripIdentity(record)
        XCTAssertEqual(measured, "overhand:wide")
        let corrections = WorkoutGripCorrectionStore(directory: folder.appendingPathComponent("GripCorrections"))
        try await corrections.correct(record, repId: nil, orientation: "underhand", width: "narrow")
        let corrected = try await store.coachingGripIdentity(record)
        XCTAssertEqual(corrected, "underhand:narrow")
    }
}
