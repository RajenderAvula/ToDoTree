package com.example.infinitetodo

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class FilterCriteria(
    val priorities: Set<TaskPriority> = emptySet(),
    val statusPending: Boolean? = null,
    val selectedTags: Set<String> = emptySet(),
    val mustHaveLocation: Boolean = false,
    val selectedLocations: Set<String> = emptySet(),
    val mustHaveContact: Boolean = false,
    val createdFromMs: Long? = null,
    val createdToMs: Long? = null,
    val dueFromMs: Long? = null,
    val dueToMs: Long? = null
)

class TaskViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getDatabase(application).taskDao()
    private val workManager = WorkManager.getInstance(application)

    val rootTasks: Flow<List<TaskItem>> = dao.getRootTasks()
    val allTasksFlow: Flow<List<TaskItem>> = dao.getAllTasks()

    private val _filterState = MutableStateFlow(FilterCriteria())
    val filterState: StateFlow<FilterCriteria> = _filterState.asStateFlow()

    fun updateFilter(criteria: FilterCriteria) {
        _filterState.value = criteria
    }

    fun getSubtasks(parentId: Long): Flow<List<TaskItem>> = dao.getSubtasks(parentId)
    fun getSubtaskCount(parentId: Long): Flow<Int> = dao.getSubtaskCount(parentId)
    fun searchTasks(query: String): Flow<List<TaskItem>> = dao.searchTasks(query)
    fun getChecklist(taskId: Long): Flow<List<ChecklistItem>> = dao.getChecklist(taskId)
    fun getAttachments(taskId: Long): Flow<List<RichAttachment>> = dao.getAttachments(taskId)

    suspend fun getTaskById(id: Long): TaskItem? = dao.getTaskById(id)

    suspend fun createInitialDraftTask(parentId: Long?): TaskItem = withContext(Dispatchers.IO) {
        val draft = TaskItem(
            parentId = parentId,
            title = "",
            priority = TaskPriority.MEDIUM,
            createdTimestamp = System.currentTimeMillis(),
            lastModifiedTimestamp = System.currentTimeMillis()
        )
        val generatedId = dao.insertTask(draft)
        dao.getTaskById(generatedId) ?: draft.copy(id = generatedId)
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
            var googleCalId = task.calendarEventId

            val effectiveDueMs = if (task.isCompleted && dueEpochMs == null) {
                System.currentTimeMillis()
            } else {
                dueEpochMs
            }

            if (reminderEpochMs != null) {
                val hasPermission = ContextCompat.checkSelfPermission(
                    getApplication(),
                    android.Manifest.permission.WRITE_CALENDAR
                ) == PackageManager.PERMISSION_GRANTED

                if (hasPermission) {
                    val calId = CalendarHelper.getPrimaryGoogleCalendarId(getApplication())
                    if (calId != null) {
                        if (googleCalId != null) {
                            CalendarHelper.deleteEvent(getApplication(), googleCalId)
                        }
                        val prefix = if (task.isCompleted) "[COMPLETED] " else ""
                        googleCalId = CalendarHelper.insertEvent(
                            context = getApplication(),
                            calendarId = calId,
                            title = prefix + title.ifBlank { "Untitled Task" },
                            startTimeMs = reminderEpochMs,
                            notes = "Priority: ${priority.name}\nNotes: ${notes ?: "None"}"
                        )
                    }
                }
            } else if (googleCalId != null) {
                CalendarHelper.deleteEvent(getApplication(), googleCalId)
                googleCalId = null
            }

            val updated = task.copy(
                title = title,
                notes = notes,
                tags = tags,
                priority = priority,
                createdTimestamp = createdTimestampMs,
                lastModifiedTimestamp = System.currentTimeMillis(),
                reminderTimestamp = reminderEpochMs,
                dueTimestamp = effectiveDueMs,
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
                calendarEventId = googleCalId
            )
            dao.updateTask(updated)

            workManager.cancelAllWorkByTag("TASK_${task.id}")
            if (!task.isCompleted && reminderEpochMs != null && reminderEpochMs > System.currentTimeMillis()) {
                val delay = reminderEpochMs - System.currentTimeMillis()
                val workData = Data.Builder()
                    .putLong("TASK_ID", task.id)
                    .putString("TASK_TITLE", title.ifBlank { "Task Reminder" })
                    .build()

                val request = OneTimeWorkRequestBuilder<TaskReminderWorker>()
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .setInputData(workData)
                    .addTag("TASK_${task.id}")
                    .build()

                workManager.enqueue(request)
            }
        }
    }

    /**
     * Toggles task completion. When marked done, recursively marks all subtasks as done,
     * updates their dueTimestamp to completion time, and syncs status with Google Calendar.
     */
    fun toggleTaskCompletion(task: TaskItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val newCompletionState = !task.isCompleted
            val completionTimestamp = System.currentTimeMillis()

            // 1. Collect task and all nested descendants
            val descendants = getAllDescendants(task.id)
            val allToUpdate = listOf(task) + descendants

            val hasCalPermission = ContextCompat.checkSelfPermission(
                getApplication(),
                android.Manifest.permission.WRITE_CALENDAR
            ) == PackageManager.PERMISSION_GRANTED
            val calId = if (hasCalPermission) CalendarHelper.getPrimaryGoogleCalendarId(getApplication()) else null

            // 2. Update each task in Room and Calendar
            for (item in allToUpdate) {
                val updatedDueTimestamp = if (newCompletionState) {
                    completionTimestamp
                } else {
                    item.dueTimestamp
                }

                var updatedCalId = item.calendarEventId

                // Reflect completion in Google Calendar
                if (hasCalPermission && calId != null) {
                    val baseTitle = item.title.removePrefix("[COMPLETED] ").ifBlank { "Task #${item.id}" }
                    val calTitle = if (newCompletionState) "[COMPLETED] $baseTitle" else baseTitle
                    val eventTime = item.reminderTimestamp ?: updatedDueTimestamp ?: item.createdTimestamp

                    if (item.calendarEventId != null) {
                        CalendarHelper.deleteEvent(getApplication(), item.calendarEventId)
                    }

                    updatedCalId = CalendarHelper.insertEvent(
                        context = getApplication(),
                        calendarId = calId,
                        title = calTitle,
                        startTimeMs = eventTime,
                        notes = "Priority: ${item.priority.name}\nCompleted: ${if (newCompletionState) "Yes" else "No"}"
                    )
                }

                // If completing, cancel pending background notification alarms
                if (newCompletionState) {
                    workManager.cancelAllWorkByTag("TASK_${item.id}")
                }

                dao.updateTask(
                    item.copy(
                        isCompleted = newCompletionState,
                        dueTimestamp = updatedDueTimestamp,
                        lastModifiedTimestamp = completionTimestamp,
                        calendarEventId = updatedCalId
                    )
                )
            }
        }
    }

    fun deleteTask(task: TaskItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val allDescendants = getAllDescendants(task.id)
            val allToDelete = listOf(task) + allDescendants

            for (t in allToDelete) {
                workManager.cancelAllWorkByTag("TASK_${t.id}")
                t.calendarEventId?.let { calEventId ->
                    CalendarHelper.deleteEvent(getApplication(), calEventId)
                }
            }

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

    suspend fun getDescendantLayersCount(taskId: Long): Int = withContext(Dispatchers.IO) {
        val children = dao.getChildrenOf(taskId)
        if (children.isEmpty()) return@withContext 0
        1 + (children.maxOfOrNull { getDescendantLayersCount(it.id) } ?: 0)
    }

    suspend fun getHierarchyPathString(taskId: Long): String = withContext(Dispatchers.IO) {
        val path = mutableListOf<String>()
        var current: TaskItem? = dao.getTaskById(taskId)
        while (current != null) {
            path.add(0, current.title.ifBlank { "Task #${current.id}" })
            current = current.parentId?.let { dao.getTaskById(it) }
        }
        path.joinToString(" > ")
    }

    fun indentTask(task: TaskItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val siblings = if (task.parentId == null) {
                dao.getAllTasksSync().filter { it.parentId == null }
            } else {
                dao.getSubtasksSync(task.parentId)
            }
            val currentIndex = siblings.indexOfFirst { it.id == task.id }
            if (currentIndex > 0) {
                val newParent = siblings[currentIndex - 1]
                dao.updateTask(task.copy(parentId = newParent.id, lastModifiedTimestamp = System.currentTimeMillis()))
            }
        }
    }

    fun outdentTask(task: TaskItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentParent = task.parentId?.let { dao.getTaskById(it) }
            val newParentId = currentParent?.parentId
            dao.updateTask(task.copy(parentId = newParentId, lastModifiedTimestamp = System.currentTimeMillis()))
        }
    }

    fun moveTaskVertical(task: TaskItem, directionUp: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val siblings = if (task.parentId == null) {
                dao.getAllTasksSync().filter { it.parentId == null }
            } else {
                dao.getSubtasksSync(task.parentId)
            }.toMutableList()

            val idx = siblings.indexOfFirst { it.id == task.id }
            if (idx == -1) return@launch

            if (directionUp && idx > 0) {
                val prev = siblings[idx - 1]
                dao.updateTask(task.copy(orderIndex = prev.orderIndex))
                dao.updateTask(prev.copy(orderIndex = task.orderIndex))
            } else if (!directionUp && idx < siblings.size - 1) {
                val next = siblings[idx + 1]
                dao.updateTask(task.copy(orderIndex = next.orderIndex))
                dao.updateTask(next.copy(orderIndex = task.orderIndex))
            }
        }
    }

    fun moveTaskToTarget(task: TaskItem, newParentId: Long?) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.updateTask(task.copy(parentId = newParentId, lastModifiedTimestamp = System.currentTimeMillis()))
        }
    }

    fun copyTaskToTarget(taskId: Long, targetParentId: Long?) {
        viewModelScope.launch(Dispatchers.IO) {
            val original = dao.getTaskById(taskId) ?: return@launch
            val copy = original.copy(
                id = 0L,
                parentId = targetParentId,
                title = "${original.title} (Copy)",
                calendarEventId = null,
                createdTimestamp = System.currentTimeMillis(),
                lastModifiedTimestamp = System.currentTimeMillis()
            )
            val newId = dao.insertTask(copy)

            val children = dao.getChildrenOf(taskId)
            for (child in children) {
                copyTaskToTarget(child.id, newId)
            }
        }
    }

    suspend fun getAllPotentialParents(excludeTaskId: Long): List<TaskItem> = withContext(Dispatchers.IO) {
        val invalidIds = mutableSetOf(excludeTaskId)
        val descendants = getAllDescendants(excludeTaskId)
        invalidIds.addAll(descendants.map { it.id })

        dao.getAllTasksSync().filter { it.id !in invalidIds }
    }

    // Checklist Operations
    fun addChecklistItem(taskId: Long, text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.insertChecklistItem(ChecklistItem(taskId = taskId, text = text))
        }
    }

    fun toggleChecklistItem(item: ChecklistItem) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.updateChecklistItem(item.copy(isDone = !item.isDone))
        }
    }

    fun updateChecklistItem(item: ChecklistItem, newText: String, newNotes: String?, isDone: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.updateChecklistItem(item.copy(text = newText, notes = newNotes, isDone = isDone))
        }
    }

    fun deleteChecklistItem(item: ChecklistItem) {
        viewModelScope.launch(Dispatchers.IO) { dao.deleteChecklistItem(item) }
    }

    fun moveChecklistItem(item: ChecklistItem, directionUp: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.updateChecklistItem(item.copy(orderIndex = item.orderIndex + if (directionUp) -1 else 1))
        }
    }

    // Attachment Operations
    fun addAttachment(
        taskId: Long,
        type: AttachmentType,
        uriString: String,
        displayName: String,
        contactPhone: String? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.insertAttachment(
                RichAttachment(
                    taskId = taskId,
                    type = type,
                    uriString = uriString,
                    displayName = displayName,
                    contactPhone = contactPhone
                )
            )
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
            dao.updateAttachment(
                attachment.copy(
                    displayName = displayName,
                    notes = notes,
                    contactPhone = contactPhone,
                    isContactPending = isContactPending
                )
            )
        }
    }

    fun deleteAttachment(attachment: RichAttachment) {
        viewModelScope.launch(Dispatchers.IO) { dao.deleteAttachment(attachment) }
    }

    fun moveAttachment(attachment: RichAttachment, directionUp: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.updateAttachment(attachment.copy(orderIndex = attachment.orderIndex + if (directionUp) -1 else 1))
        }
    }

    // Linking Operations
    fun linkTasksBidirectional(taskIdA: Long, taskIdB: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val a = dao.getTaskById(taskIdA) ?: return@launch
            val b = dao.getTaskById(taskIdB) ?: return@launch

            val setA = a.linkedTaskIds?.split(",")?.mapNotNull { it.trim().toLongOrNull() }?.toMutableSet() ?: mutableSetOf()
            val setB = b.linkedTaskIds?.split(",")?.mapNotNull { it.trim().toLongOrNull() }?.toMutableSet() ?: mutableSetOf()

            setA.add(taskIdB)
            setB.add(taskIdA)

            dao.updateTask(a.copy(linkedTaskIds = setA.joinToString(",")))
            dao.updateTask(b.copy(linkedTaskIds = setB.joinToString(",")))
        }
    }

    fun unlinkTasksBidirectional(taskIdA: Long, taskIdB: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val a = dao.getTaskById(taskIdA) ?: return@launch
            val b = dao.getTaskById(taskIdB) ?: return@launch

            val setA = a.linkedTaskIds?.split(",")?.mapNotNull { it.trim().toLongOrNull() }?.toMutableSet() ?: mutableSetOf()
            val setB = b.linkedTaskIds?.split(",")?.mapNotNull { it.trim().toLongOrNull() }?.toMutableSet() ?: mutableSetOf()

            setA.remove(taskIdB)
            setB.remove(taskIdA)

            dao.updateTask(a.copy(linkedTaskIds = if (setA.isEmpty()) null else setA.joinToString(",")))
            dao.updateTask(b.copy(linkedTaskIds = if (setB.isEmpty()) null else setB.joinToString(",")))
        }
    }

    suspend fun getAllUniqueTags(): List<String> = withContext(Dispatchers.IO) {
        val tasks = dao.getAllTasksSync()
        tasks.mapNotNull { it.tags }
            .flatMap { it.split(",") }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    suspend fun getAllUniqueLocations(): List<String> = withContext(Dispatchers.IO) {
        dao.getAllTasksSync()
            .mapNotNull { it.locationName }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    fun syncAllTasksToCalendar(onComplete: (Int) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val hasPermission = ContextCompat.checkSelfPermission(
                getApplication(),
                android.Manifest.permission.WRITE_CALENDAR
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasPermission) {
                withContext(Dispatchers.Main) { onComplete(0) }
                return@launch
            }

            val calId = CalendarHelper.getPrimaryGoogleCalendarId(getApplication())
            if (calId == null) {
                withContext(Dispatchers.Main) { onComplete(0) }
                return@launch
            }

            val tasks = dao.getAllTasksSync()
            var count = 0
            for (t in tasks) {
                val time = t.reminderTimestamp ?: t.dueTimestamp ?: t.createdTimestamp
                if (t.calendarEventId != null) {
                    CalendarHelper.deleteEvent(getApplication(), t.calendarEventId)
                }
                val prefix = if (t.isCompleted) "[COMPLETED] " else ""
                val newId = CalendarHelper.insertEvent(
                    context = getApplication(),
                    calendarId = calId,
                    title = prefix + t.title.ifBlank { "Task #${t.id}" },
                    startTimeMs = time,
                    notes = t.notes ?: ""
                )
                if (newId != null) {
                    dao.updateTask(t.copy(calendarEventId = newId))
                    count++
                }
            }
            withContext(Dispatchers.Main) { onComplete(count) }
        }
    }

    fun shareTaskData(context: Context, taskId: Long, targetPackage: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val task = dao.getTaskById(taskId) ?: return@launch
            val hierarchy = getHierarchyPathString(taskId)

            val sb = StringBuilder()
            sb.append("📌 *${task.title.ifBlank { "Untitled Task" }}*\n")
            sb.append("🗂️ Hierarchy: $hierarchy\n")
            sb.append("⚡ Priority: ${task.priority.name}\n")
            sb.append("Status: ${if (task.isCompleted) "Completed ✓" else "Pending"}\n")
            if (task.dueTimestamp != null) sb.append("📅 Date: ${task.dueTimestamp}\n")
            if (!task.locationName.isNullOrBlank()) sb.append("📍 Location: ${task.locationName}\n")
            if (!task.notes.isNullOrBlank()) sb.append("\n📝 Notes:\n${task.notes}\n")

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, sb.toString())
                if (!targetPackage.isNullOrBlank()) {
                    setPackage(targetPackage)
                }
            }
            val chooser = Intent.createChooser(intent, "Share Task via")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        }
    }

    fun backupToDevice(outStream: OutputStream, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val tasks = dao.getAllTasksSync()
                val rootJson = JSONObject()
                val tasksArray = JSONArray()

                for (t in tasks) {
                    val obj = JSONObject().apply {
                        put("id", t.id)
                        put("parentId", t.parentId ?: JSONObject.NULL)
                        put("title", t.title)
                        put("notes", t.notes ?: JSONObject.NULL)
                        put("tags", t.tags ?: JSONObject.NULL)
                        put("priority", t.priority.name)
                        put("isCompleted", t.isCompleted)
                        put("createdTimestamp", t.createdTimestamp)
                        put("lastModifiedTimestamp", t.lastModifiedTimestamp)
                        put("reminderTimestamp", t.reminderTimestamp ?: JSONObject.NULL)
                        put("dueTimestamp", t.dueTimestamp ?: JSONObject.NULL)
                        put("repeatRule", t.repeatRule.name)
                        put("repeatIntervalDays", t.repeatIntervalDays)
                        put("repeatIntervalHours", t.repeatIntervalHours)
                        put("repeatIntervalMinutes", t.repeatIntervalMinutes)
                        put("locationName", t.locationName ?: JSONObject.NULL)
                        put("latitude", t.latitude ?: JSONObject.NULL)
                        put("longitude", t.longitude ?: JSONObject.NULL)
                    }
                    tasksArray.put(obj)
                }
                rootJson.put("tasks", tasksArray)

                ZipOutputStream(outStream).use { zip ->
                    zip.putNextEntry(ZipEntry("backup.json"))
                    zip.write(rootJson.toString().toByteArray())
                    zip.closeEntry()
                }
                withContext(Dispatchers.Main) { onResult(true) }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { onResult(false) }
            }
        }
    }

    fun restoreBackup(inStream: InputStream, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var jsonString: String? = null
                ZipInputStream(inStream).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (entry.name == "backup.json") {
                            jsonString = zip.bufferedReader().readText()
                            break
                        }
                        entry = zip.nextEntry
                    }
                }

                if (jsonString != null) {
                    val root = JSONObject(jsonString!!)
                    val array = root.getJSONArray("tasks")
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        val task = TaskItem(
                            parentId = if (obj.isNull("parentId")) null else obj.getLong("parentId"),
                            title = obj.getString("title"),
                            notes = if (obj.isNull("notes")) null else obj.getString("notes"),
                            tags = if (obj.isNull("tags")) null else obj.getString("tags"),
                            priority = TaskPriority.valueOf(obj.getString("priority")),
                            isCompleted = obj.getBoolean("isCompleted"),
                            createdTimestamp = obj.getLong("createdTimestamp"),
                            lastModifiedTimestamp = obj.getLong("lastModifiedTimestamp"),
                            reminderTimestamp = if (obj.isNull("reminderTimestamp")) null else obj.getLong("reminderTimestamp"),
                            dueTimestamp = if (obj.isNull("dueTimestamp")) null else obj.getLong("dueTimestamp"),
                            repeatRule = RecurrenceRule.valueOf(obj.getString("repeatRule")),
                            repeatIntervalDays = obj.getInt("repeatIntervalDays"),
                            repeatIntervalHours = obj.getInt("repeatIntervalHours"),
                            repeatIntervalMinutes = obj.getInt("repeatIntervalMinutes"),
                            locationName = if (obj.isNull("locationName")) null else obj.getString("locationName"),
                            latitude = if (obj.isNull("latitude")) null else obj.getDouble("latitude"),
                            longitude = if (obj.isNull("longitude")) null else obj.getDouble("longitude")
                        )
                        dao.insertTask(task)
                    }
                    withContext(Dispatchers.Main) { onResult(true) }
                } else {
                    withContext(Dispatchers.Main) { onResult(false) }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { onResult(false) }
            }
        }
    }

    fun sendBackupViaMail(onIntentReady: (Intent?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val backupFile = File(getApplication<Application>().cacheDir, "ToDoTree_Backup_${System.currentTimeMillis()}.zip")
                backupFile.outputStream().use { outStream ->
                    backupToDevice(outStream) {}
                }

                val uri = FileProvider.getUriForFile(
                    getApplication(),
                    "${getApplication<Application>().packageName}.provider",
                    backupFile
                )

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_SUBJECT, "ToDoTree Backup")
                    putExtra(Intent.EXTRA_STREAM, uri)
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                withContext(Dispatchers.Main) { onIntentReady(intent) }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { onIntentReady(null) }
            }
        }
    }
}
