package com.example.infinitetodo

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

class TaskViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getDatabase(application).taskDao()
    private val workManager = WorkManager.getInstance(application)
    private val backupRestoreManager = BackupRestoreManager(application)

    val rootTasks: Flow<List<TaskItem>> = dao.getRootTasks()

    fun searchTasks(query: String): Flow<List<TaskItem>> = dao.searchTasks(query)
    fun getSubtasks(parentId: Long): Flow<List<TaskItem>> = dao.getSubtasks(parentId)
    fun getChecklist(taskId: Long): Flow<List<ChecklistItem>> = dao.getChecklistForTask(taskId)
    fun getAttachments(taskId: Long): Flow<List<TaskAttachment>> = dao.getAttachmentsForTask(taskId)

    fun addTask(
        title: String,
        notes: String? = null,
        parentId: Long? = null,
        reminderEpochMs: Long? = null,
        syncWithGoogleCalendar: Boolean = false,
        contactName: String? = null,
        contactPhone: String? = null,
        contactEmail: String? = null,
        voicePath: String? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            var googleCalendarEventId: Long? = null
            if (syncWithGoogleCalendar && reminderEpochMs != null) {
                googleCalendarEventId = syncCalendarEvent(title, reminderEpochMs, notes)
            }

            val siblings = dao.getSubtasksSnapshot(parentId)
            val newTask = TaskItem(
                parentId = parentId,
                title = title,
                notes = notes,
                reminderTimestamp = reminderEpochMs,
                calendarEventId = googleCalendarEventId,
                contactName = contactName,
                contactPhone = contactPhone,
                contactEmail = contactEmail,
                voiceRecordingPath = voicePath,
                orderIndex = siblings.size,
                createdTimestamp = now,
                lastModifiedTimestamp = now
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
        newNotes: String?,
        reminderEpochMs: Long?,
        syncWithGoogleCalendar: Boolean,
        contactName: String?,
        contactPhone: String?,
        contactEmail: String?,
        voicePath: String?
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            var calendarEventId = task.calendarEventId
            if (syncWithGoogleCalendar && reminderEpochMs != null) {
                if (calendarEventId != null) {
                    CalendarHelper.deleteEvent(getApplication(), calendarEventId)
                }
                calendarEventId = syncCalendarEvent(newTitle, reminderEpochMs, newNotes)
            }

            val updatedTask = task.copy(
                title = newTitle,
                notes = newNotes,
                reminderTimestamp = reminderEpochMs,
                calendarEventId = calendarEventId,
                contactName = contactName,
                contactPhone = contactPhone,
                contactEmail = contactEmail,
                voiceRecordingPath = voicePath ?: task.voiceRecordingPath,
                lastModifiedTimestamp = now
            )
            dao.updateTask(updatedTask)

            workManager.cancelAllWorkByTag("TASK_${task.id}")
            if (reminderEpochMs != null && reminderEpochMs > System.currentTimeMillis()) {
                scheduleReminder(task.id, newTitle, reminderEpochMs)
            }
        }
    }

    // Direct update for inline expandable text notes
    fun updateTaskNotes(task: TaskItem, newNotes: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            dao.updateTask(task.copy(notes = newNotes, lastModifiedTimestamp = now))
        }
    }

    fun toggleTaskCompletion(task: TaskItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            dao.updateTask(task.copy(isCompleted = !task.isCompleted, lastModifiedTimestamp = now))
        }
    }

    fun deleteTask(task: TaskItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val deletedAt = System.currentTimeMillis()
            Log.d("TaskAudit", "Task '${task.title}' (ID: ${task.id}) deleted at: $deletedAt")
            dao.deleteTask(task)
            workManager.cancelAllWorkByTag("TASK_${task.id}")
            task.calendarEventId?.let { CalendarHelper.deleteEvent(getApplication(), it) }
        }
    }

    fun moveTaskVertical(task: TaskItem, directionUp: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val siblings = dao.getSubtasksSnapshot(task.parentId).toMutableList()
            val currentIndex = siblings.indexOfFirst { it.id == task.id }
            if (currentIndex == -1) return@launch

            val targetIndex = if (directionUp) currentIndex - 1 else currentIndex + 1
            if (targetIndex in siblings.indices) {
                val now = System.currentTimeMillis()
                val currentTask = siblings[currentIndex]
                val swapTask = siblings[targetIndex]
                dao.updateTask(currentTask.copy(orderIndex = targetIndex, lastModifiedTimestamp = now))
                dao.updateTask(swapTask.copy(orderIndex = currentIndex, lastModifiedTimestamp = now))
            }
        }
    }

    fun indentTask(task: TaskItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val siblings = dao.getSubtasksSnapshot(task.parentId)
            val currentIndex = siblings.indexOfFirst { it.id == task.id }
            if (currentIndex > 0) {
                val newParent = siblings[currentIndex - 1]
                val newSiblings = dao.getSubtasksSnapshot(newParent.id)
                dao.updateTask(
                    task.copy(
                        parentId = newParent.id,
                        orderIndex = newSiblings.size,
                        lastModifiedTimestamp = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    fun outdentTask(task: TaskItem) {
        viewModelScope.launch(Dispatchers.IO) {
            if (task.parentId == null) return@launch

            val currentParent = dao.getTaskById(task.parentId)
            val newGrandParentId = currentParent?.parentId
            val newSiblings = dao.getSubtasksSnapshot(newGrandParentId)

            dao.updateTask(
                task.copy(
                    parentId = newGrandParentId,
                    orderIndex = newSiblings.size,
                    lastModifiedTimestamp = System.currentTimeMillis()
                )
            )
        }
    }

    fun addChecklistItem(taskId: Long, text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val items = dao.getChecklistSnapshot(taskId)
            dao.insertChecklistItem(ChecklistItem(taskId = taskId, text = text, orderIndex = items.size))
            touchTaskTimestamp(taskId)
        }
    }

    fun toggleChecklistItem(item: ChecklistItem) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.updateChecklistItem(item.copy(isDone = !item.isDone))
            touchTaskTimestamp(item.taskId)
        }
    }

    fun deleteChecklistItem(item: ChecklistItem) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteChecklistItem(item)
            touchTaskTimestamp(item.taskId)
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
                touchTaskTimestamp(item.taskId)
            }
        }
    }

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
            touchTaskTimestamp(taskId)
        }
    }

    fun deleteAttachment(attachment: TaskAttachment) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteAttachment(attachment)
            touchTaskTimestamp(attachment.taskId)
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
                touchTaskTimestamp(attachment.taskId)
            }
        }
    }

    private suspend fun touchTaskTimestamp(taskId: Long) {
        dao.getTaskById(taskId)?.let { task ->
            dao.updateTask(task.copy(lastModifiedTimestamp = System.currentTimeMillis()))
        }
    }

    fun backupToDevice(destinationStream: OutputStream, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val success = backupRestoreManager.createZipBackup(dao, destinationStream)
            onComplete(success)
        }
    }

    fun sendBackupViaMail(onReady: (Intent?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val uri = backupRestoreManager.createMailAttachmentBackup(dao)
            if (uri != null) {
                val emailIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_SUBJECT, "ToDoTree Complete Backup with Attachments")
                    putExtra(Intent.EXTRA_TEXT, "Attached is your ToDoTree complete backup containing tasks, checklists, attachments, and voice notes.")
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                onReady(emailIntent)
            } else {
                onReady(null)
            }
        }
    }

    fun restoreBackup(sourceStream: InputStream, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val success = backupRestoreManager.restoreFromZip(dao, sourceStream)
            onComplete(success)
        }
    }

    private fun syncCalendarEvent(title: String, timeMs: Long, notes: String?): Long? {
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
                    notes = notes ?: "Synced from Infinite ToDo"
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
