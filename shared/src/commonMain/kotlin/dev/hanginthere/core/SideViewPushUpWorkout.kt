package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** Durable preview records from the side-view counter's actual endpoints. No
 * bilateral, hold-time, muscle-load or form evidence is inferred from one arm. */
class SideViewPushUpWorkout(private val sessionId: String, private val trackedAtEpochMilliseconds: Long? = null) {
    companion object {
        const val countMethod = "sideViewPushUp"
        const val boundaryPolicyVersion = 9
    }
    @Serializable data class Write(val record: WorkoutSetRecord, val finalized: Boolean)
    private val motion = SideViewPushUpMotion()
    private val writes = ArrayDeque<Write>()
    private var sourceOrigin: Double? = null
    private var setSerial = 0
    private var setId: String? = null
    private var firstPose: PoseResult? = null
    private var trackingSide: ArmMeasurement.Side? = null
    private var setStart: Double? = null
    private var lastCheckpoint: Double? = null
    private val closed = mutableListOf<RepRecord>()
    private var attempt: Attempt? = null

    private class Attempt(val startedAt: Double, val ordinal: Int, val side: ArmMeasurement.Side) {
        private val angles = AngleAccumulator()
        private var previous: MotionSample? = null
        private var observed = 0.0
        private val samples = mutableListOf<MotionSample>()
        private var truncated = false
        fun observe(sample: MotionSample) {
            val old = previous
            val dt = old?.let { (sample.seconds-it.seconds).takeIf { d ->
                d > 0 && d <= ExerciseCounter.maximumGapSeconds && sample.usable && it.usable
            } }
            val value = if (side == ArmMeasurement.Side.left) sample.leftDegrees else sample.rightDegrees
            val prior = if (side == ArmMeasurement.Side.left) old?.leftDegrees else old?.rightDegrees
            angles.observe(value, sample.seconds, prior, dt, sample.usable)
            if (dt != null) observed += dt
            if (samples.isEmpty() || sample.seconds-samples.last().seconds >= .1 || old?.usable != sample.usable) {
                if (samples.size == WorkoutAnalysis.maximumCurveSamples) { samples.removeAt(0); truncated = true }
                samples += sample
            }
            previous = sample
        }
        fun record(id: String, end: Double, reason: String, rep: SideViewPushUpMotion.Rep? = null): RepRecord {
            val duration = end-startedAt
            val metric = angles.metrics(duration)
            val absent = AngleMetrics(null, null, null, 0.0, null)
            return RepRecord("$id:rep:$ordinal", id, ordinal, ExerciseCounter.Exercise.pushUp,
                startedAt, rep?.deepestAt, rep?.returnedAt, rep?.returnedAt, end, reason,
                if (rep != null) "accepted" else "interrupted", rep?.deepestAt?.minus(startedAt),
                rep?.let { it.returnedAt-it.deepestAt },
                if (side == ArmMeasurement.Side.left) metric else absent,
                if (side == ArmMeasurement.Side.right) metric else absent,
                null, null, observed, if (duration > 0) (observed/duration).coerceIn(0.0, 1.0) else null,
                samples.toList(), truncated, SideViewPushUpMotion.policyVersion, WorkoutAnalysis.metricVersion,
                acceptanceReason = if (rep != null) "sideViewCycle" else null, countMethod = countMethod)
        }
    }
    fun observe(pose: PoseResult, camera: SideViewPushUpMotion.Camera): SideViewPushUpMotion.Snapshot {
        if (sourceOrigin == null && pose.timestamp.seconds.isFinite() && pose.timestamp.seconds >= 0)
            sourceOrigin = pose.timestamp.seconds
        val state = motion.observe(pose, camera)
        // A release/fault belongs to the old set, never to a newly acquired athlete.
        state.endedSet?.let { close(it) }
        val start = motion.attemptStartedAt
        if (start != null && attempt == null) {
            if (setId == null) {
                setId = "$sessionId-pushup-${setSerial++}"; firstPose = pose
                trackingSide = checkNotNull(state.side); setStart = start
            }
            attempt = Attempt(start, closed.size+1, checkNotNull(trackingSide))
        }
        attempt?.let { pending ->
            val selected = motion.selectedPose?.takeIf { it.timestamp == pose.timestamp && state.observationUsable }
            val angle = selected?.let { ArmMeasurement(it, pending.side).estimate?.elbowDegrees }
            pending.observe(MotionSample(pose.timestamp.seconds,
                angle.takeIf { pending.side == ArmMeasurement.Side.left },
                angle.takeIf { pending.side == ArmMeasurement.Side.right }, null, angle != null, false))
            state.rep?.let { rep ->
                closed += pending.record(checkNotNull(setId), rep.returnedAt, "returned", rep)
                attempt = null
            }
        }
        // Shallow setup bobs stay private; they cannot create a history entry.
        if (start == null && state.rep == null && attempt != null) {
            if (state.setStarted) {
                closed += attempt!!.record(checkNotNull(setId), pose.timestamp.seconds, "attemptCancelled")
                attempt = null
            } else resetSet()
        }
        if (state.setStarted && (state.rep != null || lastCheckpoint?.let { pose.timestamp.seconds-it >= 1 } != false)) {
            writes += Write(record(pose.timestamp.seconds, "running"), false)
            lastCheckpoint = pose.timestamp.seconds
        }
        return state
    }
    fun finish(reason: String) {
        val ended = motion.finish(reason)
        if (ended != null) close(ended) else resetSet()
    }
    private fun close(summary: SideViewPushUpMotion.SetSummary) {
        if (setId != null) {
            val end = checkNotNull(summary.endedAt)
            attempt?.let { closed += it.record(checkNotNull(setId), end, checkNotNull(summary.endReason)) }
            attempt = null
            writes += Write(record(end, checkNotNull(summary.endReason)), true)
        }
        resetSet()
    }
    private fun record(end: Double, reason: String): WorkoutSetRecord {
        val pose = checkNotNull(firstPose)
        val epoch = trackedAtEpochMilliseconds?.let { origin ->
            val elapsed = (checkNotNull(setStart)-checkNotNull(sourceOrigin))*1000
            if (elapsed.isFinite() && elapsed >= 0 && elapsed < Long.MAX_VALUE-origin.toDouble()) origin+elapsed.toLong() else null
        }
        return WorkoutSetRecord(checkNotNull(setId), ExerciseCounter.Exercise.pushUp, setStart, end, reason,
            closed + listOfNotNull(attempt?.record(checkNotNull(setId), end, "inProgress")),
            pose.backend, pose.requestRevision, schemaVersion = 2, trackingSide = trackingSide,
            imageSize = pose.imageSize, trackedAtEpochMilliseconds = epoch,
            countMethod = countMethod, countPolicyVersion = SideViewPushUpMotion.policyVersion,
            boundaryPolicyVersion = boundaryPolicyVersion)
    }
    private fun resetSet() {
        setId = null; firstPose = null; trackingSide = null; setStart = null
        lastCheckpoint = null; closed.clear(); attempt = null
    }
    fun nextWrite(): Write? = writes.firstOrNull()
    fun acknowledgeWrite() { writes.removeFirst() }
}

/** Native owners forward writes to their existing serial durable outbox. */
class SideViewPushUpWorkoutBridge(sessionId: String, trackedAtEpochMilliseconds: Long = 0) {
    private val workout = SideViewPushUpWorkout(sessionId, trackedAtEpochMilliseconds.takeIf { it > 0 })
    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    fun observeJson(poseJson: String, camera: String): String = WorkoutRecordCodec.json.encodeToString(
        workout.observe(WorkoutRecordCodec.json.decodeFromString<PoseResult>(poseJson), SideViewPushUpMotion.Camera.valueOf(camera)))
    fun finish(reason: String) = workout.finish(reason)
    fun nextWriteJson(): String? = workout.nextWrite()?.let { WorkoutRecordCodec.json.encodeToString(it) }
    fun acknowledgeWrite() = workout.acknowledgeWrite()
}
