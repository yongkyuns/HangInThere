package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** Metadata/measurement summaries only; no video, filesystem paths, device IDs or raw frame traces. */
@Serializable data class WorkoutLibraryBackupEnvelope(val library: WorkoutLibraryState,
    val measuredSummaries: List<WorkoutSetRecord> = emptyList(),
    val gripCorrections: List<GripCorrectionLedger> = emptyList(),
    val schemaVersion: Int = 1, val format: String = "hanginthere-workout-library", val includesMedia: Boolean = false)

object WorkoutLibraryBackup {
    const val maximumBackupCharacters = 32 * 1024 * 1024
    private fun validateArchivalPhases(record: WorkoutSetRecord) {
        record.activity?.phases?.let { spans ->
            require(spans.size <= WorkoutPhaseAnalysis.maximumSpans && spans.map { it.id }.distinct().size == spans.size)
            require(spans.zipWithNext().all { (a, b) -> a.endedAt <= b.startedAt })
            spans.forEach { phase ->
                require(phase.id.length in 1..128 && phase.startedAt.isFinite() && phase.endedAt.isFinite() && phase.startedAt >= 0 && phase.endedAt >= phase.startedAt)
                require(phase.observedSeconds.isFinite() && phase.observedSeconds in 0.0..(phase.endedAt - phase.startedAt + 1e-6))
                require(record.startedAt == null || phase.startedAt >= record.startedAt)
                require(record.endedAt == null || phase.endedAt <= record.endedAt)
                require(phase.observedStartedAt == null || phase.observedStartedAt.isFinite() && phase.observedStartedAt in phase.startedAt..phase.endedAt && phase.observedSeconds <= phase.endedAt - phase.observedStartedAt + 1e-6)
            }
        }
    }
    fun export(state: WorkoutLibraryState, summaries: List<WorkoutSetRecord>? = null, gripCorrections: List<GripCorrectionLedger> = emptyList()): String {
        WorkoutLibrary.validate(state)
        val records = summaries ?: state.entries.map { it.measured }
        val indexed = state.entries.associateBy { it.measured.id }
        require(records.map { it.id }.distinct().size == records.size)
        require(records.map { it.id }.toSet() == state.entries.map { it.measured.id }.toSet())
        records.forEach { record -> require(record == WorkoutRecordCodec.summary(record) && WorkoutLibrary.compact(record) == indexed[record.id]?.measured) }
        val output = WorkoutRecordCodec.json.encodeToString(WorkoutLibraryBackupEnvelope(state.copy(media = emptyList(),
            mediaPolicy = state.mediaPolicy.copy(enabled = false), diagnosticsConsent = false), records, gripCorrections))
        require(output.length <= maximumBackupCharacters)
        decode(output)
        return output
    }
    fun restore(existing: WorkoutLibraryState, input: String): WorkoutLibraryState {
        WorkoutLibrary.validate(existing); require(input.length <= maximumBackupCharacters)
        val envelope = decode(input)
        require(envelope.schemaVersion == 1 && envelope.format == "hanginthere-workout-library" && !envelope.includesMedia)
        val incoming = envelope.library
        WorkoutLibrary.validate(incoming)
        require(incoming.media.isEmpty() && !incoming.mediaPolicy.enabled && !incoming.diagnosticsConsent)
        // Review histories must share an exact prefix; divergent edits require explicit user reconciliation.
        val common = minOf(existing.reviews.events.size, incoming.reviews.events.size)
        require(existing.reviews.events.take(common) == incoming.reviews.events.take(common)) { "Review histories conflict" }
        val ledger = if (incoming.reviews.events.size > existing.reviews.events.size) incoming.reviews else existing.reviews
        val deleted = (existing.deletions + incoming.deletions).groupBy { it.setId }.map { (_, rows) -> rows.maxBy { it.deletedAtEpochMilliseconds } }
        val entries = (existing.entries + incoming.entries).groupBy { it.measured.id }.map { (_, rows) ->
            require(rows.map { it.measured }.distinct().size == 1) { "Immutable workout conflict" }; rows.first()
        }.filter { entry -> deleted.none { it.setId == entry.measured.id } }
        fun <T, K> union(a: List<T>, b: List<T>, key: (T) -> K): List<T> = (a + b).groupBy(key).map { (_, values) ->
            require(values.distinct().size == 1) { "Application records conflict; choose which version to retain" }; values.first()
        }
        val next = existing.copy(entries = entries, reviews = ledger, deletions = deleted,
            workoutLogDeletions = (existing.workoutLogDeletions + incoming.workoutLogDeletions).groupBy { it.id }.map { (_, rows) -> rows.maxBy { it.deletedAtEpochMilliseconds } },
            workoutLogs = (existing.workoutLogs + incoming.workoutLogs).groupBy { it.id }.map { (_, rows) ->
                val newest = rows.maxOf { it.updatedAtEpochMilliseconds }
                val latest = rows.filter { it.updatedAtEpochMilliseconds == newest }.distinct()
                require(latest.size == 1) { "Workout log edits conflict" }; latest.single()
            }.filter { log -> (existing.workoutLogDeletions + incoming.workoutLogDeletions).none { it.id == log.id } },
            goals = union(existing.goals, incoming.goals) { it.comparisonKey }, plans = union(existing.plans, incoming.plans) { it.id },
            defaultContexts = union(existing.defaultContexts, incoming.defaultContexts) { it.exercise },
            merges = union(existing.merges, incoming.merges) { it.id }.filter { group ->
                val sourceParts = entries.flatMap { WorkoutCorrections.project(it.measured, WorkoutCorrections.current(ledger, it.measured.id)) }.map { it.id }
                group.setIds.all { it in sourceParts }
            },
            media = existing.media.filter { media -> deleted.none { it.setId == media.setId } })
        WorkoutLibrary.validate(next)
        return if (next == existing) existing else next.copy(revision = existing.revision + 1)
    }
    fun decode(input: String): WorkoutLibraryBackupEnvelope {
        require(input.length <= maximumBackupCharacters)
        val envelope = WorkoutRecordCodec.json.decodeFromString<WorkoutLibraryBackupEnvelope>(input)
        require(envelope.schemaVersion == 1 && envelope.format == "hanginthere-workout-library" && !envelope.includesMedia)
        WorkoutLibrary.validate(envelope.library)
        require(envelope.measuredSummaries.map { it.id }.distinct().size == envelope.measuredSummaries.size)
        require(envelope.measuredSummaries.map { it.id }.toSet() == envelope.library.entries.map { it.measured.id }.toSet())
        val entries = envelope.library.entries.associateBy { it.measured.id }
        envelope.measuredSummaries.forEach { record -> require(record == WorkoutRecordCodec.summary(record) && WorkoutLibrary.compact(record) == entries[record.id]?.measured); validateArchivalPhases(record) }
        require(envelope.gripCorrections.map { it.setId }.distinct().size == envelope.gripCorrections.size)
        val summaries = envelope.measuredSummaries.associateBy { it.id }
        envelope.gripCorrections.forEach { ledger -> WorkoutGripReview.report(requireNotNull(summaries[ledger.setId]), ledger) }
        return envelope
    }
}

@Serializable data class WorkoutDiagnosticSummary(val policyVersion: Int, val phaseMetricVersion: Int,
    val reviewedSets: Int, val measuredCompletedReps: Int, val interruptedSets: Int,
    val modelBackend: String, val performanceScope: String = "Content-free application summary; no device performance qualification",
    val schemaVersion: Int = 1)
object WorkoutDiagnostics {
    fun export(state: WorkoutLibraryState): WorkoutDiagnosticSummary {
        require(state.diagnosticsConsent) { "Enable diagnostics consent before exporting" }
        WorkoutLibrary.validate(state)
        val sets = WorkoutLibrary.reviewed(state)
        return WorkoutDiagnosticSummary(ExerciseCounter.policyVersion, 2, sets.size, sets.sumOf { it.measuredCompletedReps },
            sets.count { it.endReason !in listOf("manual", "disengaged", "sourceEnded") }, "Native camera/model adapters")
    }
}
