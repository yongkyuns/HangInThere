package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlin.math.*

/** Median corner translation consensus plus independent full-image scale evidence. */
class StaticSceneStability {
    @Serializable data class PatchTranslation(val dxPixels:Double,val dyPixels:Double){val isFinite get()=dxPixels.isFinite() && dyPixels.isFinite()}
    @Serializable enum class State { uncalibrated,calibrating,stable,moved;val allowsLiveSet get()=this==stable }
    @Serializable enum class MovementKind { translation,scale }
    var state=State.uncalibrated;private set
    var movementKind:MovementKind?=null;private set
    var latestShiftFraction:Double?=null;private set
    var latestScaleFraction:Double?=null;private set
    var latestConsensusPatches=0;private set
    var latestScaleMeasurementAvailable=false;private set
    var maximumShiftFraction=0.0;private set
    var maximumScaleFraction=0.0;private set
    private var imageShortSide:Double?=null;private var translationOverThresholdSince:Double?=null;private var scaleOverThresholdSince:Double?=null;private var lastTimestamp:Double?=null
    fun reset(){state=State.uncalibrated;movementKind=null;latestShiftFraction=null;latestScaleFraction=null;latestConsensusPatches=0;latestScaleMeasurementAvailable=false;maximumShiftFraction=0.0;maximumScaleFraction=0.0;imageShortSide=null;translationOverThresholdSince=null;scaleOverThresholdSince=null;lastTimestamp=null}
    fun calibrate(size:ImageSize):Boolean {if(!size.isValid){reset();return false};reset();imageShortSide=min(size.width,size.height);state=State.calibrating;return true}
    fun calibrate(imageShortSide:Double)=calibrate(ImageSize(imageShortSide,imageShortSide))
    /** Source-time freshness, including the existing scene cadence/future tolerance. */
    fun isReady(timestamp:Double?):Boolean {
        val sample=lastTimestamp ?: return false
        return state.allowsLiveSet && timestamp!=null && timestamp.isFinite() &&
            sample<=timestamp+maximumFutureSkewSeconds && timestamp-sample<=maximumObservationGapSeconds
    }
    fun qualifiedReference(reference:BarSegment?,timestamp:Double):BarSegment? = reference.takeIf { isReady(timestamp) }
    fun observe(translations:List<PatchTranslation>,globalScaleFraction:Double?,timestamp:Double):State {
        val short=imageShortSide
        if((state!=State.calibrating && state!=State.stable) || short==null) return state
        if(!timestamp.isFinite() || lastTimestamp?.let{timestamp<=it}==true) {
            invalidateObservation();return state
        }
        if(lastTimestamp?.let { timestamp-it>maximumObservationGapSeconds }==true) invalidateObservation()
        lastTimestamp=timestamp
        val translation=translationConsensus(translations.filter{it.isFinite},short)
        latestConsensusPatches=translation.second
        latestShiftFraction=translation.first
        translation.first?.let{maximumShiftFraction=max(maximumShiftFraction,it)}
        val scale=globalScaleFraction?.takeIf{it.isFinite() && it>=0}
        latestScaleMeasurementAvailable=scale!=null
        latestScaleFraction=scale
        scale?.let{maximumScaleFraction=max(maximumScaleFraction,it)}
        if(translation.first==null || translation.second<minimumConsensusPatches || scale==null) state=State.calibrating
        if(state==State.calibrating && translation.second>=minimumConsensusPatches && (translation.first ?: Double.POSITIVE_INFINITY)<movementThresholdFraction && scale!=null && scale<scaleThresholdFraction) state=State.stable
        if(translation.second>=minimumConsensusPatches && translation.first!=null && translation.first!!>=movementThresholdFraction){val since=translationOverThresholdSince;if(since!=null){if(timestamp-since>=movementDwellSeconds){movementKind=MovementKind.translation;state=State.moved}} else translationOverThresholdSince=timestamp} else translationOverThresholdSince=null
        if(state!=State.moved){if(scale!=null && scale>=scaleThresholdFraction){val since=scaleOverThresholdSince;if(since!=null){if(timestamp-since>=movementDwellSeconds){movementKind=MovementKind.scale;state=State.moved}} else scaleOverThresholdSince=timestamp} else scaleOverThresholdSince=null}
        return state
    }
    private fun invalidateObservation() {
        state=State.calibrating;latestShiftFraction=null;latestScaleFraction=null
        latestConsensusPatches=0;latestScaleMeasurementAvailable=false
        translationOverThresholdSince=null;scaleOverThresholdSince=null
    }
    private fun translationConsensus(translations:List<PatchTranslation>,short:Double):Pair<Double?,Int> {
        if(translations.size<minimumConsensusPatches) return null to translations.size
        val mx=median(translations.map{it.dxPixels});val my=median(translations.map{it.dyPixels});val tolerance=consensusToleranceFraction*short
        val inliers=translations.filter{hypot(it.dxPixels-mx,it.dyPixels-my)<=tolerance}
        if(inliers.size<minimumConsensusPatches) return null to inliers.size
        val fraction=hypot(median(inliers.map{it.dxPixels}),median(inliers.map{it.dyPixels}))/short
        return fraction.takeIf{it.isFinite()} to inliers.size
    }
    private fun median(values:List<Double>):Double {val sorted=values.sorted();val middle=sorted.size/2;return if(sorted.size%2==0)(sorted[middle-1]+sorted[middle])/2 else sorted[middle]}
    companion object {const val movementThresholdFraction=.008;const val consensusToleranceFraction=.006;const val scaleThresholdFraction=.012;const val movementDwellSeconds=.25;const val minimumConsensusPatches=2
        // The established 0.8-second scene age permits the native 0.33-second
        // registration cadence. Body-frame continuity has its own tighter bound.
        const val maximumObservationGapSeconds=.8
        const val maximumFutureSkewSeconds=.05
    }
}
