package dev.hanginthere.android

import dev.hanginthere.android.session.WorkoutVoiceAudio
import dev.hanginthere.android.session.WorkoutVoiceCoach
import org.junit.Assert.*
import org.junit.Test

class WorkoutVoiceCoachTest {
    private class Audio : WorkoutVoiceAudio {
        override var onInterrupted: () -> Unit = {}
        val results = mutableListOf<(String) -> Unit>()
        val errors = mutableListOf<(String, Boolean) -> Unit>()
        val speech = mutableListOf<Pair<String, () -> Unit>>()
        var closed = false
        override fun listen(onResult: (String) -> Unit, onError: (String, Boolean) -> Unit) { results += onResult; errors += onError }
        override fun cancelListening() {}
        override fun speak(text: String, onFinished: () -> Unit) { speech += text to onFinished }
        override fun stopSpeaking() {}
        override fun releaseFocus() {}
        override fun close() { closed = true }
    }
    private class Fixture(enabled: Boolean = false) {
        val audio = Audio()
        val delayed = mutableListOf<() -> Unit>()
        val commands = mutableListOf<String>()
        val coach = WorkoutVoiceCoach(audio, enabled, later = { delayed += it }).also {
            it.setActive(true); it.onCommand = { command -> commands += command.action; null }
        }
        fun resume() { val pending = delayed.toList(); delayed.clear(); pending.forEach { it() } }
    }
    @Test fun permissionResultsCannotStartAMicrophoneAfterTheScreenHasClosed() {
        val f = Fixture(); val token = f.coach.requestPermission()
        f.coach.setActive(false); f.coach.permissionResult(token, true)
        assertFalse(f.coach.listening); assertTrue(f.audio.results.isEmpty())
        f.coach.setActive(true)
        f.coach.permissionResult(f.coach.requestPermission(), false)
        assertTrue(f.coach.status!!.contains("microphone access")); assertFalse(f.coach.microphoneEnabled)
        f.coach.permissionResult(f.coach.requestPermission(), true)
        assertTrue(f.coach.listening)
    }
    @Test fun oneFinalResultExecutesOnceAndLateResultsCannotReachANewScreen() {
        val f = Fixture(); f.coach.startListening(); val stale = f.audio.results.last()
        stale("add rep"); stale("add rep")
        assertEquals(listOf("add"), f.commands)
        f.coach.setActive(false); f.coach.setActive(true); f.resume(); stale("finish set")
        assertEquals(listOf("add"), f.commands); assertFalse(f.coach.listening)
        f.coach.startListening(); f.audio.results.last()("do not finish set")
        assertEquals(listOf("add"), f.commands)
    }
    @Test fun spokenFeedbackSuspendsRecognitionAndOldSpeechCannotRestartIt() {
        val f = Fixture(true); f.coach.startListening(); val stale = f.audio.results.last()
        f.coach.speak("Finish set")
        assertFalse(f.coach.listening); assertTrue(f.coach.microphoneEnabled)
        stale("finish set"); assertTrue(f.commands.isEmpty())
        val finished = f.audio.speech.last().second
        f.coach.stopListening(); finished(); f.resume()
        assertFalse(f.coach.microphoneEnabled); assertFalse(f.coach.listening)
        f.coach.startListening(); f.coach.speak("Three")
        f.audio.speech.last().second(); f.resume()
        assertTrue(f.coach.listening)
        f.coach.close(); f.audio.results.last()("add rep")
        assertTrue(f.commands.isEmpty()); assertTrue(f.audio.closed)
    }
    @Test fun interruptionsAndRecognitionErrorsRequireExplicitRetryExceptSilence() {
        val f = Fixture(true); f.coach.startListening()
        f.audio.errors.last()("silence", true); f.resume(); assertTrue(f.coach.listening)
        f.audio.onInterrupted(); f.resume()
        assertFalse(f.coach.microphoneEnabled); assertTrue(f.coach.status!!.contains("paused"))
        f.coach.startListening(); f.audio.errors.last()("Unavailable", false); f.resume()
        assertFalse(f.coach.microphoneEnabled); assertEquals("Unavailable", f.coach.status)
    }
    @Test fun mutedCuesStaySilentAndCountsAreAnnouncedOncePerSet() {
        val f = Fixture(); f.coach.observedCount("first",1); assertTrue(f.audio.speech.isEmpty())
        f.coach.enableSpokenCues(true); f.coach.observedCount("first",2)
        assertEquals("2",f.audio.speech.last().first)
        f.audio.speech.last().second(); f.coach.observedCount("first",2)
        assertEquals(1,f.audio.speech.size)
        f.coach.observedCount("second",1); assertEquals("1",f.audio.speech.last().first)
        f.coach.setActive(false); f.coach.speak("stale cue"); assertEquals(2,f.audio.speech.size)
    }
}
