package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.math.hypot
import kotlin.math.min

/** Stateless correspondence of fresh, scored hand MCPs to original measured
 * body wrists. Preserves the original person index and every observed arm; no
 * selection by angle, SDK result order, score ranking or workout outcome.
 * This is geometric correspondence, not temporal athlete identity, apparatus,
 * contact/load, exercise classification, or permission to count a repetition.
 * Native owners must retain originating pixels/pose/source epoch together. */
object ObservedGripAssociation {
    const val policyVersion = 1
    private const val maximumPeople = 32
    /** Captured at the hand request's source boundary, never inferred from its
     * Double seconds or reassigned when a result is delivered. Exact rational
     * time does not prove image/epoch identity; the native owner retains those. */
    @Serializable data class Frame(val pose: PoseResult, val handSourceTime: PresentationTime)
    @Serializable data class Arm(val side: ArmMeasurement.Side, val handIndex: Int,
        val shoulder: Point2D, val wrist: Point2D, val grip: Point2D, val measurement: ArmMeasurement.Estimate)
    @Serializable @ConsistentCopyVisibility
    data class Sample private constructor(val timestamp: PresentationTime, val imageSize: ImageSize,
        val originalPersonIndex: Int, val arms: List<Arm>, val bodyBackend: String, val bodyRevision: Int,
        val handModel: String, val handBackend: String, val policyVersion: Int = ObservedGripAssociation.policyVersion) {
        companion object {
            internal fun create(pose: PoseResult, index: Int, arms: List<Arm>, hand: GripObservation) =
                Sample(pose.timestamp, pose.imageSize, index, arms.toList(), pose.backend, pose.requestRevision, hand.modelId, hand.backend)
        }
    }

    fun associate(frame: Frame): Sample? {
        val pose = frame.pose
        val hand = pose.gripObservation ?: return null
        val size = pose.imageSize
        if (frame.handSourceTime != pose.timestamp || !size.isValid || !pose.timestamp.seconds.isFinite() || pose.backend.isBlank() ||
            pose.people.size !in 1..maximumPeople || hand.schemaVersion != 1 || hand.failure != null ||
            hand.coordinateConvention != "cameraRightDownAway_v1" || hand.seconds != pose.timestamp.seconds ||
            hand.imageSize != size || hand.modelId.isBlank() || hand.backend.isBlank() || hand.hands.size !in 1..2 ||
            hand.trackedBody.size > 6 || hand.trackedBody.map { it.joint }.distinct().size != hand.trackedBody.size) return null
        if (pose.people.any { p -> p.landmarks.size > 64 || p.landmarks.map { it.joint }.distinct().size != p.landmarks.size }) return null
        val centres = hand.hands.map { GripMCPGeometry.measure(it, size, requiresScores = true)?.centre ?: return null }
        val tolerance = .12 * min(size.width, size.height)
        if (centres.size == 2 && distance(centres[0], centres[1]) < tolerance) return null
        val matches = centres.mapIndexed { handIndex, grip ->
            val possible = pose.people.flatMapIndexed { personIndex, person ->
                ArmMeasurement.Side.entries.mapNotNull { side ->
                    val measured = ArmMeasurement.observedPerson(person, size, side).estimate ?: return@mapNotNull null
                    val shoulder = person.landmark(side.joints[0])!!.position
                    val wrist = person.landmark(side.joints[2])!!.position
                    if (distance(grip, wrist) > tolerance) return@mapNotNull null
                    personIndex to Arm(side, handIndex, shoulder, wrist, grip, measured)
                }
            }
            possible.singleOrNull() ?: return null
        }
        val index = matches.map { it.first }.distinct().singleOrNull() ?: return null
        val arms = matches.map { it.second }.sortedBy { it.side.ordinal }
        if (arms.map { it.side }.distinct().size != arms.size) return null
        val original = pose.people[index]
        // Empty means independent full-image hand inference. If an adapter
        // supplies a tracked-body binding, every supplied value must be exact.
        if (hand.trackedBody.any { tracked ->
                val point = original.landmarks.singleOrNull { it.joint.name == tracked.joint.name }
                point == null || point.position != tracked.position || point.confidence != tracked.score ||
                    !tracked.score.isFinite() || tracked.score !in 0.0..1.0
            }) return null
        return Sample.create(pose, index, arms, hand)
    }
    private fun distance(a: Point2D, b: Point2D) = hypot(a.x - b.x, a.y - b.y)
}

class ObservedGripAssociationBridge {
    @Serializable private data class Search(val observation: ObservedGripAssociation.Sample, val regions: List<BarRegion>)
    /** Existing dip contact crops on uniquely corresponding observed arms.
     * This requests pixels only; it acquires no support and grants no rep. */
    @Throws(IllegalArgumentException::class)
    fun dipSearchJson(input: String): String? {
        val frame = WorkoutRecordCodec.json.decodeFromString<ObservedGripAssociation.Frame>(input)
        val sample = ObservedGripAssociation.associate(frame) ?: return null
        val regions = sample.arms.map { AutomaticDipRails.contactRegion(it.shoulder, it.grip, sample.imageSize) ?: return null }
        return WorkoutRecordCodec.json.encodeToString(Search(sample, regions))
    }
    @Throws(IllegalArgumentException::class)
    fun associateJson(input: String): String? = ObservedGripAssociation.associate(
        WorkoutRecordCodec.json.decodeFromString<ObservedGripAssociation.Frame>(input))?.let { WorkoutRecordCodec.json.encodeToString(it) }
}
