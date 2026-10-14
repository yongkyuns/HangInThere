package dev.hanginthere.core

import kotlinx.serialization.Serializable

/**
 * Platform-free live setup and set lifecycle. Adapters deliver upright poses,
 * genuine contour candidates and independent camera evidence on one source clock.
 * [search] does not mutate setup. [observe] consumes detections synchronously from
 * that same pose, so adapters must reject canceled or stale inference completions.
 */
class WorkoutRuntime(selection:WorkoutExerciseSelection=WorkoutExerciseSelection.automatic,side:ArmMeasurement.Side=ArmMeasurement.Side.left, val automaticWorkouts:Boolean=false, private val nowEpochMilliseconds:()->Long?={null}) {
    @Serializable data class FrameInput(
        val pose:PoseResult,
        val candidates:List<List<BarLineCandidate>> = emptyList(),
        val attitude:PhoneOrientationStability.Quaternion? = null,
        val motionTimestamp:Double? = null,
        val translations:List<StaticSceneStability.PatchTranslation> = emptyList(),
        val globalScaleFraction:Double? = null,
        val sceneTimestamp:Double? = null,
        val paused:Boolean = false,
        val cameraReady:Boolean = true,
    )
    @Serializable data class Snapshot(
        val exercise:ExerciseCounter.Exercise?,
        val apparatusExercise:ExerciseCounter.Exercise?,
        val phase:LiveSetSession.Phase,
        val observedMovements:Int,
        val countdownSeconds:Int?,
        val referenceEdge:BarSegment?,
        val acquisitionState:AutomaticPullUpBar.State,
        val phoneState:PhoneOrientationStability.State,
        val sceneState:StaticSceneStability.State,
        val setupIssue:String?,
        val setupGeneration:Int,
        val isReplayReview:Boolean=false,
        val latestCompletedSet:WorkoutSetRecord?=null,
        val activeSetId:String?=null,
        val trackingIssue:String?=null,
        val framingHint:String?=null,
    )
    var selection=selection;private set
    var side=side;private set
    val session=LiveSetSession()
    private val automaticStart=AutomaticWorkoutStart()
    private var qualifiedStartPose:PoseResult?=null
    var latestCompletedSet:WorkoutSetRecord?=null;private set
    private fun captureCompletedSet(){if(session.phase==LiveSetSession.Phase.finished && !session.record.hasPractice) latestCompletedSet=null
        else if(session.phase==LiveSetSession.Phase.finished && session.record.hasPractice && latestCompletedSet?.id != session.setId) {
        val record = session.record
        val end = nowEpochMilliseconds()?.takeIf { start -> record.trackedAtEpochMilliseconds?.let { start >= it } == true }
        latestCompletedSet = record.copy(trackedEndedAtEpochMilliseconds = end)
    }}
    val phoneOrientation=PhoneOrientationStability()
    val staticScene=StaticSceneStability()
    val handsFreeStart=HandsFreeSetStart()
    private val identity=AutomaticExerciseDetection()
    private val apparatus=AutomaticApparatusReference()
    var confirmedBar:ConfirmedBar?=null;private set
    private var activeExercise=selection.exercise ?: ExerciseCounter.Exercise.pullUp
    private var discardNextSetFrame=false
    private val sourceTimeline=WorkoutSourceTimeline()
    private val lastFrameAt get()=sourceTimeline.lastSeconds
    private var lastAcquisitionSeconds:Double?=null
    private var replayExercise:ExerciseCounter.Exercise?=null
    private var replayReview=false
    private var issue:String?="waitingForFrame"
    private var generation=0
    private var framingHint:String?=null
    private val framingFeedback=WorkoutFramingFeedback()
    init {session.reset(activeExercise,side);session.automaticFinishEnabled=automaticWorkouts}
    val resolvedExercise get()=selection.exercise ?: replayExercise ?: identity.exercise
    /** Apparatus can precede stable Auto identity; recorded acquisition must await both. */
    val isReplayAcquisitionReady get() = confirmedBar != null && resolvedExercise != null
    val snapshot get()=Snapshot(resolvedExercise,selection.exercise ?: replayExercise ?: identity.apparatusCandidate,session.phase,session.observedMovements,handsFreeStart.state.seconds,confirmedBar?.referenceEdge,apparatus.state,phoneOrientation.state,staticScene.state,issue,generation,replayReview,latestCompletedSet,session.setId.takeIf { session.phase==LiveSetSession.Phase.running },session.trackingIssue,framingHint.takeIf { session.phase==LiveSetSession.Phase.idle && issue in listOf("framingIncomplete", "barReferenceNeeded", "exerciseIdentityNeeded") })

    fun configure(selection:WorkoutExerciseSelection,side:ArmMeasurement.Side=this.side) {
        if(session.phase==LiveSetSession.Phase.running || (this.selection==selection && this.side==side)) return
        this.selection=selection;this.side=side;identity.reset();replayExercise=null;replayReview=false
        activeExercise=selection.exercise ?: activeExercise
        clearSetup();session.reset(activeExercise,side)
    }
    fun search(pose:PoseResult):AutomaticApparatusReference.Search? {
        if(session.phase!=LiveSetSession.Phase.idle || confirmedBar!=null) return null
        val exercise=selection.exercise ?: replayExercise ?: AutomaticExerciseDetection.apparatusCandidate(pose) ?: return null
        return AutomaticApparatusReference.search(pose,exercise)
    }
    fun observe(input:FrameInput):Snapshot {
        val pose=input.pose;val seconds=pose.timestamp.seconds
        framingHint=null
        if(automaticWorkouts && !replayReview && session.phase==LiveSetSession.Phase.finished && WorkoutLifecycle.shouldResume(session.endReason?.name)){val lost=session.endReason==LiveSetSession.EndReason.trackingLost;captureCompletedSet();nextSet();if(lost) clearSetup()}
        if(!input.cameraReady){interrupt(LiveSetSession.EndReason.cameraFailure);issue="cameraUnavailable";return snapshot}
        if(confirmedBar!=null && confirmedBar!!.imageSize!=pose.imageSize){interrupt(LiveSetSession.EndReason.setupInvalidated);issue="barReferenceUnavailable";return snapshot}
        if(session.phase==LiveSetSession.Phase.idle && selection==WorkoutExerciseSelection.automatic && replayExercise==null) {
            val previous=identity.exercise;val previousApparatus=identity.apparatusCandidate
            identity.observe(pose)
            if(previousApparatus!=identity.apparatusCandidate || (previous!=null && identity.exercise==null)) {
                activeExercise=identity.apparatusCandidate ?: activeExercise
                clearSetup();session.reset(activeExercise,side)
            }
        }
        val motionFresh=isFresh(seconds,input.motionTimestamp,motionMaximumAgeSeconds) && input.attitude!=null
        val framingReady=pose.imageSize.isValid && pose.people.size==1 && (if(selection==WorkoutExerciseSelection.automatic || activeExercise==ExerciseCounter.Exercise.pullUp) PullUpArmMeasurement(pose).arms.isNotEmpty() else ArmMeasurement(pose,side).estimate!=null)
        // Auto acquisition needs both current-frame chains to distinguish a hang
        // from support. One arm may still continue an already running set or a
        // resolved replay; this guidance must not tighten those counting gates.
        val automaticFramingIncomplete=session.phase==LiveSetSession.Phase.idle &&
            selection==WorkoutExerciseSelection.automatic && replayExercise==null &&
            PullUpArmMeasurement(pose).arms.size!=2
        var acquiredThisFrame=false
        if(session.phase==LiveSetSession.Phase.idle && confirmedBar==null && motionFresh && framingReady && !input.paused && (selection.exercise!=null || identity.apparatusCandidate!=null) && input.candidates.isNotEmpty()) {
            if(lastAcquisitionSeconds==null || (seconds-lastAcquisitionSeconds!!)>=AutomaticPullUpBar.samplingInterval) {
                lastAcquisitionSeconds=seconds
                val proposal=apparatus.observe(pose,activeExercise,side,input.candidates)
                if(proposal!=null && proposal.isValid && phoneOrientation.calibrate(input.attitude,input.motionTimestamp!!) && staticScene.calibrate(pose.imageSize)) {
                    confirmedBar=proposal;generation++;acquiredThisFrame=true
                }
            }
        }
        if(confirmedBar!=null) {
            if(!motionFresh) {
                handsFreeStart.reset()
                if(session.phase==LiveSetSession.Phase.running) {
                    session.interruptAndFinish("phoneOrientationUnavailable",LiveSetSession.EndReason.setupInvalidated)
                    captureCompletedSet()
                    clearSetup();issue="phoneOrientationUnavailable";return snapshot
                }
            } else if(phoneOrientation.observe(input.attitude,input.motionTimestamp!!)==PhoneOrientationStability.State.moved) {
                interrupt(LiveSetSession.EndReason.phoneMoved);issue="phoneMoved";return snapshot
            }
            if(!acquiredThisFrame && isFresh(seconds,input.sceneTimestamp,sceneMaximumAgeSeconds)) {
                if(staticScene.observe(input.translations,input.globalScaleFraction,input.sceneTimestamp!!)==StaticSceneStability.State.moved) {
                    val endReason=if(staticScene.movementKind==StaticSceneStability.MovementKind.scale) LiveSetSession.EndReason.sceneScaled else LiveSetSession.EndReason.sceneShifted
                    interrupt(endReason);issue=endReason.name;return snapshot
                }
            }
        }
        sourceTimeline.observe(seconds)
        val bar=confirmedBar
        val sceneFresh=isFresh(seconds,input.sceneTimestamp,sceneMaximumAgeSeconds)
        issue=when {
            !framingReady || automaticFramingIncomplete -> "framingIncomplete"
            resolvedExercise==null -> "exerciseIdentityNeeded"
            bar==null && apparatus.state==AutomaticPullUpBar.State.ambiguous ->
                if(activeExercise==ExerciseCounter.Exercise.dip) "dipRailsUnclear" else "pullUpBarUnclear"
            bar==null -> "barReferenceNeeded"
            !motionFresh || !phoneOrientation.state.allowsLiveSet -> "phoneStabilityNeeded"
            !sceneFresh || !staticScene.state.allowsLiveSet -> "sceneStabilityNeeded"
            input.paused -> "paused"
            else -> null
        }
        framingHint=framingFeedback.observe(pose, idle=session.phase==LiveSetSession.Phase.idle,
            barConfirmed=bar!=null, automatic=selection==WorkoutExerciseSelection.automatic,
            pullUp=activeExercise==ExerciseCounter.Exercise.pullUp, side=side)
        if(replayReview && session.phase==LiveSetSession.Phase.idle) {
            // A recording has no spoken start cue. Preserve the entire source
            // timeline once its actual background registration qualifies. Even
            // an unusable pose is consumed so coverage and baseline timing match.
            val cameraQualified=bar!=null && motionFresh && phoneOrientation.state.allowsLiveSet
                && sceneFresh && staticScene.state.allowsLiveSet
            if(cameraQualified && !input.paused) {
                handsFreeStart.reset();session.automaticFinishEnabled=false;session.start(activeExercise,side)
                discardNextSetFrame=false;session.consume(pose,bar?.referenceEdge)
            }
            return snapshot
        }
        if(session.phase==LiveSetSession.Phase.idle) {
            val eligible=issue==null && (activeExercise==ExerciseCounter.Exercise.dip || HandsFreeSetStart.isStartingPosition(pose,bar!!.referenceEdge))
            if(!eligible || input.paused || (qualifiedStartPose!=null && (seconds<=qualifiedStartPose!!.timestamp.seconds || seconds-qualifiedStartPose!!.timestamp.seconds>ExerciseCounter.maximumGapSeconds))) qualifiedStartPose=null
            val baseline=qualifiedStartPose
            if(eligible && qualifiedStartPose==null) qualifiedStartPose=pose
            val began=if(automaticWorkouts) automaticStart.observe(seconds,eligible && !input.paused) else if(activeExercise==ExerciseCounter.Exercise.dip) handsFreeStart.observeDip(pose,side,bar?.referenceEdge,eligible,input.paused) else handsFreeStart.observe(seconds,eligible,input.paused)
            if(began) {
                if(resolvedExercise!=null && (selection!=WorkoutExerciseSelection.automatic || replayExercise!=null || identity.lock())) {
                    session.automaticFinishEnabled=automaticWorkouts;session.start(activeExercise,side,nowEpochMilliseconds());discardNextSetFrame=!automaticWorkouts
                    if(automaticWorkouts){baseline?.let{session.consume(it,bar?.referenceEdge)};session.consume(pose,bar?.referenceEdge);qualifiedStartPose=null}
                } else handsFreeStart.reset()
                return snapshot
            }
        } else if(session.phase==LiveSetSession.Phase.running) {
            if(discardNextSetFrame){discardNextSetFrame=false;return snapshot}
            session.consume(pose,bar?.referenceEdge);captureCompletedSet()
        }
        return snapshot
    }
    /** A watchdog calls this when no analyzed frame arrives; timers cannot earn dwell. */
    fun tick(timestamp:Double) {
        if(automaticWorkouts && !replayReview && session.phase==LiveSetSession.Phase.running && lastFrameAt!=null && WorkoutLifecycle.observationOutage(timestamp-lastFrameAt!!)) interrupt(LiveSetSession.EndReason.trackingLost)
        if(handsFreeStart.state.phase==HandsFreeSetStart.Phase.countdown && (lastFrameAt==null || !timestamp.isFinite() || timestamp-lastFrameAt!!>HandsFreeSetStart.maximumFrameGap)) handsFreeStart.reset()
    }
    fun stop(){clearFramingFeedback();handsFreeStart.reset();discardNextSetFrame=false;session.finish();captureCompletedSet()}
    fun nextSet(){if(session.phase!=LiveSetSession.Phase.finished) return;clearFramingFeedback();handsFreeStart.reset();automaticStart.reset();qualifiedStartPose=null;discardNextSetFrame=false;replayReview=false;session.prepareNextSet();if(selection==WorkoutExerciseSelection.automatic){identity.reset();replayExercise=null;clearSetup()}}
    fun interrupt(reason:LiveSetSession.EndReason) {
        val counterReason=if(reason==LiveSetSession.EndReason.setupInvalidated) "barReferenceUnavailable" else reason.name
        session.interruptAndFinish(counterReason,reason);captureCompletedSet();handsFreeStart.reset();automaticStart.reset();qualifiedStartPose=null;discardNextSetFrame=false;clearSetup()
        if(session.phase==LiveSetSession.Phase.idle){identity.reset();replayExercise=null}
    }
    /** Explicit manual apparatus fallback still must have valid pixels and camera calibration. */
    fun confirmManualBar(bar:ConfirmedBar,attitude:PhoneOrientationStability.Quaternion?,motionTimestamp:Double):Boolean {
        if(session.phase==LiveSetSession.Phase.running || !bar.isValid || resolvedExercise==null || bar.role!=expectedRole() || !phoneOrientation.calibrate(attitude,motionTimestamp) || !staticScene.calibrate(bar.imageSize)) return false
        clearAcquisition();clearFramingFeedback();confirmedBar=bar;generation++;return true
    }
    /** Recorded review rewinds after acquisition, retaining only the observed apparatus identity.
     * Review counts directly from the first frame with qualifying image registration,
     * without the live countdown or queued-frame skip. Replay attitude is explicitly
     * virtual; registration still comes from the actual frames.
     */
    fun rewindReplay(attitude:PhoneOrientationStability.Quaternion,motionTimestamp:Double):Boolean {
        val bar=confirmedBar ?: return false;val exercise=resolvedExercise ?: return false
        if(!phoneOrientation.calibrate(attitude,motionTimestamp) || !staticScene.calibrate(bar.imageSize)) return false
        replayExercise=exercise;replayReview=true;activeExercise=exercise;identity.reset();handsFreeStart.reset();session.reset(exercise,side);sourceTimeline.reset();clearFramingFeedback();lastAcquisitionSeconds=null;discardNextSetFrame=false;generation++;return true
    }
    private fun expectedRole()=if(activeExercise==ExerciseCounter.Exercise.pullUp) ConfirmedBar.Role.pullUpGrip else if(side==ArmMeasurement.Side.left) ConfirmedBar.Role.leftDipRail else ConfirmedBar.Role.rightDipRail
    private fun clearAcquisition(){apparatus.reset();lastAcquisitionSeconds=null;handsFreeStart.reset();automaticStart.reset()}
    private fun clearFramingFeedback(){framingHint=null;framingFeedback.reset()}
    private fun clearSetup(){clearAcquisition();confirmedBar=null;phoneOrientation.reset();staticScene.reset();generation++;issue="barReferenceNeeded";clearFramingFeedback()}
    companion object {
        const val motionMaximumAgeSeconds=.5
        const val sceneMaximumAgeSeconds=.8
        private fun isFresh(frame:Double,sample:Double?,maximumAge:Double)=frame.isFinite() && sample!=null && sample.isFinite() && sample<=frame+.05 && frame-sample<=maximumAge
    }
}

/** Shared actionable setup guidance for live overlays and recorded-review outcomes. */
object WorkoutSetupGuidance {
    fun message(issue:String):String?=when(issue) {
        "multiplePeople" -> "Show one person"
        "framingIncomplete" -> "Show both arms and hands clearly"
        "exerciseIdentityNeeded" -> "Hold a clear hang or top support with both arms and hands visible"
        "barReferenceNeeded", "barReferenceUnavailable" -> "Show the gripping bar or rails beside your hands"
        "dipRailsUnclear" -> "Show the bar at both hands, or two clearly separated rails"
        "pullUpBarUnclear" -> "Change the camera angle so the gripping bar is clearly visible"
        "phoneMoved" -> "Phone moved · set it down and start again"
        "sceneShifted", "sceneScaled" -> "Camera view changed · start again"
        "phoneUnavailable", "motionUnavailable" -> "Waiting for phone motion sensors"
        "phoneStabilityNeeded" -> "Keep the camera still while stability is checked"
        "sceneStabilityNeeded", "sceneUnavailable" -> "Keep the camera still with a stable background visible"
        "paused" -> "Close settings to continue setup"
        else -> null
    }
}
