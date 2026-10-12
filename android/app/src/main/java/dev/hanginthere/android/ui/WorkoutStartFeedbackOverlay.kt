package dev.hanginthere.android.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hanginthere.core.LiveSetSession
import dev.hanginthere.core.WorkoutRuntime
import dev.hanginthere.core.WorkoutStartFeedback
import kotlinx.coroutines.delay
import java.util.Locale

/** Display/audio feedback observes a real live-set identity; it never gates inference. */
@Composable
fun WorkoutStartFeedbackOverlay(snapshot: WorkoutRuntime.Snapshot?, live: Boolean, foreground: Boolean,
    policy: WorkoutStartFeedback, announce: ((String) -> Unit)? = null,
    nowSeconds: () -> Double = { SystemClock.elapsedRealtimeNanos() / 1e9 }) {
    val context = LocalContext.current
    val speaker = if (announce == null && live && foreground) remember(context) { WorkoutStartSpeaker(context) } else null
    DisposableEffect(speaker) { onDispose { speaker?.close() } }
    val announcement by rememberUpdatedState(announce)
    var visible by remember { mutableStateOf(false) }
    val running = snapshot?.phase == LiveSetSession.Phase.running
    LaunchedEffect(snapshot?.activeSetId, live, running, foreground) {
        visible = false
        if (!live || !running || !foreground) { policy.clear(); speaker?.stop(); return@LaunchedEffect }
        if (policy.begin(snapshot?.activeSetId, live, running, nowSeconds())) {
            visible = true
            announcement?.invoke(WorkoutStartFeedback.spokenMessage) ?: speaker?.speak(WorkoutStartFeedback.spokenMessage)
            while (policy.remainingSeconds(nowSeconds()) > 0) {
                delay((policy.remainingSeconds(nowSeconds()) * 1000).toLong().coerceAtLeast(1))
            }
            visible = false
        }
    }
    if (visible && live && running && foreground && policy.remainingSeconds(nowSeconds()) > 0) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(Modifier.padding(24.dp).widthIn(max = 420.dp)
                .background(Color.Black.copy(alpha = .85f), RoundedCornerShape(24.dp))
                .border(2.dp, Color(0xFF69F0AE), RoundedCornerShape(24.dp)).padding(24.dp)
                .testTag("liveSetStarted"), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("SET STARTED", color = Color(0xFF69F0AE), fontSize = 36.sp, fontWeight = FontWeight.ExtraBold)
                val healthy = snapshot?.let { it.setupIssue == null && it.trackingIssue == null } == true
                Text(if (healthy) "Tracking your reps" else "Reacquiring your pose",
                    color = if (healthy) Color.White else Color(0xFFFFB74D), fontSize = 18.sp)
            }
        }
    }
}

/** Main-thread TTS owner. Late engine initialization cannot announce an expired/ended set. */
private class WorkoutStartSpeaker(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var ready = false
    private var closed = false
    private var pending: Pair<String, Double>? = null
    init {
        engine = TextToSpeech(context.applicationContext) { status -> main.post {
            if (!closed && status == TextToSpeech.SUCCESS) {
                ready = engine?.setLanguage(Locale.ENGLISH)?.let { it >= TextToSpeech.LANG_AVAILABLE } == true
                deliver()
            }
        } }
    }
    fun speak(message: String) {
        pending = message to (SystemClock.elapsedRealtimeNanos() / 1e9 + WorkoutStartFeedback.displaySeconds)
        deliver()
    }
    private fun deliver() {
        if (!ready || closed) return
        val cue = pending ?: return
        pending = null
        if (SystemClock.elapsedRealtimeNanos() / 1e9 < cue.second) engine?.speak(cue.first, TextToSpeech.QUEUE_FLUSH, null, "workout-start")
    }
    fun stop() { pending = null; engine?.stop() }
    fun close() { closed = true; stop(); engine?.shutdown(); engine = null }
}
