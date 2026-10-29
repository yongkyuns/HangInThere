# Pull-up grip estimates

A separate [RGB hand-patch prototype](workout/GRIP_DATA_EXPANSION.md)
trains overhand/underhand inference from small hand images after detection and
allows a clear athlete with a spotter. Its held-out underhand failure remains
unresolved; it has not replaced the production geometric policy described below.

The latest [crop and label review with separate-video retraining](workout/GRIP_REVIEWED_CROP_EXPERIMENT.md)
fixes the known head-turn diagnostic, but the fresh chin-up comparison still fails.

The subsequent [expanded hand-data and detector experiment](workout/GRIP_EXPANDED_PATCH_EXPERIMENT.md)
adds twelve chin-up training recordings and improves automatic crop coverage,
but its fresh classifier comparison regresses overall. It remains offline.

The [three-class appearance experiment](workout/GRIP_THREEWAY_APPEARANCE_EXPERIMENT.md)
adds neutral/parallel grip and directly reviewed palm/dorsum/side labels, with
matched binary and ternary heads tested on separate videos. Neutral improves
with reviewed orientation, but dorsal-hand confusion, orientation dependence
and chin-up regression prevent phone deployment.

The [image-only surface qualification follow-up](workout/GRIP_SURFACE_QUALIFICATION_V2.md)
improves raw anatomical accuracy from 36% to 60% on new videos. Side appearance
and confidence gating still fail, so orientation fusion and phone deployment
remain gated.

The [quality-head repair](workout/GRIP_QUALITY_GATE_REPAIR_V3.md) fixes the blanket
confidence rejection without changing the surface model or 0.8 thresholds.
It restores a small set of correct accepted hand observations on unscored
videos, while low coverage and anatomical errors still prevent deployment.

The [contact-recall experiment](workout/GRIP_CONTACT_RECALL_EXPERIMENT_V4.md)
triples contact recall on the remaining 19 videos using a separate wider patch,
but the full pipeline still accepts unreadable anatomy. It remains offline.

The [observability and temporal experiment](workout/GRIP_TEMPORAL_OBSERVABILITY_EXPERIMENT_V5.md)
audits source sequences against actual crops and tests frozen temporal rules on
16 new recordings. It finds no verified automatic video-level improvement;
cropping, target-hand ownership and weak torso evidence remain blocking issues.
The human review packet is pending; the prototype remains offline.

The [hand-first extraction experiment](workout/GRIP_HAND_EXTRACTION_EXPERIMENT_V6.md)
removes body-wrist-only output crops and tests ownership independently. It
produces fewer invalid crops but loses complete-hand coverage on five fresh
recordings, so it remains offline and does not trigger grip retraining.

The [correct-crop and orientation diagnostic](workout/GRIP_CROP_ORIENTATION_DIAGNOSTIC_V7.md)
compares fixed models on exposed adult recordings. Manual crops alone recover
two valid inputs but do not fix the remaining chin-up errors; reviewed view
restores missing neutral decisions while exposing strong view dependence.
The next training cohort must cross grips with camera views before retraining.

The [proposed front/rear scope](workout/GRIP_SUPPORTED_VIEW_SCOPE_V8.md) narrows
the supported grip task and retains side cameras as explicit rejection tests.
The [implemented follow-up](workout/GRIP_FRONT_REAR_IMPLEMENTATION_V9.md) adds the
view gate and phone prompt and fills the development coverage gaps. Retraining
still fails anatomical qualification, so the learned candidate remains offline.

Grip policy v4 is optional metadata. iOS retains Apple Vision body inference and its Swift counter; Android retains MediaPipe Heavy and its Kotlin counter. Neither counter consumes hand orientation or width to accept a rep. Shared Kotlin owns wrist crops, geometric association, interpretation, source-time confirmation, bounded timelines, rep summaries, variation totals, and correction policy. Native code owns SDK handles, frames, storage and presentation.

Both platforms use the same checksum-pinned Hand Landmarker full v1 and Pose Landmarker Lite v1 bundles. The secondary Lite model supplies an independent estimated body frame, without changing the primary pose engine. iOS uses the official 0.10.21 binary release because its simulator slice supports Intel and ARM; Android keeps the existing 0.10.32 release. SDK/model IDs are provenance, not accuracy guarantees. Inferred monocular depth is not a measured anatomical coordinate or grip-confidence score.

## Input and geometry

Canonical pixels are upright and unmirrored, including the front camera. Camera-vector axes are right/down/away; hand and body world origins differ. Only vectors within each model result are compared. Missing depth stays unavailable. Native adapters extract six named body points and six hand points; no native policy classifies a grip.

Crop policy v3 first preserves the original forearm-scaled source-pixel recipe: 1.5 times an observed forearm length, bounded to 12–40% of the short image dimension, shifted 15% beyond the wrist; without a usable elbow the first size is 20%. If that crop produces no complete identity-valid hand result, one wrist-centered crop spanning 28% of the short dimension is tried. Shared Kotlin produces these ordered, clipped, deduplicated candidate lists with an explicit intended anatomical side for both platforms. The side hint cannot establish identity: the observed wrist must match the auxiliary anatomical wrist with sufficient separation, and a confident SDK side contradiction rejects the candidate. Each wrist stops at the first identity-valid result, so valid hands incur no extra model call and each observation returns at most two hands from at most four hand-model calls. Model detection/presence thresholds remain 0.5. The crop version is included in backend provenance.

Body preflight v2 uses the existing shared body-frame validation and front/rear view support before planning hand work. It binds the auxiliary context to the current primary pose by source time, image size and the named primary landmark positions/scores. An invalid or foreign body context produces no hand-stage requests on either platform, so it makes zero hand-model calls. Valid contexts keep the same crop candidates and identity checks; hand orientation does not influence eligibility. Auxiliary body inference and one-time model initialization remain necessary. The preflight version is recorded in backend provenance, independently of grip policy v4 and unchanged crop policy v3. Native helper call counts are internal test diagnostics, not grip confidence or stored workout measurements.

Crops translate coordinates back to the original image without rotation or reflection. Points outside their actual source crop remain unavailable even if their restored coordinates fall inside the full image. Candidate retries consider identity only, never palm direction or a preferred grip label. Existing geometric checks and bilateral source-time confirmation still decide whether orientation evidence is usable. The JSON inference bridge also exposes an offline geometric estimate for evaluation; it does not bypass live confirmation. These framing sizes are engineering defaults, not calibrated workout accuracy thresholds.

The independent body detection must provide six strong body landmarks, with valid shoulder/hip geometry and reasonable segment lengths. Its four shoulders-and-wrists anchors must agree with confident primary-body anchors within 12% of the short dimension. Primary hips are additional identity evidence when confident: a conflicting confident hip rejects the frame, while weak or absent primary hips do not erase the strong auxiliary frame. Missing/weak primary shoulders or wrists, weak auxiliary hips, duplicate joints or malformed scores remain unavailable. The primary-hip distinction introduced in grip policy v2 is retained; historical policy-v1/v2 records retain their original version and measured labels. Hands associate with anatomical wrists by distance and a separation margin; array order never chooses a side. A confident SDK handedness contradiction is unavailable evidence. Tasks handedness labels are used on canonical unmirrored pixels: an upstream public `right_hands.jpg` smoke inspection yields Right for both independently pictured right hands. Legacy selfie-display assumptions must not introduce another reflection.

The palm normal derives from wrist/index/middle/pinky MCP geometry, with nondegenerate, mutually consistent triangles. Anatomical ordering requires **left −1 / right +1** times `cross(index−wrist, pinky−wrist)`. The literal test layouts are independently defined; the convention was checked against [Gray's Anatomy plate 219, left hand palmar view](https://commons.wikimedia.org/wiki/File:Gray219.png). Palm alignment with body facing gives overhand/underhand; inward lateral alignment gives neutral. Intermediate orientations, conflicting identity and unavailable anatomy remain unknown. Bilateral different orientations yield mixed grip. Stability cannot remove hidden-hand or monocular ambiguity.

Width measures wrist separation projected along the acquired support, divided by a median shoulder-span baseline frozen during an observed ready hang. Baseline acquisition requires five frames spanning at least 0.4 seconds with at most 15% variation. Width requires fresh near-frontal body context (`abs(facing.z) ≥ 0.85`) and current bilateral wrists near the support; it does not require a known hand orientation or an RGB contact prediction. Narrow `<0.85`, wide `>1.30`, and the intervening **Medium** band are camera-relative engineering categories, with median filtering/hysteresis. The stored middle-band name remains `shoulderWidth` for existing history. Live and per-attempt views show the shoulder-relative ratio; saved estimates include independent coverage, width policy version and diagnostic reason. They are not centimeters or a validated population standard. View/setup changes require fresh calibration. See [v20 width metadata and supported readability comparison](workout/GRIP_WIDTH_METADATA_V20.md).

## Time, history and corrections

Secondary inference is disabled for resolved dip sessions and scheduled at most four times per source second for pull-ups, with one task/image in flight. Late completion never blocks body tracking. Results expire after 0.5 seconds, and repeated cached results cannot confirm hands or earn hand-observed time. Three distinct supporting hand observations spanning at least 0.5 seconds within a rolling one-second window confirm each side. An unsupported side view immediately clears prior confirmation and returns unknown. Returning to front/rear requires fresh evidence. An uncertain observation immediately makes that side unknown and earns no orientation credit, while recent supporting timestamps remain available for up to 0.6 seconds so a new valid reading can restore confirmation. A known contradictory orientation clears previous support; source-observation gaps over 0.4 seconds also reset it. Support history is capped at 32 timestamps per side. Setup/interruption/body-loss cutoffs and native generations reject old pending results. The Apple bridge uses the original source-second clock for this metadata, so truncating the geometry transport timestamp cannot turn a pre-loss result into fresh evidence. Blocking SDK calls use the existing isolated native-frame executor on iOS and an owned serial executor on Android.

Independent orientation and width timelines retain at most 128 spans each. Journals merge bounded deltas; model/backend and interruption boundaries remain explicit. Per-attempt summaries need at least 80% credited coverage for each dimension. A supported change inside an attempt is “Grip changed”/“Width changed.” Missing coverage remains unknown. Native review shows completed-rep totals by variation, per-attempt labels, and original bilateral orientation coverage; incomplete attempts never enter variation rep totals. Raw hand points/pixels are not retained in workout history.

Annotations use a separate atomic correction directory. Each versioned event replaces labels for one attempt or the whole finalized set, with an ordered sequence and timestamp. The newest applicable event wins; “Automatic estimate” restores the measured dimension. Measured records, counts, timings, angles and coverage remain immutable. User labels are explicitly marked. Corrections are not inferred evidence and do not retroactively change readiness, rep acceptance, exercise-family goals or coaching metrics. Ledger growth is bounded to 1,024 events per set.

## Validation limits

Common tests cover literal anatomy, identity ambiguity, absent depth, cached/delayed observations, gaps, sustained outages, bounded/deduplicated primary and fallback crops, explicit crop-side requests, shared candidate identity, short uncertainty without credited gaps, long uncertainty and contradictory orientations, required upper-body identity vs optional primary hips, historical policy preservation, mixed/changed grips, coverage, corrections, old records and incomplete attempts. Apple checks exercise crop planning/offline estimation and the real JSON/array FFI, correction persistence, separate baseline/grip-enabled 2,000-frame checks against the unchanged 5ms host budget, replay exercise switching, and narrow/wide UI renders. Native iOS and Android smoke tests load both pinned models, run body inference and all four primary/fallback hand calls on authored blank pixels with a valid authored body context, and demand unavailable evidence rather than fabricated grips. They also require zero hand calls for rejected body geometry and stale source context on the same native SDK handles. Android API29/API35 engineering CI runs these alongside existing native/parity evaluations.

The private chin-up footage remains local. A dense development probe of the full 21.6-second recording uses 325 production Apple body-pose frames at 15 requested Hz and 80 pinned Python model samples below four source samples/second. A fixed manually inspected bar and immediate offline result delivery isolate crop/analysis behavior. Of 48 sampled bilateral wrist-contact inference frames, the original crop returns both hands in 43 and the missing-hand context fallback in 48. Grip policy v2 confirms underhand in 98 of 197 primary wrist-contact frames; policy v3 with identity retries and bounded support confirms it in 134 of 197 (about 50% to 68%). No opposing orientation is confirmed in this probe. The observed first attempt's credited orientation coverage improves from zero to about 52%, which remains below the unchanged 80% summary requirement; attempt labels correctly stay unknown. Width classification is unchanged.

A replay through the actual shared preflight planner would omit 38 of the 165 recorded hand-model calls (about 23%), across 13 previously active hand stages. All 80 raw geometric estimates remain identical when rejected contexts omit their hands. This measures call eligibility on recorded observations, not a native inference speedup.

These are development-exposed full-recording diagnostics, not held-out grip qualification or native end-to-end latency tests. They do not validate the recording's rep count. Unknown remains the correct output for insufficient bilateral/body evidence. Field accuracy for underhand/overhand/neutral/mixed grips, spacing, occluded/closed hands, front/rear camera and varied viewing angles, plus sustained physical-device inference latency/thermal cost, still need independently labelled capture sets. No rep/form qualification gates are relaxed by this feature.

The [v11 complete review and controlled anatomy experiment](workout/GRIP_ANATOMY_ISOLATION_V11.md)
reopens coverage gaps, compares warm-start anatomy-only/joint training, and
retains all original-reference metrics. No new learned candidate is enabled.

The [v12 frozen quality-head robustness experiment](workout/GRIP_QUALITY_ROBUSTNESS_V12.md)
adds reviewed synthetic training negatives and a real-only control. Both select
the starting checkpoint; the two washed-out false accepts remain. Further
head-only retraining did not improve this development set.

The current development direction is [establishing supported capture conditions](workout/GRIP_WORKING_CONDITIONS.md):
front/rear views with clearly visible hands at sufficient native image scale,
then actionable placement guidance outside that range. This supersedes the
proposed separate quality encoder as the next experiment. The new
[iPhone body-scale and visibility guidance](workout/GRIP_BODY_SCALE_GUIDANCE.md)
uses shoulder/torso size as the distance proxy and displays large actions and
per-hand diagnostics. Its initial placement targets still need a controlled
phone-distance sweep to qualify grip performance.

The [v14 rejection audit](workout/GRIP_REJECTION_AUDIT_V14.md) identifies contact
as the dominant immediate gate and documents weak/ambiguous open-hand controls.
Bypassing the gate admits additional bad inputs; no threshold or deployed
model changes are made by the audit.

The subsequent [posture/motion admission experiment](workout/GRIP_MOTION_ADMISSION_V15.md)
implements the requested removal of learned image-contact admission. A visible
arm must establish exercise through fixed-wrist shoulder rise and elbow
flexion; setup/release evidence is scored separately. On 30 recording-held-out
regressions all automatic grip labels remain unknown. Sparse setup/release
controls have no motion activations, but exercise acquisition, torso direction
and readability still prevent usable coverage. Both the failed initial gate
and the per-arm revision remain offline with unchanged model thresholds.

The [counter-context and independent torso follow-up](workout/GRIP_REP_CONTEXT_V16.md)
reuses the exact shared pull-up counter instead of a second movement detector.
Twelve isolated setup/release segments produce no movements or exercise
admission. Independent torso assessment increases correct view outputs from
14 to 28 sampled frames but exposes one wrong direction; all grip video labels
still remain unknown. Both runs and their limitations are retained offline.

The [v17 confirmed-view controlled baseline](workout/GRIP_CONTROLLED_BASELINE_V17.md)
produces correct labels during each marked full-cycle interval in two exposed
owner recordings, with sparse and late chin-up coverage. The frozen comparison
then fails on six newly exposed separate recordings: omitting readability admits
a wrong grip, while a clear rear-view source never acquires the counter start.
The candidate remains offline; confirmed camera view is a diagnostic input,
and no model, threshold or phone update is promoted from these results.

The [v18 full-rate starting-state follow-up](workout/GRIP_START_CADENCE_V18.md)
feeds every original body frame to the unchanged counter and reuses RGB only
with verified athlete ownership. The rear clip still never meets the measured
starting-hang condition. Explicit research diagnostics distinguish missing
acquisition from release; the two exposed controls retain their sparse correct
labels, and neither separate known-grip source passes. The subsequent user
scope correction retains strict starting-hang requirements and excludes
partial-range outliers instead of changing admission for them.

The [v19 supported-capture evaluation](workout/GRIP_SUPPORTED_CAPTURE_V19.md)
records source exclusions before classifier inference. Twelve newly screened
sources fail the capture conditions, so they establish no fresh accuracy.
A supported, previously exposed rear-view regression acquires exercise but
stays unknown: 45/50 eligible hand crops have strong correct dorsal evidence,
while 0/50 pass learned readability. This remains a real supported failure;
counter/model thresholds and shipping behavior are unchanged.

The [v20 follow-up](workout/GRIP_WIDTH_METADATA_V20.md) removes the learned
readability veto within that frozen supported scope. The rear-view regression
now has correct overhand labels on 19/38 scheduled exercise frames, with no
wrong new decisions on the three exposed supported recordings. Coverage remains
incomplete; this is not independent accuracy or a phone RGB deployment. The
shared width estimator and live/review metadata presentation are implemented
separately from this offline RGB experiment.

The [v21 separate-video width validation](workout/GRIP_WIDTH_VALIDATION_V21.md)
does not qualify narrow/medium accuracy. New narrow footage stays unknown;
provisional medium references are mostly unknown and their few known outputs
say wide. Source overlays expose shoulder-span disagreement and tracking/support
interruptions. An assembled real-video set sequence verifies no width carryover,
but a continuous supported alternating workout remains unqualified. Models,
thresholds and production code are unchanged.

The [v22 continuity/calibration repair](workout/GRIP_WIDTH_CONTINUITY_V22.md)
introduces width policy 2. It observes the counter's existing extended starting
hang rather than adding another serial calibration dwell, and permits width-only
continuity with fresh matching body evidence when arm measurements fail. Width
still cannot activate before the unchanged counter is ready. The exposed video
gain is small; narrow/medium accuracy remains unqualified. V21's provisional
medium shoulder references are disputed, so no correction factor or threshold
change is fitted to make those labels pass.

The [v23 frozen new-video validation](workout/GRIP_WIDTH_REFERENCE_VALIDATION_V23.md)
standardizes source-only joint-center uncertainty regions before inference.
Three new supported clips have correct offline RGB grip-type decisions, with
55–82% scheduled exercise coverage. Medium width is correct on 45/152 native
exercise frames; narrow width remains unverifiable and the wide clip is
blocked by the fixed shoulder-pixel floor. All four completed reps retain
unknown width. An assembled alternating sequence verifies explicit set resets,
not continuous-workout accuracy. Production code and models are unchanged.

The [v24 shoulder-pixel gate repair](workout/GRIP_WIDTH_PIXEL_GATE_V24.md)
introduces width policy 3. It removes the fixed projected-shoulder size veto
while preserving confidence, support geometry, stable ready-hang calibration,
identity/view checks and real interruption resets. The medium example now
retains its width through its completed rep; the wide example improves but still
has insufficient completed-rep coverage. All 824 shared tests and the unsigned
iPhone build pass. Fresh full-set/continuous-alternation qualification remains
outstanding; source-only views and offline RGB results are not phone deployment.

The [v25 bounded confidence-pause repair](workout/GRIP_WIDTH_CONFIDENCE_PAUSE_V25.md)
introduces width policy 4. Frozen shoulder geometry now owns ratio validation
throughout a rep, and brief single-wrist confidence dips can suspend only the
calibration for at most 0.35 s. Paused frames remain Unknown and uncredited;
fresh width-band confirmation is required after recovery. The wide example's
second completed rep now saves Wide at 81% coverage; its first remains Unknown.
All 831 shared tests and the unsigned iPhone build pass. No new unseen accuracy
or phone installation is claimed.

The [v26 placement-revalidation repair](workout/GRIP_WIDTH_PLACEMENT_RECOVERY_V26.md)
introduces width policy 5. A brief lone weak-wrist outlier can remain unmeasured
while strong torso/opposite-wrist continuity preserves calibration. Fresh
confident wrists at the same supported placement and in the same canonical band
can resume an already confirmed width; moved grips require fresh confirmation.
The first wide rep now saves Wide at 93% coverage, and the second reaches 98%.
All 838 shared tests and the unsigned iPhone build pass. This remains exposed
regression evidence, not independent accuracy or a phone installation.

## Fresh full-set validation v27

The frozen v26 detector is **not qualified for reliable full-set grip metadata**
on this new cohort. Production code, both RGB models, thresholds and counter
policy were unchanged. The [result fixture](../Evaluation/fixtures/grip-full-set-validation-v27.json)
records the complete source ledger and evidence paths.

Twelve previously unused public video groups were acquired. Five were excluded
by source review for angle/cropping or edits that interrupted sets; one VP9-in-MP4
original failed Apple's native decoder. Six recordings reached inference:
16,883 original native frames, 2,114 scheduled RGB images and 3,525 automatic
hand crops. Thirteen exercise segments include eleven temporally complete sets;
some are pike, lateral, assisted or mixed-grip stress cases, so this is not eleven
ordinary strict pull-up sets. All twelve groups are now exposed evaluation data.

Two recordings contain real continuous changes, with no resets at reference set
boundaries. [Coach Erik Hajer](https://www.youtube.com/watch?v=YnykpezuXWg) moves
from overhand to underhand across six sets, narrowing hand placement.
[The mechanical drop set](https://www.youtube.com/watch?v=hD0BOEH7bME) changes
wide overhand → closer underhand → neutral in one shot. The last neutral set is
cut short. Actual release/context clears occur between these sets; neither clip
is assembled from separate source videos.

| Recording / segment | Visible grip coverage during exercise | Wrong visible grip frames | Completed counter reps |
| --- | ---: | ---: | ---: |
| Hajer, overhand regular | 86% | 0 | 4 |
| Hajer, overhand pike | 74% | 0 | 4 |
| Hajer, overhand lateral | 82% | 0 | 5 |
| Hajer, underhand regular | 68% | 0 | 4 |
| Hajer, underhand pike | 77% | 0 | 5 |
| Hajer, underhand lateral | 61% | 0 | 3 |
| Mechanical drop set, overhand | 59% | 270 | 3 |
| Mechanical drop set, underhand | 49% | 184 | 2 |
| Mechanical drop set, neutral | 52% | 144 | 1 |
| Gloved parallel-handle stress set | 20% | 195 | 3 |
| Rear medium mechanical-drop stress set | 0% | 0 | 0 |
| Rear close chin-up | 0% | 0 | 0 |
| Opposing-hand airborne switches | 7% | Unsupported mixed grip; not correctness gold | 0 |

Coverage counts all native exercise frames, including unknowns and tracking
failures. Adjacent frames are correlated observations, not independent trials.
Across the 34 actual completed counter-owned reps, offline RGB metadata is correct
on 18, wrong on 3 and unknown on 13 at the unchanged 80% credited-time rule.
These figures exclude uncounted source reps; they are not end-to-end rep recall
or population accuracy. Native saved grip orientation is still Unknown because
the trained RGB classifier is a separate research pipeline.

The successful Hajer type change has no prior overhand label during the new
underhand exercise window. The mechanical-drop errors are fresh hand evidence
after real release/context clears, rather than old labels surviving the reset.
At 6.133 s, its visible hand patch incorrectly favors dorsal surface at 0.811;
the other hand is behind an upright and remains unclassified. At 26.400 s, an
underhand patch incorrectly favors palm at 0.822. View is supplied as front, so
these errors cannot be attributed to automatic face/orientation inference.
The gloved neutral stress set also emits wrong underhand decisions.

The rear close chin-up exposes a different failure: 112/131 scheduled exercise
images have raw underhand evidence, but all 131 are rejected by the shared
exercise context. The counter never establishes outbound exercise reliably.
The rear medium set also never admits exercise. Lowering image confidence would
not address either failure. No manual crop rescue or counter relaxation was
applied.

Width is known on 19/34 completed reps: 18 Wide and one Medium; 15 stay Unknown.
Only six known-width reps have an unambiguous source-region category, and all
six agree. The remaining reference intervals straddle boundaries. This reference
uncertainty affects scoring only; it is not an inference/readability gate, and
an interval spanning two bands does not establish that either predicted band
is wrong. The separate unambiguous Medium source remains entirely Unknown.
**Narrow accuracy and categorical narrow/medium/wide alternation remain
unqualified**, despite visible changes in hand spacing. The wider references
are too limited to justify a general accuracy claim.

There are zero new counter movements, acceptances or completed reps across
4,881 definite setup/rest/release frames. However, the Hajer recording keeps an
exercise/grip label for 303 frames (about 10.1 s) during one-hand explanatory
rest at 55.02–65.10 s. It produces no counted rep, but metadata lifecycle still
needs correction. This replay seeds support from observed wrists and supplies
source-reviewed views; it does not qualify automatic acquisition on the phone.

Source annotations and model/code hashes were frozen before inference. Reviewing
all 2,177 hang/exercise crop patches exposed one source timing annotation error:
the close chin-up's last sample shows release, so it is a complete ten-excursion
set. That temporal correction was recorded before inspecting predictions/counter
outcomes, after learned inference had started. Original annotations, code, the
correction receipt and raw outputs remain preserved; grip and width references
were not changed. No source entered fitting.

Evidence is under `build/grip-width-v27/`: `continuous-timelines.png`,
`failure-examples.png`, `run/test/report.json`, `run/test/attribution-audit.json`,
`run/test/scored-reps.json`, source/reference/input freezes and all acquisition
exclusions. The exact shared replay passes, as do six accounting tests covering
unknown gaps, identity resets, time clipping, event deltas and completion rules.
No phone build or installation was attempted for this evaluation-only change.
The disposable compiler-cache check found no remaining caches; temporary plot
cache files are removed after rendering.

## Exercise metadata lifecycle and pose failure audit (v28)

The v27 one-hand explanatory rest exposed a metadata lifecycle bug: the counter
could remain returning while one hand had left support. Policy 2 in
`WorkoutGripExerciseContext` now revokes offline RGB eligibility when a current,
confident wrist on an independently usable arm is far from the supplied support
line. Missing, weak, duplicate or invalid joints cannot witness departure. This
adds no image-based bar/contact check. After departure, regrasping cannot restore
the old evidence bank; an actual counter ready phase followed by outbound motion
is required. Counter readiness and counting are unchanged.

The final replay removes all 303 stale-label frames and all 37 new grip decisions
in the 4,881 definite setup/rest/release frames. Counter and width frame fields
are identical across five fixed replays totaling 28,985 native frames; saved rep
records are byte-identical. The original regular exercise windows lose no correct
visible grip frames. Completed-rep RGB metadata remains 18 correct, three wrong
and 13 Unknown: this change fixes lifecycle, not hand-surface classification.
On the three older v23 sources, all 71 classifier decisions, scores and times are
identical. One retained underhand frame becomes Unknown at 12.053708 s, outside
the frozen exercise window and one frame before the unchanged counter release.
No older active-window label changes. This is not an all-frame-identical RGB
result.

An initial candidate incorrectly measured distance to the finite wrist-seeded
segment. Motion along the support past its endpoints suppressed 67 correct
underhand frames. That candidate and its failures are archived under
`build/grip-context-v28/first-attempt/`; the final candidate measures perpendicular
distance to the support line and includes a regression test for this geometry.
These are exposed development iterations, not a new untouched holdout.

The rear close chin-up's missed start remains unresolved. Apple Vision puts
wrists on the torso and loses usable arm observations near several peaks. A
current-image MediaPipe Hand/Pose Lite probe on 145 existing images also misses
hands or mislocalizes arms at critical times. Initial wrist locations only bound
search regions; they were never substituted as current evidence. No synthetic
confidence, repeated-image votes, fused joints, model retraining or relaxed
counter requirements were introduced. The probe does not qualify a replacement
tracker. Narrow/medium width and categorical width alternation remain unqualified.

Validation passes 848 shared tests (zero failures/errors/skips), including nine
new lifecycle/geometry cases, and `:shared:compileKotlinIosArm64`. This context is
currently consumed by offline RGB research; the phone's geometric grip owner
does not yet use it. No phone update or installation was made.

See `Evaluation/fixtures/grip-exercise-context-cleanup-v28.json` and the authoritative
`build/grip-context-v28/final/comparison.json`. The final directory preserves input
and candidate freezes, all five replays, native rep records, RGB comparisons and
JUnit results. `metadata-cleanup.png` shows the rest fix;
`pose-failure-probe.png` shows the unresolved arm observations. Frozen v27 evidence
is preserved rather than rewritten around the new policy.

## Visible-arm capture requirement and phone guidance (v29)

After reviewing the v28 annotated source frames, the user confirmed that
`c79BFTkrt9s` repeatedly hides its arms during the rear narrow chin-up. It is
outside supported pose/rep-counting capture conditions and remains an occlusion
stress test. This is a retrospective decision after observing the failures,
not a pre-inference exclusion. Original v27/v28 scores, timing references and
results remain unchanged; no improved aggregate accuracy is claimed by removing
the failure. Hand-patch references remain available for their separate task.
The review is recorded in `Evaluation/fixtures/grip-arm-visibility-review-v29.json`.

Front/rear placement alone is insufficient: shoulders, elbows and wrists must
remain visible through the movement. The iOS live overlay now displays a large
orange **SHOW YOUR ARMS** card with **Move camera so both arms are visible**
after one second of continuously unmeasurable arm chains during a running set.
The heading changes to **CHECK VIEW**, and this warning takes priority over the
grip card, grip label and set-start announcement. It describes unclear tracking
rather than asserting that physical occlusion has been detected.

This is guidance only. Pull-ups retain the existing fallback to either usable
arm; one briefly hidden arm does not trigger the warning. A usable arm clears
it immediately. Dips retain their selected-arm rule. Missing/ambiguous athletes,
invalid image geometry, duplicate/regressing time and source gaps longer than
0.35 seconds cannot accumulate warning duration. Camera/analysis resets and
set transitions clear the feedback. Setup retains its existing readiness guidance.
No classifier, counter, rep acceptance or metadata policy changes are involved.

Six new temporal/fallback cases pass within 24 native Swift framing/readiness
tests. The shipping SwiftUI overlay is rendered and checked at 320- and 430-point
widths, including the visible Finish set control and suppression of conflicting
grip advice. The complete unsigned iPhone app build passes. Evidence is under
`build/arm-visibility-v29/`; previews are under
`build/workout-analysis/render/arm-visibility-{320,430}.png`.
No phone installation was performed.

## Fresh visible-arm source validation (v30)

The source-only capture screen precedes all pose/RGB inference. Nineteen source
groups were selected, eighteen acquired and one unavailable. Fourteen acquired
clips were excluded for side/oblique views, rear narrow self-occlusion, gloves,
tiny hands or camera cuts/occlusion. The initial ten-source acquisition was
expanded twice, explicitly recorded before inference, to seek the unfilled
narrow/medium and real grip-change conditions. Every acquisition outcome remains
in the ledger. No replacement was based on detector success.

Two complete recordings and two partial-cycle diagnostics were frozen and
processed: 4,736 original native frames, 446 scheduled RGB images and 702 automatic
crops. Source-confirmed front view and wrist-seeded support remain diagnostic
inputs, not qualified phone acquisition. No model, threshold, crop, counter or
width policy was changed. These sources are now exposed test footage, not a fresh
reserve for a later tuning comparison.

| Recording | Qualification | Full cycles referenced / completed | Correct / wrong / Unknown rep grip |
| --- | --- | --- | --- |
| [Chin-up demonstration](https://www.youtube.com/watch?v=QA5ko5Ibu0g) | Complete source set, front | 2 / 2 | 1 / 0 / 1 |
| [Loaded/unloaded pull-up drop set](https://www.youtube.com/watch?v=B4I9eBw88yQ) | Complete source set, front, coach present | 6 / 6 | 5 / 0 / 1 |
| [Underhand demonstration](https://www.youtube.com/watch?v=BJ8qpXv3bxk) | Partial-cycle diagnostic | 3 / 3 | 2 / 0 / 1 |
| [Medium-titled pull-up demonstration](https://www.youtube.com/watch?v=0j9CUk1NjOY) | Partial-cycle diagnostic | 2 / 1 | 0 / 0 / 1 |

The chin-up source includes an initial excursion from standing without an extended
hang; the two subsequent full hang-to-hang cycles were separately marked before
inference. Partial diagnostics have cut-off set beginnings/endings and do not
qualify complete sets. The missed partial cycle briefly has no raw Apple body
observation at 4.972–5.038 s as the head rises outside the frame. It remains in
the result, rather than being retrospectively removed.

Across the twelve actual completed reps, grip metadata is correct on eight,
wrong on zero and Unknown on four at the unchanged 80% credited-time rule. The
two complete recordings alone have six correct and two Unknown. These are small,
correlated source results with source-reviewed orientation, not general accuracy.
All 2,542 definite setup/release frames have zero new counter movements,
acceptances, completed reps, visible grip labels or grip decisions. Fourteen such
frames nevertheless have exercise context eligible; this is not a zero-false-
context result.

First-rep Unknowns have distinct causes. The pull-up's raw hand evidence is
correct, but its first visible label arrives at 22.155 s after temporal
confirmation. The chin-up's 19.770 s automatic crops sit below the grip and clip
the finger area; a correct right-hand prediction does not establish correct
localization. Its next qualifying image arrives at 23.774 s. Neither learned
readability nor image contact is a requirement. No manual crop rescue was applied.

Width remains unqualified. Ten completed reps save Wide and two save Unknown;
none of the ten known-width reps has a single-band reference. However, the
chin-up's frozen source interval is 0.676–1.257, which spans Narrow/Medium and
excludes nominal Wide. The original categorical scorer leaves all uncertain
references unscored. A separately recorded **post-inference** interval-compatibility
audit flags that saved Wide as incompatible with its source interval. Reference
uncertainty cannot excuse a wholly disjoint prediction. The other nine known
widths overlap broad intervals and are not verified successes. Source annotations
remain provisional agent review, not human gold; nominal-band compatibility does
not replace the declared hysteresis policy or calibrate a new threshold.

At 19.503 s, QA's pose shoulder span is 89.57 px, with an implied frozen shoulder
denominator of 81.55 px and reported width ratio 1.420. The pose shoulders are
medial relative to the frozen source regions. This points to geometry/calibration
inflation, separately from hand-surface inference or camera-orientation logic.

No accepted recording establishes a real grip-type or categorical width change.
The loaded/unloaded clip keeps the same grip throughout, including the weight
drop. It is replayed continuously without a reference-boundary reset. Narrow and
Medium band accuracy and real grip-change qualification remain open.

Evidence: `Evaluation/fixtures/grip-visible-arms-validation-v30.json` and
`build/grip-fullsets-v30/run/test/{report-v30,attribution-audit}.json`.
`complete-set-timelines.png` and `first-rep-examples.png` show the results and raw
crops. Six accounting, six reference-geometry and four interval-compatibility
tests pass, plus the exact shared replay JUnit test. The unchanged scorer is run
with the same Python 3.12 environment as source annotation; a first system-Python
attempt differed by 9e-16 in an exact geometry assertion, recorded separately.
No phone installation or training was performed.

## Constant grip type within an owned set (v31)

The user clarified that incomplete hand views may remain Unknown and grip type
is assumed constant during a set. A reliable later identification can therefore
annotate the set and its earlier reps. `WorkoutSetGripAnalysis` now owns this
small, separate annotation for each actual `WorkoutAnalysis`/set ID. It consumes
already-confirmed hand classifications during exercise or a supported inter-rep
hang. It does not weaken hand, view, subject, confidence or temporal qualification.

Missing hands and temporary tracking interruptions leave the set annotation
alone while direct frame estimates can become Unknown. A new set starts Unknown.
Conflicting confirmed types or model/backend provenance leave the automatic set
annotation Unknown rather than choosing the last label. Repeated/cached source
timestamps cannot advance confirmation times. No annotation from another set,
unsupported policy version or outside the set's source-time window is used by
review. Explicit user corrections still take precedence.

`WorkoutSetRecord.setGrip` stores the annotation and original confirmation times;
compact summaries, merged drafts and the native Swift transport preserve it.
Review applies it with `orientationSource = setEstimated`, shows **Set grip** and
**Grip inferred from this set**, and keeps each rep's original hand coverage and
measured label. A rep can display an inferred Underhand type beside 0% directly
classified hand coverage. This does not invent an earlier hand observation or
change a stored count, motion sample, per-rep measurement or width. Older records
without the optional annotation retain their existing automatic estimates.

Reprojecting the unchanged v30 decisions on the same source-identity episodes
annotates all twelve completed reps correctly: the previous eight correct/four
Unknown become twelve correct as set metadata. Four reps gain an annotation;
two of these had already completed before the first accepted type identification,
and two had insufficient direct coverage despite identification near their end.
Original frame labels, raw scores, native rep records and widths are unchanged.
These recordings have no accepted real grip change, so this is exposed-data
metadata development, not new classifier accuracy, fresh-video qualification or
automatic physical-set boundary validation. Reference labels never enter the
annotation owner. Real grip-change and Narrow/Medium width gaps remain open.

Validation passes 860 shared tests, including set isolation, late annotation,
missing-hand continuity, conflict handling, corrections, compact serialization,
draft merging and preserved incomplete-attempt counts. Five native Swift metadata
tests pass; the actual review renders at 320/430-point widths, preserving the
original 0% hand coverage display. The full unsigned iPhone app builds. Android
review uses the shared report and distinguishes inherited labels; the Android app
was not compiled because this host has no Android SDK. The phone classifier/model
is unchanged and no phone installation was performed.

See `Evaluation/fixtures/grip-set-metadata-v31.json` and
`build/grip-set-metadata-v31/{comparison,baseline-freeze,candidate-freeze}.json`.
The old affected sources are archived under `baseline-code/`, preserving their
frozen versions. The new regression does not rewrite or rerun v30 inference.

## Optional grip guidance (v32)

Grip classification and capture feedback are optional metadata. They do not
participate in starting-pose evidence, readiness dwell, the counter, or automatic
set boundaries. The hand SDK already runs asynchronously on its own worker;
inference failure returns an unavailable hand observation rather than failing
the body frame. The iPhone guidance now says **GRIP · OPTIONAL** and **Start and
reps don't wait for grip** in large text. Actual start checks appear before the
grip card; detailed capture advice remains expandable.

Thirty-two focused shared grip tests pass. A runtime regression delivers the
same body-motion cycle with missing, failed, stale, side-view and differing hand
observations: automatic start, completed rep timing and motion measurements stay
identical. Another test keeps wide spacing independent of overhand, underhand,
neutral and unknown hand evidence. The shipping SwiftUI renders at 320/430-point
widths, including hidden hands beside READY TO START and TRACKING.

Wide hand spacing and arm angles can support a future evaluated classifier, but
spacing alone must not force overhand or erase direct hand evidence. Supinated
pull-ups at a 60 cm width were included in
[Lee et al. (2018)](https://doi.org/10.26862/jkpts.2018.09.25.2.47).
The existing width calibration also has a known false-Wide example in v30.
No new anatomical cutoff, orientation prior, model training or classifier
accuracy improvement is claimed by this change. Evidence is retained under
`build/grip-optional-v32/`.
The signed Debug build was installed on the iPhone 14 Pro; all 20 existing app
data files were preserved. Automatic launch was denied because the phone was
locked. Installation does not qualify live classifier accuracy.
