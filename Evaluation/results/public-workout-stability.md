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
A fresh CI run is required for this new source; the `fbd64d0` full-corpus run above
does not validate it. Local full performance and all existing accuracy/field gates
remain open.
