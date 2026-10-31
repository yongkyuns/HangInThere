package dev.hanginthere.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import dev.hanginthere.core.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

@Composable internal fun WorkoutCalendarPanel(
    anchor: Long, mode: WorkoutCalendarMode, zone: String, selected: WorkoutCalendarDayRange?,
    activity: List<WorkoutPracticeDay>, loading: Boolean,
    onAnchor: (Long) -> Unit, onMode: (WorkoutCalendarMode) -> Unit,
    onSelect: (WorkoutCalendarDayRange) -> Unit, onShowAll: () -> Unit,
) {
    val firstWeekday = Calendar.getInstance().firstDayOfWeek
    val days = WorkoutCalendarPolicy.days(anchor, zone, mode, firstWeekday)
    val anchorDate = LocalDate.ofEpochDay(WorkoutCalendarPolicy.day(anchor, zone).day)
    val today = WorkoutCalendarPolicy.day(System.currentTimeMillis(), zone).day
    val locale = Locale.getDefault()
    val month = DateTimeFormatter.ofPattern("MMMM yyyy", locale)
    val short = DateTimeFormatter.ofPattern("MMM d", locale)
    val complete = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", locale)
    val title = if (mode == WorkoutCalendarMode.month) anchorDate.format(month)
        else "${LocalDate.ofEpochDay(days.first().day).format(short)} – ${LocalDate.ofEpochDay(days.last().day).format(short)}, ${LocalDate.ofEpochDay(days.last().day).year}"
    fun move(amount: Int) {
        val next = WorkoutCalendarPolicy.move(anchor, zone, mode, firstWeekday, amount)
        onAnchor(next); onSelect(WorkoutCalendarPolicy.day(next, zone))
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Calendar", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { val now = System.currentTimeMillis(); onAnchor(now); onSelect(WorkoutCalendarPolicy.day(now, zone)) }) { Text("Today") }
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            WorkoutCalendarMode.entries.forEachIndexed { index, value ->
                SegmentedButton(selected = mode == value, onClick = { onMode(value) }, shape = SegmentedButtonDefaults.itemShape(index, 2),
                    modifier = Modifier.testTag("calendar-mode-${value.name}")) { Text(if (value == WorkoutCalendarMode.week) "Week" else "Month") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { move(-1) }, modifier = Modifier.semantics { contentDescription = "Previous ${mode.name}" }) { Text("‹", style = MaterialTheme.typography.headlineSmall) }
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            TextButton(onClick = { move(1) }, modifier = Modifier.semantics { contentDescription = "Next ${mode.name}" }) { Text("›", style = MaterialTheme.typography.headlineSmall) }
        }
        Row(Modifier.fillMaxWidth()) {
            days.take(7).forEach { day -> Text(LocalDate.ofEpochDay(day.day).format(DateTimeFormatter.ofPattern("EEE", locale)),
                Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        days.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                week.forEach { day ->
                    val item = activity.firstOrNull { it.day == day.day }
                    val hasWorkout = item?.workoutIds?.isNotEmpty() == true
                    val isSelected = selected?.day == day.day
                    val isToday = today == day.day
                    val date = LocalDate.ofEpochDay(day.day)
                    val inMonth = mode == WorkoutCalendarMode.week || date.month == anchorDate.month
                    val textColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (inMonth) 1f else .4f)
                    val description = date.format(complete) + if (hasWorkout) ", ${item!!.workoutIds.size} workouts, ${item.completedReps + item.loggedReps} reps" else if (loading) ", loading activity" else ", no workouts"
                    Surface(onClick = { onAnchor(day.startEpochMilliseconds); onSelect(day) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("calendar-day-${day.day}").semantics { contentDescription = description; this.selected = isSelected },
                        shape = RoundedCornerShape(11.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                        border = if (isSelected || isToday) BorderStroke(if (isSelected) 2.dp else 1.dp, MaterialTheme.colorScheme.primary) else null) {
                        Column(Modifier.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("${date.dayOfMonth}", style = if (isToday || isSelected) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium, color = textColor)
                            Surface(Modifier.size(5.dp), shape = CircleShape, color = if (hasWorkout) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent) {}
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(5.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary) {}
            Text("Workout", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (loading) CircularProgressIndicator(Modifier.padding(start = 8.dp).size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.weight(1f))
            if (selected != null) TextButton(onClick = onShowAll) { Text("All workouts") }
        }
    }
}
