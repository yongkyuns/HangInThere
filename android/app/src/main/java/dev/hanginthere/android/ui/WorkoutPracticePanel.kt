package dev.hanginthere.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.hanginthere.core.*
import java.util.Locale

@Composable
internal fun WorkoutPracticePanel(set: WorkoutSetRecord) {
    Text("Set phases", style=MaterialTheme.typography.titleMedium)
    Text("${set.exercise.title} · ${set.completedCount} completed reps · ${set.reps.size-set.completedCount} incomplete attempts")
    val activity=set.activity
    if(activity==null) { Text("Phase measurements are unavailable for this older set. Its rep summary is preserved.");return }
    val phase=if(set.exercise==ExerciseCounter.Exercise.pullUp)WorkoutMotionPhase.hang else WorkoutMotionPhase.support
    val longest=if(phase==WorkoutMotionPhase.hang)activity.longestHangSeconds else activity.longestSupportSeconds
    Text("Longest ${phase.title.lowercase()}: ${duration(longest)} · total ${duration(activity.seconds(phase))}")
    val start=set.startedAt ?: activity.phases.firstOrNull()?.startedAt ?: 0.0
    val elapsed=maxOf(.001,(set.endedAt ?: start)-start)
    activity.phases.groupBy { it.phase }.forEach { (kind,spans) ->
        Text("${kind.title} · ${duration(activity.seconds(kind))}", style=MaterialTheme.typography.labelMedium)
        Canvas(Modifier.fillMaxWidth().height(14.dp).semantics {
            contentDescription="${kind.title}: ${duration(activity.seconds(kind))} observed"
        }) {
            spans.forEach { span ->
                val x=((span.startedAt-start)/elapsed*size.width).toFloat()
                val end=((span.endedAt-start)/elapsed*size.width).toFloat()
                val color=when(kind) {
                    WorkoutMotionPhase.hang,WorkoutMotionPhase.support -> Color(0xFF80CBC4)
                    WorkoutMotionPhase.lowering -> Color(0xFFFF9800)
                    WorkoutMotionPhase.peakHold,WorkoutMotionPhase.bottomHold,WorkoutMotionPhase.effortHold -> Color(0xFFFFD54F)
                    WorkoutMotionPhase.release,WorkoutMotionPhase.unobserved,WorkoutMotionPhase.acquiring -> Color.Gray
                    else -> Color.Cyan
                }
                if(end>x) drawLine(color,Offset(x,size.height/2),Offset(end,size.height/2),size.height)
            }
        }
    }
    Text("0 s → ${duration(elapsed)}. Only fresh observations add hold time; gaps and hidden arms split a continuous hold.",style=MaterialTheme.typography.bodySmall)
    if(activity.timelineTruncated)Text("The timeline shows recent phases; totals include all observed intervals.")
}

@Composable
internal fun WorkoutProgressPanel(report: WorkoutProgressReport, onEditGoal: (() -> Unit)?) {
    Text("Your progress",style=MaterialTheme.typography.titleMedium)
    Text(report.coaching)
    Text("Best ${report.holdTitle.lowercase()}: ${report.bestHoldSeconds?.let(::duration) ?: "Unavailable"} · ${report.completedReps} completed reps")
    report.bestElbowExcursionDegrees?.let { Text(String.format(Locale.ROOT,"Best observed both-arm range: %.0f°",it)) }
    Text(if(report.goal.createdAtEpochMilliseconds==null) "Suggested next target" else if(report.goalAchieved) "Goal reached" else "Current goal",style=MaterialTheme.typography.titleSmall)
    Text(if(report.goal.metric==WorkoutGoalMetric.holdSeconds) "${duration(report.goal.target)} continuous ${report.holdTitle.lowercase()}" else "${report.goal.target.toInt()} completed reps in a set")
    LinearProgressIndicator(progress={ (report.goalFraction ?: 0.0).toFloat() },modifier=Modifier.fillMaxWidth())
    Text(report.goalBest?.let { if(report.goal.metric==WorkoutGoalMetric.holdSeconds) "${duration(it)} ${if(report.goal.createdAtEpochMilliseconds==null) "baseline" else "since goal was set"}" else "${it.toInt()} reps ${if(report.goal.createdAtEpochMilliseconds==null) "baseline" else "since goal was set"}" } ?: "Track a new set to work toward this goal.")
    onEditGoal?.let { TextButton(onClick=it) { Text("Edit goal") } }
    var holdMetric by remember { mutableStateOf(true) }
    if(report.trend.isNotEmpty()) {
        Row { TextButton(onClick={holdMetric=true}) { Text(report.holdTitle) }; TextButton(onClick={holdMetric=false}) { Text("Reps") } }
        Text(if(holdMetric)"Longest hold (s) · recent sets" else "Completed reps · recent sets",style=MaterialTheme.typography.labelMedium)
        val values=report.trend.map { if(holdMetric) it.holdSeconds else it.completedReps.toDouble() }
        val maximum=maxOf(1.0,values.filterNotNull().maxOrNull() ?: 0.0)
        Text("0 → ${if(holdMetric)duration(maximum) else "${maximum.toInt()} reps"}",style=MaterialTheme.typography.bodySmall)
        Canvas(Modifier.fillMaxWidth().height(140.dp).semantics {
            contentDescription=report.trend.zip(values).joinToString("; ") { (point,value) ->
                "Set ${point.id}: ${value?.let { if(holdMetric)duration(it) else "${it.toInt()} reps" } ?: "Unavailable"}"
            }
        }) {
            values.forEachIndexed { index,value -> value?.let {
                val x=(index+.5f)/values.size*size.width
                val y=(1-it/maximum).toFloat()*(size.height-12.dp.toPx())+6.dp.toPx()
                drawCircle(Color(0xFF80CBC4),4.dp.toPx(),Offset(x,y))
            } }
        }
        Text(java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(java.util.Date(report.trend.first().trackedAtEpochMilliseconds)) + " → " +
            java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(java.util.Date(report.trend.last().trackedAtEpochMilliseconds)) + " · ${report.trend.size} sets",style=MaterialTheme.typography.bodySmall)
    }
    var milestones by remember { mutableStateOf(false) }
    TextButton(onClick={milestones=!milestones}) { Text("Milestones · ${report.milestones.count { it.achieved }} reached") }
    if(milestones)report.milestones.forEach { milestone ->
        Text("${if(milestone.achieved) "✓" else "○"} ${milestone.title}")
        milestone.achievedAtEpochMilliseconds?.let { Text(java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(it)),style=MaterialTheme.typography.bodySmall) }
    }
}

@Composable
internal fun WorkoutGoalEditor(report: WorkoutProgressReport, onSave: (WorkoutGoalMetric,Double)->Unit, onClose: ()->Unit) {
    var metric by remember { mutableStateOf(report.goal.metric) }
    var target by remember { mutableStateOf(report.goal.target.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest=onClose,title={Text("Workout goal")},text={Column {
        Text("Choose a target for future ${report.exercise.title.lowercase()} sets. Adjust it whenever you need.")
        Row { TextButton(onClick={metric=WorkoutGoalMetric.holdSeconds}) { Text(report.holdTitle) }; TextButton(onClick={metric=WorkoutGoalMetric.completedReps}) { Text("Reps") } }
        Text(if(metric==WorkoutGoalMetric.holdSeconds)"Continuous hold seconds" else "Completed reps in one set")
        OutlinedTextField(value=target,onValueChange={target=it},label={Text("Target")},singleLine=true)
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    }},confirmButton={TextButton(onClick={
        runCatching { val value=requireNotNull(target.toDoubleOrNull()); WorkoutGoal(report.exercise,metric,value).validate();onSave(metric,value) }
            .onSuccess { onClose() }.onFailure { error="Enter whole reps (1–1000) or hold seconds (1–3600)." }
    }) { Text("Save") }},dismissButton={TextButton(onClick=onClose) { Text("Cancel") }})
}
private fun duration(value: Double)=String.format(Locale.ROOT,"%.1f s",value)
