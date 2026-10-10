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

Recorded pixels honor actual decoder BT.601/BT.709 and full/limited-range metadata.
Known HDR asks API31+ decoders for SDR tone mapping and requires verified SDR output
before analysis; unsupported HDR, high-bit-depth or unconverted BT.2020 is an
actionable import failure. An SDR conversion succeeding does not qualify the
original HDR container. Camera pixels retain the explicit legacy BT.601 assumption.

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

[CI run37990163632](https://github.com/yongkyuns/HangInThere/actions/runs/37990163632)
evaluates implementation commit `4d6ae095324ad18edeadfcaf902e6c540348cb81` on both
API29 and API35. Its native reports show:

| Check | Both emulator results |
| --- | --- |
| Positive pull-up, fixed observed edge | 1/1 movements |
| Positive pull-up, production Auto replay | 1/1, complete108-frame counting pass |
| Rendered replay UI, Auto and explicit Pull-up/right-side settings | 1 movement, completed |
| Authored held pull-up, Auto setup | Observed bar,3/2/1 countdown, running after3.083333s |
| Authored held dip, Auto setup | Dip identified; rail acquisition and countdown fail |
| Original pull-up/dip captures | 0/1 and0/9; accuracy jobs fail |

The same run passed all89 shared tests, the iOS simulator framework build and
Swift replay of both emulators' model observations. Same-host core p95 was1.3µs
for Kotlin and6.1µs for Swift, with identical consumed checksums. These core-only
times exclude camera and pose inference.

Decode-inclusive production pipeline p95 across the three clips was109.8–130.6ms
on API29 and83.8–102.9ms on API35. Peak process PSS was299.8–311.9MiB and
284.5–290.8MiB respectively. These are emulator regression measurements with
different runner hosts, not a phone comparison or a real-time guarantee. The native
jobs remain red because of the dip setup assertion; this checkpoint does not
establish full functional or performance parity.

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

[Private CI run37998132925](https://github.com/yongkyuns/HangInThere/actions/runs/37998132925)
evaluates commit `46903641818fa54dfec2c5ba165088ce3cb40408`. On both API29 and API35,
the complete SDR derivatives count 2/2 pull-ups and 4/4 dips in production Auto,
for both arm selections. Counting covers all690 and1124 source frames respectively;
actual contours acquire the apparatus before the counting pass rewinds. The separate
frozen fixed-edge diagnostics give the same totals, and production Swift reproduces
all count/event outcomes, reasons and timestamps from the identical Android poses.
These two recordings are development evidence, not a held-out accuracy benchmark.

Both unchanged HDR originals fail import on both emulators before analysis. API35
cannot initialize their Dolby Vision decoder; an SDR derivative passing does not
qualify the original container. Consequently both private jobs remain red despite
all four SDR cases passing. No private poses, source images or detailed reports are
published. Optimization and contour changes require their own native reruns before
these counts can be attributed to a later implementation.

The attempted direct8-bit color transform at `f123466` was discarded: despite
exhaustive host pixel differences bounded by one channel level, native API29
Auto dip acquisition regressed to unavailable for both arm selections. The
fixed-edge diagnostic still counted4/4, isolating failure to the complete setup
path. Current conversion preserves the prior float-transform pixel output; no
buffer optimization is claimed without successful complete native reruns.
