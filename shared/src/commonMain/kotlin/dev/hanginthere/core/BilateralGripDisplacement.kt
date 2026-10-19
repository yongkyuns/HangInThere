package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.math.hypot
import kotlin.math.min

/** Image-space shoulder displacement against independently estimated hand MCP
 * centres. These are separate features: a hand centre never replaces a body
 * wrist or elbow. Geometry does not establish apparatus identity/contact, camera
 * stability, body load, anatomical bend, an exercise, or a completed repetition.
 * Callers own those gates and source-time continuity; this class accumulates no
 * dwell, events, or counts. In particular, small camera motion can retain a grip.
 */
object BilateralGripDisplacement {
    @ConsistentCopyVisibility
    data class Arm private constructor(val shoulder: Point2D, val wrist: Point2D, val grip: Point2D,
        val elbowDegrees: Double?, val projectedArmPixels: Double?) {
        companion object {
            internal fun create(shoulder: Point2D, wrist: Point2D, grip: Point2D, estimate: ArmMeasurement.Estimate?) =
                Arm(shoulder, wrist, grip, estimate?.elbowDegrees,
                    estimate?.let { it.upperArmPixels + it.forearmPixels })
        }
        val isStartingTop: Boolean get() = elbowDegrees?.let { it >= DipSupportMeasurement.startingDegrees } == true &&
            projectedArmPixels?.let { wrist.y - shoulder.y >= .35 * it } == true
    }

    @ConsistentCopyVisibility
    data class Sample private constructor(val timestamp: PresentationTime, val imageSize: ImageSize,
        val left: Arm, val right: Arm, val handPointsHaveScores: Boolean,
        internal val bodyBackend: String, internal val bodyRevision: Int,
        internal val handModel: String, internal val handBackend: String) {
        companion object {
            /** Checks the hand result's time, image and originating tracked-body
             * subset (shoulders/wrists/optional hips). That subset cannot prove a
             * full primary-pose/pixel/source-epoch identity: adapters must retain
             * the original primary pose in the inference context. Auxiliary 3D
             * body estimates and SDK handedness do not supply image correspondence.
             * Missing hand scores remain explicitly unscored. */
            fun create(pose: PoseResult): Sample? {
                val hands = pose.gripObservation ?: return null
                val person = pose.people.singleOrNull() ?: return null
                val size = pose.imageSize
                if (!size.isValid || !pose.timestamp.seconds.isFinite() || pose.backend.isBlank() ||
                    hands.schemaVersion != 1 || hands.failure != null || hands.coordinateConvention != "cameraRightDownAway_v1" ||
                    hands.seconds != pose.timestamp.seconds || hands.imageSize != size ||
                    hands.modelId.isBlank() || hands.backend.isBlank() || hands.hands.size != 2 ||
                    hands.trackedBody.size !in 4..6 || person.landmarks.size > 64 ||
                    person.landmarks.map { it.joint }.distinct().size != person.landmarks.size ||
                    hands.trackedBody.map { it.joint }.distinct().size != hands.trackedBody.size) return null
                fun inside(p: Point2D) = p.isFinite && p.x in 0.0..size.width && p.y in 0.0..size.height
                fun body(joint: PoseJoint) = person.landmarks.singleOrNull { it.joint == joint }
                    ?.takeIf { it.isVisible() && inside(it.position) }
                // Exact values document the originating tracked-body subset;
                // proximity to a different frame/person is insufficient.
                if (hands.trackedBody.any { point ->
                        val original = person.landmarks.singleOrNull { it.joint.name == point.joint.name }
                        original == null || original.position != point.position || original.confidence != point.score ||
                            !point.score.isFinite() || point.score !in 0.0..1.0 || !inside(point.position)
                    }) return null
                val sides = listOf(ArmMeasurement.Side.left, ArmMeasurement.Side.right)
                val shoulders = sides.map { body(it.joints[0])?.position ?: return null }
                val wrists = sides.map { body(it.joints[2])?.position ?: return null }
                if (sides.any { side -> side.joints.filterIndexed { i, _ -> i != 1 }.any { joint ->
                        hands.trackedBody.none { it.joint.name == joint.name }
                    } }) return null
                val short = min(size.width, size.height)
                if (distance(wrists[0], wrists[1]) < short * .12) return null
                val required = setOf(GripHandJoint.indexMCP, GripHandJoint.middleMCP, GripHandJoint.pinkyMCP)
                val centres = hands.hands.map { hand ->
                    if (hand.points.size !in 3..6 || hand.points.map { it.joint }.distinct().size != hand.points.size ||
                        hand.points.any { !inside(it.position) || it.score?.let { s -> !s.isFinite() || s !in 0.0..1.0 } == true }) return null
                    val region = hand.sourceRegion
                    if (region != null && !region.isValidFor(size)) return null
                    val points = hand.points.filter { it.joint in required }
                    if (points.size != 3 || points.any { point ->
                            point.score?.let { it < ArmMeasurement.confidenceThreshold } == true ||
                                region?.let { point.position.x !in it.left.toDouble()..(it.left.toDouble() + it.width) ||
                                    point.position.y !in it.top.toDouble()..(it.top.toDouble() + it.height) } == true
                        }) return null
                    Point2D(points.sumOf { it.position.x / 3 }, points.sumOf { it.position.y / 3 }).takeIf { it.isFinite } ?: return null
                }
                val associations = listOf(listOf(0, 1), listOf(1, 0)).filter { order ->
                    order.indices.all { distance(centres[order[it]], wrists[it]) <= short * .12 }
                }
                val order = associations.singleOrNull() ?: return null
                val arms = sides.indices.map { i -> Arm.create(shoulders[i], wrists[i], centres[order[i]], ArmMeasurement(pose, sides[i]).estimate) }
                return Sample(pose.timestamp, size, arms[0], arms[1],
                    hands.hands.all { h -> h.points.filter { it.joint in required }.all { it.score != null } },
                    pose.backend, pose.requestRevision, hands.modelId, hands.backend)
            }
        }
        val isStartingTop get() = left.isStartingTop && right.isStartingTop
    }

    @Serializable
    data class ArmDisplacement(val axialPixels: Double, val lateralPixels: Double, val gripDriftPixels: Double,
        val baselineArmPixels: Double, val retainsGrip: Boolean)
    @Serializable
    data class Measurement(val timestamp: PresentationTime, val left: ArmDisplacement, val right: ArmDisplacement,
        val handPointsHaveScores: Boolean)

    class Anchor private constructor(private val sample: Sample) {
        companion object {
            fun create(sample: Sample): Anchor? = sample.takeIf { it.isStartingTop && listOf(it.left, it.right).all { arm ->
                    val length = arm.projectedArmPixels ?: return@all false
                    val span = distance(arm.shoulder, arm.grip)
                    length.isFinite() && length > 0 && span.isFinite() && span > 0 && span >= .35 * length
                } }?.let(::Anchor)
        }
        fun measure(current: Sample): Measurement? {
            // Native source clocks may legitimately change rational timescale.
            // Exact ordering avoids Double collisions near large source ticks.
            if (SourceTimeComparison.compareElapsed(current.timestamp, sample.timestamp, SourceTimeLimit(0, 1)) != 1 ||
                current.imageSize != sample.imageSize ||
                current.bodyBackend != sample.bodyBackend || current.bodyRevision != sample.bodyRevision ||
                current.handModel != sample.handModel || current.handBackend != sample.handBackend) return null
            fun arm(top: Arm, now: Arm): ArmDisplacement {
                val span = distance(top.shoulder, top.grip)
                val x = (top.shoulder.x - top.grip.x) / span; val y = (top.shoulder.y - top.grip.y) / span
                val dx = now.shoulder.x - top.shoulder.x; val dy = now.shoulder.y - top.shoulder.y
                val drift = distance(now.grip, top.grip)
                val length = top.projectedArmPixels!!
                return ArmDisplacement(-(dx * x + dy * y), -dx * y + dy * x, drift, length,
                    drift <= DipSupportMeasurement.gripRetentionArmFraction * length)
            }
            val left = arm(sample.left, current.left); val right = arm(sample.right, current.right)
            if (listOf(left, right).any { !it.axialPixels.isFinite() || !it.lateralPixels.isFinite() || !it.gripDriftPixels.isFinite() }) return null
            return Measurement(current.timestamp, left, right,
                sample.handPointsHaveScores && current.handPointsHaveScores)
        }
    }
    private fun distance(a: Point2D, b: Point2D) = hypot(a.x - b.x, a.y - b.y)
}

/** Stateless Apple transport for the same shared geometry; no tracking policy. */
class BilateralGripDisplacementBridge {
    @Serializable private data class Input(val anchor: PoseResult, val current: PoseResult)
    @Throws(IllegalArgumentException::class)
    fun measureJson(input: String): String? {
        val frames = WorkoutRecordCodec.json.decodeFromString<Input>(input)
        val first = BilateralGripDisplacement.Sample.create(frames.anchor) ?: return null
        val next = BilateralGripDisplacement.Sample.create(frames.current) ?: return null
        val measurement = BilateralGripDisplacement.Anchor.create(first)?.measure(next) ?: return null
        return WorkoutRecordCodec.json.encodeToString(measurement)
    }
}
