# Completing the remaining field-validation goals

The remaining roadmap goals require observed evidence. This protocol connects
independent session labels, physical-device reports, coach findings and usability
trials to the existing benchmark and release assessor. Preparing a packet does
not complete those goals.

## Campaign responsibilities and outputs

| Goal | Required work | Retained evidence |
| --- | --- | --- |
| Independent session benchmark | Recruit consented athletes; collect supported conditions and negatives; use two reviewers blind to runtime output; adjudicate and freeze athlete/session splits before tuning | Frozen annotations, capture/transform hashes, consent and exposure audit |
| Accuracy and repeatability | Run the actual native apps on untouched test sessions under predeclared gates; score counts, boundaries, phases, holds and coverage by supported condition | Bound native exports and whole-session score report |
| Physical performance parity | Run sustained live camera/pose/scene/display sessions on representative iOS and Android devices; measure timing, drops, battery and thermal behavior | Content-free physical device matrix and original measurement reports |
| Coach review | Qualified coach reviews the exact app and coaching specification; coordinator verifies credentials | Dated signed findings, credential verification and report digest |
| Equipment-friendly usability | Observe beginners on both platforms from actual equipment distance and in actual gym noise, without guiding the tasks | Trial protocol, observations, failures, adjudication and dated reviewer findings |

Keep originals, participant identities, credentials and recordings in the campaign's
approved local storage. Use opaque participant/source/reviewer groups in aggregate
files. Tools here read named JSON reports and the coaching specification; they do
not acquire media, contact reviewers, upload recordings or grant training consent.

## Freeze collection and measurement decisions

Declare supported device/camera/equipment/lighting/assistance conditions and cohort
sizes before collecting the test split. Include pull-ups, dips, underhand/other grips,
assisted and negative practice, hangs/support holds, incomplete returns, obscured
arms, multiple people, empty scenes and genuine exercise transitions. Define
exclusions, missing-label treatment, timing tolerance and repeatability trials in
the campaign audit. Disclose prior developer/model exposure; existing development
videos are regression data and cannot become an untouched holdout.

Use [the frozen benchmark contract](../../Evaluation/WORKOUT_BENCHMARK.md) for
exact source-PTS labels and native normalization. Freeze the measurement and
physical-device policy before tuning with `workout_release_readiness.py freeze`.
Choose thresholds and cohort requirements with the reviewers; the tooling imposes
no claim that its synthetic examples are sufficient population evidence.

Run the full frozen test split on both native platforms once after tuning.
Preserve failed runs, exclusions
and every original regression gate. A consumed test split requires new independent
sessions for another release qualification. Score supported conditions individually;
a pooled passing result cannot hide a failed condition.

## Physical device work

Use [the existing iPhone protocol](../DEVICE_QUALIFICATION.md) for stationary,
normal-set, disturbance and thermal-soak trials. Execute analogous live Android
trials with the shipping CameraX/MediaPipe/OpenCV pipeline. Emulator replay and
macOS FFI timings cannot fill physical rows.

Record tested app/model versions, public device families, sustained duration,
whole-pipeline p95, frame drop fraction, battery fraction per hour, maximum thermal
state and audited camera/sensor clock alignment. Store the original measurement
report and its SHA-256. Missing measurements stay null. Record physical capture and
consent explicitly; export the content-free matrix in the existing assessor schema.

Each matrix row must include `run_sha256`, the canonical JSON fingerprint of the
matching accuracy report's `run` object. This binds platform, backend, app revision,
analysis-policy versions and automatic capture mode. Use
`workout_release_readiness.fingerprint(score["run"])` for that value, and audit the
original physical report against that run before adding the row. A matching hash
is a binding declaration, not proof that the physical trial occurred. Legacy rows
without the binding remain readable but cannot qualify physical performance.

## Usability observations

Predeclare equipment distances, noise conditions, participant mix, trial count,
success criteria and permitted assistance before testing. Observe both iOS and
Android, including beginners, for these unaided tasks:

1. Position the camera and obtain a valid setup.
2. Recognize tracking start while on the equipment.
3. Recognize tracking loss, resume and actual set end.
4. Review a session and correct an exercise/attempt or set grouping.
5. Distinguish a completed cycle from a partial or interrupted observation.
6. Find goal progress and explain the next focus and its measurement limits.

Record distance/noise measurements, recognition delays, task failures and assistance.
Do not require an athlete to exceed their own chosen exercise capacity for a trial.
The reviewer evaluates the predeclared acceptance criteria and every supported
condition. Unobserved tasks/platforms or unresolved failures remain incomplete.

## Prepare and record the human decisions

The commands below use campaign-local aggregate files. `APP_REVISION` must be the
full 40-character revision of the evaluated app, not an unrelated current checkout.
The coaching specification must match that revision.

```sh
python3 scripts/workout_release_readiness.py prepare-reviews local/release.lock.json \
  --score local/ios-test-score.json --score local/android-test-score.json \
  --devices local/device-matrix.json \
  --app-revision "$APP_REVISION" \
  --coaching-spec docs/workout/COACHING_SPEC.md \
  --output local/reviews-v1.json
```

This creates three **pending** decisions with unchecked criteria. It never
overwrites an existing packet. Supply actual independent human findings in a
separately retained signed report, then record its digest, UTC date, pseudonymous
reviewer ID, matching role, human/independence declarations, reviewed context hashes
and completed checks. Reviewers decide approved/rejected; software cannot verify
credentials, consent, independence or whether a claimed trial occurred. The
campaign coordinator must audit those facts against the retained originals.

Each decision binds the app revision, coaching specification, frozen policy and
benchmark, both complete score reports and device matrix. The reports must use
the same evaluated revision, analysis-policy versions and frozen reference
sessions; each platform and each supported condition must independently pass.
A missing platform remains a failed gate. Scores from another build cannot be
approved under the requested app revision. Changed artifacts require a
new packet and review. Keep prior packets/reports in the audit archive. Status
changes must have a corresponding dated human decision; CI/model output cannot
be entered as a qualified coach or observed usability review.

```sh
python3 scripts/workout_release_readiness.py assess local/release.lock.json \
  --score local/ios-test-score.json --score local/android-test-score.json \
  --devices local/device-matrix.json \
  --app-revision "$APP_REVISION" \
  --coaching-spec docs/workout/COACHING_SPEC.md \
  --reviews local/reviews-v1.json --require-field-reviews \
  --output local/reviewed-readiness.json
```

Exit 0 means the declared measurement/device gates pass and all supplied human
attestations are complete and approved. Exit 1 retains the report and identifies
failed measurements or missing/incomplete/rejected reviews. Exit 2 rejects malformed
or mismatched evidence. JSON duplicate keys, future review dates, unsupported
conditions and attempts to overwrite immutable inputs are rejected.

All result paths must be fresh; existing reports and decision packets are never
overwritten. Use versioned output names for subsequent assessments. Review-required
flags are assessment-only and are rejected by freeze or packet preparation.

Assessment reports and review packets now use schema version 2. Version 1
packets require fresh review of the paired evidence; the tool never migrates an
old approval automatically. The frozen policy and benchmark formats are unchanged.
One `--score` remains usable for a partial diagnostic, with the missing platform
listed and qualification false. Report order does not affect review binding.

Without `--reviews` or `--require-field-reviews`, exit status reports only the
paired measurement/device gates; `remaining_external_reviews` stays open. A passing
reviewed assessment does not waive original CI failures or establish anatomical
validity. Close roadmap checkboxes only after the coordinator verifies actual
evidence, the declared gates pass and required native release checks are satisfied.
