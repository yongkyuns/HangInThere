package dev.hanginthere.core

import kotlin.test.*
import kotlin.time.Instant

@OptIn(kotlin.time.ExperimentalTime::class)
class WorkoutReminderPolicyTests {
    private fun at(text:String)=Instant.parse(text).toEpochMilliseconds()
    @Test fun completedDaysAreSkippedAndMovedDaysDeduplicate() {
        val now=at("2026-10-29T12:00:00Z")
        val completed=WorkoutCalendarPolicy.day(now,"America/Toronto").day
        val schedule=WorkoutReminderSchedule(true,18,15,listOf(5,6),mapOf("2026-10-29" to "2026-10-30"))
        val plan=WorkoutReminderPolicy.occurrences(schedule,now,"America/Toronto",setOf(completed))
        assertEquals(60,plan.size);assertEquals("workout-reminder.2026-10-30",plan.first().id)
        assertEquals(plan.size,plan.map { it.id }.distinct().size)
        assertEquals("workout-reminder.2026-11-05",WorkoutReminderPolicy.occurrences(schedule,now,"America/Toronto",setOf(completed,completed+1)).first().id)
        assertTrue(WorkoutReminderPolicy.occurrences(schedule.copy(enabled=false),now,"America/Toronto",emptySet()).isEmpty())
    }
    @Test fun daylightSavingUsesLocalWallClockAndNeverReplaysPastTimes() {
        val plan=WorkoutReminderPolicy.occurrences(WorkoutReminderSchedule(true,2,30,listOf(1)),at("2026-03-07T12:00:00Z"),"America/Toronto",emptySet())
        assertEquals(at("2026-03-08T07:30:00Z"),plan.first().epochMilliseconds)
        val fall=WorkoutReminderPolicy.occurrences(WorkoutReminderSchedule(true,1,30,listOf(1)),at("2026-10-31T12:00:00Z"),"America/Toronto",emptySet())
        assertEquals(at("2026-11-01T05:30:00Z"),fall.first().epochMilliseconds)
    }
}
