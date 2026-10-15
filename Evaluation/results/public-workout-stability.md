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

## Validation at `fbd64d0`

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
agreement. Completed hosted Android histograms are reported below.

The retained [counts-only local audit](public-workout-stability-local.audit.json)
binds media, compiled source and executable hashes. The separate
[authored clock regression](public-workout-stability-clock.json) binds before/after
source checkpoints. Local raw geometry/pixels are excluded from these files.

## Hosted full corpus

[Native run 38651765115](https://github.com/yongkyuns/HangInThere/actions/runs/38651765115)
uses `fbd64d0` and the unchanged 33-video / 149-repetition corpus. Contracts, both
Apple native shards, their merge, and both Android API jobs completed successfully.
Independent merging/scoring of their collected reports matches the hosted outputs
byte for byte. All inputs reach EOF with verified source clocks (11,629 source
frames per platform); successful collection does not mean correct counting.

| Actual native replay | Accepted events | Completed cycles / 149 | Exact clips / 33 | Setup unavailable |
| --- | ---: | ---: | ---: | ---: |
| Apple selected pull-up | 11 | 10 | 0 | 32 |
| Android Auto, API 29 | 0 | 0 | 0 | 33 |
| Android Auto, API 35 | 0 | 0 | 0 | 33 |

Every clip's status, source-frame count and event times match the prior
[`e73e57c` evaluation](public-workout-identity.md). The source-clock regression fix
does **not** demonstrate a corpus acquisition or count gain. The unchanged
published-count gate also fails the workflow's final accuracy job after verifying
three reports with matching manifest and source revisions. All other jobs pass;
the completed workflow concludes failure. Neither platform meets accuracy
requirements or establishes functional/physical parity.

Each Android first pass has 6,231 bilateral-arm frames, 586 actual contour samples,
544 contour-evidence frames, zero contour errors and zero confirmed-reference
frames. Earlier stage/state/issue counters match the prior checkpoint. Both APIs
record 263 fresh eligible-edge stability samples: 155 candidate starts and 19
accumulation samples. API 29 records 81 edge changes / 8 grip changes; API 35
records 80 / 9. The one classification difference is in `repcount_stu1_37`;
histograms are not identical. Neither records a qualified reference. Earlier
grip/edge rejections remain outside this denominator.

The [stage diagnostics](public-workout-stability-native.diagnostics.json) retain
the exact aggregates. [Artifact binding](public-workout-stability-native.artifacts.json)
records all five verified GitHub archive digests and twelve unchanged native,
model, score and Apple provenance files. Each Apple shard's report hash and 34 Git
source hashes match `fbd64d0`; executable/framework hashes are CI provenance, not
independently reproduced local binaries. Public labels, media and the Android
Heavy float16/1 model hash are unchanged. Raw pixels/poses/media are excluded.
Existing Apple simulator and original-capture accuracy failures, and all five
field-validation goals, remain open. PR #29 remains draft.

## Scene-registration audit

The separate audit-only source checkpoint `113a43452bc483079ff490c7937112e3121c073a`
adds estimated background motion alongside existing dwell diagnostics. It observes
the previous contour-sampled frame and the prior dwell anchor before a reset can
replace it. Missing estimates remain missing; they are not static-camera evidence.
The observations never feed production acquisition or counting.

The same three public clips still reach EOF on all 844 frames with zero accepted
events/references. The original bar states and stability diagnostics match at all
99 sampled observations; source-clock/reference-anchor and finite/missing-signal
checks also pass. In `countix_Ace4EsUWyYc`, 10 of 13 edge resets coincide with estimated
translation at or above the unchanged 0.8% image-motion threshold against the
adjacent frame; 10 of 13 also do against the prior dwell anchor. The longest
uninterrupted bar dwell remains 0.64 seconds. These are model estimates on exposed
footage, not independent physical-camera/equipment annotations or proof of a
causal reset mechanism. Registration results can vary between executions.

The [scalar-only scene audit](public-workout-scene-local.audit.json) binds media,
27 compiled Git source hashes and the executable, and retains per-sample source
times, anchor times and optional image-motion estimates. Raw pose/geometry/pixels
remain local. No tolerance, labelled split or accuracy gate changes.

## Subsequent shared setup guidance

App checkpoint `de16bf9c9b945eb67152474dd9feb2f070f839b1` addresses a presentation
gap: measurable arms can fit inside the image while the bar-search region extends
above it. Shared guidance policy 2 reuses the existing overhead search reach and
suggests **Leave room above your hands for the bar** during unconfirmed pull-up or
Auto setup. Existing arm/identity/error advice takes priority. The existing
0.30-second debounce and on-video caption carry the hint, including while an edge
is unclear; confirmed/running sets stay silent. Crop geometry, acquisition,
readiness and count policy 8 are unchanged. This cannot repair an exposed saved
clip or establish an accuracy gain.

Kotlin Native passes 308 tests and JVM passes 310. Production Swift/Kotlin setup
snapshots still match across 85 cases / 4,846 frames plus 17 geometry cases; all
30 counter cases match. The existing same-host core budget passes (Swift p95
0.0623 ms, Kotlin 0.0166 ms; camera/model excluded). Shipping Apple FFI reaches all
functional guards, including 12 framing contracts and 2,000 consumed results on
each framing path; native export/schema/source normalization also passes.

**Local full-bridge performance fails.** Two initial runs fail the unchanged
analysis budget at 31.161998 and 7.069439 ms before framing is measured. A single
sequential immutable-baseline/current comparison then measures:

| Host FFI p95 | Prior `fbd64d0` | Current guidance | Existing budget |
| --- | ---: | ---: | ---: |
| Analysis | 39.625243 ms | 46.512637 ms | <5 ms |
| Grip | Not reached | 28.369001 ms | <5 ms |
| Arm framing | Not reached | 1.925307 ms | <5 ms |
| Overhead framing | Not present | 5.837049 ms | <5 ms |

The current harness retains all four measurements and writes `passed: false`
before exiting nonzero when any budget fails. Functional guards, sample counts
and strict budgets remain intact. Both comparison builds use the same local
toolchain/configuration, isolated output directories and sequential execution.
CPU activity and swap usage are recorded; these do not establish the cause of
the timing failures or a regression attributable to the guidance patch. No
further local retries or exclusions convert these failures into success.

The [guidance validation record](public-workout-overhead-framing.validation.json)
binds the tested source, executables, frameworks, common test totals, exact parity
results and all failed attempts. Initial failures use the earlier harness variant
that stopped at the first budget failure; the paired current run uses the final
reporting harness. Common and shipping-bridge CI already discover these tests.
A fresh CI run was still pending when this local record was made; its completed
results follow. The `fbd64d0` full-corpus run above does not validate this guidance
source. Local failed timings and all existing accuracy/field gates remain recorded.

## Completed overhead-guidance CI at `d0e9ada`

[Shared/Apple CI 38661485364](https://github.com/yongkyuns/HangInThere/actions/runs/38661485364)
passes all 310 JVM tests, production setup/count oracles, shipping FFI contracts,
native source normalization and the required narrow/wide, light/dark UI renders.
All four host FFI paths meet the unchanged <5 ms budgets:

| Host FFI p95 | CI measurement |
| --- | ---: |
| Analysis | 1.470916 ms |
| Grip | 1.136708 ms |
| Arm framing | 0.188708 ms |
| Overhead framing | 0.305500 ms |

The twelve framing cases and 2,000 consumed results per framing path pass. These
CI-host results do not erase the failed local measurements, identify their cause
or qualify camera/model/physical-device performance.

[Android CI 38661485195](https://github.com/yongkyuns/HangInThere/actions/runs/38661485195)
passes native API 29/35 engineering and emulator budgets, the required controlled
positive, software HDR decoder contracts, identical inputs, common policy parity
and Swift replay of the exact Android-produced observations. The controlled
positive counts one movement; the original pull-up remains 0/1 and the original
dip remains 0/9 on both fixed-edge and Auto paths. Both original-capture accuracy
jobs still fail and the workflow concludes failure. Policy/event agreement is
distinct from meeting the reference labels.

The PR checkout is merge commit `6e571ffb68172a9d75e78bb14b93af9f36cbe4d1`;
its complete Git tree matches branch head `d0e9ada5a505f031e3d3df2ab4248d1a3a3869c9`.
[Verified CI artifacts](public-workout-overhead-framing-ci.artifacts.json) bind four
archive digests, exact host/core/parity outputs, both 310-test totals and rounded
native log summaries. Native normalized source output was independently checked
from the verified archive. Raw native poses, APKs and source videos are excluded
from retained Git records. These results validate the overhead-guidance checkpoint;
they do not validate subsequent grip-assessment instrumentation or establish a
fresh 33-video accuracy score. All five field-validation goals and the simulator
model/decoder blockers remain open. PR #29 remains draft.

## Shared grip assessment at `f0c5aec`

The next checkpoint exposes the existing pull-up search conditions as a typed
shared assessment. `grip()` still returns the same nullable grip and crop; the
first rejection now identifies context, athlete count, unavailable arms or grip
landmarks, or projected hand geometry. Check order, confidence thresholds,
geometry, dwell and count policy 8 are unchanged. Failed assessments reuse enum
singletons, and asking for an assessment cannot advance or reset apparatus dwell.

Android native evaluation records a version-1 counts-only histogram over every
actual first-acquisition pose, including frames before contour sampling. The
histogram uses this production assessment outside the measured pipeline interval;
it does not feed acquisition or counting. Version, allowed statuses, integer
counts and complete first-pass denominators are checked. Legacy reports remain
unassessed. An eligible grip is not a confirmed bar, started set or completed rep;
the overhead pull-up assessment does not grade dip suitability. iOS acquisition
does not yet export this histogram.

The [source-bound local validation](public-workout-grip-assessment.validation.json)
records 313 Kotlin Native and 315 JVM tests with no failures, 85 Swift/Kotlin setup
cases / 4,846 frames plus 17 geometry cases, and 30 counter cases. All 87 Python
evaluation contracts pass; the affected 17-test class also passes after the final
type guards. Existing core budgets pass. A single rebuilt shipping Apple FFI run
exits zero and passes normalization and all four unchanged <5 ms host budgets:
analysis 3.015573 ms, grip 3.692934 ms, arm framing 0.260913 ms and overhead framing
0.301328 ms. Each path consumes 2,000 results. Prior failed local measurements
remain recorded; this run does not explain their cause or qualify device timing.
Independent code/evidence review finds no actionable issues.

At this local checkpoint, new-source Android compilation and actual native
histograms still required hosted evaluation. Its completed results follow. No new
corpus accuracy or counting gain is claimed by this diagnostic change.

## Completed grip-assessment evaluation at `1bb4378`

[Shared/Apple CI 38670979769](https://github.com/yongkyuns/HangInThere/actions/runs/38670979769)
passes all 315 JVM tests, setup/count parity, shipping FFI, native normalization
and required UI renders. Host FFI p95 is 0.601791 ms for analysis, 0.854417 ms for
grip, 0.046500 ms for arm framing and 0.074709 ms for overhead framing, all below
the unchanged 5 ms limits. [Android CI 38670979690](https://github.com/yongkyuns/HangInThere/actions/runs/38670979690)
passes native API 29/35 compilation, lint, emulator budgets, the 1/1 controlled
positive, HDR contracts, exact inputs and Swift replay of Android observations.
Original-capture accuracy still fails: pull-up 0/1 and dip 0/9 on both fixed-edge
and Auto paths. [Verified CI bindings](public-workout-grip-assessment-ci.artifacts.json)
retain four archive digests and scalar host/core/parity outputs. The PR merge
checkout `d98daf32f8586d603d256a1603a1649ecb4fea5a` has the same complete Git tree as
branch checkpoint `1bb43782050142cbefe6ad15d215d13ae44b9dbf`.

The [full native run 38670981277](https://github.com/yongkyuns/HangInThere/actions/runs/38670981277)
also completes all 33 original clips / 149 published reps / 11,629 source frames
per platform. Native execution, contracts and Apple shard merging pass; final
published-count accuracy fails. Apple again reports 11 accepted / 10 completed
cycles and Android Auto reports zero, with 0/33 exact clips on every platform.
Media hashes, statuses, frame counts and event times match the `fbd64d0` run.
Five verified archives, twelve unchanged hosted outputs and 34 Git source hashes
per Apple shard are retained in the [native bindings](public-workout-grip-native.artifacts.json).
Independent merging and all three scores byte-match hosted outputs. Framework
hashes remain CI provenance, not locally reproduced binaries.

Version-1 grip histograms match **for every clip** across Android APIs 29 and 35:

| First grip-search result | Frames per API |
| --- | ---: |
| Eligible | 3,947 |
| Left arm unavailable | 1,889 |
| Right arm unavailable | 2,063 |
| No person | 735 |
| Multiple people | 711 |
| Grip landmarks unavailable | 374 |
| Hands not above shoulders | 1,269 |
| Projected hand span too small | 516 |
| Hands not level | 125 |
| **All first-pass frames** | **11,629** |

These are the first failed production checks, not independent joint-error rates
or labelled physical pose accuracy. The left-arm check precedes the right-arm
check. Of 6,231 bilateral-arm frames, 374 fail the existing higher-confidence
hand/shoulder requirement and 3,947 pass grip-search rules. Auto acquisition still
confirms no reference. The 586 contour-sampled frames include 544 with evidence
and zero native contour errors; their scopes include Auto apparatus searches and
must not be equated with the pull-up-only histogram. [Stage diagnostics](public-workout-grip-native.diagnostics.json)
preserve complete denominators and previous acquisition totals/states/issues.

This confirms a presentation gap when arm measurements are available but grip
landmarks are insufficient. It does not justify lowering confidence, stability
or accuracy gates. A subsequent shared setup hint addresses that gap; the hosted
results above do not validate its later source or establish a counting gain.
All five field-validation goals and known simulator blockers remain open.

## Shared grip-visibility guidance at `662767a`

Guidance policy 3 reuses the typed production assessment to show **Keep both hands
and shoulders clearly visible** when both arm measurements are available but
bar-search grip landmarks are insufficient. Both apps receive it through their
existing shared guidance interfaces and video captions. Higher-priority arm,
identity and malformed-evidence advice remains first. Valid Auto dip support
does not inherit the pull-up confidence requirement, including when the previous
exercise selection was pull-up. Confirmed apparatus and running sets stay silent.
The existing 0.30-second confirmation filters transient advice; recovery and
lifecycle changes clear it. This hint neither loosens the 0.5 grip-search threshold
nor advances acquisition, countdowns, counts or credited time. Overhead advice
reuses the already assessed grip without recalculating it.

[Local validation](public-workout-grip-visibility.validation.json) passes 316
Kotlin Native and 318 JVM tests, exact setup parity across 85 cases / 4,846 frames
plus 17 geometry cases, and 30 counter cases under the existing core budget. New
tests cover all four grip joints, the exact confidence boundary, Auto dip support
and feedback lifecycle without starting or crediting a set. An initial command
with incorrect oracle directory paths was cancelled before tests; the corrected
full suites pass and both invocation records remain explicit.

The rebuilt shipping Apple FFI exits zero and passes normalization. Seven new
direct guidance contracts and three feedback checks pass. Five 2,000-result host
paths meet their unchanged <5 ms budgets: analysis 1.124776 ms, grip 1.809990 ms,
arm framing 0.167947 ms, overhead framing 0.234389 ms and the new grip-visibility
path 0.322846 ms. Independent code and evidence reviews find no actionable issues.
Prior failed local measurements remain recorded. These are synthetic host FFI
measurements, with no camera/model or physical-device timing qualification.

At this local checkpoint, fresh new-source Android CI was still required. Its
completed results follow. The native corpus at `1bb4378` above excludes the later
hint; no new corpus count or accuracy gain is claimed. The hint helps users correct
future setup and cannot repair saved clips.

## Completed visibility-guidance CI at `f61a146`

[Shared/Apple CI 38737532676](https://github.com/yongkyuns/HangInThere/actions/runs/38737532676)
passes all 318 JVM tests, production setup/count parity, native normalization,
shipping FFI and required UI renders. All five unchanged <5 ms host FFI budgets
pass: analysis 1.347916 ms, grip 1.172625 ms, arm framing 0.187917 ms, overhead framing
0.286334 ms and grip visibility 0.351709 ms. The seven new direct guidance cases,
three new feedback checks and 2,000 results per measured path pass.

[Android CI 38737532681](https://github.com/yongkyuns/HangInThere/actions/runs/38737532681)
passes native API 29/35 compilation, lint, emulator budgets, controlled positive,
HDR contracts, identical inputs and Swift replay of Android model observations.
Both original public-capture accuracy jobs still fail: pull-up 0/1 and dip 0/9 on
fixed-edge and Auto paths. Engineering parity does not establish rep accuracy.

[Verified bindings](public-workout-grip-visibility-ci.artifacts.json) retain four
archive digests, both 318-test totals and exact scalar host/core/parity outputs.
Normalized native source output was independently checked. Merge checkout
`aceb3b735dcc484d7892b20a8957ccfa121a84c1` has the same complete tree as branch
checkpoint `f61a1460dc945a3a1592d911e73b86531d424a73`. Host/emulator results exclude
physical-device qualification and preserve earlier failed local measurements.

## Rejected fresh-edge continuity preference

The next experiment asks whether a newly visible contour makes selection abandon
an already witnessed edge that is still freshly visible. Six of thirteen resets
in the cached Ace candidate lists have some matching adjacent contours. This is
only a candidate-level upper bound; those pairs need not be eligible or match the
selected dwell witness.

The [comparison source](../EdgeContinuityProbe.swift) runs the original production
selector and a separate experimental preference on identical native decoded
poses/contours. Original full-set ambiguity rejection remains mandatory. The
preference may use only a newly observed, individually eligible edge matching the
dwell witness under the unchanged angle, distance, overlap, time, image-size and
hand-motion conditions. A cached edge never becomes a new observation. Missing
or ambiguous evidence resets the experimental dwell. Production code is unchanged.

On the pinned public `countix_Ace4EsUWyYc` clip, all 250 frames and 32 sampled
observations complete. The baseline exactly reproduces the previous audit's
sample states/diagnostics. The preference changes one edge choice at 4.16 seconds,
where both paths already accumulate normally. Both retain thirteen edge-change
resets, maximum dwell 0.64 seconds and no confirmed reference. This specific
preference has **no demonstrated acquisition gain on this clip**. It is not
promoted into either app and does not rule out other strategies on other inputs.

The [scalar audit](public-workout-edge-continuity.audit.json) binds original media,
manifest, all 27 compiled source hashes, executable and exact experimental source
bytes. The source was compiled from an identical local staging copy; the 26
production Git sources match `f61a146`. Sample output contains timing/status/counts,
not coordinates, poses or pixels. Independent review verifies source hashes,
baseline equivalence and the negative result. The experiment's actual native run
is its validation; it is a standalone research tool, not a shipping app policy.

A single-assistant visual inspection of original frames 30 and 40 also observes
rack displacement in image space, consistent with the earlier registration audit.
This is development inspection, not independent physical-camera/equipment labels
or a causal decomposition of each reset. No confidence/stability threshold, model,
label or release gate changes. The latest full 33-video count score remains the
`1bb4378` result above. Published-count failures, simulator blockers and all five
field-validation goals remain open; PR #29 stays draft.

## Shared acquisition guidance at `8337d6e`

Guidance policy 4 adds **Hold your start position · keep the phone steady** while
a real bar or rail candidate accumulates qualifying observations. The production
diagnostic must be fresh, its state must be holding, and the current grip search
must still be possible. Missing, ambiguous, stale, future or qualified evidence
cannot invent a hold. Arm visibility and overhead-framing advice retain priority.
This instruction neither asserts that equipment is confirmed nor attributes a
failure to physical camera movement. Both apps use the common presentation rule.

The Apple bridge carries the raw observation clock, avoiding microsecond pose
transport truncation that can make a fresh observation appear future-dated.
Cancelled searches and asynchronous candidate loss clear only acquisition
feedback immediately. Repeated failed equipment searches preserve visibility
advice and its confirmation interval. Lifecycle, exercise, confirmation and
running-state changes cannot retain the old acquisition cue. Counting policy 8,
grip confidence, geometry, reference dwell, countdown and credited-time rules are
unchanged; no cached edge earns an observation.

The [source-bound validation](public-workout-acquisition-guidance.validation.json)
passes 322 Kotlin Native and 324 JVM tests, all 85 setup cases / 4,846 frames plus
17 geometry cases, and all 30 counter cases. Core p95 remains under the existing
1 ms budget (Swift 0.0623 ms, Kotlin 0.0041 ms). The shipping Apple FFI passes 22
acquisition feedback checks, scoped invalidation contracts, normalization and all
six unchanged <5 ms host budgets. Each measured path consumes 2,000 results:

| Host FFI path | p95 |
| --- | ---: |
| Analysis | 1.266427 ms |
| Grip | 1.660083 ms |
| Arm framing | 0.114919 ms |
| Overhead framing | 0.154434 ms |
| Grip visibility | 0.208283 ms |
| Acquisition guidance | 0.182261 ms |

The full iOS app builds and all 15 selected camera/framing tests pass on the iOS
18.6 simulator with the 18.5 SDK. Existing AVCaptureSession Sendable warnings
remain. All 104 shipping UI renders and OCR checks pass; the six newly covered
caption layouts are visually inspected at 320 and 430 points, with readable text
and no clipping or control overlap. Independent code review finds no remaining
actionable issues after the asynchronous candidate-loss fix.

Fresh hosted Android validation is pending for this source. These synthetic
host/simulator checks do not qualify sustained camera/device performance or a
counting accuracy gain. The latest full 33-video native score remains `1bb4378`;
the rejected continuity experiment, prior failed local timings, original public
count failures, simulator blockers and all five field-validation goals remain
recorded and open. PR #29 remains the sole draft integration PR.

## Completed acquisition-guidance CI at `751c53c`

[Shared/Apple CI 38756787324](https://github.com/yongkyuns/HangInThere/actions/runs/38756787324)
passes all 324 JVM tests, production setup/count parity, native source
normalization, shipping FFI and all 104 required UI renders/OCR checks. All six
unchanged <5 ms host budgets pass: analysis 0.990250 ms, grip 1.149625 ms, arm
framing 0.217917 ms, overhead framing 0.339416 ms, grip visibility 0.421916 ms and
acquisition guidance 0.345500 ms. The 22 acquisition feedback checks and 2,000
consumed results per measured path pass.

[Android CI 38756787240](https://github.com/yongkyuns/HangInThere/actions/runs/38756787240)
passes all seven engineering jobs: native API 29/35 compilation, lint, emulator
budgets, HDR contracts, production policy parity, identical public inputs and
Swift replay of native observations. The controlled pull-up remains 1/1.
**Original-capture accuracy still fails on both APIs:** pull-up 0/1 and dip 0/9 on
both fixed-edge and Auto paths. Unit-test fixture output is excluded from these
native results.

The [verified CI bindings](public-workout-acquisition-guidance-ci.artifacts.json)
retain four archive digests, exact scalar host/core/parity outputs, both 324-test
totals and rounded native log summaries. Normalized native output is independently
checked. Merge checkout `e169b5b355c5a033e08426d19216c24378d4189b` has the same
complete tree as branch checkpoint `751c53ccf96cb4a7842bc967499e6040d8bca973`.

[Apple P0 38756786866](https://github.com/yongkyuns/HangInThere/actions/runs/38756786866)
passes native macOS real-Vision/replay integration. Simulator mechanics still
fails the one pinned-H264 pixel test with eight green/yellow assertions among
296 tests. All eight simulator vision tests still fail with the missing
`cnn_human_pose.espresso.weights` / Vision Code 9 error. These are the existing
decoder/model blockers, not a new setup-guidance regression; both gates remain
fatal. Earlier local timing failures, public count failures, physical-device
qualification and all five field-validation goals remain open. No new full-corpus
accuracy score or counting gain is inferred from this presentation change.

## Historical person-crop result closed

The completed [Android crop experiment 38247556801](https://github.com/yongkyuns/HangInThere/actions/runs/38247556801)
at `7beed84c7b39e985a8d5018026f417dcb51d63be` had no recorded outcome in the
evaluation notes. Its four original archives are now hash-verified, and both the
historical and current checkers independently accept all 448 source-frame entries
per API. Eighteen hosted Swift mode-replay cases bind the exact local trace hashes
and match the native counts/events. This audits historical inference; no fresh
model execution or current-source accuracy qualification is claimed.

The bounded person-crop recipe preserves the clear pull-up's 1/1 result but leaves
the cropped pull-up at 0/1 and dip at 0/9. Usable right-arm frames fall from 25 to
6 of 40 on the cropped pull-up and from 34 to 16 of 300 on the dip. Left-arm
coverage also falls in every clip. Counts, coverage, events and skips agree across
both APIs. This is coverage under the existing measurement policy, not ground
truth joint accuracy. Two unavailable crop regions remain in the source denominator.

The [scalar audit](public-workout-person-crop.audit.json) and
[completed comparison](../../docs/ANDROID.md) preserve the original model/media,
recipe, source clocks, confidence, geometry and count policy 8. Per-mode timings
are diagnostic with three retained models, crop always last and preprocessing
costs excluded; they establish no production/device speedup. The recipe remains
diagnostic because it demonstrates no count or arm-coverage gain on these exposed
development cases. No app code, threshold, label, release gate or original failure
changes, and no raw poses, pixels, APKs or source media are added to Git.

## Automatic exercise identity clock fix at `41d51ca`

Both Auto recognition policies previously subtracted rounded absolute seconds.
A true 0.6-second hold from source ticks 1,100,000 to 1,700,000 at timescale
1,000,000 could therefore miss the minimum identity duration. Similarly, an
exactly allowed 0.35-second gap from 1.2 to 1.55 seconds could discard the hold.
The [source-bound before/after record](public-workout-identity-clock.validation.json)
reproduces the defect at prior source `4c9bb22`: three of four new JVM regression
tests fail; two tests produce six issues in the 242-test Swift core suite.

Swift and Kotlin now retain first/last PresentationTime values and reuse the
existing source-tick elapsed-time helper for identity dwell and frame gaps. No
new tolerance is added. Identity still requires four observations over at least
0.6 seconds, with gaps no larger than 0.35 seconds. Grip/pose eligibility, lock and
reset behavior, apparatus confirmation, countdown, count policy 8 and credited
time remain unchanged. Mixed timescales retain the helper's existing floating
subtraction fallback; this fix does not promise exact boundaries across clocks.

Validation passes all 242 Swift core tests, 326 Kotlin Native tests, 328 JVM tests,
and 20 selected iOS identity/camera tests with a full app build. The production
Swift oracle and common engine match across 93 cases / 4,878 frames plus 17
geometry cases. Eight new clock cases carry independently authored per-frame
identity labels. All snapshots of the original 85 cases / 4,846 frames remain
identical. Regressions cover exact/one-microsecond-short holds, insufficient
observations, exact/over-limit gaps, invalid/duplicate/reversed/overflowing clocks,
large source origins, mixed timescales and the common runtime. Identity alone
cannot invent apparatus, countdown or movement credit. Independent code review
finds no actionable issues.

All 30 counter cases pass; core p95 remains below the unchanged 1 ms budget
(Swift 0.0091 ms, Kotlin 0.0028 ms). The rebuilt shipping Apple FFI passes source
normalization and all six unchanged <5 ms host budgets over 2,000 results per path:
analysis 1.014764 ms, grip 1.564904 ms, arm framing 0.087514 ms, overhead framing
0.145316 ms, grip visibility 0.191488 ms and acquisition guidance 0.155863 ms.
These host measurements do not measure identity-policy overhead separately or
qualify camera/model/device performance.

Fresh hosted Android and full 33-video native evaluation are pending for this
source. The last completed full-corpus score remains `1bb4378`; no new video
accuracy gain is established by authored clock tests. Earlier failures, simulator
decoder/model blockers and all five independent/field-validation goals remain
open. PR #29 stays draft, with original labels and release gates in force.
