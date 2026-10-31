package dev.hanginthere.core

import kotlin.test.*

class MotionWorkoutRuntimeTests {
    private val ready = OverheadPullUpMotion.Camera.ready
    private fun frames() = (0L..600L step 40).map { BodyDipFixtures.pose(it) } + BodyDipFixtures.cycle(640)
    @Test fun androidLifecycleMatchesTheIosControllerFrameForFrameWithoutRailsOrGrip() {
        for (choice in listOf(WorkoutExerciseSelection.dip, WorkoutExerciseSelection.automatic)) {
            val source = BodyAthleteSelection(); val directSource = BodyAthleteSelection()
            val runtime = MotionWorkoutRuntime(choice, newSetId = { "same" })
            val ios = RelativeDipWorkout("same")
            for (pose in frames()) {
                source.observe(pose); directSource.observe(pose)
                val actual = runtime.observe(source, ready)
                val expected = ios.observe(directSource, DipCycleMotion.Camera.ready)
                assertEquals(expected.started, runtime.hasStarted)
                assertEquals(expected.motion.cycles, actual.movements)
                assertEquals(expected.motion.targetProgress, actual.targetProgress)
                assertEquals(expected.motion.targetReached, actual.targetReached)
                assertEquals(expected.motion.reason, actual.reason)
                if (runtime.hasStarted) assertEquals(ios.record(true), runtime.currentSet)
            }
            assertEquals(1, runtime.currentSet!!.completedCount)
            assertNull(runtime.currentSet!!.referenceEdge)
        }
    }
    @Test fun automaticLifecyclePreservesBothSetsAndDoesNotCountReleaseOrSetup() {
        val runtime = MotionWorkoutRuntime(WorkoutExerciseSelection.automatic)
        val source = BodyAthleteSelection(); val completed = linkedMapOf<String, WorkoutSetRecord>()
        val stream = frames() + (1680L..2800L step 40).map { BodyDipFixtures.shifted(BodyDipFixtures.pose(it)) } +
            frames().map { it.copy(timestamp = PresentationTime(it.timestamp.value+3000,1000)) }
        for (pose in stream) {
            source.observe(pose); runtime.observe(source, ready)
            runtime.latestCompletedSet?.let { completed[it.id] = it }
        }
        runtime.finish("endOfInput"); runtime.latestCompletedSet?.let { completed[it.id] = it }
        assertEquals(listOf(1,1), completed.values.map { it.completedCount })
        assertEquals("disengaged", completed.values.first().endReason)
        assertEquals("endOfInput", completed.values.last().endReason)
        assertTrue(runtime.totalAnalyzedFrames > 0)
    }
    @Test fun unknownCameraAndStaticPreparationCannotStartAndManualFinishDoesNotAutoRestart() {
        val source = BodyAthleteSelection(); val runtime = MotionWorkoutRuntime(WorkoutExerciseSelection.dip)
        frames().forEach { source.observe(it); runtime.observe(source, OverheadPullUpMotion.Camera.unavailable) }
        assertFalse(runtime.hasStarted); assertNull(runtime.currentSet)
        val next = frames().map { it.copy(timestamp = PresentationTime(it.timestamp.value+3000,1000)) }
        next.forEach { source.observe(it); runtime.observe(source, ready) }
        assertEquals(1, runtime.currentSet!!.completedCount)
        runtime.finish()
        source.observe(BodyDipFixtures.pose(5000)); runtime.observe(source, ready)
        assertEquals(LiveSetSession.Phase.finished, runtime.snapshot.phase)
        runtime.nextSet()
        (5040L..6000L step 40).forEach { source.observe(BodyDipFixtures.pose(it)); runtime.observe(source, ready) }
        assertFalse(runtime.hasStarted)
    }
    @Test fun staleCaptureCannotManufactureAReleaseOrAnotherRep() {
        val source = BodyAthleteSelection(); val runtime = MotionWorkoutRuntime(WorkoutExerciseSelection.dip)
        frames().forEach { source.observe(it); runtime.observe(source, ready) }
        runtime.tick(100.0)
        assertEquals("trackingLost", runtime.latestCompletedSet!!.endReason)
        assertEquals(1, runtime.latestCompletedSet!!.completedCount)
    }
    @Test fun absolutePlacementIsSharedAndMissingGravityCannotQualify() {
        assertEquals(0.0, WorkoutCameraPlacement.tiltDegrees(0.0,1.0,0.0))
        assertFalse(WorkoutCameraPlacement.isUsable(WorkoutCameraPlacement.tiltDegrees(0.0,0.0,1.0)))
        assertNull(WorkoutCameraPlacement.tiltDegrees(0.0,0.0,0.0))
        assertFalse(WorkoutCameraPlacement.isUsable(null))
        assertTrue(WorkoutCameraPlacement.isUsable(20.0)); assertFalse(WorkoutCameraPlacement.isUsable(20.01))
    }
}
