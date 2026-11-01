package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlin.math.*

/** Camera-qualified hanging knee/leg raises. Counts visible lift-return cycles,
 * not anatomical form, foot support, bar contact or muscular effort. */
class HangingLegRaiseMotion {
    @Serializable enum class Phase { seekingStart, ready, lifting, raised }
    @Serializable data class Rep(val startedAt: Double, val raisedAt: Double, val returnedAt: Double)
    @Serializable data class SetSummary(val startedAt: Double, val endedAt: Double, val endReason: String, val reps: List<Rep>)
    @Serializable data class Snapshot(val phase: Phase, val title: String, val cycles: Int,
        val setCycles: Int, val setStarted: Boolean, val side: ArmMeasurement.Side?,
        val progress: Double?, val targetReached: Boolean, val observationUsable: Boolean,
        val rep: Rep? = null, val endedSet: SetSummary? = null)
    companion object {
        const val policyVersion = 2
        const val countMethod = "hangingLegRaise"
        const val boundaryPolicyVersion = 11
    }
    private data class Body(val side: ArmMeasurement.Side, val shoulder: Point2D,
        val wrist: Point2D, val hip: Point2D, val knee: Point2D, val length: Double) {
        val down get() = (knee.y-hip.y)/length
    }
    private val source = BodyAthleteSelection(BodyAthleteAssociation.Profile.projectedTorso)
    private var clock: PresentationTime? = null
    private var generation: Long? = null
    private var phase = Phase.seekingStart
    private var baseline: Body? = null
    private var candidate: Body? = null
    private var since: PresentationTime? = null
    private var lastUsable: PresentationTime? = null
    private var endpointSince: PresentationTime? = null
    private var releaseSince: PresentationTime? = null
    private var departure: Double? = null
    private var highestAt: Double? = null
    private var highest = Double.POSITIVE_INFINITY
    private var setStart: Double? = null
    private val reps = mutableListOf<Rep>()
    private var cycles = 0
    internal val attemptStartedAt get() = departure
    internal val extremeAt get() = highestAt
    private fun elapsed(a: PresentationTime, b: PresentationTime, n: Int, d: Int) =
        SourceTimeComparison.compareElapsed(a,b,SourceTimeLimit(n,d))
    private fun reset() {
        phase=Phase.seekingStart;baseline=null;candidate=null;since=null;lastUsable=null
        endpointSince=null;releaseSince=null;departure=null;highestAt=null;highest=Double.POSITIVE_INFINITY
        setStart=null;reps.clear()
    }
    private fun close(at: Double, reason: String): SetSummary? {
        val ended=setStart?.let { SetSummary(it,at,reason,reps.toList()) };reset();return ended
    }
    fun finish(reason: String = "endOfInput"): SetSummary? {
        require(reason.isNotBlank() && reason.length<=64)
        return clock?.let { close(it.seconds,reason) }
    }
    fun observe(pose: PoseResult, camera: SideViewPushUpMotion.Camera): Snapshot {
        val time=pose.timestamp
        var ended: SetSummary?=null;var rep: Rep?=null
        fun result(title: String, usable: Boolean=false, progress: Double?=null)=Snapshot(
            phase,title,cycles,reps.size,setStart!=null,baseline?.side,progress,
            usable && phase==Phase.raised,usable,rep,ended)
        if (!time.seconds.isFinite() || time.seconds<0 || clock?.let { elapsed(time,it,0,1)!=1 }==true) {
            clock?.let { ended=close(it.seconds,"invalidSourceClock") };return result("Waiting for fresh body detection")
        }
        if (clock?.let { elapsed(time,it,7,20)==1 }==true) ended=close(time.seconds,"sourceTimeGap")
        clock=time
        val decision=source.observe(pose)
        if (generation!=null && generation!=decision.sourceGeneration) ended=close(time.seconds,"athleteEpochChanged") ?: ended
        generation=decision.sourceGeneration
        if (camera!=SideViewPushUpMotion.Camera.ready) {
            ended=close(time.seconds,if(camera==SideViewPushUpMotion.Camera.moved) "cameraMoved" else "cameraUnavailable") ?: ended
            return result("Keep the phone still")
        }
        if (lastUsable?.let { elapsed(time,it,1,2)==1 }==true) ended=close(time.seconds,"bodyTrackingLost") ?: ended
        if (decision.status!=BodyAthleteAssociation.Status.associated) {
            candidate=null;since=null;endpointSince=null;releaseSince=null
            if(decision.status==BodyAthleteAssociation.Status.lost) ended=close(time.seconds,"athleteAssociationLost") ?: ended
            return result("Show one athlete · hands to knees")
        }
        val selected=checkNotNull(source.selectedPose)
        val bodies=ArmMeasurement.Side.entries.mapNotNull { measure(selected,it) }
        val current=baseline?.let { fixed -> bodies.firstOrNull { it.side==fixed.side } }
            ?: bodies.maxByOrNull { it.length }.takeIf { baseline==null }
        if(current==null) { candidate=null;since=null;endpointSince=null;releaseSince=null;return result("Show shoulder, hand, hip and knee") }
        if(current.length < .16*min(pose.imageSize.width,pose.imageSize.height)) {
            since=null;candidate=null;endpointSince=null;return result("Move a little closer")
        }
        val overhead=current.wrist.y <= current.shoulder.y-.55*current.length
        val upright=current.hip.y-current.shoulder.y >= .7*current.length
        val fixed=baseline
        if(fixed==null) {
            if(!overhead || !upright || current.down<.55) { candidate=null;since=null;return result("Hang still · legs down") }
            if(candidate?.let { it.side==current.side && distance(it.hip,current.hip)<.15*it.length && distance(it.wrist,current.wrist)<.15*it.length }!=true) {
                candidate=current;since=time
            }
            if(elapsed(time,checkNotNull(since),3,5)?.let { it>=0 }==true) {
                baseline=current;lastUsable=time;phase=Phase.ready;return result("Ready · raise your legs",true,0.0)
            }
            return result("Hold for a moment")
        }
        val released=!overhead || !upright || distance(current.wrist,fixed.wrist)>.4*fixed.length || distance(current.hip,fixed.hip)>.5*fixed.length
        if(released) {
            endpointSince=null
            if(releaseSince==null) releaseSince=time
            if(elapsed(time,checkNotNull(releaseSince),2,5)?.let { it>=0 }==true) ended=close(time.seconds,"positionChanged") ?: ended
            return result("Hang still · avoid swinging")
        }
        releaseSince=null
        if(current.length !in .65*fixed.length..1.4*fixed.length) { endpointSince=null;return result("Keep your torso in view") }
        lastUsable=time
        val rise=fixed.down-current.down
        val progress=(rise/(fixed.down-.08)).coerceIn(0.0,1.0)
        if(phase==Phase.ready && rise>=.2) { phase=Phase.lifting;departure=time.seconds }
        if(phase==Phase.lifting && rise<.12) { phase=Phase.ready;departure=null;endpointSince=null }
        if(phase==Phase.raised && current.down<highest) { highest=current.down;highestAt=time.seconds }
        val endpoint=when(phase) {
            Phase.lifting -> current.down<=.08 && rise>=.5
            Phase.raised -> current.down>=max(.5,fixed.down-.2)
            else -> false
        }
        if(!endpoint) endpointSince=null else {
            if(endpointSince==null) endpointSince=time
            if(elapsed(time,checkNotNull(endpointSince),3,25)?.let { it>=0 }==true) {
                endpointSince=null
                if(phase==Phase.lifting) {
                    phase=Phase.raised;highestAt=time.seconds;highest=current.down
                    if(setStart==null) setStart=checkNotNull(departure)
                } else {
                    rep=Rep(checkNotNull(departure),checkNotNull(highestAt),time.seconds)
                    reps+=rep;cycles++;phase=Phase.ready;departure=null;highestAt=null;highest=Double.POSITIVE_INFINITY
                }
            }
        }
        return result(if(phase==Phase.raised) "Lower your legs" else "Raise knees to hip height",true,progress)
    }
    private fun distance(a: Point2D,b: Point2D)=hypot(a.x-b.x,a.y-b.y)
    private fun measure(pose: PoseResult,side: ArmMeasurement.Side): Body? {
        val prefix=if(side==ArmMeasurement.Side.left) "left" else "right"
        fun point(name: String)=pose.people.single().landmarks.singleOrNull { it.joint.name==prefix+name }?.let {
            it.position.takeIf { pt -> it.isVisible() && pt.x in 0.0..pose.imageSize.width && pt.y in 0.0..pose.imageSize.height }
        }
        val shoulder=point("Shoulder") ?: return null;val wrist=point("Wrist") ?: return null
        val hip=point("Hip") ?: return null;val knee=point("Knee") ?: return null
        val length=distance(shoulder,hip)
        if(length<2*ArmMeasurement.minimumSegmentFraction*pose.imageSize.height || distance(hip,knee)<.15*length) return null
        return Body(side,shoulder,wrist,hip,knee,length)
    }
}
