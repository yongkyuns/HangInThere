package dev.hanginthere.core

import kotlinx.datetime.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlin.time.Instant

@Serializable enum class WorkoutGoalPeriod { day, week }
@Serializable data class WorkoutActivityGoal(val exercise: String, val target: Int, val period: WorkoutGoalPeriod = WorkoutGoalPeriod.week) {
    fun validate() { require(WorkoutLogPolicy.validSet(exercise, 0, null, "kg") && target in 1..100000) }
}
@Serializable data class WorkoutActivityGoalProgress(val goal: WorkoutActivityGoal, val reps: Int,
    val cameraReps: Int, val loggedReps: Int, val remaining: Int, val fraction: Double, val achieved: Boolean)
@Serializable data class WorkoutActivityDay(val day: Long, val reps: Int, val active: Boolean, val future: Boolean)
@Serializable data class WorkoutExerciseActivity(val exercise: String, val reps: Int, val sets: Int, val bestSet: Int)
@Serializable data class WorkoutActivityReport(val reps: Int, val sets: Int, val activeDays: Int,
    val cameraReps: Int, val loggedReps: Int, val previousReps: Int, val previousActiveDays: Int,
    val days: List<WorkoutActivityDay>, val goals: List<WorkoutActivityGoalProgress>,
    val exercises: List<WorkoutExerciseActivity>, val excludedUndatedSets: Int,
    val headline: String, val comparison: String)

/** Activity goals count recorded work, independently of camera comparison quality or context. */
@OptIn(kotlin.time.ExperimentalTime::class)
object WorkoutActivityProgress {
    fun exerciseKey(name: String): String = when (name.lowercase().filter(Char::isLetter)) {
        "pullup", "pullups", "chinup", "chinups" -> "pullups"
        "dip", "dips", "parallelbardip", "parallelbardips" -> "dips"
        "pushup", "pushups" -> "pushups"
        "situp", "situps" -> "situps"
        "lyinglegraise", "lyinglegraises", "floorlegraise", "floorlegraises" -> "lyinglegraises"
        "legraise", "legraises", "hanginglegraise", "hanginglegraises", "hangingkneeraise", "hangingkneeraises" -> "legraises"
        else -> WorkoutLogPolicy.exerciseKey(name)
    }
    private data class Entry(val exercise: String, val at: Long, val reps: Int, val camera: Int, val logged: Int, val active: Boolean)
    private fun entries(state: WorkoutLibraryState, now: Long): List<Entry> {
        val camera = WorkoutLibrary.sourceSets(state).filter { it.endReason != "running" }.mapNotNull { set ->
            val at = set.trackedAtEpochMilliseconds?.takeIf { it <= now } ?: return@mapNotNull null
            val reps = set.measuredCompletedReps + set.userReportedCompletedReps
            Entry(set.exercise.title, at, reps, set.measuredCompletedReps, set.userReportedCompletedReps,
                reps > 0 || set.practiceAttempts > 0 || (set.totalHoldSeconds ?: 0.0) > 0)
        }
        val logged = state.workoutLogs.filter { it.epochMilliseconds <= now }.flatMap { log ->
            log.sets.map { Entry(it.exercise, log.epochMilliseconds, it.reps, 0, it.reps, it.reps > 0) }
        }
        return camera + logged
    }
    fun completedDays(state: WorkoutLibraryState, now: Long, timeZoneId: String): Set<Long> {
        WorkoutLibrary.validate(state)
        return entries(state, now).filter { it.active }.map { WorkoutCalendarPolicy.day(it.at, timeZoneId).day }.toSet()
    }
    fun report(state: WorkoutLibraryState, now: Long, timeZoneId: String, firstWeekday: Int = 2): WorkoutActivityReport {
        WorkoutLibrary.validate(state); require(now > 0)
        val zone = TimeZone.of(timeZoneId)
        val today = WorkoutCalendarPolicy.day(now, timeZoneId)
        val week = WorkoutCalendarPolicy.days(now, timeZoneId, WorkoutCalendarMode.week, firstWeekday)
        val all = entries(state, now)
        val current = all.filter { it.at >= week.first().startEpochMilliseconds }
        val localNow = Instant.fromEpochMilliseconds(now).toLocalDateTime(zone)
        val previousCutoff = localNow.date.minus(7, DateTimeUnit.DAY).atTime(localNow.time).toInstant(zone).toEpochMilliseconds()
        val previousStart = WorkoutCalendarPolicy.dayByKey(week.first().day - 7, timeZoneId).startEpochMilliseconds
        val previous = all.filter { it.at >= previousStart && it.at <= previousCutoff }
        fun activeDays(rows: List<Entry>) = rows.filter { it.active }.map { WorkoutCalendarPolicy.day(it.at,timeZoneId).day }.distinct().size
        val goals = state.activityGoals.map { goal ->
            val start = if (goal.period == WorkoutGoalPeriod.day) today.startEpochMilliseconds else week.first().startEpochMilliseconds
            val rows = all.filter { it.at >= start && exerciseKey(it.exercise) == exerciseKey(goal.exercise) }
            val reps = rows.sumOf { it.reps }
            WorkoutActivityGoalProgress(goal,reps,rows.sumOf { it.camera },rows.sumOf { it.logged },
                (goal.target-reps).coerceAtLeast(0),(reps.toDouble()/goal.target).coerceIn(0.0,1.0),reps >= goal.target)
        }
        val total = current.sumOf { it.reps }; val old = previous.sumOf { it.reps }
        val comparison = when {
            total == 0 && old == 0 -> "Your weekly totals start here."
            old == 0 -> "Last week at this point: 0 reps"
            total > old -> "${total-old} more reps than this time last week"
            total < old -> "${old-total} fewer reps than this time last week"
            else -> "Matching this time last week"
        }
        return WorkoutActivityReport(total,current.count { it.active },activeDays(current),current.sumOf { it.camera },current.sumOf { it.logged },old,activeDays(previous),
            week.map { day -> val rows=current.filter { it.at >= day.startEpochMilliseconds && it.at < day.endEpochMilliseconds }
                WorkoutActivityDay(day.day,rows.sumOf { it.reps },rows.any { it.active },day.startEpochMilliseconds > now) },goals,
            current.filter { it.active }.groupBy { exerciseKey(it.exercise) }.values.map { rows ->
                WorkoutExerciseActivity(rows.first().exercise,rows.sumOf { it.reps },rows.size,rows.maxOf { it.reps })
            }.sortedByDescending { it.reps },
            WorkoutLibrary.sourceSets(state).count { it.endReason != "running" && it.trackedAtEpochMilliseconds == null },
            if(goals.any { it.achieved }) "Goal reached. Keep building." else if(total > old && old > 0) "You're building momentum." else if(total > 0) "Every set adds up." else "One set is a good start.",comparison)
    }
}

class WorkoutActivityBridge {
    fun exerciseKey(name: String): String = WorkoutActivityProgress.exerciseKey(name)
    @Throws(IllegalArgumentException::class)
    fun reportJson(stateJson: String, now: Long, timeZoneId: String, firstWeekday: Int): String = WorkoutRecordCodec.json.encodeToString(
        WorkoutActivityProgress.report(WorkoutRecordCodec.json.decodeFromString(stateJson),now,timeZoneId,firstWeekday))
    @Throws(IllegalArgumentException::class)
    fun completedDaysJson(stateJson: String, now: Long, timeZoneId: String): String = WorkoutRecordCodec.json.encodeToString(
        WorkoutActivityProgress.completedDays(WorkoutRecordCodec.json.decodeFromString(stateJson),now,timeZoneId))
}
