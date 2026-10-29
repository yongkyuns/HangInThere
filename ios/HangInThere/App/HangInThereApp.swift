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

    var body: some View {
        ZStack {
            ReplayView()
            if showsBrand { BrandLaunchView().transition(.opacity).zIndex(1) }
        }
        .task {
            await Task.yield()
            // Start immediately; the entrance neither blocks controls nor waits on a timer.
            withAnimation(reduceMotion ? nil : .easeOut(duration: 0.45)) { showsBrand = false }
        }
    }
}
