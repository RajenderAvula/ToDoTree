package com.example.infinitetodo

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

data class FilterCriteria(
    val priorities: Set<TaskPriority> = emptySet(),
    val statusPending: Boolean? = null,
    val mustHaveContact: Boolean = false,
    val selectedTags: Set<String> = emptySet(),
    val createdFromMs: Long? = null,
    val createdToMs: Long? = null,
    val dueFromMs: Long? = null,
    val dueToMs: Long? = null,

    // Location Filters
    val mustHaveLocation: Boolean = false,
    val selectedLocations: Set<String> = emptySet()
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
    fun getSubtaskCount(parentId: Long): Flow<Int> = dao.getSubtaskCount(parentId)
    fun getChecklist(taskId: Long): Flow<List<ChecklistItem>> = dao.getChecklistForTask(taskId)
    fun getAttachments(taskId: Long): Flow<List<RichAttachment>> = dao.getAttachmentsForTask(taskId)
    fun searchTasks(query: String): Flow<List<TaskItem>> = dao.searchTasks(query)

    suspend fun getTaskById(taskId: Long): TaskItem? = dao.getTaskById(taskId)

    fun linkTasksBidirectional(taskAId: Long, taskBId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val taskA = dao.getTaskById(taskAId) ?: return@launch
            val taskB = dao.getTaskById(taskBId) ?: return@launch

            val setA = taskA.linkedTaskIds?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toMutableSet() ?: mutableSetOf()
            setA.add(taskBId.toString())
            dao.updateTask(taskA.copy(linkedTaskIds = setA.joinToString(","), lastModifiedTimestamp = System.currentTimeMillis()))

            val setB = taskB.linkedTaskIds?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toMutableSet() ?: mutableSetOf()
            setB.add(taskAId.toString())
            dao.updateTask(taskB.copy(linkedTaskIds = setB.joinToString(","), lastModifiedTimestamp = System.currentTimeMillis()))
        }
    }

    fun unlinkTasksBidirectional(taskAId: Long, taskBId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val taskA = dao.getTaskById(taskAId) ?: return@launch
            val taskB = dao.getTaskById(taskBId) ?: return@launch

            val setA = taskA.linkedTaskIds?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toMutableSet() ?: mutableSetOf()
            setA.remove(taskBId.toString())
            dao.updateTask(taskA.copy(linkedTaskIds = setA.joinToString(","), lastModifiedTimestamp = System.currentTimeMillis()))

            val setB = taskB.linkedTaskIds?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toMutableSet() ?: mutableSetOf()
            setB.remove(taskAId.toString())
            dao.updateTask(taskB.copy(linkedTaskIds = setB.joinToString(","), lastModifiedTimestamp = System.currentTimeMillis()))
        }
    }

    suspend fun getAllUniqueTags(): List<String> {
        val all = dao.getAllTasksSnapshot()
        val set = mutableSetOf<String>()
        for (t in all) {
            t.tags?.split(",")?.forEach { tag ->
                val clean = tag.trim()
                if (clean.isNotEmpty()) set.add(clean)
            }
        }
        return set.sorted()
    }

    suspend fun getAllUniqueLocations(): List<String> {
        val all = dao.getAllTasksSnapshot()
        val set = mutableSetOf<String>()
        for (t in all) {
            t.locationName?.let { loc ->
                val clean = loc.trim()
                if (clean.isNotEmpty()) set.add(clean)
            }
        }
        return set.sorted()
    }

    suspend fun getDescendantLayersCount(taskId: Long): Int {
        var currentLevel = listOf(taskId)
        var depth = 0
        while (currentLevel.isNotEmpty()) {
            val nextLevel = mutableListOf<Long>()
            for (id in currentLevel) {
                val children = dao.getSubtasksSnapshot(id)
                nextLevel.addAll(children.map { it.id })
            }
            if (nextLevel.isNotEmpty()) {
                depth++
                currentLevel = nextLevel
            } else {
                break
            }
        }
        return depth
    }

    suspend fun getLayerLevel(taskId: Long): Int {
        var level = 1
        var curr = dao.getTaskById(taskId)
        while (curr?.parentId != null) {
            level++
            curr = dao.getTaskById(curr.parentId!!)
        }
        return level
    }

    suspend fun getHierarchyPathString(taskId: Long): String {
        val trail = mutableListOf<TaskItem>()
        var currentId: Long? = taskId
        while (currentId != null) {
            val task = dao.getTaskById(currentId) ?: break
            trail.add(0, task)
            currentId = task.parentId
        }
        return trail.joinToString(" ➔ ") { it.title.ifBlank { "Task #${it.id}" } }
    }

    suspend fun getAllPotentialParents(excludeTaskId: Long): List<TaskItem> {
        val all = dao.getAllTasksSnapshot()
        return all.filter { it.id != excludeTaskId }
    }

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
                        putExtra(Intent.EXTRA_TEXT, "Attached is your ToDoTree complete backup archive containing tasks, checklists, attachments, and audio recordings.")
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

    suspend fun buildTaskHierarchySchemaText(taskId: Long): String {
        val rootTask = dao.getTaskById(taskId) ?: return ""
        val sb = StringBuilder()
        val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

        val path = getHierarchyPathString(taskId)
        sb.append("📋 TASK EXPORT SCHEMA\n")
        sb.append("Hierarchy Path: $path\n")
        sb.append("======================================\n\n")

        suspend fun appendNode(node: TaskItem, indentLevel: Int) {
            val indent = "  ".repeat(indentLevel)
            val statusSymbol = if (node.isCompleted) "[COMPLETED ✓]" else "[PENDING]"
            sb.append("${indent}● ${node.title.ifBlank { "Untitled" }} $statusSymbol\n")
            sb.append("${indent}  - Priority: ${node.priority.name}\n")
            sb.append("${indent}  - Created: ${dateFormat.format(Date(node.createdTimestamp))}\n")

            if (!node.tags.isNullOrBlank()) {
                sb.append("${indent}  - Tags: ${node.tags}\n")
            }

            if (!node.locationName.isNullOrBlank() || (node.latitude != null && node.longitude != null)) {
                val locTitle = node.locationName ?: "Pinned Location"
                sb.append("${indent}  - 📍 Location: $locTitle\n")
                if (node.latitude != null && node.longitude != null) {
                    sb.append("${indent}    Map Link: https://maps.google.com/?q=${node.latitude},${node.longitude}\n")
                }
            }

            if (!node.notes.isNullOrBlank()) {
                sb.append("${indent}  - Notes: ${node.notes}\n")
            }

            val checklists = dao.getChecklistSnapshot(node.id)
            if (checklists.isNotEmpty()) {
                sb.append("${indent}  - Checklists:\n")
                checklists.forEach { chk ->
                    val chkBox = if (chk.isDone) "[x]" else "[ ]"
                    sb.append("${indent}    $chkBox ${chk.text}\n")
                }
            }

            val attachments = dao.getAttachmentsSnapshot(node.id)
            if (attachments.isNotEmpty()) {
                sb.append("${indent}  - Attachments/Contacts:\n")
                attachments.forEach { att ->
                    val phoneInfo = if (att.contactPhone != null) " (${att.contactPhone})" else ""
                    sb.append("${indent}    * [${att.type}] ${att.displayName}$phoneInfo\n")
                }
            }

            sb.append("\n")

            val children = dao.getSubtasksSnapshot(node.id)
            for (child in children) {
                appendNode(child, indentLevel + 1)
            }
        }

        appendNode(rootTask, 0)
        return sb.toString()
    }

    fun shareTaskData(context: Context, taskId: Long, targetPackage: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val schemaText = buildTaskHierarchySchemaText(taskId)
            val task = dao.getTaskById(taskId)
            val subject = "Task Schema: ${task?.title ?: "Export"}"

            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, schemaText)
                if (targetPackage != null) {
                    `package` = targetPackage
                }
            }

            withContext(Dispatchers.Main) {
                try {
                    if (targetPackage != null) {
                        context.startActivity(sendIntent)
                    } else {
                        val chooser = Intent.createChooser(sendIntent, "Share Task via")
                        context.startActivity(chooser)
                    }
                } catch (_: Exception) {
                    val chooser = Intent.createChooser(sendIntent, "Share Task via")
                    context.startActivity(chooser)
                }
            }
        }
    }

    suspend fun syncTaskToCalendar(task: TaskItem): Boolean {
        val hasPermission = ContextCompat.checkSelfPermission(
            getApplication(),
            android.Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return false

        return try {
            val target = CalendarHelper.getPrimaryGoogleCalendar(getApplication()) ?: return false
            val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
            val createdEpochMs = task.createdTimestamp

            val parentTask = if (task.parentId != null) dao.getTaskById(task.parentId) else null
            val hierarchyPrefix = if (parentTask != null) "[Subtask of '${parentTask.title}'] " else "[Main Task] "
            val cleanTitle = task.title.removePrefix("[DONE] ✓ ")
            val fullCalendarTitle = if (task.isCompleted) "[DONE] ✓ $hierarchyPrefix$cleanTitle" else "$hierarchyPrefix$cleanTitle"

            val attachments = dao.getAttachmentsSnapshot(task.id)
            val checklists = dao.getChecklistSnapshot(task.id)

            val notesBody = task.notes ?: ""
            val tagsSummary = if (!task.tags.isNullOrBlank()) "Tags: [${task.tags}]\n" else ""
            val locSummary = if (!task.locationName.isNullOrBlank() || (task.latitude != null && task.longitude != null)) {
                "Location: ${task.locationName ?: "Coordinates: ${task.latitude}, ${task.longitude}"}\n"
            } else ""

            val chkSummary = if (checklists.isNotEmpty()) {
                "\n\nChecklist:\n" + checklists.joinToString("\n") { (if (it.isDone) "✓ " else "○ ") + it.text }
            } else ""
            val attSummary = if (attachments.isNotEmpty()) {
                "\n\nAttachments & Contacts:\n" + attachments.joinToString("\n") { "- [${it.type}] ${it.displayName}" }
            } else ""

            val auditNote = "Created: ${dateFormat.format(Date(createdEpochMs))}\nModified: ${dateFormat.format(Date(task.lastModifiedTimestamp))}\n"
            val repeatNotice = if (task.repeatRule != RecurrenceRule.NONE) "Recurrence: ${task.repeatRule.name}\n" else ""
            val fullDescription = "$auditNote$tagsSummary$locSummary$repeatNotice Priority: ${task.priority.name}\nStatus: ${if (task.isCompleted) "Completed" else "Pending"}\n\n$notesBody$chkSummary$attSummary".trim()

            var updatedSuccessfully = false
            val currentEventAlive = task.calendarEventId != null && CalendarHelper.eventExists(getApplication(), task.calendarEventId!!)

            if (currentEventAlive) {
                updatedSuccessfully = CalendarHelper.updateEvent(
                    context = getApplication(),
                    target = target,
                    eventId = task.calendarEventId!!,
                    title = fullCalendarTitle,
                    notes = fullDescription,
                    createdTimestampMs = createdEpochMs
                )
            }

            if (!updatedSuccessfully) {
                val newEventId = CalendarHelper.insertEvent(
                    context = getApplication(),
                    target = target,
                    title = fullCalendarTitle,
                    createdTimestampMs = createdEpochMs,
                    notes = fullDescription
                )
                if (newEventId != null) {
                    dao.updateTask(task.copy(calendarEventId = newEventId))
                }
            }
            true
        } catch (e: Exception) {
            Log.e("CalendarSync", "Sync failed for task '${task.title}'", e)
            false
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

    suspend fun createInitialDraftTask(parentId: Long?): TaskItem {
        val now = System.currentTimeMillis()
        val siblings = dao.getSubtasksSnapshot(parentId)
        val draft = TaskItem(
            parentId = parentId,
            title = "",
            orderIndex = siblings.size,
            createdTimestamp = now,
            lastModifiedTimestamp = now
        )
        val id = dao.insertTask(draft)
        return draft.copy(id = id)
    }

    fun saveTask(
        task: TaskItem,
        title: String,
        notes: String?,
        tags: String?,
        priority: TaskPriority,
        createdTimestampMs: Long,
        reminderEpochMs: Long?,
        dueEpochMs: Long?,
        repeatRule: RecurrenceRule,
        repeatIntervalDays: Int,
        repeatIntervalHours: Int,
        repeatIntervalMinutes: Int,
        repeatStartDate: Long?,
        repeatStartTimeMs: Long?,
        repeatEndTimeMs: Long?,
        linkedTaskIds: String?,
        locationName: String?,
        latitude: Double?,
        longitude: Double?
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val updated = task.copy(
                title = title,
                notes = notes,
                tags = tags,
                priority = priority,
                createdTimestamp = createdTimestampMs,
                reminderTimestamp = reminderEpochMs,
                dueTimestamp = dueEpochMs,
                repeatRule = repeatRule,
                repeatIntervalDays = repeatIntervalDays,
                repeatIntervalHours = repeatIntervalHours,
                repeatIntervalMinutes = repeatIntervalMinutes,
                repeatStartDate = repeatStartDate,
                repeatStartTimeMs = repeatStartTimeMs,
                repeatEndTimeMs = repeatEndTimeMs,
                linkedTaskIds = linkedTaskIds,
                locationName = locationName,
                latitude = latitude,
                longitude = longitude,
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

    /*fun toggleTaskCompletion(task: TaskItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val updated = task.copy(
                isCompleted = !task.isCompleted,
                lastModifiedTimestamp = now
            )
            dao.updateTask(updated)
            syncTaskToCalendar(updated)
        }
    }*/
    // 1. Update toggleTaskCompletion to find and update all descendants
    fun toggleTaskCompletion(task: TaskItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val newCompletionState = !task.isCompleted
            val now = System.currentTimeMillis()
            val completedTime = if (newCompletionState) now else null

            // Fetch all nested subtasks under this task recursively
            val allDescendants = getAllDescendants(task.id)
            val allTasksToUpdate = listOf(task) + allDescendants

            for (item in allTasksToUpdate) {
                val updated = item.copy(
                    isCompleted = newCompletionState,
                    completedTimestamp = completedTime,
                    lastModifiedTimestamp = now
                )
                dao.updateTask(updated)
                syncTaskToCalendar(updated)
            }
        }
    }

    // 2. Add this helper function inside TaskViewModel
    private suspend fun getAllDescendants(parentId: Long): List<TaskItem> {
        val result = mutableListOf<TaskItem>()
        val directChildren = dao.getSubtasksSync(parentId)
        for (child in directChildren) {
            result.add(child)
            result.addAll(getAllDescendants(child.id))
        }
        return result
    }
/**
     * Recursively deletes the main task and every nested child from:
     * 1. WorkManager notification queue
     * 2. Google Calendar via CalendarContract
     * 3. Local Room Database
     */
    fun deleteTask(task: TaskItem) {
        viewModelScope.launch(Dispatchers.IO) {
            // Collect all children, grandchildren, etc.
            val allDescendants = getAllDescendants(task.id)
            val allToDelete = listOf(task) + allDescendants

            // Clean up calendar events & local notification alarms for all of them
            for (t in allToDelete) {
                workManager.cancelAllWorkByTag("TASK_${t.id}")
                t.calendarEventId?.let { calEventId ->
                    CalendarHelper.deleteEvent(getApplication(), calEventId)
                }
            }

            // Room CASCADE handles child rows in DB once parent is deleted
            dao.deleteTask(task)
        }
    }

    private suspend fun getAllDescendants(parentId: Long): List<TaskItem> {
        val result = mutableListOf<TaskItem>()
        val immediateChildren = dao.getSubtasksSync(parentId)
        for (child in immediateChildren) {
            result.add(child)
            result.addAll(getAllDescendants(child.id))
        }
        return result
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
