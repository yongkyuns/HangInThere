# Android architecture and qualification

The Android client uses native Kotlin/Compose and CameraX. Its production camera
and recorded-video adapters feed a Kotlin Multiplatform workout core. The core
exports JVM, Android and static iOS frameworks; the shipping Swift client remains
the reference implementation during this port. It does **not** yet link that
framework. CI compiles the framework and compares the two engines before a future
iOS adapter migration can replace the reference.

| Boundary | Generalized policy | Android adapter |
| --- | --- | --- |
| Observations | Upright, unmirrored pixel coordinates, anatomical joint names, rational source timestamps | CameraX/MediaCodec canonical pixels and MediaPipe Heavy CPU |
| Apparatus | Observed contour fitting, grip identity, acquisition continuity | OpenCV pixel contours in pose-guided crops |
| Stability | Quaternion delta, background translation/scale gates, freshness | Rotation-vector sensor and background image registration |
| Workout | Auto exercise identity, countdown, set lifecycle, bilateral evidence, policy-v8 events and progress | Serial analysis owner, generation invalidation and Compose overlay |

Pose confidence is SDK evidence, not a calibrated probability. MediaPipe uses
the weaker visibility/presence value. Counter and setup code have no camera, UI,
MediaPipe or OpenCV imports. Source PTS advances dwell; display timers cannot earn
a movement. Real pixels must support an apparatus edge, and missing scene evidence
cannot certify a fixed camera. Front preview stays unmirrored, matching analysis.

## Evaluation layers

1. `prepare-workout-oracle.py` generates original analytic trajectories with
   explicit movement labels. The unchanged production Swift sources generate
   per-frame summaries, events and progress snapshots; JVM tests compare every
   field within 1e-9 for floating-point math. Static, partial, interrupted,
   bilateral-disagreement, missing-arm, crowd and timestamp cases are included.
2. `prepare-setup-oracle.py` compares exercise identity, observed apparatus
   acquisition, countdown, camera stability and contour geometry against Swift.
   Setup tests also exercise the composed runtime rather than only isolated gates.
3. Android API29/API35 CI decodes pinned public pull-up/dip media through the
   production MediaCodec, MediaPipe and OpenCV adapters. Exact frame count, actual
   PTS, derivative checksums and independently reviewed movement totals are
   asserted. Fixed-edge counting and production Auto replay are distinct evidence.
4. A Mac CI job replays Android-produced model observations through production
   Swift. It compares count and event outcomes/reasons/times on identical poses,
   independently of Apple Vision versus MediaPipe accuracy differences.

The public clips are development smoke cases, not a held-out accuracy benchmark.
The pull-up subject's head is cropped; strict chin clearance and dip depth remain
unverified on both platforms. No user recordings or local predictions are
published. Source recordings and test APKs are excluded from CI artifacts.

## Performance evidence

The same Mac runs optimized Swift and warmed JVM core loops on identical cases;
CI records p50/p95 per-frame time and enforces a 1ms shared-core p95 budget. This
isolates policy overhead. The Android report separately measures cold/warm pose,
decode, contour and production pipeline costs, plus native/Java allocation and
process PSS. Emulator budgets detect large regressions (pose p95 below2s, PSS
below1GiB); they do not promise real-time performance on a phone.

Physical Android front/back cameras, vendor sensor clocks, thermal throttling,
battery use and sustained live throughput cannot be qualified by an emulator.
Full device performance parity remains open until representative Android hardware
is tested. Do not describe green core or emulator checks as proof of that parity.

See [Android build and ownership details](../android/README.md) and the
[CI workflow](../.github/workflows/android.yml). The APK artifact is for evaluation;
it contains the pinned Heavy pose model and needs no model download at runtime.
