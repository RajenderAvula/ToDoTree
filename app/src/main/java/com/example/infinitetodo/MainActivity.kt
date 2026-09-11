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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRight
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
            TopAppBar(title = { Text("Hierarchical Infinite Tasks") })
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
    viewModel: TaskViewModel,
    onAddSubtask: (Long) -> Unit,
    onEditTask: (TaskItem) -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }
    var isPlayingVoice by remember { mutableStateOf(false) }
    var newChecklistText by remember { mutableStateOf("") }
    var showAddChecklistField by remember { mutableStateOf(false) }

    val subtasks by viewModel.getSubtasks(task.id).collectAsState(initial = emptyList())
    val checklist by viewModel.getChecklist(task.id).collectAsState(initial = emptyList())
    val attachments by viewModel.getAttachments(task.id).collectAsState(initial = emptyList())

    val context = LocalContext.current
    val audioHelper = remember { AudioRecorderHelper(context) }

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
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(6.dp)) {
                // Header row: Expand, Complete, Title, and Slide Move Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        modifier = Modifier.size(28.dp),
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
                        style = MaterialTheme.typography.bodyLarge,
                        textDecoration = if (task.isCompleted) TextDecoration.LineThrough else null,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onEditTask(task) }
                    )

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

                // Voice Note player & Caller Chip
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

                // MOVABLE ATTACHMENTS LIST
                if (attachments.isNotEmpty()) {
                    Text(
                        "Attachments (Sliding):",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 32.dp, top = 2.dp)
                    )
                    attachments.forEach { att ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 32.dp, top = 2.dp)
                        ) {
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
                            IconButton(modifier = Modifier.size(24.dp), onClick = { viewModel.moveAttachment(att, true) }) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move Attachment Up", modifier = Modifier.size(14.dp))
                            }
                            IconButton(modifier = Modifier.size(24.dp), onClick = { viewModel.moveAttachment(att, false) }) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move Attachment Down", modifier = Modifier.size(14.dp))
                            }
                            IconButton(modifier = Modifier.size(24.dp), onClick = { viewModel.deleteAttachment(att) }) {
                                Icon(Icons.Default.Close, contentDescription = "Delete Attachment", modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }

                // MOVABLE CHECKLIST ITEMS
                if (checklist.isNotEmpty()) {
                    Text(
                        "Checklist:",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 32.dp, top = 4.dp)
                    )
                    checklist.forEach { item ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 32.dp)
                        ) {
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
                            IconButton(modifier = Modifier.size(24.dp), onClick = { viewModel.moveChecklistItem(item, true) }) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move Item Up", modifier = Modifier.size(14.dp))
                            }
                            IconButton(modifier = Modifier.size(24.dp), onClick = { viewModel.moveChecklistItem(item, false) }) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move Item Down", modifier = Modifier.size(14.dp))
                            }
                            IconButton(modifier = Modifier.size(24.dp), onClick = { viewModel.deleteChecklistItem(item) }) {
                                Icon(Icons.Default.Close, contentDescription = "Delete Item", modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }

                // Add checklist item inline field
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

                // Bottom actions: Add Checklist, Add Attachment, Add Subtask, Edit, Delete
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

        // Recursive subtree
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

                // Voice Recorder Control
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

                // Contact Picker
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
