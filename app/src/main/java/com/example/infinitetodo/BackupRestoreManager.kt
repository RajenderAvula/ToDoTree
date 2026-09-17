package com.example.infinitetodo

import android.content.Context
import android.net.Uri
import android.util.Log
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

            // 1. Build JSON metadata
            val rootJson = JSONObject()
            val tasksArray = JSONArray()
            for (t in tasks) {
                val obj = JSONObject().apply {
                    put("id", t.id)
                    put("parentId", t.parentId ?: JSONObject.NULL)
                    put("title", t.title)
                    put("notes", t.notes ?: JSONObject.NULL)
                    put("tags", t.tags ?: JSONObject.NULL)
                    put("priority", t.priority.name)
                    put("isCompleted", t.isCompleted)
                    put("completedTimestamp", t.completedTimestamp ?: JSONObject.NULL)
                    put("createdTimestamp", t.createdTimestamp)
                    put("lastModifiedTimestamp", t.lastModifiedTimestamp)
                    put("reminderTimestamp", t.reminderTimestamp ?: JSONObject.NULL)
                    put("dueTimestamp", t.dueTimestamp ?: JSONObject.NULL)
                    put("repeatRule", t.repeatRule.name)
                    put("repeatIntervalDays", t.repeatIntervalDays)
                    put("repeatIntervalHours", t.repeatIntervalHours)
                    put("repeatIntervalMinutes", t.repeatIntervalMinutes)
                    put("repeatStartDate", t.repeatStartDate ?: JSONObject.NULL)
                    put("repeatStartTimeMs", t.repeatStartTimeMs ?: JSONObject.NULL)
                    put("repeatEndTimeMs", t.repeatEndTimeMs ?: JSONObject.NULL)
                    put("locationName", t.locationName ?: JSONObject.NULL)
                    put("latitude", t.latitude ?: JSONObject.NULL)
                    put("longitude", t.longitude ?: JSONObject.NULL)
                    put("orderIndex", t.orderIndex)
                    put("linkedTaskIds", t.linkedTaskIds ?: JSONObject.NULL)
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
                    put("createdTimestamp", c.createdTimestamp)
                    put("orderIndex", c.orderIndex)
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
                    put("createdTimestamp", a.createdTimestamp)
                    put("orderIndex", a.orderIndex)
                }
                attachmentsArray.put(obj)
            }
            rootJson.put("attachments", attachmentsArray)

            zipOut.putNextEntry(ZipEntry("metadata.json"))
            zipOut.write(rootJson.toString(2).toByteArray(Charsets.UTF_8))
            zipOut.closeEntry()

            // 2. Package attachments (Audio files, videos, documents)
            for (att in attachments) {
                if (att.type == AttachmentType.CONTACT) continue

                val safeName = att.displayName.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
                val entryPath = "files/att_${att.id}_$safeName"

                try {
                    val rawUri = att.uriString
                    if (rawUri.startsWith("/")) {
                        val localFile = File(rawUri)
                        if (localFile.exists() && localFile.isFile) {
                            zipOut.putNextEntry(ZipEntry(entryPath))
                            localFile.inputStream().use { inStream ->
                                inStream.copyTo(zipOut)
                            }
                            zipOut.closeEntry()
                        }
                    } else {
                        val parsedUri = Uri.parse(rawUri)
                        context.contentResolver.openInputStream(parsedUri)?.use { inStream ->
                            zipOut.putNextEntry(ZipEntry(entryPath))
                            inStream.copyTo(zipOut)
                            zipOut.closeEntry()
                        }
                    }
                } catch (e: Exception) {
                    Log.w("BackupManager", "Skipping attachment ${att.displayName}", e)
                }
            }

            zipOut.flush()
            zipOut.close()
            true
        } catch (e: Exception) {
            Log.e("BackupManager", "createZipBackup failed", e)
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
        } catch (e: Exception) {
            Log.e("BackupManager", "createMailAttachmentBackup failed", e)
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
                    targetFile.outputStream().use { fileOut ->
                        zipIn.copyTo(fileOut)
                    }
                    fileMap[entryName] = targetFile.absolutePath
                }
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
            zipIn.close()

            if (metadataJsonString.isNullOrBlank()) {
                Log.e("BackupManager", "Restore failed: metadata.json missing or empty")
                return false
            }

            val rootJson = JSONObject(metadataJsonString)
            val tasksArray = rootJson.optJSONArray("tasks") ?: JSONArray()
            val checklistsArray = rootJson.optJSONArray("checklists") ?: JSONArray()
            val attachmentsArray = rootJson.optJSONArray("attachments") ?: JSONArray()

            // 1. Clear database
            dao.clearAllTasks()

            // 2. Insert tasks hierarchy by layers (roots first, then children to avoid Foreign Key violations)
            val taskObjs = mutableListOf<JSONObject>()
            for (i in 0 until tasksArray.length()) {
                taskObjs.add(tasksArray.getJSONObject(i))
            }

            val idMapping = mutableMapOf<Long, Long>()
            val remainingTasks = taskObjs.toMutableList()

            var iterations = 0
            while (remainingTasks.isNotEmpty() && iterations < 50) {
                iterations++
                val iterator = remainingTasks.iterator()
                while (iterator.hasNext()) {
                    val obj = iterator.next()
                    val oldId = obj.getLong("id")
                    val oldParentId = if (obj.isNull("parentId")) null else obj.getLong("parentId")

                    if (oldParentId == null || idMapping.containsKey(oldParentId)) {
                        val mappedParentId = if (oldParentId != null) idMapping[oldParentId] else null

                        val priorityStr = obj.optString("priority", TaskPriority.MEDIUM.name)
                        val priority = try { TaskPriority.valueOf(priorityStr) } catch (_: Exception) { TaskPriority.MEDIUM }

                        val repeatRuleStr = obj.optString("repeatRule", RecurrenceRule.NONE.name)
                        val repeatRule = try { RecurrenceRule.valueOf(repeatRuleStr) } catch (_: Exception) { RecurrenceRule.NONE }

                        val taskItem = TaskItem(
                            parentId = mappedParentId,
                            title = obj.optString("title", "Untitled Task"),
                            notes = if (obj.isNull("notes")) null else obj.getString("notes"),
                            tags = if (obj.isNull("tags")) null else obj.getString("tags"),
                            priority = priority,
                            isCompleted = obj.optBoolean("isCompleted", false),
                            completedTimestamp = if (obj.isNull("completedTimestamp")) null else obj.getLong("completedTimestamp"),
                            createdTimestamp = obj.optLong("createdTimestamp", System.currentTimeMillis()),
                            lastModifiedTimestamp = obj.optLong("lastModifiedTimestamp", System.currentTimeMillis()),
                            reminderTimestamp = if (obj.isNull("reminderTimestamp")) null else obj.getLong("reminderTimestamp"),
                            dueTimestamp = if (obj.isNull("dueTimestamp")) null else obj.getLong("dueTimestamp"),
                            repeatRule = repeatRule,
                            repeatIntervalDays = obj.optInt("repeatIntervalDays", 0),
                            repeatIntervalHours = obj.optInt("repeatIntervalHours", 0),
                            repeatIntervalMinutes = obj.optInt("repeatIntervalMinutes", 0),
                            repeatStartDate = if (obj.isNull("repeatStartDate")) null else obj.getLong("repeatStartDate"),
                            repeatStartTimeMs = if (obj.isNull("repeatStartTimeMs")) null else obj.getLong("repeatStartTimeMs"),
                            repeatEndTimeMs = if (obj.isNull("repeatEndTimeMs")) null else obj.getLong("repeatEndTimeMs"),
                            locationName = if (obj.isNull("locationName")) null else obj.getString("locationName"),
                            latitude = if (obj.isNull("latitude")) null else obj.getDouble("latitude"),
                            longitude = if (obj.isNull("longitude")) null else obj.getDouble("longitude"),
                            orderIndex = obj.optInt("orderIndex", 0),
                            linkedTaskIds = if (obj.isNull("linkedTaskIds")) null else obj.getString("linkedTaskIds")
                        )

                        val newId = dao.insertTask(taskItem)
                        idMapping[oldId] = newId
                        iterator.remove()
                    }
                }
            }

            // Insert any orphan tasks as root tasks
            for (obj in remainingTasks) {
                val oldId = obj.getLong("id")
                val taskItem = TaskItem(
                    parentId = null,
                    title = obj.optString("title", "Untitled Task"),
                    orderIndex = obj.optInt("orderIndex", 0)
                )
                val newId = dao.insertTask(taskItem)
                idMapping[oldId] = newId
            }

            // 3. Insert checklists
            val newChecklists = mutableListOf<ChecklistItem>()
            for (i in 0 until checklistsArray.length()) {
                val obj = checklistsArray.getJSONObject(i)
                val oldTaskId = obj.getLong("taskId")
                val newTaskId = idMapping[oldTaskId] ?: continue

                newChecklists.add(
                    ChecklistItem(
                        taskId = newTaskId,
                        text = obj.optString("text", ""),
                        notes = if (obj.isNull("notes")) null else obj.getString("notes"),
                        isDone = obj.optBoolean("isDone", false),
                        createdTimestamp = obj.optLong("createdTimestamp", System.currentTimeMillis()),
                        orderIndex = obj.optInt("orderIndex", 0)
                    )
                )
            }
            if (newChecklists.isNotEmpty()) {
                dao.insertAllChecklistItems(newChecklists)
            }

            // 4. Insert rich attachments
            val newAttachments = mutableListOf<RichAttachment>()
            for (i in 0 until attachmentsArray.length()) {
                val obj = attachmentsArray.getJSONObject(i)
                val oldTaskId = obj.getLong("taskId")
                val newTaskId = idMapping[oldTaskId] ?: continue
                val oldId = obj.getLong("id")
                val displayName = obj.optString("displayName", "Attachment")

                val safeName = displayName.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
                val zipKey = "files/att_${oldId}_$safeName"
                val targetPath = fileMap[zipKey] ?: obj.optString("uriString", "")

                val typeStr = obj.optString("type", AttachmentType.FILE.name)
                val attType = try { AttachmentType.valueOf(typeStr) } catch (_: Exception) { AttachmentType.FILE }

                newAttachments.add(
                    RichAttachment(
                        taskId = newTaskId,
                        type = attType,
                        uriString = targetPath,
                        displayName = displayName,
                        notes = if (obj.isNull("notes")) null else obj.getString("notes"),
                        contactPhone = if (obj.isNull("contactPhone")) null else obj.getString("contactPhone"),
                        isContactPending = obj.optBoolean("isContactPending", false),
                        createdTimestamp = obj.optLong("createdTimestamp", System.currentTimeMillis()),
                        orderIndex = obj.optInt("orderIndex", 0)
                    )
                )
            }
            if (newAttachments.isNotEmpty()) {
                dao.insertAllAttachments(newAttachments)
            }

            true
        } catch (e: Exception) {
            Log.e("BackupManager", "restoreFromZip failed", e)
            false
        }
    }
}
