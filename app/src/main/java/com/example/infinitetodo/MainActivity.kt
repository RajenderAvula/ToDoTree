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
import kotlin.math.roundToInt

// Universal Date/Time Formatters available across all Composables
val fullDateTimeFormat: SimpleDateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
val shortDateFormat: SimpleDateFormat = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
val dateOnlyFormat: SimpleDateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
val timeOnlyFormat: SimpleDateFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())

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

    var taskForTargetMove by remember { mutableStateOf<TaskItem?>(null) }
    var taskForTargetCopy by remember { mutableStateOf<TaskItem?>(null) }

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
                    onMoveToTarget = { taskForTargetMove = it },
                    onCopyToTarget = { taskForTargetCopy = it }
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
// COMPACT CONTACT QUICK ACTION STRIP (DEDUPLICATED)
// -----------------------------------------------------------------------------------------
@Composable
fun ContactActionRow(
    displayName: String,
    phoneNumber: String,
    isPending: Boolean,
    onTogglePending: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var showExtraMenu by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.padding(vertical = 3.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(6.dp))

            Column(modifier = Modifier.widthIn(max = 140.dp)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = phoneNumber,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(8.dp))

            FilledTonalIconButton(
                modifier = Modifier.size(28.dp),
                onClick = { LocationAndContactHelper.launchDialer(context, phoneNumber) }
            ) {
                Icon(
                    Icons.Default.Phone,
                    contentDescription = "Call",
                    tint = Color(0xFF1976D2),
                    modifier = Modifier.size(14.dp)
                )
            }
            Spacer(Modifier.width(4.dp))

            FilledTonalIconButton(
                modifier = Modifier.size(28.dp),
                onClick = { LocationAndContactHelper.launchSms(context, phoneNumber) }
            ) {
                Icon(
                    Icons.Default.ChatBubble,
                    contentDescription = "Text SMS",
                    tint = Color(0xFFF57C00),
                    modifier = Modifier.size(14.dp)
                )
            }
            Spacer(Modifier.width(4.dp))

            FilledTonalIconButton(
                modifier = Modifier.size(28.dp),
                onClick = { LocationAndContactHelper.launchWhatsApp(context, phoneNumber) }
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "WhatsApp",
                    tint = Color(0xFF2E7D32),
                    modifier = Modifier.size(14.dp)
                )
            }
            Spacer(Modifier.width(4.dp))

            Box {
                FilledTonalIconButton(
                    modifier = Modifier.size(28.dp),
                    onClick = { showExtraMenu = true }
                ) {
                    Icon(
                        Icons.Default.Apps,
                        contentDescription = "All Messaging Apps",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp)
                    )
                }

                DropdownMenu(
                    expanded = showExtraMenu,
                    onDismissRequest = { showExtraMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Open in Telegram") },
                        leadingIcon = { Icon(Icons.Default.Send, contentDescription = null, tint = Color(0xFF0288D1)) },
                        onClick = {
                            showExtraMenu = false
                            LocationAndContactHelper.launchTelegram(context, phoneNumber)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Share via Any Installed App...") },
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        onClick = {
                            showExtraMenu = false
                            LocationAndContactHelper.openAllAppsContactMenu(context, displayName, phoneNumber)
                        }
                    )
                }
            }

            if (onTogglePending != null) {
                Spacer(Modifier.width(6.dp))
                Surface(
                    color = if (isPending) Color(0xFFFFF3E0) else Color(0xFFE8F5E9),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.clickable { onTogglePending() }
                ) {
                    Text(
                        text = if (isPending) "Pending" else "Done ✓",
                        color = if (isPending) Color(0xFFE65100) else Color(0xFF2E7D32),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------------------
// REUSABLE TASK STATUS ROW
// -----------------------------------------------------------------------------------------
@Composable
fun TaskMetadataStatusRow(task: TaskItem) {
    val dateFormat = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (task.isCompleted) {
            val doneDate = task.completedTimestamp ?: task.lastModifiedTimestamp
            Surface(
                color = Color(0xFFE8F5E9),
                shape = RoundedCornerShape(4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Completed",
                        modifier = Modifier.size(13.dp),
                        tint = Color(0xFF2E7D32)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Completed: ${dateFormat.format(Date(doneDate))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF2E7D32),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        } else {
            task.dueTimestamp?.let { due ->
                val isOverdue = due < System.currentTimeMillis()
                Surface(
                    color = if (isOverdue) Color(0xFFFFEBEE) else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Event,
                            contentDescription = "Due Date",
                            modifier = Modifier.size(12.dp),
                            tint = if (isOverdue) Color(0xFFD32F2F) else MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = "Due: ${dateFormat.format(Date(due))}${if (isOverdue) " (Overdue)" else ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isOverdue) Color(0xFFD32F2F) else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (isOverdue) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }

        task.reminderTimestamp?.let { rem ->
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Alarm, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(3.dp))
                    Text("Remind: ${dateFormat.format(Date(rem))}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        if (task.repeatRule != RecurrenceRule.NONE) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Repeat, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = when (task.repeatRule) {
                            RecurrenceRule.CUSTOM -> "Every ${task.repeatIntervalDays}d ${task.repeatIntervalHours}h ${task.repeatIntervalMinutes}m"
                            else -> task.repeatRule.name
                        },
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@Composable
fun DeleteConfirmationDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = { Text(message) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun PriorityBadge(priority: TaskPriority) {
    val bg = when (priority) {
        TaskPriority.URGENT -> Color(0xFFD32F2F)
        TaskPriority.HIGH -> Color(0xFFF57C00)
        TaskPriority.MEDIUM -> Color(0xFF0288D1)
        TaskPriority.LOW -> Color(0xFF689F38)
    }
    Surface(color = bg, shape = RoundedCornerShape(4.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (priority == TaskPriority.URGENT) {
                Icon(Icons.Default.Whatshot, contentDescription = "Urgent", tint = Color.White, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(2.dp))
            }
            Text(
                text = priority.name,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}

@Composable
fun HighlightedText(
    text: String,
    query: String,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
    fontWeight: FontWeight? = null
) {
    if (query.isBlank() || !text.contains(query, ignoreCase = true)) {
        Text(text, style = style, fontWeight = fontWeight)
        return
    }

    val annotated = remember(text, query) {
        buildAnnotatedString {
            var startIndex = 0
            val lowerText = text.lowercase()
            val lowerQuery = query.lowercase()

            while (startIndex < text.length) {
                val index = lowerText.indexOf(lowerQuery, startIndex)
                if (index == -1) {
                    append(text.substring(startIndex))
                    break
                }
                append(text.substring(startIndex, index))
                pushStyle(
                    SpanStyle(
                        background = Color(0xFFFFD54F),
                        color = Color(0xFF212121),
                        fontWeight = FontWeight.Bold
                    )
                )
                append(text.substring(index, index + query.length))
                pop()
                startIndex = index + query.length
            }
        }
    }

    Text(annotated, style = style, fontWeight = fontWeight)
}

// -----------------------------------------------------------------------------------------
// COMMON MULTI-CRITERIA FILTER BAR WITH QUICK RESET
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
    val context = LocalContext.current
    val dateChipFormat = remember { SimpleDateFormat("dd MMM", Locale.getDefault()) }
    var availableTags by remember { mutableStateOf<List<String>>(emptyList()) }
    var availableLocations by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()

    val isAnyFilterActive = remember(filterState, searchQuery) {
        searchQuery.isNotBlank() ||
        filterState.priorities.isNotEmpty() ||
        filterState.statusPending != null ||
        filterState.mustHaveContact ||
        filterState.selectedTags.isNotEmpty() ||
        filterState.createdFromMs != null ||
        filterState.dueFromMs != null ||
        filterState.mustHaveLocation ||
        filterState.selectedLocations.isNotEmpty()
    }

    LaunchedEffect(showFilterSheet) {
        if (showFilterSheet) {
            scope.launch {
                availableTags = viewModel.getAllUniqueTags()
                availableLocations = viewModel.getAllUniqueLocations()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text("Search task, tag, note, contact, location...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                        IconButton(onClick = { showFilterSheet = !showFilterSheet }) {
                            BadgedBox(
                                badge = {
                                    if (isAnyFilterActive) {
                                        Badge { Text("!") }
                                    }
                                }
                            ) {
                                Icon(
                                    Icons.Default.FilterList,
                                    contentDescription = "Filters",
                                    tint = if (isAnyFilterActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )

            // Quick reset button directly on top bar when active
            if (isAnyFilterActive) {
                Spacer(Modifier.width(6.dp))
                IconButton(
                    onClick = {
                        onSearchQueryChange("")
                        viewModel.resetFilters()
                        Toast.makeText(context, "Filters reset", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.FilterAltOff,
                        contentDescription = "Reset Filters",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        AnimatedVisibility(visible = showFilterSheet) {
            Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Filter Options", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    TextButton(
                        onClick = {
                            onSearchQueryChange("")
                            viewModel.resetFilters()
                            Toast.makeText(context, "All filters cleared", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Reset All")
                    }
                }

                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Location:", style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.CenterVertically))

                    FilterChip(
                        selected = filterState.mustHaveLocation,
                        onClick = { viewModel.updateFilter(filterState.copy(mustHaveLocation = !filterState.mustHaveLocation)) },
                        leadingIcon = { Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error) },
                        label = { Text("Has Location") }
                    )

                    availableLocations.forEach { loc ->
                        FilterChip(
                            selected = loc in filterState.selectedLocations,
                            onClick = {
                                val current = filterState.selectedLocations.toMutableSet()
                                if (loc in current) current.remove(loc) else current.add(loc)
                                viewModel.updateFilter(filterState.copy(selectedLocations = current))
                            },
                            label = { Text("📍 $loc") }
                        )
                    }
                }

                if (availableTags.isNotEmpty()) {
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Tags:", style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.CenterVertically))
                        availableTags.forEach { tag ->
                            FilterChip(
                                selected = tag in filterState.selectedTags,
                                onClick = {
                                    val current = filterState.selectedTags.toMutableSet()
                                    if (tag in current) current.remove(tag) else current.add(tag)
                                    viewModel.updateFilter(filterState.copy(selectedTags = current))
                                },
                                label = { Text("#$tag") }
                            )
                        }
                    }
                }

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
                            label = {
                                if (priority == TaskPriority.URGENT) {
                                    Text("🔥 URGENT", fontWeight = FontWeight.ExtraBold)
                                } else {
                                    Text(priority.name)
                                }
                            },
                            colors = if (priority == TaskPriority.URGENT && priority in filterState.priorities) {
                                FilterChipDefaults.filterChipColors(containerColor = Color(0xFFD32F2F), labelColor = Color.White)
                            } else FilterChipDefaults.filterChipColors()
                        )
                    }
                }

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

                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Dates:", style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.CenterVertically))

                    AssistChip(
                        onClick = {
                            val c = Calendar.getInstance()
                            DatePickerDialog(context, { _, y, m, d ->
                                c.set(y, m, d, 0, 0, 0)
                                val from = c.timeInMillis
                                c.set(y, m, d, 23, 59, 59)
                                val to = c.timeInMillis
                                viewModel.updateFilter(filterState.copy(createdFromMs = from, createdToMs = to))
                            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
                        },
                        label = {
                            Text(filterState.createdFromMs?.let { "Created: ${dateChipFormat.format(Date(it))}" } ?: "Filter Created Date")
                        }
                    )

                    AssistChip(
                        onClick = {
                            val c = Calendar.getInstance()
                            DatePickerDialog(context, { _, y, m, d ->
                                c.set(y, m, d, 0, 0, 0)
                                val from = c.timeInMillis
                                c.set(y, m, d, 23, 59, 59)
                                val to = c.timeInMillis
                                viewModel.updateFilter(filterState.copy(dueFromMs = from, dueToMs = to))
                            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
                        },
                        label = {
                            Text(filterState.dueFromMs?.let { "Due: ${dateChipFormat.format(Date(it))}" } ?: "Filter Due Date")
                        }
                    )
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------------------
// 1. HOME DASHBOARD TAB (WITH DAILY REFRESHED NOTIFICATION ICON)
// -----------------------------------------------------------------------------------------
@Composable
fun HomeDashboardTab(
    viewModel: TaskViewModel,
    onOpenTask: (TaskItem) -> Unit
) {
    val allTasks by viewModel.allTasksFlow.collectAsState(initial = emptyList())
    val todayDueTasks by viewModel.todayDueOrReminderTasks.collectAsState(initial = emptyList())
    var searchQuery by remember { mutableStateOf("") }
    val searchResults by viewModel.searchTasks(searchQuery).collectAsState(initial = emptyList())
    val filterState by viewModel.filterState.collectAsState()

    var showTodayNotificationsDialog by remember { mutableStateOf(false) }

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

    Column(modifier = Modifier.fillMaxSize()) {
        TaskFilterHeaderBar(viewModel, searchQuery) { searchQuery = it }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Task Analytics & Metrics Summary", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                            // Notification icon about tasks that have reminder/repeat/due date till today
                            IconButton(onClick = { showTodayNotificationsDialog = true }) {
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
                                        contentDescription = "Today & Overdue Tasks Alert",
                                        tint = if (todayDueTasks.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
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

            items(displayedTasks, key = { it.id }) { task ->
                val subtaskCount by viewModel.getSubtaskCount(task.id).collectAsState(initial = 0)
                val attachments by viewModel.getAttachments(task.id).collectAsState(initial = emptyList())
                val contacts = remember(attachments) { attachments.filter { it.type == AttachmentType.CONTACT } }

                var hierarchyPath by remember { mutableStateOf("") }
                val scope = rememberCoroutineScope()
                LaunchedEffect(task.id) {
                    scope.launch { hierarchyPath = viewModel.getHierarchyPathString(task.id) }
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenTask(task) },
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
                            PriorityBadge(task.priority)
                            if (subtaskCount > 0) {
                                Spacer(Modifier.width(8.dp))
                                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(4.dp)) {
                                    Text("[$subtaskCount subtasks]", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                                }
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = task.isCompleted,
                                onCheckedChange = { viewModel.toggleTaskCompletion(task) }
                            )
                            Spacer(Modifier.width(6.dp))
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onOpenTask(task) }
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
        }

        // Dialog displaying tasks due, repeated, or with reminders scheduled till today
        if (showTodayNotificationsDialog) {
            AlertDialog(
                onDismissRequest = { showTodayNotificationsDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text("Due, Repeat & Reminders Till Today (${todayDueTasks.size})")
                    }
                },
                text = {
                    if (todayDueTasks.isEmpty()) {
                        Text("No pending tasks due or scheduled for reminder/repeat today.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 350.dp),
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
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(item.title.ifBlank { "Untitled" }, fontWeight = FontWeight.Bold)
                                            val dueText = item.dueTimestamp?.let {
                                                "Due: " + SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(it))
                                            } ?: item.reminderTimestamp?.let {
                                                "Reminder: " + SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(it))
                                            } ?: "Repeating Active"
                                            Text(dueText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                        }
                                        Icon(Icons.AutoMirrored.Filled.ArrowRight, contentDescription = null)
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
    val searchResults by viewModel.searchTasks(searchQuery).collectAsState(initial = emptyList())
    val filterState by viewModel.filterState.collectAsState()
    val context = LocalContext.current

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

    Column(modifier = Modifier.fillMaxSize()) {
        TaskFilterHeaderBar(viewModel, searchQuery) { searchQuery = it }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Hierarchical Tasks", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row {
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
            items(displayedTasks, key = { it.id }) { task ->
                TaskNodeView(
                    task = task,
                    depth = 0,
                    viewMode = viewMode,
                    searchQuery = searchQuery,
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
            (searchQuery.isBlank() || task.title.contains(searchQuery, true)) &&
            (filterState.priorities.isEmpty() || task.priority in filterState.priorities) &&
            (filterState.statusPending == null || (if (filterState.statusPending == true) !task.isCompleted else task.isCompleted)) &&
            (filterState.selectedTags.isEmpty() || (task.tags?.split(",")?.map { it.trim() }?.any { it in filterState.selectedTags } == true)) &&
            (!filterState.mustHaveLocation || (!task.locationName.isNullOrBlank() || task.latitude != null)) &&
            (filterState.selectedLocations.isEmpty() || (task.locationName != null && task.locationName in filterState.selectedLocations)) &&
            (filterState.createdFromMs == null || (task.createdTimestamp in filterState.createdFromMs!!..filterState.createdToMs!!)) &&
            (filterState.dueFromMs == null || (task.dueTimestamp != null && task.dueTimestamp in filterState.dueFromMs!!..filterState.dueToMs!!))
        }.sortedBy { task ->
            if (task.isCompleted) task.completedTimestamp ?: task.lastModifiedTimestamp else task.createdTimestamp
        }
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
            Text("${scheduledTasks.size} Tasks in Calendar View", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            IconButton(onClick = {
                viewModel.syncAllTasksToCalendar { count ->
                    Toast.makeText(context, "Synced $count task(s) to Calendar", Toast.LENGTH_SHORT).show()
                }
            }) {
                Icon(Icons.Default.Sync, contentDescription = "Sync All")
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(scheduledTasks, key = { it.id }) { task ->
                var hierarchyPath by remember { mutableStateOf("") }
                val scope = rememberCoroutineScope()
                LaunchedEffect(task.id) {
                    scope.launch { hierarchyPath = viewModel.getHierarchyPathString(task.id) }
                }

                val primaryTimestamp = if (task.isCompleted) {
                    task.completedTimestamp ?: task.lastModifiedTimestamp
                } else {
                    task.dueTimestamp ?: task.createdTimestamp
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenTask(task) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
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

                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (task.isCompleted) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.primaryContainer
                                    )
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = timeFormat.format(Date(primaryTimestamp)),
                                    fontWeight = FontWeight.Bold,
                                    color = if (task.isCompleted) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(dateFormat.format(Date(primaryTimestamp)), style = MaterialTheme.typography.labelSmall)
                                Text(
                                    text = task.title.ifBlank { "Untitled Task" },
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        textDecoration = if (task.isCompleted) TextDecoration.LineThrough else null
                                    ),
                                    fontWeight = FontWeight.SemiBold
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    PriorityBadge(task.priority)
                                    if (task.calendarEventId != null) {
                                        Text("• Google Calendar Synced ✓", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                            Checkbox(checked = task.isCompleted, onCheckedChange = { viewModel.toggleTaskCompletion(task) })
                        }

                        TaskMetadataStatusRow(task)
                    }
                }
            }
        }
    }
}

@Composable
fun GanttChartTab(
    viewModel: TaskViewModel,
    onOpenTask: (TaskItem) -> Unit
) {
    val allTasks by viewModel.allTasksFlow.collectAsState(initial = emptyList())
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }

    val ganttTasks = remember(allTasks) {
        allTasks.sortedBy { it.createdTimestamp }
    }

    val minTime = remember(ganttTasks) {
        ganttTasks.minOfOrNull { it.createdTimestamp } ?: System.currentTimeMillis()
    }
    val maxTime = remember(ganttTasks) {
        (ganttTasks.mapNotNull { if (it.isCompleted) it.completedTimestamp ?: it.lastModifiedTimestamp else it.dueTimestamp }.maxOrNull()
            ?: (System.currentTimeMillis() + 7 * 86400000L))
            .coerceAtLeast(minTime + 86400000L)
    }
    val totalDuration = (maxTime - minTime).coerceAtLeast(1L)

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Text("Gantt Chart Timeline", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Task progression with full hierarchy path, completion markers and expandable dates", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(8.dp))

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(ganttTasks, key = { it.id }) { task ->
                val taskStart = task.createdTimestamp
                val taskEnd = if (task.isCompleted) {
                    task.completedTimestamp ?: task.lastModifiedTimestamp
                } else {
                    task.dueTimestamp ?: (taskStart + 86400000L)
                }

                val startFraction = ((taskStart - minTime).toFloat() / totalDuration).coerceIn(0f, 1f)
                val spanFraction = ((taskEnd - taskStart).toFloat() / totalDuration).coerceIn(0.08f, 1f - startFraction)

                var showDetails by remember { mutableStateOf(false) }
                var hierarchyPath by remember { mutableStateOf("") }
                val scope = rememberCoroutineScope()
                LaunchedEffect(task.id) {
                    scope.launch { hierarchyPath = viewModel.getHierarchyPathString(task.id) }
                }

                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        if (hierarchyPath.isNotBlank()) {
                            Text(
                                text = "Path: $hierarchyPath",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 2.dp)
                            )
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                text = task.title.ifBlank { "Untitled Task" },
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    textDecoration = if (task.isCompleted) TextDecoration.LineThrough else null
                                ),
                                fontWeight = FontWeight.SemiBold
                            )
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
                                    .padding(start = (startFraction * 260).dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (task.isCompleted) Color(0xFF43A047)
                                        else when (task.priority) {
                                            TaskPriority.URGENT -> Color(0xFFD32F2F)
                                            TaskPriority.HIGH -> Color(0xFFF57C00)
                                            TaskPriority.MEDIUM -> Color(0xFF0288D1)
                                            TaskPriority.LOW -> Color(0xFF689F38)
                                        }
                                    )
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = { showDetails = !showDetails }) {
                                Text(if (showDetails) "Hide Details ▲" else "Show Details ▼", style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = { onOpenTask(task) }) {
                                Text("Open Workspace ➔", style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        AnimatedVisibility(visible = showDetails) {
                            Column(modifier = Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("Created Date: ${dateFormat.format(Date(task.createdTimestamp))}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                if (task.isCompleted) {
                                    val completedAt = task.completedTimestamp ?: task.lastModifiedTimestamp
                                    Text(
                                        text = "Completed Date: ${dateFormat.format(Date(completedAt))}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF2E7D32),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text("Due Date: ${task.dueTimestamp?.let { dateFormat.format(Date(it)) } ?: "None set"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                if (!task.tags.isNullOrBlank()) {
                                    Text("Tags: #${task.tags.split(",").joinToString(" #")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

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
    val scope = rememberCoroutineScope()

    val createBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val outputStream = context.contentResolver.openOutputStream(uri)
                if (outputStream != null) {
                    viewModel.backupToDevice(outputStream) { success ->
                        Toast.makeText(
                            context,
                            if (success) "Backup saved to device successfully!" else "Backup failed to write",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    Toast.makeText(context, "Cannot open selected storage location", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Backup error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val restoreBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                if (inputStream != null) {
                    viewModel.restoreBackup(inputStream) { success ->
                        Toast.makeText(
                            context,
                            if (success) "Backup restored successfully from device!" else "Restore failed: Invalid or corrupt zip",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    Toast.makeText(context, "Cannot open selected backup file", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Restore error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
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
                Text("Backup & Restore Data", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

                Button(
                    onClick = {
                        createBackupLauncher.launch("ToDoTree_DeviceBackup_${System.currentTimeMillis()}.zip")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
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
                                Toast.makeText(context, "Failed to create email backup package", Toast.LENGTH_SHORT).show()
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
                    onClick = {
                        restoreBackupLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Restore, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Restore from Device")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Appearance & Layout", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { showThemeDialog = true },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("App Theme (${currentTheme.name})")
                    Icon(Icons.Default.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Compact View Mode")
                    Switch(checked = viewMode == TaskViewMode.COMPACT, onCheckedChange = {
                        onViewModeChange(if (it) TaskViewMode.COMPACT else TaskViewMode.DETAILED)
                    })
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
// REUSABLE TASK TREE ROW
// -----------------------------------------------------------------------------------------
@Composable
fun TaskNodeView(
    task: TaskItem,
    depth: Int,
    viewMode: TaskViewMode,
    searchQuery: String,
    viewModel: TaskViewModel,
    onAddSubtask: (Long) -> Unit,
    onOpenFullScreen: (TaskItem) -> Unit,
    onMoveToTarget: (TaskItem) -> Unit,
    onCopyToTarget: (TaskItem) -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }

    var isUndocked by remember { mutableStateOf(false) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val animatedOffsetY by animateFloatAsState(targetValue = offsetY, label = "dragY")

    val subtasks by viewModel.getSubtasks(task.id).collectAsState(initial = emptyList())
    val subtaskCount by viewModel.getSubtaskCount(task.id).collectAsState(initial = 0)
    val attachments by viewModel.getAttachments(task.id).collectAsState(initial = emptyList())
    val contacts = remember(attachments) { attachments.filter { it.type == AttachmentType.CONTACT } }

    val layerLevel = depth + 1

    var layersBelow by remember { mutableIntStateOf(0) }
    LaunchedEffect(task.id, subtasks) {
        layersBelow = viewModel.getDescendantLayersCount(task.id)
    }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()) }

    if (showDeleteConfirm) {
        DeleteConfirmationDialog(
            title = "Delete Task?",
            message = "Are you sure you want to delete '${task.title.ifBlank { "this task" }}'? All subtasks, checklists, attachments and its calendar event will also be removed.",
            onConfirm = {
                viewModel.deleteTask(task)
                showDeleteConfirm = false
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 14).dp)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, animatedOffsetY.roundToInt()) }
                .scale(if (isUndocked) 1.02f else 1f)
                .border(
                    width = if (isUndocked) 2.dp else 0.dp,
                    color = if (isUndocked) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(vertical = if (viewMode == TaskViewMode.COMPACT) 2.dp else 4.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = if (isUndocked) 8.dp else 2.dp)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                    PriorityBadge(task.priority)
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "Layer $layerLevel • $layersBelow below",
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    if (subtaskCount > 0) {
                        Spacer(Modifier.width(8.dp))
                        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(4.dp)) {
                            Text("[$subtaskCount subtasks]", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        modifier = Modifier
                            .size(36.dp)
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
                            Icons.Default.DragIndicator,
                            contentDescription = "Hold to Undock and Drag",
                            tint = if (isUndocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Checkbox(
                        checked = task.isCompleted,
                        onCheckedChange = { viewModel.toggleTaskCompletion(task) },
                        modifier = Modifier.size(32.dp)
                    )

                    Spacer(Modifier.width(6.dp))

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onOpenFullScreen(task) }
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

                    if (subtaskCount > 0) {
                        IconButton(
                            modifier = Modifier.size(36.dp),
                            onClick = { isExpanded = !isExpanded }
                        ) {
                            Icon(
                                imageVector = if (isExpanded) Icons.Default.ArrowDropDown else Icons.AutoMirrored.Filled.ArrowRight,
                                contentDescription = "Expand Inline Subtree",
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                TaskMetadataStatusRow(task)

                if (!task.locationName.isNullOrBlank() || (task.latitude != null && task.longitude != null)) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .padding(start = 36.dp, top = 4.dp)
                            .clickable {
                                if (task.latitude != null && task.longitude != null) {
                                    LocationAndContactHelper.openInMap(context, task.latitude, task.longitude, task.locationName)
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = task.locationName ?: "Pinned (${task.latitude}, ${task.longitude})",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 36.dp, top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Created: ${dateFormat.format(Date(task.createdTimestamp))}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    if (task.isCompleted) {
                        val doneTime = task.completedTimestamp ?: task.lastModifiedTimestamp
                        Text("Completed: ${dateFormat.format(Date(doneTime))}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                    }
                    Text("Modified: ${dateFormat.format(Date(task.lastModifiedTimestamp))}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }

                if (contacts.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(start = 36.dp, top = 6.dp),
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

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        IconButton(modifier = Modifier.size(30.dp), onClick = { viewModel.moveTaskVertical(task, directionUp = true) }) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = "Move Up", modifier = Modifier.size(17.dp))
                        }
                        IconButton(modifier = Modifier.size(30.dp), onClick = { viewModel.moveTaskVertical(task, directionUp = false) }) {
                            Icon(Icons.Default.ArrowDownward, contentDescription = "Move Down", modifier = Modifier.size(17.dp))
                        }
                        if (task.parentId != null) {
                            IconButton(modifier = Modifier.size(30.dp), onClick = { viewModel.outdentTask(task) }) {
                                Icon(Icons.Default.KeyboardDoubleArrowLeft, contentDescription = "Outdent", tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(17.dp))
                            }
                        }
                        IconButton(modifier = Modifier.size(30.dp), onClick = { viewModel.indentTask(task) }) {
                            Icon(Icons.Default.KeyboardDoubleArrowRight, contentDescription = "Indent", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(17.dp))
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        IconButton(modifier = Modifier.size(30.dp), onClick = { onAddSubtask(task.id) }) {
                            Icon(Icons.Default.SubdirectoryArrowRight, contentDescription = "Add Subtask", modifier = Modifier.size(17.dp))
                        }
                        IconButton(modifier = Modifier.size(30.dp), onClick = { onMoveToTarget(task) }) {
                            Icon(Icons.Default.DriveFileMove, contentDescription = "Move Target", modifier = Modifier.size(17.dp))
                        }
                        IconButton(modifier = Modifier.size(30.dp), onClick = { onCopyToTarget(task) }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy Target", modifier = Modifier.size(17.dp))
                        }
                        IconButton(
                            modifier = Modifier.size(30.dp),
                            onClick = { viewModel.shareTaskData(context, task.id) }
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(17.dp))
                        }
                        IconButton(
                            modifier = Modifier.size(36.dp),
                            onClick = { onOpenFullScreen(task) }
                        ) {
                            Icon(
                                imageVector = Icons.Default.OpenInFull,
                                contentDescription = "Open Full Screen Mode",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(modifier = Modifier.size(30.dp), onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(17.dp))
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
                    searchQuery = searchQuery,
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
// FULL SCREEN WORKSPACE DIALOG
// -----------------------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenTaskWorkspaceDialog(
    initialTask: TaskItem,
    viewModel: TaskViewModel,
    onDismiss: () -> Unit
) {
    var primaryTask by remember(initialTask.id) { mutableStateOf(initialTask) }
    var referencedTask by remember { mutableStateOf<TaskItem?>(null) }
    var selectedWorkspaceTab by remember { mutableIntStateOf(0) }

    val allTasks by viewModel.allTasksFlow.collectAsState(initial = emptyList())

    LaunchedEffect(allTasks, primaryTask.id) {
        val updated = allTasks.find { it.id == primaryTask.id }
        if (updated != null) {
            primaryTask = updated
        }
    }

    LaunchedEffect(allTasks, referencedTask?.id) {
        val refId = referencedTask?.id
        if (refId != null) {
            val updatedRef = allTasks.find { it.id == refId }
            if (updatedRef != null) {
                referencedTask = updatedRef
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        title = {
                            Text(if (selectedWorkspaceTab == 0) primaryTask.title.ifBlank { "Task Workspace" } else "Referenced: ${referencedTask?.title ?: ""}")
                        },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
                            }
                        }
                    )

                    if (referencedTask != null) {
                        TabRow(selectedTabIndex = selectedWorkspaceTab) {
                            Tab(
                                selected = selectedWorkspaceTab == 0,
                                onClick = { selectedWorkspaceTab = 0 },
                                text = { Text(primaryTask.title.ifBlank { "Original Task" }, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                            Tab(
                                selected = selectedWorkspaceTab == 1,
                                onClick = { selectedWorkspaceTab = 1 },
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(referencedTask?.title?.ifBlank { "Referenced" } ?: "Referenced", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                if (selectedWorkspaceTab == 0) {
                    key(primaryTask.id) {
                        SingleTaskEditorView(
                            task = primaryTask,
                            viewModel = viewModel,
                            onOpenReferencedCrossTab = { target ->
                                referencedTask = target
                                selectedWorkspaceTab = 1
                            }
                        )
                    }
                } else if (referencedTask != null) {
                    key(referencedTask!!.id) {
                        SingleTaskEditorView(
                            task = referencedTask!!,
                            viewModel = viewModel,
                            onOpenReferencedCrossTab = { nextTarget ->
                                referencedTask = nextTarget
                            }
                        )
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------------------
// SINGLE TASK EDITOR VIEW (WITH SCHEDULED / CREATED DATE TIME PICKER)
// -----------------------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SingleTaskEditorView(
    task: TaskItem,
    viewModel: TaskViewModel,
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

    // Scheduled / Created Date Time State
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

    var itemPendingDeleteChecklist by remember { mutableStateOf<ChecklistItem?>(null) }
    var itemPendingDeleteAttachment by remember { mutableStateOf<RichAttachment?>(null) }

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

    if (itemPendingDeleteAttachment != null) {
        DeleteConfirmationDialog(
            title = "Delete Attachment?",
            message = "Are you sure you want to remove '${itemPendingDeleteAttachment?.displayName}'?",
            onConfirm = {
                itemPendingDeleteAttachment?.let { viewModel.deleteAttachment(it) }
                itemPendingDeleteAttachment = null
            },
            onDismiss = { itemPendingDeleteAttachment = null }
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = { viewModel.shareTaskData(context, task.id) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Share")
                }

                OutlinedButton(
                    onClick = { viewModel.shareTaskData(context, task.id, targetPackage = "com.whatsapp") }
                ) {
                    Text("WhatsApp")
                }
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
                    Toast.makeText(context, "Saved changes ✓", Toast.LENGTH_SHORT).show()
                }
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Save")
            }
        }

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

        // SCHEDULED / CREATED TIME INTERACTIVE PICKER CARD
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

        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            label = { Text("Description & Notes") },
            minLines = 4,
            maxLines = 10,
            modifier = Modifier.fillMaxWidth()
        )

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

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Files, Videos, Audios & Contacts", fontWeight = FontWeight.Bold)

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

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
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
                                    IconButton(modifier = Modifier.size(32.dp), onClick = { itemPendingDeleteAttachment = att }) {
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

// -----------------------------------------------------------------------------------------
// DESTINATION PICKER DIALOG (MOVE / COPY)
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
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 350.dp)) {
                item {
                    ListItem(
                        headlineContent = { Text("★ Root Level (Main Task)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable { onSelectTarget(null) }
                    )
                    HorizontalDivider()
                }
                items(potentialParents, key = { it.id }) { parentCandidate ->
                    ListItem(
                        headlineContent = { Text(parentCandidate.title.ifBlank { "Task #${parentCandidate.id}" }) },
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
