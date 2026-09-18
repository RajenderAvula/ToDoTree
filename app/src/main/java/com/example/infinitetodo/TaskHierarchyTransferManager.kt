package com.example.infinitetodo

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// -----------------------------------------------------------------------------------------
// DATA MODELS & ENUMS
// -----------------------------------------------------------------------------------------

enum class TargetHierarchyLevel(val title: String, val subtitle: String) {
    MAIN("Main Task", "Root level (Layer 1)"),
    SUB("Subtask", "Direct child of a Main Task (Layer 2)"),
    SUBSUB("Sub-Subtask", "Child of a Subtask (Layer 3)"),
    DEEPER("Sub-Sub-Subtask+", "Child of a Sub-Subtask (Layer 4+)"),
    ANY("All Levels", "Select any task in tree")
}

enum class CustomTargetRole(val title: String, val description: String) {
    MAIN_TASK("Main Task", "Root level (no parent)"),
    SUB_TASK("Subtask", "Child of a Main Task"),
    SUB_SUB_TASK("Sub-Subtask", "Child of a Subtask"),
    CHILD_OF_ANOTHER_TRANSFERRED("Under Transferred Task", "Child of another task in this batch")
}

data class DescendantNode(
    val task: TaskItem,
    val depthLevel: Int
)

data class TaskHierarchyPlan(
    val task: TaskItem,
    var targetRole: CustomTargetRole = CustomTargetRole.MAIN_TASK,
    var chosenParentId: Long? = null,
    var chosenTransferredParentTaskId: Long? = null
)

// -----------------------------------------------------------------------------------------
// HIERARCHY ENGINE: INTERMEDIATE SKIPPING & CUSTOM TRANSFER LOGIC
// -----------------------------------------------------------------------------------------

object TaskHierarchyTransferEngine {

    suspend fun getTaskDepth(dao: TaskDao, taskId: Long): Int = withContext(Dispatchers.IO) {
        var depth = 0
        var curr = dao.getTaskById(taskId)
        while (curr?.parentId != null) {
            depth++
            curr = dao.getTaskById(curr.parentId!!)
        }
        depth
    }

    suspend fun getAllDescendantsTree(dao: TaskDao, taskId: Long): List<DescendantNode> = withContext(Dispatchers.IO) {
        val result = mutableListOf<DescendantNode>()
        suspend fun recurse(parentId: Long, depth: Int) {
            val children = dao.getSubtasksSnapshot(parentId)
            for (c in children) {
                result.add(DescendantNode(c, depth))
                recurse(c.id, depth + 1)
            }
        }
        recurse(taskId, 1)
        result
    }

    suspend fun getAllPotentialParents(dao: TaskDao, excludeTaskId: Long, isCopy: Boolean): List<TaskItem> = withContext(Dispatchers.IO) {
        val all = dao.getAllTasksSnapshot()
        if (isCopy) {
            all
        } else {
            val descendants = getAllDescendantsTree(dao, excludeTaskId).map { it.task.id }.toSet()
            all.filter { it.id != excludeTaskId && it.id !in descendants }
        }
    }

    /**
     * Finds the closest ancestor of [taskId] that is included in [selectedIds].
     * If all intermediate ancestors are unselected, it points directly to [rootTaskId].
     */
    private suspend fun resolveClosestSelectedAncestor(
        dao: TaskDao,
        taskId: Long,
        rootTaskId: Long,
        selectedIds: Set<Long>
    ): Long {
        var curr = dao.getTaskById(taskId)
        while (curr != null && curr.parentId != null && curr.parentId != rootTaskId) {
            val pId = curr.parentId!!
            if (pId in selectedIds) {
                return pId
            }
            curr = dao.getTaskById(pId)
        }
        return rootTaskId
    }

    /**
     * Copies a task and selectively includes descendants (skipping any intermediate level).
     * Bypassed intermediate tasks are omitted, and deeper descendants attach directly to the nearest selected ancestor.
     */
    suspend fun executeSelectiveTreeCopy(
        dao: TaskDao,
        rootTask: TaskItem,
        targetParentIds: Set<Long?>,
        numberOfCopies: Int,
        copyAllSubtasks: Boolean,
        selectedSubtaskIds: Set<Long>
    ) = withContext(Dispatchers.IO) {
        val allDescendants = getAllDescendantsTree(dao, rootTask.id)
        val activeSelectedIds = if (copyAllSubtasks) allDescendants.map { it.task.id }.toSet() else selectedSubtaskIds

        repeat(numberOfCopies) {
            for (targetParentId in targetParentIds) {
                val now = System.currentTimeMillis()
                val siblings = dao.getSubtasksSnapshot(targetParentId)
                val newRootTitle = if (targetParentId == rootTask.parentId) "${rootTask.title} (Copy)" else rootTask.title

                val rootCopy = rootTask.copy(
                    id = 0L,
                    parentId = targetParentId,
                    title = newRootTitle,
                    calendarEventId = null,
                    orderIndex = siblings.size,
                    createdTimestamp = now,
                    lastModifiedTimestamp = now
                )
                val newRootId = dao.insertTask(rootCopy)

                dao.getChecklistSnapshot(rootTask.id).forEach { dao.insertChecklistItem(it.copy(id = 0L, taskId = newRootId)) }
                dao.getAttachmentsSnapshot(rootTask.id).forEach { dao.insertAttachment(it.copy(id = 0L, taskId = newRootId)) }

                val idMapping = mutableMapOf<Long, Long>()
                idMapping[rootTask.id] = newRootId

                for (descNode in allDescendants) {
                    val originalChild = descNode.task
                    if (originalChild.id in activeSelectedIds) {
                        val closestAncestorOriginalId = resolveClosestSelectedAncestor(
                            dao = dao,
                            taskId = originalChild.id,
                            rootTaskId = rootTask.id,
                            selectedIds = activeSelectedIds
                        )
                        val mappedParentId = idMapping[closestAncestorOriginalId] ?: newRootId
                        val childSiblings = dao.getSubtasksSnapshot(mappedParentId)

                        val childCopy = originalChild.copy(
                            id = 0L,
                            parentId = mappedParentId,
                            orderIndex = childSiblings.size,
                            calendarEventId = null,
                            createdTimestamp = now,
                            lastModifiedTimestamp = now
                        )
                        val newChildId = dao.insertTask(childCopy)
                        idMapping[originalChild.id] = newChildId

                        dao.getChecklistSnapshot(originalChild.id).forEach { dao.insertChecklistItem(it.copy(id = 0L, taskId = newChildId)) }
                        dao.getAttachmentsSnapshot(originalChild.id).forEach { dao.insertAttachment(it.copy(id = 0L, taskId = newChildId)) }
                    }
                }
            }
        }
    }

    /**
     * Moves a task and selectively includes descendants.
     * Intermediate unselected tasks are bypassed: selected sub-sub-subtasks are re-parented directly to the root task.
     */
    suspend fun executeSelectiveTreeMove(
        dao: TaskDao,
        rootTask: TaskItem,
        targetParentIds: Set<Long?>,
        moveAllSubtasks: Boolean,
        selectedSubtaskIds: Set<Long>
    ) = withContext(Dispatchers.IO) {
        val targetParentId = targetParentIds.firstOrNull() ?: return@withContext
        val now = System.currentTimeMillis()
        val siblings = dao.getSubtasksSnapshot(targetParentId)

        val updatedRoot = rootTask.copy(
            parentId = targetParentId,
            orderIndex = siblings.size,
            lastModifiedTimestamp = now
        )
        dao.updateTask(updatedRoot)

        if (!moveAllSubtasks) {
            val allDescendants = getAllDescendantsTree(dao, rootTask.id)

            for (descNode in allDescendants) {
                val child = descNode.task
                if (child.id in selectedSubtaskIds) {
                    val closestAncestorOriginalId = resolveClosestSelectedAncestor(
                        dao = dao,
                        taskId = child.id,
                        rootTaskId = rootTask.id,
                        selectedIds = selectedSubtaskIds
                    )
                    val parentSiblings = dao.getSubtasksSnapshot(closestAncestorOriginalId)
                    dao.updateTask(
                        child.copy(
                            parentId = closestAncestorOriginalId,
                            orderIndex = parentSiblings.size,
                            lastModifiedTimestamp = now
                        )
                    )
                } else {
                    val oldGrandParent = rootTask.parentId
                    val oldSiblings = dao.getSubtasksSnapshot(oldGrandParent)
                    dao.updateTask(
                        child.copy(
                            parentId = oldGrandParent,
                            orderIndex = oldSiblings.size,
                            lastModifiedTimestamp = now
                        )
                    )
                }
            }
        }
    }

    suspend fun executeCustomMultiTransfer(
        dao: TaskDao,
        plans: List<TaskHierarchyPlan>,
        isCopy: Boolean,
        numberOfCopies: Int
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (isCopy) {
            repeat(numberOfCopies) {
                val idMap = mutableMapOf<Long, Long>()
                val independentPlans = plans.filter { it.targetRole != CustomTargetRole.CHILD_OF_ANOTHER_TRANSFERRED }
                val dependentPlans = plans.filter { it.targetRole == CustomTargetRole.CHILD_OF_ANOTHER_TRANSFERRED }

                for (plan in independentPlans) {
                    val targetParentId = when (plan.targetRole) {
                        CustomTargetRole.MAIN_TASK -> null
                        CustomTargetRole.SUB_TASK, CustomTargetRole.SUB_SUB_TASK -> plan.chosenParentId
                        else -> null
                    }
                    val siblings = dao.getSubtasksSnapshot(targetParentId)
                    val copy = plan.task.copy(
                        id = 0L,
                        parentId = targetParentId,
                        title = if (targetParentId == plan.task.parentId) "${plan.task.title} (Copy)" else plan.task.title,
                        orderIndex = siblings.size,
                        calendarEventId = null,
                        createdTimestamp = now,
                        lastModifiedTimestamp = now
                    )
                    val newId = dao.insertTask(copy)
                    idMap[plan.task.id] = newId
                    dao.getChecklistSnapshot(plan.task.id).forEach { dao.insertChecklistItem(it.copy(id = 0L, taskId = newId)) }
                    dao.getAttachmentsSnapshot(plan.task.id).forEach { dao.insertAttachment(it.copy(id = 0L, taskId = newId)) }
                }

                for (plan in dependentPlans) {
                    val resolvedParentId = plan.chosenTransferredParentTaskId?.let { idMap[it] }
                    val siblings = dao.getSubtasksSnapshot(resolvedParentId)
                    val copy = plan.task.copy(
                        id = 0L,
                        parentId = resolvedParentId,
                        title = plan.task.title,
                        orderIndex = siblings.size,
                        calendarEventId = null,
                        createdTimestamp = now,
                        lastModifiedTimestamp = now
                    )
                    val newId = dao.insertTask(copy)
                    idMap[plan.task.id] = newId
                    dao.getChecklistSnapshot(plan.task.id).forEach { dao.insertChecklistItem(it.copy(id = 0L, taskId = newId)) }
                    dao.getAttachmentsSnapshot(plan.task.id).forEach { dao.insertAttachment(it.copy(id = 0L, taskId = newId)) }
                }
            }
        } else {
            val independentPlans = plans.filter { it.targetRole != CustomTargetRole.CHILD_OF_ANOTHER_TRANSFERRED }
            val dependentPlans = plans.filter { it.targetRole == CustomTargetRole.CHILD_OF_ANOTHER_TRANSFERRED }

            for (plan in independentPlans) {
                val targetParentId = when (plan.targetRole) {
                    CustomTargetRole.MAIN_TASK -> null
                    CustomTargetRole.SUB_TASK, CustomTargetRole.SUB_SUB_TASK -> plan.chosenParentId
                    else -> null
                }
                val siblings = dao.getSubtasksSnapshot(targetParentId)
                dao.updateTask(plan.task.copy(parentId = targetParentId, orderIndex = siblings.size, lastModifiedTimestamp = now))
            }

            for (plan in dependentPlans) {
                val targetParentId = plan.chosenTransferredParentTaskId
                val siblings = dao.getSubtasksSnapshot(targetParentId)
                dao.updateTask(plan.task.copy(parentId = targetParentId, orderIndex = siblings.size, lastModifiedTimestamp = now))
            }
        }
    }
}

// -----------------------------------------------------------------------------------------
// UI DIALOG 1: ADVANCED TRANSFER (SUBTASK FILTERING & INTERMEDIATE SKIPPING)
// -----------------------------------------------------------------------------------------

@Composable
fun AdvancedTaskTransferDialog(
    task: TaskItem,
    isCopy: Boolean,
    viewModel: TaskViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { AppDatabase.getDatabase(context).taskDao() }

    var moveOrCopyAll by remember { mutableStateOf(true) }
    var selectedSubtaskIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var allDescendantNodes by remember { mutableStateOf<List<DescendantNode>>(emptyList()) }

    var targetLevel by remember { mutableStateOf(TargetHierarchyLevel.SUB) }
    var numberOfCopies by remember { mutableIntStateOf(1) }
    var potentialParents by remember { mutableStateOf<List<TaskItem>>(emptyList()) }
    var taskDepthsMap by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    var selectedTargetIds by remember { mutableStateOf<Set<Long?>>(emptySet()) }

    LaunchedEffect(task.id, isCopy) {
        scope.launch {
            allDescendantNodes = TaskHierarchyTransferEngine.getAllDescendantsTree(dao, task.id)
            val parents = TaskHierarchyTransferEngine.getAllPotentialParents(dao, task.id, isCopy)
            potentialParents = parents

            val depths = mutableMapOf<Long, Int>()
            for (p in parents) {
                depths[p.id] = TaskHierarchyTransferEngine.getTaskDepth(dao, p.id)
            }
            taskDepthsMap = depths
        }
    }

    val filteredParents = remember(potentialParents, taskDepthsMap, targetLevel) {
        when (targetLevel) {
            TargetHierarchyLevel.MAIN -> emptyList()
            TargetHierarchyLevel.SUB -> potentialParents.filter { (taskDepthsMap[it.id] ?: 0) == 0 }
            TargetHierarchyLevel.SUBSUB -> potentialParents.filter { (taskDepthsMap[it.id] ?: 0) == 1 }
            TargetHierarchyLevel.DEEPER -> potentialParents.filter { (taskDepthsMap[it.id] ?: 0) >= 2 }
            TargetHierarchyLevel.ANY -> potentialParents
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isCopy) Icons.Default.ContentCopy else Icons.Default.DriveFileMove,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (isCopy) "Copy '${task.title.ifBlank { "Task" }}'" else "Move '${task.title.ifBlank { "Task" }}'",
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (allDescendantNodes.isNotEmpty()) {
                    Text("1. Subtask Selective Hierarchy:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = moveOrCopyAll, onClick = { moveOrCopyAll = true })
                        Spacer(Modifier.width(6.dp))
                        Text("Entire Subtree (${allDescendantNodes.size} descendants)")
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = !moveOrCopyAll, onClick = { moveOrCopyAll = false })
                        Spacer(Modifier.width(6.dp))
                        Text("Select specific subtasks (Bypass intermediate levels)")
                    }

                    if (!moveOrCopyAll) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                        ) {
                            LazyColumn(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                items(allDescendantNodes, key = { it.task.id }) { node ->
                                    val isChecked = node.task.id in selectedSubtaskIds
                                    val indentPrefix = "—".repeat(node.depthLevel)
                                    val levelTag = when (node.depthLevel) {
                                        1 -> "[Sub]"
                                        2 -> "[Sub-Sub]"
                                        3 -> "[Sub-Sub-Sub]"
                                        else -> "[Layer ${node.depthLevel + 1}]"
                                    }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                selectedSubtaskIds = if (isChecked) selectedSubtaskIds - node.task.id else selectedSubtaskIds + node.task.id
                                            }
                                            .padding(start = ((node.depthLevel - 1) * 10).dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = isChecked,
                                            onCheckedChange = {
                                                selectedSubtaskIds = if (isChecked) selectedSubtaskIds - node.task.id else selectedSubtaskIds + node.task.id
                                            }
                                        )
                                        Text(
                                            text = "$indentPrefix $levelTag ${node.task.title.ifBlank { "Task #${node.task.id}" }}",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = if (node.depthLevel == 1) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                            }
                        }
                        Text(
                            text = "ℹ Tip: Intermediate unselected tasks will be skipped. Deeper selected tasks will attach directly to this task.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    HorizontalDivider()
                }

                Text("2. Target Hierarchy Level:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TargetHierarchyLevel.values().forEach { level ->
                        FilterChip(
                            selected = targetLevel == level,
                            onClick = {
                                targetLevel = level
                                selectedTargetIds = if (level == TargetHierarchyLevel.MAIN) setOf(null) else emptySet()
                            },
                            label = { Text(level.title) }
                        )
                    }
                }

                if (targetLevel == TargetHierarchyLevel.MAIN) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("Will be placed at Root Level as a Main Task", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                } else {
                    Text(
                        text = "Choose parent for '${targetLevel.title}':",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.labelMedium
                    )

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)
                    ) {
                        if (filteredParents.isEmpty()) {
                            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    text = "No available parent tasks found for ${targetLevel.title}.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        } else {
                            LazyColumn(modifier = Modifier.padding(6.dp)) {
                                items(filteredParents, key = { it.id }) { candidate ->
                                    val isChecked = candidate.id in selectedTargetIds
                                    val candidateDepth = taskDepthsMap[candidate.id] ?: 0
                                    val layerName = when (candidateDepth) {
                                        0 -> "Main Task"
                                        1 -> "Subtask"
                                        2 -> "Sub-Subtask"
                                        else -> "Layer ${candidateDepth + 1}"
                                    }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                selectedTargetIds = if (isCopy) {
                                                    if (isChecked) selectedTargetIds - candidate.id else selectedTargetIds + candidate.id
                                                } else {
                                                    setOf(candidate.id)
                                                }
                                            }
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (isCopy) {
                                            Checkbox(
                                                checked = isChecked,
                                                onCheckedChange = {
                                                    selectedTargetIds = if (isChecked) selectedTargetIds - candidate.id else selectedTargetIds + candidate.id
                                                }
                                            )
                                        } else {
                                            RadioButton(
                                                selected = isChecked,
                                                onClick = { selectedTargetIds = setOf(candidate.id) }
                                            )
                                        }
                                        Spacer(Modifier.width(6.dp))
                                        Column {
                                            Text(candidate.title.ifBlank { "Task #${candidate.id}" }, fontWeight = FontWeight.Medium)
                                            Text("Type: $layerName", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (isCopy) {
                    HorizontalDivider()
                    Text("3. Number of Copies to Make:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalIconButton(
                            onClick = { if (numberOfCopies > 1) numberOfCopies-- },
                            enabled = numberOfCopies > 1
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = "Minus")
                        }

                        Text(
                            text = "$numberOfCopies copy${if (numberOfCopies > 1) "ies" else ""}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        FilledTonalIconButton(onClick = { numberOfCopies++ }) {
                            Icon(Icons.Default.Add, contentDescription = "Add")
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = selectedTargetIds.isNotEmpty() || targetLevel == TargetHierarchyLevel.MAIN,
                onClick = {
                    val targets = if (targetLevel == TargetHierarchyLevel.MAIN) setOf(null) else selectedTargetIds
                    scope.launch {
                        if (isCopy) {
                            TaskHierarchyTransferEngine.executeSelectiveTreeCopy(
                                dao = dao,
                                rootTask = task,
                                targetParentIds = targets,
                                numberOfCopies = numberOfCopies,
                                copyAllSubtasks = moveOrCopyAll,
                                selectedSubtaskIds = selectedSubtaskIds
                            )
                            Toast.makeText(context, "Copied as ${targetLevel.title} successfully ✓", Toast.LENGTH_SHORT).show()
                        } else {
                            TaskHierarchyTransferEngine.executeSelectiveTreeMove(
                                dao = dao,
                                rootTask = task,
                                targetParentIds = targets,
                                moveAllSubtasks = moveOrCopyAll,
                                selectedSubtaskIds = selectedSubtaskIds
                            )
                            Toast.makeText(context, "Moved as ${targetLevel.title} successfully ✓", Toast.LENGTH_SHORT).show()
                        }
                        onDismiss()
                    }
                }
            ) {
                Text(if (isCopy) "Confirm Copy (${numberOfCopies}x)" else "Confirm Move")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// -----------------------------------------------------------------------------------------
// UI DIALOG 2: CUSTOM MULTI-TASK HIERARCHY TRANSFER (EACH TASK ROUTED INDEPENDENTLY)
// -----------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomHierarchyTransferDialog(
    tasksToTransfer: List<TaskItem>,
    isCopy: Boolean,
    viewModel: TaskViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { AppDatabase.getDatabase(context).taskDao() }
    val allTasks by viewModel.allTasksFlow.collectAsState(initial = emptyList())
    var numberOfCopies by remember { mutableIntStateOf(1) }
    var taskDepthsMap by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }

    LaunchedEffect(allTasks) {
        scope.launch {
            val depths = mutableMapOf<Long, Int>()
            for (t in allTasks) {
                depths[t.id] = TaskHierarchyTransferEngine.getTaskDepth(dao, t.id)
            }
            taskDepthsMap = depths
        }
    }

    val transferredIds = remember(tasksToTransfer) { tasksToTransfer.map { it.id }.toSet() }

    val availableMainTasks = remember(allTasks, taskDepthsMap, transferredIds) {
        allTasks.filter { it.id !in transferredIds && (taskDepthsMap[it.id] ?: 0) == 0 }
    }
    val availableSubTasks = remember(allTasks, taskDepthsMap, transferredIds) {
        allTasks.filter { it.id !in transferredIds && (taskDepthsMap[it.id] ?: 0) == 1 }
    }

    val transferPlans = remember(tasksToTransfer) {
        mutableStateListOf<TaskHierarchyPlan>().apply {
            addAll(tasksToTransfer.map { TaskHierarchyPlan(task = it) })
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isCopy) Icons.Default.ContentCopy else Icons.Default.DriveFileMove,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (isCopy) "Custom Hierarchy Copy (${tasksToTransfer.size})" else "Custom Hierarchy Move (${tasksToTransfer.size})",
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AssistChip(
                        onClick = {
                            transferPlans.forEachIndexed { i, plan ->
                                transferPlans[i] = plan.copy(targetRole = CustomTargetRole.MAIN_TASK, chosenParentId = null)
                            }
                        },
                        label = { Text("All as Main Tasks") }
                    )
                    AssistChip(
                        onClick = {
                            val defaultMain = availableMainTasks.firstOrNull()?.id
                            transferPlans.forEachIndexed { i, plan ->
                                transferPlans[i] = plan.copy(targetRole = CustomTargetRole.SUB_TASK, chosenParentId = defaultMain)
                            }
                        },
                        label = { Text("All as Subtasks") }
                    )
                }

                HorizontalDivider()

                transferPlans.forEachIndexed { index, plan ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "${index + 1}. ${plan.task.title.ifBlank { "Task #${plan.task.id}" }}",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )

                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                CustomTargetRole.values().forEach { role ->
                                    if (role == CustomTargetRole.CHILD_OF_ANOTHER_TRANSFERRED && tasksToTransfer.size <= 1) return@forEach

                                    FilterChip(
                                        selected = plan.targetRole == role,
                                        onClick = {
                                            val defaultParent = when (role) {
                                                CustomTargetRole.MAIN_TASK -> null
                                                CustomTargetRole.SUB_TASK -> availableMainTasks.firstOrNull()?.id
                                                CustomTargetRole.SUB_SUB_TASK -> availableSubTasks.firstOrNull()?.id
                                                CustomTargetRole.CHILD_OF_ANOTHER_TRANSFERRED -> tasksToTransfer.firstOrNull { it.id != plan.task.id }?.id
                                            }
                                            transferPlans[index] = plan.copy(
                                                targetRole = role,
                                                chosenParentId = if (role != CustomTargetRole.CHILD_OF_ANOTHER_TRANSFERRED) defaultParent else null,
                                                chosenTransferredParentTaskId = if (role == CustomTargetRole.CHILD_OF_ANOTHER_TRANSFERRED) defaultParent else null
                                            )
                                        },
                                        label = { Text(role.title) }
                                    )
                                }
                            }

                            when (plan.targetRole) {
                                CustomTargetRole.MAIN_TASK -> {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "✓ Root Level Main Task (No Parent)",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(8.dp)
                                        )
                                    }
                                }
                                CustomTargetRole.SUB_TASK -> {
                                    ParentDropdownPicker(
                                        label = "Parent Main Task:",
                                        candidates = availableMainTasks,
                                        selectedId = plan.chosenParentId,
                                        onSelect = { transferPlans[index] = plan.copy(chosenParentId = it) }
                                    )
                                }
                                CustomTargetRole.SUB_SUB_TASK -> {
                                    ParentDropdownPicker(
                                        label = "Parent Subtask:",
                                        candidates = availableSubTasks,
                                        selectedId = plan.chosenParentId,
                                        onSelect = { transferPlans[index] = plan.copy(chosenParentId = it) }
                                    )
                                }
                                CustomTargetRole.CHILD_OF_ANOTHER_TRANSFERRED -> {
                                    val otherTransferred = tasksToTransfer.filter { it.id != plan.task.id }
                                    ParentDropdownPicker(
                                        label = "Nest under transferred task:",
                                        candidates = otherTransferred,
                                        selectedId = plan.chosenTransferredParentTaskId,
                                        onSelect = { transferPlans[index] = plan.copy(chosenTransferredParentTaskId = it) }
                                    )
                                }
                            }
                        }
                    }
                }

                if (isCopy) {
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Number of copies:", fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilledTonalIconButton(
                                onClick = { if (numberOfCopies > 1) numberOfCopies-- },
                                enabled = numberOfCopies > 1
                            ) { Icon(Icons.Default.Remove, contentDescription = null) }
                            Text("$numberOfCopies", fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp))
                            FilledTonalIconButton(onClick = { numberOfCopies++ }) {
                                Icon(Icons.Default.Add, contentDescription = null)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch {
                        TaskHierarchyTransferEngine.executeCustomMultiTransfer(
                            dao = dao,
                            plans = transferPlans,
                            isCopy = isCopy,
                            numberOfCopies = numberOfCopies
                        )
                        Toast.makeText(context, if (isCopy) "Tasks copied successfully ✓" else "Tasks moved successfully ✓", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }
                }
            ) {
                Text(if (isCopy) "Confirm Copy (${numberOfCopies}x)" else "Confirm Move")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun ParentDropdownPicker(
    label: String,
    candidates: List<TaskItem>,
    selectedId: Long?,
    onSelect: (Long) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedTask = candidates.find { it.id == selectedId }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = selectedTask?.title?.ifBlank { "Task #${selectedTask.id}" } ?: "Select parent task...",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.heightIn(max = 240.dp)
            ) {
                if (candidates.isEmpty()) {
                    DropdownMenuItem(text = { Text("No matching tasks available") }, onClick = { expanded = false })
                } else {
                    candidates.forEach { candidate ->
                        DropdownMenuItem(
                            text = { Text(candidate.title.ifBlank { "Task #${candidate.id}" }) },
                            onClick = {
                                onSelect(candidate.id)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------------------
// UI DIALOG 3: ROLE REVERSAL / HIERARCHICAL SWAP DIALOG
// -----------------------------------------------------------------------------------------

@Composable
fun SwapTaskRoleDialog(
    taskA: TaskItem,
    viewModel: TaskViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { AppDatabase.getDatabase(context).taskDao() }
    val allTasks by viewModel.allTasksFlow.collectAsState(initial = emptyList())
    var searchQuery by remember { mutableStateOf("") }
    var selectedTaskC by remember { mutableStateOf<TaskItem?>(null) }
    var nestUnder by remember { mutableStateOf(true) }

    val eligibleTasks = remember(allTasks, taskA.id, searchQuery) {
        allTasks.filter { other ->
            other.id != taskA.id &&
            (searchQuery.isBlank() || other.title.contains(searchQuery, ignoreCase = true))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SwapVert, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Reverse Roles / Swap Hierarchy", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("Current Task (A):", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text(taskA.title.ifBlank { "Task #${taskA.id}" }, fontWeight = FontWeight.Bold)
                    }
                }

                Text("Reversal Mode:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { nestUnder = true },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = nestUnder, onClick = { nestUnder = true })
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text("Promote C as Subtask, nest A under C", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                            Text("C takes A's parent; A becomes Sub-subtask under C", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { nestUnder = false },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = !nestUnder, onClick = { nestUnder = false })
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text("Direct Slot Swap", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                            Text("C moves to A's branch; A moves to C's branch", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }

                HorizontalDivider()

                Text("Select Target Task (C):", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search task to swap with...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                ) {
                    LazyColumn(modifier = Modifier.padding(4.dp)) {
                        items(eligibleTasks, key = { it.id }) { candidate ->
                            val isSelected = candidate.id == selectedTaskC?.id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedTaskC = candidate }
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = isSelected, onClick = { selectedTaskC = candidate })
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(candidate.title.ifBlank { "Task #${candidate.id}" }, fontWeight = FontWeight.Medium)
                                    Text(
                                        text = if (candidate.parentId == null) "Main Task" else "Subtask / Lower Task",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = selectedTaskC != null,
                onClick = {
                    selectedTaskC?.let { taskC ->
                        scope.launch(Dispatchers.IO) {
                            val now = System.currentTimeMillis()
                            if (nestUnder) {
                                val updatedC = taskC.copy(parentId = taskA.parentId, orderIndex = taskA.orderIndex, lastModifiedTimestamp = now)
                                dao.updateTask(updatedC)
                                val subtasksOfC = dao.getSubtasksSnapshot(taskC.id)
                                val updatedA = taskA.copy(parentId = taskC.id, orderIndex = subtasksOfC.size, lastModifiedTimestamp = now)
                                dao.updateTask(updatedA)
                            } else {
                                val updatedC = taskC.copy(parentId = taskA.parentId, orderIndex = taskA.orderIndex, lastModifiedTimestamp = now)
                                val updatedA = taskA.copy(parentId = taskC.parentId, orderIndex = taskC.orderIndex, lastModifiedTimestamp = now)
                                dao.updateTask(updatedC)
                                dao.updateTask(updatedA)
                            }
                            withContext(Dispatchers.Main) { onDismiss() }
                        }
                    }
                }
            ) {
                Text("Confirm Swap")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
