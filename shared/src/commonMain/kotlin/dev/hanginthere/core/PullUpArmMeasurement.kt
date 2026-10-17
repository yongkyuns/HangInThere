package dev.hanginthere.core

import kotlin.math.min

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
}
