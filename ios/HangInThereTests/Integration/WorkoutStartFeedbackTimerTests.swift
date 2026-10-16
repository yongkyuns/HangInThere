import Foundation
import Testing
@testable import HangInThere

@MainActor
struct WorkoutStartFeedbackTimerTests {
    @MainActor private final class Clock {
        var seconds = 10.0
    }

    @Test func cueExpiryDoesNotDependOnItsQueuedTaskGettingExecution() {
        let clock = Clock()
        let feedback = WorkoutAnalysisAdapter(feedbackClock: { clock.seconds })
        feedback.start(exercise: .pullUp)
        #expect(feedback.beginStartFeedback(isRunning: true))
        #expect(feedback.showsSetStarted)
        clock.seconds = 11.5
        #expect(feedback.showsSetStarted)
        #expect(!feedback.beginStartFeedback(isRunning: true))
        clock.seconds = 12
        // This synchronous actor turn has never let the timer task run.
        #expect(!feedback.showsSetStarted)
        _ = feedback.finish(reason: "disengaged")
    }

    @Test func expiredStartCueIsHiddenBeforeItsTaskRuns() {
        let feedback = WorkoutAnalysisAdapter()
        feedback.start(exercise: .dip)
        // No actor suspension: the timer task cannot have run yet. The shared
        // two-second UI budget has already elapsed at this supplied start time.
        #expect(feedback.beginStartFeedback(isRunning: true,
            nowSeconds: ProcessInfo.processInfo.systemUptime - 3))
        #expect(!feedback.showsSetStarted)
        _ = feedback.finish(reason: "disengaged")
    }

    @Test func freshCueFollowsItsSetAndCannotBeReannounced() {
        let feedback = WorkoutAnalysisAdapter()
        feedback.start(exercise: .pullUp)
        #expect(!feedback.beginStartFeedback(isRunning: false))
        #expect(!feedback.showsSetStarted)
        #expect(feedback.beginStartFeedback(isRunning: true))
        #expect(feedback.showsSetStarted)
        #expect(!feedback.beginStartFeedback(isRunning: true))
        _ = feedback.finish(reason: "disengaged")
        #expect(!feedback.showsSetStarted)
        #expect(!feedback.beginStartFeedback(isRunning: true))
        feedback.start(exercise: .dip)
        #expect(feedback.beginStartFeedback(isRunning: true))
        #expect(feedback.showsSetStarted)
        _ = feedback.finish(reason: "disengaged")
        #expect(!feedback.showsSetStarted)
    }
}
