package dev.hanginthere.core

import kotlin.math.*

/** Wrist observations locate the retained grip; they do not invent an apparatus edge. */
@ConsistentCopyVisibility
data class DipSupportMeasurement private constructor(
    val shoulder: Point2D, val wrist: Point2D, val degrees: Double?,
    val armLength: Double?, val otherDegrees: Double?,
) {
    companion object {
        const val startingDegrees = 140.0
        fun create(pose: PoseResult, side: ArmMeasurement.Side, referenceEdge: BarSegment): DipSupportMeasurement? {
            if (!pose.imageSize.isValid || !referenceEdge.isValid || pose.people.size != 1 ||
                !listOf(referenceEdge.a, referenceEdge.b).all { it.x in 0.0..pose.imageSize.width && it.y in 0.0..pose.imageSize.height }) return null
            fun point(joint: PoseJoint): Point2D? {
                val matches = pose.people[0].landmarks.filter { it.joint == joint }
                if (matches.size != 1) return null
                val landmark = matches[0]
                if (!landmark.isVisible() || landmark.position.x !in 0.0..pose.imageSize.width ||
                    landmark.position.y !in 0.0..pose.imageSize.height) return null
                return landmark.position
            }
            val shoulder = point(side.joints[0]) ?: return null
            val wrist = point(side.joints[2]) ?: return null
            if (referenceEdge.distance(wrist) > 0.12 * min(pose.imageSize.width, pose.imageSize.height)) return null
            val arm = ArmMeasurement(pose, side).estimate
            val other = if (side == ArmMeasurement.Side.left) ArmMeasurement.Side.right else ArmMeasurement.Side.left
            return DipSupportMeasurement(shoulder, wrist, arm?.elbowDegrees,
                arm?.let { it.upperArmPixels + it.forearmPixels }, ArmMeasurement(pose, other).estimate?.elbowDegrees)
        }
    }
    val isStartingSupport: Boolean get() {
        val roundingTolerance = 1e-6
        val degrees = degrees ?: return false
        val armLength = armLength ?: return false
        if (degrees + roundingTolerance < startingDegrees || wrist.y - shoulder.y < 0.35 * armLength) return false
        return otherDegrees?.let { it + roundingTolerance >= startingDegrees } ?: true
    }

    @ConsistentCopyVisibility
    data class Anchor private constructor(val wrist: Point2D, val shoulder: Point2D, val axis: Point2D, val armLength: Double) {
        companion object {
            fun create(sample: DipSupportMeasurement): Anchor? {
                val armLength = sample.armLength ?: return null
                if (armLength <= 0) return null
                val dx = sample.shoulder.x - sample.wrist.x; val dy = sample.shoulder.y - sample.wrist.y
                val span = hypot(dx, dy)
                if (span < 0.35 * armLength) return null
                return Anchor(sample.wrist, sample.shoulder, Point2D(dx / span, dy / span), armLength)
            }
        }
        fun height(shoulder: Point2D): Double = (shoulder.x - wrist.x) * axis.x + (shoulder.y - wrist.y) * axis.y
        fun retainsGrip(sample: DipSupportMeasurement): Boolean =
            hypot(sample.wrist.x - wrist.x, sample.wrist.y - wrist.y) <= 0.12 * armLength
        fun isStableStart(sample: DipSupportMeasurement): Boolean = sample.isStartingSupport && retainsGrip(sample) &&
            hypot(sample.shoulder.x - shoulder.x, sample.shoulder.y - shoulder.y) <= 0.08 * armLength
    }
}
