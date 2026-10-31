package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.math.*

/** Source-owned push-up cycles. The default preserves the side-only evaluation
 * contract; the app also enables a distinct bilateral front-view profile.
 * Missing leg joints do not establish straight-leg form. Owns source-bound association;
 * the caller supplies independent native camera evidence, never a pose-derived flag.
 * Not connected to automatic exercise selection until separate-video qualification.
 */
class SideViewPushUpMotion(private val allowFrontView: Boolean = false) {
    @Serializable enum class View { side, front }
    @Serializable enum class Camera { ready, unavailable, moved }
    @Serializable enum class Phase { seekingPlank, ready, lowering, lowered }
    @Serializable enum class Cue(val title: String) {
        placePhone("Keep the phone still"), landscape("Turn your phone sideways"),
        wholeBody("Show your hip and a knee or ankle"), closer("Move a little closer"),
        sidePlank("Show a side-on plank"), straightLegs("Hold a straight plank"),
        acquiring("Hold for a moment"), ready("Ready — lower your chest"),
        push("Push back up"), armHidden("Keep your arm in view"),
        athleteUnclear("Keep the athlete clearly in view"), complete("Set complete"),
        frontPlank("Face the camera · hold a plank"), bothArms("Show both shoulders, elbows and hands"),
        upperBody("Show your arms and upper body"), frontHips("Show your hips to start")
    }
    @Serializable data class Rep(val startedAt: Double, val deepestAt: Double, val returnedAt: Double)
    @Serializable data class SetSummary(val index: Int, val startedAt: Double,
        val endedAt: Double? = null, val endReason: String? = null, val reps: List<Rep> = emptyList(),
        val policyVersion: Int = SideViewPushUpMotion.policyVersion)
    @Serializable data class Snapshot(val phase: Phase, val cue: Cue, val title: String,
        val cycles: Int, val setCycles: Int, val setStarted: Boolean, val side: ArmMeasurement.Side?,
        val progress: Double?, val targetReached: Boolean, val observationUsable: Boolean,
        val rep: Rep? = null, val endedSet: SetSummary? = null,
        val association: BodyAthleteAssociation.Decision? = null,
        val setupHint: String = SideViewPushUpMotion.setupHint, val view: View? = null)

    companion object {
        const val policyVersion = 2
        const val frontPolicyVersion = 1
        const val frontCountMethod = "frontViewPushUp"
        const val setupHint = "Side view · Steady phone · Arm, hip and knee or ankle visible"
        private val zero = SourceTimeLimit(0, 1)
        private val frameGap = SourceTimeLimit(7, 20)
        private val bodyGap = SourceTimeLimit(1, 1)
        private val calibrationDwell = SourceTimeLimit(3, 5)
        private val endpointDwell = SourceTimeLimit(3, 25)
        private val releaseDwell = SourceTimeLimit(2, 5)
        private fun elapsed(end: PresentationTime, start: PresentationTime, limit: SourceTimeLimit) =
            SourceTimeComparison.compareElapsed(end, start, limit)
    }
    private data class Measurement(val side: ArmMeasurement.Side, val degrees: Double?,
        val shoulder: Point2D?, val wrist: Point2D?, val armLength: Double?,
        val bodyDegrees: Double?, val bodyAxis: Double?, val kneeDegrees: Double?, val bodySpan: Double?) {
        fun plank(width: Double) = bodyDegrees != null && bodyDegrees >= 150 &&
            bodyAxis != null && bodyAxis <= 30 && bodySpan != null && bodySpan >= .4*width
    }
    private data class Calibration(val since: PresentationTime, val samples: MutableList<Measurement>)
    private data class Anchor(val degrees: Double, val shoulderY: Double, val wrist: Point2D, val armLength: Double,
        val otherWrist: Point2D? = null)
    private val source = BodyAthleteSelection(if (allowFrontView) BodyAthleteAssociation.Profile.pushUp else BodyAthleteAssociation.Profile.sideView)
    private var view: View? = null
    private var frontCalibration: Calibration? = null
    private var highWater: PresentationTime? = null
    private var generation: Long? = null
    private var phase = Phase.seekingPlank
    private var side: ArmMeasurement.Side? = null
    private var anchor: Anchor? = null
    private val candidates = mutableMapOf<ArmMeasurement.Side, Calibration>()
    private var endpointSince: PresentationTime? = null
    private var releaseSince: PresentationTime? = null
    private var lastArm: PresentationTime? = null
    private var lastBody: PresentationTime? = null
    private var departure: PresentationTime? = null
    private var deepest: PresentationTime? = null
    private var deepestDrop = 0.0
    private var serial = 0
    private var cycles = 0
    private var active: SetSummary? = null
    var latestCompletedSet: SetSummary? = null; private set
    val currentSet get() = active
    val selectedPose get() = source.selectedPose
    internal val attemptStartedAt get() = departure?.seconds

    private fun clearAttempt() {
        phase = Phase.seekingPlank; side = null; anchor = null; candidates.clear()
        view = null; frontCalibration = null
        endpointSince = null; lastArm = null; lastBody = null; departure = null
        deepest = null; deepestDrop = 0.0
    }
    private fun close(time: PresentationTime, reason: String): SetSummary? {
        val ended = active?.copy(endedAt = time.seconds, endReason = reason)
        if (ended != null) latestCompletedSet = ended
        active = null; clearAttempt(); releaseSince = null
        return ended
    }
    /** Explicit source/app interruption cannot complete a pending rep. */
    fun finish(reason: String = "endOfInput"): SetSummary? {
        require(reason.isNotBlank() && reason.length <= 64)
        return highWater?.let { close(it, reason) }
    }

    fun observe(pose: PoseResult, camera: Camera): Snapshot {
        val time = pose.timestamp
        var rep: Rep? = null
        var ended: SetSummary? = null
        var decision: BodyAthleteAssociation.Decision? = null
        fun result(cue: Cue, usable: Boolean = false, progress: Double? = null) = Snapshot(
            phase, cue, cue.title, cycles, active?.reps?.size ?: 0, active != null, side,
            progress, usable && phase == Phase.lowered, usable, rep, ended, decision,
            when {
                view == View.front -> "Front view · Steady phone · Both arms visible"
                allowFrontView && view == null -> "Front or side · Steady phone · Arms and hips visible"
                else -> setupHint
            }, view)
        if (!time.seconds.isFinite() || time.seconds < 0 ||
            highWater?.let { elapsed(time, it, zero) != 1 } == true) {
            highWater?.let { ended = close(it, "invalidSourceClock") }
            return result(Cue.acquiring)
        }
        if (highWater?.let { elapsed(time, it, frameGap) == 1 } == true)
            ended = close(time, "sourceTimeGap")
        highWater = time
        decision = source.observe(pose)
        if (generation != null && generation != decision.sourceGeneration)
            ended = close(time, "athleteEpochChanged") ?: ended
        generation = decision.sourceGeneration
        if (camera != Camera.ready) {
            ended = close(time, if (camera == Camera.moved) "cameraMoved" else "cameraUnavailable") ?: ended
            return result(Cue.placePhone)
        }
        if (pose.imageSize.width <= pose.imageSize.height) {
            ended = close(time, "portraitFraming") ?: ended
            return result(Cue.landscape)
        }
        if (decision.status != BodyAthleteAssociation.Status.associated) {
            endpointSince = null; releaseSince = null; candidates.clear(); frontCalibration = null
            if (decision.status == BodyAthleteAssociation.Status.lost ||
                lastArm?.let { elapsed(time, it, frameGap) == 1 } == true)
                ended = close(time, "athleteAssociationLost") ?: ended
            return result(if (pose.people.size > 1) Cue.athleteUnclear else if (allowFrontView) Cue.upperBody else Cue.wholeBody)
        }
        val selected = checkNotNull(source.selectedPose)
        val width = selected.imageSize.width
        val observed = ArmMeasurement.Side.entries.associateWith { measure(selected, it) }
        val front = if (allowFrontView) measureFront(selected, observed) else null
        // Expire old evidence BEFORE accepting a newly returned measurement.
        if (side != null && (lastArm?.let { elapsed(time, it, frameGap) == 1 } == true ||
            lastBody?.let { elapsed(time, it, bodyGap) == 1 } == true)) {
            ended = close(time, "bodyTrackingLost") ?: ended
        }
        val m = if (view == View.front) front else side?.let { observed.getValue(it) }
        if (m != null) {
            if (m.degrees != null) lastArm = time
            if (if (view == View.front) frontSupported(selected, m) else m.plank(width)) lastBody = time
            val released = view != View.front && (m.kneeDegrees?.let { it < 140 } == true ||
                (m.bodyDegrees != null && !m.plank(width)))
            if (released) {
                // Positive current posture earns release dwell, never missing joints.
                endpointSince = null; candidates.clear(); departure = null; deepest = null
                if (releaseSince == null) releaseSince = time
                if (elapsed(time, releaseSince!!, releaseDwell)?.let { it >= 0 } == true) {
                    ended = close(time, "postureReleased") ?: ended
                    return result(if (ended != null) Cue.complete else Cue.straightLegs)
                }
                phase = Phase.ready
                return result(Cue.straightLegs)
            }
            if (releaseSince != null) {
                // Even a brief observed kneel cancels the attempt and requires a fresh plank.
                clearAttempt(); releaseSince = null
            }
        }
        if (phase == Phase.seekingPlank) {
            if (front != null && front.degrees!! >= 155 && frontPlank(selected, front)) {
                val calibration = frontCalibration ?: Calibration(time, mutableListOf()).also { frontCalibration = it }
                calibration.samples += front
                if (elapsed(time, calibration.since, calibrationDwell)?.let { it >= 0 } == true) {
                    val samples = calibration.samples
                    side = ArmMeasurement.Side.left; view = View.front
                    anchor = Anchor(samples.map { it.degrees!! }.average(), samples.map { it.shoulder!!.y }.average(),
                        front.wrist!!, samples.map { it.armLength!! }.average(),
                        selected.people.single().landmark(PoseJoint.rightWrist)!!.position)
                    lastArm = time; lastBody = time; phase = Phase.ready; candidates.clear(); frontCalibration = null
                    return result(Cue.ready, true, 0.0)
                }
            } else frontCalibration = null
            for (s in ArmMeasurement.Side.entries) {
                val value = observed.getValue(s)
                if (value.degrees == null || value.degrees < 150 || !value.plank(width) ||
                    value.kneeDegrees?.let { it < 150 } == true) {
                    candidates.remove(s); continue
                }
                val calibration = candidates.getOrPut(s) { Calibration(time, mutableListOf()) }
                calibration.samples += value
                if (elapsed(time, calibration.since, calibrationDwell)?.let { it >= 0 } == true) {
                    val samples = calibration.samples
                    side = s; view = View.side
                    anchor = Anchor(samples.map { it.degrees!! }.average(), samples.map { it.shoulder!!.y }.average(),
                        Point2D(samples.map { it.wrist!!.x }.average(), samples.map { it.wrist!!.y }.average()),
                        samples.map { it.armLength!! }.average())
                    lastArm = time; lastBody = time; phase = Phase.ready; candidates.clear()
                    return result(Cue.ready, true, 0.0)
                }
            }
            if (candidates.isNotEmpty() || frontCalibration != null) return result(Cue.acquiring)
            if (allowFrontView && source.selectedPose?.people?.singleOrNull()?.let { person ->
                val a = person.landmark(PoseJoint.leftShoulder)?.position
                val b = person.landmark(PoseJoint.rightShoulder)?.position
                a != null && b != null && abs(a.x-b.x) >= .12*width
            } == true) return result(when {
                front == null -> Cue.bothArms
                selected.people.single().let { it.landmark(PoseJoint.leftHip) == null && it.landmark(PoseJoint.rightHip) == null } -> Cue.frontHips
                else -> Cue.frontPlank
            })
            val visible = observed.values.firstOrNull { it.degrees != null } ?: return result(Cue.armHidden)
            return result(when {
                visible.bodyDegrees == null -> Cue.wholeBody
                visible.bodyAxis!! > 30 -> Cue.sidePlank
                visible.bodySpan!! < .4*width -> Cue.closer
                else -> Cue.straightLegs
            })
        }
        val value = (if (view == View.front) front else observed.getValue(checkNotNull(side)))
            ?: run { endpointSince = null; return result(Cue.bothArms) }
        val fixed = checkNotNull(anchor)
        if (value.degrees == null || !(if (view == View.front) frontSupported(selected, value) else value.plank(width))) {
            endpointSince = null
            return result(if (view == View.front) Cue.frontPlank else if (value.degrees == null) Cue.armHidden else Cue.wholeBody)
        }
        val drift = hypot(value.wrist!!.x-fixed.wrist.x, value.wrist.y-fixed.wrist.y)/fixed.armLength
        val otherDrift = fixed.otherWrist?.let { prior ->
            selected.people.single().landmark(PoseJoint.rightWrist)?.position?.let {
                hypot(it.x-prior.x, it.y-prior.y)/fixed.armLength
            }
        } ?: 0.0
        if (max(drift, otherDrift) > .3) {
            ended = close(time, "handSupportMoved") ?: ended
            return result(Cue.acquiring)
        }
        val drop = (value.shoulder!!.y-fixed.shoulderY)/fixed.armLength
        val bottomAngle = if (view == View.front) 120.0 else 110.0
        val progress = min((fixed.degrees-value.degrees)/(fixed.degrees-bottomAngle), drop/.2).coerceIn(0.0, 1.0)
        if (phase == Phase.ready && drop >= .05 && value.degrees <= fixed.degrees-10) {
            departure = time; phase = Phase.lowering
        }
        if (phase == Phase.lowering && drop <= .02 && value.degrees >= fixed.degrees-15) {
            phase = Phase.ready; departure = null; endpointSince = null
        }
        if (phase == Phase.lowered && drop > deepestDrop) { deepestDrop = drop; deepest = time }
        val bothReturned = view != View.front || ArmMeasurement.Side.entries.all {
            ArmMeasurement(selected, it).estimate?.elbowDegrees?.let { d -> d >= fixed.degrees-15 } == true
        }
        val endpoint = if (phase != Phase.lowered) value.degrees <= bottomAngle && drop >= .2 else
            value.degrees >= fixed.degrees-15 && drop <= .15 && bothReturned
        if (!endpoint) endpointSince = null else {
            if (endpointSince == null) endpointSince = time
            if (elapsed(time, endpointSince!!, endpointDwell)?.let { it >= 0 } == true) {
                endpointSince = null
                if (phase != Phase.lowered) {
                    phase = Phase.lowered; deepestDrop = drop; deepest = time
                    if (departure == null) departure = time
                    if (active == null) active = SetSummary(serial++, departure!!.seconds,
                        policyVersion = if (view == View.front) frontPolicyVersion else policyVersion)
                } else {
                    rep = Rep(checkNotNull(departure).seconds, checkNotNull(deepest).seconds, time.seconds)
                    active = checkNotNull(active).copy(reps = active!!.reps + rep)
                    cycles++; phase = Phase.ready; departure = null; deepest = null; deepestDrop = 0.0
                }
            }
        }
        return result(if (phase == Phase.lowered) Cue.push else Cue.ready, true, progress)
    }

    // Front measurements require BOTH current arms. Hips establish the initial
    // receding plank, but never fill hidden arm endpoints during a repetition.
    private fun measureFront(pose: PoseResult, arms: Map<ArmMeasurement.Side, Measurement>): Measurement? {
        val l = arms.getValue(ArmMeasurement.Side.left); val r = arms.getValue(ArmMeasurement.Side.right)
        if (l.degrees == null || r.degrees == null || abs(l.degrees-r.degrees) > 35) return null
        val scale = (l.armLength!!+r.armLength!!)/2
        val span = abs(l.shoulder!!.x-r.shoulder!!.x)
        if (span < max(.12*pose.imageSize.width, .4*scale) || abs(l.shoulder.y-r.shoulder.y) > .3*span) return null
        // The larger angle means both elbows must reach the bottom; the smaller
        // angle on return is checked independently at the return endpoint.
        return Measurement(ArmMeasurement.Side.left, max(l.degrees, r.degrees),
            Point2D((l.shoulder.x+r.shoulder.x)/2, (l.shoulder.y+r.shoulder.y)/2),
            l.wrist, scale, null, null, null, null)
    }
    private fun frontSupported(pose: PoseResult, m: Measurement): Boolean {
        val p = pose.people.single()
        val wrists = listOf(PoseJoint.leftWrist, PoseJoint.rightWrist).map { p.landmark(it)?.position ?: return false }
        val shoulders = listOf(PoseJoint.leftShoulder, PoseJoint.rightShoulder).map { p.landmark(it)?.position ?: return false }
        return wrists.zip(shoulders).all { (w, s) -> w.y-s.y >= .15*m.armLength!! } &&
            abs(wrists[0].x-wrists[1].x) >= .5*abs(shoulders[0].x-shoulders[1].x)
    }
    private fun frontPlank(pose: PoseResult, m: Measurement): Boolean {
        if (!frontSupported(pose, m)) return false
        val p = pose.people.single()
        val hips = listOf(PoseJoint.leftHip, PoseJoint.rightHip).mapNotNull { p.landmark(it)?.position }
        if (hips.isEmpty()) return false
        val y = hips.map { it.y }.average(); val x = hips.map { it.x }.average()
        return y >= m.shoulder!!.y+.1*m.armLength!! && y <= m.wrist!!.y-.12*m.armLength &&
            abs(x-m.shoulder.x) <= .6*abs(p.landmark(PoseJoint.leftShoulder)!!.position.x-p.landmark(PoseJoint.rightShoulder)!!.position.x) &&
            ArmMeasurement.Side.entries.all { ArmMeasurement(pose, it).estimate!!.elbowDegrees >= 155 }
    }

    private fun measure(pose: PoseResult, side: ArmMeasurement.Side): Measurement {
        val person = pose.people.single()
        fun point(joint: PoseJoint): Point2D? {
            val points = person.landmarks.filter { it.joint == joint }
            val p = points.singleOrNull() ?: return null
            return p.position.takeIf { p.isVisible() && it.x in 0.0..pose.imageSize.width && it.y in 0.0..pose.imageSize.height }
        }
        val shoulder = point(side.joints[0]); val wrist = point(side.joints[2])
        val left = side == ArmMeasurement.Side.left
        val hip = point(if (left) PoseJoint.leftHip else PoseJoint.rightHip)
        val knee = point(if (left) PoseJoint.leftKnee else PoseJoint.rightKnee)
        val ankle = point(if (left) PoseJoint.leftAnkle else PoseJoint.rightAnkle)
        val floor = ArmMeasurement.minimumSegmentFraction*min(pose.imageSize.width, pose.imageSize.height)
        fun angle(a: Point2D?, b: Point2D?, c: Point2D?): Double? {
            if (a == null || b == null || c == null || min(hypot(a.x-b.x, a.y-b.y), hypot(c.x-b.x, c.y-b.y)) < floor) return null
            return PoseGeometry.elbowAngle(a, b, c)
        }
        val arm = ArmMeasurement(pose, side).estimate
        // Prefer the full observed line. A missing ankle can use the knee,
        // without synthesizing a foot or certifying the unseen lower leg.
        val bodyEnd = ankle ?: knee
        val body = angle(shoulder, hip, bodyEnd)
        return Measurement(side, arm?.elbowDegrees, shoulder, wrist,
            arm?.let { it.upperArmPixels+it.forearmPixels }, body,
            body?.let { atan2(abs(bodyEnd!!.y-shoulder!!.y), abs(bodyEnd.x-shoulder.x))*180/PI },
            angle(hip, knee, ankle), body?.let { abs(bodyEnd!!.x-shoulder!!.x) })
    }
}

/** Apple uses the identical source owner and counter through this small JSON ABI. */
class SideViewPushUpMotionBridge(allowFrontView: Boolean = false) {
    private val motion = SideViewPushUpMotion(allowFrontView)
    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    fun observeJson(poseJson: String, camera: String): String = WorkoutRecordCodec.json.encodeToString(
        motion.observe(WorkoutRecordCodec.json.decodeFromString<PoseResult>(poseJson), SideViewPushUpMotion.Camera.valueOf(camera)))
    fun finishJson(reason: String): String = WorkoutRecordCodec.json.encodeToString(motion.finish(reason))
}
