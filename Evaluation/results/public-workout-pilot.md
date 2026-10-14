# Public workout repetition pilot — 2026-10-14

All three final-source hosted native evaluations process the same 33 frozen
original videos and verify all 11,629 source frame timestamps. Hosted Apple
records ten returned cycles against 149 published repetitions; Android API29 and
API35 each record zero. Setup is unavailable in 32/33 Apple clips and all Android
clips. No native evaluation has a video with an exact completed count.

| Native run | Accepted movements | Returned completed cycles / published reps | Exact completed counts | Setup unavailable |
| --- | ---: | ---: | ---: | ---: |
| Hosted Apple, final source | 11 | 10/149 | 0/33 | 32/33 |
| Android API29, final source | 0 | 0/149 | 0/33 | 33/33 |
| Android API35, final source | 0 | 0/149 | 0/33 | 33/33 |
| Local Apple baseline | 10 | 9/149 | 0/33 | 32/33 |

These are published repetition diagnostics, not strict-form, athlete-holdout,
coaching, phase/hold or physical-device qualification. The full
[dataset protocol](../PUBLIC_WORKOUT_DATASETS.md) describes selection, source pins,
label conventions, split assignments and unresolved video-use/exposure questions.
Every requested case remains in the denominator.

## Native local Apple result

| Dataset | Videos | Published reps | Accepted movements | Returned completed cycles | Exact completed counts |
| --- | ---: | ---: | ---: | ---: | ---: |
| Countix validation | 29 | 101 | 0 | 0 | 0/29 |
| RepCount-A bounded sample | 4 | 48 | 10 | 9 | 0/4 |
| Total | 33 | 149 | 10 | 9 | 0/33 |

Mean absolute completed-count error is 4.2424 per video. All sources reach EOF
with exact expected frame counts and source clocks; no case fails or is
interrupted. A successfully decoded source with unavailable setup remains a
count miss. Counts alone are not event precision/recall.

The sole acquired video, `repcount_stu1_34`, has 13 published cycles, ten accepted
movements and nine returned cycles. All nine returned events match a published
cycle end within the predeclared 0.5-second tolerance; four reference events are
missed and none is unmatched. Mean matched timing error is 0.1689 seconds and p95
is 0.4 seconds. This is one video's boundary diagnostic; the published boundaries
have not been adjudicated against the app's return convention, and this is not a
general precision, form or phase-accuracy result. The extra accepted movement
without an observed return does not enter completed-cycle analytics.

## Local pose and acquisition support

These counts observe unchanged production Vision output once per unique source
timestamp. Acquisition/rewind passes do not inflate the denominator. “Available”
is the app's arm-measurement policy, not an independently labelled correct pose.
Apparatus search support means the production search prerequisites exist; it does
not establish a detected or confirmed bar.

| Dataset | Source frames | Single-person frames | Both arms available | Pull-up search support |
| --- | ---: | ---: | ---: | ---: |
| Countix | 7,116 | 4,789 | 3,234 | 1,476 |
| RepCount-A | 4,513 | 3,198 | 2,517 | 1,203 |
| Total | 11,629 | 7,987 | 5,751 | 2,679 |

The large loss of setup coverage precedes count acceptance. Pose support,
apparatus evidence and recording-condition compatibility are the first areas to
investigate; these aggregate counters do not isolate a single cause. Sampled
source frames include rings, low light, side views and arm occlusion. Those visual
observations are not frozen supported-condition annotations and do not remove
cases from scoring. Rings and a straight-bar strict-form exercise are not
interchangeable label contracts.

## Local baseline reproducibility

The native local run is bound to app source
`4dca39647fc1a131a8bf498721febd8633f3f6c3`, macOS 15.5 on x86_64, Xcode 16.4 /
Swift 6.1.2, Apple Vision 2D request revision 1, and counter policy 8. It uses the
production selected-pull-up replay path and shipping common analysis bridge.
The compiled-source/framework/executable hashes are in the retained provenance.

The scorer is source `9ced9b19fb6bfadbf7a442ffa92c2e1aa4f697fc`. App source in
`ios/`, `android/`, `shared/` and `models/` is identical between these two commits;
the later change makes incomplete processing exit unsuccessfully after preserving
the score. Fresh acquisition from an empty cache reproduces the same manifest
hash. Validation at that checkpoint passes all 263 script tests, including the 34
public-dataset, collection and processing-exit tests. Independent review is complete.

Retained immutable local outputs, with no source pixels or raw landmarks:

- [Native result](public-workout-pilot-apple.native.json), SHA-256
  `92d4257614dc7104ad189c452f78a8b4b3caf8433e03b3053d2aee48d0a300d4`.
- [Build provenance](public-workout-pilot-apple.provenance.json), SHA-256
  `b7557a7457a01667c8a527493de43df964c38e602e2e8fdd3b4370c0bed631a8`.
- [Independent score](public-workout-pilot-apple.score.json), SHA-256
  `0edcc6deadaa5a7d7eb9de8d569efabb43e7342f26ce73a445a4757759e98252`.
- Prepared source-clock manifest SHA-256:
  `2de38bc646463d0715aa7b103505d9daee928a5bd482417baec677b4f1c9ed36`.

## Initial hosted run

[Hosted run 38575778868](https://github.com/yongkyuns/HangInThere/actions/runs/38575778868)
on `9ced9b1` passes all 34 then-current contracts and builds both Android APKs.
API35 completes all 33 original videos and verifies all 11,629 source timestamps
with the identical manifest hash. It accepts zero movements and records zero
returned cycles against 149 published repetitions: zero exact clips and completed
count MAE 4.5152. Every case reports `setupUnavailable`, with 19
`framingIncomplete` and 14 `exerciseIdentityNeeded` outcomes. No native errors or
interruptions are reported and no counting pass starts. Successful processing
does not make this an accuracy pass.

The API35 artifact archive (ID `11903833603`) was verified against GitHub's
SHA-256 `6a33d5fce5d57b6b997e05bf951a6934a0cafd3e205044751593a5a23b9a1796`,
and its score was independently recomputed from the native report and manifest.
Retained [native result](public-workout-pilot-android-api35-initial.native.json),
[score](public-workout-pilot-android-api35-initial.score.json) and
[prepared model pin](public-workout-pilot-android-api35-initial.model.json) contain
no source pixels or raw landmarks. The model is the pinned production MediaPipe
Heavy `float16/1`, SHA-256
`64437af838a65d18e5ba7a0d39b465540069bc8aae8308de3e318aad31fcbc7b`.

API29 builds successfully, but Google system-image installation fails with
`Error on ZipFile unknown archive` before an emulator or test starts. Its
preparation artifact (ID `11902906985`, archive SHA-256
`ef7c46ba4c25aa5d68960d3a7f60df7fc5c9faca933cba80a5f4afd084a61099`)
has the same input manifest and model pin, but no native result. This is an
infrastructure failure, not API29 accuracy evidence.

The hosted Apple ARM VM compiles the production replay and completes 31 clips
before its 45-minute job timeout interrupts the next clip. Its artifact (ID
`11905220381`, verified archive SHA-256
`779d8d7875cb6393c0178903ef7587a837100b3487d41ade397ca4fadfe731c3`)
contains preparation metadata, not a completed native report. The whole batch
therefore remains unqualified on that attempt. The aggregate accuracy job is
skipped because native jobs failed; the workflow remains red.

## Final sharded pipeline

Source `ce0e717f7a1dfc3b6657a8d9501e457d4a190dd0` splits Apple execution into two
deterministic original-manifest shards and verifies every case exactly once
before combining measurements. Replay operations allow 900 wall-clock seconds
per clip on slow VMs; loading keeps its 300-second watchdog. Source frames,
EOF/PTS integrity, native counting rules, accuracy tolerances and existing app
performance gates are unchanged. This is an operational workload correction,
not physical-device performance qualification. App/runtime and Android runner
code are byte-identical to `9ced9b1`.

All 270 script tests pass, including 41 public-dataset/collection/processing-exit
and shard contracts. Independent review passes. A real native Apple shard smoke
test compiles and verifies its assignment, source clocks, source revision and
build provenance. [Final-source hosted evaluation](https://github.com/yongkyuns/HangInThere/actions/runs/38588197423)
has completed both Android jobs, both Apple workers and hosted Apple aggregation.
All three native scores verify complete input processing; all three fail exact
published counts. The combined hosted accuracy gate verifies exactly three
scores, the shared manifest hash and shared source revision, then fails for the
published-count mismatches. The workflow completes with failure at 17:08 UTC:
all 41 contracts, both Apple workers, Apple aggregation and both Android native
jobs pass; only accuracy fails. No processing or accuracy failure is waived.

Both Apple worker archives were checked against GitHub's digests; their native
reports match their build-provenance digests and the original input manifest.
Independent full-corpus aggregation verifies all 33 assignments exactly once and
all 11,629 source timestamps. It records 11 accepted movements and ten returned
cycles, with unavailable setup in 32 videos and zero exact clips. Completed-count
MAE is 4.2121. Countix contributes 0/101; RepCount contributes 10/48 returned
cycles. In the sole acquired clip (`repcount_stu1_34`), nine cycle ends match the
published labels, four are missed and one returned event is unmatched at the
declared tolerance. This is a boundary diagnostic, not an independently
adjudicated form or cycle-convention verdict.

The hosted Apple workers use Swift 6.2.4 on arm64, unlike the local x86_64 / Swift
6.1.2 baseline. The one additional accepted/returned event does not establish an
algorithm improvement or reproducibility across native runtimes. App source and
input bytes remain unchanged. Observed pose support is 7,981 single-person,
5,774 both-arm and 2,696 pull-up-search frames out of 11,629 unique source frames;
these are availability-policy counts, not labelled pose accuracy.

The hosted combined report and score match independently recomputed outputs
byte-for-byte. Retained [combined native report](public-workout-pilot-apple-final.native.json)
has SHA-256 `2b75cec287b5c9179c6a4b67832d515d31f21714a72f9f84bd89d32068b907df`;
the [score](public-workout-pilot-apple-final.score.json) has SHA-256
`f0d7feb9ba0cdf899a826b81b20b11754a35728b3f71ec08c0c69cb50d8a5a1e`.
Both are bound to the original manifest and `ce0e717` source checkpoint. Worker
reports and their matching build provenance remain independently inspectable:
[worker 0 native](public-workout-pilot-apple-final-shard0.native.json),
[worker 0 provenance](public-workout-pilot-apple-final-shard0.provenance.json),
[worker 1 native](public-workout-pilot-apple-final-shard1.native.json),
[worker 1 provenance](public-workout-pilot-apple-final-shard1.provenance.json).

Verified GitHub artifact archives:

- Worker 0: ID `11910190105`, SHA-256
  `f4231e69a886e656ea9b64e9a8a29a0f2ba0b3f2e258d274fcc49e02fe793951`.
- Worker 1: ID `11909053613`, SHA-256
  `b31ad2768d6987a850c4bc37d1fc863a6f9e4cf731d96c052a5f069fbb4967bc`.
- Combined: ID `11913000904`, SHA-256
  `ba12882c0e5525dd8854a61b2bfa5853948d4ffe531c6f9b3b558f0155d165e2`.

API35 reproduces the initial accuracy result on the final source checkpoint:
all 33 videos and all 11,629 source timestamps are verified, with zero accepted
movements, zero returned cycles, zero exact clips and completed-count MAE 4.5152.
All 33 cases have unavailable setup (19 `framingIncomplete`, 14
`exerciseIdentityNeeded`); no native errors or interruptions occur, and no
counting pass starts. Its native job succeeds for complete processing; this
remains an accuracy failure.

Artifact archive ID `11909985591` was verified against GitHub's SHA-256
`fc8e313212f06bbf198567dbf9a39d90f9f6bd57ae6572fca243aa1bedbad66b`.
The score was independently recomputed from the exact native report and original
manifest. Retained immutable final-source API35 outputs:

- [Native result](public-workout-pilot-android-api35-final.native.json), SHA-256
  `e10ed96cea080031e43dde78ed13d56948918c73ec034b21e47e336d2ccd05b4`.
- [Score](public-workout-pilot-android-api35-final.score.json), SHA-256
  `ae033b5df4d104eec8a4de514bb449c96d6968238826200b8efc6d99db179d7e`.
- [Model pin](public-workout-pilot-android-api35-final.model.json), SHA-256
  `6d5bf7ad52a8ab17e04410bf491779a0b2053292203ad953312ec2a05f0a89f2`.

API29 now installs its emulator image and executes the real native test. It
processes all 33 clips and all 11,629 source timestamps, with zero accepted or
returned events, zero exact clips and completed-count MAE 4.5152. All 33 cases
have unavailable setup, with the same 19 `framingIncomplete` and 14
`exerciseIdentityNeeded` outcomes, no native errors, no interruptions and no
counting frames. Its source revision, manifest and model pins match API35.
Their complete diagnostic scores are byte-identical; this establishes agreement
on this failing batch, not accuracy or physical-device performance parity. The
initial corrupt-image installation remains retained as a prior infrastructure
failure; a successful fresh retry does not establish a causal repair.

API29 archive ID `11912936850` was verified against GitHub's SHA-256
`9e6c3e6355d8c19b808a532186056112c2ebc4fdafda1c8dc0a670f83f06cb51`.
Its score was independently recomputed. Retained immutable final-source outputs:

- [Native result](public-workout-pilot-android-api29-final.native.json), SHA-256
  `333e79f1bf3fd1c1ac4b65d00e72cee4e13c05f6607a56d26aff2e18a7d07483`.
- [Score](public-workout-pilot-android-api29-final.score.json), SHA-256
  `ae033b5df4d104eec8a4de514bb449c96d6968238826200b8efc6d99db179d7e`.
- [Model pin](public-workout-pilot-android-api29-final.model.json), SHA-256
  `6d5bf7ad52a8ab17e04410bf491779a0b2053292203ad953312ec2a05f0a89f2`.

The result-only commit leaves evaluated app and harness code unchanged from
`ce0e717`. New native measurements, artifact digests and score recomputation are
bound to that code checkpoint, rather than to the later evidence commit.

Android uses production automatic exercise acquisition; its lifecycle differs
from the selected Apple replay path. These results do not isolate a vision-model
winner or establish cross-platform accuracy or device-performance parity. The
superseded queued dispatch on `4dca396` was cancelled and is not validation
evidence. Existing original-video and simulator failures remain unwaived; the
five independent field-validation goals remain open.
