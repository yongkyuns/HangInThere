package dev.hanginthere.core

import kotlin.math.hypot
import kotlin.math.min

/** Retains observed setup through a brief single-arm dropout, never readiness,
 * identity dwell, apparatus observations or rep evidence. One current arm must
 * still agree with its last bilateral grip and exercise. Missing frames cannot
 * renew the deadline. Camera qualification remains owned by WorkoutRuntime. */
internal class WorkoutSetupRecovery {
    private var bilateral: PoseResult? = null
    private var lastTime: PresentationTime? = null

    fun retain(pose: PoseResult, exercise: ExerciseCounter.Exercise?): Boolean {
        val gap = lastTime?.let { pose.timestamp.elapsedSeconds(it) }
        if (!pose.timestamp.seconds.isFinite() || gap?.let { !it.isFinite() || it <= 0 } == true) {
            reset(); return false
        }
        lastTime = pose.timestamp
        val previous = bilateral
        val arms = PullUpArmMeasurement(pose).arms
        if (arms.size == 2) {
            // The current full pose may be the frame that first qualifies
            // identity. Remember it, but retain nothing until the owner reports
            // an actually qualified exercise on a subsequent observation.
            val candidate = AutomaticExerciseDetection.apparatusCandidate(pose)
            bilateral = pose.takeIf { candidate != null && (exercise == null || candidate == exercise) }
            return false
        }
        if (exercise == null || previous == null || pose.people.size != 1 || !pose.imageSize.isValid ||
            pose.imageSize != previous.imageSize || arms.size != 1) {
            bilateral = null; return false
        }
        val elapsed = pose.timestamp.elapsedSeconds(previous.timestamp)
        val arm = arms.single()
        val prior = PullUpArmMeasurement(previous).arms.singleOrNull { it.side == arm.side }
        val short = min(pose.imageSize.width, pose.imageSize.height)
        val compatible = if (exercise == ExerciseCounter.Exercise.pullUp)
            arm.shoulder.y - arm.wrist.y > short * .08
        else arm.estimate.elbowDegrees + 1e-6 >= DipSupportMeasurement.startingDegrees &&
            arm.wrist.y - arm.shoulder.y >= .35 * (arm.estimate.upperArmPixels + arm.estimate.forearmPixels)
        if (!elapsed.isFinite() || elapsed <= 0 || elapsed > HandsFreeSetStart.maximumFrameGap || prior == null || !compatible ||
            hypot(arm.wrist.x - prior.wrist.x, arm.wrist.y - prior.wrist.y) > short * .02 ||
            hypot(arm.shoulder.x - prior.shoulder.x, arm.shoulder.y - prior.shoulder.y) > short * .08) {
            bilateral = null; return false
        }
        return true
    }

    fun reset() { bilateral = null; lastTime = null }
}

/** Native adapters supply raw current-frame joints; recovery policy stays shared.
 * Source ticks are retained so rounding cannot extend the recovery deadline. */
class WorkoutSetupRecoveryBridge {
    private val recovery = WorkoutSetupRecovery()
    @Throws(IllegalArgumentException::class)
    fun retain(sourceValue: Long, sourceTimescale: Int, width: Double, height: Double, peopleCount: Int,
               joints: IntArray, triples: DoubleArray, exercise: String?): Boolean {
        val time = PresentationTime(sourceValue, sourceTimescale)
        val pose = workoutBridgePose(time.seconds, width, height, peopleCount, joints, triples).copy(timestamp = time)
        return recovery.retain(pose, exercise?.let { ExerciseCounter.Exercise.valueOf(it) })
    }
    fun reset() = recovery.reset()
}
