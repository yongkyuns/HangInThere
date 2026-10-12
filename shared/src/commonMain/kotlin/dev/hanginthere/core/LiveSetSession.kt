package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.random.Random

/** One concrete exercise per set; external interruptions close the actual counter. */
class LiveSetSession {
    @Serializable enum class Phase { idle,running,finished }
    @Serializable enum class EndReason { manual,appInactive,cameraInterrupted,cameraFailure,setupInvalidated,phoneMoved,sceneShifted,sceneScaled,disengaged,trackingLost;val isInterruption get()=this!=manual && this!=disengaged }
    var setId=newSetId();private set
    var analysis=WorkoutAnalysis(setId,ExerciseCounter.Exercise.pullUp);private set
    private var boundary=AutomaticSetBoundary()
    var automaticFinishEnabled=false
    private var backend:String?=null
    private var modelRevision:Int?=null
    private var imageSize:ImageSize?=null
    private var reference:BarSegment?=null
    private var trackedAtEpochMilliseconds:Long?=null
    val record get()=WorkoutSetRecord(setId,exercise,firstSourceSeconds,lastSourceSeconds,endReason?.name ?: "running",analysis.records,backend,modelRevision,trackingSide=side,imageSize=imageSize,referenceEdge=reference,trackedAtEpochMilliseconds=trackedAtEpochMilliseconds)
    val draftDelta get()=(analysis.activeRecord ?: analysis.records.lastOrNull())?.let{record.copy(reps=listOf(it))}
    var phase=Phase.idle;private set
    var endReason:EndReason?=null;private set
    var counter=ExerciseCounter();private set
    var analyzedFrames=0;private set
    var usableFrames=0;private set
    var firstSourceSeconds:Double?=null;private set
    var lastSourceSeconds:Double?=null;private set
    val exercise get()=counter.exercise
    val side get()=counter.side
    val observedMovements get()=counter.observedMovements
    val partialAttempts get()=counter.partialAttempts
    val interruptedAttempts get()=counter.interruptedAttempts
    val trackingIssue get()=counter.trackingIssue
    val durationSeconds get()=if(firstSourceSeconds!=null && lastSourceSeconds!=null) max(0.0,lastSourceSeconds!!-firstSourceSeconds!!) else 0.0
    val trackingCoverage get()=if(analyzedFrames>0) usableFrames.toDouble()/analyzedFrames else null
    val movementTimes get()=firstSourceSeconds?.let{first->counter.events.filter{it.outcome==ExerciseCounter.Outcome.movement}.map{max(0.0,it.sourceSeconds-first)}} ?: emptyList()
    fun start(exercise:ExerciseCounter.Exercise,side:ArmMeasurement.Side,trackedAtEpochMilliseconds:Long?=null) {reset(exercise,side);this.trackedAtEpochMilliseconds=trackedAtEpochMilliseconds;phase=Phase.running}
    fun consume(pose:PoseResult,referenceEdge:BarSegment?) {
        if(phase!=Phase.running) return
        val time=pose.timestamp.seconds
        if(time.isFinite()){if(firstSourceSeconds==null) firstSourceSeconds=time;lastSourceSeconds=time}
        if(imageSize==null) imageSize=pose.imageSize
        if(reference==null) reference=referenceEdge
        backend=pose.backend;modelRevision=pose.requestRevision
        val eventCount=counter.events.size
        analyzedFrames++;counter.consume(pose,referenceEdge)
        val features=MotionFeatureExtractor.extract(pose,referenceEdge,counter.trackingIssue==null && referenceEdge!=null)
        analysis.observe(features,counter.phase,counter.events.drop(eventCount).lastOrNull())
        if(automaticFinishEnabled) boundary.observe(features,counter.observedMovements+counter.partialAttempts+counter.interruptedAttempts>0 || counter.phase in listOf(ExerciseCounter.Phase.ready,ExerciseCounter.Phase.outbound,ExerciseCounter.Phase.returning))?.let { finish(EndReason.valueOf(it)) }
        if(referenceEdge!=null && counter.trackingIssue==null) usableFrames++
    }
    fun interrupt(reason:String){if(phase==Phase.running) {counter.interrupt(reason);analysis.interrupt(reason)}}
    fun finish(reason:EndReason=EndReason.manual){if(phase!=Phase.running) return;counter.finish();analysis.finish(reason.name);endReason=reason;phase=Phase.finished}
    fun interruptAndFinish(reason:String,endReason:EndReason){if(phase!=Phase.running) return;counter.interrupt(reason);counter.finish();analysis.finish(reason);this.endReason=endReason;phase=Phase.finished}
    fun prepareNextSet(){if(phase==Phase.finished) reset(counter.exercise,counter.side)}
    fun reset(exercise:ExerciseCounter.Exercise,side:ArmMeasurement.Side){setId=newSetId();analysis=WorkoutAnalysis(setId,exercise);boundary.reset();imageSize=null;reference=null;backend=null;modelRevision=null;trackedAtEpochMilliseconds=null;counter=ExerciseCounter(exercise,side);phase=Phase.idle;endReason=null;analyzedFrames=0;usableFrames=0;firstSourceSeconds=null;lastSourceSeconds=null}
    companion object { fun newSetId()=Random.nextLong().toULong().toString(16).padStart(16,'0')+Random.nextLong().toULong().toString(16).padStart(16,'0') }
}
