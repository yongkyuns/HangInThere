package dev.hanginthere.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.hanginthere.core.WorkoutActivityProgress
import dev.hanginthere.android.R
import dev.hanginthere.core.WorkoutTip
import dev.hanginthere.core.WorkoutTips
import dev.hanginthere.core.WorkoutTipSettings
import dev.hanginthere.core.WorkoutTipDelivery
import dev.hanginthere.android.session.WorkoutReminders
import java.time.LocalDate

/** A quiet, optional card; never shown on top of the live camera. */
@Composable internal fun WorkoutTrainingTipCard(exercise: String? = null, moment: String = "plan", ordinal: Int? = null, onPresentationChanged: (Boolean) -> Unit = {}) {
    if (!rememberTrainingTipSettings().workoutTipsEnabled) return
    val tip = WorkoutTips.featured(exercise, moment, ordinal?.toLong()?.minus(1) ?: LocalDate.now().toEpochDay()) ?: return
    var open by remember { mutableStateOf(false) }
    var showDetail by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Training tip", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                IconButton(onClick = { showDetail = false; open = true; onPresentationChanged(true) }, modifier = Modifier.testTag("browseTrainingTips")) {
                    Icon(painterResource(R.drawable.training_book), contentDescription = "Browse training tips")
                }
            }
            TextButton(onClick = { showDetail = true; open = true; onPresentationChanged(true) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.fillMaxWidth().testTag("trainingTip-${tip.id}")) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(tip.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(tip.cue, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (open) WorkoutTrainingTipsDialog(exercise, if (showDetail) tip else null) { open = false; onPresentationChanged(false) }
}

@Composable internal fun rememberTrainingTipSettings(): WorkoutTipSettings {
    val context = LocalContext.current
    val preferences = remember(context) { WorkoutReminders.preferences(context) }
    var settings by remember { mutableStateOf(WorkoutReminders.tipSettings(context)) }
    DisposableEffect(preferences) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == WorkoutReminders.tipsKey) settings = WorkoutReminders.tipSettings(context)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return settings
}

@Composable internal fun WorkoutBetweenSetTip(exercise: String, completedSets: Int) {
    val enabled = rememberTrainingTipSettings().workoutTipsEnabled
    val tip = WorkoutTipDelivery.betweenSets(exercise, completedSets, running = false, replay = false, enabled = enabled) ?: return
    Surface(color = androidx.compose.ui.graphics.Color.Black.copy(alpha = .7f), shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) {
        Text(tip.cue, Modifier.padding(10.dp).testTag("betweenSetTrainingTip"), color = androidx.compose.ui.graphics.Color(0xFFD4F09C), style = MaterialTheme.typography.titleMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun WorkoutTipSettingsDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val settings = rememberTrainingTipSettings()
    var denied by remember { mutableStateOf(false) }
    var library by remember { mutableStateOf(false) }
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        denied = !granted
        if (granted) WorkoutReminders.saveTips(context, WorkoutReminders.tipSettings(context).copy(notificationsEnabled = true))
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.systemBarsPadding()) {
                TopAppBar(title = { Text("Settings") }, actions = { TextButton(onClick = onClose) { Text("Done") } })
                Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Workout tips", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Switch(settings.workoutTipsEnabled, { WorkoutReminders.saveTips(context, settings.copy(workoutTipsEnabled = it)) }, Modifier.testTag("toggleWorkoutTips").semantics { contentDescription = "Workout tips" })
                    }
                    Text("Relevant tips between sets, in setup and in reviews.", style = MaterialTheme.typography.bodyMedium)
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Tip notifications", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Switch(settings.notificationsEnabled, { enabled ->
                            if (enabled && android.os.Build.VERSION.SDK_INT >= 33 && !WorkoutReminders.allowed(context)) permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            else { WorkoutReminders.saveTips(context, settings.copy(notificationsEnabled = enabled)); denied = enabled && !WorkoutReminders.tipsAllowed(context) }
                        }, Modifier.testTag("toggleTipNotifications").semantics { contentDescription = "Tip notifications" })
                    }
                    if (settings.notificationsEnabled) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(!settings.daily, { WorkoutReminders.saveTips(context, settings.copy(daily = false)) }, label = { Text("Three / week") })
                            FilterChip(settings.daily, { WorkoutReminders.saveTips(context, settings.copy(daily = true)) }, label = { Text("Daily") })
                        }
                        TextButton(onClick = { android.app.TimePickerDialog(context, { _, hour, minute ->
                            WorkoutReminders.saveTips(context, WorkoutReminders.tipSettings(context).copy(hour = hour, minute = minute))
                        }, settings.hour, settings.minute, android.text.format.DateFormat.is24HourFormat(context)).show() }) {
                            Text("Around %02d:%02d".format(settings.hour, settings.minute))
                        }
                    }
                    Text("Quiet tips at your chosen time. Three times a week is Monday, Wednesday and Friday.", style = MaterialTheme.typography.bodySmall)
                    if (denied || settings.notificationsEnabled && !WorkoutReminders.tipsAllowed(context)) {
                        Text("Notifications are off in Settings.", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text("Open notification settings") }
                    }
                    TextButton(onClick = { library = true }) { Text("Browse training tips") }
                    WorkoutReminders.error(context)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
    if (library) WorkoutTrainingTipsDialog(onClose = { library = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun WorkoutTrainingTipsDialog(exercise: String? = null, initialTip: WorkoutTip? = null, onClose: () -> Unit) {
    val exerciseLabels = linkedMapOf("" to "All exercises", "pullUp" to "Pull-ups", "dip" to "Dips", "pushUp" to "Push-ups", "sitUp" to "Sit-ups")
    var selectedExercise by remember { mutableStateOf(exerciseLabels.keys.firstOrNull { it.isNotEmpty() && WorkoutActivityProgress.exerciseKey(it) == exercise?.let(WorkoutActivityProgress::exerciseKey) } ?: "") }
    var category by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var selectedTip by remember { mutableStateOf(initialTip) }
    val tips = remember(selectedExercise, category, search) { WorkoutTips.query(selectedExercise.takeIf { it.isNotEmpty() }, category.takeIf { it.isNotEmpty() }, search) }
    val detail = selectedTip
    BackHandler { if (selectedTip != null) selectedTip = null else onClose() }
    Dialog(onDismissRequest = { if (selectedTip != null) selectedTip = null else onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.systemBarsPadding()) {
                TopAppBar(title = { Text(if (detail == null) "Training tips" else "Training tip") }, navigationIcon = {
                    if (detail != null) TextButton(onClick = { selectedTip = null }) { Text("Back") }
                }, actions = { TextButton(onClick = onClose) { Text("Done") } })
                if (detail != null) {
                    WorkoutTrainingTipDetail(detail)
                } else {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = search, onValueChange = { search = it }, label = { Text("Search tips") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("trainingTipSearch"))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TipFilter("Exercise", exerciseLabels.getValue(selectedExercise), exerciseLabels, Modifier.weight(1f)) { selectedExercise = it }
                            TipFilter("Topic", category.ifEmpty { "All topics" }, linkedMapOf("" to "All topics") + WorkoutTips.categories.associateWith { it }, Modifier.weight(1f)) { category = it }
                        }
                    }
                    LazyColumn(Modifier.weight(1f).testTag("trainingTipLibrary"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item { Text("${tips.size} tips · Sources included", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        items(tips, key = { it.id }) { tip ->
                            Card(onClick = { selectedTip = tip }, modifier = Modifier.fillMaxWidth().testTag("tip-${tip.id}")) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(tip.category, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    Text(tip.title, style = MaterialTheme.typography.titleMedium)
                                    Text(tip.cue, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        if (tips.isEmpty()) item { Text("No matching tips. Try another topic, exercise or search.") }
                        item { Text(WorkoutTips.catalog.audience, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}

@Composable private fun TipFilter(label: String, selected: String, options: Map<String, String>, modifier: Modifier, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("$label: $selected") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { onSelect(key); expanded = false }) }
        }
    }
}

@Composable internal fun WorkoutTrainingTipDetail(tip: WorkoutTip) {
    val uriHandler = LocalUriHandler.current
    var linkError by remember(tip.id) { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(tip.category, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(tip.title, style = MaterialTheme.typography.headlineLarge)
        Text(tip.cue, style = MaterialTheme.typography.titleLarge)
        Text(tip.detail, style = MaterialTheme.typography.bodyLarge)
        if (tip.exercises.isNotEmpty()) {
            val names = mapOf("pullUp" to "Pull-ups", "dip" to "Dips", "pushUp" to "Push-ups", "sitUp" to "Sit-ups")
            Text("For ${tip.exercises.joinToString { names[it] ?: it }}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("Sources", style = MaterialTheme.typography.titleMedium)
        WorkoutTips.catalog.sources.filter { it.id in tip.sourceIds }.forEach { source ->
            Card(onClick = { linkError = runCatching { uriHandler.openUri(source.url) }.isFailure }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(source.publisher, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text(source.title, style = MaterialTheme.typography.bodyMedium)
                    Text("${source.kind} · Checked ${source.checkedOn}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (linkError) Text("Couldn't open the source. Check that a browser is available.", color = MaterialTheme.colorScheme.error)
        Text("Adapted from the linked guidance. Not an endorsement or individual medical assessment.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
