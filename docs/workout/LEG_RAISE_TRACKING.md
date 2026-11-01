# Hanging leg raises — Preview

A fifth illustrated home shortcut opens the same camera experience on iOS and
Android. Shared Kotlin owns athlete association, readiness, lift/return counting,
set boundaries and saved records. It supports hanging straight-leg and bent-knee
raises; lying, chair-supported, inverted and toes-to-bar variants are outside
this preview. Automatic exercise selection remains pull-up/dip only.

Use a steady, level landscape phone beside you or at a modest three-quarter
angle. Keep one shoulder, overhead hand, hip and knee visible, including when
legs are lowered. Head, feet and elbow can be cropped. Hang still with legs down
until Ready. Frontal views have not qualified for this preview.

Readiness needs a held upright torso, overhead hand and lowered knee. A rep
requires visible knee elevation to approximately hip height and a sustained
return to the starting leg position. Endpoints dwell for 120 ms; readiness holds
for 600 ms after source association. All timing uses source presentation time.
Missing endpoints pause evidence; prolonged loss, camera movement, relocation,
source gaps and athlete changes cancel pending cycles. Static holds and partial
lifts do not count. Finish set remains available. No rail or image-contact
classifier is involved; the detector does not verify that feet are unsupported.

The iOS route retains counted-set video and Start / Top / Return keyframes.
Both platforms retain phase timing, history, activity-goal totals and optional
voice counting. Review labels are Raise / Lower. No elbow-range, muscle effort
or fatigue estimate is inferred for this exercise. Android retains its existing
record/history behavior; this change does not introduce Android video capture.

## Public-video validation

The results below are the frozen policy-1 baseline. Policy 2 keeps the same
counts on all six cached observations after fixing unused-side hip loss; this
regression replay is not fresh held-out or device qualification.

Source: [34data/workout-vids](https://huggingface.co/datasets/34data/workout-vids),
revision `f1c551e140fa3c8d1dc6b4dfa41caf4d994509b7`. All 21 leg-raises clips
were visually screened before inference. The dataset supplies exercise labels,
not repetition annotations; this project's counts are manual visible-cycle
labels with coarse review anchors. Initial/final partial cycles are excluded.
No artificial starting holds, temporal trimming or missing joints were inserted.
The dataset card declares no media license; raw videos are not redistributed.

The fixture records every screened source, selection reason, family, video hash,
archive entry and role. FitnessFAQs-family clips are development only. The test
families are separate and were not used to tune the counter. These are short
internet clips, not a representative production benchmark.

| Source | Role | Complete cycles | Counted | Finding |
| --- | --- | ---: | ---: | --- |
| leg_raises_9 | Development | 1 | 1 | Oblique lift and return |
| leg_raises_12 | Development | 1 | 0 | Knee unavailable/cropped during lowered start |
| leg_raises_14 | Development | 2 | 0 | Lowered knees leave frame; cannot acquire start |
| leg_raises_17 | Held-out side view | 3 | 3 | Initial partial movement ignored; all full returns counted |
| leg_raises_20 | Held-out setup/release | 1 | 0 | Short clip never establishes held Ready posture |
| leg_raises_7 | Held-out framing diagnostic | 5 | 0 | Front view and cropped overhead hands; remains unknown |

Development: 1/4 cycles. Held-out side/setup clips: 3/4 cycles, one of two clips
exact. The separate out-of-profile front/cropped-hand diagnostic is 0/5. These
failures remain in the report; this is **not production qualification**. Native
Apple Vision pixel replay assumes camera stability. Replaying those observations
on JVM checks shared counting and durable-record parity; it does not evaluate
Android's independent pose estimator. Fresh live phone and Android pixel tests
remain necessary before removing Preview.

Fixtures: `Evaluation/fixtures/public-legraise-v1.json` and
`public-legraise-results-v1.json`. Reproduce with the six SHA-pinned local videos:

```sh
scripts/evaluate-leg-raises.sh MEDIA_DIRECTORY NEW_OUTPUT_DIRECTORY
```

The runner decodes complete videos through the app's Apple pose estimator,
exports traces, runs the shared JVM owner, validates saved records and checks
native/JVM event parity. Regression tests cover full cycles, partial/setup motion,
static holds, missing joints, permitted crops, camera/source/athlete faults,
release, durable recovery and timing-only review. Home cards were rendered at
320/393-point widths in both themes. No new dependency or CI job was added.

Validation completed: shared JVM suite, six-source native/JVM record parity,
18 iOS logging/camera-selection tests, Android unit tests, instrumentation compile
and lint; signed iPhone build 167. The phone has not been updated in this task.

The home-card artwork was generated with the built-in imagegen tool. Final assets:
`ios/HangInThere/Assets.xcassets/LegRaiseIllustration.imageset/illustration.png` and
`android/app/src/main/res/drawable-nodpi/illustration_leg_raise.png`. The exact
style reference and prompt are retained in `docs/brand-assets.json`.

## Lying leg raises — Preview

Lying raises have their own sixth home shortcut, icon, camera route and exercise
identity on both platforms. Existing `legRaise` records remain hanging raises;
new `lyingLegRaise` records keep activity goals, comparisons and history separate.
The camera, progress bar, voice counting, counted-set video (iOS), keyframes and
Raise / Lower review use the existing experience. Say “start lying raises” or
“start lying leg raises.”

Use a low, level landscape phone directly beside you. Keep shoulder, hip, knees
and preferably feet visible. Lie back with legs extended, hold until Ready, lift
both legs and lower fully. Hands and head need not be visible. If feet are
cropped from the beginning, the counter can use the knee; a cycle never switches
between knee and foot endpoints. Losing the chosen foot mid-cycle pauses it.
This preview does not support alternating single-leg lowering, reverse crunches,
sit-ups, scissor kicks or prone leg lifts.

Shared policy 1 / boundary 12 uses a dedicated flat-body association profile,
which accepts collinear side-view joints while retaining measured torso scale,
source continuity and athlete association. After the tracker's 600 ms torso
acquisition, a lowered-leg endpoint needs 120 ms dwell. Lift and return endpoints
also need 120 ms. A full lift reaches about 55° projected elevation and adequate
vertical travel, then returns to the low position. Visible feet supply full-leg
travel and span; a shortened leg span rejects deep tucks. Soft knee angles do
not masquerade as tracking loss. Torso relocation, camera movement and prolonged
missing observations cancel pending evidence. None of this verifies spinal
contact, anatomical form, calories or muscular fatigue.

### Lying-raise public diagnostics

Policy 3 locates the athlete from complete visible shoulder–hip pairs, so losing
an unused far-side hip does not discard the other visible torso. The counter
still binds its own side and endpoint; spatial association never substitutes a
missing exercise joint. A sustained deep tuck requires a fresh lowered start;
isolated knee jitter only pauses evidence. Earlier records remain readable.
The first-lift progress bar stays visible before set activation on both platforms.
The six cached public traces retain the policy-1 counts below; this is regression
evidence, not new accuracy qualification.

Source: [PushupBench](https://huggingface.co/datasets/anonymousatom/pushupbench),
revision `2e3129f81f4d6f0c2f19d2d7d033e714ec617786` (dataset card CC-BY-4.0).
Six clips were selected by action name before image screening and inference.
Publisher counts are preserved, including for variants. No start holds or
missing joints were synthesized. Original RGB videos were decoded with the app's
Apple pose estimator; final counter revisions reuse those SHA-pinned observations.
The mask-only InfiniteRep mirror was inspected and not used as RGB evidence.

| Source | Role / scope | Published count | Final counted |
| --- | --- | ---: | ---: |
| bench-58 | Development, leg-raise/clap variation | 8 | 5 |
| bench-166 | Development, bilateral lying raises | 10 | 4 |
| bench-187 | Variant diagnostic, alternating single-leg lowers | 14 | 6 |
| bench-64 | Development negative, reverse crunch / tuck | 12 other-action reps | 0 |
| bench-201 | Separate-source negative, prone leg lift | 6 other-action reps | 0 |
| bench-207 | Separate-source negative, plank leg lift | 6 other-action reps | 0 |

The bilateral test clip was promoted to development after its failure was
inspected. **There is no held-out positive accuracy claim.** The separate-source
prone/plank negatives were not used to tune the policy. Alternating single-leg
counts are outside the bilateral preview profile, so the raw 6 versus 14 is not
an accuracy score. Misses remain in the report: readiness can arrive late, and
joint/torso instability still interrupts otherwise recognizable lifts. This
version needs a fresh supported-view positive set and live phone validation
before it can be described as reliable.

Fixtures: `public-lying-legraise-v1.json` and
`public-lying-legraise-results-v1.json` in `Evaluation/fixtures`. Reproduce complete
RGB inference and native/JVM parity with:

```sh
scripts/evaluate-leg-raises.sh MEDIA_DIRECTORY NEW_OUTPUT_DIRECTORY lyingLegRaise
```

`Evaluation/LegRaiseTraceReplay.swift` supports checking the native owner against
pinned cached pose traces after a common-policy edit. That parity is not an
Android pose-estimator or live-camera qualification.

The built-in imagegen artwork is saved as
`ios/HangInThere/Assets.xcassets/LyingLegRaiseIllustration.imageset/illustration.png`
and `android/app/src/main/res/drawable-nodpi/illustration_lying_leg_raise.png`.
The exact prompt and style reference are in `docs/brand-assets.json`. All six
home cards were rendered at 320/393 points in both themes. Duplicate development
trace/state caches were removed; evaluated media and final evidence remain.

Final lying-raise validation: 1,192 shared tests (zero failures, one optional
skip), native/JVM event parity on all six traces, 18 iOS camera/logging tests,
Android unit tests, instrumentation compile and lint, and signed iPhone build
168. Build 168 has not been installed on the phone.
