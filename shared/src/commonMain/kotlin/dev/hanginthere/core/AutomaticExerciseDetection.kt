package dev.hanginthere.core

import kotlinx.serialization.Serializable

@Serializable enum class WorkoutExerciseSelection {
    automatic,pullUp,dip;
    val exercise:ExerciseCounter.Exercise? get()=when(this){automatic->null;pullUp->ExerciseCounter.Exercise.pullUp;dip->ExerciseCounter.Exercise.dip}
}
/** Starting pose identity never substitutes for separately observed apparatus. */
class AutomaticExerciseDetection {
    var exercise:ExerciseCounter.Exercise?=null;private set
    var apparatusCandidate:ExerciseCounter.Exercise?=null;private set
    var isLocked=false;private set
    private var firstSeconds:Double?=null;private var lastSeconds:Double?=null;private var imageSize:ImageSize?=null;private var observations=0
    fun reset(){exercise=null;apparatusCandidate=null;isLocked=false;firstSeconds=null;lastSeconds=null;imageSize=null;observations=0}
    fun observe(pose:PoseResult):ExerciseCounter.Exercise? {
        if(isLocked) return exercise
        val seconds=pose.timestamp.seconds;val next=apparatusCandidate(pose)
        if(!seconds.isFinite() || next==null){reset();return null}
        if(lastSeconds?.let{seconds<=it || seconds-it>HandsFreeSetStart.maximumFrameGap}==true){reset();return null}
        if(apparatusCandidate!=next || imageSize!=pose.imageSize){reset();apparatusCandidate=next;imageSize=pose.imageSize}
        lastSeconds=seconds
        if(candidate(pose)!=next){exercise=null;firstSeconds=null;observations=0;return null}
        if(firstSeconds==null) firstSeconds=seconds
        observations++
        if(observations>=4 && seconds-(firstSeconds ?: seconds)>=minimumStableSeconds) exercise=next
        return exercise
    }
    fun lock():Boolean {if(exercise==null) return false;isLocked=true;return true}
    companion object {
        const val minimumStableSeconds=.6
        fun candidate(pose:PoseResult):ExerciseCounter.Exercise? {val candidate=apparatusCandidate(pose) ?: return null;return if(candidate==ExerciseCounter.Exercise.pullUp && !PullUpArmMeasurement(pose).isExtended) null else candidate}
        fun apparatusCandidate(pose:PoseResult):ExerciseCounter.Exercise? {val pullUp=AutomaticApparatusReference.search(pose,ExerciseCounter.Exercise.pullUp)!=null;val dip=AutomaticApparatusReference.search(pose,ExerciseCounter.Exercise.dip)!=null;return if(pullUp==dip) null else if(pullUp) ExerciseCounter.Exercise.pullUp else ExerciseCounter.Exercise.dip}
    }
}
