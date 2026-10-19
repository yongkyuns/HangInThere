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

`StaticSceneStability` uses the largest agreeing subsets of four native corner
translations. Multiple maximal inlier sets describe one usable hypothesis only
when every pair shares two original patches, their median centers agree within
the existing radius, and all centers agree on the motion threshold classification.
It retains the largest center magnitude; it never averages competing estimates
into a stationary result. Distinct or moving/stationary hypotheses remain
unavailable. The two-patch minimum, consensus radius, motion/scale thresholds,
source freshness and movement dwell remain independent requirements. Translation
agreement alone does not rule out rotation, parallax or foreground motion. The
Swift live monitor mirrors this rule; common, Apple bridge and setup parity tests
guard both implementations.

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
The explicit `finiteEdges` mode also retains observed straight subpaths, with the
same reciprocal finite support, width and work bounds. `aroundGrip` permits a
tube to project above an independently estimated MCP centre; it uses the supplied
ROI and existing finite-distance bound. Defaults remain `curvedOnly` and
`belowGrip`, preserving the existing bilateral path. Neither opt-in is enabled
for counting merely because it supplies more proposals.

`ObservedGripAssociation` supplies stateless correspondence for one or two
independent, scored MCP observations against original measured body wrists.
It enumerates every original person and side, retains the original person index
and all corresponding arms, and rejects multiple matches, duplicate sides and
cross-person assignments. No missing wrist/elbow is reconstructed. Its input
requires the hand request's original rational timestamp tuple, preventing nearby
frames with equal floating seconds from sharing a binding. Native owners still
retain exact image and source-epoch ownership. This primitive grants no temporal
athlete identity, apparatus/contact, exercise, camera or repetition evidence.
Its default `gripCentre` correspondence preserves policy 1 and the original
three-MCP transport. Explicit `observedWrist` correspondence (policy 2) matches
the hand SDK's directly observed, scored wrist to the original body wrist using
the same radius. It requires that extra observation: a knuckle centre cannot
fill a missing wrist. The returned body wrist, optional observed hand wrist and
MCP grasp centre remain separate. This avoids rejecting a close view merely
because a real wrist-to-knuckle offset exceeds an image-relative matching radius.
The Apple `wristAndGripMCPs` opt-in keeps crop coordinates in the original image
and adds `-wrist_mcp_v1` to its source model ID. Existing acquisition keeps the
three-MCP default. A format change can therefore revoke future temporal evidence
without changing the actual pose model or silently mixing feature streams.

Dip pixel searches share `DipRailSearchGeometry`. When the hand is clearly
lateral to its measured shoulder, it extends the outward edge by the measured
upper-arm length, while preserving the original body exclusion, vertical range
and image bounds. Missing arm length or unresolved outward direction retains the
original crop. This addresses the verified source-frame case where the old crop
left less than the minimum observed rail arc beside an occluding hand. The association search
reports `searchPolicyVersion: 2`; curved acquisition reports policy 3. These are
pixel context changes; proposal ambiguity, finite support, dwell, camera and
repetition rules still apply independently.

`CurvedDipRailAcquisition` applies the existing four-observation, 0.9-second
source dwell to two current curved proposals and independently matched MCP hands.
It retains finite source boundaries, checks every competing geometric proposal,
and restarts on shape, hand, backend, image-size or source-continuity changes.
One serial worker owns search, reset and observation. It captures a generation before inference; cancelled callbacks cannot
alter newer evidence. Invalidating evidence retains the source clock high-water;
only explicit source activation permits a new clock epoch. The emitted reference
contains the qualifying frame's geometry, time and model context. Native owners
must retain its original primary pose and pixels together. A reference locates
stable image features; camera, apparatus/contact/load, starting posture and rep
qualification remain separate. This component is not yet connected to live counts.

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

`BilateralDipMotion` is a separate version-1 image-motion policy. It requires the
actual retained two-rail acquisition reference, current matched MCPs, current
camera evidence, both measured starting arms, bilateral travel and a sustained
measured return. It preserves exact source-clock dwell/gap bounds and tracks each
arm's unfinished return as negative evidence, preventing a new descent from being
joined into an older cycle. Camera movement requires support acquired after that
movement. Its output is explicitly `imageCycles`, with partial and interrupted
observations separate; it does not change policy-8 counts or establish anatomical
depth, form or load. Native owners must preserve acquisition pixel provenance;
serializing a reference cannot prove that provenance. Common JVM/Native tests and
actual Apple FFI checks guard the policy. The Apple `native/apple` package supplies
bounded ordered Canny pixels; `VisionDipObservationWorker` joins fresh native MCP
observations to the original primary pose and runs common acquisition off the UI
thread. Live counting/analytics integration and mobile performance qualification
remain separate work.
