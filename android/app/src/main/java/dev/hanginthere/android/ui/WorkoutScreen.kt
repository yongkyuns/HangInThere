package dev.hanginthere.android.ui

import android.util.Size
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.hardware.display.DisplayManager
import android.view.Surface
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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
fun WorkoutScreen(model: WorkoutViewModel, allowCamera: Boolean = true,
    initialSelection: WorkoutExerciseSelection = WorkoutExerciseSelection.automatic,
    onBack: (() -> Unit)? = null,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current; val owner = LocalLifecycleOwner.current
    val view = LocalView.current
    var displayRotation by remember { mutableIntStateOf(view.display?.rotation ?: Surface.ROTATION_0) }
    var cameraAnalysis by remember { mutableStateOf<ImageAnalysis?>(null) }
    SideEffect { cameraAnalysis?.targetRotation = displayRotation }
    DisposableEffect(view) {
        val displays = context.getSystemService(DisplayManager::class.java)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                view.display?.takeIf { it.displayId == displayId }?.let { displayRotation = it.rotation }
            }
        }
        displayRotation = view.display?.rotation ?: Surface.ROTATION_0
        displays.registerDisplayListener(listener,Handler(Looper.getMainLooper()))
        onDispose { displays.unregisterDisplayListener(listener) }
    }
    var front by remember { mutableStateOf(true) }
    var showPose by remember { mutableStateOf(true) }
    var settings by remember { mutableStateOf(false) }
    var analysis by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(initialSelection) }
    var side by remember { mutableStateOf(ArmMeasurement.Side.left) }
    var resumed by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    val isFloor = state.floorExercise != null
    val isLyingLegRaise = state.floorExercise == ExerciseCounter.Exercise.lyingLegRaise
    val isLegRaise = state.floorExercise == ExerciseCounter.Exercise.legRaise
    val isSitUp = state.floorExercise == ExerciseCounter.Exercise.sitUp
    val completedSet = state.result?.snapshot?.latestCompletedSet
    var afterSetReport by remember(completedSet?.id) { mutableStateOf(completedSet?.let { WorkoutFeedback.summary(it) }) }
    var completedDeleted by remember(completedSet?.id) { mutableStateOf(false) }
    LaunchedEffect(completedSet?.id, analysis, state.result?.storageError) {
        val completed = completedSet ?: return@LaunchedEffect
        try {
            val (deleted, report) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val library = dev.hanginthere.android.session.WorkoutRecordStore.live(context.applicationContext).libraryState()
                val deleted = library.deletions.any { it.setId == completed.id }
                deleted to if (deleted) null else WorkoutFeedback.activitySummary(library, completed.id, System.currentTimeMillis(), java.time.ZoneId.systemDefault().id, java.util.Calendar.getInstance().firstDayOfWeek)
            }
            completedDeleted = deleted; afterSetReport = report
        } catch (failure: Throwable) { if (failure is kotlinx.coroutines.CancellationException) throw failure }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) model.replay(uri, selection, side) }
    val guideKey = "cameraGuide-${state.floorExercise?.name ?: "bars"}"
    val preferences = remember { context.getSharedPreferences("workoutGuidance", android.content.Context.MODE_PRIVATE) }
    var guideSeen by remember(guideKey) { mutableStateOf(preferences.getBoolean(guideKey, false)) }
    val placement = if (isLegRaise) "Steady, level phone · landscape" else if (isFloor) "Low, level phone · landscape" else "Phone at chest height · aim level"
    val viewGuide = when { isLyingLegRaise -> "Side view · shoulder, hip and knees visible"
        isLegRaise -> "Side or three-quarter view · hands, hip and knees visible"
        isSitUp -> "Side view · shoulder, hip and bent knees visible"
        isFloor -> "Front or side · shoulders, elbows, hands and hips visible"
        else -> "Front or rear view · keep both arms visible" }
    val startGuide = when { isLyingLegRaise -> "Lie back with legs extended. Wait for Ready."
        isLegRaise -> "Hang still with legs down. Wait for Ready."
        isSitUp -> "Lie back with knees bent. Wait for Ready."
        isFloor -> "Hold a straight plank. Wait for Ready."
        else -> "Hold a full hang or top support. Wait for Ready." }
    if (!state.replay && !guideSeen) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.systemBarsPadding().padding(24.dp)) {
                TextButton(onClick = { onBack?.invoke() }) { Text("Back") }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Spacer(Modifier.height(12.dp))
                    Text("A clear view. A good start.", style = MaterialTheme.typography.headlineLarge)
                    listOf(android.R.drawable.ic_menu_camera to placement, android.R.drawable.ic_menu_view to viewGuide,
                        android.R.drawable.checkbox_on_background to startGuide).forEach { (icon, text) ->
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(32.dp))
                            Text(text, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    WorkoutTrainingTipCard(state.floorExercise?.name, "prepare")
                }
                Button(onClick = { preferences.edit().putBoolean(guideKey, true).apply(); guideSeen = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Open camera") }
            }
        }
        return
    }
    val voice = rememberWorkoutVoiceCoach(active = !settings && !analysis && !licenses && !state.replay && !state.interrupted && resumed) { command ->
        val snapshot = state.result?.snapshot
        val running = snapshot?.phase == LiveSetSession.Phase.running
        when (command.action) {
            "start", "next" -> when {
                running -> "A set is already running."
                command.exercise != null && command.exercise != (state.floorExercise ?: snapshot?.exercise)?.name -> {
                    when (command.exercise) {
                        "pullUp", "dip" -> if (isFloor) "Choose that exercise on the home screen." else {
                            selection = if (command.exercise == "dip") WorkoutExerciseSelection.dip else WorkoutExerciseSelection.pullUp
                            "Show your starting position."
                        }
                        else -> "Choose that exercise on the home screen."
                    }
                }
                isFloor -> { model.nextSet(); startGuide }
                else -> { if (snapshot?.phase == LiveSetSession.Phase.finished) model.nextSet() else model.startManually(); startGuide }
            }
            "finish" -> if (running) { model.finishSet(); null } else "No set is running."
            "add", "undo" -> "Camera counting is automatic. Review corrections after the set."
            "weight", "clearWeight" -> "Set weight in exercise setup before camera counting."
            else -> null
        }
    }
    LaunchedEffect(state.result?.snapshot?.activeSetId, state.result?.snapshot?.observedMovements) {
        state.result?.snapshot?.takeIf { it.phase == LiveSetSession.Phase.running }?.let { snapshot ->
            snapshot.activeSetId?.let { voice.observedCount(it, snapshot.observedMovements) }
        }
    }
    LaunchedEffect(state.result?.cue) {
        if (state.result?.snapshot?.phase != LiveSetSession.Phase.running) state.result?.cue?.let { voice.guidance(it) }
    }
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
                            .setTargetRotation(view.display?.rotation ?: Surface.ROTATION_0)
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                        camera.unbindAll()
                        val bound = camera.bindToLifecycle(owner, if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                        cameraAnalysis = analysis
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
        onDispose { disposed = true; cameraAnalysis = null; provider?.unbindAll() }
    }
    LaunchedEffect(state.replay, resumed) {
        while (!state.replay && resumed) { delay(100); model.tick(SystemClock.elapsedRealtimeNanos() / 1e9) }
    }
    LaunchedEffect(settings, licenses) { model.pause(settings || licenses) }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val landscape = maxWidth > maxHeight
        state.bitmap?.let { Image(it.asImageBitmap(), contentDescription = "Workout video", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        val pose = state.result?.pose
        val effortUsable = pose?.people?.size == 1 && state.result?.observationUsable == true && !state.interrupted
        val effortTarget = if (!effortUsable) 0f else if (isFloor) {
            state.result?.progress?.takeIf { it.isFinite() }?.coerceIn(0.0,1.0)?.toFloat() ?: 0f
        } else {
            state.result?.currentSet?.activity?.phases?.lastOrNull()?.phase?.name
                ?.let(WorkoutSetPresentation::weight)?.toFloat() ?: 0f
        }
        val easedEffort by animateFloatAsState(effortTarget, tween(220,easing = LinearEasing), label = "jointEffort")
        val heat = if (effortUsable) easedEffort else 0f
        val jointColor = if (heat < .5f) lerp(Color(0xFF29DB63),Color(0xFFFAD124),heat*2)
            else lerp(Color(0xFFFAD124),Color(0xFFFF3029),(heat-.5f)*2)
        Canvas(Modifier.fillMaxSize()) {
            if (pose != null) {
                val transform = VideoTransform(pose.imageSize.width, pose.imageSize.height, size.width.toDouble(), size.height.toDouble())
                fun screen(point: Point2D): Offset { val p = transform.display(point); return Offset(p.x.toFloat(), p.y.toFloat()) }
                if ((settings || showPose) && pose.people.size == 1) {
                    val person = pose.people.single()
                    val limbs = listOf(PoseJoint.leftShoulder to PoseJoint.leftElbow, PoseJoint.leftElbow to PoseJoint.leftWrist,
                        PoseJoint.rightShoulder to PoseJoint.rightElbow, PoseJoint.rightElbow to PoseJoint.rightWrist,
                        PoseJoint.leftShoulder to PoseJoint.rightShoulder, PoseJoint.leftShoulder to PoseJoint.leftHip,
                        PoseJoint.rightShoulder to PoseJoint.rightHip, PoseJoint.leftHip to PoseJoint.rightHip,
                        PoseJoint.leftHip to PoseJoint.leftKnee, PoseJoint.leftKnee to PoseJoint.leftAnkle,
                        PoseJoint.rightHip to PoseJoint.rightKnee, PoseJoint.rightKnee to PoseJoint.rightAnkle)
                    limbs.forEach { (a, b) ->
                        val from = person.landmark(a); val to = person.landmark(b)
                        if (from != null && to != null) {
                            drawLine(Color.Black.copy(alpha = .55f),screen(from.position),screen(to.position),5.dp.toPx(),StrokeCap.Round)
                            drawLine(jointColor.copy(alpha = .9f),screen(from.position),screen(to.position),3.dp.toPx(),StrokeCap.Round)
                        }
                    }
                    person.landmarks.filter { it.isVisible() }.forEach { drawCircle(jointColor, 3.dp.toPx(), screen(it.position)) }
                }
            }
        }
        val snapshot = state.result?.snapshot
        val running = snapshot?.phase == LiveSetSession.Phase.running
        val count = snapshot?.let { if (running) it.observedMovements else
            if (completedDeleted) 0 else afterSetReport?.measuredCompletedReps ?: completedSet?.completedCount ?: 0 } ?: 0
        Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CameraIcon(android.R.drawable.ic_menu_close_clear_cancel, "Back") { onBack?.invoke() }
                Column(Modifier.weight(1f)) {
                    Text(snapshot?.exercise?.title ?: state.floorExercise?.title ?: if (selection == WorkoutExerciseSelection.dip) "Dips" else if (selection == WorkoutExerciseSelection.pullUp) "Pull-ups" else "Workout",
                        color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    if (running) {
                        val healthy = snapshot?.setupIssue == null && snapshot?.trackingIssue == null && state.result?.observationUsable == true
                        Text(if (healthy) "TRACKING" else "PAUSED", color = if (healthy) Color(0xFF69F0AE) else Color(0xFFFFB74D), fontSize = 11.sp, maxLines = 1)
                    }
                }
                if (landscape) Text("$count ${if (count == 1) "rep" else "reps"}", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
                CameraIcon(android.R.drawable.ic_menu_sort_by_size, "Workout analysis") { analysis = true }
                CameraIcon(android.R.drawable.ic_menu_preferences, "Workout settings and details") { settings = true }
            }
            if (!landscape) Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$count ${if (count == 1) "rep" else "reps"}", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.weight(1f))
                CameraIcon(android.R.drawable.ic_menu_view, if (showPose) "Hide joints" else "Show joints",
                    tint = if (showPose) Color(0xFF69F0AE) else Color.White) { showPose = !showPose }
            }
        }
        if (landscape) CameraIcon(android.R.drawable.ic_menu_view, if (showPose) "Hide joints" else "Show joints",
            Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 70.dp, end = 16.dp),
            tint = if (showPose) Color(0xFF69F0AE) else Color.White) { showPose = !showPose }
        if ((running || isFloor && state.result?.observationUsable == true) && !state.interrupted) {
            val usable = state.result?.observationUsable == true && snapshot?.setupIssue == null && snapshot?.trackingIssue == null
            RepPositionGauge(if (usable) state.result?.progress ?: 0.0 else 0.0,
                targetReached = usable && state.result?.targetReached == true, paused = !usable,
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 16.dp, top = if (landscape) 84.dp else 138.dp))
        }
        state.result?.snapshot?.countdownSeconds?.let { Text("$it", Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.5f)).padding(24.dp), fontSize = 72.sp, color = Color.White) }
        WorkoutStartFeedbackOverlay(state.result?.snapshot, live = !state.replay,
            foreground = resumed && !state.interrupted, policy = model.startFeedback, announce = { voice.speak(it) })
        WorkoutSessionFeedbackEffects(state.result?.snapshot, live = !state.replay, foreground = resumed, completion = model.completionFeedback, announce = { voice.speak(it) })
        Column(Modifier.align(if (landscape) Alignment.BottomEnd else Alignment.BottomCenter).navigationBarsPadding().padding(16.dp).widthIn(max = if (landscape) 280.dp else 360.dp).fillMaxWidth().heightIn(max = maxHeight - 80.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            val snapshot = state.result?.snapshot
            val running = snapshot?.phase == LiveSetSession.Phase.running
            val message = when {
                state.error != null -> state.error!!
                state.finished && state.replay -> "Video evaluated · ${state.result?.history?.sumOf { it.completedCount } ?: 0} reps"
                state.interrupted -> "Camera paused"
                running -> state.result?.cue.orEmpty()
                snapshot?.setupIssue != null -> when (snapshot.setupIssue) {
                    "cameraPlacementNeeded" -> placement
                    "sceneStabilityNeeded", "phoneStabilityNeeded" -> "Keep the phone still"
                    else -> state.result?.cue ?: "Show shoulders, elbows and wrists"
                }
                else -> state.result?.cue ?: startGuide
            }
            if(running && !state.interrupted) state.result?.currentSet?.grip?.latest?.let { grip ->
                if(grip.reason == "gripViewUnsupported") {
                    Text("Move camera toward your front or back", Modifier.background(Color.Black.copy(alpha = 0.85f)).padding(16.dp),
                        color = Color.White, style = MaterialTheme.typography.titleLarge)
                    Text("Grip unknown · Rep counting can continue", color = Color.White)
                }
                Text("Estimated · ${grip.label}", Modifier.background(Color.Black.copy(alpha = 0.6f)).padding(8.dp),
                    color = Color.White, style = MaterialTheme.typography.headlineSmall)
            }
            if(running && !state.interrupted) state.result?.currentSet?.activity?.phases?.lastOrNull()?.takeIf {
                it.phase==WorkoutMotionPhase.hang || it.phase==WorkoutMotionPhase.support
            }?.let { span ->
                Text(String.format(java.util.Locale.ROOT,"%s · %.1f s",span.phase.title,span.observedSeconds),
                    Modifier.background(Color.Black.copy(alpha=.7f)).padding(8.dp),color=Color(0xFF80CBC4))
            }
            if (!running && !completedDeleted) afterSetReport?.takeIf { it.practiceAttempts > 0 }?.let {
                Text("${it.practiceAttempts} incomplete ${if (it.practiceAttempts == 1) "attempt" else "attempts"}", color = Color.White)
            }
            if (!running && !state.replay && !state.interrupted && !completedDeleted && completedSet != null) {
                WorkoutBetweenSetTip(completedSet.exercise.name, maxOf(1, state.result?.history?.size ?: 0))
            }
            if (!state.replay) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                WorkoutVoiceButtons(voice, tint = Color.White)
                Spacer(Modifier.weight(1f))
                if (allowCamera)
                CameraIcon(android.R.drawable.ic_menu_camera, "Switch to ${if (front) "rear" else "front"} camera",
                    enabled = !running) { front = !front }
            }
            WorkoutVoiceStatus(voice, color = Color.White)
            Row(Modifier.fillMaxWidth().height(72.dp).background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                val ready = snapshot?.setupIssue == null && state.result?.observationUsable == true && !state.interrupted && state.error == null
                Icon(painterResource(if (ready) android.R.drawable.checkbox_on_background else android.R.drawable.ic_menu_view),
                    contentDescription = null, tint = if (ready) Color(0xFF69F0AE) else Color(0xFFFFB74D), modifier = Modifier.size(24.dp))
                Text(message, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if(!running)afterSetReport?.takeIf { it.goalLabel != null }?.let { report ->
                Spacer(Modifier.height(8.dp))
                Text(report.goalLabel.orEmpty(),color=Color(0xFF69F0AE),style=MaterialTheme.typography.titleSmall)
                val target=report.goalTarget;val value=report.goalValue
                if(target!=null && value!=null)LinearProgressIndicator(progress={(value/target).coerceIn(0.0,1.0).toFloat()},modifier=Modifier.fillMaxWidth().padding(top=8.dp))
            }
            Spacer(Modifier.height(12.dp))
            WorkoutActions(state, onFinish = model::finishSet, onNext = model::nextSet,
                onRestart = { model.startLive(selection, side) }, onReview = { analysis = true })

        }
    }
    if (settings) AlertDialog(onDismissRequest = { settings = false }, title = { Text("Workout settings") }, text = {
        Column {
            if (!isFloor) Text("Exercise")
            if (!isFloor) Row { listOf(WorkoutExerciseSelection.automatic to "Auto", WorkoutExerciseSelection.pullUp to "Pull-up", WorkoutExerciseSelection.dip to "Dip").forEach { (choice, title) -> TextButton(onClick = { selection = choice; if (state.replay) model.changeReplaySelection(choice, side) }, enabled = state.replay || state.result?.snapshot?.phase != LiveSetSession.Phase.running) { Text(if (selection == choice) "✓ $title" else title) } } }
            Text(placement)
            Text(viewGuide)
            Text(startGuide)
            TextButton(onClick = { settings = false; guideSeen = false }) { Text("Camera setup") }
            if (!isFloor) TextButton(onClick = { settings = false; videoPicker.launch(arrayOf("video/*")) }) { Text("Evaluate a video") }
            if (state.replay) TextButton(onClick = { settings = false; model.startLive(selection, side) }) { Text("Return to live camera") }
            Text("Counts are observed movements. Depth and lockout form are not yet verified.")
            TextButton(onClick = { settings = false; licenses = true }) { Text("Open-source licenses") }
        }
    }, confirmButton = { TextButton(onClick = { settings = false }) { Text("Done") } })
    if (analysis) WorkoutReviewDialog(state.result?.reps.orEmpty(), state.result?.history.orEmpty(), state.result?.storageError, currentSet=state.result?.currentSet, goalsEnabled=!state.replay && !isFloor) { analysis = false }
    if (licenses) AlertDialog(onDismissRequest = { licenses = false }, title = { Text("Open-source licenses") }, text = {
        val notice = remember { listOf("hdr-notice.txt", "ffmpeg-LGPL-2.1.txt", "zimg-WTFPL.txt").joinToString("\n\n") {
            context.assets.open("licenses/$it").bufferedReader().use { reader -> reader.readText() }
        } }
        Text(notice, Modifier.verticalScroll(rememberScrollState()))
    }, confirmButton = { TextButton(onClick = { licenses = false }) { Text("Done") } })
}

/** Terminal replay state takes precedence over any previously rendered counter snapshot. */
@Composable
internal fun WorkoutActions(state: WorkoutViewModel.State, onFinish: () -> Unit, onNext: () -> Unit,
    onRestart: () -> Unit, onReview: () -> Unit = {}) {
    if (state.replay && state.finished) {
        FilledTonalButton(onClick = onNext) { Text("Evaluate again") }
    } else if (state.result?.snapshot?.phase == LiveSetSession.Phase.running) {
        FilledTonalButton(onClick = onFinish, modifier = Modifier.heightIn(min = 48.dp)) { Text("Finish set") }
    } else if (state.interrupted && !state.replay) {
        FilledTonalButton(onClick = onRestart) { Text("Resume camera") }
    } else if (state.result?.snapshot?.phase == LiveSetSession.Phase.finished || state.result?.snapshot?.latestCompletedSet != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = onReview) { Text("Review") }
            Button(onClick = onNext) { Text(if (state.replay) "Evaluate again" else "Next set") }
        }
    }
}

@Composable
private fun CameraIcon(icon: Int, label: String, modifier: Modifier = Modifier, enabled: Boolean = true,
    tint: Color = Color.White, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = modifier.size(44.dp).background(Color.Black.copy(alpha = 0.65f), CircleShape), enabled = enabled) {
        Icon(painterResource(icon), contentDescription = label, tint = tint.copy(alpha = if (enabled) 1f else 0.4f), modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun RepPositionGauge(progress: Double, targetReached: Boolean, paused: Boolean, modifier: Modifier = Modifier) {
    val value by animateFloatAsState(progress.coerceIn(0.0, 1.0).toFloat(), tween(80), label = "Rep progress")
    val color = if (paused) Color(0xFFFFB74D) else if (targetReached) Color(0xFF69F0AE) else Color.Cyan
    Column(modifier.semantics(mergeDescendants = true) {
        contentDescription = "Rep progress"
        progressBarRangeInfo = ProgressBarRangeInfo(if (paused) 0f else progress.coerceIn(0.0,1.0).toFloat(), 0f..1f)
        stateDescription = if (paused) "Paused" else if (targetReached) "Target reached. Return to start." else "Move toward target"
    }.background(Color.Black.copy(alpha = 0.66f), RoundedCornerShape(16.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (paused) "Paused" else if (targetReached) "Return" else "Move", color = color, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(Modifier.width(20.dp).height(136.dp).background(Color.White.copy(alpha = 0.16f), CircleShape)) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(value).background(color, CircleShape))
                Box(Modifier.align(Alignment.TopCenter).width(30.dp).height(3.dp).background(if (targetReached) color else Color.White))
            }
            Column(Modifier.height(136.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text(if (targetReached) "✓" else "Target", color = Color.White, style = MaterialTheme.typography.labelSmall)
                Text("Start", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
