import SwiftUI

@main @MainActor
struct HangInThereApp: App {
    var body: some Scene {
        WindowGroup { BrandedRootView() }
    }
}

private struct BrandedRootView: View {
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
        .task {
            await Task.yield()
            // Start immediately; the entrance neither blocks controls nor waits on a timer.
            withAnimation(reduceMotion ? nil : .easeOut(duration: 0.45)) { showsBrand = false }
        }
    }
    private func refreshReminders() async {
        await WorkoutReminders.shared.refresh(profile: WorkoutCoachPreferences.shared.profile)
    }

}
