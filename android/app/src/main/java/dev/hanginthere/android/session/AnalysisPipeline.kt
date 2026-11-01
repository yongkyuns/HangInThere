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
    recordStoreOverride: WorkoutRecordStore? = null, private val replay: Boolean = false, private val floorExercise: ExerciseCounter.Exercise? = null) : Closeable {
    data class Result(val pose: PoseResult, val snapshot: WorkoutRuntime.Snapshot, val progress: Double, val cue: String,
        val analysisMillis: Double, val reps: List<RepRecord> = emptyList(), val history: List<WorkoutSetRecord> = emptyList(),
        val storageError: String? = null, val currentSet: WorkoutSetRecord? = null,
        val motion: MotionWorkoutRuntime.Snapshot? = null,
        val targetReached: Boolean = false, val observationUsable: Boolean = false,
        val rawPose: PoseResult = pose, val camera: OverheadPullUpMotion.Camera = OverheadPullUpMotion.Camera.unavailable)
    // Replay has no live calendar timestamps or recorded phone-motion sensor.
    val runtime = MotionWorkoutRuntime(selection, automaticWorkouts,
        nowEpochMilliseconds = { if (replay) null else System.currentTimeMillis() }, newSetId = { UUID.randomUUID().toString() })
    private val isFloor get() = floorExercise != null
    private val athlete = BodyAthleteSelection(when(floorExercise) { ExerciseCounter.Exercise.pushUp -> BodyAthleteAssociation.Profile.pushUp
        ExerciseCounter.Exercise.sitUp, ExerciseCounter.Exercise.legRaise, ExerciseCounter.Exercise.lyingLegRaise -> BodyAthleteAssociation.Profile.projectedTorso
        else -> BodyAthleteAssociation.Profile.upright })
    private val lyingLegRaises = if (floorExercise == ExerciseCounter.Exercise.lyingLegRaise) LyingLegRaiseWorkout(UUID.randomUUID().toString(), if (replay) null else System.currentTimeMillis()) else null
    private val legRaises = if (floorExercise == ExerciseCounter.Exercise.legRaise) HangingLegRaiseWorkout(UUID.randomUUID().toString(), if (replay) null else System.currentTimeMillis()) else null
    private val sitUps = if (floorExercise == ExerciseCounter.Exercise.sitUp) SideViewSitUpWorkout(UUID.randomUUID().toString(), if (replay) null else System.currentTimeMillis()) else null
    private val pushUps = if (floorExercise == ExerciseCounter.Exercise.pushUp)
        SideViewPushUpWorkout(UUID.randomUUID().toString(), if (replay) null else System.currentTimeMillis(), true) else null
    private data class FloorState(val title: String, val progress: Double?, val targetReached: Boolean,
        val observationUsable: Boolean, val setStarted: Boolean, val setCycles: Int)
    private var floorState: FloorState? = null
    private fun observeFloor(pose: PoseResult, camera: SideViewPushUpMotion.Camera): FloorState =
        lyingLegRaises?.observe(pose,camera)?.let { FloorState(it.title,it.progress,it.targetReached,it.observationUsable,it.setStarted,it.setCycles) }
            ?: legRaises?.observe(pose,camera)?.let { FloorState(it.title,it.progress,it.targetReached,it.observationUsable,it.setStarted,it.setCycles) }
            ?: sitUps?.observe(pose, camera)?.let { FloorState(it.title,it.progress,it.targetReached,it.observationUsable,it.setStarted,it.setCycles) }
            ?: checkNotNull(pushUps).observe(pose,camera).let { FloorState(it.title,it.progress,it.targetReached,it.observationUsable,it.setStarted,it.setCycles) }
    private fun finishFloor(reason: String) { lyingLegRaises?.finish(reason); legRaises?.finish(reason); sitUps?.finish(reason); pushUps?.finish(reason) }

    private var floorRecord: WorkoutSetRecord? = null
    private var floorCompleted: WorkoutSetRecord? = null
    private val hasStarted get() = if (isFloor) floorState?.setStarted == true else runtime.hasStarted
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
        if (isFloor) return WorkoutRuntime.Snapshot(checkNotNull(floorExercise), floorExercise,
            if (floorState?.setStarted == true) LiveSetSession.Phase.running else LiveSetSession.Phase.idle,
            floorState?.setCycles ?: 0, null, null, AutomaticPullUpBar.State.lookingForGrip, phone.state, stability.state,
            issue, generation, isReplayReview = replay, latestCompletedSet = floorCompleted, activeSetId = floorRecord?.id)
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
        val raw = body.copy(gripObservation = if (!isFloor && runtime.snapshot.exercise == ExerciseCounter.Exercise.pullUp)
            grips.observe(bitmap, body) else null)
        val decision = athlete.observe(raw)
        val pose = checkNotNull(athlete.selectedPose)
        val seconds = pose.timestamp.seconds
        latestSourceSeconds = seconds
        val motionFresh = attitude != null && seconds-attitude.seconds in 0.0..0.5
        val placementReady = replay || attitude?.let { WorkoutCameraPlacement.isUsable(WorkoutCameraPlacement.attitudeTiltDegrees(it.quaternion)) } == true
        val associated = decision.status == BodyAthleteAssociation.Status.associated
        val eligible = placementReady && motionFresh && associated && (isFloor || PullUpArmMeasurement(pose).arms.size == 2) && !paused
        val contextChanged = referenceSize != null && (referenceSize != pose.imageSize || referenceContext != (pose.backend to pose.requestRevision))
        if (contextChanged) {
            if (isFloor) finishFloor("setupInvalidated") else runtime.interrupt("setupInvalidated"); resetCamera()
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
            stability.isReady(seconds), !hasStarted, eligible && phone.state == PhoneOrientationStability.State.stable)
        // Actual displacement revokes private preparation and requires a new image epoch.
        if (moved && !hasStarted) {
            runtime.resetPreparation(); resetCamera()
        }
        if ((needsReference || moved) && !hasStarted && eligible) {
            runtime.resetPreparation(); scene.freeze(bitmap); referenceSize = pose.imageSize; referenceContext = pose.backend to pose.requestRevision
            phone.calibrate(attitude!!.quaternion, attitude.seconds); stability.calibrate(pose.imageSize)
            acquisition.beginReference(pose.timestamp.value, pose.timestamp.timescale)
            lastSceneSeconds = null; generation++; moved = false
        }
        lastCameraReady = referenceSize != null && motionFresh && placementReady &&
            phone.state == PhoneOrientationStability.State.stable && stability.isReady(seconds) && acquisition.hasIndependentEvidence && !paused
        val camera = when { moved -> OverheadPullUpMotion.Camera.moved
            lastCameraReady -> OverheadPullUpMotion.Camera.ready; else -> OverheadPullUpMotion.Camera.unavailable }
        if (isFloor) {
            floorState = observeFloor(raw, SideViewPushUpMotion.Camera.valueOf(camera.name))
            issue = when { !placementReady -> "cameraPlacementNeeded"; !lastCameraReady -> "sceneStabilityNeeded"; else -> null }
            val title = when { !placementReady -> if (floorExercise == ExerciseCounter.Exercise.legRaise) "Level phone · side or three-quarter view" else if (floorExercise in listOf(ExerciseCounter.Exercise.sitUp, ExerciseCounter.Exercise.lyingLegRaise)) "Low, level phone · side view" else "Low, level phone · front or side"; !lastCameraReady -> "Keep the phone still"; else -> checkNotNull(floorState).title }
            val result = refreshRecords(Result(pose, snapshot, floorState?.progress ?: 0.0, title, 0.0, rawPose = raw, camera = camera))
            return result.copy(analysisMillis = (System.nanoTime()-started)/1e6)
        }
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
        if (isFloor) {
            while (true) {
                val write = lyingLegRaises?.nextWrite()?.let { it.record to it.finalized }
                    ?: legRaises?.nextWrite()?.let { it.record to it.finalized }
                    ?: sitUps?.nextWrite()?.let { it.record to it.finalized }
                    ?: pushUps?.nextWrite()?.let { it.record to it.finalized } ?: break
                if (persistWorkouts) recordStore.enqueue(write.first, finalized = write.second)
                if (write.second) {
                    floorCompleted = write.first; floorRecord = null
                    history = (listOf(write.first)+history.filter { it.id != write.first.id }).take(50)
                } else floorRecord = write.first
                if (lyingLegRaises != null) lyingLegRaises.acknowledgeWrite() else if (legRaises != null) legRaises.acknowledgeWrite() else if (sitUps != null) sitUps.acknowledgeWrite() else checkNotNull(pushUps).acknowledgeWrite()
            }
            if (persistWorkouts && System.nanoTime() >= retryAfterNanos) runCatching { recordStore.flushPending() }
                .onSuccess { storageError = null; retryAfterNanos = 0L }
                .onFailure { storageError = "Workout save will retry: ${it.message}"; retryAfterNanos = System.nanoTime()+1_000_000_000L }
            return
        }
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
        if (isFloor) return result.copy(snapshot = snapshot, reps = floorRecord?.reps.orEmpty(), history = history,
            storageError = storageError, currentSet = floorRecord, progress = floorState?.progress ?: 0.0,
            targetReached = floorState?.targetReached == true, observationUsable = floorState?.observationUsable == true,
            cue = if (issue == "waitingForFrame") "Waiting for body detection" else result.cue)
        // Keep replay results in memory only, including every completed set.
        if (!persistWorkouts) runtime.latestCompletedSet?.takeIf { it.hasPractice }?.let { record ->
            history = (listOf(record)+history.filter { it.id != record.id }).take(50)
        }
        val record = runtime.currentSet
        return result.copy(snapshot = snapshot, reps = record?.reps.orEmpty(), history = history, storageError = storageError,
            currentSet = record, motion = runtime.snapshot, progress = runtime.snapshot.targetProgress ?: 0.0,
            targetReached = runtime.snapshot.targetReached, observationUsable = runtime.snapshot.observationUsable)
    }
    fun tick(sourceNow: Double, result: Result): Result {
        if (!isFloor) runtime.tick(sourceNow)
        if (latestSourceSeconds?.let { sourceNow-it > ExerciseCounter.maximumGapSeconds } == true) {
            issue = "waitingForFrame"; lastCameraReady = false
            if (isFloor) { finishFloor("sourceFramesUnavailable"); floorState = null }
        }
        return refreshRecords(result)
    }
    fun stop(reason: String = "manual") { if (isFloor) { finishFloor(reason); floorState = null } else runtime.finish(reason); persistRecords() }
    fun nextSet() { if (isFloor) { stop(); floorCompleted = null } else runtime.nextSet() }
    fun interrupt(reason: LiveSetSession.EndReason) { if (isFloor) stop(reason.name) else { runtime.interrupt(reason.name); persistRecords() } }
    fun startManually() { if (lastCameraReady && !isFloor) runtime.requestManualStart() }
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
