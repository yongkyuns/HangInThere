# Supported capture screening and valid-case failure (v19)

The user confirmed that the rear clip investigated in v18 is a partial-range
outlier. Keep the full starting-hang requirement and exclude such recordings
from supported-input qualification. This supersedes v18's proposed separation
of grip activity admission from strict rep initialization. Historical outputs
remain unchanged.

The continued supported-case evaluation finds a different failure: a valid
rear-view pull-up acquires the starting hang and movement, but its learned
readability head rejects all eligible hand observations. That clip stays in
the supported failure denominator.

## Source screening before classification

The v19 protocol freezes the existing counter, models, 0.80 thresholds,
readability-enabled v17 hand policy and automatic wrist-crop geometry. Camera
view is source-confirmed as a diagnostic setup input; this does not qualify
automatic orientation or the phone pipeline. Learned image contact remains
unused. No new training or phone installation occurs.

The first four SHA-selected remaining sources were screened before hand
inference. None meets the full capture conditions. A separate batch of eight
older reserve sources was then frozen from metadata, ordered by source
resolution and source hash, also before hand inference. All eight were screened;
no clip was replaced because its classifier failed. Their groups/recording
hashes are disjoint from both hand models' fitting and calibration roles.

| Newly screened source | Exclusion from fixed full-capture qualification |
| --- | --- |
| 1KJ2CrNPjI4 | Bent-arm hold/leg raises; no full starting hang or complete pull |
| 5M1PFdVAblE | Hands/forearms clip the top edge during extension |
| S1w26NGt6VQ | Distant, small gloved hands; surface cannot be verified |
| qOYstr_A_5o | Small blurred hands at the upper boundary during swinging pulls |
| -5FM8mMJeYc | Fingerboard/edge grip and a later performer cut |
| 2BC4P5QWDSk | Horizontal inverted rows |
| BuJk69zP0No | Film sequence, dramatic exposure, cuts and changing camera angle |
| K6-XDRh0EYM | End-screen montage containing multiple small simultaneous videos |
| KjzNFkGb8Is | Readable underhand dorsal gloves, but repeated zoom/reframing and later clipped hips |
| SZ5Gkb_OyTM | Side/oblique view and hands outside the upper image edge |
| Y1TTOUienAA | Finger-under-bar gag; no full athlete or starting hang |
| fKZHo9Oz-4o | Short oblique ring/handle tuck hold without a preceding full hang |

The KjzNFkGb8Is source has a verifiable underhand hand surface and could support
a separately defined future hand-only experiment. It is not counted as a
qualified full capture here. A clearly identifiable athlete with a coach is
not itself an exclusion; qOYstr_A_5o fails image detail, not person count.

All twelve remain in the source ledger. **Zero new recordings qualify, so
fresh supported-video accuracy is unavailable.** These exclusions do not earn
classification successes or failures. Four groups from the newer acquisition
remain unread. Historical reserve manifests and source roles are preserved.

## Supported regression

Two existing front/rear source examples, M02/M05, were reviewed separately as
exposed regressions. M05's right gripping hand is outside the image, so it is
excluded before inference. M02 (`-beNuPANrTI`) has a stable rear view, a visible
full hang and separately visible hands. It remains supported regardless of
the resulting detector output. Two previously exposed owner clips are retained
as development controls.

| Supported recording | Grip output | First correct label | Original exercise RGB samples with a label |
| --- | --- | ---: | ---: |
| Owner front pull-up | Correct overhand | 12.02 s | 29/56 |
| Owner front chin-up | Correct underhand | 16.54 s | 3/48 |
| M02 rear pull-up, exposed regression | Unknown | None | 0/38 |

M02 has 199 exercise-eligible native body frames, so failure to acquire a full
hang is not its blocker. Among 50 hand crops associated with current exercise
and wrist support:

- 45 exceed 0.80 for the **correct dorsal hand surface**;
- zero exceed 0.80 for the opposing palmar surface;
- zero meet the 0.80 learned readability requirement;
- all 45 strong correct dorsal predictions are rejected by readability.

For example, the right crop at 4.27 s has dorsal score 0.9987 and readability
0.5391. Crops and source labels were inspected before scoring; no box, desired
grip, threshold or confidence was altered to make it pass. The 45/50 figure is
a correlated single-hand surface diagnostic with known view, not grip-video
accuracy. The classifier still produces no confirmed grip on this valid clip.

This is a supported rejection to fix. It must not be dismissed as another
posture outlier. The earlier v17 readability omission admitted a wrong label
on blurred footage; that experiment remains a failure and is not promoted.

## Verification

The replay processes 4,599 original native body frames and 615 original
scheduled RGB frames across all sixteen intake/control records. All 615 sampled
poses match their full-rate native observations exactly. Crops use the current
native tracked athlete at each scheduled source timestamp; no old or foreign
subject crop is borrowed. All 330 automatic crop images are reconstructed and
checked. Excluded sources receive no hand-model calls or correctness credit.

Thirty frozen pins pass. Thirteen targeted Python tests pass, including five
new tests for exclusion accounting, missing-frame denominators, unknown
reference labels, conflicting predictions and setup/release events. The actual
unchanged shared counter is replayed through the existing JVM exporter.

Within the supported controls there are 279 definite setup and 182 definite
release native frames: zero new counted movements, zero exercise admissions,
and zero visible grip labels. M02 does not contain definite setup/release
reference windows, so those negative results are owner-development controls.

The new source-screen conditions are manual research annotations, not an
implemented automatic phone gate. No independent supported test video was
available from these two batches, and no model is qualified for deployment.
Compiler-cache cleanup is restricted to the existing allowlist; sources,
models, labels and the new review evidence are retained.

Machine evidence: `Evaluation/fixtures/grip-supported-capture-v19.json`,
`build/grip-supported-v19/source-references.json`, `verification.json`,
`exposure-ledger.json`, and `test/report.json`.

Scripts: `scripts/evaluate-grip-supported-v19.py`,
`scripts/audit-grip-supported-v19.py`, and
`scripts/tests/test_grip_supported_v19.py`.
