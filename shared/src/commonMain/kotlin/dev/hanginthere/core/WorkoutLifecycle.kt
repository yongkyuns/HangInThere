package dev.hanginthere.core

/** Recovery policy is common; native adapters keep the camera/foreground lifetime. */
object WorkoutLifecycle {
    fun shouldResume(endReason: String?) = endReason == "disengaged" || endReason == "trackingLost"
    fun observationOutage(elapsedSeconds: Double) = elapsedSeconds.isFinite() && elapsedSeconds > AutomaticSetBoundary.outageSeconds
}
