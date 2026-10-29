# Rail-free dip body-motion experiment

On the owner's 2026-10-21 five-dip development recording, the experimental body
path counts **5/5 image cycles** with **zero setup or dismount counts**. The
existing rail-dependent native replays count 0/5 because support acquisition
never qualifies. This is a development result on one forward-facing recording,
not a replacement for the public detection gate or a strict-form verdict.

The experiment lives in `Evaluation/android/DipBodyMotionExperiment.kt`, which
compiles into evaluation tests only. Neither shipping app nor the installed
iPhone build uses it yet. Pull-up behavior and existing dip policies are unchanged.

## What supplies readiness and a cycle

The experiment consumes the original, upright Apple Vision body observations and
source timestamps from the production video reader. It does not consume a rail
reference, rail pixels, independent hand inference, lower-body joints, reference
windows or an expected repetition count.

- Acquire a measured bilateral top: both elbows at least 140 degrees, shoulders
  above wrists by at least 35% of projected arm length, with stable shoulders and
  wrists for 0.25 seconds. This clip becomes ready at 6.20 seconds.
- Freeze that observed top's wrist/shoulder axes and projected arm lengths.
  Relative wrist drift above 12% of arm length resets the attempt.
- Require both shoulder travel and elbow bending to depart. The bottom needs
  bilateral travel of at least 18% of arm length (and at least 2% of the image
  short side), plus 30 degrees of bend from the top or an angle at most 100
  degrees, sustained for 0.12 seconds.
- Count only after both arms return near their starting shoulder positions and
  recover extension for 0.12 seconds. Mounting without a prior top, a shallow
  adjustment, or lowering to dismount without returning cannot complete a cycle.
- Missing arms, multiple athletes, context changes, non-increasing timestamps
  and source gaps over 0.35 seconds reset the current attempt. A fleeting,
  unconfirmed return cannot merge two attempts into one cycle.

Crossed legs are an optional diagnostic only. Opposing hip/ankle ordering can
suggest crossing in the image; missing or ambiguous legs produce unknown.
This weak projected cue is not calibrated physical crossing detection, and it
never advances readiness, relaxes a gate or earns a count. On this recording it
is true on 43 frames, false on 124 and unknown on 740. Removing **all** hip, knee
and ankle observations produces identical readiness, phases and count events.

## Measured development result

All **907/907 decoded source frames** pass the independent BGRA timestamp
inventory. The native Mac exporter uses the production decoder, orientation,
resizing and `VisionPoseEstimator`; the evaluation detector runs on the JVM.

| Check | Result |
| --- | --- |
| Complete recording | 5 cycles |
| Count times, source seconds | 11.400, 14.100, 17.102, 20.635, 23.935 |
| All lower-body observations removed | Same 5 events and readiness |
| Approach, mount, cross legs, hold: 0–9.20s, 277 frames | 0 in full video; 0 in isolated replay |
| Dismount and approach camera: 25.50s–end, 142 frames | 0 in full video; 0 in isolated replay |
| Authored motion/continuity controls and local evaluation | 12 tests pass |

The controls cover a genuine complete cycle, mounting without an acquired top,
static holds, shallow bobs, one-arm motion, bend without travel, travel without
bend, moving wrists, dismount without return, missing/duplicate/low-confidence
joints, extra athletes, clock/geometry changes, endpoint dwell, and crossed,
uncrossed or absent lower-body cues. These controls are analytic, separate from
the actual setup and dismount source observations.

The owner supplied the expected five repetitions. A visual development audit
checked the return images. The original coarse return windows are retained;
the first window was refined after exact source-frame inspection showed its
initial lower bound was late. No detector thresholds changed after its first
5-cycle result. These are development labels, not independent held-out labels.

## Reproducing the evaluation locally

Create a local reference JSON with `videoSHA256`, `expectedCycles`,
`returnWindows` (source-second pairs), and `zeroCountWindows` (objects with
`name`, `start`, `end`). Run with Xcode, Swift and JDK 17 available:

```sh
python3 scripts/evaluate-dip-body-motion.py VIDEO.mov REFERENCE.json NEW_OUTPUT_DIRECTORY
```

The script builds the native observation exporter, verifies every decoded source
timestamp, runs the experiment and controls, and writes `summary.json`,
`results.json`, source/media/executable hashes and logs. Labels only score events
or slice control windows; they never enter the detector. A failed match, missed
event, extra event, setup count, or lower-body dependency fails the development
check. Keep private inputs and outputs under ignored `build/`; nothing uploads.

This session's evidence is under `build/dip-body-motion/verified/`, with the first
run and visual audit retained beside it. The public-gate and older detector
results remain unchanged.

## Limits before app integration

This first version requires two measurable arms; a side-view path has not been
implemented or evaluated. Stationary wrists are image evidence, not verified
support or camera stability. Camera/scene movement, feet-supported lowering,
assistance and other similar actions need additional recordings and controls.
No anatomical depth, load or strict-form claim follows from a completed image
cycle. Live integration also needs its own camera stability, lifecycle, analytics
method/version and phone-performance checks. The result supports pursuing this
approach without making rails or leg crossing mandatory.
