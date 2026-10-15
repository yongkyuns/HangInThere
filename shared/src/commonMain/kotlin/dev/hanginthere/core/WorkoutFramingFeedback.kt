package dev.hanginthere.core

/** Setup presentation only. No timer, counter, acquisition or hold-credit state is advanced. */
class WorkoutFramingFeedback {
    private data class Context(val barConfirmed: Boolean, val automatic: Boolean,
        val pullUp: Boolean, val side: ArmMeasurement.Side, val imageSize: ImageSize)
    private var context: Context? = null
    private var lastSeconds: Double? = null
    private var candidate: WorkoutFramingGuidance.Advice? = null
    private var candidateSince: Double? = null

    fun reset() { context = null; lastSeconds = null; clearCandidate() }
    private fun clearCandidate() { candidate = null; candidateSince = null }

    /** A failed equipment search must not restart an unrelated visibility cue's debounce. */
    fun invalidateAcquisition(): Boolean {
        if (candidate?.acquisition != true) return false
        clearCandidate()
        return true
    }

    fun observe(pose: PoseResult, idle: Boolean, barConfirmed: Boolean, automatic: Boolean,
                pullUp: Boolean, side: ArmMeasurement.Side, sourceSeconds: Double = pose.timestamp.seconds,
                acquisitionState: AutomaticPullUpBar.State? = null,
                referenceStability: AutomaticReferenceStability.Diagnostic? = null): String? {
        if (!idle) { reset(); return null }
        val currentContext = Context(barConfirmed, automatic, pullUp, side, pose.imageSize)
        if (currentContext != context) { reset(); context = currentContext }
        val seconds = sourceSeconds
        if (!seconds.isFinite()) { reset(); return "Pose unavailable · retry the camera" }
        if (lastSeconds?.let { seconds <= it || seconds - it > maximumFrameGap } == true) clearCandidate()
        lastSeconds = seconds
        val advice = WorkoutFramingGuidance.setupAdvice(pose, idle, barConfirmed, automatic, pullUp, side,
            acquisitionState, referenceStability, sourceSeconds = seconds)
        // Clear/fault/identity states take effect now; old camera advice is never
        // retained across new evidence, configuration, lost frames or set state.
        if (advice == null || advice.immediate) { clearCandidate(); return advice?.message }
        if (candidate != advice) { candidate = advice; candidateSince = seconds; return null }
        return advice.message.takeIf { seconds - candidateSince!! >= confirmationSeconds }
    }

    companion object {
        const val policyVersion = 1
        const val confirmationSeconds = .30
        const val maximumFrameGap = HandsFreeSetStart.maximumFrameGap
    }
}
