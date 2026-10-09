# Native Android client

The Compose screen is a full camera canvas with aligned, unmirrored pose and apparatus overlays. Auto is the default exercise selection. Settings provide manual exercise/grip selection, two-tap observed reference-edge fallback, and local video import. Counts remain provisional observed movements, not verified depth/lockout form.

## Build

Use JDK17, Android SDK36 and the checked-in Gradle8.13 wrapper. Tool/plugin/artifact versions are explicit. MediaPipe0.10.32 is checksum-verified before packaging and includes actual ARM32/ARM64/x86/x86_64 JNI, each independently inspected with16KiB ELF LOAD alignment. Earlier0.10.26.1 lacked emulator JNI and is not used. Download the official Heavy model with its verified SHA256:

```
python3 scripts/prepare-mediapipe-model.py --output android/app/src/main/assets/pose_landmarker_heavy.task --metadata build/android-model.json
./gradlew :android:app:assembleDebug
```

The model is ignored; no user footage belongs in app or test assets. Instrumentation uses only the pinned public fixtures prepared by `scripts/prepare-android-fixtures.py`.

## Boundaries and ownership

* `shared` contains image geometry, arm measurement, counting/progress, apparatus acquisition, exercise selection, stability and set lifecycle. It has no Android or pose SDK imports and exports static `WorkoutCore` iOS frameworks.
* `CanonicalPixels` is the sole camera/video orientation and scaling boundary: upright top-left pixels, maximum1280 dimension, no front-camera reflection or left/right swapping. Even-aligned YUV420 crops pack exact row/pixel-stride plane samples into I420 and use OpenCV bulk limited-range BT.601 conversion; odd crop origins or dimensions preserve the original scalar sampling. Native differential tests bound RGB rounding differences to two levels and preserve caller plane positions. `VideoTransform` is shared by drawing and manual tap mapping.
* `PoseEstimator` maps33 MediaPipe landmarks to the canonical19-joint schema. Derived neck/root use midpoint positions and the weaker parent score. Raw minimum visibility/presence is recorded explicitly; it is not calibrated to Apple Vision confidence. CPU Heavy and numPoses2 are pinned; multiple visible people fail the shared single-athlete gate.
* `ObservedEdges` extracts actual OpenCV Canny contours from pose-guided crops. The shared bounded fitter operates on observed pixel samples; it never invents a between-wrists bar/rail. Its geometry ranking is not semantic confidence.
* `SceneRegistration` freezes the apparatus-acquisition image. Four background-corner phase translations and independent robust ORB affine scale feed the shared scene gate. Missing or insufficient evidence cannot mark the scene stable. Android rotation-vector samples independently qualify rotation. A bounded capture-time history selects the newest nonfuture sample; callback/conversion latency never pairs a frame with newer motion. Cameras without the Android REALTIME timestamp contract receive an actionable video-evaluation fallback.
* `AnalysisPipeline` is one serial, closeable owner of model/native resources and `WorkoutRuntime`. Scene registration resets on reference changes and retains the original registration image during replay rewind.
* `WorkoutViewModel` owns a single inference executor and generation invalidation. CameraX keeps only the latest pending frame and closes every ImageProxy. UI snapshots are conflated to one pending update. Camera changes, exercise/grip changes, video import and lifecycle stop invalidate earlier outputs; backgrounding ends and preserves an interrupted set until the user explicitly starts the next one. Heavy inference/contours/decoding never run on the UI thread.
* `VideoFrames` uses MediaExtractor + MediaCodec YUV output. Source PTS are never inferred from frame indices, seek intervals, decode latency or wall time. Track rotation applies once. Output Images, buffers and codecs are released deterministically. Replay first acquires an observed apparatus, rewinds with that fixed reference, then processes the full source timeline without the live three-second countdown. Replay's identity quaternion is a virtual fixed-phone assumption, clearly separate from live sensor evidence; actual background registration is still required.

## Qualification limits

Emulator instrumentation reports exact decoded PTS, decoded frame count, observed MediaPipe movement counts on reviewed fixed edges, actual contour detections, pose/decode/contour/scene latency distributions plus separate production Auto replay acquisition and full pipeline/display-copy timings and process/native/Java memory. `pipelineWithDisplayCopyLatencyMillis` includes decoder read, canonical pixel conversion, inference, contours/registration, shared runtime and display copy; it excludes model initialization and the final EOF read that returns no frame. Reports are written under app external-files `evaluation/` even when count parity fails. A fixed-edge fixture count is not a proof that production Auto setup succeeds on that view. JVM shared-engine oracle parity separately isolates policy equivalence from differences in body-pose backends.

CPU/emulator wall time is not phone frame-rate, thermal, battery or camera qualification. Front-camera hardware, sensor timebase alignment and background texture sensitivity need physical Android coverage before claiming full live/performance parity. Replay setting/grip changes restart the full cached video source on a new generation; completion closes the actual counter. Completed replays expose Evaluate again. Exercise/grip/camera changes are disabled during live sets so the selected exercise remains fixed. UI instrumentation captures the real app before assertions, including failures. No fallback fabricates stable registration evidence.
