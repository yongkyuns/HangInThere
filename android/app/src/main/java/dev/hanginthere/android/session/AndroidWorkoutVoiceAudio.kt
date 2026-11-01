package dev.hanginthere.android.session

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import java.util.Locale

/** Main-thread, on-device English recognition. No cloud fallback or audio files. */
internal class AndroidWorkoutVoiceAudio(private val context: Context) : WorkoutVoiceAudio {
    override var onInterrupted: () -> Unit = {}
    private val main = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ change -> if (change < 0) onInterrupted() }, main).build()
    private var recognizer: SpeechRecognizer? = null
    private var engine: TextToSpeech? = null
    private var ready = false
    private var closed = false
    private var speechId = 0L
    private data class Cue(val id: String, val text: String, val expires: Long, val done: () -> Unit)
    private var pending: Cue? = null
    private var current: Cue? = null
    private val routeLoss = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (recognizer != null || current != null || pending != null) onInterrupted()
        }
    }

    init {
        ContextCompat.registerReceiver(context, routeLoss, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        engine = TextToSpeech(context.applicationContext) { status -> main.post {
            if (!closed) {
                ready = status == TextToSpeech.SUCCESS && (engine?.setLanguage(Locale.US) ?: -1) >= TextToSpeech.LANG_AVAILABLE
                if (ready) deliver() else { pending?.done?.invoke(); pending = null }
            }
        } }
        engine?.setAudioAttributes(attributes)
        engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finished(utteranceId)
            @Deprecated("Platform callback") override fun onError(utteranceId: String?) = finished(utteranceId)
            @Deprecated("Platform callback") override fun onError(utteranceId: String?, errorCode: Int) = finished(utteranceId)
        })
    }
    private fun finished(id: String?) { main.post {
        current?.takeIf { it.id == id }?.let { current = null; it.done() }
    } }
    private fun acquireFocus() = manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    override fun listen(onResult: (String) -> Unit, onError: (String, Boolean) -> Unit) {
        cancelListening()
        if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            onError("On-device English recognition is unavailable. Spoken cues still work.", false); return
        }
        if (!acquireFocus()) { onError("Audio is busy. Tap the microphone to retry.", false); return }
        try {
            val source = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            recognizer = source
            source.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    if (recognizer === source) onResult(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
                }
                override fun onError(error: Int) {
                    if (recognizer !== source) return
                    val retry = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    val message = when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Allow microphone access in Settings."
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Install on-device English speech recognition in system settings."
                        else -> "Voice commands paused. Tap the microphone to retry."
                    }
                    onError(message, retry)
                }
                override fun onPartialResults(partialResults: Bundle?) {} // Partial words cannot execute a command.
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            source.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1))
        } catch (_: Exception) { onError("Voice commands couldn’t start. Tap the microphone to retry.", false) }
    }
    override fun cancelListening() {
        val source = recognizer; recognizer = null
        source?.cancel(); source?.destroy()
    }
    override fun speak(text: String, onFinished: () -> Unit) {
        if (closed || !acquireFocus()) { onFinished(); return }
        pending = Cue((++speechId).toString(), text, SystemClock.elapsedRealtime() + 2_000, onFinished)
        val id = pending?.id
        main.postDelayed({ pending?.takeIf { it.id == id }?.let { pending = null; it.done() } }, 2_000)
        deliver()
    }
    private fun deliver() {
        if (!ready || closed) return
        val cue = pending ?: return
        pending = null
        if (SystemClock.elapsedRealtime() >= cue.expires) { cue.done(); return }
        current = cue
        if (engine?.speak(cue.text, TextToSpeech.QUEUE_FLUSH, null, cue.id) != TextToSpeech.SUCCESS) { current = null; cue.done() }
    }
    override fun stopSpeaking() { pending = null; current = null; engine?.stop() }
    override fun releaseFocus() { manager.abandonAudioFocusRequest(focus) }
    override fun close() {
        if (closed) return
        closed = true; cancelListening(); stopSpeaking(); releaseFocus(); engine?.shutdown(); engine = null
        context.unregisterReceiver(routeLoss)
        main.removeCallbacksAndMessages(null); onInterrupted = {}
    }
}
