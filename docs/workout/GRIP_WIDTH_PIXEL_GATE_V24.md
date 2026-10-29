# Width policy 3: remove the projected-shoulder pixel veto

The medium example now retains its calibrated width through the completed rep.
Its exercise-frame width coverage improves from 30% to 91%, and that completed
rep saves Medium with 100% credited coverage. The wide example now produces
Wide labels, but its early completed reps still lack enough coverage. This is
a targeted rejection-rule repair, not full width-classification qualification.

## Change

`WorkoutGripWidth` no longer requires current shoulder spacing to exceed 8% of
the image's short side. That condition conflated distance/view with normal
shoulder projection changes. It also erased an already valid starting-hang
reference when two medium-example frames fell just below the pixel cutoff.

Both current-support checks and baseline acquisition now use finite,
noncoincident joint centers and the existing supported-wrist/ratio geometry.
The starting-hang shoulder denominator stays frozen through rep posture changes.
There is no new smaller pixel threshold or shoulder correction factor.

The existing safeguards remain:

- Current bilateral wrist/shoulder confidence is at least 0.5; duplicate,
  missing or outside-image joints remain invalid.
- Wrists must remain near the current acquired support. Geometric ratios must
  remain finite within the existing 0.1–4.0 range.
- The caller owns athlete identity, capture framing and fresh front/rear view
  qualification. The live torso-facing threshold remains 0.85.
- Calibration still requires the unchanged counter's ready hang, five frames
  spanning 0.4 seconds, and at most 15% shoulder-span variation.
- Real support/view loss, interruption, source/reference/image-size change,
  clock regression or an excessive source gap still invalidates calibration.
- Width bands, confirmation, filtering/hysteresis, 80% per-rep coverage,
  classifier confidence, learned models and counting are unchanged.

Saved width metadata uses policy version 3. Historical optional version fields
and the stored middle-band name `shoulderWidth` retain their existing meaning.

## Exposed regression results

Original videos, labels, shot windows, models and native source clocks were
preserved. These clips were already exposed while diagnosing the bug; this
comparison is development regression evidence, not unseen accuracy.

| Source | Known width / native exercise frames, before → after | Candidate output |
|---|---:|---|
| Nuffield medium | 45/152 → 138/152 (91%) | Medium; no wrong known frames |
| Carlito narrow-titled | 76/197 → 197/197 (100%) | Narrow; original width reference remains uncertain |
| Holly wide | 0/385 → 186/385 (48%) | Wide; no wrong known frames |

![Same original frames before and after the repair](../../build/grip-width-v24/medium-before-after.png)

The first example no longer loses its baseline at 11.48–11.52 s. No new starting
hang is needed merely because its current shoulder projection contracts.

Of four completed counter-owned reps, saved known width increases from zero to
two: Medium for Nuffield and Narrow for Carlito. Carlito's source-only reference
interval still crosses bands, so that saved Narrow label is not a new correctness
success. Both completed Holly reps remain Unknown (9% and 47% credited width
coverage); its final incomplete attempt has Wide metadata and does not enter
completed-rep totals. A usable frame label is not equivalent to a reliable rep
summary. Brief actual wrist-confidence/support losses still clear calibration.

The counter, motion events, support reference, release, tracking and exercise
context are bit-identical across **11,248** replayed frames: 1,517 current-source
frames, 2,152 assembled alternating-set frames and 7,579 older regression frames.
Offline RGB grip-type states/decisions are also identical. New sets start
Unknown, and assembled width outputs match isolated runs, including the repeated
source. This tests explicit set/source boundaries, not automatic boundaries
inside a continuous filmed alternating workout.

The distinct v23 controls still have zero new movements, exercise admissions
or visible grip-type labels across 347 setup and 88 release frames. Width can
legitimately appear during a supported ready hang before the first movement.
No alternation assumption is used to guess width. Older out-of-scope sources
and disputed shoulder references remain preserved, not relabeled into successes.

## Fresh source check

After freezing the candidate, two short public sources were selected by saved
metadata, checked against both fitting manifests and reviewed before pose
inference. No outcome-based replacements or model fitting occurred.

- [Live Lean TV](https://www.youtube.com/watch?v=IK3sH7wOAWE): the fixed front
  segment, 6.5–9.8 s, has a source-only shoulder/wrist reference interval of
  1.60–3.06, wholly Wide. On its 99 native frames the candidate produces Wide on
  12, Unknown on 87, and no wrong known labels. It first reports width at 9.4094 s.
  The short edited segment starts before an acquired hang; 60 frames lack usable
  body/support evidence, 12 wait for calibration and 15 confirm the band. It
  contains no complete source-isolated rep cycle. **12% coverage does not
  qualify fresh full-set performance.**
- [KILO Strength Society](https://www.youtube.com/watch?v=a29HExMHMyU): excluded
  before pose inference because close rear footage crops hips/legs and then the
  moving camera becomes oblique/side. Original media and exclusion are retained;
  it contributes no accuracy credit.

The full accepted source has 854 independently decoded native frames. All
outside-shot width outputs remain Unknown. Its references are provisional agent
annotations, not independent human gold. Source-confirmed view and wrist-seeded
support remain diagnostic inputs. This is width-only validation; no new learned
hand inference or hand training data was created.

## Verification and remaining work

All **824 shared JVM tests pass**, including four new contracts for scale
invariance, shoulder contraction, stable live coverage and rejection of invalid
or unstable geometry. Existing weak/missing wrist, stale view, interruption,
clock and alternating-set contracts still pass. All four native replay tests
pass. The generic unsigned iPhone app builds successfully. It was not installed
on a phone. The phone's hand classifier is still geometric MediaPipe; learned
RGB classification remains offline research.

The next unresolved issue is coverage after genuine wrist/body tracking
interruptions, particularly on the wide example. Preserve Unknown and zero
credit where observations are invalid; do not lower confidence, weaken view
limits or assume the next grip/width from a sequence. Fresh complete front/rear
narrow/medium sets and a continuous supported alternating workout are still
needed for broader qualification.

Cache cleanup removed an estimated 152 MB of reproducible compiler caches after
builds finished. About 179 GB remains free. Original recordings, references,
models, compiled products and evaluation evidence were retained.

Evidence: `build/grip-width-v24/`, especially baseline source snapshots,
`baseline-freeze.json`, `candidate-freeze.json`, `comparison.json`, native and
assembled rep records, `fresh/report.json`, test/build logs and cache receipt.
The initial system-Python scoring attempt failed an exact floating-point
reference comparison; its log is preserved. Repeating with the same bundled
Python runtime used to freeze the references succeeded without code, reference
or prediction changes. Use `build/grip-training/venv/bin/python` for reproduction.

Scripts: `audit-grip-width-v24.py`, `validate-grip-width-v24.py`,
`render-grip-width-v24.py`, and the unchanged V22 native replay exporter.
Snapshot: `Evaluation/fixtures/grip-width-pixel-gate-v24.json`.
