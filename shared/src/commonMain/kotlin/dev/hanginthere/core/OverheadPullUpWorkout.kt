package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.math.min

/** Source-owned overhead image cycles. Camera qualification is independent;
 * neither apparatus detection nor grip metadata can authorize a movement. */
class OverheadPullUpWorkout(private val setId: String, private val trackedAtEpochMilliseconds: Long? = null) {
    companion object { const val boundaryPolicyVersion = 8 }
    @Serializable data class Snapshot(val motion: OverheadPullUpMotion.Snapshot, val started: Boolean,
        val analysisRevision: Int, val analyzedFrames: Int, val usableFrames: Int, val endReason: String?)
    private data class Observed(val step: OverheadPullUpMotion.AnalysisStep, val pose: PoseResult)
    private val motion = OverheadPullUpMotion()
    private var analysis = newAnalysis()
    private fun newAnalysis() = WorkoutAnalysis(setId, ExerciseCounter.Exercise.pullUp,
        OverheadPullUpMotion.policyVersion, OverheadPullUpMotion.countMethod)
    private val boundary = AutomaticSetBoundary()
    // Private qualification can deliver earlier actual source samples. Do not
    // publish them as unusable and then rewind the analysis when proof arrives.
    private val pending = linkedMapOf<PresentationTime, Observed>()
    private var ready: Observed? = null
    private var manual: Observed? = null
    private var support: OverheadPullUpMotion.Sample? = null
    private var witnessedOverheadHold = false
    private var generation: Long? = null
    private var analysisStarted = false
    private var started = false
    private var first: Double? = null
    private var firstInput: Double? = null
    private var last: PoseResult? = null
    private var recordedContext: PoseResult? = null
    private var calendarStart: Long? = null
    private var ending: String? = null
    private var analyzed = 0
    private var usable = 0
    val hasStarted get() = started
    val isFinished get() = ending != null
    val revision get() = analysis.revision
    val closedRecordCount get() = if(started) analysis.records.size else 0
    val gripLabel get() = analysis.currentGrip?.label

    fun observe(selection: BodyAthleteSelection, camera: OverheadPullUpMotion.Camera,
        automaticFinish: Boolean = true, automaticStart: Boolean = true): Snapshot {
        check(!isFinished) { "Create a new owner after finishing" }
        val frame = checkNotNull(selection.frame)
        val pose = frame.pose
        val lost = frame.decision.status == BodyAthleteAssociation.Status.lost ||
            generation?.let { it != frame.decision.sourceGeneration } == true
        generation = frame.decision.sourceGeneration
        val contextChanged = support?.let {
            it.size != pose.imageSize || it.backend != pose.backend || it.revision != pose.requestRevision
        } == true
        if(lost || contextChanged || camera == OverheadPullUpMotion.Camera.moved) {
            // Known context loss invalidates private calibration too, before
            // a set starts. An unknown camera may retain a lower constraint;
            // a moved camera must never carry it into a new setup.
            motion.interrupt(if(lost) "athleteAssociationLost" else "setupInvalidated")
            flushPending(); analysis.interrupt(if(lost) "athleteAssociationLost" else "setupInvalidated")
            boundary.breakContinuity(); ready = null; support = null; witnessedOverheadHold = false
        }
        val state = motion.observe(selection,setId,camera == OverheadPullUpMotion.Camera.ready,
            allowDeparture = started || automaticStart)
        manual = null
        if(!state.sourceAccepted) {
            flushPending(); analysis.interrupt(state.reason); boundary.breakContinuity()
            ready = null; support = null; witnessedOverheadHold = false
            return snapshot(state)
        }
        if(firstInput == null) firstInput = pose.timestamp.seconds
        last = pose
        val completions = motion.completedEvidence
        if(!started) (completions.firstOrNull()?.support ?: motion.measuredSupport)?.let { support = it }
        if(camera == OverheadPullUpMotion.Camera.ready &&
            (state.observationUsable && support?.overheadReleaseWitness == true ||
                completions.any { it.support.overheadReleaseWitness })) witnessedOverheadHold = true
        if(camera == OverheadPullUpMotion.Camera.ready && state.observationUsable && support != null &&
            state.phase == OverheadPullUpMotion.Phase.ready && OverheadSupportReleasePosture.matches(pose,down=false))
            witnessedOverheadHold = true

        val previous = pending.toMap()
        val currentStep = motion.analysisSteps.lastOrNull { it.time == pose.timestamp }
        if(currentStep != null) pending[pose.timestamp] = Observed(currentStep,pose)
        if(state.reason == "calibratingOverheadHands") {
            // This also bounds storage during high-frame-rate qualification.
            while(pending.size > 256) {
                val oldest = pending.keys.first()
                pending.remove(oldest)?.let { deliver(unqualified(it),pose.timestamp.seconds) }
            }
        } else {
            // Frames excluded by the qualified candidate still retain their
            // original clock/measurements, with no motion or hold credit.
            val delivery = pending.mapValues { unqualified(it.value) }.toMutableMap()
            for(step in motion.analysisSteps) {
                val original = if(step.time == pose.timestamp) pose else previous[step.time]?.pose
                checkNotNull(original) { "Calibration must retain each original source frame" }
                delivery[step.time] = Observed(step,original)
            }
            pending.clear()
            delivery.values.sortedWith { a,b ->
                checkNotNull(SourceTimeComparison.compareElapsed(a.step.time,b.step.time,SourceTimeLimit(0,1)))
            }.forEach { deliver(it,pose.timestamp.seconds) }
        }
        if(!started && automaticStart && analysisStarted &&
            (completions.isNotEmpty() || state.observationUsable && motion.hasObservedExcursion)) {
            started = true; recordedContext = pose; bindCalendarStart()
        }
        if(!started && state.phase == OverheadPullUpMotion.Phase.ready && state.observationUsable)
            currentStep?.let { manual = Observed(it,pose) }
        val associated = frame.decision.status == BodyAthleteAssociation.Status.associated
        val cameraReady = camera == OverheadPullUpMotion.Camera.ready
        val positiveRelease = cameraReady && associated && support?.let { OverheadPullUpMotion.positiveRelease(pose,it) } == true
        val postureReleased = cameraReady && associated && witnessedOverheadHold &&
            support?.let { OverheadPullUpMotion.handsDeparted(pose,it) } == true &&
            OverheadSupportReleasePosture.matches(pose,down=true)
        if(started) {
            when {
                lost -> finish("trackingLost")
                camera == OverheadPullUpMotion.Camera.moved || contextChanged -> finish("setupInvalidated")
                automaticFinish -> boundary.observe(feature(currentStep,pose,positiveRelease || postureReleased,postureReleased),true)?.let(::finish)
            }
        } else if(analysisStarted && state.phase in listOf(OverheadPullUpMotion.Phase.ready,OverheadPullUpMotion.Phase.idle) &&
            !motion.hasObservedExcursion) resetPrivateAnalysis()
        return snapshot(state)
    }
    private fun snapshot(state: OverheadPullUpMotion.Snapshot) = Snapshot(state,started,analysis.revision,analyzed,usable,ending)
    private fun unqualified(observed: Observed) = observed.copy(step=observed.step.copy(
        usable=false,phase=OverheadPullUpMotion.Phase.idle,event=null))
    private fun feature(step: OverheadPullUpMotion.AnalysisStep?,pose: PoseResult,
        released: Boolean = false,postureReleased: Boolean = false): MotionSample {
        val sample = step?.sample
        return MotionSample(pose.timestamp.seconds,sample?.arms?.get(0)?.degrees,sample?.arms?.get(1)?.degrees,
            if(step?.usable == true) sample?.arms?.map { it.shoulder.y / min(pose.imageSize.width,pose.imageSize.height) }?.average() else null,
            step?.usable == true,released,postureReleased)
    }
    private fun deliver(observed: Observed,confirmedAt: Double) {
        val step = observed.step
        val phase = when(step.phase) {
            OverheadPullUpMotion.Phase.idle -> ExerciseCounter.Phase.seekingStart
            OverheadPullUpMotion.Phase.ready -> ExerciseCounter.Phase.ready
            OverheadPullUpMotion.Phase.outbound -> ExerciseCounter.Phase.outbound
            OverheadPullUpMotion.Phase.returning -> ExerciseCounter.Phase.returning
        }
        if(!analysisStarted && step.usable && phase == ExerciseCounter.Phase.outbound) {
            analysisStarted = true
            ready?.takeIf { step.time.seconds > it.step.time.seconds &&
                step.time.elapsedSeconds(it.step.time) <= ExerciseCounter.maximumGapSeconds }?.let {
                first = it.step.time.seconds; analyze(it,ExerciseCounter.Phase.ready,null)
            }
        }
        if(analysisStarted || started) {
            val event = step.event?.let { event -> WorkoutAnalysis.MotionEvent(when(event.outcome) {
                "imageCycle" -> WorkoutAnalysis.MotionOutcome.completed
                "partial" -> WorkoutAnalysis.MotionOutcome.partial
                else -> WorkoutAnalysis.MotionOutcome.interrupted
            },event.observedCompletion ?: event.seconds,event.reason,event.peak.takeIf { event.outcome == "imageCycle" },
                confirmedAt.takeIf { event.outcome == "imageCycle" }) }
            if(first == null) first = step.time.seconds
            analyze(observed,phase,event)
        }
        ready = observed.takeIf { !analysisStarted && step.usable && phase == ExerciseCounter.Phase.ready }
    }
    private fun analyze(observed: Observed,phase: ExerciseCounter.Phase,event: WorkoutAnalysis.MotionEvent?) {
        val sample = feature(observed.step,observed.pose)
        analysis.observeGrip(observed.pose,null,phase,sample.usable)
        analysis.observeMotion(sample,phase,event)
        analyzed++; if(sample.usable) usable++
    }
    private fun flushPending() {
        pending.values.forEach { deliver(unqualified(it),last?.timestamp?.seconds ?: it.step.time.seconds) }
        pending.clear()
    }
    private fun resetPrivateAnalysis() {
        analysis = newAnalysis(); analysisStarted = false; first = null; analyzed = 0; usable = 0; ready = null
    }
    private fun bindCalendarStart() {
        if(calendarStart == null && trackedAtEpochMilliseconds != null) {
            val elapsed = ((first ?: return)-(firstInput ?: return))*1000
            if(elapsed.isFinite() && elapsed >= 0 && elapsed < Long.MAX_VALUE-trackedAtEpochMilliseconds.toDouble())
                calendarStart = trackedAtEpochMilliseconds+elapsed.toLong()
        }
    }
    fun startManually(): Boolean {
        if(started || isFinished || analysisStarted) return false
        val prepared = manual ?: return false
        started = true; analysisStarted = true; first = prepared.step.time.seconds
        recordedContext = prepared.pose; bindCalendarStart()
        analyze(prepared,ExerciseCounter.Phase.ready,null); manual = null
        return true
    }
    fun interrupt(reason: String) {
        if(isFinished) return
        flushPending(); motion.interrupt(reason); analysis.interrupt(reason); boundary.breakContinuity()
        ready = null; manual = null; support = null; witnessedOverheadHold = false
        if(!started) resetPrivateAnalysis()
    }
    fun finish(reason: String) {
        if(isFinished) return
        require(reason.isNotBlank() && reason.length <= 64)
        flushPending(); motion.interrupt(reason); analysis.finish(reason); ending = reason
    }
    fun record(includeActive: Boolean = false): WorkoutSetRecord = record(
        if(started) analysis.records+listOfNotNull(if(includeActive)analysis.activeRecord else null) else emptyList(),analysis.activity)
    private fun record(reps: List<RepRecord>,activity: WorkoutActivity) = WorkoutSetRecord(
        setId,ExerciseCounter.Exercise.pullUp,first.takeIf { started },last?.timestamp?.seconds,ending ?: "running",reps,
        recordedContext?.backend,recordedContext?.requestRevision,schemaVersion=2,imageSize=recordedContext?.imageSize,
        trackedAtEpochMilliseconds=calendarStart,activity=activity.takeIf { started },grip=analysis.gripRecord.takeIf { started },
        setGrip=analysis.setGrip.takeIf { started },countMethod=OverheadPullUpMotion.countMethod,
        countPolicyVersion=OverheadPullUpMotion.policyVersion,boundaryPolicyVersion=boundaryPolicyVersion)
    fun draftDeltaJson(): String? = if(started) WorkoutRecordCodec.json.encodeToString(
        record(listOfNotNull(analysis.activeRecord ?: analysis.records.lastOrNull()),analysis.activityDelta)) else null
    fun reviewDeltaJson(): String = WorkoutRecordCodec.json.encodeToString(record(
        if(started) listOfNotNull(analysis.activeRecord) else emptyList(),analysis.activity))
    fun lastClosedRecordJson(): String? = if(started) analysis.records.lastOrNull()?.let { WorkoutRecordCodec.json.encodeToString(it) } else null
}

class OverheadPullUpWorkoutBridge(setId: String,trackedAtEpochMilliseconds: Long = 0) {
    private val workout = OverheadPullUpWorkout(setId,trackedAtEpochMilliseconds.takeIf { it > 0 })
    val revision get() = workout.revision
    val closedRecordCount get() = workout.closedRecordCount
    val gripLabel get() = workout.gripLabel
    @Throws(IllegalArgumentException::class,IllegalStateException::class)
    fun observeSelectedJson(selection: BodyAthleteSelection,sourceValue: Long,sourceTimescale: Int,
        camera: String,automaticFinish: Boolean,automaticStart: Boolean): String {
        require(selection.frame?.raw?.timestamp == PresentationTime(sourceValue,sourceTimescale)) {
            "The selected athlete must belong to this exact source frame"
        }
        return WorkoutRecordCodec.json.encodeToString(workout.observe(selection,OverheadPullUpMotion.Camera.valueOf(camera),
            automaticFinish,automaticStart))
    }
    fun startManually() = workout.startManually()
    fun interrupt(reason: String) = workout.interrupt(reason)
    fun finish(reason: String) = workout.finish(reason)
    fun setJson() = WorkoutRecordCodec.json.encodeToString(workout.record())
    fun draftDeltaJson() = workout.draftDeltaJson()
    fun reviewDeltaJson() = workout.reviewDeltaJson()
    fun lastClosedRecordJson() = workout.lastClosedRecordJson()
}
