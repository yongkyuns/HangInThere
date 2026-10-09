package dev.hanginthere.android

import dev.hanginthere.android.session.ReplayOutcome
import dev.hanginthere.core.*
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class ReplayOutcomeTest {
    @Test fun earnedMovementBeforeSceneInterruptionIsRetainedButIncompleteEvenAtEof() {
        val runtime = WorkoutRuntime()
        runtime.session.start(ExerciseCounter.Exercise.pullUp, ArmMeasurement.Side.left)
        val edge = BarSegment(Point2D(100.0, 500.0), Point2D(900.0, 500.0))
        // Analytic arm chains exercise the real counter; this is not a native pose accuracy fixture.
        listOf(0.0 to 170.0, 0.15 to 170.0, 0.3 to 80.0, 0.45 to 80.0).forEach { (time, angle) ->
            val halfAngle = angle * PI / 360; val rise = 120 * sin(halfAngle)
            val points = listOf(Point2D(500.0, 500 + 2 * rise), Point2D(500 + 120 * cos(halfAngle), 500 + rise), Point2D(500.0, 500.0))
            val pose = PoseResult(PresentationTime((time * 1000).roundToLong(), 1000), ImageSize(1000.0, 1000.0),
                listOf(PoseObservation(ArmMeasurement.Side.left.joints.zip(points).map { (joint, point) -> Landmark(joint, point, 1.0) })), "analytic regression", 0)
            runtime.session.consume(pose, edge)
        }
        assertEquals(1, runtime.session.observedMovements)
        runtime.interrupt(LiveSetSession.EndReason.sceneShifted)
        assertEquals(1, runtime.session.observedMovements)
        // Both a ViewModel early stop and a decoder that continues to EOF must reject completion.
        listOf(false, true).forEach { sourceEnded ->
            val result = ReplayOutcome.atEnd(runtime.session.analyzedFrames, true, sourceEnded,
                runtime.snapshot.setupIssue, runtime.session.endReason)
            assertEquals(ReplayOutcome.Kind.interrupted, result.kind)
            assertTrue(result.message!!.contains("camera view changed"))
        }
    }
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
