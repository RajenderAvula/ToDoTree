package com.example.infinitetodo

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class TaskViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getDatabase(application).taskDao()
    private val workManager = WorkManager.getInstance(application)

    val rootTasks: Flow<List<TaskItem>> = dao.getRootTasks()

    fun getSubtasks(parentId: Long): Flow<List<TaskItem>> = dao.getSubtasks(parentId)

    fun addTask(
        title: String,
        parentId: Long? = null,
        reminderEpochMs: Long? = null,
        attachmentUri: Uri? = null,
        attachmentName: String? = null,
        syncWithGoogleCalendar: Boolean = false
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            // 1. Persist storage permissions for the attached file
            attachmentUri?.let { uri ->
                try {
                    getApplication<Application>().contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: SecurityException) {}
            }

            // 2. Silent Google Calendar Event Creation (Option B)
            var googleCalendarEventId: Long? = null
            if (syncWithGoogleCalendar && reminderEpochMs != null) {
                val hasPermission = ContextCompat.checkSelfPermission(
                    getApplication(),
                    android.Manifest.permission.WRITE_CALENDAR
                ) == PackageManager.PERMISSION_GRANTED

                if (hasPermission) {
                    val calId = CalendarHelper.getPrimaryGoogleCalendarId(getApplication())
                    if (calId != null) {
                        googleCalendarEventId = CalendarHelper.insertEvent(
                            context = getApplication(),
                            calendarId = calId,
                            title = title,
                            startTimeMs = reminderEpochMs,
                            notes = "Attachment: ${attachmentName ?: "None"}\nSynced via Infinite ToDo"
                        )
                    }
                }
            }

            // 3. Save Task to Room DB
            val newTask = TaskItem(
                parentId = parentId,
                title = title,
                reminderTimestamp = reminderEpochMs,
                attachmentUri = attachmentUri?.toString(),
                attachmentName = attachmentName,
                calendarEventId = googleCalendarEventId
            )
            val generatedId = dao.insertTask(newTask)

            // 4. Schedule Local Notification via WorkManager
            if (reminderEpochMs != null && reminderEpochMs > System.currentTimeMillis()) {
                val delay = reminderEpochMs - System.currentTimeMillis()
                val workData = Data.Builder()
                    .putLong("TASK_ID", generatedId)
                    .putString("TASK_TITLE", title)
                    .build()

                val request = OneTimeWorkRequestBuilder<TaskReminderWorker>()
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .setInputData(workData)
                    .addTag("TASK_$generatedId")
                    .build()

                workManager.enqueue(request)
            }
        }
    }

    fun toggleTaskCompletion(task: TaskItem) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.updateTask(task.copy(isCompleted = !task.isCompleted))
        }
    }

    fun deleteTask(task: TaskItem) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteTask(task)
            workManager.cancelAllWorkByTag("TASK_${task.id}")

            // Silently delete from Google Calendar if it was linked
            task.calendarEventId?.let { calEventId ->
                CalendarHelper.deleteEvent(getApplication(), calEventId)
            }
        }
    }
}

