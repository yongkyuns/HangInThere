# New-video grip and width validation, frozen policy v2

Grip type is correct on three new supported videos, with incomplete coverage.
Width remains unqualified: the new medium reference has correct frame labels
but insufficient per-rep coverage; the wide reference stays unknown; the
narrow-titled reference is too uncertain to score width correctness.
No model, threshold, production code or rep-counting rule changed in this round.

## Reference convention and test separation

Six public sources were selected by saved search metadata. The existing
counter, width policy 2, crop geometry, tracker, RGB temporal policy and both
learned models were frozen before opening their pixels. Original recordings
are retained under `Data/external/grip-width-v23/` with source hashes and
download metadata. These six source IDs/groups are absent from both fitting
manifests. No training or validation examples were added to either model.

Source review admitted three front/front-oblique exercise shots and excluded
three side/oblique/cropped demonstrations **before inference**. The exclusion
ledger remains visible; excluded clips do not earn accuracy credit. A coach
beside a clearly exercising athlete is not an exclusion.

For each admitted source, original-pixel regions mark shoulder joint centers
and wrist centers, with a support direction. Conservative interval propagation
uses the widest plausible numerator and smallest plausible denominator, and
vice versa. A reference gets a band only when the entire interval fits the
unchanged 0.85/1.30 boundaries. Single center points cannot hide uncertainty.
These are agent-reviewed provisional references, frozen before pose/hand
inference; they are **not independently human-adjudicated gold**. Historical
disputed v21 references remain unchanged.

| New source | Grip reference | Width ratio interval | Width reference |
|---|---|---:|---|
| [Nuffield Health](https://www.youtube.com/watch?v=qVztO-F-IwI), athlete beside coach | Underhand | 0.883–1.268 | Medium |
| [Carlito](https://www.youtube.com/watch?v=88bye9FlQR8), narrow-titled chin-up | Underhand | 0.528–1.332 | Uncertain |
| [Holly Skinner Coach](https://www.youtube.com/watch?v=PdRboCRf1Mg), pull-up | Overhand | 1.452–1.992 | Wide |

The other sources, with reasons in `source-references.json`, are
[MrSupplement](https://www.youtube.com/watch?v=abY26INaGg8),
[OPEX](https://www.youtube.com/watch?v=jgFel4wZl3I), and
[FunctionalMuscleFitness](https://www.youtube.com/watch?v=a79hAiNCl9k).
The narrow title is not treated as measurement truth.

## Frozen results

| Source | Correct grip labels / scheduled exercise images | First correct grip | Known width / native exercise frames | Width result |
|---|---:|---:|---:|---|
| Nuffield | 12/22 (55%) | 13.16 s | 45/152 (30%) | Medium, no wrong known frames |
| Carlito | 27/33 (82%) | 5.005 s | 76/197 (39%) | Narrow output, reference uncertain |
| Holly | 31/49 (63%) | 3.20 s | 0/385 | Unknown |

All three have only correct new grip decisions. Each of six annotated exercise
cycle windows contains a correct label; this does not imply all cycles were
counted or labeled throughout. Readability remains diagnostic, the RGB contact
head is unused, and the classifier confidence threshold remains 0.8. Source
front/rear orientation is supplied by review, so this is not automatic camera
orientation qualification.

The 1,517 native body frames and 218 scheduled RGB images include absent
crops/tracks in coverage. All 238 automatic hand crops were visually reviewed
before RGB inference and reconstructed exactly in the audit. No manual crop
rescue, prediction-based source replacement or post-inference relabeling was
performed.

There are nine actual counter-owned attempts and four completed reps.
**All four completed reps still have unknown width.** The sole stored narrow
attempt has a partial outcome, cannot enter completed-rep variation totals,
and its width reference is uncertain. Correct frame classifications are not
enough to claim usable saved metadata. Offline RGB orientation is scored
separately; it is not inserted into the native width-only replay records.

The distinct source controls contain 347 setup frames and 88 release frames,
with zero new movements, exercise admissions or visible grip-type labels.
Holly has no labeled setup/release window; its endcard is not counted as proof
of release handling. No blanket claim about all possible setup motions follows.

## Exact remaining width blockers

The wide clip's tracked shoulder span has a median of **48.10 px**. The current
minimum is **57.6 px** (8% of the 720 px short side). This gate rejects 385 of
390 shot frames; four others have a low-confidence wrist and one lacks a
tracked person. The valid-confidence frames still have supported wrists. This
is not a grip-classifier confidence/readability rejection. The source stays
in the supported failure denominator, rather than being excluded afterward.
Manual front/front-oblique review does not establish the live depth-facing
threshold (`abs(facing.z) >= 0.85`); the phone may request repositioning for this
view. This diagnostic therefore does not justify relaxing the live view gate.

![Tracked wrists and shoulder-span rejection](../../build/grip-width-v23/width-gate-diagnostic.png)

The medium source establishes a correct width before exercise. At 11.48–11.52 s,
two frames cross the current shoulder-size floor and erase its frozen baseline.
The next usable frames then wait for another ready hang until 14.68 s. The
first completed rep has only 6.9% credited width coverage, below the unchanged
80% summary requirement. This is a concrete calibration-loss cause, rather
than evidence that the hand patches need retraining.

The next implementation experiment should distinguish a temporarily unusable
current measurement from loss of an already validated same-athlete baseline.
Current shoulder projection changes during reps and mixes camera distance with
view angle. Keep invalid frames unknown and uncredited, bound any baseline
retention, and require fresh identity/view/support evidence; real release,
source changes and camera-view changes must still invalidate calibration.
Evaluate this on exposed development regressions, then use new sources for
any accuracy claim. Do not lower the size/confidence thresholds to pass these
three now-exposed videos.

## Alternating sets and verification

A four-block assembled sequence alternates medium-underhand → wide-overhand →
uncertain-width-underhand → repeated medium-underhand. All 2,152 original
frames keep their source clocks. Width, counter, support and exercise-context
outputs match isolated replays; every new set starts unknown. Grip temporal
states also match the fresh isolated calls. The repeated source contributes
no independent accuracy evidence. This verifies explicit source/set resets,
**not a continuous filmed alternating workout** or automatic set boundaries.

Six reference-geometry unit tests pass, including uncertainty, invalid regions,
projection and scale invariance. Both exact shared-detector replay tests pass.
The audit verifies 29 frozen pins, six originals, zero fitting-source overlap,
every source image/pose, all automatic crops and assembled outputs. Production
code is unchanged, so no new iPhone build or installation was performed. The
phone still uses geometric MediaPipe hand inference; this learned RGB test is
offline research.

Cache cleanup found no removable compiler caches; approximately 186 GB remains
free. Original recordings, models, crops, labels and evidence were preserved.

Evidence: `build/grip-width-v23/`, particularly `protocol.json`,
`source-references.json`, `reference-freeze.json`, `test/report.json`,
`width-calibration-diagnostics.json`, `verification.json`, and
`source-exposure-ledger.json`. All six sources are now exposed validation
regressions, not future unseen tests. Reproduction uses
`scripts/evaluate-grip-width-v23.py`, `scripts/audit-grip-width-v23.py`,
`scripts/render-grip-width-v23.py`, and the unchanged V22 native replay exporter.
Snapshot: `Evaluation/fixtures/grip-width-reference-validation-v23.json`.
