package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/** Shared durable owner. Leg-raise timing is recorded without relabelling hip
 * angles as elbow metrics or inventing physiological effort measurements. */
class LyingLegRaiseWorkout(private val sessionId: String, private val trackedAtEpochMilliseconds: Long? = null) {
    init { require(sessionId.length <= 39 && WorkoutCorrections.safeId(sessionId)) }
    @Serializable data class Write(val record: WorkoutSetRecord, val finalized: Boolean)
    private val motion = LyingLegRaiseMotion()
    private val writes = ArrayDeque<Write>()
    private var origin: Double? = null
    private var serial = 0
    private var id: String? = null
    private var firstPose: PoseResult? = null
    private var side: ArmMeasurement.Side? = null
    private var start: Double? = null
    private var checkpoint: Double? = null
    private var pendingStart: Double? = null
    private var observed = 0.0
    private var previous: Pair<Double,Boolean>? = null
    private val reps = mutableListOf<RepRecord>()
    fun observe(pose: PoseResult, camera: SideViewPushUpMotion.Camera): LyingLegRaiseMotion.Snapshot {
        val seconds = pose.timestamp.seconds
        if (origin == null && seconds.isFinite() && seconds >= 0) origin = seconds
        val state = motion.observe(pose,camera)
        state.endedSet?.let { finishSet(it.endedAt,it.endReason) }
        val departure = motion.attemptStartedAt
        if (departure != null && pendingStart == null) {
            pendingStart = departure; observed = 0.0; previous = null
        }
        if (pendingStart != null) {
            previous?.let { (at,usable) ->
                val dt = seconds-at
                if (usable && state.observationUsable && dt > 0 && dt <= .35) observed += dt
            }
            previous = seconds to state.observationUsable
        }
        if (state.setStarted && id == null) {
            id = "$sessionId-lyinglegraise-${serial++}"; firstPose = pose; side = state.side; start = checkNotNull(pendingStart)
        }
        state.rep?.let { rep ->
            reps += attempt(rep.returnedAt,"returned",rep)
            pendingStart = null; previous = null; observed = 0.0
        }
        if (departure == null && state.rep == null) { pendingStart = null; previous = null; observed = 0.0 }
        if (state.setStarted && (state.rep != null || checkpoint?.let { seconds-it >= 1 } != false)) {
            writes += Write(record(seconds,"running"),false); checkpoint = seconds
        }
        return state
    }
    private fun attempt(end: Double, reason: String, rep: LyingLegRaiseMotion.Rep? = null): RepRecord {
        val from = rep?.startedAt ?: checkNotNull(pendingStart)
        val extreme = rep?.raisedAt ?: motion.extremeAt
        val absent = AngleMetrics(null,null,null,0.0,null)
        val duration = end-from
        return RepRecord("${checkNotNull(id)}:rep:${reps.size+1}",checkNotNull(id),reps.size+1,
            ExerciseCounter.Exercise.lyingLegRaise,from,extreme,rep?.returnedAt,rep?.returnedAt,end,reason,
            if (rep != null) "accepted" else "interrupted",rep?.let { it.raisedAt-from },
            rep?.let { it.returnedAt-it.raisedAt },absent,absent,null,null,observed,
            if(duration > 0) (observed/duration).coerceIn(0.0,1.0) else null,emptyList(),false,
            LyingLegRaiseMotion.policyVersion,WorkoutAnalysis.metricVersion,
            acceptanceReason = if(rep != null) "lyingLegRaiseCycle" else null,countMethod = LyingLegRaiseMotion.countMethod)
    }
    private fun record(end: Double, reason: String): WorkoutSetRecord {
        val pose = checkNotNull(firstPose)
        val epoch = trackedAtEpochMilliseconds?.let { at ->
            val delta = (checkNotNull(start)-checkNotNull(origin))*1000
            if (delta.isFinite() && delta >= 0 && delta < Long.MAX_VALUE-at.toDouble()) at+delta.toLong() else null
        }
        return WorkoutSetRecord(checkNotNull(id),ExerciseCounter.Exercise.lyingLegRaise,start,end,reason,
            reps.toList()+listOfNotNull(pendingStart?.let { attempt(end,"inProgress") }),pose.backend,pose.requestRevision,
            schemaVersion=2,trackingSide=side,imageSize=pose.imageSize,trackedAtEpochMilliseconds=epoch,
            countMethod=LyingLegRaiseMotion.countMethod,countPolicyVersion=LyingLegRaiseMotion.policyVersion,
            boundaryPolicyVersion=LyingLegRaiseMotion.boundaryPolicyVersion)
    }
    private fun finishSet(at: Double, reason: String) {
        if (id != null) {
            if (pendingStart != null) { reps += attempt(at,reason); pendingStart = null }
            writes += Write(record(at,reason),true)
        }
        id=null; firstPose=null; side=null; start=null; checkpoint=null
        pendingStart=null; previous=null; observed=0.0; reps.clear()
    }
    fun finish(reason: String) {
        motion.finish(reason)?.let { finishSet(it.endedAt,it.endReason) }
        pendingStart=null; previous=null; observed=0.0
    }
    fun nextWrite(): Write? = writes.firstOrNull()
    fun acknowledgeWrite() { writes.removeFirst() }
}
