package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlin.math.*

/** Independent current-frame estimate; missing joints never reuse an earlier angle. */
@Serializable
data class ArmMeasurement(val side: Side, val estimate: Estimate?, val unavailableReason: UnavailableReason?) {
    @Serializable
    enum class Side {
        left, right;
        val joints: List<PoseJoint> get() = when (this) {
            left -> listOf(PoseJoint.leftShoulder, PoseJoint.leftElbow, PoseJoint.leftWrist)
            right -> listOf(PoseJoint.rightShoulder, PoseJoint.rightElbow, PoseJoint.rightWrist)
        }
    }
    @Serializable
    enum class UnavailableReason {
        noPerson, multiplePeople, invalidImageSize, missingJoint, duplicateJoint,
        invalidJoint, lowConfidence, shortProjectedSegment
    }
    @Serializable
    data class Estimate(val elbowDegrees: Double, val upperArmPixels: Double, val forearmPixels: Double,
        val minimumJointConfidence: Double)

    constructor(pose: PoseResult, side: Side): this(side, evaluate(pose, side))
    private constructor(side: Side, result: Pair<Estimate?, UnavailableReason?>): this(side, result.first, result.second)

    companion object {
        const val policyVersion = 1
        const val confidenceThreshold = 0.3
        const val minimumSegmentFraction = 0.02
        private fun evaluate(pose: PoseResult, side: Side): Pair<Estimate?, UnavailableReason?> {
            fun fail(reason: UnavailableReason) = null to reason
            if (!pose.imageSize.isValid) return fail(UnavailableReason.invalidImageSize)
            if (pose.people.isEmpty()) return fail(UnavailableReason.noPerson)
            if (pose.people.size != 1) return fail(UnavailableReason.multiplePeople)
            val chain = mutableListOf<Landmark>()
            for (joint in side.joints) {
                val points = pose.people[0].landmarks.filter { it.joint == joint }
                val point = points.firstOrNull() ?: return fail(UnavailableReason.missingJoint)
                if (points.size != 1) return fail(UnavailableReason.duplicateJoint)
                if (!point.position.isFinite || !point.confidence.isFinite() || point.confidence !in 0.0..1.0 ||
                    point.position.x !in 0.0..pose.imageSize.width || point.position.y !in 0.0..pose.imageSize.height)
                    return fail(UnavailableReason.invalidJoint)
                if (point.confidence < confidenceThreshold) return fail(UnavailableReason.lowConfidence)
                chain += point
            }
            val s = chain[0].position; val e = chain[1].position; val w = chain[2].position
            val upper = hypot(s.x - e.x, s.y - e.y); val forearm = hypot(w.x - e.x, w.y - e.y)
            val floor = minimumSegmentFraction * min(pose.imageSize.width, pose.imageSize.height)
            val degrees = PoseGeometry.elbowAngle(s, e, w)
            if (upper < floor || forearm < floor || degrees == null) return fail(UnavailableReason.shortProjectedSegment)
            return Estimate(degrees, upper, forearm, chain.minOf { it.confidence }) to null
        }
    }
}
