package com.example.infinitetodo

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val taskViewModel: TaskViewModel = viewModel()
                    InfiniteTodoApp(taskViewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfiniteTodoApp(viewModel: TaskViewModel) {
    val rootTasks by viewModel.rootTasks.collectAsState(initial = emptyList())
    var showCreateDialogForParentId by remember { mutableStateOf<Long?>(null) }
    var isCreatingRootTask by remember { mutableStateOf(false) }

    // Request Calendar and Notification runtime permissions
    val permissionsToRequest = remember {
        val list = mutableListOf(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        list.toTypedArray()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { /* Permissions evaluated dynamically */ }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(permissionsToRequest)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hierarchical Infinite Tasks") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
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
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            items(rootTasks, key = { it.id }) { rootTask ->
                TaskNodeView(
                    task = rootTask,
                    depth = 0,
                    viewModel = viewModel,
                    onAddSubtask = { parentId -> showCreateDialogForParentId = parentId }
                )
            }
        }

        if (isCreatingRootTask) {
            CreateTaskDialog(
                onDismiss = { isCreatingRootTask = false },
                onConfirm = { title, time, uri, name, syncCal ->
                    viewModel.addTask(title, null, time, uri, name, syncCal)
                    isCreatingRootTask = false
                }
            )
        }

        showCreateDialogForParentId?.let { parentId ->
            CreateTaskDialog(
                onDismiss = { showCreateDialogForParentId = null },
                onConfirm = { title, time, uri, name, syncCal ->
                    viewModel.addTask(title, parentId, time, uri, name, syncCal)
                    showCreateDialogForParentId = null
                }
            )
        }
    }
}

@Composable
fun TaskNodeView(
    task: TaskItem,
    depth: Int,
    viewModel: TaskViewModel,
    onAddSubtask: (Long) -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }
    val subtasks by viewModel.getSubtasks(task.id).collectAsState(initial = emptyList())
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 16).dp)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { isExpanded = !isExpanded }) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ArrowDropDown else Icons.AutoMirrored.Filled.ArrowRight,
                        contentDescription = "Expand/Collapse"
                    )
                }

                Checkbox(
                    checked = task.isCompleted,
                    onCheckedChange = { viewModel.toggleTaskCompletion(task) }
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.bodyLarge,
                        textDecoration = if (task.isCompleted) TextDecoration.LineThrough else null
                    )

                    // File Attachment viewer chip
                    task.attachmentUri?.let { uriStr ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .clickable {
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW).apply {
                                            data = Uri.parse(uriStr)
                                            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        }
                                        context.startActivity(intent)
                                    } catch (_: Exception) {
                                        Toast.makeText(context, "No app available to open this file", Toast.LENGTH_SHORT).show()
                                    }
                                }
                        ) {
                            Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = task.attachmentName ?: "Attachment",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // Scheduled Reminder & Google Calendar Indicator
                    if (task.reminderTimestamp != null || task.calendarEventId != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                            task.reminderTimestamp?.let { epoch ->
                                val dateStr = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(epoch))
                                Icon(Icons.Default.Alarm, contentDescription = null, modifier = Modifier.size(13.dp))
                                Spacer(Modifier.width(3.dp))
                                Text(text = dateStr, style = MaterialTheme.typography.bodySmall)
                            }
                            if (task.calendarEventId != null) {
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    Icons.Default.EventAvailable,
                                    contentDescription = "Synced to Google Calendar",
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(2.dp))
                                Text(
                                    text = "Google Cal",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        }
                    }
                }

                IconButton(onClick = { onAddSubtask(task.id) }) {
                    Icon(Icons.Default.AddSubdirectoryArrowRight, contentDescription = "Add Subtask")
                }

                IconButton(onClick = { viewModel.deleteTask(task) }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        // Recursive child branch rendering
        if (isExpanded) {
            subtasks.forEach { subtask ->
                TaskNodeView(
                    task = subtask,
                    depth = depth + 1,
                    viewModel = viewModel,
                    onAddSubtask = onAddSubtask
                )
            }
        }
    }
}

@Composable
fun CreateTaskDialog(
    onDismiss: () -> Unit,
    onConfirm: (
        title: String,
        reminderMs: Long?,
        fileUri: Uri?,
        fileName: String?,
        syncWithGoogleCalendar: Boolean
    ) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var reminderMs by remember { mutableStateOf<Long?>(null) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileName by remember { mutableStateOf<String?>(null) }
    var syncCalendar by remember { mutableStateOf(true) }
    val context = LocalContext.current

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        selectedUri = uri
        uri?.let {
            context.contentResolver.query(it, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex != -1) {
                    selectedFileName = cursor.getString(nameIndex)
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Task") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Task Description") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Date & Time Picker
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
                        } ?: "Pick Date & Time"
                    )
                }

                // File Attachment (SAF)
                OutlinedButton(
                    onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.AttachFile, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(text = selectedFileName ?: "Attach File")
                }

                // Google Calendar Option B Sync Toggle
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
                onClick = { onConfirm(title, reminderMs, selectedUri, selectedFileName, syncCalendar) }
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

