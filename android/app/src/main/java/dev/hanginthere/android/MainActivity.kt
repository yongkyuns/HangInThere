package dev.hanginthere.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hanginthere.android.session.WorkoutRecordStore
import dev.hanginthere.android.session.WorkoutViewModel
import dev.hanginthere.android.ui.*
import dev.hanginthere.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val model by viewModels<WorkoutViewModel>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent {
            var cameraAllowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
            var camera by remember { mutableStateOf(false) }
            var history by remember { mutableStateOf(false) }
            var log by remember { mutableStateOf(false) }
            var initialLog by remember { mutableStateOf<WorkoutLog?>(null) }
            var capture by remember { mutableStateOf<WorkoutLogCameraRequest?>(null) }
            var selection by remember { mutableStateOf(WorkoutExerciseSelection.automatic) }
            var error by remember { mutableStateOf<String?>(null) }
            var logRevision by remember { mutableIntStateOf(0) }
            val workout by model.state.collectAsStateWithLifecycle()
            val store = remember { WorkoutRecordStore.live(applicationContext) }
            val permission = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                cameraAllowed = it; camera = it
            }
            val videoPicker = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) { capture = null; selection = WorkoutExerciseSelection.automatic; model.replay(uri); camera = true }
            }
            fun startCamera() {
                model.prepareLive(selection)
                log = false; history = false
                if (cameraAllowed) camera = true else permission.launch(Manifest.permission.CAMERA)
            }
            val record = workout.result?.snapshot?.latestCompletedSet
            LaunchedEffect(record?.id) {
                val request = capture
                if (request != null && record != null && !workout.replay) {
                    try { withContext(Dispatchers.IO) { store.linkCameraSet(record, request.log, request.load, request.unit) }; if (!camera) logRevision++ }
                    catch (failure: Throwable) { if (failure is kotlinx.coroutines.CancellationException) throw failure; error = "The camera set was saved, but couldn't be linked. Open history to review it." }
                }
            }
            HangTheme {
                BackHandler(camera) { model.suspendForBackground(); camera = false; if (capture != null) { initialLog = capture!!.log; log = true } }
                if (camera) WorkoutScreen(model, allowCamera = cameraAllowed, initialSelection = selection, onBack = {
                    model.suspendForBackground(); camera = false
                    if (capture != null) { initialLog = capture!!.log; log = true }
                }) else Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Spacer(Modifier.height(24.dp))
                        Text("Hang in there", style = MaterialTheme.typography.displaySmall)
                        Text("A little stronger, one set at a time.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { capture = null; selection = WorkoutExerciseSelection.automatic; startCamera() }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Start workout") }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            listOf("Pull-ups" to WorkoutExerciseSelection.pullUp, "Dips" to WorkoutExerciseSelection.dip).forEach { (title, choice) ->
                                FilledTonalButton(onClick = { capture = null; selection = choice; startCamera() }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text(title) }
                            }
                        }
                        OutlinedButton(onClick = { initialLog = null; log = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Log workout") }
                        OutlinedButton(onClick = { history = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Your workouts") }
                        TextButton(onClick = { videoPicker.launch(arrayOf("video/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Review a video") }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
                val onCamera: (WorkoutLogCameraRequest) -> Unit = { request ->
                    capture = request; selection = if (request.exercise == ExerciseCounter.Exercise.pullUp) WorkoutExerciseSelection.pullUp else WorkoutExerciseSelection.dip; startCamera()
                }
                if (log) key(logRevision) { WorkoutLogDialog(recordStore = store, initial = initialLog, onCamera = onCamera, onClose = { log = false }) }
                if (history) WorkoutLibraryDialog(recordStore = store, onCamera = onCamera) { history = false }
            }
        }
    }
}
