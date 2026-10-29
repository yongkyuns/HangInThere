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
    private var witnessedHangHands=emptyList<Point2D>()
    private var identitySupportReference:BarSegment?=null
    private var backend:String?=null;private var revision:Int?=null
    private var firstTime:PresentationTime?=null;private var lastTime:PresentationTime?=null;private var imageSize:ImageSize?=null;private var observations=0
    fun reset(){witnessSides=emptyList();witnessedHangHands=emptyList();identitySupportReference=null;backend=null;revision=null;exercise=null;apparatusCandidate=null;isLocked=false;firstTime=null;lastTime=null;imageSize=null;observations=0}
    fun observe(pose:PoseResult,reference:BarSegment?=null):ExerciseCounter.Exercise? {
        if(isLocked) return exercise
        val seconds=pose.timestamp.seconds;val next=searchCandidate(pose,reference)
        if(!seconds.isFinite() || next==null){reset();return null}
        val gap=lastTime?.let{pose.timestamp.elapsedSeconds(it)}
        if(gap!=null && (!gap.isFinite() || gap<=0 || gap>HandsFreeSetStart.maximumFrameGap)){reset();return null}
        val sides=PullUpArmMeasurement(pose).arms.map{it.side}
        if(apparatusCandidate!=next || imageSize!=pose.imageSize || witnessSides!=sides || backend!=pose.backend || revision!=pose.requestRevision){reset();apparatusCandidate=next;imageSize=pose.imageSize;witnessSides=sides;backend=pose.backend;revision=pose.requestRevision}
        lastTime=pose.timestamp
        // A qualified hang identifies the exercise, not a pose to freeze. Keep
        // that identity through flexion only while fresh grip evidence
        // still identifies the same apparatus. Set start separately needs a hang.
        if(exercise==next) return exercise
        // A confirmed observed bar plus current overhead arms identifies a
        // pull-up independently of the brief extended pose needed to start.
        // Without that reference, keep the original extended-hang discovery.
        val support=if(next==ExerciseCounter.Exercise.pullUp) observedPullUpIdentityReference(pose,reference) else null
        val identityPose = if(support!=null) ExerciseCounter.Exercise.pullUp else if(next==ExerciseCounter.Exercise.dip) apparatusCandidate(pose) else candidate(pose)
        if(identityPose!=next){exercise=null;firstTime=null;observations=0;identitySupportReference=null;return null}
        if(identitySupportReference!=support){
            // Acquiring a bar must not erase already valid extended-hang
            // evidence. A previously used support changing/disappearing does.
            if(identitySupportReference!=null){firstTime=null;observations=0}
            identitySupportReference=support
        }
        if(firstTime==null) firstTime=pose.timestamp
        observations++
        // Reuse source-tick subtraction for exact dwell/gap boundaries; no tolerance is added.
        if(observations>=4 && pose.timestamp.elapsedSeconds(firstTime ?: pose.timestamp)>=minimumStableSeconds) {
            exercise=next
            if(next==ExerciseCounter.Exercise.pullUp) witnessedHangHands=PullUpArmMeasurement(pose).arms.map{it.wrist}
        }
        return exercise
    }
    private fun observedPullUpIdentityReference(pose:PoseResult,reference:BarSegment?,minimumScore:Double=.5):BarSegment? {
        val edge=reference?.ordered ?: return null
        if(!edge.isValid || !pose.imageSize.isValid || listOf(edge.a,edge.b).any {
            it.x !in 0.0..pose.imageSize.width || it.y !in 0.0..pose.imageSize.height
        }) return null
        // Current confirmed-bar association can persist through the first pull,
        // even before identity has earned its dwell. Shoulder rise changes the
        // search crop, not the lifetime of the actually observed bar. This does
        // not establish movement/start by itself; the owner checks those.
        val measurement=PullUpArmMeasurement(pose)
        val person=pose.people.singleOrNull() ?: return null
        val short=kotlin.math.min(pose.imageSize.width,pose.imageSize.height)
        val hands=listOf(PoseJoint.leftWrist,PoseJoint.rightWrist,PoseJoint.leftShoulder,PoseJoint.rightShoulder)
            .map { person.landmark(it,minimumScore)?.position ?: return null }
        if(!PullUpArmMeasurement.hasDistinctFrontRearArms(pose)) return null
        return edge.takeIf { measurement.arms.size==2 && measurement.arms.all { arm ->
            val signed=((edge.b.x-edge.a.x)*(arm.wrist.y-edge.a.y)-(edge.b.y-edge.a.y)*(arm.wrist.x-edge.a.x))/edge.length
            edge.distance(arm.wrist)<=short*.085 && signed>=-short*.015 && arm.shoulder.y-arm.wrist.y>=-short*.02
        } }
    }
    /** A pure current-frame search query cannot earn identity or source dwell.
     * After a dip support context was actually seen, fresh observed contact
     * below both shoulders can preserve that apparatus search through a deep
     * bend. It cannot discover an exercise or qualify a new starting pose. */
    fun searchCandidate(pose:PoseResult,reference:BarSegment?=null):ExerciseCounter.Exercise? {
        // Lower-score current geometry preserves an observed reference only;
        // the independent 0.5-score identity dwell above still cannot earn time.
        if(observedPullUpIdentityReference(pose,reference,ArmMeasurement.confidenceThreshold)!=null) return ExerciseCounter.Exercise.pullUp
        if(retainsWitnessedPullUp(pose,reference)) return ExerciseCounter.Exercise.pullUp
        apparatusCandidate(pose)?.let { return it }
        if(apparatusCandidate!=ExerciseCounter.Exercise.dip || imageSize!=pose.imageSize) return null
        val gap=lastTime?.let { pose.timestamp.elapsedSeconds(it) } ?: return null
        if(!gap.isFinite() || gap<0 || gap>HandsFreeSetStart.maximumFrameGap ||
            AutomaticApparatusReference.search(pose,ExerciseCounter.Exercise.dip)==null) return null
        val arms=PullUpArmMeasurement(pose).arms
        if(arms.map{it.side}!=witnessSides) return null
        return ExerciseCounter.Exercise.dip.takeIf { arms.isNotEmpty() && arms.all { it.wrist.y>it.shoulder.y } }
    }
    /** Preserve qualified hang identity while contours are still being acquired.
     * Hands remain beside the immutable actually observed qualifying-hang hands.
     * These points never become a bar reference or earn identity/start/count. */
    private fun retainsWitnessedPullUp(pose:PoseResult,reference:BarSegment?):Boolean {
        if(exercise!=ExerciseCounter.Exercise.pullUp || apparatusCandidate!=ExerciseCounter.Exercise.pullUp ||
            imageSize!=pose.imageSize || backend!=pose.backend || revision!=pose.requestRevision) return false
        val gap=lastTime?.let { pose.timestamp.elapsedSeconds(it) } ?: return false
        if(!gap.isFinite() || gap<0 || gap>HandsFreeSetStart.maximumFrameGap) return false
        val arms=PullUpArmMeasurement(pose).arms
        if(arms.size!=2 || arms.map { it.side }!=witnessSides) return false
        val short=kotlin.math.min(pose.imageSize.width,pose.imageSize.height)
        if(reference==null) {
            val person=pose.people.singleOrNull() ?: return false
            if(witnessedHangHands.size!=2 || listOf(PoseJoint.leftWrist,PoseJoint.rightWrist).any{person.landmark(it,.5)==null}) return false
            return arms.zip(witnessedHangHands).all{(arm,hand)->kotlin.math.hypot(arm.wrist.x-hand.x,arm.wrist.y-hand.y)<=short*.02}
        }
        if(!reference.isValid) return false
        val edge=reference.ordered
        if(listOf(edge.a,edge.b).any { it.x !in 0.0..pose.imageSize.width || it.y !in 0.0..pose.imageSize.height }) return false
        // A high pull may raise shoulders above the bar. Current measurable
        // arms and hands at the confirmed reference preserve identity only.
        return arms.all { arm ->
            edge.distance(arm.wrist)<=short*.085
        }
    }
    fun lock():Boolean {if(exercise==null) return false;isLocked=true;return true}
    /** Only the current source-owned dip counter's committed bilateral
     * excursion with an independently ready camera may call this route. */
    fun confirmObservedDipMovement(pose:PoseResult):Boolean {
        if(isLocked) return exercise==ExerciseCounter.Exercise.dip
        val arms=PullUpArmMeasurement(pose).arms
        val short=kotlin.math.min(pose.imageSize.width,pose.imageSize.height)
        if(!pose.timestamp.seconds.isFinite() || pose.timestamp.seconds<0 || !pose.imageSize.isValid ||
            pose.people.size!=1 || arms.size!=2 || arms.any{it.wrist.y-it.shoulder.y < -.02*short}) return false
        lastTime?.let {
            val gap=pose.timestamp.elapsedSeconds(it)
            if(!gap.isFinite() || gap<0 || gap>HandsFreeSetStart.maximumFrameGap ||
                imageSize!=pose.imageSize || backend!=pose.backend || revision!=pose.requestRevision)return false
        }
        exercise=ExerciseCounter.Exercise.dip;apparatusCandidate=exercise;imageSize=pose.imageSize
        backend=pose.backend;revision=pose.requestRevision;witnessSides=arms.map{it.side}
        witnessedHangHands=emptyList();identitySupportReference=null;lastTime=pose.timestamp
        return true
    }

    /** Called only after the current shared movement owner commits a confirmed
     * bilateral excursion beside an observed bar and independent ready camera.
     * This is a separate trajectory route, not shortened posture dwell. */
    fun confirmObservedPullUpMovement(pose:PoseResult,reference:BarSegment?):Boolean {
        if(isLocked) return exercise==ExerciseCounter.Exercise.pullUp
        if(observedPullUpIdentityReference(pose,reference,ArmMeasurement.confidenceThreshold)==null) return false
        lastTime?.let { val gap=pose.timestamp.elapsedSeconds(it);if(!gap.isFinite() || gap<0 || gap>HandsFreeSetStart.maximumFrameGap)return false }
        exercise=ExerciseCounter.Exercise.pullUp;apparatusCandidate=exercise;imageSize=pose.imageSize;backend=pose.backend;revision=pose.requestRevision
        witnessSides=PullUpArmMeasurement(pose).arms.map { it.side };witnessedHangHands=PullUpArmMeasurement(pose).arms.map { it.wrist };lastTime=pose.timestamp
        return true
    }
    /** Only after the source-owned overhead owner commits a bilateral image
     * trajectory and independent ready camera; posture alone cannot call this. */
    fun confirmObservedOverheadPullUpMovement(pose:PoseResult):Boolean {
        if(isLocked) return exercise==ExerciseCounter.Exercise.pullUp
        val arms=PullUpArmMeasurement(pose).arms
        if(!pose.timestamp.seconds.isFinite() || pose.timestamp.seconds<0 || !pose.imageSize.isValid ||
            pose.people.size!=1 || arms.size!=2) return false
        lastTime?.let {
            val gap=pose.timestamp.elapsedSeconds(it)
            if(!gap.isFinite() || gap<0 || gap>HandsFreeSetStart.maximumFrameGap ||
                imageSize!=pose.imageSize || backend!=pose.backend || revision!=pose.requestRevision)return false
        }
        exercise=ExerciseCounter.Exercise.pullUp;apparatusCandidate=exercise;imageSize=pose.imageSize
        backend=pose.backend;revision=pose.requestRevision;witnessSides=arms.map { it.side }
        witnessedHangHands=arms.map { it.wrist };identitySupportReference=null;lastTime=pose.timestamp
        return true
    }
    companion object {
        const val minimumStableSeconds=.6
        const val identityPolicyVersion=8
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
