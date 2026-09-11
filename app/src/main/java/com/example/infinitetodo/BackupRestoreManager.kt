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
                    put("isCompleted", t.isCompleted)
                    put("reminderTimestamp", t.reminderTimestamp ?: JSONObject.NULL)
                    put("contactName", t.contactName ?: JSONObject.NULL)
                    put("contactPhone", t.contactPhone ?: JSONObject.NULL)
                    put("contactEmail", t.contactEmail ?: JSONObject.NULL)
                    put("voiceRecordingPath", t.voiceRecordingPath ?: JSONObject.NULL)
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
                    put("isDone", c.isDone)
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
                    put("uriString", a.uriString)
                    put("fileName", a.fileName)
                    put("orderIndex", a.orderIndex)
                }
                attachmentsArray.put(obj)
            }
            rootJson.put("attachments", attachmentsArray)

            zipOut.putNextEntry(ZipEntry("metadata.json"))
            zipOut.write(rootJson.toString(2).toByteArray(Charsets.UTF_8))
            zipOut.closeEntry()

            for (t in tasks) {
                val voicePath = t.voiceRecordingPath
                if (!voicePath.isNullOrBlank()) {
                    val file = File(voicePath)
                    if (file.exists()) {
                        zipOut.putNextEntry(ZipEntry("voice_${file.name}"))
                        file.inputStream().use { it.copyTo(zipOut) }
                        zipOut.closeEntry()
                    }
                }
            }

            for (att in attachments) {
                try {
                    val uri = Uri.parse(att.uriString)
                    context.contentResolver.openInputStream(uri)?.use { inStream ->
                        zipOut.putNextEntry(ZipEntry("files/att_${att.id}_${att.fileName}"))
                        inStream.copyTo(zipOut)
                        zipOut.closeEntry()
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
            val restoredVoiceDir = File(context.filesDir, "restored_voices").apply { mkdirs() }

            val fileMap = mutableMapOf<String, String>()

            while (entry != null) {
                val entryName = entry.name
                if (entryName == "metadata.json") {
                    val baos = ByteArrayOutputStream()
                    zipIn.copyTo(baos)
                    metadataJsonString = baos.toString(Charsets.UTF_8.name())
                } else if (entryName.startsWith("voice_")) {
                    val targetVoice = File(restoredVoiceDir, entryName)
                    targetVoice.outputStream().use { zipIn.copyTo(it) }
                    fileMap[entryName] = targetVoice.absolutePath
                } else if (entryName.startsWith("files/")) {
                    val targetFile = File(restoredFilesDir, entryName.substringAfter("files/"))
                    targetFile.outputStream().use { zipIn.copyTo(it) }
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

                var voicePath = if (obj.isNull("voiceRecordingPath")) null else obj.getString("voiceRecordingPath")
                if (voicePath != null) {
                    val voiceFileName = "voice_" + File(voicePath).name
                    if (fileMap.containsKey(voiceFileName)) {
                        voicePath = fileMap[voiceFileName]
                    }
                }

                val now = System.currentTimeMillis()
                val task = TaskItem(
                    parentId = mappedParentId,
                    title = obj.getString("title"),
                    notes = if (obj.isNull("notes")) null else obj.getString("notes"),
                    isCompleted = obj.optBoolean("isCompleted", false),
                    reminderTimestamp = if (obj.isNull("reminderTimestamp")) null else obj.getLong("reminderTimestamp"),
                    contactName = if (obj.isNull("contactName")) null else obj.getString("contactName"),
                    contactPhone = if (obj.isNull("contactPhone")) null else obj.getString("contactPhone"),
                    contactEmail = if (obj.isNull("contactEmail")) null else obj.getString("contactEmail"),
                    voiceRecordingPath = voicePath,
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

                newChecklists.add(
                    ChecklistItem(
                        taskId = newTaskId,
                        text = obj.getString("text"),
                        isDone = obj.optBoolean("isDone", false),
                        orderIndex = obj.optInt("orderIndex", 0)
                    )
                )
            }
            dao.insertAllChecklistItems(newChecklists)

            val newAttachments = mutableListOf<TaskAttachment>()
            for (i in 0 until attachmentsArray.length()) {
                val obj = attachmentsArray.getJSONObject(i)
                val oldId = obj.getLong("id")
                val oldTaskId = obj.getLong("taskId")
                val newTaskId = idMapping[oldTaskId] ?: continue
                val fileName = obj.getString("fileName")

                val zipKey = "files/att_${oldId}_${fileName}"
                val targetUri = fileMap[zipKey] ?: obj.getString("uriString")

                newAttachments.add(
                    TaskAttachment(
                        taskId = newTaskId,
                        uriString = targetUri,
                        fileName = fileName,
                        orderIndex = obj.optInt("orderIndex", 0)
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
