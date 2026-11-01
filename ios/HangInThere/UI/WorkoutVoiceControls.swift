import SwiftUI

struct WorkoutVoiceButtons: View {
    @State private var coach = WorkoutVoiceCoach.shared
    var body: some View {
        HStack(spacing: 4) {
            Button {
                coach.enabled.toggle()
                if coach.enabled { coach.speak("Voice coach on.") }
            } label: {
                Image(systemName: coach.enabled ? "speaker.wave.2.fill" : "speaker.slash")
                    .frame(width: 44, height: 44)
            }.accessibilityLabel(coach.enabled ? "Mute voice coach" : "Enable voice coach")
                .accessibilityIdentifier("toggleWorkoutVoice")
            Button { Task { await coach.toggleListening() } } label: {
                Group {
                    if coach.authorizing { ProgressView() }
                    else { Image(systemName: coach.microphoneEnabled ? "mic.fill" : "mic.slash") }
                }.frame(width: 44, height: 44)
            }.foregroundStyle(coach.microphoneEnabled ? Color.red : .primary)
                .accessibilityLabel(coach.microphoneEnabled || coach.authorizing ? "Stop listening" : "Listen for workout commands")
                .accessibilityHint("Try start set, finish set, undo rep, or use twenty kilograms.")
                .accessibilityIdentifier("toggleWorkoutListening")
        }.buttonStyle(.plain)
    }
}

struct WorkoutVoiceStatus: View {
    @State private var coach = WorkoutVoiceCoach.shared
    var body: some View {
        if let status = coach.status {
            Text(status).font(.footnote).lineLimit(2).fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("workoutVoiceStatus")
        } else if coach.listening {
            Label("Listening for workout commands", systemImage: "waveform").font(.footnote)
                .accessibilityIdentifier("workoutVoiceListening")
        }
    }
}

private struct WorkoutVoiceContext: ViewModifier {
    @State private var id = UUID()
    let active: Bool
    let handler: (WorkoutVoiceCommand) -> String?
    func body(content: Content) -> some View {
        content.onChange(of: active, initial: true) { _, enabled in
                if enabled { WorkoutVoiceCoach.shared.activate(id, handler: handler) }
                else { WorkoutVoiceCoach.shared.deactivate(id) }
            }
            .onDisappear { WorkoutVoiceCoach.shared.deactivate(id) }
    }
}
extension View {
    func workoutVoiceContext(active: Bool = true, _ handler: @escaping (WorkoutVoiceCommand) -> String?) -> some View {
        modifier(WorkoutVoiceContext(active: active, handler: handler))
    }
}
