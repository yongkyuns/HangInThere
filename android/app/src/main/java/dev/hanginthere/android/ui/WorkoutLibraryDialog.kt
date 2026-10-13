package dev.hanginthere.android.ui

import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.hanginthere.android.session.WorkoutRecordStore
import dev.hanginthere.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone
import java.util.UUID

private data class LibraryDashboard(val state: WorkoutLibraryState, val page: WorkoutHistoryPage, val insights: WorkoutLibraryInsights)

/** Native presentation of shared review/application policy. No camera-state mutations. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun WorkoutLibraryDialog(recordStore: WorkoutRecordStore? = null, onClose: () -> Unit) {
    val context = LocalContext.current
    val store = recordStore ?: remember(context) { WorkoutRecordStore.live(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var dashboard by remember { mutableStateOf<LibraryDashboard?>(null) }
    var history by remember { mutableStateOf<List<WorkoutReviewedSet>>(emptyList()) }
    var key by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<WorkoutSetRecord?>(null) }
    var evidenceRecord by remember { mutableStateOf<WorkoutSetRecord?>(null) }
    var editCorrection by remember { mutableStateOf(WorkoutReviewCorrection()) }
    var deleting by remember { mutableStateOf<WorkoutReviewedSet?>(null) }
    var playing by remember { mutableStateOf<java.io.File?>(null) }
    var attachingSet by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var exportText by remember { mutableStateOf<String?>(null) }
    suspend fun load(more: Boolean = false) {
        try {
            val cursor = if (more) dashboard?.page?.nextCursor else null
            val next = withContext(Dispatchers.IO) {
                val state = store.libraryForReview()
                LibraryDashboard(state, WorkoutLibrary.page(state, cursor), WorkoutInsights.report(state, key,
                    System.currentTimeMillis(), TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000))
            }
            dashboard = next; key = next.insights.selectedComparisonKey; history = if (more) history + next.page.sets else next.page.sets; error = null
        } catch (failure: Throwable) { if (failure is kotlinx.coroutines.CancellationException) throw failure; error = failure.message }
    }
    fun apply(command: WorkoutLibraryCommand, onSuccess: () -> Unit = {}) { scope.launch {
        try { withContext(Dispatchers.IO) { store.libraryCommand(command) }; load(); onSuccess() }
        catch (failure: Throwable) { if (failure is kotlinx.coroutines.CancellationException) throw failure; error = failure.message }
    } }
    val backupExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            try { val text = requireNotNull(exportText); withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(text.toByteArray()) } }; exportText = null }
            catch (failure: Throwable) { error = failure.message }
        }
    }
    val backupImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try { withContext(Dispatchers.IO) {
                val text = requireNotNull(context.contentResolver.openInputStream(uri)).use { stream ->
                    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(65536)
                    while (true) { val count = stream.read(buffer); if (count < 0) break
                        require(output.size() + count <= WorkoutLibraryBackup.maximumBackupCharacters) { "Backup is too large" }; output.write(buffer, 0, count) }
                    output.toString("UTF-8")
                }; store.libraryRestore(text)
            }; load() } catch (failure: Throwable) { error = failure.message }
        }
    }
    val mediaImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && attachingSet != null) scope.launch {
            try { val id = requireNotNull(attachingSet); withContext(Dispatchers.IO) { store.attachLibraryMedia(uri, id) }; load() }
            catch (failure: Throwable) { error = failure.message }
        }
    }
    LaunchedEffect(Unit) { load() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                TopAppBar(title = { Text("Workout library") }, actions = { TextButton(onClick = onClose) { Text("Done") } })
                ScrollableTabRow(selectedTabIndex = tab) { listOf("Progress", "History", "Plans", "Storage").forEachIndexed { index, title -> Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) }) } }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    val current = dashboard
                    if (current == null) CircularProgressIndicator() else when (tab) {
                        0 -> {
                            val report = current.insights
                            Text(report.focus.title, style = MaterialTheme.typography.headlineSmall)
                            Text(report.focus.message); Text(report.focus.limitation, style = MaterialTheme.typography.bodySmall)
                            Text("${report.focus.sampleSessions} comparable sessions · ${report.unclassifiedSets} sets need context")
                            report.focus.recentMedian?.let { Text("Recent measured median: $it") }
                            if (report.focus.evidenceSetIds.isNotEmpty()) {
                                var sourcesExpanded by remember { mutableStateOf(false) }
                                TextButton(onClick = { sourcesExpanded = !sourcesExpanded }) { Text("Measurements behind this focus") }
                                if (sourcesExpanded) report.focus.evidenceSetIds.forEachIndexed { index, id ->
                                    TextButton(onClick = { scope.launch { try { evidenceRecord = withContext(Dispatchers.IO) { store.libraryRecord(id) } } catch (failure: Throwable) { error = failure.message } } }) { Text("Review source measurement ${index + 1}") }
                                }
                            }
                            if (report.comparisonKeys.isNotEmpty()) LibraryChoice("Comparison", key ?: report.selectedComparisonKey.orEmpty(), report.comparisonKeys, ::comparisonTitle) { key = it; scope.launch { load() } }
                            if (report.trend.isNotEmpty()) {
                                LibraryPlot("Observed hold seconds", report.trend.map { it.longestHoldSeconds })
                                LibraryPlot("Measured completed cycles", report.trend.map { it.measuredCompletedReps.toDouble() })
                                LibraryPlot("Comparable elbow excursion (degrees)", report.trend.map { it.elbowExcursionDegrees })
                                LibraryPlot("Mean measured return seconds", report.trend.map { it.meanReturnSeconds })
                            }
                            val comparison = report.selectedComparisonKey
                            if (comparison != null) {
                                TextButton(onClick = { apply(WorkoutLibraryCommand("defaultContext", id = comparison, atEpochMilliseconds = System.currentTimeMillis())) }) { Text("Use this context for future live sets") }
                                Text("Assistance, load, equipment and camera setup carry forward. Review when these change; effort and readiness remain per set.")
                                TextButton(onClick = { apply(WorkoutLibraryCommand("clearDefaultContexts", atEpochMilliseconds = System.currentTimeMillis())) }) { Text("Clear future workout context") }
                                var target by remember(comparison) { mutableStateOf(report.goal?.target?.toString().orEmpty()) }
                                var metric by remember(comparison) { mutableStateOf(report.goal?.metric ?: WorkoutGoalMetric.holdSeconds) }
                                Text("Your chosen goal", style = MaterialTheme.typography.titleMedium)
                                report.goal?.let { Text("${it.target} ${it.metric.name} · ${if (report.goalAchieved) "Reached" else "In progress"}") }
                                report.suggestedGoal?.let { suggestion ->
                                    Text("Recent-session suggestion: ${suggestion.target} ${if (suggestion.metric == WorkoutGoalMetric.holdSeconds) "seconds" else "cycles"}")
                                    TextButton(onClick = { target = suggestion.target.toString(); metric = suggestion.metric }) { Text("Use recent suggestion") }
                                }
                                val chosenGoal = report.goal; val goalBest = report.goalBest
                                if (chosenGoal != null && goalBest != null) {
                                    Text("Observed progress: $goalBest / ${chosenGoal.target}")
                                    LinearProgressIndicator(progress = { (goalBest / chosenGoal.target).coerceIn(0.0, 1.0).toFloat() }, modifier = Modifier.fillMaxWidth())
                                }
                                LibraryChoice("Metric", metric.name, WorkoutGoalMetric.entries.map { it.name }) { metric = WorkoutGoalMetric.valueOf(it) }
                                OutlinedTextField(target, { target = it }, label = { Text("Target") }, modifier = Modifier.fillMaxWidth())
                                Button(onClick = {
                                    val exercise = current.page.sets.firstOrNull { it.comparisonKey == comparison }?.exercise ?: report.goal?.exercise ?: ExerciseCounter.Exercise.valueOf(WorkoutRecordCodec.json.decodeFromString<List<String>>(comparison).first())
                                    target.toDoubleOrNull()?.let { apply(WorkoutLibraryCommand("goal", goal = WorkoutLibraryGoal(comparison, WorkoutGoal(exercise, metric, it, System.currentTimeMillis())), atEpochMilliseconds = System.currentTimeMillis())) }
                                }, enabled = target.toDoubleOrNull() != null) { Text("Save chosen goal") }
                            }
                            Text("${report.consistencyDaysLast28} practice days in the last 28 days", style = MaterialTheme.typography.titleMedium)
                            Text("Holds and attempts count as practice; repeated sets on one day earn one practice day.")
                            LibraryPlot("Weekly practice days (maximum 7)", report.weekly.map { it.days.toDouble() }, maximum = 7.0)
                            Text("Milestone history", style = MaterialTheme.typography.titleMedium)
                            report.milestones.filter { it.achieved }.forEach { item -> Text(item.title + (item.achievedAtEpochMilliseconds?.let { " · " + DateFormat.getDateInstance().format(Date(it)) } ?: " · date unavailable")) }
                        }
                        1 -> {
                            Text("Review and correct", style = MaterialTheme.typography.headlineSmall)
                            Text("Corrections retain original measurements. User-reported completions stay separate from measured cycles.")
                            history.forEach { set ->
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(set.exercise.title, style = MaterialTheme.typography.titleMedium)
                                        Text("${set.measuredCompletedReps} measured cycles · ${set.userReportedCompletedReps} user-reported · ${set.practiceAttempts} practice attempts")
                                        set.holdSeconds?.let { Text("Observed hold: $it s") }
                                        set.trackedAtEpochMilliseconds?.let { Text(DateFormat.getDateTimeInstance().format(Date(it))) }
                                        set.sourceSetIds.forEach { sourceId ->
                                            if (set.sourceSetIds.size > 1) Text("Original recording · ${sourceId.take(8)}", style = MaterialTheme.typography.titleSmall)
                                            Row {
                                                TextButton(onClick = { scope.launch { try { evidenceRecord = withContext(Dispatchers.IO) { store.libraryRecord(sourceId) } } catch (failure: Throwable) { error = failure.message } } }) { Text("Measurements") }
                                                TextButton(onClick = { scope.launch { try { editing = withContext(Dispatchers.IO) { store.libraryRecord(sourceId) }; editCorrection = WorkoutCorrections.current(current.state.reviews, sourceId) } catch (failure: Throwable) { error = failure.message } } }) { Text("Edit") }
                                                TextButton(onClick = { apply(WorkoutLibraryCommand("undo", setId = sourceId, atEpochMilliseconds = System.currentTimeMillis())) }) { Text("Undo") }
                                            }
                                            if (current.state.mediaPolicy.enabled) TextButton(onClick = { attachingSet = sourceId; mediaImport.launch(arrayOf("video/*")) }) { Text("Attach local review video") }
                                            current.state.media.filter { it.setId == sourceId }.forEach { media ->
                                                Row {
                                                    TextButton(onClick = { scope.launch { playing = withContext(Dispatchers.IO) { store.libraryMedia(media.id) } } }) { Text("Play video") }
                                                    TextButton(onClick = { apply(WorkoutLibraryCommand("deleteMedia", id = media.id, atEpochMilliseconds = System.currentTimeMillis())) }) { Text("Delete video") }
                                                }
                                            }
                                        }
                                        TextButton(onClick = { error = null; deleting = set }) { Text(if (set.sourceSetIds.size > 1) "Delete recordings" else "Delete source recording") }
                                        if (set.isMerged) Text("Merged group · ${set.sourceSetIds.size} original recording(s); source timing remains separate.")
                                        if (set.isMerged) TextButton(onClick = { apply(WorkoutLibraryCommand("unmerge", id = set.id, atEpochMilliseconds = System.currentTimeMillis())) }) { Text("Unmerge grouped set") }
                                        Row { Checkbox(set.id in selected, { value -> selected = if (value) selected + set.id else selected - set.id }); Text("Select for grouping or merging") }
                                    }
                                }
                            }
                            if (current.page.nextCursor != null) TextButton(onClick = { scope.launch { load(true) } }) { Text("Load more history") }
                            Button(onClick = { scope.launch {
                                val sources = history.filter { it.id in selected }.flatMap { it.sourceSetIds }.distinct()
                                try { withContext(Dispatchers.IO) { store.libraryCommand(WorkoutLibraryCommand("assignWorkout", id = UUID.randomUUID().toString(), setIds = sources, atEpochMilliseconds = System.currentTimeMillis())) }; selected = emptySet(); load() }
                                catch (failure: Throwable) { error = failure.message }
                            } }, enabled = selected.size > 1) { Text("Group selected sets into a workout") }
                            Button(onClick = { apply(WorkoutLibraryCommand("merge", id = UUID.randomUUID().toString(), setIds = selected.sorted(), atEpochMilliseconds = System.currentTimeMillis())) { selected = emptySet() } }, enabled = selected.size > 1) { Text("Merge selected comparable sets") }
                            Text("Workouts and exercise transitions", style = MaterialTheme.typography.titleMedium)
                            current.insights.sessions.forEach { session ->
                                Text("${session.exercises.joinToString(" → ") { it.title }} · ${session.setIds.size} sets · ${session.measuredCompletedReps} measured cycles")
                                if (session.restSeconds.isNotEmpty()) Text("Between recorded set boundaries: ${session.restSeconds.joinToString { "${it.toInt()} s" }}")
                                else if (session.startIntervalsSeconds.isNotEmpty()) Text("Finish clock unavailable. Intervals between starts: ${session.startIntervalsSeconds.joinToString { "${it.toInt()} s" }}")
                                if (session.interruptedSets > 0) Text("${session.interruptedSets} interrupted sets retained")
                            }
                        }
                        2 -> LibraryPlans(current) { apply(it) }
                        3 -> {
                            Text("Your local data", style = MaterialTheme.typography.headlineSmall)
                            Text("Backup includes measured summaries, corrections, goals and plans. Videos and diagnostic consent are excluded.")
                            Button(onClick = { scope.launch { try { exportText = withContext(Dispatchers.IO) { store.libraryExport() }; backupExport.launch("HangInThere-workouts.json") } catch (failure: Throwable) { error = failure.message } } }) { Text("Export backup / analysis") }
                            Button(onClick = { backupImport.launch(arrayOf("application/json")) }) { Text("Restore backup") }
                            Row { Switch(current.state.mediaPolicy.enabled, { apply(WorkoutLibraryCommand("mediaPolicy", mediaPolicy = current.state.mediaPolicy.copy(enabled = it), atEpochMilliseconds = System.currentTimeMillis())) }); Text("Keep explicitly attached review videos") }
                            Text("Off by default. Up to ${current.state.mediaPolicy.maximumClips} videos, ${current.state.mediaPolicy.maximumBytes / 1048576} MB and ${current.state.mediaPolicy.maximumAgeDays} days. Turning this off deletes retained videos.")
                            LibraryRetention(current.state.mediaPolicy) { policy -> apply(WorkoutLibraryCommand("mediaPolicy", mediaPolicy = policy, atEpochMilliseconds = System.currentTimeMillis())) }
                            Row { Switch(current.state.diagnosticsConsent, { apply(WorkoutLibraryCommand("diagnosticsConsent", consent = it, atEpochMilliseconds = System.currentTimeMillis())) }); Text("Allow content-free diagnostic export") }
                            if (current.state.diagnosticsConsent) Button(onClick = {
                                exportText = WorkoutRecordCodec.json.encodeToString(WorkoutDiagnosticSummary.serializer(), WorkoutDiagnostics.export(current.state)); backupExport.launch("HangInThere-diagnostics.json")
                            }) { Text("Export diagnostic summary") }
                            Text("No upload runs automatically. Backup and video export do not grant training consent.")
                        }
                    }
                }
            }
        }
    }
    editing?.let { record -> LibraryCorrectionDialog(record, editCorrection, errorMessage = error, onClose = { editing = null }) { correction ->
        apply(WorkoutLibraryCommand("review", setId = record.id, correction = correction, atEpochMilliseconds = System.currentTimeMillis())) { editing = null }
    } }
    deleting?.let { set -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete original recordings and their local review videos?") },
        text = { Column { Text("This removes ${set.sourceSetIds.size} original recording(s), all their splits and their contribution to progress. Backups cannot undo deletion."); error?.let { Text(it, color = MaterialTheme.colorScheme.error) } } },
        confirmButton = { TextButton(onClick = { apply(WorkoutLibraryCommand("deleteSources", setIds = set.sourceSetIds, atEpochMilliseconds = System.currentTimeMillis())) { deleting = null } }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
    evidenceRecord?.let { record -> WorkoutReviewDialog(emptyList(), listOf(record), null, showsLibraryLink = false, onClose = { evidenceRecord = null }) }
    playing?.let { file -> Dialog(onDismissRequest = { playing = null }) {
        AndroidView(factory = { viewContext -> VideoView(viewContext).apply { setVideoURI(Uri.fromFile(file)); setOnPreparedListener { start() } } }, modifier = Modifier.fillMaxWidth().height(400.dp), onRelease = { it.stopPlayback() })
    } }
}

@Composable private fun LibraryChoice(label: String, value: String, choices: List<String>, title: (String) -> String = { it }, changed: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = true }) { Text("$label: ${title(value)}") }
        DropdownMenu(expanded, { expanded = false }) { choices.forEach { choice -> DropdownMenuItem(text = { Text(title(choice)) }, onClick = { changed(choice); expanded = false }) } }
    }
}
private fun comparisonTitle(value: String): String = runCatching {
    val parts = WorkoutRecordCodec.json.decodeFromString<List<String>>(value)
    "${parts[0]} · ${parts[2]} ${parts[3]} · ${parts[4]} kg · ${parts[5]} · ${parts[6]}"
}.getOrDefault("Comparison group")
@Composable private fun LibraryPlot(title: String, values: List<Double?>, maximum: Double? = null) {
    val seriesColor = MaterialTheme.colorScheme.primary
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    Text(title, style = MaterialTheme.typography.titleSmall)
    Text(values.mapIndexed { index, value -> "${index + 1}: ${value?.let { String.format(java.util.Locale.ROOT, "%.1f", it) } ?: "unavailable"}" }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
    Canvas(Modifier.fillMaxWidth().height(150.dp).semantics { contentDescription = "$title, oldest to newest; unavailable values are gaps. ${values.joinToString()}" }) {
        val max = maximum ?: maxOf(1.0, values.filterNotNull().maxOrNull() ?: 1.0)
        drawLine(axisColor, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        var previous: Offset? = null
        values.forEachIndexed { index, value ->
            if (value == null) previous = null else {
                val point = Offset((index.toFloat() / maxOf(1, values.size - 1)) * size.width, (1 - value / max).toFloat() * size.height)
                previous?.let { drawLine(seriesColor, it, point, 2.dp.toPx()) }; drawCircle(seriesColor, 3.dp.toPx(), point); previous = point
            }
        }
    }
}
@Composable private fun LibraryContextFields(context: WorkoutContext, change: (WorkoutContext) -> Unit) {
    LibraryChoice("Motion context", context.intent.name, WorkoutIntent.entries.map { it.name }) { change(context.copy(intent = WorkoutIntent.valueOf(it))) }
    LibraryChoice("Assistance", context.assistance.name, WorkoutAssistance.entries.map { it.name }) { change(context.copy(assistance = WorkoutAssistance.valueOf(it), assistanceLabel = if (it == "none") "" else context.assistanceLabel)) }
    if (context.assistance != WorkoutAssistance.none) OutlinedTextField(context.assistanceLabel, { change(context.copy(assistanceLabel = it)) }, label = { Text("Assistance setting / band") })
    var load by remember(context.addedLoadKg) { mutableStateOf(context.addedLoadKg?.toString().orEmpty()) }
    OutlinedTextField(load, { load = it; change(context.copy(addedLoadKg = it.toDoubleOrNull())) }, label = { Text("Added load kg (0 for none)") })
    OutlinedTextField(context.equipment, { change(context.copy(equipment = it)) }, label = { Text("Equipment / setup label") })
    OutlinedTextField(context.cameraView, { change(context.copy(cameraView = it)) }, label = { Text("Camera view: front, oblique, side") })
    OutlinedTextField(context.variation, { change(context.copy(variation = it)) }, label = { Text("Grip / variation label") })
    LibraryChoice("Perceived effort", context.effort?.toString() ?: "unrecorded", listOf("unrecorded") + (0..10).map { it.toString() }) { change(context.copy(effort = it.toIntOrNull())) }
    LibraryChoice("Readiness", context.readiness.name, WorkoutReadiness.entries.map { it.name }) { change(context.copy(readiness = WorkoutReadiness.valueOf(it))) }
    OutlinedTextField(context.recoveryNote, { change(context.copy(recoveryNote = it)) }, label = { Text("Optional recovery note") })
}
@Composable private fun LibraryCorrectionDialog(record: WorkoutSetRecord, original: WorkoutReviewCorrection, errorMessage: String?, onClose: () -> Unit, save: (WorkoutReviewCorrection) -> Unit) {
    var correction by remember(record.id) { mutableStateOf(original) }
    var split by remember { mutableIntStateOf(1) }
    AlertDialog(onDismissRequest = onClose, title = { Text("Review set") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LibraryChoice("Exercise", (correction.exercise ?: record.exercise).name, ExerciseCounter.Exercise.entries.map { it.name }) { correction = correction.copy(exercise = ExerciseCounter.Exercise.valueOf(it).takeIf { value -> value != record.exercise }) }
            errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("Changing exercise preserves raw evidence; it does not reinterpret hold metrics or establish completed cycles.")
            record.reps.forEach { rep -> LibraryChoice("Attempt ${rep.ordinal}", correction.attempts.firstOrNull { it.repId == rep.id }?.classification?.name ?: "automatic", WorkoutAttemptClassification.entries.map { it.name }, { if (it == "userCompleted") "Completed — user reported" else it }) { value ->
                correction = correction.copy(attempts = correction.attempts.filter { it.repId != rep.id } + if (value == "automatic") emptyList() else listOf(WorkoutAttemptCorrection(rep.id, WorkoutAttemptClassification.valueOf(value))))
            } }
            LibraryContextFields(correction.context) { correction = correction.copy(context = it) }
            if (record.reps.size > 1) {
                LibraryChoice("Split after attempt", split.toString(), (1 until record.reps.size).map { it.toString() }) { split = it.toInt() }
                TextButton(onClick = { correction = correction.copy(partitions = listOf(WorkoutSetPartition(UUID.randomUUID().toString(), record.reps.take(split).map { it.id }, true), WorkoutSetPartition(UUID.randomUUID().toString(), record.reps.drop(split).map { it.id }, false))) }) { Text("Split into two sets") }
            }
            if (correction.partitions.isNotEmpty()) TextButton(onClick = { correction = correction.copy(partitions = emptyList()) }) { Text("Merge this source's splits") }
            Text("Only one split owns the hold totals. Separate recordings retain separate source clocks.")
        }
    }, confirmButton = { TextButton(onClick = { save(correction) }) { Text("Save") } }, dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } })
}
@Composable private fun LibraryRetention(policy: WorkoutMediaPolicy, save: (WorkoutMediaPolicy) -> Unit) {
    var clips by remember(policy) { mutableStateOf(policy.maximumClips.toString()) }
    var days by remember(policy) { mutableStateOf(policy.maximumAgeDays.toString()) }
    var megabytes by remember(policy) { mutableStateOf((policy.maximumBytes / 1048576).coerceAtLeast(1).toString()) }
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) { Text("Video retention limits") }
    if (expanded) {
        OutlinedTextField(clips, { clips = it }, label = { Text("Maximum videos (1–100)") })
        OutlinedTextField(days, { days = it }, label = { Text("Maximum days (1–365)") })
        OutlinedTextField(megabytes, { megabytes = it }, label = { Text("Video budget MB (1–1024)") })
        val count = clips.toIntOrNull(); val age = days.toIntOrNull(); val bytes = megabytes.toIntOrNull()
        Button(onClick = { save(policy.copy(maximumClips = requireNotNull(count), maximumAgeDays = requireNotNull(age), maximumBytes = requireNotNull(bytes) * 1048576L)) },
            enabled = count != null && count in 1..100 && age != null && age in 1..365 && bytes != null && bytes in 1..1024) { Text("Save retention limits") }
    }
}
@Composable private fun LibraryPlans(dashboard: LibraryDashboard, apply: (WorkoutLibraryCommand) -> Unit) {
    var exercise by remember { mutableStateOf(ExerciseCounter.Exercise.pullUp) }
    var metric by remember { mutableStateOf(WorkoutGoalMetric.holdSeconds) }
    var target by remember { mutableStateOf("5") }; var count by remember { mutableStateOf("3") }
    var day by remember { mutableLongStateOf(WorkoutInsights.localDay(System.currentTimeMillis(), TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000)) }
    var choosingDate by remember { mutableStateOf(false) }
    var context by remember { mutableStateOf(WorkoutContext()) }
    Text("Plan your practice", style = MaterialTheme.typography.headlineSmall)
    Text("Plans use your chosen targets. Link an observed workout to mark completion. These are tracking defaults, not training prescriptions.")
    dashboard.insights.plans.forEach { report ->
        val status = when (report.status) { "completed" -> "Completed"; "observed" -> "Recorded progress"; "linkedNotEligible" -> "Linked recording does not match"; "notRecorded" -> "No eligible recording"; else -> "Planned" }
        Text("${report.title} · $status", style = MaterialTheme.typography.titleMedium)
        report.scheduledDay?.let { Text("Planned for " + DateFormat.getDateInstance().apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(it * 86400000L))) }
        report.items.forEachIndexed { index, item ->
            Text("${item.exercise.title}: ${item.sets} sets of ${item.target} ${if (item.metric == WorkoutGoalMetric.holdSeconds) "hold seconds" else "completed cycles"} per set")
            Text("${report.measuredValues.getOrElse(index) { 0.0 }.toInt()}/${item.sets} sets meet this target${if (report.workoutId == null) " · preview; link to confirm" else ""}", style = MaterialTheme.typography.bodySmall)
            Text("${item.context.assistance.name} ${item.context.assistanceLabel} · ${item.context.addedLoadKg?.let { "$it kg" } ?: "load unrecorded"} · ${item.context.equipment} · ${item.context.cameraView} · ${item.context.intent.name} ${item.context.variation}", style = MaterialTheme.typography.bodySmall)
        }
        val plan = dashboard.state.plans.single { it.id == report.planId }
        var choosingWorkout by remember(report.planId) { mutableStateOf(false) }
        if (report.candidateWorkoutIds.isNotEmpty()) {
            TextButton(onClick = { choosingWorkout = true }) { Text(if (report.workoutId == null) "Link recorded workout" else "Change linked workout") }
            DropdownMenu(choosingWorkout, { choosingWorkout = false }) {
                report.candidateWorkoutIds.forEach { id ->
                    val session = dashboard.insights.sessions.firstOrNull { it.id == id }
                    val label = session?.let { "${it.startedAtEpochMilliseconds?.let { time -> DateFormat.getDateTimeInstance().format(Date(time)) } ?: "Date unavailable"} · ${it.exercises.joinToString { exercise -> exercise.title }} · ${it.setIds.size} sets" } ?: "Recorded workout ${id.take(8)}"
                    DropdownMenuItem(text = { Text(label) }, onClick = {
                        choosingWorkout = false
                        apply(WorkoutLibraryCommand("plan", plan = plan.copy(workoutId = id), atEpochMilliseconds = System.currentTimeMillis()))
                    })
                }
            }
        } else Text("No comparable recording on the planned day with sufficient tracking coverage.")
        if (report.workoutId != null) TextButton(onClick = { apply(WorkoutLibraryCommand("plan", plan = plan.copy(workoutId = null), atEpochMilliseconds = System.currentTimeMillis())) }) { Text("Unlink workout") }
        TextButton(onClick = { apply(WorkoutLibraryCommand("deletePlan", id = report.planId, atEpochMilliseconds = System.currentTimeMillis())) }) { Text("Delete plan") }
    }
    Text("Create a single-exercise plan. Declare assistance, load, equipment and camera view to match recordings.", style = MaterialTheme.typography.bodySmall)
    LibraryChoice("Exercise", exercise.name, ExerciseCounter.Exercise.entries.map { it.name }) { exercise = ExerciseCounter.Exercise.valueOf(it) }
    LibraryChoice("Metric", metric.name, WorkoutGoalMetric.entries.map { it.name }) { metric = WorkoutGoalMetric.valueOf(it) }
    OutlinedTextField(target, { target = it }, label = { Text("Target per set") })
    OutlinedTextField(count, { count = it }, label = { Text("Number of sets") })
    TextButton(onClick = { choosingDate = true }) { Text("Practice date: " + remember(day) { DateFormat.getDateInstance().apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(day * 86400000L)) }) }
    if (choosingDate) {
        @OptIn(ExperimentalMaterial3Api::class)
        val date = rememberDatePickerState(initialSelectedDateMillis = day * 86400000L)
        @OptIn(ExperimentalMaterial3Api::class)
        DatePickerDialog(onDismissRequest = { choosingDate = false }, confirmButton = { TextButton(onClick = { date.selectedDateMillis?.let { day = it / 86400000L }; choosingDate = false }) { Text("Choose") } }) { DatePicker(date) }
    }
    LibraryContextFields(context) { context = it }
    Button(onClick = {
        val targetValue = target.toDoubleOrNull(); val countValue = count.toIntOrNull()
        if (targetValue != null && countValue != null) apply(WorkoutLibraryCommand("plan", plan = WorkoutPlan(UUID.randomUUID().toString(), "${exercise.title} practice", day,
            listOf(WorkoutPlanItem(UUID.randomUUID().toString(), exercise, metric, targetValue, countValue, context))), atEpochMilliseconds = System.currentTimeMillis()))
    }, enabled = target.toDoubleOrNull() != null && count.toIntOrNull() != null) { Text("Save practice plan") }
}
