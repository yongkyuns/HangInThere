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
    const val policyVersion = 2

    @Serializable data class Frame(val pose: PoseResult, val handSourceTime: PresentationTime,
        /** False only for providers that return positions without joint scores.
         * A reported low score is always rejected; handedness is never a score. */
        val requiresJointConfidence: Boolean = true)

    @Serializable data class BodyWrist(val side: ArmMeasurement.Side, val position: Point2D)
    /** Unique person correspondence only. Several hands may still compete for
     * a body side; these are candidate edges, not independent limb assignments. */
    @Serializable data class Match(val originalPersonIndex: Int, val handIndex: Int,
        val observedWrist: Point2D, val jointConfidence: Double?, val bodyWrists: List<BodyWrist>)
    /** A measured wrist with no nearby body candidate is different from an
     * unavailable wrist, which could compete with any otherwise matched arm.
     * Older transports omit this point and remain explicitly unavailable. */
    @Serializable data class UnresolvedHand(val handIndex: Int, val candidatePersonIndices: List<Int>,
        val observedWrist: Point2D? = null)
    @Serializable data class Competition(val originalPersonIndex: Int, val side: ArmMeasurement.Side,
        val handIndices: List<Int>)
    @Serializable data class Result(val timestamp: PresentationTime, val imageSize: ImageSize,
        val bodyBackend: String, val bodyRevision: Int, val handModel: String, val handBackend: String,
        val matches: List<Match>, val unresolvedHands: List<UnresolvedHand>, val competingBodyWrists: List<Competition>,
        val policyVersion: Int = ObservedWristAssociation.policyVersion) {
        /** Unique body-side candidate edges, preserving every competitor.
         * An unresolved hand with an unavailable wrist blocks selection. A
         * measured hand belonging to another/unknown person need not erase an
         * independent edge, provided this person is not one of its candidates.
         * This selects current image correspondence, never temporal identity,
         * support/contact, camera readiness, exercise or permission to count. */
        fun uniqueArmMatches(): List<Match> {
            if (policyVersion != ObservedWristAssociation.policyVersion ||
                unresolvedHands.any { it.observedWrist == null }) return emptyList()
            return matches.filter { match ->
                val wrist = match.bodyWrists.singleOrNull() ?: return@filter false
                unresolvedHands.none { match.originalPersonIndex in it.candidatePersonIndices } &&
                    competingBodyWrists.none { it.originalPersonIndex == match.originalPersonIndex && it.side == wrist.side } &&
                    matches.count { other -> other.originalPersonIndex == match.originalPersonIndex &&
                        other.bodyWrists.any { it.side == wrist.side } } == 1
            }
        }
    }

    fun associate(frame: Frame): Result? {
        val pose = frame.pose
        val validated = ObservedHandValidation.validate(pose, frame.handSourceTime) ?: return null
        val hand = validated.hand
        val size = pose.imageSize
        fun inside(p: Point2D) = p.isFinite && p.x in 0.0..size.width && p.y in 0.0..size.height
        fun wrist(person: PoseObservation, side: ArmMeasurement.Side): BodyWrist? {
            val point = person.landmarks.singleOrNull { it.joint == side.joints[2] } ?: return null
            return point.takeIf { inside(it.position) && it.confidence.isFinite() &&
                it.confidence in ArmMeasurement.confidenceThreshold..1.0 }?.let { BodyWrist(side, it.position) }
        }
        val matches = mutableListOf<Match>()
        val unresolved = mutableListOf<UnresolvedHand>()
        for ((handIndex, value) in hand.hands.withIndex()) {
            val point = ObservedHandValidation.wrist(value, size, frame.requiresJointConfidence)
            if (point == null) { unresolved += UnresolvedHand(handIndex, emptyList()); continue }
            val candidates = pose.people.mapIndexedNotNull { index, person ->
                val wrists = ArmMeasurement.Side.entries.mapNotNull { wrist(person, it) }.filter {
                    hypot(it.position.x - point.position.x, it.position.y - point.position.y) <= .12 * min(size.width, size.height)
                }
                if (wrists.isEmpty()) null else index to wrists
            }
            val candidate = candidates.singleOrNull()
            if (candidate == null) {
                unresolved += UnresolvedHand(handIndex, candidates.map { it.first }, point.position); continue
            }
            val original = pose.people[candidate.first]
            // Origin binding does not remove a geometrically competing person.
            // Every supplied tracked value must belong to the original sample.
            if (!validated.boundTo(original)) return null
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


}

class ObservedWristAssociationBridge {
    @Throws(IllegalArgumentException::class)
    fun associateJson(input: String): String? = observation(input)?.let { WorkoutRecordCodec.json.encodeToString(it) }

    /** Same source validation and selection as typed Android callers. The
     * source owner retains the exact input pose/pixels/epoch with this result. */
    @Throws(IllegalArgumentException::class)
    fun uniqueArmMatchesJson(input: String): String? = observation(input)?.let {
        WorkoutRecordCodec.json.encodeToString(it.uniqueArmMatches())
    }

    private fun observation(input: String) = ObservedWristAssociation.associate(
        WorkoutRecordCodec.json.decodeFromString<ObservedWristAssociation.Frame>(input))
}
