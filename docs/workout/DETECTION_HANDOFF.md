# Detection handoff — 2026-10-21

**Automatic dip detection is unresolved. Work is paused for later pickup.**
The last production-source commit is
[`ca7e7d1`](https://github.com/yongkyuns/HangInThere/commit/ca7e7d11b388d319611e5ef41589d2b96f608aa0)
on `codex/android-parity`. This handoff changes documentation only; it does not
establish a new app, accuracy result or device qualification.

- [PR #29](https://github.com/yongkyuns/HangInThere/pull/29) remains draft, targeting `main`.
- [Issue #31](https://github.com/yongkyuns/HangInThere/issues/31) tracks the roadmap and remaining qualification.
- [Issue #24](https://github.com/yongkyuns/HangInThere/issues/24) records the older endpoint-timing problem. Its consumed clips are development evidence, not a new holdout.
- [Archived issue description](ROADMAP_CHECKPOINTS_2026-10-21.md) preserves earlier checkpoints. Historical “active,” “next,” and CI statements there are superseded by this handoff.

## What is implemented

The branch contains native iOS and Android clients, shared Kotlin analysis and
application policy, automatic set boundaries, phase/hold metrics, practice versus
completed-rep evidence, progressive history/review, correction ledgers, comparable
progress, goals and coaching projections, and bounded durable storage/backup.
See the [implementation map](ROADMAP_IMPLEMENTATION.md) for module ownership.
Implementation does not establish accurate automatic exercise tracking.

Capture and pose inference remain native: Apple Vision on iOS and MediaPipe on
Android. Both apps use shared analysis/application logic. The established Swift
counter/setup is still the policy-v8 reference; shared Android policies and native
adapters require parity checks. This is not one shared vision model.

Recent committed fixes preserve independent wrist matches alongside unrelated
hands (`e465ff6`) and retain observed rail subarcs before contact pruning
(`ca7e7d1`). Source-bound wrist freshness and recorded-review observation guidance
are also committed. These fixes have regression coverage but have not recovered
the missed public-video dips.

## Last production-source validation

These results apply to production source `ca7e7d1`, not to a newly built app at
the documentation handoff commit. The native replay verifies its PR merge source
against the production source pins.

| Scope | Result | Evidence and limit |
| --- | --- | --- |
| Original Apple native temporal replay | **1/26 matched, 25 missed, 0 extra; accuracy failed** | [Run 39529716205](https://github.com/yongkyuns/HangInThere/actions/runs/39529716205). Pull-up control 1/1; rear dips 0/10, side 0/6, controlled 0/9. All three dip clips report `setupUnavailable`. Input integrity passes. |
| Original Android capture | **0/10 reference events in each fixed-edge and Auto evaluation, on API 29 and API 35; accuracy failed** | [Run 39529716271](https://github.com/yongkyuns/HangInThere/actions/runs/39529716271). Nine controlled dips and one smoke pull-up; a separate positive-margin pull-up passes 1/1 in both modes. |
| Shared unit tests and Swift bridge | **742 JVM + 719 Kotlin/Native Arm64 tests pass; 22 wrist-association ABI cases pass** | [Run 39529716207](https://github.com/yongkyuns/HangInThere/actions/runs/39529716207). Engineering evidence, not end-to-end accuracy. |
| Android mechanics | 34 host tests pass; 75 instrumentation cases per API, four skips, no failures/errors | Same Android run. Decoder, HDR, shared parity, identical-input checks and Swift replay of Android observations pass. |
| Cached observation/UI checks | 1,686 dip observations agree across JVM/Native guidance; 18 narrow/wide progress render checks pass | Cached observation parity and presentation only; no fresh SDK accuracy claim. |
| Core performance | Same-host p95: JVM 2.08 µs; Swift 4.54 µs | Counter only; capture, inference and sustained physical-device workloads are excluded. |

Original references, matching windows and fatal accuracy gates remain unchanged.
Passing mechanics, cached parity or unit tests does not waive the failed source
accuracy checks. Physical-phone throughput, thermal/battery behavior, robust
crowded-scene ownership and occluded-hand correctness remain unqualified. No
merge or new phone-install qualification is claimed.

## Resume with this bounded task

1. **Locate the first missing automatic-setup prerequisite in the production
   replay.** Follow source-time evidence through athlete/hand selection, observed
   rail proposals, apparatus acquisition, camera stability and readiness. Start
   with the controlled public dip clip; compare failing and valid neighboring
   observations. `setupUnavailable` identifies the failed stage, not its root
   cause. Camera angle, occlusion and competing people are hypotheses to test.
2. Make the smallest supported fix in shared policy where appropriate, retaining
   native adapter contracts. Add a regression that fails before the fix and
   checks the actual setup/count behavior. Do not bypass ownership, apparatus,
   camera or completed-return requirements to manufacture a count.
3. Run an end-to-end before/after comparison with the **unchanged public source
   references** and the authorized local four-dip recording. That recording has
   four complete dips, no intentional unfinished attempt and a clap for sync;
   it is exposed development data. Recheck incomplete attempts, holds, source
   gaps, camera movement and multi-person negatives. Missing intervals must not
   earn hold time or completed-rep analytics.
4. Run shared/Swift parity and the original Android API29/API35 capture gates
   in CI at the exact source revision. Keep native-source accuracy, cached
   policy parity and platform mechanics reported separately. A cache-only
   improvement is insufficient. Preserve fatal missed/extra/setup failures.
5. Publish the measured before/after and remaining unsupported conditions before
   removing draft status or calling the app ready. Qualify physical-device
   performance separately; emulator/host results cannot confirm it.

The next checkpoint should contain a reproduced setup failure, its specific
cause, a tested fix (or a precise unresolved dependency), and original-source
results. Avoid another broad model/threshold sweep without that evidence.

## Start here in the code and CI

| Responsibility | Entry points |
| --- | --- |
| Shared runtime and setup | [`WorkoutRuntime.kt`](../../shared/src/commonMain/kotlin/dev/hanginthere/core/WorkoutRuntime.kt), [`AutomaticDipRails.kt`](../../shared/src/commonMain/kotlin/dev/hanginthere/core/AutomaticDipRails.kt), [`AutomaticDipBar.kt`](../../shared/src/commonMain/kotlin/dev/hanginthere/core/AutomaticDipBar.kt) |
| Observed rail and wrist correspondence | [`ObservedRailFitter.kt`](../../shared/src/commonMain/kotlin/dev/hanginthere/core/ObservedRailFitter.kt), [`ObservedWristAssociation.kt`](../../shared/src/commonMain/kotlin/dev/hanginthere/core/ObservedWristAssociation.kt) |
| Native reference and pose adapters | [`AutomaticDipRails.swift`](../../ios/HangInThere/Analysis/AutomaticDipRails.swift), [`AutomaticDipBar.swift`](../../ios/HangInThere/Analysis/AutomaticDipBar.swift), [`VisionPoseEstimator.swift`](../../ios/HangInThere/Pose/VisionPoseEstimator.swift), [Android `PoseEstimator.kt`](../../android/app/src/main/java/dev/hanginthere/android/vision/PoseEstimator.kt) |
| Original-source replay and fatal scoring | [`temporal.yml`](../../.github/workflows/temporal.yml), [`temporal_native_replay.py`](../../scripts/temporal_native_replay.py). Keep the selected-exercise integrity and strict accuracy jobs separate; strict scoring uses `--require-exact-events`. |
| Android qualification | [`android.yml`](../../.github/workflows/android.yml), [architecture and evaluation scope](../ANDROID.md) |
| Shared analysis/FFI and Apple mechanics | [`workout-analysis.yml`](../../.github/workflows/workout-analysis.yml), [`ci.yml`](../../.github/workflows/ci.yml), [`test-core.sh`](../../scripts/test-core.sh), [`test-workout-bridge.sh`](../../scripts/test-workout-bridge.sh) |

Use the checked-in workflow commands and pinned dataset preparation rather than
inventing a new evaluation protocol. On a fresh checkout, fetch/switch to
`codex/android-parity` and read this document before running builds or experiments.

## Research retained for context, not promotion

No experimental counter or learned tracker below is integrated into the app or
qualified by the original native/Android accuracy gates. These results should
prevent repeated dead ends, not become a replacement acceptance protocol.

| Experiment | Finding | Decision |
| --- | --- | --- |
| Fixed-source-scale endpoint span, V5 | Best cached public dip result: **13 matched / 25, 12 missed, 0 extra**. | Incomplete, unintegrated. Fails genuine short complete-cycle controls across 0.25/0.35-second gaps. |
| Joint-availability variant, V6 | **12 matched / 25, 13 missed, 0 extra**. | Rejected regression. Same short-cycle limitation. |
| Original 55 authored legacy assertions under the observed-return clock experiment | 55 counts agree; 54 full assertions pass. `MissingEndpointNeedsFreshDwell` shifts 2.80→2.75 s; nine instrumented clock cases pass. | A documented experimental timing change, not an all-green production suite. Preserve the original oracle. |
| Native crop/tone, apparatus patches, geometry/phase/model alternatives | Earlier capability screens did not establish an acceptable measurement/correspondence route. | Read the local capability ledger before repeating a rejected family; no general impossibility claim. |
| Causal BootsTAPIR on six controlled dip frames | Two-region tracker/mask/current-pose checks pass 5/6. All 96 material-point identities remain unverified on weak-texture clothing. | Neither tracked points nor current pose proposals have independent anatomical truth. No counter override. |
| Causal BootsTAPIR on the first TAPVid DAVIS record (`goat`) | 90 frames, five tracks; 65/445 post-seed points have error ≥4 px on the 256-pixel evaluation scale, two ≥8 px. Worst error 8.995 px despite estimated visibility 0.9803. All reference points are visible. | The strict 4-pixel correspondence screen fails. High visibility probability is not a localization bound. One sequence does not qualify human/occluded tracking or workout events. |
| Isolated single-frame ONNX sampler | Eight shape cases and four invalid-input guards pass; maximum ORT difference 2.384×10⁻⁷. Graph is 304 bytes. | Operator parity only. No full-model export, mobile acceleration or device performance qualification. Tracker costs about 1.67 s/frame on the cached x86 CPU setup. |

A possible fixed-raster head/hip foreground-change witness remains a **parked,
untested hypothesis**. Moving pose windows alone must produce no motion evidence;
camera motion, segmentation flicker and unrelated image changes are confounders.
It has not established joint correctness or resolved automatic setup. Do not
resume it ahead of the production failure investigation by default.

## What a remote checkout will and will not contain

Production sources, tests, workflow definitions, this summary and the archived
issue description are committed. Private recordings, downloaded datasets,
checkpoints, environments, raw observations and research scripts under ignored
`build/` and `Data/` remain on this Mac. They are not remotely reproducible from
this handoff alone. Use public workflow preparation for a fresh source replay;
private-video validation requires the authorized local assets or a new transfer.

The following are local recovery pointers, not repository links or CI inputs:

| Local path | Purpose |
| --- | --- |
| `build/dip-learned-track-feasibility-goal/validation-packet.json` | Last canonical experiment packet; source `ca7e7d1`. Its older active-status field is superseded by this paused handoff. SHA-256 `6c5a434d7e241aa8611fd403bda32f37ca266b7e5de711d9491d23ff407e0ce8`. |
| `build/dip-learned-track-feasibility-goal/capability-ledger.json` | 65-entry capability ledger. SHA-256 `e0effbde0a2e9130beb772eb345d22cb320ccd9943fd7393444206b2ecf954d2`. |
| `build/dip-fixed-span-goal/validation-packet.json` | V5 result and protocol pins; implementation in `engine_v3.py` in that directory. |
| `build/dip-joint-availability-goal/validation-packet.json` | Rejected V6 result. |
| `build/dip-tracker-correspondence-goal/result-audit.json` and `independent-review.json` | Latest completed tracker/sampler audit. Audit SHA-256 `b065493bf4e73f71a2ba291bc4d97c22d3f4e5be45d2409c0cdf54fde7fb410f`. |
| `build/dip-tracker-correspondence-goal/tracker-plan.json` and `tracker-observations.jsonl` | Latest frozen tracker plan SHA-256 `6d6de732888075ca600066a9caf03d90d2b3104013e7c7e725ce584ff865a1d8`; raw observations SHA-256 `b7171294259154cf40ee28c80d982b9f5a833ca7a9b6a701dafe25626613d4af`. |
| `build/phone-two-view-2026-10-18/continuation.json` | Local private-recording intake/continuation context. Development evidence only. |

The last canonical packet predates the completed tracker-correspondence audit;
there is no finalized `build/dip-tracker-correspondence-goal/validation-packet.json`.
Read its actual terminal receipts and reviews rather than assuming the older
continuation pointers contain those findings.

Keep work lean: reuse existing dependencies, serialize expensive commands, use
at most two build/test workers, and stop task-owned processes when finished.
About 58 GiB was free at handoff. Do not push media, model weights or environments,
and do not read the private report key. No Apple/vendor reports are requested.
Leave unrelated local editor/lint files untouched.
