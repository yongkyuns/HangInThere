package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.hypot

/** Torso direction is independent of hand localization. For the scoped upright
 * camera setup, missing/cropped hips can use the measured 3D shoulder axis and
 * camera up. This does not grant hand identity or qualify a grip prediction. */
object WorkoutGripTorsoView {
    @Serializable data class Assessment(val available: Boolean = false, val supported: Boolean = false,
        val view: String = "unknown", val facingZ: Double? = null, val method: String? = null,
        val reason: String? = null, val alignmentThreshold: Double = WorkoutGripGeometry.minimumCameraFacingAlignment,
        val policyVersion: Int = 1)

    fun assess(o: GripObservation): Assessment {
        fun fail(reason: String) = Assessment(reason=reason)
        if (o.schemaVersion != 1 || o.coordinateConvention != "cameraRightDownAway_v1" || o.failure != null ||
            !o.seconds.isFinite() || !o.imageSize.isValid || o.body.size > 6 || o.trackedBody.size > 6 ||
            o.body.map { it.joint }.distinct().size != o.body.size ||
            o.trackedBody.map { it.joint }.distinct().size != o.trackedBody.size) return fail("invalidTorsoObservation")
        val body = o.body.associateBy { it.joint }; val tracked = o.trackedBody.associateBy { it.joint }
        val short = minOf(o.imageSize.width, o.imageSize.height)
        fun inside(p: Point2D) = p.isFinite && p.x in 0.0..o.imageSize.width && p.y in 0.0..o.imageSize.height
        fun reliable(p: GripBodyPoint?) = p != null && p.score.isFinite() && p.score in .5..1.0 && inside(p.position) && p.world.isFinite
        val shoulders = listOf(GripBodyJoint.leftShoulder, GripBodyJoint.rightShoulder)
        for (joint in shoulders) {
            val p = body[joint]; val primary = tracked[joint]
            if (!reliable(p) || primary == null || !primary.score.isFinite() || primary.score !in .5..1.0 || !inside(primary.position))
                return fail("torsoShouldersUnavailable")
            if (hypot(p!!.position.x-primary.position.x, p.position.y-primary.position.y) > short*.12)
                return fail("torsoIdentityMismatch")
        }
        // A confident contradictory hip remains negative identity evidence;
        // cropped/weak hips are not fabricated into an orientation cue.
        val hips = listOf(GripBodyJoint.leftHip, GripBodyJoint.rightHip)
        for (joint in hips) {
            val p = body[joint]; val primary = tracked[joint]
            if (reliable(p) && primary != null && primary.score.isFinite() && primary.score in .5..1.0 && inside(primary.position) &&
                hypot(p!!.position.x-primary.position.x, p.position.y-primary.position.y) > short*.12)
                return fail("torsoIdentityMismatch")
        }
        val ls = body.getValue(GripBodyJoint.leftShoulder); val rs = body.getValue(GripBodyJoint.rightShoulder)
        val across = ls.world-rs.world
        if (across.length !in .08..1.0) return fail("torsoScaleUnavailable")
        val hasHips = hips.all { reliable(body[it]) }
        val up: GripVector3
        val method: String
        if (hasHips) {
            up = (ls.world+rs.world-body.getValue(GripBodyJoint.leftHip).world-body.getValue(GripBodyJoint.rightHip).world)*.5
            if (up.length !in .12..1.2) return fail("torsoAxisUnavailable")
            method = "shouldersAndHips"
        } else {
            // Explicit upright-camera assumption, not a learned confidence.
            val imageSpan = hypot(ls.position.x-rs.position.x, ls.position.y-rs.position.y)
            if (imageSpan <= 0 || abs(ls.position.y-rs.position.y)/imageSpan > .5)
                return fail("uprightShoulderViewRequired")
            up = GripVector3(0.0, -1.0, 0.0)
            method = "uprightShoulders"
        }
        val left = across.unit() ?: return fail("torsoAxisUnavailable")
        val vertical = up.unit() ?: return fail("torsoAxisUnavailable")
        if (abs(left.dot(vertical)) > .6) return fail("torsoAxisUnavailable")
        val facing = left.cross(vertical).unit() ?: return fail("torsoAxisUnavailable")
        val supported = abs(facing.z) >= WorkoutGripGeometry.minimumCameraFacingAlignment
        return Assessment(true, supported, if (!supported) "unknown" else if (facing.z < 0) "front" else "rear",
            facing.z, method, if (supported) null else "frontRearViewRequired")
    }
}
