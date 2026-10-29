package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

@Serializable data class WorkoutAfterSetReport(val title: String, val measuredCompletedReps: Int,
    val practiceAttempts: Int, val observedHoldSeconds: Double?, val observation: String, val spokenCue: String,
    val policyVersion: Int = 1, val goalTarget: Double? = null, val goalValue: Double? = null, val goalMetric: WorkoutGoalMetric? = null)
object WorkoutFeedback {
    fun summary(record: WorkoutSetRecord): WorkoutAfterSetReport {
        require(record.endReason != "running")
        val hold = record.activity?.let { if (record.exercise == ExerciseCounter.Exercise.pullUp) it.longestHangSeconds else it.longestSupportSeconds }
        val incomplete = record.reps.count { it.isPracticeForAnalytics }
        val interrupted = record.endReason !in listOf("manual", "disengaged", "sourceEnded")
        val title = if (interrupted) "Tracking stopped" else if (record.hasPractice) "Set ended" else "No measurable practice"
        val message = when {
            interrupted -> "Available practice is captured. Missing observations do not establish a completed cycle."
            record.completedCount > 0 -> "Review measured return timing and both-arm evidence in your results."
            incomplete > 0 -> "Your attempts are practice. Unfinished returns are excluded from completed-rep metrics."
            (hold ?: 0.0) > 0 -> "Your observed hold is useful practice, even without a completed rep."
            else -> "No measurable practice was recorded. Review camera framing and apparatus visibility."
        }
        val speech = if (!record.hasPractice) "No measurable practice recorded. Check camera framing." else if (interrupted) "Tracking stopped. Available practice captured." else if (record.completedCount > 0)
            "Set ended. ${record.completedCount} completed ${if (record.completedCount == 1) "cycle" else "cycles"}." else "Set ended. Practice captured."
        return WorkoutAfterSetReport(title, record.completedCount, incomplete, hold, message, speech)
    }
    fun summary(state: WorkoutLibraryState, id: String): WorkoutAfterSetReport {
        WorkoutLibrary.validate(state)
        val record = requireNotNull(state.entries.firstOrNull { it.measured.id == id }?.measured)
        val sources = WorkoutLibrary.sourceSets(state)
        val sets = sources.filter { it.sourceSetId == id }
        val key = sets.mapNotNull { it.comparisonKey }.distinct().singleOrNull()
        val goal = state.goals.firstOrNull { it.comparisonKey == key }?.goal?.takeIf { chosen ->
            record.trackedAtEpochMilliseconds?.let { time -> time >= (chosen.createdAtEpochMilliseconds ?: Long.MAX_VALUE) } == true }
        val session = WorkoutInsights.sessions(sources).first { item -> item.setIds.any { part -> sets.any { it.id == part } } }
        val value = goal?.let { WorkoutInsights.goalValue(it, sources.filter { it.id in session.setIds && it.comparisonKey == key }) }
        val measured = sets.sumOf { it.measuredCompletedReps }; val practice = sets.sumOf { it.practiceAttempts }
        val raw = summary(record)
        return raw.copy(title = if (raw.title == "Set ended") "Set recorded" else raw.title,
            measuredCompletedReps = measured, practiceAttempts = practice, observedHoldSeconds = sets.mapNotNull { it.holdSeconds }.maxOrNull(),
            observation = if (measured != raw.measuredCompletedReps || practice != raw.practiceAttempts) "Review your corrected classification in the workout library; original measurements are preserved." else raw.observation,
            spokenCue = if (measured != raw.measuredCompletedReps || practice != raw.practiceAttempts) "$measured measured completed cycles. Results include your reviews." else raw.spokenCue,
            goalTarget = goal?.target, goalValue = value, goalMetric = goal?.metric)
    }
}
/** Debounced presentation cues only. Monotonic clock never changes tracking policy. */
class WorkoutTrackingFeedback {
    private var setId: String? = null
    private var pausedAt: Double? = null
    private var announced = false
    fun observe(id: String, running: Boolean, trackingIssue: String?, nowSeconds: Double): String? {
        if (!nowSeconds.isFinite()) return null
        if (setId != id) { setId = id; pausedAt = null; announced = false }
        if (!running) { pausedAt = null; return null }
        if (trackingIssue == null) {
            pausedAt = null
            return if (announced) { announced = false; "Tracking resumed" } else null
        }
        if (pausedAt == null) pausedAt = nowSeconds
        if (!announced && nowSeconds - pausedAt!! >= .6) { announced = true; return "Tracking paused. Check camera visibility." }
        return null
    }
}
class WorkoutCompletionFeedback {
    private var lastSetId: String? = null
    fun begin(setId: String?, live: Boolean, foreground: Boolean): Boolean {
        if (!live || !foreground || setId == null || setId == lastSetId) return false
        lastSetId = setId
        return true
    }
}
class WorkoutFeedbackBridge {
    @Throws(IllegalArgumentException::class) fun librarySummaryJson(stateJson: String, setId: String): String = WorkoutRecordCodec.json.encodeToString(
        WorkoutFeedback.summary(WorkoutRecordCodec.json.decodeFromString(stateJson), setId))
    @Throws(IllegalArgumentException::class) fun summaryJson(recordJson: String): String = WorkoutRecordCodec.json.encodeToString(
        WorkoutFeedback.summary(WorkoutRecordCodec.json.decodeFromString(recordJson)))
}
