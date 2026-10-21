package dev.hanginthere.core

import kotlin.math.atan2
import kotlin.math.PI

/** Per-frame geometry of a monocular estimate. These values do not establish
 * apparatus contact, load, anatomical extension, a rep, or camera invariance of
 * the model itself. Missing depth stays unavailable; image points never supply
 * a substitute z coordinate. No history or exercise-specific thresholds. */
object EstimatedBodyGeometry {
    const val convention = "cameraRightDownAway_hipOrigin_v1"
    const val policyVersion = 1

    data class Measurement(val elbowDegrees: Double, val upperArmToTorsoDegrees: Double,
        val armLength: Double, val torsoLength: Double, val hipToWristDistance: Double,
        val shoulderToWristAlongTorso: Double)

    fun measure(pose: PoseResult, side: ArmMeasurement.Side): Measurement? {
        if (pose.estimatedWorldConvention != convention || !pose.imageSize.isValid ||
            pose.timestamp.timescale <= 0) return null
        val body = pose.people.singleOrNull() ?: return null
        val world = body.estimatedWorldLandmarks ?: return null
        if (body.landmarks.size > 64 || world.size > 64 ||
            body.landmarks.map { it.joint }.distinct().size != body.landmarks.size ||
            world.map { it.joint }.distinct().size != world.size) return null
        fun point(joint: PoseJoint): GripVector3? {
            val image = body.landmarks.singleOrNull { it.joint == joint } ?: return null
            val estimate = world.singleOrNull { it.joint == joint } ?: return null
            if (!image.isVisible() || !estimate.isVisible() ||
                image.position.x !in 0.0..pose.imageSize.width ||
                image.position.y !in 0.0..pose.imageSize.height) return null
            return estimate.position.let { GripVector3(it.x, it.y, it.z) }
        }
        val shoulder = point(side.joints[0]) ?: return null
        val elbow = point(side.joints[1]) ?: return null
        val wrist = point(side.joints[2]) ?: return null
        val hip = point(if (side == ArmMeasurement.Side.left) PoseJoint.leftHip else PoseJoint.rightHip) ?: return null
        val upper = elbow - shoulder; val forearm = wrist - elbow; val down = hip - shoulder
        if (listOf(upper.length, forearm.length, down.length).any { !it.isFinite() || it <= 1e-6 }) return null
        val torso = down.unit() ?: return null
        val upperDirection = upper.unit() ?: return null
        val forearmDirection = forearm.unit() ?: return null
        // Unit directions avoid overflow in otherwise finite cross products.
        fun angle(a: GripVector3, b: GripVector3) = atan2(a.cross(b).length, a.dot(b)) * 180 / PI
        val result = Measurement(angle(upperDirection * -1.0, forearmDirection), angle(upperDirection, torso),
            upper.length + forearm.length, down.length, (hip - wrist).length, (wrist - shoulder).dot(torso))
        return result.takeIf { listOf(it.elbowDegrees, it.upperArmToTorsoDegrees,
            it.armLength, it.torsoLength, it.hipToWristDistance, it.shoulderToWristAlongTorso).all { v -> v.isFinite() } }
    }
}
