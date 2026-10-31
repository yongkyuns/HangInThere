package dev.hanginthere.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.hanginthere.android.session.WorkoutRecordStore
import dev.hanginthere.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Date
import java.util.UUID

internal data class WorkoutLogCameraRequest(val log: WorkoutLog, val exercise: ExerciseCounter.Exercise, val load: Double?, val unit: String)
private fun newLog() = System.currentTimeMillis().let { WorkoutLog(UUID.randomUUID().toString(), it, updatedAtEpochMilliseconds = it) }
private fun loadNumber(text: String) = text.replace(DecimalFormatSymbols.getInstance().decimalSeparator, '.').toDoubleOrNull()
private fun formatLoad(value: Double?) = value?.let { NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2; isGroupingUsed = false }.format(it) }.orEmpty()
private fun WorkoutLoggedSet.loadLabel() = load?.let { "${formatLoad(it)} $unit" } ?: "—"
private fun restLabel(seconds: Double?) = seconds?.toInt()?.let { "%d:%02d".format(it / 60, it % 60) } ?: "—"

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun WorkoutLogDialog(
    recordStore: WorkoutRecordStore? = null, initial: WorkoutLog? = null,
    onCamera: ((WorkoutLogCameraRequest) -> Unit)? = null, onClose: () -> Unit,
) {
    val context = LocalContext.current
    val store = recordStore ?: remember(context) { WorkoutRecordStore.live(context.applicationContext) }
    var log by remember(initial?.id) { mutableStateOf(initial ?: newLog()) }
    var history by remember { mutableStateOf(emptyList<WorkoutLog>()) }
    var cameraSets by remember { mutableStateOf(emptyList<WorkoutSetDigest>()) }
    var exercise by rememberSaveable { mutableStateOf(initial?.sets?.lastOrNull()?.exercise ?: "Pull-ups") }
    var reps by rememberSaveable { mutableStateOf("5") }
    var load by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf("lb") }
    var choosing by remember { mutableStateOf(false) }
    var unitMenu by remember { mutableStateOf(false) }
    var datePicker by remember { mutableStateOf(false) }
    var counter by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<WorkoutLoggedSet?>(null) }
    var progress by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val parsedLoad = loadNumber(load)
    val valid = (load.isBlank() || parsedLoad != null) && WorkoutLogPolicy.validSet(exercise, reps.toIntOrNull(), parsedLoad, unit)
    fun prefill(name: String, current: WorkoutLog = log, past: List<WorkoutLog> = history) {
        exercise = (current.sets + past.flatMap { it.sets }).firstOrNull { WorkoutLogPolicy.exerciseKey(it.exercise) == WorkoutLogPolicy.exerciseKey(name) }?.exercise ?: name
        val last = WorkoutLogPolicy.prefill(exercise, current, past)
        reps = (last?.reps ?: 5).toString(); load = formatLoad(last?.load); unit = last?.unit ?: unit
    }
    suspend fun persist(next: WorkoutLog) {
        saving = true
        try {
            val saved = withContext(Dispatchers.IO) { store.saveWorkoutLog(next) }
            log = saved; history = (history.filter { it.id != saved.id } + saved).sortedByDescending { it.epochMilliseconds }; error = null
        } finally { saving = false }
    }
    fun save(next: WorkoutLog, onSuccess: () -> Unit = {}) { scope.launch {
        try { persist(next); onSuccess() }
        catch (failure: Throwable) { if (failure is kotlinx.coroutines.CancellationException) throw failure; error = "Couldn't save the set. Try again." }
    } }
    fun entry(count: Int = reps.toIntOrNull() ?: 0) = WorkoutLoggedSet(UUID.randomUUID().toString(), exercise.trim(), count, parsedLoad, unit)
    LaunchedEffect(store, initial?.id) {
        try {
            val state = withContext(Dispatchers.IO) { store.libraryForReview() }
            history = state.workoutLogs
            log = history.firstOrNull { it.id == log.id } ?: log
            cameraSets = WorkoutJourney.workout(state, log.id, System.currentTimeMillis())?.sets.orEmpty()
            prefill(exercise)
        } catch (failure: Throwable) { if (failure is kotlinx.coroutines.CancellationException) throw failure; error = "Couldn't load your workout. Close and retry." }
    }
    WorkoutLogPage("Workout log", onClose = { if (!saving) onClose() }, actions = {
        TextButton(onClick = { progress = true }) { Text("Progress") }
    }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { datePicker = true }, enabled = !saving && cameraSets.isEmpty()) {
                    Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(log.epochMilliseconds)))
                }
                Spacer(Modifier.weight(1f))
                Text("${log.sets.size + cameraSets.size} sets · ${log.sets.sumOf { it.reps } + cameraSets.sumOf { it.completedReps }} reps", style = MaterialTheme.typography.labelLarge)
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    TextButton(onClick = { choosing = true }, enabled = !saving, modifier = Modifier.testTag("chooseLogExercise")) {
                        Text(exercise, style = MaterialTheme.typography.titleLarge); Text(" ▾")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(load, { load = it }, label = { Text("Weight") }, placeholder = { Text("Optional") },
                            modifier = Modifier.weight(1f).testTag("logWeight"), singleLine = true, enabled = !saving,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        Box {
                            TextButton(onClick = { unitMenu = true }, enabled = !saving) { Text("$unit ▾") }
                            DropdownMenu(unitMenu, { unitMenu = false }) {
                                listOf("lb", "kg").forEach { next -> DropdownMenuItem(text = { Text(next) }, onClick = {
                                    if (next != unit && parsedLoad != null && parsedLoad.isFinite() && parsedLoad >= 0)
                                        load = formatLoad(WorkoutLogPolicy.convertLoad(parsedLoad, unit, next))
                                    unit = next; unitMenu = false
                                }) }
                            }
                        }
                        OutlinedTextField(reps, { reps = it }, label = { Text("Reps") }, modifier = Modifier.width(86.dp).testTag("logReps"),
                            enabled = !saving, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done))
                    }
                    Button(onClick = { save(log.copy(sets = log.sets + entry())) }, enabled = valid && !saving,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("saveLoggedSet")) { Text("Log set") }
                    FilledTonalButton(onClick = { counter = true }, enabled = valid && !saving,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("openTapCounter")) { Text("Count reps") }
                    val cameraExercise = WorkoutLogPolicy.cameraExercise(exercise)
                    val zone = java.time.ZoneId.systemDefault().id
                    if (onCamera != null && cameraExercise != null && WorkoutCalendarPolicy.day(log.epochMilliseconds, zone).day == WorkoutCalendarPolicy.day(System.currentTimeMillis(), zone).day) {
                        TextButton(onClick = { onCamera(WorkoutLogCameraRequest(log, cameraExercise, parsedLoad, unit)) }, enabled = valid && !saving) { Text("Count with camera") }
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (log.sets.isEmpty() && cameraSets.isEmpty()) Text("Add a result or open the tap counter.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            WorkoutLogTable(log) { if (!saving) editing = it }
            if (cameraSets.isNotEmpty()) Text("Camera sets", style = MaterialTheme.typography.titleMedium)
            cameraSets.forEach { set -> Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp)) {
                Text(set.exercise.title, Modifier.weight(1f)); Text("${set.completedReps} reps")
            } } }
        }
    }
    if (choosing) WorkoutExercisePicker((log.sets + history.flatMap { it.sets }).map { it.exercise }, onClose = { choosing = false }) { prefill(it); choosing = false }
    if (counter) WorkoutTapCounter(entry(0).copy(method = "tap"), onClose = { counter = false }) { set ->
        persist(log.copy(sets = log.sets + set)); reps = set.reps.toString(); counter = false
    }
    editing?.let { set -> WorkoutLoggedSetEditor(set, onClose = { editing = null }, onSave = { result ->
        persist(log.copy(sets = log.sets.map { if (it.id == result.id) result else it })); editing = null
    }, onDelete = { persist(log.copy(sets = log.sets.filter { it.id != set.id })); editing = null }) }
    if (progress) WorkoutLoggedProgress(history) { progress = false }
    if (datePicker) {
        val zone = java.time.ZoneId.systemDefault().id
        val selected = WorkoutCalendarPolicy.day(log.epochMilliseconds, zone)
        val today = WorkoutCalendarPolicy.day(System.currentTimeMillis(), zone).day
        val state = rememberDatePickerState(initialSelectedDateMillis = selected.day * 86_400_000,
            selectableDates = object : SelectableDates { override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis / 86_400_000 in 0..today })
        DatePickerDialog(onDismissRequest = { datePicker = false }, confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { val next = log.copy(epochMilliseconds = WorkoutCalendarPolicy.dayByKey(it / 86_400_000, zone).startEpochMilliseconds)
                    if (log.sets.isEmpty()) log = next else save(next) }
                datePicker = false
            }) { Text("Save") }
        }, dismissButton = { TextButton(onClick = { datePicker = false }) { Text("Back") } }) { DatePicker(state) }
    }
}

@Composable internal fun WorkoutLogTable(log: WorkoutLog, onEdit: (WorkoutLoggedSet) -> Unit) {
    log.sets.map { it.exercise }.distinct().forEach { name ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Row { Text("Set", Modifier.weight(.6f)); Text("Weight", Modifier.weight(1.2f)); Text("Reps", Modifier.weight(.8f)); Text("Rest", Modifier.weight(.8f)) }
                log.sets.filter { it.exercise == name }.forEachIndexed { index, set ->
                    Row(Modifier.fillMaxWidth().testTag("logged-set-${set.id}").clickable { onEdit(set) }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}", Modifier.weight(.6f)); Text(set.loadLabel(), Modifier.weight(1.2f))
                        Text("${set.reps}", Modifier.weight(.8f)); Text(restLabel(WorkoutLogPolicy.restBefore(log, set.id)), Modifier.weight(.8f))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun WorkoutLogPage(title: String, onClose: () -> Unit, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) { Column(Modifier.systemBarsPadding()) {
            TopAppBar(title = { Text(title) }, navigationIcon = { TextButton(onClick = onClose) { Text("Back") } }, actions = actions)
            content()
        } }
    }
}

@Composable private fun WorkoutExercisePicker(names: List<String>, onClose: () -> Unit, onSelect: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val suggestions = (names + WorkoutLogPolicy.suggestedExercises).distinct().sorted().filter { it.contains(query.trim(), ignoreCase = true) }
    WorkoutLogPage("Exercise", onClose) {
        Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text("Find or add an exercise") }, modifier = Modifier.fillMaxWidth().testTag("exerciseSearch"), singleLine = true)
            suggestions.forEach { name -> TextButton(onClick = { onSelect(name) }, modifier = Modifier.fillMaxWidth()) { Text(name) } }
            val name = query.trim()
            if (WorkoutLogPolicy.validSet(name, 0, null, "lb") && suggestions.none { it.equals(name, true) }) TextButton(onClick = { onSelect(name) }) { Text("Use “$name”") }
        }
    }
}

@Composable private fun WorkoutTapCounter(set: WorkoutLoggedSet, onClose: () -> Unit, onFinish: suspend (WorkoutLoggedSet) -> Unit) {
    var count by rememberSaveable { mutableIntStateOf(0) }
    val id = rememberSaveable { set.id }
    val started = rememberSaveable { System.currentTimeMillis() }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    WorkoutLogPage(set.exercise, { if (!saving) onClose() }) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(set.loadLabel(), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Button(onClick = { if (count < 1000) count++ }, enabled = !saving,
                modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp).testTag("tapRep")) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("$count", fontSize = 90.sp); Text("Tap for a rep") }
            }
            TextButton(onClick = { count-- }, enabled = count > 0 && !saving) { Text("Undo") }
            if (error) Text("Couldn't save. Your count is still here.", color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.weight(1f))
            Button(onClick = { scope.launch {
                saving = true
                try { onFinish(set.copy(id = id, reps = count, startedAtEpochMilliseconds = started, endedAtEpochMilliseconds = System.currentTimeMillis())) }
                catch (failure: Throwable) { if (failure is kotlinx.coroutines.CancellationException) throw failure; error = true }
                finally { saving = false }
            } }, enabled = !saving, modifier = Modifier.fillMaxWidth().testTag("finishTapSet")) { Text("Finish set") }
        }
    }
}

@Composable private fun WorkoutLoggedSetEditor(set: WorkoutLoggedSet, onClose: () -> Unit,
    onSave: suspend (WorkoutLoggedSet) -> Unit, onDelete: suspend () -> Unit) {
    var reps by rememberSaveable(set.id) { mutableStateOf(set.reps.toString()) }
    var load by rememberSaveable(set.id) { mutableStateOf(formatLoad(set.load)) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val value = loadNumber(load)
    val valid = (load.isBlank() || value != null) && WorkoutLogPolicy.validSet(set.exercise, reps.toIntOrNull(), value, set.unit)
    fun perform(action: suspend () -> Unit) { scope.launch {
        saving = true
        try { action() } catch (failure: Throwable) { if (failure is kotlinx.coroutines.CancellationException) throw failure; error = true } finally { saving = false }
    } }
    WorkoutLogPage("Edit set", { if (!saving) onClose() }) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(set.exercise, style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(reps, { reps = it }, label = { Text("Reps") }, enabled = !saving, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done), modifier = Modifier.testTag("editLogReps"))
            OutlinedTextField(load, { load = it }, label = { Text("Weight · ${set.unit}") }, enabled = !saving, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            if (error) Text("Couldn't save. Try again.", color = MaterialTheme.colorScheme.error)
            Button(onClick = { perform { onSave(set.copy(reps = reps.toInt(), load = value)) } }, enabled = valid && !saving) { Text("Save") }
            TextButton(onClick = { perform { onDelete() } }, enabled = !saving, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete set") }
        }
    }
}

@Composable private fun WorkoutLoggedProgress(history: List<WorkoutLog>, onClose: () -> Unit) {
    val names = history.flatMap { it.sets }.map { it.exercise }.distinct().sorted()
    var selected by rememberSaveable { mutableStateOf(names.firstOrNull().orEmpty()) }
    var menu by remember { mutableStateOf(false) }
    val progress = remember(history, selected) { WorkoutLogPolicy.progress(history, selected) }
    val color = MaterialTheme.colorScheme.primary
    WorkoutLogPage("Logged progress", onClose) {
        Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (names.isEmpty()) Text("Log your first set") else {
                Box { TextButton(onClick = { menu = true }) { Text("$selected ▾") }
                    DropdownMenu(menu, { menu = false }) { names.forEach { name -> DropdownMenuItem(text = { Text(name) }, onClick = { selected = name; menu = false }) } } }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    JourneyMetric("${progress.setCount}", "Logged sets", Modifier.weight(1f)); JourneyMetric("${progress.reps}", "Reps", Modifier.weight(1f)); JourneyMetric("${progress.bestReps}", "Most / set", Modifier.weight(1f))
                }
                Text("Reps per workout", style = MaterialTheme.typography.titleMedium)
                Canvas(Modifier.fillMaxWidth().height(180.dp).semanticsProgress(progress)) {
                    val peak = (progress.workouts.maxOfOrNull { it.reps } ?: 1).coerceAtLeast(1)
                    val width = size.width / progress.workouts.size.coerceAtLeast(1)
                    progress.workouts.forEachIndexed { index, point -> drawLine(color, Offset(width * (index + .5f), size.height),
                        Offset(width * (index + .5f), size.height * (1 - point.reps.toFloat() / peak)), (width * .65f).coerceAtMost(40.dp.toPx())) }
                }
                Text("Recent sets", style = MaterialTheme.typography.titleMedium)
                progress.recentSets.forEach { set -> Row(Modifier.fillMaxWidth()) { Text(set.loadLabel(), Modifier.weight(1f)); Text("${set.reps} reps") } }
            }
        }
    }
}
private fun Modifier.semanticsProgress(progress: WorkoutLogProgress) = this.then(Modifier.semantics {
    contentDescription = "Reps per workout: " + progress.workouts.joinToString { "${DateFormat.getDateInstance().format(Date(it.epochMilliseconds))}: ${it.reps}" }
})
