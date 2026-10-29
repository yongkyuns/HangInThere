package dev.hanginthere.core

import kotlinx.serialization.Serializable

/** A set annotation, not evidence that every frame/rep showed a readable hand.
 * Only already-confirmed hand classifications enter this owner. Missing hands
 * leave the annotation alone. Contradictory confirmed evidence stays Unknown.
 * Construct a new owner for every actual workout set. */
@Serializable data class WorkoutSetGrip(
    val setId: String,
    val orientation: GripOrientation = GripOrientation.unknown,
    val firstConfirmedAt: Double? = null,
    val lastConfirmedAt: Double? = null,
    val modelId: String? = null,
    val inferenceBackend: String? = null,
    val reason: String = "setGripUnidentified",
    val policyVersion: Int = 1,
) {
    val isConfirmed get() = orientation in confirmedOrientations && reason == "confirmedWithinSet"
    companion object {
        val confirmedOrientations = setOf(GripOrientation.underhand, GripOrientation.overhand,
            GripOrientation.neutral, GripOrientation.mixed)
    }
}

class WorkoutSetGripAnalysis(private val setId: String) {
    private var lastEvidence: Double? = null
    private var conflicted = false
    var current = WorkoutSetGrip(setId); private set

    /** Caller has already applied subject/view/hand/temporal qualification.
     * Repeated cached timestamps cannot move confirmation times forward. */
    fun observeConfirmed(orientation: GripOrientation, seconds: Double, modelId: String?, backend: String?) {
        if (orientation !in WorkoutSetGrip.confirmedOrientations || !seconds.isFinite() || seconds < 0 ||
            lastEvidence?.let { seconds <= it } == true) return
        lastEvidence = seconds
        if (conflicted) return
        if (current.firstConfirmedAt == null) {
            current = WorkoutSetGrip(setId, orientation, seconds, seconds, modelId, backend, "confirmedWithinSet")
        } else if (current.orientation != orientation || current.modelId != modelId || current.inferenceBackend != backend) {
            conflicted = true
            current = current.copy(orientation = GripOrientation.unknown, lastConfirmedAt = seconds,
                reason = "conflictingSetGripEvidence")
        } else current = current.copy(lastConfirmedAt = seconds)
    }
}
