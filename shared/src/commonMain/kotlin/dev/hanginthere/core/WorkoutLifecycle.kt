package dev.hanginthere.core

/** Recovery policy is common; native adapters keep the camera/foreground lifetime. */
object WorkoutLifecycle {
    fun shouldResume(endReason: String?) = endReason == "disengaged" || endReason == "trackingLost"
    fun observationOutage(elapsedSeconds: Double) = elapsedSeconds.isFinite() && elapsedSeconds > AutomaticSetBoundary.outageSeconds
}

/** Presentation-only feedback. Its monotonic UI clock never drives rep/set policy. */
class WorkoutStartFeedback {
    private var announcedSetId: String? = null
    private var shownAt: Double? = null
    fun begin(setId: String?, live: Boolean, running: Boolean, nowSeconds: Double): Boolean {
        if (!live || !running || setId.isNullOrEmpty() || setId == announcedSetId || !nowSeconds.isFinite()) return false
        announcedSetId = setId
        shownAt = nowSeconds
        return true
    }
    fun remainingSeconds(nowSeconds: Double): Double {
        val start = shownAt ?: return 0.0
        if (!nowSeconds.isFinite() || nowSeconds < start) return 0.0
        return (displaySeconds - (nowSeconds - start)).coerceIn(0.0, displaySeconds)
    }
    // Preserve the announcement identity so foreground/recomposition cannot
    // replay a start cue for a set that has already begun.
    fun clear() { shownAt = null }
    companion object {
        const val displaySeconds = 2.0
        const val spokenMessage = "Set started"
    }
}
