# Width policy 4: bounded calibration pauses

The second completed rep in the wide example now saves **Wide**, with 81.1%
credited width coverage instead of 46.7%. Overall exercise-frame width coverage
on that source increases from 48% to 66%. The first completed wide rep remains
Unknown; the medium and narrow-titled source results are unchanged.

## Two causes, two changes

The earlier diagnosis grouped different failures together. At 1.90 s all four
required wrist/shoulder landmarks exceed the 0.5 confidence cutoff, and both
wrists remain on the acquired support. Yet the current compressed shoulder
projection gives an instantaneous ratio above 4.0, so it still vetoed the
measurement. The starting-hang denominator gives a valid ratio near 2.4.

Current supported wrist geometry is now checked independently of the divisor.
Once calibrated, all ratio validation consistently uses the frozen starting-hang
shoulder span. Before calibration, the observed shoulder span must still pass
the existing 0.1–4.0 ratio bounds and stability/ready-hang conditions. This does
not admit an implausible initial calibration or change the width categories.

The other interruptions are brief low-confidence wrist observations. A frozen
baseline may now pause for **at most 0.35 source seconds**, using the counter's
existing maximum source-gap duration, only when:

- The caller retains athlete/exercise continuity and a fresh supported front/rear
  view. It has not signaled an interruption or release.
- Exactly one current wrist is below 0.5; the other wrist and both shoulders
  remain at or above 0.5.
- All four distinct landmarks are present, finite and inside the image. Both raw
  wrist locations remain near the current acquired support.
- A valid starting-hang baseline already exists, with recent fully measured
  supported geometry. Weak frames do not extend the deadline.

The weak wrist does **not** become a measured or trusted wrist. Every paused
frame reports Unknown, has no numeric ratio and earns no width coverage. The
previous band/filter history is discarded. Once both wrists are valid again,
the new spacing must earn the unchanged 0.5-second band confirmation. A grip
change during the pause therefore cannot inherit the old width label.

Missing or duplicate joints, two weak wrists, weak shoulders, off-support raw
wrist coordinates, unsupported/stale view, explicit interruption, changed
source/reference/image size, invalid clock or an expired gap still clear the
calibration. No confidence threshold, front/rear gate, category boundary,
rep-counting rule or 80% per-rep coverage requirement was lowered.

Saved width metadata now records width policy 4. The geometric hand-orientation
policy and learned models are unchanged.

## Video regression results

| Source | Known width / exercise frames, policy 3 → policy 4 | Output |
|---|---:|---|
| Nuffield medium | 138/152 → 138/152 (91%) | Medium |
| Carlito narrow-titled | 197/197 → 197/197 (100%) | Narrow; reference remains uncertain |
| Holly wide | 186/385 → 254/385 (66%) | Wide, no wrong known frames |

The three Holly confidence pauses occur at 1.4333, 2.3000 and 6.8333 s. The weak
wrist scores are 0.467, 0.367 and 0.481 respectively. Each paused output stays
Unknown with no ratio. At 2.3333 s, a weak wrist jumps well away from support;
this remains a hard reset, rather than being treated as a confidence pause.

![Wide rep coverage before and after](../../build/grip-width-v25/wide-rep-continuity.png)

For the completed wide reps:

- First rep, 1.2–3.5 s: coverage improves from 8.7% to 21.7%, but the off-support
  observation and renewed calibration wait leave it Unknown.
- Second rep, 5.4–8.4 s: coverage improves from 46.7% to 81.1%, so it now saves
  Wide under the unchanged 80% requirement.

Across all three sources, three of four completed reps now have known width
metadata, up from two under policy 3. One is the narrow-titled source with an
uncertain reference; this is **not** a 75% accuracy claim. The final incomplete
wide attempt remains excluded from completed-rep variation totals.

These are exposed development regressions. Original labels, reference intervals,
outlier exclusions, videos and native clocks are unchanged. No new source was
downloaded, relabeled or added to fitting in this round. The previously fresh
Live Lean front segment remains 12/99 known Wide frames and is now an exposed
regression, not a new test. Fresh full-set qualification remains outstanding.
Replay still supplies source-confirmed view and a wrist-seeded support reference;
it does not qualify automatic phone framing, orientation or support acquisition.

## Verification

- All **831 shared JVM tests pass**. Six new contracts cover frozen-ratio
  validity, fresh confirmation after a pause/grip change, absolute pause expiry,
  calibration acquisition rejection, hard losses/reference/size/clock resets,
  and live zero-credit recovery. A new research-only exporter adds one replay
  entry; four opted-in native replay runs pass.
- Counter phase/events, motion/support/release/tracking and exercise-context
  outputs are bit-identical on **12,102 native frames**: 1,517 current-source,
  2,152 assembled set-sequence, 7,579 older-regression and 854 previous fresh-source
  frames. Counter-owned attempt fields are unchanged apart from width metadata.
- Offline learned grip-type states/decisions are identical. No hand inference
  or training was rerun. The live torso-facing gate remains 0.85.
- All three native confidence-pause frames are audited against original points;
  the assembled sequence contains the same three pauses. No other cohort earns
  a confidence pause. Invalid/pause frames never report known width or a ratio.
- Assembled sets start Unknown and match isolated outputs. Distinct setup/release
  controls retain zero new movements or visible grip-type labels. This does not
  qualify automatic boundaries in a continuous alternating workout.
- The generic unsigned iPhone build succeeds. No phone installation occurred.

The remaining issue is reliable metadata when wrist locations themselves become
unusable, plus independent complete front/rear narrow/medium and alternating-set
validation. This change deliberately retains the visible failures rather than
relaxing confidence or filling invalid periods with cached labels.

After builds finished, cleanup removed an estimated 152 MB of reproducible
compiler caches. About 179 GB remains free; APFS did not immediately report a
free-space increase. Original data, models, compiled products and evidence remain.

Implementation: `WorkoutGripWidth.kt`, `WorkoutGripAnalysis.kt`, and the width
geometry helper in `WorkoutGrip.kt`. Replay:
`Evaluation/androidTests/GripWidthValidationV25Tests.kt`; historical V22 exporter
is unchanged. Audit/figure: `scripts/audit-grip-width-v25.py` and
`scripts/render-grip-width-v25.py`. Evidence: `build/grip-width-v25/`, including
baseline source snapshots, candidate pins, `comparison.json`, original replay
and rep outputs, full test receipts and iPhone build log. Snapshot:
`Evaluation/fixtures/grip-width-confidence-pause-v25.json`.
