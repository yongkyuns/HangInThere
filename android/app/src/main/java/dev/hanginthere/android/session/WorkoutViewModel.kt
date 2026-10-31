package dev.hanginthere.android.session

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.camera.core.ImageProxy
import androidx.lifecycle.AndroidViewModel
import dev.hanginthere.android.camera.*
import dev.hanginthere.android.replay.VideoFrames
import dev.hanginthere.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Generation invalidation is immediate; queued/in-flight camera and replay results cannot escape. */
class WorkoutViewModel(application: Application) : AndroidViewModel(application) {
    data class State(val bitmap: Bitmap? = null, val result: AnalysisPipeline.Result? = null, val error: String? = null,
        val replay: Boolean = false, val finished: Boolean = false, val selection: WorkoutExerciseSelection = WorkoutExerciseSelection.automatic,
        val side: ArmMeasurement.Side = ArmMeasurement.Side.left, val interrupted: Boolean = false,
        val replayOutcome: ReplayOutcome.Kind? = null)
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    // Main/UI owner survives recomposition and Activity recreation. Inference
    // does not read or mutate this presentation clock/policy.
    val startFeedback = WorkoutStartFeedback()
    val completionFeedback = WorkoutCompletionFeedback()
    val inferenceExecutor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0)
    // EOF can precede the main-thread publication. Keep controls from replacing
    // a terminal replay with an idle snapshot during that handoff.
    private val terminalReplayGeneration = AtomicLong(-1)
    private var pipeline: AnalysisPipeline? = null
    private val pendingUI = AtomicReference<Pair<Long, State>?>(null)
    private val uiScheduled = AtomicBoolean(false)
    private val tickScheduled = AtomicBoolean(false)
    private val attitude = PhoneAttitude(application)
    @Volatile private var paused = false
    private data class Control(val token: Long, val action: (AnalysisPipeline) -> Unit)
    private val controls = ArrayBlockingQueue<Control>(16)
    private val controlLock = Any()
    private var replayRunning = false
    private var replayUri: Uri? = null
    private fun markReplayTerminal(token: Long) {
        terminalReplayGeneration.updateAndGet { maxOf(it, token) }
    }
    private fun control(action: (AnalysisPipeline) -> Unit) {
        val command = Control(generation.get(), action)
        if (terminalReplayGeneration.get() == command.token) return
        val queued = synchronized(controlLock) {
            if (replayRunning) {
                if (!controls.offer(command)) mutableState.value = state.value.copy(error = "Too many pending workout actions")
                true
            } else false
        }
        if (!queued) inferenceExecutor.execute { if (command.token == generation.get()) pipeline?.let { applyControl(command, it) } }
    }
    private fun drainControls(token: Long, active: AnalysisPipeline) {
        while (true) {
            val command = synchronized(controlLock) {
                if (generation.get() == token) controls.poll() else null
            } ?: break
            if (command.token == token && generation.get() == token) applyControl(command, active)
        }
    }
    private fun applyControl(command: Control, active: AnalysisPipeline) {
        if (terminalReplayGeneration.get() == command.token) return
        command.action(active)
        val current = state.value
        val snapshot = active.snapshot
        publish(command.token, current.copy(result = current.result?.let { active.refreshRecords(it) },
            finished = current.replay && (current.finished || snapshot.phase == LiveSetSession.Phase.finished)))
    }
    private fun publish(token: Long, value: State) {
        pendingUI.set(token to value)
        if (uiScheduled.compareAndSet(false, true)) main.post(::drainUI)
    }
    private fun drainUI() {
        pendingUI.getAndSet(null)?.let { (token, value) -> if (generation.get() == token) mutableState.value = value }
        uiScheduled.set(false)
        if (pendingUI.get() != null && uiScheduled.compareAndSet(false, true)) main.post(::drainUI)
    }
    fun tick(sourceNow: Double) {
        if (state.value.replay || !tickScheduled.compareAndSet(false, true)) return
        val token = generation.get()
        inferenceExecutor.execute {
            try {
                if (token != generation.get()) {
                    return@execute
                }
                val result = state.value.result
                val active = pipeline
                if (result == null || active == null) {
                    return@execute
                }
                publish(token, state.value.copy(result = active.tick(sourceNow, result)))
            } finally { tickScheduled.set(false) }
        }
    }
    fun finishSet() { control { it.stop() } }
    fun nextSet() {
        val current = state.value
        if (current.replay) replayUri?.let { replay(it, current.selection, current.side) }
        else control { it.nextSet() }
    }
    fun changeReplaySelection(selection: WorkoutExerciseSelection, side: ArmMeasurement.Side) {
        replayUri?.takeIf { state.value.replay }?.let { replay(it, selection, side) }
    }
    fun startManually() { control { it.startManually() } }
    /** Clear retained replay/interruption before the screen binds a new camera. */
    fun prepareLive(selection: WorkoutExerciseSelection) {
        generation.incrementAndGet()
        synchronized(controlLock) { replayRunning = false; controls.clear() }
        replayUri = null
        mutableState.value = State(selection = selection, side = state.value.side)
    }
    fun startLive(selection: WorkoutExerciseSelection = state.value.selection, side: ArmMeasurement.Side = state.value.side): Long {
        val token = generation.incrementAndGet()
        synchronized(controlLock) { replayRunning = false; controls.clear() }
        mutableState.value = State(selection = selection, side = side)
        attitude.start()
        inferenceExecutor.execute {
            if (token != generation.get()) return@execute
            pipeline?.close(); pipeline = null
            if (token != generation.get()) return@execute
            try { pipeline = AnalysisPipeline(getApplication(), selection, side) }
            catch (error: Throwable) { publish(token, mutableState.value.copy(error = error.message ?: "Unable to initialize analysis")) }
        }
        return token
    }
    fun cameraFrame(image: ImageProxy, token: Long) {
        try {
            if (token != generation.get()) return
            val raw = CanonicalPixels.yuv(image.width, image.height, image.cropRect, image.planes.map { CanonicalPixels.Plane(it.buffer, it.rowStride, it.pixelStride) })
            val pixels = CanonicalPixels.upright(raw, image.imageInfo.rotationDegrees)
            if (pixels !== raw) raw.recycle()
            try {
                val result = pipeline?.process(pixels, image.imageInfo.timestamp / 1000, attitude.sampleAt(image.imageInfo.timestamp / 1e9), paused) ?: return
                // UI owns one immutable snapshot; inference never mutates its pixels.
                val display = pixels.copy(Bitmap.Config.ARGB_8888, false)
                publish(token, mutableState.value.copy(bitmap = display, result = result, error = null))
            } finally { pixels.recycle() }
        } catch (error: Throwable) { publish(token, mutableState.value.copy(error = error.message ?: "Camera analysis failed")) }
        finally { image.close() }
    }
    fun replay(uri: Uri, selection: WorkoutExerciseSelection = state.value.selection, side: ArmMeasurement.Side = state.value.side) {
        replayUri = uri
        val token = generation.incrementAndGet(); attitude.close()
        synchronized(controlLock) { replayRunning = true; controls.clear() }
        mutableState.value = State(replay = true, selection = selection, side = side)
        inferenceExecutor.execute {
            // Keep the latest dispatch's replayRunning flag when skipping obsolete work.
            if (token != generation.get()) return@execute
            pipeline?.close(); pipeline = null
            if (token != generation.get()) return@execute
            var latest = State(replay = true, selection = selection, side = side)
            try {
                pipeline = AnalysisPipeline(getApplication(), selection, side, persistWorkouts = false, replay = true)
                val active = requireNotNull(pipeline)
                // One uncut source pass includes setup, every set, rest and release.
                var sourceEnded = false
                VideoFrames(getApplication(), uri, cancelled = { generation.get() != token }).use { reader ->
                    while (generation.get() == token) {
                        drainControls(token, active)
                        while (paused && generation.get() == token) { drainControls(token, active); Thread.sleep(50) }
                        val frame = reader.next { generation.get() != token }
                        if (frame == null) { sourceEnded = generation.get() == token; break }
                        frame.use {
                            val time = it.presentationTimeUs / 1e6
                            // Recorded video has an explicit fixed-camera assumption. Actual
                            // image registration must still independently qualify every frame.
                            val fixed = PhoneAttitude.Sample(requireNotNull(PhoneOrientationStability.Quaternion.create(0.0, 0.0, 0.0, 1.0)), time)
                            val result = active.process(it.bitmap, it.presentationTimeUs, fixed)
                            latest = latest.copy(bitmap = it.bitmap.copy(Bitmap.Config.ARGB_8888, false), result = result)
                            publish(token, latest)
                        }
                    }
                }
                if (generation.get() == token) {
                    val outcome = ReplayOutcome.atEnd(active.totalAnalyzedFrames, active.hasQualifiedCamera, sourceEnded,
                        active.snapshot.setupIssue, active.endReason)
                    active.stop("endOfInput")
                    markReplayTerminal(token)
                    latest = latest.copy(result = latest.result?.let { active.refreshRecords(it) }, finished = true,
                        error = outcome.message, interrupted = outcome.kind == ReplayOutcome.Kind.interrupted, replayOutcome = outcome.kind)
                    publish(token, latest)
                }
            } catch (error: Throwable) {
                val active = pipeline
                active?.interrupt(LiveSetSession.EndReason.cameraFailure)
                val outcome = ReplayOutcome(ReplayOutcome.Kind.analysisFailed, error.message ?: "Video analysis failed")
                markReplayTerminal(token)
                publish(token, latest.copy(result = latest.result?.let { active?.refreshRecords(it) ?: it },
                    error = outcome.message, replay = true, finished = true, interrupted = true, replayOutcome = outcome.kind))
            }
            finally {
                synchronized(controlLock) { if (generation.get() == token) replayRunning = false }
                pipeline?.let { drainControls(token, it) }
            }
        }
    }
    fun cameraFailure(error: Throwable) {
        mutableState.value = state.value.copy(error = error.message ?: "Camera unavailable")
        control { active -> active.interrupt(LiveSetSession.EndReason.cameraFailure) }
    }
    fun pause(value: Boolean) { paused = value }
    fun suspendForBackground() {
        val token = generation.incrementAndGet(); attitude.close()
        val last = state.value
        if (last.replay) markReplayTerminal(token)
        mutableState.value = last.copy(interrupted = true, finished = true, error = if (last.replay) "Video evaluation interrupted · open the video again" else null)
        inferenceExecutor.execute {
            val active = pipeline
            if (active != null) {
                active.interrupt(LiveSetSession.EndReason.appInactive)
                publish(token, last.copy(result = last.result?.let { active.refreshRecords(it) }, interrupted = true, finished = true,
                    error = if (last.replay) "Video evaluation interrupted · open the video again" else null))
                active.close(); pipeline = null
            }
        }
    }
    fun stop() { generation.incrementAndGet(); attitude.close(); controls.clear(); inferenceExecutor.execute { pipeline?.close(); pipeline = null } }
    override fun onCleared() { stop(); inferenceExecutor.shutdown(); super.onCleared() }
}
