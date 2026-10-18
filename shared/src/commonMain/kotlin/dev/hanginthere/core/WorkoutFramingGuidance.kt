package dev.hanginthere.core

import kotlin.math.min

/** Presentation only: never changes arm eligibility, acquisition, counting or credited time. */
object WorkoutFramingGuidance {
    const val policyVersion = 4
    const val edgeMarginFraction = .03
    internal data class Advice(val message: String, val immediate: Boolean = false,
        val acquisition: Boolean = false)
    private val liveAcquisitionStatuses = setOf(AutomaticReferenceStability.Status.candidateStarted,
        AutomaticReferenceStability.Status.contextChanged, AutomaticReferenceStability.Status.sourceGap,
        AutomaticReferenceStability.Status.edgeChanged, AutomaticReferenceStability.Status.gripMoved,
        AutomaticReferenceStability.Status.accumulating)

    /** Only setup advice; a confirmed manual dip retains its selected-arm fallback. */
    fun setupMessage(pose: PoseResult, idle: Boolean, barConfirmed: Boolean, automatic: Boolean,
                     pullUp: Boolean, side: ArmMeasurement.Side): String? =
        setupAdvice(pose, idle, barConfirmed, automatic, pullUp, side)?.message

    internal fun setupAdvice(pose: PoseResult, idle: Boolean, barConfirmed: Boolean, automatic: Boolean,
                             pullUp: Boolean, side: ArmMeasurement.Side,
                             acquisitionState: AutomaticPullUpBar.State? = null,
                             referenceStability: AutomaticReferenceStability.Diagnostic? = null,
                             sourceSeconds: Double = pose.timestamp.seconds): Advice? {
        if (!idle) return null
        val framingReady = if (automatic) PullUpArmMeasurement(pose).arms.size == 2
            else if (pullUp) PullUpArmMeasurement(pose).arms.isNotEmpty()
            else ArmMeasurement(pose, side).estimate != null
        if (barConfirmed && framingReady) return null
        val armAdvice = advice(pose, requireBothArms = !barConfirmed || automatic || pullUp, side = side)
        if (armAdvice != null) return armAdvice
        val pullUpAssessment = if (!barConfirmed && (automatic || pullUp)) AutomaticPullUpBar.assessGrip(pose) else null
        if (pullUpAssessment != null) {
            when (val grip = pullUpAssessment) {
                // Measurable arms do not imply sufficient confidence for bar search.
                // Auto must not apply a pull-up requirement to valid dip support.
                AutomaticPullUpBar.GripRejection.gripLandmarksUnavailable ->
                    if (!automatic || AutomaticDipRails.grip(pose) == null)
                        return Advice("Keep both hands and shoulders clearly visible")
                is AutomaticPullUpBar.Grip -> if (AutomaticPullUpBar.overheadSearchIsClipped(pose, grip))
                    return Advice("Leave room above your hands for the bar")
                else -> Unit
            }
        }
        if (!barConfirmed && acquisitionState == AutomaticPullUpBar.State.holding && referenceStability != null) {
            val diagnostic = referenceStability
            // The raw FFI clock avoids microsecond transport truncation making a fresh observation appear future-dated.
            val age = diagnostic.sourceSeconds?.let { sourceSeconds - it }
            if (age != null && age.isFinite() && age in 0.0..AutomaticReferenceStability.maximumFrameGap &&
                diagnostic.status in liveAcquisitionStatuses && diagnostic.observations > 0 &&
                diagnostic.stableSeconds.isFinite() && diagnostic.stableSeconds >= 0 &&
                (if (pullUp) pullUpAssessment is AutomaticPullUpBar.Grip
                    // Contact search also runs through deep flexion and overhead
                    // poses; a starting-position cue still needs dip context.
                    else AutomaticDipRails.grip(pose) != null))
                return Advice("Hold your start position · keep the phone steady", acquisition = true)
        }
        return null
    }

    fun message(pose: PoseResult, requireBothArms: Boolean = true,
                side: ArmMeasurement.Side = ArmMeasurement.Side.left): String? = advice(pose, requireBothArms, side)?.message

    private fun advice(pose: PoseResult, requireBothArms: Boolean, side: ArmMeasurement.Side): Advice? {
        if (!pose.imageSize.isValid) return Advice("Pose unavailable · retry the camera", immediate = true)
        if (pose.people.isEmpty()) return Advice("Show one athlete clearly", immediate = true)
        if (pose.people.size != 1) return Advice("Keep one athlete in view", immediate = true)
        val sides = if (requireBothArms) ArmMeasurement.Side.entries else listOf(side)
        val points = pose.people.single().landmarks
        val measurements = sides.map { ArmMeasurement(pose, it) }
        val chains = sides.flatMap { it.joints }.map { joint -> points.filter { it.joint == joint } }
        // Invalid scores/coordinates or duplicated joints are not an athlete's framing error.
        if (chains.any { chain -> chain.size > 1 || chain.any {
            !it.position.isFinite || !it.confidence.isFinite() || it.confidence !in 0.0..1.0
        } }) return Advice("Pose unavailable · retry the camera", immediate = true)
        if (chains.flatten().any { it.isVisible() &&
            (it.position.x !in 0.0..pose.imageSize.width || it.position.y !in 0.0..pose.imageSize.height) })
            return Advice("Move phone back · keep shoulders, elbows and hands in frame")
        if (measurements.any { it.estimate == null }) {
            if (measurements.any { it.unavailableReason == ArmMeasurement.UnavailableReason.missingJoint })
                return Advice("Keep shoulders, elbows and hands in frame")
            if (measurements.any { it.unavailableReason == ArmMeasurement.UnavailableReason.shortProjectedSegment } ||
                measurements.count { it.estimate != null } == 1)
                return Advice(if (requireBothArms) "Angle camera so both arms and hands are clear"
                    else "Adjust the view so the selected arm and hand are clear")
            return Advice(if (requireBothArms) "Improve lighting · keep arms and hands clear"
                else "Improve lighting · keep the selected arm and hand clear")
        }
        val margin = min(pose.imageSize.width, pose.imageSize.height) * edgeMarginFraction
        if (chains.flatten().any { it.position.x < margin || it.position.y < margin ||
            pose.imageSize.width - it.position.x < margin || pose.imageSize.height - it.position.y < margin })
            return Advice("Move phone back · leave room around arms and hands")
        return null
    }
}

/** The same raw-joint transport as analysis; no JSON or native copy of framing policy. */
class WorkoutFramingBridge {
    private val feedback = WorkoutFramingFeedback()
    val jointNames get() = PoseJoint.entries.map { it.name }
    @Throws(IllegalArgumentException::class)
    fun message(width: Double, height: Double, peopleCount: Int, joints: IntArray, triples: DoubleArray,
                requireBothArms: Boolean, side: String): String? = WorkoutFramingGuidance.message(
        workoutBridgePose(0.0, width, height, peopleCount, joints, triples), requireBothArms,
        ArmMeasurement.Side.valueOf(side))

    @Throws(IllegalArgumentException::class)
    fun setupMessage(width: Double, height: Double, peopleCount: Int, joints: IntArray, triples: DoubleArray,
                     idle: Boolean, barConfirmed: Boolean, automatic: Boolean, pullUp: Boolean,
                     side: String): String? = WorkoutFramingGuidance.setupMessage(
        workoutBridgePose(0.0, width, height, peopleCount, joints, triples), idle, barConfirmed, automatic,
        pullUp, ArmMeasurement.Side.valueOf(side))

    fun resetFeedback() = feedback.reset()
    fun invalidateAcquisitionFeedback(): Boolean = feedback.invalidateAcquisition()
    @Throws(IllegalArgumentException::class)
    fun observeSetup(seconds: Double, width: Double, height: Double, peopleCount: Int,
                     joints: IntArray, triples: DoubleArray, idle: Boolean, barConfirmed: Boolean,
                     automatic: Boolean, pullUp: Boolean, side: String): String? =
        observeSetupWithAcquisition(seconds, width, height, peopleCount, joints, triples, idle, barConfirmed,
            automatic, pullUp, side, "", "", Double.NaN, 0, Double.NaN)

    /** Atomic, optional acquisition evidence; unknown/missing values cannot invent a hold. */
    @Throws(IllegalArgumentException::class)
    fun observeSetupWithAcquisition(seconds: Double, width: Double, height: Double, peopleCount: Int,
                     joints: IntArray, triples: DoubleArray, idle: Boolean, barConfirmed: Boolean,
                     automatic: Boolean, pullUp: Boolean, side: String, acquisitionState: String,
                     stabilityStatus: String, stabilitySeconds: Double, stabilityObservations: Int,
                     stableSeconds: Double): String? {
        try {
            val state = AutomaticPullUpBar.State.entries.find { it.name == acquisitionState }
            val status = AutomaticReferenceStability.Status.entries.find { it.name == stabilityStatus }
            val diagnostic = status?.let { AutomaticReferenceStability.Diagnostic(it, stabilityObservations,
                stableSeconds, stabilitySeconds.takeIf { it.isFinite() }) }
            return feedback.observe(workoutBridgePose(seconds, width, height, peopleCount, joints, triples),
                idle, barConfirmed, automatic, pullUp, ArmMeasurement.Side.valueOf(side), sourceSeconds = seconds,
                acquisitionState = state, referenceStability = diagnostic)
        } catch (error: IllegalArgumentException) { feedback.reset(); throw error }
    }
}
