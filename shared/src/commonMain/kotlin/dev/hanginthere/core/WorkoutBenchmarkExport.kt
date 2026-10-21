package dev.hanginthere.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

@Serializable data class WorkoutBenchmarkRun(val id: String, val platform: String, val backend: String,
    @SerialName("source_revision") val sourceRevision: String,
    @SerialName("counter_policy_version") val counterPolicyVersion: Int = ExerciseCounter.policyVersion,
    @SerialName("activity_metric_version") val activityMetricVersion: Int = WorkoutPhaseAnalysis.metricVersion,
    @SerialName("rep_metric_version") val repMetricVersion: Int = WorkoutAnalysis.metricVersion,
    @SerialName("capture_mode") val captureMode: String = "automatic")
@Serializable data class WorkoutBenchmarkPhase(val phase: String, val seconds: List<Double>)
@Serializable data class WorkoutBenchmarkSet(val exercise: ExerciseCounter.Exercise, val seconds: List<Double>,
    @SerialName("end_kind") val endKind: String, @SerialName("completed_rep_seconds") val completedRepSeconds: List<Double>,
    val phases: List<WorkoutBenchmarkPhase>)
data class WorkoutBenchmarkMetricVersions(val counter: Int, val activity: Int, val rep: Int)
@Serializable data class WorkoutBenchmarkSession(val id: String, @SerialName("capture_sha256") val captureSha256: String,
    @SerialName("span_seconds") val spanSeconds: List<Double>, val status: String,
    @SerialName("observed_intervals") val observedIntervals: List<List<Double>>, val sets: List<WorkoutBenchmarkSet>,
    /** Source versions cannot be reconstructed from normalized curves. In-memory
     * predictions must retain the provenance established by session(records). */
    @Transient val sourceVersions: Set<WorkoutBenchmarkMetricVersions> = emptySet())
@Serializable data class WorkoutBenchmarkPrediction(@SerialName("benchmark_sha256") val benchmarkSha256: String,
    val run: WorkoutBenchmarkRun, val sessions: List<WorkoutBenchmarkSession>, @SerialName("schema_version") val schemaVersion: Int = 1,
    val timebase: String = "source_pts_seconds", @SerialName("completed_cycle_definition") val completedCycleDefinition: String = "start_extreme_return")

/** Normalizes shipping measured records, not reviews. Explicit capture/source
 * binding and observed intervals are mandatory; calendar clocks cannot substitute.
 * The frozen Python scorer independently validates the entire normalized run. */
object WorkoutBenchmarkExport {
    fun session(id: String, captureSha: String, span: List<Double>, records: List<WorkoutSetRecord>,
        sourceOffsets: List<Double>, observedIntervals: List<List<Double>>, failed: Boolean = false): WorkoutBenchmarkSession {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,100}")) && captureSha.matches(Regex("[0-9a-f]{64}")))
        require(span.size == 2 && span.all { it.isFinite() } && span[0] >= 0 && span[1] > span[0])
        require(records.size == sourceOffsets.size && sourceOffsets.all { it.isFinite() })
        require(observedIntervals.all { it.size == 2 && it.all(Double::isFinite) && it[0] >= span[0] && it[1] <= span[1] && it[0] <= it[1] })
        require(observedIntervals.zipWithNext().all { (a, b) -> a[1] <= b[0] })
        if (failed) { require(records.isEmpty() && observedIntervals.isEmpty()); return WorkoutBenchmarkSession(id, captureSha, span, "failed", emptyList(), emptyList()) }
        val sourceVersions = records.map { record ->
            val activityVersion = requireNotNull(record.activity).metricVersion
            val repVersion = when (activityVersion) { 1, 2 -> 1; WorkoutPhaseAnalysis.metricVersion -> WorkoutAnalysis.metricVersion; else -> throw IllegalArgumentException("Unknown source metric version") }
            require(record.schemaVersion == 1 && record.countMethod == null && record.countPolicyVersion == null &&
                record.reps.all { it.countMethod == null && it.countPolicyVersion == ExerciseCounter.policyVersion && it.metricVersion == repVersion })
            WorkoutBenchmarkMetricVersions(ExerciseCounter.policyVersion, activityVersion, repVersion)
        }.toSet()
        require(sourceVersions.size <= 1) { "A benchmark session cannot combine different metric engines" }
        fun supported(time: Double) = observedIntervals.any { time in it[0]..it[1] }
        val sets = records.zip(sourceOffsets).map { (record, offset) ->
            require(record.endReason != "running" && record.activity?.timelineTruncated == false) { "Complete source phase evidence is required" }
            val start = requireNotNull(record.startedAt) + offset
            val end = requireNotNull(record.endedAt) + offset
            require(start >= span[0] && end <= span[1] && end > start && supported(start))
            val endKind = when (record.endReason) { "disengaged" -> "disengaged"; "trackingLost" -> "trackingLost"; "manual" -> "manual"; "sourceEnded" -> "sourceEnd"; else -> "interruption" }
            if (endKind in listOf("disengaged", "trackingLost")) require(end < span[1])
            if (endKind == "disengaged") require(supported(end))
            val events = record.completedReps.map { requireNotNull(it.returnedAt) + offset }.sorted()
            require(events.all { it >= start && it <= end && it < span[1] && supported(it) })
            val phases = record.activity!!.phases.filter { it.endedAt > it.startedAt && it.phase != WorkoutMotionPhase.unobserved }.mapNotNull {
                require(it.startedAt + offset >= start && it.endedAt + offset <= end)
                val isHold = it.phase in listOf(WorkoutMotionPhase.hang, WorkoutMotionPhase.support, WorkoutMotionPhase.peakHold, WorkoutMotionPhase.bottomHold, WorkoutMotionPhase.effortHold)
                if (isHold && it.observedSeconds == 0.0) return@mapNotNull null
                val creditedStart = if (isHold) it.observedStartedAt ?: it.startedAt.takeIf { _ -> kotlin.math.abs(it.endedAt - it.startedAt - it.observedSeconds) <= 1e-6 }
                    ?: throw IllegalArgumentException("Phase credit timing is unavailable in this legacy summary") else it.startedAt
                require(creditedStart in it.startedAt..it.endedAt)
                if (isHold) {
                    val observed = observedIntervals.sumOf { interval -> maxOf(0.0, minOf(interval[1], it.endedAt + offset) - maxOf(interval[0], creditedStart + offset)) }
                    require(kotlin.math.abs(observed - it.observedSeconds) <= 1e-6) { "Normalized support must preserve credited phase time" }
                }
                WorkoutBenchmarkPhase(it.phase.name, listOf(creditedStart + offset, it.endedAt + offset))
            }
            require(phases.zipWithNext().all { (a, b) -> a.seconds[1] <= b.seconds[0] })
            phases.zipWithNext().filter { (a, b) -> a.seconds[1] == b.seconds[0] && a.phase != b.phase && a.phase != "unobserved" && b.phase != "unobserved" }
                .forEach { (_, b) -> require(supported(b.seconds[0])) }
            WorkoutBenchmarkSet(record.exercise, listOf(start, end), endKind, events, phases)
        }
        require(sets.zipWithNext().all { (a, b) -> a.seconds[0] <= b.seconds[0] })
        return WorkoutBenchmarkSession(id, captureSha, span, "processed", observedIntervals, sets, sourceVersions)
    }
    fun prediction(benchmarkSha: String, run: WorkoutBenchmarkRun, sessions: List<WorkoutBenchmarkSession>): WorkoutBenchmarkPrediction {
        require(benchmarkSha.matches(Regex("[0-9a-f]{64}")) && run.sourceRevision.matches(Regex("[0-9a-f]{40}")))
        require(run.id.matches(Regex("[A-Za-z0-9_-]{1,100}")) && run.backend.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        require(run.platform in listOf("ios", "android") && run.captureMode == "automatic")
        require(run.counterPolicyVersion == ExerciseCounter.policyVersion &&
            (run.activityMetricVersion to run.repMetricVersion) in listOf(1 to 1, 2 to 1,
                WorkoutPhaseAnalysis.metricVersion to WorkoutAnalysis.metricVersion))
        require(sessions.map { it.id }.distinct().size == sessions.size)
        val expected = WorkoutBenchmarkMetricVersions(run.counterPolicyVersion, run.activityMetricVersion, run.repMetricVersion)
        require(sessions.all { it.sets.isEmpty() || it.sourceVersions == setOf(expected) }) { "Run versions must match normalized source records" }
        return WorkoutBenchmarkPrediction(benchmarkSha, run, sessions)
    }
}

class WorkoutBenchmarkExportBridge {
    @Throws(IllegalArgumentException::class) fun sessionJson(id: String, captureSha: String, spanJson: String,
        recordsJson: String, offsetsJson: String, observedIntervalsJson: String, failed: Boolean): String = WorkoutRecordCodec.json.encodeToString(
        WorkoutBenchmarkExport.session(id, captureSha, WorkoutRecordCodec.json.decodeFromString(spanJson), WorkoutRecordCodec.json.decodeFromString(recordsJson),
            WorkoutRecordCodec.json.decodeFromString(offsetsJson), WorkoutRecordCodec.json.decodeFromString(observedIntervalsJson), failed))
}
