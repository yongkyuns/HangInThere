# Proposed front/rear scope — 2026-10-23

Start grip classification with approximately front-facing and rear-facing
athletes. Strongly side-on cameras should receive a visible reposition prompt
and an unknown grip result. This is a proposed research/product scope, not an
implemented phone change. Pull-up, chin-up and neutral/parallel remain three
grip classes; a neutral grip is distinct from a side camera or a hand's edge.

The camera-support check must depend on the selected athlete's torso and image
quality, with face evidence only as bounded support. Face absence cannot mean
rear. Require usable hand/bar-contact pixels and stable view evidence before
acceptance. A head turn should not change the body-view decision. Show a large,
actionable setup message such as “Move the camera toward your front or back.”
If support is lost during a set, grip metadata returns to unknown; this must not
reset reps or require grip recognition for readiness.

Do not yet promise a precise ±degree cutoff. The current data has coarse
front/rear/oblique labels, not measured camera yaw. The v7 torso probe also had
four wrong argmax views and four missing contexts among fourteen confidently
reviewed frames. Checking the current torso head's highest-scoring class alone
would not establish reliable camera eligibility. Validate a separate support
decision with borderline views, body rotation and head turns before connecting
it to the phone UI. Front and rear must each qualify; front-only is a possible
smaller launch scope if rear qualification fails.

## Current data under the narrower scope

The audit preserves all source roles and inherited directly reviewed patch
labels in `build/grip-surface-v2/fitting.jsonl`. It counts a source group only
when at least one actual crop has a closed-contact reference and readable
palm/dorsum/side reference. Repeated frames or multiple angles of one grouped
athlete do not become new independent groups. These remain agent annotations,
not new human gold review.

| Grip | Front training groups | Rear training groups | Front validation groups | Rear validation groups |
| --- | ---: | ---: | ---: | ---: |
| Overhand | 24 | 20 | 5 | 4 |
| Underhand | 11 | 6 | 3 | 2 |
| Neutral | 4 | 3 | 1 | 1 |

Restricting the supported view removes the empty oblique-underhand validation
cell from the supported task. It does **not** repair the weak neutral validation
coverage: its front and rear cells contain only two and three readable crops,
respectively, each from one source group. Rear underhand also has only two
validation groups. Use a minimum of three readable independent groups per
fitting cell as a development coverage floor; this is not an accuracy guarantee.
The narrower cohort does not yet meet even that floor.

Retain side views as rejection/regression inputs, with their false accepts
reported explicitly. Report supported-view coverage and grip accuracy separately
so rejecting difficult frames cannot silently inflate end-to-end accuracy.
Never move previously exposed test recordings into fitting to fill these cells.

## Next bounded work

Fill the rear-underhand and front/rear-neutral validation gaps with independent
development groups, preserving all angles of an athlete in one role. Public
source research began before this scope change; no media was downloaded and no
new source was silently added to fitting. The attempted MuscleWiki page fetches
returned HTTP 403; no access-control workaround was attempted. Large Kinetics
test shards were not downloaded. Existing local data is retained.

Then qualify the stronger image-only anatomical classifier before fitting the
replacement grip heads. Neutral probability must not change when the supplied
camera view changes. Include image-only, view-only and combined controls, keep
the existing 0.8 acceptance thresholds, and freeze models before any new final
test footage is inspected. Earlier tests remain regression evidence. The old
surface-validation guard is unchanged.

Audit: `Evaluation/fixtures/grip-cohort-audit-v8.json`.
Command: `scripts/audit-grip-cohort-v8.py` with the input above and a fresh output.
No training, production changes or phone installation occurred in this step.

## Implemented follow-up

The subsequent app implementation and filled development coverage are recorded
in [GRIP_FRONT_REAR_IMPLEMENTATION_V9.md](GRIP_FRONT_REAR_IMPLEMENTATION_V9.md).
The proposal and counts above describe the pre-implementation v8 snapshot.
