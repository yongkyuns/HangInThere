import Foundation
import Observation
import WorkoutCore

struct WorkoutCoachProfile: Codable, Equatable, Sendable {
    enum Goal: String, Codable, CaseIterable, Sendable {
        case consistency, moreReps, firstRep
        var title: String {
            switch self { case .consistency: "Build a habit"; case .moreReps: "Do more reps"; case .firstRep: "My first rep" }
        }
        var symbol: String {
            switch self { case .consistency: "calendar"; case .moreReps: "arrow.up.right"; case .firstRep: "sparkles" }
        }
    }
    var goal: Goal = .consistency
    var weekdays = [2, 4, 6] // Calendar weekday values; localized labels in the view.
    var minutes = 25
    var exercises: [ExerciseCounter.Exercise] = [.pullUp, .dip]
    var movedDays: [String: String] = [:]
    var startedAtEpochMilliseconds = Int64(Date().timeIntervalSince1970 * 1000)
    var isValid: Bool {
        !weekdays.isEmpty && Set(weekdays).count == weekdays.count && weekdays.allSatisfy { (1...7).contains($0) } &&
        [15, 25, 40].contains(minutes) && !exercises.isEmpty && Set(exercises.map(\.rawValue)).count == exercises.count
    }
}

enum WorkoutCoachEffort: Int, CaseIterable, Sendable {
    case easy = 3, right = 6, hard = 9
    var title: String { switch self { case .easy: "Easy"; case .right: "About right"; case .hard: "Too hard" } }
    var symbol: String { switch self { case .easy: "sun.max"; case .right: "checkmark.circle"; case .hard: "battery.25percent" } }
    init?(rating: Int?) {
        guard let rating else { return nil }
        self = rating <= 3 ? .easy : rating >= 8 ? .hard : .right
    }
}

/// Read-only subset of the existing shared reviewed-history contract.
struct WorkoutCoachSet: Decodable, Sendable {
    let id: String
    let sourceSetId: String
    let exercise: ExerciseCounter.Exercise
    let measuredCompletedReps: Int
    let trackedAtEpochMilliseconds: Int64?
    let comparisonKey: String?
    let context: LibraryContext
    let trackingCoverage: Double?
    let endReason: String
    let elbowExcursionDegrees: Double?
    let meanReturnSeconds: Double?
    let exerciseCorrected: Bool
    let isMerged: Bool
    let countMethod: String?
    let countPolicyVersion: Int
    var gripIdentity: String? = nil
    var cohortKey: String? {
        guard let comparisonKey else { return nil }
        if exercise == .dip || !context.variation.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return comparisonKey }
        return gripIdentity.map { comparisonKey + "|" + $0 }
    }
    var effort: WorkoutCoachEffort? { WorkoutCoachEffort(rating: context.effort) }
    var usable: Bool {
        measuredCompletedReps > 0 && !exerciseCorrected && !isMerged &&
        ["manual", "disengaged", "endOfInput", "sourceEnded"].contains(endReason)
    }
}

struct WorkoutCoachRecommendation: Identifiable, Sendable {
    let exercise: ExerciseCounter.Exercise
    var reps: Int?
    var sets: Int
    var restSeconds: Int
    let reason: String
    let source: WorkoutCoachSet?
    var id: String { exercise.rawValue }
}

enum WorkoutCoach {
    static func dayKey(_ date: Date, calendar: Calendar = .current) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }
    static func date(_ key: String, calendar: Calendar = .current) -> Date? {
        let parts = key.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        guard let date = calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2])),
              dayKey(date, calendar: calendar) == key else { return nil }
        return calendar.startOfDay(for: date)
    }
    static func recordedDays(history: [WorkoutCoachSet], loggedHistory: [LoggedWorkout], calendar: Calendar = .current) -> Set<String> {
        let dates = history.compactMap(\.trackedAtEpochMilliseconds).map { Date(timeIntervalSince1970: Double($0) / 1000) } +
            loggedHistory.filter { !$0.sets.isEmpty }.map(\.date)
        return Set(dates.map { dayKey($0, calendar: calendar) })
    }
    static func schedule(profile: WorkoutCoachProfile, from now: Date, calendar: Calendar = .current) -> [Date] {
        let start = calendar.startOfDay(for: now)
        let regular = (-7..<14).compactMap { calendar.date(byAdding: .day, value: $0, to: start) }
            .filter { profile.weekdays.contains(calendar.component(.weekday, from: $0)) }
        let dates = regular.compactMap { day -> Date? in
            if let move = profile.movedDays[dayKey(day, calendar: calendar)] { return date(move, calendar: calendar) }
            return day
        } + profile.movedDays.values.compactMap { date($0, calendar: calendar) }
        return Array(Set(dates.filter { $0 >= start })).sorted().prefix(7).map { $0 }
    }
    static func move(_ profile: WorkoutCoachProfile, from source: Date, to destination: Date, calendar: Calendar = .current) -> WorkoutCoachProfile {
        var next = profile
        let from = dayKey(source, calendar: calendar), to = dayKey(destination, calendar: calendar)
        let originalDays = profile.movedDays.keys.filter { profile.movedDays[$0] == from }
        for original in originalDays { next.movedDays[original] = to }
        if originalDays.isEmpty || profile.weekdays.contains(calendar.component(.weekday, from: source)) { next.movedDays[from] = to }
        return next
    }
    static func recommendation(exercise: ExerciseCounter.Exercise, profile: WorkoutCoachProfile,
                               history: [WorkoutCoachSet], loggedHistory: [LoggedWorkout] = [], now: Date = Date(), calendar: Calendar = .current) -> WorkoutCoachRecommendation {
        let since = now.addingTimeInterval(-60 * 86400)
        let dated = history.filter {
            guard $0.exercise == exercise, let time = $0.trackedAtEpochMilliseconds else { return false }
            let date = Date(timeIntervalSince1970: Double(time) / 1000)
            return date <= now && date >= since
        }.sorted { ($0.trackedAtEpochMilliseconds ?? 0) > ($1.trackedAtEpochMilliseconds ?? 0) }
        let recent = dated.filter(\.usable)
        let sets = profile.minutes == 15 || profile.goal == .firstRep ? 2 : 3
        guard let latest = recent.first else {
            let datedLogs = loggedHistory.filter { $0.date >= since && $0.date <= now }
            if dated.isEmpty, let last = datedLogs.sorted(by: { $0.epochMilliseconds > $1.epochMilliseconds })
                .lazy.flatMap({ $0.sets.reversed() }).first(where: { LoggedWorkout.cameraExercise($0.exercise) == exercise && $0.reps > 0 }) {
                return .init(exercise: exercise, reps: last.reps, sets: 2, restSeconds: 120,
                    reason: "A starting target from your last logged set: \(last.reps) reps" +
                        (last.load.map { " at \($0.formatted()) \(last.unit)" } ?? "") + ". Repeat the same setup and adjust to how you feel.", source: nil)
            }
            return .init(exercise: exercise, reps: nil, sets: 2, restSeconds: dated.first?.effort == .hard ? 180 : 120,
                         reason: dated.first?.effort == .hard ? "Your last set felt too hard. Take more rest or finish for today. A clear recording can help set a target later." : "Comfortable practice first. A clear, uninterrupted set helps us suggest a rep target—no max test needed.", source: nil)
        }
        // An unknown setup may seed a single-set suggestion, never a progression trend.
        let comparable = Array((latest.cohortKey == nil ? [latest] : recent.filter { $0.cohortKey == latest.cohortKey }).prefix(12))
        let counts = comparable.prefix(6).map(\.measuredCompletedReps).sorted()
        var target = counts[counts.count / 2]
        var rest = 120
        var reason = latest.cohortKey == nil
            ? "A starting suggestion from your last set. Use the same assistance, load and grip; add setup details in History before comparing progress."
            : "Repeat your recent middle set in this setup. Your goal is \(profile.goal.title.lowercased())."
        if dated.first?.effort == .hard {
            let reference = dated.first?.usable == true ? (dated.first?.measuredCompletedReps ?? target) : target
            if reference <= 1 {
                return .init(exercise: exercise, reps: nil, sets: 2, restSeconds: 180,
                    reason: "Your last set felt too hard. Rest or finish for today. Comfortable practice can replace a numeric rep target.", source: latest)
            }
            target = reference - 1; rest = 180
            reason = "You rated your last set too hard. Ease the next target and take more rest, or finish for today."
        } else if profile.goal == .moreReps, latest.cohortKey != nil, dated.first?.id == latest.id {
            let days = Dictionary(grouping: comparable) { set in
                dayKey(Date(timeIntervalSince1970: Double(set.trackedAtEpochMilliseconds ?? 0) / 1000), calendar: calendar)
            }
            let lastDays = days.keys.sorted().suffix(2).compactMap { days[$0] }
            let all = lastDays.flatMap { $0 }
            let ranges = all.compactMap(\.elbowExcursionDegrees)
            let tempos = all.compactMap(\.meanReturnSeconds).filter { $0.isFinite && $0 > 0 }
            let paceChanged = tempos.count >= 2 && (tempos.max() ?? 0) > (tempos.min() ?? 1) * 1.5
            let similarRange = ranges.count == all.count && (ranges.min() ?? 0) >= 20 &&
                (ranges.max() ?? 0) <= (ranges.min() ?? 1) * 1.2
            if lastDays.count == 2 && lastDays.allSatisfy({ $0.count >= 2 }) &&
                all.allSatisfy({ $0.effort == .easy && $0.measuredCompletedReps >= target && ($0.trackingCoverage ?? 0) >= 0.8 }) && similarRange && !paceChanged {
                target += 1
                reason = "Two recent practice days felt easy with similar recorded range. Try one extra rep; you can keep the previous target instead."
            }
        }
        return .init(exercise: exercise, reps: min(1000, target), sets: sets, restSeconds: rest, reason: reason, source: latest)
    }
}

@MainActor @Observable final class WorkoutCoachPreferences {
    static let shared = WorkoutCoachPreferences()
    private(set) var profile: WorkoutCoachProfile?
    private(set) var restUntil: Date?
    private(set) var restingSetId: String?
    @ObservationIgnored private let defaults: UserDefaults
    private let key = "workoutCoachProfile.v1"
    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if let data = defaults.data(forKey: key), let value = try? JSONDecoder().decode(WorkoutCoachProfile.self, from: data), value.isValid { profile = value }
    }
    func save(_ value: WorkoutCoachProfile) throws {
        guard value.isValid else { throw CocoaError(.coderInvalidValue) }
        let data = try JSONEncoder().encode(value)
        defaults.set(data, forKey: key); profile = value
    }
    func startRest(setId: String, seconds: Int) {
        restingSetId = setId; restUntil = Date().addingTimeInterval(Double(max(0, seconds)))
    }
}

extension WorkoutRecordStore {
    func coachingHistory() throws -> [WorkoutCoachSet] {
        struct Page: Decodable { let sets: [WorkoutCoachSet] }
        let json = try WorkoutLibraryBridge().pageJson(stateJson: libraryState(), cursorJson: nil, limit: 100)
        var sets = try JSONDecoder().decode(Page.self, from: Data(json.utf8)).sets
        for index in sets.indices where sets[index].exercise == .pullUp && sets[index].context.variation.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            if let record = try libraryRecord(id: sets[index].sourceSetId) { sets[index].gripIdentity = try? coachingGripIdentity(record) }
        }
        return sets
    }
    func coachingEffort(id: String) throws -> Int? {
        guard let text = try libraryCorrection(id: id),
              let object = try JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any] else { return nil }
        return (object["context"] as? [String: Any])?["effort"] as? Int
    }
    func saveCoachingEffort(id: String, effort: WorkoutCoachEffort) throws {
        // Change just the athlete's effort rating, preserving corrections, grouping and setup.
        var correction: [String: Any] = [:]
        if let text = try libraryCorrection(id: id) {
            correction = try JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any] ?? [:]
        }
        var context = correction["context"] as? [String: Any] ?? [:]
        context["effort"] = effort.rawValue; correction["context"] = context
        let command: [String: Any] = ["action": "review", "setId": id, "correction": correction,
                                    "atEpochMilliseconds": Int64(Date().timeIntervalSince1970 * 1000)]
        _ = try libraryCommand(String(decoding: JSONSerialization.data(withJSONObject: command), as: UTF8.self))
    }
    func saveCoachPlan(_ items: [WorkoutCoachRecommendation], date: Date, calendar: Calendar = .current) throws {
        let localStart = calendar.startOfDay(for: date)
        let day = Int64(floor((localStart.timeIntervalSince1970 + Double(calendar.timeZone.secondsFromGMT(for: localStart))) / 86400))
        let planItems: [[String: Any]] = try items.compactMap { item in
            guard let reps = item.reps else { return nil }
            let context = item.source?.context ?? LibraryContext()
            var value: [String: Any] = ["id": "coach-" + item.id, "exercise": item.id, "metric": "completedReps", "target": reps, "sets": item.sets,
                                        "context": try JSONSerialization.jsonObject(with: JSONEncoder().encode(context))]
            if let source = item.source, let method = source.countMethod { value["countMethod"] = method; value["countPolicyVersion"] = source.countPolicyVersion }
            return value
        }
        guard !planItems.isEmpty else { return } // First-session calibration has no invented rep target.
        let existing = try JSONSerialization.jsonObject(with: Data(libraryState().utf8)) as? [String: Any]
        let id = "coach-" + WorkoutCoach.dayKey(date, calendar: calendar)
        // A linked historical plan is a record of the athlete's accepted workout.
        if (existing?["plans"] as? [[String: Any]])?.contains(where: { $0["id"] as? String == id && $0["workoutId"] is String }) == true { return }
        let plan: [String: Any] = ["id": id, "title": "Your coached practice", "scheduledDay": day, "items": planItems]
        _ = try libraryCommand(String(decoding: JSONSerialization.data(withJSONObject: ["action": "plan", "plan": plan,
                          "atEpochMilliseconds": Int64(Date().timeIntervalSince1970 * 1000)]), as: UTF8.self))
    }
}
