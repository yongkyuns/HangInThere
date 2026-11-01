import SwiftUI
import WorkoutCore
import UserNotifications
import Observation

@MainActor @Observable final class TrainingTipRoute {
    static let shared = TrainingTipRoute()
    var tip: TrainingTip?
}

final class HangNotificationDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        return true
    }
    // Foreground banners stay suppressed; the app supplies context-specific tips.
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions { [] }
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let id = response.notification.request.content.userInfo["trainingTipId"] as? String
        await MainActor.run {
            if let id { TrainingTipRoute.shared.tip = TrainingTips.catalog?.tips.first { $0.id == id } }
        }
    }
}

@main @MainActor
struct HangInThereApp: App {
    @UIApplicationDelegateAdaptor(HangNotificationDelegate.self) private var notificationDelegate
    var body: some Scene {
        WindowGroup { BrandedRootView() }
    }
}

private struct BrandedRootView: View {
    @State private var tipRoute = TrainingTipRoute.shared
    @State private var showsBrand = true
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        ZStack {
            ReplayView()
            if showsBrand { BrandLaunchView().transition(.opacity).zIndex(1) }
        }
        .tint(HangPalette(scheme).accent)
        .sheet(item: $tipRoute.tip) { tip in
            NavigationStack { WorkoutTrainingTipDetail(tip: tip).toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { tipRoute.tip = nil } } } }.hangTheme()
        }
        .onChange(of: scenePhase, initial: true) { _, phase in
            if phase == .active { Task {
                // Cleanup is best effort here; storage errors remain visible in the library.
                try? await WorkoutRecordStore.shared.maintainLibraryMedia()
                await refreshReminders()
            } }
        }
        .onChange(of: WorkoutCoachPreferences.shared.profile) { _, _ in
            Task { await refreshReminders() }
        }
        .onReceive(NotificationCenter.default.publisher(for: .NSSystemTimeZoneDidChange)) { _ in
            Task { await refreshReminders() }
        }
        .onReceive(NotificationCenter.default.publisher(for: .NSCalendarDayChanged)) { _ in
            Task { await refreshReminders() }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.significantTimeChangeNotification)) { _ in
            Task { await refreshReminders() }
        }
        .onReceive(NotificationCenter.default.publisher(for: .workoutActivityChanged).receive(on: RunLoop.main)) { _ in
            Task { await refreshReminders() }
        }
        .task {
            await Task.yield()
            // Start immediately; the entrance neither blocks controls nor waits on a timer.
            withAnimation(reduceMotion ? nil : .easeOut(duration: 0.45)) { showsBrand = false }
        }
    }
    private func refreshReminders() async {
        do {
            let state = try await WorkoutRecordStore.shared.libraryState()
            let json = try WorkoutActivityBridge().completedDaysJson(stateJson: state, now: Int64(Date().timeIntervalSince1970 * 1000), timeZoneId: TimeZone.current.identifier)
            let days = try JSONDecoder().decode(Set<Int64>.self, from: Data(json.utf8))
            await WorkoutReminders.shared.refresh(profile: WorkoutCoachPreferences.shared.profile, completedDays: days)
        } catch { } // Keep the existing schedule if workout storage cannot be read.
    }

}
