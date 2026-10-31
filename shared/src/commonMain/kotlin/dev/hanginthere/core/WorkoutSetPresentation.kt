package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlin.math.*

/** Relative observed phase-time proxy, not measured force, capacity, fatigue or calories. */
object WorkoutSetPresentation {
    const val recoverySeconds = 60.0
    @Serializable data class Point(val id: Int, val seconds: Double, val units: Double, val segment: Int)
    @Serializable data class Demand(val points: List<Point>, val recovery: List<Point>, val units: Double,
        val isPartial: Boolean, val hasGaps: Boolean)
    fun weight(phase: String): Double? = when (phase) {
        "pulling", "rising", "peakHold", "bottomHold", "effortHold" -> 1.0
        "lowering" -> .5
        "hang", "support" -> .25
        else -> null
    }
    fun recovered(units: Double, seconds: Double): Double =
        if (units.isFinite() && seconds.isFinite()) max(0.0, units)*exp(-max(0.0, seconds)/recoverySeconds) else 0.0
    fun demand(activity: WorkoutActivity?): Demand {
        val spans = activity?.phases.orEmpty().filter { it.startedAt.isFinite() && it.endedAt.isFinite() && it.endedAt > it.startedAt }.sortedBy { it.startedAt }
        val partial = activity?.timelineTruncated == true
        val origin = spans.firstOrNull()?.startedAt ?: return Demand(emptyList(), emptyList(), 0.0, partial, false)
        val points = mutableListOf<Point>(); var total = 0.0; var credited = 0.0; var cursor = origin
        spans.forEachIndexed { index, span ->
            val weight = weight(span.phase.name) ?: return@forEachIndexed
            if (!span.observedSeconds.isFinite() || span.observedSeconds <= 0) return@forEachIndexed
            val start = max(cursor, max(span.startedAt, span.endedAt-span.observedSeconds))
            if (span.endedAt <= start) return@forEachIndexed
            points += Point(points.size, start-origin, total, index)
            total += (span.endedAt-start)*weight; credited += span.endedAt-start
            points += Point(points.size, span.endedAt-origin, total, index); cursor = span.endedAt
        }
        val end = spans.maxOf { it.endedAt }-origin
        return Demand(points, if (points.isEmpty()) emptyList() else (0..30).map {
            Point(it, end+it, recovered(total, it.toDouble()), 0)
        }, total, partial, end-credited > .05)
    }
    @Serializable data class TempoRep(val id: String, val ordinal: Int, val outward: Double, val returning: Double,
        val lifting: Double, val range: Double?, val reliable: Boolean)
    @Serializable data class Tempo(val reps: List<TempoRep>, val change: Double?, val comparisonNote: String)
    fun tempo(record: WorkoutSetRecord): Tempo {
        val reps = record.completedReps.sortedBy { it.ordinal }.mapNotNull { rep ->
            val turn = rep.extremeAt ?: return@mapNotNull null; val returned = rep.returnedAt ?: return@mapNotNull null
            val outward = turn-rep.startedAt; val returning = returned-turn
            if (!outward.isFinite() || !returning.isFinite() || outward <= 0 || returning <= 0) return@mapNotNull null
            val angles = listOf(rep.left, rep.right).mapNotNull { metric ->
                val lo = metric.minimum; val hi = metric.maximum
                if (lo == null || hi == null || !lo.isFinite() || !hi.isFinite() || hi <= lo || (metric.coverage ?: 0.0) < .8) null else hi-lo
            }
            TempoRep(rep.id, rep.ordinal, outward, returning, if (record.exercise == ExerciseCounter.Exercise.pullUp) outward else returning,
                angles.takeIf { it.isNotEmpty() }?.average(), (rep.durationCoverage ?: 0.0) >= .8 && !rep.curveTruncated)
        }
        if (reps.size < 3) return Tempo(reps, null, "Three comparable reps are needed to show a tempo trend.")
        val comparison = reps.take(2)+reps.last()
        if (comparison.any { !it.reliable || (it.range ?: 0.0) < 20 })
            return Tempo(reps, null, "Tracking coverage is insufficient for a tempo comparison.")
        val referenceRange = comparison.take(2).map { it.range!! }.average()
        if (comparison.any { abs(it.range!!/referenceRange-1) > .2 })
            return Tempo(reps, null, "Movement range changed; these reps aren't comparable.")
        return Tempo(reps, reps.last().lifting/reps.take(2).map { it.lifting }.average()-1,
            "Last rep versus the first two, with similar observed range. Intentional pacing can also change tempo.")
    }
    @Serializable data class Keyframe(val repId: String, val ordinal: Int, val phase: String, val sourceSeconds: Double, val clipSeconds: Double)
    fun keyframes(record: WorkoutSetRecord, clipStart: Double): List<Keyframe> {
        if (!clipStart.isFinite()) return emptyList()
        val end = record.completedReps.mapNotNull { it.returnedAt }.maxOrNull()?.takeIf { it.isFinite() } ?: return emptyList()
        return record.completedReps.take(60).flatMap { rep ->
            listOf("Start" to rep.startedAt, (if (rep.exercise == ExerciseCounter.Exercise.pullUp) "Top" else "Bottom") to rep.extremeAt,
                "Return" to rep.returnedAt).mapNotNull { (phase, time) ->
                time?.takeIf { it.isFinite() && it >= clipStart && it <= end }?.let { Keyframe(rep.id, rep.ordinal, phase, it, it-clipStart) }
            }
        }
    }
}

/** JSON only at the native storage/presentation boundary; all formulas live above. */
class WorkoutSetPresentationBridge {
    private val transport = kotlinx.serialization.json.Json(WorkoutRecordCodec.json) { allowSpecialFloatingPointValues = true }
    fun weight(phase: String): Double? = WorkoutSetPresentation.weight(phase)
    fun recovered(units: Double, seconds: Double) = WorkoutSetPresentation.recovered(units, seconds)
    fun demandJson(activityJson: String): String = WorkoutRecordCodec.json.encodeToString(
        WorkoutSetPresentation.demand(transport.decodeFromString<WorkoutActivity?>(activityJson)))
    fun tempoJson(recordJson: String): String = WorkoutRecordCodec.json.encodeToString(
        WorkoutSetPresentation.tempo(WorkoutRecordCodec.json.decodeFromString<WorkoutSetRecord>(recordJson)))
    fun keyframesJson(recordJson: String, clipStart: Double): String = WorkoutRecordCodec.json.encodeToString(
        WorkoutSetPresentation.keyframes(WorkoutRecordCodec.json.decodeFromString<WorkoutSetRecord>(recordJson), clipStart))
}
