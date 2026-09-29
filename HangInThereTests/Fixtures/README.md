# Test footage and provenance

Generated fixtures are not committed or included in the normal app. Run
`python3 scripts/prepare-fixtures.py` before building the test target. Missing
media, failed integrity checks, or missing expected landmarks fail the test.

`source.json` pins a 32,946,819-byte official-duty U.S. Marine Corps video by its
Wikimedia-published SHA-1. Preparation records SHA-256, the recipe, encoder
version, derivative checksums and every decoded presentation timestamp.
[The Commons record](https://commons.wikimedia.org/wiki/File:Get_Fit-_Proper_Pull-Up_Technique,_Marine_Corps_Air_Station,_Iwakuni,_Japan_2026_(B-ROLL)_(1017606).webm)
records public-domain status in the United States. The
[original release](https://www.dvidshub.net/video/1017606) credits Andrew Knight
and Saul Hernandez, U.S. Marine Corps / AFN Iwakuni. Test use implies no
endorsement and does not authorize marketing or unrelated uses.

## Reviewed motion interval

The derivative uses **source seconds 29 through 33**, resized to width 640 and
sampled at 10 FPS, with audio removed. The PNG is the derivative's frame at
1.9 seconds. Those are fixture transformations, not the original camera rate.
The entire source was inspected at one-second intervals; all 40 selected frames
were then inspected before running pose inference on the new interval.

This continuous rear/oblique shot shows one athlete hanging, ascending, reaching
a peak, descending, and returning to hang. The head/chin leaves the image at the
peak. It therefore supports a **motion/occlusion smoke check**, not strict
chin-over-bar validation or a qualified exercise-view profile. The earlier
0-4-second trim contained two people introducing the exercise and zero pull-ups.
The source manifest records that correction and the single-reviewer limits.

Original bytes remain in ignored `Data/external/`; derivatives remain in ignored
`generated/` and are only bundled into tests. The full source is not a permanent
CI artifact. No network access happens inside the app or test process.

The image test requires a real shoulder/elbow/wrist chain. Video tests use the
**app's actual AVAssetReader/Core Image/Vision path** and check all 40 source PTS.
Three preselected checkpoints (zero-based frames 10, 24, 36) correspond to hang,
peak, and returned hang. A broad body-root vertical-displacement check (>10% of
image height up, then down) rejects a static/stale skeleton. This tolerance was
chosen from visual motion before inspecting model outputs; it is not an app
counting threshold or a claimed anatomical error bound.

Each `[Pose sample]` log line exports the model's actual frame index, timestamp,
image dimensions, joints/confidence and derivative SHA-256 for visual auditing.
Only the pinned public fixture is logged, never the app's private imports. These
**model predictions are not ground-truth labels**.

The same fixture also has one explicitly reviewed **demo expectation** in
`source.json`: pull-up, left-arm tracking, and a fixed visible gripping-bar edge
from frame 0. `realWorkoutFixtureRunsThroughVisionCountingAndResults` sends the
video through the default `ReplayController`, real Apple Vision estimator, normal
bar-confirmation lifecycle, production movement counter, and completed-session
result data. It must finish with one observed movement and no synthetic pose or
counter substitution. The expected event remains **movement only**: it does not
establish chin clearance, rep validity, form quality, or population-level accuracy.

Dip footage and independently labelled held-out evaluation remain separate
qualification work.

Separate tests create four-quadrant videos with AVAssetWriter. Their synthetic
pixels test actual decoding, orientation, irregular timestamps and lifecycle;
they never replace the human-motion fixture.

The original source bytes were acquired and verified on 2026-09-26:
SHA-1 `9c455b575ce8f3df74ab2a8d7f30bbb85f4c227a`,
SHA-256 `d278e8f61fbc1cfbedc8a38ddcdfa5b9b44d1429a209c198b59172d7fb6443a7`.
Local preparation of the new interval produced 40 ordered timestamps; derivative
hashes may differ with the recorded encoder version. Consult the exact CI run
for model results on this interval; passing on the old introduction is not
qualification of the new motion fixture. Missing media or expected landmarks
must remain real test failures.


## Real-video diversity corpus

`corpus.json` extends the single smoke clip into a tiered real-world corpus.
Every downloaded source is HTTPS-addressed, byte-counted, SHA-256 pinned, licensed,
credited, and transformed reproducibly by `scripts/prepare-video-corpus.py`.

Current reviewed scenarios:

- **Iwakuni standard rear/oblique** — existing count-qualified outdoor/military fixture;
- **Iwakuni multi-person introduction** — two-person, zero-rep stress case;
- **FitnessScape standard indoor** — second count-qualified standard pull-up view;
  indexed-frame review shows the file starts mid-attempt, reaches full extension,
  then contains one countable extension-to-top movement;
- **Yokota crowded indoor pull-up** — 20 reviewed frames with a foreground
  occluder plus the athlete and surrounding gym activity. This is a stress case:
  real Vision must expose at least one multi-person frame, and production arm
  measurement must reject that frame instead of silently selecting a person;
- **JULLIAN W portrait dips (early development probe)** — the earlier 96-frame
  Pexels candidate exposed low pose coverage and a 0/5 result under the pre-v4
  counter. It remains diagnostic evidence, not a qualifying corpus case;
- **JULLIAN PRODUCTION controlled dips (development)** — a separate 300-frame,
  fixed-camera Pexels clip. A pinned source-only 8 FPS re-review corrected the
  coarse seven-cycle annotation to nine complete cycles. Counter policy v6,
  with reviewed rail coordinates transformed into the production pose raster,
  scored **TP=9 / FP=0 / FN=0**, with zero partial/interrupted attempts. This
  clip is explicitly development-exposed, not held out;
- **Romina Martinez parallel-bar dips (consumed held out)** — the independently
  locked 27-frame, three-cycle Pexels interval failed its first native qualification:
  25/27 person frames (92.6% vs 95% floor), 21/27 any-arm frames (77.8% vs 85%),
  and 0/3 movements with one interrupted attempt under policy v4. The exact
  pre-inference labels remain unchanged in `dip-heldout.json`; the result is
  preserved in `dip-heldout-result.json` and the clip is not part of the passing corpus;
- **Pavel Danilyuk parallel-bar dips (consumed holdout v2)** — a 14.0 s,
  112-frame Pexels side/oblique clip was frozen in `dip-heldout-v2.json`
  before inference, after policy v6 was frozen in `dip-policy-v6-freeze.json`.
  On its first eligible Apple-Vision run, tracking passed (109/112 person,
  101/112 any-arm) and v6 produced **3/3 movements with zero partial/interrupted
  attempts**. The stricter frozen event windows failed because detections were
  early at 3.25, 7.875, and 12.625 s. `dip-heldout-v2-result.json` preserves
  this count-pass / timing-fail result;
- **Ketut Subiyanto frontal parallel-bar clip (source-only rejected candidate)** —
  reviewed alongside Pavel with no model inference, then rejected as a count
  holdout because frontal arm/torso overlap and small visible endpoint excursion
  made top/bottom cycle labels ambiguous;
- **Solodkyi portrait one-arm** — portrait, large swing/inversion, blur/defocus stress;
- **Solodkyi outdoor tree branch** — nonstandard apparatus, foliage/high-contrast
  background, swing/inversion stress.

The new clips are not all rep ground truth. `tier` is deliberate:

- `count-qualified`: reviewed movement-cycle expectation and fixed bar reference;
- `tracking-qualified`: reviewed person/arm visibility floors;
- `stress-coverage`: difficult real footage must decode and produce sufficient
  real-Vision scene evidence, but no rep/form label is implied. Multi-person
  scene-level arm coverage is not athlete identity continuity.

Coverage floors were recorded from visual review before Apple Vision corpus
inference. The initial indoor count label of two was corrected after indexed-frame
visual re-review showed that frame 0 is already mid-ascent; only the later
extension-to-top movement is countable. CI must not relax endpoint thresholds merely
to match model output.

Preparation also emits contact sheets sampled across the full decoded clip plus exact
frame timestamps. Short clips are no longer represented by only one or two early
thumbnails. CI retains
the approved derived corpus temporarily for inspection; source media remains ignored
under `Data/external/`.

Real parallel-bar footage is reproducibly pinned. Policy v6 is
development-qualified on JULLIAN (9/9) and consumed Romina (3/3), then achieved
**held-out exact rep-count success** on the independent Pavel view (3/3, no
partial/interrupted attempts, tracking floors passed). The separately frozen
event-time windows did not pass: all three detections occurred 0.125–0.375 s
before the reviewed return windows. Therefore rep counting has targeted held-out
evidence, while precise endpoint timing, dip depth/lockout validity, body
alignment, 3D joint accuracy and population-level accuracy remain unqualified.
