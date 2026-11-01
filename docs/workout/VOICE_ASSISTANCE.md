# Workout voice assistance

The speaker button enables spoken rep counts, setup cues and set summaries.
The microphone button separately enables English workout commands. Both are
optional; camera counting and manual controls work without them.

| Screen | Commands |
| --- | --- |
| Camera workout | Start set, next set, finish set |
| Workout log | Start set, start dips/pull-ups/push-ups/sit-ups, use twenty kilograms, clear weight |
| Manual counter | Add rep, undo rep, finish set |
| Any active voice screen | Mute voice, stop listening |

Camera commands cannot add or undo detected reps, change weight mid-set, or
switch between floor and bar camera routes. Choose the appropriate home tile
for a different camera setup. Exercise selection never bypasses readiness.

Only a finalized, whole utterance executes. Partial transcripts and phrases
such as “do not finish set” cannot act. Speech interpretation and summaries use
common Kotlin. Native adapters own audio and permissions. Recognition pauses
during spoken cues and resumes if the microphone is still enabled.

Leaving the active screen, backgrounding, an audio interruption or headset
disconnect stops listening. Late permission, recognition and speech callbacks
cannot restart it; tap the microphone to resume. Denied permission or missing
recognition support displays guidance and leaves workout controls available.

iPhone requires on-device English recognition. Android voice commands require
Android 12+ and an installed on-device English recognition service; older or
unsupported devices can still use spoken cues. There is no cloud fallback.
See the platform contracts for [Android recognition](https://developer.android.com/reference/android/speech/SpeechRecognizer)
and [Apple audio interruptions](https://developer.apple.com/documentation/avfaudio/handling-audio-interruptions).

Automated checks cover command parsing, partial utterances, permission denial,
delayed authorization after navigation, duplicate/stale results, audio
interruptions and recognition/speech handoff. Gym-noise accuracy, microphone
distance and real headset/call behavior still require physical-device testing;
simulator and fake-audio checks do not establish those results.
