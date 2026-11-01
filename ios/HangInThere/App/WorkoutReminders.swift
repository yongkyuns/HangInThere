import Foundation
import Observation
import WorkoutCore
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
    var tipId: String? = nil
}

struct TrainingTipDeliverySettings: Codable, Equatable, Sendable {
    var workoutTipsEnabled = true
    var notificationsEnabled = false
    var daily = false
    var hour = 10
    var minute = 0
    var isValid: Bool { (0...23).contains(hour) && (0...59).contains(minute) }
}

enum TrainingTipNotificationPlan {
    static let prefix = "training-tip."
    static func occurrences(settings: TrainingTipDeliverySettings, now: Date = Date(), calendar: Calendar = .current) -> [WorkoutReminderOccurrence] {
        struct Item: Decodable { let id: String; let epochMilliseconds: Int64; let tipId: String }
        guard settings.isValid, let input = try? JSONEncoder().encode(settings),
              let json = try? WorkoutTipDeliveryBridge().notificationsJson(settingsJson: String(decoding: input, as: UTF8.self), now: Int64(now.timeIntervalSince1970 * 1000), timeZoneId: calendar.timeZone.identifier),
              let items = try? JSONDecoder().decode([Item].self, from: Data(json.utf8)) else { return [] }
        return items.map { item in
            let date = Date(timeIntervalSince1970: Double(item.epochMilliseconds) / 1000)
            return WorkoutReminderOccurrence(id: item.id, date: date, components: calendar.dateComponents([.year,.month,.day,.hour,.minute], from: date), tipId: item.tipId)
        }
    }
}

enum WorkoutReminderPlan {
    static let prefix = "workout-reminder."
    // Leave room for other notification types. Refill on launch and schedule edits.
    static let maximumCount = 40 // Reserve 18 requests for training tips, below the combined OS limit.
    static func occurrences(profile: WorkoutCoachProfile?, settings: WorkoutReminderSettings,
                            now: Date = Date(), calendar: Calendar = .current, completedDays: Set<Int64> = []) -> [WorkoutReminderOccurrence] {
        guard settings.enabled, settings.isValid, let profile, profile.isValid else { return [] }
        struct Scheduled: Decodable { let id: String; let epochMilliseconds: Int64 }
        let input: [String: Any] = ["enabled": settings.enabled, "hour": settings.hour, "minute": settings.minute,
            "weekdays": profile.weekdays, "movedDays": profile.movedDays]
        guard let data = try? JSONSerialization.data(withJSONObject: input),
              let completed = try? JSONEncoder().encode(completedDays),
              let json = try? WorkoutReminderBridge().occurrencesJson(scheduleJson: String(decoding: data, as: UTF8.self),
                now: Int64(now.timeIntervalSince1970 * 1000), timeZoneId: calendar.timeZone.identifier,
                completedDaysJson: String(decoding: completed, as: UTF8.self)),
              let items = try? JSONDecoder().decode([Scheduled].self, from: Data(json.utf8)) else { return [] }
        return items.prefix(maximumCount).map { item in
            let date = Date(timeIntervalSince1970: Double(item.epochMilliseconds) / 1000)
            return WorkoutReminderOccurrence(id: item.id, date: date, components: calendar.dateComponents([.year,.month,.day,.hour,.minute],from: date))
        }
    }
}

enum WorkoutReminderAuthorization: Sendable { case notDetermined, allowed, denied }
protocol WorkoutReminderNotificationCenter: Sendable {
    func authorization() async -> WorkoutReminderAuthorization
    func requestPermission() async throws -> Bool
    func pendingIdentifiers() async -> [String]
    func removePending(_ ids: [String]) async
    func add(_ occurrence: WorkoutReminderOccurrence) async throws
    func removeDeliveredTips() async
}
extension WorkoutReminderNotificationCenter { func removeDeliveredTips() async {} }

@MainActor @Observable final class WorkoutReminders {
    #if os(iOS)
    static let shared = WorkoutReminders(center: SystemWorkoutReminderCenter())
    #endif
    private(set) var settings: WorkoutReminderSettings
    private(set) var tipSettings: TrainingTipDeliverySettings
    private(set) var authorization = WorkoutReminderAuthorization.notDetermined
    private(set) var nextReminder: Date?
    private(set) var error: String?
    private(set) var isUpdating = false
    @ObservationIgnored private let center: any WorkoutReminderNotificationCenter
    @ObservationIgnored private let defaults: UserDefaults
    @ObservationIgnored private var desiredProfile: WorkoutCoachProfile?
    @ObservationIgnored private var revision = 0
    private let key = "workoutReminders.v1"
    private let tipKey = "trainingTipSettings.v1"
    @ObservationIgnored private var tipEditRevision = 0
    @ObservationIgnored private var completedDays: Set<Int64> = []

    init(center: any WorkoutReminderNotificationCenter, defaults: UserDefaults = .standard) {
        self.center = center; self.defaults = defaults
        if let data = defaults.data(forKey: key), let value = try? JSONDecoder().decode(WorkoutReminderSettings.self, from: data), value.isValid {
            settings = value
        } else { settings = WorkoutReminderSettings() }
        if let data = defaults.data(forKey: tipKey), let value = try? JSONDecoder().decode(TrainingTipDeliverySettings.self, from: data), value.isValid {
            tipSettings = value
        } else { tipSettings = TrainingTipDeliverySettings() }
    }
    func setTipSettings(_ value: TrainingTipDeliverySettings, profile: WorkoutCoachProfile?) async {
        guard value.isValid else { return }
        tipEditRevision += 1; let edit = tipEditRevision
        if value.notificationsEnabled && !tipSettings.notificationsEnabled {
            authorization = await center.authorization()
            if authorization == .notDetermined {
                do { authorization = try await center.requestPermission() ? .allowed : .denied }
                catch { self.error = "Couldn't enable tip notifications. Try again."; return }
            }
            guard edit == tipEditRevision else { return }
            guard authorization == .allowed else { error = "Notifications are off in Settings."; return }
        }
        tipSettings = value
        if let data = try? JSONEncoder().encode(value) { defaults.set(data, forKey: tipKey) }
        if !value.notificationsEnabled { await center.removeDeliveredTips() }
        error = nil
        await refresh(profile: profile)
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
    func refresh(profile: WorkoutCoachProfile?, completedDays: Set<Int64>? = nil) async {
        if let completedDays { self.completedDays = completedDays }
        desiredProfile = profile; revision += 1
        guard !isUpdating else { return }
        isUpdating = true
        while true {
            let current = revision
            await apply(profile: desiredProfile, settings: settings, tips: tipSettings, completed: self.completedDays)
            if current == revision { break }
        }
        isUpdating = false
    }
    private func clearPending() async {
        let ids = await center.pendingIdentifiers().filter { $0.hasPrefix(WorkoutReminderPlan.prefix) || $0.hasPrefix(TrainingTipNotificationPlan.prefix) }
        await center.removePending(ids)
        nextReminder = nil
    }
    private func apply(profile: WorkoutCoachProfile?, settings: WorkoutReminderSettings, tips: TrainingTipDeliverySettings, completed: Set<Int64>) async {
        await clearPending()
        guard settings.enabled || tips.notificationsEnabled else {
            if authorization == .denied {
                authorization = await center.authorization()
                if authorization == .allowed { error = nil }
            }
            return
        }
        authorization = await center.authorization()
        guard authorization == .allowed else { error = "Notifications are off in Settings."; return }
        let plan = WorkoutReminderPlan.occurrences(profile: profile, settings: settings, completedDays: completed)
        let tipPlan = TrainingTipNotificationPlan.occurrences(settings: tips)
        do {
            for item in plan + tipPlan { try await center.add(item) }
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
    func removeDeliveredTips() async {
        let ids = await center.deliveredNotifications().filter { $0.request.identifier.hasPrefix(TrainingTipNotificationPlan.prefix) }.map { $0.request.identifier }
        center.removeDeliveredNotifications(withIdentifiers: ids)
    }
    func add(_ occurrence: WorkoutReminderOccurrence) async throws {
        let content = UNMutableNotificationContent()
        content.title = "Time for your workout"
        content.body = "A little movement, at your pace. Your plan is ready."
        content.sound = .default
        content.threadIdentifier = "workout-reminders"
        if let tipId = occurrence.tipId, let tip = TrainingTips.catalog?.tips.first(where: { $0.id == tipId }) {
            content.title = "Training tip · " + tip.title
            content.body = tip.cue
            content.sound = nil
            content.threadIdentifier = "training-tips"
            content.userInfo = ["trainingTipId": tip.id]
        }
        let trigger = UNCalendarNotificationTrigger(dateMatching: occurrence.components, repeats: false)
        try await center.add(UNNotificationRequest(identifier: occurrence.id, content: content, trigger: trigger))
    }
}
#endif
