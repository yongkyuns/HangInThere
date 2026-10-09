package dev.hanginthere.android

import dev.hanginthere.android.session.ReplayOutcome
import dev.hanginthere.core.LiveSetSession
import dev.hanginthere.core.WorkoutRuntime
import dev.hanginthere.core.ExerciseCounter
import dev.hanginthere.core.ArmMeasurement
import org.junit.Assert.*
import org.junit.Test

class ReplayOutcomeTest {
    @Test fun analysisFailureClosesPriorRunningCounter() {
        val runtime = WorkoutRuntime()
        runtime.session.start(ExerciseCounter.Exercise.dip, ArmMeasurement.Side.left)
        val result = ReplayOutcome.analysisFailure(runtime, "Decoder failed")
        assertEquals(ReplayOutcome.Kind.analysisFailed, result.kind)
        assertEquals("Decoder failed", result.message)
        assertEquals(LiveSetSession.Phase.finished, runtime.snapshot.phase)
        assertEquals(LiveSetSession.EndReason.cameraFailure, runtime.session.endReason)
    }
    @Test fun noPersonAtEofRemainsSetupFailure() {
        val result = ReplayOutcome.atEnd(0, false, true, "framingIncomplete", null)
        assertEquals(ReplayOutcome.Kind.setupUnavailable, result.kind)
        assertTrue(result.message!!.contains("both arms and hands"))
    }
    @Test fun acquiredReferenceWithoutCountingIsNotSuccessfulZero() {
        val result = ReplayOutcome.atEnd(0, true, true, "sceneStabilityNeeded", null)
        assertEquals(ReplayOutcome.Kind.setupUnavailable, result.kind)
        assertTrue(result.message!!.contains("stable background"))
    }
    @Test fun sceneInterruptionIsNotFullSourceSuccess() {
        val result = ReplayOutcome.atEnd(25, true, false, "barReferenceNeeded", LiveSetSession.EndReason.sceneShifted)
        assertEquals(ReplayOutcome.Kind.interrupted, result.kind)
        assertTrue(result.message!!.contains("camera view changed"))
    }
    @Test fun completeAnalyzedStillCanHonestlyHaveZeroMovements() {
        val result = ReplayOutcome.atEnd(25, true, true, null, LiveSetSession.EndReason.manual)
        assertEquals(ReplayOutcome.Kind.complete, result.kind)
        assertNull(result.message)
    }
}
