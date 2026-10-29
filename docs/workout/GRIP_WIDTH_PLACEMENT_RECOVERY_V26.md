# Width policy 5: revalidate current hand placement after a short gap

The first completed rep in the wide example now saves **Wide**, with 92.8%
measured width coverage. The second reaches 97.8%, and overall exercise-frame
coverage improves from 66% to 90%. Medium and narrow-titled regressions are
unchanged. This is an exposed regression result, not independent accuracy.

## What the remaining failure actually was

At 2.333 s the source still shows both hands on the bar, but the primary body
model places one wrist on the torso with confidence **0.365**. Policy 4 correctly
refused to measure that point, but treated its off-support coordinate as a hard
calibration reset. That weak coordinate is not reliable positive release
evidence. The other wrist, shoulders and hips remain tracked.

![Original frame and the erroneous weak wrist point](../../build/grip-width-v26/weak-wrist-source-example.png)

The bad wrist is not corrected, interpolated, promoted above 0.5 or used for a
numeric width. The frame remains Unknown and earns no coverage. Recovery uses
the next original source frame's confident wrist measurements.

## Changed behavior

Two conditions now distinguish a brief unmeasured gap from a new placement:

1. A lone low-confidence wrist outlier may suspend an existing calibration for
   at most **0.35 s**, even when its unreliable raw coordinate is off support.
   The other strong wrist must stay supported. Both current shoulders and hips
   must be strong, finite and visible, and match the last fully measured body
   within the existing **0.12 short-side** association radius. Caller-owned
   athlete/exercise identity, fresh front/rear view and reference continuity
   remain mandatory. Weak frames never extend the deadline.
2. On recovery, an already confirmed width may resume immediately only if
   **both fresh wrists** are confident and supported, their positions each match
   the previous supported placement within **0.15 frozen shoulder spans**, the
   fresh canonical ratio band is unchanged, and strong body continuity remains.
   The reported ratio is calculated from these current points. No cached ratio,
   backdated confirmation or observation time is manufactured.

The 0.15 placement margin is a new, prespecified engineering parameter. It was
frozen before replay and was not tuned afterward to improve these clips. It
still needs independent validation. It is not a calibrated probability or a
population standard.

Moved hands, a changed width band or a band that was never confirmed still need
the full existing 0.5 s of fresh confirmation. Strong off-support wrists,
missing/duplicate joints, two weak wrists, weak/missing torso anchors, a body
discontinuity, expired/stale view, actual interruption, source/reference/size
changes and invalid clocks clear calibration. The prior hard-negative test for
a weak off-support coordinate is deliberately replaced with the stronger body
continuity/zero-credit tests; positive support-loss tests remain rejection tests.

No landmark confidence cutoff, width category, ready-hang acquisition rule,
80% rep-summary requirement, live 0.85 facing gate, hand model or counter changed.
No extra model inference or image tracking was added. Stored width policy is 5.

## Native replay result

| Source | Known width / native exercise frames, policy 4 → policy 5 | Output |
|---|---:|---|
| Nuffield medium | 138/152 → 138/152 (91%) | Medium |
| Carlito narrow-titled | 197/197 → 197/197 (100%) | Narrow; reference remains uncertain |
| Holly wide | 254/385 → 346/385 (90%) | Wide; no wrong known frames |

![First completed wide rep, before and after](../../build/grip-width-v26/wide-first-rep-recovery.png)

Holly's completed reps now have:

- First rep, 1.2–3.5 s: **21.7% → 92.8%** credited coverage, Unknown → Wide.
- Second rep, 5.4–8.4 s: **81.1% → 97.8%** credited coverage, Wide retained.

All four completed reps across these three sources now have known width
metadata. This is **not a 100% accuracy claim**: Carlito's original shoulder/wrist
reference interval still crosses bands, the references are provisional agent
reviews, and all these recordings were already exposed development regressions.
Incomplete attempts remain excluded from completed-rep variation totals.

Three immediate recoveries use original source times 1.4667, 2.3667 and 6.8667 s.
Their maximum per-wrist displacement from the last supported positions is 1.04,
2.42 and 2.25 px respectively, below the frozen 6.85 px placement margin. The
audit verifies current confidence, canonical band, current support geometry,
body continuity, elapsed time and the unchanged frozen divisor. Four native
weak frames remain Unknown with null ratios, including the torso outlier; the
assembled sequence contains the same four uncredited pauses.

## Verification and scope

All **838 shared JVM tests pass**. Six new contracts cover a weak outlier with
fresh numeric recovery, same-band relocated hands, weak/missing/foreign body
anchors, an unconfirmed pre-gap band, positive support loss and exact zero gap
credit in the live analysis. Prior changed-band, timeout, clock, view, identity
interruption, missing-hand and source-boundary tests still pass. Four opted-in
native replay runs pass.

Counter phase/events, motion/support/release/tracking and exercise-state outputs
are bit-identical across **12,102 frames**. Native counter-owned attempt fields
also match previous records except grip metadata. Offline RGB grip-type states
and decisions are identical; hand classification was not retrained or rerun.
New assembled sets start Unknown and match isolated outputs. Setup/release
controls retain zero new movements and no visible grip-type labels.

The previously fresh Live Lean clip stays at 12/99 Wide frames. No new videos,
training examples, model weights or revised labels were introduced. Frame
excerpts use original indices/PTS and are diagnostic only; FFmpeg RGB conversion
may differ from the Apple decoder. The original recordings and all prior
failure evidence are preserved.

Replay still supplies a source-confirmed view and wrist-seeded support. This
does not qualify automatic phone acquisition or continuous filmed alternating
sets. Fresh complete front/rear narrow/medium and alternating-set validation
remains necessary before a broad accuracy claim.

The generic unsigned iPhone build succeeds. The physical iPhone was unavailable
when checked, so this update was **not installed**. After compilation finished,
cleanup removed an estimated 152 MB of reproducible compiler caches. About
177 GB remains free; original data, models and compiled products remain intact.

Implementation: `WorkoutGripWidth.kt` only. Replay:
`Evaluation/androidTests/GripWidthValidationV26Tests.kt`, retaining the unchanged
counter/feature/context path. Audit/plot: `scripts/audit-grip-width-v26.py` and
`scripts/render-grip-width-v26.py`. Evidence: `build/grip-width-v26/`, including
baseline source snapshots, pre-replay candidate pins, `comparison.json`, source
frame receipt, native/assembled rep records, full test logs and iPhone build log.
Snapshot: `Evaluation/fixtures/grip-width-placement-recovery-v26.json`.
