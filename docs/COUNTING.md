# Current counting policy (v8)

`BilateralDipMotion` v1 is a separate image-motion engine under integration.
It uses two fresh, independently observed hand MCP centres, finite curved rail
boundaries and current scene registration. Both measured arms must establish a
stable starting support, travel and sustain their return before an image cycle
is emitted. A missing elbow cannot establish the start or return. Gaps, camera
movement, stale observations and unresolved returns interrupt that evidence.
Its output does not verify anatomical depth, form or load and must never be
serialized as an ExerciseCounter v8 result. The current live/replay controllers
still use v8; adding this engine and its native adapter does not enable it there.

Local source replay of a labelled four-dip recording from two simultaneous views
produces four image cycles in each view. The actual Kotlin/Native and JVM engines
agree on every one of 227 observations. The iOS SDK's OpenCV 4.5.3 extraction
matches all ordered contour pixels from 304 source crops against the macOS 4.11
adapter. These checks do not establish Android native vision or physical-phone
throughput parity. The original public-video accuracy gate remains mandatory
and currently fails; none of its labels or matching rules have changed.

Pull-up behavior retains policy v7. Dip behavior uses the support-relative policy
below; saved summaries record `selectedGripWithAvailableArms` for dips.

Pull-ups use both independently measurable shoulder/elbow/wrist chains from one
athlete. If one chain is obscured or below the existing confidence/geometry gates,
the other can continue using its own previously observed extended-hang baseline.
The side picker applies only to dips. Available arms must agree; the counter does
not average angles or choose whichever arm would produce a count.

A pull-up increments at the bent-arm endpoint after an extended starting hang:

- All usable elbows reach at least 155 degrees for 0.12 seconds to establish a hang.
- All usable elbows go below 140 degrees to begin the attempt.
- All usable elbows reach at most 100 degrees, and each shoulder's perpendicular
  distance to the fixed bar decreases by at least 20% of its own hang distance
  and at least 4% of the image's shorter dimension, for 0.12 seconds.
- At least one arm must remain continuously measurable throughout each endpoint
  hold; alternating disjoint arm observations cannot supply the dwell.
- Another count requires returning to the extended hang for 0.12 seconds.

An arm newly visible while bent cannot borrow the other arm's hang baseline;
tracking resets until a valid starting hang is observed. No measurable arm,
multiple athletes, invalid apparatus, or a source-time gap over 0.35 seconds
interrupts tracking. Live counting also requires a started set, a fixed confirmed
bar, and the existing phone/scene stability checks. These are provisional movement
counts: chin clearance and exercise form are not verified.

## Dip support and movement

Automatic dip identity uses 0.6 seconds of measurable support posture, including
flexed elbows. This identifies which apparatus to look for; it cannot start a set.
Requiring top extension throughout identity acquisition would impose a second,
longer hold than the separate starting check. Genuine observed apparatus, camera
stability and fresh top support remain required before counting begins.

A confirmed finite support segment remains mandatory; automatic contour setup
or manual confirmation can supply it. One athlete's selected
shoulder and wrist must be unique, in bounds, and meet the existing 0.3 joint-score
gate; the wrist must be within 12% of the image short side of that segment. This
is image proximity evidence, not verified physical hand contact.

Starting support requires a measurable selected elbow of at least 140 degrees,
the shoulder above the wrist by at least 35% of projected upper-arm plus forearm
length, and every other measurable arm also at least 140 degrees. At that support
pose, freeze the selected wrist and the unit direction from wrist to shoulder.
Shoulder height is its signed projection along this direction. Rail orientation
does not determine the movement axis, including when rails project vertically.

- A 0.12s support hold acquires the counter's top baseline.
  An opposite arm first becoming measurable restarts this hold. If it first
  appears after acquisition, the counter requires fresh support rather than
  borrowing the selected arm's baseline or ignoring the new evidence. An active
  attempt is interrupted; it cannot enter completed-rep analytics. A previously
  witnessed arm can still be briefly hidden without losing its baseline.
- Meaningful lowering requires travel of at least
  `max(18% of acquired projected arm length, 2% of image short side)`.
- Departure requires half that travel and at least 15 degrees of elbow bending.
  The 120-degree support sanity gate remains; it is not strict lockout grading.
  A projected dip can satisfy the relative bend/travel gates while its elbow
  remains above 120 degrees throughout the movement.
- The bottom requires the full travel and either an elbow angle at most 100
  degrees or at least 30 degrees of bending from the top, for 0.12s. A usable
  opposite arm with its own top evidence must also meet the bend gate.
- Count only after returning within `max(8% of acquired arm length, 1% of image
  short side)` of the cycle's top, recovering at least half the required travel
  from its bottom, and showing elbow recovery to at least
  `max(120 degrees, top angle minus 20 degrees)` for 0.12s. A usable opposite arm
  with a top baseline must also recover. No count is awarded at the bottom.
- Near-extension frames (at least 155 degrees) can update local top posture
  upwards, preserving the highest witnessed posture through a gradual descent.
  A completed return establishes the next cycle's local top. The wrist, movement
  axis and arm scale remain frozen.
- Wrist movement beyond 12% of acquired arm length interrupts the attempt.
  Missing shoulder/wrist observations pause; outages over 0.75s reset support.
  A missing elbow may sustain an already observed return endpoint using reliable
  shoulder/grip geometry, but cannot establish a new top or bottom.

All dwell uses actual source timestamps. Existing time-gap and phone/scene motion
guards remain. These engineering gates count movement, not strict dip depth/form.
Projected arm scale can be unreliable under foreshortening; unsupported views
must be evaluated separately rather than treated as qualified.

## Automatic workout starting and completion

Both shipping apps use common automatic readiness after genuine support/bar
acquisition and stable phone/scene setup. At least 0.12 seconds of consecutive
eligible source frames starts tracking; a qualified prior frame seeds the
counter's existing starting baseline. The default flow requires no gesture or
spoken countdown. Interrupted readiness restarts on fresh evidence. The legacy
three-second countdown remains covered by policy/native regression tests.

No knee or ankle observations are required. Crossed legs are optional; this policy
does not implement a crossed-leg classifier. Disabling hands-free start exposes
the manual Start set control. Dip support acquisition is automatic when both grips
and either separate paired handles or a shared paired straight bar are visible
and stable. Manual setup remains available for unclear support; acquisition does
not change the movement-counting thresholds. A one-second observed bilateral
release completes a set; a three-second tracking outage interrupts it. Static
holds alone never end a set. The shared analysis retains accepted reps even
without an observed return. See
[motion records, metrics and lifecycle contracts](workout/AUTOMATIC_ANALYSIS.md).

The policy-v2 evidence below is retained as historical evaluation context.

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
