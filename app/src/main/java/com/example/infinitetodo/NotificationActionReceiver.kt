package com.example.infinitetodo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "TASK_ALERTS_HIGH_PRIORITY"
        const val CHANNEL_NAME = "Tasks Alerts & Due Dates"

        const val ACTION_TRIGGER_ALERT = "com.example.infinitetodo.TRIGGER_ALERT"
        const val ACTION_SNOOZE = "com.example.infinitetodo.ACTION_SNOOZE"
        const val ACTION_DISMISS = "com.example.infinitetodo.ACTION_DISMISS"

        const val EXTRA_TASK_ID = "EXTRA_TASK_ID"
        const val EXTRA_TASK_TITLE = "EXTRA_TASK_TITLE"
        const val EXTRA_NOTIFICATION_TYPE = "EXTRA_NOTIFICATION_TYPE"
        const val EXTRA_NOTIFICATION_ID = "EXTRA_NOTIFICATION_ID"
        const val EXTRA_SNOOZE_MINUTES = "EXTRA_SNOOZE_MINUTES"
        const val EXTRA_REQUEST_CODE_OFFSET = "EXTRA_REQUEST_CODE_OFFSET"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        val taskTitle = intent.getStringExtra(EXTRA_TASK_TITLE) ?: "Task Alert"
        val type = intent.getStringExtra(EXTRA_NOTIFICATION_TYPE) ?: "Reminder"
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, (taskId * 10).toInt())

        when (intent.action) {
            ACTION_TRIGGER_ALERT -> {
                if (taskId != -1L) {
                    CoroutineScope(Dispatchers.IO).launch {
                        val dao = AppDatabase.getDatabase(context).taskDao()
                        val task = dao.getTaskById(taskId)
                        if (task != null && !task.isCompleted) {
                            showHighPriorityNotification(context, task, type, notificationId)

                            if (type == TaskSchedulerHelper.TYPE_REPEAT && task.repeatRule != RecurrenceRule.NONE) {
                                val nextTime = getNextRecurringTime(task)
                                if (nextTime != null) {
                                    TaskSchedulerHelper.setExactAlarm(
                                        context,
                                        task.id,
                                        task.title,
                                        TaskSchedulerHelper.TYPE_REPEAT,
                                        nextTime,
                                        3000
                                    )
                                }
                            }
                        }
                    }
                }
            }

            ACTION_SNOOZE -> {
                notificationManager.cancel(notificationId)
                val snoozeMinutes = intent.getIntExtra(EXTRA_SNOOZE_MINUTES, 5)
                val snoozeTriggerAt = System.currentTimeMillis() + (snoozeMinutes * 60 * 1000L)

                TaskSchedulerHelper.setExactAlarm(
                    context,
                    taskId,
                    taskTitle,
                    "Snoozed $type",
                    snoozeTriggerAt,
                    5000 + snoozeMinutes
                )
            }

            ACTION_DISMISS -> {
                notificationManager.cancel(notificationId)
            }
        }
    }

    private fun showHighPriorityNotification(
        context: Context,
        task: TaskItem,
        type: String,
        notificationId: Int
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Urgent task alarms, due dates, and snoozes"
                enableLights(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), null)
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Open App Button
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_TASK_ID", task.id)
        }
        val openPI = PendingIntent.getActivity(
            context,
            notificationId + 1,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Snooze 5 Min Button
        val snooze5Intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = ACTION_SNOOZE
            putExtra(EXTRA_TASK_ID, task.id)
            putExtra(EXTRA_TASK_TITLE, task.title)
            putExtra(EXTRA_NOTIFICATION_TYPE, type)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(EXTRA_SNOOZE_MINUTES, 5)
        }
        val snooze5PI = PendingIntent.getBroadcast(
            context,
            notificationId + 2,
            snooze5Intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Snooze 10 Min Button
        val snooze10Intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = ACTION_SNOOZE
            putExtra(EXTRA_TASK_ID, task.id)
            putExtra(EXTRA_TASK_TITLE, task.title)
            putExtra(EXTRA_NOTIFICATION_TYPE, type)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(EXTRA_SNOOZE_MINUTES, 10)
        }
        val snooze10PI = PendingIntent.getBroadcast(
            context,
            notificationId + 3,
            snooze10Intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Dismiss / Close Button
        val dismissIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = ACTION_DISMISS
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        val dismissPI = PendingIntent.getBroadcast(
            context,
            notificationId + 4,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val bodyText = if (!task.notes.isNullOrBlank()) task.notes else "Tap to open or choose an option below"

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("[$type] ${task.title}")
            .setContentText(bodyText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(openPI)
            .addAction(android.R.drawable.ic_menu_view, "Open", openPI)
            .addAction(android.R.drawable.ic_lock_idle_alarm, "+5 Min", snooze5PI)
            .addAction(android.R.drawable.ic_lock_idle_alarm, "+10 Min", snooze10PI)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Close", dismissPI)

        notificationManager.notify(notificationId, builder.build())
    }

    private fun getNextRecurringTime(task: TaskItem): Long? {
        val base = System.currentTimeMillis()
        val calendar = Calendar.getInstance()
        return when (task.repeatRule) {
            RecurrenceRule.DAILY -> base + (24 * 60 * 60 * 1000L)
            RecurrenceRule.WEEKLY -> base + (7 * 24 * 60 * 60 * 1000L)
            RecurrenceRule.FORTNIGHTLY -> base + (14 * 24 * 60 * 60 * 1000L)
            RecurrenceRule.MONTHLY -> {
                calendar.add(Calendar.MONTH, 1)
                calendar.timeInMillis
            }
            RecurrenceRule.SIX_MONTHLY -> {
                calendar.add(Calendar.MONTH, 6)
                calendar.timeInMillis
            }
            RecurrenceRule.YEARLY -> {
                calendar.add(Calendar.YEAR, 1)
                calendar.timeInMillis
            }
            RecurrenceRule.CUSTOM -> {
                val totalMinutes = (task.repeatIntervalDays * 24 * 60) +
                        (task.repeatIntervalHours * 60) +
                        task.repeatIntervalMinutes
                if (totalMinutes > 0) base + (totalMinutes * 60 * 1000L) else null
            }
            RecurrenceRule.NONE -> null
        }
    }
}
