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
        #expect(logCommands == 1 && counterCommands == 1)
    }
}
#endif
