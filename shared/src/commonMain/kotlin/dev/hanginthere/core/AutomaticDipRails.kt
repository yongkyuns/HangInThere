package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlin.math.*

class AutomaticReferenceStability {
    @Serializable enum class Status {
        waiting, invalidEvidence, sourceRegression, candidateStarted, contextChanged,
        sourceGap, edgeChanged, gripMoved, accumulating, qualified
    }
    /** Counts/timing only; no image geometry and no additional qualification rule. */
    @Serializable data class Diagnostic(val status:Status=Status.waiting,
        val observations:Int=0,val stableSeconds:Double=0.0,val sourceSeconds:Double?=null)
    var diagnostic=Diagnostic();private set
    private var edges=emptyList<BarSegment>();private var grips=emptyList<Point2D>();private var size:ImageSize?=null
    private var firstTime:PresentationTime?=null;private var lastTime:PresentationTime?=null;private var observations=0
    fun reset(){edges=emptyList();grips=emptyList();size=null;firstTime=null;lastTime=null;observations=0;diagnostic=Diagnostic()}
    fun observe(next:List<BarSegment>,hands:List<Point2D>,pose:PoseResult):Boolean {
        val seconds=pose.timestamp.seconds
        if(!seconds.isFinite() || next.isEmpty() || next.any{!it.isValid} || hands.isEmpty() || hands.any{!it.isFinite} || !pose.imageSize.isValid){
            reset();diagnostic=Diagnostic(Status.invalidEvidence,sourceSeconds=seconds.takeIf{it.isFinite()});return false
        }
        val gap=lastTime?.let{pose.timestamp.elapsedSeconds(it)}
        if(gap!=null && (!gap.isFinite() || gap<=0)){
            reset();diagnostic=Diagnostic(Status.sourceRegression,sourceSeconds=seconds);return false
        }
        val short=min(pose.imageSize.width,pose.imageSize.height)
        // Report the first failing condition in the existing qualification order.
        val restart=when {
            lastTime==null -> Status.candidateStarted
            size!=pose.imageSize || edges.size!=next.size || grips.size!=hands.size -> Status.contextChanged
            gap!!>maximumFrameGap -> Status.sourceGap
            !edges.zip(next).all{sameLine(it.first,it.second,short*.008)} -> Status.edgeChanged
            !grips.zip(hands).all{hypot(it.first.x-it.second.x,it.first.y-it.second.y)<=short*.02} -> Status.gripMoved
            else -> null
        }
        // Caller-owned mutable lists cannot rewrite the witnessed baseline.
        if(restart!=null){edges=next.toList();grips=hands.toList();size=pose.imageSize;firstTime=pose.timestamp;observations=0}
        lastTime=pose.timestamp;observations++
        val elapsed=pose.timestamp.elapsedSeconds(firstTime ?: pose.timestamp)
        val qualified=observations>=minimumObservations && elapsed>=minimumStableSeconds
        diagnostic=Diagnostic(if(qualified) Status.qualified else restart ?: Status.accumulating,observations,elapsed,seconds)
        return qualified
    }
    companion object {
        const val minimumObservations=4
        const val minimumStableSeconds=.9
        const val maximumFrameGap=.8
        fun sameLine(p:BarSegment,q:BarSegment,tolerance:Double,angleDegrees:Double=3.0,overlapFraction:Double=.65):Boolean {
            if(!sameSupportingLine(p,q,tolerance,angleDegrees)) return false
            val ux=(p.b.x-p.a.x)/p.length;val uy=(p.b.y-p.a.y)/p.length
            fun projection(point:Point2D)=(point.x-p.a.x)*ux+(point.y-p.a.y)*uy
            val a=projection(q.a);val b=projection(q.b);val overlap=min(p.length,max(a,b))-max(0.0,min(a,b))
            return overlap>=min(p.length,q.length)*overlapFraction
        }
        /** Apparatus identity also compares disjoint fragments, without extending their observed support. */
        fun sameSupportingLine(p:BarSegment,q:BarSegment,tolerance:Double,angleDegrees:Double=3.0):Boolean {
            if(!p.isValid || !q.isValid) return false
            val ux=(p.b.x-p.a.x)/p.length;val uy=(p.b.y-p.a.y)/p.length
            val dot=abs((ux*(q.b.x-q.a.x)+uy*(q.b.y-q.a.y))/q.length)
            return dot>=cos(angleDegrees*PI/180) && p.perpendicularDistance(q.a)<=tolerance && p.perpendicularDistance(q.b)<=tolerance && q.perpendicularDistance(p.a)<=tolerance && q.perpendicularDistance(p.b)<=tolerance
        }
    }
}
/** Independent bilateral rail observations, including local body-edge exclusion. */
class AutomaticDipRails {
    data class Grip(val hands:List<Point2D>,val regions:List<BarRegion>)
    var state=AutomaticPullUpBar.State.lookingForGrip;private set
    private data class ObservedRail(val pair:BarCandidate,val edge:BarSegment) {
        val halfWidth:Double get() {
            val center=pair.centerline
            return listOf(pair.firstEdge.a,pair.firstEdge.b,pair.secondEdge.a,pair.secondEdge.b).maxOf{center.perpendicularDistance(it)}
        }
    }
    private var stability=AutomaticReferenceStability();private var witnessedRails=emptyList<ObservedRail>();private var witnessedHands=emptyList<Point2D>()
    val referenceStabilityDiagnostic get() = stability.diagnostic.takeIf {
        state==AutomaticPullUpBar.State.holding || state==AutomaticPullUpBar.State.locked
    }
    fun reset(){state=AutomaticPullUpBar.State.lookingForGrip;stability=AutomaticReferenceStability();witnessedRails=emptyList();witnessedHands=emptyList()}
    fun observe(pose:PoseResult,side:ArmMeasurement.Side,candidates:List<List<BarLineCandidate>>):ConfirmedBar? {
        if(state==AutomaticPullUpBar.State.locked) return null
        val grip=grip(pose) ?: run{reset();return null}
        if(candidates.size!=2){reset();return null}
        val short=min(pose.imageSize.width,pose.imageSize.height)
        if(witnessedHands.size==2 && !witnessedHands.zip(grip.hands).all{hypot(it.first.x-it.second.x,it.first.y-it.second.y)<=short*.02}) reset()
        val local=grip.regions.mapIndexed { index,crop ->
            val observedCrop=BarRegion(floor(crop.minX)-1,floor(crop.minY)-1,ceil(crop.maxX)+1,ceil(crop.maxY)+1)
            candidates[index].filter{observedCrop.contains(it.edge.a) && observedCrop.contains(it.edge.b)}
        }
        var rails=if(witnessedRails.size==2) (0..1).mapNotNull { index ->
            val witness=witnessedRails[index]
            val matching=local[index].map{it.edge}.filter {
                it.distance(grip.hands[index])<=short*.12 && isBelowGrip(it,grip.hands[index]) &&
                    AutomaticReferenceStability.sameLine(witness.edge,it,short*.01,5.0,.3)
            }.sortedWith(compareBy<BarSegment>{witness.edge.perpendicularDistance(it.midpoint)}.thenByDescending{it.length}).firstOrNull()
            matching?.let{ObservedRail(witness.pair,it.ordered)}
        } else emptyList()
        if(rails.size!=2) {
            // Losing either witness requires two fresh observed pairs and a new dwell.
            reset()
            rails=(0..1).mapNotNull{selection(local[it],grip.hands[it],pose.imageSize)}
        }
        if(rails.size!=2){reset();state=if(candidates.all{it.isEmpty()}) AutomaticPullUpBar.State.findingEdge else AutomaticPullUpBar.State.ambiguous;return null}
        val edges=rails.map{it.edge}
        // A distant midpoint or opposite silhouette choice does not establish a second tube.
        val sameTube=AutomaticReferenceStability.sameSupportingLine(rails[0].pair.centerline,rails[1].pair.centerline,
            rails[0].halfWidth+rails[1].halfWidth+short*.01,5.0)
        val sameFreshEdge=AutomaticReferenceStability.sameSupportingLine(edges[0],edges[1],short*.01,5.0)
        if(hypot(edges[0].midpoint.x-edges[1].midpoint.x,edges[0].midpoint.y-edges[1].midpoint.y)<short*.10 || sameTube || sameFreshEdge){reset();state=AutomaticPullUpBar.State.ambiguous;return null}
        if(witnessedRails.isEmpty()){witnessedRails=rails;witnessedHands=grip.hands}
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
            return selection(candidates,hand,size)?.edge
        }
        fun minimumPairLength(size:ImageSize):Double=max(24.0,min(size.width,size.height)*.04)
        private fun isBelowGrip(edge:BarSegment,hand:Point2D):Boolean {
            // Observed handles may meet the grasp; forearm edges above it may not.
            return min(edge.a.y,edge.b.y)>=hand.y
        }
        private fun selection(candidates:List<BarLineCandidate>,hand:Point2D,size:ImageSize):ObservedRail? {
            if(!size.isValid || !hand.isFinite) return null
            val short=min(size.width,size.height);val bounds=BarRegion(0.0,0.0,size.width,size.height)
            val tubes=mutableListOf<Pair<BarCandidate,BarSegment>>()
            for(i in candidates.indices) for(j in i+1 until candidates.size) {
                val pair=BarFitter.pair(candidates[i].edge,candidates[j].edge,minimumPairLength(size)) ?: continue
                if(!listOf(pair.firstEdge.a,pair.firstEdge.b,pair.secondEdge.a,pair.secondEdge.b).all{bounds.contains(it)} || pair.centerline.distance(hand)>short*.12 || pair.centerline.midpoint.y<hand.y+short*.025) continue
                val a=candidates[i].edge;val b=candidates[j].edge
                if(!isBelowGrip(a,hand) || !isBelowGrip(b,hand)) continue
                val edge=if(a.length==b.length){if(a.distance(hand)<=b.distance(hand)) a else b} else if(a.length>b.length) a else b
                // Rank only complete proposals; an unreachable silhouette must
                // not hide a valid rail merely because its centerline is near.
                if(!(edge.distance(hand)<=short*.12)) continue
                tubes.add(pair to edge)
            }
            // A grasp can hide the handle's upper end. Prefer hand alignment
            // over offset clutter; eligibility still requires finite support.
            val tube=tubes.sortedWith(compareBy<Pair<BarCandidate,BarSegment>>{it.first.centerline.perpendicularDistance(hand)}
                .thenByDescending{it.first.geometryScore}.thenBy{it.first.centerline.distance(hand)}).firstOrNull() ?: return null
            return ObservedRail(tube.first,tube.second.ordered)
        }
    }
}
class AutomaticApparatusReference {
    @Serializable data class Search(val hands:List<Point2D>,val regions:List<BarRegion>,val minimumObservedLength:Double?=null) {
        fun matches(other:Search,size:ImageSize)=hands.size==other.hands.size && hands.zip(other.hands).all{hypot(it.first.x-it.second.x,it.first.y-it.second.y)<=min(size.width,size.height)*.02}
    }
    private var pullUp=AutomaticPullUpBar();private var dip=AutomaticDipRails();private var sharedDipBar=AutomaticDipBar()
    var state=AutomaticPullUpBar.State.lookingForGrip;private set
    var referenceStabilityDiagnostic:AutomaticReferenceStability.Diagnostic?=null;private set
    fun reset(){pullUp=AutomaticPullUpBar();dip=AutomaticDipRails();sharedDipBar=AutomaticDipBar();state=AutomaticPullUpBar.State.lookingForGrip;referenceStabilityDiagnostic=null}
    fun observe(pose:PoseResult,exercise:ExerciseCounter.Exercise,side:ArmMeasurement.Side,candidates:List<List<BarLineCandidate>>):ConfirmedBar? {
        if(exercise==ExerciseCounter.Exercise.pullUp) return pullUp.observe(pose,candidates.firstOrNull() ?: emptyList()).also{state=pullUp.state;referenceStabilityDiagnostic=pullUp.referenceStabilityDiagnostic}
        if(candidates.size!=2 && candidates.size!=3){dip.reset();sharedDipBar.reset();state=AutomaticPullUpBar.State.lookingForGrip;referenceStabilityDiagnostic=null;return null}
        val rails=dip.observe(pose,side,candidates.take(2))
        if(dip.state==AutomaticPullUpBar.State.holding || dip.state==AutomaticPullUpBar.State.locked){
            // Preserve an independently witnessed parallel-rail dwell.
            sharedDipBar.reset();state=dip.state;referenceStabilityDiagnostic=dip.referenceStabilityDiagnostic;return rails
        }
        val shared=sharedDipBar.observe(pose,side,candidates.getOrNull(2) ?: emptyList())
        state=if(sharedDipBar.state==AutomaticPullUpBar.State.holding || sharedDipBar.state==AutomaticPullUpBar.State.locked) sharedDipBar.state else dip.state
        referenceStabilityDiagnostic=if(sharedDipBar.state==AutomaticPullUpBar.State.holding || sharedDipBar.state==AutomaticPullUpBar.State.locked) sharedDipBar.referenceStabilityDiagnostic else dip.referenceStabilityDiagnostic
        return shared
    }
    companion object {
        fun search(pose:PoseResult,exercise:ExerciseCounter.Exercise):Search?=when(exercise){ExerciseCounter.Exercise.pullUp -> AutomaticPullUpBar.grip(pose)?.let{Search(listOf(it.left,it.right),listOf(it.region))};ExerciseCounter.Exercise.dip -> AutomaticDipRails.grip(pose)?.let{Search(it.hands,it.regions+listOfNotNull(AutomaticDipBar.region(it,pose.imageSize)),AutomaticDipRails.minimumPairLength(pose.imageSize))}}
    }
}
