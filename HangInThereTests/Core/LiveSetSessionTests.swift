import Foundation
import Testing
@testable import HangInThere

struct LiveSetSessionTests {
    @Test func livePullUpUsesProductionCounterAndSourceTime() {
        var session = LiveSetSession()
        session.start(exercise: .pullUp, side: .left)

        feed(&session, [(10.0, 170), (10.15, 170), (10.30, 80), (10.45, 80)])
        #expect(session.phase == .running)
        #expect(session.observedMovements == 1)
        #expect(session.movementTimes.count == 1)
        #expect(abs((session.movementTimes.first ?? -1) - 0.45) < 1e-9)
        #expect(session.trackingCoverage == 1)

        session.finish()
        #expect(session.phase == .finished)
        #expect(session.endReason == .manual)
        #expect(abs(session.durationSeconds - 0.45) < 1e-9)
        #expect(session.counter.phase == .finished)
    }

    @Test func unusableLiveFrameLowersCoverageAndInterruptsAttempt() {
        var session = LiveSetSession()
        session.start(exercise: .pullUp, side: .left)

        feed(&session, [(0.0, 170), (0.15, 170), (0.30, 120)])
        session.consume(
            ExerciseCounterTests.pose(0.45, degrees: 80, confidence: 0.1),
            referenceEdge: ExerciseCounterTests.referenceEdge
        )

        #expect(session.analyzedFrames == 4)
        #expect(session.usableFrames == 3)
        #expect(abs((session.trackingCoverage ?? -1) - 0.75) < 1e-9)
        #expect(session.counter.interruptedAttempts == 1)
        #expect(session.trackingIssue == "lowConfidence")
    }

    @Test func missingBarCannotSilentlyCount() {
        var session = LiveSetSession()
        session.start(exercise: .pullUp, side: .left)
        session.consume(ExerciseCounterTests.pose(0), referenceEdge: nil)
        session.consume(ExerciseCounterTests.pose(0.15), referenceEdge: nil)
        session.consume(ExerciseCounterTests.pose(0.30, degrees: 80), referenceEdge: nil)
        session.consume(ExerciseCounterTests.pose(0.45, degrees: 80), referenceEdge: nil)

        #expect(session.observedMovements == 0)
        #expect(session.trackingCoverage == 0.0)
        #expect(session.trackingIssue == "barReferenceUnavailable")
    }

    @Test func finishingIncompleteDipRecordsInterruption() {
        var session = LiveSetSession()
        session.start(exercise: .dip, side: .left)
        feed(&session, [(0.0, 170), (0.15, 170), (0.30, 80), (0.45, 80)])
        session.finish()

        #expect(session.phase == .finished)
        #expect(session.observedMovements == 0)
        #expect(session.counter.interruptedAttempts == 1)
        #expect(session.counter.lastEvent?.reason == "endOfInput")
    }

    @Test func interruptionEndsSetWithoutDiscardingValidMovements() {
        var session = LiveSetSession()
        session.start(exercise: .pullUp, side: .left)
        feed(&session, [(0.0, 170), (0.15, 170), (0.30, 80), (0.45, 80)])
        #expect(session.observedMovements == 1)

        session.interruptAndFinish(
            reason: "appInactive",
            endReason: .appInactive
        )

        #expect(session.phase == .finished)
        #expect(session.endReason == .appInactive)
        #expect(session.observedMovements == 1)
        #expect(session.counter.phase == .finished)
    }

    @Test func interruptionDuringAttemptRecordsInterruptedAttempt() {
        var session = LiveSetSession()
        session.start(exercise: .pullUp, side: .left)
        feed(&session, [(0.0, 170), (0.15, 170), (0.30, 120)])

        session.interruptAndFinish(
            reason: "cameraInterrupted",
            endReason: .cameraInterrupted
        )

        #expect(session.phase == .finished)
        #expect(session.endReason == .cameraInterrupted)
        #expect(session.observedMovements == 0)
        #expect(session.counter.interruptedAttempts == 1)
        #expect(session.counter.lastEvent?.reason == "cameraInterrupted")
    }

    @Test func phoneMovementIsAnExplicitInterruptionReason() {
        var session = LiveSetSession()
        session.start(exercise: .pullUp, side: .left)
        feed(&session, [(0.0, 170), (0.15, 170), (0.30, 80), (0.45, 80)])
        #expect(session.observedMovements == 1)

        session.interruptAndFinish(
            reason: "phoneMoved",
            endReason: .phoneMoved
        )

        #expect(session.phase == .finished)
        #expect(session.endReason == .phoneMoved)
        #expect(session.observedMovements == 1)
    }

    @Test func sceneShiftIsAnExplicitInterruptionReason() {
        var session = LiveSetSession()
        session.start(exercise: .pullUp, side: .left)
        feed(&session, [(0.0, 170), (0.15, 170), (0.30, 80), (0.45, 80)])
        #expect(session.observedMovements == 1)

        session.interruptAndFinish(
            reason: "sceneShifted",
            endReason: .sceneShifted
        )

        #expect(session.phase == .finished)
        #expect(session.endReason == .sceneShifted)
        #expect(session.observedMovements == 1)
    }

    @Test func nextSetPreservesSelectionButClearsResults() {
        var session = LiveSetSession()
        session.start(exercise: .dip, side: .right)
        feed(&session, [
            (0.0, 170), (0.15, 170), (0.30, 80), (0.45, 80),
            (0.60, 170), (0.75, 170)
        ])
        #expect(session.observedMovements == 1)
        session.finish()
        session.prepareNextSet()

        #expect(session.phase == .idle)
        #expect(session.exercise == .dip)
        #expect(session.side == .right)
        #expect(session.observedMovements == 0)
        #expect(session.movementTimes.isEmpty)
        #expect(session.trackingCoverage == nil)
        #expect(session.endReason == nil)
    }

    private func feed(_ session: inout LiveSetSession, _ samples: [(Double, Double)]) {
        for (time, angle) in samples {
            session.consume(
                ExerciseCounterTests.pose(
                    time,
                    degrees: angle,
                    exercise: session.exercise,
                    side: session.side
                ),
                referenceEdge: ExerciseCounterTests.referenceEdge
            )
        }
    }
}
