package dev.hanginthere.core

import kotlin.test.*

class StaticSceneEvidenceTests {
    private val still = List(3) { StaticSceneStability.PatchTranslation(0.0, 0.0) }
    private val shifted = List(3) { StaticSceneStability.PatchTranslation(12.0, 0.0) }
    private val attitude = PhoneOrientationStability.Quaternion.create(0.0, 0.0, 0.0, 1.0)!!
    private fun runtime(exercise: ExerciseCounter.Exercise, automatic: Boolean = false) =
        WorkoutRuntime(if (exercise == ExerciseCounter.Exercise.pullUp) WorkoutExerciseSelection.pullUp else WorkoutExerciseSelection.dip,
            automaticWorkouts = automatic).also {
            val role = if (exercise == ExerciseCounter.Exercise.pullUp) ConfirmedBar.Role.pullUpGrip else ConfirmedBar.Role.leftDipRail
            assertTrue(it.confirmManualBar(ConfirmedBar(role, ConfirmedBar.Method.manualEdge,
                CounterFixtures.edge, imageSize = ImageSize(1000.0, 1000.0), sourceTime = PresentationTime(0, 1000)), attitude, 0.0))
        }
    private fun frame(time: Double, sceneTime: Double, exercise: ExerciseCounter.Exercise,
        scale: Double? = 0.0, patches: List<StaticSceneStability.PatchTranslation> = still) =
        WorkoutRuntime.FrameInput(CounterFixtures.bilateral(time, 170.0, 170.0, exercise = exercise),
            attitude = attitude, motionTimestamp = time, translations = patches, globalScaleFraction = scale, sceneTimestamp = sceneTime)
    private fun monitor() = StaticSceneStability().also {
        assertTrue(it.calibrate(ImageSize(1000.0, 1000.0)))
        it.observe(still, 0.0, 1.0)
        assertEquals(StaticSceneStability.State.stable, it.state)
    }

    @Test fun missingMeasurementCannotRetainReadinessOrLatestValues() {
        val scene = monitor()
        scene.observe(still, null, 1.1)
        assertEquals(StaticSceneStability.State.calibrating, scene.state)
        assertNull(scene.latestScaleFraction)
        assertFalse(scene.latestScaleMeasurementAvailable)
        scene.observe(still, 0.0, 1.2)
        assertEquals(StaticSceneStability.State.stable, scene.state)
        scene.observe(emptyList(), 0.0, 1.3)
        assertEquals(StaticSceneStability.State.calibrating, scene.state)
        assertNull(scene.latestShiftFraction)
        scene.observe(still, 0.0, 1.4)
        assertEquals(StaticSceneStability.State.stable, scene.state)
    }

    @Test fun separatedMovementSamplesCannotCompleteDwell() {
        for (scale in listOf(false, true)) {
            val scene = monitor()
            val patches = if (scale) still else shifted
            val fraction = if (scale) .03 else 0.0
            scene.observe(patches, fraction, 1.1)
            scene.observe(patches, fraction, 2.0)
            assertNotEquals(StaticSceneStability.State.moved, scene.state)
            scene.observe(patches, fraction, 2.2)
            assertNotEquals(StaticSceneStability.State.moved, scene.state)
            scene.observe(patches, fraction, 2.3)
            assertEquals(StaticSceneStability.State.moved, scene.state)
        }
    }

    @Test fun duplicateRewindAndInvalidClockBreakQualificationAndDwell() {
        for (badTime in listOf(1.2, .9, Double.NaN, Double.POSITIVE_INFINITY)) {
            val scene = monitor()
            scene.observe(shifted, 0.0, 1.1)
            scene.observe(shifted, 0.0, 1.2)
            scene.observe(shifted, 0.0, badTime)
            assertEquals(StaticSceneStability.State.calibrating, scene.state)
            assertNull(scene.latestShiftFraction)
            assertNull(scene.latestScaleFraction)
            scene.observe(shifted, 0.0, 1.4)
            assertNotEquals(StaticSceneStability.State.moved, scene.state)
            scene.observe(shifted, 0.0, 1.6)
            assertNotEquals(StaticSceneStability.State.moved, scene.state)
            scene.observe(shifted, 0.0, 1.7)
            assertEquals(StaticSceneStability.State.moved, scene.state)
        }
    }

    @Test fun missingSignalBreaksOnlyItsOwnMovementDwell() {
        val scene = monitor()
        scene.observe(shifted, 0.0, 1.1)
        scene.observe(emptyList(), 0.0, 1.2)
        scene.observe(shifted, 0.0, 1.4)
        assertNotEquals(StaticSceneStability.State.moved, scene.state)
        scene.observe(shifted, 0.0, 1.7)
        assertEquals(StaticSceneStability.State.moved, scene.state)
        assertEquals(StaticSceneStability.MovementKind.translation, scene.movementKind)

        val scale = monitor()
        scale.observe(still, .03, 1.1)
        scale.observe(still, Double.NaN, 1.2)
        assertNull(scale.latestScaleFraction)
        assertEquals(StaticSceneStability.State.calibrating, scale.state)
        scale.observe(still, .03, 1.4)
        assertNotEquals(StaticSceneStability.State.moved, scale.state)
        scale.observe(emptyList(), .03, 1.7)
        assertEquals(StaticSceneStability.State.moved, scale.state)
        assertEquals(StaticSceneStability.MovementKind.scale, scale.movementKind)
    }

    @Test fun movementRemainsLatchedUntilExplicitRecalibration() {
        val scene = monitor()
        scene.observe(shifted, 0.0, 1.1)
        scene.observe(shifted, 0.0, 1.4)
        assertEquals(StaticSceneStability.State.moved, scene.state)
        scene.observe(emptyList(), null, Double.NaN)
        scene.observe(still, 0.0, 2.0)
        assertEquals(StaticSceneStability.State.moved, scene.state)
        assertTrue(scene.calibrate(ImageSize(1000.0, 1000.0)))
        scene.observe(still, 0.0, 2.1)
        assertEquals(StaticSceneStability.State.stable, scene.state)
        assertNull(scene.movementKind)
    }

    @Test fun readinessAndReferenceRequireCurrentSourceTime() {
        val scene = monitor()
        assertTrue(scene.isReady(1.8))
        assertFalse(scene.isReady(1.800001))
        assertFalse(scene.isReady(null))
        assertFalse(scene.isReady(Double.NaN))
        assertFalse(scene.isReady(.94))
        assertTrue(scene.isReady(.96))
        assertNotNull(scene.qualifiedReference(CounterFixtures.edge, 1.1))
        assertNull(scene.qualifiedReference(CounterFixtures.edge, 2.0))
        scene.observe(emptyList(), null, 1.2)
        assertNull(scene.qualifiedReference(CounterFixtures.edge, 1.2))
    }

    @Test fun cachedRegistrationSupportsFasterPoseFramesAndBothStartModes() {
        for (exercise in ExerciseCounter.Exercise.entries) for (automatic in listOf(false, true)) {
            val runtime = runtime(exercise, automatic)
            val countdown = mutableSetOf<Int>()
            for (i in 0..60) {
                // Actual Android delivery: 12 Hz poses reuse each 3 Hz registration.
                val snapshot = runtime.observe(frame(i / 12.0, (i / 4 * 4) / 12.0, exercise))
                snapshot.countdownSeconds?.let(countdown::add)
                assertEquals(StaticSceneStability.State.stable, snapshot.sceneState)
            }
            assertEquals(LiveSetSession.Phase.running, runtime.session.phase)
            assertEquals(0, runtime.session.observedMovements)
            if (!automatic) assertTrue(countdown.containsAll(listOf(3, 2, 1)))
        }
    }

    @Test fun repeatedCachedRegistrationCannotRenewItsSourceAgeOrCredit() {
        val runtime = runtime(ExerciseCounter.Exercise.pullUp)
        assertTrue(runtime.rewindReplay(attitude, 0.0))
        for (i in 0..9) runtime.observe(frame(i / 12.0, 0.0, ExerciseCounter.Exercise.pullUp))
        val credited = runtime.session.analysis.activity.seconds(WorkoutMotionPhase.hang)
        assertTrue(credited > 0)
        for (i in 10..16) {
            val snapshot = runtime.observe(frame(i / 12.0, 0.0, ExerciseCounter.Exercise.pullUp))
            assertEquals("sceneStabilityNeeded", snapshot.setupIssue)
            assertEquals("barReferenceUnavailable", snapshot.trackingIssue)
            assertEquals(credited, runtime.session.analysis.activity.seconds(WorkoutMotionPhase.hang))
        }
        assertEquals(0, runtime.session.record.completedCount)
    }

    @Test fun changedEvidenceAtTheSameSourceTimeIsNotACachedObservation() {
        val runtime = runtime(ExerciseCounter.Exercise.pullUp)
        runtime.observe(frame(0.0, 0.0, ExerciseCounter.Exercise.pullUp))
        runtime.observe(frame(.1, 0.0, ExerciseCounter.Exercise.pullUp))
        assertEquals(StaticSceneStability.State.stable, runtime.staticScene.state)
        runtime.observe(frame(.2, 0.0, ExerciseCounter.Exercise.pullUp, scale = null))
        assertEquals(StaticSceneStability.State.calibrating, runtime.staticScene.state)
        assertEquals("sceneStabilityNeeded", runtime.snapshot.setupIssue)
        runtime.observe(frame(.3, .3, ExerciseCounter.Exercise.pullUp))
        assertEquals(StaticSceneStability.State.stable, runtime.staticScene.state)
    }

    @Test fun cachedMovementDoesNotAdvanceDwellButFreshRegistrationCanConfirmIt() {
        val runtime = runtime(ExerciseCounter.Exercise.pullUp)
        runtime.observe(frame(0.0, 0.0, ExerciseCounter.Exercise.pullUp))
        runtime.observe(frame(.1, .1, ExerciseCounter.Exercise.pullUp, patches = shifted))
        for (time in listOf(.2, .3, .4)) {
            val snapshot = runtime.observe(frame(time, .1, ExerciseCounter.Exercise.pullUp, patches = shifted))
            assertEquals(StaticSceneStability.State.stable, snapshot.sceneState)
        }
        val snapshot = runtime.observe(frame(.45, .45, ExerciseCounter.Exercise.pullUp, patches = shifted))
        assertEquals("sceneShifted", snapshot.setupIssue)
        assertNull(runtime.confirmedBar)
    }

    @Test fun runtimeCannotCompleteMovementOnAnUnqualifiedSceneFrame() {
        val runtime = WorkoutRuntime(selection=WorkoutExerciseSelection.pullUp)
        val attitude = PhoneOrientationStability.Quaternion.create(0.0, 0.0, 0.0, 1.0)!!
        val bar = ConfirmedBar(ConfirmedBar.Role.pullUpGrip, ConfirmedBar.Method.manualEdge,
            CounterFixtures.edge, imageSize=ImageSize(1000.0, 1000.0), sourceTime=PresentationTime(0,1000))
        assertTrue(runtime.confirmManualBar(bar, attitude, 0.0))
        assertTrue(runtime.rewindReplay(attitude, 0.0))
        for ((time, angle) in listOf(0.0 to 170.0, .15 to 170.0, .3 to 120.0, .45 to 80.0)) {
            runtime.observe(WorkoutRuntime.FrameInput(CounterFixtures.bilateral(time, angle, angle),
                attitude=attitude, motionTimestamp=time, translations=still,
                globalScaleFraction=if(time==.45) null else 0.0, sceneTimestamp=time))
        }
        assertEquals(LiveSetSession.Phase.running, runtime.session.phase)
        assertEquals(0, runtime.session.observedMovements)
        assertEquals(1, runtime.session.interruptedAttempts)
        assertEquals(3, runtime.session.usableFrames)
        assertEquals("sceneStabilityNeeded", runtime.snapshot.setupIssue)
        assertEquals("barReferenceUnavailable", runtime.session.trackingIssue)
        assertEquals(bar, runtime.confirmedBar)
        runtime.observe(WorkoutRuntime.FrameInput(CounterFixtures.bilateral(.6, 170.0, 170.0),
            attitude=attitude, motionTimestamp=.6, translations=still, globalScaleFraction=0.0, sceneTimestamp=.6))
        assertEquals(StaticSceneStability.State.stable, runtime.staticScene.state)
        assertEquals(0, runtime.session.observedMovements)
    }

    @Test fun unqualifiedSceneCannotCreditHangTimeDespiteVisibleArms() {
        val scene = StaticSceneStability()
        assertTrue(scene.calibrate(ImageSize(1000.0, 1000.0)))
        val session = LiveSetSession()
        session.start(ExerciseCounter.Exercise.pullUp, ArmMeasurement.Side.left)
        for (i in 0..4) {
            val time=1.0+i*.15
            scene.observe(still, 0.0, time)
            session.consume(CounterFixtures.bilateral(time, 170.0, 170.0),
                scene.qualifiedReference(CounterFixtures.edge, time))
        }
        val credited=session.analysis.activity.seconds(WorkoutMotionPhase.hang)
        assertTrue(credited>0)
        for (i in 5..7) {
            val time=1.0+i*.15
            scene.observe(still, null, time)
            session.consume(CounterFixtures.bilateral(time, 170.0, 170.0),
                scene.qualifiedReference(CounterFixtures.edge, time))
        }
        assertEquals(credited, session.analysis.activity.seconds(WorkoutMotionPhase.hang))
        assertEquals(5, session.usableFrames)
        assertEquals(8, session.analyzedFrames)
        assertEquals(0, session.record.completedCount)
    }

    @Test fun neitherExerciseCanBorrowCachedReferenceForAnUnavailableEndpoint() {
        for (exercise in ExerciseCounter.Exercise.entries) {
            for (lost in listOf(false, true)) {
                val session=LiveSetSession()
                session.start(exercise, ArmMeasurement.Side.left)
                for ((time, angle) in listOf(0.0 to 170.0, .15 to 170.0, .3 to 80.0, .45 to 80.0, .6 to 170.0, .75 to 170.0)) {
                    session.consume(CounterFixtures.bilateral(time, angle, angle, exercise=exercise),
                        if(lost && time==.45) null else CounterFixtures.edge)
                }
                session.finish()
                assertEquals(if(lost) 0 else 1, session.observedMovements)
                assertEquals(if(lost) 0 else 1, session.record.completedCount)
                if(lost) {
                    assertTrue(session.record.reps.any { it.closeReason=="barReferenceUnavailable" })
                    assertTrue(session.record.reps.none { it.isCompleteForAnalytics })
                }
            }
        }
    }
}
