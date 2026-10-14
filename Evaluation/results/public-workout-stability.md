# Apparatus source-clock dwell and stability diagnostics — 2026-10-14

App/harness checkpoint `fbd64d09d3a4f0c74daca91739d6b307b3541f7b`
subtracts same-timescale source ticks before conversion to seconds. The previous
policy could see a true 0.9-second hold as `0.8999999999999999` and require another
observation. An immutable `af4cf68` source build fails the authored 3000/3300/3600/
3900 ms sequence; the current production policy qualifies exactly 0.9 seconds and
rejects a hold one microsecond short. Required observations (four), maximum source
gap (0.8 seconds), geometry tolerances, live-start eligibility and count policy 8
remain unchanged. Invalid scales or signed subtraction overflow cannot qualify.
Mixed-timescale clocks retain the prior floating-point subtraction behavior.

Kotlin also copies caller-owned candidate/hand lists so later mutation cannot
rewrite an already observed baseline. Swift's value arrays retain the same
ownership guarantee. Counts/timing-only diagnostics describe the first failing
stability condition. Native Android acquisition histograms record only fresh
selected-apparatus observations, excluding cached states and replay rewind.
Earlier grip/edge selection rejection is outside that diagnostic denominator.

## Validation

| Check | Result |
| --- | --- |
| Production Swift core/policy sources | 239 tests pass |
| Kotlin Native common tests, macOS x86_64 | 305 tests pass |
| JVM common/parity tests, JDK 17 | 307 tests pass |
| Actual Swift/Kotlin setup snapshots, including diagnostics | 85 cases / 4,846 frames plus 17 geometry cases match |
| Actual Swift/Kotlin counter snapshots | 30 authored cases pass |
| Public dataset/collection contracts | 41 pass |
| Existing same-host core budget | Swift p95 0.0623 ms, Kotlin p95 0.0242 ms; both below 1 ms |
| Xcode regression-suite registration | Project lint/list and independent review pass |
| Shipping Apple analysis bridge and source normalization | Pass; existing host budgets pass |
| Independent implementation review | No actionable findings |

Shipping synthetic Apple FFI p95 is 1.956446 ms; grip transport/analysis p95 is
1.677946 ms and framing p95 is 0.110007 ms, within the existing 5 ms host budgets.
The [validation record](public-workout-stability.validation.json) retains exact
parity and bridge metrics. Host core timings exclude camera/model work and do not establish phone latency,
sustained inference, thermal/battery behavior or a platform speed ranking. Commit
`6d75db9` additionally registers the new Swift suite in the manually enumerated
Xcode test target; all app/model/native-evaluation/scorer sources remain identical
to `fbd64d0`. The older [identity report](public-workout-identity.md) now correctly
states 30 counter oracle cases, matching its retained local parity report; native
results and artifact files are unchanged.

## Local actual Apple audits

The same three previously selected public clips use the production Apple decoder,
pose estimator and contour detector with selected pull-up policy. All 844 frames
reach EOF, with 99 contour samples and zero contour extraction errors. Media
hashes match the unchanged frozen manifest
`2de38bc646463d0715aa7b103505d9daee928a5bd482417baec677b4f1c9ed36`.
These are exposed local diagnostics on Xcode 16.4 / Swift 6.1.2, not Auto/live
qualification, independently labelled equipment accuracy or a full-corpus score.

| Clip | Frames | Contour samples / nonempty | Fresh stability samples | Confirmed reference |
| --- | ---: | ---: | --- | --- |
| `countix_6cDE1YpzSaE` | 294 | 33 / 1 | None | No |
| `countix_88hPGnxh_Go` | 300 | 34 / 25 | None | No |
| `countix_Ace4EsUWyYc` | 250 | 32 / 21 | 3 candidate starts, 13 edge changes, 4 accumulation samples | No |

The first two clips fail before eligible edges reach stability. In the third,
the longest uninterrupted dwell is 0.64 seconds; repeated selected-edge changes
prevent confirmation. None yields accepted movements. There is **no demonstrated
acquisition gain in these audits**. Edge drift is an observed policy reset reason,
not independently adjudicated physical bar motion or proof that a tolerance should
be widened. Geometry/count rules and published labels are not changed to force
agreement. Actual Android histograms still require hosted native evaluation.

The retained [counts-only local audit](public-workout-stability-local.audit.json)
binds media, compiled source and executable hashes. The separate
[authored clock regression](public-workout-stability-clock.json) binds before/after
source checkpoints. Local raw geometry/pixels are excluded from these files.

## Hosted full corpus

[Native run 38651765115](https://github.com/yongkyuns/HangInThere/actions/runs/38651765115)
uses `fbd64d0` and the unchanged 33-video / 149-repetition corpus. Its outcome is
pending; no fresh full-corpus accuracy, Android acquisition gain or physical
performance parity is claimed. The previous completed
[native evaluation](public-workout-identity.md) still reports 10/149 returned cycles
on selected Apple replay and 0/149 on Android Auto, with 0/33 exact clips on each
platform. Its published-count accuracy failure remains unwaived. Existing Apple
simulator and original-capture accuracy failures, and all five field-validation
goals, remain open. PR #29 remains draft.
