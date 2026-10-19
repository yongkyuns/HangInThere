# Kotlin workout core

This Kotlin Multiplatform module contains platform-independent geometry,
apparatus acquisition, exercise selection, camera-stability rules, countdown,
set lifecycle, counting, and progress. It has no camera, UI, MediaPipe, OpenCV,
or Apple Vision dependencies.

Android uses this module in production. iOS links the static `WorkoutCore` framework
for common motion analysis, rep metrics, automatic readiness/set boundaries and
recovery/storage rules. Camera/model adapters remain native; the existing Swift
counter/setup implementation remains the policy-v8 parity reference. See
[automatic analysis](../docs/workout/AUTOMATIC_ANALYSIS.md) for the integration seam,
versioned records, qualification limits and follow-up migration.

`commonMain` holds policies; `commonTest` protects policy behavior; `jvmTest`
compares Kotlin outputs with production Swift oracles. From the repository root:

```sh
python3 scripts/prepare-workout-oracle.py
python3 scripts/prepare-setup-oracle.py
./gradlew :shared:jvmTest
```

On macOS, `./gradlew :shared:linkDebugFrameworkIosSimulatorArm64` builds the
simulator framework. The shipping Apple adapter is also exercised by `scripts/test-workout-bridge.sh`.
Framework compilation alone is not performance qualification. See [architecture and qualification](../docs/ANDROID.md)
for the native-adapter contracts and remaining platform differences.

`ObservedCurveGeometry` supplies bounded finite-curve distance and correspondence
for curved feature observations. It compares local directions over the common
observed subarc so clipping an endpoint cannot create a different tangent window.
The JVM, Kotlin Native and Apple bridge checks exercise that contract. It is a
geometry primitive for ongoing curved-support work; the shipping automatic
apparatus path still requires `ConfirmedBar` and its observed straight edge.
A curve match alone supplies no grip, body load, workout start or rep evidence.
