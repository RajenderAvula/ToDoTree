package com.example.infinitetodo

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupRestoreManager(private val context: Context) {

    suspend fun createZipBackup(dao: TaskDao, destinationStream: OutputStream): Boolean {
        return try {
            val tasks = dao.getAllTasksSnapshot()
            val checklists = dao.getAllChecklistSnapshot()
            val attachments = dao.getAllAttachmentsSnapshot()

            val zipOut = ZipOutputStream(BufferedOutputStream(destinationStream))

            val rootJson = JSONObject()
            val tasksArray = JSONArray()
            for (t in tasks) {
                val obj = JSONObject().apply {
                    put("id", t.id)
                    put("parentId", t.parentId ?: JSONObject.NULL)
                    put("title", t.title)
                    put("notes", t.notes ?: JSONObject.NULL)
                    put("tags", t.tags ?: JSONObject.NULL)
                    put("isCompleted", t.isCompleted)
                    put("priority", t.priority.name)
                    put("reminderTimestamp", t.reminderTimestamp ?: JSONObject.NULL)
                    put("dueTimestamp", t.dueTimestamp ?: JSONObject.NULL)
                    put("repeatRule", t.repeatRule.name)
                    put("repeatIntervalDays", t.repeatIntervalDays)
                    put("repeatIntervalHours", t.repeatIntervalHours)
                    put("repeatIntervalMinutes", t.repeatIntervalMinutes)
                    put("repeatStartDate", t.repeatStartDate ?: JSONObject.NULL)
                    put("repeatStartTimeMs", t.repeatStartTimeMs ?: JSONObject.NULL)
                    put("repeatEndTimeMs", t.repeatEndTimeMs ?: JSONObject.NULL)
                    put("linkedTaskIds", t.linkedTaskIds ?: JSONObject.NULL)
                    put("orderIndex", t.orderIndex)
                    put("createdTimestamp", t.createdTimestamp)
                    put("lastModifiedTimestamp", t.lastModifiedTimestamp)
                }
                tasksArray.put(obj)
            }
            rootJson.put("tasks", tasksArray)

            val checklistArray = JSONArray()
            for (c in checklists) {
                val obj = JSONObject().apply {
                    put("id", c.id)
                    put("taskId", c.taskId)
                    put("text", c.text)
                    put("notes", c.notes ?: JSONObject.NULL)
                    put("isDone", c.isDone)
                    put("orderIndex", c.orderIndex)
                    put("createdTimestamp", c.createdTimestamp)
                    put("lastModifiedTimestamp", c.lastModifiedTimestamp)
                }
                checklistArray.put(obj)
            }
            rootJson.put("checklists", checklistArray)

            val attachmentsArray = JSONArray()
            for (a in attachments) {
                val obj = JSONObject().apply {
                    put("id", a.id)
                    put("taskId", a.taskId)
                    put("type", a.type.name)
                    put("uriString", a.uriString)
                    put("displayName", a.displayName)
                    put("notes", a.notes ?: JSONObject.NULL)
                    put("contactPhone", a.contactPhone ?: JSONObject.NULL)
                    put("isContactPending", a.isContactPending)
                    put("orderIndex", a.orderIndex)
                    put("createdTimestamp", a.createdTimestamp)
                    put("lastModifiedTimestamp", a.lastModifiedTimestamp)
                }
                attachmentsArray.put(obj)
            }
            rootJson.put("attachments", attachmentsArray)

            zipOut.putNextEntry(ZipEntry("metadata.json"))
            zipOut.write(rootJson.toString(2).toByteArray(Charsets.UTF_8))
            zipOut.closeEntry()

            for (att in attachments) {
                try {
                    if (att.type == AttachmentType.AUDIO && att.uriString.startsWith("/")) {
                        val file = File(att.uriString)
                        if (file.exists() && file.isFile) {
                            zipOut.putNextEntry(ZipEntry("files/att_${att.id}_${file.name}"))
                            file.inputStream().use { input: FileInputStream -> input.copyTo(zipOut) }
                            zipOut.closeEntry()
                        }
                    } else if (att.type != AttachmentType.CONTACT) {
                        val uri = Uri.parse(att.uriString)
                        val stream = context.contentResolver.openInputStream(uri)
                        stream?.use { inStream: InputStream ->
                            val safeName = att.displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                            zipOut.putNextEntry(ZipEntry("files/att_${att.id}_$safeName"))
                            inStream.copyTo(zipOut)
                            zipOut.closeEntry()
                        }
                    }
                } catch (_: Exception) {}
            }

            zipOut.flush()
            zipOut.close()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun createMailAttachmentBackup(dao: TaskDao): Uri? {
        return try {
            val backupFile = File(context.cacheDir, "ToDoTree_Backup_${System.currentTimeMillis()}.zip")
            val success = createZipBackup(dao, FileOutputStream(backupFile))
            if (success) {
                FileProvider.getUriForFile(context, "${context.packageName}.provider", backupFile)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun restoreFromZip(dao: TaskDao, sourceStream: InputStream): Boolean {
        return try {
            val zipIn = ZipInputStream(BufferedInputStream(sourceStream))
            var entry: ZipEntry? = zipIn.nextEntry
            var metadataJsonString: String? = null
            val restoredFilesDir = File(context.filesDir, "restored_attachments").apply { mkdirs() }

            val fileMap = mutableMapOf<String, String>()

            while (entry != null) {
                val entryName = entry.name
                if (entryName == "metadata.json") {
                    val baos = ByteArrayOutputStream()
                    zipIn.copyTo(baos)
                    metadataJsonString = baos.toString(Charsets.UTF_8.name())
                } else if (entryName.startsWith("files/")) {
                    val cleanFileName = entryName.substringAfter("files/")
                    val targetFile = File(restoredFilesDir, cleanFileName)
                    targetFile.outputStream().use { outStream: FileOutputStream -> zipIn.copyTo(outStream) }
                    fileMap[entryName] = targetFile.toURI().toString()
                }
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
            zipIn.close()

            if (metadataJsonString == null) return false

            val rootJson = JSONObject(metadataJsonString)
            val tasksArray = rootJson.optJSONArray("tasks") ?: JSONArray()
            val checklistsArray = rootJson.optJSONArray("checklists") ?: JSONArray()
            val attachmentsArray = rootJson.optJSONArray("attachments") ?: JSONArray()

            val idMapping = mutableMapOf<Long, Long>()

            dao.clearAllTasks()

            for (i in 0 until tasksArray.length()) {
                val obj = tasksArray.getJSONObject(i)
                val oldId = obj.getLong("id")
                val oldParentId = if (obj.isNull("parentId")) null else obj.getLong("parentId")
                val mappedParentId = if (oldParentId != null) idMapping[oldParentId] else null

                val priorityStr = obj.optString("priority", "MEDIUM")
                val priorityVal = try { TaskPriority.valueOf(priorityStr) } catch (_: Exception) { TaskPriority.MEDIUM }

                val repeatStr = obj.optString("repeatRule", "NONE")
                val repeatVal = try { RecurrenceRule.valueOf(repeatStr) } catch (_: Exception) { RecurrenceRule.NONE }

                val now = System.currentTimeMillis()
                val task = TaskItem(
                    parentId = mappedParentId,
                    title = obj.getString("title"),
                    notes = if (obj.isNull("notes")) null else obj.getString("notes"),
                    tags = if (obj.isNull("tags")) null else obj.getString("tags"),
                    isCompleted = obj.optBoolean("isCompleted", false),
                    priority = priorityVal,
                    reminderTimestamp = if (obj.isNull("reminderTimestamp")) null else obj.getLong("reminderTimestamp"),
                    dueTimestamp = if (obj.isNull("dueTimestamp")) null else obj.getLong("dueTimestamp"),
                    repeatRule = repeatVal,
                    repeatIntervalDays = obj.optInt("repeatIntervalDays", 0),
                    repeatIntervalHours = obj.optInt("repeatIntervalHours", 0),
                    repeatIntervalMinutes = obj.optInt("repeatIntervalMinutes", 0),
                    repeatStartDate = if (obj.isNull("repeatStartDate")) null else obj.getLong("repeatStartDate"),
                    repeatStartTimeMs = if (obj.isNull("repeatStartTimeMs")) null else obj.getLong("repeatStartTimeMs"),
                    repeatEndTimeMs = if (obj.isNull("repeatEndTimeMs")) null else obj.getLong("repeatEndTimeMs"),
                    calendarEventId = null,
                    linkedTaskIds = if (obj.isNull("linkedTaskIds")) null else obj.getString("linkedTaskIds"),
                    orderIndex = obj.optInt("orderIndex", 0),
                    createdTimestamp = obj.optLong("createdTimestamp", now),
                    lastModifiedTimestamp = obj.optLong("lastModifiedTimestamp", now)
                )

                val newId = dao.insertTask(task)
                idMapping[oldId] = newId
            }

            val newChecklists = mutableListOf<ChecklistItem>()
            for (i in 0 until checklistsArray.length()) {
                val obj = checklistsArray.getJSONObject(i)
                val oldTaskId = obj.getLong("taskId")
                val newTaskId = idMapping[oldTaskId] ?: continue

                val now = System.currentTimeMillis()
                newChecklists.add(
                    ChecklistItem(
                        taskId = newTaskId,
                        text = obj.getString("text"),
                        notes = if (obj.isNull("notes")) null else obj.getString("notes"),
                        isDone = obj.optBoolean("isDone", false),
                        orderIndex = obj.optInt("orderIndex", 0),
                        createdTimestamp = obj.optLong("createdTimestamp", now),
                        lastModifiedTimestamp = obj.optLong("lastModifiedTimestamp", now)
                    )
                )
            }
            dao.insertAllChecklistItems(newChecklists)

            val newAttachments = mutableListOf<RichAttachment>()
            for (i in 0 until attachmentsArray.length()) {
                val obj = attachmentsArray.getJSONObject(i)
                val oldId = obj.getLong("id")
                val oldTaskId = obj.getLong("taskId")
                val newTaskId = idMapping[oldTaskId] ?: continue

                val typeStr = obj.optString("type", "FILE")
                val typeVal = try { AttachmentType.valueOf(typeStr) } catch (_: Exception) { AttachmentType.FILE }

                val displayName = obj.getString("displayName")
                val originalUri = obj.getString("uriString")

                val safeName = displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val zipKey = "files/att_${oldId}_$safeName"
                val finalUri = fileMap[zipKey] ?: originalUri

                val now = System.currentTimeMillis()
                newAttachments.add(
                    RichAttachment(
                        taskId = newTaskId,
                        type = typeVal,
                        uriString = finalUri,
                        displayName = displayName,
                        notes = if (obj.isNull("notes")) null else obj.getString("notes"),
                        contactPhone = if (obj.isNull("contactPhone")) null else obj.getString("contactPhone"),
                        isContactPending = obj.optBoolean("isContactPending", true),
                        orderIndex = obj.optInt("orderIndex", 0),
                        createdTimestamp = obj.optLong("createdTimestamp", now),
                        lastModifiedTimestamp = obj.optLong("lastModifiedTimestamp", now)
                    )
                )
            }
            dao.insertAllAttachments(newAttachments)

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
