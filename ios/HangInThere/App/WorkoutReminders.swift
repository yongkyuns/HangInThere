import Foundation
import Observation
#if os(iOS)
import UserNotifications
#endif

struct WorkoutReminderSettings: Codable, Equatable, Sendable {
    var enabled = false
    var hour = 18
    var minute = 0
    var isValid: Bool { (0...23).contains(hour) && (0...59).contains(minute) }
}

struct WorkoutReminderOccurrence: Equatable, Sendable {
    let id: String
    let date: Date
    let components: DateComponents
}

enum WorkoutReminderPlan {
    static let prefix = "workout-reminder."
    // Leave room for other notification types. Refill on launch and schedule edits.
    static let maximumCount = 60
    static func occurrences(profile: WorkoutCoachProfile?, settings: WorkoutReminderSettings,
                            now: Date = Date(), calendar: Calendar = .current) -> [WorkoutReminderOccurrence] {
        guard settings.enabled, settings.isValid, let profile, profile.isValid else { return [] }
        let start = calendar.startOfDay(for: now)
        let regular = (0..<730).compactMap { calendar.date(byAdding: .day, value: $0, to: start) }
            .filter { profile.weekdays.contains(calendar.component(.weekday, from: $0)) }
        let moved = regular.compactMap { day -> Date? in
            if let destination = profile.movedDays[WorkoutCoach.dayKey(day, calendar: calendar)] {
                return WorkoutCoach.date(destination, calendar: calendar)
            }
            return day
        } + profile.movedDays.values.compactMap { WorkoutCoach.date($0, calendar: calendar) }
        return Set(moved).sorted().compactMap { day in
            guard let fire = calendar.nextDate(after: day.addingTimeInterval(-1),
                                           matching: DateComponents(hour: settings.hour, minute: settings.minute, second: 0),
                                           matchingPolicy: .nextTimePreservingSmallerComponents, repeatedTimePolicy: .first), calendar.isDate(fire, inSameDayAs: day), fire > now else { return nil }
            // Floating local date components follow the phone's time zone.
            let components = calendar.dateComponents([.year, .month, .day, .hour, .minute], from: fire)
            return WorkoutReminderOccurrence(id: prefix + WorkoutCoach.dayKey(day, calendar: calendar), date: fire, components: components)
        }.prefix(maximumCount).map { $0 }
    }
}

enum WorkoutReminderAuthorization: Sendable { case notDetermined, allowed, denied }
protocol WorkoutReminderNotificationCenter: Sendable {
    func authorization() async -> WorkoutReminderAuthorization
    func requestPermission() async throws -> Bool
    func pendingIdentifiers() async -> [String]
    func removePending(_ ids: [String]) async
    func add(_ occurrence: WorkoutReminderOccurrence) async throws
}

@MainActor @Observable final class WorkoutReminders {
    #if os(iOS)
    static let shared = WorkoutReminders(center: SystemWorkoutReminderCenter())
    #endif
    private(set) var settings: WorkoutReminderSettings
    private(set) var authorization = WorkoutReminderAuthorization.notDetermined
    private(set) var nextReminder: Date?
    private(set) var error: String?
    private(set) var isUpdating = false
    @ObservationIgnored private let center: any WorkoutReminderNotificationCenter
    @ObservationIgnored private let defaults: UserDefaults
    @ObservationIgnored private var desiredProfile: WorkoutCoachProfile?
    @ObservationIgnored private var revision = 0
    private let key = "workoutReminders.v1"

    init(center: any WorkoutReminderNotificationCenter, defaults: UserDefaults = .standard) {
        self.center = center; self.defaults = defaults
        if let data = defaults.data(forKey: key), let value = try? JSONDecoder().decode(WorkoutReminderSettings.self, from: data), value.isValid {
            settings = value
        } else { settings = WorkoutReminderSettings() }
    }
    func setEnabled(_ enabled: Bool, profile: WorkoutCoachProfile?) async {
        if enabled {
            guard profile?.isValid == true else { error = "Choose your workout days first."; return }
            authorization = await center.authorization()
            if authorization == .notDetermined {
                do { authorization = try await center.requestPermission() ? .allowed : .denied }
                catch { self.error = "Couldn't enable reminders. Try again."; return }
            }
            guard authorization == .allowed else { error = "Notifications are off in Settings."; return }
        }
        settings.enabled = enabled; persist(); error = nil
        await refresh(profile: profile)
    }
    func setTime(hour: Int, minute: Int, profile: WorkoutCoachProfile?) async {
        guard (0...23).contains(hour), (0...59).contains(minute) else { return }
        settings.hour = hour; settings.minute = minute; persist()
        await refresh(profile: profile)
    }
    private func persist() {
        if let data = try? JSONEncoder().encode(settings) { defaults.set(data, forKey: key) }
    }
    // Serialize updates, coalescing edits made while the OS is handling a request.
    // An older add can never finish after a newer disable/remove operation.
    func refresh(profile: WorkoutCoachProfile?) async {
        desiredProfile = profile; revision += 1
        guard !isUpdating else { return }
        isUpdating = true
        while true {
            let current = revision
            await apply(profile: desiredProfile, settings: settings)
            if current == revision { break }
        }
        isUpdating = false
    }
    private func clearPending() async {
        let ids = await center.pendingIdentifiers().filter { $0.hasPrefix(WorkoutReminderPlan.prefix) }
        await center.removePending(ids)
        nextReminder = nil
    }
    private func apply(profile: WorkoutCoachProfile?, settings: WorkoutReminderSettings) async {
        await clearPending()
        guard settings.enabled else {
            if authorization == .denied {
                authorization = await center.authorization()
                if authorization == .allowed { error = nil }
            }
            return
        }
        authorization = await center.authorization()
        guard authorization == .allowed else { error = "Notifications are off in Settings."; return }
        let plan = WorkoutReminderPlan.occurrences(profile: profile, settings: settings)
        do {
            for item in plan { try await center.add(item) }
            nextReminder = plan.first?.date; error = nil
        } catch {
            await clearPending()
            self.error = "Couldn't schedule reminders. Try again."
        }
    }
}

#if os(iOS)
private struct SystemWorkoutReminderCenter: WorkoutReminderNotificationCenter {
    private var center: UNUserNotificationCenter { .current() }
    func authorization() async -> WorkoutReminderAuthorization {
        switch await center.notificationSettings().authorizationStatus {
        case .authorized, .provisional, .ephemeral: .allowed
        case .notDetermined: .notDetermined
        default: .denied
        }
    }
    func requestPermission() async throws -> Bool {
        try await center.requestAuthorization(options: [.alert, .sound])
    }
    func pendingIdentifiers() async -> [String] { await center.pendingNotificationRequests().map(\.identifier) }
    func removePending(_ ids: [String]) async { center.removePendingNotificationRequests(withIdentifiers: ids) }
    func add(_ occurrence: WorkoutReminderOccurrence) async throws {
        let content = UNMutableNotificationContent()
        content.title = "Time for your workout"
        content.body = "A little movement, at your pace. Your plan is ready."
        content.sound = .default
        content.threadIdentifier = "workout-reminders"
        let trigger = UNCalendarNotificationTrigger(dateMatching: occurrence.components, repeats: false)
        try await center.add(UNNotificationRequest(identifier: occurrence.id, content: content, trigger: trigger))
    }
}
#endif
