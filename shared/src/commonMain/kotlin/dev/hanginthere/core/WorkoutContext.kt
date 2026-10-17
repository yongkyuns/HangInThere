package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable enum class WorkoutIntent { ordinary, assisted, negative, hold, practice }
@Serializable enum class WorkoutAssistance { none, band, machine, partner, footSupport, unknown }
@Serializable enum class WorkoutReadiness { unrecorded, low, usual, high }

/** Athlete-supplied context, never inferred from pixels or interpreted as medical data. */
@Serializable data class WorkoutContext(
    val intent: WorkoutIntent = WorkoutIntent.ordinary,
    val assistance: WorkoutAssistance = WorkoutAssistance.unknown,
    val assistanceLabel: String = "",
    val addedLoadKg: Double? = null,
    val equipment: String = "",
    val cameraView: String = "",
    val variation: String = "",
    val effort: Int? = null,
    val readiness: WorkoutReadiness = WorkoutReadiness.unrecorded,
    val recoveryNote: String = "",
) {
    fun validate() {
        require(addedLoadKg == null || addedLoadKg.isFinite() && addedLoadKg in 0.0..500.0)
        require(effort == null || effort in 0..10)
        require(listOf(assistanceLabel, equipment, cameraView, variation).all { it.length <= 64 && it.none(Char::isISOControl) })
        require(recoveryNote.length <= 240 && recoveryNote.none { it.isISOControl() && it != '\n' })
        require(assistance != WorkoutAssistance.none || assistanceLabel.isEmpty())
    }
    val comparable: Boolean get() = assistance != WorkoutAssistance.unknown && addedLoadKg != null &&
        equipment.isNotBlank() && cameraView.isNotBlank() &&
        (assistance == WorkoutAssistance.none || assistanceLabel.isNotBlank())

    /** JSON array avoids delimiter collisions in user labels. Effort/readiness are not comparison keys. */
    fun comparisonKey(exercise: ExerciseCounter.Exercise, metricVersion: Int, countPolicyVersion: Int): String? {
        validate()
        if (!comparable) return null
        return WorkoutRecordCodec.json.encodeToString(listOf(exercise.name, intent.name, assistance.name,
            assistanceLabel.trim(), addedLoadKg.toString(), equipment.trim(), cameraView.trim(), variation.trim(),
            metricVersion.toString(), countPolicyVersion.toString()))
    }
}

/** Explicit exercise profiles reuse lifecycle, evidence and library interfaces. No new count thresholds. */
@Serializable data class WorkoutExerciseProfile(val id: ExerciseCounter.Exercise, val title: String,
    val holdPhase: WorkoutMotionPhase, val holdTitle: String, val supportedMetrics: List<WorkoutGoalMetric>,
    val countPolicyVersion: Int, val phaseMetricVersion: Int, val unverified: List<String>)

object WorkoutExerciseProfiles {
    val all: List<WorkoutExerciseProfile> = ExerciseCounter.Exercise.entries.map {
        WorkoutExerciseProfile(it, it.title,
            if (it == ExerciseCounter.Exercise.pullUp) WorkoutMotionPhase.hang else WorkoutMotionPhase.support,
            if (it == ExerciseCounter.Exercise.pullUp) "Hang" else "Top support",
            WorkoutGoalMetric.entries, ExerciseCounter.policyVersion, WorkoutPhaseAnalysis.metricVersion,
            if (it == ExerciseCounter.Exercise.pullUp) listOf("chin clearance", "anatomical form") else listOf("dip depth", "lockout", "anatomical form"))
    }
    fun profile(exercise: ExerciseCounter.Exercise) = all.single { it.id == exercise }
}
