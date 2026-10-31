# Personal coaching v1

Home offers optional setup: goal, preferred weekdays and available time. Defaults
can be accepted with one tap; quick workouts remain available without a profile.
Today shows editable pull-up/dip suggestions, a local week view and movable
sessions. Unrecorded days are offered for rescheduling only after setup began.
No account, network service or calendar permission is needed. Notifications are
requested only when workout reminders are explicitly enabled.

Home's Workout log supports custom exercises, quick set entry, lb/kg loads and
tap counting. Today can open the same log with its suggested targets. If there
is no recent camera history, a recent logged pull-up/dip set can seed a target,
with the entered load shown in its explanation; it cannot establish measured
range, tempo or automatic progression. Logged and camera sets share calendar
workouts but keep distinct counts and evidence. Tap timestamps establish rest
between consecutively timed sets; typed results never invent exercise timing.

Suggestions use the latest 100 reviewed sets, within 60 days. Observed completed
sets with a non-interrupted ending and unchanged exercise classification can
seed a starting suggestion even with gaps elsewhere; merged records are excluded.
Unknown setup can seed a
single-set suggestion but cannot establish a progression trend. Assistance,
weight, grip/variation and counting-method cohorts remain separate through the
existing comparison key. Pull-up comparisons also require either an explicit
variation or stable known grip type/width, including saved grip corrections.
Absent usable evidence, show comfortable practice
without inventing a rep capacity or requiring a max test.

The starting target is the middle of up to six recent comparable sets. The
"more reps" goal can suggest one extra rep after two distinct local days, each
with at least two easy-rated sets, matching counts, at least 80% recorded coverage
and similar recorded range.
A large recorded return-tempo change prevents that increase; it is not treated
as proven fatigue. A too-hard rating reduces the suggested target and offers
more rest or ending the session, including when the latest tracking was poor.
If one rep was already too hard, return to practice without a numeric target.
The 15-minute budget defaults to two sets; longer budgets default to three.
All targets and the initial 120-second rest suggestion are editable heuristics,
not a measured personal capacity or a validated training prescription.

Easy / About right / Too hard map to 3 / 6 / 9 on the existing self-reported
effort field. Saving feedback preserves all other corrections and raw records.
The optional rest timer survives sheet dismissal; it does not send alerts.
Starting a targeted session snapshots the plan in the existing plan library,
retaining its counting method/version. Linked historical plans are preserved;
planning failures never prevent starting a workout. New-user calibration has no
invented completion target. Schedule changes affect the coaching recurrence;
accepted historical plan snapshots stay separate. Profiles remain in local
preferences; workout feedback is included in the existing workout backup.

General rationale for individualized, goal-appropriate programming:
[ACSM resistance-training position stand, 2026](https://pmc.ncbi.nlm.nih.gov/articles/PMC12965823/).
The app's numerical rules above are implementation assumptions, not coefficients
validated by that review. The demand graph does not decide progression, estimate
readiness or establish muscle capacity. No form/injury or counting qualification
is added by this feature.

Validation: native core tests cover cold starts, mixed/unknown setups, independent
days, range/tempo changes, difficult sets with tracking loss, calendar/DST moves,
profile reload, preserved corrections/deletion and method-bound plan snapshots.
Native iOS presentation checks use existing exposed records and isolated app
data; they do not qualify camera inference or coaching effectiveness.

Workout reminders are optional in Today. A local time applies to the planned
weekdays, including moved sessions. The next 60 unique future sessions are queued
as one-time local notifications; opening the app, changing the plan, or a local
day/time-zone change refreshes that queue. There is no server or rest-timer alert.
Existing users start with reminders off. Denied permission offers iPhone Settings;
disabling removes only this feature's pending notifications. Serial updates avoid
stale alerts when a time edit or disable occurs during scheduling.
[Apple notification permission guidance](https://developer.apple.com/documentation/usernotifications/asking-permission-to-use-notifications).

The live vertical gauge shows current motion toward the source detector's travel
and arm-bend gates. It falls on the observed return, independently of the old
whole-cycle progress. A check means the detector witnessed the movement target;
only a completed movement event changes the rep count. Unavailable observations
clear the gauge. This adds display fields, not counting or form-assessment rules.

The gauge is visible once the starting posture and all camera/setup checks pass,
before automatic set commitment. It follows the first excursion using fresh
preparation snapshots; posture may change during that excursion, while all other
checks stay enforced. Rejected/stale frames and setup interruptions hide it.
