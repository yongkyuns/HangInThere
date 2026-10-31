package dev.hanginthere.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
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

internal fun WorkoutQuality.color(): Color = journeyQualityColor(level)
private fun journeyQualityColor(level: String): Color = when(level) {
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
    onOpenSet: (WorkoutSetDigest) -> Unit,
    revision: Long = 0,
    initialAnchor: Long = System.currentTimeMillis(),
    loadCalendar: (suspend (List<WorkoutCalendarDayRange>) -> List<WorkoutPracticeDay>)? = null,
    loadRange: (suspend (WorkoutCalendarDayRange?, WorkoutJourneyCursor?) -> WorkoutJourneyPage)? = null,
    onEditWorkout: (WorkoutSessionDigest) -> Unit = {},
) {
    var selectedWorkoutId by remember { mutableStateOf<String?>(null) }
    var selectedRange by remember { mutableStateOf<WorkoutCalendarDayRange?>(null) }
    val selectedDay = selectedRange?.day
    var anchor by remember { mutableLongStateOf(initialAnchor) }
    var mode by remember { mutableStateOf(WorkoutCalendarMode.month) }
    var showCalendar by remember { mutableStateOf(true) }
    var zone by remember { mutableStateOf(java.time.ZoneId.systemDefault().id) }
    val context = LocalContext.current
    DisposableEffect(context) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                zone = java.time.ZoneId.systemDefault().id; anchor = System.currentTimeMillis(); selectedRange = null
            }
        }
        val filter = android.content.IntentFilter(android.content.Intent.ACTION_TIMEZONE_CHANGED).apply { addAction(android.content.Intent.ACTION_DATE_CHANGED) }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    val days = WorkoutCalendarPolicy.days(anchor, zone, mode, java.util.Calendar.getInstance().firstDayOfWeek)
    var activity by remember { mutableStateOf<List<WorkoutPracticeDay>?>(null) }
    var calendarFailure by remember { mutableStateOf(false) }
    LaunchedEffect(days, report, revision) {
        if (loadCalendar != null) {
            activity = null; calendarFailure = false
            try { activity = loadCalendar(days) }
            catch (error: Throwable) { if (error is kotlinx.coroutines.CancellationException) throw error; calendarFailure = true }
        }
    }
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
            val page = if (loadRange != null) loadRange(selectedRange, cursor) else loadPage(selectedDay, cursor)
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
    LaunchedEffect(selectedRange, report, revision) {
        if (selectedDay == null) {
            requestVersion++
            loading = false
            loadedWorkouts = null
            nextCursor = report.nextCursor
            failure = null
        } else load(false)
    }
    val workout = (loadedWorkouts ?: report.workouts).firstOrNull { it.id == selectedWorkoutId }
    BackHandler(enabled = workout != null) { selectedWorkoutId = null }
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
                        JourneyMetric("${workout.totalReps}", "Reps", Modifier.weight(1f))
                        JourneyMetric("${workout.setCount}", "Sets", Modifier.weight(1f))
                    }
                    JourneyQualityBadge(workout.quality)
                    workout.workoutLog?.let { log ->
                        WorkoutLogTable(log) { onEditWorkout(workout) }
                        TextButton(onClick = { onEditWorkout(workout) }) { Text("Continue / edit workout") }
                    }
                    if (workout.sets.isNotEmpty()) Text("Camera sets", style = MaterialTheme.typography.titleLarge)
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
    val visible = loadedWorkouts ?: if (selectedDay == null) report.workouts else report.workouts.filter { ids != null && it.id in ids }
    Column(modifier) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            SegmentedButton(selected = showCalendar, onClick = { showCalendar = true }, shape = SegmentedButtonDefaults.itemShape(0, 2),
                modifier = Modifier.testTag("workout-layout-calendar")) { Text("Calendar") }
            SegmentedButton(selected = !showCalendar, onClick = { showCalendar = false; selectedRange = null }, shape = SegmentedButtonDefaults.itemShape(1, 2),
                modifier = Modifier.testTag("workout-layout-list")) { Text("List") }
        }
        LazyColumn(Modifier.weight(1f).testTag("workout-history-list"), state = historyScroll,
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (showCalendar) item {
                WorkoutCalendarPanel(anchor, mode, zone, selectedRange, activity ?: report.days, loadCalendar != null && activity == null && !calendarFailure,
                    onAnchor = { anchor = it }, onMode = { mode = it }, onSelect = { selectedRange = it }, onShowAll = { selectedRange = null })
                if (calendarFailure) Text("Calendar couldn't load. Close and reopen to retry.", color = MaterialTheme.colorScheme.error)
            }
            if (showCalendar && selectedDay == null) item {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Last 28 days", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        JourneyMetric("${report.completedReps + report.loggedReps}", "Reps", Modifier.weight(1f))
                        JourneyMetric("${report.workoutCount}", "Workouts", Modifier.weight(1f))
                        JourneyMetric("${report.practiceDays}", "Practice days", Modifier.weight(1f))
                    }
                    JourneyCoachingCard(report.coaching)
                }
            }
            item {
                Text(selectedDay?.let { dayLabel(it, "EEEE, MMMM d, yyyy") } ?: "Recent workouts", style = MaterialTheme.typography.titleLarge)
                if (visible.isEmpty() && !loading) Text(if (selectedDay == null) "Your first workout starts here" else "No workouts on this day",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(visible, key = { it.id }) { session -> JourneyWorkoutCard(session) { selectedWorkoutId = session.id } }
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
}

@Composable private fun JourneyWorkoutCard(session: WorkoutSessionDigest, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(session.epochMilliseconds?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) }
                ?: "Undated workout", style = MaterialTheme.typography.titleMedium)
            Text(session.exerciseNames.joinToString(" · "), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${session.totalReps} reps · ${session.setCount} ${if (session.setCount == 1) "set" else "sets"}")
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
            Text(if(set.completedReps>0)"${set.completedReps}" else hold?.let(::journeySeconds) ?: "Practice",style=MaterialTheme.typography.displayLarge)
            Text(if(set.completedReps>0) if(set.completedReps==1)"completed rep" else "completed reps" else if(hold!=null)
                if(set.exercise==ExerciseCounter.Exercise.pullUp)"Longest hang" else "Support hold" else "Holds and attempts",style=MaterialTheme.typography.titleMedium)
            if(set.completedReps>0 && set.holdSeconds!=null || set.outwardSeconds!=null || set.returnSeconds!=null) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    if(set.completedReps>0)set.holdSeconds?.let { JourneyMetric(journeySeconds(it),if(set.exercise==ExerciseCounter.Exercise.pullUp)"Longest hang" else "Support hold",Modifier.weight(1f)) }
                    set.outwardSeconds?.let { JourneyMetric(journeySeconds(it),if(set.exercise==ExerciseCounter.Exercise.pullUp)"To peak" else "To bottom",Modifier.weight(1f)) }
                    set.returnSeconds?.let { JourneyMetric(journeySeconds(it),"Return",Modifier.weight(1f)) }
                }
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
