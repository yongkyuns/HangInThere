package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Serial source-owned composition, shared by native live and replay adapters.
 * Motion policy, descriptive metrics and set boundaries remain separate. An
 * image cycle is recorded with its own method/version, never as counter v8.
 * Native owners retain the exact current image/primary pose, acquired support
 * provenance and camera evidence. This class neither manufactures those nor
 * infers anatomical depth, mechanical contact, assistance or load.
 */
class BilateralDipWorkout(private val setId: String, private val trackedAtEpochMilliseconds: Long? = null) {
    @Serializable data class Snapshot(val motion: BilateralDipMotion.Snapshot, val analysisRevision: Int,
        val analyzedFrames: Int, val usableFrames: Int, val endReason: String?)
    private val motion = BilateralDipMotion()
    private val analysis = WorkoutAnalysis(setId, ExerciseCounter.Exercise.dip, BilateralDipMotion.policyVersion, countMethod,
        ExerciseCounter.maximumDipObservationOutageSeconds)
    private val boundary = AutomaticSetBoundary()
    private var first: Double? = null
    private var last: PoseResult? = null
    private var endReason: String? = null
    private var analyzed = 0
    private var usable = 0
    private var started = false
    private var readinessSample: MotionSample? = null
    private var previousPhase = BilateralDipMotion.Phase.seekingTop
    private var anchor: BilateralGripDisplacement.Anchor? = null
    private var peak: Pair<Double, Double>? = null

    fun observe(pose: PoseResult, support: CurvedDipRailAcquisition.Reference?, camera: BilateralDipMotion.Camera,
        automaticFinish: Boolean): Snapshot {
        check(endReason == null) { "Create a new source-owned workout after finishing" }
        val state = motion.observe(pose, support, camera)
        val sample = BilateralGripDisplacement.Sample.create(pose)
        val available = camera == BilateralDipMotion.Camera.ready && support != null && sample != null &&
            state.availability in measurableStates
        if (available && (state.phase == BilateralDipMotion.Phase.outbound || state.phase == BilateralDipMotion.Phase.returning ||
            state.event?.outcome == BilateralDipMotion.Outcome.imageCycle)) {
            anchor?.measure(sample!!)?.let { measurement ->
                val travel = (measurement.left.axialPixels / measurement.left.baselineArmPixels +
                    measurement.right.axialPixels / measurement.right.baselineArmPixels) * .5
                if (travel.isFinite() && (peak == null || travel > peak!!.first)) peak = travel to pose.timestamp.seconds
            }
        }
        val short = min(pose.imageSize.width, pose.imageSize.height)
        val feature = MotionSample(pose.timestamp.seconds, sample?.left?.elbowDegrees, sample?.right?.elbowDegrees,
            if (available) (sample!!.left.shoulder.y + sample.right.shoulder.y) / (2 * short) else null,
            available, released(pose, support, camera))
        val phase = when (state.phase) {
            BilateralDipMotion.Phase.seekingTop -> ExerciseCounter.Phase.seekingStart
            BilateralDipMotion.Phase.ready -> ExerciseCounter.Phase.ready
            BilateralDipMotion.Phase.outbound -> ExerciseCounter.Phase.outbound
            BilateralDipMotion.Phase.returning -> ExerciseCounter.Phase.returning
        }
        val event = state.event?.let { WorkoutAnalysis.MotionEvent(when (it.outcome) {
            BilateralDipMotion.Outcome.imageCycle -> WorkoutAnalysis.MotionOutcome.completed
            BilateralDipMotion.Outcome.partial -> WorkoutAnalysis.MotionOutcome.partial
            BilateralDipMotion.Outcome.interrupted -> WorkoutAnalysis.MotionOutcome.interrupted
        }, it.sourceTime.seconds, it.reason, peak?.second.takeIf { _ -> it.outcome == BilateralDipMotion.Outcome.imageCycle }) }
        if (!started && state.phase == BilateralDipMotion.Phase.ready) {
            started = true
            // Retrospectively credit only the immediately preceding observed
            // starting support confirmed by the motion policy's actual dwell.
            readinessSample?.takeIf { feature.seconds > it.seconds && feature.seconds - it.seconds <= ExerciseCounter.maximumGapSeconds }?.let {
                first = it.seconds
                analysis.observeMotion(it, ExerciseCounter.Phase.ready, null); analyzed++; usable++
            }
        }
        if (started) {
            analysis.observeMotion(feature, phase, event)
            analyzed++
            if (available) usable++
            if (pose.timestamp.seconds.isFinite()) {
                if (first == null) first = pose.timestamp.seconds
                if (last == null || pose.timestamp.seconds > last!!.timestamp.seconds) last = pose
            }
            if (automaticFinish) boundary.observe(feature, true)?.let { finish(it) }
        }
        readinessSample = feature.takeIf { !started && state.availability == "acquiringMeasuredTop" }
        if (state.phase == BilateralDipMotion.Phase.ready &&
            (previousPhase == BilateralDipMotion.Phase.seekingTop || state.event != null)) {
            anchor = sample?.let(BilateralGripDisplacement.Anchor::create); peak = null
        } else if (state.phase == BilateralDipMotion.Phase.seekingTop) { anchor = null; peak = null }
        previousPhase = state.phase
        return Snapshot(state, analysis.revision, analyzed, usable, endReason)
    }

    fun finish(reason: String) {
        if (endReason != null) return
        require(reason.isNotBlank() && reason.length <= 64)
        analysis.finish(reason); endReason = reason
    }
    fun record(includeActive: Boolean = false): WorkoutSetRecord {
        val pose = last
        return WorkoutSetRecord(setId, ExerciseCounter.Exercise.dip, first, pose?.timestamp?.seconds,
            endReason ?: "running", analysis.records + listOfNotNull(if (includeActive) analysis.activeRecord else null),
            pose?.backend, pose?.requestRevision, schemaVersion = 2, imageSize = pose?.imageSize,
            trackedAtEpochMilliseconds = trackedAtEpochMilliseconds, activity = analysis.activity,
            countMethod = countMethod, countPolicyVersion = BilateralDipMotion.policyVersion)
    }
    fun recordsJson(): String = WorkoutRecordCodec.json.encodeToString(analysis.records)

    private fun released(pose: PoseResult, support: CurvedDipRailAcquisition.Reference?, camera: BilateralDipMotion.Camera): Boolean {
        if (!motion.hasCurrentSupport || support == null || camera != BilateralDipMotion.Camera.ready || pose.imageSize != support.imageSize ||
            pose.backend != support.context.bodyBackend || pose.requestRevision != support.context.bodyRevision) return false
        val sample = BilateralGripDisplacement.Sample.create(pose) ?: return false
        if (sample.handModel != support.context.handModel || sample.handBackend != support.context.handBackend ||
            SourceTimeComparison.compareElapsed(pose.timestamp, support.sourceTime, SourceTimeLimit(0, 1))?.let { it >= 0 } != true) return false
        val person = pose.people.singleOrNull() ?: return false
        val arms = ArmMeasurement.Side.entries.map { ArmMeasurement(pose, it).estimate ?: return false }
        val wrists = listOf(PoseJoint.leftWrist, PoseJoint.rightWrist).map { person.landmark(it)?.position ?: return false }
        val grips = listOf(sample.left.grip, sample.right.grip)
        val short = min(pose.imageSize.width, pose.imageSize.height)
        val threshold = max(short * .12, arms.sumOf { it.upperArmPixels + it.forearmPixels } * .125)
        val rails = listOf(support.left, support.right).flatMap { listOf(it.firstBoundary, it.secondBoundary) }
        // Both current body wrists must leave the retained grips AND every
        // finite observed boundary. A missing hand/arm never supplies release.
        return support.hands.size == 2 && wrists.indices.all { i ->
            hypot(wrists[i].x - support.hands[i].x, wrists[i].y - support.hands[i].y) > threshold &&
                hypot(grips[i].x - support.hands[i].x, grips[i].y - support.hands[i].y) > threshold &&
                rails.all { rail -> listOf(wrists[i], grips[i]).all { point ->
                    ObservedCurveGeometry.distance(rail, point, pose.imageSize)?.let { it > threshold } == true
                } }
        }
    }
    companion object {
        const val countMethod = "bilateralDipImageMotion"
        private val measurableStates = setOf("available", "ready", "acquiringMeasuredTop", "waitingForMeasuredTop", "waitingForBilateralTravel")
    }
}

/** Native FFI composes the same method used directly on Android. A finished
 * instance is immutable; owners allocate the next set/source explicitly. */
class BilateralDipWorkoutBridge(setId: String, trackedAtEpochMilliseconds: Long = 0) {
    private val workout = BilateralDipWorkout(setId, trackedAtEpochMilliseconds.takeIf { it > 0 })
    @Serializable private data class Input(val pose: PoseResult, val support: CurvedDipRailAcquisition.Reference?,
        val camera: BilateralDipMotion.Camera, val automaticFinish: Boolean = false)
    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    fun observeJson(input: String): String {
        val value = WorkoutRecordCodec.json.decodeFromString<Input>(input)
        return WorkoutRecordCodec.json.encodeToString(workout.observe(value.pose, value.support, value.camera, value.automaticFinish))
    }
    fun finish(reason: String) = workout.finish(reason)
    fun recordsJson(): String = workout.recordsJson()
    fun setJson(includeActive: Boolean): String = WorkoutRecordCodec.json.encodeToString(workout.record(includeActive))
}
