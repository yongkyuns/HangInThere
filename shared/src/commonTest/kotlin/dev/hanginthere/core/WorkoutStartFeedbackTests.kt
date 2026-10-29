package dev.hanginthere.core

import kotlin.test.*

class WorkoutStartFeedbackTests {
    @Test fun onlyRealLiveRunningSetsCanAnnounce() {
        val feedback = WorkoutStartFeedback()
        assertFalse(feedback.begin("set", live = true, running = false, nowSeconds = 10.0))
        assertFalse(feedback.begin("set", live = false, running = true, nowSeconds = 10.0))
        assertFalse(feedback.begin(null, live = true, running = true, nowSeconds = 10.0))
        assertFalse(feedback.begin("", live = true, running = true, nowSeconds = 10.0))
        assertEquals(0.0, feedback.remainingSeconds(10.0))
        assertTrue(feedback.begin("set", live = true, running = true, nowSeconds = 10.0))
    }
    @Test fun repeatedFramesCannotRepeatSpeechOrExtendTheCue() {
        val feedback = WorkoutStartFeedback()
        assertTrue(feedback.begin("set", true, true, 10.0))
        for (i in 1..30) assertFalse(feedback.begin("set", true, true, 10.0 + i / 10.0))
        assertEquals(2.0, feedback.remainingSeconds(10.0))
        assertEquals(.5, feedback.remainingSeconds(11.5))
        assertEquals(0.0, feedback.remainingSeconds(12.0))
        assertEquals(0.0, feedback.remainingSeconds(20.0))
    }
    @Test fun interruptionHidesCueWithoutAnnouncingTheSameSetAgain() {
        val feedback = WorkoutStartFeedback()
        assertTrue(feedback.begin("first", true, true, 10.0))
        feedback.clear()
        assertEquals(0.0, feedback.remainingSeconds(10.1))
        assertFalse(feedback.begin("first", true, true, 10.2))
        assertTrue(feedback.begin("next", true, true, 10.3))
        assertEquals(2.0, feedback.remainingSeconds(10.3))
    }
    @Test fun invalidOrEarlierUiTimeCannotShowOrReserveAFalseCue() {
        val feedback = WorkoutStartFeedback()
        for (time in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFalse(feedback.begin("set", true, true, time))
            assertEquals(0.0, feedback.remainingSeconds(time))
        }
        assertTrue(feedback.begin("set", true, true, 10.0))
        assertEquals(0.0, feedback.remainingSeconds(9.9))
        assertEquals(0.0, feedback.remainingSeconds(Double.NaN))
    }
}
