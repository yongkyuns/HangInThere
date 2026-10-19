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

`StaticSceneStability` uses a unique largest agreeing subset of the four native
corner translations. Two unrelated registration failures cannot pull a global
median away from two agreeing corners. Equal competing hypotheses are unavailable;
the rule never prefers stationary corners over a larger moving consensus. The
two-patch minimum, consensus radius, motion/scale thresholds, source freshness and
movement dwell remain independent requirements. Translation agreement alone does
not rule out camera rotation, parallax or foreground motion. The live Swift monitor
mirrors this policy, with common tests, Apple bridge checks and setup parity cases
guarding both implementations.

`ObservedCurveGeometry` supplies bounded finite-curve distance and correspondence
for curved feature observations. It compares local directions over the common
observed subarc so clipping an endpoint cannot create a different tangent window.
The JVM, Kotlin Native and Apple bridge checks exercise that contract. It is a
geometry primitive for ongoing curved-support work; the shipping automatic
apparatus path still requires `ConfirmedBar` and its observed straight edge.
A curve match alone supplies no grip, body load, workout start or rep evidence.

`ObservedRailFitter` pairs finite curved contour boundaries using their local
directions and reciprocal observed support. It retains curved points instead of
replacing them with chords, keeps competing proposals for acquisition to assess,
and checks grip proximity only against the paired subarcs. Raw contour adjacency,
point/run/candidate bounds and a shared work budget prevent bridging rejected
pixels or unbounded geometry work. Native adapters still own edge extraction;
this fitter supplies geometric proposals and does not confirm apparatus identity,
two distinct rails, camera stability, contact, a set start or a repetition.

`BilateralGripDisplacement` measures each shoulder against an independently
estimated, uniquely associated hand MCP centre. It uses `GripObservation`'s
original source time and exact tracked-body subset, without requiring auxiliary
3D body estimates or replacing body wrists/elbows. That subset excludes elbows
and cannot prove the full pose/pixel identity: native inference owners must retain
the original primary pose and reset anchors when the source epoch changes.
Equal/reversed source times are rejected using exact rational ordering.
Starting anchors require both
measured top-support arm chains; later missing elbows do not erase independently
observed shoulder displacement. Missing hand scores remain explicitly unscored.
The shared JSON boundary is exercised by the shipping Apple bridge checks.
This is a geometry primitive for ongoing detection work. It does not emit events
or change the shipping counter. Source continuity, actual finite apparatus,
camera stability, grip contact and exercise identity need independent gates;
displacement and hand retention alone cannot establish a rep, load or dip depth.
