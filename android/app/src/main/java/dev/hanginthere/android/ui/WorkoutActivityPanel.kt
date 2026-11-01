package dev.hanginthere.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.hanginthere.core.*
import kotlinx.coroutines.launch

@Composable internal fun WorkoutActivityPanel(report: WorkoutActivityReport,
    onSave: suspend (WorkoutActivityGoal) -> Boolean, onRemove: suspend (WorkoutActivityGoal) -> Boolean) {
    var editing by remember { mutableStateOf<WorkoutActivityGoal?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(Modifier.fillMaxWidth().testTag("weeklyWorkoutSummary")) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("This week", style = MaterialTheme.typography.titleMedium)
                Row {
                    JourneyMetric("${report.reps}","Reps",Modifier.weight(1f))
                    JourneyMetric("${report.sets}","Sets",Modifier.weight(1f))
                    JourneyMetric("${report.activeDays}","Active days",Modifier.weight(1f))
                }
                val peak = report.days.maxOfOrNull { it.reps }?.coerceAtLeast(1) ?: 1
                Row(Modifier.fillMaxWidth().height(if(report.reps==0)48.dp else 92.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    report.days.forEach { day ->
                        val label = java.time.LocalDate.ofEpochDay(day.day).dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW,java.util.Locale.getDefault())
                        Column(Modifier.weight(1f).semantics { contentDescription = "$label, ${day.reps} reps${if(day.active) ", active day" else ""}" },
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if(day.reps > 0) "${day.reps}" else " ",style = MaterialTheme.typography.labelSmall)
                            Box(Modifier.fillMaxWidth().height((54f * day.reps / peak).coerceAtLeast(6f).dp)
                                .background(if(day.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha=if(day.future) .04f else .1f),RoundedCornerShape(6.dp)))
                            Text(label,style=MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Text(report.headline,style=MaterialTheme.typography.titleMedium)
                Text(report.comparison,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(report.cameraReps > 0 || report.loggedReps > 0) Text("Camera ${report.cameraReps} · Logged ${report.loggedReps}",style=MaterialTheme.typography.labelSmall)
            }
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text("Your rep goals",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
            TextButton(onClick={editing=WorkoutActivityGoal("Pull-ups",0)},modifier=Modifier.testTag("addActivityGoal")) { Text("+ Add") }
        }
        if(report.goals.isEmpty()) Text("Choose a target. Every recorded rep adds up.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        report.goals.forEach { progress ->
            Card(Modifier.fillMaxWidth().clickable { editing=progress.goal }.testTag("activityGoal-${progress.goal.exercise}")) {
                Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        WorkoutExerciseIcon(progress.goal.exercise,40.dp)
                        Column(Modifier.weight(1f)) {
                            Text(progress.goal.exercise,style=MaterialTheme.typography.titleMedium)
                            Text(if(progress.goal.period==WorkoutGoalPeriod.week) "Weekly target" else "Daily target",style=MaterialTheme.typography.labelSmall)
                        }
                        Text("${progress.reps} / ${progress.goal.target}${if(progress.achieved) " ✓" else ""}",style=MaterialTheme.typography.titleMedium)
                    }
                    LinearProgressIndicator(progress={progress.fraction.toFloat()},modifier=Modifier.fillMaxWidth())
                    Text(if(progress.achieved) "Goal reached · ${progress.reps} reps" else "${progress.remaining} reps to go",style=MaterialTheme.typography.titleSmall)
                }
            }
        }
        if(report.exercises.isNotEmpty()) {
            var expanded by remember { mutableStateOf(false) }
            TextButton(onClick={expanded=!expanded}) { Text(if(expanded) "This week by exercise ▴" else "This week by exercise ▾") }
            if(expanded) report.exercises.forEach { item -> Row(Modifier.fillMaxWidth()) {
                Text(item.exercise,Modifier.weight(1f)); Column(horizontalAlignment=Alignment.End) {
                    Text("${item.reps} reps · ${item.sets} sets"); Text("Best set · ${item.bestSet} reps",style=MaterialTheme.typography.labelSmall)
                }
            } }
        }
        if(report.excludedUndatedSets > 0) Text("${report.excludedUndatedSets} undated sets aren’t assigned to a week.",style=MaterialTheme.typography.labelSmall)
    }
    editing?.let { goal -> ActivityGoalEditor(goal,report.exercises.map { it.exercise }+report.goals.map { it.goal.exercise },onSave,onRemove) { editing=null } }
}

@Composable private fun ActivityGoalEditor(goal: WorkoutActivityGoal, exercises: List<String>,
    onSave: suspend (WorkoutActivityGoal) -> Boolean,onRemove: suspend (WorkoutActivityGoal) -> Boolean,onClose: ()->Unit) {
    var exercise by remember { mutableStateOf(goal.exercise) }
    var target by remember { mutableStateOf(goal.target.takeIf { it>0 }?.toString().orEmpty()) }
    var period by remember { mutableStateOf(goal.period) }
    var saving by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    val scope=rememberCoroutineScope()
    AlertDialog(onDismissRequest={if(!saving)onClose()},title={Text("Rep goal")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Box {
            TextButton(onClick={choosing=true},enabled=goal.target==0 && !saving) { Text("$exercise ▾") }
            DropdownMenu(choosing,{choosing=false}) {
                (exercises+WorkoutLogPolicy.suggestedExercises+goal.exercise).distinct().sorted().forEach { name -> DropdownMenuItem(text={Text(name)},onClick={exercise=name;choosing=false}) }
            }
        }
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) { WorkoutGoalPeriod.entries.forEach { choice ->
            FilterChip(selected=period==choice,onClick={period=choice},enabled=!saving,label={Text(if(choice==WorkoutGoalPeriod.week) "Weekly" else "Daily")})
        } }
        OutlinedTextField(target,{target=it},label={Text("Reps")},singleLine=true,enabled=!saving,
            keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Number))
        Text(if(period==WorkoutGoalPeriod.week) "Counts all recorded reps this week." else "Counts all recorded reps today.",style=MaterialTheme.typography.bodySmall)
        if(failure)Text("Couldn’t save. Please try again.",color=MaterialTheme.colorScheme.error)
        if(goal.target>0)TextButton(onClick={scope.launch {saving=true;if(onRemove(goal))onClose()else failure=true;saving=false}},enabled=!saving) {Text("Remove goal")}
    }},confirmButton={TextButton(onClick={scope.launch {saving=true;if(onSave(WorkoutActivityGoal(exercise,target.toInt(),period)))onClose()else failure=true;saving=false}},
        enabled=!saving && target.toIntOrNull() in 1..100000) {Text("Save goal")}},dismissButton={TextButton(onClick=onClose,enabled=!saving){Text("Back")}})
}
