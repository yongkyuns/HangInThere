# Body-scale and hand-visibility guidance on iPhone

The iPhone live pull-up screen now has a large capture-guidance card during
setup and an active set. Distance uses body size and posture as requested; it
does not need a hand-size measurement or a new trained quality encoder.

## What the card means

| State | Main action | Evidence |
|---|---|---|
| Body appears small | **MOVE PHONE CLOSER** | Both shoulder span and torso length are below the placement targets. |
| Wrist is near/outside an image edge | **KEEP HANDS IN FRAME** | A confident primary wrist is clipped or within the edge margin. |
| Hand evidence is missing | **SHOW BOTH HANDS** | A wrist is unclear, or fresh associated hand inference did not return a complete identity-valid hand. |
| Torso view is unsupported | **USE FRONT OR REAR VIEW** | The existing associated torso-frame gate rejects the view. |
| Body cannot be measured | **SHOW YOUR UPPER BODY** | Reliable shoulder and hip context is unavailable. |
| Evidence is pending | **CHECKING HANDS** | Fresh hand/body evidence for the current athlete is pending, expired or mismatched. |
| Both hands are detected | **BOTH HANDS DETECTED** | Body scale passes, view is supported and both hands have usable associated landmarks. |

Orange action text and a thick orange border identify placement issues. Cyan
identifies pending analysis; green identifies detected hands with sufficient
body scale. The action is 30-point heavy text, with an opaque dark card and an
18-point per-hand status line. Text and symbols accompany color. The card shows
shoulder/torso percentages at all times; tapping that row reveals the target,
body/view-match status, analysis age, policy version and any inference failure.

Green confirms capture evidence, not a known grip or recognition accuracy.
Grip confirmation still uses the existing policy. Every card states that rep
tracking can continue. This guidance never changes workout readiness, set
starting, rep acceptance or credited grip history.

## Body measurement and initial range

Shoulder span is the Euclidean distance between the shoulders. Torso length is
the distance between shoulder and hip midpoints. Each is divided by the camera
image's **short side**, making the measure independent of resolution and less
sensitive to arm raising than a whole-person bounding box. Primary landmarks
need confidence at least 0.5. Missing primary hips may use strong auxiliary hips
only when the auxiliary observation matches the current athlete.

The initial placement targets are:

- Shoulder span at least **18%** of the image's short side; **or**
- Torso length at least **28%** of the image's short side.

Only falling below **both** triggers “move phone closer.” Narrow shoulders from
foreshortening or a tucked/shortened torso alone cannot trigger the distance
cue. A supported front/rear observation takes priority over scale advice, and
hand clipping takes priority over moving closer. The edge margin is 3.5% of the
short image side. No fixed maximum body percentage is imposed: edge/context
visibility establishes when the camera is too close.

These are engineering placement targets, **not a validated usable grip-size
range, a measured distance in metres, or proof of readable hand detail**.
Body size alone cannot guarantee hand visibility. A controlled distance sweep
on the intended phone still needs to establish recognition performance within
the suggested framing range. The live diagnostics make that measurement
practical without concealing uncertainty behind a grip confidence number.

## Existing-footage check

`scripts/audit-grip-body-scale.py` inspects the unchanged v11 development input.
It measures primary pose body scale without fitting a model, decoding new
videos, reading final reserves or using classifier successes to select inputs.
The authoritative receipt is
`build/grip-capture-v1/body-scale-audit-final.json`.

Among reviewed front/rear, closed-grip patches with measurable primary shoulders
and hips:

| Existing development refs | Body measurable | Below both targets | At/above either target |
|---|---:|---:|---:|
| Training: readable hand | 91 | 41 | 50 |
| Training: unclear/mixed hand | 74 | 53 | 21 |
| Validation: readable hand | 20 | 8 | 12 |
| Validation: unclear/mixed hand | 46 | 30 | 16 |

Readability and body scale overlap substantially. This supports a placement
hint, not replacing hand detection with body scale or claiming that the target
solves recognition. Many primary poses lack reliable hips; those are explicitly
reported as unavailable rather than silently counted as success. Live guidance
can also use associated auxiliary hips, so this table is not a full replay of
live admission. Counts are correlated hand patches, not independent recordings.
All 526 training and 218 validation inputs remain in the audit inventory.

## Freshness and diagnostics

Hand inference must be within 0.5 seconds of the current single-person pose,
match image size, have a valid torso frame, and agree with current shoulder and
wrist positions within the existing 12%-of-short-side association tolerance.
Each side requires one identity-valid SDK hand with wrist/index/middle/pinky
landmarks inside its source image and crop. Multiple hands, contradictory SDK
handedness, missing joints, expired results and foreign body contexts cannot
produce green. Missing hand evidence alone never diagnoses excessive distance.

Source-time debounce holds changing advice for 0.4 seconds. Losing the evidence
behind a green state clears it immediately. Camera suspension, analysis
failure, setup reset and expired body frames clear prior feedback. Dips retain
their current behavior and do not display this pull-up grip card.

## Verification

The full shared JVM suite passes 787 tests, including nine new capture-guidance
tests alongside existing workout tests. New cases cover absent hands at small/large body scale, wrist-height
changes, torso/shoulder foreshortening, source resolution, clipped hands,
unavailable hips, auxiliary hip fallback, side views, incomplete hands,
stale/future/foreign observations and prompt recovery.

Eighteen targeted Apple tests pass, exercising the real JSON bridge and native
feedback decoder plus existing framing and readiness tests. A dedicated temporary host runner
selects only these media-independent tests because this checkout lacks the
generated decoder-smoke fixtures; it does not claim that the full Apple media
suite ran. The failed general-runner preflight and initial Swift import failure
are retained with the corrected run.

`scripts/render-workout-review.sh --grip-capture-only` renders five conditions at
widths 320/430, in setup and running states, plus expanded diagnostics and a
small-screen scrollable layout. Its 22
renders use the actual SwiftUI card and OCR assertions for the action, body
numbers and rep-tracking message. The narrow/wide main cards and expanded
diagnostics are also visually inspected. Signed device-build and installation
receipts are stored separately under `build/grip-capture-v1/`.

An additional existing-readiness render sweep was stopped after seven PNGs
when Apple Vision text recognition stalled. A process sample shows a semaphore
wait in `VNRecognizeTextRequest` after SwiftUI rendered the image, rather than
an app layout hang. That partial extra sweep is not reported as passing. The
22 new capture renders, native integration tests and signed build completed.

Installation was attempted, but the physical iPhone lost its trusted connection
and is currently listed as unavailable. The signed app is retained at
`build/DerivedData/Build/Products/Debug-iphoneos/HangInThere.app`; reconnect and
unlock the phone to install it. No phone installation or live-distance
qualification is claimed by the build verification receipt.

No learned model is changed, and no fresh grip-accuracy result is claimed. This
is the capture guidance and diagnostic build needed for a phone-distance sweep.
