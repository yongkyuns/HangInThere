package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlin.math.*

/** Rotation evidence; translation and scale require independent image registration. */
class PhoneOrientationStability {
    @ConsistentCopyVisibility
    @Serializable data class Quaternion private constructor(val x:Double,val y:Double,val z:Double,val w:Double) {
        fun angularDistanceDegrees(other:Quaternion):Double=2*acos(abs(x*other.x+y*other.y+z*other.z+w*other.w).coerceIn(0.0,1.0))*180/PI
        companion object {
            fun create(x:Double,y:Double,z:Double,w:Double):Quaternion? {if(!listOf(x,y,z,w).all{it.isFinite()}) return null;val norm=sqrt(x*x+y*y+z*z+w*w);return if(norm.isFinite() && norm>1e-9) Quaternion(x/norm,y/norm,z/norm,w/norm) else null}
        }
    }
    @Serializable enum class State { unavailable,uncalibrated,stable,moved;val allowsLiveSet get()=this==stable }
    var state=State.uncalibrated;private set
    var latestDeltaDegrees:Double?=null;private set
    var maximumDeltaDegrees=0.0;private set
    private var baseline:Quaternion?=null;private var overThresholdSince:Double?=null;private var lastTimestamp:Double?=null
    fun reset(){state=State.uncalibrated;latestDeltaDegrees=null;maximumDeltaDegrees=0.0;baseline=null;overThresholdSince=null;lastTimestamp=null}
    fun markUnavailable(){reset();state=State.unavailable}
    fun calibrate(attitude:Quaternion?,timestamp:Double):Boolean {if(attitude==null || !timestamp.isFinite()){reset();return false};baseline=attitude;lastTimestamp=timestamp;overThresholdSince=null;latestDeltaDegrees=0.0;maximumDeltaDegrees=0.0;state=State.stable;return true}
    fun observe(attitude:Quaternion?,timestamp:Double):State {
        val base=baseline
        if(state!=State.stable || base==null || attitude==null || !timestamp.isFinite() || lastTimestamp?.let{timestamp<=it}==true) return state
        lastTimestamp=timestamp;val delta=base.angularDistanceDegrees(attitude)
        if(!delta.isFinite()) return state
        latestDeltaDegrees=delta;maximumDeltaDegrees=max(maximumDeltaDegrees,delta)
        if(delta>=movementThresholdDegrees){val since=overThresholdSince;if(since!=null){if(timestamp-since>=movementDwellSeconds) state=State.moved} else overThresholdSince=timestamp} else overThresholdSince=null
        return state
    }
    companion object {const val movementThresholdDegrees=1.5;const val movementDwellSeconds=.25}
}
