package com.example.infinitetodo

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.draw.scale
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
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

enum class AppNavTab(val title: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    TASKS("Tasks", Icons.Default.AccountTree),
    CALENDAR("Calendar", Icons.Default.CalendarMonth),
    GANTT("Gantt", Icons.Default.Timeline),
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
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
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
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)) {
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
                AppNavTab.GANTT -> GanttChartTab(
                    viewModel = viewModel,
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

        // Full Screen Workspace
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

        // Target Destination Dialogs
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
// COMMON MULTI-CRITERIA FILTER BAR
// -----------------------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskFilterHeaderBar(
    viewModel: TaskViewModel,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit
) {
    val filterState by viewModel.filterState.collectAsState()
    var showFilterSheet by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
            .padding(8.dp)
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = { Text("Search title, checklist, notes, contact...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                    IconButton(onClick = { showFilterSheet = !showFilterSheet }) {
                        Icon(
                            Icons.Default.FilterList,
                            contentDescription = "Filters",
                            tint = if (filterState.priorities.isNotEmpty() || filterState.statusPending != null || filterState.mustHaveContact) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        )
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        AnimatedVisibility(visible = showFilterSheet) {
            Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Priority chips
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Priority:", style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.CenterVertically))
                    TaskPriority.values().forEach { priority ->
                        FilterChip(
                            selected = priority in filterState.priorities,
                            onClick = {
                                val current = filterState.priorities.toMutableSet()
                                if (priority in current) current.remove(priority) else current.add(priority)
                                viewModel.updateFilter(filterState.copy(priorities = current))
                            },
                            label = { Text(priority.name) }
                        )
                    }
                }

                // Status chips
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Status:", style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.CenterVertically))
                    FilterChip(
                        selected = filterState.statusPending == null,
                        onClick = { viewModel.updateFilter(filterState.copy(statusPending = null)) },
                        label = { Text("All") }
                    )
                    FilterChip(
                        selected = filterState.statusPending == true,
                        onClick = { viewModel.updateFilter(filterState.copy(statusPending = true)) },
                        label = { Text("Pending") }
                    )
                    FilterChip(
                        selected = filterState.statusPending == false,
                        onClick = { viewModel.updateFilter(filterState.copy(statusPending = false)) },
                        label = { Text("Completed") }
                    )
                    FilterChip(
                        selected = filterState.mustHaveContact,
                        onClick = { viewModel.updateFilter(filterState.copy(mustHaveContact = !filterState.mustHaveContact)) },
                        label = { Text("Has Contact") }
                    )
                }
            }
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
    val allTasks by viewModel.allTasksFlow.collectAsState(initial = emptyList())
    var searchQuery by remember { mutableStateOf("") }
    val searchResults by viewModel.searchTasks(searchQuery).collectAsState(initial = emptyList())
    val filterState by viewModel.filterState.collectAsState()
    val context = LocalContext.current

    val displayedTasks = remember(allTasks, searchResults, searchQuery, filterState) {
        val base = if (searchQuery.isNotBlank()) searchResults else allTasks
        base.filter { task ->
            (filterState.priorities.isEmpty() || task.priority in filterState.priorities) &&
            (filterState.statusPending == null || (if (filterState.statusPending == true) !task.isCompleted else task.isCompleted))
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TaskFilterHeaderBar(viewModel, searchQuery) { searchQuery = it }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Infinite ToDo", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text("${displayedTasks.size} Tasks Listed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    IconButton(onClick = {
                        PrintHelper.printTasks(context, "Full Agenda Print", displayedTasks)
                    }) {
                        Icon(Icons.Default.Print, contentDescription = "Print Agenda")
                    }
                }
            }

            items(displayedTasks, key = { it.id }) { task ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenTask(task) },
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = task.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    textDecoration = if (task.isCompleted) TextDecoration.LineThrough else null
                                )
                                Spacer(Modifier.width(8.dp))
                                PriorityBadge(task.priority)
                            }
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
// 2. TASKS TREE TAB (WITH UNDOCKED HOLD SLIDING & ENLARGED CONTROLS)
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
    var searchQuery by remember { mutableStateOf("") }
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize()) {
        TaskFilterHeaderBar(viewModel, searchQuery) { searchQuery = it }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Hierarchical Tree", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row {
                IconButton(onClick = {
                    viewModel.syncAllTasksToCalendar { count ->
                        Toast.makeText(context, "Synced $count task(s) to Google Calendar", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Icon(Icons.Default.Sync, contentDescription = "Sync All")
                }
                IconButton(onClick = {
                    PrintHelper.printTasks(context, "Hierarchy Tree Print", rootTasks)
                }) {
                    Icon(Icons.Default.Print, contentDescription = "Print Tree")
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 2.dp)
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
    val allTasks by viewModel.allTasksFlow.collectAsState(initial = emptyList())
    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    var searchQuery by remember { mutableStateOf("") }
    val filterState by viewModel.filterState.collectAsState()

    val scheduledTasks = remember(allTasks, searchQuery, filterState) {
        allTasks.filter { task ->
            val ts = task.dueTimestamp ?: task.reminderTimestamp
            ts != null &&
            (searchQuery.isBlank() || task.title.contains(searchQuery, true)) &&
            (filterState.priorities.isEmpty() || task.priority in filterState.priorities) &&
            (filterState.statusPending == null || (if (filterState.statusPending == true) !task.isCompleted else task.isCompleted))
        }.sortedBy { it.dueTimestamp ?: it.reminderTimestamp }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TaskFilterHeaderBar(viewModel, searchQuery) { searchQuery = it }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${scheduledTasks.size} Scheduled Events", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            IconButton(onClick = {
                viewModel.syncAllTasksToCalendar { count ->
                    Toast.makeText(context, "Synced $count task(s) to Calendar", Toast.LENGTH_SHORT).show()
                }
            }) {
                Icon(Icons.Default.Sync, contentDescription = "Sync All")
            }
        }

        if (scheduledTasks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No scheduled tasks match the filter", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(scheduledTasks, key = { it.id }) { task ->
                    val epoch = task.dueTimestamp ?: task.reminderTimestamp ?: 0L
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenTask(task) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(timeFormat.format(Date(epoch)), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(dateFormat.format(Date(epoch)), style = MaterialTheme.typography.labelSmall)
                                Text(task.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    PriorityBadge(task.priority)
                                    if (task.calendarEventId != null) {
                                        Text("• Synced to Google Calendar", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                            Checkbox(checked = task.isCompleted, onCheckedChange = { viewModel.toggleTaskCompletion(task) })
                        }
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------------------
// 4. GANTT CHART TIMELINE TAB
// -----------------------------------------------------------------------------------------
@Composable
fun GanttChartTab(
    viewModel: TaskViewModel,
    onOpenTask: (TaskItem) -> Unit
) {
    val allTasks by viewModel.allTasksFlow.collectAsState(initial = emptyList())
    val dateFormat = remember { SimpleDateFormat("dd MMM", Locale.getDefault()) }

    val ganttTasks = remember(allTasks) {
        allTasks.sortedBy { it.createdTimestamp }
    }

    val minTime = remember(ganttTasks) {
        ganttTasks.minOfOrNull { it.createdTimestamp } ?: System.currentTimeMillis()
    }
    val maxTime = remember(ganttTasks) {
        (ganttTasks.mapNotNull { it.dueTimestamp }.maxOrNull() ?: (System.currentTimeMillis() + 7 * 86400000L))
            .coerceAtLeast(minTime + 86400000L)
    }
    val totalDuration = (maxTime - minTime).coerceAtLeast(1L)

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Text("Gantt Chart Timeline", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Visual task span from creation to due date", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(dateFormat.format(Date(minTime)), style = MaterialTheme.typography.labelSmall)
            Text(dateFormat.format(Date(maxTime)), style = MaterialTheme.typography.labelSmall)
        }

        Divider(modifier = Modifier.padding(vertical = 6.dp))

        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(ganttTasks, key = { it.id }) { task ->
                val taskStart = task.createdTimestamp
                val taskEnd = task.dueTimestamp ?: (taskStart + 86400000L)

                val startFraction = ((taskStart - minTime).toFloat() / totalDuration).coerceIn(0f, 1f)
                val spanFraction = ((taskEnd - taskStart).toFloat() / totalDuration).coerceIn(0.08f, 1f - startFraction)

                Column(modifier = Modifier.fillMaxWidth().clickable { onOpenTask(task) }) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(task.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        PriorityBadge(task.priority)
                    }
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(20.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(startFraction + spanFraction)
                                .fillMaxHeight()
                                .padding(start = (startFraction * 300).dp) // approximate visual offset
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (task.isCompleted) Color(0xFF43A047)
                                    else when (task.priority) {
                                        TaskPriority.URGENT -> Color(0xFFE53935)
                                        TaskPriority.HIGH -> Color(0xFFFB8C00)
                                        TaskPriority.MEDIUM -> Color(0xFF1E88E5)
                                        TaskPriority.LOW -> Color(0xFF7CB342)
                                    }
                                )
                        )
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

    val allTasks by viewModel.allTasksFlow.collectAsState(initial = emptyList())

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
        Text("Settings & Tools", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Print & Export", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Button(
                    onClick = { PrintHelper.printTasks(context, "Full Agenda Print", allTasks) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Print, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Print All Tasks & Checklists")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Layout & Theme", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { showThemeDialog = true },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Theme (${currentTheme.name})")
                    Icon(Icons.Default.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                Divider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Compact Tree Rows")
                    Switch(checked = viewMode == TaskViewMode.COMPACT, onCheckedChange = {
                        onViewModeChange(if (it) TaskViewMode.COMPACT else TaskViewMode.DETAILED)
                    })
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Backup & Restore", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                OutlinedButton(
                    onClick = { createBackupLauncher.launch("ToDoTree_Backup_${System.currentTimeMillis()}.zip") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Backup to Device (ZIP)")
                }
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
                                RadioButton(selected = (currentTheme == mode), onClick = {
                                    onThemeChange(mode)
                                    showThemeDialog = false
                                })
                                Spacer(Modifier.width(8.dp))
                                Text(mode.name, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showThemeDialog = false }) { Text("Close") } }
            )
        }
    }
}

// -----------------------------------------------------------------------------------------
// REUSABLE TASK TREE ROW NODE (WITH UNDOCKED DRAG HIGHLIGHTING)
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

    // Undocked Hold & Slide Drag State
    var isUndocked by remember { mutableStateOf(false) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val animatedOffsetY by animateFloatAsState(targetValue = offsetY, label = "dragY")

    val subtasks by viewModel.getSubtasks(task.id).collectAsState(initial = emptyList())
    val checklist by viewModel.getChecklist(task.id).collectAsState(initial = emptyList())
    val attachments by viewModel.getAttachments(task.id).collectAsState(initial = emptyList())

    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 16).dp)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, animatedOffsetY.roundToInt()) }
                .scale(if (isUndocked) 1.03f else 1f)
                .border(
                    width = if (isUndocked) 2.dp else 0.dp,
                    color = if (isUndocked) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(vertical = if (viewMode == TaskViewMode.COMPACT) 2.dp else 4.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = if (isUndocked) 8.dp else 2.dp)
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // UNDOCKED SLIDER DRAG HANDLE (Enlarged 44dp hit target)
                    IconButton(
                        modifier = Modifier
                            .size(44.dp)
                            .pointerInput(task.id) {
                                detectDragGestures(
                                    onDragStart = { isUndocked = true },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        offsetX += dragAmount.x
                                        offsetY += dragAmount.y
                                        if (offsetY > 48f) {
                                            viewModel.moveTaskVertical(task, directionUp = false)
                                            offsetY = 0f
                                        } else if (offsetY < -48f) {
                                            viewModel.moveTaskVertical(task, directionUp = true)
                                            offsetY = 0f
                                        }
                                    },
                                    onDragEnd = {
                                        if (offsetX > 100f) viewModel.indentTask(task)
                                        else if (offsetX < -100f) viewModel.outdentTask(task)
                                        offsetX = 0f
                                        offsetY = 0f
                                        isUndocked = false
                                    },
                                    onDragCancel = {
                                        offsetX = 0f
                                        offsetY = 0f
                                        isUndocked = false
                                    }
                                )
                            },
                        onClick = {}
                    ) {
                        Icon(
                            imageVector = Icons.Default.DragIndicator,
                            contentDescription = "Hold to Undock and Slide",
                            tint = if (isUndocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    // Enlarge Tree Expander Icon
                    IconButton(
                        modifier = Modifier.size(44.dp),
                        onClick = { isExpanded = !isExpanded }
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ArrowDropDown else Icons.AutoMirrored.Filled.ArrowRight,
                            contentDescription = "Expand Tree",
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Checkbox(
                        checked = task.isCompleted,
                        onCheckedChange = { viewModel.toggleTaskCompletion(task) },
                        modifier = Modifier.size(40.dp)
                    )

                    Column(modifier = Modifier.weight(1f).clickable { onOpenFullScreen(task) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = task.title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                textDecoration = if (task.isCompleted) TextDecoration.LineThrough else null
                            )
                            Spacer(Modifier.width(6.dp))
                            PriorityBadge(task.priority)
                        }

                        if (checklist.isNotEmpty() || attachments.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 2.dp)) {
                                if (checklist.isNotEmpty()) {
                                    val done = checklist.count { it.isDone }
                                    Text("☑ $done/${checklist.size}", style = MaterialTheme.typography.labelSmall)
                                }
                                if (attachments.isNotEmpty()) {
                                    Text("📎 ${attachments.size}", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }

                    // Enlarged Action Buttons for moving, copying, linking, and printing
                    IconButton(modifier = Modifier.size(44.dp), onClick = { onAddSubtask(task.id) }) {
                        Icon(Icons.Default.SubdirectoryArrowRight, contentDescription = "Add Subtask", modifier = Modifier.size(24.dp))
                    }
                    IconButton(modifier = Modifier.size(44.dp), onClick = { onMoveToTarget(task) }) {
                        Icon(Icons.Default.DriveFileMove, contentDescription = "Move to...", modifier = Modifier.size(24.dp))
                    }
                    IconButton(modifier = Modifier.size(44.dp), onClick = { onCopyToTarget(task) }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy to...", modifier = Modifier.size(24.dp))
                    }
                    IconButton(modifier = Modifier.size(44.dp), onClick = {
                        PrintHelper.printTasks(context, "Task '${task.title}' Print", listOf(task))
                    }) {
                        Icon(Icons.Default.Print, contentDescription = "Print", modifier = Modifier.size(24.dp))
                    }
                    IconButton(modifier = Modifier.size(44.dp), onClick = { viewModel.deleteTask(task) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(24.dp))
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
// FULL SCREEN WORKSPACE VIEW (ALL FEATURES ON INITIAL CREATION & EDIT)
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
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }

    var title by remember { mutableStateOf(existingTask?.title ?: "") }
    var notes by remember { mutableStateOf(existingTask?.notes ?: "") }
    var priority by remember { mutableStateOf(existingTask?.priority ?: TaskPriority.MEDIUM) }
    var reminderMs by remember { mutableStateOf(existingTask?.reminderTimestamp) }
    var dueMs by remember { mutableStateOf(existingTask?.dueTimestamp) }
    var repeatRule by remember { mutableStateOf(existingTask?.repeatRule ?: RecurrenceRule.NONE) }
    var linkedIds by remember { mutableStateOf(existingTask?.linkedTaskIds ?: "") }

    var isRecordingAudio by remember { mutableStateOf(false) }
    var recordedAudioPath by remember { mutableStateOf<String?>(null) }
    var newChecklistText by remember { mutableStateOf("") }
    var manualPhone by remember { mutableStateOf("") }
    var manualContactName by remember { mutableStateOf("") }

    val taskId = existingTask?.id ?: 0L
    val liveChecklist by viewModel.getChecklist(taskId).collectAsState(initial = emptyList())
    val liveAttachments by viewModel.getAttachments(taskId).collectAsState(initial = emptyList())

    // File pickers
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (existingTask != null) {
            for (uri in uris) {
                var fileName = "Document"
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (c.moveToFirst() && idx != -1) fileName = c.getString(idx)
                }
                viewModel.addAttachment(existingTask.id, AttachmentType.FILE, uri.toString(), fileName)
            }
        }
    }

    // Video Recording Launcher via Camera Intent
    var tempVideoUri by remember { mutableStateOf<Uri?>(null) }
    val videoRecordLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CaptureVideo()
    ) { success: Boolean ->
        if (success && tempVideoUri != null && existingTask != null) {
            viewModel.addAttachment(
                existingTask.id,
                AttachmentType.VIDEO,
                tempVideoUri.toString(),
                "Video Recording ${dateFormat.format(Date())}"
            )
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
                    val name = cursor.getString(nameCol)
                    var phone: String? = null

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
                                phone = phoneCursor.getString(numberCol)
                            }
                        }
                    }

                    if (existingTask != null) {
                        viewModel.addAttachment(
                            taskId = existingTask.id,
                            type = AttachmentType.CONTACT,
                            uriString = uri.toString(),
                            displayName = name ?: "Contact",
                            contactPhone = phone
                        )
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
                    title = { Text(if (isNewTask) "Create Task Workspace" else "Task Workspace") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        Button(
                            enabled = title.isNotBlank(),
                            onClick = {
                                if (isNewTask) {
                                    viewModel.addTask(title, notes, parentId, priority, reminderMs, dueMs, repeatRule, linkedIds)
                                } else {
                                    viewModel.updateTask(existingTask!!, title, notes, priority, reminderMs, dueMs, repeatRule, linkedIds)
                                }
                                onDismiss()
                            }
                        ) {
                            Text("Save")
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
                    label = { Text("Task Title *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Priority Selection Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Priority Level:", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TaskPriority.values().forEach { p ->
                            FilterChip(
                                selected = priority == p,
                                onClick = { priority = p },
                                label = { Text(p.name) }
                            )
                        }
                    }
                }

                // Expandable Text Note Box
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Description & Notes") },
                    minLines = 4,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth()
                )

                // Dates: Reminder, Due Date & Recurrence
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Schedule & Due Dates", fontWeight = FontWeight.Bold)

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    val cal = Calendar.getInstance()
                                    DatePickerDialog(context, { _, y, m, d ->
                                        TimePickerDialog(context, { _, h, min ->
                                            cal.set(y, m, d, h, min)
                                            reminderMs = cal.timeInMillis
                                        }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
                                    }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(reminderMs?.let { "Remind: ${dateFormat.format(Date(it))}" } ?: "Set Reminder")
                            }

                            OutlinedButton(
                                onClick = {
                                    val cal = Calendar.getInstance()
                                    DatePickerDialog(context, { _, y, m, d ->
                                        TimePickerDialog(context, { _, h, min ->
                                            cal.set(y, m, d, h, min)
                                            dueMs = cal.timeInMillis
                                        }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
                                    }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(dueMs?.let { "Due: ${dateFormat.format(Date(it))}" } ?: "Set Due Date")
                            }
                        }

                        // Recurrence Selection
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Repeat: ", style = MaterialTheme.typography.bodyMedium)
                            RecurrenceRule.values().forEach { rule ->
                                FilterChip(
                                    selected = repeatRule == rule,
                                    onClick = { repeatRule = rule },
                                    label = { Text(rule.name) },
                                    modifier = Modifier.padding(horizontal = 2.dp)
                                )
                            }
                        }
                    }
                }

                // Cross-Task Linking
                OutlinedTextField(
                    value = linkedIds,
                    onValueChange = { linkedIds = it },
                    label = { Text("Linked Task IDs (Comma-separated)") },
                    placeholder = { Text("e.g. 102, 105, 108") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // CHECKLISTS WITH EXPANDABLE NOTES & AUDIT DATES
                if (existingTask != null) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Interactive Checklists (${liveChecklist.size})", fontWeight = FontWeight.Bold)

                            liveChecklist.forEach { item ->
                                var showItemNote by remember { mutableStateOf(false) }
                                var itemNoteText by remember(item.notes) { mutableStateOf(item.notes ?: "") }

                                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = item.isDone, onCheckedChange = { viewModel.toggleChecklistItem(item) })
                                        Text(
                                            text = item.text,
                                            style = MaterialTheme.typography.bodyLarge,
                                            textDecoration = if (item.isDone) TextDecoration.LineThrough else null,
                                            modifier = Modifier.weight(1f)
                                        )
                                        IconButton(onClick = { showItemNote = !showItemNote }) {
                                            Icon(Icons.Default.NoteAlt, contentDescription = "Edit Item Note")
                                        }
                                        IconButton(onClick = { viewModel.moveChecklistItem(item, true) }) {
                                            Icon(Icons.Default.ArrowDropUp, contentDescription = "Move Up")
                                        }
                                        IconButton(onClick = { viewModel.moveChecklistItem(item, false) }) {
                                            Icon(Icons.Default.ArrowDropDown, contentDescription = "Move Down")
                                        }
                                        IconButton(onClick = { viewModel.deleteChecklistItem(item) }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                                        }
                                    }

                                    // Item Modified Stamp
                                    Text(
                                        "Modified: ${dateFormat.format(Date(item.lastModifiedTimestamp))}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.padding(start = 40.dp)
                                    )

                                    if (showItemNote) {
                                        OutlinedTextField(
                                            value = itemNoteText,
                                            onValueChange = {
                                                itemNoteText = it
                                                viewModel.updateChecklistItem(item, item.text, it, item.isDone)
                                            },
                                            label = { Text("Checklist Item Note") },
                                            modifier = Modifier.fillMaxWidth().padding(start = 40.dp, top = 4.dp)
                                        )
                                    }
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = newChecklistText,
                                    onValueChange = { newChecklistText = it },
                                    placeholder = { Text("New checklist item...") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(8.dp))
                                Button(onClick = {
                                    if (newChecklistText.isNotBlank()) {
                                        viewModel.addChecklistItem(existingTask.id, newChecklistText)
                                        newChecklistText = ""
                                    }
                                }) {
                                    Text("Add")
                                }
                            }
                        }
                    }
                }

                // MULTI-ATTACHMENTS & CONTACTS ENGINE
                if (existingTask != null) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Attachments, Videos, Audios & Contacts", fontWeight = FontWeight.Bold)

                            // Action Buttons
                            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { filePickerLauncher.launch(arrayOf("*/*")) }) {
                                    Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Add Files")
                                }

                                OutlinedButton(onClick = {
                                    val videoFile = File(context.cacheDir, "video_${System.currentTimeMillis()}.mp4")
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", videoFile)
                                    tempVideoUri = uri
                                    videoRecordLauncher.launch(uri)
                                }) {
                                    Icon(Icons.Default.Videocam, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Record Video")
                                }

                                OutlinedButton(onClick = {
                                    if (isRecordingAudio) {
                                        recordedAudioPath = audioHelper.stopRecording()
                                        isRecordingAudio = false
                                        recordedAudioPath?.let {
                                            viewModel.addAttachment(existingTask.id, AttachmentType.AUDIO, it, "Voice Memo ${dateFormat.format(Date())}")
                                        }
                                    } else {
                                        audioHelper.startRecording()
                                        isRecordingAudio = true
                                    }
                                }) {
                                    Icon(if (isRecordingAudio) Icons.Default.Stop else Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (isRecordingAudio) "Stop Audio" else "Record Audio")
                                }

                                OutlinedButton(onClick = { contactPickerLauncher.launch(null) }) {
                                    Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Phonebook Contact")
                                }
                            }

                            // Manual Contact Input
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = manualContactName,
                                    onValueChange = { manualContactName = it },
                                    label = { Text("Name") },
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(6.dp))
                                OutlinedTextField(
                                    value = manualPhone,
                                    onValueChange = { manualPhone = it },
                                    label = { Text("Mobile") },
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(6.dp))
                                Button(onClick = {
                                    if (manualPhone.isNotBlank()) {
                                        viewModel.addAttachment(
                                            taskId = existingTask.id,
                                            type = AttachmentType.CONTACT,
                                            uriString = "tel:$manualPhone",
                                            displayName = if (manualContactName.isBlank()) "Contact" else manualContactName,
                                            contactPhone = manualPhone
                                        )
                                        manualContactName = ""
                                        manualPhone = ""
                                    }
                                }) {
                                    Text("Save")
                                }
                            }

                            Divider(modifier = Modifier.padding(vertical = 4.dp))

                            // Attachment List
                            liveAttachments.forEach { att ->
                                var showAttNote by remember { mutableStateOf(false) }
                                var attNoteText by remember(att.notes) { mutableStateOf(att.notes ?: "") }

                                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = when (att.type) {
                                                    AttachmentType.VIDEO -> Icons.Default.Videocam
                                                    AttachmentType.AUDIO -> Icons.Default.Mic
                                                    AttachmentType.CONTACT -> Icons.Default.Person
                                                    AttachmentType.IMAGE -> Icons.Default.Image
                                                    AttachmentType.FILE -> Icons.Default.AttachFile
                                                },
                                                contentDescription = null
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Column(modifier = Modifier.weight(1f).clickable {
                                                // Preview / Open intent
                                                try {
                                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                                        setDataAndType(Uri.parse(att.uriString), "*/*")
                                                        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                                                    }
                                                    context.startActivity(intent)
                                                } catch (_: Exception) {
                                                    Toast.makeText(context, "No app found to preview this attachment", Toast.LENGTH_SHORT).show()
                                                }
                                            }) {
                                                Text(att.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                                                att.contactPhone?.let { Text("Tel: $it", style = MaterialTheme.typography.bodySmall) }
                                                Text("Modified: ${dateFormat.format(Date(att.lastModifiedTimestamp))}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                            }

                                            // Status Radio Button for Contacts
                                            if (att.type == AttachmentType.CONTACT) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    RadioButton(
                                                        selected = !att.isContactPending,
                                                        onClick = {
                                                            viewModel.updateAttachment(att, att.displayName, att.notes, att.contactPhone, !att.isContactPending)
                                                        }
                                                    )
                                                    Text(if (att.isContactPending) "Pending" else "Done", style = MaterialTheme.typography.labelSmall)
                                                }
                                            }

                                            IconButton(onClick = { showAttNote = !showAttNote }) {
                                                Icon(Icons.Default.EditNote, contentDescription = "Edit Note")
                                            }
                                            IconButton(onClick = { viewModel.moveAttachment(att, true) }) {
                                                Icon(Icons.Default.ArrowDropUp, contentDescription = "Up")
                                            }
                                            IconButton(onClick = { viewModel.moveAttachment(att, false) }) {
                                                Icon(Icons.Default.ArrowDropDown, contentDescription = "Down")
                                            }
                                            IconButton(onClick = { viewModel.deleteAttachment(att) }) {
                                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                                            }
                                        }

                                        if (showAttNote) {
                                            OutlinedTextField(
                                                value = attNoteText,
                                                onValueChange = {
                                                    attNoteText = it
                                                    viewModel.updateAttachment(att, att.displayName, it, att.contactPhone, att.isContactPending)
                                                },
                                                label = { Text("Attachment Note") },
                                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
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
    }
}

// -----------------------------------------------------------------------------------------
// REUSABLE HELPER UI COMPONENTS
// -----------------------------------------------------------------------------------------
@Composable
fun PriorityBadge(priority: TaskPriority) {
    val bg = when (priority) {
        TaskPriority.URGENT -> Color(0xFFD32F2F)
        TaskPriority.HIGH -> Color(0xFFF57C00)
        TaskPriority.MEDIUM -> Color(0xFF0288D1)
        TaskPriority.LOW -> Color(0xFF689F38)
    }
    Surface(color = bg, shape = RoundedCornerShape(4.dp)) {
        Text(
            text = priority.name,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

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
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 350.dp)) {
                item {
                    ListItem(
                        headlineContent = { Text("★ Root Level (Main Task)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable { onSelectTarget(null) }
                    )
                    Divider()
                }
                items(potentialParents, key = { it.id }) { parentCandidate ->
                    ListItem(
                        headlineContent = { Text(parentCandidate.title) },
                        supportingContent = { Text(if (parentCandidate.parentId == null) "Main Task" else "Subtask", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.clickable { onSelectTarget(parentCandidate.id) }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
