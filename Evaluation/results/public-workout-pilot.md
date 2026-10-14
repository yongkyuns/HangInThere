# Public workout repetition pilot — 2026-10-14

The production Apple replay processed all 33 frozen original videos and verified
all 11,629 source frame timestamps. It accepted ten movements and recorded nine
returned completed cycles against 149 published repetitions. Automatic apparatus
setup was unavailable in 32 videos. No video has an exact completed count.

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

## Pose and acquisition support

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

## Reproducibility and pending hosted evaluation

The native local run is bound to app source
`4dca39647fc1a131a8bf498721febd8633f3f6c3`, macOS 15.5 on x86_64, Xcode 16.4 /
Swift 6.1.2, Apple Vision 2D request revision 1, and counter policy 8. It uses the
production selected-pull-up replay path and shipping common analysis bridge.
The compiled-source/framework/executable hashes are in the retained provenance.

The scorer is source `9ced9b19fb6bfadbf7a442ffa92c2e1aa4f697fc`. App source in
`ios/`, `android/`, `shared/` and `models/` is identical between these two commits;
the later change makes incomplete processing exit unsuccessfully after preserving
the score. Fresh acquisition from an empty cache reproduces the same manifest
hash. Final validation passes all 263 script tests, including the 34
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

[Hosted Apple and Android API29/API35 evaluation](https://github.com/yongkyuns/HangInThere/actions/runs/38575778868)
on `9ced9b1` is queued at this report's last inspection. There is no qualified
Android or hosted Apple result yet. Android uses production automatic exercise
acquisition; its lifecycle differs from the selected Apple replay path. No
cross-platform accuracy or device-performance parity claim follows from the
local result. The superseded queued dispatch on `4dca396` was cancelled and is
not validation evidence. Existing original-video and simulator failures remain
unwaived; the five independent field-validation goals remain open.
