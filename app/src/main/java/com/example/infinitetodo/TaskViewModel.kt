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

    // Formats and updates Calendar event title, description, start/end time, AND attachments
    private suspend fun syncTaskToCalendar(task: TaskItem) {
        val eventId = task.calendarEventId ?: return

        val parentTask = if (task.parentId != null) dao.getTaskById(task.parentId) else null

        val hierarchyPrefix = if (parentTask != null) {
            "[Subtask of '${parentTask.title}'] "
        } else {
            "[Main Task] "
        }

        val baseCleanTitle = task.title
            .removePrefix("[DONE] ✓ ")
            .replace(Regex("^\\[(Main Task|Subtask of '[^']+')\\]\\s*"), "")

        val fullCalendarTitle = if (task.isCompleted) {
            "[DONE] ✓ $hierarchyPrefix$baseCleanTitle"
        } else {
            "$hierarchyPrefix$baseCleanTitle"
        }

        val hierarchyLine = if (parentTask != null) {
            "Hierarchy: Subtask under '${parentTask.title}'"
        } else {
            "Hierarchy: Top-level Main Task"
        }
        val statusLine = "Status: ${if (task.isCompleted) "Completed ✓" else "Pending"}"
        val userNotes = task.notes ?: ""

        // Gather latest ordered attachments
        val attachments = dao.getAttachmentsSnapshot(task.id)
        val attachmentSummary = if (attachments.isNotEmpty()) {
            "\n\nAttachments (${attachments.size}):\n" + attachments.mapIndexed { idx, att ->
                "${idx + 1}. ${att.fileName}"
            }.joinToString("\n")
        } else {
            "\n\nAttachments: None"
        }

        val fullDescription = "$statusLine\n$hierarchyLine\n\nNotes:\n$userNotes$attachmentSummary".trim()

        CalendarHelper.updateEvent(
            context = getApplication(),
            eventId = eventId,
            title = fullCalendarTitle,
            notes = fullDescription,
            startTimeMs = task.reminderTimestamp
        )
    }

    // Manual single-task sync button trigger
    fun manualSyncTaskToCalendar(taskId: Long, onDone: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val task = dao.getTaskById(taskId)
            if (task == null) {
                onDone(false)
                return@launch
            }

            if (task.calendarEventId != null) {
                syncTaskToCalendar(task)
                onDone(true)
            } else if (task.reminderTimestamp != null) {
                // If never synced before but has a reminder date/time, register an event now
                val parentTask = if (task.parentId != null) dao.getTaskById(task.parentId) else null
                val prefix = if (parentTask != null) "[Subtask of '${parentTask.title}'] " else "[Main Task] "
                val calTitle = if (task.isCompleted) "[DONE] ✓ $prefix${task.title}" else "$prefix${task.title}"

                val attachments = dao.getAttachmentsSnapshot(task.id)
                val attSummary = if (attachments.isNotEmpty()) {
                    "\n\nAttachments:\n" + attachments.mapIndexed { i, a -> "${i + 1}. ${a.fileName}" }.joinToString("\n")
                } else ""
                val calDesc = "Status: ${if (task.isCompleted) "Completed ✓" else "Pending"}\nNotes:\n${task.notes ?: ""}$attSummary".trim()

                val newCalId = syncCalendarEvent(calTitle, task.reminderTimestamp, calDesc)
                if (newCalId != null) {
                    val updated = task.copy(calendarEventId = newCalId)
                    dao.updateTask(updated)
                    syncTaskToCalendar(updated)
                    onDone(true)
                } else {
                    onDone(false)
                }
            } else {
                onDone(false)
            }
        }
    }

    // Bulk sync button trigger for all tasks
    fun syncAllTasksToCalendar(onDone: (Int) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val allTasks = dao.getAllTasksSnapshot()
            var count = 0
            for (task in allTasks) {
                if (task.calendarEventId != null) {
                    syncTaskToCalendar(task)
                    count++
                } else if (task.reminderTimestamp != null) {
                    val newCalId = syncCalendarEvent(task.title, task.reminderTimestamp, task.notes)
                    if (newCalId != null) {
                        val updated = task.copy(calendarEventId = newCalId)
                        dao.updateTask(updated)
                        syncTaskToCalendar(updated)
                        count++
                    }
                }
            }
            onDone(count)
        }
    }

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
            val siblings = dao.getSubtasksSnapshot(parentId)
            val parentTask = if (parentId != null) dao.getTaskById(parentId) else null

            var googleCalendarEventId: Long? = null
            if (syncWithGoogleCalendar && reminderEpochMs != null) {
                val hierarchyPrefix = if (parentTask != null) "[Subtask of '${parentTask.title}'] " else "[Main Task] "
                val calTitle = "$hierarchyPrefix$title"
                val hierarchyDesc = if (parentTask != null) "Hierarchy: Subtask under '${parentTask.title}'" else "Hierarchy: Top-level Main Task"
                val calDesc = "Status: Pending\n$hierarchyDesc\n\nNotes:\n${notes ?: ""}".trim()

                googleCalendarEventId = syncCalendarEvent(calTitle, reminderEpochMs, calDesc)
            }

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
                    val parentTask = if (task.parentId != null) dao.getTaskById(task.parentId) else null
                    val prefix = if (parentTask != null) "[Subtask of '${parentTask.title}'] " else "[Main Task] "
                    val calTitle = if (task.isCompleted) "[DONE] ✓ $prefix$newTitle" else "$prefix$newTitle"
                    val hierarchyDesc = if (parentTask != null) "Hierarchy: Subtask under '${parentTask.title}'" else "Hierarchy: Top-level Main Task"
                    val calDesc = "Status: ${if (task.isCompleted) "Completed ✓" else "Pending"}\n$hierarchyDesc\n\nNotes:\n${newNotes ?: ""}".trim()

                    CalendarHelper.updateEvent(
                        context = getApplication(),
                        eventId = calendarEventId,
                        title = calTitle,
                        notes = calDesc,
                        startTimeMs = reminderEpochMs
                    )
                } else {
                    val parentTask = if (task.parentId != null) dao.getTaskById(task.parentId) else null
                    val prefix = if (parentTask != null) "[Subtask of '${parentTask.title}'] " else "[Main Task] "
                    val calTitle = if (task.isCompleted) "[DONE] ✓ $prefix$newTitle" else "$prefix$newTitle"
                    val hierarchyDesc = if (parentTask != null) "Hierarchy: Subtask under '${parentTask.title}'" else "Hierarchy: Top-level Main Task"
                    val calDesc = "Status: ${if (task.isCompleted) "Completed ✓" else "Pending"}\n$hierarchyDesc\n\nNotes:\n${newNotes ?: ""}".trim()

                    calendarEventId = syncCalendarEvent(calTitle, reminderEpochMs, calDesc)
                }
            } else if (!syncWithGoogleCalendar && calendarEventId != null) {
                CalendarHelper.deleteEvent(getApplication(), calendarEventId)
                calendarEventId = null
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
            syncTaskToCalendar(updatedTask)

            workManager.cancelAllWorkByTag("TASK_${task.id}")
            if (reminderEpochMs != null && reminderEpochMs > System.currentTimeMillis()) {
                scheduleReminder(task.id, newTitle, reminderEpochMs)
            }
        }
    }

    fun updateTaskReminder(task: TaskItem, newTimestampMs: Long?) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val updatedTask = task.copy(
                reminderTimestamp = newTimestampMs,
                lastModifiedTimestamp = now
            )
            dao.updateTask(updatedTask)
            syncTaskToCalendar(updatedTask)

            workManager.cancelAllWorkByTag("TASK_${task.id}")
            if (newTimestampMs != null && newTimestampMs > System.currentTimeMillis()) {
                scheduleReminder(task.id, task.title, newTimestampMs)
            }
        }
    }

    fun toggleTaskCompletion(task: TaskItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val newCompletionState = !task.isCompleted
            val now = System.currentTimeMillis()
            val updated = task.copy(
                isCompleted = newCompletionState,
                lastModifiedTimestamp = now
            )
            dao.updateTask(updated)
            syncTaskToCalendar(updated)
        }
    }

    fun indentTask(task: TaskItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val siblings = dao.getSubtasksSnapshot(task.parentId)
            val currentIndex = siblings.indexOfFirst { it.id == task.id }
            if (currentIndex > 0) {
                val newParent = siblings[currentIndex - 1]
                val newSiblings = dao.getSubtasksSnapshot(newParent.id)
                val updatedTask = task.copy(
                    parentId = newParent.id,
                    orderIndex = newSiblings.size,
                    lastModifiedTimestamp = System.currentTimeMillis()
                )
                dao.updateTask(updatedTask)
                syncTaskToCalendar(updatedTask)
            }
        }
    }

    fun outdentTask(task: TaskItem) {
        viewModelScope.launch(Dispatchers.IO) {
            if (task.parentId == null) return@launch

            val currentParent = dao.getTaskById(task.parentId)
            val newGrandParentId = currentParent?.parentId
            val newSiblings = dao.getSubtasksSnapshot(newGrandParentId)

            val updatedTask = task.copy(
                parentId = newGrandParentId,
                orderIndex = newSiblings.size,
                lastModifiedTimestamp = System.currentTimeMillis()
            )
            dao.updateTask(updatedTask)
            syncTaskToCalendar(updatedTask)
        }
    }

    fun updateTaskNotes(task: TaskItem, newNotes: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val updated = task.copy(notes = newNotes, lastModifiedTimestamp = now)
            dao.updateTask(updated)
            syncTaskToCalendar(updated)
        }
    }

    fun duplicateTask(taskId: Long, targetParentId: Long?) {
        viewModelScope.launch(Dispatchers.IO) {
            val original = dao.getTaskById(taskId) ?: return@launch
            deepCopyRecursive(original, targetParentId)
        }
    }

    private suspend fun deepCopyRecursive(task: TaskItem, newParentId: Long?) {
        val siblings = dao.getSubtasksSnapshot(newParentId)
        val copyTitle = "${task.title} (Copy)"
        val parentTask = if (newParentId != null) dao.getTaskById(newParentId) else null

        var newCalendarEventId: Long? = null
        if (task.calendarEventId != null && task.reminderTimestamp != null) {
            val prefix = if (parentTask != null) "[Subtask of '${parentTask.title}'] " else "[Main Task] "
            val calTitle = if (task.isCompleted) "[DONE] ✓ $prefix$copyTitle" else "$prefix$copyTitle"
            val hierarchyDesc = if (parentTask != null) "Hierarchy: Subtask under '${parentTask.title}'" else "Hierarchy: Top-level Main Task"
            val calDesc = "Status: ${if (task.isCompleted) "Completed ✓" else "Pending"}\n$hierarchyDesc\n\nNotes:\n${task.notes ?: ""}".trim()

            newCalendarEventId = syncCalendarEvent(calTitle, task.reminderTimestamp, calDesc)
        }

        val copy = task.copy(
            id = 0L,
            parentId = newParentId,
            title = copyTitle,
            calendarEventId = newCalendarEventId,
            orderIndex = siblings.size,
            createdTimestamp = System.currentTimeMillis(),
            lastModifiedTimestamp = System.currentTimeMillis()
        )
        val newGeneratedId = dao.insertTask(copy)

        if (task.reminderTimestamp != null && task.reminderTimestamp > System.currentTimeMillis()) {
            scheduleReminder(newGeneratedId, copyTitle, task.reminderTimestamp)
        }

        val checklists = dao.getChecklistSnapshot(task.id)
        for (item in checklists) {
            dao.insertChecklistItem(item.copy(id = 0L, taskId = newGeneratedId))
        }

        val attachments = dao.getAttachmentsSnapshot(task.id)
        for (att in attachments) {
            dao.insertAttachment(att.copy(id = 0L, taskId = newGeneratedId))
        }

        val children = dao.getSubtasksSnapshot(task.id)
        for (child in children) {
            deepCopyRecursive(child, newGeneratedId)
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

    // ATTACHMENT MUTATIONS: Updates Room & pushes new attachment list directly to Google Calendar
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
            dao.getTaskById(taskId)?.let { syncTaskToCalendar(it) }
        }
    }

    fun deleteAttachment(attachment: TaskAttachment) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteAttachment(attachment)
            touchTaskTimestamp(attachment.taskId)
            dao.getTaskById(attachment.taskId)?.let { syncTaskToCalendar(it) }
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
                dao.getTaskById(attachment.taskId)?.let { syncTaskToCalendar(it) }
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
