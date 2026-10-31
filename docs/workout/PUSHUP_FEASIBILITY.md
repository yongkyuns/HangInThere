# Push-up feasibility

2026-10-30: Two owner recordings, five push-ups each. These are exposed
development clips. iOS now exposes a separate live practice preview from Home.

| Complete video | Apple Vision 1 | MediaPipe Heavy | Setup/release false counts |
|---|---:|---:|---:|
| Side, 27.97 s / 839 frames | 5/5 | 5/5 | 0 with both |
| Front, 29.70 s / 891 frames | Not admitted | Not admitted | 0 with both |

The side-view probe acquired a straight-leg plank at 7.37 s (Vision) and
6.97 s (MediaPipe), then released on kneeling at 23.60/23.54 s. Each return
matched one manually marked window. Front view never acquired readiness;
its zero counts are a deliberate unsupported-view rejection, not a counting pass.

On the side clip, usable near-arm geometry covered 99.3%/100% of exercise
frames; shoulder–hip–ankle geometry covered 93.3%/96.4%. The far arm was
mostly unavailable. Front-view arms were often usable, but complete body
geometry appeared in only 3–15% of exercise frames across sides/estimators.
Those percentages describe joint availability, not landmark accuracy.

The initial fixed 150° return rule with a 0.35 s lower-body timeout produced
0/5 Vision and 5/5 MediaPipe. Vision's projected elbow angle fluctuates near
extension, and an ankle disappears briefly near the third bottom. The revised
probe calibrates extension from the starting plank, corroborates the return
with shoulder recovery, and preserves cycle state across lower-body gaps up
to one second. It still requires current arm and body evidence at both
endpoints; missing measurements cannot complete a rep. These changes were
developed on these recordings and require separate videos before qualification.

`Evaluation/pushup_feasibility.py` contains the evaluation-only counter and
its thresholds. It processes full-source timestamps without label/count inputs.
Five concise synthetic tests cover setup, partial motion, release, missing
joints, arm switching, source gaps and invalid measurements.

Recommended first app profile: standard floor push-ups from the side, a stable
low phone in landscape, whole body visible, one clear shoulder/elbow/wrist
chain, and straight-leg plank calibration. Use an exercise-specific athlete
association profile: the existing paired-shoulder/upright assumptions suit
pull-ups/dips and should not be relaxed globally. Native phone replay on
separate complete videos remains the next qualification step.
Strict depth/form grading, knee push-ups and front-view counting remain outside
this initial scope. The shared controller below implements the initial profile.

## Shared implementation

`SideViewPushUpMotion` now owns side-view athlete association, plank calibration,
observed down/up cycles, set release and concise capture cues in common Kotlin.
iOS Home includes an illustrated Push-ups shortcut with a Preview label. It
opens side-view setup and live practice with large rep/readiness feedback and
normalized depth progress. Native capture supplies independent camera readiness,
with matched landscape preview/inference rotation. Three focused integration
cases verify camera gating, rotation isolation and that practice never creates
legacy pull-up/dip records. The preview now saves source-timed sets and rep curves
through the existing recoverable history outbox. Review shows lowering/pressing
tempo and measurements from the same visible arm; setup remains private and
interrupted returns cannot count. The side-view method has its own comparison
identity and boundary version. No hold, fatigue or muscle-load estimate is inferred.
Android entry and set-video recording remain pending; this preview saves no video.

The shared replay retains 5/5 with each estimator, zero setup/release counts,
and front-view rejection. Repeating the original recording with shifted clocks
produces two separate 5-rep sets; that is a regression, not independent evidence.
The initial integration lost Vision's third rep when the hip briefly vanished.
The isolated side-view association profile now permits up to one second of
same-arm spatial continuity after torso acquisition. It cannot acquire from an
arm alone, switch sides, synthesize a hip or complete a missing-body endpoint.
The default upright profile and existing Apple constructors are preserved.

Nine common regression checks cover these boundaries. Apple native/JVM replay
matches on all 3,460 frame snapshots and final set records. This qualifies common
rule transport on the two exposed clips, not capture/model accuracy on devices.
Separate complete positive sets are still needed before production rollout;
the additional public candidates reviewed did not provide a suitable such set.
Evidence: `build/pushup-shared-v141/`.

Local evidence (ignored, includes private frames):
`build/pushup-feasibility-v140/`, containing frozen pre-inference manual labels,
source/model hashes, pose streams, cycle results, charts and tracking examples.
Both estimators consumed the same production-decoded opaque sRGB pixels.
Vision ran on macOS; MediaPipe 0.10.32 CPU VIDEO used Android's pinned Heavy
weights. This does not qualify native Android execution, phone throughput,
camera registration or production athlete association.

Replay probe: `python3 Evaluation/pushup_feasibility.py build/pushup-feasibility-v140`

Checks: `python3 -m unittest discover -s Evaluation/tests -p test_pushup_feasibility.py`
