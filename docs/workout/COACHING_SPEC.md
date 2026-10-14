# Coaching behavior for independent review

This specification describes the implemented app rules. It is a review subject,
not a completed coach approval or a training prescription. Review the exact app
revision and both native interfaces together with this document.

## Evidence and practice

Completed-cycle analytics require acceptance and an observed return to the
starting position. Accepted pull-ups with an unrecorded return, partial attempts
and interrupted observations remain distinct practice evidence. Tracking loss
does not establish a strength or form failure. Gaps, missing scene qualification
and unavailable reference geometry earn no hold time.

Pull-ups report observed hang time; dips report observed top-support time. Phase
timing, elbow angles and projected excursion are camera measurements. They do not
establish chin clearance, dip depth, lockout, force, fatigue or anatomical validity.
Grip and spacing estimates retain their own observation coverage and uncertainty.

## Comparable library suggestions

`WorkoutInsights` in `WorkoutLibraryInsights.kt` requires explicit exercise,
intent, assistance, load, equipment, camera view and variation context, plus
matching metric/count versions. Dated sessions need at least 80% tracking coverage.
Unclassified recordings remain reviewable but do not establish a comparable baseline.

The library uses the most recent five comparable sessions. At least three sessions
and a positive median are required for a suggestion. It selects completed cycles
when their median is positive; otherwise it uses the longest observed hold.
Suggested cycle targets are the median plus one, rounded upward. Suggested hold
targets are the median plus the larger of one second or 10%, rounded upward.
Targets are capped at 1,000 cycles or 3,600 seconds by input validation. These caps
are storage/input limits, not recommended workout amounts.

Chosen goals do not automatically increase. Eligible measurements after goal
creation determine progress. A stable trend requires five comparable sessions
within 5% of the median, with a 0.5-second minimum tolerance for holds. The wording
asks the user to review recording quality and context; it does not prescribe
harder training or diagnose a plateau in strength.

## Compatibility review and editable targets

The existing `WorkoutProgression` policy version 2 is also accessible in native
practice review. Its editable starter target uses the best completed set plus one,
or the longest observed hold plus one second/10%; without measured holds it offers
five seconds. Its historical projection lacks the library's comparable-session
context. These starter defaults must be reviewed separately from library
suggestions; neither is a verified exercise prescription.

## Goals, plans and feedback

The user selects goals and planned work. Plan completion requires an explicit
recorded-workout link on the scheduled day, matching comparison context, sufficient
coverage and enough measured sets meeting the target. User-reported completions
stay separate from measured metrics and milestones.

Source clocks measure motion. Actual live wall-clock dates describe habits and
rest; imported recordings never acquire a fabricated capture date. Workouts group
by a 45-minute start gap unless explicitly assigned. Merging presentation rows
preserves original source clocks and practice dates.

Start/end/pause/resume cues describe tracking status. After-set feedback describes
observed goal progress. Beginners' hangs and partial attempts remain practice.
Missing or interrupted measurements produce recording advice and evidence links.

## Requested coach decision

Review suitability of the progression defaults and wording, including the
compatibility targets; beginner hang/support and partial-attempt practice; assistance
and negative context; uncertainty explanations; and distinctions between tracking
goals and exercise/form claims. Record required changes or limitations in the
independent report. A rejected or incomplete review remains an open roadmap goal.

The review packet records a pseudonymous qualified-coach role, credential-check
attestation, exact artifact bindings, review date and report digest. A coordinator
must verify the actual credentials and signed report independently. The software
does not authenticate a coach or generate an approval.
