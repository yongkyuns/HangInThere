package dev.hanginthere.core

import kotlin.test.*
import kotlin.time.Instant

@OptIn(kotlin.time.ExperimentalTime::class)
class WorkoutTipDeliveryTests {
    private fun at(value: String) = Instant.parse(value).toEpochMilliseconds()
    @Test fun notificationsAreOptInAndContainVariedTraceableNonMedicalTips() {
        val now = at("2026-10-30T12:00:00Z")
        assertTrue(WorkoutTipDelivery.notifications(WorkoutTipSettings(), now, "America/Toronto").isEmpty())
        val plan = WorkoutTipDelivery.notifications(WorkoutTipSettings(notificationsEnabled = true), now, "America/Toronto")
        assertEquals(18, plan.size)
        assertEquals("training-tip.2026-10-30", plan.first().id)
        assertEquals(at("2026-10-30T14:00:00Z"), plan.first().epochMilliseconds)
        assertEquals(plan.size, plan.map { it.id }.distinct().size)
        assertTrue(plan.map { it.tipId }.distinct().size >= 8)
        assertTrue(plan.all { item -> WorkoutTips.catalog.tips.any { it.id == item.tipId && it.category != "Safety" && it.exercises.isEmpty() } })
    }
    @Test fun dailyScheduleUsesLocalTimeThroughDstAndSkipsPastTimes() {
        val settings = WorkoutTipSettings(notificationsEnabled = true, daily = true)
        val plan = WorkoutTipDelivery.notifications(settings, at("2026-10-31T15:00:00Z"), "America/Toronto")
        assertEquals("training-tip.2026-11-01", plan.first().id)
        assertEquals(at("2026-11-01T15:00:00Z"), plan.first().epochMilliseconds)
        val spring = WorkoutTipDelivery.notifications(settings.copy(hour = 2, minute = 30), at("2026-03-07T15:00:00Z"), "America/Toronto")
        assertEquals(at("2026-03-08T07:30:00Z"), spring.first().epochMilliseconds)
        assertFailsWith<IllegalArgumentException> { WorkoutTipDelivery.notifications(settings.copy(hour = 24), at("2026-10-31T15:00:00Z"), "UTC") }
    }
    @Test fun allExercisesHaveRestCuesButNoTipsInterruptCountingOrReplay() {
        for (exercise in listOf("pullUp", "dip", "pushUp", "sitUp")) {
            val tip = assertNotNull(WorkoutTipDelivery.betweenSets(exercise, 1, false, false, true))
            assertTrue(tip in WorkoutTips.query(exercise))
            assertNull(WorkoutTipDelivery.betweenSets(exercise, 1, true, false, true))
            assertNull(WorkoutTipDelivery.betweenSets(exercise, 1, false, true, true))
            assertNull(WorkoutTipDelivery.betweenSets(exercise, 1, false, false, false))
            assertNull(WorkoutTipDelivery.betweenSets(exercise, 0, false, false, true))
        }
    }
}
