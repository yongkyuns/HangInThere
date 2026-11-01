package dev.hanginthere.android.session

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.hanginthere.android.MainActivity
import dev.hanginthere.android.R
import dev.hanginthere.core.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.util.concurrent.Executors

/** One pending, inexact alarm. The shared policy owns dates and completed-day suppression. */
internal object WorkoutReminders {
    @Volatile var foreground = false
    const val tipsKey = "trainingTips"
    private const val tipAction = "dev.hanginthere.TRAINING_TIP"
    private const val tipChannel = "training-tips"
    private const val channel="workout-reminders"
    private val worker=Executors.newSingleThreadExecutor()
    fun preferences(context:Context)=context.getSharedPreferences("workoutReminders",Context.MODE_PRIVATE)
    fun tipSettings(context: Context): WorkoutTipSettings = runCatching {
        WorkoutRecordCodec.json.decodeFromString<WorkoutTipSettings>(preferences(context).getString(tipsKey, null) ?: return@runCatching WorkoutTipSettings()).also { it.validate() }
    }.getOrDefault(WorkoutTipSettings())
    fun saveTips(context: Context, settings: WorkoutTipSettings) {
        settings.validate()
        preferences(context).edit().putString(tipsKey, WorkoutRecordCodec.json.encodeToString(settings)).apply()
        refresh(context)
    }
    fun tipsAllowed(context: Context) = allowed(context) && context.getSystemService(NotificationManager::class.java).getNotificationChannel(tipChannel)?.importance != NotificationManager.IMPORTANCE_NONE
    fun settings(context:Context):WorkoutReminderSchedule = runCatching {
        WorkoutRecordCodec.json.decodeFromString<WorkoutReminderSchedule>(preferences(context).getString("settings",null) ?: return@runCatching WorkoutReminderSchedule()).also { it.validate() }
    }.getOrDefault(WorkoutReminderSchedule())
    fun save(context:Context,schedule:WorkoutReminderSchedule) {
        schedule.validate()
        preferences(context).edit().putString("settings",WorkoutRecordCodec.json.encodeToString(schedule)).apply()
        refresh(context)
    }
    fun allowed(context:Context)=NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)
    fun error(context:Context)=preferences(context).getString("error",null)
    private fun alarm(context:Context,at:Long=0):PendingIntent=PendingIntent.getBroadcast(context,31,
        Intent(context,WorkoutReminderReceiver::class.java).setAction("dev.hanginthere.WORKOUT_REMINDER").putExtra("scheduledAt",at),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun refresh(context:Context)=enqueue(context.applicationContext) { reconcile(it) }
    private fun enqueue(context:Context,done:()->Unit={},action:(Context)->Unit) {
        worker.execute {
            try { action(context);preferences(context).edit().remove("error").apply() }
            catch (_:Exception) { preferences(context).edit().putString("error","Couldn’t update reminders. Open the app to retry.").apply() }
            finally { done() }
        }
    }
    private fun reconcile(context:Context) {
        reconcileTips(context)
        val schedule=settings(context)
        val manager=context.getSystemService(AlarmManager::class.java)
        if(!schedule.enabled || !allowed(context)) {
            manager.cancel(alarm(context));NotificationManagerCompat.from(context).cancel(31);preferences(context).edit().remove("scheduledAt").apply();return
        }
        val now=System.currentTimeMillis();val zone=java.time.ZoneId.systemDefault().id
        val days=WorkoutActivityProgress.completedDays(WorkoutRecordStore.live(context).libraryForReview(),now,zone)
        if(WorkoutCalendarPolicy.day(now,zone).day in days)NotificationManagerCompat.from(context).cancel(31)
        val next=WorkoutReminderPolicy.occurrences(schedule,now,zone,days).firstOrNull()
        manager.cancel(alarm(context))
        if(next!=null) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next.epochMilliseconds,alarm(context,next.epochMilliseconds))
            preferences(context).edit().putLong("scheduledAt",next.epochMilliseconds).apply()
        }else preferences(context).edit().remove("scheduledAt").apply()
    }
    private fun tipAlarm(context: Context, at: Long = 0): PendingIntent = PendingIntent.getBroadcast(context, 32,
        Intent(context, WorkoutReminderReceiver::class.java).setAction(tipAction).putExtra("scheduledAt", at),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun reconcileTips(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java)
        manager.cancel(tipAlarm(context))
        if (!tipSettings(context).notificationsEnabled || !tipsAllowed(context)) {
            preferences(context).edit().remove("scheduledTipAt").apply()
            NotificationManagerCompat.from(context).cancel(32)
            return
        }
        val zone = java.time.ZoneId.systemDefault().id
        val lastDay = preferences(context).getLong("lastTipDay", Long.MIN_VALUE)
        val next = WorkoutTipDelivery.notifications(tipSettings(context), System.currentTimeMillis(), zone).firstOrNull {
            WorkoutCalendarPolicy.day(it.epochMilliseconds, zone).day != lastDay
        }
        if (next != null) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.epochMilliseconds, tipAlarm(context, next.epochMilliseconds))
            preferences(context).edit().putLong("scheduledTipAt", next.epochMilliseconds).apply()
        } else preferences(context).edit().remove("scheduledTipAt").apply()
    }
    private fun receiveTip(app: Context, intent: Intent) {
        val scheduled = intent.getLongExtra("scheduledAt", 0)
        val now = System.currentTimeMillis(); val zone = java.time.ZoneId.systemDefault().id
        val day = WorkoutCalendarPolicy.day(now, zone).day
        if (scheduled <= 0 || scheduled > now || preferences(app).getLong("scheduledTipAt", 0) != scheduled ||
            !tipSettings(app).notificationsEnabled || !tipsAllowed(app) || WorkoutCalendarPolicy.day(scheduled, zone).day != day) return
        // Consume before posting, so duplicate broadcasts cannot produce duplicate tips.
        preferences(app).edit().remove("scheduledTipAt").apply()
        if (foreground || preferences(app).getLong("lastTipDay", Long.MIN_VALUE) == day) return
        val item = WorkoutTipDelivery.notifications(tipSettings(app), scheduled - 1, zone).firstOrNull { it.epochMilliseconds == scheduled } ?: return
        val tip = WorkoutTips.catalog.tips.firstOrNull { it.id == item.tipId } ?: return
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(tipChannel, "Training tips", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(app, 32, Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("trainingTipId", tip.id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(app, tipChannel).setSmallIcon(R.drawable.training_book)
            .setContentTitle("Training tip · ${tip.title}").setContentText(tip.cue)
            .setStyle(NotificationCompat.BigTextStyle().bigText(tip.cue)).setSilent(true)
            .setContentIntent(open).setAutoCancel(true).build()
        try {
            NotificationManagerCompat.from(app).notify(32, notification)
            preferences(app).edit().putLong("lastTipDay", day).apply()
        } catch (_: SecurityException) { /* Permission can change after the check. */ }
    }
    fun receive(context:Context,intent:Intent,done:()->Unit) = enqueue(context.applicationContext,done) { app ->
        if (intent.action == tipAction) receiveTip(app, intent)
        if(intent.action=="dev.hanginthere.WORKOUT_REMINDER") {
            val scheduled=intent.getLongExtra("scheduledAt",0)
            val now=System.currentTimeMillis();val zone=java.time.ZoneId.systemDefault().id
            val currentDay=WorkoutCalendarPolicy.day(now,zone).day
            val valid=scheduled>0 && scheduled<=now && preferences(app).getLong("scheduledAt",0)==scheduled && settings(app).enabled && allowed(app) &&
                WorkoutCalendarPolicy.day(scheduled,zone).day==currentDay
            if(valid && currentDay !in WorkoutActivityProgress.completedDays(WorkoutRecordStore.live(app).libraryForReview(),now,zone)) {
                val manager=app.getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(NotificationChannel(channel,"Workout reminders",NotificationManager.IMPORTANCE_DEFAULT))
                val open=PendingIntent.getActivity(app,31,Intent(app,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val notification=NotificationCompat.Builder(app,channel).setSmallIcon(R.drawable.exercise_pull_up)
                    .setContentTitle("Time for your workout").setContentText("A little movement, at your pace.")
                    .setContentIntent(open).setAutoCancel(true).build()
                try { NotificationManagerCompat.from(app).notify(31,notification) }
                catch (_: SecurityException) { /* Permission may be revoked after allowed(); reconcile below cancels the alarm. */ }
            }
        }
        reconcile(app)
    }
}

class WorkoutReminderReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action !in setOf("dev.hanginthere.WORKOUT_REMINDER","dev.hanginthere.TRAINING_TIP",Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED,Intent.ACTION_MY_PACKAGE_REPLACED))return
        val pending=goAsync()
        WorkoutReminders.receive(context,intent) {pending.finish()}
    }
}
