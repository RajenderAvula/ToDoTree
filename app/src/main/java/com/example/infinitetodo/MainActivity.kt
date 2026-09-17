package com.example.infinitetodo

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
import kotlin.math.ceil
import kotlin.math.roundToInt

val fullDateTimeFormat: SimpleDateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
val shortDateFormat: SimpleDateFormat = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
val dateOnlyFormat: SimpleDateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
val timeOnlyFormat: SimpleDateFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())

const val TASKS_PER_PAGE = 10

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
    val scope = rememberCoroutineScope()

    var taskForAdvancedTransfer by remember { mutableStateOf<Pair<TaskItem, Boolean>?>(null) }

    val context = LocalContext.current

    val permissionsToRequest = remember {
        val list = mutableListOf(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
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
                        scope.launch {
                            val draft = viewModel.createInitialDraftTask(null)
                            activeFullScreenTask = draft
                        }
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
                    onOpenTask = { task -> activeFullScreenTask = task }
                )
                AppNavTab.TASKS -> TasksTreeTab(
                    viewModel = viewModel,
                    viewMode = viewMode,
                    onAddSubtask = { parentId ->
                        scope.launch {
                            val draft = viewModel.createInitialDraftTask(parentId)
                            activeFullScreenTask = draft
                        }
                    },
                    onOpenFullScreen = { task -> activeFullScreenTask = task },
                    onMoveTask = { task -> taskForAdvancedTransfer = Pair(task, false) },
                    onCopyTask = { task -> taskForAdvancedTransfer = Pair(task, true) }
                )
                AppNavTab.CALENDAR -> CalendarAgendaTab(
                    viewModel = viewModel,
                    onOpenTask = { task -> activeFullScreenTask = task }
                )
                AppNavTab.GANTT -> GanttChartTab(
                    viewModel = viewModel,
                    onOpenTask = { task -> activeFullScreenTask = task }
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

        activeFullScreenTask?.let { taskToEdit ->
            key(taskToEdit.id) {
                FullScreenTaskWorkspaceDialog(
                    initialTask = taskToEdit,
                    viewModel = viewModel,
                    onDismiss = { activeFullScreenTask = null }
                )
            }
        }

        taskForAdvancedTransfer?.let { (task, isCopy) ->
            AdvancedTaskTransferDialog(
                task = task,
                isCopy = isCopy,
                viewModel = viewModel,
                onDismiss = { taskForAdvancedTransfer = null }
            )
        }
    }
}

// -----------------------------------------------------------------------------------------
// PAGINATION CONTROLLER WIDGET (PREVENTS OVERLAPPING THE CREATE TASK FAB)
// -----------------------------------------------------------------------------------------
@Composable
fun TaskPaginationBar(
    currentPage: Int,
    totalItems: Int,
    itemsPerPage: Int = TASKS_PER_PAGE,
    onPageChange: (Int) -> Unit
) {
    val totalPages = remember(totalItems, itemsPerPage) {
        ceil(totalItems.toDouble() / itemsPerPage).toInt().coerceAtLeast(1)
    }

    if (totalPages > 1) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .padding(end = 76.dp) // Added right margin so FAB never obstructs the Next button
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { onPageChange(currentPage - 1) },
                    enabled = currentPage > 1,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Prev")
                }

                Text(
                    text = "Page $currentPage / $totalPages",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )

                OutlinedButton(
                    onClick = { onPageChange(currentPage + 1) },
                    enabled = currentPage < totalPages,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("Next")
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowRight, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------------------
// 1. HOME DASHBOARD TAB (NOTIFICATION ICON ON LEFT & MULTI-TASK DELETION)
// -----------------------------------------------------------------------------------------
@Composable
fun HomeDashboardTab(
    viewModel: TaskViewModel,
    onOpenTask: (TaskItem) -> Unit
) {
    val allTasks by viewModel.allTasksFlow.collectAsState(initial = emptyList())
    var searchQuery by remember { mutableStateOf("") }
    val searchResults by viewModel.searchTasks(searchQuery).collectAsState(initial = emptyList())
    val filterState by viewModel.filterState.collectAsState()

    var showTodayNotificationsDialog by remember { mutableStateOf(false) }
    var currentPage by remember { mutableIntStateOf(1) }

    // Multi-Task Batch Selection State
    var isMultiSelectTasksMode by remember { mutableStateOf(false) }
    var selectedTaskIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showBatchDeleteTasksConfirm by remember { mutableStateOf(false) }

    val todayDueTasks = remember(allTasks) {
        val endOfTodayMs = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis

        allTasks.filter { task ->
            if (task.isCompleted) return@filter false

            val hasDueTodayOrPast = task.dueTimestamp != null && task.dueTimestamp <= endOfTodayMs
            val hasReminderTodayOrPast = task.reminderTimestamp != null && task.reminderTimestamp <= endOfTodayMs
            val hasRepeatActive = task.repeatRule != RecurrenceRule.NONE && (task.repeatStartDate == null || task.repeatStartDate <= endOfTodayMs)

            hasDueTodayOrPast || hasReminderTodayOrPast || hasRepeatActive
        }
    }

    val totalCreated = allTasks.size
    val totalCompleted = allTasks.count { it.isCompleted }
    val totalPending = totalCreated - totalCompleted
    val urgentCount = allTasks.count { it.priority == TaskPriority.URGENT }
    val highCount = allTasks.count { it.priority == TaskPriority.HIGH }
    val medCount = allTasks.count { it.priority == TaskPriority.MEDIUM }
    val lowCount = allTasks.count { it.priority == TaskPriority.LOW }

    val displayedTasks = remember(allTasks, searchResults, searchQuery, filterState) {
        val base = if (searchQuery.isNotBlank()) searchResults else allTasks
        base.filter { task ->
            (filterState.priorities.isEmpty() || task.priority in filterState.priorities) &&
            (filterState.statusPending == null || (if (filterState.statusPending == true) !task.isCompleted else task.isCompleted)) &&
            (filterState.selectedTags.isEmpty() || (task.tags?.split(",")?.map { it.trim() }?.any { it in filterState.selectedTags } == true)) &&
            (!filterState.mustHaveLocation || (!task.locationName.isNullOrBlank() || task.latitude != null)) &&
            (filterState.selectedLocations.isEmpty() || (task.locationName != null && task.locationName in filterState.selectedLocations)) &&
            (filterState.createdFromMs == null || (task.createdTimestamp in filterState.createdFromMs!!..filterState.createdToMs!!)) &&
            (filterState.dueFromMs == null || (task.dueTimestamp != null && task.dueTimestamp in filterState.dueFromMs!!..filterState.dueToMs!!))
        }
    }

    val paginatedTasks = remember(displayedTasks, currentPage) {
        val startIndex = (currentPage - 1) * TASKS_PER_PAGE
        displayedTasks.drop(startIndex).take(TASKS_PER_PAGE)
    }

    if (showBatchDeleteTasksConfirm) {
        DeleteConfirmationDialog(
            title = "Delete ${selectedTaskIds.size} Task(s)?",
            message = "Are you sure you want to delete the ${selectedTaskIds.size} selected tasks? All their subtasks, checklists, attachments and calendar entries will also be permanently deleted.",
            onConfirm = {
                viewModel.deleteTasksBatch(selectedTaskIds)
                selectedTaskIds = emptySet()
                isMultiSelectTasksMode = false
                showBatchDeleteTasksConfirm = false
            },
            onDismiss = { showBatchDeleteTasksConfirm = false }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TaskFilterHeaderBar(viewModel, searchQuery) { searchQuery = it }

        // BATCH MULTI-TASK SELECTION TOOLBAR WITH CANCEL OPTION
        if (isMultiSelectTasksMode) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${selectedTaskIds.size} selected",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            onClick = {
                                if (selectedTaskIds.size == displayedTasks.size) {
                                    selectedTaskIds = emptySet()
                                } else {
                                    selectedTaskIds = displayedTasks.map { it.id }.toSet()
                                }
                            }
                        ) {
                            Text(if (selectedTaskIds.size == displayedTasks.size) "Deselect All" else "Select All")
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (selectedTaskIds.isNotEmpty()) {
                            Button(
                                onClick = { showBatchDeleteTasksConfirm = true },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Delete")
                            }
                        }

                        // Cancel Option for Multi-Select Mode
                        OutlinedButton(
                            onClick = {
                                isMultiSelectTasksMode = false
                                selectedTaskIds = emptySet()
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Cancel")
                        }
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // ROW WITH NOTIFICATION ICON PLACED DIRECTLY ON THE LEFT SIDE
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 1. Notification Alert Icon (Far Left)
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier.padding(end = 10.dp)
                            ) {
                                IconButton(
                                    onClick = { showTodayNotificationsDialog = true },
                                    modifier = Modifier.size(38.dp)
                                ) {
                                    BadgedBox(
                                        badge = {
                                            if (todayDueTasks.isNotEmpty()) {
                                                Badge(
                                                    containerColor = MaterialTheme.colorScheme.error,
                                                    contentColor = MaterialTheme.colorScheme.onError
                                                ) {
                                                    Text("${todayDueTasks.size}")
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = if (todayDueTasks.isNotEmpty()) Icons.Default.NotificationsActive else Icons.Default.NotificationsNone,
                                            contentDescription = "Alerts",
                                            tint = if (todayDueTasks.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                            }

                            // 2. Title Text
                            Text(
                                text = "Task Analytics & Summary",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )

                            // 3. Multi-Select Task Mode Toggle
                            if (!isMultiSelectTasksMode && displayedTasks.isNotEmpty()) {
                                TextButton(onClick = { isMultiSelectTasksMode = true }) {
                                    Icon(Icons.Default.Checklist, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Select")
                                }
                            }
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("$totalCreated", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                Text("Created", style = MaterialTheme.typography.labelSmall)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("$totalPending", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color(0xFFF57C00))
                                Text("Pending", style = MaterialTheme.typography.labelSmall)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("$totalCompleted", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color(0xFF388E3C))
                                Text("Completed", style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        HorizontalDivider()

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Urgent: $urgentCount", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold, color = Color(0xFFD32F2F))
                            Text("High: $highCount", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color(0xFFF57C00))
                            Text("Med: $medCount", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color(0xFF0288D1))
                            Text("Low: $lowCount", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color(0xFF689F38))
                        }
                    }
                }
            }

            items(paginatedTasks, key = { it.id }) { task ->
                val subtaskCount by viewModel.getSubtaskCount(task.id).collectAsState(initial = 0)
                val attachments by viewModel.getAttachments(task.id).collectAsState(initial = emptyList())
                val contacts = remember(attachments) { attachments.filter { it.type == AttachmentType.CONTACT } }

                val isTaskSelected = task.id in selectedTaskIds

                var hierarchyPath by remember { mutableStateOf("") }
                val scope = rememberCoroutineScope()
                LaunchedEffect(task.id) {
                    scope.launch { hierarchyPath = viewModel.getHierarchyPathString(task.id) }
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isTaskSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier
                        )
                        .clickable {
                            if (isMultiSelectTasksMode) {
                                selectedTaskIds = if (isTaskSelected) selectedTaskIds - task.id else selectedTaskIds + task.id
                            } else {
                                onOpenTask(task)
                            }
                        },
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        if (hierarchyPath.isNotBlank()) {
                            Text(
                                text = "Path: $hierarchyPath",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                            if (isMultiSelectTasksMode) {
                                Checkbox(
                                    checked = isTaskSelected,
                                    onCheckedChange = {
                                        selectedTaskIds = if (isTaskSelected) selectedTaskIds - task.id else selectedTaskIds + task.id
                                    }
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            PriorityBadge(task.priority)
                            if (subtaskCount > 0) {
                                Spacer(Modifier.width(8.dp))
                                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(4.dp)) {
                                    Text("[$subtaskCount subtasks]", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                                }
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!isMultiSelectTasksMode) {
                                Checkbox(
                                    checked = task.isCompleted,
                                    onCheckedChange = { viewModel.toggleTaskCompletion(task) }
                                )
                                Spacer(Modifier.width(6.dp))
                            }

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        if (isMultiSelectTasksMode) {
                                            selectedTaskIds = if (isTaskSelected) selectedTaskIds - task.id else selectedTaskIds + task.id
                                        } else {
                                            onOpenTask(task)
                                        }
                                    }
                            ) {
                                HighlightedText(
                                    text = task.title.ifBlank { "Untitled Task" },
                                    query = searchQuery,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        textDecoration = if (task.isCompleted) TextDecoration.LineThrough else null
                                    ),
                                    fontWeight = FontWeight.SemiBold
                                )

                                if (!task.tags.isNullOrBlank()) {
                                    Text(
                                        text = task.tags.split(",").joinToString(" ") { "#${it.trim()}" },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }

                                if (!task.notes.isNullOrBlank()) {
                                    HighlightedText(
                                        text = task.notes,
                                        query = searchQuery,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                            IconButton(
                                onClick = { onOpenTask(task) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.OpenInFull, contentDescription = "Open Full Screen", tint = MaterialTheme.colorScheme.primary)
                            }
                        }

                        TaskMetadataStatusRow(task)

                        if (!task.locationName.isNullOrBlank()) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(12.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(task.locationName, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }

                        if (contacts.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(top = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                contacts.forEach { contact ->
                                    val phone = contact.contactPhone ?: ""
                                    if (phone.isNotBlank()) {
                                        ContactActionRow(
                                            displayName = contact.displayName,
                                            phoneNumber = phone,
                                            isPending = contact.isContactPending,
                                            onTogglePending = {
                                                viewModel.updateAttachment(
                                                    contact,
                                                    contact.displayName,
                                                    contact.notes,
                                                    contact.contactPhone,
                                                    !contact.isContactPending
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // PAGINATION FOOTER (10 TASKS PER PAGE)
            item {
                TaskPaginationBar(
                    currentPage = currentPage,
                    totalItems = displayedTasks.size,
                    itemsPerPage = TASKS_PER_PAGE,
                    onPageChange = { currentPage = it }
                )
            }
        }

        if (showTodayNotificationsDialog) {
            AlertDialog(
                onDismissRequest = { showTodayNotificationsDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text("Active Alerts Till Today (${todayDueTasks.size})")
                    }
                },
                text = {
                    if (todayDueTasks.isEmpty()) {
                        Text("No pending tasks due or scheduled for reminder/repeat today.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 380.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(todayDueTasks, key = { it.id }) { item ->
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            showTodayNotificationsDialog = false
                                            onOpenTask(item)
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            Text(item.title.ifBlank { "Untitled" }, fontWeight = FontWeight.Bold)

                                            item.dueTimestamp?.let { due ->
                                                Text(
                                                    text = "📅 Due: ${SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(due))}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.error,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }

                                            item.reminderTimestamp?.let { rem ->
                                                Text(
                                                    text = "⏰ Reminder: ${SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(rem))}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }

                                            if (item.repeatRule != RecurrenceRule.NONE) {
                                                val repDetail = when (item.repeatRule) {
                                                    RecurrenceRule.CUSTOM -> "Every ${item.repeatIntervalDays}d ${item.repeatIntervalHours}h ${item.repeatIntervalMinutes}m"
                                                    else -> item.repeatRule.name
                                                }
                                                Text(
                                                    text = "🔁 Repeat: $repDetail",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.tertiary,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                        }
                                        Icon(Icons.AutoMirrored.Filled.ArrowRight, contentDescription = "Open Task")
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showTodayNotificationsDialog = false }) {
                        Text("Close")
                    }
                }
            )
        }
    }
}

// -----------------------------------------------------------------------------------------
// 2. TASKS TREE TAB (WITH 10 TASKS PER PAGE PAGINATION & BATCH DELETE)
// -----------------------------------------------------------------------------------------
@Composable
fun TasksTreeTab(
    viewModel: TaskViewModel,
    viewMode: TaskViewMode,
    onAddSubtask: (Long) -> Unit,
    onOpenFullScreen: (TaskItem) -> Unit,
    onMoveTask: (TaskItem) -> Unit,
    onCopyTask: (TaskItem) -> Unit
) {
    val rootTasks by viewModel.rootTasks.collectAsState(initial = emptyList())
    var searchQuery by remember { mutableStateOf("") }
    val searchResults by viewModel.searchTasks(searchQuery).collectAsState(initial = emptyList())
    val filterState by viewModel.filterState.collectAsState()
    val context = LocalContext.current

    var currentPage by remember { mutableIntStateOf(1) }

    // Multi-Task Batch Selection State
    var isMultiSelectTreeMode by remember { mutableStateOf(false) }
    var selectedTaskIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }

    val displayedTasks = remember(rootTasks, searchResults, searchQuery, filterState) {
        val base = if (searchQuery.isNotBlank()) searchResults else rootTasks
        base.filter { task ->
            (filterState.priorities.isEmpty() || task.priority in filterState.priorities) &&
            (filterState.statusPending == null || (if (filterState.statusPending == true) !task.isCompleted else task.isCompleted)) &&
            (filterState.selectedTags.isEmpty() || (task.tags?.split(",")?.map { it.trim() }?.any { it in filterState.selectedTags } == true)) &&
            (!filterState.mustHaveLocation || (!task.locationName.isNullOrBlank() || task.latitude != null)) &&
            (filterState.selectedLocations.isEmpty() || (task.locationName != null && task.locationName in filterState.selectedLocations)) &&
            (filterState.createdFromMs == null || (task.createdTimestamp in filterState.createdFromMs!!..filterState.createdToMs!!)) &&
            (filterState.dueFromMs == null || (task.dueTimestamp != null && task.dueTimestamp in filterState.dueFromMs!!..filterState.dueToMs!!))
        }
    }

    val paginatedTasks = remember(displayedTasks, currentPage) {
        val startIndex = (currentPage - 1) * TASKS_PER_PAGE
        displayedTasks.drop(startIndex).take(TASKS_PER_PAGE)
    }

    if (showBatchDeleteConfirm) {
        DeleteConfirmationDialog(
            title = "Delete ${selectedTaskIds.size} Task(s)?",
            message = "Are you sure you want to delete the ${selectedTaskIds.size} selected tasks and their subtrees?",
            onConfirm = {
                viewModel.deleteTasksBatch(selectedTaskIds)
                selectedTaskIds = emptySet()
                isMultiSelectTreeMode = false
                showBatchDeleteConfirm = false
            },
            onDismiss = { showBatchDeleteConfirm = false }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TaskFilterHeaderBar(viewModel, searchQuery) { searchQuery = it }

        // Multi-Task Action Bar
        if (isMultiSelectTreeMode) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${selectedTaskIds.size} selected",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            onClick = {
                                if (selectedTaskIds.size == displayedTasks.size) {
                                    selectedTaskIds = emptySet()
                                } else {
                                    selectedTaskIds = displayedTasks.map { it.id }.toSet()
                                }
                            }
                        ) {
                            Text(if (selectedTaskIds.size == displayedTasks.size) "Deselect All" else "Select All")
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (selectedTaskIds.isNotEmpty()) {
                            Button(
                                onClick = { showBatchDeleteConfirm = true },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Delete")
                            }
                        }

                        // Cancel Option for Multi-Select Mode
                        OutlinedButton(
                            onClick = {
                                isMultiSelectTreeMode = false
                                selectedTaskIds = emptySet()
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Cancel")
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Hierarchical Tasks", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row {
                IconButton(onClick = { isMultiSelectTreeMode = !isMultiSelectTreeMode }) {
                    Icon(
                        Icons.Default.Checklist,
                        contentDescription = "Multi Select",
                        tint = if (isMultiSelectTreeMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    )
                }
                IconButton(onClick = {
                    viewModel.syncAllTasksToCalendar { count ->
                        Toast.makeText(context, "Synced $count task(s) to Google Calendar", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Icon(Icons.Default.Sync, contentDescription = "Sync All")
                }
                IconButton(onClick = {
                    PrintHelper.printTasks(context, "Hierarchy Tree Print", displayedTasks)
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
            items(paginatedTasks, key = { it.id }) { task ->
                val isSelected = task.id in selectedTaskIds

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isMultiSelectTreeMode) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = {
                                selectedTaskIds = if (isSelected) selectedTaskIds - task.id else selectedTaskIds + task.id
                            },
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        TaskNodeView(
                            task = task,
                            depth = 0,
                            viewMode = viewMode,
                            searchQuery = searchQuery,
                            viewModel = viewModel,
                            onAddSubtask = onAddSubtask,
                            onOpenFullScreen = onOpenFullScreen,
                            onMoveTask = onMoveTask,
                            onCopyTask = onCopyTask
                        )
                    }
                }
            }

            item {
                TaskPaginationBar(
                    currentPage = currentPage,
                    totalItems = displayedTasks.size,
                    itemsPerPage = TASKS_PER_PAGE,
                    onPageChange = { currentPage = it }
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------------------
// FULL SCREEN NOTES/DESCRIPTION DEDICATED EDITOR DIALOG
// -----------------------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenNotesEditorDialog(
    initialNotes: String,
    taskTitle: String,
    onDismiss: () -> Unit,
    onSaveNotes: (String) -> Unit
) {
    var editableNotes by remember { mutableStateOf(initialNotes) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text("Notes: ${taskTitle.ifBlank { "Task" }}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    },
                    actions = {
                        Button(
                            onClick = {
                                onSaveNotes(editableNotes)
                                onDismiss()
                            }
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Done")
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
            ) {
                OutlinedTextField(
                    value = editableNotes,
                    onValueChange = { editableNotes = it },
                    placeholder = { Text("Write extensive descriptions, notes, ideas...") },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------------------
// SINGLE TASK WORKSPACE (SAVE EXITS FULL SCREEN & EXPANDABLE DESCRIPTION TAB)
// -----------------------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SingleTaskEditorView(
    task: TaskItem,
    viewModel: TaskViewModel,
    onDismiss: () -> Unit,
    onMoveTask: (TaskItem) -> Unit,
    onCopyTask: (TaskItem) -> Unit,
    onOpenReferencedCrossTab: (TaskItem) -> Unit
) {
    val context = LocalContext.current
    val audioHelper = remember { AudioRecorderHelper(context) }
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }
    val scope = rememberCoroutineScope()

    var title by remember(task.id) { mutableStateOf(task.title) }
    var notes by remember(task.id) { mutableStateOf(task.notes ?: "") }
    var tagsText by remember(task.id) { mutableStateOf(task.tags ?: "") }
    var priority by remember(task.id) { mutableStateOf(task.priority) }

    var createdMs by remember(task.id) { mutableLongStateOf(task.createdTimestamp) }
    var reminderMs by remember(task.id) { mutableStateOf(task.reminderTimestamp) }
    var dueMs by remember(task.id) { mutableStateOf(task.dueTimestamp) }

    var locationName by remember(task.id) { mutableStateOf(task.locationName ?: "") }
    var latitude by remember(task.id) { mutableStateOf(task.latitude) }
    var longitude by remember(task.id) { mutableStateOf(task.longitude) }
    var isResolvingLocation by remember { mutableStateOf(false) }

    var repeatRule by remember(task.id) { mutableStateOf(task.repeatRule) }
    var repeatDaysText by remember(task.id) { mutableStateOf(task.repeatIntervalDays.toString()) }
    var repeatHoursText by remember(task.id) { mutableStateOf(task.repeatIntervalHours.toString()) }
    var repeatMinutesText by remember(task.id) { mutableStateOf(task.repeatIntervalMinutes.toString()) }
    var repeatStartDate by remember(task.id) { mutableStateOf(task.repeatStartDate ?: System.currentTimeMillis()) }
    var repeatStartTimeMs by remember(task.id) { mutableStateOf(task.repeatStartTimeMs ?: System.currentTimeMillis()) }
    var repeatEndTimeMs by remember(task.id) { mutableStateOf(task.repeatEndTimeMs ?: (System.currentTimeMillis() + 43200000L)) }

    var isRecordingAudio by remember { mutableStateOf(false) }
    var recordedAudioPath by remember { mutableStateOf<String?>(null) }
    var newChecklistText by remember { mutableStateOf("") }
    var manualPhone by remember { mutableStateOf("") }
    var manualContactName by remember { mutableStateOf("") }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var itemPendingDeleteChecklist by remember { mutableStateOf<ChecklistItem?>(null) }

    // Multi-File Attachment Batch Delete State
    var selectedAttachmentIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var isAttachmentSelectionMode by remember { mutableStateOf(false) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }

    // Full-Screen Notes Workspace Sheet State
    var showFullScreenNotesDialog by remember { mutableStateOf(false) }

    val liveChecklist by viewModel.getChecklist(task.id).collectAsState(initial = emptyList())
    val liveAttachments by viewModel.getAttachments(task.id).collectAsState(initial = emptyList())
    val allTasks by viewModel.allTasksFlow.collectAsState(initial = emptyList())

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val fineGranted = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (fineGranted || coarseGranted) {
            isResolvingLocation = true
            LocationAndContactHelper.requestFreshLocation(
                context = context,
                onLocationFound = { loc ->
                    latitude = loc.latitude
                    longitude = loc.longitude

                    if (locationName.isBlank()) {
                        locationName = "Resolving place name..."
                    }

                    scope.launch {
                        val place = LocationAndContactHelper.resolvePlaceName(context, loc.latitude, loc.longitude)
                        locationName = place
                        isResolvingLocation = false

                        viewModel.saveTask(
                            task = task,
                            title = title,
                            notes = notes,
                            tags = tagsText,
                            priority = priority,
                            createdTimestampMs = createdMs,
                            reminderEpochMs = reminderMs,
                            dueEpochMs = dueMs,
                            repeatRule = repeatRule,
                            repeatIntervalDays = repeatDaysText.toIntOrNull() ?: 0,
                            repeatIntervalHours = repeatHoursText.toIntOrNull() ?: 0,
                            repeatIntervalMinutes = repeatMinutesText.toIntOrNull() ?: 0,
                            repeatStartDate = repeatStartDate,
                            repeatStartTimeMs = repeatStartTimeMs,
                            repeatEndTimeMs = repeatEndTimeMs,
                            linkedTaskIds = task.linkedTaskIds,
                            locationName = place,
                            latitude = loc.latitude,
                            longitude = loc.longitude
                        )
                        Toast.makeText(context, "Location saved: $place", Toast.LENGTH_SHORT).show()
                    }
                },
                onError = { err ->
                    isResolvingLocation = false
                    Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                }
            )
        } else {
            Toast.makeText(context, "Location permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    if (showFullScreenNotesDialog) {
        FullScreenNotesEditorDialog(
            initialNotes = notes,
            taskTitle = title,
            onDismiss = { showFullScreenNotesDialog = false },
            onSaveNotes = { updatedNotes ->
                notes = updatedNotes
            }
        )
    }

    if (showDeleteConfirm) {
        DeleteConfirmationDialog(
            title = "Delete Task?",
            message = "Are you sure you want to delete '${task.title.ifBlank { "this task" }}'? All subtasks, checklists, attachments, and calendar events will also be removed.",
            onConfirm = {
                viewModel.deleteTask(task)
                showDeleteConfirm = false
                onDismiss()
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }

    if (itemPendingDeleteChecklist != null) {
        DeleteConfirmationDialog(
            title = "Delete Checklist Item?",
            message = "Are you sure you want to delete '${itemPendingDeleteChecklist?.text}'?",
            onConfirm = {
                itemPendingDeleteChecklist?.let { viewModel.deleteChecklistItem(it) }
                itemPendingDeleteChecklist = null
            },
            onDismiss = { itemPendingDeleteChecklist = null }
        )
    }

    if (showBatchDeleteConfirm) {
        DeleteConfirmationDialog(
            title = "Delete ${selectedAttachmentIds.size} Attachment(s)?",
            message = "Are you sure you want to delete the ${selectedAttachmentIds.size} selected attachments permanently?",
            onConfirm = {
                val toDelete = liveAttachments.filter { it.id in selectedAttachmentIds }
                viewModel.deleteAttachmentsBatch(toDelete)
                selectedAttachmentIds = emptySet()
                isAttachmentSelectionMode = false
                showBatchDeleteConfirm = false
            },
            onDismiss = { showBatchDeleteConfirm = false }
        )
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        for (uri in uris) {
            var fileName = "Document"
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && idx != -1) fileName = c.getString(idx)
            }
            viewModel.addAttachment(task.id, AttachmentType.FILE, uri.toString(), fileName)
        }
    }

    var tempVideoUri by remember { mutableStateOf<Uri?>(null) }
    val videoRecordLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CaptureVideo()
    ) { success: Boolean ->
        if (success && tempVideoUri != null) {
            viewModel.addAttachment(
                task.id,
                AttachmentType.VIDEO,
                tempVideoUri.toString(),
                "Video Recording ${fullDateTimeFormat.format(Date())}"
            )
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

                    viewModel.addAttachment(
                        taskId = task.id,
                        type = AttachmentType.CONTACT,
                        uriString = uri.toString(),
                        displayName = name ?: "Contact",
                        contactPhone = phone
                    )
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // =========================================================================================
        // DOCKETED / STICKY HEADER BAR (PINNED ABOVE SCROLLABLE CONTENT)
        // =========================================================================================
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
            shadowElevation = 4.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Row 1: Action Controls Strip
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // SAVE BUTTON (SAVES AND EXITS FULL SCREEN WORKSPACE)
                    Button(
                        onClick = {
                            val days = repeatDaysText.toIntOrNull() ?: 0
                            val hours = repeatHoursText.toIntOrNull() ?: 0
                            val minutes = repeatMinutesText.toIntOrNull() ?: 0

                            viewModel.saveTask(
                                task = task,
                                title = title,
                                notes = notes,
                                tags = tagsText,
                                priority = priority,
                                createdTimestampMs = createdMs,
                                reminderEpochMs = reminderMs,
                                dueEpochMs = dueMs,
                                repeatRule = repeatRule,
                                repeatIntervalDays = days,
                                repeatIntervalHours = hours,
                                repeatIntervalMinutes = minutes,
                                repeatStartDate = repeatStartDate,
                                repeatStartTimeMs = repeatStartTimeMs,
                                repeatEndTimeMs = repeatEndTimeMs,
                                linkedTaskIds = task.linkedTaskIds,
                                locationName = locationName.ifBlank { null },
                                latitude = latitude,
                                longitude = longitude
                            )
                            Toast.makeText(context, "Saved changes ✓", Toast.LENGTH_SHORT).show()
                            onDismiss() // Exits full screen mode automatically
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Save")
                    }

                    OutlinedButton(
                        onClick = onDismiss,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Cancel")
                    }

                    FilledTonalButton(
                        onClick = { viewModel.shareTaskData(context, task.id) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Share")
                    }

                    OutlinedButton(
                        onClick = { viewModel.shareTaskData(context, task.id, targetPackage = "com.whatsapp") },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("WhatsApp")
                    }

                    FilledTonalButton(
                        onClick = { onMoveTask(task) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.DriveFileMove, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Move")
                    }

                    FilledTonalButton(
                        onClick = { onCopyTask(task) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Copy")
                    }

                    Button(
                        onClick = { showDeleteConfirm = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Delete")
                    }
                }

                // Row 2: Priority Badge and Created Timestamp
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PriorityBadge(priority)

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "Created: ${fullDateTimeFormat.format(Date(createdMs))}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Row 3: Due, Reminder, Repeat & Completion Chips
                TaskMetadataStatusRow(
                    isCompleted = task.isCompleted,
                    completedTimestamp = task.completedTimestamp,
                    lastModifiedTimestamp = task.lastModifiedTimestamp,
                    dueTimestamp = dueMs,
                    reminderTimestamp = reminderMs,
                    repeatRule = repeatRule,
                    repeatIntervalDays = repeatDaysText.toIntOrNull() ?: 0,
                    repeatIntervalHours = repeatHoursText.toIntOrNull() ?: 0,
                    repeatIntervalMinutes = repeatMinutesText.toIntOrNull() ?: 0
                )

                // Row 4: Location, Tags, Checklists & Attachments Summary in Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (locationName.isNotBlank() || latitude != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.clickable {
                                if (latitude != null && longitude != null) {
                                    LocationAndContactHelper.openInMap(context, latitude!!, longitude!!, locationName)
                                }
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(13.dp))
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    text = locationName.ifBlank { "GPS: ${String.format(Locale.US, "%.3f, %.3f", latitude, longitude)}" },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    if (tagsText.isNotBlank()) {
                        tagsText.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { tag ->
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "#$tag",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    if (liveChecklist.isNotEmpty()) {
                        val doneCount = liveChecklist.count { it.isDone }
                        Surface(
                            color = if (doneCount == liveChecklist.size) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Checklist, contentDescription = null, modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.outline)
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "Checklist: $doneCount/${liveChecklist.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    if (liveAttachments.isNotEmpty()) {
                        val filesCount = liveAttachments.count { it.type == AttachmentType.FILE || it.type == AttachmentType.IMAGE }
                        val audiosCount = liveAttachments.count { it.type == AttachmentType.AUDIO }
                        val videosCount = liveAttachments.count { it.type == AttachmentType.VIDEO }
                        val contactsCount = liveAttachments.count { it.type == AttachmentType.CONTACT }

                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.secondary)
                                Spacer(Modifier.width(3.dp))
                                val summaryParts = mutableListOf<String>()
                                if (filesCount > 0) summaryParts.add("$filesCount files")
                                if (audiosCount > 0) summaryParts.add("$audiosCount audio")
                                if (videosCount > 0) summaryParts.add("$videosCount video")
                                if (contactsCount > 0) summaryParts.add("$contactsCount contacts")
                                Text(
                                    text = summaryParts.joinToString(", "),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }

        // =========================================================================================
        // SCROLLABLE BODY CONTENT
        // =========================================================================================
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (task.isCompleted) {
                val doneDate = task.completedTimestamp ?: task.lastModifiedTimestamp
                Surface(
                    color = Color(0xFFE8F5E9),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Task Completed: ${fullDateTimeFormat.format(Date(doneDate))}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF2E7D32),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Task Title *") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // DESCRIPTION & NOTES WITH FULL-SCREEN EXPANSION OPTION
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Description & Notes", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    TextButton(
                        onClick = { showFullScreenNotesDialog = true }
                    ) {
                        Icon(Icons.Default.OpenInFull, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Open Full Screen Notes")
                    }
                }

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    placeholder = { Text("Add notes, descriptions, links...") },
                    minLines = 4,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(6.dp))
                            Text("Task Location", fontWeight = FontWeight.Bold)
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilledTonalButton(
                                enabled = !isResolvingLocation,
                                onClick = {
                                    locationPermissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                    )
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                if (isResolvingLocation) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Finding place...", style = MaterialTheme.typography.labelSmall)
                                } else {
                                    Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Pin GPS", style = MaterialTheme.typography.labelSmall)
                                }
                            }

                            if (latitude != null && longitude != null) {
                                OutlinedButton(
                                    onClick = {
                                        LocationAndContactHelper.openInMap(
                                            context,
                                            latitude!!,
                                            longitude!!,
                                            locationName.ifBlank { null }
                                        )
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Map", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = locationName,
                        onValueChange = { locationName = it },
                        label = { Text("Location Name / Place") },
                        placeholder = { Text("e.g. Office, Starbucks, Campus Hall B") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            if (locationName.isNotEmpty() || latitude != null) {
                                IconButton(onClick = {
                                    locationName = ""
                                    latitude = null
                                    longitude = null
                                }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear Location")
                                }
                            }
                        }
                    )

                    if (latitude != null && longitude != null) {
                        Text(
                            text = "GPS Coordinates: ${String.format(Locale.US, "%.5f", latitude)}, ${String.format(Locale.US, "%.5f", longitude)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            OutlinedTextField(
                value = tagsText,
                onValueChange = { tagsText = it },
                label = { Text("Tags (comma separated)") },
                placeholder = { Text("e.g. work, shopping, urgent") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Scheduled / Created Time", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(
                            onClick = {
                                val cal = Calendar.getInstance().apply { timeInMillis = createdMs }
                                DatePickerDialog(context, { _, y, m, d ->
                                    TimePickerDialog(context, { _, h, min ->
                                        cal.set(y, m, d, h, min, 0)
                                        createdMs = cal.timeInMillis
                                    }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
                                }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                            }
                        ) {
                            Icon(Icons.Default.EditCalendar, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(fullDateTimeFormat.format(Date(createdMs)))
                        }
                    }
                    Text(
                        text = "Modified: ${fullDateTimeFormat.format(Date(task.lastModifiedTimestamp))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Priority Level:", fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TaskPriority.values().forEach { p ->
                        FilterChip(
                            selected = priority == p,
                            onClick = { priority = p },
                            label = {
                                if (p == TaskPriority.URGENT) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Whatshot, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("🔥 URGENT", fontWeight = FontWeight.ExtraBold)
                                    }
                                } else {
                                    Text(p.name, fontWeight = FontWeight.Bold)
                                }
                            },
                            colors = if (p == TaskPriority.URGENT) {
                                FilterChipDefaults.filterChipColors(
                                    containerColor = if (priority == p) Color(0xFFD32F2F) else Color(0xFFFFEBEE),
                                    labelColor = if (priority == p) Color.White else Color(0xFFD32F2F)
                                )
                            } else FilterChipDefaults.filterChipColors()
                        )
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Schedule & Due Dates", fontWeight = FontWeight.Bold)

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
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
                                Text(reminderMs?.let { "Remind: ${dateFormat.format(Date(it))}" } ?: "Set Reminder", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (reminderMs != null) {
                                IconButton(onClick = { reminderMs = null }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Default.Clear, contentDescription = "Cancel Reminder", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                }
                            }
                        }

                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
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
                                Text(dueMs?.let { "Due: ${dateFormat.format(Date(it))}" } ?: "Set Due Date", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (dueMs != null) {
                                IconButton(onClick = { dueMs = null }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Default.Clear, contentDescription = "Cancel Due Date", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }

                    HorizontalDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Repeat Configuration", fontWeight = FontWeight.Bold)
                        if (repeatRule != RecurrenceRule.NONE) {
                            TextButton(onClick = {
                                repeatRule = RecurrenceRule.NONE
                                repeatDaysText = "0"
                                repeatHoursText = "0"
                                repeatMinutesText = "0"
                            }) {
                                Text("Cancel Repeat", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        RecurrenceRule.values().forEach { rule ->
                            val ruleLabel = when (rule) {
                                RecurrenceRule.NONE -> "None"
                                RecurrenceRule.DAILY -> "Daily"
                                RecurrenceRule.WEEKLY -> "Weekly"
                                RecurrenceRule.FORTNIGHTLY -> "Fortnightly"
                                RecurrenceRule.MONTHLY -> "Monthly"
                                RecurrenceRule.SIX_MONTHLY -> "Six Monthly"
                                RecurrenceRule.YEARLY -> "Yearly"
                                RecurrenceRule.CUSTOM -> "Custom"
                            }
                            FilterChip(
                                selected = repeatRule == rule,
                                onClick = {
                                    repeatRule = rule
                                    when (rule) {
                                        RecurrenceRule.NONE -> { repeatDaysText = "0"; repeatHoursText = "0"; repeatMinutesText = "0" }
                                        RecurrenceRule.DAILY -> { repeatDaysText = "1"; repeatHoursText = "0"; repeatMinutesText = "0" }
                                        RecurrenceRule.WEEKLY -> { repeatDaysText = "7"; repeatHoursText = "0"; repeatMinutesText = "0" }
                                        RecurrenceRule.FORTNIGHTLY -> { repeatDaysText = "14"; repeatHoursText = "0"; repeatMinutesText = "0" }
                                        RecurrenceRule.MONTHLY -> { repeatDaysText = "30"; repeatHoursText = "0"; repeatMinutesText = "0" }
                                        RecurrenceRule.SIX_MONTHLY -> { repeatDaysText = "180"; repeatHoursText = "0"; repeatMinutesText = "0" }
                                        RecurrenceRule.YEARLY -> { repeatDaysText = "365"; repeatHoursText = "0"; repeatMinutesText = "0" }
                                        else -> {}
                                    }
                                },
                                label = { Text(ruleLabel) }
                            )
                        }
                    }

                    if (repeatRule != RecurrenceRule.NONE) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Interval Duration:", fontWeight = FontWeight.SemiBold)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text("Days:")
                                OutlinedTextField(
                                    value = repeatDaysText,
                                    onValueChange = { input -> repeatDaysText = input.filter { it.isDigit() } },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.width(64.dp),
                                    singleLine = true
                                )
                                Text("Hrs:")
                                OutlinedTextField(
                                    value = repeatHoursText,
                                    onValueChange = { input -> repeatHoursText = input.filter { it.isDigit() } },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.width(64.dp),
                                    singleLine = true
                                )
                                Text("Min:")
                                OutlinedTextField(
                                    value = repeatMinutesText,
                                    onValueChange = { input -> repeatMinutesText = input.filter { it.isDigit() } },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.width(64.dp),
                                    singleLine = true
                                )
                            }

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        val c = Calendar.getInstance().apply { repeatStartTimeMs?.let { timeInMillis = it } }
                                        TimePickerDialog(context, { _, h, min ->
                                            c.set(Calendar.HOUR_OF_DAY, h)
                                            c.set(Calendar.MINUTE, min)
                                            repeatStartTimeMs = c.timeInMillis
                                        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false).show()
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(repeatStartTimeMs?.let { "Start: ${timeFormat.format(Date(it))}" } ?: "Start time")
                                }

                                OutlinedButton(
                                    onClick = {
                                        val c = Calendar.getInstance().apply { repeatEndTimeMs?.let { timeInMillis = it } }
                                        TimePickerDialog(context, { _, h, min ->
                                            c.set(Calendar.HOUR_OF_DAY, h)
                                            c.set(Calendar.MINUTE, min)
                                            repeatEndTimeMs = c.timeInMillis
                                        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false).show()
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(repeatEndTimeMs?.let { "End: ${timeFormat.format(Date(it))}" } ?: "End time")
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    val c = Calendar.getInstance().apply { repeatStartDate?.let { timeInMillis = it } }
                                    DatePickerDialog(context, { _, y, m, d ->
                                        c.set(y, m, d, 0, 0, 0)
                                        repeatStartDate = c.timeInMillis
                                    }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(repeatStartDate?.let { "Start date: [${dateFormat.format(Date(it))}]" } ?: "Set start date")
                            }

                            Button(
                                onClick = {
                                    val days = repeatDaysText.toIntOrNull() ?: 0
                                    val hours = repeatHoursText.toIntOrNull() ?: 0
                                    val minutes = repeatMinutesText.toIntOrNull() ?: 0

                                    viewModel.saveTask(
                                        task = task,
                                        title = title,
                                        notes = notes,
                                        tags = tagsText,
                                        priority = priority,
                                        createdTimestampMs = createdMs,
                                        reminderEpochMs = reminderMs,
                                        dueEpochMs = dueMs,
                                        repeatRule = repeatRule,
                                        repeatIntervalDays = days,
                                        repeatIntervalHours = hours,
                                        repeatIntervalMinutes = minutes,
                                        repeatStartDate = repeatStartDate,
                                        repeatStartTimeMs = repeatStartTimeMs,
                                        repeatEndTimeMs = repeatEndTimeMs,
                                        linkedTaskIds = task.linkedTaskIds,
                                        locationName = locationName.ifBlank { null },
                                        latitude = latitude,
                                        longitude = longitude
                                    )
                                    Toast.makeText(context, "Repeat pattern applied ✓", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Repeat, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Set / Apply Repeat Pattern")
                            }
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Cross-Task Linking (Bidirectional)", fontWeight = FontWeight.Bold)

                    var showLinkDropdown by remember { mutableStateOf(false) }
                    val otherTasks = remember(allTasks, task.id) { allTasks.filter { it.id != task.id } }

                    Box {
                        OutlinedButton(onClick = { showLinkDropdown = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.AddLink, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Link with another task...")
                        }

                        DropdownMenu(
                            expanded = showLinkDropdown,
                            onDismissRequest = { showLinkDropdown = false }
                        ) {
                            otherTasks.forEach { other ->
                                DropdownMenuItem(
                                    text = { Text(other.title.ifBlank { "Task #${other.id}" }) },
                                    onClick = {
                                        viewModel.linkTasksBidirectional(task.id, other.id)
                                        showLinkDropdown = false
                                    }
                                )
                            }
                        }
                    }

                    val liveLinkedIds = remember(task.linkedTaskIds) {
                        task.linkedTaskIds?.split(",")?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList()
                    }

                    if (liveLinkedIds.isNotEmpty()) {
                        Text("Linked Tasks:", style = MaterialTheme.typography.labelMedium)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            liveLinkedIds.forEach { id ->
                                val linkedTask = allTasks.find { it.id == id }
                                AssistChip(
                                    onClick = {
                                        scope.launch {
                                            val target = viewModel.getTaskById(id)
                                            if (target != null) {
                                                onOpenReferencedCrossTab(target)
                                            } else {
                                                Toast.makeText(context, "Linked task not found", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    leadingIcon = { Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                    label = {
                                        Text(
                                            text = "${linkedTask?.title ?: "Task #$id"} ➔",
                                            color = MaterialTheme.colorScheme.primary,
                                            textDecoration = TextDecoration.Underline,
                                            fontWeight = FontWeight.Bold
                                        )
                                    },
                                    trailingIcon = {
                                        IconButton(modifier = Modifier.size(16.dp), onClick = {
                                            viewModel.unlinkTasksBidirectional(task.id, id)
                                        }) {
                                            Icon(Icons.Default.Clear, contentDescription = "Unlink")
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Checklists (${liveChecklist.size})", fontWeight = FontWeight.Bold)

                    liveChecklist.forEach { item ->
                        var isRenamingTitle by remember { mutableStateOf(false) }
                        var renameTitleText by remember(item.text) { mutableStateOf(item.text) }
                        var isNoteExpanded by remember { mutableStateOf(false) }
                        var itemNoteText by remember(item.notes) { mutableStateOf(item.notes ?: "") }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = item.isDone,
                                        onCheckedChange = { viewModel.toggleChecklistItem(item) },
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = item.text,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        textDecoration = if (item.isDone) TextDecoration.LineThrough else null,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { isRenamingTitle = !isRenamingTitle }
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Created: ${SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(item.createdTimestamp))}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )

                                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                        IconButton(modifier = Modifier.size(32.dp), onClick = { isRenamingTitle = !isRenamingTitle }) {
                                            Icon(Icons.Default.Edit, contentDescription = "Edit Text", modifier = Modifier.size(18.dp))
                                        }
                                        IconButton(modifier = Modifier.size(32.dp), onClick = { isNoteExpanded = !isNoteExpanded }) {
                                            Icon(
                                                Icons.Default.NoteAlt,
                                                contentDescription = "Note",
                                                tint = if (!item.notes.isNullOrBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                            )
                                        }
                                        IconButton(modifier = Modifier.size(32.dp), onClick = { viewModel.moveChecklistItem(item, true) }) {
                                            Icon(Icons.Default.ArrowUpward, contentDescription = "Up", modifier = Modifier.size(18.dp))
                                        }
                                        IconButton(modifier = Modifier.size(32.dp), onClick = { viewModel.moveChecklistItem(item, false) }) {
                                            Icon(Icons.Default.ArrowDownward, contentDescription = "Down", modifier = Modifier.size(18.dp))
                                        }
                                        IconButton(modifier = Modifier.size(32.dp), onClick = { itemPendingDeleteChecklist = item }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }

                                AnimatedVisibility(visible = isRenamingTitle) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                            .padding(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = renameTitleText,
                                            onValueChange = { renameTitleText = it },
                                            label = { Text("Edit Checklist Name") },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                            TextButton(onClick = { isRenamingTitle = false }) { Text("Cancel") }
                                            Spacer(Modifier.width(8.dp))
                                            Button(onClick = {
                                                viewModel.updateChecklistItem(item, renameTitleText, item.notes, item.isDone)
                                                isRenamingTitle = false
                                            }) { Text("Save Name") }
                                        }
                                    }
                                }

                                AnimatedVisibility(visible = isNoteExpanded) {
                                    OutlinedTextField(
                                        value = itemNoteText,
                                        onValueChange = {
                                            itemNoteText = it
                                            viewModel.updateChecklistItem(item, item.text, it, item.isDone)
                                        },
                                        label = { Text("Checklist Note / Description") },
                                        minLines = 2,
                                        maxLines = 6,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newChecklistText,
                            onValueChange = { newChecklistText = it },
                            placeholder = { Text("New checklist item...") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            if (newChecklistText.isNotBlank()) {
                                viewModel.addChecklistItem(task.id, newChecklistText)
                                newChecklistText = ""
                            }
                        }) {
                            Text("Add")
                        }
                    }
                }
            }

            // =========================================================================================
            // ATTACHMENTS CARD WITH BATCH SELECTION & DEDICATED CANCEL OPTION
            // =========================================================================================
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Files, Videos, Audios & Contacts", fontWeight = FontWeight.Bold)

                        if (liveAttachments.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isAttachmentSelectionMode) {
                                    TextButton(
                                        onClick = {
                                            if (selectedAttachmentIds.size == liveAttachments.size) {
                                                selectedAttachmentIds = emptySet()
                                            } else {
                                                selectedAttachmentIds = liveAttachments.map { it.id }.toSet()
                                            }
                                        }
                                    ) {
                                        Text(if (selectedAttachmentIds.size == liveAttachments.size) "Deselect All" else "Select All")
                                    }

                                    if (selectedAttachmentIds.isNotEmpty()) {
                                        Button(
                                            onClick = { showBatchDeleteConfirm = true },
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Delete (${selectedAttachmentIds.size})")
                                        }
                                    }

                                    Spacer(Modifier.width(4.dp))
                                    // CANCEL OPTION FOR ATTACHMENT MULTI-SELECT
                                    OutlinedButton(
                                        onClick = {
                                            isAttachmentSelectionMode = false
                                            selectedAttachmentIds = emptySet()
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text("Cancel")
                                    }
                                } else {
                                    TextButton(onClick = { isAttachmentSelectionMode = true }) {
                                        Icon(Icons.Default.Checklist, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Select Multiple")
                                    }
                                }
                            }
                        }
                    }

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
                                    viewModel.addAttachment(task.id, AttachmentType.AUDIO, it, "Voice Memo ${fullDateTimeFormat.format(Date())}")
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
                            Text("Pick Contact")
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
                            label = { Text("Phone") },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(6.dp))
                        Button(onClick = {
                            if (manualPhone.isNotBlank()) {
                                viewModel.addAttachment(
                                    taskId = task.id,
                                    type = AttachmentType.CONTACT,
                                    uriString = "tel:$manualPhone",
                                    displayName = if (manualContactName.isBlank()) "Contact" else manualContactName,
                                    contactPhone = manualPhone
                                )
                                manualContactName = ""
                                manualPhone = ""
                            }
                        }) {
                            Text("Add")
                        }
                    }

                    HorizontalDivider()

                    liveAttachments.forEach { att ->
                        var isAttachmentNoteExpanded by remember { mutableStateOf(false) }
                        var attNoteText by remember(att.notes) { mutableStateOf(att.notes ?: "") }
                        val isSelected = att.id in selectedAttachmentIds

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .then(
                                    if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier
                                ),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isAttachmentSelectionMode) {
                                        Checkbox(
                                            checked = isSelected,
                                            onCheckedChange = {
                                                selectedAttachmentIds = if (isSelected) selectedAttachmentIds - att.id else selectedAttachmentIds + att.id
                                            }
                                        )
                                        Spacer(Modifier.width(6.dp))
                                    }

                                    if (att.type == AttachmentType.CONTACT && !att.contactPhone.isNullOrBlank()) {
                                        ContactActionRow(
                                            displayName = att.displayName,
                                            phoneNumber = att.contactPhone,
                                            isPending = att.isContactPending,
                                            onTogglePending = {
                                                viewModel.updateAttachment(
                                                    att,
                                                    att.displayName,
                                                    att.notes,
                                                    att.contactPhone,
                                                    !att.isContactPending
                                                )
                                            }
                                        )
                                    } else {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    try {
                                                        val intent = Intent(Intent.ACTION_VIEW).apply {
                                                            setDataAndType(Uri.parse(att.uriString), "*/*")
                                                            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                                                        }
                                                        context.startActivity(intent)
                                                    } catch (_: Exception) {
                                                        Toast.makeText(context, "Cannot preview file", Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = when (att.type) {
                                                    AttachmentType.VIDEO -> Icons.Default.Videocam
                                                    AttachmentType.AUDIO -> Icons.Default.Mic
                                                    AttachmentType.CONTACT -> Icons.Default.Person
                                                    AttachmentType.IMAGE -> Icons.Default.Image
                                                    AttachmentType.FILE -> Icons.Default.AttachFile
                                                },
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(26.dp)
                                            )
                                            Spacer(Modifier.width(12.dp))

                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = att.displayName,
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Created: ${SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(att.createdTimestamp))}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )

                                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                        IconButton(modifier = Modifier.size(32.dp), onClick = { isAttachmentNoteExpanded = !isAttachmentNoteExpanded }) {
                                            Icon(
                                                Icons.Default.EditNote,
                                                contentDescription = "Note",
                                                tint = if (!att.notes.isNullOrBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                            )
                                        }
                                        IconButton(modifier = Modifier.size(32.dp), onClick = { viewModel.moveAttachment(att, true) }) {
                                            Icon(Icons.Default.ArrowUpward, contentDescription = "Up", modifier = Modifier.size(18.dp))
                                        }
                                        IconButton(modifier = Modifier.size(32.dp), onClick = { viewModel.moveAttachment(att, false) }) {
                                            Icon(Icons.Default.ArrowDownward, contentDescription = "Down", modifier = Modifier.size(18.dp))
                                        }
                                        IconButton(modifier = Modifier.size(32.dp), onClick = {
                                            viewModel.deleteAttachment(att)
                                        }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }

                                AnimatedVisibility(visible = isAttachmentNoteExpanded) {
                                    OutlinedTextField(
                                        value = attNoteText,
                                        onValueChange = {
                                            attNoteText = it
                                            viewModel.updateAttachment(att, att.displayName, it, att.contactPhone, att.isContactPending)
                                        },
                                        label = { Text("Attachment Note / Details") },
                                        minLines = 2,
                                        maxLines = 6,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}
