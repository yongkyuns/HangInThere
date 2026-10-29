# Grip recognition: establish a supported camera setup first

The development objective is reliable grip recognition under explicit,
achievable capture conditions. The athlete should receive actionable placement
guidance when those conditions are not met. Broad recognition of tiny, hidden,
blurred or heavily oblique hands is outside the current objective.

This direction supersedes the proposed separate RGB quality encoder in
`GRIP_QUALITY_ROBUSTNESS_V12.md`. Keep the existing trained artifacts and
results as historical evidence. Do not start another quality-encoder experiment
as the default next step.

## Initial supported setup

- One identifiable athlete, viewed from the front or rear. Keep the existing
  torso-based view gate; a head turn must not change the view classification.
- Both gripping hands fully inside the image and separately visible. Their
  actual source pixels must contain enough detail to distinguish grip. Hands
  hidden by the body, overlapping each other or cropped at the image edge need
  repositioning.
- Frame enough shoulders, hips and arms to establish the existing torso and
  wrist associations. Move the camera close enough to resolve the hands while
  retaining that context throughout the movement.
- Use a fixed phone and ordinary, usable lighting. Severe blur or exposure
  clipping needs a capture adjustment rather than an attempt to recover an
  unsupported anatomical label.
- Use observed exercise posture and motion to admit hand evidence. Setup
  reaches and released hands do not provide a pull-up/chin-up/neutral label.
  Do not require the RGB contact head to recognize a bar, handle or finger
  closure before admitting an otherwise observable exercising hand.
- Within these supported capture conditions, do not require a separate learned
  readability prediction. Keep hand ownership, front/rear view, exercise
  context, surface/neutral confidence and temporal agreement. Correct surface
  labels can coexist with a failing readability head; surface confidence alone
  still does not establish that an unsupported input is usable. The
  [v20 comparison](GRIP_WIDTH_METADATA_V20.md) changes this gate offline without
  changing model thresholds or deploying the RGB model to the phone.
- Retain the existing full starting-hang and movement-range requirement for
  pull-ups. Bent-arm holds and partial-range examples that never show the
  required starting posture are outside this qualification. The user's v19
  scope correction supersedes the proposed v18 separation of grip activity
  from strict rep initialization. Do not change the counter to accommodate
  those outliers.

Start with underhand and overhand on a straight bar and neutral on separately
visible parallel handles. Keep apparatus ambiguity, fingerboards, unusual
angled handles and commando grips outside this initial qualification. This is
a capture/evaluation scope; it adds no rail-detection requirement to dips.

These are proposed working conditions to validate. Existing public-source
labels alone do not establish a successful phone setup.

## Placement feedback

| Observable condition | Guidance |
|---|---|
| Athlete's torso view is too oblique | Move camera toward your front or back |
| Both shoulder span and torso length are below the body-scale placement targets | Move phone closer |
| Hands are outside the image, hidden or cannot be located | Keep both hands clearly in view |
| Camera is too close to retain the necessary upper-body context | Move phone back · keep hands and upper body in frame |
| Visible image detail is lost to lighting or movement | Improve lighting or hold the phone steady |

Distance guidance uses body size and posture as the first proxy; it does not
require a hand-size measurement. A missing hand
result alone cannot distinguish distance from occlusion, clipping or a detector
miss. It must not automatically tell the athlete to move closer. Do not imply
that lighting or motion has been diagnosed when those causes are unmeasured;
use a general hand-visibility message in that case.

Measure body scale from source-image shoulder span and shoulder-to-hip torso
length, accounting for posture and view. A 128×128 model input, an expanded
wrist crop and digital enlargement do not establish original hand detail. The
initial iPhone placement targets are documented in
[body-scale guidance](GRIP_BODY_SCALE_GUIDANCE.md); they still require a
controlled phone-distance sweep to establish recognition performance.

The app implements the front/rear gate and large iPhone setup/running prompts
for body scale and per-hand visibility. The existing rep/framing checks support more situations
than grip recognition. A grip placement issue keeps the grip label unavailable;
rep counting and set readiness retain their existing policy.

## Establish the working range

1. Inspect existing readable, closed-hand front/rear examples as candidate
   supported inputs. Preserve all original labels, source roles and previous
   results. Use challenging examples as explicit outside-scope checks; they
   remain in admission and rejection accounting.
2. Measure shoulder span and torso length in the original camera image. Use
   body scale as the distance proxy; verify hand detection separately. Do not
   infer distance from an expanded crop or use classifier successes to define
   the supported set.
3. Record a small distance/framing sweep with the intended phone resolution:
   close, intermediate and far, front and rear, each supported grip. Keep
   shoulders/hips, both hands and the required movement range in frame. Verify
   that some usable distance satisfies both hand detail and body framing. If
   no range does, change the capture setup before expanding model complexity.
4. Validate the body-scale placement targets and stability rule on development
   recordings. Keep different source recordings for evaluation; repeated frames
   and camera cuts of the same athlete do not create independent test groups.
5. Train and compare grip recognition on the supported subset. Report grip
   accuracy, unknown rate and usable bilateral/video coverage within that
   subset. Separately report how many input recordings meet the conditions,
   whether unsupported inputs receive the correct guidance, and how many
   supported inputs are incorrectly rejected.

Readability labels can describe a candidate development subset. They are not a
deployed admission test or evidence that the app can recognize the conditions
automatically. A subset chosen using model successes is also not a valid
working-range qualification.

Record source-only scope decisions before classifier inference. Excluded
recordings remain in source-intake accounting, separately from supported-input
accuracy. A visible qualifying hang that the pose detector misses remains a
supported detection failure; do not filter it using the detector's output.
When no new recording meets the capture conditions, independent accuracy is
unavailable rather than zero or perfect.

The v11 inventory contains 100/218 validation patches marked front/rear, clear
and closed, plus 154/526 training patches with those properties and a supported
grip label. These are single-hand candidates, not qualified bilateral phone
recordings. Existing readable-source gaps remain: train rear-neutral, validation
front-underhand, validation front-neutral and validation rear-neutral. Keep
separate videos for evaluation and preserve the untouched final reserves.

## Implementation boundary

Use body-scale/visibility checks and large actionable guidance before
considering another learned quality model. Conditions that can
be measured directly should be visible to the athlete. The first success
criterion is a repeatable capture setup in which the grip classifier works and
the app can tell the athlete how to reach that setup.

The iPhone implementation is described in
[body-scale guidance](GRIP_BODY_SCALE_GUIDANCE.md). The additional diagnostic
and placement UI is implemented; controlled physical-device distance
qualification remains. Grip classifier thresholds, crop policy and rep policy
are unchanged.

The [motion-admission experiment](GRIP_MOTION_ADMISSION_V15.md) implements this
direction offline and tests recording-separated setup/release controls. Its
per-arm revision avoids requiring visible hips for exercise evidence. All
automatic grip labels still remain unknown on that cohort, so it does not
qualify a usable phone setup or replace the live geometric classifier.
