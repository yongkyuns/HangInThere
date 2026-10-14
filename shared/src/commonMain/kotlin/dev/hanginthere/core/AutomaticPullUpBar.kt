package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlin.math.*

/** Pose guides the crop; only freshly observed image edges can become references. */
class AutomaticPullUpBar {
    @Serializable enum class State { lookingForGrip,findingEdge,ambiguous,holding,locked }
    data class Grip(val left:Point2D,val right:Point2D,val region:BarRegion)
    var state=State.lookingForGrip;private set
    private var stability=AutomaticReferenceStability()
    val referenceStabilityDiagnostic get() = stability.diagnostic.takeIf {
        state==State.holding || state==State.locked
    }
    fun reset(){state=State.lookingForGrip;stability=AutomaticReferenceStability()}
    fun observe(pose:PoseResult,candidates:List<BarLineCandidate>):ConfirmedBar? {
        if(state==State.locked) return null
        val grip=grip(pose) ?: run{reset();return null}
        val edge=select(candidates,grip,pose.imageSize) ?: run{reset();state=if(candidates.isEmpty()) State.findingEdge else State.ambiguous;return null}
        state=State.holding
        if(!stability.observe(listOf(edge),listOf(grip.left,grip.right),pose)) return null
        state=State.locked
        return ConfirmedBar(ConfirmedBar.Role.pullUpGrip,ConfirmedBar.Method.automaticContours,edge,null,pose.imageSize,pose.timestamp)
    }
    companion object {
        const val samplingInterval=.3
        fun grip(pose:PoseResult):Grip? {
            if(!pose.imageSize.isValid || !pose.timestamp.seconds.isFinite() || pose.people.size!=1 || ArmMeasurement(pose,ArmMeasurement.Side.left).estimate==null || ArmMeasurement(pose,ArmMeasurement.Side.right).estimate==null) return null
            val person=pose.people[0]
            val points=listOf(PoseJoint.leftWrist,PoseJoint.rightWrist,PoseJoint.leftShoulder,PoseJoint.rightShoulder).mapNotNull{person.landmark(it,.5)?.position}
            if(points.size!=4) return null
            val l=points[0];val r=points[1];val short=min(pose.imageSize.width,pose.imageSize.height)
            if(points[2].y-l.y<=short*.08 || points[3].y-r.y<=short*.08 || abs(l.x-r.x)<short*.12 || abs(l.y-r.y)>short*.10) return null
            val region=BarRegion(Point2D(max(0.0,min(l.x,r.x)-short*.12),max(0.0,min(l.y,r.y)-max(12.0,short*.12))),Point2D(min(pose.imageSize.width,max(l.x,r.x)+short*.12),min(pose.imageSize.height,max(l.y,r.y)+max(6.0,short*.04))))
            return if(region.isValid(pose.imageSize)) Grip(l,r,region) else null
        }
        fun select(candidates:List<BarLineCandidate>,grip:Grip,size:ImageSize):BarSegment? {
            val short=min(size.width,size.height);val span=abs(grip.left.x-grip.right.x)
            val eligible=candidates.map{it.edge.ordered}.filter {e ->
                ConfirmedBar(ConfirmedBar.Role.pullUpGrip,ConfirmedBar.Method.automaticContours,e,null,size,PresentationTime(0,1)).isValid && e.length>=span*.65 && abs(e.b.y-e.a.y)<=(e.b.x-e.a.x)*tan(15*PI/180) && listOf(grip.left,grip.right).all{e.distance(it)<=short*.085 && e.midpoint.y<=it.y+short*.015}
            }.sortedBy{it.midpoint.y}
            val upper=eligible.firstOrNull() ?: return null
            return if(eligible.all{sameLine(upper,it,short*.03)}) upper.ordered else null
        }
        private fun sameLine(pOriginal:BarSegment,qOriginal:BarSegment,tolerance:Double):Boolean {
            val p=pOriginal.ordered;val q=qOriginal.ordered
            if(!p.isValid || !q.isValid) return false
            val dot=((p.b.x-p.a.x)*(q.b.x-q.a.x)+(p.b.y-p.a.y)*(q.b.y-q.a.y))/(p.length*q.length)
            val overlap=min(p.b.x,q.b.x)-max(p.a.x,q.a.x)
            return dot>=cos(3*PI/180) && overlap>=min(p.b.x-p.a.x,q.b.x-q.a.x)*.65 && p.perpendicularDistance(q.a)<=tolerance && p.perpendicularDistance(q.b)<=tolerance && q.perpendicularDistance(p.a)<=tolerance && q.perpendicularDistance(p.b)<=tolerance
        }
    }
}
