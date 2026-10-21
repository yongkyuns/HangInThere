package dev.hanginthere.core

import kotlinx.serialization.Serializable

@Serializable enum class WorkoutExerciseSelection {
    automatic,pullUp,dip;
    val exercise:ExerciseCounter.Exercise? get()=when(this){automatic->null;pullUp->ExerciseCounter.Exercise.pullUp;dip->ExerciseCounter.Exercise.dip}
}
/** Apparatus posture identifies a dip independently of starting extension.
 * Identity never substitutes for observed apparatus or current start evidence. */
class AutomaticExerciseDetection {
    var exercise:ExerciseCounter.Exercise?=null;private set
    var apparatusCandidate:ExerciseCounter.Exercise?=null;private set
    var isLocked=false;private set
    private var witnessSides=emptyList<ArmMeasurement.Side>()
    private var firstTime:PresentationTime?=null;private var lastTime:PresentationTime?=null;private var imageSize:ImageSize?=null;private var observations=0
    fun reset(){witnessSides=emptyList();exercise=null;apparatusCandidate=null;isLocked=false;firstTime=null;lastTime=null;imageSize=null;observations=0}
    fun observe(pose:PoseResult):ExerciseCounter.Exercise? {
        if(isLocked) return exercise
        val seconds=pose.timestamp.seconds;val next=searchCandidate(pose)
        if(!seconds.isFinite() || next==null){reset();return null}
        val gap=lastTime?.let{pose.timestamp.elapsedSeconds(it)}
        if(gap!=null && (!gap.isFinite() || gap<=0 || gap>HandsFreeSetStart.maximumFrameGap)){reset();return null}
        val sides=PullUpArmMeasurement(pose).arms.map{it.side}
        if(apparatusCandidate!=next || imageSize!=pose.imageSize || witnessSides!=sides){reset();apparatusCandidate=next;imageSize=pose.imageSize;witnessSides=sides}
        lastTime=pose.timestamp
        // A qualified hang identifies the exercise, not a pose to freeze. Keep
        // that identity through flexion only while fresh grip evidence
        // still identifies the same apparatus. Set start separately needs a hang.
        if(exercise==next) return exercise
        // Dip support can be identified during flexion. Requiring extended
        // elbows for this entire dwell incorrectly turns identity into a second,
        // longer start hold. WorkoutStartEvidence still requires a fresh top.
        val identityPose = if(next==ExerciseCounter.Exercise.dip) apparatusCandidate(pose) else candidate(pose)
        if(identityPose!=next){exercise=null;firstTime=null;observations=0;return null}
        if(firstTime==null) firstTime=pose.timestamp
        observations++
        // Reuse source-tick subtraction for exact dwell/gap boundaries; no tolerance is added.
        if(observations>=4 && pose.timestamp.elapsedSeconds(firstTime ?: pose.timestamp)>=minimumStableSeconds) exercise=next
        return exercise
    }
    /** A pure current-frame search query cannot earn identity or source dwell.
     * After a dip support context was actually seen, fresh observed contact
     * below both shoulders can preserve that apparatus search through a deep
     * bend. It cannot discover an exercise or qualify a new starting pose. */
    fun searchCandidate(pose:PoseResult):ExerciseCounter.Exercise? {
        apparatusCandidate(pose)?.let { return it }
        if(apparatusCandidate!=ExerciseCounter.Exercise.dip || imageSize!=pose.imageSize) return null
        val gap=lastTime?.let { pose.timestamp.elapsedSeconds(it) } ?: return null
        if(!gap.isFinite() || gap<0 || gap>HandsFreeSetStart.maximumFrameGap ||
            AutomaticApparatusReference.search(pose,ExerciseCounter.Exercise.dip)==null) return null
        val arms=PullUpArmMeasurement(pose).arms
        if(arms.map{it.side}!=witnessSides) return null
        return ExerciseCounter.Exercise.dip.takeIf { arms.isNotEmpty() && arms.all { it.wrist.y>it.shoulder.y } }
    }
    fun lock():Boolean {if(exercise==null) return false;isLocked=true;return true}
    companion object {
        const val minimumStableSeconds=.6
        fun candidate(pose:PoseResult):ExerciseCounter.Exercise? {
            val candidate=apparatusCandidate(pose) ?: return null
            val starting=when(candidate) {
                ExerciseCounter.Exercise.pullUp -> PullUpArmMeasurement(pose).isExtended
                ExerciseCounter.Exercise.dip -> AutomaticDipRails.isStartingPosition(pose) || AutomaticSingleDipRail.contact(pose)?.isStartingPosition==true
            }
            return candidate.takeIf{starting}
        }
        fun apparatusCandidate(pose:PoseResult):ExerciseCounter.Exercise? {val pullUp=AutomaticApparatusReference.search(pose,ExerciseCounter.Exercise.pullUp)!=null;val dip=AutomaticDipRails.grip(pose)!=null || AutomaticSingleDipRail.contact(pose)?.isSupportLike==true;return if(pullUp==dip) null else if(pullUp) ExerciseCounter.Exercise.pullUp else ExerciseCounter.Exercise.dip}
    }
}
