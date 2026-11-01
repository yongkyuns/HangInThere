package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlin.math.*

/** Side-on bent-knee cycles, not form grading. All endpoints use current,
 * source-associated joints. Native camera stability is supplied independently. */
class SideViewSitUpMotion {
    @Serializable enum class Phase { seekingStart, ready, lifting, raised }
    @Serializable data class Rep(val startedAt: Double, val raisedAt: Double, val returnedAt: Double)
    @Serializable data class SetSummary(val startedAt: Double, val endedAt: Double, val endReason: String, val reps: List<Rep>)
    @Serializable data class Snapshot(val phase: Phase, val title: String, val cycles: Int,
        val setCycles: Int, val setStarted: Boolean, val side: ArmMeasurement.Side?,
        val progress: Double?, val targetReached: Boolean, val observationUsable: Boolean,
        val rep: Rep? = null, val endedSet: SetSummary? = null)
    companion object {
        const val policyVersion = 2
        const val countMethod = "sideViewSitUp"
        const val boundaryPolicyVersion = 10
    }
    private data class Body(val side: ArmMeasurement.Side, val shoulder: Point2D, val hip: Point2D,
        val knee: Point2D, val ankle: Point2D?, val length: Double, val tilt: Double, val kneeAngle: Double?)
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
    private var highestY = Double.POSITIVE_INFINITY
    private var setStart: Double? = null
    private val reps = mutableListOf<Rep>()
    private var cycles = 0
    internal val attemptStartedAt get() = departure
    internal val extremeAt get() = highestAt
    private fun elapsed(a: PresentationTime, b: PresentationTime, n: Int, d: Int) =
        SourceTimeComparison.compareElapsed(a, b, SourceTimeLimit(n, d))
    private fun reset() {
        phase = Phase.seekingStart; baseline = null; candidate = null; since = null
        lastUsable = null; endpointSince = null; releaseSince = null; departure = null; highestAt = null
        highestY = Double.POSITIVE_INFINITY; setStart = null; reps.clear()
    }
    private fun close(at: Double, reason: String): SetSummary? {
        val ended = setStart?.let { SetSummary(it, at, reason, reps.toList()) }
        reset(); return ended
    }
    fun finish(reason: String = "endOfInput"): SetSummary? {
        require(reason.isNotBlank() && reason.length <= 64)
        return clock?.let { close(it.seconds, reason) }
    }
    fun observe(pose: PoseResult, camera: SideViewPushUpMotion.Camera): Snapshot {
        val time = pose.timestamp
        var ended: SetSummary? = null
        var rep: Rep? = null
        fun result(title: String, usable: Boolean = false, progress: Double? = null) = Snapshot(
            phase, title, cycles, reps.size, setStart != null, baseline?.side, progress,
            usable && phase == Phase.raised, usable, rep, ended)
        if (!time.seconds.isFinite() || time.seconds < 0 || clock?.let { elapsed(time,it,0,1) != 1 } == true) {
            clock?.let { ended = close(it.seconds,"invalidSourceClock") }
            return result("Waiting for fresh body detection")
        }
        if (clock?.let { elapsed(time,it,7,20) == 1 } == true) ended = close(time.seconds,"sourceTimeGap")
        clock = time
        val decision = source.observe(pose)
        if (generation != null && generation != decision.sourceGeneration) ended = close(time.seconds,"athleteEpochChanged") ?: ended
        generation = decision.sourceGeneration
        if (camera != SideViewPushUpMotion.Camera.ready) {
            ended = close(time.seconds,if(camera == SideViewPushUpMotion.Camera.moved) "cameraMoved" else "cameraUnavailable") ?: ended
            return result("Keep the phone still")
        }
        if (pose.imageSize.width <= pose.imageSize.height) {
            ended = close(time.seconds,"portraitFraming") ?: ended
            return result("Turn your phone sideways")
        }
        if (lastUsable?.let { elapsed(time,it,1,2) == 1 } == true) ended = close(time.seconds,"bodyTrackingLost") ?: ended
        if (decision.status != BodyAthleteAssociation.Status.associated) {
            candidate = null; since = null; endpointSince = null; releaseSince = null
            if (decision.status == BodyAthleteAssociation.Status.lost) ended = close(time.seconds,"athleteAssociationLost") ?: ended
            return result("Show one athlete · side view")
        }
        val selected = checkNotNull(source.selectedPose)
        val bodies = ArmMeasurement.Side.entries.mapNotNull { measure(selected,it) }
        val current = baseline?.let { fixed -> bodies.firstOrNull { it.side == fixed.side } }
            ?: bodies.maxByOrNull { it.length }.takeIf { baseline == null }
        if (current == null) {
            candidate = null; since = null; endpointSince = null; releaseSince = null
            return result("Show shoulder, hip and knee")
        }
        val fixed = baseline
        if (fixed == null) {
            val shoulderPair = selected.people.single().let { p ->
                listOf(PoseJoint.leftShoulder,PoseJoint.rightShoulder).mapNotNull { p.landmark(it)?.position }
            }
            if (current.length < .18*pose.imageSize.height) { since = null; candidate = null; return result("Move a little closer") }
            if (shoulderPair.size == 2 && abs(shoulderPair[0].x-shoulderPair[1].x) > .65*current.length) {
                since = null; candidate = null; return result("Place the phone beside you")
            }
            val resting = abs(current.tilt) <= 20 && (current.kneeAngle?.let { it in 40.0..140.0 } ?: (current.hip.y-current.knee.y >= .15*current.length)) &&
                abs(current.knee.x-current.hip.x) >= .3*current.length
            if (!resting) { since = null; candidate = null; return result("Lie back · bend your knees") }
            if (candidate?.let { it.side == current.side && hypot(it.hip.x-current.hip.x,it.hip.y-current.hip.y) <= .15*it.length } != true) {
                candidate = current; since = time
            }
            if (elapsed(time,checkNotNull(since),3,5)?.let { it >= 0 } == true) {
                baseline = current; lastUsable = time; phase = Phase.ready
                return result("Ready · sit up",true,0.0)
            }
            return result("Hold for a moment")
        }
        val hipDrift = hypot(current.hip.x-fixed.hip.x,current.hip.y-fixed.hip.y)/fixed.length
        val kneeDrift = hypot(current.knee.x-fixed.knee.x,current.knee.y-fixed.knee.y)/fixed.length
        if (hipDrift > .3 || kneeDrift > .4) {
            endpointSince = null
            if (releaseSince == null) releaseSince = time
            // Confirm a relocation; one noisy hip/knee must not erase a set.
            if (elapsed(time,checkNotNull(releaseSince),2,5)?.let { it >= 0 } == true)
                ended = close(time.seconds,"positionChanged") ?: ended
            return result("Lie back · reset your position")
        }
        releaseSince = null
        if (current.length !in .5*fixed.length..1.5*fixed.length || current.tilt < -25 ||
            current.kneeAngle?.let { it !in 30.0..150.0 } == true) {
            endpointSince = null
            return result("Keep your knees bent · side view")
        }
        lastUsable = time
        val rise = (fixed.shoulder.y-current.shoulder.y)/fixed.length
        val progress = min((current.tilt-fixed.tilt)/(50-fixed.tilt),rise/.45).coerceIn(0.0,1.0)
        if (phase == Phase.ready && current.tilt >= fixed.tilt+12 && rise >= .15) {
            phase = Phase.lifting; departure = time.seconds
        }
        if (phase == Phase.lifting && current.tilt <= fixed.tilt+8 && rise <= .15) {
            phase = Phase.ready; departure = null; endpointSince = null
        }
        if (phase == Phase.raised && current.shoulder.y < highestY) { highestY = current.shoulder.y; highestAt = time.seconds }
        val endpoint = when (phase) {
            Phase.lifting -> current.tilt >= 50 && rise >= .45
            // Use the same lying-back posture as acquisition; a slightly
            // different resting shoulder projection must not trap a whole set.
            Phase.raised -> abs(current.tilt) <= 20 && abs(rise) <= .25
            else -> false
        }
        if (!endpoint) endpointSince = null else {
            if (endpointSince == null) endpointSince = time
            if (elapsed(time,checkNotNull(endpointSince),3,25)?.let { it >= 0 } == true) {
                endpointSince = null
                if (phase == Phase.lifting) {
                    phase = Phase.raised; highestAt = time.seconds; highestY = current.shoulder.y
                    if (setStart == null) setStart = checkNotNull(departure)
                } else {
                    rep = Rep(checkNotNull(departure),checkNotNull(highestAt),time.seconds)
                    reps += rep; cycles++; phase = Phase.ready; departure = null; highestAt = null; highestY = Double.POSITIVE_INFINITY
                }
            }
        }
        return result(if (phase == Phase.raised) "Lower all the way back" else "Ready · sit up",true,progress)
    }
    private fun measure(pose: PoseResult, side: ArmMeasurement.Side): Body? {
        val p = pose.people.single()
        val prefix = if (side == ArmMeasurement.Side.left) "left" else "right"
        fun point(name: String) = p.landmarks.singleOrNull { it.joint.name == prefix+name }?.let {
            it.position.takeIf { pt -> it.isVisible() && pt.x in 0.0..pose.imageSize.width && pt.y in 0.0..pose.imageSize.height }
        }
        val shoulder = point("Shoulder") ?: return null; val hip = point("Hip") ?: return null
        val knee = point("Knee") ?: return null; val ankle = point("Ankle")
        val length = hypot(shoulder.x-hip.x,shoulder.y-hip.y)
        val floor = ArmMeasurement.minimumSegmentFraction*pose.imageSize.height
        if (length < 2*floor || hypot(knee.x-hip.x,knee.y-hip.y) < floor) return null
        val kneeAngle = ankle?.takeIf { hypot(it.x-knee.x,it.y-knee.y) >= floor }?.let { PoseGeometry.elbowAngle(hip,knee,it) }
        return Body(side,shoulder,hip,knee,ankle,length,atan2(hip.y-shoulder.y,abs(hip.x-shoulder.x))*180/PI,kneeAngle)
    }
}
