package dev.hanginthere.core

import kotlin.test.*

class WorkoutReadinessClockTests {
    private fun observe(policy:AutomaticWorkoutStart,ticks:Long,eligible:Boolean=true,scale:Int=1_000_000)=
        policy.observe(PresentationTime(ticks,scale),eligible)

    @Test fun exactReadinessDurationQualifiesWithoutRoundingUpShortHolds() {
        for(origin in listOf(0L,1_100_000L,60_100_000L,9_007_199_254_740_990L)) {
            val policy=AutomaticWorkoutStart()
            assertFalse(observe(policy,origin))
            assertFalse(observe(policy,origin+119_999))
            assertTrue(observe(policy,origin+120_000))
        }
    }
    @Test fun exactReadinessGapIsAllowedButOneTickMoreDiscardsDwell() {
        val exact=AutomaticWorkoutStart()
        assertFalse(observe(exact,1_200_000))
        assertTrue(observe(exact,1_550_000))
        val beyond=AutomaticWorkoutStart()
        for(t in listOf(1_200_000L,1_550_001L,1_670_001L)) assertFalse(observe(beyond,t))
        assertTrue(observe(beyond,1_790_001))
    }
    @Test fun faultsAndEligibilityLossRequireFreshReadiness() {
        for(fault in listOf(PresentationTime(1_100_000,1_000_000),PresentationTime(1_099_999,1_000_000),
            PresentationTime(0,0),PresentationTime(1,-1),PresentationTime(Long.MAX_VALUE,1_000_000))) {
            val policy=AutomaticWorkoutStart()
            assertFalse(observe(policy,1_100_000))
            assertFalse(policy.observe(fault,true))
            assertFalse(observe(policy,1_220_000))
            assertTrue(observe(policy,1_340_000))
        }
        val policy=AutomaticWorkoutStart()
        assertFalse(observe(policy,1_100_000));assertFalse(observe(policy,1_220_000,eligible=false))
        assertFalse(observe(policy,1_340_000));assertTrue(observe(policy,1_460_000))
        policy.reset();assertFalse(observe(policy,1_580_000))
        assertFalse(policy.observe(PresentationTime(Long.MIN_VALUE,1_000_000),true))
        assertFalse(policy.observe(PresentationTime(Long.MAX_VALUE,1_000_000),true))
    }
    @Test fun scalarCompatibilityAndSourceClocksCannotShareDwell() {
        val policy=AutomaticWorkoutStart()
        assertFalse(policy.observe(1.0,true));assertTrue(policy.observe(1.15,true))
        assertFalse(observe(policy,1_300_000));assertFalse(observe(policy,1_420_000))
        assertTrue(observe(policy,1_540_000))
        assertFalse(policy.observe(1.7,true));assertFalse(policy.observe(1.85,true))
        assertTrue(policy.observe(2.0,true))
        policy.reset();assertFalse(policy.observe(Double.NaN,true))
        assertFalse(policy.observe(0.0,true));assertFalse(policy.observe(Double.POSITIVE_INFINITY,true))
    }
    @Test fun rawBridgeKeepsTicksAndResetWithoutInventingAWorkout() {
        val bridge=WorkoutAnalysisBridge("readiness", "pullUp", 0)
        assertFalse(bridge.readyAt(1_100_000,1_000_000,true))
        assertFalse(bridge.readyAt(1_219_999,1_000_000,true))
        assertTrue(bridge.readyAt(1_220_000,1_000_000,true))
        bridge.resetReadiness();assertFalse(bridge.readyAt(1_340_000,1_000_000,true))
        assertEquals(0,bridge.closedRecordCount);assertNull(bridge.draftDeltaJson())
    }
    @Test fun runtimeUsesSourceTicksForBothWorkoutsAfterActualSetupGates() {
        val attitude=PhoneOrientationStability.Quaternion.create(0.0,0.0,0.0,1.0)!!
        for(exercise in ExerciseCounter.Exercise.entries) {
            val selection=if(exercise==ExerciseCounter.Exercise.pullUp) WorkoutExerciseSelection.pullUp else WorkoutExerciseSelection.dip
            val role=if(exercise==ExerciseCounter.Exercise.pullUp) ConfirmedBar.Role.pullUpGrip else ConfirmedBar.Role.leftDipRail
            val runtime=WorkoutRuntime(selection,automaticWorkouts=true)
            val bar=ConfirmedBar(role,ConfirmedBar.Method.manualEdge,CounterFixtures.edge,
                imageSize=ImageSize(1000.0,1000.0),sourceTime=PresentationTime(1_100_000,1_000_000))
            assertTrue(runtime.confirmManualBar(bar,attitude,1.1))
            for(ticks in listOf(1_100_000L,1_219_999L,1_220_000L)) {
                val time=PresentationTime(ticks,1_000_000)
                val pose=CounterFixtures.bilateral(time.seconds,170.0,170.0,exercise=exercise).copy(timestamp=time)
                val s=runtime.observe(WorkoutRuntime.FrameInput(pose,attitude=attitude,motionTimestamp=time.seconds,
                    translations=List(3){StaticSceneStability.PatchTranslation(0.0,0.0)},globalScaleFraction=0.0,sceneTimestamp=time.seconds))
                assertEquals(if(ticks==1_220_000L) LiveSetSession.Phase.running else LiveSetSession.Phase.idle,s.phase)
                assertEquals(0,s.observedMovements)
            }
        }
    }
}
