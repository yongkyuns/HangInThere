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
