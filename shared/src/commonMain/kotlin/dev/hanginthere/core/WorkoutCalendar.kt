package dev.hanginthere.core

import kotlinx.datetime.*
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable enum class WorkoutCalendarMode { week, month }

/** ISO civil dates and real local-midnight bounds, identical on Apple and Android. */
@OptIn(kotlin.time.ExperimentalTime::class)
object WorkoutCalendarPolicy {
    fun dayByKey(day: Long, timeZoneId: String): WorkoutCalendarDayRange = range(LocalDate.fromEpochDays(day.toInt()), TimeZone.of(timeZoneId))

    fun day(epochMilliseconds: Long, timeZoneId: String): WorkoutCalendarDayRange =
        range(Instant.fromEpochMilliseconds(epochMilliseconds).toLocalDateTime(TimeZone.of(timeZoneId)).date, TimeZone.of(timeZoneId))

    fun days(anchorEpochMilliseconds: Long, timeZoneId: String, mode: WorkoutCalendarMode, firstWeekday: Int): List<WorkoutCalendarDayRange> {
        require(firstWeekday in 1..7) // Sunday = 1, matching OS locale preferences.
        val zone = TimeZone.of(timeZoneId)
        val date = Instant.fromEpochMilliseconds(anchorEpochMilliseconds).toLocalDateTime(zone).date
        fun weekStart(value: LocalDate): LocalDate {
            val weekday = value.dayOfWeek.isoDayNumber % 7 + 1
            return value.minus((weekday - firstWeekday + 7) % 7, DateTimeUnit.DAY)
        }
        val first = if (mode == WorkoutCalendarMode.week) weekStart(date) else weekStart(LocalDate(date.year, date.month, 1))
        val end = if (mode == WorkoutCalendarMode.week) first.plus(7, DateTimeUnit.DAY)
            else weekStart(LocalDate(date.year, date.month, 1).plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY)).plus(7, DateTimeUnit.DAY)
        return (first.toEpochDays() until end.toEpochDays()).map { range(LocalDate.fromEpochDays(it), zone) }
    }
    fun move(anchorEpochMilliseconds: Long, timeZoneId: String, mode: WorkoutCalendarMode, firstWeekday: Int, amount: Int): Long {
        val zone = TimeZone.of(timeZoneId)
        val date = Instant.fromEpochMilliseconds(anchorEpochMilliseconds).toLocalDateTime(zone).date
        val start = if (mode == WorkoutCalendarMode.month) LocalDate(date.year, date.month, 1)
            else LocalDate.fromEpochDays(days(anchorEpochMilliseconds, timeZoneId, mode, firstWeekday).first().day.toInt())
        return start.plus(amount, if (mode == WorkoutCalendarMode.month) DateTimeUnit.MONTH else DateTimeUnit.WEEK)
            .atStartOfDayIn(zone).toEpochMilliseconds()
    }
    private fun range(date: LocalDate, zone: TimeZone) = WorkoutCalendarDayRange(date.toEpochDays().toLong(),
        date.atStartOfDayIn(zone).toEpochMilliseconds(), date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds())
}

class WorkoutCalendarBridge {
    @Throws(IllegalArgumentException::class)
    fun day(epochMilliseconds: Long, timeZoneId: String) = WorkoutCalendarPolicy.day(epochMilliseconds, timeZoneId)
    @Throws(IllegalArgumentException::class)
    fun days(anchorEpochMilliseconds: Long, timeZoneId: String, mode: String, firstWeekday: Int) =
        WorkoutCalendarPolicy.days(anchorEpochMilliseconds, timeZoneId, WorkoutCalendarMode.valueOf(mode), firstWeekday)
    @Throws(IllegalArgumentException::class)
    fun move(anchorEpochMilliseconds: Long, timeZoneId: String, mode: String, firstWeekday: Int, amount: Int) =
        WorkoutCalendarPolicy.move(anchorEpochMilliseconds, timeZoneId, WorkoutCalendarMode.valueOf(mode), firstWeekday, amount)
}
