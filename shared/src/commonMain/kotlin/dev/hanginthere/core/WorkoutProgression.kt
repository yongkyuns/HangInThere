package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlin.math.ceil

@Serializable enum class WorkoutGoalMetric { holdSeconds, completedReps }
@Serializable data class WorkoutGoal(val exercise: ExerciseCounter.Exercise, val metric: WorkoutGoalMetric,
    val target: Double, val createdAtEpochMilliseconds: Long? = null,
    val countMethod: String? = null, val countPolicyVersion: Int? = null) {
    fun validate() {
        require(target.isFinite() && target >= 1)
        require(if (metric == WorkoutGoalMetric.completedReps) target <= 1000 && target == target.toInt().toDouble() else target <= 3600)
        require(countMethod == null && countPolicyVersion == null || exercise == ExerciseCounter.Exercise.dip &&
            countMethod == BilateralDipWorkout.countMethod && countPolicyVersion == BilateralDipMotion.policyVersion)
    }
}
@Serializable data class WorkoutMilestone(val id: String, val title: String, val achieved: Boolean,
    val achievedAtEpochMilliseconds: Long?)
@Serializable data class WorkoutProgressPoint(val id: String, val trackedAtEpochMilliseconds: Long,
    val holdSeconds: Double?, val completedReps: Int, val elbowExcursionDegrees: Double?)
/** Compact durable-history projection; no curves, phases or camera metadata. */
@Serializable data class WorkoutProgressEvidence(val id: String, val exercise: ExerciseCounter.Exercise,
    val trackedAtEpochMilliseconds: Long?, val endReason: String, val hasPractice: Boolean,
    val holdSeconds: Double?, val totalHoldSeconds: Double?, val elbowExcursionDegrees: Double?,
    val completedCount: Int, val incompleteAttempts: Int, val attemptCount: Int,
    val countMethod: String? = null, val countPolicyVersion: Int? = null)
@Serializable data class WorkoutProgressReport(val exercise: ExerciseCounter.Exercise, val holdTitle: String,
    val bestHoldSeconds: Double?, val latestHoldSeconds: Double?, val totalHoldSeconds: Double?,
    val partialAttempts: Int, val completedReps: Int, val bestElbowExcursionDegrees: Double?,
    val goal: WorkoutGoal, val goalBest: Double?, val goalFraction: Double?, val goalAchieved: Boolean,
    val coaching: String, val milestones: List<WorkoutMilestone>, val trend: List<WorkoutProgressPoint>,
    val policyVersion: Int = 2)

/** Evidence-based feedback, not a form/strength assessment. Source clocks never
 * become calendar dates. Suggested targets are editable app defaults, not a
 * training prescription. Recovered interrupted journals can demonstrate holds
 * and attempts, but a rep still needs its actual acceptance and observed return. */
object WorkoutProgression {
    private fun hold(record: WorkoutSetRecord): Double? = record.activity?.let {
        if (record.exercise == ExerciseCounter.Exercise.pullUp) it.longestHangSeconds else it.longestSupportSeconds
    }
    private fun excursion(record: WorkoutSetRecord): Double? = record.reps.mapNotNull(WorkoutEvidence::comparableElbowExcursion).maxOrNull()
    fun evidence(record: WorkoutSetRecord) = WorkoutProgressEvidence(record.id, record.exercise,record.trackedAtEpochMilliseconds,
        record.endReason,record.hasPractice,hold(record),record.activity?.seconds(if(record.exercise==ExerciseCounter.Exercise.pullUp)WorkoutMotionPhase.hang else WorkoutMotionPhase.support),
        excursion(record),record.completedCount,record.reps.count { it.isPracticeForAnalytics },record.reps.size,
        record.countMethod,record.countPolicyVersion)
    /** An explicit legacy goal remains legacy. Without one, use the most recent
     * method's cohort; another method cannot supply its baseline or milestones. */
    private fun cohort(history: List<WorkoutProgressEvidence>, exercise: ExerciseCounter.Exercise, goal: WorkoutGoal? = null): List<WorkoutProgressEvidence> {
        val candidates = history.filter { it.exercise == exercise && it.endReason != "running" && it.hasPractice &&
            (it.countMethod == null || it.countMethod == BilateralDipWorkout.countMethod && it.countPolicyVersion == BilateralDipMotion.policyVersion) }
            .distinctBy { it.id }.sortedByDescending { it.trackedAtEpochMilliseconds ?: Long.MIN_VALUE }
        val method = if (goal != null) goal.countMethod else candidates.firstOrNull()?.countMethod
        val version = if (goal != null) goal.countPolicyVersion else candidates.firstOrNull()?.countPolicyVersion
        return candidates.filter { it.countMethod == method && (method == null || it.countPolicyVersion == version) }
    }
    fun suggestedGoal(history: List<WorkoutSetRecord>, exercise: ExerciseCounter.Exercise): WorkoutGoal = suggestedEvidenceGoal(history.map(::evidence),exercise)
    private fun suggestedEvidenceGoal(history: List<WorkoutProgressEvidence>, exercise: ExerciseCounter.Exercise): WorkoutGoal {
        val sets = cohort(history, exercise)
        val reps = sets.maxOfOrNull { it.completedCount } ?: 0
        val method = sets.firstOrNull()?.countMethod
        val version = sets.firstOrNull()?.countPolicyVersion.takeIf { method != null }
        return if (reps > 0) WorkoutGoal(exercise, WorkoutGoalMetric.completedReps, (reps+1).coerceAtMost(1000).toDouble(),
            countMethod = method, countPolicyVersion = version)
        else {
            val best = sets.mapNotNull { it.holdSeconds }.maxOrNull() ?: 0.0
            WorkoutGoal(exercise, WorkoutGoalMetric.holdSeconds, if (best > 0) ceil(best+maxOf(1.0,best*.1)).coerceAtMost(3600.0) else 5.0,
                countMethod = method, countPolicyVersion = version)
        }
    }
    fun report(history: List<WorkoutSetRecord>, exercise: ExerciseCounter.Exercise, goal: WorkoutGoal? = null): WorkoutProgressReport =
        reportEvidence(history.map(::evidence),exercise,goal)
    fun reportEvidence(history: List<WorkoutProgressEvidence>, exercise: ExerciseCounter.Exercise, goal: WorkoutGoal? = null): WorkoutProgressReport {
        goal?.validate(); require(goal == null || goal.exercise == exercise)
        val sets = cohort(history, exercise, goal)
        val goalValue = goal ?: suggestedEvidenceGoal(sets,exercise)
        val holds = sets.mapNotNull { it.holdSeconds }
        val best = holds.maxOrNull()
        val total = sets.mapNotNull { it.totalHoldSeconds }
            .takeIf { it.isNotEmpty() }?.sum()
        // Targets created today cannot be completed by an older saved workout.
        val eligible = sets.filter { goalValue.createdAtEpochMilliseconds == null || it.trackedAtEpochMilliseconds?.let { time -> time >= goalValue.createdAtEpochMilliseconds } == true }
        val goalBest = if(goalValue.metric == WorkoutGoalMetric.holdSeconds) eligible.mapNotNull { it.holdSeconds }.maxOrNull()
            else eligible.maxOfOrNull { it.completedCount }?.toDouble()
        val partial = sets.sumOf { it.incompleteAttempts }
        val completed = sets.sumOf { it.completedCount }
        val holdTitle = if(exercise == ExerciseCounter.Exercise.pullUp) "Hang" else "Top support"
        val coach = when {
            sets.isEmpty() -> "Start with a visible ${holdTitle.lowercase()} to establish your baseline. Your holds and attempts count as practice."
            goalBest != null && goalBest >= goalValue.target -> "Goal reached. Review your recent sets, then choose your next target when you are ready."
            sets.first().holdSeconds == null -> "This older set has no phase measurements. Track a new set to establish your hold baseline."
            best == null || best <= 0 -> "Keep both arms and your grip visible to measure ${holdTitle.lowercase()} time. Tracking gaps never add hold time."
            completed == 0 && partial > 0 -> "Your ${holdTitle.lowercase()} and movement attempts are recorded. Review each attempt's evidence; an unrecorded return or tracking interruption cannot establish a completed cycle."
            completed == 0 -> "You have a ${format(best)} second ${holdTitle.lowercase()} baseline. Use an adjustable hold target to track your next improvement."
            goalValue.countMethod != null -> "Compare observed image cycles, return timing and both-arm measurements. Anatomical depth, form and load are unverified."
            else -> "Compare completed reps, hold time and both-arm range across sets. Increase your target when you are ready."
        }
        fun milestone(id: String, title: String, predicate: (WorkoutProgressEvidence)->Boolean): WorkoutMilestone {
            val matches = sets.filter(predicate)
            val date = if(matches.any { it.trackedAtEpochMilliseconds == null }) null else matches.mapNotNull { it.trackedAtEpochMilliseconds }.minOrNull()
            return WorkoutMilestone(id,title,matches.isNotEmpty(),date)
        }
        val milestones = listOf(milestone("firstPractice","First recorded practice") { it.hasPractice },
            milestone("firstAttempt","First movement attempt") { it.attemptCount > 0 }) +
            listOf(5,10,20,30).map { duration -> milestone("hold:$duration","$duration s ${holdTitle.lowercase()}") { (it.holdSeconds ?: 0.0) >= duration } } +
            listOf(1,3,5,10).map { reps -> milestone("reps:$reps",
                if (goalValue.countMethod != null) { if (reps == 1) "First observed image cycle" else "$reps observed image cycles in a set" }
                else if(reps==1) "First completed rep" else "$reps completed reps in a set") { it.completedCount >= reps } }
        return WorkoutProgressReport(exercise,holdTitle,best,sets.firstOrNull()?.holdSeconds,total,partial,completed,
            sets.mapNotNull { it.elbowExcursionDegrees }.maxOrNull(),goalValue,goalBest,
            goalBest?.let { (it/goalValue.target).coerceIn(0.0,1.0) },goalBest?.let { it>=goalValue.target } == true,
            coach,milestones,sets.filter { it.trackedAtEpochMilliseconds != null }.take(20).reversed().map {
                WorkoutProgressPoint(it.id,it.trackedAtEpochMilliseconds!!,it.holdSeconds,it.completedCount,it.elbowExcursionDegrees)
            })
    }
    private fun format(value: Double) = (value*10).toInt()/10.0
}

/** JSON only at review/goal actions. Native apps own atomic storage and UI. */
class WorkoutProgressBridge {
    fun phaseTitle(phase: String): String = WorkoutMotionPhase.valueOf(phase).title
    @Throws(IllegalArgumentException::class)
    fun evidenceJson(recordJson: String): String = WorkoutAnalysis.json.encodeToString(WorkoutProgression.evidence(WorkoutRecordCodec.json.decodeFromString(recordJson)))
    @Throws(IllegalArgumentException::class)
    fun reportEvidenceJson(evidenceJson: String, exercise: String, goalJson: String?): String = WorkoutAnalysis.json.encodeToString(
        WorkoutProgression.reportEvidence(WorkoutRecordCodec.json.decodeFromString(evidenceJson),ExerciseCounter.Exercise.valueOf(exercise),
            goalJson?.let { WorkoutRecordCodec.json.decodeFromString<WorkoutGoal>(it) }))
    @Throws(IllegalArgumentException::class)
    fun reportJson(historyJson: String, exercise: String, goalJson: String?): String = WorkoutAnalysis.json.encodeToString(
        WorkoutProgression.report(WorkoutRecordCodec.json.decodeFromString(historyJson),ExerciseCounter.Exercise.valueOf(exercise),
            goalJson?.let { WorkoutRecordCodec.json.decodeFromString<WorkoutGoal>(it) }))
    @Throws(IllegalArgumentException::class)
    fun goalJson(exercise: String, metric: String, target: Double, createdAtEpochMilliseconds: Long): String {
        return goalForMethodJson(exercise, metric, target, createdAtEpochMilliseconds, null, 0)
    }
    @Throws(IllegalArgumentException::class)
    fun goalForMethodJson(exercise: String, metric: String, target: Double, createdAtEpochMilliseconds: Long,
        countMethod: String?, countPolicyVersion: Int): String {
        val goal = WorkoutGoal(ExerciseCounter.Exercise.valueOf(exercise),WorkoutGoalMetric.valueOf(metric),target,createdAtEpochMilliseconds,
            countMethod, countPolicyVersion.takeIf { it > 0 })
        goal.validate(); return WorkoutAnalysis.json.encodeToString(goal)
    }
}
