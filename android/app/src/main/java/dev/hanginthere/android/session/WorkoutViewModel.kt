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
        val side: ArmMeasurement.Side = ArmMeasurement.Side.left, val interrupted: Boolean = false)
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    val inferenceExecutor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0)
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
    private fun control(action: (AnalysisPipeline) -> Unit) {
        val command = Control(generation.get(), action)
        val queued = synchronized(controlLock) {
            if (replayRunning) {
                if (!controls.offer(command)) mutableState.value = state.value.copy(error = "Too many pending workout actions")
                true
            } else false
        }
        if (!queued) inferenceExecutor.execute { if (command.token == generation.get()) pipeline?.let(command.action) }
    }
    private fun drainControls(token: Long, active: AnalysisPipeline) {
        while (true) {
            val command = synchronized(controlLock) {
                if (generation.get() == token) controls.poll() else null
            } ?: break
            if (command.token == token && generation.get() == token) command.action(active)
        }
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
                active.runtime.tick(sourceNow)
                publish(token, state.value.copy(result = result.copy(snapshot = active.runtime.snapshot)))
            } finally { tickScheduled.set(false) }
        }
    }
    fun finishSet() { control { it.runtime.stop() } }
    fun nextSet() { control { it.runtime.nextSet() } }
    fun confirmManual(a: Point2D, b: Point2D) {
        val token = generation.get(); val current = state.value; val pose = current.result?.pose ?: return
        control { active ->
            if (generation.get() != token) return@control
            val role = if (current.result?.snapshot?.exercise == ExerciseCounter.Exercise.dip || current.selection == WorkoutExerciseSelection.dip) {
                if (current.side == ArmMeasurement.Side.left) ConfirmedBar.Role.leftDipRail else ConfirmedBar.Role.rightDipRail
            } else {
                ConfirmedBar.Role.pullUpGrip
            }
            val bar = ConfirmedBar(role, ConfirmedBar.Method.manualEdge, BarSegment(a, b), imageSize = pose.imageSize, sourceTime = pose.timestamp)
            val sample = if (current.replay) PhoneAttitude.Sample(requireNotNull(PhoneOrientationStability.Quaternion.create(0.0, 0.0, 0.0, 1.0)), pose.timestamp.seconds) else attitude.latest
            if (bar.isValid && sample != null) active.runtime.confirmManualBar(bar, sample.quaternion, sample.seconds)
        }
    }
    fun startLive(selection: WorkoutExerciseSelection = state.value.selection, side: ArmMeasurement.Side = state.value.side): Long {
        val token = generation.incrementAndGet()
        synchronized(controlLock) { replayRunning = false; controls.clear() }
        mutableState.value = State(selection = selection, side = side)
        attitude.start()
        inferenceExecutor.execute {
            pipeline?.close(); pipeline = null
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
    fun replay(uri: Uri) {
        val selection = state.value.selection; val side = state.value.side
        val token = generation.incrementAndGet(); attitude.close()
        synchronized(controlLock) { replayRunning = true; controls.clear() }
        mutableState.value = State(replay = true, selection = selection, side = side)
        inferenceExecutor.execute {
            pipeline?.close(); pipeline = null
            try {
                pipeline = AnalysisPipeline(getApplication(), selection, side)
                val active = requireNotNull(pipeline)
                var latest = State(replay = true, selection = selection, side = side)
                var acquired = false
                fun runPass(acquireOnly: Boolean) {
                    VideoFrames(getApplication(), uri).use { reader ->
                        while (generation.get() == token) {
                            drainControls(token, active)
                            while (paused && generation.get() == token) { drainControls(token, active); Thread.sleep(50) }
                            val frame = reader.next { generation.get() != token } ?: break
                            frame.use {
                                val time = it.presentationTimeUs / 1e6
                                val virtualAttitude = PhoneAttitude.Sample(requireNotNull(PhoneOrientationStability.Quaternion.create(0.0, 0.0, 0.0, 1.0)), time)
                                val result = active.process(it.bitmap, it.presentationTimeUs, virtualAttitude)
                                latest = latest.copy(bitmap = it.bitmap.copy(Bitmap.Config.ARGB_8888, false), result = result)
                                publish(token, latest)
                                acquired = result.snapshot.referenceEdge != null
                            }
                            if (acquireOnly && acquired) break
                        }
                    }
                }
                runPass(true)
                if (generation.get() == token && acquired && active.rewindReplay()) runPass(false)
                if (generation.get() == token) {
                    active.runtime.stop()
                    latest = latest.copy(result = latest.result?.copy(snapshot = active.runtime.snapshot), finished = true)
                    publish(token, latest)
                }
            } catch (error: Throwable) { publish(token, mutableState.value.copy(error = error.message ?: "Video evaluation failed", replay = true, finished = true)) }
            finally {
                synchronized(controlLock) { if (generation.get() == token) replayRunning = false }
                pipeline?.let { drainControls(token, it) }
            }
        }
    }
    fun cameraFailure(error: Throwable) {
        mutableState.value = state.value.copy(error = error.message ?: "Camera unavailable")
        control { active -> active.runtime.interrupt(LiveSetSession.EndReason.cameraFailure) }
    }
    fun pause(value: Boolean) { paused = value }
    fun suspendForBackground() {
        val token = generation.incrementAndGet(); attitude.close()
        val last = state.value
        mutableState.value = last.copy(interrupted = true, finished = true, error = if (last.replay) "Video evaluation interrupted · open the video again" else null)
        inferenceExecutor.execute {
            val active = pipeline
            if (active != null) {
                active.runtime.interrupt(LiveSetSession.EndReason.appInactive)
                publish(token, last.copy(result = last.result?.copy(snapshot = active.runtime.snapshot), interrupted = true, finished = true,
                    error = if (last.replay) "Video evaluation interrupted · open the video again" else null))
                active.close(); pipeline = null
            }
        }
    }
    fun stop() { generation.incrementAndGet(); attitude.close(); controls.clear(); inferenceExecutor.execute { pipeline?.close(); pipeline = null } }
    override fun onCleared() { stop(); inferenceExecutor.shutdown(); super.onCleared() }
}
