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
        syncWithGoogleCalendar: Boolean = false,
        contactName: String? = null,
        contactPhone: String? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            persistUriPermission(attachmentUri)

            var googleCalendarEventId: Long? = null
            if (syncWithGoogleCalendar && reminderEpochMs != null) {
                googleCalendarEventId = syncCalendarEvent(title, reminderEpochMs, attachmentName)
            }

            val siblings = dao.getSubtasksSnapshot(parentId)
            val newTask = TaskItem(
                parentId = parentId,
                title = title,
                reminderTimestamp = reminderEpochMs,
                attachmentUri = attachmentUri?.toString(),
                attachmentName = attachmentName,
                calendarEventId = googleCalendarEventId,
                contactName = contactName,
                contactPhone = contactPhone,
                orderIndex = siblings.size
            )
            val generatedId = dao.insertTask(newTask)

            if (reminderEpochMs != null && reminderEpochMs > System.currentTimeMillis()) {
                scheduleReminder(generatedId, title, reminderEpochMs)
            }
        }
    }

    fun updateTask(
        task: TaskItem,
        newTitle: String,
        reminderEpochMs: Long?,
        attachmentUri: Uri?,
        attachmentName: String?,
        syncWithGoogleCalendar: Boolean,
        contactName: String?,
        contactPhone: String?
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            persistUriPermission(attachmentUri)

            var calendarEventId = task.calendarEventId
            if (syncWithGoogleCalendar && reminderEpochMs != null) {
                if (calendarEventId != null) {
                    CalendarHelper.deleteEvent(getApplication(), calendarEventId)
                }
                calendarEventId = syncCalendarEvent(newTitle, reminderEpochMs, attachmentName)
            }

            val updatedTask = task.copy(
                title = newTitle,
                reminderTimestamp = reminderEpochMs,
                attachmentUri = attachmentUri?.toString() ?: task.attachmentUri,
                attachmentName = attachmentName ?: task.attachmentName,
                calendarEventId = calendarEventId,
                contactName = contactName,
                contactPhone = contactPhone
            )
            dao.updateTask(updatedTask)

            workManager.cancelAllWorkByTag("TASK_${task.id}")
            if (reminderEpochMs != null && reminderEpochMs > System.currentTimeMillis()) {
                scheduleReminder(task.id, newTitle, reminderEpochMs)
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
            task.calendarEventId?.let { calEventId ->
                CalendarHelper.deleteEvent(getApplication(), calEventId)
            }
        }
    }

    // Reordering sliding tasks up and down
    fun moveTask(task: TaskItem, directionUp: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val siblings = dao.getSubtasksSnapshot(task.parentId).toMutableList()
            val currentIndex = siblings.indexOfFirst { it.id == task.id }
            if (currentIndex == -1) return@launch

            val targetIndex = if (directionUp) currentIndex - 1 else currentIndex + 1
            if (targetIndex in siblings.indices) {
                val currentTask = siblings[currentIndex]
                val swapTask = siblings[targetIndex]

                dao.updateTask(currentTask.copy(orderIndex = targetIndex))
                dao.updateTask(swapTask.copy(orderIndex = currentIndex))
            }
        }
    }

    // Deep duplicate a task and all recursive child subtasks
    fun duplicateTask(taskId: Long, targetParentId: Long?) {
        viewModelScope.launch(Dispatchers.IO) {
            val original = dao.getTaskById(taskId) ?: return@launch
            deepCopyRecursive(original, targetParentId)
        }
    }

    private suspend fun deepCopyRecursive(task: TaskItem, newParentId: Long?) {
        val siblings = dao.getSubtasksSnapshot(newParentId)
        val copy = task.copy(
            id = 0L,
            parentId = newParentId,
            title = "${task.title} (Copy)",
            calendarEventId = null,
            orderIndex = siblings.size
        )
        val newGeneratedId = dao.insertTask(copy)

        val children = dao.getSubtasksSnapshot(task.id)
        for (child in children) {
            deepCopyRecursive(child, newGeneratedId)
        }
    }

    private fun persistUriPermission(uri: Uri?) {
        uri?.let {
            try {
                getApplication<Application>().contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {}
        }
    }

    private fun syncCalendarEvent(title: String, timeMs: Long, attachmentName: String?): Long? {
        val hasPermission = ContextCompat.checkSelfPermission(
            getApplication(),
            android.Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            val calId = CalendarHelper.getPrimaryGoogleCalendarId(getApplication())
            if (calId != null) {
                return CalendarHelper.insertEvent(
                    context = getApplication(),
                    calendarId = calId,
                    title = title,
                    startTimeMs = timeMs,
                    notes = "Attachment: ${attachmentName ?: "None"}"
                )
            }
        }
        return null
    }

    private fun scheduleReminder(taskId: Long, title: String, triggerAtEpochMs: Long) {
        val delay = triggerAtEpochMs - System.currentTimeMillis()
        val workData = Data.Builder()
            .putLong("TASK_ID", taskId)
            .putString("TASK_TITLE", title)
            .build()

        val request = OneTimeWorkRequestBuilder<TaskReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workData)
            .addTag("TASK_$taskId")
            .build()

        workManager.enqueue(request)
    }
}
