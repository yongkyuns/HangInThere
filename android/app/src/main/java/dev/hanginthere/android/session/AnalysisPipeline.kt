package dev.hanginthere.android.session

import android.content.Context
import android.graphics.Bitmap
import dev.hanginthere.android.camera.PhoneAttitude
import dev.hanginthere.android.vision.*
import dev.hanginthere.core.*
import kotlinx.serialization.decodeFromString
import java.io.Closeable
import java.util.UUID

/** Serial native observations into the same motion controllers as the iOS app. */
class AnalysisPipeline(context: Context, selection: WorkoutExerciseSelection, side: ArmMeasurement.Side,
    automaticWorkouts: Boolean = true, private val persistWorkouts: Boolean = true,
    recordStoreOverride: WorkoutRecordStore? = null, private val replay: Boolean = false) : Closeable {
    data class Result(val pose: PoseResult, val snapshot: WorkoutRuntime.Snapshot, val progress: Double, val cue: String,
        val analysisMillis: Double, val reps: List<RepRecord> = emptyList(), val history: List<WorkoutSetRecord> = emptyList(),
        val storageError: String? = null, val currentSet: WorkoutSetRecord? = null,
        val motion: MotionWorkoutRuntime.Snapshot? = null,
        val rawPose: PoseResult = pose, val camera: OverheadPullUpMotion.Camera = OverheadPullUpMotion.Camera.unavailable)
    // Replay has no live calendar timestamps or recorded phone-motion sensor.
    val runtime = MotionWorkoutRuntime(selection, automaticWorkouts,
        nowEpochMilliseconds = { if (replay) null else System.currentTimeMillis() }, newSetId = { UUID.randomUUID().toString() })
    private val athlete = BodyAthleteSelection()
    private val poses = PoseEstimator(context)
    private val grips = GripInference(context)
    private val scene = SceneRegistration()
    private val phone = PhoneOrientationStability()
    private val stability = StaticSceneStability()
    private val acquisition = CameraAcquisitionEvidence()
    private var referenceSize: ImageSize? = null
    private var referenceContext: Pair<String, Int>? = null
    private var lastSceneSeconds: Double? = null
    private var generation = 0
    private var issue: String? = "waitingForFrame"
    private var lastCameraReady = false
    private var latestSourceSeconds: Double? = null
    private val recordStore = recordStoreOverride ?: WorkoutRecordStore.live(context)
    private var storageError: String? = null
    private var history = if (persistWorkouts) runCatching { recordStore.recent() }.getOrElse {
        storageError = it.message; emptyList()
    } else emptyList()
    private var draftRevision = -1
    private var draftSetId: String? = null
    private var queuedSetId: String? = null
    private var retryAfterNanos = 0L
    val snapshot get(): WorkoutRuntime.Snapshot {
        val state = runtime.snapshot
        return WorkoutRuntime.Snapshot(state.exercise, state.exercise, state.phase, state.movements, null, null,
            AutomaticPullUpBar.State.lookingForGrip, phone.state, stability.state, issue, generation,
            isReplayReview = replay, latestCompletedSet = runtime.latestCompletedSet,
            activeSetId = runtime.currentSet?.id, trackingIssue = state.reason.takeIf {
                state.phase == LiveSetSession.Phase.running && !state.observationUsable })
    }
    val totalAnalyzedFrames get() = runtime.totalAnalyzedFrames
    val endReason get() = runtime.snapshot.endReason?.let { reason -> LiveSetSession.EndReason.entries.firstOrNull { it.name == reason } }
    val hasQualifiedCamera get() = acquisition.hasIndependentEvidence

    fun process(bitmap: Bitmap, ptsUs: Long, attitude: PhoneAttitude.Sample?, paused: Boolean = false): Result {
        val started = System.nanoTime()
        val body = poses.estimate(bitmap, ptsUs)
        val raw = body.copy(gripObservation = if (runtime.snapshot.exercise == ExerciseCounter.Exercise.pullUp)
            grips.observe(bitmap, body) else null)
        val decision = athlete.observe(raw)
        val pose = checkNotNull(athlete.selectedPose)
        val seconds = pose.timestamp.seconds
        latestSourceSeconds = seconds
        val motionFresh = attitude != null && seconds-attitude.seconds in 0.0..0.5
        val placementReady = replay || attitude?.let { WorkoutCameraPlacement.isUsable(WorkoutCameraPlacement.attitudeTiltDegrees(it.quaternion)) } == true
        val associated = decision.status == BodyAthleteAssociation.Status.associated
        val eligible = placementReady && motionFresh && associated && PullUpArmMeasurement(pose).arms.size == 2 && !paused
        val contextChanged = referenceSize != null && (referenceSize != pose.imageSize || referenceContext != (pose.backend to pose.requestRevision))
        if (contextChanged) {
            runtime.interrupt("setupInvalidated"); resetCamera()
        }
        var moved = false
        if (referenceSize != null) {
            if (motionFresh) phone.observe(attitude!!.quaternion, attitude.seconds)
            if (acquisition.permitsRegistration(pose.timestamp.value, pose.timestamp.timescale) &&
                (lastSceneSeconds?.let { seconds-it >= AutomaticPullUpBar.samplingInterval } ?: true)) {
                val evidence = scene.observe(bitmap)
                stability.observe(evidence.translations, evidence.scaleFraction, seconds)
                lastSceneSeconds = seconds
                acquisition.observeScene(pose.timestamp.value, pose.timestamp.timescale, stability.isReady(seconds))
            }
            moved = phone.state == PhoneOrientationStability.State.moved || stability.state == StaticSceneStability.State.moved
        }
        val needsReference = referenceSize == null || acquisition.shouldReacquire(pose.timestamp.value, pose.timestamp.timescale,
            stability.isReady(seconds), !runtime.hasStarted, eligible && phone.state == PhoneOrientationStability.State.stable)
        // Actual displacement revokes private preparation and requires a new image epoch.
        if (moved && !runtime.hasStarted) {
            runtime.resetPreparation(); resetCamera()
        }
        if ((needsReference || moved) && !runtime.hasStarted && eligible) {
            runtime.resetPreparation(); scene.freeze(bitmap); referenceSize = pose.imageSize; referenceContext = pose.backend to pose.requestRevision
            phone.calibrate(attitude!!.quaternion, attitude.seconds); stability.calibrate(pose.imageSize)
            acquisition.beginReference(pose.timestamp.value, pose.timestamp.timescale)
            lastSceneSeconds = null; generation++; moved = false
        }
        lastCameraReady = referenceSize != null && motionFresh && placementReady &&
            phone.state == PhoneOrientationStability.State.stable && stability.isReady(seconds) && acquisition.hasIndependentEvidence && !paused
        val camera = when { moved -> OverheadPullUpMotion.Camera.moved
            lastCameraReady -> OverheadPullUpMotion.Camera.ready; else -> OverheadPullUpMotion.Camera.unavailable }
        val motion = runtime.observe(athlete, camera, automaticStart = !paused)
        issue = when {
            paused -> "paused"
            !motionFresh -> "phoneStabilityNeeded"
            !placementReady -> "cameraPlacementNeeded"
            moved -> "phoneMoved"
            !associated || pose.people.isEmpty() -> "framingIncomplete"
            !lastCameraReady -> "sceneStabilityNeeded"
            !motion.observationUsable -> motion.reason
            else -> null
        }
        val result = refreshRecords(Result(pose, snapshot, motion.targetProgress ?: 0.0, cue(motion), 0.0, motion = motion, rawPose = raw, camera = camera))
        return result.copy(analysisMillis = (System.nanoTime()-started)/1e6)
    }
    private fun resetCamera() {
        scene.reset(); referenceSize = null; referenceContext = null; lastSceneSeconds = null
        phone.reset(); stability.reset(); acquisition.reset(); lastCameraReady = false; grips.reset()
    }
    private fun persistRecords() {
        if (!persistWorkouts) return
        runtime.currentSet?.let { record ->
            if (!runtime.isFinished && (draftSetId != record.id || draftRevision != runtime.revision)) {
                runtime.draftDeltaJson?.let { recordStore.enqueue(WorkoutRecordCodec.json.decodeFromString<WorkoutSetRecord>(it), finalized = false) }
                draftSetId = record.id; draftRevision = runtime.revision
            }
        }
        runtime.latestCompletedSet?.takeIf { it.id != queuedSetId && it.hasPractice }?.let { record ->
            recordStore.enqueue(record, finalized = true); queuedSetId = record.id
            history = (listOf(record)+history.filter { it.id != record.id }).take(50)
        }
        if (System.nanoTime() >= retryAfterNanos) runCatching { recordStore.flushPending() }
            .onSuccess { storageError = null; retryAfterNanos = 0L }
            .onFailure { storageError = "Workout save will retry: ${it.message}"; retryAfterNanos = System.nanoTime()+1_000_000_000L }
    }
    fun refreshRecords(result: Result): Result {
        persistRecords()
        // Keep replay results in memory only, including every completed set.
        if (!persistWorkouts) runtime.latestCompletedSet?.takeIf { it.hasPractice }?.let { record ->
            history = (listOf(record)+history.filter { it.id != record.id }).take(50)
        }
        val record = runtime.currentSet
        return result.copy(snapshot = snapshot, reps = record?.reps.orEmpty(), history = history, storageError = storageError,
            currentSet = record, motion = runtime.snapshot, progress = runtime.snapshot.targetProgress ?: 0.0)
    }
    fun tick(sourceNow: Double, result: Result): Result {
        runtime.tick(sourceNow)
        if (latestSourceSeconds?.let { sourceNow-it > ExerciseCounter.maximumGapSeconds } == true) {
            issue = "waitingForFrame"; lastCameraReady = false
        }
        return refreshRecords(result)
    }
    fun stop(reason: String = "manual") { runtime.finish(reason); persistRecords() }
    fun nextSet() { runtime.nextSet() }
    fun interrupt(reason: LiveSetSession.EndReason) { runtime.interrupt(reason.name); persistRecords() }
    fun startManually() { if (lastCameraReady) runtime.requestManualStart() }
    private fun cue(state: MotionWorkoutRuntime.Snapshot): String = when {
        issue == "cameraPlacementNeeded" -> "Phone at chest height · aim level"
        state.phase == LiveSetSession.Phase.finished -> "Set finished"
        state.reason in listOf("calibratingOverheadHands", "overheadHandsNeeded", "seekingTop", "calibratingTop") ->
            if (state.exercise == ExerciseCounter.Exercise.pullUp) "Hold a full hang" else "Hold top support"
        !state.observationUsable -> "Show shoulders, elbows and wrists"
        state.targetReached -> if (state.exercise == ExerciseCounter.Exercise.pullUp) "Lower with control" else "Push up"
        state.phase == LiveSetSession.Phase.idle -> if (state.exercise == ExerciseCounter.Exercise.pullUp) "Hold a full hang" else "Hold top support"
        else -> if (state.exercise == ExerciseCounter.Exercise.pullUp) "Pull up" else "Lower with control"
    }
    override fun close() {
        try { stop(if (replay) "endOfInput" else "appInactive"); retryAfterNanos = 0L; persistRecords() }
        finally { poses.close(); grips.close(); scene.close() }
    }
}
