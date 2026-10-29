package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.math.*

/** Image-patch grip setup, separate from the single-person rep counter. Chooses
 * one clearly raised-hands athlete among original 2D observations, then binds
 * hand search crops to that athlete. No auxiliary body/hand depth or grip label
 * participates. Spotters are allowed; competing athletes/overlapping hands are
 * unavailable. One serial owner per source; no retained pose fills a dropout.
 */
class GripPatchSubject(private val sourceId: String) {
    @Serializable data class Target(val side: ArmMeasurement.Side, val wrist: Point2D,
        val otherWrist: Point2D?, val forearmToward: Point2D, val regions: List<PersonInferenceRegion>)
    @Serializable data class Plan(val sourceId: String, val timestamp: PresentationTime, val imageSize: ImageSize,
        val status: String, val personIndex: Int? = null, val targets: List<Target> = emptyList(), val policyVersion: Int = 1)
    private data class Candidate(val index: Int, val wrists: List<Point2D>, val span: Double)
    private var last: PresentationTime? = null
    private var anchor: Candidate? = null
    private var first: PresentationTime? = null
    private var observations = 0
    private var size: ImageSize? = null
    private var backend: String? = null
    private var revision: Int? = null
    init { require(sourceId.isNotBlank() && sourceId.length <= 256) }
    private fun clear() { anchor = null; first = null; observations = 0 }
    fun observe(pose: PoseResult): Plan {
        fun result(status: String, index: Int? = null, targets: List<Target> = emptyList()) =
            Plan(sourceId, pose.timestamp, pose.imageSize, status, index, targets)
        if (!pose.timestamp.seconds.isFinite() || last?.let {
                SourceTimeComparison.compareElapsed(pose.timestamp,it,SourceTimeLimit(0,1)) != 1 } == true) {
            clear(); return result("sourceClockUnavailable")
        }
        if (last?.let { SourceTimeComparison.compareElapsed(pose.timestamp,it,SourceTimeLimit(7,20)) == 1 } == true ||
            size != pose.imageSize || backend != pose.backend || revision != pose.requestRevision) clear()
        last = pose.timestamp; size = pose.imageSize; backend = pose.backend; revision = pose.requestRevision
        if (!pose.imageSize.isValid || pose.backend.isBlank() || pose.people.size > 16) { clear(); return result("invalidContext") }
        val short = min(pose.imageSize.width,pose.imageSize.height)
        fun point(person: PoseObservation, joint: PoseJoint, threshold: Double = .3) =
            person.landmarks.singleOrNull { it.joint == joint }?.takeIf { it.isVisible(threshold) &&
                it.position.x in 0.0..pose.imageSize.width && it.position.y in 0.0..pose.imageSize.height }?.position
        val candidates = pose.people.mapIndexedNotNull { index, person ->
            if (person.landmarks.size > 64 || person.landmarks.map { it.joint }.distinct().size != person.landmarks.size) return@mapIndexedNotNull null
            val shoulders = ArmMeasurement.Side.entries.map { point(person,it.joints[0]) ?: return@mapIndexedNotNull null }
            val wrists = ArmMeasurement.Side.entries.map { point(person,it.joints[2],.5) ?: return@mapIndexedNotNull null }
            val span = distance(shoulders[0],shoulders[1])
            if (span < short*.06 || wrists.indices.any { wrists[it].y > shoulders[it].y + short*.03 }) return@mapIndexedNotNull null
            Candidate(index,wrists,span)
        }
        if (candidates.size != 1) { clear(); return result(if (candidates.isEmpty()) "noRaisedHandsAthlete" else "competingAthletes") }
        val current = candidates.single()
        if (distance(current.wrists[0],current.wrists[1]) < short*.12) { clear(); return result("overlappingHands") }
        val old = anchor
        val continuous = old != null && current.wrists.indices.all { distance(current.wrists[it],old.wrists[it]) <= short*.15 } &&
            current.span/old.span in (1/1.5)..1.5
        if (!continuous) { clear(); first = pose.timestamp }
        anchor = current; observations++
        if (observations < 3 || SourceTimeComparison.compareElapsed(pose.timestamp,first!!,SourceTimeLimit(1,4))!! < 0)
            return result("acquiringAthlete",current.index)
        val person = pose.people[current.index]
        val selected = pose.copy(people=listOf(person),gripObservation=null)
        val requests = WorkoutGripInference.handRequests(selected)
        if (requests.size != 2) { clear(); return result("handRegionsUnavailable") }
        val targets = requests.map { request ->
            val i=request.side.ordinal
            Target(request.side,current.wrists[i],current.wrists[1-i],
                point(person,request.side.joints[1]) ?: point(person,request.side.joints[0])!!,request.regions)
        }
        // A different person's observed wrist inside a target crop makes its
        // ownership unclear. A spotter whose hands stay below does not block it.
        if (pose.people.indices.any { i -> i != current.index && ArmMeasurement.Side.entries.any { side ->
                point(pose.people[i],side.joints[2])?.let { wrist -> targets.any { t -> t.regions.any { r ->
                    wrist.x in r.left.toDouble()..(r.left+r.width).toDouble() && wrist.y in r.top.toDouble()..(r.top+r.height).toDouble()
                } } } == true } }) { clear(); return result("competingHandInCrop") }
        return result("ready",current.index,targets)
    }
    companion object { private fun distance(a:Point2D,b:Point2D)=hypot(a.x-b.x,a.y-b.y) }
}

/** Research policy v2: acquire the torso using current observations, then search
 * each supported raised hand independently. Never synthesize a missing wrist.
 * Low-confidence wrists require a confident elbow and SDK ownership validation.
 * The original policy remains the default bridge used by existing clients. */
class GripPatchSubjectV2(private val sourceId: String) {
    private val bilateral=GripPatchSubject(sourceId)
    private data class Candidate(val index:Int,val center:Point2D,val span:Double,
        val wrists:List<Point2D?>,val supported:List<Boolean>)
    private var last:PresentationTime?=null
    private var anchor:Candidate?=null
    private var first:PresentationTime?=null
    private var observations=0
    private var context:Triple<ImageSize,String,Int>?=null
    init { require(sourceId.isNotBlank() && sourceId.length<=256) }
    private fun clear(){anchor=null;first=null;observations=0}
    fun observe(pose:PoseResult):GripPatchSubject.Plan {
        val trusted=bilateral.observe(pose)
        val expanded=observeExpanded(pose)
        return if(trusted.status=="ready")trusted.copy(policyVersion=2) else expanded
    }
    private fun observeExpanded(pose:PoseResult):GripPatchSubject.Plan {
        fun result(status:String,index:Int?=null,targets:List<GripPatchSubject.Target> = emptyList()) =
            GripPatchSubject.Plan(sourceId,pose.timestamp,pose.imageSize,status,index,targets,2)
        if(!pose.timestamp.seconds.isFinite() || last?.let {
                SourceTimeComparison.compareElapsed(pose.timestamp,it,SourceTimeLimit(0,1))!=1 }==true){
            clear();return result("sourceClockUnavailable")
        }
        val nextContext=Triple(pose.imageSize,pose.backend,pose.requestRevision)
        if(context!=nextContext || last?.let {
                SourceTimeComparison.compareElapsed(pose.timestamp,it,SourceTimeLimit(7,20))==1 }==true)clear()
        last=pose.timestamp;context=nextContext
        if(!pose.imageSize.isValid || pose.backend.isBlank() || pose.people.size>16){clear();return result("invalidContext")}
        val short=min(pose.imageSize.width,pose.imageSize.height)
        fun point(person:PoseObservation,joint:PoseJoint,threshold:Double=.3) =
            person.landmarks.singleOrNull { it.joint==joint }?.takeIf { it.isVisible(threshold) &&
                it.position.x in 0.0..pose.imageSize.width && it.position.y in 0.0..pose.imageSize.height }
        val candidates=pose.people.mapIndexedNotNull { index,person ->
            if(person.landmarks.size>64 || person.landmarks.map{it.joint}.distinct().size!=person.landmarks.size)return@mapIndexedNotNull null
            val shoulders=ArmMeasurement.Side.entries.map{point(person,it.joints[0])?.position ?: return@mapIndexedNotNull null}
            val span=distance(shoulders[0],shoulders[1]);if(span<short*.04)return@mapIndexedNotNull null
            val wrists=ArmMeasurement.Side.entries.map{point(person,it.joints[2])}
            // A spotter may raise hands above shoulders while holding someone's
            // waist. Positive observed head geometry helps reject that case.
            // No head point is required and absence never supplies orientation.
            val head=listOf(PoseJoint.nose,PoseJoint.leftEye,PoseJoint.rightEye,PoseJoint.leftEar,PoseJoint.rightEar)
                .mapNotNull{point(person,it)?.position?.y}.minOrNull()
            val supported=ArmMeasurement.Side.entries.map { side ->
                val wrist=wrists[side.ordinal]
                wrist!=null && wrist.position.y<=shoulders[side.ordinal].y+short*.03 &&
                    (head==null || wrist.position.y<=head+span*.3) &&
                    (wrist.confidence>=.5 || point(person,side.joints[1],.5)!=null)
            }
            if(supported.none{it})return@mapIndexedNotNull null
            Candidate(index,Point2D((shoulders[0].x+shoulders[1].x)/2,(shoulders[0].y+shoulders[1].y)/2),span,
                wrists.map{it?.position},supported)
        }
        if(candidates.isEmpty()){clear();return result("noRaisedHandsAthlete")}
        // A uniquely bilateral athlete may coexist with a spotter raising one
        // hand. Equal support leaves identity unresolved; no size-based guess.
        val best=candidates.maxOf{it.supported.count{v->v}}
        val primary=candidates.filter{it.supported.count{v->v}==best}
        if(primary.size!=1){clear();return result("competingAthletes")}
        val current=primary.single();val wrists=current.wrists
        if(wrists.all{it!=null} && distance(wrists[0]!!,wrists[1]!!)<max(short*.04,current.span*.35)){
            clear();return result("overlappingHands")
        }
        val old=anchor
        if(old==null || distance(current.center,old.center)>short*.15 || current.span/old.span !in (1/1.5)..1.5){
            clear();first=pose.timestamp
        }
        anchor=current;observations++
        if(observations<3 || SourceTimeComparison.compareElapsed(pose.timestamp,first!!,SourceTimeLimit(1,4))!!<0)
            return result("acquiringAthlete",current.index)
        val person=pose.people[current.index]
        fun region(cx:Double,cy:Double,side:Double):PersonInferenceRegion? {
            val x=floor(max(0.0,cx-side/2)).toInt();val y=floor(max(0.0,cy-side/2)).toInt()
            val right=ceil(min(pose.imageSize.width,cx+side/2)).toInt();val bottom=ceil(min(pose.imageSize.height,cy+side/2)).toInt()
            return PersonInferenceRegion(x,y,right-x,bottom-y).takeIf{it.isValidFor(pose.imageSize)}
        }
        val targets=ArmMeasurement.Side.entries.mapNotNull { side ->
            if(!current.supported[side.ordinal])return@mapNotNull null
            val wrist=wrists[side.ordinal]!!;val elbow=point(person,side.joints[1],.5)?.position
            val toward=elbow ?: point(person,side.joints[0])!!.position
            val length=(elbow?.let{distance(wrist,it)*1.5} ?: short*.2).coerceIn(short*.12,short*.4)
            val cx=wrist.x+(elbow?.let{(wrist.x-it.x)*.15} ?: 0.0)
            val cy=wrist.y+(elbow?.let{(wrist.y-it.y)*.15} ?: 0.0)
            val regions=listOfNotNull(region(cx,cy,length),region(wrist.x,wrist.y,short*.28)).distinct()
            if(regions.isEmpty())return@mapNotNull null
            GripPatchSubject.Target(side,wrist,wrists[1-side.ordinal],toward,regions)
        }
        if(targets.isEmpty()){clear();return result("handRegionsUnavailable")}
        if(pose.people.indices.any { i->i!=current.index && ArmMeasurement.Side.entries.any { side ->
                point(pose.people[i],side.joints[2])?.position?.let { wrist ->targets.any{t->t.regions.any{r->
                    wrist.x in r.left.toDouble()..(r.left+r.width).toDouble() && wrist.y in r.top.toDouble()..(r.top+r.height).toDouble()
                }}}==true }}){clear();return result("competingHandInCrop")}
        return result("ready",current.index,targets)
    }
    companion object {private fun distance(a:Point2D,b:Point2D)=hypot(a.x-b.x,a.y-b.y)}
}

class GripPatchSubjectBridge(sourceId: String) {
    private val selector = GripPatchSubject(sourceId)
    @Throws(IllegalArgumentException::class)
    fun observeJson(poseJson: String): String {
        require(poseJson.length <= 262144)
        return WorkoutRecordCodec.json.encodeToString(selector.observe(WorkoutRecordCodec.json.decodeFromString<PoseResult>(poseJson)))
    }
}
