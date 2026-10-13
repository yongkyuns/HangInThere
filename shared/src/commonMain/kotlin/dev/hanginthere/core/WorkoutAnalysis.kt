package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.*

/** Source-time data contract. Null means unobserved, never a zero angle/duration. */
@Serializable data class MotionSample(
    val seconds: Double, val leftDegrees: Double?, val rightDegrees: Double?,
    val shoulderHeight: Double?, val usable: Boolean, val released: Boolean,
)

/** Camera/model adapters provide poses; all analysis geometry lives here. */
object MotionFeatureExtractor {
    fun extract(pose: PoseResult, reference: BarSegment?, policyUsable: Boolean, heldGrips: List<Point2D>? = null): MotionSample {
        val left = ArmMeasurement(pose, ArmMeasurement.Side.left).estimate
        val right = ArmMeasurement(pose, ArmMeasurement.Side.right).estimate
        val person = pose.people.singleOrNull()
        val shoulders = listOf(PoseJoint.leftShoulder, PoseJoint.rightShoulder).mapNotNull { person?.landmark(it)?.position }
        val short = min(pose.imageSize.width, pose.imageSize.height)
        val height = if (reference?.isValid == true && left != null && right != null && pose.imageSize.isValid && shoulders.size == 2)
            shoulders.map { it.y / short }.average() else null
        // Only positive, bilateral current-frame evidence can end a set. Missing
        // joints, empty frames and crowd ambiguity are never a release gesture.
        val hands = listOf(PoseJoint.leftWrist, PoseJoint.rightWrist).mapNotNull { person?.landmark(it)?.position }
        val released = reference?.isValid == true && left != null && right != null && hands.size == 2 && heldGrips?.size == 2 &&
            hands.zip(heldGrips).all { (hand, grip) -> hypot(hand.x-grip.x,hand.y-grip.y) > max(short * .12, (left.upperArmPixels + left.forearmPixels + right.upperArmPixels + right.forearmPixels) * .125) } &&
            hands.all { reference.distance(it) > max(short * .12, (left.upperArmPixels + left.forearmPixels + right.upperArmPixels + right.forearmPixels) * .125) }
        return MotionSample(pose.timestamp.seconds, left?.elbowDegrees, right?.elbowDegrees, height,
            policyUsable && (left != null || right != null), released)
    }
}

/** Freeze both observed hand locations at the acquired start. A dip's selected
 * rail cannot alone describe the other hand's contact with its separate rail. */
class WorkoutMotionFeatures {
    private var heldGrips: List<Point2D>? = null
    private var imageSize: ImageSize? = null
    fun extract(pose: PoseResult, reference: BarSegment?, usable: Boolean, phase: ExerciseCounter.Phase): MotionSample {
        if (heldGrips == null && phase == ExerciseCounter.Phase.ready && usable && reference?.isValid == true && pose.imageSize.isValid &&
            ArmMeasurement(pose,ArmMeasurement.Side.left).estimate != null && ArmMeasurement(pose,ArmMeasurement.Side.right).estimate != null) {
            val person = pose.people.singleOrNull()
            val hands = listOf(PoseJoint.leftWrist,PoseJoint.rightWrist).mapNotNull { person?.landmark(it)?.position }
            if (hands.size == 2) { heldGrips=hands; imageSize=pose.imageSize }
        }
        return MotionFeatureExtractor.extract(pose,reference,usable,heldGrips.takeIf { imageSize==pose.imageSize })
    }
    fun reset() { heldGrips=null;imageSize=null }
}

@Serializable data class AngleMetrics(
    val minimum: Double?, val maximum: Double?, val mean: Double?,
    val observedSeconds: Double, val coverage: Double?,
)
/** Completed-cycle analytics are separate from the counter's acceptance milestone.
 * Raw attempts stay in journals so recovery cannot invent or erase evidence. */
object WorkoutAnalytics {
    fun isCompletedRep(hasAcceptance: Boolean, hasReturn: Boolean, closeReason: String): Boolean =
        hasAcceptance && hasReturn && closeReason == "returned"
}
@Serializable data class RepRecord(
    val id: String, val setId: String, val ordinal: Int, val exercise: ExerciseCounter.Exercise,
    val startedAt: Double, val extremeAt: Double?, val acceptedAt: Double?,
    val returnedAt: Double?, val closedAt: Double, val closeReason: String,
    val outcome: String, val outboundSeconds: Double?, val returnSeconds: Double?,
    val left: AngleMetrics, val right: AngleMetrics,
    val shoulderExcursion: Double?, val bilateralExtremeOffsetSeconds: Double?,
    val usableDurationSeconds: Double, val durationCoverage: Double?,
    val samples: List<MotionSample>, val curveTruncated: Boolean,
    val countPolicyVersion: Int = ExerciseCounter.policyVersion, val metricVersion: Int = 1,
    val acceptanceReason: String? = null, val formVerification: String = "unverified",
    val grip: GripEstimate? = null,
) {
    val isCompleteForAnalytics get() = WorkoutAnalytics.isCompletedRep(acceptedAt != null, returnedAt != null, closeReason)
}
@Serializable data class WorkoutSetRecord(
    val id: String, val exercise: ExerciseCounter.Exercise, val startedAt: Double?,
    val endedAt: Double?, val endReason: String, val reps: List<RepRecord>,
    val backend: String?, val modelRevision: Int?,
    val schemaVersion: Int = 1, val lifecycleVersion: Int = 1,
    val trackingSide: ArmMeasurement.Side? = null, val imageSize: ImageSize? = null, val referenceEdge: BarSegment? = null,
    val trackedAtEpochMilliseconds: Long? = null,
    val activity: WorkoutActivity? = null,
    val grip: WorkoutGripRecord? = null,
    /** Actual live calendar finish; absent for replay, legacy and interrupted recovery. */
    val trackedEndedAtEpochMilliseconds: Long? = null,
) {
    val acceptedCount get() = reps.count { it.acceptedAt != null }
    val completedReps get() = reps.filter { it.isCompleteForAnalytics }
    val completedCount get() = completedReps.size
    val hasPractice get() = reps.isNotEmpty() || activity?.hasPractice == true
}

/** Incremental integration, independent of frame rate. No integration across a gap. */
internal class AngleAccumulator {
    private var minimum: Double? = null
    private var maximum: Double? = null
    private var integral = 0.0
    private var observed = 0.0
    var extremeAt: Double? = null; private set
    fun observe(value: Double?, time: Double, previous: Double?, dt: Double?, valid: Boolean) {
        if (value == null || !value.isFinite() || !valid) return
        if (minimum == null || value < minimum!!) { minimum = value; extremeAt = time }
        maximum = max(maximum ?: value, value)
        if (previous != null && dt != null) { integral += (previous + value) * .5 * dt; observed += dt }
    }
    fun metrics(duration: Double) = AngleMetrics(minimum, maximum, if (observed > 0) integral / observed else null,
        observed, if (duration > 0) (observed / duration).coerceIn(0.0, 1.0) else null)
}

/**
 * Observes the established counter's phase/events; it does not redefine rep gates.
 * An accepted pull-up remains accepted if its subsequent return is interrupted.
 * [revision] changes only on record updates, so native clients needn't serialize
 * the growing records on every frame. Mutation has one serial owner per session.
 */
class WorkoutAnalysis(val setId: String, val exercise: ExerciseCounter.Exercise) {
    private class Attempt(val start: Double, val ordinal: Int) {
        val left = AngleAccumulator(); val right = AngleAccumulator()
        var extreme: Double? = null; var accepted: Double? = null
        var minimumMeanAngle: Double? = null
        var acceptanceReason: String? = null
        var previous: MotionSample? = null
        var usableSeconds = 0.0
        var heightMin: Double? = null; var heightMax: Double? = null
        val samples = mutableListOf<MotionSample>()
        var truncated = false
        fun observe(sample: MotionSample, measureExtreme: Boolean = true) {
            val old = previous
            val dt = old?.let { (sample.seconds - it.seconds).takeIf { d -> d > 0 && d <= ExerciseCounter.maximumGapSeconds && it.usable && sample.usable } }
            val meanAngle = if(sample.leftDegrees!=null && sample.rightDegrees!=null) (sample.leftDegrees+sample.rightDegrees)*.5 else null
            if(measureExtreme && sample.usable && meanAngle!=null && (minimumMeanAngle==null || meanAngle<minimumMeanAngle!!)){minimumMeanAngle=meanAngle;extreme=sample.seconds}
            left.observe(sample.leftDegrees, sample.seconds, old?.leftDegrees, dt, sample.usable)
            right.observe(sample.rightDegrees, sample.seconds, old?.rightDegrees, dt, sample.usable)
            if (dt != null) usableSeconds += dt
            if (sample.usable && sample.shoulderHeight != null) {
                heightMin = min(heightMin ?: sample.shoulderHeight, sample.shoulderHeight)
                heightMax = max(heightMax ?: sample.shoulderHeight, sample.shoulderHeight)
            }
            // Display curve is bounded; full-resolution integrals above are not.
            // Invalid points are retained to prevent charts drawing over gaps.
            if (samples.isEmpty() || sample.seconds - samples.last().seconds >= .1 || old?.usable != sample.usable || (old?.leftDegrees == null) != (sample.leftDegrees == null) || (old?.rightDegrees == null) != (sample.rightDegrees == null)) {
                if (samples.size == maximumCurveSamples) { samples.removeAt(0); truncated = true }
                samples += sample
            }
            previous = sample
        }
        fun record(setId: String, exercise: ExerciseCounter.Exercise, end: Double, reason: String, returned: Boolean): RepRecord {
            val duration = max(0.0, end - start)
            val l = left.metrics(duration); val r = right.metrics(duration)
            return RepRecord("$setId:rep:$ordinal", setId, ordinal, exercise, start, extreme, accepted,
                end.takeIf { returned }, end, reason, if (accepted != null) "accepted" else if (reason == "partial") "partial" else "interrupted",
                extreme?.minus(start), if (returned && extreme != null) end - extreme!! else null, l, r,
                if (heightMin != null && heightMax != null) heightMax!! - heightMin!! else null,
                if ((l.coverage ?: 0.0) >= .99 && (r.coverage ?: 0.0) >= .99 && left.extremeAt != null && right.extremeAt != null)
                    abs(left.extremeAt!! - right.extremeAt!!) else null,
                usableSeconds, if (duration > 0) (usableSeconds / duration).coerceIn(0.0, 1.0) else null,
                samples.toList(), truncated, acceptanceReason=acceptanceReason)
        }
    }
    private val phases = WorkoutPhaseAnalysis(setId, exercise)
    private val grips = WorkoutGripAnalysis(setId)
    val gripRecord get() = if(exercise==ExerciseCounter.Exercise.pullUp && grips.hasObservation)grips.record else null
    val gripDelta get() = if(exercise==ExerciseCounter.Exercise.pullUp && grips.hasObservation)grips.delta else null
    val currentGrip get() = grips.current.takeIf { exercise==ExerciseCounter.Exercise.pullUp }
    fun observeGrip(pose:PoseResult,reference:BarSegment?,phase:ExerciseCounter.Phase,usable:Boolean,sourceSeconds:Double=pose.timestamp.seconds) {
        if(exercise==ExerciseCounter.Exercise.pullUp) {
            val before=grips.current
            grips.observe(pose,reference,phase,usable,sourceSeconds)
            val after=grips.current
            if(before.left!=after.left || before.right!=after.right || before.width!=after.width || before.reason!=after.reason)revision++
        }
    }
    val activity get() = phases.activity
    val activityDelta get() = activity.let { it.copy(phases=it.phases.takeLast(3)) }
    private var attempt: Attempt? = null
    private var previous: MotionSample? = null
    private var lastPhase = ExerciseCounter.Phase.seekingStart
    private var ordinal = 0
    private var closed: List<RepRecord> = emptyList()
    var revision = 0; private set
    val records: List<RepRecord> get() = closed
    val activeAccepted get() = attempt?.accepted != null
    val lastSourceSeconds get() = previous?.seconds
    val activeRecord get() = attempt?.let { withGrip(it.record(setId, exercise, previous?.seconds ?: it.start, "inProgress", false)) }

    fun observe(sample: MotionSample, phase: ExerciseCounter.Phase, event: ExerciseCounter.Event?) {
        if (!sample.seconds.isFinite()) { interrupt("invalidTimestamp"); return }
        val old = previous
        if (old != null && sample.seconds <= old.seconds) { interrupt("nonIncreasingTimestamp"); return }
        if (old != null && sample.seconds - old.seconds > ExerciseCounter.maximumGapSeconds) interrupt("sourceTimeGap")
        if (phases.observe(sample, phase)) revision++
        if (attempt == null && phase == ExerciseCounter.Phase.outbound && lastPhase != phase) {
            attempt = Attempt(old?.seconds ?: sample.seconds, ++ordinal)
            old?.let { attempt!!.observe(it, measureExtreme=false) }
        }
        attempt?.observe(sample)
        if (event?.outcome == ExerciseCounter.Outcome.movement) {
            // Defensive: retain an accepted event even if an adapter attached late.
            if (attempt == null) attempt = Attempt(sample.seconds, ++ordinal).also { it.observe(sample) }
            attempt!!.accepted = event.sourceSeconds; attempt!!.acceptanceReason=event.reason; revision++
        }
        when {
            event?.outcome == ExerciseCounter.Outcome.partial -> close(sample.seconds, "partial", false)
            event?.outcome == ExerciseCounter.Outcome.interrupted -> close(sample.seconds, event.reason, false)
            phase == ExerciseCounter.Phase.ready && lastPhase == ExerciseCounter.Phase.returning -> close(sample.seconds, "returned", true)
            phase == ExerciseCounter.Phase.seekingStart && lastPhase != phase -> close(sample.seconds, "trackingLost", false)
        }
        previous = sample; lastPhase = phase
    }
    fun interrupt(reason: String) { phases.interrupt(); previous?.seconds?.let { close(it, reason, false) };grips.interrupt(); lastPhase = ExerciseCounter.Phase.seekingStart; previous = null }
    fun finish(reason: String) { previous?.seconds?.let { close(it, reason, false) } }
    private fun close(time: Double, reason: String, returned: Boolean) {
        attempt?.let { closed += withGrip(it.record(setId, exercise, time, reason, returned)); revision++ }; attempt = null
    }
    private fun withGrip(rep:RepRecord)=if(exercise==ExerciseCounter.Exercise.pullUp && grips.hasObservation)rep.copy(grip=grips.summarize(rep.startedAt,rep.closedAt)) else rep
    fun recordsJson(): String = json.encodeToString(records)
    companion object {
        const val maximumCurveSamples = 256
        val json = Json { encodeDefaults = true; explicitNulls = true }
    }
}

/** Only fresh observed release earns completion; long endpoint holds never time out. */
class AutomaticSetBoundary {
    private var last: Double? = null
    private var releaseSince: Double? = null
    private var unavailableSince: Double? = null
    private var engaged=false
    fun observe(sample: MotionSample, hasActivity: Boolean): String? {
        val time = sample.seconds
        if (!time.isFinite() || (last != null && time <= last!!)) {
            // Break continuity without forgetting the source high-water mark.
            // Otherwise a run of rejected older frames can earn fresh dwell.
            breakContinuity()
            return null
        }
        if (last != null && time - last!! > ExerciseCounter.maximumGapSeconds) breakContinuity()
        last = time
        if(hasActivity) engaged=true
        if (!engaged) { releaseSince = null; unavailableSince = null; return null }
        if (sample.released) {
            unavailableSince = null
            if (releaseSince == null) releaseSince = time
            if (time - releaseSince!! >= releaseDwellSeconds) return "disengaged"
        } else {
            releaseSince = null
            if (!sample.usable) {
                if (unavailableSince == null) unavailableSince = time
                if (time - unavailableSince!! >= outageSeconds) return "trackingLost"
            } else unavailableSince = null
        }
        return null
    }
    /** Within-set faults cannot earn dwell or erase the latest source time. */
    fun breakContinuity() { releaseSince = null; unavailableSince = null }
    fun reset() { last = null; breakContinuity(); engaged=false }
    companion object { const val releaseDwellSeconds = 1.0; const val outageSeconds = 3.0 }
}

/** Shared brief source-time readiness gate after actual apparatus/camera qualification. */
class AutomaticWorkoutStart {
    private var since: Double? = null
    private var last: Double? = null
    fun observe(seconds: Double, eligible: Boolean): Boolean {
        if (!seconds.isFinite() || !eligible || (last != null && (seconds <= last!! || seconds - last!! > ExerciseCounter.maximumGapSeconds))) { reset(); return false }
        last = seconds
        if (since == null) since = seconds
        return seconds - since!! >= ExerciseCounter.endpointDwellSeconds
    }
    fun reset() { since = null; last = null }
}
