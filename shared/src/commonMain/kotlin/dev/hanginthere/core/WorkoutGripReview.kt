package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/** User annotations live in a separate ledger; finalized measured workouts are immutable.
 * Each event is a complete replacement annotation for a set or an attempt. A null
 * field restores automatic estimation for that dimension. The newest applicable
 * sequence wins, including resets and later whole-set corrections. */
@Serializable data class GripCorrection(val sequence: Int, val repId: String?, val orientation: GripOrientation?,
    val width: GripWidth?, val recordedAtEpochMilliseconds: Long)
@Serializable data class GripCorrectionLedger(val setId: String, val corrections: List<GripCorrection> = emptyList(), val schemaVersion: Int = 1)
@Serializable data class GripRepVariation(val repId: String, val ordinal: Int, val orientation: GripOrientation,
    val width: GripWidth, val label: String, val orientationSource: String, val widthSource: String,
    val measured: GripEstimate?)
@Serializable data class GripVariationTotal(val label: String, val completedReps: Int)
@Serializable data class GripVariationReport(val reps: List<GripRepVariation>, val totals: List<GripVariationTotal>, val policyVersion: Int = 2,
    val setGrip: WorkoutSetGrip? = null)

class WorkoutGripReviewBridge {
    @Throws(IllegalArgumentException::class)
    fun mergeBackupLedgerJson(recordJson: String, existingJson: String?, incomingJson: String): String {
        val record = WorkoutRecordCodec.json.decodeFromString<WorkoutSetRecord>(recordJson)
        val incoming = WorkoutRecordCodec.json.decodeFromString<GripCorrectionLedger>(incomingJson)
        val existing = existingJson?.let { WorkoutRecordCodec.json.decodeFromString<GripCorrectionLedger>(it) } ?: GripCorrectionLedger(record.id)
        return WorkoutRecordCodec.json.encodeToString(WorkoutGripReview.mergeLedgers(record, existing, incoming))
    }
    @Throws(IllegalArgumentException::class)
    fun reportJson(recordJson: String, ledgerJson: String?): String {
        val record = WorkoutRecordCodec.json.decodeFromString<WorkoutSetRecord>(recordJson)
        val ledger = ledgerJson?.let { WorkoutRecordCodec.json.decodeFromString<GripCorrectionLedger>(it) }
        return WorkoutRecordCodec.json.encodeToString(WorkoutGripReview.report(record, ledger))
    }
    @Throws(IllegalArgumentException::class)
    fun correctJson(recordJson: String, ledgerJson: String?, repId: String?, orientation: String?, width: String?, recordedAtEpochMilliseconds: Long): String {
        val record = WorkoutRecordCodec.json.decodeFromString<WorkoutSetRecord>(recordJson)
        val ledger = ledgerJson?.let { WorkoutRecordCodec.json.decodeFromString<GripCorrectionLedger>(it) } ?: GripCorrectionLedger(record.id)
        return WorkoutRecordCodec.json.encodeToString(WorkoutGripReview.correct(record, ledger, repId,
            orientation?.let(GripOrientation::valueOf), width?.let(GripWidth::valueOf), recordedAtEpochMilliseconds))
    }
    fun label(orientation: String, width: String): String = GripEstimate.orientationTitle(GripOrientation.valueOf(orientation)) + " · " + GripEstimate.widthTitle(GripWidth.valueOf(width))
}
object WorkoutGripReview {
    const val maximumCorrections = 1024
    private fun validate(record: WorkoutSetRecord, ledger: GripCorrectionLedger) {
        require(ledger.schemaVersion == 1 && ledger.setId == record.id && ledger.corrections.size <= maximumCorrections)
        require(ledger.corrections.withIndex().all { (index,event) -> event.sequence == index+1 })
        ledger.corrections.forEach { event ->
            require(event.sequence > 0 && event.recordedAtEpochMilliseconds > 0)
            require(event.repId == null || record.reps.any { it.id == event.repId })
        }
    }
    fun mergeLedgers(record: WorkoutSetRecord, existing: GripCorrectionLedger, incoming: GripCorrectionLedger): GripCorrectionLedger {
        validate(record, existing); validate(record, incoming)
        val common = minOf(existing.corrections.size, incoming.corrections.size)
        require(existing.corrections.take(common) == incoming.corrections.take(common)) { "Grip correction histories conflict" }
        return if (existing.corrections.size >= incoming.corrections.size) existing else incoming
    }
    fun correct(record: WorkoutSetRecord, ledger: GripCorrectionLedger, repId: String?, orientation: GripOrientation?, width: GripWidth?, at: Long): GripCorrectionLedger {
        validate(record, ledger)
        require(record.exercise == ExerciseCounter.Exercise.pullUp && record.endReason != "running")
        require(repId == null || record.reps.any { it.id == repId })
        require(at > 0 && ledger.corrections.size < maximumCorrections)
        require(orientation != GripOrientation.varied && width != GripWidth.varied)
        return ledger.copy(corrections = ledger.corrections + GripCorrection((ledger.corrections.lastOrNull()?.sequence ?: 0) + 1, repId, orientation, width, at))
    }
    fun report(record: WorkoutSetRecord, ledger: GripCorrectionLedger? = null): GripVariationReport {
        if (ledger != null) validate(record, ledger)
        if (record.exercise != ExerciseCounter.Exercise.pullUp) return GripVariationReport(emptyList(), emptyList())
        // The saved set owner supplies this annotation. Never pool labels from
        // another set or relabel stored frame evidence/width measurements.
        val setGrip = record.setGrip?.takeIf { metadata ->
            metadata.setId == record.id && metadata.policyVersion == 1 &&
                metadata.firstConfirmedAt?.let { it.isFinite() && it >= 0 &&
                    (record.startedAt == null || it >= record.startedAt) } != false &&
                metadata.lastConfirmedAt?.let { it.isFinite() &&
                    metadata.firstConfirmedAt?.let { first -> it >= first } == true &&
                    (record.endedAt == null || it <= record.endedAt) } != false &&
                (!metadata.isConfirmed || metadata.firstConfirmedAt != null && metadata.lastConfirmedAt != null)
        }
        val reps = record.reps.map { rep ->
            val event = ledger?.corrections?.lastOrNull { it.repId == null || it.repId == rep.id }
            val orientation = event?.orientation ?: setGrip?.orientation ?: rep.grip?.orientation ?: GripOrientation.unknown
            val width = event?.width ?: rep.grip?.width ?: GripWidth.unknown
            GripRepVariation(rep.id, rep.ordinal, orientation, width,
                GripEstimate.orientationTitle(orientation) + " · " + GripEstimate.widthTitle(width),
                if(event?.orientation != null) "user" else if(setGrip != null) "setEstimated" else "estimated", if(event?.width != null) "user" else "estimated", rep.grip)
        }
        val completed = record.reps.filter { it.isCompleteForAnalytics }.map { it.id }.toSet()
        val totals = reps.filter { it.repId in completed }.groupingBy { it.label }.eachCount()
            .map { (label, count) -> GripVariationTotal(label, count) }
        return GripVariationReport(reps, totals, setGrip=setGrip)
    }
}
