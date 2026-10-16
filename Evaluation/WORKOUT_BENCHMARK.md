# Frozen workout-session benchmark

`scripts/workout_benchmark.py` provides a platform-neutral annotation, split and
scoring contract for automatic workouts. It complements the existing
[session aggregates](SESSION_QUALIFICATION.md) and clip temporal scorer. It does
not change runtime behavior or issue a release verdict. Its committed fixtures
are synthetic contracts, not independently collected workout evidence.

## Freeze before running or tuning

Keep captures, consent records, personal names and private annotations locally.
Use opaque participant/source/reviewer groups and a coarse device-family label.
Copy `Evaluation/fixtures/workout-benchmark-synthetic.json` as a schema example,
then create independent annotations from actual source PTS. Nominal FPS cannot
establish timing. A capture hash must refer to the exact recording/transform
used by the native runner; audit that binding at collection/export time.

Real evidence uses `evidence_class=independently_reviewed_sessions`. Every session
requires at least two distinct reviewer groups, review without runtime output,
and adjudication. These declarations are auditable provenance, not proof that
independent review occurred. The tool does not verify consent or media bytes.
Do not reuse development-exposed footage as fresh independent test evidence.

Assign development, validation and test splits before evaluation. Participant
groups, source-session groups and identical capture hashes cannot cross splits,
including differently trimmed samples declared from the same original source.
Freeze event/phase tolerances and the allowed end response delay at that point:

```sh
python3 scripts/workout_benchmark.py freeze local/annotations.json \
  --output local/campaign-v1.lock.json
```

The lock includes the canonical annotations' SHA256. It cannot be overwritten by
`freeze`; any changed labels, groups or tolerances invalidate existing run
bindings. Archive the lock before running. Hashes cannot prove chronology or
prevent someone deliberately creating a new lock after seeing results. Record
every exposure/exclusion in the campaign audit and reserve fresh test sessions.
No private artifacts are uploaded by this tool or the committed CI workflow.

## Reference labels and normalized native outputs

One session can include several sets/exercises, or no sets for a negative case.
Label source spans, set start/end uncertainty windows, completed-return windows,
known phase intervals and ungradable intervals. Phase labels use the shared
`WorkoutMotionPhase` vocabulary. An absent phase annotation is unavailable;
it is not a negative label. A null end window represents a censored set with no
observed disengagement. Hang-only practice and a final incomplete movement have
zero completed-return windows and remain in the benchmark.

Completed cycles use `start_extreme_return`, independently of the pull-up
counter's top acceptance milestone. A native normalizer must use shared
`WorkoutAnalytics.isCompletedRep` and export eligible `returnedAt` source times,
not `acceptedAt`, aggregate counts or wall-clock notification times. Completed
cycles do not establish chin clearance, depth, anatomical validity or strict form.

`Evaluation/fixtures/workout-predictions-synthetic.json` shows the normalized
output contract. Bind a run to the lock and exact capture hash, identify platform,
backend, source revision and each analysis-policy version, and explicitly declare
automatic capture. Raw stored records alone cannot prove automatic start origin.
Manual starts are outside this contract. Manual/interruption/source-end closures
cannot claim a positive automatic disengagement. A tracking-loss timeout is an
interruption and counts as a false positive normal end on gradable footage,
even when its timestamp happens to coincide with a true disengagement.

Observation support intervals have **closed endpoints for discrete frames**.
Positive-width intervals may represent only fresh, actually observed usable
adjacent intervals, never a tracking gap or nominal frame-time interpolation.
Zero-width `[t,t]` entries represent isolated observed frames: they support an
event but earn no elapsed time. Completed returns, starts, positive disengagement
ends and physical phase transitions require this support; unsupported output
is rejected explicitly. Tracking-loss timeouts need no pose support because
they cannot claim a successful observed disengagement. Intervals used for time
integration and ungradable exclusions are half-open; adjacent intervals can be
merged without adding time. Do not turn unavailable/retired curves into fabricated
observation intervals.

Every session in the selected split must appear, including negatives and failed
processing. A failed session supplies no measurements and stays in recall and
coverage denominators. This implementation scores normalized JSON; native capture
exporters, independently collected labels and a physical-device campaign remain
required integration/qualification work. It does not infer trustworthy masks or
automatic origins from a summary, nor replay media itself.

```sh
python3 scripts/workout_benchmark.py score local/campaign-v1.lock.json \
  local/ios-run.json --split test --output local/ios-metrics.json
python3 scripts/workout_benchmark.py score local/campaign-v1.lock.json \
  local/android-run.json --split test --output local/android-metrics.json
```

## Metrics and interpretation

- Completed-cycle precision/recall use the existing chronological, one-to-one,
  maximum-cardinality event matcher, grouped by exercise. Duplicates remain
  extras. A correct total count with wrong event times still fails event scoring.
- Exact set counts match parents by start and exercise. Missed/extra sets and
  false starts/ends expose incorrect splits even when the workout's total rep
  count agrees. Full-set count eligibility requires a fully gradable reference
  set; excluded intervals and ignored predictions remain explicit.
- Automatic-end matching allows the separately frozen response grace after a
  physical release window. It does not extend the early-close tolerance. Timing
  error still measures distance to the original release label, exposing delay.
  The synthetic fixture uses a 1-second response allowance; this is a contract
  example, not a newly validated product latency target.
- Phase agreement over **all labelled time** and agreement **when reported**
  appear with phase-output and observation coverage. Missing output cannot make
  full-session phase accuracy look perfect. Boundaries are matched by exercise
  and transition type only where phase truth is labelled; unlabelled/excluded
  predictions remain explicitly ignored. Matched timing mean/p95 appears with
  TP/FP/FN and measured/reference counts, never alone.
- Hold duration compares independently labelled hang/support/endpoint/mid-range
  holds with credited observed time. Gaps earn no hold time. Signed error and
  mean/p95 absolute error are reported for exposed sessions, not diluted by
  unrelated zero-exposure sessions.
- Reports retain per-session results, exercise/context strata, participant/source
  sample sizes, failures, exclusions, lock/input hashes and scorer/matcher hashes.
  Repeated sessions/frames are correlated; these metrics provide no population
  confidence interval, calibrated confidence or automatic release gate.

The Session qualification and Dataset evaluation workflows exercise all new
contracts and the unchanged legacy temporal tests. Only hard-coded synthetic
fixtures are used by the new benchmark CI step. Independent annotation,
source verification, representative collection and predeclared release gates
remain open in roadmap #31.

The required Dataset evaluation host job installs FFmpeg and FFprobe before the
regressions. This keeps the real VFR fixture-encoding and metadata-stripping
transcode tests active in CI; a missing media-tool installation fails the job
instead of silently leaving those tests skipped. These tests create synthetic
recordings and do not read private recordings or credentials.
