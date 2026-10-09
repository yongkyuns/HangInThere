package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlin.math.*

class AutomaticReferenceStability {
    private var edges=emptyList<BarSegment>();private var grips=emptyList<Point2D>();private var size:ImageSize?=null
    private var firstSeconds:Double?=null;private var lastSeconds:Double?=null;private var observations=0
    fun reset(){edges=emptyList();grips=emptyList();size=null;firstSeconds=null;lastSeconds=null;observations=0}
    fun observe(next:List<BarSegment>,hands:List<Point2D>,pose:PoseResult):Boolean {
        val seconds=pose.timestamp.seconds
        if(!seconds.isFinite() || next.isEmpty() || next.any{!it.isValid} || hands.isEmpty() || hands.any{!it.isFinite} || !pose.imageSize.isValid){reset();return false}
        if(lastSeconds?.let{seconds<=it}==true){reset();return false}
        val short=min(pose.imageSize.width,pose.imageSize.height)
        val continues=size==pose.imageSize && edges.size==next.size && grips.size==hands.size && lastSeconds?.let{seconds-it<=.8}==true && edges.zip(next).all{sameLine(it.first,it.second,short*.008)} && grips.zip(hands).all{hypot(it.first.x-it.second.x,it.first.y-it.second.y)<=short*.02}
        if(!continues){edges=next;grips=hands;size=pose.imageSize;firstSeconds=seconds;observations=0}
        lastSeconds=seconds;observations++
        return observations>=4 && seconds-(firstSeconds ?: seconds)>=.9
    }
    companion object {
        fun sameLine(p:BarSegment,q:BarSegment,tolerance:Double,angleDegrees:Double=3.0,overlapFraction:Double=.65):Boolean {
            if(!p.isValid || !q.isValid) return false
            val ux=(p.b.x-p.a.x)/p.length;val uy=(p.b.y-p.a.y)/p.length
            val dot=abs((ux*(q.b.x-q.a.x)+uy*(q.b.y-q.a.y))/q.length)
            fun projection(point:Point2D)=(point.x-p.a.x)*ux+(point.y-p.a.y)*uy
            val a=projection(q.a);val b=projection(q.b);val overlap=min(p.length,max(a,b))-max(0.0,min(a,b))
            return dot>=cos(angleDegrees*PI/180) && overlap>=min(p.length,q.length)*overlapFraction && p.perpendicularDistance(q.a)<=tolerance && p.perpendicularDistance(q.b)<=tolerance && q.perpendicularDistance(p.a)<=tolerance && q.perpendicularDistance(p.b)<=tolerance
        }
    }
}
/** Independent bilateral rail observations, including local body-edge exclusion. */
class AutomaticDipRails {
    data class Grip(val hands:List<Point2D>,val regions:List<BarRegion>)
    var state=AutomaticPullUpBar.State.lookingForGrip;private set
    private var stability=AutomaticReferenceStability();private var witnessedRails=emptyList<BarSegment>();private var witnessedHands=emptyList<Point2D>()
    fun reset(){state=AutomaticPullUpBar.State.lookingForGrip;stability=AutomaticReferenceStability();witnessedRails=emptyList();witnessedHands=emptyList()}
    fun observe(pose:PoseResult,side:ArmMeasurement.Side,candidates:List<List<BarLineCandidate>>):ConfirmedBar? {
        if(state==AutomaticPullUpBar.State.locked) return null
        val grip=grip(pose) ?: run{reset();return null}
        if(candidates.size!=2){reset();return null}
        val short=min(pose.imageSize.width,pose.imageSize.height)
        if(witnessedHands.size==2 && !witnessedHands.zip(grip.hands).all{hypot(it.first.x-it.second.x,it.first.y-it.second.y)<=short*.02}) reset()
        val edges=mutableListOf<BarSegment>()
        for(index in 0..1) {
            val crop=grip.regions[index]
            val observedCrop=BarRegion(floor(crop.minX)-1,floor(crop.minY)-1,ceil(crop.maxX)+1,ceil(crop.maxY)+1)
            val local=candidates[index].filter{observedCrop.contains(it.edge.a) && observedCrop.contains(it.edge.b)}
            val matching=if(witnessedRails.size==2) local.map{it.edge}.filter{it.distance(grip.hands[index])<=short*.12 && min(it.a.y,it.b.y)>=grip.hands[index].y+short*.04 && AutomaticReferenceStability.sameLine(witnessedRails[index],it,short*.01,5.0,.3)}.sortedWith(compareBy<BarSegment>{witnessedRails[index].perpendicularDistance(it.midpoint)}.thenByDescending{it.length}).firstOrNull() else null
            (matching ?: select(local,grip.hands[index],pose.imageSize))?.let{edges.add(it)}
        }
        if(edges.size!=2){reset();state=if(candidates.all{it.isEmpty()}) AutomaticPullUpBar.State.findingEdge else AutomaticPullUpBar.State.ambiguous;return null}
        if(hypot(edges[0].midpoint.x-edges[1].midpoint.x,edges[0].midpoint.y-edges[1].midpoint.y)<short*.10){reset();state=AutomaticPullUpBar.State.ambiguous;return null}
        if(witnessedRails.isEmpty()){witnessedRails=edges.toList();witnessedHands=grip.hands}
        state=AutomaticPullUpBar.State.holding
        if(!stability.observe(edges,grip.hands,pose)) return null
        val bar=ConfirmedBar(if(side==ArmMeasurement.Side.left) ConfirmedBar.Role.leftDipRail else ConfirmedBar.Role.rightDipRail,ConfirmedBar.Method.automaticContours,edges[if(side==ArmMeasurement.Side.left) 0 else 1],null,pose.imageSize,pose.timestamp)
        if(!bar.isValid){reset();return null}
        state=AutomaticPullUpBar.State.locked;return bar
    }
    companion object {
        fun grip(pose:PoseResult):Grip? {
            if(!pose.imageSize.isValid || !pose.timestamp.seconds.isFinite()) return null
            val arms=PullUpArmMeasurement(pose).arms
            if(arms.size!=2) return null
            val short=min(pose.imageSize.width,pose.imageSize.height)
            if(!arms.all{it.estimate.elbowDegrees+1e-6>=DipSupportMeasurement.startingDegrees && it.wrist.y-it.shoulder.y>=.35*(it.estimate.upperArmPixels+it.estimate.forearmPixels)}) return null
            val hands=arms.map{it.wrist}
            if(abs(hands[0].x-hands[1].x)<short*.12 || abs(hands[0].y-hands[1].y)>short*.10) return null
            val regions=arms.map{arm -> val hand=arm.wrist;var minX=max(0.0,hand.x-short*.12);var maxX=min(pose.imageSize.width,hand.x+short*.12);if(hand.x-arm.shoulder.x>short*.04) minX=max(minX,(hand.x+arm.shoulder.x)/2);if(arm.shoulder.x-hand.x>short*.04) maxX=min(maxX,(hand.x+arm.shoulder.x)/2);BarRegion(minX,max(0.0,hand.y-short*.08),maxX,min(pose.imageSize.height,hand.y+short*.20))}
            return if(regions.all{it.isValid(pose.imageSize)}) Grip(hands,regions) else null
        }
        fun select(candidates:List<BarLineCandidate>,hand:Point2D,size:ImageSize):BarSegment? {
            if(!size.isValid || !hand.isFinite) return null
            val short=min(size.width,size.height);val bounds=BarRegion(0.0,0.0,size.width,size.height)
            val tubes=mutableListOf<Pair<BarCandidate,BarSegment>>()
            for(i in candidates.indices) for(j in i+1 until candidates.size) {
                val pair=BarFitter.pair(candidates[i].edge,candidates[j].edge,max(24.0,short*.04)) ?: continue
                if(!listOf(pair.firstEdge.a,pair.firstEdge.b,pair.secondEdge.a,pair.secondEdge.b).all{bounds.contains(it)} || pair.centerline.distance(hand)>short*.12 || pair.centerline.midpoint.y<hand.y+short*.025) continue
                val a=candidates[i].edge;val b=candidates[j].edge
                if(min(a.a.y,a.b.y)<hand.y+short*.04 || min(b.a.y,b.b.y)<hand.y+short*.04) continue
                val edge=if(a.length==b.length){if(a.distance(hand)<=b.distance(hand)) a else b} else if(a.length>b.length) a else b
                tubes.add(pair to edge)
            }
            val tube=tubes.sortedWith(compareByDescending<Pair<BarCandidate,BarSegment>>{it.first.geometryScore}.thenBy{it.first.centerline.distance(hand)}).firstOrNull() ?: return null
            return if(tube.second.distance(hand)<=short*.12) tube.second.ordered else null
        }
    }
}
class AutomaticApparatusReference {
    @Serializable data class Search(val hands:List<Point2D>,val regions:List<BarRegion>) {
        fun matches(other:Search,size:ImageSize)=hands.size==other.hands.size && hands.zip(other.hands).all{hypot(it.first.x-it.second.x,it.first.y-it.second.y)<=min(size.width,size.height)*.02}
    }
    private var pullUp=AutomaticPullUpBar();private var dip=AutomaticDipRails()
    var state=AutomaticPullUpBar.State.lookingForGrip;private set
    fun reset(){pullUp=AutomaticPullUpBar();dip=AutomaticDipRails();state=AutomaticPullUpBar.State.lookingForGrip}
    fun observe(pose:PoseResult,exercise:ExerciseCounter.Exercise,side:ArmMeasurement.Side,candidates:List<List<BarLineCandidate>>):ConfirmedBar? = when(exercise){ExerciseCounter.Exercise.pullUp -> pullUp.observe(pose,candidates.firstOrNull() ?: emptyList()).also{state=pullUp.state};ExerciseCounter.Exercise.dip -> dip.observe(pose,side,candidates).also{state=dip.state}}
    companion object {
        fun search(pose:PoseResult,exercise:ExerciseCounter.Exercise):Search?=when(exercise){ExerciseCounter.Exercise.pullUp -> AutomaticPullUpBar.grip(pose)?.let{Search(listOf(it.left,it.right),listOf(it.region))};ExerciseCounter.Exercise.dip -> AutomaticDipRails.grip(pose)?.let{Search(it.hands,it.regions)}}
    }
}
