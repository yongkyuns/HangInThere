package dev.hanginthere.evaluation

import dev.hanginthere.core.ExerciseCounter
import dev.hanginthere.core.WorkoutRuntime
import kotlinx.serialization.json.*

/** Evaluation-only source evidence. Counting and completed-cycle eligibility stay in WorkoutRuntime. */
class PublicReplayProgress(expectedFrames: Int, expectedPts: List<Long>) {
    private val expected = expectedPts.toList()
    private val validExpected = expectedFrames > 0 && expected.size == expectedFrames &&
        expected.zipWithNext().all { (a, b) -> a < b } &&
        expected.map { it / 1e6 }.zipWithNext().all { (a, b) -> a < b }
    private val observed = mutableListOf<Long>()
    private var valid = validExpected
    var events: List<ExerciseCounter.Event> = emptyList(); private set
    var completedRepSeconds: List<Double> = emptyList(); private set
    private var countingPass = false

    /** Must wrap processing inside the pipeline's use block, before close can finish/reset it. */
    fun <T> captureOnExit(runtime: WorkoutRuntime, countingPassStarted: () -> Boolean, block: () -> T): T {
        try { return block() } finally {
            events = runtime.session.counter.events.toList()
            completedRepSeconds = runtime.session.record.completedReps.map { requireNotNull(it.returnedAt) }
            countingPass = countingPassStarted()
        }
    }

    fun snapshot(outcome: String?, mediaVerified: Boolean = true): JsonObject {
        // Acquisition EOF alone cannot turn a failed rewind into a processed zero.
        if (outcome == "complete" && !countingPass) return failed()
        return snapshot(outcome,
            if (countingPass) events.filter { it.outcome == ExerciseCounter.Outcome.movement }.map { it.sourceSeconds } else emptyList(),
            if (countingPass) completedRepSeconds else emptyList(), mediaVerified)
    }

    /** Acquisition and rewound counting are separate passes of the same source. */
    fun beginPass() { observed.clear(); valid = validExpected }

    fun checkNext(pts: Long) {
        if (!valid || observed.size >= expected.size || expected[observed.size] != pts) {
            valid = false
            error("Evaluation source PTS integrity failed")
        }
    }

    /** Call only after the actual native frame has been processed successfully. */
    fun observe(pts: Long) { checkNext(pts); observed += pts }

    fun finishPass() {
        if (!valid || observed.size != expected.size) {
            valid = false
            error("Evaluation source EOF integrity failed")
        }
    }

    fun snapshot(outcome: String?, accepted: List<Double>, completed: List<Double>, mediaVerified: Boolean = true): JsonObject {
        val finished = outcome == "complete" || outcome == "setupUnavailable"
        val first = observed.firstOrNull()?.div(1e6)
        val last = observed.lastOrNull()?.div(1e6)
        fun bounded(events: List<Double>) = events.all { it.isFinite() && first != null && last != null && it >= first && it <= last } &&
            events.zipWithNext().all { (a, b) -> a <= b }
        val verified = (outcome == null || outcome in setOf("complete", "setupUnavailable", "interrupted", "analysisFailed")) &&
            mediaVerified && valid && observed.isNotEmpty() && (!finished || observed.size == expected.size) &&
            bounded(accepted) && bounded(completed) && (outcome != "setupUnavailable" || (accepted.isEmpty() && completed.isEmpty()))
        if (!verified) return failed()
        return buildJsonObject {
            put("status", if (finished) (if (outcome == "complete") "processed" else "setupUnavailable") else "interrupted")
            put("frames", observed.size); put("source_clock_verified", finished)
            put("acceptance_seconds", JsonArray(accepted.map(::JsonPrimitive)))
            put("completed_seconds", JsonArray(completed.map(::JsonPrimitive)))
            put("progress_evidence", buildJsonObject {
                put("schema_version", 1); put("source_prefix_verified", true)
                put("observed_source_end_seconds", requireNotNull(last))
            })
        }
    }

    companion object {
        fun failed() = buildJsonObject {
            put("status", "failed"); put("frames", 0); put("source_clock_verified", false)
            put("acceptance_seconds", JsonArray(emptyList())); put("completed_seconds", JsonArray(emptyList()))
            put("progress_evidence", buildJsonObject {
                put("schema_version", 1); put("source_prefix_verified", false); put("observed_source_end_seconds", JsonNull)
            })
        }
    }
}
