package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlin.math.ceil

/** Source-clock dwell: only consecutive eligible analyzed frames earn countdown time. */
class HandsFreeSetStart {
    @Serializable enum class Phase { waiting,countdown,started }
    @Serializable data class State(val phase:Phase=Phase.waiting,val seconds:Int?=null)
    var state=State();private set
    private var beganAt:Double?=null;private var lastFrameAt:Double?=null;private var dipAnchor:DipSupportMeasurement.Anchor?=null
    fun reset(){state=State();beganAt=null;lastFrameAt=null;dipAnchor=null}
    fun observeDip(pose:PoseResult,side:ArmMeasurement.Side,referenceEdge:BarSegment?,eligible:Boolean,paused:Boolean=false):Boolean {
        if(paused){reset();return false}
        if(state.phase==Phase.started) return false
        val sample=referenceEdge?.let{DipSupportMeasurement.create(pose,side,it)}
        if(!eligible || sample==null || !sample.isStartingSupport){reset();return false}
        if(lastFrameAt?.let{pose.timestamp.seconds-it>maximumFrameGap}==true) reset()
        if(dipAnchor?.let{!it.isStableStart(sample)}==true){reset();return false}
        if(dipAnchor==null) dipAnchor=DipSupportMeasurement.Anchor.create(sample)
        if(dipAnchor==null){reset();return false}
        return observe(pose.timestamp.seconds,true,paused)
    }
    fun observe(timestamp:Double,eligible:Boolean,paused:Boolean=false):Boolean {
        if(paused){reset();return false}
        if(state.phase==Phase.started) return false
        if(!eligible || !timestamp.isFinite()){reset();return false}
        lastFrameAt?.let{if(timestamp<=it){reset();return false};if(timestamp-it>maximumFrameGap) reset()}
        lastFrameAt=timestamp
        if(beganAt==null) beganAt=timestamp
        val remaining=countdownSeconds-(timestamp-(beganAt ?: timestamp))
        if(remaining<=0){state=State(Phase.started);return true}
        state=State(Phase.countdown,ceil(remaining).toInt());return false
    }
    companion object {
        const val countdownSeconds=3.0
        const val maximumFrameGap=ExerciseCounter.maximumGapSeconds
        fun isStartingPosition(pose:PoseResult,referenceEdge:BarSegment):Boolean {val measurement=PullUpArmMeasurement(pose);return measurement.isExtended && measurement.hasGrip(referenceEdge,pose.imageSize)}
    }
}
