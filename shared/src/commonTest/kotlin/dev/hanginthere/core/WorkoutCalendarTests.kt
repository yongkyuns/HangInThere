package dev.hanginthere.core

import kotlin.test.*

@OptIn(kotlin.time.ExperimentalTime::class)
class WorkoutCalendarTests {
    private val start = 1_700_000_000_000L
    private val hour = 3_600_000L
    private fun state(vararg times: Long): WorkoutLibraryState = times.foldIndexed(WorkoutLibraryState()) { index, state, at ->
        val log = WorkoutLog("workout-$index", at, listOf(WorkoutLoggedSet("row-$index", "Bench press", index + 1)), at)
        WorkoutLibrary.apply(state, WorkoutLibraryCommand("saveWorkoutLog", atEpochMilliseconds = at, workoutLog = log))
    }
    @Test fun historicalAndDstDaysUseInclusiveStartExclusiveEndForBothMarkersAndHistory() {
        for (hours in listOf(23L, 25L)) {
            val end = start + hours * hour
            val ranges = listOf(WorkoutCalendarDayRange(20_000, start, end), WorkoutCalendarDayRange(20_001, end, end + 24 * hour))
            val state = state(start - 1, start, end - 1, end, end + 24 * hour)
            val now = end + 100 * 24 * hour
            assertEquals(0, WorkoutJourney.report(state, now, 0).practiceDays)
            val days = WorkoutJourney.calendarDays(state, ranges, now)
            assertEquals(setOf("workout-1", "workout-2"), days[0].workoutIds.toSet())
            assertEquals(5, days[0].loggedReps); assertEquals(0, days[0].completedReps)
            assertEquals(listOf("workout-3"), days[1].workoutIds)
            for ((index, range) in ranges.withIndex()) {
                assertEquals(days[index].workoutIds.toSet(), WorkoutJourney.page(state, now, 0, day = range.day, range = range).workouts.map { it.id }.toSet())
            }
        }
    }
    @Test fun futureWorkoutsDoNotShowAndMalformedRangesAreRejected() {
        val range = WorkoutCalendarDayRange(20_000, start, start + 24 * hour)
        val state = state(start, start + hour)
        assertEquals(listOf("workout-0"), WorkoutJourney.calendarDays(state, listOf(range), start).single().workoutIds)
        for (bad in listOf(emptyList(), listOf(range.copy(endEpochMilliseconds = start)), listOf(range, range),
            listOf(range, range.copy(day = range.day + 1, startEpochMilliseconds = range.endEpochMilliseconds + 1, endEpochMilliseconds = range.endEpochMilliseconds + 24 * hour)))) {
            assertFailsWith<IllegalArgumentException> { WorkoutJourney.calendarDays(state, bad, start) }
        }
        assertFailsWith<IllegalArgumentException> { WorkoutJourney.page(state, start, 0, day = range.day + 1, range = range) }
    }
    @Test fun sharedGridHasRealWeekStartsLeapDayAndSafeMonthNavigation() {
        val date = kotlin.time.Instant.parse("2024-02-29T17:00:00Z").toEpochMilliseconds()
        val zone = "America/Toronto"
        for (first in listOf(1, 2)) {
            val days = WorkoutCalendarPolicy.days(date, zone, WorkoutCalendarMode.month, first)
            assertEquals(35, days.size)
            assertEquals(WorkoutCalendarPolicy.day(date, zone).day, days[if (first == 1) 32 else 31].day)
            assertTrue(days.zipWithNext().all { (a, b) -> a.day + 1 == b.day && a.endEpochMilliseconds == b.startEpochMilliseconds })
            assertEquals(7, WorkoutCalendarPolicy.days(date, zone, WorkoutCalendarMode.week, first).size)
        }
        val january = kotlin.time.Instant.parse("2024-01-31T17:00:00Z").toEpochMilliseconds()
        val february = WorkoutCalendarPolicy.move(january, zone, WorkoutCalendarMode.month, 1, 1)
        assertEquals(kotlin.time.Instant.parse("2024-02-01T05:00:00Z").toEpochMilliseconds(), february)
        assertEquals(kotlin.time.Instant.parse("2023-12-01T05:00:00Z").toEpochMilliseconds(),
            WorkoutCalendarPolicy.move(january, zone, WorkoutCalendarMode.month, 1, -1))
    }
    @Test fun sharedBoundsSupportDstAndFractionalHourZones() {
        for ((date, hours) in listOf("2024-03-10T17:00:00Z" to 23L, "2024-11-03T17:00:00Z" to 25L)) {
            val range = WorkoutCalendarPolicy.day(kotlin.time.Instant.parse(date).toEpochMilliseconds(), "America/Toronto")
            assertEquals(hours * hour, range.endEpochMilliseconds - range.startEpochMilliseconds)
            assertEquals(range.day + 1, WorkoutCalendarPolicy.day(range.endEpochMilliseconds, "America/Toronto").day)
        }
        val kathmandu = WorkoutCalendarPolicy.day(kotlin.time.Instant.parse("2024-02-01T12:00:00Z").toEpochMilliseconds(), "Asia/Kathmandu")
        assertEquals(kotlin.time.Instant.parse("2024-01-31T18:15:00Z").toEpochMilliseconds(), kathmandu.startEpochMilliseconds)
        assertEquals(kathmandu, WorkoutCalendarPolicy.dayByKey(kathmandu.day, "Asia/Kathmandu"))
        val early = WorkoutCalendarPolicy.days(1, "UTC", WorkoutCalendarMode.month, 1)
        assertEquals(early.size, WorkoutJourney.calendarDays(WorkoutLibraryState(), early, start).size)
        assertFailsWith<IllegalArgumentException> { WorkoutCalendarPolicy.days(start, "UTC", WorkoutCalendarMode.week, 0) }
    }

}
