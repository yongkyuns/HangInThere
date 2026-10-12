package dev.hanginthere.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Common storage projection. Summary records remain durable when curves expire. */
class WorkoutRecordCodec {
    @Throws(IllegalArgumentException::class)
    fun summaryJson(input: String): String = json.encodeToString(summary(json.decodeFromString<WorkoutSetRecord>(input)))
    @Throws(IllegalArgumentException::class)
    fun mergeDraftJson(previous: String?, delta: String): String = json.encodeToString(mergeDraft(
        previous?.let{json.decodeFromString<WorkoutSetRecord>(it)},json.decodeFromString<WorkoutSetRecord>(delta)))
    @Throws(IllegalArgumentException::class)
    fun recoveryJson(input: String): String = json.encodeToString(recover(json.decodeFromString<WorkoutSetRecord>(input)))
    companion object {
        fun mergeDraft(previous: WorkoutSetRecord?, delta: WorkoutSetRecord): WorkoutSetRecord {
            require(delta.schemaVersion==1 && delta.lifecycleVersion==1)
            if(previous==null) return summary(delta)
            require(previous.id==delta.id && previous.exercise==delta.exercise && previous.schemaVersion==delta.schemaVersion && previous.lifecycleVersion==delta.lifecycleVersion && previous.trackingSide==delta.trackingSide && previous.imageSize==delta.imageSize && previous.referenceEdge==delta.referenceEdge)
            require(previous.trackedAtEpochMilliseconds==delta.trackedAtEpochMilliseconds) { "Workout calendar timestamp is immutable" }
            val reps=previous.reps.associateBy{it.id}.toMutableMap()
            delta.reps.forEach { next ->
                require(next.setId==delta.id)
                val old=reps[next.id]
                require(old==null || (old.metricVersion==next.metricVersion && old.countPolicyVersion==next.countPolicyVersion))
                val terminal = old != null && old.closeReason != "inProgress"
                val eligible = old == null || (!terminal || next.closeReason != "inProgress") && next.closedAt >= old.closedAt
                if (eligible) {
                    require(old?.acceptedAt==null || old.acceptedAt==next.acceptedAt) { "Accepted evidence cannot be erased" }
                    reps[next.id]=next
                }
            }
            val newest = if ((delta.endedAt ?: Double.NEGATIVE_INFINITY) >= (previous.endedAt ?: Double.NEGATIVE_INFINITY)) delta else previous
            return summary(newest.copy(startedAt=listOfNotNull(previous.startedAt,delta.startedAt).minOrNull(),reps=reps.values.sortedBy{it.ordinal}))
        }
        fun recover(record:WorkoutSetRecord)=record.copy(endReason="recoveredInterruption")

        val json = Json { encodeDefaults = true; explicitNulls = false }
        const val maximumFeatureSets = 50
        const val maximumFeatureBytes = 50L * 1024 * 1024
        fun summary(record: WorkoutSetRecord) = record.copy(reps = record.reps.map {
            it.copy(samples = emptyList(), curveTruncated = it.curveTruncated || it.samples.isNotEmpty())
        })
    }
}

/** Serial-owner outbox. A failed native write stays queued across set rearming.
 * Delivery is acknowledged only after atomic persistence succeeds. */
class WorkoutWriteQueue {
    data class Write(val token: Long, val record: WorkoutSetRecord, val finalized: Boolean)
    private val pending = linkedMapOf<String, Write>()
    private var token = 0L
    fun enqueue(record: WorkoutSetRecord, finalized: Boolean) {
        val old = pending[record.id]
        if (old?.finalized == true) {
            if (finalized) require(old.record == record) { "Conflicting finalized workout" }
            return
        }
        val next = if (finalized) record else WorkoutRecordCodec.mergeDraft(old?.record, record)
        pending[record.id] = Write(++token, next, finalized)
    }
    @Throws(IllegalArgumentException::class)
    fun enqueueJson(input: String, finalized: Boolean) = enqueue(WorkoutRecordCodec.json.decodeFromString(input), finalized)
    fun next(): Write? = pending.values.firstOrNull()
    fun nextJson(): String? = next()?.let { WorkoutRecordCodec.json.encodeToString(it.record) }
    val nextFinalized: Boolean get() = next()?.finalized ?: false
    val nextToken: Long get() = next()?.token ?: -1
    val size: Int get() = pending.size
    fun acknowledge(token: Long) { pending.entries.removeAll { it.value.token == token } }
}
