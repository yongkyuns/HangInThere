package dev.hanginthere.android.ui

import android.Manifest
import android.app.TimePickerDialog
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import dev.hanginthere.android.session.WorkoutReminders

@Composable internal fun WorkoutReminderControls() {
    val context=LocalContext.current
    var schedule by remember {mutableStateOf(WorkoutReminders.settings(context))}
    var denied by remember {mutableStateOf(false)}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        denied=!granted
        if(granted){schedule=schedule.copy(enabled=true);WorkoutReminders.save(context,schedule)}
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row {
                Text("Workout reminders",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                Switch(checked=schedule.enabled,onCheckedChange={ enabled ->
                    if(enabled && Build.VERSION.SDK_INT>=33 && !WorkoutReminders.allowed(context))permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else {schedule=schedule.copy(enabled=enabled);WorkoutReminders.save(context,schedule);denied=enabled && !WorkoutReminders.allowed(context)}
                })
            }
            if(schedule.enabled) {
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp), maxItemsInEachRow=4) {
                    val calendar=java.util.Calendar.getInstance()
                    val labels=java.text.DateFormatSymbols().shortWeekdays
                    (0..6).map { (calendar.firstDayOfWeek-1+it)%7+1 }.forEach { day ->
                        FilterChip(selected=day in schedule.weekdays,onClick={
                            val days=if(day in schedule.weekdays)schedule.weekdays-day else schedule.weekdays+day
                            if(days.isNotEmpty()){schedule=schedule.copy(weekdays=days.sorted());WorkoutReminders.save(context,schedule)}
                        },label={Text(labels[day])},modifier=Modifier.semantics { contentDescription=java.text.DateFormatSymbols().weekdays[day] })
                    }
                }
                TextButton(onClick={TimePickerDialog(context,{_,hour,minute->schedule=schedule.copy(hour=hour,minute=minute);WorkoutReminders.save(context,schedule)},schedule.hour,schedule.minute,android.text.format.DateFormat.is24HourFormat(context)).show()}) {
                    Text("Around %02d:%02d".format(schedule.hour,schedule.minute))
                }
                Text("Skipped on days you’ve already trained.",style=MaterialTheme.typography.bodySmall)
            }
            if(denied || schedule.enabled && !WorkoutReminders.allowed(context)) {
                Text("Notifications are off in Settings.",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,context.packageName))}){Text("Open Settings")}
            }
            WorkoutReminders.error(context)?.let {Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)}
        }
    }
}
