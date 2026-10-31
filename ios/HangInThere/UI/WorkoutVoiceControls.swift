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
                    else { Image(systemName: coach.listening ? "mic.fill" : "mic.slash") }
                }.frame(width: 44, height: 44)
            }.foregroundStyle(coach.listening ? Color.red : .primary)
                .accessibilityLabel(coach.listening || coach.authorizing ? "Stop listening" : "Listen for workout commands")
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
    let handler: (WorkoutVoiceCommand) -> String?
    func body(content: Content) -> some View {
        content.onAppear { WorkoutVoiceCoach.shared.activate(id, handler: handler) }
            .onDisappear { WorkoutVoiceCoach.shared.deactivate(id) }
    }
}
extension View {
    func workoutVoiceContext(_ handler: @escaping (WorkoutVoiceCommand) -> String?) -> some View {
        modifier(WorkoutVoiceContext(handler: handler))
    }
}
