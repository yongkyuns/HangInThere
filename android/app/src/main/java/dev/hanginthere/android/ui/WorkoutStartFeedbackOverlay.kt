package dev.hanginthere.android.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.background
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
import dev.hanginthere.core.WorkoutTrackingFeedback
import dev.hanginthere.core.WorkoutCompletionFeedback
import dev.hanginthere.core.WorkoutFeedback
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
        Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = 84.dp), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.width(200.dp).height(48.dp)
                .background(Color.Black.copy(alpha = .8f), RoundedCornerShape(24.dp))
                .testTag("liveSetStarted"), contentAlignment = Alignment.Center) {
                Text("SET STARTED", color = Color(0xFF69F0AE), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

/** Main-thread TTS owner. Late engine initialization cannot announce an expired/ended set. */
internal class WorkoutStartSpeaker(context: Context) {
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

@Composable internal fun WorkoutSessionFeedbackEffects(snapshot: WorkoutRuntime.Snapshot?, live: Boolean,
    foreground: Boolean, completion: WorkoutCompletionFeedback, announce: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val tracking = remember { WorkoutTrackingFeedback() }
    val announcement by rememberUpdatedState(announce)
    val speaker = if (announce == null && live && foreground) remember(context) { WorkoutStartSpeaker(context) } else null
    DisposableEffect(speaker) { onDispose { speaker?.close() } }
    LaunchedEffect(snapshot?.activeSetId, snapshot?.trackingIssue, snapshot?.phase, live, foreground) {
        val id = snapshot?.activeSetId
        if (live && foreground && id != null) {
            val running = snapshot.phase == LiveSetSession.Phase.running
            tracking.observe(id, running, snapshot.trackingIssue, SystemClock.elapsedRealtimeNanos() / 1e9)?.let { announcement?.invoke(it) ?: speaker?.speak(it) }
            if (running && snapshot.trackingIssue != null) {
                delay(650)
                tracking.observe(id, true, snapshot.trackingIssue, SystemClock.elapsedRealtimeNanos() / 1e9)?.let { announcement?.invoke(it) ?: speaker?.speak(it) }
            }
        }
    }
    LaunchedEffect(snapshot?.latestCompletedSet?.id, live, foreground) {
        val record = snapshot?.latestCompletedSet
        if (record != null && completion.begin(record.id, live, foreground)) announcement?.invoke(WorkoutFeedback.summary(record).spokenCue) ?: speaker?.speak(WorkoutFeedback.summary(record).spokenCue)
    }
}
