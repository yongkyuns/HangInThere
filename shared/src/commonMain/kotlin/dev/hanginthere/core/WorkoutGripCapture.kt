package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlin.math.hypot

/** Placement advice only. Body scale is a distance proxy, never grip confidence
 * or a calibrated physical distance. It cannot change readiness or count reps. */
@Serializable data class GripCaptureAssessment(
    val state: String,
    val shoulderFraction: Double? = null,
    val torsoFraction: Double? = null,
    val leftHand: String = "waiting",
    val rightHand: String = "waiting",
    val observationAge: Double? = null,
    val minimumShoulderFraction: Double = WorkoutGripCapture.minimumShoulderFraction,
    val minimumTorsoFraction: Double = WorkoutGripCapture.minimumTorsoFraction,
    val policyVersion: Int = WorkoutGripCapture.policyVersion,
    val bodyContext: String = "waitingForObservation",
    val failure: String? = null,
)

object WorkoutGripCapture {
    const val policyVersion = 1
    // Initial placement targets from the exposed development footage. These
    // intentionally do not claim a validated grip-recognition working range.
    const val minimumShoulderFraction = .18
    const val minimumTorsoFraction = .28
    const val edgeMarginFraction = .035

    fun assess(pose: PoseResult): GripCaptureAssessment {
        val size = pose.imageSize
        if (!size.isValid || !pose.timestamp.seconds.isFinite()) return GripCaptureAssessment("unavailable")
        if (pose.people.isEmpty()) return GripCaptureAssessment("stepIntoFrame")
        val person = pose.people.singleOrNull() ?: return GripCaptureAssessment("oneAthlete")
        if (person.landmarks.map { it.joint }.distinct().size != person.landmarks.size)
            return GripCaptureAssessment("unavailable")
        val short = minOf(size.width, size.height)
        fun inside(p: Point2D) = p.isFinite && p.x in 0.0..size.width && p.y in 0.0..size.height
        fun primary(joint: PoseJoint) = person.landmark(joint, .5)?.position?.takeIf(::inside)
        val ls = primary(PoseJoint.leftShoulder)
        val rs = primary(PoseJoint.rightShoulder)
        val shoulders = if (ls != null && rs != null) hypot(ls.x-rs.x, ls.y-rs.y)/short else null
        val frame = pose.gripObservation
        val age = frame?.seconds?.let { pose.timestamp.seconds-it }?.takeIf { it.isFinite() && it >= 0 }
        val fresh = frame != null && frame.imageSize == size && age != null && age <= WorkoutGripGeometry.maximumAgeSeconds
        val basis = if (fresh) WorkoutGripGeometry.bodyBasis(frame!!) else null
        // A recent observation must also agree with the CURRENT single athlete.
        val associated = fresh && basis != null && listOf(PoseJoint.leftShoulder, PoseJoint.rightShoulder,
            PoseJoint.leftWrist, PoseJoint.rightWrist).all { joint ->
            val p = primary(joint)
            val observed = frame!!.trackedBody.singleOrNull { it.joint.name == joint.name }
            p != null && observed != null && observed.score >= .5 &&
                hypot(p.x-observed.position.x, p.y-observed.position.y) <= short*.12
        }
        fun hip(joint: PoseJoint): Point2D? = primary(joint) ?: if (associated)
            frame!!.body.singleOrNull { it.joint.name == joint.name }?.position?.takeIf(::inside) else null
        val lh = hip(PoseJoint.leftHip)
        val rh = hip(PoseJoint.rightHip)
        val torso = if (ls != null && rs != null && lh != null && rh != null)
            hypot((ls.x+rs.x-lh.x-rh.x)*.5, (ls.y+rs.y-lh.y-rh.y)*.5)/short else null
        val wrists = listOf(PoseJoint.leftWrist, PoseJoint.rightWrist).map { joint ->
            person.landmark(joint, .5)?.position
        }
        fun handStatus(side: ArmMeasurement.Side): String {
            val wrist = wrists[if (side == ArmMeasurement.Side.left) 0 else 1] ?: return "wristUnclear"
            if (!inside(wrist) || wrist.x < short*edgeMarginFraction || wrist.y < short*edgeMarginFraction ||
                size.width-wrist.x < short*edgeMarginFraction || size.height-wrist.y < short*edgeMarginFraction)
                return "edge"
            if (!associated) return "waiting"
            val matches = frame!!.hands.filter { WorkoutGripGeometry.hasHandIdentity(frame, it, side) }
            if (matches.size != 1 || frame.hands.size > 2) return "notDetected"
            val hand = matches.single()
            val required = setOf(GripHandJoint.wrist, GripHandJoint.indexMCP, GripHandJoint.middleMCP, GripHandJoint.pinkyMCP)
            val valid = hand.points.filter { it.joint in required }.let { points ->
                points.size == required.size && points.map { it.joint }.toSet() == required && points.all { point ->
                    inside(point.position) && (point.score == null || point.score.isFinite() && point.score in .5..1.0) &&
                        (hand.sourceRegion?.let { r -> r.isValidFor(size) && point.position.x in r.left.toDouble()..(r.left+r.width).toDouble() &&
                            point.position.y in r.top.toDouble()..(r.top+r.height).toDouble() } ?: true)
                }
            }
            return if (valid) "detected" else "notDetected"
        }
        val left = handStatus(ArmMeasurement.Side.left)
        val right = handStatus(ArmMeasurement.Side.right)
        val context = when {
            frame == null -> "waitingForObservation"
            frame.imageSize != size -> "imageMismatch"
            age == null -> "timestampMismatch"
            !fresh -> "staleObservation"
            frame.failure != null -> "inferenceFailure"
            basis == null -> "bodyFrameUnavailable"
            !associated -> "athleteMismatch"
            !WorkoutGripGeometry.supportsView(basis) -> "viewUnsupported"
            else -> "frameMatched"
        }
        fun result(state: String) = GripCaptureAssessment(state, shoulders, torso, left, right, age,
            bodyContext = context, failure = frame?.failure?.takeIf { fresh })
        if (shoulders == null || shoulders <= .02 || torso == null || torso <= .04) return result("showUpperBody")
        if (associated && !WorkoutGripGeometry.supportsView(basis!!)) return result("frontRear")
        if (left == "edge" || right == "edge") return result("handsClipped")
        // Both must look small. Shoulder foreshortening or a tucked torso alone
        // must not be diagnosed as excessive camera distance.
        if (shoulders < minimumShoulderFraction && torso < minimumTorsoFraction) return result("moveCloser")
        if (left == "wristUnclear" || right == "wristUnclear") return result("showHands")
        if (frame?.failure != null && fresh) return result("analysisUnavailable")
        if (!associated) return result("checking")
        if (left != "detected" || right != "detected") return result("showHands")
        return result("handsDetected")
    }
}

/** Source-time debounce prevents distance/visibility prompts flickering with
 * pose jitter. Missing/stale/foreign hand evidence clears a green state at once. */
class WorkoutGripCaptureBridge {
    private var last: Double? = null
    private var candidate: String? = null
    private var since = 0.0
    private var displayed: String? = null
    fun reset() { last = null; candidate = null; displayed = null }
    @Throws(IllegalArgumentException::class)
    fun assessJson(poseJson: String): String = WorkoutRecordCodec.json.encodeToString(
        WorkoutGripCapture.assess(WorkoutRecordCodec.json.decodeFromString<PoseResult>(poseJson)))
    @Throws(IllegalArgumentException::class)
    fun observeJson(poseJson: String): String {
        val pose = WorkoutRecordCodec.json.decodeFromString<PoseResult>(poseJson)
        val time = pose.timestamp.seconds
        if (!time.isFinite() || last?.let { time <= it || time-it > .5 } == true) reset()
        val raw = WorkoutGripCapture.assess(pose)
        val previous = displayed
        if (candidate != raw.state) { candidate = raw.state; since = time }
        val immediate = raw.state in setOf("unavailable", "stepIntoFrame", "oneAthlete", "analysisUnavailable", "checking") ||
            previous == "handsDetected" && raw.state != "handsDetected"
        if (previous == null || immediate || time-since >= .4) displayed = raw.state
        last = time
        return WorkoutRecordCodec.json.encodeToString(raw.copy(state = displayed ?: raw.state))
    }
}
