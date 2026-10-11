# Android architecture and qualification

The Android client uses native Kotlin/Compose and CameraX. Its production camera
and recorded-video adapters feed a Kotlin Multiplatform workout core. The core
exports JVM, Android and static iOS frameworks; the shipping Swift client remains
the reference implementation during this port. It does **not** yet link that
framework. CI compiles the framework and compares the two engines before a future
iOS adapter migration can replace the reference.

## Current merge blockers

PR29 remains a draft. Passing policy parity does not qualify the missing native
capabilities below. Original labels, decoder color checks and playback deadlines
remain unchanged.

| Scope | Latest demonstrated limitation | Required qualification |
| --- | --- | --- |
| Native dip setup, API29/API35 | Held public pixels show a shared straight bar; the last qualified parallel-only selector cannot acquire it | The unchanged held positive must acquire observed support and complete native Auto/live countdown with the new shared-bar selector |
| Original public accuracy | Cropped pull-up0/1; panning dip0/9 | Qualification of occluded poses and camera-relative references before claiming these captures are supported |
| Original phone HDR imports | Both APIs now count2/2 pull-ups and4/4 dips in Auto and fixed modes, for both sides | Preserve the full-source results after the shared-bar setup change; qualify physical-device performance separately |
| iOS simulator Vision | Apple body-pose weights fail to load | Real inference in a supported simulator runtime; native Mac and physical iPhone results remain separate |
| iOS simulator decoder | Pinned H264 green/yellow samples have incorrect native chroma | Correct native decoding of the immutable fixture, with existing color tolerances |
| iOS replay latency | Hosted replay intermittently exceeds the unchanged completion deadline | Repeat hosted replay after the dedicated executor change; a local pass is insufficient |

iOS replay now uses a dedicated serial Dispatch executor for blocking native
decoding, rendering and inference. Swift still checks actor isolation and fresh
decoder ownership. This prevents that actor from occupying a cooperative worker;
it is not yet evidence that the hosted native stall has been eliminated.

When apparatus is ambiguous, both live clients now ask the user to change the
camera angle. Android records `dipRailsUnclear` or `pullUpBarUnclear`, while
retaining framing/identity precedence. A new clear view must earn fresh apparatus
dwell and the complete countdown. No manual confirmation is required by the cue.

`Evaluation/fixtures/native-dip-overlap.json` preserves all72 actual API35 pose
records and all18 observed bilateral contour calls from run38084810283, including
source PTS, asset/model pins and the decoded-image hash. Both anatomical reference
choices are additional **rejection regressions** in the Swift/Kotlin oracle, and
also exercise the composed Kotlin runtime with zero reference, countdown and
movements. They do not replace or relabel the original failed native positive.
The absent scene samples and fixed virtual attitude are explicit policy-test inputs.

Physical camera/sensor timing, sustained performance, thermal/battery behavior,
strict chin clearance and dip depth still need separate device/form qualification.

| Boundary | Generalized policy | Android adapter |
| --- | --- | --- |
| Observations | Upright, unmirrored pixel coordinates, anatomical joint names, rational source timestamps | CameraX, MediaCodec SDR/software HEVC HDR canonical pixels and MediaPipe Heavy CPU |
| Apparatus | Observed contour fitting, grip identity, acquisition continuity | OpenCV pixel contours in pose-guided crops |
| Stability | Quaternion delta, background translation/scale gates, freshness | Rotation-vector sensor and background image registration |
| Workout | Auto exercise identity, countdown, set lifecycle, bilateral evidence, policy-v8 events and progress | Serial analysis owner, generation invalidation and Compose overlay |

Guided edge fitting retains at most512 actual observed segments for grouping.
Clutter uses segment length and canonical endpoint ties to retain the strongest
support, avoiding whole-frame rejection at the old segment-count limit. The
200,000-point and four-million simplification-operation guards remain in force;
this change neither extrapolates edges nor changes apparatus/count thresholds.

Pose confidence is SDK evidence, not a calibrated probability. MediaPipe uses
the weaker visibility/presence value. Counter and setup code have no camera, UI,
MediaPipe or OpenCV imports. Source PTS advances dwell; display timers cannot earn
a movement. Real pixels must support an apparatus edge, and missing scene evidence
cannot certify a fixed camera. Front preview stays unmirrored, matching analysis.

Recorded SDR pixels honor actual decoder BT.601/BT.709 and full/limited-range
metadata. Complete Main10-family BT.2020 HLG/PQ inputs use the bounded software
HEVC decoder and independently checked HDR-to-SDR mapping documented in
[Android HDR imports](ANDROID-HDR.md). Every native input/output frame must prove
its declared format and color metadata. Inputs that cannot produce verified supported pixels fail
explicitly. Camera pixels retain the explicit legacy BT.601 assumption.

The adapter gives MediaPipe its own bitmap because closing its image recycles
that input. The canonical frame remains available to contour extraction, scene
registration and display. Native CI checks this ownership contract and includes
the copy in timing and memory measurements. Replay EOF distinguishes completed
evaluation, unresolved setup and interruption; decoding completion alone cannot
turn an unevaluated video into a successful zero count. Failed replay analysis
closes the actual counting session and always exposes retry. Recorded acquisition
waits for both observed apparatus and stable exercise identity; a bar alone cannot
end the acquisition pass before Auto has identified the exercise. Native integrity
checks compare decoder microseconds against container integer ticks and rational
timebases, avoiding FFprobe decimal rounding while preserving actual source PTS.

## Evaluation layers

1. `prepare-workout-oracle.py` generates original analytic trajectories with
   explicit movement labels. Production Swift sources generate
   per-frame summaries, events and progress snapshots; JVM tests compare every
   field within 1e-9 for floating-point math. Static, partial, interrupted,
   bilateral-disagreement, missing-arm, crowd and timestamp cases are included.
2. `prepare-setup-oracle.py` compares exercise identity, observed apparatus
   acquisition, countdown, camera stability and contour geometry against Swift.
Setup tests also exercise the composed runtime rather than only isolated gates.
3. Android API29/API35 CI decodes pinned public pull-up/dip media through the
   production MediaCodec, MediaPipe and OpenCV adapters. Exact frame count, actual
   PTS, derivative checksums and independently reviewed movement totals are
   asserted for every fixture. The prospective `pullup_margin` positive requires
   one movement in both fixed-edge and production Auto replay, with uninterrupted
   counting through EOF. Fixed-edge and Auto are distinct evidence.
4. A Mac CI job replays Android-produced model observations through production
   Swift. It compares count and event outcomes/reasons/times on identical poses,
   independently of Apple Vision versus MediaPipe accuracy differences.

The public clips are development smoke cases, not a held-out accuracy benchmark.
Separate authored setup videos repeat a source-only reviewed starting frame for
six seconds. Native tests use the real decoder, pose model, contours and background
registration without replay rewind, then require Auto identity, the complete
three-second countdown and zero false movements. The fixed recording quaternion
is explicitly virtual. These cases test static setup integration and do not prove
dynamic dip accuracy or a naturally observed six-second starting hold.
The original cropped `pullup_smoke` (1 expected) and panning
`dip_controlled_pexels` (9 expected) failed as 0/1 and 0/9 on both emulators in
run37983414013. They were reclassified as diagnostics **after those failures and
source-only review**, retaining their labels and explicit accuracy failures. A
separate `Original capture accuracy diagnostics` job intentionally stays red while
these fail; there is no `continue-on-error` on instrumentation or that job. This
means the overall workflow may remain red even if the named policy/native jobs
pass. Those job names distinguish engineering qualification from original capture
accuracy; no branch protection requirements are changed here.

The clearer Commons pull-up window/full visible bar annotation was frozen before
its new Android inference; prior iOS source exposure is disclosed in its recipe.
The public dynamic dip cases remain unqualified: three additional public candidates
reviewed before inference had moving cameras, held-only motion or bench dips.
The authored static dip Auto/countdown test currently fails: exercise identity is
recognized, but the partly occluded grip does not provide two independently eligible
observed rails. Both API29/API35 required native jobs fail this setup assertion, independently
of the original capture accuracy jobs. Swift and Kotlin reject the same rail
geometry; no thresholds or fixture labels were changed to bypass the failure.
Static dip Auto/countdown and generalized dynamic dip accuracy therefore remain unqualified. Strict chin
clearance and dip depth remain unverified on both platforms. No user recordings or local predictions are
published. Source recordings and test APKs are excluded from CI artifacts.

Public held-setup timelines retain the exact apparatus search regions, contour
candidates and bounded detector errors returned by the production pipeline on
each detection sample. A null apparatus observation means no detection ran on
that frame; empty candidate lists with errors distinguish detector failure from
a successful search with no eligible edges. The first decoded frame of each
hash-checked public setup fixture is also retained for contour diagnosis. These
records use the existing detector call and canonical decoder pixels. The app
result retains no image buffers, and the private evaluation does not export
these public setup artifacts.

Dip acquisition compares the paired silhouette widths and image supporting lines
of both observed tubes. Widely separated fragments of one tube cannot establish
two rails, including when the crops choose opposite silhouette edges. Each frame
still needs two fresh matching observed edges. Losing either paired witness resets
both witnesses and dwell; reacquisition requires two fresh pairs. The original
below-hand, grip, countdown and counting gates remain in place. This conservative
ambiguity check can reject physically separate rails whose projections overlap.
It corrects a false-positive path; it does not recover an occluded rail or qualify
the held-dip setup fixture. Core tests and the per-frame Swift/Kotlin CI oracle cover
fragment identity, perspective-separated rails, partial occlusion and reacquisition.

Rail selection applies every eligibility gate before ranking tube pairs. A pair
whose fitted centerline is near the hand can still have its selected observed edge
outside the grip-distance limit. That unusable proposal must not hide a lower-ranked
eligible rail. Matching Swift/Kotlin tests and52 per-frame setup oracle cases include
this clutter case for both anatomical reference sides and an unreachable-only
negative. Grip thresholds, bilateral tube identity and acquisition dwell are unchanged.

Replay selects the HDR adapter from complete MIME, profile and color metadata.
Main10, Main10HDR10 and Main10HDR10Plus are exact accepted Android profile values;
unknown or combined profiles are rejected. Dolby Vision profile8 with complete
HLG/BT.2020 metadata may use its compatible HEVC base layer. The native reader
verifies actual 10-bit pixels rather than trusting the extractor classification.
SDR still uses the existing MIME-selected MediaCodec path. Its strict SDR negotiation/output
checks remain in place if a known HDR stream reaches that path; a codec selection
or requested conversion never proves that converted pixels were delivered.
Native reports retain decoder backend, declared mapping and failure stage;
private diagnostic details remain encrypted.

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


## Native evaluation checkpoint

[Android CI run38098329024](https://github.com/yongkyuns/HangInThere/actions/runs/38098329024)
evaluates branch `30126227f0416efaa1f2d6fe0a21b49d251df09f` through PR merge
`01114525df8d8221db78fde1eea81e67e4e696b3`. Their Android, shared, iOS,
script and workflow trees match. Both API29/API35 native reports show:

| Check | Both emulator results |
| --- | --- |
| Four-ABI build, lint, unit and APK gate | Pass, including exact pinned NDK C++ runtime bytes and ABI-specific load alignment |
| Authored HLG/PQ/color HDR JNI contracts | All3 methods pass: VFR PTS, B-frame drain, colors, resize, rotation, rewind and cancellation |
| Positive pull-up, fixed observed edge | 1/1 movements |
| Positive pull-up, production Auto replay | 1/1, complete108-frame counting pass |
| Rendered replay UI, Auto and explicit Pull-up/right-side settings | 1 movement, completed |
| Authored held pull-up, Auto setup | Observed bar and3/2/1 countdown |
| Authored held dip, Auto setup | Dip identified; rail acquisition and countdown fail |
| Original public pull-up/dip captures | 0/1 and0/9; accuracy jobs fail |

All107 shared tests pass with zero failures/skips. The54 setup cases and12
geometry cases match Swift, the iOS framework compiles, and Swift reproduces
both emulators' native count/events. Same-host core p95 is4.9µs for Kotlin and
7.4µs for Swift, below1ms; these times exclude camera and model work.
Decode-inclusive Auto pipeline p95 across the three public clips is68.5–128.0ms
on API29 and105.3–183.3ms on API35. Peak process PSS is303.4–312.7MiB and
292.4–295.4MiB respectively. These are emulator regression measurements on
different hosts. Native jobs remain red because of the held-dip setup assertion.

[Baseline Apple CI run38098329031](https://github.com/yongkyuns/HangInThere/actions/runs/38098329031)
uses Xcode26.3/iOS26.2; [current-runtime qualification run38098374076](https://github.com/yongkyuns/HangInThere/actions/runs/38098374076)
uses Xcode26.6/iOS26.5 on the branch directly. Both pass262/262 native Mac tests
and261/262 simulator mechanics tests; the same pinned H264 color assertions
fail on both runtimes. All8 simulator Vision tests fail on both because Apple
body-pose weights cannot load. The current runtime therefore does not resolve
those failures and is an explicit dispatch diagnostic, not a replacement baseline.
All7 automatic replay tests pass in both runs, but earlier hosted replay exceeded
the unchanged20-second completion deadline after the dedicated executor change.
Those passes do not establish that the intermittent native stall is fixed.

See [Android build and ownership details](../android/README.md) and the
[CI workflow](../.github/workflows/android.yml). The APK artifact is for evaluation;
it contains the pinned Heavy pose model and needs no model download at runtime.

## Authorized private recordings

An explicit authorization can use the separate owner-only
[private evaluation workflow](../.github/workflows/android-private.yml).
`prepare-private-android-evaluation.py` prepares anonymous, silent SDR derivatives
with explicit HDR conversion, preserving every frame and exact rational source PTS;
original-format cases remain separate. Frozen labels and source-reviewed manual
edges are retained. Production Auto replay and manual fixed-edge counter diagnostics
are graded independently, not treated as equivalent setup evidence.

The local caller age-encrypts input assets before transport. The input decryption
key is a temporary environment secret restricted to the reviewed branch; report
decryption keys stay only on the authorizing Mac. Plaintext media, poses, logs and
embedded test APKs never enter public artifacts or shared build caches. Detailed
results are encrypted before upload and retained for two days. Only anonymous
counts and pass/fail status appear in public summaries, which require complete
source coverage and successful native execution/collection. Missing reports fail
closed. Temporary input secrets and ciphertext release assets are removed after all
evaluation jobs terminate. None of this qualifies physical live-camera performance.

### Authorized recording checkpoint

[Private CI run38096344678](https://github.com/yongkyuns/HangInThere/actions/runs/38096344678)
evaluates `130b2dfe8cc976f07a42d14ea397f9cf386cb455`. On both API29 and API35,
all4 SDR cases count2/2 pull-ups and4/4 dips with Auto and fixed references,
for either anatomical side. Counting covers every690/1124 source frame.
On API35, the software HDR path also counts both original pull-up sides2/2
through all690 frames. Original dips count4/4 with the fixed reference through
all1124 frames, but Auto fails rail acquisition. The8 fixed-reference native
pose traces reproduce the same Swift events, reasons and timestamps.
API29 originals were rejected before decoding because MediaExtractor classified
them as Main10HDR10 (4096); the exact Main10-family classification fix is in301.
[Original-only follow-up38098398872](https://github.com/yongkyuns/HangInThere/actions/runs/38098398872)
uses the4 unchanged original-format rows from the frozen8-case manifest at301;
both API29 and API35 count both original pull-up sides2/2 in Auto and with fixed
references, through all690 source frames. Both original dip sides count4/4 with
fixed references through all1124 frames, but Auto cannot acquire the rails.
All4 fixed-reference traces on each API reproduce the Swift events, reasons and
timestamps. Both native jobs therefore fail automatic dip qualification. These
results remain separate from the earlier SDR cases.

The subsequent geometry correction makes line grouping and tube pairing
independent of endpoint direction. Nearly vertical edges with small slopes on
opposite sides of vertical previously failed the signed direction comparison.
Pair endpoints are aligned before projecting their finite shared support;
crossing, width, taper and length checks remain intact. Mirrored rotation and
endpoint-order regressions run in Swift/Kotlin, and both new cases are required
in the 14-case geometry oracle. Replay of the exact captured Android apparatus
candidates now finds a dip lock missed by the old geometry. A fresh native
video run is required to qualify the changed contour grouping and full setup.

Contour grouping also preserves thin tube silhouettes. Its 5px fragment-noise
tolerance previously collapsed supported 2–5px parallel edges before pairing.
The fitter now reuses the tube-pairing predicate to keep edges separate when
their finite shared support qualifies; disjoint noisy fragments still join.
Width, taper, crossing and support checks remain unchanged. Mirrored tests cover
thin tubes at rotated orientations, endpoint/contour reversal, duplicate edges,
and tapered overlap whose full-edge mean separation is below 2px. All 220 local
Swift and 112 Kotlin tests pass; 54 setup cases, 3,014 frames and 17 geometry
cases match production Swift. Fresh native video evaluation remains required.
Private CI serializes identical revision/input batches while allowing independent
frozen original and SDR batches on isolated runners with run-scoped artifacts.

The complete native follow-ups at `354721d` are terminal on both API29 and API35:
[four originals](https://github.com/yongkyuns/HangInThere/actions/runs/38104081270)
and [four SDR cases](https://github.com/yongkyuns/HangInThere/actions/runs/38104082785).
Both sides still count pull-ups 2/2 with Auto and fixed references, and dips 4/4
with fixed references, through every 690/1,124 source frame. Automatic dips fail
in both formats. This regresses the previously passing SDR automatic setup;
every fixed native trace still matches Swift events, reasons and timestamps.

Dip rail selection now prefers the eligible tube whose supporting line aligns
with the wrist, followed by observed support and finite distance. A grasp may
hide the handle's top, so either length or endpoint distance alone can favor
offset clutter. References remain actual finite image edges; grip/body bounds,
distinct bilateral rails, witness continuity and acquisition dwell still apply.
Mirrored regressions cover longer eligible clutter, both sides, reversed inputs
and dwell. All 221 Swift and 113 Kotlin tests pass with zero failures/skips;
56 setup cases, 3,126 frames and 17 geometry cases match production Swift.
Both added setup cases must execute in the oracle. Replaying captured SDR native
observations recovers setup; the proposed edge also counts four movements when
used with the same complete native pose trace on both APIs/sides. Fresh native
[SDR run38106794698](https://github.com/yongkyuns/HangInThere/actions/runs/38106794698)
at `956d17b` verifies all four cases on API29/API35: both sides count 2/2 pull-ups
and 4/4 dips automatically and with fixed references through all 690/1,124 source
frames. Maximum Auto pipeline p95 is 153.4ms and peak PSS is 314.9MiB; these are
emulator regression budgets. The unchanged
[original HDR run38106793078](https://github.com/yongkyuns/HangInThere/actions/runs/38106793078)
still counts 2/2 pull-ups automatically and 4/4 fixed-reference dips, but fails
automatic dip acquisition on both APIs/sides. All 16 original/SDR fixed native
traces match Swift counts, event reasons and source timestamps.

Android now smooths the bounded grayscale contour crop with a 5x5 Gaussian
kernel before Canny. Pose inference and display retain the original pixels;
shared fitting, candidate limits, body exclusion, bilateral witnesses and dwell
remain unchanged. A local host diagnostic recovered original HDR setup and
counted all four dips with the same complete native poses on both APIs/sides.
That host diagnostic used OpenCV4.13 rather than Android4.12. Fresh native
[original run38111525602](https://github.com/yongkyuns/HangInThere/actions/runs/38111525602)
and [SDR run38111531369](https://github.com/yongkyuns/HangInThere/actions/runs/38111531369)
at `d0d843c` now pass all16 cases across API29/API35: both sides count2/2
pull-ups and4/4 dips with Auto and fixed references through every690/1,124
source frame. Original Auto pipeline p95 reaches260.9ms and peak PSS395.4MiB;
SDR reaches181.3ms and309.5MiB. These satisfy the emulator regression budgets,
not a physical-camera or strict-form claim. All16 complete fixed traces match
Swift movement counts, event reasons and source PTS to1e-9; the Swift executable
is rebuilt from the same immutable implementation revision. Native synthetic regressions cover changing
pixel noise, thin/wide rails, source-pixel preservation, dwell, and rejection of
texture and forearm-like silhouettes; they do not claim pose-model accuracy.

Straight-bar dips now have a separate shared support selector beside the existing
parallel-rail selector in both Swift and Kotlin. Dip acquisition adds one bounded
common crop to the two local grip crops. A fresh paired tube must share at least
half the bilateral grip span, expose a finite near-horizontal upper edge near
both hands, have its center on/below each wrist in upright pixels, and have no
distinct eligible alternative. A nearby paired stripe above either grip cannot
start setup. Missing paired support
restarts acquisition; pose alone never supplies a reference. Parallel-rail dwell
keeps priority, and its independent-witness rejection rules remain unchanged.
Both modes use the existing source-time dwell and complete live countdown.
The reference role retains the tracked anatomical hand for counter compatibility;
both hands witness the shared edge. User guidance now describes dip support.

Local qualification of this selector includes223 Swift core tests,116 Kotlin
tests,31 Android app unit tests, actual-source instrumentation compilation,
and72 setup cases/4,022 frames plus17 geometry cases matched against production
Swift. Cases cover both anatomical sides, fresh-pair loss, ambiguous bars,
short support, above-grip stripes, forearms and parallel-rail priority. The iOS app compiles with
the new source registered. Fresh native held-pixel/countdown and full-source
original/SDR runs must qualify the additional crop and setup mode; the earlier
`d0d843c` results do not establish this new mode's accuracy or performance.
The271 native macOS tests pass, including shared-bar acquisition/rewind through
all source frames for either tracked hand. This is host integration evidence;
simulator Vision, color and hosted replay-latency gates remain separate.

Completed replay state is retained until the user starts another evaluation.
Manual-reference controls cannot mutate a replay after EOF or replace its
terminal failure/retry action with an idle snapshot. A monotonic generation
stamp also protects the worker-to-UI handoff and background interruption from
obsolete commands. The native UI regression covers both a rejected Auto
reference and a reference that an explicitly selected exercise would accept;
neither submission may hide `Evaluate again` or change the completed result.
The regression passes on API29 and API35 in
[run38102543956](https://github.com/yongkyuns/HangInThere/actions/runs/38102543956)
at `50751a3`. The only instrumentation failure on either API is held-dip Auto
acquisition; the private-only recording test is intentionally skipped publicly.
At the same revision,
[Apple run38102543963](https://github.com/yongkyuns/HangInThere/actions/runs/38102543963)
passes 264 native Mac tests. Simulator mechanics pass 263/264, with eight color
assertions in the pinned H264 decoder test failing; all eight simulator Vision
tests fail with the unavailable model weights. These gates remain red.

These recordings are development evidence, not a held-out benchmark. Successful
HDR decoding or fixed-reference counting does not qualify automatic setup.
Private poses, images and detailed reports remain local/encrypted. Replay traces
retain the exact apparatus observations consumed by policy outside the timed
pipeline, so subsequent native failures can be replayed without reconstructing
pixels or inventing candidate rails. A null observation means no detector call ran.

The attempted direct8-bit color transform at `f123466` was discarded: despite
exhaustive host pixel differences bounded by one channel level, native API29
Auto dip acquisition regressed to unavailable for both arm selections. The
fixed-edge diagnostic still counted4/4, isolating failure to the complete setup
path. Current conversion preserves the prior float-transform pixel output; no
buffer optimization is claimed without successful complete native reruns.
