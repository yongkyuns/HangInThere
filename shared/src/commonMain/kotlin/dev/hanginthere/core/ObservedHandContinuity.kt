package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.math.hypot
import kotlin.math.min

/** Temporal correspondence of independently measured hand points. A surviving
 * point track grants no body/limb ownership, apparatus contact or repetition.
 * Native owners capture epoch, ordinal and exact time with the original image
 * request. This component cannot verify pixels or repair relabeled input. */
class ObservedHandContinuity private constructor(private val context: Context, private val tracks: List<MutableTrack>,
    private var highTime: PresentationTime, private var highOrdinal: Long) {
    @Serializable data class Frame(val generation: Long, val sourceEpoch: String, val sourceFrameOrdinal: Long,
        val pose: PoseResult, val handSourceTime: PresentationTime? = null, val handSourceFrameOrdinal: Long? = null,
        val requiresJointConfidence: Boolean = true)
    @Serializable data class Start(val frame: Frame, val seedHandIndices: List<Int>)
    @Serializable enum class State { fresh, unavailable, ambiguous, terminated }
    @Serializable enum class Reason { seeded, currentPoint, observationUnavailable, invalidObservation,
        invalidCompetitor, ambiguousCorrespondence, pointUnavailable, observationOutage,
        sourceRegression, sourceContextChanged, sourceProvenanceMismatch }
    @Serializable data class Sample(val position: Point2D, val sourceTime: PresentationTime,
        val sourceFrameOrdinal: Long, val originalHandIndex: Int, val jointConfidence: Double?)
    @Serializable data class Track(val id: Int, val seed: Sample, val lastObservedSample: Sample,
        val currentSample: Sample?, val state: State, val reason: Reason)
    /** The high-water mark belongs to consumed valid-clock frames, not to a
     * rejected context/regression callback. Each point carries its own source. */
    @Serializable data class Snapshot(val generation: Long, val sourceEpoch: String,
        val highWaterSourceTime: PresentationTime, val highWaterSourceFrameOrdinal: Long,
        val tracks: List<Track>, val policyVersion: Int = 1)

    private data class Context(val generation: Long, val epoch: String, val size: ImageSize,
        val bodyBackend: String, val bodyRevision: Int, val handModel: String, val handBackend: String,
        val requiresScore: Boolean) {
        fun accepts(frame: Frame): Boolean {
            val pose = frame.pose
            val hand = pose.gripObservation
            return frame.sourceEpoch == epoch && pose.imageSize == size && pose.backend == bodyBackend &&
                pose.requestRevision == bodyRevision && frame.requiresJointConfidence == requiresScore &&
                (hand == null || hand.modelId == handModel && hand.backend == handBackend &&
                    hand.schemaVersion == 1 && hand.coordinateConvention == "cameraRightDownAway_v1")
        }
    }
    private class MutableTrack(val id: Int, val seed: Sample) {
        var last = seed
        var current: Sample? = seed
        var state = State.fresh
        var reason = Reason.seeded
        fun unavailable(reason: Reason, state: State = State.unavailable) {
            current = null
            this.state = state
            this.reason = reason
        }
        fun snapshot() = Track(id, seed, last, current, state, reason)
    }

    companion object {
        const val policyVersion = 1
        private val outage = SourceTimeLimit(3, 4)
        private val zero = SourceTimeLimit(0, 1)

        /** Explicit start only: all supplied competitors must be usable, even
         * if only one raw hand is selected as a seed. No side or owner assigned. */
        fun start(input: Start): ObservedHandContinuity? {
            val frame = input.frame
            if (frame.generation < 0 || frame.sourceEpoch.isBlank() || frame.sourceFrameOrdinal < 0 ||
                input.seedHandIndices.size !in 1..2 || input.seedHandIndices.distinct().size != input.seedHandIndices.size ||
                !freshProvenance(frame)) return null
            val validated = ObservedHandValidation.validate(frame.pose,
                frame.handSourceTime ?: return null, allowBodyless = true) ?: return null
            val points = validated.hand.hands.map { ObservedHandValidation.wrist(it, frame.pose.imageSize,
                frame.requiresJointConfidence) ?: return null }
            if (input.seedHandIndices.any { it !in points.indices }) return null
            val context = Context(frame.generation, frame.sourceEpoch, frame.pose.imageSize.copy(),
                frame.pose.backend, frame.pose.requestRevision, validated.hand.modelId, validated.hand.backend, frame.requiresJointConfidence)
            val tracks = input.seedHandIndices.mapIndexed { id, index -> MutableTrack(id, sample(frame, index, points[index])) }
            return ObservedHandContinuity(context, tracks, frame.pose.timestamp.copy(), frame.sourceFrameOrdinal)
        }

        private fun freshProvenance(frame: Frame) = frame.handSourceTime == frame.pose.timestamp &&
            frame.handSourceFrameOrdinal == frame.sourceFrameOrdinal
        private fun sample(frame: Frame, index: Int, point: GripHandPoint) = Sample(point.position.copy(),
            frame.pose.timestamp.copy(), frame.sourceFrameOrdinal, index, point.score)
        private fun distance(a: Point2D, b: Point2D) = hypot(a.x - b.x, a.y - b.y)
    }

    /** Wrong-generation asynchronous results are rejected without changing a
     * current lifetime. Other malformed observations advance a valid frame's
     * high-water mark but supply no fresh point and cannot extend its expiry. */
    fun observe(frame: Frame): Snapshot? {
        if (frame.generation != context.generation) return null
        if (!context.accepts(frame)) return terminate(Reason.sourceContextChanged)
        if (frame.sourceFrameOrdinal <= highOrdinal ||
            SourceTimeComparison.compareElapsed(frame.pose.timestamp, highTime, zero) != 1)
            return terminate(Reason.sourceRegression)
        highTime = frame.pose.timestamp.copy()
        highOrdinal = frame.sourceFrameOrdinal
        for (track in tracks) {
            if (track.state == State.terminated) continue
            if (SourceTimeComparison.compareElapsed(highTime, track.last.sourceTime, outage) == 1)
                track.unavailable(Reason.observationOutage, State.terminated)
            else track.unavailable(Reason.observationUnavailable)
        }
        val live = tracks.filter { it.state != State.terminated }
        val hand = frame.pose.gripObservation
        if (hand == null) {
            if (frame.handSourceTime != null || frame.handSourceFrameOrdinal != null)
                live.forEach { it.unavailable(Reason.sourceProvenanceMismatch) }
            return snapshot()
        }
        if (!freshProvenance(frame)) {
            live.forEach { it.unavailable(Reason.sourceProvenanceMismatch) }
            return snapshot()
        }
        val validated = ObservedHandValidation.validate(frame.pose,
            frame.handSourceTime!!, allowBodyless = true)
        if (validated == null) {
            live.forEach { it.unavailable(Reason.invalidObservation) }
            return snapshot()
        }
        val points = hand.hands.map { ObservedHandValidation.wrist(it, context.size, context.requiresScore) }
        if (points.any { it == null }) {
            live.forEach { it.unavailable(Reason.invalidCompetitor) }
            return snapshot()
        }
        val limit = .12 * min(context.size.width, context.size.height)
        val edges = live.flatMap { track -> points.mapIndexedNotNull { index, point ->
            if (distance(track.last.position, point!!.position) <= limit && distance(track.seed.position, point.position) <= limit)
                track.id to index else null
        } }
        for (track in live) {
            val choices = edges.filter { it.first == track.id }.map { it.second }
            if (choices.size == 1 && edges.count { it.second == choices[0] } == 1) {
                val index = choices[0]
                val observed = sample(frame, index, points[index]!!)
                track.last = observed
                track.current = observed
                track.state = State.fresh
                track.reason = Reason.currentPoint
            } else track.unavailable(if (choices.isEmpty()) Reason.pointUnavailable else Reason.ambiguousCorrespondence,
                if (choices.isEmpty()) State.unavailable else State.ambiguous)
        }
        return snapshot()
    }

    private fun terminate(reason: Reason): Snapshot {
        tracks.filter { it.state != State.terminated }.forEach { it.unavailable(reason, State.terminated) }
        return snapshot()
    }
    fun snapshot() = Snapshot(context.generation, context.epoch, highTime.copy(), highOrdinal, tracks.map { it.snapshot() })
}

/** Lifecycle generation must be captured with each native inference request.
 * Start never replaces a lifetime; reset explicitly retires it. */
class ObservedHandContinuityBridge {
    var generation: Long = 0
        private set
    private var tracker: ObservedHandContinuity? = null
    fun reset(): Long {
        check(generation < Long.MAX_VALUE) { "Hand continuity generation exhausted" }
        tracker = null
        generation += 1
        return generation
    }
    @Throws(IllegalArgumentException::class)
    fun startJson(input: String): String? {
        if (tracker != null) return null
        val start = WorkoutRecordCodec.json.decodeFromString<ObservedHandContinuity.Start>(input)
        if (start.frame.generation != generation) return null
        val started = ObservedHandContinuity.start(start) ?: return null
        tracker = started
        return WorkoutRecordCodec.json.encodeToString(started.snapshot())
    }
    @Throws(IllegalArgumentException::class)
    fun observeJson(input: String): String? = tracker?.observe(
        WorkoutRecordCodec.json.decodeFromString<ObservedHandContinuity.Frame>(input))?.let { WorkoutRecordCodec.json.encodeToString(it) }
}
