package dev.hanginthere.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.hanginthere.core.*
import java.util.Locale

/** Read-only review routes: overview -> set -> rep. No camera-state mutations. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutReviewDialog(current: List<RepRecord>, history: List<WorkoutSetRecord>, error: String?,
    currentSet: WorkoutSetRecord? = null, goalsEnabled: Boolean = true, showsLibraryLink: Boolean = true,
    summaryOverride: WorkoutSetDigest? = null, onClose: () -> Unit) {
    var selectedRep by remember { mutableStateOf<String?>(null) }
    var selectedSetId by remember { mutableStateOf<String?>(null) }
    var setsMenu by remember { mutableStateOf(false) }
    var library by remember { mutableStateOf(false) }
    val localSets = currentSet?.let { listOf(it) + history.filter { old -> old.id != it.id } } ?: history
    val record=localSets.firstOrNull { it.id==selectedSetId } ?: currentSet ?: history.firstOrNull()
    val summary=remember(record,summaryOverride) { summaryOverride ?: record?.let(WorkoutJourney::set) }
    val sources=if(selectedSetId!=null)record?.reps.orEmpty() else currentSet?.reps ?: history.takeIf { it.isNotEmpty() }?.flatMap { it.reps } ?: current
    val selected=sources.firstOrNull { it.id==selectedRep }
    val completed=summary?.reps.orEmpty().filter { it.status=="completed" }
    val attempts=summary?.reps.orEmpty().filter { it.status!="completed" && it.status!="excluded" }
    val maximum=maxOf(1.0,completed.mapNotNull { it.totalSeconds }.maxOrNull() ?: 0.0)
    val showsSourcePhases=summaryOverride==null || record!=null && summaryOverride.id==record.id &&
        summaryOverride.exercise==record.exercise && summaryOverride.completedReps==record.completedCount &&
        summaryOverride.reportedReps==0 && summaryOverride.practiceAttempts==record.reps.size-record.completedCount
    BackHandler(enabled=selectedRep!=null) { selectedRep=null }
    Dialog(onDismissRequest={if(selectedRep!=null)selectedRep=null else onClose()},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                TopAppBar(title={Text(if(selected==null)"Set summary" else "Rep details")},navigationIcon={
                    if(selected!=null)TextButton(onClick={selectedRep=null}) { Text("Back") }
                },actions={
                    if(goalsEnabled && showsLibraryLink && selected==null)TextButton(onClick={library=true}) { Text("History") }
                    if(!goalsEnabled && summaryOverride==null && localSets.size>1 && selected==null)Box {
                        TextButton(onClick={setsMenu=true}) { Text("Sets") }
                        DropdownMenu(expanded=setsMenu,onDismissRequest={setsMenu=false}) {
                            localSets.forEachIndexed { index, set ->
                                DropdownMenuItem(text={Text("Set ${index+1} · ${set.exercise.title}")},onClick={selectedSetId=set.id;selectedRep=null;setsMenu=false})
                            }
                        }
                    }
                    TextButton(onClick=onClose) { Text("Done") }
                })
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                    error?.let { Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
                    if(selected!=null) {
                        val digest=summary?.reps?.firstOrNull { it.id==selected.id }
                        Text(if(digest?.status=="completed")"Rep ${selected.ordinal}" else "Attempt ${selected.ordinal}",style=MaterialTheme.typography.headlineSmall)
                        if(digest?.status!="completed" && !selected.isCompleteForAnalytics) {
                            val assessment=WorkoutEvidence.assess(selected,record?.endReason)
                            Text(assessment.title,style=MaterialTheme.typography.titleMedium)
                            assessment.reason?.let { Text(it,style=MaterialTheme.typography.bodyMedium) }
                        }
                        if(selected.samples.isEmpty())Text("Motion curve unavailable. The timing summary is saved.") else AnglePlot(selected)
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(18.dp)) {
                            JourneyMetric(selected.outboundSeconds?.let(::journeySeconds) ?: "—",if(selected.exercise==ExerciseCounter.Exercise.pullUp)"To peak" else "To bottom",Modifier.weight(1f))
                            JourneyMetric(selected.returnSeconds?.let(::journeySeconds) ?: "—","Return",Modifier.weight(1f))
                        }
                        JourneyDisclosure("Camera measurements") {
                            Text("Left mean / range: ${angles(selected.left)}\nRight mean / range: ${angles(selected.right)}")
                        }
                        JourneyDisclosure("Original recording") {
                            AttemptEvidence(selected,history.firstOrNull { it.id==selected.setId }?.endReason ?: record?.endReason)
                        }
                    } else if(summary!=null) {
                        JourneySetHeadline(summary)
                        JourneyCoachingCard(summary.coaching)
                        if(completed.isNotEmpty()) {
                            Text("Your reps",style=MaterialTheme.typography.titleLarge)
                            Text(if(summary.exercise==ExerciseCounter.Exercise.pullUp)"Pull · Lower" else "Lower · Press",style=MaterialTheme.typography.labelMedium)
                            completed.forEach { rep -> key(rep.id) { JourneyRepRow(rep,maximum) { selectedRep=rep.id } } }
                        }
                        if(attempts.isNotEmpty())JourneyDisclosure("Other attempts · ${attempts.size}") {
                            attempts.forEach { rep -> key(rep.id) { JourneyRepRow(rep,maximum) { selectedRep=rep.id } } }
                        }
                        if(record?.activity!=null && showsSourcePhases)JourneyDisclosure("Phase breakdown") { WorkoutPracticePanel(record) }
                        if(record?.exercise==ExerciseCounter.Exercise.pullUp && showsSourcePhases)JourneyDisclosure("Grip and variation") { WorkoutGripPanel(record,goalsEnabled) }
                    } else Text("Your next workout starts here",style=MaterialTheme.typography.headlineSmall)
                }
            }
        }
    }
    if(library)WorkoutLibraryDialog { library=false }
}
@Composable internal fun JourneyDisclosure(title:String,content:@Composable ColumnScope.()->Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        TextButton(onClick={expanded=!expanded}) { Text((if(expanded)"⌄ " else "› ")+title) }
        if(expanded)content()
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
