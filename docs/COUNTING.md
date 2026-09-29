# Bar-relative movement counter (policy v2)

`ExerciseCounter` remains a small framework-free state machine shared by the app,
core tests and saved-observation diagnostics. Apple Vision supplies body landmarks;
a separately confirmed fixed bar/rail edge supplies apparatus geometry. Neither
system derives the other.

**Policy v2 does not count without an apparatus reference.** There is no fallback to
wrist motion, projected arm length or an inferred bar. The UI continues to report
**Form unverified**: chin clearance, strict dip depth, lockout and physical hand
contact are separate measurements.

## Inputs and state

For every displayed source frame the counter receives:

- actual source presentation time;
- the selected anatomical arm's shoulder/elbow/wrist pose evidence;
- one fixed `BarSegment` confirmed during bar setup.

The bar edge and pose use the same upright top-left pixel coordinates. The counter
uses the shoulder's perpendicular image distance to the infinite line defined by
the observed finite bar edge. The finite endpoints establish the line only; body
motion is not clamped to an edge endpoint.

A sustained extended-arm observation arms the state machine. After departure, a
bent endpoint requires both elbow flexion and meaningful reduction of
shoulder-to-bar distance. For a supported view, pull-up ascent and dip descent both
bring the selected shoulder closer to the gripping bar/rail line. Pull-ups count at
the sustained bent endpoint; dips count only after returning to sustained extension.
A final pull-up hold keeps its already-observed movement without requiring descent.

Returning to extension before the bent endpoint is a `partial` attempt. Missing or
ambiguous pose data, invalid timestamps, source-time gaps, or a missing/invalid bar
reference interrupt an active attempt. EOF never manufactures a completion.

## Fixed provisional policy v2

| Parameter | Value |
| --- | ---: |
| Extended interior elbow angle | >=155 degrees |
| Departure hysteresis | <140 degrees |
| Bent interior elbow angle | <=100 degrees |
| Continuous endpoint evidence | >=0.12 source seconds |
| Largest source-time gap | 0.35 seconds |
| Required shoulder-to-bar distance reduction | >=20% of armed start distance |
| Absolute movement floor | >=4% of image short side |

The movement gate is `max(20% of start shoulder-to-bar distance, 4% of image short
side)`. These are engineering constants, not validated form criteria. Existing
`ArmMeasurement` quality checks still require a unique usable shoulder/elbow/wrist
chain. Multiple-person ambiguity can pause measurement; the controlled POC does not
add an identity tracker.

Policy v1 used shoulder motion relative to the wrist plus projected-arm-length and
absolute wrist-drift guards. Real-video evaluation showed those assumptions were
not physically reliable under foreshortening and camera/apparatus image motion.
They are removed rather than relaxed.

## Replay lifecycle

Bar confirmation rewinds the current fixed-camera source to the beginning and
resets count state, ensuring every counted frame uses the same reference. A normal
restart of the same source preserves the confirmed bar and resets the count.
Changing exercise/arm, importing another source, closing the source, or changing
image dimensions invalidates the bar. Clearing the bar also clears count state.

Only displayed source frames advance the counter. Pause preserves state; a pending
inference result cannot be counted twice.

## Saved-observation diagnostic

The exact production counter can be replayed over source-PTS pose observations:

```sh
./scripts/count-replay.sh observations.jsonl pullUp left output.json \
  401.9179 113.4035 489.4577 99.9114
```

The four optional coordinates are the frozen bar-edge endpoints. Omitting them is
valid for a diagnostic, but policy v2 then emits no movement counts. Reports retain
the exact reference edge, source/executable hashes, source revision and toolchain.
Temporal scoring verifies that a supplied edge exactly matches the frozen reviewed
reference; an unreviewed substitute is rejected.

## Development real-video result

On the existing 180-frame fixed-camera pull-up sequence, a development bar edge was
selected from image-line evidence only and frozen before running policy v2. Replaying
the retained real Apple Vision observations produced **one movement at 4.50 source
seconds**, inside the existing reviewed 4.433-4.633 second event window, with zero
interrupted attempts.

This is useful causal evidence that replacing the policy-v1 projection/contact
guards fixes the identified single-person failure. It is **not held-out counting
qualification**: the apparatus reference was added after policy-v1 failure analysis,
and chin clearance is still unmeasured.

The spectator/moving-camera dip clips remain stress diagnostics rather than the
controlled fixed-phone acceptance set. They do not receive a frozen fixed apparatus
reference under policy v2.

## Next qualification

The next accuracy gate is controlled fixed-camera pull-up and parallel-bar-dip
video with bar references fixed before running the counter, source-separated
movement-event labels, and supported-view coverage. Strict pull-up validity also
needs chin-vs-bar evidence; strict dip validity needs an independently defined depth
criterion. Physical iPhone performance remains a separate gate.