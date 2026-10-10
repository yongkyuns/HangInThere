package dev.hanginthere.android.ui

import android.util.Size
import android.os.SystemClock
import kotlinx.coroutines.delay
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.hardware.camera2.CameraCharacteristics
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hanginthere.android.session.WorkoutViewModel
import dev.hanginthere.core.*

@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
@Composable
fun WorkoutScreen(model: WorkoutViewModel, allowCamera: Boolean = true) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current; val owner = LocalLifecycleOwner.current
    var front by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(WorkoutExerciseSelection.automatic) }
    var side by remember { mutableStateOf(ArmMeasurement.Side.left) }
    var resumed by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var manual by remember { mutableStateOf(false) }
    var firstTap by remember { mutableStateOf<Point2D?>(null) }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) model.replay(uri, selection, side) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumed = true
            if (event == Lifecycle.Event.ON_STOP) { resumed = false; model.suspendForBackground() }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(front, selection, side, state.replay, resumed, state.interrupted, allowCamera) {
        var provider: ProcessCameraProvider? = null
        var disposed = false
        if (allowCamera && !state.replay && resumed && !state.interrupted) {
            val token = model.startLive(selection, side)
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                if (!disposed) {
                    runCatching {
                        val camera = future.get(); provider = camera
                        val analysis = ImageAnalysis.Builder().setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(Size(960, 540), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build())
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                        camera.unbindAll()
                        val bound = camera.bindToLifecycle(owner, if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                        val timebase = Camera2CameraInfo.from(bound.cameraInfo).getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
                        if (timebase == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) {
                            analysis.setAnalyzer(model.inferenceExecutor) { model.cameraFrame(it, token) }
                        } else {
                            camera.unbindAll()
                            model.cameraFailure(IllegalStateException("This camera cannot align frame and motion timestamps. Use video evaluation."))
                        }
                    }.onFailure { model.cameraFailure(it) }
                }
            }, ContextCompat.getMainExecutor(context))
        }
        onDispose { disposed = true; provider?.unbindAll() }
    }
    LaunchedEffect(state.replay, resumed) {
        while (!state.replay && resumed) { delay(100); model.tick(SystemClock.elapsedRealtimeNanos() / 1e9) }
    }
    LaunchedEffect(settings, manual, licenses) { model.pause(settings || manual || licenses) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        state.bitmap?.let { Image(it.asImageBitmap(), contentDescription = "Workout video", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        val pose = state.result?.pose
        Canvas(Modifier.fillMaxSize().pointerInput(manual, pose?.imageSize) {
            if (manual && pose != null) detectTapGestures { offset ->
                val transform = VideoTransform(pose.imageSize.width, pose.imageSize.height, size.width.toDouble(), size.height.toDouble())
                val point = transform.image(Point2D(offset.x.toDouble(), offset.y.toDouble()))
                if (point.x in 0.0..pose.imageSize.width && point.y in 0.0..pose.imageSize.height) {
                    val first = firstTap
                    if (first == null) firstTap = point else { model.confirmManual(first, point); firstTap = null; manual = false }
                }
            }
        }) {
            if (pose != null) {
                val transform = VideoTransform(pose.imageSize.width, pose.imageSize.height, size.width.toDouble(), size.height.toDouble())
                fun screen(point: Point2D): Offset { val p = transform.display(point); return Offset(p.x.toFloat(), p.y.toFloat()) }
                if (pose.people.size == 1) {
                    val person = pose.people.single()
                    val limbs = listOf(PoseJoint.leftShoulder to PoseJoint.leftElbow, PoseJoint.leftElbow to PoseJoint.leftWrist,
                        PoseJoint.rightShoulder to PoseJoint.rightElbow, PoseJoint.rightElbow to PoseJoint.rightWrist,
                        PoseJoint.leftShoulder to PoseJoint.rightShoulder, PoseJoint.leftShoulder to PoseJoint.leftHip,
                        PoseJoint.rightShoulder to PoseJoint.rightHip, PoseJoint.leftHip to PoseJoint.rightHip,
                        PoseJoint.leftHip to PoseJoint.leftKnee, PoseJoint.leftKnee to PoseJoint.leftAnkle,
                        PoseJoint.rightHip to PoseJoint.rightKnee, PoseJoint.rightKnee to PoseJoint.rightAnkle)
                    limbs.forEach { (a, b) ->
                        val from = person.landmark(a); val to = person.landmark(b)
                        if (from != null && to != null) drawLine(Color.Cyan.copy(alpha = 0.8f), screen(from.position), screen(to.position), 3.dp.toPx(), StrokeCap.Round)
                    }
                    person.landmarks.filter { it.isVisible() }.forEach { drawCircle(Color.White, 2.dp.toPx(), screen(it.position)) }
                }
                state.result?.snapshot?.referenceEdge?.let { drawLine(Color(0xFFFFD54F), screen(it.a), screen(it.b), 3.dp.toPx(), StrokeCap.Round) }
                firstTap?.let { drawCircle(Color.Yellow, 5.dp.toPx(), screen(it)) }
            }
        }
        Column(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(16.dp).background(Color.Black.copy(alpha = 0.5f))) {
            Text(state.result?.snapshot?.exercise?.title ?: if (selection == WorkoutExerciseSelection.automatic) "Auto" else if (selection == WorkoutExerciseSelection.dip) "Dips" else "Pull-ups", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = Color.White)
            Text("${state.result?.snapshot?.observedMovements ?: 0}", Modifier.padding(horizontal = 12.dp), fontSize = 42.sp, color = Color.White)
        }
        Column(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp)) {
            FilledTonalButton(onClick = { settings = true }) { Text("Settings") }
            if (allowCamera && !state.replay) FilledTonalButton(onClick = { front = !front }, enabled = state.result?.snapshot?.phase != LiveSetSession.Phase.running) { Text("Flip") }
        }
        state.result?.snapshot?.countdownSeconds?.let { Text("$it", Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.5f)).padding(24.dp), fontSize = 72.sp, color = Color.White) }
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(20.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            val snapshot = state.result?.snapshot
            val running = snapshot?.phase == LiveSetSession.Phase.running
            val message = when {
                state.error != null -> state.error!!
                manual -> if (firstTap == null) "Tap one end of the visible edge" else "Tap the other end"
                state.finished -> "Video evaluated · ${snapshot?.observedMovements ?: 0} movements"
                running -> state.result?.cue.orEmpty()
                snapshot?.setupIssue != null -> setupText(snapshot.setupIssue!!)
                else -> "Hold a hang or top support · show both hands"
            }
            Text(message, Modifier.background(Color.Black.copy(alpha = 0.6f)).padding(10.dp), color = Color.White)
            WorkoutActions(state, onFinish = model::finishSet, onNext = model::nextSet,
                onRestart = { model.startLive(selection, side) })
        }
    }
    if (settings) AlertDialog(onDismissRequest = { settings = false }, title = { Text("Workout settings") }, text = {
        Column {
            Text("Exercise")
            Row { listOf(WorkoutExerciseSelection.automatic to "Auto", WorkoutExerciseSelection.pullUp to "Pull-up", WorkoutExerciseSelection.dip to "Dip").forEach { (choice, title) -> TextButton(onClick = { selection = choice; if (state.replay) model.changeReplaySelection(choice, side) }, enabled = state.replay || state.result?.snapshot?.phase != LiveSetSession.Phase.running) { Text(if (selection == choice) "✓ $title" else title) } } }
            Text("Reference grip")
            Row { ArmMeasurement.Side.entries.forEach { choice -> TextButton(onClick = { side = choice; if (state.replay) model.changeReplaySelection(selection, choice) }, enabled = state.replay || state.result?.snapshot?.phase != LiveSetSession.Phase.running) { Text(if (side == choice) "✓ ${choice.name}" else choice.name) } } }
            TextButton(onClick = { settings = false; firstTap = null; manual = true }) { Text("Set visible reference edge") }
            TextButton(onClick = { settings = false; videoPicker.launch(arrayOf("video/*")) }) { Text("Evaluate a video") }
            if (state.replay) TextButton(onClick = { settings = false; model.startLive(selection, side) }) { Text("Return to live camera") }
            Text("Counts are observed movements. Depth and lockout form are not yet verified.")
            TextButton(onClick = { settings = false; licenses = true }) { Text("Open-source licenses") }
        }
    }, confirmButton = { TextButton(onClick = { settings = false }) { Text("Done") } })
    if (licenses) AlertDialog(onDismissRequest = { licenses = false }, title = { Text("Open-source licenses") }, text = {
        val notice = remember { listOf("hdr-notice.txt", "ffmpeg-LGPL-2.1.txt", "zimg-WTFPL.txt").joinToString("\n\n") {
            context.assets.open("licenses/$it").bufferedReader().use { reader -> reader.readText() }
        } }
        Text(notice, Modifier.verticalScroll(rememberScrollState()))
    }, confirmButton = { TextButton(onClick = { licenses = false }) { Text("Done") } })
}

/** Terminal replay state takes precedence over any previously rendered counter snapshot. */
@Composable
internal fun WorkoutActions(state: WorkoutViewModel.State, onFinish: () -> Unit, onNext: () -> Unit, onRestart: () -> Unit) {
    if (state.replay && state.finished) {
        FilledTonalButton(onClick = onNext) { Text("Evaluate again") }
    } else if (state.result?.snapshot?.phase == LiveSetSession.Phase.running) {
        LinearProgressIndicator(progress = { (state.result.progress).toFloat() }, Modifier.fillMaxWidth().padding(vertical = 10.dp),
            color = if (state.result.progress == 1.0) Color.Green else Color.Cyan)
        FilledTonalButton(onClick = onFinish) { Text("End set") }
    } else if (state.interrupted && !state.replay) {
        FilledTonalButton(onClick = onRestart) { Text("Start next set") }
    } else if (state.result?.snapshot?.phase == LiveSetSession.Phase.finished) {
        FilledTonalButton(onClick = onNext) { Text(if (state.replay) "Evaluate again" else "Next set") }
    }
}

private fun setupText(issue: String): String = WorkoutSetupGuidance.message(issue)
    ?: "Hold your starting position · show both hands"
