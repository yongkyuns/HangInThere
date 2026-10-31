import XCTest
@testable import HangInThere

@MainActor final class WorkoutReminderTests: XCTestCase {
    private var calendar: Calendar {
        var result = Calendar(identifier: .gregorian); result.timeZone = TimeZone(identifier: "America/Toronto")!; return result
    }
    private func date(_ day: String, hour: Int = 0) -> Date {
        calendar.date(bySettingHour: hour, minute: 0, second: 0, of: WorkoutCoach.date(day, calendar: calendar)!)!
    }
    private func suite() -> UserDefaults {
        let name = "reminder-test-\(UUID())"
        addTeardownBlock { UserDefaults.standard.removePersistentDomain(forName: name) }
        return UserDefaults(suiteName: name)!
    }
    func testPlanSkipsPastTimesAndDeduplicatesMovedSessions() {
        var profile = WorkoutCoachProfile(); profile.weekdays = [5, 6]
        profile.movedDays = ["2026-10-29": "2026-10-30"]
        let settings = WorkoutReminderSettings(enabled: true, hour: 18, minute: 15)
        let plan = WorkoutReminderPlan.occurrences(profile: profile, settings: settings, now: date("2026-10-29", hour: 19), calendar: calendar)
        XCTAssertEqual(plan.count, 60)
        XCTAssertEqual(plan.first?.id, "workout-reminder.2026-10-30")
        XCTAssertEqual(plan.first?.components.hour, 18)
        XCTAssertEqual(plan.first?.components.minute, 15)
        XCTAssertNil(plan.first?.components.timeZone, "Notifications use local wall-clock time")
        XCTAssertEqual(Set(plan.map(\.id)).count, plan.count)
        XCTAssertFalse(plan.contains { $0.id.hasSuffix("2026-10-29") })
        XCTAssertTrue(WorkoutReminderPlan.occurrences(profile: profile, settings: .init(), now: date("2026-10-29"), calendar: calendar).isEmpty)
        XCTAssertTrue(WorkoutReminderPlan.occurrences(profile: nil, settings: settings).isEmpty)
    }
    func testDaylightSavingUsesOneReminderAtTheAdjustedLocalTime() {
        var profile = WorkoutCoachProfile(); profile.weekdays = [1]
        let spring = WorkoutReminderPlan.occurrences(profile: profile, settings: .init(enabled: true, hour: 2, minute: 30), now: date("2026-03-07"), calendar: calendar)
        XCTAssertEqual(spring.first?.components.day, 8)
        XCTAssertEqual(spring.first?.components.hour, 3)
        XCTAssertEqual(spring.first?.components.minute, 30)
        let fall = WorkoutReminderPlan.occurrences(profile: profile, settings: .init(enabled: true, hour: 1, minute: 30), now: date("2026-10-31"), calendar: calendar)
        XCTAssertEqual(fall.first?.components.day, 1)
        XCTAssertEqual(fall.first.map { calendar.timeZone.secondsFromGMT(for: $0.date) }, -14400)
        XCTAssertEqual(fall.filter { $0.components.month == 11 && $0.components.day == 1 }.count, 1)
    }
    func testPermissionIsRequestedOnlyByExplicitEnableAndDeniedPermissionDoesNotEnable() async {
        let center = ReminderCenterStub(status: .notDetermined, grantsPermission: false)
        let service = WorkoutReminders(center: center, defaults: suite())
        await service.refresh(profile: WorkoutCoachProfile())
        var requests = await center.permissionRequests
        XCTAssertEqual(requests, 0)
        XCTAssertFalse(service.settings.enabled)
        await service.setEnabled(true, profile: WorkoutCoachProfile())
        requests = await center.permissionRequests
        XCTAssertEqual(requests, 1)
        XCTAssertFalse(service.settings.enabled)
        XCTAssertEqual(service.authorization, .denied)
        XCTAssertNotNil(service.error)
        let pending = await center.pendingIdentifiers()
        XCTAssertTrue(pending.isEmpty)
        await center.setStatus(.allowed)
        await service.refresh(profile: WorkoutCoachProfile())
        XCTAssertNil(service.error)
        XCTAssertFalse(service.settings.enabled, "Changing system permission must not opt in automatically")
    }
    func testEnableEditReloadAndDisableReplaceOnlyOwnedReminders() async {
        let center = ReminderCenterStub(status: .notDetermined, grantsPermission: true)
        await center.seed("another-feature")
        let defaults = suite()
        let service = WorkoutReminders(center: center, defaults: defaults)
        var profile = WorkoutCoachProfile(); profile.weekdays = [2]
        await service.setEnabled(true, profile: profile)
        XCTAssertTrue(service.settings.enabled)
        var requests = await center.permissionRequests
        XCTAssertEqual(requests, 1)
        var pending = await center.pendingIdentifiers()
        XCTAssertEqual(pending.count, 61)
        await service.setTime(hour: 9, minute: 45, profile: profile)
        var scheduled = await center.occurrences
        XCTAssertEqual(Set(scheduled.values.map { $0.components.hour }), [9])
        XCTAssertEqual(Set(scheduled.values.map { $0.components.minute }), [45])
        let reloaded = WorkoutReminders(center: center, defaults: defaults)
        XCTAssertEqual(reloaded.settings, service.settings)
        await reloaded.refresh(profile: profile)
        requests = await center.permissionRequests
        XCTAssertEqual(requests, 1)
        profile.weekdays = [6]
        await reloaded.refresh(profile: profile)
        scheduled = await center.occurrences
        XCTAssertTrue(scheduled.values.allSatisfy { calendar.component(.weekday, from: $0.date) == 6 })
        await reloaded.setEnabled(false, profile: profile)
        pending = await center.pendingIdentifiers()
        XCTAssertEqual(pending, ["another-feature"])
        XCTAssertNil(reloaded.nextReminder)
    }
    func testRevokedPermissionClearsPendingWithoutPromptingAgain() async {
        let center = ReminderCenterStub(); let service = WorkoutReminders(center: center, defaults: suite())
        await service.setEnabled(true, profile: WorkoutCoachProfile())
        await center.setStatus(.denied)
        await service.refresh(profile: WorkoutCoachProfile())
        let pending = await center.pendingIdentifiers(), requests = await center.permissionRequests
        XCTAssertTrue(pending.isEmpty)
        XCTAssertEqual(requests, 0)
        XCTAssertNil(service.nextReminder)
        XCTAssertNotNil(service.error)
    }
    func testDisableDuringAnInFlightAddCannotLeaveStaleNotifications() async {
        let center = ReminderCenterStub(pauseFirstAdd: true)
        let service = WorkoutReminders(center: center, defaults: suite())
        let enabling = Task { await service.setEnabled(true, profile: WorkoutCoachProfile()) }
        await center.waitForPausedAdd()
        await service.setEnabled(false, profile: WorkoutCoachProfile())
        await center.resumeAdd()
        await enabling.value
        let pending = await center.pendingIdentifiers()
        XCTAssertTrue(pending.isEmpty)
        XCTAssertFalse(service.settings.enabled)
        XCTAssertFalse(service.isUpdating)
    }
    func testSchedulingFailureClearsPartialRequestsAndCanBeRetried() async {
        let center = ReminderCenterStub(failAdds: true)
        let service = WorkoutReminders(center: center, defaults: suite())
        await service.setEnabled(true, profile: WorkoutCoachProfile())
        var pending = await center.pendingIdentifiers()
        XCTAssertTrue(pending.isEmpty)
        XCTAssertNotNil(service.error)
        await center.setFailAdds(false)
        await service.refresh(profile: WorkoutCoachProfile())
        pending = await center.pendingIdentifiers()
        XCTAssertEqual(pending.count, 60)
        XCTAssertNil(service.error)
    }
}

private actor ReminderCenterStub: WorkoutReminderNotificationCenter {
    var status: WorkoutReminderAuthorization
    let grantsPermission: Bool
    var permissionRequests = 0
    var occurrences: [String: WorkoutReminderOccurrence] = [:]
    private var foreign: [String] = []
    private var pauseFirstAdd: Bool
    private var paused: CheckedContinuation<Void, Never>?
    private var started: CheckedContinuation<Void, Never>?
    private var failAdds: Bool
    init(status: WorkoutReminderAuthorization = .allowed, grantsPermission: Bool = true, pauseFirstAdd: Bool = false, failAdds: Bool = false) {
        self.status = status; self.grantsPermission = grantsPermission; self.pauseFirstAdd = pauseFirstAdd; self.failAdds = failAdds
    }
    func authorization() -> WorkoutReminderAuthorization { status }
    func requestPermission() -> Bool { permissionRequests += 1; status = grantsPermission ? .allowed : .denied; return grantsPermission }
    func pendingIdentifiers() -> [String] { foreign + occurrences.keys.sorted() }
    func removePending(_ ids: [String]) { for id in ids { occurrences[id] = nil }; foreign.removeAll { ids.contains($0) } }
    func add(_ occurrence: WorkoutReminderOccurrence) async throws {
        if pauseFirstAdd {
            pauseFirstAdd = false
            await withCheckedContinuation { continuation in paused = continuation; started?.resume(); started = nil }
        }
        if failAdds { throw CocoaError(.fileWriteUnknown) }
        occurrences[occurrence.id] = occurrence
    }
    func seed(_ id: String) { foreign.append(id) }
    func setStatus(_ value: WorkoutReminderAuthorization) { status = value }
    func setFailAdds(_ value: Bool) { failAdds = value }
    func waitForPausedAdd() async { if paused == nil { await withCheckedContinuation { started = $0 } } }
    func resumeAdd() { paused?.resume(); paused = nil }
}
