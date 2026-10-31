package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.math.*

/** Experimental standard floor push-ups. Owns source-bound side-view association;
 * the caller supplies independent native camera evidence, never a pose-derived flag.
 * Not connected to automatic exercise selection until separate-video qualification.
 */
class SideViewPushUpMotion {
    @Serializable enum class Camera { ready, unavailable, moved }
    @Serializable enum class Phase { seekingPlank, ready, lowering, lowered }
    @Serializable enum class Cue(val title: String) {
        placePhone("Keep the phone still"), landscape("Turn your phone sideways"),
        wholeBody("Show your whole body"), closer("Move a little closer"),
        sidePlank("Show a side-on plank"), straightLegs("Straighten your legs"),
        acquiring("Hold for a moment"), ready("Ready — lower your chest"),
        push("Push back up"), armHidden("Keep your arm in view"),
        athleteUnclear("Keep the athlete clearly in view"), complete("Set complete")
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
        val setupHint: String = SideViewPushUpMotion.setupHint)

    companion object {
        const val policyVersion = 1
        const val setupHint = "Side view · Low, stable phone · Head to feet visible"
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
    private data class Anchor(val degrees: Double, val shoulderY: Double, val wrist: Point2D, val armLength: Double)
    private val source = BodyAthleteSelection(BodyAthleteAssociation.Profile.sideView)
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
            progress, usable && phase == Phase.lowered, usable, rep, ended, decision)
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
            endpointSince = null; releaseSince = null; candidates.clear()
            if (decision.status == BodyAthleteAssociation.Status.lost ||
                lastArm?.let { elapsed(time, it, frameGap) == 1 } == true)
                ended = close(time, "athleteAssociationLost") ?: ended
            return result(if (pose.people.size > 1) Cue.athleteUnclear else Cue.wholeBody)
        }
        val selected = checkNotNull(source.selectedPose)
        val width = selected.imageSize.width
        val observed = ArmMeasurement.Side.entries.associateWith { measure(selected, it) }
        val current = side?.let { observed.getValue(it) }
        // Expire old evidence BEFORE accepting a newly returned measurement.
        if (current != null && (lastArm?.let { elapsed(time, it, frameGap) == 1 } == true ||
            lastBody?.let { elapsed(time, it, bodyGap) == 1 } == true)) {
            ended = close(time, "bodyTrackingLost") ?: ended
        }
        val m = side?.let { observed.getValue(it) }
        if (m != null) {
            if (m.degrees != null) lastArm = time
            if (m.plank(width)) lastBody = time
            val released = m.kneeDegrees?.let { it < 140 } == true ||
                (m.bodyDegrees != null && !m.plank(width))
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
            for (s in ArmMeasurement.Side.entries) {
                val value = observed.getValue(s)
                if (value.degrees == null || value.degrees < 150 || !value.plank(width) ||
                    value.kneeDegrees == null || value.kneeDegrees < 150) {
                    candidates.remove(s); continue
                }
                val calibration = candidates.getOrPut(s) { Calibration(time, mutableListOf()) }
                calibration.samples += value
                if (elapsed(time, calibration.since, calibrationDwell)?.let { it >= 0 } == true) {
                    val samples = calibration.samples
                    side = s
                    anchor = Anchor(samples.map { it.degrees!! }.average(), samples.map { it.shoulder!!.y }.average(),
                        Point2D(samples.map { it.wrist!!.x }.average(), samples.map { it.wrist!!.y }.average()),
                        samples.map { it.armLength!! }.average())
                    lastArm = time; lastBody = time; phase = Phase.ready; candidates.clear()
                    return result(Cue.ready, true, 0.0)
                }
            }
            if (candidates.isNotEmpty()) return result(Cue.acquiring)
            val visible = observed.values.firstOrNull { it.degrees != null } ?: return result(Cue.armHidden)
            return result(when {
                visible.bodyDegrees == null -> Cue.wholeBody
                visible.bodyAxis!! > 30 -> Cue.sidePlank
                visible.bodySpan!! < .4*width -> Cue.closer
                else -> Cue.straightLegs
            })
        }
        val value = observed.getValue(checkNotNull(side))
        val fixed = checkNotNull(anchor)
        if (value.degrees == null || !value.plank(width)) {
            endpointSince = null
            return result(if (value.degrees == null) Cue.armHidden else Cue.wholeBody)
        }
        val drift = hypot(value.wrist!!.x-fixed.wrist.x, value.wrist.y-fixed.wrist.y)/fixed.armLength
        if (drift > .3) {
            ended = close(time, "handSupportMoved") ?: ended
            return result(Cue.acquiring)
        }
        val drop = (value.shoulder!!.y-fixed.shoulderY)/fixed.armLength
        val progress = min((fixed.degrees-value.degrees)/(fixed.degrees-110), drop/.2).coerceIn(0.0, 1.0)
        if (phase == Phase.ready && drop >= .05 && value.degrees <= fixed.degrees-10) {
            departure = time; phase = Phase.lowering
        }
        if (phase == Phase.lowering && drop <= .02 && value.degrees >= fixed.degrees-15) {
            phase = Phase.ready; departure = null; endpointSince = null
        }
        if (phase == Phase.lowered && drop > deepestDrop) { deepestDrop = drop; deepest = time }
        val endpoint = if (phase != Phase.lowered) value.degrees <= 110 && drop >= .2 else
            value.degrees >= fixed.degrees-15 && drop <= .15
        if (!endpoint) endpointSince = null else {
            if (endpointSince == null) endpointSince = time
            if (elapsed(time, endpointSince!!, endpointDwell)?.let { it >= 0 } == true) {
                endpointSince = null
                if (phase != Phase.lowered) {
                    phase = Phase.lowered; deepestDrop = drop; deepest = time
                    if (departure == null) departure = time
                    if (active == null) active = SetSummary(serial++, departure!!.seconds)
                } else {
                    rep = Rep(checkNotNull(departure).seconds, checkNotNull(deepest).seconds, time.seconds)
                    active = checkNotNull(active).copy(reps = active!!.reps + rep)
                    cycles++; phase = Phase.ready; departure = null; deepest = null; deepestDrop = 0.0
                }
            }
        }
        return result(if (phase == Phase.lowered) Cue.push else Cue.ready, true, progress)
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
        val body = angle(shoulder, hip, ankle)
        return Measurement(side, arm?.elbowDegrees, shoulder, wrist,
            arm?.let { it.upperArmPixels+it.forearmPixels }, body,
            body?.let { atan2(abs(ankle!!.y-shoulder!!.y), abs(ankle.x-shoulder.x))*180/PI },
            angle(hip, knee, ankle), body?.let { abs(ankle!!.x-shoulder!!.x) })
    }
}

/** Apple uses the identical source owner and counter through this small JSON ABI. */
class SideViewPushUpMotionBridge {
    private val motion = SideViewPushUpMotion()
    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    fun observeJson(poseJson: String, camera: String): String = WorkoutRecordCodec.json.encodeToString(
        motion.observe(WorkoutRecordCodec.json.decodeFromString<PoseResult>(poseJson), SideViewPushUpMotion.Camera.valueOf(camera)))
    fun finishJson(reason: String): String = WorkoutRecordCodec.json.encodeToString(motion.finish(reason))
}
