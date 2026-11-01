#if os(iOS)
import Foundation
import Testing
@testable import HangInThere

@MainActor
struct WorkoutVoiceCoachTests {
    @Test func sharedCommandsDecodeWithoutSubstringOrNegativeWeightTriggers() {
        #expect(WorkoutVoiceCommand.parse("please start dips") == .init(action: "start", exercise: "dip"))
        #expect(WorkoutVoiceCommand.parse("use twenty point five kilograms") == .init(action: "weight", load: 20.5, unit: "kg"))
        #expect(WorkoutVoiceCommand.parse("do not finish set") == nil)
        #expect(WorkoutVoiceCommand.parse("use -20 kg") == nil)
        #expect(WorkoutVoiceCommand.parse("use twenty") == nil)
        #expect(WorkoutVoiceCommand.parse("finish set", isFinal: false) == nil)
        #expect(WorkoutVoiceCommand.parse("finish set tomorrow", isFinal: true) == nil)
        #expect(WorkoutVoiceCommand.parse("start sit-ups") == .init(action: "start", exercise: "sitUp"))
    }
    @Test func commandsRouteOnlyToVisibleWorkoutAndStopAfterLeaving() throws {
        let name = "voice-tests-\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: name))
        defer { defaults.removePersistentDomain(forName: name) }
        let coach = WorkoutVoiceCoach(defaults: defaults), parent = UUID(), counter = UUID()
        var logCommands = 0, counterCommands = 0
        #expect(!coach.enabled && !coach.listening && !coach.authorizing)
        coach.activate(parent) { _ in logCommands += 1; return "log" }
        coach.activate(counter) { _ in counterCommands += 1; return "counter" }
        #expect(coach.perform(.init(action: "undo")) == "counter")
        #expect(logCommands == 0 && counterCommands == 1)
        coach.deactivate(counter)
        #expect(coach.perform(.init(action: "start")) == "log")
        #expect(logCommands == 1)
        coach.enabled = true
        coach.perform(.init(action: "mute"))
        #expect(!coach.enabled)
        coach.deactivate(parent)
        #expect(coach.perform(.init(action: "finish")) == nil)
        #expect(!coach.listening && coach.status == nil)
        #expect(!coach.microphoneEnabled && !coach.authorizing)
        #expect(logCommands == 1 && counterCommands == 1)
    }
    @Test func delayedSpeechPermissionCannotOpenAMicrophoneInAnotherScreen() async throws {
        var pending: CheckedContinuation<Bool, Never>?
        let coach = WorkoutVoiceCoach(authorizeSpeech: {
            await withCheckedContinuation { pending = $0 }
        })
        coach.activate(UUID()) { _ in nil }
        let task = Task { await coach.toggleListening() }
        for _ in 0..<100 where pending == nil { await Task.yield() }
        let permission = try #require(pending)
        #expect(coach.authorizing)
        coach.activate(UUID()) { _ in nil }
        permission.resume(returning: true)
        await task.value
        #expect(!coach.authorizing && !coach.microphoneEnabled && !coach.listening)
    }
    @Test func deniedSpeechPermissionLeavesManualControlsAvailable() async {
        let coach = WorkoutVoiceCoach(authorizeSpeech: { false })
        var finishes = 0
        coach.activate(UUID()) { _ in finishes += 1; return nil }
        await coach.toggleListening()
        #expect(!coach.authorizing && !coach.microphoneEnabled)
        #expect(coach.status?.contains("speech access") == true)
        coach.perform(.init(action: "finish"))
        #expect(finishes == 1)
    }
}
#endif
