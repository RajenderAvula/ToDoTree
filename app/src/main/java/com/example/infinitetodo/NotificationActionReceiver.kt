package com.example.infinitetodo

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_SNOOZE = "com.example.infinitetodo.ACTION_SNOOZE"
        const val ACTION_DISMISS = "com.example.infinitetodo.ACTION_DISMISS"

        const val EXTRA_TASK_ID = "EXTRA_TASK_ID"
        const val EXTRA_TASK_TITLE = "EXTRA_TASK_TITLE"
        const val EXTRA_NOTIFICATION_TYPE = "EXTRA_NOTIFICATION_TYPE"
        const val EXTRA_NOTIFICATION_ID = "EXTRA_NOTIFICATION_ID"
        const val EXTRA_SNOOZE_MINUTES = "EXTRA_SNOOZE_MINUTES"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Cancel current notification banner
        if (notificationId != 0) {
            notificationManager.cancel(notificationId)
        }

        when (intent.action) {
            ACTION_DISMISS -> {
                // Already dismissed above
            }
            ACTION_SNOOZE -> {
                val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
                val taskTitle = intent.getStringExtra(EXTRA_TASK_TITLE) ?: "Task Reminder"
                val notificationType = intent.getStringExtra(EXTRA_NOTIFICATION_TYPE) ?: "Reminder"
                val snoozeMinutes = intent.getIntExtra(EXTRA_SNOOZE_MINUTES, 5)

                if (taskId != -1L) {
                    val workData = Data.Builder()
                        .putLong("TASK_ID", taskId)
                        .putString("TASK_TITLE", taskTitle)
                        .putString("NOTIFICATION_TYPE", "Snoozed $notificationType")
                        .build()

                    val snoozeWork = OneTimeWorkRequestBuilder<TaskReminderWorker>()
                        .setInitialDelay(snoozeMinutes.toLong(), TimeUnit.MINUTES)
                        .setInputData(workData)
                        .addTag("SNOOZE_${taskId}_${System.currentTimeMillis()}")
                        .build()

                    WorkManager.getInstance(context).enqueue(snoozeWork)
                }
            }
        }
    }
}
