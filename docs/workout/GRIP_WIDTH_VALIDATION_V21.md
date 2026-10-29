# Separate-video narrow/medium validation: failed qualification

The [v22 follow-up](GRIP_WIDTH_CONTINUITY_V22.md) repairs calibration timing and
width-only continuity. It also corrects two interpretations here: the provisional
medium shoulder references are not consistent with model joint-center locations,
and much of the reported support loss precedes initial acquisition rather than
being a tracking dropout. Historical references/results below are preserved;
they must not be used as adjudicated medium ground truth.

The new video replay does **not** validate reliable narrow/medium metadata.
Narrow remains unknown. Provisional medium examples are almost entirely unknown
and their few confirmed widths say wide. The assembled set sequence isolates
state correctly, but accurate grip-type/width alternation in a continuous filmed
session remains unqualified. Production code, models and thresholds are unchanged.

## Sources and references

The current v20 width/counter code, both hand models, crop policy and confidence
thresholds were frozen before opening the four remaining newer reserve clips.
Five public publisher videos were subsequently acquired under that same frozen
policy, with bounded downloads and original bytes/checksums retained. Nine
recordings represent eight source groups: both Physitrack variations share one
studio/performer group. All nine are disjoint from both models' fitting and
calibration records, including group IDs. No sources were added to training.

Original-pixel review and provisional manual wrist/shoulder references were
locked before width or RGB inference. These are agent annotations, not
independently adjudicated human gold. Width-component eligibility and full
capture eligibility are separate, explicit reference fields. A supported
detector failure stays in its denominator.

| New source | Pre-inference scope decision |
|---|---|
| `_ceh4f86aBc` | Low contrast/blur and low oblique camera; excluded |
| `H1CJDm4Cx7Q` | Oblique view, wrists clipped; excluded |
| `31g2vp497OU` | Oblique view, short descent/release without a complete full-hang cycle; excluded |
| `z9fVTSn7C2E` | Small hands in low-resolution assisted neutral footage; excluded |
| Physitrack underhand | Medium width candidate, rear shot 23.7–28.6 s; enters bent-arm and ends after extension, component only |
| Physitrack overhand | Medium width candidate, rear shot 29.6–34.7 s; one full-capture candidate |
| STRONG ATHLETE close chin-up | Clear narrow rear shot 2.6–10.6 s; cropped hips, component only |
| Invictus close chin-up | Oblique side view; excluded |
| Robert Timms alternating routine | Oblique views/close-ups for changing variations; rear wide shot 83.6–87.4 s is component only |

Publisher sources: [Physitrack underhand](https://ca.physitrack.com/home-exercise-video/pull-up-%2528narrow-underhand-grip-with-legs-uncrossed%2529),
[Physitrack assisted overhand](https://ca.physitrack.com/home-exercise-video/assisted-pull-up-%2528narrow-overhand-grip%2529),
[STRONG ATHLETE](https://www.youtube.com/watch?v=d4kpdlbv-d4),
[Invictus Performance](https://www.youtube.com/watch?v=IqOAA0jhpeE), and
[Muscle & Strength routine](https://www.muscleandstrength.com/videos/around-the-world-pull-up-routine-robert-timms).
Publisher exercise names do not determine the width band. Annotated original
image points supply the provisional width references.

## First-run results

The replay uses all 7,579 original native body frames and 1,137 scheduled RGB
images. Only annotated eligible shots receive hand-model calls: 80 exact
automatic wrist crops. The exact shared counter and width component run at
native cadence. Source-confirmed camera shots and wrist-seeded support are
diagnostic setup inputs, not automatic phone acquisition.

| Reviewed shot | Reference | Known width frames / all shot frames | Confirmed output |
|---|---|---:|---|
| STRONG ATHLETE rear | Narrow | 0/240 | Unknown |
| Physitrack underhand rear | Medium, provisional | 1/122 | Wide, 1.64× |
| Physitrack overhand rear | Medium, provisional | 2/128 | Wide, median 1.84× |
| Timms rear | Wide | 0/91 | Unknown |

Zero grip-type labels are confirmed in these shots. That is missing coverage,
not evidence of correct grip classification. All nine counter-owned attempts
across the new sources retain unknown per-rep width under the unchanged 80%
coverage rule. Four counter cycles complete somewhere in the all-source replay;
that total is not a supported-capture success count.

## What failed

At the two manually marked medium reference frames, the wrist estimates are
near their source references but Vision's shoulder spans are smaller. The
source-reference ratios are 1.18 and 1.11; instantaneous Vision ratios are 1.62
and 1.55. Frozen-baseline predictions are 1.64 and 1.84. Anatomical landmark
placement needs independent adjudication before these provisional labels become
accuracy ground truth. This mismatch is a reason to investigate the denominator,
not evidence for moving the category thresholds.

The narrow reference has a source ratio of 0.63 and an instantaneous Vision
ratio of 0.62, but shoulder confidence is only about 0.37–0.39. The tracked
athlete is unavailable on 232/240 reviewed frames; the counter stays seeking
start. The component cannot obtain a stable usable starting-hang baseline.
The cropped-hips source remains outside full-capture qualification even though
its narrow wrist/shoulder geometry is useful for diagnosing this failure.

On the two medium shots the replay reports support loss on 90/122 and 88/128
frames respectively, with three and eight identity epochs. That disrupts ready
hang calibration and width confirmation. Calling every rejected frame a
hand-classifier failure would misdiagnose these results.

The next development target is shoulder reference consistency and tracking/
support continuity. Keep these exposed sources as regressions. A future
comparison needs new, supported narrow/medium recordings with human-checked
shoulder points and continuous full-hang cycles; it must retain the unknowns.

## Alternating sets

An assembled sequence replays original poses and source clocks in one process:
wide overhand owner control → narrow underhand → medium underhand → wide
overhand control → medium overhand → narrow underhand. It contains 5,112 frames;
repeated sources are regression repeats, not additional independent examples.

Every new set begins unknown. All new-source outputs match their isolated replay
exactly; both wide-control repeats also match. Four control attempts retain wide
metadata while nine other attempts remain unknown. The medium shots' wrong
wide outputs are therefore fresh measurements rather than inherited metadata.
The owner control's twice-replayed 288 setup and 148 release frames produce no
new movements, exercise admissions or known width.

This verifies state isolation across explicit set/source boundaries. It does
not verify grip-type accuracy, same-camera automatic boundaries or a real
continuous alternating workout. The acquired alternating routine cannot qualify
those claims under the agreed front/rear capture conditions.

## Evidence and verification

- Eleven targeted JVM tests passed: ten width contracts plus the actual new
  video exporter. The assembled replay also passed separately.
- The independent audit verifies 26 frozen files, all original frame identities,
  all 80 crop pixels and every assembled-sequence frame against its baseline.
- Two failed reporting attempts are retained. Accounting repairs handle repeated
  identical title-card images and omitted optional JSON timestamps. The original
  script and repair checksums are saved; inference/labels/policy were unchanged.
- No production/app code, model fit, confidence threshold or rep policy changed.
  No new phone build or installation is needed for this offline validation.

Reproduce with `scripts/evaluate-grip-width-v21.py` and
`scripts/audit-grip-width-v21.py`. Protocol, references, raw outputs, width
overlays, set records and checksums are in `build/grip-width-v21/`.
`Evaluation/fixtures/grip-width-validation-v21.json` snapshots the report.
Safe cache cleanup preserves original videos, annotations, model artifacts and
all first-run evidence. The four formerly unread newer reserve groups are now
exposed; historical reserve manifests are preserved.
