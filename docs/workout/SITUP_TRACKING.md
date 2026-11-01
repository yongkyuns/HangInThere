# Sit-up preview

Sit-ups have a dedicated home-screen entry on iOS and Android. Common Kotlin
owns source association, readiness, counting, boundaries and durable records.
The iOS floor-camera route reuses counted-set video, keyframes, pose visibility,
voice counting and history. Android reuses camera stability and history; this
change does not add Android set-video capture.

Exercises use matching home shortcuts, camera controls, rep labels,
vertical target gauges and Review / Next set actions. Front camera and visible
joints are the defaults. First-use guides share a layout with exercise-specific
placement and starting poses. Push-ups and sit-ups remain Preview features;
grip analysis and modeled demand remain specific to supported bar exercises.

## Supported setup

Use a low, level, steady landscape phone beside one athlete. Keep a shoulder,
hip and knee visible. A foot helps establish bent knees; a cropped foot can use
positive knee elevation instead. Lie back with bent knees until Ready.
Front/overhead views, leg tucks, V-ups and bench/GHD variants are outside this
preview's working conditions. Missing joints cannot complete an endpoint.

A held resting pose establishes scale and selects one side. A rep requires
both a sustained torso rise (50° projected tilt and 0.45 torso-length shoulder
rise) and a sustained lying-back return. The return uses the same 20° resting
posture as acquisition and must approach the initial shoulder height. Setup
bobs and static holds cannot count. A confirmed hip/knee relocation ends the
set; brief noisy geometry pauses it. Long observation gaps, ambiguity, source
faults and camera movement cancel pending evidence. Manual Finish remains
available; resting between reps is not automatically treated as set completion.

Records retain completed and interrupted attempts, source-time coverage and
up/down timing. Sit-up angles are not stored as elbow measurements. Review
uses Sit up / Lie back labels and Top keyframes. Muscle demand, fatigue and
range comparisons are not inferred for sit-ups.

## Public-video check

The table records the frozen policy-1 baseline. Policy 2 fixes unused-side hip
loss while keeping the counting side fixed. Its five cached observation replays
retain these counts; this is regression evidence, not fresh qualification.

[RepCount](https://svip-lab.github.io/dataset/RepCount_dataset.html) provides count
and cycle-frame labels, which do not certify anatomical form or our exact return
phase. The [frozen corpus](../../Evaluation/fixtures/public-situp-v1.json) pins
publisher annotations, original video hashes, source-family roles and all 32
image-screening decisions. Five ordinary floor sit-up clips were retained before
inference: two development and three test sources. Athlete identities and hidden
duplicates are unverified. Small foot obstructions remain in the evaluation.

| Source | Role | Published reps | Counted returns |
|---|---|---:|---:|
| stu1-53 | Development | 9 | 9 |
| stu2-66 | Development | 6 | 3 |
| stu10-58 | Test | 5 | 4 |
| stu5-52 | Test | 4 | 3 |
| stu4-51 | Test | 6 | 0 |

The final rules produce **19 returns against 30 published reps**: **12/15** in
development and **7/15** in test clips. This is published-count agreement, not
valid-rep accuracy. `stu4-51` reaches Ready, but its usable shoulder–hip projection
peaks at 46.65°, below the configured rise endpoint. It remains a failure in the
result; thresholds were not lowered to make that clip pass.

The first prototype counted 8/30. A development return-check correction brought
that to 16/30; confirming relocation instead of ending on a single noisy joint
brought it to 19/30. Test outputs were inspected during this iteration, so these
are exposed evaluation sources, not a fresh final holdout. Further work needs
new footage. The [results](../../Evaluation/fixtures/public-situp-results-v1.json)
retain both the initial and final totals.

All five originals reached native EOF: **3,940 decoded frames** through production
Apple Vision inference on macOS. Final cached-observation replays agree exactly
between Apple Kotlin/Native and JVM for count timestamps and durable returns.
Camera readiness was assumed for these replays; this does not qualify live iPhone
or Android inference, setup/release accuracy, or production reliability.

Validation also covers shallow/static setup, observed returns, cropped feet,
hidden hips, confirmed relocation, brief jitter, source/camera/person faults,
record recovery and keyframe/timing semantics. Existing push-up video coverage
passes. iOS simulator and Android Kotlin builds pass.

Originals and compact evidence remain outside Git under
`Data/external/public-situp-v163/` and `build/situp-v163/`. The native runner
`Evaluation/PushUpCoverageReplay.swift` accepts `exercise: "sitUp"` per case.
`SitUpReplayTests` consumes a pinned `SITUP_REPLAY_MANIFEST` and writes diagnostics
to `SITUP_REPLAY_OUTPUT`; no dataset or extra job is added to ordinary CI.
