package com.example.infinitetodo

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

enum class TaskPriority {
    LOW,
    MEDIUM,
    HIGH,
    URGENT
}

enum class RecurrenceRule {
    NONE,
    DAILY,
    WEEKLY,
    FORTNIGHTLY,
    MONTHLY,
    SIX_MONTHLY,
    YEARLY,
    CUSTOM
}

enum class AttachmentType {
    FILE,
    IMAGE,
    AUDIO,
    VIDEO,
    CONTACT
}

enum class AppThemeMode {
    LIGHT,
    DARK,
    SYSTEM,
    EMERALD,
    SUNSET,
    OCEAN
}

enum class TaskViewMode {
    DETAILED,
    COMPACT
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
    val priority: TaskPriority = TaskPriority.MEDIUM,
    val isCompleted: Boolean = false,
    val orderIndex: Int = 0,
    val createdTimestamp: Long = System.currentTimeMillis(),
    val lastModifiedTimestamp: Long = System.currentTimeMillis(),
    val reminderTimestamp: Long? = null,
    val dueTimestamp: Long? = null,
    val repeatRule: RecurrenceRule = RecurrenceRule.NONE,
    val repeatIntervalDays: Int = 0,
    val repeatIntervalHours: Int = 0,
    val repeatIntervalMinutes: Int = 0,
    val repeatStartDate: Long? = null,
    val repeatStartTimeMs: Long? = null,
    val repeatEndTimeMs: Long? = null,
    val linkedTaskIds: String? = null,
    val locationName: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val calendarEventId: Long? = null
)

@Entity(
    tableName = "checklists",
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
    tableName = "attachments",
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
    val contactPhone: String? = null,
    val isContactPending: Boolean = true,
    val notes: String? = null,
    val orderIndex: Int = 0,
    val createdTimestamp: Long = System.currentTimeMillis(),
    val lastModifiedTimestamp: Long = System.currentTimeMillis()
)

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE parentId IS NULL ORDER BY orderIndex ASC, id DESC")
    fun getRootTasks(): Flow<List<TaskItem>>

    @Query("SELECT * FROM tasks WHERE parentId = :parentId ORDER BY orderIndex ASC, id ASC")
    fun getSubtasks(parentId: Long): Flow<List<TaskItem>>

    @Query("SELECT * FROM tasks WHERE parentId = :parentId ORDER BY orderIndex ASC, id ASC")
    suspend fun getSubtasksSync(parentId: Long): List<TaskItem>

    @Query("SELECT * FROM tasks WHERE parentId = :parentId")
    suspend fun getChildrenOf(parentId: Long): List<TaskItem>

    @Query("SELECT * FROM tasks ORDER BY createdTimestamp DESC")
    fun getAllTasks(): Flow<List<TaskItem>>

    @Query("SELECT * FROM tasks ORDER BY createdTimestamp DESC")
    suspend fun getAllTasksSync(): List<TaskItem>

    @Query("SELECT * FROM tasks ORDER BY id ASC")
    suspend fun getAllTasksSnapshot(): List<TaskItem>

    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    suspend fun getTaskById(id: Long): TaskItem?

    @Query("SELECT COUNT(*) FROM tasks WHERE parentId = :parentId")
    fun getSubtaskCount(parentId: Long): Flow<Int>

    @Query("""
        SELECT * FROM tasks 
        WHERE title LIKE '%' || :query || '%' 
           OR notes LIKE '%' || :query || '%' 
           OR tags LIKE '%' || :query || '%' 
           OR locationName LIKE '%' || :query || '%'
        ORDER BY createdTimestamp DESC
    """)
    fun searchTasks(query: String): Flow<List<TaskItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: TaskItem): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllTasks(tasks: List<TaskItem>)

    @Update
    suspend fun updateTask(task: TaskItem)

    @Delete
    suspend fun deleteTask(task: TaskItem)

    @Query("DELETE FROM tasks")
    suspend fun clearAllTasks()

    // Checklist queries
    @Query("SELECT * FROM checklists WHERE taskId = :taskId ORDER BY orderIndex ASC, id ASC")
    fun getChecklist(taskId: Long): Flow<List<ChecklistItem>>

    @Query("SELECT * FROM checklists WHERE taskId = :taskId ORDER BY orderIndex ASC, id ASC")
    suspend fun getChecklistSnapshot(taskId: Long): List<ChecklistItem>

    @Query("SELECT * FROM checklists ORDER BY id ASC")
    suspend fun getAllChecklistSnapshot(): List<ChecklistItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChecklistItem(item: ChecklistItem): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllChecklistItems(items: List<ChecklistItem>)

    @Update
    suspend fun updateChecklistItem(item: ChecklistItem)

    @Delete
    suspend fun deleteChecklistItem(item: ChecklistItem)

    // Attachment queries
    @Query("SELECT * FROM attachments WHERE taskId = :taskId ORDER BY orderIndex ASC, id ASC")
    fun getAttachments(taskId: Long): Flow<List<RichAttachment>>

    @Query("SELECT * FROM attachments WHERE taskId = :taskId ORDER BY orderIndex ASC, id ASC")
    suspend fun getAttachmentsSnapshot(taskId: Long): List<RichAttachment>

    @Query("SELECT * FROM attachments ORDER BY id ASC")
    suspend fun getAllAttachmentsSnapshot(): List<RichAttachment>

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
    version = 2,
    exportSchema = false
)
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
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
