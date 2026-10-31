package dev.hanginthere.core

import kotlinx.serialization.Serializable

/** Athlete-entered results remain separate from camera measurements. */
@Serializable data class WorkoutLoggedSet(val id: String, val exercise: String, val reps: Int,
    val load: Double? = null, val unit: String = "lb", val method: String = "entry",
    val startedAtEpochMilliseconds: Long? = null, val endedAtEpochMilliseconds: Long? = null)
@Serializable data class WorkoutLog(val id: String, val epochMilliseconds: Long,
    val sets: List<WorkoutLoggedSet> = emptyList(), val updatedAtEpochMilliseconds: Long)
@Serializable data class WorkoutLogDeletion(val id: String, val deletedAtEpochMilliseconds: Long)

object WorkoutLogValidation {
    fun validate(log: WorkoutLog) {
        require(WorkoutCorrections.safeId(log.id) && log.epochMilliseconds > 0 && log.updatedAtEpochMilliseconds > 0)
        require(log.sets.size <= 1000 && log.sets.map { it.id }.distinct().size == log.sets.size)
        log.sets.forEach { set ->
            require(WorkoutCorrections.safeId(set.id) && WorkoutLogPolicy.validSet(set.exercise, set.reps, set.load, set.unit))
            require(set.method in listOf("entry", "tap"))
            require((set.startedAtEpochMilliseconds == null) == (set.endedAtEpochMilliseconds == null))
            require(set.startedAtEpochMilliseconds == null || set.startedAtEpochMilliseconds > 0 &&
                set.endedAtEpochMilliseconds!! >= set.startedAtEpochMilliseconds)
        }
    }
}

@Serializable data class WorkoutLogProgressPoint(val id: String, val epochMilliseconds: Long, val reps: Int)
@Serializable data class WorkoutLogProgress(val setCount: Int, val reps: Int, val bestReps: Int,
    val workouts: List<WorkoutLogProgressPoint>, val recentSets: List<WorkoutLoggedSet>)

/** Entry, tap-count and camera-linked logs use one policy on both platforms. */
object WorkoutLogPolicy {
    val suggestedExercises = listOf("Pull-ups", "Dips", "Bench press", "EZ curls", "Push-ups", "Squats", "Rows")
    fun exerciseKey(name: String): String = name.trim().lowercase()
    fun cameraExercise(name: String): ExerciseCounter.Exercise? = when (name.lowercase().filter(Char::isLetter)) {
        "pullup", "pullups", "chinup", "chinups" -> ExerciseCounter.Exercise.pullUp
        "dip", "dips", "parallelbardip", "parallelbardips" -> ExerciseCounter.Exercise.dip
        else -> null
    }
    fun validSet(exercise: String, reps: Int?, load: Double?, unit: String): Boolean =
        exercise.isNotBlank() && exercise.length <= 80 && exercise.none(Char::isISOControl) &&
        reps != null && reps in 0..1000 && unit in listOf("lb", "kg") &&
        (load == null || load.isFinite() && load in 0.0..2000.0)
    fun convertLoad(value: Double, from: String, to: String): Double {
        require(value.isFinite() && value >= 0 && from in listOf("lb", "kg") && to in listOf("lb", "kg"))
        return if (from == to) value else if (from == "lb") value * 0.45359237 else value / 0.45359237
    }
    fun lastSet(exercise: String, history: List<WorkoutLog>): WorkoutLoggedSet? = history.sortedByDescending { it.epochMilliseconds }
        .asSequence().flatMap { it.sets.asReversed() }.firstOrNull { exerciseKey(it.exercise) == exerciseKey(exercise) }
    fun prefill(exercise: String, current: WorkoutLog, history: List<WorkoutLog>): WorkoutLoggedSet? =
        current.sets.lastOrNull { exerciseKey(it.exercise) == exerciseKey(exercise) } ?: lastSet(exercise, history.filter { it.id != current.id })
    fun restBefore(log: WorkoutLog, setId: String): Double? {
        val index = log.sets.indexOfFirst { it.id == setId }
        if (index <= 0) return null
        val start = log.sets[index].startedAtEpochMilliseconds ?: return null
        val end = log.sets[index - 1].endedAtEpochMilliseconds ?: return null
        return if (start >= end) (start - end) / 1000.0 else null
    }
    fun progress(history: List<WorkoutLog>, exercise: String): WorkoutLogProgress {
        val rows = history.sortedByDescending { it.epochMilliseconds }.flatMap { log ->
            log.sets.filter { exerciseKey(it.exercise) == exerciseKey(exercise) } }
        val points = history.sortedBy { it.epochMilliseconds }.mapNotNull { log ->
            val matching = log.sets.filter { exerciseKey(it.exercise) == exerciseKey(exercise) }
            if (matching.isEmpty()) null else WorkoutLogProgressPoint(log.id, log.epochMilliseconds, matching.sumOf { it.reps }) }
        return WorkoutLogProgress(rows.size, rows.sumOf { it.reps }, rows.maxOfOrNull { it.reps } ?: 0, points, rows.take(30))
    }
    fun saveCommand(log: WorkoutLog, now: Long): WorkoutLibraryCommand {
        val next = log.copy(updatedAtEpochMilliseconds = maxOf(log.updatedAtEpochMilliseconds + 1, now))
        WorkoutLogValidation.validate(next)
        return WorkoutLibraryCommand("saveWorkoutLog", atEpochMilliseconds = next.updatedAtEpochMilliseconds, workoutLog = next)
    }
    fun cameraLinkCommand(state: WorkoutLibraryState, setId: String, workoutId: String, load: Double?, unit: String, now: Long): WorkoutLibraryCommand {
        require(state.workoutLogs.any { it.id == workoutId }) { "Workout is no longer available" }
        require(state.entries.any { it.measured.id == setId } && state.deletions.none { it.setId == setId }) { "Camera set is no longer available" }
        require(load == null || load.isFinite() && load in 0.0..2000.0)
        require(unit in listOf("lb", "kg"))
        val prior = WorkoutCorrections.current(state.reviews, setId)
        val context = if (load == null) prior.context else prior.context.copy(addedLoadKg = convertLoad(load, unit, "kg"))
        return WorkoutLibraryCommand("review", setId = setId, correction = prior.copy(workoutId = workoutId, context = context), atEpochMilliseconds = now)
    }
}

class WorkoutLogBridge {
    fun exerciseKey(name: String): String = WorkoutLogPolicy.exerciseKey(name)
    fun cameraExercise(name: String): String? = WorkoutLogPolicy.cameraExercise(name)?.name
    fun suggestedExercises(): List<String> = WorkoutLogPolicy.suggestedExercises
    fun validSet(exercise: String, reps: Int?, load: Double?, unit: String) = WorkoutLogPolicy.validSet(exercise, reps, load, unit)
    @Throws(IllegalArgumentException::class)
    fun convertLoad(value: Double, from: String, to: String) = WorkoutLogPolicy.convertLoad(value, from, to)
    @Throws(IllegalArgumentException::class)
    fun lastSetJson(exercise: String, historyJson: String): String? = WorkoutLogPolicy.lastSet(exercise,
        WorkoutRecordCodec.json.decodeFromString<List<WorkoutLog>>(historyJson))?.let { WorkoutRecordCodec.json.encodeToString(WorkoutLoggedSet.serializer(), it) }
    @Throws(IllegalArgumentException::class)
    fun prefillJson(exercise: String, currentJson: String, historyJson: String): String? = WorkoutLogPolicy.prefill(exercise,
        WorkoutRecordCodec.json.decodeFromString<WorkoutLog>(currentJson), WorkoutRecordCodec.json.decodeFromString<List<WorkoutLog>>(historyJson))
        ?.let { WorkoutRecordCodec.json.encodeToString(WorkoutLoggedSet.serializer(), it) }
    @Throws(IllegalArgumentException::class)
    fun restBefore(logJson: String, setId: String): Double? = WorkoutLogPolicy.restBefore(WorkoutRecordCodec.json.decodeFromString(logJson), setId)
    @Throws(IllegalArgumentException::class)
    fun progressJson(historyJson: String, exercise: String): String = WorkoutRecordCodec.json.encodeToString(WorkoutLogProgress.serializer(),
        WorkoutLogPolicy.progress(WorkoutRecordCodec.json.decodeFromString(historyJson), exercise))
    @Throws(IllegalArgumentException::class)
    fun saveCommandJson(logJson: String, now: Long): String = WorkoutRecordCodec.json.encodeToString(WorkoutLibraryCommand.serializer(),
        WorkoutLogPolicy.saveCommand(WorkoutRecordCodec.json.decodeFromString(logJson), now))
    @Throws(IllegalArgumentException::class)
    fun cameraLinkCommandJson(stateJson: String, setId: String, workoutId: String, load: Double?, unit: String, now: Long): String =
        WorkoutRecordCodec.json.encodeToString(WorkoutLibraryCommand.serializer(), WorkoutLogPolicy.cameraLinkCommand(
            WorkoutRecordCodec.json.decodeFromString(stateJson), setId, workoutId, load, unit, now))
}
