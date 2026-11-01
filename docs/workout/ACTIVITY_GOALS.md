# Activity goals and workout summaries

Daily and weekly rep goals live in **Your workouts → Your rep goals**. Weekly
is the default. A goal counts all recorded reps for that exercise in the current
local period, including work recorded before the target was chosen. Periods reset
at local midnight or the locale’s first weekday; calendar policy handles DST.

Camera reps, manually logged sets and explicit reported-rep corrections all
contribute. Setup metadata and recording-coverage thresholds do not gate these
activity totals. Incomplete/excluded reps, running sets, future records and
undated camera recordings cannot inflate a period’s reps. Undated sets remain
in history with a visible explanation. Edits, deletion, splits and linked logs
use the same source projection, so presentation merges never duplicate totals.
Goals are stored in the library and included in backup/restore.

The weekly card shows reps, sets, active days, a daily chart and the difference
from the same local point last week. Each goal shows count/target, remaining reps
and completion. Workout detail compares recorded rep volume with the preceding
workout using the same exercises and shows the most reps in a set. These are
activity-volume comparisons. Existing context-sensitive comparison targets,
range and tempo analysis remain separate in Trends; their targets are preserved.

Reminders use common scheduling policy and skip days with saved activity.
iPhone schedules up to 40 upcoming workout dates (reserving room for informational tips) and refreshes after activity edits,
foregrounding and calendar/timezone changes. Android schedules one upcoming
alarm, rechecks completion before displaying it, and rebuilds after app launch,
workout edits, reboot and time changes. Both require user opt-in and system
notification permission. Android uses [inexact alarms](https://developer.android.com/develop/background-work/services/alarms),
so battery management may delay delivery; no exact-alarm permission is required.

Tests cover mixed sources, context-free counting, edits/deletion, backup,
period rollover, linked logs, session comparisons, DST, reminder suppression
and the native iOS calendar trigger. Physical-device notification delivery still
needs a phone check; simulator scheduling tests do not prove a banner was shown.
