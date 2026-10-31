package dev.hanginthere.core

/** Current-frame starting geometry, separate from retained exercise identity,
 * apparatus acquisition and source-clock dwell. Never reuses an earlier pose. */
object WorkoutStartEvidence {
    const val policyVersion = 2
    fun hasStartingPose(pose: PoseResult, exercise: ExerciseCounter.Exercise, automatic: Boolean,
                        side: ArmMeasurement.Side, reference: BarSegment?): Boolean {
        if (!pose.timestamp.seconds.isFinite()) return false
        val edge = reference ?: return false
        if (!edge.isValid) return false
        return when (exercise) {
            ExerciseCounter.Exercise.pushUp -> false // Owned by the side-view preview, without apparatus.
            // The owner has already qualified exercise identity, the observed
            // reference and camera. Do not discover the exercise again here.
            // Automatic starts still need BOTH current measurable chains,
            // full extension and grip beside that actual finite reference.
            ExerciseCounter.Exercise.pullUp -> PullUpArmMeasurement(pose).let { arms ->
                (!automatic || arms.arms.size == 2) && arms.isExtended && arms.hasGrip(edge, pose.imageSize)
            }
            // An explicitly selected/manual dip retains its selected-arm path.
            ExerciseCounter.Exercise.dip -> (!automatic || AutomaticExerciseDetection.candidate(pose) == exercise) &&
                DipSupportMeasurement.create(pose, side, edge)?.isStartingSupport == true
        }
    }
}
