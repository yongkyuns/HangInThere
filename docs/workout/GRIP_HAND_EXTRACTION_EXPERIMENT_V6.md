# Hand-first extraction experiment, v6

The new extractor produces cleaner crops but loses too much hand coverage to
replace the baseline. On five previously untouched recordings, its seven output
crops are reviewed as complete hands belonging to the intended subject. However,
it covers only **7/48** visible target-hand references versus **12/48** for the
baseline. Every new correct crop comes from one recording. The experiment stays
offline; no phone app, counter, classifier weight or learned-confidence gate
changed. Grip classification was not rerun because extraction did not pass the
predeclared coverage criterion.

## Implementation

`scripts/extract-grip-localization-v6.py` separates hand proposals from ownership:

- Run the existing checksum-pinned Hand Landmarker on every selected source
  frame, even when body acquisition fails. A full-frame pass is followed by
  bounded body-wrist regions or overlapping generic upper-image regions, with
  at most one previous-hand region. The total is capped at four calls/frame.
  Detection/presence thresholds remain 0.5; no landmark or depth feature is used
  to classify grip.
- Only a currently detected 21-point hand can produce a crop. There is **no
  body-wrist-only output fallback**. The square contains the detected hand,
  short wrist and margin, and is resized to the existing RGB128 input. Detections
  at search/source boundaries, duplicate detections and conflicting hands are
  rejected. The native hand extent minimum remains 16 pixels.
- Associate a hand with a unique currently supported overhead athlete, checking
  distance to the intended wrist and separation from other people's wrists.
  Native SDK chirality cannot establish ownership.
- When body acquisition is missing, a sole sufficiently large actual face
  rectangle can propose ownership. It must agree across at least two nearby
  observations and have compatible hand position/extension, with no competing
  bodies/faces. Native face confidence at 0.5 is **proposal eligibility**, not a
  calibrated ownership/grip probability. A single face score cannot authorize
  output. This replaced an overly restrictive single-face-score development
  gate; no learned grip threshold was lowered.
- Keep the sharpest detected crop per source/track/hand slot/second. Preserve
  its actual timestamp and the time it becomes available. Repeated frames do
  not accumulate confidence. Cuts, gaps and owner changes reset history.

The face helper, `Evaluation/GripLocalizationFacesV6.swift`, returns source-bound
Apple Vision rectangles independently of body landmarks. It supplies no athlete
orientation or grip label. Face-only spatial ownership remains a hypothesis
whose errors require measurement, not proof of identity.

The policy uses the current body/face owner rather than inferring ownership from
the hand's apparent grip. Previous detected hands supply search regions, never
stale pixels as current evidence. Overhead/body-direction rules can still fail
at the top of a repetition, in rotated footage, or with poor body inference.
The scalar sharpness ranking can favor background edges; it is not a learned
readability score.

## Data and review

The exposed regression selection fixes 24 frames from eight earlier recordings:
source-edge clipping, clear yellow-shirt hands, a coach, bystanders, two active
athletes, missing-body close-ups, distant gloved hands and an edited recording.
All 69 sampled prefix frames for those sources are retained for actual ownership
continuity; only the 24 predeclared frames contribute accuracy counts.

All five remaining uninventoried recording IDs in the already cached, pinned
Kinetics validation archive are reserved before viewing pixels. They require
only 3,956,151 bytes of extracted original videos and no network download. None
is replaced because of difficult content. Source requests are 0.25/0.5/0.75
seconds within seconds 0, 2, 4 and 6, giving 57 actual frames and three EOF
misses. The baseline and candidate use identical source pixels/timestamps.
Baseline body tracking uses the full native pose sequence before sampling.

The 17-pin freeze binds the policy, detector, face executable/source, scripts,
source membership and matched baseline inputs before direct fresh source/hand
review and before new-policy inference. The baseline was prepared before this
freeze without inspecting its source/crop outputs. No fresh result changes
the policy, thresholds or source selection.

Source-only review records the intended subject and complete visible gripping
or overhead hands, using image-left/right identity independently of SDK side.
Hands below the work region, source-edge truncation and unresolved competing
subjects remain explicit. Source references are evaluated separately from
anatomical readability: a small, blurred or gloved hand can still count as a
localization target.

All 86 emitted baseline/candidate crops on scored frames receive a blinded,
mixed-method source/crop review. Labels distinguish complete target hands,
partial/occluded hands, non-hands, wrong people, ambiguous ownership and hands
outside the work region. Crop hashes, source hashes, boxes and identities are
bound in the review index. Duplicate outputs cannot increase coverage.
These are **agent pixel reviews, not independent human gold labels**. The prior
human-review request remains pending.

An enlarged source strip corrected four fresh source references before
numerical scoring: head/upper-body clipping in the supported-child recording
had been mistaken for clipping of the hands. Both hands are present in F042–F045.
The initial 44-hand reference and its freeze remain intact; the separately
adjudicated common reference has 48 hands and is applied to both methods.
The correction changes no output crop, detector policy or selected recording.

## Results

| Exposed regression, 24 scored frames | Baseline | Candidate |
| --- | ---: | ---: |
| Complete target hands covered | **16/22** | 12/22 |
| Total emitted crops | 36 | 13 |
| Non-hand crops | 5 | 1 |
| Wrong-person crops | 2 | 0 |
| Ambiguous ownership | 6 | 0 |
| Partial/occluded crops | 4 | 0 |
| Hands outside work region | 3 | 0 |

| Untouched five recordings, 57 frames | Baseline | Candidate |
| --- | ---: | ---: |
| Complete target hands covered | **12/48** | 7/48 |
| Total emitted crops | 30 | 7 |
| Ambiguous ownership | 18 | 0 |
| Other reviewed invalid crop accepts | 0 | 0 |

The fresh candidate's seven complete crops all come from the same adult
recording. Zero bad accepts among seven crops is not a dependable error-rate
estimate. The sample also includes rotated/backlit footage, simultaneous
athletes, a supported child and tiny distant hands; it does not establish
representative adult phone-camera accuracy or athlete-independent performance.
Every source frame remains in the inventory, including ambiguous scenes.

### Remaining failure

One regression crop still contains a bracelet/forearm rather than the gripping
hand. The Hand Landmarker hallucinates a hand there, and the crop structurally
contains all 21 inferred points. **Landmark containment cannot certify actual
hand correctness.** Actual source inspection exposes the error.

The missing-body close-up produces two hand-only proposals late in its retained
prefix, after face agreement. That timestamp is outside the 24 scored regression
frames, so it receives no coverage credit. The diagnostic demonstrates that
body-independent proposal/association can run, but intermittent face support
and source-edge clipping remain. It is not a qualified two-hand recovery.

![Actual complete crops, abstentions and remaining false hand](../../build/grip-localization-v6/examples.png)

The candidate keeps four fresh crops after within-second sharpness selection.
Selection recomputes exactly from the original seven crops and timestamps; it
does not create new evidence or demonstrate a grip-accuracy improvement.

## Decision

Do not deploy or use this candidate to regenerate training labels automatically.
It substantially reduces unreliable inputs but fails the predeclared requirement
to improve coverage without increasing bad accepts. Restoring detection recall
for real closed grips, gloves, small hands and partially visible bodies is the
next bottleneck. That calls for verified hand-box supervision and explicit
non-hand/forearm negatives, rather than further training a grip classifier on
missed or mislocalized inputs. Ownership/ambiguity remains a separate check.

The exposed failures are now fixed regression evidence. New accuracy claims
need independently verified references and another untouched cohort. Unknown
grip must continue to permit rep counting; this experiment changes neither.

## Verification and storage

All **462 Python tests pass**, including new tests for full landmark containment,
source/search clipping, missing-body proposals, required temporal ownership,
competing athletes/faces, other-person wrist ambiguity, missing face evidence,
non-overhead hands, duplicate detections, sharpness/timestamp selection and
coverage accounting. The existing shared JVM planner export passes, and
`git diff --check` passes. Tests establish behavior, not model accuracy.

The audit reconstructs 35 new crops across all 126 processed candidate frames
and all 86 reviewed baseline/candidate crops from their original source pixels.
It verifies the four-call ceiling, exact clearer-crop selection, all 17 current
freeze pins, the initial source-reference freeze, and every older v2–v5 freeze.

The final candidate uses 201 hand calls for 69 regression-prefix frames and
169 calls for 57 fresh frames. Host hand extraction takes 3.63 and 2.44 seconds
respectively; separate native face/body stages and phone latency/thermals are
not included. It has not been ported to or benchmarked on the phone.

Cleanup removes 111 unreferenced native PNGs (33,329,691 bytes) and the completed
experiment's reproducible Swift module cache (222,996,391 bytes), totaling
**256,326,082 bytes**. All original videos, selected/tested RGB, crops, native
poses/clocks, models, first outputs, reviews, source/build logs and compiled face
helper remain. The idle Gradle daemon is stopped. Free-space changes may also
reflect other processes and are not attributed to this cleanup alone.

Protocol and source references are `Evaluation/fixtures/grip-localization-*-v6.json`.
Complete first outputs, reports, reviews, development snapshots, correction
evidence, audits and provenance are in `build/grip-localization-v6/`.
