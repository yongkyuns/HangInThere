# Full-rate body replay and starting-state diagnostics (v18)

The rear-view grip failure is not fixed by replaying more body frames. None of
the 141 original frames in the predeclared stable-view segment reaches the
counter's measured extended-arm starting condition. Clear hand patches and a
confirmed rear view therefore never reach grip inference admission.

This follow-up fixes the research replay's body cadence and adds explicit
starting-state diagnostics. It does not improve measured grip accuracy, change
the counter or enable a model on the phone.

## Changes

The previous replay consumed the body observations retained beside approximately
four RGB samples per second. The new replay consumes **every original body
observation** while retaining the original sparse RGB schedule. The existing
production Apple decoder/body exporter was reused; no new frame images,
classifier predictions, model downloads or training were needed.

There are 2,798 decoded body observations across the two exposed owner recordings
and six now-exposed public sources. The same fixed-view scope as v17 retains
2,689 observations; the 109 frames after the rear camera begins orbiting remain
outside this experiment. All 362 previously sampled poses match the new native
observations exactly. These are development regressions, not new held-out tests.

RGB reuse also checks athlete identity. The original crop's selected-person pose
must exactly match the current selected-person observation at that timestamp.
An empty or different selection cannot inherit the original crop. Intervening
body-only frames have opaque `noRGB:` keys and never fabricate hand evidence.

The counter replay uses the unchanged `GripRepContextV16Tests` exporter, counter
policy v8, support-reference acquisition and exercise-context policy. Hand
decisions use frozen v17 **with readability**, including the unchanged 0.80
readability and anatomy thresholds, confirmed source view and temporal voting.
The failed readability omission is not retested as a candidate.

Read-only Kotlin diagnostics distinguish:

- athlete tracking unavailable;
- usable arms unavailable;
- starting hang not observed;
- support reference not acquired or subsequently lost;
- starting hang being held;
- waiting for exercise motion;
- active counter exercise.

The legacy replay's broad `released` flag remains unchanged in its original
output. Consumers of the new diagnostic sidecar can see that failure to acquire
an initial reference is not positive evidence of an athlete releasing it. This
correction is in research diagnostics; no new phone prompt is claimed.

## Results

| Recording group | Native-rate result with readability |
| --- | --- |
| Owner pull-up | First correct overhand at 12.02 s; correct labels in both marked full-cycle windows; 29/56 original exercise RGB samples show a label |
| Owner chin-up | First correct underhand at 16.54 s; correct label in its marked full-cycle window; 3/48 original exercise RGB samples show a label |
| Known-grip separate front clip | Unknown; no wrong grip admitted |
| Known-grip separate rear clip | Unknown; no starting reference acquired |
| Four unverified/out-of-scope separate sources | No visible grip labels; no correctness credit |

The two development controls preserve their v17 readability-enabled results.
Both known-reference public sources still fail. The glove/wrap source's former
unverifiable acceptance disappears with the more detailed tracking/counter
timeline. This is not new independent accuracy evidence or a reason to call
abstention a classification success. No separate verified chin-up or neutral
reference was added.

The rear segment has **116 frames without a qualifying measured starting hang**
and **25 with unavailable athlete tracking**. No frame has all usable measured
arms at or above 155 degrees, before even applying the existing dwell. The
largest minimum usable-arm angle is about 115.8 degrees. Individual arm maxima
also remain below the requirement. Brief tracking losses reset acquisition.
These are detector measurements, not a judgement of true joint angles or rep
form. The original image and measured landmarks are shown together in
`build/grip-start-v18/rear-start-diagnostic.png`.

Across all eight replays, the 459 definite setup frames and 200 definite release
frames produce **zero new counted movements, zero exercise admissions and zero
visible grip labels**. Boundary bands remain uncertain. The ring-hold source
does briefly earn counter exercise admission during its activity window, while
its missing confirmed supported view keeps grip unknown; the counter alone
does not establish apparatus type.

## Verification and limits

Forty frozen pins pass, including the previous v17 experiment's models, inputs,
policy and first results. Two behavioural diagnostic tests and the actual-data
diagnostic export pass (three JVM tests). The unchanged counter export also ran
on all 2,689 retained body frames. The new helper only reads counter outputs and
arm measurements; it cannot award readiness or count a rep.

The first v18 report's `rgbExerciseFrames` field counts reusable owned RGB
observations. The accounting addendum preserves **every original scheduled
exercise image** in the denominator, including ownership-unavailable samples.
It changes no original prediction or score. Unknown references cannot earn
correctness credit. See `Evaluation/fixtures/grip-start-cadence-v18.json` and
`build/grip-start-v18/verification.json`.

This is Mac-native offline replay with explicitly confirmed view, not iPhone
inference speed, thermal performance or automatic orientation qualification.
No new reserves were opened. Original media, sparse crops, models and evidence
remain intact. The temporary plotting font cache was removed after rendering;
the reproducible-cache allowlist was checked separately.

## Implication

Strict rep initialization currently blocks grip classification even when pulling
motion and a readable hand surface are visible. The next implementation should
qualify **grip activity** from observed pulling motion separately from the
counter's full starting-hang requirement, keeping that counter requirement for
rep acceptance. It needs explicit setup/release negatives and identity resets,
and must retain readability rather than revive the false-accepting omission.
Earlier v15 motion gates also required an absolute extended-arm baseline, so
reusing them unchanged would retain the same dependency.

Scripts: `scripts/evaluate-grip-start-v18.py`,
`scripts/audit-grip-start-v18.py`, `scripts/render-grip-start-v18.py`.
Diagnostic exporter/tests: `Evaluation/androidTests/GripStartDiagnosticsV18Tests.kt`.
