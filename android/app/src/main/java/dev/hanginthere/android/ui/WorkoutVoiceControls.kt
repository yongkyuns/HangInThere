package dev.hanginthere.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.hanginthere.android.R
import dev.hanginthere.android.session.AndroidWorkoutVoiceAudio
import dev.hanginthere.android.session.WorkoutVoiceCoach
import dev.hanginthere.core.WorkoutVoiceCommand

@Composable internal fun rememberWorkoutVoiceCoach(active: Boolean = true,
    onCommand: (WorkoutVoiceCommand) -> String?): WorkoutVoiceCoach {
    val context = LocalContext.current.applicationContext
    val owner = LocalLifecycleOwner.current
    val preferences = remember(context) { context.getSharedPreferences("workoutVoice", 0) }
    val coach = remember(context) {
        val main = Handler(Looper.getMainLooper())
        WorkoutVoiceCoach(AndroidWorkoutVoiceAudio(context), preferences.getBoolean("enabled", false),
            saveEnabled = { preferences.edit().putBoolean("enabled", it).apply() }, later = { main.postDelayed(it, 400) })
    }
    val allowed by rememberUpdatedState(active)
    val handler by rememberUpdatedState(onCommand)
    var foreground by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    SideEffect { coach.onCommand = { handler(it) } }
    DisposableEffect(active && foreground, coach) {
        if (active && foreground) coach.enableSpokenCues(preferences.getBoolean("enabled", false))
        coach.setActive(active && foreground)
        onDispose { coach.setActive(false) }
    }
    DisposableEffect(owner, coach) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { foreground = false; coach.setActive(false) }
            if (event == Lifecycle.Event.ON_START) {
                foreground = true
                coach.enableSpokenCues(preferences.getBoolean("enabled", false)); coach.setActive(allowed)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); coach.close() }
    }
    return coach
}

@Composable internal fun WorkoutVoiceButtons(coach: WorkoutVoiceCoach, tint: Color = LocalContentColor.current) {
    val context = LocalContext.current
    var permissionToken by remember { mutableStateOf<Long?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionToken?.let { coach.permissionResult(it, granted) }; permissionToken = null
    }
    Row {
        IconButton(onClick = { coach.enableSpokenCues(!coach.enabled); if (coach.enabled) coach.speak("Voice coach on.") },
            modifier = Modifier.size(44.dp).testTag("toggleWorkoutVoice")) {
            Icon(painterResource(if (coach.enabled) R.drawable.voice_on else R.drawable.voice_off),
                contentDescription = if (coach.enabled) "Mute voice coach" else "Enable voice coach", tint = tint)
        }
        IconButton(onClick = {
            if (coach.microphoneEnabled || coach.authorizing) coach.stopListening()
            else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) coach.startListening()
            else { permissionToken = coach.requestPermission(); permission.launch(Manifest.permission.RECORD_AUDIO) }
        }, modifier = Modifier.size(44.dp).testTag("toggleWorkoutListening")) {
            Icon(painterResource(if (coach.microphoneEnabled) R.drawable.mic_on else R.drawable.mic_off),
                contentDescription = if (coach.microphoneEnabled || coach.authorizing) "Stop listening" else "Listen for workout commands",
                tint = if (coach.microphoneEnabled) MaterialTheme.colorScheme.error else tint)
        }
    }
}

@Composable internal fun WorkoutVoiceStatus(coach: WorkoutVoiceCoach, color: Color = LocalContentColor.current) {
    val text = coach.status ?: if (coach.listening) "Listening for workout commands" else if (coach.authorizing) "Microphone permission needed" else null
    if (text != null) Text(text, color = color, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("workoutVoiceStatus"))
}
