package dev.hanginthere.core

import kotlin.test.*

class WorkoutLogTests {
    private val now = 20000L * 86400000
    private fun log(id: String = "workout", at: Long = now) = WorkoutLog(id, at,
        listOf(WorkoutLoggedSet("bench", "Bench press", 5, 165.0), WorkoutLoggedSet("curl", "EZ curls", 7, 75.9)), now)
    private fun save(state: WorkoutLibraryState, log: WorkoutLog) = WorkoutLibrary.apply(state,
        WorkoutLibraryCommand("saveWorkoutLog", atEpochMilliseconds = log.updatedAtEpochMilliseconds, workoutLog = log))

    @Test fun loggedResultsReachCalendarAndHistoryWithoutInventingCameraEvidence() {
        val state = save(WorkoutLibraryState(), log())
        val report = WorkoutJourney.report(state, now, 0)
        assertEquals(0, report.completedReps); assertEquals(12, report.loggedReps)
        assertEquals(1, report.practiceDays); assertEquals(1, report.workoutCount)
        val workout = report.workouts.single()
        assertEquals(2, workout.setCount); assertEquals("logged", workout.quality.level)
        assertTrue(workout.sets.isEmpty()); assertEquals(75.9, workout.workoutLog!!.sets.last().load)
        assertEquals(12, report.days.last().loggedReps)
        assertEquals(workout, WorkoutJourney.workout(state, workout.id, now))
        assertTrue(WorkoutJourney.report(state, now - 1, 0).workouts.isEmpty())
    }
    @Test fun invalidInputsAndStaleEditsAreRejected() {
        val original = log()
        val row = original.sets.first()
        for (bad in listOf(row.copy(exercise = " "), row.copy(reps = -1), row.copy(reps = 1001),
            row.copy(load = Double.NaN), row.copy(load = -1.0), row.copy(unit = "stone"), row.copy(method = "camera"),
            row.copy(startedAtEpochMilliseconds = now), row.copy(startedAtEpochMilliseconds = now, endedAtEpochMilliseconds = now - 1))) {
            assertFailsWith<IllegalArgumentException> { save(WorkoutLibraryState(), original.copy(sets = listOf(bad))) }
        }
        val state = save(WorkoutLibraryState(), original)
        assertFailsWith<IllegalArgumentException> { save(state, original.copy(updatedAtEpochMilliseconds = now - 1)) }
        assertFailsWith<IllegalArgumentException> { save(state, original.copy(sets = listOf(row, row))) }
    }
    @Test fun backupRetainsLatestEditsAndDeletionPreventsResurrection() {
        val original = save(WorkoutLibraryState(), log())
        val backup = WorkoutLibraryBackup.export(original)
        val edited = save(original, log().copy(sets = listOf(log().sets.first().copy(reps = 6)), updatedAtEpochMilliseconds = now + 1))
        assertEquals(6, WorkoutLibraryBackup.restore(edited, backup).workoutLogs.single().sets.single().reps)
        val deleted = WorkoutLibrary.apply(edited, WorkoutLibraryCommand("deleteWorkoutLog", id = "workout", atEpochMilliseconds = now + 2))
        assertTrue(WorkoutLibraryBackup.restore(deleted, backup).workoutLogs.isEmpty())
        assertFailsWith<IllegalArgumentException> { save(deleted, log().copy(updatedAtEpochMilliseconds = now + 3)) }
        assertEquals(original.workoutLogs, WorkoutLibraryBackup.restore(WorkoutLibraryState(), backup).workoutLogs)
    }
    @Test fun manualCalendarDaysAndPaginationReachEveryWorkout() {
        val state = (0..109).fold(WorkoutLibraryState()) { state, index -> save(state, log("workout-$index", now - index * 86400000L)) }
        val overview = WorkoutJourney.report(state, now, 0)
        assertEquals(28, overview.practiceDays); assertEquals(28 * 12, overview.loggedReps)
        val old = WorkoutJourney.page(state, now, 0, cursor = overview.nextCursor)
        assertEquals(10, old.workouts.size)
        val dated = WorkoutJourney.page(state, now, 0, day = now / 86400000 - 109)
        assertEquals("workout-109", dated.workouts.single().id)
        assertEquals(110, (overview.workouts + old.workouts).map { it.id }.distinct().size)
    }
}
