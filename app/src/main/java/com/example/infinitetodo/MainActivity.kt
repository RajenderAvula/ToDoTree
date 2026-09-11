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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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

enum class AppNavTab(val title: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    TASKS("Tasks", Icons.Default.FormatListBulleted),
    CALENDAR("Calendar", Icons.Default.CalendarMonth),
    SEARCH("Search", Icons.Default.Search),
    SETTINGS("Settings", Icons.Default.Settings)
}

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
                    MainAppScaffold(
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
fun MainAppScaffold(
    viewModel: TaskViewModel,
    currentTheme: AppThemeMode,
    onThemeChange: (AppThemeMode) -> Unit,
    viewMode: TaskViewMode,
    onViewModeChange: (TaskViewMode) -> Unit
) {
    var selectedTab by remember { mutableStateOf(AppNavTab.HOME) }

    var activeFullScreenTask by remember { mutableStateOf<TaskItem?>(null) }
    var isCreatingFullScreenTask by remember { mutableStateOf(false) }
    var fullScreenParentId by remember { mutableStateOf<Long?>(null) }

    var taskForTargetMove by remember { mutableStateOf<TaskItem?>(null) }
    var taskForTargetCopy by remember { mutableStateOf<TaskItem?>(null) }

    val context = LocalContext.current

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
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
            ) {
                AppNavTab.values().forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(tab.icon, contentDescription = tab.title) },
                        label = { Text(tab.title) },
                        alwaysShowLabel = true
                    )
                }
            }
        },
        floatingActionButton = {
            if (selectedTab == AppNavTab.HOME || selectedTab == AppNavTab.TASKS) {
                FloatingActionButton(
                    onClick = {
                        fullScreenParentId = null
                        activeFullScreenTask = null
                        isCreatingFullScreenTask = true
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Task")
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (selectedTab) {
                AppNavTab.HOME -> HomeDashboardTab(
                    viewModel = viewModel,
                    viewMode = viewMode,
                    onOpenTask = { activeFullScreenTask = it },
                    onNavigateToTab = { selectedTab = it },
                    onAddNewTask = {
                        fullScreenParentId = null
                        activeFullScreenTask = null
                        isCreatingFullScreenTask = true
                    }
                )
                AppNavTab.TASKS -> TasksTreeTab(
                    viewModel = viewModel,
                    viewMode = viewMode,
                    onAddSubtask = { parentId ->
                        fullScreenParentId = parentId
                        activeFullScreenTask = null
                        isCreatingFullScreenTask = true
                    },
                    onOpenFullScreen = { activeFullScreenTask = it },
                    onMoveToTarget = { taskForTargetMove = it },
                    onCopyToTarget = { taskForTargetCopy = it }
                )
                AppNavTab.CALENDAR -> CalendarAgendaTab(
                    viewModel = viewModel,
                    onOpenTask = { activeFullScreenTask = it }
                )
                AppNavTab.SEARCH -> SearchExploreTab(
                    viewModel = viewModel,
                    viewMode = viewMode,
                    onOpenTask = { activeFullScreenTask = it }
                )
                AppNavTab.SETTINGS -> SettingsManagerTab(
                    currentTheme = currentTheme,
                    onThemeChange = onThemeChange,
                    viewMode = viewMode,
                    onViewModeChange = onViewModeChange,
                    viewModel = viewModel
                )
            }
        }

        // Full Screen Workspace View
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

        // Move target dialog
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

        // Copy target dialog
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
    }
}

// -----------------------------------------------------------------------------------------
// 1. HOME DASHBOARD TAB
// -----------------------------------------------------------------------------------------
@Composable
fun HomeDashboardTab(
    viewModel: TaskViewModel,
    viewMode: TaskViewMode,
    onOpenTask: (TaskItem) -> Unit,
    onNavigateToTab: (AppNavTab) -> Unit,
    onAddNewTask: () -> Unit
) {
    val rootTasks by viewModel.rootTasks.collectAsState(initial = emptyList())
    val completedCount = rootTasks.count { it.isCompleted }
    val pendingCount = rootTasks.size - completedCount

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Infinite ToDo", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Hierarchical Task Tree & Sync", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
            }
            IconButton(onClick = { onNavigateToTab(AppNavTab.SETTINGS) }) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
        }

        // Overview Summary Cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onNavigateToTab(AppNavTab.TASKS) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("$pendingCount", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("Pending Tasks", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("$completedCount", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text("Completed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }

        // Quick Navigation Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { onNavigateToTab(AppNavTab.SEARCH) },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Search")
            }
            OutlinedButton(
                onClick = { onNavigateToTab(AppNavTab.CALENDAR) },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Agenda")
            }
        }

        // Recent / Priority Root Tasks
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Top-Level Tasks", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { onNavigateToTab(AppNavTab.TASKS) }) {
                Text("View Full Tree")
            }
        }

        if (rootTasks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.TaskAlt, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(8.dp))
                    Text("No tasks created yet", color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onAddNewTask) {
                        Text("Create First Task")
                    }
                }
            }
        } else {
            rootTasks.take(5).forEach { task ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenTask(task) },
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = task.isCompleted,
                            onCheckedChange = { viewModel.toggleTaskCompletion(task) }
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = task.title,
                                style = MaterialTheme.typography.bodyLarge,
                                textDecoration = if (task.isCompleted) TextDecoration.LineThrough else null
                            )
                            if (!task.notes.isNullOrBlank()) {
                                Text(
                                    text = task.notes,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Icon(Icons.AutoMirrored.Filled.ArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------------------
// 2. FULL TREE TASKS TAB
// -----------------------------------------------------------------------------------------
@Composable
fun TasksTreeTab(
    viewModel: TaskViewModel,
    viewMode: TaskViewMode,
    onAddSubtask: (Long) -> Unit,
    onOpenFullScreen: (TaskItem) -> Unit,
    onMoveToTarget: (TaskItem) -> Unit,
    onCopyToTarget: (TaskItem) -> Unit
) {
    val rootTasks by viewModel.rootTasks.collectAsState(initial = emptyList())

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Task Hierarchy Tree", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("${rootTasks.size} Root Tasks", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 4.dp)
        ) {
            items(rootTasks, key = { it.id }) { rootTask ->
                TaskNodeView(
                    task = rootTask,
                    depth = 0,
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
// 3. CALENDAR AGENDA TAB
// -----------------------------------------------------------------------------------------
@Composable
fun CalendarAgendaTab(
    viewModel: TaskViewModel,
    onOpenTask: (TaskItem) -> Unit
) {
    val rootTasks by viewModel.rootTasks.collectAsState(initial = emptyList())
    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    // Filter tasks that have calendar/reminder timestamps
    val scheduledTasks = remember(rootTasks) {
        rootTasks.filter { it.reminderTimestamp != null }
            .sortedBy { it.reminderTimestamp }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Calendar & Agenda", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Synced with Google Calendar", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                }
                IconButton(onClick = {
                    viewModel.syncAllTasksToCalendar { count ->
                        val msg = if (count >= 0) "Synced $count task(s) to Calendar" else "Calendar permission required"
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Icon(Icons.Default.Sync, contentDescription = "Sync All")
                }
            }
        }

        if (scheduledTasks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.EventBusy, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(12.dp))
                    Text("No scheduled tasks found", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.outline)
                    Text("Add date and time to any task to view it here", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(scheduledTasks, key = { it.id }) { task ->
                    val epoch = task.reminderTimestamp ?: 0L
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenTask(task) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(timeFormat.format(Date(epoch)), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(dateFormat.format(Date(epoch)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                Text(
                                    text = task.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    textDecoration = if (task.isCompleted) TextDecoration.LineThrough else null
                                )
                                if (task.calendarEventId != null) {
                                    Text("✓ Google Calendar Linked", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Checkbox(
                                checked = task.isCompleted,
                                onCheckedChange = { viewModel.toggleTaskCompletion(task) }
                            )
                        }
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------------------
// 4. SEARCH & EXPLORE TAB
// -----------------------------------------------------------------------------------------
@Composable
fun SearchExploreTab(
    viewModel: TaskViewModel,
    viewMode: TaskViewMode,
    onOpenTask: (TaskItem) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val searchResults by viewModel.searchTasks(searchQuery).collectAsState(initial = emptyList())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Search Everything") },
            placeholder = { Text("Tasks, notes, checklists, contacts...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (searchQuery.isBlank()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Type any keyword to search across all nested tasks", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            Text("Found ${searchResults.size} result(s):", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(searchResults, key = { it.id }) { task ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenTask(task) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = task.isCompleted,
                                onCheckedChange = { viewModel.toggleTaskCompletion(task) }
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = task.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    textDecoration = if (task.isCompleted) TextDecoration.LineThrough else null
                                )
                                if (!task.notes.isNullOrBlank()) {
                                    Text(
                                        text = task.notes,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------------------
// 5. SETTINGS MANAGER TAB
// -----------------------------------------------------------------------------------------
@Composable
fun SettingsManagerTab(
    currentTheme: AppThemeMode,
    onThemeChange: (AppThemeMode) -> Unit,
    viewMode: TaskViewMode,
    onViewModeChange: (TaskViewMode) -> Unit,
    viewModel: TaskViewModel
) {
    val context = LocalContext.current
    var showThemeDialog by remember { mutableStateOf(false) }

    val createBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        uri?.let { destUri ->
            context.contentResolver.openOutputStream(destUri)?.use { outStream ->
                viewModel.backupToDevice(outStream) { success ->
                    Toast.makeText(context, if (success) "Backup saved successfully!" else "Backup failed", Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(context, if (success) "Backup restored successfully!" else "Restore failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("App Settings & Preferences", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        // Appearance
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Appearance & Layout", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showThemeDialog = true },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("App Color Theme", style = MaterialTheme.typography.bodyLarge)
                        Text(currentTheme.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    Icon(Icons.Default.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Compact View Mode", style = MaterialTheme.typography.bodyLarge)
                        Text("Hide metadata chips for a cleaner tree", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    Switch(
                        checked = viewMode == TaskViewMode.COMPACT,
                        onCheckedChange = {
                            onViewModeChange(if (it) TaskViewMode.COMPACT else TaskViewMode.DETAILED)
                        }
                    )
                }
            }
        }

        // Calendar Sync
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Google Calendar Integration", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Force synchronization of all scheduled tasks and changes into Google Calendar.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                Button(
                    onClick = {
                        viewModel.syncAllTasksToCalendar { count ->
                            val msg = if (count >= 0) "Synced $count task(s) to Calendar" else "Calendar permission required"
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Sync, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Sync All to Google Calendar")
                }
            }
        }

        // Backup & Restore
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Data Backup & Restore", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Exports all tasks, nested checklists, attached files, and voice recordings into a ZIP file.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)

                OutlinedButton(
                    onClick = { createBackupLauncher.launch("ToDoTree_Backup_${System.currentTimeMillis()}.zip") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Backup to Device (ZIP)")
                }

                OutlinedButton(
                    onClick = {
                        viewModel.sendBackupViaMail { intent ->
                            if (intent != null) {
                                context.startActivity(Intent.createChooser(intent, "Send Backup via Email"))
                            } else {
                                Toast.makeText(context, "Failed to create email backup", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Email, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Backup & Send via Email")
                }

                HorizontalDivider()

                Button(
                    onClick = { restoreBackupLauncher.launch(arrayOf("application/zip", "*/*")) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Restore, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Restore from Device / Mail")
                }
            }
        }

        // App Info
        Box(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), contentAlignment = Alignment.Center) {
            Text("Infinite ToDo • Standard Bottom Navigation v2.0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }

        // Theme Dialog
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

// -----------------------------------------------------------------------------------------
// FULL SCREEN WORKSPACE VIEW
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

    val taskId = existingTask?.id ?: 0L
    val liveChecklist by viewModel.getChecklist(taskId).collectAsState(initial = emptyList())
    val liveAttachments by viewModel.getAttachments(taskId).collectAsState(initial = emptyList())

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
        properties = DialogProperties(usePlatformDefaultWidth = false)
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
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
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
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Task Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

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

// -----------------------------------------------------------------------------------------
// REUSABLE TASK TREE ROW NODE
// -----------------------------------------------------------------------------------------
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
    var isNotesBoxExpanded by remember { mutableStateOf(false) }
    var currentNoteText by remember(task.notes) { mutableStateOf(task.notes ?: "") }

    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val animatedOffsetX by animateFloatAsState(targetValue = offsetX, label = "slideX")

    val subtasks by viewModel.getSubtasks(task.id).collectAsState(initial = emptyList())
    val checklist by viewModel.getChecklist(task.id).collectAsState(initial = emptyList())
    val attachments by viewModel.getAttachments(task.id).collectAsState(initial = emptyList())

    val context = LocalContext.current
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

                        // Up and down arrows
                        IconButton(modifier = Modifier.size(22.dp), onClick = { viewModel.moveTaskVertical(task, directionUp = true) }) {
                            Icon(Icons.Default.ArrowDropUp, contentDescription = "Move Up", modifier = Modifier.size(20.dp))
                        }
                        IconButton(modifier = Modifier.size(22.dp), onClick = { viewModel.moveTaskVertical(task, directionUp = false) }) {
                            Icon(Icons.Default.ArrowDropDown, contentDescription = "Move Down", modifier = Modifier.size(20.dp))
                        }

                        // Outdent/Indent arrows
                        if (task.parentId != null) {
                            IconButton(modifier = Modifier.size(22.dp), onClick = { viewModel.outdentTask(task) }) {
                                Icon(Icons.Default.KeyboardDoubleArrowLeft, contentDescription = "Promote", tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
                            }
                        }
                        IconButton(modifier = Modifier.size(22.dp), onClick = { viewModel.indentTask(task) }) {
                            Icon(Icons.Default.KeyboardDoubleArrowRight, contentDescription = "Indent", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
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
                                .clickable { onOpenFullScreen(task) }
                        )

                        IconButton(
                            modifier = Modifier.size(26.dp),
                            onClick = { onOpenFullScreen(task) }
                        ) {
                            Icon(Icons.Default.OpenInFull, contentDescription = "Open Full Screen", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(17.dp))
                        }

                        if (viewMode == TaskViewMode.COMPACT) {
                            IconButton(modifier = Modifier.size(24.dp), onClick = {
                                viewModel.manualSyncTaskToCalendar(task.id) { feedback ->
                                    Toast.makeText(context, feedback, Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Icon(Icons.Default.Sync, contentDescription = "Sync", modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(modifier = Modifier.size(24.dp), onClick = { attachmentPickerLauncher.launch(arrayOf("*/*")) }) {
                                Icon(Icons.Default.AttachFile, contentDescription = "Attach", modifier = Modifier.size(15.dp))
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
                                Icon(Icons.Default.Sync, contentDescription = "Sync", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            }
                            IconButton(modifier = Modifier.size(28.dp), onClick = { onMoveToTarget(task) }) {
                                Icon(Icons.Default.DriveFileMove, contentDescription = "Move", modifier = Modifier.size(17.dp))
                            }
                            IconButton(modifier = Modifier.size(28.dp), onClick = { onCopyToTarget(task) }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(17.dp))
                            }
                            IconButton(modifier = Modifier.size(28.dp), onClick = { attachmentPickerLauncher.launch(arrayOf("*/*")) }) {
                                Icon(Icons.Default.AttachFile, contentDescription = "Attach", modifier = Modifier.size(17.dp))
                            }
                            IconButton(modifier = Modifier.size(28.dp), onClick = { onAddSubtask(task.id) }) {
                                Icon(Icons.Default.SubdirectoryArrowRight, contentDescription = "Add Subtask", modifier = Modifier.size(17.dp))
                            }
                            IconButton(modifier = Modifier.size(28.dp), onClick = { onOpenFullScreen(task) }) {
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
                    onOpenFullScreen = onOpenFullScreen,
                    onMoveToTarget = onMoveToTarget,
                    onCopyToTarget = onCopyToTarget
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------------------
// TARGET DESTINATION DIALOG (MOVE / COPY)
// -----------------------------------------------------------------------------------------
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
