package dev.hanginthere.evaluation

import dev.hanginthere.core.*
import kotlinx.serialization.Serializable

/** TEST ONLY source-owned episodes, not registered/durable workout records.
 * Camera verdict is supplied externally. No source exercise/count/set labels,
 * apparatus references, RGB contact or grip metadata enter this owner. */
class OverheadPullUpSessionExperiment(private val id: String) {
    @Serializable data class Episode(val id: String, val startedAt: Double,
        val endedAt: Double?, val endReason: String,
        val events: List<OverheadPullUpMotionExperiment.Event>)
    @Serializable data class Snapshot(val motion: OverheadPullUpMotionExperiment.Snapshot,
        val started: Boolean, val endReason: String?, val positiveRelease: Boolean,
        val completed: Int, val categoricalPostureRelease: Boolean = false)
    private val motion = OverheadPullUpMotionExperiment()
    private val boundary = AutomaticSetBoundary()
    private var support: OverheadPullUpMotionExperiment.Sample? = null
    private var witnessedOverheadHold = false
    private var generation: Long? = null
    private var readyAt: Double? = null
    private var provisionalAt: Double? = null
    private var first: Double? = null
    private var last: Double? = null
    private var ending: String? = null
    private val events = mutableListOf<OverheadPullUpMotionExperiment.Event>()
    val hasStarted get() = first != null
    val isFinished get() = ending != null
    val completed get() = events.size
    fun observe(selection: BodyAthleteSelection, cameraReady: Boolean, cameraMoved: Boolean = false): Snapshot {
        check(!isFinished)
        val frame = checkNotNull(selection.frame)
        val pose = frame.pose
        val oldGeneration = generation
        val lost = frame.decision.status == BodyAthleteAssociation.Status.lost ||
            oldGeneration != null && oldGeneration != frame.decision.sourceGeneration
        generation = frame.decision.sourceGeneration
        if (lost || cameraMoved || support?.let {
                it.size != pose.imageSize || it.backend != pose.backend || it.revision != pose.requestRevision
            } == true) { support = null;witnessedOverheadHold = false;readyAt = null;provisionalAt = null }
        val state = motion.observe(selection,id,cameraReady && !cameraMoved)
        if (!state.sourceAccepted) {
            boundary.breakContinuity(); readyAt = null; provisionalAt = null; support = null;witnessedOverheadHold = false
            return Snapshot(state,hasStarted,ending,false,completed)
        }
        val completions=state.events.filter { it.outcome=="imageCycle" }
        val completedSupport=motion.completedEvidence.firstOrNull()?.support
        if (!hasStarted) (completedSupport ?: motion.measuredSupport)?.let { support = it }
        val current = motion.currentSample
        if(cameraReady && !cameraMoved && state.observationUsable && support?.overheadReleaseWitness==true)
            witnessedOverheadHold=true
        if(cameraReady && !cameraMoved && completedSupport?.overheadReleaseWitness==true)
            witnessedOverheadHold=true
        if (support != null && cameraReady && !cameraMoved && state.observationUsable &&
            state.phase == OverheadPullUpMotionExperiment.Phase.ready &&
            OverheadSupportReleasePosture.matches(pose,down=false)) witnessedOverheadHold = true
        val positiveRelease = cameraReady && !cameraMoved &&
            frame.decision.status == BodyAthleteAssociation.Status.associated &&
            support?.let { OverheadPullUpMotionExperiment.positiveRelease(pose,it) } == true
        // Reuse production v2's stronger torso-axis cue and its unchanged .25s
        // source dwell. Four-point exit without current elbows/hips still uses
        // the unchanged one-second distance/posture fallback.
        val postureReleased = witnessedOverheadHold && cameraReady && !cameraMoved &&
            frame.decision.status == BodyAthleteAssociation.Status.associated &&
            support?.let { OverheadPullUpMotionExperiment.handsDeparted(pose,it) } == true &&
            OverheadSupportReleasePosture.matches(pose,down=true)
        val sample = MotionSample(pose.timestamp.seconds,current?.arms?.get(0)?.degrees,
            current?.arms?.get(1)?.degrees,
            if (state.observationUsable) current?.arms?.map { it.shoulder.y }?.average() else null,
            state.observationUsable,positiveRelease || postureReleased,postureReleased)
        // Keep the actual pre-departure source sample while full excursion is
        // still private. Committing later cannot move the episode start past
        // its own first observed departure. No provisional episode is published.
        if (!hasStarted && provisionalAt == null && state.observationUsable &&
            state.phase == OverheadPullUpMotionExperiment.Phase.outbound)
            provisionalAt = readyAt?.takeIf { pose.timestamp.seconds-it <= ExerciseCounter.maximumGapSeconds }
                ?: pose.timestamp.seconds
        if (!hasStarted && (completions.isNotEmpty() || motion.hasObservedExcursion && state.observationUsable))
            first = completions.firstOrNull()?.startWitnessAt ?: motion.observedSetStart?.seconds ?: provisionalAt ?: pose.timestamp.seconds
        if (hasStarted) {
            last = pose.timestamp.seconds
            events.addAll(completions)
            when {
                cameraMoved -> finish("phoneMoved")
                lost -> finish("trackingLost")
                else -> boundary.observe(sample,true)?.let(::finish)
            }
        }
        if (!hasStarted) {
            if (state.phase == OverheadPullUpMotionExperiment.Phase.ready && state.observationUsable) {
                readyAt = pose.timestamp.seconds;provisionalAt = null
            } else if (state.phase == OverheadPullUpMotionExperiment.Phase.idle) {
                readyAt = null;provisionalAt = null
            }
        }
        return Snapshot(state,hasStarted,ending,positiveRelease || postureReleased,completed,postureReleased)
    }
    private fun finish(reason: String) { ending = reason; motion.interrupt(reason) }
    fun episode(): Episode? = first?.let { Episode(id,it,last,ending ?: "running",events.toList()) }
}
