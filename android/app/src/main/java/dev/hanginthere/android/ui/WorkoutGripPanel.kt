package dev.hanginthere.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.hanginthere.android.session.WorkoutGripCorrectionStore
import dev.hanginthere.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun WorkoutGripPanel(set:WorkoutSetRecord,allowsCorrections:Boolean=true) {
    val context=LocalContext.current
    val store=remember(context) { WorkoutGripCorrectionStore(context.applicationContext) }
    val scope=rememberCoroutineScope()
    var report by remember(set.id) { mutableStateOf<GripVariationReport?>(null) }
    var error by remember(set.id) { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<String?>(null) }
    var orientation by remember { mutableStateOf<GripOrientation?>(null) }
    var width by remember { mutableStateOf<GripWidth?>(null) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(set.id,set.endReason,set.reps,set.setGrip,revision,allowsCorrections) {
        runCatching { withContext(Dispatchers.IO) { store.report(set,allowsCorrections) } }
            .onSuccess { report=it;error=null }.onFailure { if(it is kotlinx.coroutines.CancellationException)throw it;error=it.message }
    }
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text("Grip variations",style=MaterialTheme.typography.titleMedium)
        Text("Grip width is hand spacing ÷ shoulder width, measured from a stable starting hang. Narrow, medium and wide are relative to your shoulders.",style=MaterialTheme.typography.bodySmall)
        report?.setGrip?.let { metadata ->
            Text("Set grip · ${GripEstimate.orientationTitle(metadata.orientation)}",style=MaterialTheme.typography.titleSmall)
            Text(if(metadata.isConfirmed) "Identified during this set and applied to its reps."
                else if(metadata.reason=="conflictingSetGripEvidence") "Grip estimates disagree within this set."
                else "Waiting for a clear grip view during this set.",style=MaterialTheme.typography.bodySmall)
        }
        report?.totals?.forEach { Text("${it.label} · ${it.completedReps} ${if(it.completedReps==1)"rep" else "reps"}") }
        var expanded by remember { mutableStateOf(false) }
        TextButton(onClick={expanded=!expanded}) { Text("Per attempt") }
        if(expanded)report?.reps?.forEach { rep ->
            Text("${rep.ordinal} · ${rep.label}")
            Text(if(rep.orientationSource=="user" || rep.widthSource=="user")"Includes your correction"
                else if(rep.orientationSource=="setEstimated")if(report?.setGrip?.isConfirmed==true)"Grip inferred from this set" else "Set grip unknown"
                else "Automatic estimate",style=MaterialTheme.typography.bodySmall)
            rep.measured?.orientationCoverage?.let { Text("Both hands classified for ${(it*100).toInt()}% of this attempt",style=MaterialTheme.typography.bodySmall) }
            if(rep.widthSource!="user")rep.measured?.let { measured ->
                measured.widthRatioLabel?.let { Text("Hand spacing · $it",style=MaterialTheme.typography.bodyMedium) }
                measured.widthCoverage?.let { Text("Width measured for ${(it*100).toInt()}% of this attempt",style=MaterialTheme.typography.bodySmall) }
            }
        }
        if(allowsCorrections && set.endReason!="running" && report?.reps?.isNotEmpty()==true)TextButton(onClick={target=null;orientation=null;width=null;editing=true}) { Text("Correct grip labels") }
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    }
    if(editing)AlertDialog(onDismissRequest={if(!saving)editing=false},title={Text("Correct grip")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            GripChoice("Apply to", listOf(null to "Whole set")+(report?.reps ?: emptyList()).map { it.repId to "Attempt ${it.ordinal}" },target) { target=it }
            GripChoice("Orientation",listOf(null to "Automatic estimate")+GripOrientation.entries.filter { it!=GripOrientation.varied }.map { it to GripEstimate.orientationTitle(it) },orientation) { orientation=it }
            GripChoice("Width",listOf(null to "Automatic estimate")+GripWidth.entries.filter { it!=GripWidth.varied }.map { it to GripEstimate.widthTitle(it) },width) { width=it }
            Text("Saving replaces labels for the selected attempts. Automatic estimate restores the original measurement. Rep counts and motion analytics are preserved.",style=MaterialTheme.typography.bodySmall)
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        }
    },confirmButton={TextButton(enabled=!saving,onClick={
        saving=true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { store.correct(set,target,orientation,width) } }
                .onSuccess { editing=false;revision++ }.onFailure { if(it is kotlinx.coroutines.CancellationException)throw it;error=it.message }
            saving=false
        }
    }) { Text("Save") }},dismissButton={TextButton(enabled=!saving,onClick={editing=false}) { Text("Cancel") }})
}
@Composable private fun <T> GripChoice(title:String,choices:List<Pair<T,String>>,value:T,onChoose:(T)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick={expanded=true}) { Text("$title: ${choices.firstOrNull { it.first==value }?.second.orEmpty()}") }
        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
            choices.forEach { (choice,label)->DropdownMenuItem(text={Text(label)},onClick={onChoose(choice);expanded=false}) }
        }
    }
}
