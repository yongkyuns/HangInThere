package dev.hanginthere.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** Serial source owner. Every decision refers to an original person index in
 * the same immutable raw pose. Native setup and counting share this instance;
 * a caller cannot author a continuity flag for the movement owner. */
class BodyAthleteSelection {
    internal data class Frame(val raw: PoseResult, val pose: PoseResult,
        val decision: BodyAthleteAssociation.Decision)
    private var generation = 0L
    private var source: ForegroundAssociation.Source? = null
    private var association: BodyAthleteAssociation? = null
    private var highWater: PresentationTime? = null
    private var resetNext = false
    internal var frame: Frame? = null; private set

    /** Explicit capture/source disposal, never a per-set or per-frame reset. */
    fun reset() {
        generation++; source = null; association = null; highWater = null
        resetNext = false; frame = null
    }
    fun observe(pose: PoseResult): BodyAthleteAssociation.Decision {
        val time = pose.timestamp
        if (!time.seconds.isFinite() || time.seconds < 0 ||
            highWater?.let { SourceTimeComparison.compareElapsed(time, it, SourceTimeLimit(0, 1)) != 1 } == true) {
            val rejected = BodyAthleteAssociation.Decision(BodyAthleteAssociation.Status.ambiguous,
                "clockFault", seconds = time.seconds.takeIf { it.isFinite() }, sourceGeneration = source?.generation ?: generation)
            frame = Frame(pose, pose.copy(people = emptyList(), gripObservation = null), rejected)
            return rejected
        }
        highWater = time
        if (source == null || resetNext || source!!.imageSize != pose.imageSize ||
            source!!.backend != pose.backend || source!!.revision != pose.requestRevision) {
            source = ForegroundAssociation.Source(generation++, pose.imageSize, pose.backend, pose.requestRevision)
            association = BodyAthleteAssociation(source!!); resetNext = false
        }
        val decision = association!!.observe(source!!, time, pose.people)
        if (decision.status == BodyAthleteAssociation.Status.lost) resetNext = true
        val selected = decision.candidateIndex?.let { pose.people[it] }
        // RGB grip observations belonged to the original SDK subject. They
        // cannot follow a different selected body by merely changing its index.
        frame = Frame(pose, pose.copy(people = listOfNotNull(selected),
            gripObservation = pose.gripObservation.takeIf { decision.candidateIndex == 0 }), decision)
        return decision
    }
    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    fun observeJson(poseJson: String): String = WorkoutRecordCodec.json.encodeToString(
        observe(WorkoutRecordCodec.json.decodeFromString<PoseResult>(poseJson)))
}
