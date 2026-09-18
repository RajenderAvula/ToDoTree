package com.example.infinitetodo

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.widget.Toast
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
    val mustHaveLocation: Boolean = false,
    val selectedLocations: Set<String> = emptySet(),
    val selectedTags: Set<String> = emptySet(),
    val createdFromMs: Long? = null,
    val createdToMs: Long? = null,
    val dueFromMs: Long? = null,
    val dueToMs: Long? = null
)

data class DescendantNode(
    val task: TaskItem,
    val depthLevel: Int
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

    fun resetFilters() {
        _filterState.value = FilterCriteria()
    }

    fun getSubtasks(parentId: Long): Flow<List<TaskItem>> = dao.getSubtasks(parentId)
    fun getSubtaskCount(parentId: Long): Flow<Int> = dao.getSubtaskCount(parentId)
    fun getChecklist(taskId: Long): Flow<List<ChecklistItem>> = dao.getChecklistForTask(taskId)
    fun getAttachments(taskId: Long): Flow<List<RichAttachment>> = dao.getAttachmentsForTask(taskId)
    fun searchTasks(query: String): Flow<List<TaskItem>> = dao.searchTasks(query)

    suspend fun getTaskById(taskId: Long): TaskItem? = dao.getTaskById(taskId)

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
        return all.mapNotNull { it.locationName?.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
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
        val trail = getBreadcrumbTrail(taskId)
        return trail.joinToString(" ➔ ") { it.title.ifBlank { "Task #${it.id}" } }
    }

    suspend fun getBreadcrumbTrail(leafTaskId: Long?): List<TaskItem> {
        if (leafTaskId == null) return emptyList()
        val path = mutableListOf<TaskItem>()
        var currentId: Long? = leafTaskId
        while (currentId != null) {
            val task = dao.getTaskById(currentId) ?: break
            path.add(0, task)
            currentId = task.parentId
        }
        return path
    }

    suspend fun getAllPotentialParents(excludeTaskId: Long, isCopy: Boolean): List<TaskItem> {
        val all = dao.getAllTasksSnapshot()
        if (isCopy) {
            return all.filter { it.id != excludeTaskId }
        }
        val invalidIds = mutableSetOf(excludeTaskId)
        fun collectDescendants(parentId: Long) {
            val children = all.filter { it.parentId == parentId }
            for (c in children) {
                invalidIds.add(c.id)
                collectDescendants(c.id)
            }
        }
        collectDescendants(excludeTaskId)
        return all.filter { it.id !in invalidIds }
    }

    suspend fun getAllDescendantsTree(rootTaskId: Long): List<DescendantNode> {
        val result = mutableListOf<DescendantNode>()
        suspend fun traverse(parentId: Long, depth: Int) {
            val children = dao.getSubtasksSnapshot(parentId)
            for (child in children) {
                result.add(DescendantNode(child, depth))
                traverse(child.id, depth + 1)
            }
        }
        traverse(rootTaskId, 1)
        return result
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

    suspend fun syncTaskToCalendar(task: TaskItem): Boolean {
        val hasPermission = ContextCompat.checkSelfPermission(
            getApplication(),
            android.Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return false

        return try {
            val target = CalendarHelper.getPrimaryGoogleCalendar(getApplication()) ?: return false
            val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
            val syncCreatedEpochMs = task.createdTimestamp

            val parentTask = if (task.parentId != null) dao.getTaskById(task.parentId) else null
            val hierarchyPrefix = if (parentTask != null) "[Subtask of '${parentTask.title}'] " else "[Main Task] "
            val cleanTitle = task.title.removePrefix("[DONE] ✓ ")
            val fullCalendarTitle = if (task.isCompleted) "[DONE] ✓ $hierarchyPrefix$cleanTitle" else "$hierarchyPrefix$cleanTitle"

            val attachments = dao.getAttachmentsSnapshot(task.id)
            val checklists = dao.getChecklistSnapshot(task.id)

            val notesBody = task.notes ?: ""
            val tagsSummary = if (!task.tags.isNullOrBlank()) "Tags: [${task.tags}]\n" else ""
            val locSummary = if (!task.locationName.isNullOrBlank()) "Location: ${task.locationName}\n" else ""
            val chkSummary = if (checklists.isNotEmpty()) {
                "\n\nChecklist:\n" + checklists.joinToString("\n") { (if (it.isDone) "✓ " else "○ ") + it.text }
            } else ""
            val attSummary = if (attachments.isNotEmpty()) {
                "\n\nAttachments & Contacts:\n" + attachments.joinToString("\n") { "- [${it.type}] ${it.displayName}" }
            } else ""

            val auditNote = "Created Stamp: ${dateFormat.format(Date(syncCreatedEpochMs))}\nModified: ${dateFormat.format(Date(task.lastModifiedTimestamp))}\n"
            val repeatNotice = if (task.repeatRule != RecurrenceRule.NONE) "Recurrence: ${task.repeatRule.name}\n" else ""
            val fullDescription = "$auditNote$locSummary$tagsSummary$repeatNotice Priority: ${task.priority.name}\nStatus: ${if (task.isCompleted) "Completed" else "Pending"}\n\n$notesBody$chkSummary$attSummary".trim()

            if (task.calendarEventId != null) {
                CalendarHelper.updateEvent(
                    context = getApplication(),
                    target = target,
                    eventId = task.calendarEventId,
                    title = fullCalendarTitle,
                    notes = fullDescription,
                    createdTimestampMs = syncCreatedEpochMs
                )
            } else {
                val newEventId = CalendarHelper.insertEvent(
                    context = getApplication(),
                    target = target,
                    title = fullCalendarTitle,
                    createdTimestampMs = syncCreatedEpochMs,
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

    fun toggleTaskCompletion(task: TaskItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val newCompleted = !task.isCompleted
            val updated = task.copy(
                isCompleted = newCompleted,
                completedTimestamp = if (newCompleted) now else null,
                lastModifiedTimestamp = now
            )
            dao.updateTask(updated)
            syncTaskToCalendar(updated)
        }
    }

    fun deleteTask(task: TaskItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val allDescendants = getAllDescendants(task.id)
            val allTasksToDelete = listOf(task) + allDescendants

            for (t in allTasksToDelete) {
                workManager.cancelAllWorkByTag("TASK_${t.id}")
                t.calendarEventId?.let { calEventId ->
                    CalendarHelper.deleteEvent(getApplication(), calEventId)
                }
            }
            dao.deleteTask(task)
        }
    }

    // MULTI-TASK BATCH DELETION
    fun deleteTasksBatch(taskIds: Set<Long>) {
        viewModelScope.launch(Dispatchers.IO) {
            if (taskIds.isEmpty()) return@launch
            val allSnapshot = dao.getAllTasksSnapshot()
            val targets = allSnapshot.filter { it.id in taskIds }

            for (task in targets) {
                val descendants = getAllDescendants(task.id)
                val allToDelete = listOf(task) + descendants
                for (t in allToDelete) {
                    workManager.cancelAllWorkByTag("TASK_${t.id}")
                    t.calendarEventId?.let { calId ->
                        CalendarHelper.deleteEvent(getApplication(), calId)
                    }
                    dao.deleteTask(t)
                }
            }
        }
    }

    private suspend fun getAllDescendants(parentId: Long): List<TaskItem> {
        val result = mutableListOf<TaskItem>()
        val immediateChildren = dao.getSubtasksSnapshot(parentId)
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

    fun executeAdvancedMove(
        task: TaskItem,
        targetParentIds: Set<Long?>,
        moveAllSubtasks: Boolean,
        selectedSubtaskIds: Set<Long>
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            if (targetParentIds.isEmpty()) return@launch

            val targetList = targetParentIds.toList()
            val primaryTarget = targetList.first()

            val now = System.currentTimeMillis()
            val siblings = dao.getSubtasksSnapshot(primaryTarget)
            val updated = task.copy(
                parentId = primaryTarget,
                orderIndex = siblings.size,
                lastModifiedTimestamp = now
            )
            dao.updateTask(updated)
            syncTaskToCalendar(updated)

            if (!moveAllSubtasks) {
                val allDescendants = getAllDescendantsTree(task.id)
                for (node in allDescendants) {
                    if (node.task.id !in selectedSubtaskIds) {
                        dao.updateTask(node.task.copy(parentId = task.parentId, lastModifiedTimestamp = now))
                    }
                }
            }

            for (i in 1 until targetList.size) {
                val extraTarget = targetList[i]
                deepCopySelectiveRecursive(
                    task = task,
                    newParentId = extraTarget,
                    copyAllSubtasks = moveAllSubtasks,
                    selectedSubtaskIds = selectedSubtaskIds,
                    copyIndex = 1
                )
            }
        }
    }

    fun executeAdvancedCopy(
        task: TaskItem,
        targetParentIds: Set<Long?>,
        numberOfCopies: Int,
        copyAllSubtasks: Boolean,
        selectedSubtaskIds: Set<Long>
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val count = numberOfCopies.coerceAtLeast(1)
            for (targetParentId in targetParentIds) {
                for (copyNum in 1..count) {
                    deepCopySelectiveRecursive(
                        task = task,
                        newParentId = targetParentId,
                        copyAllSubtasks = copyAllSubtasks,
                        selectedSubtaskIds = selectedSubtaskIds,
                        copyIndex = copyNum
                    )
                }
            }
        }
    }

    private suspend fun deepCopySelectiveRecursive(
        task: TaskItem,
        newParentId: Long?,
        copyAllSubtasks: Boolean,
        selectedSubtaskIds: Set<Long>,
        copyIndex: Int
    ) {
        val siblings = dao.getSubtasksSnapshot(newParentId)
        val copyTitle = if (copyIndex > 1) "${task.title} (Copy $copyIndex)" else "${task.title} (Copy)"
        val now = System.currentTimeMillis()

        val copy = task.copy(
            id = 0L,
            parentId = newParentId,
            title = copyTitle,
            calendarEventId = null,
            orderIndex = siblings.size,
            createdTimestamp = now,
            lastModifiedTimestamp = now
        )
        val newId = dao.insertTask(copy)
        syncTaskToCalendar(copy.copy(id = newId))

        val checklists = dao.getChecklistSnapshot(task.id)
        for (item in checklists) {
            dao.insertChecklistItem(item.copy(id = 0L, taskId = newId, createdTimestamp = now, lastModifiedTimestamp = now))
        }

        val attachments = dao.getAttachmentsSnapshot(task.id)
        for (att in attachments) {
            dao.insertAttachment(att.copy(id = 0L, taskId = newId, createdTimestamp = now, lastModifiedTimestamp = now))
        }

        val children = dao.getSubtasksSnapshot(task.id)
        for (child in children) {
            if (copyAllSubtasks || child.id in selectedSubtaskIds) {
                deepCopySelectiveRecursive(
                    task = child,
                    newParentId = newId,
                    copyAllSubtasks = copyAllSubtasks,
                    selectedSubtaskIds = selectedSubtaskIds,
                    copyIndex = 1
                )
            }
        }
    }

    fun linkTasksBidirectional(taskId1: Long, taskId2: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val t1 = dao.getTaskById(taskId1) ?: return@launch
            val t2 = dao.getTaskById(taskId2) ?: return@launch

            val list1 = t1.linkedTaskIds?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toMutableSet() ?: mutableSetOf()
            list1.add(taskId2.toString())

            val list2 = t2.linkedTaskIds?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toMutableSet() ?: mutableSetOf()
            list2.add(taskId1.toString())

            dao.updateTask(t1.copy(linkedTaskIds = list1.joinToString(",")))
            dao.updateTask(t2.copy(linkedTaskIds = list2.joinToString(",")))
        }
    }

    fun unlinkTasksBidirectional(taskId1: Long, taskId2: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val t1 = dao.getTaskById(taskId1) ?: return@launch
            val t2 = dao.getTaskById(taskId2) ?: return@launch

            val list1 = t1.linkedTaskIds?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toMutableSet() ?: mutableSetOf()
            list1.remove(taskId2.toString())

            val list2 = t2.linkedTaskIds?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toMutableSet() ?: mutableSetOf()
            list2.remove(taskId1.toString())

            dao.updateTask(t1.copy(linkedTaskIds = list1.joinToString(",")))
            dao.updateTask(t2.copy(linkedTaskIds = list2.joinToString(",")))
        }
    }

    fun shareTaskData(context: Context, taskId: Long, targetPackage: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val task = dao.getTaskById(taskId) ?: return@launch
            val checklists = dao.getChecklistSnapshot(taskId)
            val attachments = dao.getAttachmentsSnapshot(taskId)

            val shareText = buildString {
                append("📌 Task: ${task.title}\n")
                if (!task.notes.isNullOrBlank()) append("Notes: ${task.notes}\n")
                if (!task.locationName.isNullOrBlank()) append("📍 Location: ${task.locationName}\n")
                append("Priority: ${task.priority.name}\n")
                if (checklists.isNotEmpty()) {
                    append("\nChecklist:\n")
                    checklists.forEach { append("- ${if (it.isDone) "✓" else "○"} ${it.text}\n") }
                }
                if (attachments.isNotEmpty()) {
                    append("\nContacts & Attachments:\n")
                    attachments.forEach { append("- [${it.type}] ${it.displayName} ${it.contactPhone ?: ""}\n") }
                }
            }

            withContext(Dispatchers.Main) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, task.title)
                    putExtra(Intent.EXTRA_TEXT, shareText)
                    if (targetPackage != null) setPackage(targetPackage)
                }
                try {
                    context.startActivity(Intent.createChooser(intent, "Share Task via"))
                } catch (e: Exception) {
                    Toast.makeText(context, "Could not open selected app", Toast.LENGTH_SHORT).show()
                }
            }
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

    fun deleteAttachmentsBatch(attachments: List<RichAttachment>) {
        viewModelScope.launch(Dispatchers.IO) {
            if (attachments.isEmpty()) return@launch
            val taskId = attachments.first().taskId
            for (att in attachments) {
                dao.deleteAttachment(att)
            }
            touchTask(taskId)
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
    // -----------------------------------------------------------------------------------------
    // CUSTOM MULTI-TASK HIERARCHY TRANSFER ENGINE
    // -----------------------------------------------------------------------------------------  
     
}
