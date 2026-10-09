package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlin.math.max

/** One concrete exercise per set; external interruptions close the actual counter. */
class LiveSetSession {
    @Serializable enum class Phase { idle,running,finished }
    @Serializable enum class EndReason { manual,appInactive,cameraInterrupted,cameraFailure,setupInvalidated,phoneMoved,sceneShifted,sceneScaled;val isInterruption get()=this!=manual }
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
    fun start(exercise:ExerciseCounter.Exercise,side:ArmMeasurement.Side) {reset(exercise,side);phase=Phase.running}
    fun consume(pose:PoseResult,referenceEdge:BarSegment?) {
        if(phase!=Phase.running) return
        val time=pose.timestamp.seconds
        if(time.isFinite()){if(firstSourceSeconds==null) firstSourceSeconds=time;lastSourceSeconds=time}
        analyzedFrames++;counter.consume(pose,referenceEdge)
        if(referenceEdge!=null && counter.trackingIssue==null) usableFrames++
    }
    fun interrupt(reason:String){if(phase==Phase.running) counter.interrupt(reason)}
    fun finish(reason:EndReason=EndReason.manual){if(phase!=Phase.running) return;counter.finish();endReason=reason;phase=Phase.finished}
    fun interruptAndFinish(reason:String,endReason:EndReason){if(phase!=Phase.running) return;counter.interrupt(reason);counter.finish();this.endReason=endReason;phase=Phase.finished}
    fun prepareNextSet(){if(phase==Phase.finished) reset(counter.exercise,counter.side)}
    fun reset(exercise:ExerciseCounter.Exercise,side:ArmMeasurement.Side){counter=ExerciseCounter(exercise,side);phase=Phase.idle;endReason=null;analyzedFrames=0;usableFrames=0;firstSourceSeconds=null;lastSourceSeconds=null}
}
