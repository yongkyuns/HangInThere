package dev.hanginthere.core

import kotlin.math.min

/** Presentation only: never changes arm eligibility, acquisition, counting or credited time. */
object WorkoutFramingGuidance {
    const val policyVersion = 2
    const val edgeMarginFraction = .03
    internal data class Advice(val message: String, val immediate: Boolean = false)

    /** Only setup advice; a confirmed manual dip retains its selected-arm fallback. */
    fun setupMessage(pose: PoseResult, idle: Boolean, barConfirmed: Boolean, automatic: Boolean,
                     pullUp: Boolean, side: ArmMeasurement.Side): String? =
        setupAdvice(pose, idle, barConfirmed, automatic, pullUp, side)?.message

    internal fun setupAdvice(pose: PoseResult, idle: Boolean, barConfirmed: Boolean, automatic: Boolean,
                             pullUp: Boolean, side: ArmMeasurement.Side): Advice? {
        if (!idle) return null
        val framingReady = if (automatic) PullUpArmMeasurement(pose).arms.size == 2
            else if (pullUp) PullUpArmMeasurement(pose).arms.isNotEmpty()
            else ArmMeasurement(pose, side).estimate != null
        if (barConfirmed && framingReady) return null
        val armAdvice = advice(pose, requireBothArms = !barConfirmed || automatic || pullUp, side = side)
        if (armAdvice != null) return armAdvice
        if (!barConfirmed && (automatic || pullUp) && AutomaticPullUpBar.overheadSearchIsClipped(pose))
            return Advice("Leave room above your hands for the bar")
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
    @Throws(IllegalArgumentException::class)
    fun observeSetup(seconds: Double, width: Double, height: Double, peopleCount: Int,
                     joints: IntArray, triples: DoubleArray, idle: Boolean, barConfirmed: Boolean,
                     automatic: Boolean, pullUp: Boolean, side: String): String? {
        try {
            return feedback.observe(workoutBridgePose(seconds, width, height, peopleCount, joints, triples),
                idle, barConfirmed, automatic, pullUp, ArmMeasurement.Side.valueOf(side), sourceSeconds = seconds)
        } catch (error: IllegalArgumentException) { feedback.reset(); throw error }
    }
}
