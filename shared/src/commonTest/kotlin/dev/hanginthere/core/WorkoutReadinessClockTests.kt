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
            PresentationTime(0,0),PresentationTime(1,-1))) {
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
    @Test fun mixedTimebaseHoldUsesExactRationalElapsedAtLargeOrigins() {
        for (origin in listOf(9_007_199_254_740_992L, -9_007_199_254_740_992L)) {
            val policy = AutomaticWorkoutStart()
            assertFalse(observe(policy, origin))
            assertFalse(observe(policy, origin + 60_000))
            // Values calculated with exact rational arithmetic, not Double seconds.
            val end = if (origin > 0) 9_007_190_247_661_737L else -9_007_190_247_421_738L
            assertFalse(observe(policy, end, scale = 999_999))
            assertTrue(observe(policy, end + 1, scale = 999_999))
        }
    }
    @Test fun mixedTimebaseGapBoundaryCannotHideAnObservationOutage() {
        for ((lastTick, nextTick) in listOf(1_000_000L to 1_349_999L,
                9_007_199_254_740_943L to 9_007_190_247_891_688L)) {
            val policy = AutomaticWorkoutStart()
            assertFalse(observe(policy, lastTick))
            assertFalse(observe(policy, nextTick, scale = 999_999))
            assertFalse(observe(policy, nextTick + 60_000, scale = 999_999))
            assertFalse(observe(policy, nextTick + 120_000, scale = 999_999))
            assertTrue(observe(policy, nextTick + 180_000, scale = 999_999))
        }
        val boundary = AutomaticWorkoutStart()
        assertFalse(observe(boundary, 1_000_000))
        assertTrue(observe(boundary, 1_349_998, scale = 999_999))
        val largeBoundary = AutomaticWorkoutStart()
        assertFalse(observe(largeBoundary, 9_007_199_254_740_929))
        assertTrue(observe(largeBoundary, 9_007_190_247_891_673, scale = 999_999))
    }
    @Test fun badFramesCannotEraseTheReadinessHighWaterTime() {
        for (fault in listOf<(AutomaticWorkoutStart) -> Unit>(
            { observe(it, 1_060_000) }, // Duplicate.
            { observe(it, 1_020_000) }, // Regressed.
            { observe(it, 0, scale = 0) },
            { observe(it, 1_120_000, eligible = false) },
        )) {
            val policy = AutomaticWorkoutStart()
            assertFalse(observe(policy, 1_000_000))
            assertFalse(observe(policy, 1_060_000))
            fault(policy)
            for (tick in listOf(900_000L, 960_000L, 1_020_000L, 1_060_000L))
                assertFalse(observe(policy, tick))
            assertFalse(observe(policy, 1_180_000))
            assertFalse(observe(policy, 1_240_000))
            assertTrue(observe(policy, 1_300_000))
        }
    }
    @Test fun aForwardClockJumpRequiresAnOwnerResetBeforeRewindingTheSource() {
        val policy = AutomaticWorkoutStart()
        assertFalse(observe(policy, 1_000_000))
        assertFalse(observe(policy, Long.MAX_VALUE))
        for (tick in listOf(1_060_000L, 1_120_000L, 1_180_000L, 1_240_000L))
            assertFalse(observe(policy, tick))
        policy.reset()
        assertFalse(observe(policy, 0)); assertTrue(observe(policy, 120_000))
    }
    @Test fun nativeBridgeUsesTheSameMixedClockAndFaultPolicyForBothWorkouts() {
        for (exercise in ExerciseCounter.Exercise.entries) {
            val bridge = WorkoutAnalysisBridge("exact-readiness", exercise.name, 0)
            val start = 9_007_199_254_740_992L
            assertFalse(bridge.readyAt(start, 1_000_000, true))
            assertFalse(bridge.readyAt(start + 60_000, 1_000_000, true))
            assertFalse(bridge.readyAt(9_007_190_247_661_737L, 999_999, true))
            assertTrue(bridge.readyAt(9_007_190_247_661_738L, 999_999, true))
            assertFalse(bridge.readyAt(0, 0, true))
            for (tick in listOf(1_000_000L, 1_060_000L, 1_120_000L))
                assertFalse(bridge.readyAt(tick, 1_000_000, true))
            bridge.resetReadiness()
            assertFalse(bridge.readyAt(0, 1_000_000, true))
            assertTrue(bridge.readyAt(120_000, 1_000_000, true))
            assertEquals(0, bridge.closedRecordCount); assertNull(bridge.draftDeltaJson())
        }
    }

    @Test fun scalarCompatibilityAndSourceClocksCannotShareDwell() {
        val policy=AutomaticWorkoutStart()
        assertFalse(policy.observe(1.0,true));assertTrue(policy.observe(1.15,true))
        assertFalse(observe(policy,1_300_000));assertFalse(observe(policy,1_420_000))
        assertFalse(observe(policy,1_540_000))
        policy.reset()
        assertFalse(observe(policy,1_660_000));assertTrue(observe(policy,1_780_000))
        assertFalse(policy.observe(1.7,true));assertFalse(policy.observe(1.85,true))
        assertFalse(policy.observe(2.0,true))
        policy.reset();assertFalse(policy.observe(2.15,true));assertTrue(policy.observe(2.3,true))
        policy.reset();assertFalse(policy.observe(Double.NaN,true))
        assertFalse(policy.observe(0.0,true));assertFalse(policy.observe(Double.POSITIVE_INFINITY,true))
    }
    @Test fun switchingClockOverloadsCannotEraseTheSourceFence() {
        for (raw in listOf(false, true)) {
            val policy = AutomaticWorkoutStart()
            fun read(ticks: Long) = if (raw) observe(policy, ticks) else policy.observe(ticks / 1_000_000.0, true)
            assertFalse(read(1_000_000)); assertFalse(read(1_060_000))
            assertFalse(if (raw) policy.observe(0.0, true) else observe(policy, 0))
            for (ticks in listOf(0L, 60_000L, 120_000L)) assertFalse(read(ticks))
            assertFalse(read(1_120_000)); assertFalse(read(1_180_000)); assertTrue(read(1_300_000))
        }
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
