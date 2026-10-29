package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/** One source-owned pull-up set. Preparation may observe a confirmed bar before
 * posture identity finishes. A camera-qualified measured bilateral excursion
 * beside the observed bar independently establishes pull-up movement and commits
 * an automatic workout. A static pose alone cannot commit it. Grip is metadata. */
class BodyPullUpWorkout(private val setId: String, private val trackedAtEpochMilliseconds: Long? = null) {
    @Serializable data class Snapshot(val motion: PullUpCycleMotion.Snapshot, val started: Boolean,
        val analysisRevision: Int, val analyzedFrames: Int, val usableFrames: Int, val endReason: String?)
    private val motion = PullUpCycleMotion()
    private var analysis = newAnalysis()
    private fun newAnalysis() = WorkoutAnalysis(setId, ExerciseCounter.Exercise.pullUp,
        PullUpCycleMotion.policyVersion, PullUpCycleMotion.countMethod)
    private val features = WorkoutMotionFeatures()
    private val boundary = AutomaticSetBoundary()
    private var started = false
    private var analysisStarted = false
    private var endReason: String? = null
    private var first: Double? = null
    private var firstInput: Double? = null
    private var last: PoseResult? = null
    private var reference: BarSegment? = null
    private var recordedReference: BarSegment? = null
    private var recordedContext: PoseResult? = null
    private var ready: Pair<PoseResult, MotionSample>? = null
    private var manual: Pair<PoseResult, MotionSample>? = null
    private var analyzed = 0
    private var usable = 0
    private var calendarStart: Long? = null
    val hasStarted get() = started
    val isFinished get() = endReason != null
    val revision get() = analysis.revision
    val closedRecordCount get() = if (started) analysis.records.size else 0
    val gripLabel get() = analysis.currentGrip?.label

    fun observe(pose: PoseResult, edge: BarSegment?, camera: PullUpCycleMotion.Camera,
        automaticStart: Boolean = true, automaticFinish: Boolean = true): Snapshot {
        check(!isFinished) { "Create a new owner after finishing" }
        if (reference != null && edge != null && reference != edge.ordered) interrupt("supportChanged")
        if (reference == null && edge?.isValid == true) reference = edge.ordered
        val state = motion.observe(pose, setId, camera == PullUpCycleMotion.Camera.ready, edge,
            allowDeparture = started || automaticStart)
        manual = null
        if (!state.sourceAccepted) {
            analysis.interrupt(state.reason); boundary.breakContinuity(); ready = null; features.reset()
            return Snapshot(state, started, analysis.revision, analyzed, usable, endReason)
        }
        if (firstInput == null) firstInput = pose.timestamp.seconds
        if (camera == PullUpCycleMotion.Camera.moved) features.reset()
        val phase = when (state.phase) {
            PullUpCycleMotion.Phase.idle -> ExerciseCounter.Phase.seekingStart
            PullUpCycleMotion.Phase.ready -> ExerciseCounter.Phase.ready
            PullUpCycleMotion.Phase.outbound -> ExerciseCounter.Phase.outbound
            PullUpCycleMotion.Phase.returning -> ExerciseCounter.Phase.returning
        }
        val feature = features.extract(pose, edge, state.observationUsable, phase)
        if (!started && phase == ExerciseCounter.Phase.ready && feature.usable) manual = pose to feature
        if (!analysisStarted && phase == ExerciseCounter.Phase.outbound && feature.usable) {
            analysisStarted = true
            ready?.takeIf { pose.timestamp.seconds > it.first.timestamp.seconds &&
                pose.timestamp.elapsedSeconds(it.first.timestamp) <= ExerciseCounter.maximumGapSeconds }?.let { (p, f) ->
                first = f.seconds; observeAnalysis(p, f, ExerciseCounter.Phase.ready, null)
            }
        }
        if (analysisStarted || started) {
            val event = state.event?.let { e -> WorkoutAnalysis.MotionEvent(when (e.outcome) {
                "imageCycle" -> WorkoutAnalysis.MotionOutcome.completed
                "partial" -> WorkoutAnalysis.MotionOutcome.partial
                else -> WorkoutAnalysis.MotionOutcome.interrupted
            }, e.seconds, e.reason, e.peak.takeIf { e.outcome == "imageCycle" }) }
            if (first == null) first = feature.seconds
            observeAnalysis(pose, feature, phase, event)
        }
        if (!started && automaticStart && state.observationUsable && analysisStarted && motion.hasObservedExcursion) {
            started = true; recordedReference = reference; recordedContext = pose; bindCalendarStart()
        }
        if (started) {
            last = pose
            if (camera == PullUpCycleMotion.Camera.moved) finish("setupInvalidated")
            else if (automaticFinish) boundary.observe(feature, true)?.let(::finish)
        } else if (analysisStarted && phase == ExerciseCounter.Phase.ready && !motion.hasObservedExcursion) {
            // A shallow setup bob has not committed a set or earned a cycle.
            analysis = newAnalysis(); analysisStarted = false; first = null; analyzed = 0; usable = 0
        }
        ready = (pose to feature).takeIf { !analysisStarted && phase == ExerciseCounter.Phase.ready && feature.usable }
        return Snapshot(state, started, analysis.revision, analyzed, usable, endReason)
    }

    private fun observeAnalysis(pose: PoseResult, feature: MotionSample, phase: ExerciseCounter.Phase,
        event: WorkoutAnalysis.MotionEvent?) {
        analysis.observeGrip(pose, reference, phase, feature.usable)
        analysis.observeMotion(feature, phase, event)
        analyzed++; if (feature.usable) usable++
        last = pose
    }
    private fun bindCalendarStart() {
        if (calendarStart == null && trackedAtEpochMilliseconds != null) {
            val elapsed = ((first ?: return) - (firstInput ?: return)) * 1000
            if (elapsed.isFinite() && elapsed >= 0 && elapsed < Long.MAX_VALUE - trackedAtEpochMilliseconds.toDouble())
                calendarStart = trackedAtEpochMilliseconds + elapsed.toLong()
        }
    }
    fun startManually(): Boolean {
        if (started || isFinished || analysisStarted) return false
        val (pose, feature) = manual ?: return false
        started = true; analysisStarted = true; first = feature.seconds
        recordedReference = reference; recordedContext = pose; bindCalendarStart()
        observeAnalysis(pose, feature, ExerciseCounter.Phase.ready, null)
        manual = null
        return true
    }
    fun interrupt(reason: String) {
        if (isFinished) return
        motion.interrupt(reason); analysis.interrupt(reason); features.reset(); boundary.breakContinuity()
        reference = null; ready = null; manual = null
        if (!started) { analysis = newAnalysis(); analysisStarted = false; first = null; analyzed = 0; usable = 0 }
    }
    fun finish(reason: String) {
        if (isFinished) return
        require(reason.isNotBlank() && reason.length <= 64)
        motion.interrupt(reason); analysis.finish(reason); endReason = reason
    }
    fun record(includeActive: Boolean = false): WorkoutSetRecord = record(
        if (started) analysis.records + listOfNotNull(if (includeActive) analysis.activeRecord else null) else emptyList(), analysis.activity)
    private fun record(reps: List<RepRecord>, activity: WorkoutActivity): WorkoutSetRecord = WorkoutSetRecord(
        setId, ExerciseCounter.Exercise.pullUp, first.takeIf { started }, last?.timestamp?.seconds,
        endReason ?: "running", reps.takeIf { started } ?: emptyList(), recordedContext?.backend, recordedContext?.requestRevision, schemaVersion = 2,
        imageSize = recordedContext?.imageSize, referenceEdge = recordedReference,
        trackedAtEpochMilliseconds = calendarStart, activity = activity.takeIf { started },
        grip = analysis.gripRecord.takeIf { started }, setGrip = analysis.setGrip.takeIf { started },
        countMethod = PullUpCycleMotion.countMethod, countPolicyVersion = PullUpCycleMotion.policyVersion,
        boundaryPolicyVersion = WorkoutLifecycle.boundaryPolicyVersion)
    fun draftDeltaJson(): String? = if (started && (analysis.activity.hasPractice || analysis.activeRecord != null || analysis.records.isNotEmpty()))
        WorkoutRecordCodec.json.encodeToString(record(listOfNotNull(analysis.activeRecord ?: analysis.records.lastOrNull()), analysis.activityDelta)) else null
    fun reviewDeltaJson(): String = WorkoutRecordCodec.json.encodeToString(record(listOfNotNull(analysis.activeRecord), analysis.activity))
    fun lastClosedRecordJson(): String? = if (started) analysis.records.lastOrNull()?.let { WorkoutRecordCodec.json.encodeToString(it) } else null
}

/** Exact original source ticks travel inside poseJson, without scalar clock conversion. */
class BodyPullUpWorkoutBridge(setId: String, trackedAtEpochMilliseconds: Long = 0) {
    private val workout = BodyPullUpWorkout(setId, trackedAtEpochMilliseconds.takeIf { it > 0 })
    val revision get() = workout.revision
    val closedRecordCount get() = workout.closedRecordCount
    val gripLabel get() = workout.gripLabel
    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    fun observePreparedJson(poseJson: String, referenceJson: String?, camera: String,
        automaticStart: Boolean): String = WorkoutRecordCodec.json.encodeToString(workout.observe(
        WorkoutRecordCodec.json.decodeFromString(poseJson), referenceJson?.let { WorkoutRecordCodec.json.decodeFromString<BarSegment>(it) },
        PullUpCycleMotion.Camera.valueOf(camera), automaticStart))
    fun startManually() = workout.startManually()
    fun interrupt(reason: String) = workout.interrupt(reason)
    fun finish(reason: String) = workout.finish(reason)
    fun setJson(): String = WorkoutRecordCodec.json.encodeToString(workout.record())
    fun draftDeltaJson() = workout.draftDeltaJson()
    fun reviewDeltaJson() = workout.reviewDeltaJson()
    fun lastClosedRecordJson() = workout.lastClosedRecordJson()
}
