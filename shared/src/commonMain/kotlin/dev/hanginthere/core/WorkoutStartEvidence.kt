package dev.hanginthere.core

/** Current-frame starting geometry, separate from retained exercise identity,
 * apparatus acquisition and source-clock dwell. Never reuses an earlier pose. */
object WorkoutStartEvidence {
    fun hasStartingPose(pose: PoseResult, exercise: ExerciseCounter.Exercise, automatic: Boolean,
                        side: ArmMeasurement.Side, reference: BarSegment?): Boolean {
        if (!pose.timestamp.seconds.isFinite()) return false
        if (automatic && AutomaticExerciseDetection.candidate(pose) != exercise) return false
        val edge = reference ?: return false
        if (!edge.isValid) return false
        return when (exercise) {
            ExerciseCounter.Exercise.pullUp -> HandsFreeSetStart.isStartingPosition(pose, edge)
            // An explicitly selected/manual dip retains its selected-arm path.
            ExerciseCounter.Exercise.dip -> DipSupportMeasurement.create(pose, side, edge)?.isStartingSupport == true
        }
    }
}
