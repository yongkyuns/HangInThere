package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlin.math.*

/** Side-view lying leg raises. Visible lift-return cycles with a stationary torso;
 * anatomical form, spinal contact and muscular effort are not measured. */
class LyingLegRaiseMotion {
    @Serializable enum class Phase { seekingStart, ready, lifting, raised }
    @Serializable data class Rep(val startedAt: Double, val raisedAt: Double, val returnedAt: Double)
    @Serializable data class SetSummary(val startedAt: Double, val endedAt: Double, val endReason: String, val reps: List<Rep>)
    @Serializable data class Snapshot(val phase: Phase, val title: String, val cycles: Int,
        val setCycles: Int, val setStarted: Boolean, val side: ArmMeasurement.Side?,
        val progress: Double?, val targetReached: Boolean, val observationUsable: Boolean,
        val rep: Rep? = null, val endedSet: SetSummary? = null)
    companion object {
        const val policyVersion = 3
        const val countMethod = "lyingLegRaise"
        const val boundaryPolicyVersion = 12
    }
    private data class Body(val side: ArmMeasurement.Side, val shoulder: Point2D,
        val hip: Point2D, val knee: Point2D, val ankle: Point2D?, val length: Double,
        val tilt: Double, val legTilt: Double, val kneeAngle: Double?, val tip: Point2D,
        val usesAnkle: Boolean, val legLength: Double)
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
    private var tuckSince: PresentationTime? = null
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
        endpointSince=null;releaseSince=null;tuckSince=null;departure=null;highestAt=null;highest=Double.POSITIVE_INFINITY
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
            candidate=null;since=null;endpointSince=null;releaseSince=null;tuckSince=null
            if(decision.status==BodyAthleteAssociation.Status.lost) ended=close(time.seconds,"athleteAssociationLost") ?: ended
            return result("Show one athlete · side view")
        }
        val selected=checkNotNull(source.selectedPose)
        val bodies=ArmMeasurement.Side.entries.mapNotNull { measure(selected,it,baseline?.usesAnkle) }
        val current=baseline?.let { fixed -> bodies.firstOrNull { it.side==fixed.side } }
            ?: bodies.maxByOrNull { it.length }.takeIf { baseline==null }
        if(current==null) { candidate=null;since=null;endpointSince=null;releaseSince=null;tuckSince=null;return result(if(baseline?.usesAnkle==true) "Keep shoulder, hip and foot visible" else "Show shoulder, hip and knee") }
        if(current.length < .16*min(pose.imageSize.width,pose.imageSize.height)) {
            since=null;candidate=null;endpointSince=null;tuckSince=null;return result("Move a little closer")
        }
        val lying=abs(current.tilt)<=20
        val straight=current.kneeAngle?.let { it>=145 } ?: true
        val fixed=baseline
        if(fixed==null) {
            if(!lying || !straight || abs(current.legTilt)>20 || (current.hip.x-current.shoulder.x)*(current.knee.x-current.hip.x)<=0) { candidate=null;since=null;return result("Lie back · legs extended") }
            if(candidate?.let { it.side==current.side && distance(it.hip,current.hip)<.15*it.length && distance(it.shoulder,current.shoulder)<.15*it.length }!=true) {
                candidate=current;since=time
            }
            // Source association has already established 600 ms of stable torso
            // tracking. Only the lowered-leg endpoint needs a fresh dwell here.
            if(elapsed(time,checkNotNull(since),3,25)?.let { it>=0 }==true) {
                baseline=current;lastUsable=time;phase=Phase.ready;return result("Ready · raise your legs",true,0.0)
            }
            return result("Hold for a moment")
        }
        val released=abs(current.tilt)>25 || distance(current.shoulder,fixed.shoulder)>.3*fixed.length || distance(current.hip,fixed.hip)>.3*fixed.length
        if(released) {
            endpointSince=null;tuckSince=null
            if(releaseSince==null) releaseSince=time
            if(elapsed(time,checkNotNull(releaseSince),2,5)?.let { it>=0 }==true) ended=close(time.seconds,"positionChanged") ?: ended
            return result("Keep your torso down")
        }
        releaseSince=null
        if(current.length !in .65*fixed.length..1.4*fixed.length) { endpointSince=null;tuckSince=null;return result("Keep your torso in view") }
        // A softened knee must not be mistaken for tracking loss or for a
        // completed tuck. Use the actual full-leg span when the ankle was visible
        // at acquisition; never switch endpoint joints during a cycle.
        lastUsable=time
        if(current.usesAnkle && current.legLength < .8*fixed.legLength) {
            // A deep tuck changes the exercise posture. Require a fresh low,
            // extended start instead of joining its later extension to a rep.
            if(current.kneeAngle?.let { it<120 }==true) {
                if(tuckSince==null) tuckSince=time
                if(elapsed(time,checkNotNull(tuckSince),3,25)?.let { it>=0 }==true)
                    ended=close(time.seconds,"legsTucked") ?: ended
            } else tuckSince=null
            endpointSince=null
            return result("Keep your legs extended")
        }
        tuckSince=null
        val rise=(fixed.tip.y-current.tip.y)/fixed.legLength
        val progress=min((current.legTilt-fixed.legTilt)/(55-fixed.legTilt),rise/.5).coerceIn(0.0,1.0)
        if(phase==Phase.ready && current.legTilt>=fixed.legTilt+12 && rise>=.12) { phase=Phase.lifting;departure=time.seconds }
        if(phase==Phase.lifting && current.legTilt<=fixed.legTilt+8 && rise<.12) { phase=Phase.ready;departure=null;endpointSince=null }
        if(phase==Phase.raised && current.tip.y<highest) { highest=current.tip.y;highestAt=time.seconds }
        val endpoint=when(phase) {
            Phase.lifting -> current.legTilt>=55 && rise>=.4
            Phase.raised -> abs(current.legTilt)<=20 && abs(rise)<=.35
            else -> false
        }
        if(!endpoint) endpointSince=null else {
            if(endpointSince==null) endpointSince=time
            if(elapsed(time,checkNotNull(endpointSince),3,25)?.let { it>=0 }==true) {
                endpointSince=null
                if(phase==Phase.lifting) {
                    phase=Phase.raised;highestAt=time.seconds;highest=current.tip.y
                    if(setStart==null) setStart=checkNotNull(departure)
                } else {
                    rep=Rep(checkNotNull(departure),checkNotNull(highestAt),time.seconds)
                    reps+=rep;cycles++;phase=Phase.ready;departure=null;highestAt=null;highest=Double.POSITIVE_INFINITY
                }
            }
        }
        return result(when(phase) {
            Phase.raised -> "Lower your legs"
            Phase.lifting -> "Lift higher"
            else -> "Raise your legs"
        },true,progress)
    }
    private fun distance(a: Point2D,b: Point2D)=hypot(a.x-b.x,a.y-b.y)
    private fun measure(pose: PoseResult,side: ArmMeasurement.Side,useAnkle: Boolean?): Body? {
        val prefix=if(side==ArmMeasurement.Side.left) "left" else "right"
        fun point(name: String)=pose.people.single().landmarks.singleOrNull { it.joint.name==prefix+name }?.let {
            it.position.takeIf { pt -> it.isVisible() && pt.x in 0.0..pose.imageSize.width && pt.y in 0.0..pose.imageSize.height }
        }
        val shoulder=point("Shoulder") ?: return null
        val hip=point("Hip") ?: return null;val knee=point("Knee") ?: return null;val ankle=point("Ankle")
        val length=distance(shoulder,hip)
        if(length<2*ArmMeasurement.minimumSegmentFraction*pose.imageSize.height || distance(hip,knee)<.15*length) return null
        val kneeAngle=ankle?.takeIf { distance(it,knee)>=ArmMeasurement.minimumSegmentFraction*pose.imageSize.height }?.let { PoseGeometry.elbowAngle(hip,knee,it) }
        val usesAnkle=useAnkle ?: (ankle!=null)
        val tip=if(usesAnkle) ankle ?: return null else knee
        val legLength=distance(hip,tip)
        if(legLength<.15*length) return null
        return Body(side,shoulder,hip,knee,ankle,length,
            atan2(hip.y-shoulder.y,abs(hip.x-shoulder.x))*180/PI,
            atan2(hip.y-tip.y,abs(hip.x-tip.x))*180/PI,kneeAngle,tip,usesAnkle,legLength)
    }
}
