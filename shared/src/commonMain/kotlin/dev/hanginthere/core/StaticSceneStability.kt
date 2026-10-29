package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlin.math.*

/** Coherent corner translation consensus plus independent full-image scale evidence. */
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
        val translation=translationConsensus(translations,short)
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
        // Every maximum hypothesis must agree. Two shared patches remain
        // required for moving hypotheses. Stationary-only alternatives can
        // instead prove that every contributing original patch is itself
        // below the unchanged movement limit; their centers must still agree.
        if(translations.size>maximumRegistrationPatches) return null to 0
        val finite=translations.filter{it.isFinite}
        if(finite.size<minimumConsensusPatches) return null to finite.size
        val tolerance=consensusToleranceFraction*short
        var bestSize=0
        val best=mutableListOf<TranslationHypothesis>()
        for(mask in 1 until (1 shl finite.size)) {
            val count=mask.countOneBits()
            if(count<minimumConsensusPatches || count<bestSize) continue
            val subset=finite.filterIndexed { index,_ -> mask and (1 shl index)!=0 }
            val x=median(subset.map{it.dxPixels});val y=median(subset.map{it.dyPixels})
            if(subset.any{hypot(it.dxPixels-x,it.dyPixels-y)>tolerance}) continue
            if(count>bestSize){bestSize=count;best.clear()}
            best+=TranslationHypothesis(x,y,mask)
        }
        if(best.isEmpty()) return null to 0
        val stationaryWitnesses=best.all { h ->
            hypot(h.x,h.y)/short<movementThresholdFraction && finite.indices.all { i ->
                h.mask and (1 shl i)==0 || hypot(finite[i].dxPixels,finite[i].dyPixels)/short<movementThresholdFraction
            }
        }
        for(i in best.indices) for(j in i+1 until best.size) {
            val a=best[i];val b=best[j]
            if(!stationaryWitnesses && (a.mask and b.mask).countOneBits()<minimumConsensusPatches ||
                hypot(a.x-b.x,a.y-b.y)>tolerance) return null to 0
        }
        val fractions=best.map{hypot(it.x,it.y)/short}
        if(fractions.any{!it.isFinite()} || fractions.any{
                (it>=movementThresholdFraction)!=(fractions.first()>=movementThresholdFraction)}) return null to 0
        // Preserve the most conservative witnessed displacement. Never average
        // competing centers into a synthetic stationary estimate.
        return fractions.maxOrNull() to bestSize
    }
    private data class TranslationHypothesis(val x:Double,val y:Double,val mask:Int)
    private fun median(values:List<Double>):Double {val sorted=values.sorted();val middle=sorted.size/2;return if(sorted.size%2==0)sorted[middle-1]/2+sorted[middle]/2 else sorted[middle]}
    companion object {const val policyVersion=3;const val movementThresholdFraction=.008;const val consensusToleranceFraction=.006;const val scaleThresholdFraction=.012;const val movementDwellSeconds=.25;const val minimumConsensusPatches=2
        const val maximumRegistrationPatches=6
        // The established 0.8-second scene age permits the native 0.33-second
        // registration cadence. Body-frame continuity has its own tighter bound.
        const val maximumObservationGapSeconds=.8
        const val maximumFutureSkewSeconds=.05
    }
}
