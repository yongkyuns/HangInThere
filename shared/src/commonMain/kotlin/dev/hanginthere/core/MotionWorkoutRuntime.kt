package dev.hanginthere.core

/** Live/replay lifecycle around the same source-owned controllers used by iOS.
 * Native capture supplies independent camera evidence. Grip and apparatus never gate counting. */
class MotionWorkoutRuntime(
    private val selection: WorkoutExerciseSelection = WorkoutExerciseSelection.automatic,
    private val automaticWorkouts: Boolean = true,
    private val nowEpochMilliseconds: () -> Long? = { null },
    private val newSetId: () -> String = { "motion-${kotlin.random.Random.nextLong().toString(16)}" },
) {
    @kotlinx.serialization.Serializable
    data class Snapshot(val exercise: ExerciseCounter.Exercise, val phase: LiveSetSession.Phase,
        val movements: Int, val progress: Double, val targetProgress: Double?, val targetReached: Boolean,
        val reason: String, val observationUsable: Boolean, val analyzedFrames: Int, val endReason: String?)
    private val identity = AutomaticExerciseDetection()
    private var exercise = selection.exercise ?: ExerciseCounter.Exercise.pullUp
    private var pull: OverheadPullUpWorkout? = null
    private var dip: RelativeDipWorkout? = null
    private var completed: WorkoutSetRecord? = null
    private var finalizedId: String? = null
    private var analyzedBefore = 0
    private var manualRequired = false
    private var pendingManual = false
    private var lastSeconds: Double? = null
    var snapshot = emptySnapshot(); private set
    val latestCompletedSet get() = completed
    val currentSet get() = if (hasStarted) pull?.record(true) ?: dip?.record(true) else null
    val draftDeltaJson get() = if (hasStarted) pull?.draftDeltaJson() ?: dip?.draftDeltaJson() else null
    val revision get() = pull?.revision ?: dip?.revision ?: 0
    val totalAnalyzedFrames get() = analyzedBefore + snapshot.analyzedFrames
    val hasStarted get() = pull?.hasStarted == true || dip?.hasStarted == true
    val isFinished get() = pull?.isFinished == true || dip?.isFinished == true

    private fun emptySnapshot() = Snapshot(exercise, LiveSetSession.Phase.idle, 0, 0.0, null, false,
        "waitingForFrame", false, 0, null)
    private fun createOwners() {
        val id = newSetId(); val tracked = nowEpochMilliseconds()
        pull = if (exercise == ExerciseCounter.Exercise.pullUp) OverheadPullUpWorkout(id, tracked) else null
        dip = if (exercise == ExerciseCounter.Exercise.dip) RelativeDipWorkout(id, tracked) else null
    }
    fun observe(source: BodyAthleteSelection, camera: OverheadPullUpMotion.Camera,
        automaticStart: Boolean = true): Snapshot {
        val frame = checkNotNull(source.frame)
        if (isFinished) {
            if (!automaticWorkouts || manualRequired) return snapshot
            nextSet()
        }
        val pose = frame.pose
        lastSeconds = pose.timestamp.seconds
        if (!hasStarted && selection == WorkoutExerciseSelection.automatic) {
            identity.observe(pose, null)
            val arms = PullUpArmMeasurement(pose).arms
            val mayPrepareDip = arms.size == 2 && arms.all { it.wrist.y > it.shoulder.y }
            val target = identity.apparatusCandidate ?: if (mayPrepareDip) ExerciseCounter.Exercise.dip else exercise
            if (target != exercise) { exercise = target; pull = null; dip = null; snapshot = emptySnapshot() }
        }
        if (pull == null && dip == null) createOwners()
        val allowStart = automaticStart && !manualRequired
        val p = pull?.observe(source, camera, automaticWorkouts, allowStart)
        val d = dip?.observe(source, when (camera) {
            OverheadPullUpMotion.Camera.ready -> DipCycleMotion.Camera.ready
            OverheadPullUpMotion.Camera.moved -> DipCycleMotion.Camera.moved
            OverheadPullUpMotion.Camera.unavailable -> DipCycleMotion.Camera.unavailable
        }, automaticWorkouts, allowStart)
        if (pendingManual && camera == OverheadPullUpMotion.Camera.ready) {
            if (pull?.startManually() == true || dip?.startManually() == true) manualRequired = false
        }
        pendingManual = false
        if (hasStarted && !identity.isLocked && selection == WorkoutExerciseSelection.automatic) {
            if (exercise == ExerciseCounter.Exercise.pullUp) identity.confirmObservedOverheadPullUpMovement(pose)
            else identity.confirmObservedDipMovement(pose)
            identity.lock()
        }
        val reason = p?.motion?.reason ?: checkNotNull(d).motion.reason
        snapshot = Snapshot(exercise, when { isFinished -> LiveSetSession.Phase.finished
            hasStarted -> LiveSetSession.Phase.running; else -> LiveSetSession.Phase.idle },
            p?.motion?.cycles ?: checkNotNull(d).motion.cycles,
            p?.motion?.progress ?: checkNotNull(d).motion.progress,
            p?.motion?.targetProgress ?: d?.motion?.targetProgress,
            p?.motion?.targetReached ?: checkNotNull(d).motion.targetReached,
            reason, p?.motion?.observationUsable ?: checkNotNull(d).motion.observationUsable,
            p?.analyzedFrames ?: checkNotNull(d).analyzedFrames, p?.endReason ?: d?.endReason)
        captureCompleted()
        return snapshot
    }
    private fun captureCompleted() {
        if (!isFinished || !hasStarted) return
        val record = pull?.record() ?: checkNotNull(dip).record()
        if (record.id == finalizedId) return
        finalizedId = record.id
        if (record.hasPractice) completed = record.copy(trackedEndedAtEpochMilliseconds =
            nowEpochMilliseconds()?.takeIf { end -> record.trackedAtEpochMilliseconds?.let { end >= it } == true })
    }
    fun finish(reason: String = "manual") {
        if (isFinished) return
        pull?.finish(reason); dip?.finish(reason)
        snapshot = snapshot.copy(phase = LiveSetSession.Phase.finished, endReason = reason, progress = 0.0,
            targetProgress = null, targetReached = false, observationUsable = false)
        manualRequired = reason in listOf("manual", "appInactive", "cameraFailure", "endOfInput", "sourceEnded")
        captureCompleted()
    }
    fun interrupt(reason: String) {
        pull?.interrupt(reason); dip?.interrupt(reason)
        if (hasStarted) finish(reason)
        else { pull = null; dip = null; snapshot = emptySnapshot() }
    }
    /** A tick can invalidate stale observations, but cannot manufacture motion or release. */
    fun tick(seconds: Double) {
        if (hasStarted && !isFinished && lastSeconds?.let { seconds-it > ExerciseCounter.maximumGapSeconds } == true) {
            interrupt("trackingLost")
        }
    }
    fun nextSet() {
        if (!isFinished && hasStarted) finish()
        analyzedBefore += snapshot.analyzedFrames
        pull = null; dip = null; identity.reset(); manualRequired = false; pendingManual = false
        snapshot = emptySnapshot()
    }
    /** Process the next fresh source frame before starting; a stale UI snapshot is insufficient. */
    fun requestManualStart() { if (!hasStarted && !isFinished) pendingManual = true }
    fun resetPreparation() { if (!hasStarted) { pull = null; dip = null; snapshot = emptySnapshot() } }
}
