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

    fun scheduleAllAlerts(context: Context, task: TaskItem) {
        if (task.isCompleted) {
            cancelAllAlerts(context, task.id)
            return
        }

        cancelAllAlerts(context, task.id)
        val now = System.currentTimeMillis()

        // 1. Reminder Alert
        if (task.reminderTimestamp != null && task.reminderTimestamp > now) {
            setExactAlarm(context, task.id, task.title, TYPE_REMINDER, task.reminderTimestamp, 1000)
        }

        // 2. Due Date Alert
        if (task.dueTimestamp != null && task.dueTimestamp > now) {
            setExactAlarm(context, task.id, task.title, TYPE_DUE, task.dueTimestamp, 2000)
        }

        // 3. Repeat Interval Alert
        if (task.repeatRule != RecurrenceRule.NONE) {
            val nextRepeat = calculateFirstRepeatTrigger(task, now)
            if (nextRepeat != null && nextRepeat > now) {
                setExactAlarm(context, task.id, task.title, TYPE_REPEAT, nextRepeat, 3000)
            }
        }
    }

    fun setExactAlarm(
        context: Context,
        taskId: Long,
        taskTitle: String,
        type: String,
        triggerAtMs: Long,
        requestCodeOffset: Int
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_TRIGGER_ALERT
            putExtra(NotificationActionReceiver.EXTRA_TASK_ID, taskId)
            putExtra(NotificationActionReceiver.EXTRA_TASK_TITLE, taskTitle)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_TYPE, type)
            putExtra(NotificationActionReceiver.EXTRA_REQUEST_CODE_OFFSET, requestCodeOffset)
        }

        val requestCode = (taskId * 10000 + requestCodeOffset).toInt()
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
        } catch (e: Exception) {
            Log.e("Scheduler", "Failed to schedule exact alarm for task $taskId", e)
        }
    }

    fun cancelAllAlerts(context: Context, taskId: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val offsets = listOf(1000, 2000, 3000)

        for (offset in offsets) {
            val intent = Intent(context, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_TRIGGER_ALERT
            }
            val requestCode = (taskId * 10000 + offset).toInt()
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
            }
        }
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
