package dev.hanginthere.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.hanginthere.android.session.WorkoutGoalStore
import dev.hanginthere.android.session.WorkoutRecordStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.hanginthere.core.*
import java.util.Locale
import java.util.Date
import java.text.DateFormat

/** Read-only: presentation does not send pause/configuration controls to inference. */
@Composable
fun WorkoutReviewDialog(current: List<RepRecord>, history: List<WorkoutSetRecord>, error: String?, currentSet: WorkoutSetRecord? = null, goalsEnabled: Boolean = true, onClose: () -> Unit) {
    var selected by remember { mutableStateOf<String?>(null) }
    var selectedSet by remember { mutableStateOf<String?>(null) }
    var selectedAttempt by remember { mutableStateOf<String?>(null) }
    var exercise by remember { mutableStateOf(currentSet?.exercise ?: history.firstOrNull()?.exercise ?: ExerciseCounter.Exercise.pullUp) }
    var editingGoal by remember { mutableStateOf(false) }
    var goalRevision by remember { mutableIntStateOf(0) }
    val context=LocalContext.current
    val goalStore=remember(context) { WorkoutGoalStore(context.applicationContext) }
    var archive by remember { mutableStateOf<List<WorkoutProgressEvidence>>(emptyList()) }
    var archiveLoaded by remember { mutableStateOf(!goalsEnabled) }
    var archiveError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(history,goalsEnabled) {
        if(goalsEnabled)runCatching { withContext(Dispatchers.IO) { WorkoutRecordStore.live(context.applicationContext).progressEvidence(currentSet?.id.takeIf { currentSet?.endReason=="running" }) } }
            .onSuccess { archive=it;archiveLoaded=true;archiveError=null }.onFailure { if(it is kotlinx.coroutines.CancellationException)throw it;archiveError=it.message }
    }
    val progress=remember(history,archive,archiveLoaded,exercise,goalRevision,goalsEnabled) {
        runCatching {
            val recent=history.map(WorkoutProgression::evidence)
            val evidence=recent+archive.filter { old -> recent.none { it.id==old.id } }
            if(goalsEnabled && archiveLoaded) goalStore.reportEvidence(evidence,exercise) else WorkoutProgression.reportEvidence(evidence,exercise)
        }
    }
    val practiceHistory=history.filter { it.hasPractice }
    val practice=practiceHistory.firstOrNull { it.id==selectedSet } ?: currentSet ?: practiceHistory.firstOrNull()
    val attempts=(practice?.reps ?: current).filter { !it.isCompleteForAnalytics }
    val completedCurrent = current.filter { it.isCompleteForAnalytics }
    val completedHistory = history.filter { it.completedCount > 0 }
    val reps = if(selectedSet!=null || currentSet!=null || current.isEmpty()) practice?.completedReps.orEmpty() else completedCurrent
    val all = (completedCurrent + completedHistory.flatMap { it.completedReps }).distinctBy { it.id }
    val rep = all.firstOrNull { it.id == selected } ?: reps.lastOrNull()
    AlertDialog(onDismissRequest = onClose, title = { Text("Workout analysis") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            practice?.let { WorkoutPracticePanel(it) }
            if (reps.isEmpty() && rep == null) Text("Hangs, support holds and partial attempts are practice. Completed cycles appear in rep timing.")
            if (reps.isNotEmpty()) {
                Text("Rep timing", style = MaterialTheme.typography.titleMedium)
                Text("Blue: outward · orange: return · tap a rep to inspect")
                Text("Only completed reps are included; unfinished attempts are excluded.")
                val maximum = maxOf(1.0, reps.maxOf { (it.outboundSeconds ?: 0.0) + (it.returnSeconds ?: 0.0) })
                reps.forEach { item ->
                    Text("Rep ${item.ordinal} · ${seconds(item.outboundSeconds)} / ${seconds(item.returnSeconds)}",
                        Modifier.clickable { selected = item.id })
                    Canvas(Modifier.fillMaxWidth().height(14.dp).clickable { selected = item.id }.semantics {
                        contentDescription = "Rep ${item.ordinal}, outward ${seconds(item.outboundSeconds)}, return ${seconds(item.returnSeconds)}"
                    }) {
                        val outward = (item.outboundSeconds ?: 0.0) / maximum * size.width
                        val back = (item.returnSeconds ?: 0.0) / maximum * size.width
                        drawLine(Color.Cyan, Offset.Zero.copy(y = size.height / 2), Offset(outward.toFloat(), size.height / 2), size.height, StrokeCap.Butt)
                        drawLine(Color(0xFFFF9800), Offset(outward.toFloat(), size.height / 2), Offset((outward + back).toFloat(), size.height / 2), size.height, StrokeCap.Butt)
                    }
                }
            }
            rep?.let { item ->
                Text("Rep ${item.ordinal} · ${item.outcome}", style = MaterialTheme.typography.titleMedium)
                AttemptEvidence(item, if(currentSet?.id==item.setId) currentSet?.endReason else history.firstOrNull { it.id==item.setId }?.endReason)
                if(item.samples.isEmpty()) Text("Detailed motion is unavailable. The rep summary is preserved.") else AnglePlot(item)
                Text("Time to extreme: ${seconds(item.outboundSeconds)}\nReturn: ${seconds(item.returnSeconds)}\n" +
                    "Left mean / range: ${angles(item.left)}\nRight mean / range: ${angles(item.right)}")
                if (item.curveTruncated && item.samples.isNotEmpty()) Text("The curve shows recent motion; averages use all observed intervals.")
            }
            if(attempts.isNotEmpty()) {
                var expanded by remember { mutableStateOf(false) }
                TextButton(onClick={expanded=!expanded}) { Text("Practice attempts · ${attempts.size}") }
                if(expanded) {
                    Text("Review what was observed and why a completed cycle was not recorded. These attempts are excluded from completed-rep statistics.")
                    attempts.forEach { item ->
                        val evidence=remember(item,practice?.endReason) { WorkoutEvidence.assess(item,practice?.endReason) }
                        TextButton(onClick={selectedAttempt=item.id}) { Text("Attempt ${item.ordinal} · ${evidence.title}") }
                    }
                    attempts.firstOrNull { it.id==selectedAttempt }?.let { item ->
                        Text("Attempt ${item.ordinal} · incomplete",style=MaterialTheme.typography.titleMedium)
                        AttemptEvidence(item,practice?.endReason)
                        if(item.samples.isNotEmpty())AnglePlot(item)
                        Text("Left mean / range: ${angles(item.left)}\nRight mean / range: ${angles(item.right)}")
                    }
                }
            }
            Row { TextButton(onClick={exercise=ExerciseCounter.Exercise.pullUp}) { Text("Pull-up progress") };TextButton(onClick={exercise=ExerciseCounter.Exercise.dip}) { Text("Dip progress") } }
            archiveError?.let { Text("Progress history could not be loaded: $it") }
            if(!archiveLoaded)Text("Loading progress history…")
            progress.getOrNull()?.let { WorkoutProgressPanel(it,if(goalsEnabled && archiveLoaded)({editingGoal=true}) else null) }
            progress.exceptionOrNull()?.let { Text("Progress could not be loaded: ${it.message}") }
            if (practiceHistory.isNotEmpty()) Text("Saved sets", style = MaterialTheme.typography.titleMedium)
            practiceHistory.forEach { set ->
                val ending=when(set.endReason){"disengaged" -> "Automatically completed";"manual" -> "Ended manually";else -> "Interrupted"}
                Text("${set.exercise.title} · ${set.completedCount} ${if(set.completedCount==1) "rep" else "reps"} · $ending", Modifier.clickable { selectedSet=set.id;selected=set.completedReps.lastOrNull()?.id;selectedAttempt=null }, style = MaterialTheme.typography.titleSmall)
                set.trackedAtEpochMilliseconds?.let { time ->
                    Text(remember(time) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(Date(time)) }, style = MaterialTheme.typography.bodySmall)
                }
                set.completedReps.forEach { item -> Text("Rep ${item.ordinal} · ${seconds(item.outboundSeconds)} / ${seconds(item.returnSeconds)}",
                    Modifier.clickable { selected = item.id; selectedSet=set.id }) }
            }
            Text("Angles are projected camera measurements. Depth, lockout and exercise form are not verified.")
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text("Done") } })
    if(editingGoal)progress.getOrNull()?.let { report ->
        WorkoutGoalEditor(report,onSave={metric,target -> goalStore.setGoal(report.exercise,metric,target);goalRevision++},onClose={editingGoal=false})
    }
}
@Composable
private fun AttemptEvidence(rep: RepRecord, setEndReason: String?) {
    val evidence=remember(rep,setEndReason) { WorkoutEvidence.assess(rep,setEndReason) }
    Surface(shape=MaterialTheme.shapes.medium, color=MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(evidence.title,style=MaterialTheme.typography.titleSmall)
            Text(evidence.explanation)
            evidence.reason?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
            Text("Observed time: ${coverage(evidence.durationCoverage)}\nLeft-arm time: ${coverage(evidence.leftCoverage)}\nRight-arm time: ${coverage(evidence.rightCoverage)}",style=MaterialTheme.typography.bodySmall)
            evidence.notes.forEach { Text(it,style=MaterialTheme.typography.bodySmall) }
            if(!evidence.angleComparisonEligible)Text("Range is excluded from progress comparisons.",style=MaterialTheme.typography.bodySmall)
        }
    }
}
private fun coverage(value: Double?): String = value?.let { String.format(Locale.ROOT,"%.0f%%",it*100) } ?: "Unavailable"
@Composable
private fun AnglePlot(rep: RepRecord) {
    Text("Elbow angle (0–180°) over seconds · left blue / right orange")
    Canvas(Modifier.fillMaxWidth().height(180.dp).semantics { contentDescription = "Left and right elbow angles for ${if(rep.isCompleteForAnalytics) "rep" else "attempt"} ${rep.ordinal}; missing intervals are gaps" }) {
        val duration = maxOf(.001, rep.closedAt - rep.startedAt)
        fun point(sample: MotionSample, angle: Double) = Offset(((sample.seconds - rep.startedAt) / duration * size.width).toFloat(), ((1 - angle / 180) * size.height).toFloat())
        for (right in listOf(false, true)) {
            var previous: Pair<MotionSample, Double>? = null
            rep.samples.forEach { sample ->
                val angle = if (right) sample.rightDegrees else sample.leftDegrees
                if (!sample.usable || angle == null) previous = null
                else {
                    previous?.let { (old, value) -> if (sample.seconds - old.seconds <= ExerciseCounter.maximumGapSeconds)
                        drawLine(if (right) Color(0xFFFF9800) else Color.Cyan, point(old, value), point(sample, angle), 2.dp.toPx(), StrokeCap.Round) }
                    previous = sample to angle
                }
            }
        }
    }
    Text("0 s → ${seconds(rep.closedAt - rep.startedAt)}")
}
private fun seconds(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.2f s", it) } ?: "Unavailable"
private fun angles(value: AngleMetrics): String = if (value.mean != null && value.minimum != null && value.maximum != null)
    String.format(Locale.ROOT, "%.0f° / %.0f–%.0f°", value.mean, value.minimum, value.maximum) else "Unavailable"
