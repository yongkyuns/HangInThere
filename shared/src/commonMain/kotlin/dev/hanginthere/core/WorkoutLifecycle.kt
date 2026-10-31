package dev.hanginthere.core

/** Recovery policy is common; native adapters keep the camera/foreground lifetime. */
object WorkoutLifecycle {
    /** v2 adds body-relative release after a camera-qualified overhead hold. */
    const val boundaryPolicyVersion = 2
    /** Storage retains dip v3/v4 and source-owned overhead pull-up v5/v6/v7/v8;
     * existing observed-bar/legacy owners retain their original v1/v2. */
    const val supportedBoundaryPolicyVersion = 9
    internal fun supportsBoundaryPolicy(exercise: ExerciseCounter.Exercise, method: String?, version: Int,
        countVersion: Int?) = version in 1..boundaryPolicyVersion && method != OverheadPullUpMotion.countMethod && exercise != ExerciseCounter.Exercise.pushUp ||
        version == 3 && exercise == ExerciseCounter.Exercise.dip &&
            method in listOf(DipCycleMotion.countMethod, BilateralTorsoDipMotion.countMethod) ||
        version == 4 && exercise == ExerciseCounter.Exercise.dip &&
            method == BilateralTorsoDipMotion.countMethod && countVersion == 2 ||
        version == 5 && exercise == ExerciseCounter.Exercise.pullUp &&
            method == OverheadPullUpMotion.countMethod && countVersion == 1 ||
        version == 6 && exercise == ExerciseCounter.Exercise.pullUp &&
            method == OverheadPullUpMotion.countMethod && countVersion == 2 ||
        version == 7 && exercise == ExerciseCounter.Exercise.pullUp &&
            method == OverheadPullUpMotion.countMethod && countVersion == 3 ||
        version == 8 && exercise == ExerciseCounter.Exercise.pullUp &&
            method == OverheadPullUpMotion.countMethod && countVersion == 4 ||
        version == SideViewPushUpWorkout.boundaryPolicyVersion && exercise == ExerciseCounter.Exercise.pushUp &&
            method == SideViewPushUpWorkout.countMethod && countVersion == SideViewPushUpMotion.policyVersion
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
