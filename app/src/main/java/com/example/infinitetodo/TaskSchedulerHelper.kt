package com.example.infinitetodo

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.Calendar

object TaskSchedulerHelper {

    const val TYPE_REMINDER = "Reminder"
    const val TYPE_DUE = "Due Date"
    const val TYPE_REPEAT = "Repeat Alert"

    // Dedicated channels to prevent Android notification collapsing
    const val OFFSET_REMINDER = 1
    const val OFFSET_DUE = 2
    const val OFFSET_REPEAT = 3

    fun scheduleAllAlerts(context: Context, task: TaskItem) {
        if (task.isCompleted) {
            cancelAllAlerts(context, task.id)
            return
        }

        val now = System.currentTimeMillis()

        // 1. Isolated Reminder Alert
        if (task.reminderTimestamp != null && task.reminderTimestamp > now) {
            setExactAlarm(
                context = context,
                taskId = task.id,
                taskTitle = task.title,
                type = TYPE_REMINDER,
                triggerAtMs = task.reminderTimestamp,
                typeOffset = OFFSET_REMINDER
            )
        } else {
            cancelSpecificAlert(context, task.id, OFFSET_REMINDER)
        }

        // 2. Isolated Due Date Alert
        if (task.dueTimestamp != null && task.dueTimestamp > now) {
            setExactAlarm(
                context = context,
                taskId = task.id,
                taskTitle = task.title,
                type = TYPE_DUE,
                triggerAtMs = task.dueTimestamp,
                typeOffset = OFFSET_DUE
            )
        } else {
            cancelSpecificAlert(context, task.id, OFFSET_DUE)
        }

        // 3. Isolated Repeat Interval Alert
        if (task.repeatRule != RecurrenceRule.NONE) {
            val nextRepeat = calculateFirstRepeatTrigger(task, now)
            if (nextRepeat != null && nextRepeat > now) {
                setExactAlarm(
                    context = context,
                    taskId = task.id,
                    taskTitle = task.title,
                    type = TYPE_REPEAT,
                    triggerAtMs = nextRepeat,
                    typeOffset = OFFSET_REPEAT
                )
            }
        } else {
            cancelSpecificAlert(context, task.id, OFFSET_REPEAT)
        }
    }

    fun setExactAlarm(
        context: Context,
        taskId: Long,
        taskTitle: String,
        type: String,
        triggerAtMs: Long,
        typeOffset: Int
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Generate globally unique ID: combines task ID and channel offset
        val uniqueNotificationId = (taskId * 10 + typeOffset).toInt()
        val uniqueRequestCode = (taskId * 100 + typeOffset).toInt()

        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_TRIGGER_ALERT
            putExtra(NotificationActionReceiver.EXTRA_TASK_ID, taskId)
            putExtra(NotificationActionReceiver.EXTRA_TASK_TITLE, taskTitle)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_TYPE, type)
            putExtra(NotificationActionReceiver.EXTRA_TYPE_OFFSET, typeOffset)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, uniqueNotificationId)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            uniqueRequestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
        } catch (e: Exception) {
            Log.e("Scheduler", "Failed to schedule exact alarm for task $taskId", e)
        }
    }

    fun cancelSpecificAlert(context: Context, taskId: Long, typeOffset: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val uniqueRequestCode = (taskId * 100 + typeOffset).toInt()

        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_TRIGGER_ALERT
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            uniqueRequestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )

        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    fun cancelAllAlerts(context: Context, taskId: Long) {
        cancelSpecificAlert(context, taskId, OFFSET_REMINDER)
        cancelSpecificAlert(context, taskId, OFFSET_DUE)
        cancelSpecificAlert(context, taskId, OFFSET_REPEAT)
    }

    private fun calculateFirstRepeatTrigger(task: TaskItem, now: Long): Long? {
        val startCal = Calendar.getInstance()
        if (task.repeatStartDate != null) {
            startCal.timeInMillis = task.repeatStartDate
        }

        if (task.repeatStartTimeMs != null) {
            val timeCal = Calendar.getInstance().apply { timeInMillis = task.repeatStartTimeMs }
            startCal.set(Calendar.HOUR_OF_DAY, timeCal.get(Calendar.HOUR_OF_DAY))
            startCal.set(Calendar.MINUTE, timeCal.get(Calendar.MINUTE))
            startCal.set(Calendar.SECOND, 0)
        }

        var trigger = startCal.timeInMillis
        if (trigger <= now) {
            val totalMinutes = when (task.repeatRule) {
                RecurrenceRule.DAILY -> 24 * 60
                RecurrenceRule.WEEKLY -> 7 * 24 * 60
                RecurrenceRule.FORTNIGHTLY -> 14 * 24 * 60
                RecurrenceRule.MONTHLY -> 30 * 24 * 60
                RecurrenceRule.SIX_MONTHLY -> 180 * 24 * 60
                RecurrenceRule.YEARLY -> 365 * 24 * 60
                RecurrenceRule.CUSTOM -> (task.repeatIntervalDays * 24 * 60) +
                        (task.repeatIntervalHours * 60) +
                        task.repeatIntervalMinutes
                RecurrenceRule.NONE -> 0
            }
            if (totalMinutes > 0) {
                trigger = now + (totalMinutes * 60 * 1000L)
            }
        }
        return trigger
    }
}
