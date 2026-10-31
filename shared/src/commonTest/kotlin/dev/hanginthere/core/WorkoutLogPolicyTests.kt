package dev.hanginthere.core

import kotlin.test.*

class WorkoutLogPolicyTests {
    private fun log(id: String, time: Long, vararg sets: WorkoutLoggedSet) = WorkoutLog(id, time, sets.toList(), time)
    @Test fun prefillPrefersCurrentWorkoutAndMatchesNamesAcrossCaseAndWhitespace() {
        val old = WorkoutLoggedSet("old", "Bench press", 4, 165.0)
        val recent = old.copy(id = "recent", reps = 5, load = 75.0, unit = "kg")
        val history = listOf(log("old-log", 1000, old), log("recent-log", 2000, recent))
        assertEquals(recent, WorkoutLogPolicy.lastSet(" bench PRESS ", history))
        val current = log("current", 500, old.copy(reps = 6))
        assertEquals(6, WorkoutLogPolicy.prefill("Bench press", current, history)?.reps)
        assertNull(WorkoutLogPolicy.lastSet("Rows", history))
        assertEquals(ExerciseCounter.Exercise.pullUp, WorkoutLogPolicy.cameraExercise("Chin-ups"))
        assertEquals(ExerciseCounter.Exercise.dip, WorkoutLogPolicy.cameraExercise("Dips"))
        assertEquals(ExerciseCounter.Exercise.dip, WorkoutLogPolicy.cameraExercise("Parallel-bar dip"))
        assertNull(WorkoutLogPolicy.cameraExercise("Bench press"))
    }
    @Test fun conversionValidationAndSaveRevisionAreShared() {
        assertEquals(74.84274105, WorkoutLogPolicy.convertLoad(165.0, "lb", "kg"), .0000001)
        assertEquals(165.0, WorkoutLogPolicy.convertLoad(WorkoutLogPolicy.convertLoad(165.0, "lb", "kg"), "kg", "lb"), .0000001)
        assertFalse(WorkoutLogPolicy.validSet("Bench\npress", 5, 10.0, "lb"))
        assertFalse(WorkoutLogPolicy.validSet("Bench press", null, 10.0, "lb"))
        assertFalse(WorkoutLogPolicy.validSet("Bench press", 5, Double.NaN, "lb"))
        assertFailsWith<IllegalArgumentException> { WorkoutLogPolicy.convertLoad(10.0, "stone", "lb") }
        val value = log("log", 1000, WorkoutLoggedSet("set", "Bench press", 5))
        assertEquals(1001L, WorkoutLogPolicy.saveCommand(value, 500).workoutLog!!.updatedAtEpochMilliseconds)
    }
    @Test fun alternatingExerciseRestRequiresTwoAdjacentTimedSetsAndProgressUsesMatchingNames() {
        val bench = WorkoutLoggedSet("bench", "Bench press", 5, 165.0, startedAtEpochMilliseconds = 1000, endedAtEpochMilliseconds = 11000)
        val curl = WorkoutLoggedSet("curl", "EZ curls", 7, startedAtEpochMilliseconds = 251000, endedAtEpochMilliseconds = 261000)
        val typed = bench.copy(id = "typed", exercise = "bench PRESS", reps = 6, startedAtEpochMilliseconds = null, endedAtEpochMilliseconds = null)
        val next = curl.copy(id = "next", startedAtEpochMilliseconds = 500000, endedAtEpochMilliseconds = 510000)
        val value = log("log", 1000, bench, curl, typed, next)
        assertNull(WorkoutLogPolicy.restBefore(value, bench.id)); assertEquals(240.0, WorkoutLogPolicy.restBefore(value, curl.id))
        assertNull(WorkoutLogPolicy.restBefore(value, typed.id)); assertNull(WorkoutLogPolicy.restBefore(value, next.id))
        val progress = WorkoutLogPolicy.progress(listOf(value), " BENCH PRESS ")
        assertEquals(2, progress.setCount); assertEquals(11, progress.reps); assertEquals(6, progress.bestReps)
        assertEquals(11, progress.workouts.single().reps)
    }
    @Test fun cameraLinkPreservesCorrectionsAndConvertsOnlyEnteredLoad() {
        val record = WorkoutSetRecord("camera", ExerciseCounter.Exercise.pullUp, 0.0, 2.0, "manual", emptyList(), "authored", 1)
        var state = WorkoutLibrary.register(WorkoutLibraryState(), listOf(record))
        val correction = WorkoutReviewCorrection(context = WorkoutContext(equipment = "gym", effort = 6, addedLoadKg = 5.0))
        state = WorkoutLibrary.apply(state, WorkoutLibraryCommand("review", setId = record.id, correction = correction, atEpochMilliseconds = 1000))
        val log = log("mixed", 1000, WorkoutLoggedSet("curl", "EZ curls", 7))
        state = WorkoutLibrary.apply(state, WorkoutLogPolicy.saveCommand(log, 2000))
        val linked = WorkoutLogPolicy.cameraLinkCommand(state, record.id, log.id, 10.0, "lb", 3000).correction!!
        assertEquals(correction.context, linked.context.copy(addedLoadKg = correction.context.addedLoadKg))
        assertEquals(4.5359237, linked.context.addedLoadKg!!, .0000001)
        assertEquals("mixed", linked.workoutId)
        assertEquals(correction.context, WorkoutLogPolicy.cameraLinkCommand(state, record.id, log.id, null, "lb", 3000).correction!!.context)
        assertFailsWith<IllegalArgumentException> { WorkoutLogPolicy.cameraLinkCommand(state, record.id, "missing", null, "lb", 3000) }
    }
}
