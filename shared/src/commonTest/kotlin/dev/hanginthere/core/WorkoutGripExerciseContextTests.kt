package dev.hanginthere.core

import kotlin.test.*

class WorkoutGripExerciseContextTests {
    @Test fun readyIsSetupUntilTheCounterObservesMovement() {
        val context = WorkoutGripExerciseContext()
        for (i in 0..20) assertFalse(context.observe(i*.05, ExerciseCounter.Phase.ready, true, false).eligible)
        assertTrue(context.observe(1.1, ExerciseCounter.Phase.outbound, true, false).eligible)
        assertTrue(context.observe(1.2, ExerciseCounter.Phase.returning, true, false).eligible)
        assertTrue(context.observe(1.3, ExerciseCounter.Phase.ready, true, false).eligible)
    }
    @Test fun releaseAndLossClearBeforeAnyLaterHandInference() {
        for (kind in listOf("release", "tracking", "seeking", "finished")) {
            val context = WorkoutGripExerciseContext()
            val active = context.observe(0.0, ExerciseCounter.Phase.outbound, true, false)
            val revoked = context.observe(.1, when (kind) {
                "seeking" -> ExerciseCounter.Phase.seekingStart
                "finished" -> ExerciseCounter.Phase.finished
                else -> ExerciseCounter.Phase.returning
            }, kind != "tracking", kind == "release")
            assertFalse(revoked.eligible)
            assertTrue(revoked.epoch > active.epoch)
            assertNull(revoked.startedAt)
        }
    }
    @Test fun sourceGapsAndRegressedClocksCannotReuseOldExercise() {
        val context = WorkoutGripExerciseContext()
        context.observe(1.0, ExerciseCounter.Phase.outbound, true, false)
        assertFalse(context.observe(1.5, ExerciseCounter.Phase.ready, true, false).eligible)
        context.observe(1.6, ExerciseCounter.Phase.outbound, true, false)
        for (t in listOf(.1, .2, .3, Double.NaN)) {
            assertFalse(context.observe(t, ExerciseCounter.Phase.outbound, true, false).eligible)
        }
        assertFalse(context.observe(1.7, ExerciseCounter.Phase.ready, true, false).eligible)
    }
    @Test fun regraspHasANewCutoffAndDoesNotBorrowSetupTime() {
        val context = WorkoutGripExerciseContext()
        val first = context.observe(0.0, ExerciseCounter.Phase.outbound, true, false)
        context.observe(.1, ExerciseCounter.Phase.returning, true, true)
        context.observe(.2, ExerciseCounter.Phase.ready, true, false)
        val second = context.observe(.3, ExerciseCounter.Phase.outbound, true, false)
        assertEquals(.3, second.startedAt)
        assertTrue(second.epoch > first.epoch)
    }

    private val bar = BarSegment(Point2D(0.0,100.0),Point2D(1000.0,100.0))
    private fun pose(t:Double, wristY:Double=100.0, wristConfidence:Double=.9):PoseResult {
        val points = listOf(
            Landmark(PoseJoint.leftShoulder,Point2D(400.0,400.0),.9),
            Landmark(PoseJoint.leftElbow,Point2D(400.0,250.0),.9),
            Landmark(PoseJoint.leftWrist,Point2D(400.0,wristY),wristConfidence),
            Landmark(PoseJoint.rightShoulder,Point2D(600.0,400.0),.9),
            Landmark(PoseJoint.rightElbow,Point2D(600.0,250.0),.9),
            Landmark(PoseJoint.rightWrist,Point2D(600.0,100.0),.9))
        return PoseResult(PresentationTime((t*1000).toLong(),1000),ImageSize(1000.0,1000.0),
            listOf(PoseObservation(points)),"test",1)
    }
    @Test fun oneObservedHandDepartureRevokesMetadataWhileCounterCanRemainReturning() {
        val context = WorkoutGripExerciseContext()
        val first = context.observe(pose(0.0),bar,ExerciseCounter.Phase.outbound,true,false)
        val lost = context.observe(pose(.1,400.0),bar,ExerciseCounter.Phase.returning,true,false)
        assertFalse(lost.eligible);assertNull(lost.startedAt)
        assertEquals("handLeftSupport",lost.reason);assertTrue(lost.epoch>first.epoch)
        assertEquals(2,lost.policyVersion)
        // This is metadata revocation, not a synthetic counter event.
        assertFalse(context.observe(pose(.2),bar,ExerciseCounter.Phase.returning,true,false).eligible)
    }
    @Test fun regraspNeedsReadyThenAFreshDepartureAndGetsAFreshCutoff() {
        val context = WorkoutGripExerciseContext()
        context.observe(pose(0.0),bar,ExerciseCounter.Phase.outbound,true,false)
        context.observe(pose(.1,400.0),bar,ExerciseCounter.Phase.returning,true,false)
        assertFalse(context.observe(pose(.2),bar,ExerciseCounter.Phase.outbound,true,false).eligible)
        assertFalse(context.observe(pose(.3),bar,ExerciseCounter.Phase.ready,true,false).eligible)
        assertFalse(context.observe(pose(.4),bar,ExerciseCounter.Phase.returning,true,false).eligible)
        val restarted = context.observe(pose(.5),bar,ExerciseCounter.Phase.outbound,true,false)
        assertTrue(restarted.eligible);assertEquals(.5,restarted.startedAt)
    }
    @Test fun weakOrMissingWristCannotBePositiveDepartureEvidence() {
        for (kind in listOf("weak","missing","duplicate","nonfinite","outside","weakElbow")) {
            var p=pose(.1,400.0,if(kind=="weak") .49 else .9)
            val points=p.people.single().landmarks
            val changed=when(kind) {
                "missing" -> points.filter { it.joint!=PoseJoint.leftWrist }
                "duplicate" -> points+points.first { it.joint==PoseJoint.leftWrist }
                "nonfinite" -> points.map { if(it.joint==PoseJoint.leftWrist)it.copy(confidence=Double.NaN) else it }
                "outside" -> points.map { if(it.joint==PoseJoint.leftWrist)it.copy(position=Point2D(400.0,1001.0)) else it }
                "weakElbow" -> points.map { if(it.joint==PoseJoint.leftElbow)it.copy(confidence=.29) else it }
                else -> points
            }
            p=p.copy(people=listOf(PoseObservation(changed)))
            assertFalse(WorkoutGripExerciseContext.observedHandDeparture(p,bar),kind)
        }
    }
    @Test fun supportedHandsAndTheExactDepartureBoundaryDoNotRevokeExercise() {
        val context=WorkoutGripExerciseContext()
        context.observe(pose(0.0),bar,ExerciseCounter.Phase.outbound,true,false)
        assertTrue(context.observe(pose(.1,220.0),bar,ExerciseCounter.Phase.returning,true,false).eligible)
        assertFalse(context.observe(pose(.2,221.0),bar,ExerciseCounter.Phase.returning,true,false).eligible)
    }
    @Test fun invalidReferenceAndAmbiguousAthletesCannotWitnessDeparture() {
        val p=pose(.1,400.0)
        for(edge in listOf(null,BarSegment(Point2D(0.0,100.0),Point2D(0.0,100.0)),
                BarSegment(Point2D(-1.0,100.0),Point2D(1000.0,100.0))))
            assertFalse(WorkoutGripExerciseContext.observedHandDeparture(p,edge))
        assertFalse(WorkoutGripExerciseContext.observedHandDeparture(p.copy(people=p.people+p.people),bar))
        assertFalse(WorkoutGripExerciseContext.observedHandDeparture(p.copy(people=emptyList()),bar))
    }
    @Test fun movingAlongTheSupportLinePastASeededEndpointDoesNotMeanRelease() {
        val p=pose(.1).let { original -> original.copy(people=listOf(PoseObservation(
            original.people.single().landmarks.map {
                when(it.joint) {
                    PoseJoint.leftElbow -> it.copy(position=Point2D(600.0,250.0))
                    PoseJoint.leftWrist -> it.copy(position=Point2D(800.0,100.0))
                    else -> it
                }
            }))) }
        val seededSpan=BarSegment(Point2D(400.0,100.0),Point2D(600.0,100.0))
        assertTrue(ArmMeasurement(p,ArmMeasurement.Side.left).estimate!=null)
        assertFalse(WorkoutGripExerciseContext.observedHandDeparture(p,seededSpan))
        val context=WorkoutGripExerciseContext()
        context.observe(pose(0.0),seededSpan,ExerciseCounter.Phase.outbound,true,false)
        assertTrue(context.observe(p,seededSpan,ExerciseCounter.Phase.returning,true,false).eligible)
    }
    @Test fun departureBeforeAnyExerciseCannotTurnARegraspIntoExercise() {
        val context=WorkoutGripExerciseContext()
        assertFalse(context.observe(pose(0.0,400.0),bar,ExerciseCounter.Phase.ready,true,false).eligible)
        assertFalse(context.observe(pose(.1),bar,ExerciseCounter.Phase.returning,true,false).eligible)
        assertFalse(context.observe(pose(.2),bar,ExerciseCounter.Phase.ready,true,false).eligible)
        assertTrue(context.observe(pose(.3),bar,ExerciseCounter.Phase.outbound,true,false).eligible)
    }
    @Test fun repeatedOffSupportReadyFramesCannotEarnARearmWitness() {
        val context=WorkoutGripExerciseContext()
        context.observe(pose(0.0),bar,ExerciseCounter.Phase.outbound,true,false)
        for(i in 1..6)assertFalse(context.observe(pose(i*.05,400.0),bar,ExerciseCounter.Phase.ready,true,false).eligible)
        assertFalse(context.observe(pose(.35),bar,ExerciseCounter.Phase.outbound,true,false).eligible)
    }
    @Test fun trackingLossOrAClockGapAfterReadyInvalidatesRearm() {
        for(kind in listOf("gap","tracking","regressed")) {
            val context=WorkoutGripExerciseContext()
            context.observe(pose(0.0),bar,ExerciseCounter.Phase.outbound,true,false)
            context.observe(pose(.1,400.0),bar,ExerciseCounter.Phase.returning,true,false)
            context.observe(pose(.2),bar,ExerciseCounter.Phase.ready,true,false)
            when(kind) {
                "gap" -> assertFalse(context.observe(pose(.6),bar,ExerciseCounter.Phase.outbound,true,false).eligible)
                "tracking" -> {
                    context.observe(pose(.3),bar,ExerciseCounter.Phase.outbound,false,false)
                    assertFalse(context.observe(pose(.4),bar,ExerciseCounter.Phase.outbound,true,false).eligible)
                }
                else -> {
                    context.observe(pose(.1),bar,ExerciseCounter.Phase.outbound,true,false)
                    assertFalse(context.observe(pose(.3),bar,ExerciseCounter.Phase.outbound,true,false).eligible)
                }
            }
        }
    }
}
