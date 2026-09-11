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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    var taskToEdit by remember { mutableStateOf<TaskItem?>(null) }
    var isCreatingRootTask by remember { mutableStateOf(false) }

    val permissionsToRequest = remember {
        val list = mutableListOf(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.READ_CONTACTS
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
            TopAppBar(
                title = { Text("Hierarchical Infinite Tasks") }
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
                .padding(horizontal = 6.dp, vertical = 4.dp)
        ) {
            items(rootTasks, key = { it.id }) { rootTask ->
                TaskNodeView(
                    task = rootTask,
                    depth = 0,
                    viewModel = viewModel,
                    onAddSubtask = { parentId -> showCreateDialogForParentId = parentId },
                    onEditTask = { taskToEdit = it }
                )
            }
        }

        if (isCreatingRootTask) {
            TaskEditorDialog(
                titleHeader = "New Task",
                initialTitle = "",
                initialReminderMs = null,
                initialUri = null,
                initialFileName = null,
                initialContactName = null,
                initialContactPhone = null,
                onDismiss = { isCreatingRootTask = false },
                onConfirm = { title, time, uri, name, syncCal, cName, cPhone ->
                    viewModel.addTask(title, null, time, uri, name, syncCal, cName, cPhone)
                    isCreatingRootTask = false
                }
            )
        }

        showCreateDialogForParentId?.let { parentId ->
            TaskEditorDialog(
                titleHeader = "New Subtask",
                initialTitle = "",
                initialReminderMs = null,
                initialUri = null,
                initialFileName = null,
                initialContactName = null,
                initialContactPhone = null,
                onDismiss = { showCreateDialogForParentId = null },
                onConfirm = { title, time, uri, name, syncCal, cName, cPhone ->
                    viewModel.addTask(title, parentId, time, uri, name, syncCal, cName, cPhone)
                    showCreateDialogForParentId = null
                }
            )
        }

        taskToEdit?.let { task ->
            TaskEditorDialog(
                titleHeader = "Edit Task",
                initialTitle = task.title,
                initialReminderMs = task.reminderTimestamp,
                initialUri = task.attachmentUri?.let { Uri.parse(it) },
                initialFileName = task.attachmentName,
                initialContactName = task.contactName,
                initialContactPhone = task.contactPhone,
                onDismiss = { taskToEdit = null },
                onConfirm = { title, time, uri, name, syncCal, cName, cPhone ->
                    viewModel.updateTask(task, title, time, uri, name, syncCal, cName, cPhone)
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
    viewModel: TaskViewModel,
    onAddSubtask: (Long) -> Unit,
    onEditTask: (TaskItem) -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }
    val subtasks by viewModel.getSubtasks(task.id).collectAsState(initial = emptyList())
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 14).dp)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        modifier = Modifier.size(28.dp),
                        onClick = { isExpanded = !isExpanded }
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ArrowDropDown else Icons.Default.ArrowRight,
                            contentDescription = "Expand/Collapse"
                        )
                    }

                    Checkbox(
                        checked = task.isCompleted,
                        onCheckedChange = { viewModel.toggleTaskCompletion(task) }
                    )

                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.bodyLarge,
                        textDecoration = if (task.isCompleted) TextDecoration.LineThrough else null,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onEditTask(task) }
                    )

                    // Reordering Sliding Controls (Up / Down)
                    IconButton(
                        modifier = Modifier.size(28.dp),
                        onClick = { viewModel.moveTask(task, directionUp = true) }
                    ) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move Up")
                    }
                    IconButton(
                        modifier = Modifier.size(28.dp),
                        onClick = { viewModel.moveTask(task, directionUp = false) }
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move Down")
                    }
                }

                // Metadata Details: Calling Chip, Attachment, and Alarms
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 36.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Contact phone calling chip
                    if (!task.contactPhone.isNullOrBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .clickable {
                                    val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                                        data = Uri.parse("tel:${task.contactPhone}")
                                    }
                                    context.startActivity(dialIntent)
                                }
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.Default.Call, contentDescription = "Call Contact", modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "${task.contactName ?: "Call"}: ${task.contactPhone}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    // Attachment link
                    task.attachmentUri?.let { uriStr ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable {
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW).apply {
                                            data = Uri.parse(uriStr)
                                            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        }
                                        context.startActivity(intent)
                                    } catch (_: Exception) {
                                        Toast.makeText(context, "No app available to open file", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = task.attachmentName ?: "File",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    // Reminder
                    task.reminderTimestamp?.let { epoch ->
                        val dateStr = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(epoch))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Alarm, contentDescription = null, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(3.dp))
                            Text(text = dateStr, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                // Quick Action Bar: Edit, Copy Subtree, Add Child, Delete
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 36.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(modifier = Modifier.size(28.dp), onClick = { onEditTask(task) }) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(17.dp))
                    }
                    IconButton(modifier = Modifier.size(28.dp), onClick = { viewModel.duplicateTask(task.id, task.parentId) }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy Task", modifier = Modifier.size(17.dp))
                    }
                    IconButton(modifier = Modifier.size(28.dp), onClick = { onAddSubtask(task.id) }) {
                        Icon(Icons.Default.SubdirectoryArrowRight, contentDescription = "Add Child", modifier = Modifier.size(17.dp))
                    }
                    IconButton(modifier = Modifier.size(28.dp), onClick = { viewModel.deleteTask(task) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(17.dp))
                    }
                }
            }
        }

        // Recursive subtree invocation
        if (isExpanded) {
            subtasks.forEach { subtask ->
                TaskNodeView(
                    task = subtask,
                    depth = depth + 1,
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
    initialUri: Uri?,
    initialFileName: String?,
    initialContactName: String?,
    initialContactPhone: String?,
    onDismiss: () -> Unit,
    onConfirm: (
        title: String,
        reminderMs: Long?,
        fileUri: Uri?,
        fileName: String?,
        syncWithGoogleCalendar: Boolean,
        contactName: String?,
        contactPhone: String?
    ) -> Unit
) {
    var title by remember { mutableStateOf(initialTitle) }
    var reminderMs by remember { mutableStateOf(initialReminderMs) }
    var selectedUri by remember { mutableStateOf(initialUri) }
    var selectedFileName by remember { mutableStateOf(initialFileName) }
    var contactName by remember { mutableStateOf(initialContactName) }
    var contactPhone by remember { mutableStateOf(initialContactPhone) }
    var syncCalendar by remember { mutableStateOf(true) }
    val context = LocalContext.current

    // Contact picker launcher
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
                    val hasPhone = cursor.getInt(hasPhoneCol)

                    if (hasPhone > 0) {
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

    // File picker launcher
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

                // Contact Picker Button
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
                        } ?: "Pick Reminder Date & Time"
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
                    onConfirm(title, reminderMs, selectedUri, selectedFileName, syncCalendar, contactName, contactPhone)
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
