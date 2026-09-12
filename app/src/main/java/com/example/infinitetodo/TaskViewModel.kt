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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

data class FilterCriteria(
    val priorities: Set<TaskPriority> = emptySet(),
    val statusPending: Boolean? = null,
    val mustHaveContact: Boolean = false,
    val dateFromMs: Long? = null,
    val dateToMs: Long? = null
)

class TaskViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getDatabase(application).taskDao()
    private val workManager = WorkManager.getInstance(application)
    private val backupRestoreManager = BackupRestoreManager(application)

    val rootTasks: Flow<List<TaskItem>> = dao.getRootTasks()
    val allTasksFlow: Flow<List<TaskItem>> = dao.getAllTasksFlow()

    private val _filterState = MutableStateFlow(FilterCriteria())
    val filterState: StateFlow<FilterCriteria> = _filterState.asStateFlow()

    fun updateFilter(criteria: FilterCriteria) {
        _filterState.value = criteria
    }

    fun getSubtasks(parentId: Long): Flow<List<TaskItem>> = dao.getSubtasks(parentId)
    fun getChecklist(taskId: Long): Flow<List<ChecklistItem>> = dao.getChecklistForTask(taskId)
    fun getAttachments(taskId: Long): Flow<List<RichAttachment>> = dao.getAttachmentsForTask(taskId)
    fun searchTasks(query: String): Flow<List<TaskItem>> = dao.searchTasks(query)

    suspend fun getAllPotentialParents(excludeTaskId: Long): List<TaskItem> {
        val all = dao.getAllTasksSnapshot()
        return all.filter { it.id != excludeTaskId }
    }

    // Backup and restore implementations
    fun backupToDevice(destinationStream: OutputStream, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val success = backupRestoreManager.createZipBackup(dao, destinationStream)
            withContext(Dispatchers.Main) { onComplete(success) }
        }
    }

    fun sendBackupViaMail(onReady: (Intent?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val uri = backupRestoreManager.createMailAttachmentBackup(dao)
            withContext(Dispatchers.Main) {
                if (uri != null) {
                    val emailIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_SUBJECT, "ToDoTree Complete Backup with Attachments")
                        putExtra(Intent.EXTRA_TEXT, "Attached is your complete backup archive containing tasks, checklists, attachments, and audio recordings.")
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    onReady(emailIntent)
                } else {
                    onReady(null)
                }
            }
        }
    }

    fun restoreBackup(sourceStream: InputStream, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val success = backupRestoreManager.restoreFromZip(dao, sourceStream)
            withContext(Dispatchers.Main) { onComplete(success) }
        }
    }

    suspend fun syncTaskToCalendar(task: TaskItem): Boolean {
        val hasPermission = ContextCompat.checkSelfPermission(
            getApplication(),
            android.Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return false

        return try {
            val isAllDay = task.dueTimestamp == null && task.reminderTimestamp == null
            val effectiveTimeMs = task.dueTimestamp ?: task.reminderTimestamp ?: task.createdTimestamp

            val parentTask = if (task.parentId != null) dao.getTaskById(task.parentId) else null
            val hierarchyPrefix = if (parentTask != null) "[Subtask of '${parentTask.title}'] " else "[Main Task] "
            val cleanTitle = task.title.removePrefix("[DONE] ✓ ")
            val fullCalendarTitle = if (task.isCompleted) "[DONE] ✓ $hierarchyPrefix$cleanTitle" else "$hierarchyPrefix$cleanTitle"

            val attachments = dao.getAttachmentsSnapshot(task.id)
            val checklists = dao.getChecklistSnapshot(task.id)

            val notesBody = task.notes ?: ""
            val chkSummary = if (checklists.isNotEmpty()) {
                "\n\nChecklist:\n" + checklists.joinToString("\n") { (if (it.isDone) "✓ " else "○ ") + it.text }
            } else ""
            val attSummary = if (attachments.isNotEmpty()) {
                "\n\nAttachments & Contacts:\n" + attachments.joinToString("\n") { "- [${it.type}] ${it.displayName}" }
            } else ""

            val dateTypeNotice = if (isAllDay) "Schedule: All-Day Item (No specific time set)\n" else ""
            val fullDescription = "$dateTypeNotice Priority: ${task.priority.name}\nStatus: ${if (task.isCompleted) "Completed" else "Pending"}\n\n$notesBody$chkSummary$attSummary".trim()

            if (task.calendarEventId != null) {
                CalendarHelper.updateEvent(
                    context = getApplication(),
                    eventId = task.calendarEventId,
                    title = fullCalendarTitle,
                    notes = fullDescription,
                    startTimeMs = effectiveTimeMs,
                    isAllDay = isAllDay
                )
            } else {
                val calId = CalendarHelper.getPrimaryGoogleCalendarId(getApplication())
                if (calId != null) {
                    val newEventId = CalendarHelper.insertEvent(
                        context = getApplication(),
                        calendarId = calId,
                        title = fullCalendarTitle,
                        startTimeMs = effectiveTimeMs,
                        notes = fullDescription,
                        isAllDay = isAllDay
                    )
                    if (newEventId != null) {
                        dao.updateTask(task.copy(calendarEventId = newEventId))
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e("CalendarSync", "Sync failed for task '${task.title}'", e)
            false
        }
    }

    fun manualSyncTaskToCalendar(taskId: Long, onResult: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val task = dao.getTaskById(taskId)
            if (task == null) {
                withContext(Dispatchers.Main) { onResult("Task not found") }
                return@launch
            }
            val ok = syncTaskToCalendar(task)
            withContext(Dispatchers.Main) {
                onResult(if (ok) "Synced '${task.title}' to Calendar ✓" else "Calendar permission missing or sync failed")
            }
        }
    }

    fun syncAllTasksToCalendar(onDone: (Int) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val all = dao.getAllTasksSnapshot()
            var count = 0
            for (t in all) {
                if (syncTaskToCalendar(t)) count++
            }
            withContext(Dispatchers.Main) { onDone(count) }
        }
    }

    fun addTask(
        title: String,
        notes: String? = null,
        parentId: Long? = null,
        priority: TaskPriority = TaskPriority.MEDIUM,
        reminderEpochMs: Long? = null,
        dueEpochMs: Long? = null,
        repeatRule: RecurrenceRule = RecurrenceRule.NONE,
        linkedTaskIds: String? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val siblings = dao.getSubtasksSnapshot(parentId)
            val newTask = TaskItem(
                parentId = parentId,
                title = title,
                notes = notes,
                priority = priority,
                reminderTimestamp = reminderEpochMs,
                dueTimestamp = dueEpochMs,
                repeatRule = repeatRule,
                linkedTaskIds = linkedTaskIds,
                orderIndex = siblings.size,
                createdTimestamp = now,
                lastModifiedTimestamp = now
            )
            val id = dao.insertTask(newTask)
            val createdTask = newTask.copy(id = id)

            syncTaskToCalendar(createdTask)

            if (reminderEpochMs != null && reminderEpochMs > System.currentTimeMillis()) {
                scheduleReminder(id, title, reminderEpochMs)
            }
        }
    }

    fun updateTask(
        task: TaskItem,
        title: String,
        notes: String?,
        priority: TaskPriority,
        reminderEpochMs: Long?,
        dueEpochMs: Long?,
        repeatRule: RecurrenceRule,
        linkedTaskIds: String?
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val updated = task.copy(
                title = title,
                notes = notes,
                priority = priority,
                reminderTimestamp = reminderEpochMs,
                dueTimestamp = dueEpochMs,
                repeatRule = repeatRule,
                linkedTaskIds = linkedTaskIds,
                lastModifiedTimestamp = now
            )
            dao.updateTask(updated)
            syncTaskToCalendar(updated)

            workManager.cancelAllWorkByTag("TASK_${task.id}")
            if (reminderEpochMs != null && reminderEpochMs > System.currentTimeMillis()) {
                scheduleReminder(task.id, title, reminderEpochMs)
            }
        }
    }

    fun toggleTaskCompletion(task: TaskItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val updated = task.copy(
                isCompleted = !task.isCompleted,
                lastModifiedTimestamp = now
            )
            dao.updateTask(updated)
            syncTaskToCalendar(updated)
        }
    }

    fun deleteTask(task: TaskItem) {
        viewModelScope.launch(Dispatchers.IO) {
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
                val updated = task.copy(
                    parentId = newParent.id,
                    orderIndex = newSiblings.size,
                    lastModifiedTimestamp = System.currentTimeMillis()
                )
                dao.updateTask(updated)
                syncTaskToCalendar(updated)
            }
        }
    }

    fun outdentTask(task: TaskItem) {
        viewModelScope.launch(Dispatchers.IO) {
            if (task.parentId == null) return@launch
            val currentParent = dao.getTaskById(task.parentId)
            val newGrandParentId = currentParent?.parentId
            val newSiblings = dao.getSubtasksSnapshot(newGrandParentId)
            val updated = task.copy(
                parentId = newGrandParentId,
                orderIndex = newSiblings.size,
                lastModifiedTimestamp = System.currentTimeMillis()
            )
            dao.updateTask(updated)
            syncTaskToCalendar(updated)
        }
    }

    fun moveTaskToTarget(task: TaskItem, newParentId: Long?) {
        viewModelScope.launch(Dispatchers.IO) {
            val siblings = dao.getSubtasksSnapshot(newParentId)
            val updated = task.copy(
                parentId = newParentId,
                orderIndex = siblings.size,
                lastModifiedTimestamp = System.currentTimeMillis()
            )
            dao.updateTask(updated)
            syncTaskToCalendar(updated)
        }
    }

    fun copyTaskToTarget(taskId: Long, targetParentId: Long?) {
        viewModelScope.launch(Dispatchers.IO) {
            val original = dao.getTaskById(taskId) ?: return@launch
            deepCopyRecursive(original, targetParentId)
        }
    }

    private suspend fun deepCopyRecursive(task: TaskItem, newParentId: Long?) {
        val siblings = dao.getSubtasksSnapshot(newParentId)
        val copyTitle = "${task.title} (Copy)"
        val copy = task.copy(
            id = 0L,
            parentId = newParentId,
            title = copyTitle,
            calendarEventId = null,
            orderIndex = siblings.size,
            createdTimestamp = System.currentTimeMillis(),
            lastModifiedTimestamp = System.currentTimeMillis()
        )
        val newId = dao.insertTask(copy)
        syncTaskToCalendar(copy.copy(id = newId))

        val checklists = dao.getChecklistSnapshot(task.id)
        for (item in checklists) {
            dao.insertChecklistItem(item.copy(id = 0L, taskId = newId))
        }

        val attachments = dao.getAttachmentsSnapshot(task.id)
        for (att in attachments) {
            dao.insertAttachment(att.copy(id = 0L, taskId = newId))
        }

        val children = dao.getSubtasksSnapshot(task.id)
        for (child in children) {
            deepCopyRecursive(child, newId)
        }
    }

    fun addChecklistItem(taskId: Long, text: String, notes: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val items = dao.getChecklistSnapshot(taskId)
            val now = System.currentTimeMillis()
            dao.insertChecklistItem(
                ChecklistItem(
                    taskId = taskId,
                    text = text,
                    notes = notes,
                    orderIndex = items.size,
                    createdTimestamp = now,
                    lastModifiedTimestamp = now
                )
            )
            touchTask(taskId)
        }
    }

    fun updateChecklistItem(item: ChecklistItem, text: String, notes: String?, isDone: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            dao.updateChecklistItem(
                item.copy(
                    text = text,
                    notes = notes,
                    isDone = isDone,
                    lastModifiedTimestamp = now
                )
            )
            touchTask(item.taskId)
        }
    }

    fun toggleChecklistItem(item: ChecklistItem) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.updateChecklistItem(
                item.copy(
                    isDone = !item.isDone,
                    lastModifiedTimestamp = System.currentTimeMillis()
                )
            )
            touchTask(item.taskId)
        }
    }

    fun deleteChecklistItem(item: ChecklistItem) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteChecklistItem(item)
            touchTask(item.taskId)
        }
    }

    fun moveChecklistItem(item: ChecklistItem, directionUp: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val list = dao.getChecklistSnapshot(item.taskId).toMutableList()
            val index = list.indexOfFirst { it.id == item.id }
            if (index == -1) return@launch
            val targetIndex = if (directionUp) index - 1 else index + 1
            if (targetIndex in list.indices) {
                val now = System.currentTimeMillis()
                dao.updateChecklistItem(list[index].copy(orderIndex = targetIndex, lastModifiedTimestamp = now))
                dao.updateChecklistItem(list[targetIndex].copy(orderIndex = index, lastModifiedTimestamp = now))
                touchTask(item.taskId)
            }
        }
    }

    fun addAttachment(
        taskId: Long,
        type: AttachmentType,
        uriString: String,
        displayName: String,
        notes: String? = null,
        contactPhone: String? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val items = dao.getAttachmentsSnapshot(taskId)
            val now = System.currentTimeMillis()
            dao.insertAttachment(
                RichAttachment(
                    taskId = taskId,
                    type = type,
                    uriString = uriString,
                    displayName = displayName,
                    notes = notes,
                    contactPhone = contactPhone,
                    orderIndex = items.size,
                    createdTimestamp = now,
                    lastModifiedTimestamp = now
                )
            )
            touchTask(taskId)
        }
    }

    fun updateAttachment(
        attachment: RichAttachment,
        displayName: String,
        notes: String?,
        contactPhone: String?,
        isContactPending: Boolean
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            dao.updateAttachment(
                attachment.copy(
                    displayName = displayName,
                    notes = notes,
                    contactPhone = contactPhone,
                    isContactPending = isContactPending,
                    lastModifiedTimestamp = now
                )
            )
            touchTask(attachment.taskId)
        }
    }

    fun deleteAttachment(attachment: RichAttachment) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteAttachment(attachment)
            touchTask(attachment.taskId)
        }
    }

    fun moveAttachment(attachment: RichAttachment, directionUp: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val list = dao.getAttachmentsSnapshot(attachment.taskId).toMutableList()
            val index = list.indexOfFirst { it.id == attachment.id }
            if (index == -1) return@launch
            val targetIndex = if (directionUp) index - 1 else index + 1
            if (targetIndex in list.indices) {
                val now = System.currentTimeMillis()
                dao.updateAttachment(list[index].copy(orderIndex = targetIndex, lastModifiedTimestamp = now))
                dao.updateAttachment(list[targetIndex].copy(orderIndex = index, lastModifiedTimestamp = now))
                touchTask(attachment.taskId)
            }
        }
    }

    private suspend fun touchTask(taskId: Long) {
        dao.getTaskById(taskId)?.let { task ->
            val updated = task.copy(lastModifiedTimestamp = System.currentTimeMillis())
            dao.updateTask(updated)
            syncTaskToCalendar(updated)
        }
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
