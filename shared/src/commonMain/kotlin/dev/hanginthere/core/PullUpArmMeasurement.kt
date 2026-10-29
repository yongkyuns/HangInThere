package dev.hanginthere.core

import kotlin.math.min
import kotlin.math.hypot

class PullUpArmMeasurement(pose: PoseResult) {
    data class Arm(val side: ArmMeasurement.Side, val estimate: ArmMeasurement.Estimate,
        val shoulder: Point2D, val wrist: Point2D)
    val arms: List<Arm>
    val unavailableReason: ArmMeasurement.UnavailableReason?
    init {
        val measurements = ArmMeasurement.Side.entries.map { ArmMeasurement(pose, it) }
        arms = measurements.mapNotNull { measurement ->
            val estimate = measurement.estimate ?: return@mapNotNull null
            val shoulder = pose.people.firstOrNull()?.landmark(measurement.side.joints[0])?.position ?: return@mapNotNull null
            val wrist = pose.people.firstOrNull()?.landmark(measurement.side.joints[2])?.position ?: return@mapNotNull null
            Arm(measurement.side, estimate, shoulder, wrist)
        }
        unavailableReason = if (arms.isEmpty()) measurements.firstOrNull()?.unavailableReason else null
    }
    val isExtended: Boolean get() = arms.isNotEmpty() && arms.all { it.estimate.elbowDegrees >= ExerciseCounter.extendedDegrees }
    fun hasGrip(edge: BarSegment, imageSize: ImageSize): Boolean {
        if (arms.isEmpty() || !edge.isValid || !imageSize.isValid) return false
        val short = min(imageSize.width, imageSize.height)
        return arms.all { it.shoulder.y - it.wrist.y > short * 0.08 &&
            edge.distance(it.wrist) <= short * 0.085 && edge.midpoint.y <= it.wrist.y + short * 0.015 }
    }
    companion object {
        /** Same spatial resolution as the movement owner's front/rear sample.
         * Hand spacing is not an exercise style requirement. Both actual arm
         * chains must remain distinct; collapsed/crossed SDK chains cannot
         * guide acquisition or associate an observed reference. */
        fun hasDistinctFrontRearArms(pose: PoseResult): Boolean {
            val person = pose.people.singleOrNull() ?: return false
            if (person.landmarks.size > 64 || person.landmarks.map { it.joint }.distinct().size != person.landmarks.size) return false
            val arms = PullUpArmMeasurement(pose).arms.sortedBy { it.wrist.x }
            if (arms.size != 2) return false
            val floor = ArmMeasurement.minimumSegmentFraction * min(pose.imageSize.width, pose.imageSize.height)
            val leftElbow = person.landmark(PoseJoint.leftElbow)!!.position
            val rightElbow = person.landmark(PoseJoint.rightElbow)!!.position
            return arms[1].wrist.x - arms[0].wrist.x >= 2 * floor &&
                arms[1].shoulder.x - arms[0].shoulder.x >= floor &&
                hypot(leftElbow.x-rightElbow.x, leftElbow.y-rightElbow.y) >= floor
        }
    }
}
