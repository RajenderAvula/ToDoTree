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
    fun getChecklist(taskId: Long): Flow<List<ChecklistItem>> = dao.getChecklistForTask(taskId)
    fun getAttachments(taskId: Long): Flow<List<TaskAttachment>> = dao.getAttachmentsForTask(taskId)

    fun addTask(
        title: String,
        parentId: Long? = null,
        reminderEpochMs: Long? = null,
        syncWithGoogleCalendar: Boolean = false,
        contactName: String? = null,
        contactPhone: String? = null,
        voicePath: String? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            var googleCalendarEventId: Long? = null
            if (syncWithGoogleCalendar && reminderEpochMs != null) {
                googleCalendarEventId = syncCalendarEvent(title, reminderEpochMs)
            }

            val siblings = dao.getSubtasksSnapshot(parentId)
            val newTask = TaskItem(
                parentId = parentId,
                title = title,
                reminderTimestamp = reminderEpochMs,
                calendarEventId = googleCalendarEventId,
                contactName = contactName,
                contactPhone = contactPhone,
                voiceRecordingPath = voicePath,
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
        syncWithGoogleCalendar: Boolean,
        contactName: String?,
        contactPhone: String?,
        voicePath: String?
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            var calendarEventId = task.calendarEventId
            if (syncWithGoogleCalendar && reminderEpochMs != null) {
                if (calendarEventId != null) {
                    CalendarHelper.deleteEvent(getApplication(), calendarEventId)
                }
                calendarEventId = syncCalendarEvent(newTitle, reminderEpochMs)
            }

            val updatedTask = task.copy(
                title = newTitle,
                reminderTimestamp = reminderEpochMs,
                calendarEventId = calendarEventId,
                contactName = contactName,
                contactPhone = contactPhone,
                voiceRecordingPath = voicePath ?: task.voiceRecordingPath
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
            task.calendarEventId?.let { CalendarHelper.deleteEvent(getApplication(), it) }
        }
    }

    // Sliding vertically to swap sibling position
    fun moveTaskVertical(task: TaskItem, directionUp: Boolean) {
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

    // SLIDE RIGHT: Convert to Subtask (Indent)
    fun indentTask(task: TaskItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val siblings = dao.getSubtasksSnapshot(task.parentId)
            val currentIndex = siblings.indexOfFirst { it.id == task.id }
            if (currentIndex > 0) {
                // Adopted by the preceding sibling
                val newParent = siblings[currentIndex - 1]
                val newSiblings = dao.getSubtasksSnapshot(newParent.id)
                dao.updateTask(
                    task.copy(
                        parentId = newParent.id,
                        orderIndex = newSiblings.size
                    )
                )
            }
        }
    }

    // SLIDE LEFT: Convert Subtask to Main Task (Outdent)
    fun outdentTask(task: TaskItem) {
        viewModelScope.launch(Dispatchers.IO) {
            if (task.parentId == null) return@launch // Already a root/main task

            val currentParent = dao.getTaskById(task.parentId)
            val newGrandParentId = currentParent?.parentId // Can be null (promoted to root)
            val newSiblings = dao.getSubtasksSnapshot(newGrandParentId)

            dao.updateTask(
                task.copy(
                    parentId = newGrandParentId,
                    orderIndex = newSiblings.size
                )
            )
        }
    }

    // Checklist operations
    fun addChecklistItem(taskId: Long, text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val items = dao.getChecklistSnapshot(taskId)
            dao.insertChecklistItem(ChecklistItem(taskId = taskId, text = text, orderIndex = items.size))
        }
    }

    fun toggleChecklistItem(item: ChecklistItem) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.updateChecklistItem(item.copy(isDone = !item.isDone))
        }
    }

    fun deleteChecklistItem(item: ChecklistItem) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteChecklistItem(item)
        }
    }

    fun moveChecklistItem(item: ChecklistItem, directionUp: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val list = dao.getChecklistSnapshot(item.taskId).toMutableList()
            val index = list.indexOfFirst { it.id == item.id }
            if (index == -1) return@launch

            val targetIndex = if (directionUp) index - 1 else index + 1
            if (targetIndex in list.indices) {
                dao.updateChecklistItem(list[index].copy(orderIndex = targetIndex))
                dao.updateChecklistItem(list[targetIndex].copy(orderIndex = index))
            }
        }
    }

    // Attachments operations
    fun addAttachment(taskId: Long, uri: Uri, name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                getApplication<Application>().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {}

            val items = dao.getAttachmentsSnapshot(taskId)
            dao.insertAttachment(
                TaskAttachment(
                    taskId = taskId,
                    uriString = uri.toString(),
                    fileName = name,
                    orderIndex = items.size
                )
            )
        }
    }

    fun deleteAttachment(attachment: TaskAttachment) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteAttachment(attachment)
        }
    }

    fun moveAttachment(attachment: TaskAttachment, directionUp: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val list = dao.getAttachmentsSnapshot(attachment.taskId).toMutableList()
            val index = list.indexOfFirst { it.id == attachment.id }
            if (index == -1) return@launch

            val targetIndex = if (directionUp) index - 1 else index + 1
            if (targetIndex in list.indices) {
                dao.updateAttachment(list[index].copy(orderIndex = targetIndex))
                dao.updateAttachment(list[targetIndex].copy(orderIndex = index))
            }
        }
    }

    private fun syncCalendarEvent(title: String, timeMs: Long): Long? {
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
                    notes = "Synced from Infinite ToDo"
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
