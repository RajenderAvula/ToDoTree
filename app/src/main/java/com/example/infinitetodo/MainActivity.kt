package com.example.infinitetodo

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            var currentTheme by remember { mutableStateOf(ThemePreferences.getTheme(context)) }
            var currentViewMode by remember { mutableStateOf(ThemePreferences.getViewMode(context)) }

            InfiniteTodoTheme(
                themeMode = currentTheme,
                isDarkSystem = isSystemInDarkTheme()
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val taskViewModel: TaskViewModel = viewModel()
                    InfiniteTodoApp(
                        viewModel = taskViewModel,
                        currentTheme = currentTheme,
                        onThemeChange = {
                            currentTheme = it
                            ThemePreferences.saveTheme(context, it)
                        },
                        viewMode = currentViewMode,
                        onViewModeChange = {
                            currentViewMode = it
                            ThemePreferences.saveViewMode(context, it)
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfiniteTodoApp(
    viewModel: TaskViewModel,
    currentTheme: AppThemeMode,
    onThemeChange: (AppThemeMode) -> Unit,
    viewMode: TaskViewMode,
    onViewModeChange: (TaskViewMode) -> Unit
) {
    val rootTasks by viewModel.rootTasks.collectAsState(initial = emptyList())
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }

    val searchResults by viewModel.searchTasks(searchQuery).collectAsState(initial = emptyList())

    // State controlling the Full-Screen Editor/Creator
    var activeFullScreenTask by remember { mutableStateOf<TaskItem?>(null) }
    var isCreatingFullScreenTask by remember { mutableStateOf(false) }
    var fullScreenParentId by remember { mutableStateOf<Long?>(null) }

    var showMenu by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }

    var taskForTargetMove by remember { mutableStateOf<TaskItem?>(null) }
    var taskForTargetCopy by remember { mutableStateOf<TaskItem?>(null) }

    val context = LocalContext.current

    val createBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        uri?.let { destUri ->
            context.contentResolver.openOutputStream(destUri)?.use { outStream ->
                viewModel.backupToDevice(outStream) { success ->
                    Toast.makeText(
                        context,
                        if (success) "Backup saved successfully!" else "Backup failed",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    val restoreBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { sourceUri ->
            context.contentResolver.openInputStream(sourceUri)?.use { inStream ->
                viewModel.restoreBackup(inStream) { success ->
                    Toast.makeText(
                        context,
                        if (success) "Backup restored successfully!" else "Restore failed",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    val permissionsToRequest = remember {
        val list = mutableListOf(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        list.toTypedArray()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(permissionsToRequest)
    }

    Scaffold(
        topBar = {
            if (isSearchActive) {
                TopAppBar(
                    title = {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search tasks, checklists, notes...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            isSearchActive = false
                            searchQuery = ""
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Close Search")
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text("Hierarchical Infinite Tasks") },
                    actions = {
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Search Tasks")
                        }

                        IconButton(onClick = {
                            val nextMode = if (viewMode == TaskViewMode.DETAILED) TaskViewMode.COMPACT else TaskViewMode.DETAILED
                            onViewModeChange(nextMode)
                        }) {
                            Icon(
                                imageVector = if (viewMode == TaskViewMode.DETAILED) Icons.Default.ViewAgenda else Icons.Default.ViewHeadline,
                                contentDescription = "Switch View Style"
                            )
                        }

                        IconButton(onClick = { showThemeDialog = true }) {
                            Icon(Icons.Default.Palette, contentDescription = "Themes")
                        }

                        IconButton(onClick = { showMenu = !showMenu }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Sync All to Google Calendar") },
                                leadingIcon = { Icon(Icons.Default.Sync, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    viewModel.syncAllTasksToCalendar { count ->
                                        val msg = if (count >= 0) "Synced $count task(s) to Calendar" else "Calendar permission required"
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Backup to Device (ZIP)") },
                                leadingIcon = { Icon(Icons.Default.Save, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    createBackupLauncher.launch("ToDoTree_Backup_${System.currentTimeMillis()}.zip")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Backup & Send via Email") },
                                leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    viewModel.sendBackupViaMail { intent ->
                                        if (intent != null) {
                                            context.startActivity(Intent.createChooser(intent, "Send Backup via Email"))
                                        } else {
                                            Toast.makeText(context, "Failed to create email backup", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Restore from Device / Mail") },
                                leadingIcon = { Icon(Icons.Default.Restore, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    restoreBackupLauncher.launch(arrayOf("application/zip", "*/*"))
                                }
                            )
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                fullScreenParentId = null
                activeFullScreenTask = null
                isCreatingFullScreenTask = true
            }) {
                Icon(Icons.Default.Add, contentDescription = "Add Root Task")
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 6.dp, vertical = 4.dp)
        ) {
            if (isSearchActive && searchQuery.isNotBlank()) {
                item {
                    Text(
                        "Found ${searchResults.size} matching task(s):",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
                    )
                }
                items(searchResults, key = { it.id }) { task ->
                    TaskNodeView(
                        task = task,
                        depth = 0,
                        viewMode = viewMode,
                        viewModel = viewModel,
                        onAddSubtask = { parentId ->
                            fullScreenParentId = parentId
                            activeFullScreenTask = null
                            isCreatingFullScreenTask = true
                        },
                        onOpenFullScreen = { activeFullScreenTask = it },
                        onMoveToTarget = { taskForTargetMove = it },
                        onCopyToTarget = { taskForTargetCopy = it }
                    )
                }
            } else {
                items(rootTasks, key = { it.id }) { rootTask ->
                    TaskNodeView(
                        task = rootTask,
                        depth = 0,
                        viewMode = viewMode,
                        viewModel = viewModel,
                        onAddSubtask = { parentId ->
                            fullScreenParentId = parentId
                            activeFullScreenTask = null
                            isCreatingFullScreenTask = true
                        },
                        onOpenFullScreen = { activeFullScreenTask = it },
                        onMoveToTarget = { taskForTargetMove = it },
                        onCopyToTarget = { taskForTargetCopy = it }
                    )
                }
            }
        }

        // FULL SCREEN WORKSPACE VIEW (For creating new tasks or editing existing tasks)
        if (isCreatingFullScreenTask) {
            FullScreenTaskEditor(
                isNewTask = true,
                existingTask = null,
                parentId = fullScreenParentId,
                viewModel = viewModel,
                onDismiss = {
                    isCreatingFullScreenTask = false
                    fullScreenParentId = null
                }
            )
        }

        activeFullScreenTask?.let { taskToEdit ->
            FullScreenTaskEditor(
                isNewTask = false,
                existingTask = taskToEdit,
                parentId = taskToEdit.parentId,
                viewModel = viewModel,
                onDismiss = { activeFullScreenTask = null }
            )
        }

        // Target Destination Move Dialog
        taskForTargetMove?.let { movingTask ->
            TaskDestinationDialog(
                title = "Move '${movingTask.title}' to...",
                currentTaskId = movingTask.id,
                viewModel = viewModel,
                onDismiss = { taskForTargetMove = null },
                onSelectTarget = { targetParentId ->
                    viewModel.moveTaskToTarget(movingTask, targetParentId)
                    taskForTargetMove = null
                    Toast.makeText(context, "Task moved successfully", Toast.LENGTH_SHORT).show()
                }
            )
        }

        // Target Destination Copy Dialog
        taskForTargetCopy?.let { copyingTask ->
            TaskDestinationDialog(
                title = "Copy '${copyingTask.title}' to...",
                currentTaskId = copyingTask.id,
                viewModel = viewModel,
                onDismiss = { taskForTargetCopy = null },
                onSelectTarget = { targetParentId ->
                    viewModel.copyTaskToTarget(copyingTask.id, targetParentId)
                    taskForTargetCopy = null
                    Toast.makeText(context, "Task copied successfully", Toast.LENGTH_SHORT).show()
                }
            )
        }

        if (showThemeDialog) {
            AlertDialog(
                onDismissRequest = { showThemeDialog = false },
                title = { Text("Select App Theme") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppThemeMode.values().forEach { mode ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        onThemeChange(mode)
                                        showThemeDialog = false
                                    }
                                    .padding(vertical = 8.dp, horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = (currentTheme == mode),
                                    onClick = {
                                        onThemeChange(mode)
                                        showThemeDialog = false
                                    }
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = when (mode) {
                                        AppThemeMode.SYSTEM -> "System Default"
                                        AppThemeMode.LIGHT -> "Standard Light"
                                        AppThemeMode.DARK -> "Midnight Dark"
                                        AppThemeMode.EMERALD -> "Emerald Green"
                                        AppThemeMode.SUNSET -> "Sunset Orange"
                                        AppThemeMode.OCEAN -> "Ocean Blue"
                                    },
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showThemeDialog = false }) { Text("Close") }
                }
            )
        }
    }
}

@Composable
fun TaskNodeView(
    task: TaskItem,
    depth: Int,
    viewMode: TaskViewMode,
    viewModel: TaskViewModel,
    onAddSubtask: (Long) -> Unit,
    onOpenFullScreen: (TaskItem) -> Unit,
    onMoveToTarget: (TaskItem) -> Unit,
    onCopyToTarget: (TaskItem) -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }
    var isPlayingVoice by remember { mutableStateOf(false) }

    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val animatedOffsetX by animateFloatAsState(targetValue = offsetX, label = "slideX")

    val subtasks by viewModel.getSubtasks(task.id).collectAsState(initial = emptyList())
    val checklist by viewModel.getChecklist(task.id).collectAsState(initial = emptyList())
    val attachments by viewModel.getAttachments(task.id).collectAsState(initial = emptyList())

    val context = LocalContext.current
    val audioHelper = remember { AudioRecorderHelper(context) }
    val dateFormat = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 14).dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            if (offsetX > 60f) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.tertiaryContainer)
                        .padding(start = 16.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text("↳ Slide Right: Make Subtask", style = MaterialTheme.typography.labelMedium)
                }
            } else if (offsetX < -60f) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(end = 16.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Text("↰ Slide Left: Promote to Main Task", style = MaterialTheme.typography.labelMedium)
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(animatedOffsetX.roundToInt(), 0) }
                    .padding(vertical = if (viewMode == TaskViewMode.COMPACT) 1.dp else 3.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = if (viewMode == TaskViewMode.COMPACT) 1.dp else 2.dp)
            ) {
                Column(modifier = Modifier.padding(if (viewMode == TaskViewMode.COMPACT) 2.dp else 6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.DragIndicator,
                            contentDescription = "Slide to Move",
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier
                                .size(24.dp)
                                .pointerInput(task.id) {
                                    detectDragGestures(
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            offsetX += dragAmount.x
                                            offsetY += dragAmount.y

                                            if (offsetY > 40f) {
                                                viewModel.moveTaskVertical(task, directionUp = false)
                                                offsetY = 0f
                                            } else if (offsetY < -40f) {
                                                viewModel.moveTaskVertical(task, directionUp = true)
                                                offsetY = 0f
                                            }
                                        },
                                        onDragEnd = {
                                            if (offsetX > 120f) {
                                                viewModel.indentTask(task)
                                            } else if (offsetX < -120f) {
                                                viewModel.outdentTask(task)
                                            }
                                            offsetX = 0f
                                            offsetY = 0f
                                        },
                                        onDragCancel = {
                                            offsetX = 0f
                                            offsetY = 0f
                                        }
                                    )
                                }
                        )

                        // Reorder Arrows
                        IconButton(modifier = Modifier.size(22.dp), onClick = { viewModel.moveTaskVertical(task, directionUp = true) }) {
                            Icon(Icons.Default.ArrowDropUp, contentDescription = "Move Up", modifier = Modifier.size(20.dp))
                        }
                        IconButton(modifier = Modifier.size(22.dp), onClick = { viewModel.moveTaskVertical(task, directionUp = false) }) {
                            Icon(Icons.Default.ArrowDropDown, contentDescription = "Move Down", modifier = Modifier.size(20.dp))
                        }

                        // Hierarchy Adjustments
                        if (task.parentId != null) {
                            IconButton(modifier = Modifier.size(22.dp), onClick = { viewModel.outdentTask(task) }) {
                                Icon(Icons.Default.KeyboardDoubleArrowLeft, contentDescription = "Promote to Main Task", tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
                            }
                        }
                        IconButton(modifier = Modifier.size(22.dp), onClick = { viewModel.indentTask(task) }) {
                            Icon(Icons.Default.KeyboardDoubleArrowRight, contentDescription = "Make Subtask", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
                        }

                        IconButton(
                            modifier = Modifier.size(24.dp),
                            onClick = { isExpanded = !isExpanded }
                        ) {
                            Icon(
                                imageVector = if (isExpanded) Icons.Default.ArrowDropDown else Icons.AutoMirrored.Filled.ArrowRight,
                                contentDescription = "Expand/Collapse"
                            )
                        }

                        Checkbox(
                            checked = task.isCompleted,
                            onCheckedChange = { viewModel.toggleTaskCompletion(task) }
                        )

                        Text(
                            text = task.title,
                            style = if (viewMode == TaskViewMode.COMPACT) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                            textDecoration = if (task.isCompleted) TextDecoration.LineThrough else null,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onOpenFullScreen(task) } // Clicking opens Full-Screen Workspace
                        )

                        // Full Screen Workspace Trigger Button
                        IconButton(
                            modifier = Modifier.size(26.dp),
                            onClick = { onOpenFullScreen(task) }
                        ) {
                            Icon(
                                imageVector = Icons.Default.OpenInFull,
                                contentDescription = "Open Full Screen",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(17.dp)
                            )
                        }

                        if (viewMode == TaskViewMode.COMPACT) {
                            IconButton(modifier = Modifier.size(24.dp), onClick = {
                                viewModel.manualSyncTaskToCalendar(task.id) { feedback ->
                                    Toast.makeText(context, feedback, Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Icon(Icons.Default.Sync, contentDescription = "Sync to Calendar", modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(modifier = Modifier.size(24.dp), onClick = { onAddSubtask(task.id) }) {
                                Icon(Icons.Default.SubdirectoryArrowRight, contentDescription = "Add Subtask", modifier = Modifier.size(15.dp))
                            }
                            IconButton(modifier = Modifier.size(24.dp), onClick = { viewModel.deleteTask(task) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(15.dp))
                            }
                        }
                    }

                    // Notes snippet
                    if (!task.notes.isNullOrBlank()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 36.dp, end = 8.dp, top = 2.dp)
                                .clickable { onOpenFullScreen(task) }
                        ) {
                            Text(
                                text = "📝 ${task.notes}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Compact preview of checklist counts & attachments
                    if (checklist.isNotEmpty() || attachments.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 36.dp, top = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (checklist.isNotEmpty()) {
                                val doneCount = checklist.count { it.isDone }
                                Text(
                                    text = "☑ $doneCount/${checklist.size} items",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            if (attachments.isNotEmpty()) {
                                Text(
                                    text = "📎 ${attachments.size} file(s)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }

                    if (viewMode == TaskViewMode.DETAILED) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 32.dp, top = 4.dp, bottom = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Created: ${dateFormat.format(Date(task.createdTimestamp))}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Text("•", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            Text(
                                text = "Modified: ${dateFormat.format(Date(task.lastModifiedTimestamp))}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }

                        // Voice Memo Player
                        task.voiceRecordingPath?.let { path ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 32.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = {
                                        if (isPlayingVoice) {
                                            audioHelper.stopPlayback()
                                            isPlayingVoice = false
                                        } else {
                                            isPlayingVoice = true
                                            audioHelper.playAudio(path) { isPlayingVoice = false }
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Icon(
                                        if (isPlayingVoice) Icons.Default.Stop else Icons.Default.PlayArrow,
                                        contentDescription = "Play Audio",
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (isPlayingVoice) "Stop" else "Voice Memo", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }

                        // Detailed Action toolbar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 28.dp, top = 4.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(modifier = Modifier.size(28.dp), onClick = {
                                viewModel.manualSyncTaskToCalendar(task.id) { feedback ->
                                    Toast.makeText(context, feedback, Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Icon(Icons.Default.Sync, contentDescription = "Sync to Calendar", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            }
                            IconButton(modifier = Modifier.size(28.dp), onClick = { onMoveToTarget(task) }) {
                                Icon(Icons.Default.DriveFileMove, contentDescription = "Move to...", modifier = Modifier.size(17.dp))
                            }
                            IconButton(modifier = Modifier.size(28.dp), onClick = { onCopyToTarget(task) }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy to...", modifier = Modifier.size(17.dp))
                            }
                            IconButton(modifier = Modifier.size(28.dp), onClick = { onOpenFullScreen(task) }) {
                                Icon(Icons.Default.Edit, contentDescription = "Open Full View", modifier = Modifier.size(17.dp))
                            }
                            IconButton(modifier = Modifier.size(28.dp), onClick = { onAddSubtask(task.id) }) {
                                Icon(Icons.Default.SubdirectoryArrowRight, contentDescription = "Add Subtask", modifier = Modifier.size(17.dp))
                            }
                            IconButton(modifier = Modifier.size(28.dp), onClick = { viewModel.deleteTask(task) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(17.dp))
                            }
                        }
                    }
                }
            }
        }

        if (isExpanded) {
            subtasks.forEach { subtask ->
                TaskNodeView(
                    task = subtask,
                    depth = depth + 1,
                    viewMode = viewMode,
                    viewModel = viewModel,
                    onAddSubtask = onAddSubtask,
                    onOpenFullScreen = onOpenFullScreen,
                    onMoveToTarget = onMoveToTarget,
                    onCopyToTarget = onCopyToTarget
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------------------
// FULL SCREEN WORKSPACE VIEW (FOR CREATING NEW TASKS, EDITING, CHECKLISTS & ATTACHMENTS)
// -----------------------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenTaskEditor(
    isNewTask: Boolean,
    existingTask: TaskItem?,
    parentId: Long?,
    viewModel: TaskViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val audioHelper = remember { AudioRecorderHelper(context) }

    var title by remember { mutableStateOf(existingTask?.title ?: "") }
    var notes by remember { mutableStateOf(existingTask?.notes ?: "") }
    var reminderMs by remember { mutableStateOf(existingTask?.reminderTimestamp) }
    var contactName by remember { mutableStateOf(existingTask?.contactName) }
    var contactPhone by remember { mutableStateOf(existingTask?.contactPhone) }
    var contactEmail by remember { mutableStateOf(existingTask?.contactEmail) }
    var voicePath by remember { mutableStateOf(existingTask?.voiceRecordingPath) }
    var syncCalendar by remember { mutableStateOf(true) }

    var isRecording by remember { mutableStateOf(false) }
    var isPlayingVoice by remember { mutableStateOf(false) }

    var newChecklistText by remember { mutableStateOf("") }

    // Live Flow observations for checklists and attachments (for existing tasks)
    val taskId = existingTask?.id ?: 0L
    val liveChecklist by viewModel.getChecklist(taskId).collectAsState(initial = emptyList())
    val liveAttachments by viewModel.getAttachments(taskId).collectAsState(initial = emptyList())

    // SAF file picker for full-screen view
    val attachmentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            var fileName = "Document"
            context.contentResolver.query(it, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex != -1) {
                    fileName = cursor.getString(nameIndex)
                }
            }
            if (existingTask != null) {
                viewModel.addAttachment(existingTask.id, it, fileName)
            }
        }
    }

    // Contact Picker
    val contactPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickContact()
    ) { contactUri: Uri? ->
        contactUri?.let { uri ->
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idCol = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                    val nameCol = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                    val hasPhoneCol = cursor.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)

                    val contactId = cursor.getString(idCol)
                    contactName = cursor.getString(nameCol)

                    if (cursor.getInt(hasPhoneCol) > 0) {
                        context.contentResolver.query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            null,
                            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                            arrayOf(contactId),
                            null
                        )?.use { phoneCursor ->
                            if (phoneCursor.moveToFirst()) {
                                val numberCol = phoneCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                                contactPhone = phoneCursor.getString(numberCol)
                            }
                        }
                    }

                    context.contentResolver.query(
                        ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                        null,
                        "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
                        arrayOf(contactId),
                        null
                    )?.use { emailCursor ->
                        if (emailCursor.moveToFirst()) {
                            val emailCol = emailCursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
                            contactEmail = emailCursor.getString(emailCol)
                        }
                    }
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false) // True Full Screen
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = if (isNewTask) {
                                if (parentId == null) "New Main Task" else "New Subtask"
                            } else {
                                "Edit Task Details"
                            }
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close Full Screen")
                        }
                    },
                    actions = {
                        Button(
                            enabled = title.isNotBlank(),
                            onClick = {
                                if (isRecording) {
                                    voicePath = audioHelper.stopRecording()
                                }
                                if (isNewTask) {
                                    viewModel.addTask(title, notes, parentId, reminderMs, syncCalendar, contactName, contactPhone, contactEmail, voicePath)
                                } else {
                                    viewModel.updateTask(existingTask!!, title, notes, reminderMs, syncCalendar, contactName, contactPhone, contactEmail, voicePath)
                                }
                                onDismiss()
                            }
                        ) {
                            Text(if (isNewTask) "Create" else "Save")
                        }
                    }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Task Title
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Task Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Large Multi-line Expandable Notes Workspace
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Task Notes & Instructions") },
                    placeholder = { Text("Write detailed instructions, notes, markdown or logs here...") },
                    minLines = 4,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth()
                )

                HorizontalDivider()

                // Date & Time Picker
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            val calendar = Calendar.getInstance()
                            DatePickerDialog(
                                context,
                                { _, year, month, day ->
                                    TimePickerDialog(
                                        context,
                                        { _, hour, minute ->
                                            calendar.set(year, month, day, hour, minute)
                                            reminderMs = calendar.timeInMillis
                                        },
                                        calendar.get(Calendar.HOUR_OF_DAY),
                                        calendar.get(Calendar.MINUTE),
                                        true
                                    ).show()
                                },
                                calendar.get(Calendar.YEAR),
                                calendar.get(Calendar.MONTH),
                                calendar.get(Calendar.DAY_OF_MONTH)
                            ).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Alarm, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = reminderMs?.let {
                                SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(it))
                            } ?: "Pick Reminder Date & Time"
                        )
                    }

                    if (reminderMs != null) {
                        IconButton(onClick = { reminderMs = null }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear date", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                // Voice Memo Recording & Playback Section
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Voice Memo", style = MaterialTheme.typography.titleSmall)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = {
                                    if (isRecording) {
                                        voicePath = audioHelper.stopRecording()
                                        isRecording = false
                                    } else {
                                        voicePath = audioHelper.startRecording()
                                        isRecording = true
                                    }
                                },
                                colors = if (isRecording) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors()
                            ) {
                                Icon(if (isRecording) Icons.Default.Stop else Icons.Default.Mic, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(if (isRecording) "Stop Recording" else "Record Voice Memo")
                            }

                            voicePath?.let { path ->
                                OutlinedButton(
                                    onClick = {
                                        if (isPlayingVoice) {
                                            audioHelper.stopPlayback()
                                            isPlayingVoice = false
                                        } else {
                                            isPlayingVoice = true
                                            audioHelper.playAudio(path) { isPlayingVoice = false }
                                        }
                                    }
                                ) {
                                    Icon(if (isPlayingVoice) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (isPlayingVoice) "Stop" else "Play")
                                }
                            }
                        }
                    }
                }

                // Attached Contact Section
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Linked Contact", style = MaterialTheme.typography.titleSmall)
                            OutlinedButton(onClick = { contactPickerLauncher.launch(null) }) {
                                Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Select Contact")
                            }
                        }

                        if (!contactName.isNullOrBlank()) {
                            Text(text = "Name: $contactName", style = MaterialTheme.typography.bodyMedium)
                            contactPhone?.let { phone ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("Phone: $phone", style = MaterialTheme.typography.bodySmall)
                                    IconButton(modifier = Modifier.size(28.dp), onClick = {
                                        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
                                    }) {
                                        Icon(Icons.Default.Call, contentDescription = "Call", tint = MaterialTheme.colorScheme.primary)
                                    }
                                    IconButton(modifier = Modifier.size(28.dp), onClick = {
                                        context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone")))
                                    }) {
                                        Icon(Icons.Default.Sms, contentDescription = "SMS", tint = MaterialTheme.colorScheme.secondary)
                                    }
                                    IconButton(modifier = Modifier.size(28.dp), onClick = {
                                        val cleanNumber = phone.replace(Regex("[^0-9+]"), "")
                                        val uri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanNumber")
                                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                    }) {
                                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "WhatsApp", tint = Color(0xFF25D366))
                                    }
                                }
                            }
                            contactEmail?.let { email ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Email: $email", style = MaterialTheme.typography.bodySmall)
                                    IconButton(modifier = Modifier.size(28.dp), onClick = {
                                        context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email")))
                                    }) {
                                        Icon(Icons.Default.Mail, contentDescription = "Email", tint = MaterialTheme.colorScheme.tertiary)
                                    }
                                }
                            }
                        } else {
                            Text("No contact linked", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }

                // Checklists Section (Inside Full-Screen View)
                if (existingTask != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Checklist (${liveChecklist.size})", style = MaterialTheme.typography.titleSmall)

                            liveChecklist.forEach { item ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = item.isDone,
                                        onCheckedChange = { viewModel.toggleChecklistItem(item) }
                                    )
                                    Text(
                                        text = item.text,
                                        style = MaterialTheme.typography.bodyMedium,
                                        textDecoration = if (item.isDone) TextDecoration.LineThrough else null,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(modifier = Modifier.size(24.dp), onClick = { viewModel.moveChecklistItem(item, true) }) {
                                        Icon(Icons.Default.ArrowDropUp, contentDescription = "Move Up")
                                    }
                                    IconButton(modifier = Modifier.size(24.dp), onClick = { viewModel.moveChecklistItem(item, false) }) {
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = "Move Down")
                                    }
                                    IconButton(modifier = Modifier.size(24.dp), onClick = { viewModel.deleteChecklistItem(item) }) {
                                        Icon(Icons.Default.Close, contentDescription = "Delete item", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = newChecklistText,
                                    onValueChange = { newChecklistText = it },
                                    placeholder = { Text("Add checklist item...") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(8.dp))
                                IconButton(
                                    onClick = {
                                        if (newChecklistText.isNotBlank()) {
                                            viewModel.addChecklistItem(existingTask.id, newChecklistText)
                                            newChecklistText = ""
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.AddCircle, contentDescription = "Add Item", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }

                // File Attachments Section (Inside Full-Screen View)
                if (existingTask != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Attachments (${liveAttachments.size})", style = MaterialTheme.typography.titleSmall)
                                OutlinedButton(onClick = { attachmentPickerLauncher.launch(arrayOf("*/*")) }) {
                                    Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Add File")
                                }
                            }

                            liveAttachments.forEach { att ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = att.fileName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable {
                                                try {
                                                    context.startActivity(
                                                        Intent(Intent.ACTION_VIEW, Uri.parse(att.uriString)).apply {
                                                            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                                                        }
                                                    )
                                                } catch (_: Exception) {
                                                    Toast.makeText(context, "Cannot open file", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                    )
                                    IconButton(modifier = Modifier.size(24.dp), onClick = { viewModel.moveAttachment(att, true) }) {
                                        Icon(Icons.Default.ArrowDropUp, contentDescription = "Move Up")
                                    }
                                    IconButton(modifier = Modifier.size(24.dp), onClick = { viewModel.moveAttachment(att, false) }) {
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = "Move Down")
                                    }
                                    IconButton(modifier = Modifier.size(24.dp), onClick = { viewModel.deleteAttachment(att) }) {
                                        Icon(Icons.Default.Close, contentDescription = "Delete attachment", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }

                // Google Calendar silent sync option
                if (reminderMs != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { syncCalendar = !syncCalendar }
                    ) {
                        Checkbox(checked = syncCalendar, onCheckedChange = { syncCalendar = it })
                        Spacer(Modifier.width(6.dp))
                        Text("Silently sync to Google Calendar", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

// Dialog that allows selecting which task/subtask to Move or Copy into
@Composable
fun TaskDestinationDialog(
    title: String,
    currentTaskId: Long,
    viewModel: TaskViewModel,
    onDismiss: () -> Unit,
    onSelectTarget: (Long?) -> Unit
) {
    var potentialParents by remember { mutableStateOf<List<TaskItem>>(emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(currentTaskId) {
        scope.launch {
            potentialParents = viewModel.getAllPotentialParents(currentTaskId)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 350.dp)
            ) {
                item {
                    ListItem(
                        headlineContent = { Text("★ Top Level (Main Task)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier
                            .clickable { onSelectTarget(null) }
                            .padding(vertical = 4.dp)
                    )
                    HorizontalDivider()
                }

                items(potentialParents, key = { it.id }) { parentCandidate ->
                    ListItem(
                        headlineContent = { Text(parentCandidate.title) },
                        supportingContent = {
                            Text(if (parentCandidate.parentId == null) "Main Task" else "Subtask", style = MaterialTheme.typography.labelSmall)
                        },
                        modifier = Modifier
                            .clickable { onSelectTarget(parentCandidate.id) }
                            .padding(vertical = 2.dp)
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
