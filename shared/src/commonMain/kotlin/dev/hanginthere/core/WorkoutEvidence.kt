package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** What the recording establishes, independently of strength or form judgments. */
@Serializable enum class WorkoutAttemptStatus {
    completed, inProgress, partialMovement, returnNotRecorded, interrupted, ended
}

@Serializable data class WorkoutAttemptAssessment(
    val status: WorkoutAttemptStatus,
    val title: String,
    val explanation: String,
    val reason: String?,
    val durationCoverage: Double?,
    val leftCoverage: Double?,
    val rightCoverage: Double?,
    val angleComparisonEligible: Boolean,
    val notes: List<String>,
    val policyVersion: Int = 1,
)

/** Derived evidence, never a replacement for immutable measurements or count gates.
 * Coverage is observed source time, not calibrated model confidence. No phase,
 * return or missing-arm evidence is inferred from a retained/downsampled curve. */
object WorkoutEvidence {
    const val minimumAngleComparisonCoverage = .8
    private fun coverage(value: Double?) = value?.takeIf { it.isFinite() && it in 0.0..1.0 }
    private fun validRange(arm: AngleMetrics): Boolean =
        arm.minimum?.let { low -> arm.maximum?.let { high ->
            low.isFinite() && high.isFinite() && low >= 0 && high <= 180 && low <= high
        } } == true

    fun assess(rep: RepRecord, setEndReason: String? = null): WorkoutAttemptAssessment {
        val endedActiveAttempt = rep.closeReason == "inProgress" && setEndReason != null && setEndReason != "running"
        val status = when {
            rep.isCompleteForAnalytics -> WorkoutAttemptStatus.completed
            rep.closeReason == "inProgress" && !endedActiveAttempt -> WorkoutAttemptStatus.inProgress
            rep.acceptedAt != null && rep.returnedAt == null -> WorkoutAttemptStatus.returnNotRecorded
            endedActiveAttempt -> WorkoutAttemptStatus.interrupted
            rep.closeReason == "partial" -> WorkoutAttemptStatus.partialMovement
            rep.closeReason in listOf("manual", "disengaged", "endOfInput") -> WorkoutAttemptStatus.ended
            else -> WorkoutAttemptStatus.interrupted
        }
        val title = when (status) {
            WorkoutAttemptStatus.completed -> "Completed cycle"
            WorkoutAttemptStatus.inProgress -> "In progress"
            WorkoutAttemptStatus.partialMovement -> "Partial movement observed"
            WorkoutAttemptStatus.returnNotRecorded -> "Return not recorded"
            WorkoutAttemptStatus.interrupted -> "Observation interrupted"
            WorkoutAttemptStatus.ended -> "Attempt ended"
        }
        val explanation = when (status) {
            WorkoutAttemptStatus.completed -> "The movement and return were recorded. This cycle is included in completed-rep statistics."
            WorkoutAttemptStatus.inProgress -> "This attempt is still being tracked. It is excluded from completed-rep statistics until a completed cycle is recorded."
            WorkoutAttemptStatus.partialMovement -> "A return was observed before the movement reached the counting position. This is recorded as practice, not a completed rep."
            WorkoutAttemptStatus.returnNotRecorded -> "The movement reached the counting position, but its return was not recorded. A completed rep cannot be confirmed."
            WorkoutAttemptStatus.interrupted -> "Tracking or recording was interrupted. This record cannot establish whether the movement was completed."
            WorkoutAttemptStatus.ended -> "The attempt ended without a recorded completed cycle. It remains practice and is excluded from completed-rep statistics."
        }
        val duration = coverage(rep.durationCoverage)
        val left = coverage(rep.left.coverage)
        val right = coverage(rep.right.coverage)
        val notes = buildList {
            if (duration == null) add("Observed-time coverage is unavailable.")
            else if (duration < 1 - 1e-9) add("Some source time was not observed; gaps do not add hold time.")
            if (left == null) add("Left-arm time coverage is unavailable.")
            if (right == null) add("Right-arm time coverage is unavailable.")
            if (left != null && left < minimumAngleComparisonCoverage || right != null && right < minimumAngleComparisonCoverage)
                add("Limited arm observations: inspect the available measurements before comparing range.")
            add("Coverage describes recorded time. Camera angles do not verify exercise form.")
        }
        // An accepted interruption still has an unrecorded return. Its label
        // must not make the observed prefix comparable to a whole attempt.
        val nonInterruptedEnding = rep.closeReason in listOf("returned", "partial", "manual", "disengaged", "endOfInput")
        val comparable = nonInterruptedEnding && status !in listOf(WorkoutAttemptStatus.inProgress, WorkoutAttemptStatus.interrupted) &&
            left != null && right != null && left >= minimumAngleComparisonCoverage && right >= minimumAngleComparisonCoverage &&
            validRange(rep.left) && validRange(rep.right)
        val endingReason = if (endedActiveAttempt) {
            if (setEndReason == "recoveredInterruption") "Interrupted recording recovered" else "Set ended before this attempt was finalized"
        } else reason(rep.closeReason)
        return WorkoutAttemptAssessment(status, title, explanation, endingReason, duration, left, right, comparable, notes)
    }

    private fun reason(value: String): String? = when (value) {
        "returned", "partial", "inProgress" -> null
        "manual" -> "Set ended manually"
        "disengaged" -> "Equipment release observed"
        "endOfInput" -> "Recording ended"
        "trackingLost", "unusableArm", "lowConfidence", "missingJoint", "noPerson" -> "Body or arm tracking unavailable"
        "multiplePeople" -> "More than one person visible"
        "sourceTimeGap" -> "Gap between source frames"
        "invalidTimestamp", "nonIncreasingTimestamp" -> "Source timing interrupted"
        "inferenceFailure", "cameraFailure" -> "Camera or pose processing interrupted"
        "phoneMoved", "sceneShifted", "sceneScaled" -> "Camera or scene moved"
        "barReferenceUnavailable", "invalidBarGeometry", "setupInvalidated", "dipGripMoved" -> "Equipment reference changed or unavailable"
        "returnNotSustained" -> "Return position was not confirmed before another descent"
        "dipReturnUnresolved" -> "Return position could not be confirmed; reacquire top support"
        else -> "Observation ended without a confirmed return"
    }

    fun comparableElbowExcursion(rep: RepRecord): Double? = if (assess(rep).angleComparisonEligible)
        ((rep.left.maximum!! - rep.left.minimum!!) + (rep.right.maximum!! - rep.right.minimum!!)) * .5 else null
}

/** Review-only Apple ABI. Summaries suffice; curves never need to cross this boundary. */
class WorkoutEvidenceBridge {
    @Throws(IllegalArgumentException::class)
    fun assessmentJson(recordJson: String, setEndReason: String? = null): String = WorkoutAnalysis.json.encodeToString(
        WorkoutEvidence.assess(WorkoutRecordCodec.json.decodeFromString<RepRecord>(recordJson), setEndReason))
}
