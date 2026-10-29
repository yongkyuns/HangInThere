package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlin.math.hypot
import kotlin.math.min

/** One rail-free set owner. Native capture owns real camera calibration pixels
 * and phone/scene stability; the shared owner receives that independent verdict.
 * Readiness, motion, positive release and durable analytics use source time.
 */
class BodyDipWorkout(private val setId: String, private val trackedAtEpochMilliseconds: Long? = null) {
    @Serializable data class Snapshot(val motion: BodyDipMotion.Snapshot, val started: Boolean,
        val analysisRevision: Int, val analyzedFrames: Int, val usableFrames: Int, val endReason: String?)
    private val motion = BodyDipMotion()
    private val analysis = WorkoutAnalysis(setId, ExerciseCounter.Exercise.dip,
        BodyDipMotion.policyVersion, BodyDipMotion.countMethod)
    private val boundary = AutomaticSetBoundary()
    private var first: Double? = null
    private var last: PoseResult? = null
    private var started = false
    private var endReason: String? = null
    private var analyzed = 0
    private var usable = 0
    private var readinessSample: MotionSample? = null
    private var supportWitness: BodyDipMotion.Sample? = null
    private var releaseLast: PresentationTime? = null
    private var releaseTorsoExited = false
    private var manualPreparation: Pair<PoseResult, MotionSample>? = null
    private var firstInputSeconds: Double? = null
    private var startedEpochMilliseconds: Long? = null
    val closedRecordCount get() = analysis.records.size
    val hasStarted get() = started
    val isFinished get() = endReason != null
    val revision get() = analysis.revision

    fun observe(pose: PoseResult, camera: BodyDipMotion.Camera, automaticFinish: Boolean = true,
        automaticStart: Boolean = true): Snapshot {
        check(endReason == null) { "Create a new set owner after finishing" }
        val state = motion.observe(pose, camera, allowDeparture = started || automaticStart)
        manualPreparation = null
        if(!state.sourceAccepted) {
            analysis.interrupt(state.reason); boundary.breakContinuity(); readinessSample = null; supportWitness = null
            resetReleaseWitness()
            return Snapshot(state, started, analysis.revision, analyzed, usable, endReason)
        }
        if(firstInputSeconds == null) firstInputSeconds = pose.timestamp.seconds
        val current = BodyDipMotion.sample(pose)
        // Unavailable registration supplies neither motion nor release credit,
        // but does not prove that this camera epoch moved. Retain the private
        // measured hand locations so fresh, requalified frames can witness a
        // later dismount. Actual movement/context/source loss still revoke them.
        if(camera == BodyDipMotion.Camera.moved ||
            current != null && supportWitness?.let { !BodyDipMotion.sameContext(it, current) } == true) supportWitness = null
        if(supportWitness == null && state.phase == BodyDipMotion.Phase.ready) supportWitness = motion.measuredTop
        val short = min(pose.imageSize.width, pose.imageSize.height)
        val feature = MotionSample(pose.timestamp.seconds, current?.arms?.get(0)?.degrees,
            current?.arms?.get(1)?.degrees,
            if(state.observationUsable) current?.arms?.map { it.shoulder.y / short }?.average() else null,
            state.observationUsable, released(current, camera, pose.timestamp))
        val phase = when(state.phase) {
            BodyDipMotion.Phase.seekingTop -> ExerciseCounter.Phase.seekingStart
            BodyDipMotion.Phase.ready -> ExerciseCounter.Phase.ready
            BodyDipMotion.Phase.outbound -> ExerciseCounter.Phase.outbound
            BodyDipMotion.Phase.returning -> ExerciseCounter.Phase.returning
        }
        val event = state.event?.let { e -> WorkoutAnalysis.MotionEvent(when(e.outcome) {
            BodyDipMotion.Outcome.imageCycle -> WorkoutAnalysis.MotionOutcome.completed
            BodyDipMotion.Outcome.partial -> WorkoutAnalysis.MotionOutcome.partial
            BodyDipMotion.Outcome.interrupted -> WorkoutAnalysis.MotionOutcome.interrupted
        }, e.seconds, e.reason, e.peak.takeIf { e.outcome == BodyDipMotion.Outcome.imageCycle }) }
        if(!started && phase == ExerciseCounter.Phase.ready && feature.usable) manualPreparation = pose to feature
        // A static arms-down stance can resemble supported dip posture. It may
        // prepare a baseline but cannot commit an automatic workout. Require
        // the detector's bilateral shoulder travel AND elbow-bending departure.
        if(!started && phase in listOf(ExerciseCounter.Phase.outbound, ExerciseCounter.Phase.returning)) {
            started = true
            readinessSample?.takeIf { feature.seconds > it.seconds && feature.seconds - it.seconds <= ExerciseCounter.maximumGapSeconds }?.let {
                first = it.seconds; analysis.observeMotion(it, ExerciseCounter.Phase.ready, null); analyzed++; usable++
            }
        }
        if(started) {
            analysis.observeMotion(feature, phase, event)
            analyzed++; if(feature.usable) usable++
            if(first == null) first = feature.seconds
            bindCalendarStart()
            last = pose
            if(camera == BodyDipMotion.Camera.moved) finish("setupInvalidated")
            else if(automaticFinish) boundary.observe(feature, true)?.let(::finish)
        }
        readinessSample = feature.takeIf { !started && phase == ExerciseCounter.Phase.ready }
        return Snapshot(state, started, analysis.revision, analyzed, usable, endReason)
    }

    /** An explicit user start can record hold-only practice. Preparation while
     * automatic start is disabled cannot accumulate cycles before the tap. The
     * native owner also requires a fresh frame and qualified camera at the tap. */
    fun startManually(): Boolean {
        if(started || isFinished) return false
        val (pose, feature) = manualPreparation ?: return false
        started = true; first = feature.seconds; last = pose
        bindCalendarStart()
        analysis.observeMotion(feature, ExerciseCounter.Phase.ready, null); analyzed++; usable++
        manualPreparation = null
        return true
    }
    private fun bindCalendarStart() {
        if(startedEpochMilliseconds == null && trackedAtEpochMilliseconds != null) {
            val elapsed = ((first ?: return) - (firstInputSeconds ?: return)) * 1000
            if(elapsed.isFinite() && elapsed >= 0 && elapsed < Long.MAX_VALUE-trackedAtEpochMilliseconds.toDouble())
                startedEpochMilliseconds = trackedAtEpochMilliseconds + elapsed.toLong()
        }
    }

    /** Both currently measurable wrists must positively leave their own measured
     * starting locations. Missing hands/arms cannot supply release. This is image
     * disengagement evidence, not physical bar contact or a rail prediction. */
    private fun released(current: BodyDipMotion.Sample?, camera: BodyDipMotion.Camera, time: PresentationTime): Boolean {
        val gap = releaseLast?.let { SourceTimeComparison.compareElapsed(time,it,SourceTimeLimit(7,20)) }
        if(releaseLast != null && (gap == null || gap > 0)) releaseTorsoExited = false
        releaseLast = time
        val support = supportWitness
        if(support == null || current == null || camera != BodyDipMotion.Camera.ready || !BodyDipMotion.sameContext(support, current)) {
            releaseTorsoExited = false
            return false
        }
        // Reuse the motion policy's arm-relative support-loss distance. Also
        // require the torso to leave its original support zone: regripping or
        // raising the hands while staying at the top is not a dismount.
        val arms = current.arms.zip(support.arms)
        if(!arms.all { (now,top) -> hypot(now.wrist.x-top.wrist.x, now.wrist.y-top.wrist.y) > .12 * top.length }) {
            releaseTorsoExited = false
            return false
        }
        if(arms.all { (now, top) ->
            val span = hypot(top.shoulder.x-top.wrist.x, top.shoulder.y-top.wrist.y)
            val lowered = -((now.shoulder.x-top.shoulder.x)*(top.shoulder.x-top.wrist.x) +
                (now.shoulder.y-top.shoulder.y)*(top.shoulder.y-top.wrist.y)) / span
            lowered >= .35 * top.length
        }) releaseTorsoExited = true
        // Walking toward a fixed camera can raise the shoulders in the image.
        // Once torso exit was actually seen with BOTH wrists departed, keep it
        // only while both remain continuously departed. The common one-second
        // dwell is unchanged; returning either wrist, missing camera/body,
        // source gaps or context loss revoke this witness.
        return releaseTorsoExited
    }
    private fun resetReleaseWitness() { releaseLast = null; releaseTorsoExited = false }
    fun interrupt(reason: String) {
        if(isFinished) return
        motion.interrupt(reason); analysis.interrupt(reason); boundary.breakContinuity()
        resetReleaseWitness()
        readinessSample = null; supportWitness = null; manualPreparation = null
    }
    fun finish(reason: String) {
        if(isFinished) return
        require(reason.isNotBlank() && reason.length <= 64)
        motion.interrupt(reason); analysis.finish(reason); endReason = reason
    }
    fun record(includeActive: Boolean = false): WorkoutSetRecord = record(
        analysis.records + listOfNotNull(if(includeActive)analysis.activeRecord else null), analysis.activity)
    private fun record(reps: List<RepRecord>, activity: WorkoutActivity): WorkoutSetRecord = WorkoutSetRecord(
        setId, ExerciseCounter.Exercise.dip, first, last?.timestamp?.seconds, endReason ?: "running", reps,
        last?.backend, last?.requestRevision, schemaVersion = 2, imageSize = last?.imageSize,
        trackedAtEpochMilliseconds = startedEpochMilliseconds, activity = activity,
        countMethod = BodyDipMotion.countMethod, countPolicyVersion = BodyDipMotion.policyVersion)
    fun draftDeltaJson(): String? = if(analysis.activity.hasPractice || analysis.activeRecord != null || analysis.records.isNotEmpty())
        WorkoutRecordCodec.json.encodeToString(record(listOfNotNull(analysis.activeRecord ?: analysis.records.lastOrNull()), analysis.activityDelta)) else null
    fun reviewDeltaJson(): String = WorkoutRecordCodec.json.encodeToString(record(listOfNotNull(analysis.activeRecord), analysis.activity))
    fun lastClosedRecordJson(): String? = analysis.records.lastOrNull()?.let { WorkoutRecordCodec.json.encodeToString(it) }
}

/** Apple ABI uses the same rail-free owner as Kotlin clients. Camera readiness
 * is supplied by the actual native phone/scene gate, never inferred from a pose. */
class BodyDipWorkoutBridge(setId: String, trackedAtEpochMilliseconds: Long = 0) {
    private val workout = BodyDipWorkout(setId, trackedAtEpochMilliseconds.takeIf { it > 0 })
    val closedRecordCount get() = workout.closedRecordCount
    val revision get() = workout.revision
    val hasStarted get() = workout.hasStarted
    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    fun observeJson(poseJson: String, camera: String, automaticFinish: Boolean): String =
        WorkoutRecordCodec.json.encodeToString(workout.observe(WorkoutRecordCodec.json.decodeFromString(poseJson),
            BodyDipMotion.Camera.valueOf(camera), automaticFinish))
    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    fun observePreparedJson(poseJson: String, camera: String, automaticFinish: Boolean, automaticStart: Boolean): String =
        WorkoutRecordCodec.json.encodeToString(workout.observe(WorkoutRecordCodec.json.decodeFromString(poseJson),
            BodyDipMotion.Camera.valueOf(camera), automaticFinish, automaticStart))
    fun startManually(): Boolean = workout.startManually()
    fun interrupt(reason: String) = workout.interrupt(reason)
    fun finish(reason: String) = workout.finish(reason)
    fun setJson(): String = WorkoutRecordCodec.json.encodeToString(workout.record())
    fun draftDeltaJson(): String? = workout.draftDeltaJson()
    fun reviewDeltaJson(): String = workout.reviewDeltaJson()
    fun lastClosedRecordJson(): String? = workout.lastClosedRecordJson()
}
