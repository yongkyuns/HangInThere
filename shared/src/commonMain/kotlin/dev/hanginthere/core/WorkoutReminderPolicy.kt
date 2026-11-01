package dev.hanginthere.core

import kotlinx.datetime.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.time.Instant

@Serializable data class WorkoutReminderSchedule(val enabled: Boolean = false, val hour: Int = 18, val minute: Int = 0,
    val weekdays: List<Int> = listOf(2,4,6), val movedDays: Map<String,String> = emptyMap()) {
    fun validate() { require(hour in 0..23 && minute in 0..59 && weekdays.isNotEmpty() && weekdays.distinct().size == weekdays.size && weekdays.all { it in 1..7 }) }
}
@Serializable data class WorkoutReminderTime(val id: String, val epochMilliseconds: Long)

@OptIn(kotlin.time.ExperimentalTime::class)
object WorkoutReminderPolicy {
    fun occurrences(schedule: WorkoutReminderSchedule, now: Long, timeZoneId: String, completedDays: Set<Long>): List<WorkoutReminderTime> {
        schedule.validate(); require(now > 0)
        if (!schedule.enabled) return emptyList()
        val zone = TimeZone.of(timeZoneId)
        val today = Instant.fromEpochMilliseconds(now).toLocalDateTime(zone).date
        val regular = (0..729).map { today.plus(it,DateTimeUnit.DAY) }.filter { it.dayOfWeek.isoDayNumber % 7 + 1 in schedule.weekdays }
        val moved = regular.mapNotNull { day -> schedule.movedDays[day.toString()]?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: day } +
            schedule.movedDays.values.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
        return moved.distinct().sorted().filter { it.toEpochDays().toLong() !in completedDays }.mapNotNull { day ->
            val fire = day.atTime(schedule.hour,schedule.minute).toInstant(zone)
            if (fire.toEpochMilliseconds() <= now) null else WorkoutReminderTime("workout-reminder.$day",fire.toEpochMilliseconds())
        }.take(60)
    }
}
class WorkoutReminderBridge {
    @Throws(IllegalArgumentException::class)
    fun occurrencesJson(scheduleJson: String, now: Long, timeZoneId: String, completedDaysJson: String): String = WorkoutRecordCodec.json.encodeToString(
        WorkoutReminderPolicy.occurrences(WorkoutRecordCodec.json.decodeFromString(scheduleJson),now,timeZoneId,WorkoutRecordCodec.json.decodeFromString(completedDaysJson)))
}
