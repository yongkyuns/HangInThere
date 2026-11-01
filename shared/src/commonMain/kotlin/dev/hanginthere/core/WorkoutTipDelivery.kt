package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable data class WorkoutTipSettings(
    val workoutTipsEnabled: Boolean = true, val notificationsEnabled: Boolean = false,
    val daily: Boolean = false, val hour: Int = 10, val minute: Int = 0,
) {
    fun validate() { require(hour in 0..23 && minute in 0..59) }
}
@Serializable data class WorkoutTipNotification(val id: String, val epochMilliseconds: Long, val tipId: String)

object WorkoutTipDelivery {
    const val notificationPrefix = "training-tip."
    const val maximumNotifications = 18
    fun notifications(settings: WorkoutTipSettings, now: Long, timeZoneId: String): List<WorkoutTipNotification> {
        settings.validate()
        if (!settings.notificationsEnabled) return emptyList()
        val schedule = WorkoutReminderSchedule(true, settings.hour, settings.minute,
            if (settings.daily) (1..7).toList() else listOf(2,4,6))
        val pool = WorkoutTips.catalog.tips.filter { it.exercises.isEmpty() && it.category in setOf("Habits", "Recovery", "Nutrition", "Progression") }
        return WorkoutReminderPolicy.occurrences(schedule, now, timeZoneId, emptySet()).take(maximumNotifications).map { time ->
            val day = WorkoutCalendarPolicy.day(time.epochMilliseconds, timeZoneId).day
            val tip = pool[((day % pool.size + pool.size) % pool.size).toInt()]
            WorkoutTipNotification(time.id.replace("workout-reminder.", notificationPrefix), time.epochMilliseconds, tip.id)
        }
    }
    fun betweenSets(exercise: String, completedSets: Int, running: Boolean, replay: Boolean, enabled: Boolean): WorkoutTip? {
        if (!enabled || running || replay || completedSets <= 0) return null
        return WorkoutTips.featured(exercise, "review", completedSets.toLong() - 1)
    }
}

class WorkoutTipDeliveryBridge {
    @Throws(IllegalArgumentException::class)
    fun notificationsJson(settingsJson: String, now: Long, timeZoneId: String): String = WorkoutAnalysis.json.encodeToString(
        WorkoutTipDelivery.notifications(WorkoutAnalysis.json.decodeFromString(settingsJson), now, timeZoneId))
    fun betweenSetsJson(exercise: String, completedSets: Int, running: Boolean, replay: Boolean, enabled: Boolean): String =
        WorkoutAnalysis.json.encodeToString(WorkoutTipDelivery.betweenSets(exercise, completedSets, running, replay, enabled))
}
