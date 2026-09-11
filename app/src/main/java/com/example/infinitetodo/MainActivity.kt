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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
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

    var showCreateDialogForParentId by remember { mutableStateOf<Long?>(null) }
    var taskToEdit by remember { mutableStateOf<TaskItem?>(null) }
    var isCreatingRootTask by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
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
                            placeholder = { Text("Search tasks, contacts...") },
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
            FloatingActionButton(onClick = { isCreatingRootTask = true }) {
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
                        onAddSubtask = { parentId -> showCreateDialogForParentId = parentId },
                        onEditTask = { taskToEdit = it }
                    )
                }
            } else {
                items(rootTasks, key = { it.id }) { rootTask ->
                    TaskNodeView(
                        task = rootTask,
                        depth = 0,
                        viewMode = viewMode,
                        viewModel = viewModel,
                        onAddSubtask = { parentId -> showCreateDialogForParentId = parentId },
                        onEditTask = { taskToEdit = it }
                    )
                }
            }
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

        if (isCreatingRootTask) {
            TaskEditorDialog(
                titleHeader = "New Task",
                initialTitle = "",
                initialReminderMs = null,
                initialContactName = null,
                initialContactPhone = null,
                initialVoicePath = null,
                onDismiss = { isCreatingRootTask = false },
                onConfirm = { title, time, syncCal, cName, cPhone, voicePath ->
                    viewModel.addTask(title, null, time, syncCal, cName, cPhone, voicePath)
                    isCreatingRootTask = false
                }
            )
        }

        showCreateDialogForParentId?.let { parentId ->
            TaskEditorDialog(
                titleHeader = "New Subtask",
                initialTitle = "",
                initialReminderMs = null,
                initialContactName = null,
                initialContactPhone = null,
                initialVoicePath = null,
                onDismiss = { showCreateDialogForParentId = null },
                onConfirm = { title, time, syncCal, cName, cPhone, voicePath ->
                    viewModel.addTask(title, parentId, time, syncCal, cName, cPhone, voicePath)
                    showCreateDialogForParentId = null
                }
            )
        }

        taskToEdit?.let { task ->
            TaskEditorDialog(
                titleHeader = "Edit Task",
                initialTitle = task.title,
                initialReminderMs = task.reminderTimestamp,
                initialContactName = task.contactName,
                initialContactPhone = task.contactPhone,
                initialVoicePath = task.voiceRecordingPath,
                onDismiss = { taskToEdit = null },
                onConfirm = { title, time, syncCal, cName, cPhone, voicePath ->
                    viewModel.updateTask(task, title, time, syncCal, cName, cPhone, voicePath)
                    taskToEdit = null
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
    onEditTask: (TaskItem) -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }
    var isPlayingVoice by remember { mutableStateOf(false) }
    var newChecklistText by remember { mutableStateOf("") }
    var showAddChecklistField by remember { mutableStateOf(false) }

    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val animatedOffsetX by animateFloatAsState(targetValue = offsetX, label = "slideX")

    val subtasks by viewModel.getSubtasks(task.id).collectAsState(initial = emptyList())
    val checklist by viewModel.getChecklist(task.id).collectAsState(initial = emptyList())
    val attachments by viewModel.getAttachments(task.id).collectAsState(initial = emptyList())

    val context = LocalContext.current
    val audioHelper = remember { AudioRecorderHelper(context) }

    val dateFormat = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()) }

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
            viewModel.addAttachment(task.id, it, fileName)
        }
    }

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

                        IconButton(
                            modifier = Modifier.size(26.dp),
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
                                .clickable { onEditTask(task) }
                        )

                        if (viewMode == TaskViewMode.COMPACT) {
                            IconButton(modifier = Modifier.size(24.dp), onClick = { onAddSubtask(task.id) }) {
                                Icon(Icons.Default.SubdirectoryArrowRight, contentDescription = "Add Subtask", modifier = Modifier.size(15.dp))
                            }
                            IconButton(modifier = Modifier.size(24.dp), onClick = { viewModel.deleteTask(task) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(15.dp))
                            }
                        }
                    }

                    // Detailed metadata section with date/time audit logs
                    if (viewMode == TaskViewMode.DETAILED) {
                        // Date/Time audit row (Created & Modified)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 32.dp, bottom = 2.dp),
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

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 32.dp, bottom = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            task.voiceRecordingPath?.let { path ->
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

                            if (!task.contactPhone.isNullOrBlank()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.primaryContainer)
                                        .clickable {
                                            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${task.contactPhone}")))
                                        }
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(12.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("${task.contactName ?: "Call"}: ${task.contactPhone}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }

                        // Attachments List
                        if (attachments.isNotEmpty()) {
                            Text(
                                "Attachments:",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(start = 32.dp, top = 2.dp)
                            )
                            attachments.forEach { att ->
                                var attDragY by remember { mutableFloatStateOf(0f) }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 32.dp, top = 2.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DragHandle,
                                        contentDescription = "Drag Attachment",
                                        tint = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier
                                            .size(20.dp)
                                            .pointerInput(att.id) {
                                                detectDragGestures(
                                                    onDrag = { change, dragAmount ->
                                                        change.consume()
                                                        attDragY += dragAmount.y
                                                        if (attDragY > 30f) {
                                                            viewModel.moveAttachment(att, false)
                                                            attDragY = 0f
                                                        } else if (attDragY < -30f) {
                                                            viewModel.moveAttachment(att, true)
                                                            attDragY = 0f
                                                        }
                                                    },
                                                    onDragEnd = { attDragY = 0f }
                                                )
                                            }
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(13.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = att.fileName,
                                        style = MaterialTheme.typography.bodySmall,
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
                                    IconButton(modifier = Modifier.size(24.dp), onClick = { viewModel.deleteAttachment(att) }) {
                                        Icon(Icons.Default.Close, contentDescription = "Delete Attachment", modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                        }

                        // Checklist Items
                        if (checklist.isNotEmpty()) {
                            Text(
                                "Checklist:",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(start = 32.dp, top = 4.dp)
                            )
                            checklist.forEach { item ->
                                var itemDragY by remember { mutableFloatStateOf(0f) }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DragHandle,
                                        contentDescription = "Drag Item",
                                        tint = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier
                                            .size(20.dp)
                                            .pointerInput(item.id) {
                                                detectDragGestures(
                                                    onDrag = { change, dragAmount ->
                                                        change.consume()
                                                        itemDragY += dragAmount.y
                                                        if (itemDragY > 30f) {
                                                            viewModel.moveChecklistItem(item, false)
                                                            itemDragY = 0f
                                                        } else if (itemDragY < -30f) {
                                                            viewModel.moveChecklistItem(item, true)
                                                            itemDragY = 0f
                                                        }
                                                    },
                                                    onDragEnd = { itemDragY = 0f }
                                                )
                                            }
                                    )
                                    Checkbox(
                                        checked = item.isDone,
                                        onCheckedChange = { viewModel.toggleChecklistItem(item) },
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = item.text,
                                        style = MaterialTheme.typography.bodySmall,
                                        textDecoration = if (item.isDone) TextDecoration.LineThrough else null,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(modifier = Modifier.size(24.dp), onClick = { viewModel.deleteChecklistItem(item) }) {
                                        Icon(Icons.Default.Close, contentDescription = "Delete Item", modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                        }

                        if (showAddChecklistField) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 32.dp, top = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = newChecklistText,
                                    onValueChange = { newChecklistText = it },
                                    placeholder = { Text("New checklist item...") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = {
                                    if (newChecklistText.isNotBlank()) {
                                        viewModel.addChecklistItem(task.id, newChecklistText)
                                        newChecklistText = ""
                                        showAddChecklistField = false
                                    }
                                }) {
                                    Icon(Icons.Default.Check, contentDescription = "Confirm item")
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
                            IconButton(modifier = Modifier.size(28.dp), onClick = { showAddChecklistField = !showAddChecklistField }) {
                                Icon(Icons.Default.Checklist, contentDescription = "Add Checklist", modifier = Modifier.size(17.dp))
                            }
                            IconButton(modifier = Modifier.size(28.dp), onClick = { attachmentPickerLauncher.launch(arrayOf("*/*")) }) {
                                Icon(Icons.Default.AttachFile, contentDescription = "Add Attachment", modifier = Modifier.size(17.dp))
                            }
                            IconButton(modifier = Modifier.size(28.dp), onClick = { onAddSubtask(task.id) }) {
                                Icon(Icons.Default.SubdirectoryArrowRight, contentDescription = "Add Subtask", modifier = Modifier.size(17.dp))
                            }
                            IconButton(modifier = Modifier.size(28.dp), onClick = { onEditTask(task) }) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(17.dp))
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
                    onEditTask = onEditTask
                )
            }
        }
    }
}

@Composable
fun TaskEditorDialog(
    titleHeader: String,
    initialTitle: String,
    initialReminderMs: Long?,
    initialContactName: String?,
    initialContactPhone: String?,
    initialVoicePath: String?,
    onDismiss: () -> Unit,
    onConfirm: (
        title: String,
        reminderMs: Long?,
        syncWithGoogleCalendar: Boolean,
        contactName: String?,
        contactPhone: String?,
        voicePath: String?
    ) -> Unit
) {
    var title by remember { mutableStateOf(initialTitle) }
    var reminderMs by remember { mutableStateOf(initialReminderMs) }
    var contactName by remember { mutableStateOf(initialContactName) }
    var contactPhone by remember { mutableStateOf(initialContactPhone) }
    var voicePath by remember { mutableStateOf(initialVoicePath) }
    var syncCalendar by remember { mutableStateOf(true) }
    var isRecording by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val audioHelper = remember { AudioRecorderHelper(context) }

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
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(titleHeader) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Task Description") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedButton(
                    onClick = {
                        if (isRecording) {
                            voicePath = audioHelper.stopRecording()
                            isRecording = false
                        } else {
                            voicePath = audioHelper.startRecording()
                            isRecording = true
                        }
                    },
                    colors = if (isRecording) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer) else ButtonDefaults.outlinedButtonColors(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(if (isRecording) Icons.Default.Stop else Icons.Default.Mic, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            isRecording -> "Recording... (Tap to Stop)"
                            voicePath != null -> "Voice Memo Attached (Record Again)"
                            else -> "Record Voice Memo"
                        }
                    )
                }

                OutlinedButton(
                    onClick = { contactPickerLauncher.launch(null) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Person, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (!contactPhone.isNullOrBlank()) {
                            "${contactName ?: "Contact"}: $contactPhone"
                        } else {
                            "Attach Contact to Call"
                        }
                    )
                }

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
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Alarm, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = reminderMs?.let {
                            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it))
                        } ?: "Pick Reminder Date & Time"
                    )
                }

                if (reminderMs != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { syncCalendar = !syncCalendar }
                    ) {
                        Checkbox(checked = syncCalendar, onCheckedChange = { syncCalendar = it })
                        Spacer(Modifier.width(4.dp))
                        Text("Silently sync to Google Calendar", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = title.isNotBlank(),
                onClick = {
                    if (isRecording) {
                        voicePath = audioHelper.stopRecording()
                    }
                    onConfirm(title, reminderMs, syncCalendar, contactName, contactPhone, voicePath)
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                if (isRecording) audioHelper.stopRecording()
                onDismiss()
            }) { Text("Cancel") }
        }
    )
}
