# Detection baseline handoff — 2026-10-28

**Not production ready. Zero independently qualified sets in every release cell.**
Work on [issue #31](https://github.com/yongkyuns/HangInThere/issues/31)
prioritizes automatic set transitions and completed image-cycle counting over
further grip research. **Build 123 is installed on the
owner's iPhone 14 Pro** with a compact visual camera overlay. All 38 saved/temporary
files match the latest pre-update backup. Manual live testing is available; fresh
iPhone video inference and physical soak qualification remain outstanding.
The overlay uses one fixed-height status panel; full checks open in Diagnostics.
Return-half progress scales to the current rep's measured excursion and follows
observed retreat before counting reversal confirmation. Counting policies are unchanged: all other
motion fields match the prior implementation across 101,055 recorded pose frames,
and 40 focused/replay tests pass. These checks do not establish phone counting accuracy.
Build 122 adds background-free pull-up/dip artwork, date-grouped history and silent
local clips trimmed from the first completed rep to the last return. Video work
uses a separate bounded queue and does not gate counting. All Kotlin/counting
policies are unchanged. Sixteen real-encoder/storage checks pass, including decoded
setup/release exclusion, separate consecutive clips, zero-rep discard, expired
pre-roll rejection and deletion protection. Home was checked in iOS Simulator;
history was visually reviewed with native SwiftUI presentation fixtures. Fresh
physical recording performance/soak remains pending. Evidence and signed product
archive: `build/counted-video-history-v122/`. No counting qualification credit.
An additional local replay of the owner's two-pull-up video passed the actual
720×1280 encoder/export/library path: a silent 7.535-second clip, 227 decoded
frames with no gaps, delayed confirmation and a shifted camera clock. The earlier pre-test
phone snapshot had the same 35 protected files and no new saved sets or clips. This adds recording mechanics coverage, not physical-phone qualification.
Build 123 adds retained rep keyframes with video seeking, phase-time/slowdown
visuals and schematic muscle guides; skeleton/reference overlays are opt-in in
Diagnostics. Seventeen encoder/storage/tempo checks pass, plus six real-video
thumbnail extractions. Older clips without saved clock alignment remain playable
without guessed keyframes. The latest phone backup includes a newer dip record
(two complete cycles and one unfinished attempt) and its video, preserved byte
for byte. Evidence: `build/keyframe-review-v123/`. Counting policies are unchanged.

## Supported qualification profile

Use a fixed, approximately level phone at **chest/shoulder height**, directly in
front of or behind one clear athlete. Require sufficient lighting, reasonably
close framing, and visible head, shoulders, elbows and wrists without occlusion.
Floor-level upward and heavily side-facing views are outside this profile.
Screen capture eligibility before inference and retain every in-scope failure.

Count completed **image movement cycles**. This does not verify anatomical
lockout, chin clearance, dip depth, load or coaching/form quality. Label holds,
setup adjustments, unfinished returns and hold-only practice explicitly;
hold-only practice is not automatically a false set. Grip metadata is optional
and must never gate starting/counting. Dip rails, image-contact classification
and leg crossing are not prerequisites; leg crossing can only be an extra cue.

## Current local runtime

| Component | Method / policy | Role |
| --- | --- | --- |
| Pull-ups/chin-ups | `overheadPullUpRelativeMotion` count 4 / boundary 8 | Source-owned bilateral shoulder/arm motion and observed support |
| Parallel-bar dips | `bilateralTorsoDipRelativeMotion` count 2 / boundary 4 | Rail-free posture/motion; integrated into the automatic live path |
| Native camera | measurement 3 / point sampler 2 | Independent background correspondences and phone stability |

Preserve rational source clocks, fresh measured endpoints, athlete/context
ownership, camera-movement interruption and display/draft/history consistency.
Never synthesize missing joints or award motion/dwell to unavailable frames.
Historical count/boundary cohorts must remain readable without mixed versions.
Native capture/inference and other device/platform families need separate
qualification; shared tests alone do not establish their accuracy.

## Evidence and remaining failures

All results below are **exposed development evidence**, not final-test credit.

| Check | Result and limitation |
| --- | --- |
| Current camera policy 3, 40-cycle source | 36 counts; 32 paired peak/return matches at 0.5s, 20 at 0.2s; 5/8 exact raw set totals and 3/8 exact paired/natural lifecycles. All eight sets disengage; no labelled setup/release completions or false splits/merges. |
| Isolated camera policy 4 candidate, same source | 38 counts; 33 paired at 0.5s, 20 at 0.2s; 6/8 exact raw totals and 3/8 paired/natural lifecycles. Strict timing precision falls 88.9%→86.8%, recall rises 80%→82.5%. All 32 previous matched cycles remain. One recovered count is 17.4ms before its unchanged allowed return window. **Keep the candidate isolated pending timing/full-regression review.** |
| Candidate's complete native regression | Ten deliveries / nine unique videos, 101,055 original frames, 36 closed records and one active EOF draft; all 18,105 displayed/draft/record count prefixes agree. Owner recordings retain 5 dips, 4 dips and 2 pull-ups. Long dips retain 110/110 paired cycles and 11/11 exact naturally closed sets; long pull-ups retain 69/70 and 11/12. Rotation ends `phoneMoved` with no later completions. Rear dips retain 15 cycles but end `trackingLost`; narrow chin-ups retain 10 main / 8 tight matches. No labelled setup/dismount completions or false splits/merges. Unlabelled owner-start intervals remain unscored. |
| New frontal dip challenge | 5/5 paired at both tolerances and zero mount/dismount counts. The source ends about 0.27s after hand withdrawal: the set stays active at EOF, so automatic ending is unverified. Draft/storage recovery retains five counts without natural-close credit. |
| Engineering checks | Current shared suite: 1,097 tests / 0 failures / 1 skip; camera candidate: 15 XCTest cases plus 42 Swift Testing cases, with one optional fixture skip. Candidate native geometry: 118 cases; actual pixel controls: 27/27 static ready, 56/56 movement and 24/24 cut/blank/random never ready. These are regression checks, not population/device qualification. |

Two remaining raw misses in the 40-cycle source involve unusable body evidence,
including a confident wrist placed near an elbow. Other failures concern paired
timing. Keep the original labels/tolerances; do not reinterpret unmatched events
as successes or exclude them after predictions. The isolated replay and all ten
per-video verifiers finished successfully; this verifies execution and storage,
while the accuracy failures remain. Serial simulator replay uses
cached Mac Vision bodies and authored phone orientation/gravity; it does not
measure fresh iPhone inference, real camera delivery or a physical soak.

The smaller camera implementation in v110 preserves all 118 native geometry
results and compiles for simulator/iPhone SDK targets. It has no app replay of
its own. Its source and the v109 experiment remain separate from shipping.

The isolated count-5/boundary-9 native app now reproduces the lower-endpoint
probe's improvement on the fresh source: 35/40 paired matches from 38 emissions
at 0.5s (previously 33), and 31 at 0.2s (previously 20). Every previous match is
retained; exact paired/natural lifecycles improve from 3/8 to 4/8. First-seven
delivery checks pass, including the owner's recordings and rotation, but rear
dips still end `trackingLost`. The full regression is incomplete; its execution
handle was lost and needs revalidation. No promotion or independent qualification is claimed.

## Release acceptance contract

1. Validate the real unseeded automatic path on independently labelled,
   source-separated **uncut sessions**, including approach/setup, mount, normal
   and varying cadence, pauses/holds, partial/unfinished returns, dismount,
   rest and a second set without restarting the app. Match individual cycles
   and start/end times, not just aggregate counts. Freeze labels before inference;
   keep development/training footage out of the final independent test group.
2. Initially collect **at least 30 full sets in each cell**: pull-up/chin-up front,
   pull-up/chin-up rear, dip front and dip rear; **at least 10 athletes overall**.
   Every cell must reach **≥99% completed-rep precision and recall, ≥98% exact
   per-set counts**, and **zero setup/dismount completions or false mid-set
   splits/merges**. Report sample sizes, annotation uncertainty and source/athlete
   clustering; those minimum sample counts alone do not establish the rates.
3. Qualify **consecutive live sets and a 15-minute live-camera/analysis soak on
   the target iPhone**. Check latency, frame loss, false stability interruptions,
   thermal state and saved counts against the recording, including rest/next-set
   recovery. Replay, authored motion, unit tests and compile checks do not replace
   this physical evidence. Extend support to other device/platform families only
   after their separate qualification.

## Next actions

- Keep camera policy 4 isolated after the full regression: its fresh-source
  timing precision regresses despite recovering counts. Preserve the terminal
  per-video, negative-window, lifecycle, prefix and storage results.
- Finish the isolated lower-endpoint regression without widening labels/tolerances or adding an
  anatomical recovery gate. A prior stricter elbow-extension experiment reduced
  an older 65/70 paired baseline to 0/70; shoulder-peak ranking also gave no primary
  gain. Their failures remain archived; do not repeat them as new fixes.
- Stable build 118 is ready for the owner's manual phone test; keep it separate
  from the isolated camera candidate. Replay the **three existing originals** with fresh
  iPhone Vision; no new filming is needed for that check. Keep this separate from
  physical live-camera/consecutive-set/soak qualification. Preserve saved data.
- Fill the independent four-cell/athlete/annotation gaps. Eight newly screened
  public repository clips were excluded before inference for view/cropping or
  missing setup/release coverage. Forty further files remain unscreened. Creator
  titles and count labels alone do not prove uncut fixed-view sessions.

## Evidence locations

- Current runtime/unchanged-score proof: `build/body-deadline-live-v102/`.
- Candidate controls, per-video scores, terminal checkpoint and verified runtime
  archive: `build/camera-ambiguity-v109/`. The strict comparison is
  `fresh-development-comparison.json`; unlabelled owner-start limitations are in
  `verification-scope.json`.
- Original-pixel camera diagnosis: `build/remaining-count-causes-v108/`.
  Smaller candidate: `build/camera-ambiguity-refactor-v110/`.
- Frontal dip challenge: `build/controlled-source-intake-v105/`.
  Source exclusions: `build/controlled-source-intake-v106/`.
- Installed stable build, signing/install/launch receipts and saved-data backups:
  `build/observed-return-progress-v118/` (compact overlay: `build/compact-live-ui-v116/`).
  Phone replay inputs and unsigned test-host archive:
  `build/current-device-readiness-v107/`. Its prepared xctestrun must be rebuilt/
  rebased beside signed products; default build 1 is not a phone installation.
- New public-media access, source-only review and prior UI help-text correction:
  `build/qualification-source-access-v113/`.
- Endpoint component probe: `build/measured-lower-endpoint-v114/`.
  Isolated native candidate and live process checkpoint: `build/measured-lower-native-v115/`.
- Lower-return diagnostic and the **complete prior handoff history**, preserved
  with SHA/readback receipts: `build/lower-return-review-v111/`.
  Historical paragraphs are evidence, not current instructions/status.

Remove only reproducible caches after checking pins and archive recovery.
Retain original videos, models, poses, labels, failed evidence, results and phone
backups. Recent compiler/index cleanup retained all runtime pins; regenerated
pixel-control PNGs were removed only after their 107-case test completed, with
an exact restoration recipe. Keep code, tests, CI and this handoff concise.
