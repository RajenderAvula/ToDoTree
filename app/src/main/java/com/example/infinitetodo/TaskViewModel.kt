package com.example.infinitetodo

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*

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
    private val dao = AppDatabase.getDatabase(application).taskDao()[span_0](start_span)[span_0](end_span)
    private val backupRestoreManager = BackupRestoreManager(application)[span_1](start_span)[span_1](end_span)

    val rootTasks: Flow<List<TaskItem>> = dao.getRootTasks()[span_2](start_span)[span_2](end_span)
    val allTasksFlow: Flow<List<TaskItem>> = dao.getAllTasksFlow()[span_3](start_span)[span_3](end_span)

    private val _filterState = MutableStateFlow(FilterCriteria())[span_4](start_span)[span_4](end_span)
    val filterState: StateFlow<FilterCriteria> = _filterState.asStateFlow()[span_5](start_span)[span_5](end_span)

    fun updateFilter(criteria: FilterCriteria) {
        _filterState.value = criteria[span_6](start_span)[span_6](end_span)
    }

    fun resetFilters() {
        _filterState.value = FilterCriteria()
    }

    // Reactive flow of tasks that have reminder, due date, or repeat scheduled up to end of today
    val todayDueOrReminderTasks: Flow<List<TaskItem>> = allTasksFlow.map { tasks ->
        val endOfTodayMs = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis

        tasks.filter { task ->
            if (task.isCompleted) return@filter false

            val hasDueTodayOrPast = task.dueTimestamp != null && task.dueTimestamp <= endOfTodayMs
            val hasReminderTodayOrPast = task.reminderTimestamp != null && task.reminderTimestamp <= endOfTodayMs
            val hasRepeatActive = task.repeatRule != RecurrenceRule.NONE && (task.repeatStartDate == null || task.repeatStartDate <= endOfTodayMs)

            hasDueTodayOrPast || hasReminderTodayOrPast || hasRepeatActive
        }
    }

    fun getSubtasks(parentId: Long): Flow<List<TaskItem>> = dao.getSubtasks(parentId)[span_7](start_span)[span_7](end_span)
    fun getSubtaskCount(parentId: Long): Flow<Int> = dao.getSubtaskCount(parentId)[span_8](start_span)[span_8](end_span)
    fun getChecklist(taskId: Long): Flow<List<ChecklistItem>> = dao.getChecklistForTask(taskId)[span_9](start_span)[span_9](end_span)
    fun getAttachments(taskId: Long): Flow<List<RichAttachment>> = dao.getAttachmentsForTask(taskId)[span_10](start_span)[span_10](end_span)
    fun searchTasks(query: String): Flow<List<TaskItem>> = dao.searchTasks(query)[span_11](start_span)[span_11](end_span)

    suspend fun getTaskById(taskId: Long): TaskItem? = dao.getTaskById(taskId)[span_12](start_span)[span_12](end_span)

    fun linkTasksBidirectional(taskAId: Long, taskBId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val taskA = dao.getTaskById(taskAId) ?: return@launch[span_13](start_span)[span_13](end_span)
            val taskB = dao.getTaskById(taskBId) ?: return@launch[span_14](start_span)[span_14](end_span)

            val setA = taskA.linkedTaskIds?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toMutableSet() ?: mutableSetOf()[span_15](start_span)[span_15](end_span)
            setA.add(taskBId.toString())[span_16](start_span)[span_16](end_span)
            dao.updateTask(taskA.copy(linkedTaskIds = setA.joinToString(","), lastModifiedTimestamp = System.currentTimeMillis()))[span_17](start_span)[span_17](end_span)

            val setB = taskB.linkedTaskIds?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toMutableSet() ?: mutableSetOf()[span_18](start_span)[span_18](end_span)
            setB.add(taskAId.toString())[span_19](start_span)[span_19](end_span)
            dao.updateTask(taskB.copy(linkedTaskIds = setB.joinToString(","), lastModifiedTimestamp = System.currentTimeMillis()))[span_20](start_span)[span_20](end_span)
        }
    }

    fun unlinkTasksBidirectional(taskAId: Long, taskBId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val taskA = dao.getTaskById(taskAId) ?: return@launch[span_21](start_span)[span_21](end_span)
            val taskB = dao.getTaskById(taskBId) ?: return@launch[span_22](start_span)[span_22](end_span)

            val setA = taskA.linkedTaskIds?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toMutableSet() ?: mutableSetOf()[span_23](start_span)[span_23](end_span)
            setA.remove(taskBId.toString())[span_24](start_span)[span_24](end_span)
            dao.updateTask(taskA.copy(linkedTaskIds = setA.joinToString(","), lastModifiedTimestamp = System.currentTimeMillis()))[span_25](start_span)[span_25](end_span)

            val setB = taskB.linkedTaskIds?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toMutableSet() ?: mutableSetOf()[span_26](start_span)[span_26](end_span)
            setB.remove(taskAId.toString())[span_27](start_span)[span_27](end_span)
            dao.updateTask(taskB.copy(linkedTaskIds = setB.joinToString(","), lastModifiedTimestamp = System.currentTimeMillis()))[span_28](start_span)[span_28](end_span)
        }
    }

    suspend fun getAllUniqueTags(): List<String> {
        val all = dao.getAllTasksSnapshot()[span_29](start_span)[span_29](end_span)
        val set = mutableSetOf<String>()[span_30](start_span)[span_30](end_span)
        for (t in all) {
            t.tags?.split(",")?.forEach { tag ->
                val clean = tag.trim()[span_31](start_span)[span_31](end_span)
                if (clean.isNotEmpty()) set.add(clean)[span_32](start_span)[span_32](end_span)
            }
        }
        return set.sorted()[span_33](start_span)[span_33](end_span)
    }

    suspend fun getAllUniqueLocations(): List<String> {
        val all = dao.getAllTasksSnapshot()[span_34](start_span)[span_34](end_span)
        val set = mutableSetOf<String>()[span_35](start_span)[span_35](end_span)
        for (t in all) {
            t.locationName?.let { loc ->
                val clean = loc.trim()[span_36](start_span)[span_36](end_span)
                if (clean.isNotEmpty()) set.add(clean)[span_37](start_span)[span_37](end_span)
            }
        }
        return set.sorted()[span_38](start_span)[span_38](end_span)
    }

    suspend fun getDescendantLayersCount(taskId: Long): Int {
        var currentLevel = listOf(taskId)[span_39](start_span)[span_39](end_span)
        var depth = 0[span_40](start_span)[span_40](end_span)
        while (currentLevel.isNotEmpty()) {
            val nextLevel = mutableListOf<Long>()[span_41](start_span)[span_41](end_span)
            for (id in currentLevel) {
                val children = dao.getSubtasksSnapshot(id)[span_42](start_span)[span_42](end_span)
                nextLevel.addAll(children.map { it.id })[span_43](start_span)[span_43](end_span)
            }
            if (nextLevel.isNotEmpty()) {
                depth++[span_44](start_span)[span_44](end_span)
                currentLevel = nextLevel[span_45](start_span)[span_45](end_span)
            } else {
                break[span_46](start_span)[span_46](end_span)
            }
        }
        return depth[span_47](start_span)[span_47](end_span)
    }

    suspend fun getLayerLevel(taskId: Long): Int {
        var level = 1[span_48](start_span)[span_48](end_span)
        var curr = dao.getTaskById(taskId)[span_49](start_span)[span_49](end_span)
        while (curr?.parentId != null) {
            level++[span_50](start_span)[span_50](end_span)
            curr = dao.getTaskById(curr.parentId!!)[span_51](start_span)[span_51](end_span)
        }
        return level[span_52](start_span)[span_52](end_span)
    }

    suspend fun getHierarchyPathString(taskId: Long): String {
        val trail = mutableListOf<TaskItem>()[span_53](start_span)[span_53](end_span)
        var currentId: Long? = taskId[span_54](start_span)[span_54](end_span)
        while (currentId != null) {
            val task = dao.getTaskById(currentId) ?: break[span_55](start_span)[span_55](end_span)
            trail.add(0, task)[span_56](start_span)[span_56](end_span)
            currentId = task.parentId[span_57](start_span)[span_57](end_span)
        }
        return trail.joinToString(" ➔ ") { it.title.ifBlank { "Task #${it.id}" } }[span_58](start_span)[span_58](end_span)
    }

    suspend fun getAllPotentialParents(excludeTaskId: Long): List<TaskItem> {
        val all = dao.getAllTasksSnapshot()[span_59](start_span)[span_59](end_span)
        return all.filter { it.id != excludeTaskId }[span_60](start_span)[span_60](end_span)
    }

    fun backupToDevice(destinationStream: OutputStream, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val success = backupRestoreManager.createZipBackup(dao, destinationStream)[span_61](start_span)[span_61](end_span)
            withContext(Dispatchers.Main) { onComplete(success) }[span_62](start_span)[span_62](end_span)
        }
    }

    fun sendBackupViaMail(onReady: (Intent?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val uri = backupRestoreManager.createMailAttachmentBackup(dao)[span_63](start_span)[span_63](end_span)
            withContext(Dispatchers.Main) {
                if (uri != null) {
                    val emailIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip[span_64](start_span)"[span_64](end_span)
                        putExtra(Intent.EXTRA_SUBJECT, "ToDoTree Complete Backup with Attachments")[span_65](start_span)[span_65](end_span)
                        putExtra(Intent.EXTRA_TEXT, "Attached is your ToDoTree complete backup archive containing tasks, checklists, attachments, and audio recordings.")[span_66](start_span)[span_66](end_span)
                        putExtra(Intent.EXTRA_STREAM, uri)[span_67](start_span)[span_67](end_span)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)[span_68](start_span)[span_68](end_span)
                    }
                    onReady(emailIntent)[span_69](start_span)[span_69](end_span)
                } else {
                    onReady(null)[span_70](start_span)[span_70](end_span)
                }
            }
        }
    }

    fun restoreBackup(sourceStream: InputStream, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val success = backupRestoreManager.restoreFromZip(dao, sourceStream)[span_71](start_span)[span_71](end_span)
            withContext(Dispatchers.Main) { onComplete(success) }[span_72](start_span)[span_72](end_span)
        }
    }

    suspend fun buildTaskHierarchySchemaText(taskId: Long): String {
        val rootTask = dao.getTaskById(taskId) ?: return "[span_73](start_span)"[span_73](end_span)
        val sb = StringBuilder()[span_74](start_span)[span_74](end_span)
        val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())[span_75](start_span)[span_75](end_span)

        val path = getHierarchyPathString(taskId)[span_76](start_span)[span_76](end_span)
        sb.append("📋 TASK EXPORT SCHEMA\n")[span_77](start_span)[span_77](end_span)
        sb.append("Hierarchy Path: $path\n")[span_78](start_span)[span_78](end_span)
        sb.append("======================================\n\n")[span_79](start_span)[span_79](end_span)

        suspend fun appendNode(node: TaskItem, indentLevel: Int) {
            val indent = "  ".repeat(indentLevel)[span_80](start_span)[span_80](end_span)
            val statusSymbol = if (node.isCompleted) "[COMPLETED ✓]" else "[PENDING][span_81](start_span)"[span_81](end_span)
            sb.append("${indent}● ${node.title.ifBlank { "Untitled" }} $statusSymbol\n")[span_82](start_span)[span_82](end_span)
            sb.append("${indent}  - Priority: ${node.priority.name}\n")[span_83](start_span)[span_83](end_span)
            sb.append("${indent}  - Created: ${dateFormat.format(Date(node.createdTimestamp))}\n")[span_84](start_span)[span_84](end_span)

            if (!node.tags.isNullOrBlank()) {
                sb.append("${indent}  - Tags: ${node.tags}\n")[span_85](start_span)[span_85](end_span)
            }

            if (!node.locationName.isNullOrBlank() || (node.latitude != null && node.longitude != null)) {
                val locTitle = node.locationName ?: "Pinned Location[span_86](start_span)"[span_86](end_span)
                sb.append("${indent}  - 📍 Location: $locTitle\n")[span_87](start_span)[span_87](end_span)
                if (node.latitude != null && node.longitude != null) {
                    sb.append("${indent}    Map Link: https://maps.google.com/?q=${node.latitude},${node.longitude}\n")[span_88](start_span)[span_88](end_span)
                }
            }

            if (!node.notes.isNullOrBlank()) {
                sb.append("${indent}  - Notes: ${node.notes}\n")[span_89](start_span)[span_89](end_span)
            }

            val checklists = dao.getChecklistSnapshot(node.id)[span_90](start_span)[span_90](end_span)
            if (checklists.isNotEmpty()) {
                sb.append("${indent}  - Checklists:\n")[span_91](start_span)[span_91](end_span)
                checklists.forEach { chk ->
                    val chkBox = if (chk.isDone) "[x]" else "[ ][span_92](start_span)"[span_92](end_span)
                    sb.append("${indent}    $chkBox ${chk.text}\n")[span_93](start_span)[span_93](end_span)
                }
            }

            val attachments = dao.getAttachmentsSnapshot(node.id)[span_94](start_span)[span_94](end_span)
            if (attachments.isNotEmpty()) {
                sb.append("${indent}  - Attachments/Contacts:\n")[span_95](start_span)[span_95](end_span)
                attachments.forEach { att ->
                    val phoneInfo = if (att.contactPhone != null) " (${att.contactPhone})" else "[span_96](start_span)"[span_96](end_span)
                    sb.append("${indent}    * [${att.type}] ${att.displayName}$phoneInfo\n")[span_97](start_span)[span_97](end_span)
                }
            }

            sb.append("\n")[span_98](start_span)[span_98](end_span)

            val children = dao.getSubtasksSnapshot(node.id)[span_99](start_span)[span_99](end_span)
            for (child in children) {
                appendNode(child, indentLevel + 1)[span_100](start_span)[span_100](end_span)
            }
        }

        appendNode(rootTask, 0)[span_101](start_span)[span_101](end_span)
        return sb.toString()[span_102](start_span)[span_102](end_span)
    }

    fun shareTaskData(context: Context, taskId: Long, targetPackage: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val schemaText = buildTaskHierarchySchemaText(taskId)[span_103](start_span)[span_103](end_span)
            val task = dao.getTaskById(taskId)[span_104](start_span)[span_104](end_span)
            val subject = "Task Schema: ${task?.title ?: "Export"}[span_105](start_span)"[span_105](end_span)

            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain[span_106](start_span)"[span_106](end_span)
                putExtra(Intent.EXTRA_SUBJECT, subject)[span_107](start_span)[span_107](end_span)
                putExtra(Intent.EXTRA_TEXT, schemaText)[span_108](start_span)[span_108](end_span)
                if (targetPackage != null) {
                    `package` = targetPackage[span_109](start_span)[span_109](end_span)
                }
            }

            withContext(Dispatchers.Main) {
                try {
                    if (targetPackage != null) {
                        context.startActivity(sendIntent)[span_110](start_span)[span_110](end_span)
                    } else {
                        val chooser = Intent.createChooser(sendIntent, "Share Task via")[span_111](start_span)[span_111](end_span)
                        context.startActivity(chooser)[span_112](start_span)[span_112](end_span)
                    }
                } catch (_: Exception) {
                    val chooser = Intent.createChooser(sendIntent, "Share Task via")[span_113](start_span)[span_113](end_span)
                    context.startActivity(chooser)[span_114](start_span)[span_114](end_span)
                }
            }
        }
    }

    suspend fun syncTaskToCalendar(task: TaskItem): Boolean {
        val hasPermission = ContextCompat.checkSelfPermission(
            getApplication(),
            android.Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED[span_115](start_span)[span_115](end_span)
        if (!hasPermission) return false[span_116](start_span)[span_116](end_span)

        return try {
            val target = CalendarHelper.getPrimaryGoogleCalendar(getApplication()) ?: return false[span_117](start_span)[span_117](end_span)
            val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())[span_118](start_span)[span_118](end_span)
            val createdEpochMs = task.createdTimestamp[span_119](start_span)[span_119](end_span)

            val parentTask = if (task.parentId != null) dao.getTaskById(task.parentId) else null[span_120](start_span)[span_120](end_span)
            val hierarchyPrefix = if (parentTask != null) "[Subtask of '${parentTask.title}'] " else "[Main Task] [span_121](start_span)"[span_121](end_span)
            val cleanTitle = task.title.removePrefix("[DONE] ✓ ")[span_122](start_span)[span_122](end_span)
            val fullCalendarTitle = if (task.isCompleted) "[DONE] ✓ $hierarchyPrefix$cleanTitle" else "$hierarchyPrefix$cleanTitle[span_123](start_span)"[span_123](end_span)

            val attachments = dao.getAttachmentsSnapshot(task.id)[span_124](start_span)[span_124](end_span)
            val checklists = dao.getChecklistSnapshot(task.id)[span_125](start_span)[span_125](end_span)

            val notesBody = task.notes ?: "[span_126](start_span)"[span_126](end_span)
            val tagsSummary = if (!task.tags.isNullOrBlank()) "Tags: [${task.tags}]\n" else "[span_127](start_span)"[span_127](end_span)
            val locSummary = if (!task.locationName.isNullOrBlank() || (task.latitude != null && task.longitude != null)) {
                "Location: ${task.locationName ?: "Coordinates: ${task.latitude}, ${task.longitude}"}\n[span_128](start_span)"[span_128](end_span)
            } else "[span_129](start_span)"[span_129](end_span)

            val chkSummary = if (checklists.isNotEmpty()) {
                "\n\nChecklist:\n" + checklists.joinToString("\n") { (if (it.isDone) "✓ " else "○ ") + it.text }[span_130](start_span)[span_130](end_span)
            } else "[span_131](start_span)"[span_131](end_span)
            val attSummary = if (attachments.isNotEmpty()) {
                "\n\nAttachments & Contacts:\n" + attachments.joinToString("\n") { "- [${it.type}] ${it.displayName}" }[span_132](start_span)[span_132](end_span)
            } else "[span_133](start_span)"[span_133](end_span)

            val auditNote = "Created: ${dateFormat.format(Date(createdEpochMs))}\nModified: ${dateFormat.format(Date(task.lastModifiedTimestamp))}\n[span_134](start_span)"[span_134](end_span)
            val repeatNotice = if (task.repeatRule != RecurrenceRule.NONE) "Recurrence: ${task.repeatRule.name}\n" else "[span_135](start_span)"[span_135](end_span)
            val fullDescription = "$auditNote$tagsSummary$locSummary$repeatNotice Priority: ${task.priority.name}\nStatus: ${if (task.isCompleted) "Completed" else "Pending"}\n\n$notesBody$chkSummary$attSummary".trim()[span_136](start_span)[span_136](end_span)

            var updatedSuccessfully = false[span_137](start_span)[span_137](end_span)
            val currentEventAlive = task.calendarEventId != null && CalendarHelper.eventExists(getApplication(), task.calendarEventId!!)[span_138](start_span)[span_138](end_span)

            if (currentEventAlive) {
                updatedSuccessfully = CalendarHelper.updateEvent(
                    context = getApplication(),
                    target = target,
                    eventId = task.calendarEventId!!,
                    title = fullCalendarTitle,
                    notes = fullDescription,
                    createdTimestampMs = createdEpochMs
                )[span_139](start_span)[span_139](end_span)
            }

            if (!updatedSuccessfully) {
                val newEventId = CalendarHelper.insertEvent(
                    context = getApplication(),
                    target = target,
                    title = fullCalendarTitle,
                    createdTimestampMs = createdEpochMs,
                    notes = fullDescription
                )[span_140](start_span)[span_140](end_span)
                if (newEventId != null) {
                    dao.updateTask(task.copy(calendarEventId = newEventId))[span_141](start_span)[span_141](end_span)
                }
            }
            true[span_142](start_span)[span_142](end_span)
        } catch (e: Exception) {
            Log.e("CalendarSync", "Sync failed for task '${task.title}'", e)[span_143](start_span)[span_143](end_span)
            false[span_144](start_span)[span_144](end_span)
        }
    }

    fun syncAllTasksToCalendar(onDone: (Int) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val all = dao.getAllTasksSnapshot()[span_145](start_span)[span_145](end_span)
            var count = 0[span_146](start_span)[span_146](end_span)
            for (t in all) {
                if (syncTaskToCalendar(t)) count++[span_147](start_span)[span_147](end_span)
            }
            withContext(Dispatchers.Main) { onDone(count) }[span_148](start_span)[span_148](end_span)
        }
    }

    suspend fun createInitialDraftTask(parentId: Long?): TaskItem {
        val now = System.currentTimeMillis()[span_149](start_span)[span_149](end_span)
        val siblings = dao.getSubtasksSnapshot(parentId)[span_150](start_span)[span_150](end_span)
        val draft = TaskItem(
            parentId = parentId,
            title = "",
            orderIndex = siblings.size,
            createdTimestamp = now,
            lastModifiedTimestamp = now
        )[span_151](start_span)[span_151](end_span)
        val id = dao.insertTask(draft)[span_152](start_span)[span_152](end_span)
        return draft.copy(id = id)[span_153](start_span)[span_153](end_span)
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
            val now = System.currentTimeMillis()[span_154](start_span)[span_154](end_span)
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
            )[span_155](start_span)[span_155](end_span)
            dao.updateTask(updated)[span_156](start_span)[span_156](end_span)
            syncTaskToCalendar(updated)[span_157](start_span)[span_157](end_span)

            // Schedule alarms with isolated channels (Reminders, Due Dates, and Repeats)
            TaskSchedulerHelper.scheduleAllAlerts(getApplication(), updated)
        }
    }

    fun toggleTaskCompletion(task: TaskItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val newCompletionState = !task.isCompleted[span_158](start_span)[span_158](end_span)
            val now = System.currentTimeMillis()[span_159](start_span)[span_159](end_span)
            val completedTime = if (newCompletionState) now else null[span_160](start_span)[span_160](end_span)

            val allDescendants = getAllDescendants(task.id)[span_161](start_span)[span_161](end_span)
            val allTasksToUpdate = listOf(task) + allDescendants[span_162](start_span)[span_162](end_span)

            for (item in allTasksToUpdate) {
                val updated = item.copy(
                    isCompleted = newCompletionState,
                    completedTimestamp = completedTime,
                    lastModifiedTimestamp = now
                )[span_163](start_span)[span_163](end_span)
                dao.updateTask(updated)[span_164](start_span)[span_164](end_span)
                syncTaskToCalendar(updated)[span_165](start_span)[span_165](end_span)

                if (newCompletionState) {
                    TaskSchedulerHelper.cancelAllAlerts(getApplication(), item.id)
                } else {
                    TaskSchedulerHelper.scheduleAllAlerts(getApplication(), updated)
                }
            }
        }
    }

    private suspend fun getAllDescendants(parentId: Long): List<TaskItem> {
        val result = mutableListOf<TaskItem>()[span_166](start_span)[span_166](end_span)
        val directChildren = dao.getSubtasksSnapshot(parentId)
        for (child in directChildren) {
            result.add(child)[span_167](start_span)[span_167](end_span)
            result.addAll(getAllDescendants(child.id))[span_168](start_span)[span_168](end_span)
        }
        return result[span_169](start_span)[span_169](end_span)
    }

    fun deleteTask(task: TaskItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val allDescendants = getAllDescendants(task.id)[span_170](start_span)[span_170](end_span)
            val allToDelete = listOf(task) + allDescendants[span_171](start_span)[span_171](end_span)

            for (t in allToDelete) {
                TaskSchedulerHelper.cancelAllAlerts(getApplication(), t.id)
                t.calendarEventId?.let { calEventId ->
                    CalendarHelper.deleteEvent(getApplication(), calEventId)[span_172](start_span)[span_172](end_span)
                }
            }

            dao.deleteTask(task)[span_173](start_span)[span_173](end_span)
        }
    }

    fun moveTaskVertical(task: TaskItem, directionUp: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val siblings = dao.getSubtasksSnapshot(task.parentId).toMutableList()[span_174](start_span)[span_174](end_span)
            val currentIndex = siblings.indexOfFirst { it.id == task.id }[span_175](start_span)[span_175](end_span)
            if (currentIndex == -1) return@launch[span_176](start_span)[span_176](end_span)

            val targetIndex = if (directionUp) currentIndex - 1 else currentIndex + 1[span_177](start_span)[span_177](end_span)
            if (targetIndex in siblings.indices) {
                val now = System.currentTimeMillis()[span_178](start_span)[span_178](end_span)
                val currentTask = siblings[currentIndex][span_179](start_span)[span_179](end_span)
                val swapTask = siblings[targetIndex][span_180](start_span)[span_180](end_span)
                dao.updateTask(currentTask.copy(orderIndex = targetIndex, lastModifiedTimestamp = now))[span_181](start_span)[span_181](end_span)
                dao.updateTask(swapTask.copy(orderIndex = currentIndex, lastModifiedTimestamp = now))[span_182](start_span)[span_182](end_span)
            }
        }
    }

    fun indentTask(task: TaskItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val siblings = dao.getSubtasksSnapshot(task.parentId)[span_183](start_span)[span_183](end_span)
            val currentIndex = siblings.indexOfFirst { it.id == task.id }[span_184](start_span)[span_184](end_span)
            if (currentIndex > 0) {
                val newParent = siblings[currentIndex - 1][span_185](start_span)[span_185](end_span)
                val newSiblings = dao.getSubtasksSnapshot(newParent.id)[span_186](start_span)[span_186](end_span)
                val updated = task.copy(
                    parentId = newParent.id,
                    orderIndex = newSiblings.size,
                    lastModifiedTimestamp = System.currentTimeMillis()
                )[span_187](start_span)[span_187](end_span)
                dao.updateTask(updated)[span_188](start_span)[span_188](end_span)
                syncTaskToCalendar(updated)[span_189](start_span)[span_189](end_span)
            }
        }
    }

    fun outdentTask(task: TaskItem) {
        viewModelScope.launch(Dispatchers.IO) {
            if (task.parentId == null) return@launch[span_190](start_span)[span_190](end_span)
            val currentParent = dao.getTaskById(task.parentId)[span_191](start_span)[span_191](end_span)
            val newGrandParentId = currentParent?.parentId[span_192](start_span)[span_192](end_span)
            val newSiblings = dao.getSubtasksSnapshot(newGrandParentId)[span_193](start_span)[span_193](end_span)
            val updated = task.copy(
                parentId = newGrandParentId,
                orderIndex = newSiblings.size,
                lastModifiedTimestamp = System.currentTimeMillis()
            )[span_194](start_span)[span_194](end_span)
            dao.updateTask(updated)[span_195](start_span)[span_195](end_span)
            syncTaskToCalendar(updated)[span_196](start_span)[span_196](end_span)
        }
    }

    fun moveTaskToTarget(task: TaskItem, newParentId: Long?) {
        viewModelScope.launch(Dispatchers.IO) {
            val siblings = dao.getSubtasksSnapshot(newParentId)[span_197](start_span)[span_197](end_span)
            val updated = task.copy(
                parentId = newParentId,
                orderIndex = siblings.size,
                lastModifiedTimestamp = System.currentTimeMillis()
            )[span_198](start_span)[span_198](end_span)
            dao.updateTask(updated)[span_199](start_span)[span_199](end_span)
            syncTaskToCalendar(updated)[span_200](start_span)[span_200](end_span)
        }
    }

    fun copyTaskToTarget(taskId: Long, targetParentId: Long?) {
        viewModelScope.launch(Dispatchers.IO) {
            val original = dao.getTaskById(taskId) ?: return@launch[span_201](start_span)[span_201](end_span)
            deepCopyRecursive(original, targetParentId)[span_202](start_span)[span_202](end_span)
        }
    }

    private suspend fun deepCopyRecursive(task: TaskItem, newParentId: Long?) {
        val siblings = dao.getSubtasksSnapshot(newParentId)[span_203](start_span)[span_203](end_span)
        val copyTitle = "${task.title} (Copy)[span_204](start_span)"[span_204](end_span)
        val copy = task.copy(
            id = 0L,
            parentId = newParentId,
            title = copyTitle,
            calendarEventId = null,
            orderIndex = siblings.size,
            createdTimestamp = System.currentTimeMillis(),
            lastModifiedTimestamp = System.currentTimeMillis()
        )[span_205](start_span)[span_205](end_span)
        val newId = dao.insertTask(copy)[span_206](start_span)[span_206](end_span)
        val createdCopy = copy.copy(id = newId)
        syncTaskToCalendar(createdCopy)
        TaskSchedulerHelper.scheduleAllAlerts(getApplication(), createdCopy)

        val checklists = dao.getChecklistSnapshot(task.id)[span_207](start_span)[span_207](end_span)
        for (item in checklists) {
            dao.insertChecklistItem(item.copy(id = 0L, taskId = newId))[span_208](start_span)[span_208](end_span)
        }

        val attachments = dao.getAttachmentsSnapshot(task.id)[span_209](start_span)[span_209](end_span)
        for (att in attachments) {
            dao.insertAttachment(att.copy(id = 0L, taskId = newId))[span_210](start_span)[span_210](end_span)
        }

        val children = dao.getSubtasksSnapshot(task.id)[span_211](start_span)[span_211](end_span)
        for (child in children) {
            deepCopyRecursive(child, newId)[span_212](start_span)[span_212](end_span)
        }
    }

    fun addChecklistItem(taskId: Long, text: String, notes: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val items = dao.getChecklistSnapshot(taskId)[span_213](start_span)[span_213](end_span)
            val now = System.currentTimeMillis()[span_214](start_span)[span_214](end_span)
            dao.insertChecklistItem(
                ChecklistItem(
                    taskId = taskId,
                    text = text,
                    notes = notes,
                    orderIndex = items.size,
                    createdTimestamp = now,
                    lastModifiedTimestamp = now
                )
            )[span_215](start_span)[span_215](end_span)
            touchTask(taskId)[span_216](start_span)[span_216](end_span)
        }
    }

    fun updateChecklistItem(item: ChecklistItem, text: String, notes: String?, isDone: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()[span_217](start_span)[span_217](end_span)
            dao.updateChecklistItem(
                item.copy(
                    text = text,
                    notes = notes,
                    isDone = isDone,
                    lastModifiedTimestamp = now
                )
            )[span_218](start_span)[span_218](end_span)
            touchTask(item.taskId)[span_219](start_span)[span_219](end_span)
        }
    }

    fun toggleChecklistItem(item: ChecklistItem) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.updateChecklistItem(
                item.copy(
                    isDone = !item.isDone,
                    lastModifiedTimestamp = System.currentTimeMillis()
                )
            )[span_220](start_span)[span_220](end_span)
            touchTask(item.taskId)[span_221](start_span)[span_221](end_span)
        }
    }

    fun deleteChecklistItem(item: ChecklistItem) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteChecklistItem(item)[span_222](start_span)[span_222](end_span)
            touchTask(item.taskId)[span_223](start_span)[span_223](end_span)
        }
    }

    fun moveChecklistItem(item: ChecklistItem, directionUp: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val list = dao.getChecklistSnapshot(item.taskId).toMutableList()[span_224](start_span)[span_224](end_span)
            val index = list.indexOfFirst { it.id == item.id }[span_225](start_span)[span_225](end_span)
            if (index == -1) return@launch[span_226](start_span)[span_226](end_span)
            val targetIndex = if (directionUp) index - 1 else index + 1[span_227](start_span)[span_227](end_span)
            if (targetIndex in list.indices) {
                val now = System.currentTimeMillis()[span_228](start_span)[span_228](end_span)
                dao.updateChecklistItem(list[index].copy(orderIndex = targetIndex, lastModifiedTimestamp = now))[span_229](start_span)[span_229](end_span)
                dao.updateChecklistItem(list[targetIndex].copy(orderIndex = index, lastModifiedTimestamp = now))[span_230](start_span)[span_230](end_span)
                touchTask(item.taskId)[span_231](start_span)[span_231](end_span)
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
            val items = dao.getAttachmentsSnapshot(taskId)[span_232](start_span)[span_232](end_span)
            val now = System.currentTimeMillis()[span_233](start_span)[span_233](end_span)
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
            )[span_234](start_span)[span_234](end_span)
            touchTask(taskId)[span_235](start_span)[span_235](end_span)
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
            val now = System.currentTimeMillis()[span_236](start_span)[span_236](end_span)
            dao.updateAttachment(
                attachment.copy(
                    displayName = displayName,
                    notes = notes,
                    contactPhone = contactPhone,
                    isContactPending = isContactPending,
                    lastModifiedTimestamp = now
                )
            )[span_237](start_span)[span_237](end_span)
            touchTask(attachment.taskId)[span_238](start_span)[span_238](end_span)
        }
    }

    fun deleteAttachment(attachment: RichAttachment) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteAttachment(attachment)[span_239](start_span)[span_239](end_span)
            touchTask(attachment.taskId)[span_240](start_span)[span_240](end_span)
        }
    }

    fun moveAttachment(attachment: RichAttachment, directionUp: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val list = dao.getAttachmentsSnapshot(attachment.taskId).toMutableList()[span_241](start_span)[span_241](end_span)
            val index = list.indexOfFirst { it.id == attachment.id }[span_242](start_span)[span_242](end_span)
            if (index == -1) return@launch[span_243](start_span)[span_243](end_span)
            val targetIndex = if (directionUp) index - 1 else index + 1[span_244](start_span)[span_244](end_span)
            if (targetIndex in list.indices) {
                val now = System.currentTimeMillis()[span_245](start_span)[span_245](end_span)
                dao.updateAttachment(list[index].copy(orderIndex = targetIndex, lastModifiedTimestamp = now))[span_246](start_span)[span_246](end_span)
                dao.updateAttachment(list[targetIndex].copy(orderIndex = index, lastModifiedTimestamp = now))[span_247](start_span)[span_247](end_span)
                touchTask(attachment.taskId)[span_248](start_span)[span_248](end_span)
            }
        }
    }

    private suspend fun touchTask(taskId: Long) {
        dao.getTaskById(taskId)?.let { task ->
            val updated = task.copy(lastModifiedTimestamp = System.currentTimeMillis())[span_249](start_span)[span_249](end_span)
            dao.updateTask(updated)[span_250](start_span)[span_250](end_span)
            syncTaskToCalendar(updated)[span_251](start_span)[span_251](end_span)
        }
    }
}
