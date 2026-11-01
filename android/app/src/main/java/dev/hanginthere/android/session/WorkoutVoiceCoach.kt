package dev.hanginthere.android.session

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.hanginthere.core.WorkoutVoice
import dev.hanginthere.core.WorkoutVoiceCommand
import java.io.Closeable

/** Native audio is replaceable so late callbacks and interruptions can be tested without a mic. */
internal interface WorkoutVoiceAudio : Closeable {
    var onInterrupted: () -> Unit
    fun listen(onResult: (String) -> Unit, onError: (String, Boolean) -> Unit)
    fun cancelListening()
    fun speak(text: String, onFinished: () -> Unit)
    fun stopSpeaking()
    fun releaseFocus()
}

/** One visible workout owns commands. This channel never manufactures camera reps. */
internal class WorkoutVoiceCoach(private val audio: WorkoutVoiceAudio, initialEnabled: Boolean = false,
    private val saveEnabled: (Boolean) -> Unit = {}, private val later: (() -> Unit) -> Unit = { it() },
    private val now: () -> Double = { System.nanoTime() / 1e9 }) : Closeable {
    var enabled by mutableStateOf(initialEnabled)
        private set
    var listening by mutableStateOf(false)
        private set
    var authorizing by mutableStateOf(false)
        private set
    var status by mutableStateOf<String?>(null)
        private set
    var onCommand: (WorkoutVoiceCommand) -> String? = { null }
    private var active = false
    private var closed = false
    var microphoneEnabled by mutableStateOf(false)
        private set
    private var speaking = false
    private var epoch = 0L
    private var speechEpoch = 0L
    private var lastCount: Pair<String, Int>? = null
    private var lastGuidance: String? = null
    private var lastGuidanceAt = Double.NEGATIVE_INFINITY

    init { audio.onInterrupted = { interrupt("Voice paused. Tap the microphone to retry.") } }
    fun setActive(value: Boolean) {
        if (closed || active == value) return
        active = value
        if (!value) interrupt()
    }
    fun enableSpokenCues(value: Boolean) {
        if (enabled == value) return
        enabled = value; saveEnabled(value)
        if (!value) { speechEpoch++; speaking = false; audio.stopSpeaking(); resumeSoon() }
    }
    fun requestPermission(): Long {
        stopListening(); authorizing = active && !closed
        return epoch
    }
    fun permissionResult(token: Long, granted: Boolean) {
        if (closed || !active || !authorizing || token != epoch) return
        authorizing = false
        if (granted) startListening() else status = "Allow microphone access in Settings to use voice commands."
    }
    fun startListening() {
        if (!active || closed) return
        status = null; microphoneEnabled = true
        if (!speaking) beginRecognition()
    }
    fun stopListening() {
        microphoneEnabled = false; authorizing = false; epoch++
        audio.cancelListening(); listening = false; status = null
        if (!speaking) audio.releaseFocus()
    }
    private fun beginRecognition() {
        if (!active || closed || !microphoneEnabled || speaking) return
        val token = ++epoch
        listening = true
        audio.listen(onResult = { text ->
            if (token == epoch && active && microphoneEnabled && !closed) {
                epoch++; listening = false; audio.cancelListening()
                val command = WorkoutVoice.command(text)
                val response = when (command?.action) {
                    "stopListening" -> { stopListening(); null }
                    "mute" -> { enableSpokenCues(false); "Spoken cues muted." }
                    null -> "Try “finish set”, “undo rep”, or “use twenty kilograms”."
                    else -> onCommand(command)
                }
                if (response != null) { status = response; if (command != null) speak(response) }
                resumeSoon()
            }
        }, onError = { message, retry ->
            if (token == epoch && active && !closed) {
                epoch++; listening = false; audio.cancelListening()
                if (retry) resumeSoon() else { stopListening(); status = message }
            }
        })
    }
    private fun resumeSoon() {
        val token = epoch
        if (!microphoneEnabled && !speaking) audio.releaseFocus()
        if (!microphoneEnabled || listening || speaking || !active || closed) return
        later { if (token == epoch) beginRecognition() }
    }
    fun speak(text: String, interrupt: Boolean = true) {
        if (!enabled || !active || closed || text.isBlank() || speaking && !interrupt) return
        epoch++; listening = false; audio.cancelListening()
        val token = ++speechEpoch
        audio.stopSpeaking(); speaking = true
        audio.speak(text) {
            if (token == speechEpoch && !closed) { speaking = false; resumeSoon() }
        }
    }
    fun observedCount(id: String, count: Int) {
        val old = lastCount
        lastCount = id to count
        if (count > 0 && (old?.first != id || count > old.second)) speak(count.toString(), interrupt = false)
    }
    fun guidance(text: String) {
        if (!enabled || !active || text == lastGuidance || now() - lastGuidanceAt < 8) return
        lastGuidance = text; lastGuidanceAt = now(); speak(text, interrupt = false)
    }
    fun interrupt(message: String? = null) {
        stopListening(); speechEpoch++; speaking = false
        audio.stopSpeaking(); audio.releaseFocus(); status = message
    }
    override fun close() {
        if (closed) return
        interrupt(); active = false; closed = true; onCommand = { null }; audio.close()
    }
}
