# Qualified identity during pull-up motion — 2026-10-14

Source `e73e57c827d5ba68c7668438ecf702a0ad9b0418` fixes an Auto setup reset:
after the initial full hang qualified exercise identity, arm flexion discarded
that identity and restarted apparatus acquisition. Swift and Kotlin now retain
qualified pull-up identity only while fresh bilateral grip/apparatus evidence,
image size and source timing remain consistent. Initial 0.6-second/four-observation
hang qualification, independently observed apparatus, camera/scene gates, a fresh
full hang for live set start and count policy 8 remain required. Lost grip,
confidence, person ambiguity or invalid source context still clears unlocked
identity. Dip qualification and incomplete-rep exclusion are unchanged.

Android's native evaluator additionally reports counts-only evidence from the
first acquisition pass. It observes actual bilateral arm availability, resolved
exercise, contour sampling/evidence/errors, confirmed-reference frames, setup
issues and acquisition states. Counting-pass rewind does not inflate this
separate denominator; these counts are not independently labelled pose accuracy.
No source pixels, landmarks or raw errors are published.

## Local validation

| Check | Result |
| --- | --- |
| Exact Swift core/policy sources | 234 tests pass |
| Kotlin Native common tests, macOS x86_64 | 300 tests pass |
| JVM common and parity tests, JDK 17 | 302 tests pass |
| Actual Swift-to-Kotlin setup snapshots | 83 cases plus 17 geometry cases pass frame by frame |
| Actual Swift-to-Kotlin counter oracle | 30 cases pass |
| Shipping Apple analysis bridge and source normalization | Pass |
| Python script contracts | All 270 pass after the fixture repair below |
| Independent implementation review | No actionable issues |

The existing host core performance budget passes: Swift p95 0.0818 ms and
Kotlin p95 0.0071 ms in this same-host run. Shipping synthetic Apple FFI p95 is
2.947637 ms; grip transport/analysis p95 is 4.435216 ms and framing p95 is
0.35391 ms, all within the existing 5 ms host budgets. These measurements
exclude camera/model work and do not qualify phone latency, sustained inference,
thermal or battery behavior or establish a platform speed ranking.

The initial broad script run reports a genuine cancellation-test fixture race:
its parent observed the PID marker after creation but before PID content was
written. The fixture now closes a temporary file before same-directory atomic
rename. Startup/cancellation deadlines, exit codes and kill/reap assertions remain
unchanged. Thirty stress iterations verify sixty actual SIGTERM/SIGINT
cancellations; the complete 270-test rerun passes. The repair is commit
`6bd56aef16bc183e8a13c7651615493b5d4f0854`; app, model, native harness and
scorer files are byte-identical to the evaluated `e73e57c` checkpoint.

## Frozen native evaluation

[Hosted run 38619298337](https://github.com/yongkyuns/HangInThere/actions/runs/38619298337)
is bound to `e73e57c`. The unchanged manifest contains 33 original clips,
149 published repetitions and 11,629 source frames. Manifest SHA-256 remains
`2de38bc646463d0715aa7b103505d9daee928a5bd482417baec677b4f1c9ed36`.

All four native workers complete successfully, and all 41 hosted dataset/collection
contracts pass. Independent Apple aggregation and independent scoring of both
Android native reports verify every case and all source clocks. Hosted Apple
aggregation also passes and its native report/score match independent outputs
byte-for-byte. The final gate verifies exactly three reports, their shared manifest
and app source revision, then fails its published-count assertion. The workflow
finishes with failure; only its accuracy job fails. No input-processing or
accuracy failure is waived.

| Native evaluation | Accepted movements | Returned cycles / published reps | Exact completed counts | Setup unavailable |
| --- | ---: | ---: | ---: | ---: |
| Apple selected pull-up replay | 11 | 10/149 | 0/33 | 32/33 |
| Android API29 Auto replay | 0 | 0/149 | 0/33 | 33/33 |
| Android API35 Auto replay | 0 | 0/149 | 0/33 | 33/33 |

There is **no measured public-batch count improvement** relative to the retained
`ce0e717` [native baseline](public-workout-pilot.md). Apple's per-clip statuses,
accepted/returned event times and source-frame coverage match that hosted baseline.
It uses selected pull-up replay, so it does not exercise the Auto identity change;
actual Swift policy tests and Swift-to-Kotlin snapshot parity verify that change.
Android executes Auto, but neither run confirms apparatus or begins counting.
The regression fix addresses qualified identity during flexion, not all causes of
initial identity, contour-selection or reference-stability failure. This batch is
exposed diagnostic data, not an untouched evaluation group.

Apple's 29 Countix clips contribute 0/101 returned cycles; the four RepCount-A
clips contribute 10/48. Completed-count MAE is 4.2121 per clip. In the sole acquired
clip, `repcount_stu1_34`, nine returned events match a published cycle end within
the predeclared 0.5-second window, four reference events are missed and one
returned event is unmatched. Mean matched timing error is 0.1689 seconds and p95
is 0.4 seconds. These are unadjudicated boundary diagnostics, not general form,
phase or event-accuracy qualification. Unique-source production pose availability
matches the hosted baseline: 7,981 single-person frames, 5,774 both-arm frames and
2,696 pull-up-search frames out of 11,629 source frames.

## Android acquisition evidence

Both APIs have identical per-clip acquisition evidence. Each first pass processes
all 11,629 frames; no rewind/counting pass starts. Their full diagnostic scores
agree exactly, with completed-count MAE 4.5152 per clip. All sources reach EOF
and no native errors or interruptions occur. Agreement on this failing batch is
not accuracy or physical-device performance parity.

| First-pass observation | Frames / samples per API |
| --- | ---: |
| Usable bilateral arm chains | 6,231 frames |
| Resolved pull-up identity | 678 frames |
| Resolved dip identity | 12 frames |
| Actual contour sampling | 586 samples |
| Nonempty contour evidence | 544 samples |
| Contour extraction errors | 0 samples |
| Confirmed-reference frames | 0 frames |

Eight clips have some resolved exercise frames. Candidate presence is not a
correct bar annotation; a `holding` acquisition state is not a valid rep or a
confirmed reference. These newly retained observations have no matching historical
stage histogram, so they cannot establish an identity-coverage gain from the fix.
Diagnostics run after the measured native processing interval; full harness wall
time includes them.

| Setup issue | First-pass frames per API |
| --- | ---: |
| Framing incomplete | 5,398 |
| Exercise identity needed | 5,541 |
| Bar reference needed | 422 |
| Pull-up bar unclear | 256 |
| Dip rails unclear | 12 |

Acquisition state snapshots report 8,064 `lookingForGrip`, 1,752 `holding`,
1,527 `ambiguous` and 286 `findingEdge` frames; none report `locked`.
The final per-clip issue remains 19 framing-incomplete and 14 identity-needed
outcomes. These are production policy states over the whole first pass, not
independently labelled pose, exercise or equipment accuracy.

## Evidence and remaining work

Artifact archives are checked against GitHub's SHA-256 digests before extraction.
Both Apple reports match their retained build-provenance hashes, source revision,
manifest and all 34 compiled-source file hashes. Native reports, scores, model pins
and worker provenance are retained under the `public-workout-identity-` prefix.
The [artifact and report digest record](public-workout-identity.artifacts.json)
binds all five verified GitHub archives and all twelve retained JSON outputs.
Previous reports remain immutable. The production Android MediaPipe Heavy
`float16/1` model remains SHA-256
`64437af838a65d18e5ba7a0d39b465540069bc8aae8308de3e318aad31fcbc7b`.

Retained outputs:

- Apple [combined native report](public-workout-identity-apple.native.json) and
  [score](public-workout-identity-apple.score.json).
- Apple workers [0 native](public-workout-identity-apple-shard0.native.json) /
  [0 provenance](public-workout-identity-apple-shard0.provenance.json) and
  [1 native](public-workout-identity-apple-shard1.native.json) /
  [1 provenance](public-workout-identity-apple-shard1.provenance.json).
- API29 [native report](public-workout-identity-android-api29.native.json),
  [score](public-workout-identity-android-api29.score.json) and
  [model pin](public-workout-identity-android-api29.model.json).
- API35 [native report](public-workout-identity-android-api35.native.json),
  [score](public-workout-identity-android-api35.score.json) and
  [model pin](public-workout-identity-android-api35.model.json).

The [iOS P0 workflow](https://github.com/yongkyuns/HangInThere/actions/runs/38619269146)
passes native macOS real-Vision/replay integration but still fails simulator Vision
with missing `cnn_human_pose.espresso.weights` / Vision Code 9 and mechanics with
the same eight pinned H.264 pixel-color assertions. Those failures are unwaived.
The [Android engineering workflow](https://github.com/yongkyuns/HangInThere/actions/runs/38619262553)
passes both native API jobs, software HDR contracts and Swift/Kotlin policy parity;
both Swift replays of native Android observations and the identical-input gate
also pass. The workflow completes with failure only in the two original-capture
accuracy gates; those failures remain unwaived.

Next investigation should distinguish initial qualification, candidate association
and reference-stability resets on actual source observations, with negative
clutter/occlusion controls. Candidate counts alone do not justify relaxing geometry
or count rules. Independently adjudicated supported-condition labels and a fresh
untouched evaluation group remain necessary before claiming field accuracy.

Published labels are not adjudicated against strict-form/returned-cycle criteria.
The corpus has no annotated parallel dips, grip classes, holds or set boundaries.
All five field-validation goals remain open; original-video accuracy and Apple
simulator failures remain unwaived. See the [dataset protocol](../PUBLIC_WORKOUT_DATASETS.md).
