# Push-up tracking

The iOS preview currently supports side-view push-up cycles. Front-view counting
remains a separate implementation and validation task.

## Working conditions

- A low, level, steady phone in landscape, reasonably close to the athlete.
- One clear shoulder–elbow–wrist chain, a hip, and a knee or ankle on that side.
- A held starting plank, followed by observed lowering and return.

Policy 2 accepts a shoulder–hip–knee line when the ankle is cropped, or a
shoulder–hip–ankle line when the knee is missing. The face and far arm are not
required. Missing lower-leg geometry cannot establish straight-leg form: these
remain observed image cycles, with form and load unverified. Brief tracking gaps
can preserve cycle state, but missing required endpoints cannot complete a rep.
Camera movement, ambiguous athletes and long tracking/source gaps still interrupt.

Common Kotlin owns association, counting, set boundaries and durable rep timing.
iOS supplies independent camera/motion evidence. Vision tries quarter-turn
inference orientations and, when body coverage is incomplete, neutral image
padding. All results map back into original camera coordinates. Points in the
padding are discarded **before** selecting an observation; confidence scores
are unchanged and joints are never combined across inference passes. Preview
and saved video retain their original pixels.

Old policy-1 records remain readable. Policy-1 and policy-2 comparison identities
stay distinct. Completed-rep video, keyframes, compact encoding and retention use
the same recording preference and library path as pull-ups and dips.

## Validation

Two owner recordings contain five reps each. They are exposed development
recordings, not independent test videos. Cropped variants are regressions from
the same source; they do not add independent subjects or sessions.

| Complete source / pixel crop | Policy 1 | Policy 2 + padding |
|---|---:|---:|
| Side, original | 5/5 | 5/5 |
| Side, feet cropped | 0/5 | 5/5 |
| Side, tighter head-and-feet crop | 0/5 | 5/5 |
| Working arm cropped away | 0 | 0 |
| Both leg endpoints cropped away | 0 | 0 |
| Front view, unsupported | 0 | 0 |

Every accepted return must match its frozen manual window; setup and release
windows must contain zero counts. A zero on an unsupported view is a rejection,
not evidence that exercise counting succeeded.

`Evaluation/PushUpCoverageReplay.swift` runs source-hash-verified complete videos
through the production decoder, cropped-pixel pose inference and common counter.
Its manifest supplies `id`, `path`, `sha256`, and optional fractional top-left
`crop: [x, y, width, height]`. Camera readiness is assumed to isolate image/rule
behavior. Optional `PUSHUP_COVERAGE_TRACE` exports private pose observations for
diagnosis. Local reports and manifests are under `build/pushup-coverage-v158/`.
Common and native integration tests cover partial visibility, setup/release,
tracking gaps, old-history compatibility and video/keyframe retention.

The original full side recording previously produced 5/5 with both Vision and
MediaPipe Heavy through the common engine. The new pixel-crop study uses Apple
Vision on macOS; it does not qualify Android-native inference, iPhone throughput
or production accuracy. Separate full sets with partial framing and a dedicated
front-view profile are still needed. Strict depth grading, knee-push-up variant
classification and physiological effort estimates are outside this preview.
