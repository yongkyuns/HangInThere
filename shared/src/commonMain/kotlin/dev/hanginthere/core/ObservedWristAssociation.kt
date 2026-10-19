package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.math.hypot
import kotlin.math.min

/** Same-image hand-wrist correspondence to original primary-body wrists.
 * Missing fingers or elbows do not erase a measured wrist. This establishes
 * neither anatomical side when candidates overlap, temporal identity, apparatus,
 * load, exercise nor a repetition. Native owners retain the original image,
 * primary pose and source epoch; a cached hand result cannot be retimestamped.
 */
object ObservedWristAssociation {
    const val policyVersion = 1

    @Serializable data class Frame(val pose: PoseResult, val handSourceTime: PresentationTime,
        /** False only for providers that return positions without joint scores.
         * A reported low score is always rejected; handedness is never a score. */
        val requiresJointConfidence: Boolean = true)

    @Serializable data class BodyWrist(val side: ArmMeasurement.Side, val position: Point2D)
    /** Unique person correspondence only. Several hands may still compete for
     * a body side; these are candidate edges, not independent limb assignments. */
    @Serializable data class Match(val originalPersonIndex: Int, val handIndex: Int,
        val observedWrist: Point2D, val jointConfidence: Double?, val bodyWrists: List<BodyWrist>)
    @Serializable data class UnresolvedHand(val handIndex: Int, val candidatePersonIndices: List<Int>)
    @Serializable data class Competition(val originalPersonIndex: Int, val side: ArmMeasurement.Side,
        val handIndices: List<Int>)
    @Serializable data class Result(val timestamp: PresentationTime, val imageSize: ImageSize,
        val bodyBackend: String, val bodyRevision: Int, val handModel: String, val handBackend: String,
        val matches: List<Match>, val unresolvedHands: List<UnresolvedHand>, val competingBodyWrists: List<Competition>,
        val policyVersion: Int = ObservedWristAssociation.policyVersion)

    fun associate(frame: Frame): Result? {
        val pose = frame.pose
        val hand = pose.gripObservation ?: return null
        val size = pose.imageSize
        fun inside(p: Point2D) = p.isFinite && p.x in 0.0..size.width && p.y in 0.0..size.height
        if (frame.handSourceTime != pose.timestamp || !size.isValid || !pose.timestamp.seconds.isFinite() ||
            pose.backend.isBlank() || pose.people.size !in 1..32 || hand.schemaVersion != 1 ||
            hand.failure != null || hand.coordinateConvention != "cameraRightDownAway_v1" ||
            hand.seconds != pose.timestamp.seconds || hand.imageSize != size || hand.modelId.isBlank() ||
            hand.backend.isBlank() || hand.hands.size > 2 || hand.trackedBody.size > 6 ||
            hand.trackedBody.map { it.joint }.distinct().size != hand.trackedBody.size ||
            hand.trackedBody.any { !it.position.isFinite || !it.score.isFinite() || it.score !in 0.0..1.0 } ||
            pose.people.any { it.landmarks.size > 64 || it.landmarks.map { p -> p.joint }.distinct().size != it.landmarks.size })
            return null
        fun boundTo(person: PoseObservation) = hand.trackedBody.all { tracked ->
            val point = person.landmarks.singleOrNull { it.joint.name == tracked.joint.name }
            point != null && point.position == tracked.position && point.confidence == tracked.score
        }
        // Raw context may contain a cropped-out hip. It is checked for exact
        // origin binding, never used as positive wrist or shoulder evidence.
        if (!pose.people.any(::boundTo)) return null
        fun wrist(person: PoseObservation, side: ArmMeasurement.Side): BodyWrist? {
            val point = person.landmarks.singleOrNull { it.joint == side.joints[2] } ?: return null
            return point.takeIf { inside(it.position) && it.confidence.isFinite() &&
                it.confidence in ArmMeasurement.confidenceThreshold..1.0 }?.let { BodyWrist(side, it.position) }
        }
        val matches = mutableListOf<Match>()
        val unresolved = mutableListOf<UnresolvedHand>()
        for ((handIndex, value) in hand.hands.withIndex()) {
            val point = observedWrist(value, size, frame.requiresJointConfidence)
            if (point == null) { unresolved += UnresolvedHand(handIndex, emptyList()); continue }
            val candidates = pose.people.mapIndexedNotNull { index, person ->
                val wrists = ArmMeasurement.Side.entries.mapNotNull { wrist(person, it) }.filter {
                    hypot(it.position.x - point.position.x, it.position.y - point.position.y) <= .12 * min(size.width, size.height)
                }
                if (wrists.isEmpty()) null else index to wrists
            }
            val candidate = candidates.singleOrNull()
            if (candidate == null) {
                unresolved += UnresolvedHand(handIndex, candidates.map { it.first }); continue
            }
            val original = pose.people[candidate.first]
            // Origin binding does not remove a geometrically competing person.
            // Every supplied tracked value must belong to the original sample.
            if (!boundTo(original)) return null
            matches += Match(candidate.first, handIndex, point.position, point.score, candidate.second)
        }
        val competitions = matches.flatMap { match -> match.bodyWrists.map {
            (match.originalPersonIndex to it.side) to match.handIndex
        } }.groupBy({ it.first }, { it.second }).filterValues { it.size > 1 }.map { (key, hands) ->
            Competition(key.first, key.second, hands)
        }
        return Result(pose.timestamp, size, pose.backend, pose.requestRevision, hand.modelId, hand.backend,
            matches, unresolved, competitions)
    }

    private fun observedWrist(hand: GripHandObservation, size: ImageSize, requiresScore: Boolean): GripHandPoint? {
        if (hand.points.size !in 1..6 || hand.points.map { it.joint }.distinct().size != hand.points.size) return null
        val point = hand.points.singleOrNull { it.joint == GripHandJoint.wrist } ?: return null
        val score = point.score
        if (requiresScore && score == null || score != null && (!score.isFinite() || score !in ArmMeasurement.confidenceThreshold..1.0) ||
            !point.position.isFinite || point.position.x !in 0.0..size.width || point.position.y !in 0.0..size.height) return null
        hand.sourceRegion?.let { region ->
            if (!region.isValidFor(size) || point.position.x !in region.left.toDouble()..(region.left.toDouble() + region.width) ||
                point.position.y !in region.top.toDouble()..(region.top.toDouble() + region.height)) return null
        }
        return point
    }
}

class ObservedWristAssociationBridge {
    @Throws(IllegalArgumentException::class)
    fun associateJson(input: String): String? = ObservedWristAssociation.associate(
        WorkoutRecordCodec.json.decodeFromString<ObservedWristAssociation.Frame>(input))?.let { WorkoutRecordCodec.json.encodeToString(it) }
}
