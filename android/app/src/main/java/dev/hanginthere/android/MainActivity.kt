package dev.hanginthere.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.hanginthere.android.session.WorkoutViewModel
import dev.hanginthere.android.ui.WorkoutScreen

class MainActivity : ComponentActivity() {
    private val model by viewModels<WorkoutViewModel>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent {
            var cameraAllowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
            val permission = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { cameraAllowed = it }
            val workout by model.state.collectAsStateWithLifecycle()
            val videoPicker = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) model.replay(uri)
            }
            MaterialTheme(colorScheme = darkColorScheme()) {
                if (cameraAllowed || workout.replay) WorkoutScreen(model, allowCamera = cameraAllowed)
                else Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                    Text("Use your camera to count pull-ups and dips.")
                    Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Enable camera") }
                    TextButton(onClick = { videoPicker.launch(arrayOf("video/*")) }) { Text("Evaluate a local video") }
                }
            }
        }
    }
}
