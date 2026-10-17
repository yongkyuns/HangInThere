package dev.hanginthere.android.session

import android.content.Context
import dev.hanginthere.core.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/** UI-owned settings; targets are stable until edited, independent of inference. */
class WorkoutGoalStore(context: Context, preferenceName: String = "workout-goals") {
    private val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    fun report(history: List<WorkoutSetRecord>, exercise: ExerciseCounter.Exercise): WorkoutProgressReport {
        return reportEvidence(history.map(WorkoutProgression::evidence),exercise)
    }
    fun reportEvidence(history: List<WorkoutProgressEvidence>, exercise: ExerciseCounter.Exercise): WorkoutProgressReport {
        val key=exercise.name
        val goal=preferences.getString(key,null)?.let { WorkoutAnalysis.json.decodeFromString<WorkoutGoal>(it) }
            ?: WorkoutProgression.reportEvidence(history,exercise).goal.copy(createdAtEpochMilliseconds=System.currentTimeMillis()).also {
                preferences.edit().putString(key,WorkoutAnalysis.json.encodeToString(it)).apply()
            }
        return WorkoutProgression.reportEvidence(history,exercise,goal)
    }
    fun setGoal(exercise: ExerciseCounter.Exercise, metric: WorkoutGoalMetric, target: Double) {
        val goal=WorkoutGoal(exercise,metric,target,System.currentTimeMillis())
        goal.validate()
        preferences.edit().putString(exercise.name,WorkoutAnalysis.json.encodeToString(goal)).apply()
    }
}
