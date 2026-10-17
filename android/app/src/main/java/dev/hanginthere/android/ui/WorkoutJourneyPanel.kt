package dev.hanginthere.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.hanginthere.core.*
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.launch

internal fun WorkoutQuality.color(): Color = when(level) {
    "steady" -> Color(0xFF008577)
    "pace", "range" -> Color(0xFFB96900)
    "practice" -> Color(0xFF6554C0)
    "limited" -> Color(0xFF737780)
    else -> Color(0xFF247DB3)
}
@Composable internal fun JourneyQualityBadge(quality: WorkoutQuality) {
    Surface(shape=RoundedCornerShape(50),color=quality.color().copy(alpha=.12f),modifier=Modifier.semantics {
        contentDescription="${quality.title}. ${quality.explanation}"
    }) { Text(quality.title,Modifier.padding(horizontal=10.dp,vertical=7.dp),color=MaterialTheme.colorScheme.onSurface,style=MaterialTheme.typography.labelMedium) }
}
@Composable internal fun JourneyMetric(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier,verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(value,style=MaterialTheme.typography.headlineSmall)
        Text(label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable internal fun JourneyCoachingCard(coaching: WorkoutCoaching) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Text(coaching.title,style=MaterialTheme.typography.titleMedium)
            Text(coaching.message,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable internal fun WorkoutJourneyPanel(
    report: WorkoutJourneyReport,
    loadPage: suspend (Long?, WorkoutJourneyCursor?) -> WorkoutJourneyPage,
    modifier: Modifier = Modifier,
    onOpenSet: (WorkoutSetDigest) -> Unit
) {
    var selectedWorkoutId by remember { mutableStateOf<String?>(null) }
    var selectedDay by remember { mutableStateOf<Long?>(null) }
    var loadedWorkouts by remember { mutableStateOf<List<WorkoutSessionDigest>?>(null) }
    var nextCursor by remember { mutableStateOf<WorkoutJourneyCursor?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var requestVersion by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    suspend fun load(more: Boolean) {
        val request = ++requestVersion
        if (!more) {
            loadedWorkouts = null
            nextCursor = null
            failure = null
        }
        loading = true
        try {
            val cursor = if (!more) null else if (loadedWorkouts == null && selectedDay == null) report.nextCursor else nextCursor
            val page = loadPage(selectedDay, cursor)
            if (request != requestVersion) return
            loadedWorkouts = if (more) (loadedWorkouts ?: report.workouts) + page.workouts else page.workouts
            nextCursor = page.nextCursor
            failure = null
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            if (request == requestVersion) failure = error.message
        } finally {
            if (request == requestVersion) loading = false
        }
    }
    LaunchedEffect(selectedDay, report) {
        if (selectedDay == null) {
            requestVersion++
            loading = false
            loadedWorkouts = null
            nextCursor = report.nextCursor
            failure = null
        } else load(false)
    }
    val workout = (loadedWorkouts ?: report.workouts).firstOrNull { it.id == selectedWorkoutId }
    val historyScroll = rememberLazyListState()
    val detailScroll = rememberLazyListState()
    LaunchedEffect(selectedWorkoutId) { if (selectedWorkoutId != null) detailScroll.scrollToItem(0) }
    if (workout != null) {
        LazyColumn(modifier.testTag("workout-history-list"), state = detailScroll,
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    TextButton(onClick = { selectedWorkoutId = null }) { Text("‹ All workouts") }
                    Text(workout.epochMilliseconds?.let { DateFormat.getDateInstance().format(Date(it)) } ?: "Undated workout",
                        style = MaterialTheme.typography.headlineSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        JourneyMetric("${workout.completedReps}", "Completed reps", Modifier.weight(1f))
                        JourneyMetric("${workout.setCount}", "Sets", Modifier.weight(1f))
                    }
                    JourneyQualityBadge(workout.quality)
                    Text("Sets", style = MaterialTheme.typography.titleLarge)
                }
            }
            itemsIndexed(workout.sets, key = { _, set -> set.id }) { index, set ->
                Card(onClick = { onOpenSet(set) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Set ${index + 1} · ${set.exercise.title}", style = MaterialTheme.typography.titleMedium)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (set.completedReps > 0) "${set.completedReps} completed reps"
                                else set.holdSeconds?.let { "${journeySeconds(it)} hold" } ?: "Practice")
                            JourneyQualityBadge(set.quality)
                        }
                    }
                }
            }
        }
        return
    }
    val ids = report.days.firstOrNull { it.day == selectedDay }?.workoutIds
    val visible = loadedWorkouts ?: report.workouts.filter { ids == null || it.id in ids }
    LazyColumn(modifier.testTag("workout-history-list"), state = historyScroll,
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Last 28 days", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    JourneyMetric("${report.completedReps}", "Completed reps", Modifier.weight(1f))
                    JourneyMetric("${report.workoutCount}", "Workouts", Modifier.weight(1f))
                    JourneyMetric("${report.practiceDays}", "Practice days", Modifier.weight(1f))
                }
                JourneyCoachingCard(report.coaching)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Your practice", style = MaterialTheme.typography.titleMedium)
                    if (selectedDay != null) TextButton(onClick = { selectedDay = null }) { Text("Show all") }
                }
                JourneyCalendar(report.days, selectedDay) { selectedDay = if (selectedDay == it) null else it }
                Text("Consistent · Varied · Recorded · Practice · Limited view",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(selectedDay?.let { dayLabel(it, "MMMM d") } ?: "Recent workouts", style = MaterialTheme.typography.titleLarge)
                if (visible.isEmpty()) Text(if (selectedDay == null) "Your first workout starts here" else "No recent workout on this day",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(visible, key = { it.id }) { session ->
            JourneyWorkoutCard(session) { selectedWorkoutId = session.id }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                failure?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (loading) CircularProgressIndicator()
                val cursor = if (loadedWorkouts == null && selectedDay == null) report.nextCursor else nextCursor
                if (cursor != null) TextButton(onClick = { scope.launch { load(true) } }, enabled = !loading) { Text("Earlier workouts") }
            }
        }
    }
}

@Composable private fun JourneyCalendar(days: List<WorkoutPracticeDay>, selectedDay: Long?, onSelect: (Long) -> Unit) {
    days.chunked(7).forEach { week ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            week.forEach { day ->
                val color = day.quality?.color() ?: MaterialTheme.colorScheme.onSurfaceVariant
                Surface(onClick = { onSelect(day.day) }, modifier = Modifier.weight(1f).height(48.dp).semantics {
                    contentDescription = "${dayLabel(day.day, "EEEE, MMMM d")}, ${day.quality?.title ?: "Rest day"}, ${day.completedReps} completed reps"
                }, shape = RoundedCornerShape(9.dp), color = color.copy(alpha = if (day.quality == null) .04f else .12f),
                    border = if (selectedDay == day.day) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface) else null) {
                    Column(Modifier.padding(vertical = 5.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        Text(dayLabel(day.day, "d"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(if (day.quality == null) "−" else if (day.quality?.level == "steady") "✓" else "•",
                            style = MaterialTheme.typography.labelSmall, color = color)
                    }
                }
            }
        }
    }
}

@Composable private fun JourneyWorkoutCard(session: WorkoutSessionDigest, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(session.epochMilliseconds?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) }
                ?: "Undated workout", style = MaterialTheme.typography.titleMedium)
            Text(session.exercises.joinToString(" · ") { it.title }, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${session.completedReps} reps · ${session.setCount} ${if (session.setCount == 1) "set" else "sets"}")
                JourneyQualityBadge(session.quality)
            }
        }
    }
}

@Composable internal fun JourneySetHeadline(set: WorkoutSetDigest) {
    var explain by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            Text(set.exercise.title,style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.clickable { explain=true }) { JourneyQualityBadge(set.quality) }
            val hold=set.holdSeconds?.takeIf { it>0 }
            Text(if(set.completedReps>0)"${set.completedReps}" else hold?.let { String.format(Locale.ROOT,"%.1f",it) } ?: "Practice",style=MaterialTheme.typography.displayLarge)
            Text(if(set.completedReps>0) if(set.completedReps==1)"completed rep" else "completed reps" else if(hold!=null)"seconds of practice" else "Holds and attempts count",style=MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                set.holdSeconds?.let { JourneyMetric(journeySeconds(it),if(set.exercise==ExerciseCounter.Exercise.pullUp)"Longest hang" else "Support hold",Modifier.weight(1f)) }
                set.outwardSeconds?.let { JourneyMetric(journeySeconds(it),if(set.exercise==ExerciseCounter.Exercise.pullUp)"To peak" else "To bottom",Modifier.weight(1f)) }
                set.returnSeconds?.let { JourneyMetric(journeySeconds(it),"Return",Modifier.weight(1f)) }
            }
            if(set.reportedReps>0)Text("${set.reportedReps} user-reported reps",style=MaterialTheme.typography.labelMedium)
        }
    }
    if(explain)AlertDialog(onDismissRequest={explain=false},title={Text(set.quality.title)},text={Text(set.quality.explanation)},confirmButton={TextButton(onClick={explain=false}) { Text("Done") }})
}
@Composable internal fun JourneyRepRow(rep: WorkoutRepDigest, maximum: Double, onClick:()->Unit) {
    Card(onClick=onClick,modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text(if(rep.status=="completed")"Rep ${rep.ordinal}" else "Attempt ${rep.ordinal}",style=MaterialTheme.typography.titleSmall)
                Text(if(rep.status=="completed")rep.totalSeconds?.let(::journeySeconds) ?: "—" else if(rep.status=="reported")"Reported" else if(rep.status=="unobserved")"Unconfirmed" else "Practice",style=MaterialTheme.typography.labelMedium)
            }
            if(rep.status=="completed" && rep.totalSeconds!=null)Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                val outward=((rep.outwardSeconds ?: 0.0)/maximum*size.width).toFloat()
                val back=((rep.returnSeconds ?: 0.0)/maximum*size.width).toFloat()
                drawLine(Color(0xFF247DB3),Offset(0f,size.height/2),Offset(outward,size.height/2),size.height,StrokeCap.Round)
                drawLine(Color(0xFF247DB3).copy(alpha=.35f),Offset(outward,size.height/2),Offset(outward+back,size.height/2),size.height,StrokeCap.Round)
            }
        }
    }
}
internal fun journeySeconds(value: Double): String=String.format(Locale.ROOT,"%.1fs",value)
private fun dayLabel(day:Long,format:String):String=SimpleDateFormat(format,Locale.getDefault()).apply { timeZone=TimeZone.getTimeZone("UTC") }.format(Date(day*86400000))
