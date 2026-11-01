# Push-up tracking

The iOS preview selects a front or side profile from the starting plank and
keeps it for the movement. Common Kotlin owns association, counting, boundaries
and durable timing; iOS supplies independent camera/motion evidence.

## Working conditions

Use a low, level, steady phone in landscape, reasonably close to one athlete.
Hold a starting plank until Ready.

- **Side:** one shoulder–elbow–wrist chain, its hip, and a knee or ankle.
- **Front:** both shoulders, elbows and hands, with a hip visible at the start.
  Hips may disappear during reps; lower-leg visibility is not required.

Front counting requires both elbows to bend, shoulder descent relative to fixed
hands, and both arms returning to the calibrated top. Static planks, elbow
motion alone and incomplete returns do not count. Missing required arms cannot
complete an endpoint. Camera movement, ambiguous athletes and long tracking or
source gaps interrupt the set. Neither profile verifies form, anatomical depth,
load or whether unseen knees remain straight.

Side policy 4 retains partial-leg behavior and policies 1–3 compatibility.
It separates starting distance from posture and briefly pauses on unusable hip
geometry without inventing endpoints; see the [public-video audit](PUBLIC_PUSHUP_BASELINE.md).
Front policy 1 uses `frontViewPushUp`, keeping its comparisons separate from
`sideViewPushUp`. Recorded angle metrics use the same actual visible arm through
an attempt; front cycle validation additionally checks the other arm.
Completed-rep video, keyframes and retention share the existing library path.

Vision tries quarter-turn inference orientations and, when body coverage is
incomplete, neutral padding. Points outside the source image are discarded
before choosing an observation. Confidence is unchanged; inference passes are
never combined. Preview and saved video retain their original pixels.

## Validation

Both owner recordings are exposed development data, not held-out videos.
Full-source production decoding and Apple Vision inference produce **5/5 side
and 5/5 front**, with every return inside its frozen manual window and zero
setup/release counts.

Pinned native-pose replays through the common durable owner also verify:

| Case | Count |
|---|---:|
| Side original / cropped feet / cropped head and feet | 5/5 each |
| Side working arm or both leg endpoints cropped away | 0 |
| Front original / mirrored poses / leg points removed | 5/5 each |
| Front static plank / one elbow hidden | 0 |

Pixel crops and modified poses are regressions from the same sources, not extra
sessions. Unit/integration checks cover independent camera gating, source and
tracking faults, hidden hips, history compatibility and front/side video review.

`Evaluation/PushUpCoverageReplay.swift` runs source-hash-verified videos through
production inference. `PushUpCoverageReplayTests` optionally consumes a pinned
trace manifest via `PUSHUP_COVERAGE_MANIFEST`, writing records to
`PUSHUP_COVERAGE_OUTPUT` before scoring separate return windows. Camera readiness
is assumed in these replays. Private inputs/results remain under
`build/pushup-front-v159/`.

The front profile and partial framing still need fresh full sets and live phone
checks. These results do not qualify Android inference, iPhone throughput or
production accuracy. Strict form grading, knee-push-up classification and
physiological effort estimation remain outside this preview.
