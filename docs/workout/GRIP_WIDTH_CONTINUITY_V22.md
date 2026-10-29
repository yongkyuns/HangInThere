# Width continuity and starting-hang calibration, policy v2

Two implementation issues are repaired without changing the width bands,
confidence thresholds, learned models or rep counter:

1. Width calibration now observes the existing extended starting-hang dwell.
   It still requires five frames spanning 0.4 seconds, at most 15% shoulder-span
   variation, and supported wrists. Only the unchanged counter's **ready** phase
   can freeze/activate the baseline. This avoids starting a second serial dwell
   after the counter has already acquired that same hang. Bent-arm setup cannot
   prequalify the baseline; an unacquired full hang cannot produce a width label.
2. A missing arm/elbow measurement no longer necessarily destroys width
   calibration. Width can continue only with current bilateral wrists/shoulders
   meeting the existing 0.5 threshold, support geometry, and a fresh matching
   front/rear auxiliary body observation. Missing/stale body evidence, lost
   wrists, unsupported view, actual interruption, source/clock/model/reference
   changes still invalidate width. Hand orientation evidence resets during the
   arm outage; no cached hand classification earns coverage or confirmation.

Saved width policy version is now 2. Old records retain their original version.
The frozen shoulder denominator still cannot follow shoulder movement during a
rep. No automatic alternation assumption or new readiness gesture was added.

## Correction to the previous diagnosis

The v21 provisional medium references used wider shoulder locations than the
joint centers returned by the body models. At the two source reference frames:

| Source | Provisional manual ratio | Vision instantaneous ratio | Auxiliary body ratio |
|---|---:|---:|---:|
| Physitrack underhand | 1.18 | 1.62 | 1.46 |
| Physitrack overhand | 1.11 | 1.55 | 1.56 |

Model agreement is not anatomical ground truth. It does, however, undermine the
claim that the SDK baseline should simply be stretched to match those manual
labels. The historical references and results remain unchanged and disputed;
they do not become correct predictions or independent accuracy successes.
No correction factor or larger denominator is fitted to these clips.

The auxiliary model also gives a bad narrow-example wrist association (ratio
1.23, minimum wrist/shoulder score 0.456 versus Vision's instantaneous 0.62).
It is not promoted as a replacement body backend. All 435 source images used
the already installed body model, original pixels and frozen settings; no new
media or model was downloaded.

The prior `gripReferenceLost` tally also conflated several causes. On the two
Physitrack shots, 87/122 and 65/128 frames have no acquired starting support;
only 3/122 and 23/128 lack a tracked athlete. Calling all those frames tracking
dropouts overstated the continuity problem. On the narrow rear shot, by
contrast, 232/240 frames genuinely lack the tracked athlete. These conditions
are now separated in the research comparison diagnostics.

Future manual width references must mark the same named shoulder joint centers
for every source, record coordinate uncertainty, and be adjudicated before
opening predictions. Do not use the outside of a sleeve/deltoid as a substitute
for a joint center. If the plausible ratio interval crosses a band boundary,
mark the reference uncertain instead of selecting the desired band. Full capture
conditions and recording-separated tests still apply. Existing exposed sources
are development regressions, not a fresh test set.

## Frozen regression result

| Reviewed shot | Known width frames, v21 → final v22 | Final output |
|---|---:|---|
| Physitrack underhand | 1/122 → 4/122 | Wide, median 1.61× |
| Physitrack overhand | 2/128 → 4/128 | Wide, median 1.80× |
| Narrow rear component | 0/240 → 0/240 | Unknown |
| Timms rear wide component | 0/91 → 0/91 | Unknown |

This is a small coverage improvement, not reliable narrow/medium qualification.
No new correctness percentage is claimed from disputed reference labels. The
counter phase, events, acquired support, release and exercise-context outputs
are identical on all 7,579 original native frames. The width-only elbow-loss
path is verified with authored same-athlete/fresh-body contracts; this cohort
does not demonstrate a real-video gain from that particular repair.

The assembled six-set replay retains the original 5,112 frames and timestamps.
Every new set starts unknown; new-source outputs match their isolated results,
and repeated owner controls match each other. Its 288 setup and 148 release
control frames still produce no new movement, exercise admission or known
width. That establishes state isolation across explicit source/set boundaries,
not grip-type accuracy in a continuous filmed alternating workout.

## Verification and evidence

- All **820 shared JVM tests pass**, including 15 width contracts and 29 grip
  contracts. New contracts cover pre-ready calibration, bent-arm rejection,
  fresh-body width with unavailable arms, stale views, real wrist loss and zero
  hand-orientation credit during the outage.
- Generic unsigned iPhone build succeeds. No phone installation was performed.
- Independent audit verifies 23 frozen pins, the native counter comparison,
  every assembled-sequence output and setup/release totals.
- The first candidate's unit-test failures, its unchanged video results, and
  subsequent test-driven revisions remain in the evidence directory. The final
  candidate was frozen before its regression replay. Original v21 artifacts and
  source labels were preserved.

Implementation: `WorkoutGripWidth.kt`, `WorkoutGripAnalysis.kt`.
Replay: `Evaluation/androidTests/GripWidthValidationV22Tests.kt`.
Audit: `scripts/audit-grip-width-v22.py`.
Evidence: `build/grip-width-v22/`, including baseline source snapshots,
`candidate-revision3-freeze.json`, `comparison-r3.json`, `verification.json`,
native/assembled set records, auxiliary observations and build/test logs.
Snapshot: `Evaluation/fixtures/grip-width-continuity-v22.json`.

Follow-up: [v23 new-video reference validation](GRIP_WIDTH_REFERENCE_VALIDATION_V23.md)
freezes joint-center uncertainty regions before new-source inference. It
identifies a separate current-shoulder-size gate that can erase an acquired
baseline during normal movement; completed-rep width remains unqualified.
