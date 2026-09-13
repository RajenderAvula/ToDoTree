package com.example.infinitetodo

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

enum class TaskPriority {
    LOW, MEDIUM, HIGH, URGENT
}

enum class RecurrenceRule {
    NONE, DAILY, WEEKLY, MONTHLY, CUSTOM
}

enum class AttachmentType {
    FILE, IMAGE, AUDIO, VIDEO, CONTACT
}

@Entity(
    tableName = "tasks",
    foreignKeys = [
        ForeignKey(
            entity = TaskItem::class,
            parentColumns = ["id"],
            childColumns = ["parentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["parentId"])]
)
data class TaskItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val parentId: Long? = null,
    val title: String,
    val notes: String? = null,
    val tags: String? = null,
    val isCompleted: Boolean = false,
    val priority: TaskPriority = TaskPriority.MEDIUM,
    val reminderTimestamp: Long? = null,
    val dueTimestamp: Long? = null,
    val repeatRule: RecurrenceRule = RecurrenceRule.NONE,
    val repeatIntervalDays: Int = 1,
    val repeatTimestampMs: Long? = null, // Stores combined Date (Calendar) + Time (Clock)
    val calendarEventId: Long? = null,
    val linkedTaskIds: String? = null,
    val orderIndex: Int = 0,
    val createdTimestamp: Long = System.currentTimeMillis(),
    val lastModifiedTimestamp: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "checklist_items",
    foreignKeys = [
        ForeignKey(
            entity = TaskItem::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["taskId"])]
)
data class ChecklistItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val taskId: Long,
    val text: String,
    val notes: String? = null,
    val isDone: Boolean = false,
    val orderIndex: Int = 0,
    val createdTimestamp: Long = System.currentTimeMillis(),
    val lastModifiedTimestamp: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "rich_attachments",
    foreignKeys = [
        ForeignKey(
            entity = TaskItem::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["taskId"])]
)
data class RichAttachment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val taskId: Long,
    val type: AttachmentType,
    val uriString: String,
    val displayName: String,
    val notes: String? = null,
    val contactPhone: String? = null,
    val isContactPending: Boolean = true,
    val orderIndex: Int = 0,
    val createdTimestamp: Long = System.currentTimeMillis(),
    val lastModifiedTimestamp: Long = System.currentTimeMillis()
)

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE parentId IS NULL ORDER BY orderIndex ASC, id ASC")
    fun getRootTasks(): Flow<List<TaskItem>>

    @Query("SELECT * FROM tasks WHERE parentId = :parentId ORDER BY orderIndex ASC, id ASC")
    fun getSubtasks(parentId: Long): Flow<List<TaskItem>>

    @Query("SELECT * FROM tasks WHERE parentId IS :parentId ORDER BY orderIndex ASC, id ASC")
    suspend fun getSubtasksSnapshot(parentId: Long?): List<TaskItem>

    @Query("SELECT COUNT(*) FROM tasks WHERE parentId = :parentId")
    fun getSubtaskCount(parentId: Long): Flow<Int>

    @Query("SELECT * FROM tasks ORDER BY orderIndex ASC, id ASC")
    fun getAllTasksFlow(): Flow<List<TaskItem>>

    @Query("SELECT * FROM tasks")
    suspend fun getAllTasksSnapshot(): List<TaskItem>

    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    suspend fun getTaskById(id: Long): TaskItem?

    @Query("""
        SELECT DISTINCT t.* FROM tasks t
        LEFT JOIN checklist_items c ON t.id = c.taskId
        LEFT JOIN rich_attachments a ON t.id = a.taskId
        WHERE t.title LIKE '%' || :query || '%' 
           OR t.notes LIKE '%' || :query || '%'
           OR t.tags LIKE '%' || :query || '%'
           OR c.text LIKE '%' || :query || '%'
           OR a.displayName LIKE '%' || :query || '%'
           OR a.contactPhone LIKE '%' || :query || '%'
        ORDER BY t.lastModifiedTimestamp DESC
    """)
    fun searchTasks(query: String): Flow<List<TaskItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: TaskItem): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllTasks(tasks: List<TaskItem>): List<Long>

    @Update
    suspend fun updateTask(task: TaskItem)

    @Delete
    suspend fun deleteTask(task: TaskItem)

    @Query("DELETE FROM tasks")
    suspend fun clearAllTasks()

    // Checklist Queries
    @Query("SELECT * FROM checklist_items WHERE taskId = :taskId ORDER BY orderIndex ASC, id ASC")
    fun getChecklistForTask(taskId: Long): Flow<List<ChecklistItem>>

    @Query("SELECT * FROM checklist_items WHERE taskId = :taskId ORDER BY orderIndex ASC, id ASC")
    suspend fun getChecklistSnapshot(taskId: Long): List<ChecklistItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChecklistItem(item: ChecklistItem): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllChecklistItems(items: List<ChecklistItem>)

    @Update
    suspend fun updateChecklistItem(item: ChecklistItem)

    @Delete
    suspend fun deleteChecklistItem(item: ChecklistItem)

    // Rich Attachment Queries
    @Query("SELECT * FROM rich_attachments WHERE taskId = :taskId ORDER BY orderIndex ASC, id ASC")
    fun getAttachmentsForTask(taskId: Long): Flow<List<RichAttachment>>

    @Query("SELECT * FROM rich_attachments WHERE taskId = :taskId ORDER BY orderIndex ASC, id ASC")
    suspend fun getAttachmentsSnapshot(taskId: Long): List<RichAttachment>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttachment(attachment: RichAttachment): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllAttachments(attachments: List<RichAttachment>)

    @Update
    suspend fun updateAttachment(attachment: RichAttachment)

    @Delete
    suspend fun deleteAttachment(attachment: RichAttachment)
}

@Database(
    entities = [TaskItem::class, ChecklistItem::class, RichAttachment::class],
    version = 11,
    exportSchema = false
)
@TypeConverters(TaskConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "infinite_todo.db"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class TaskConverters {
    @TypeConverter
    fun fromPriority(priority: TaskPriority): String = priority.name

    @TypeConverter
    fun toPriority(name: String): TaskPriority = try {
        TaskPriority.valueOf(name)
    } catch (_: Exception) {
        TaskPriority.MEDIUM
    }

    @TypeConverter
    fun fromRecurrence(recurrence: RecurrenceRule): String = recurrence.name

    @TypeConverter
    fun toRecurrence(name: String): RecurrenceRule = try {
        RecurrenceRule.valueOf(name)
    } catch (_: Exception) {
        RecurrenceRule.NONE
    }

    @TypeConverter
    fun fromAttachmentType(type: AttachmentType): String = type.name

    @TypeConverter
    fun toAttachmentType(name: String): AttachmentType = try {
        AttachmentType.valueOf(name)
    } catch (_: Exception) {
        AttachmentType.FILE
    }
}
