package com.example.infinitetodo

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

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
    val isCompleted: Boolean = false,
    val reminderTimestamp: Long? = null,
    val calendarEventId: Long? = null,
    val contactName: String? = null,
    val contactPhone: String? = null,
    val contactEmail: String? = null,
    val voiceRecordingPath: String? = null,
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
    val isDone: Boolean = false,
    val orderIndex: Int = 0
)

@Entity(
    tableName = "task_attachments",
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
data class TaskAttachment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val taskId: Long,
    val uriString: String,
    val fileName: String,
    val orderIndex: Int = 0
)

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE parentId IS NULL ORDER BY orderIndex ASC, id ASC")
    fun getRootTasks(): Flow<List<TaskItem>>

    @Query("SELECT * FROM tasks WHERE parentId = :parentId ORDER BY orderIndex ASC, id ASC")
    fun getSubtasks(parentId: Long): Flow<List<TaskItem>>

    @Query("SELECT * FROM tasks WHERE parentId IS :parentId ORDER BY orderIndex ASC, id ASC")
    suspend fun getSubtasksSnapshot(parentId: Long?): List<TaskItem>

    @Query("SELECT * FROM tasks")
    suspend fun getAllTasksSnapshot(): List<TaskItem>

    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    suspend fun getTaskById(id: Long): TaskItem?

    @Query("""
        SELECT * FROM tasks 
        WHERE title LIKE '%' || :query || '%' 
           OR notes LIKE '%' || :query || '%'
           OR contactName LIKE '%' || :query || '%' 
           OR contactPhone LIKE '%' || :query || '%'
           OR contactEmail LIKE '%' || :query || '%'
        ORDER BY lastModifiedTimestamp DESC
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

    @Query("SELECT * FROM checklist_items WHERE taskId = :taskId ORDER BY orderIndex ASC, id ASC")
    fun getChecklistForTask(taskId: Long): Flow<List<ChecklistItem>>

    @Query("SELECT * FROM checklist_items WHERE taskId = :taskId ORDER BY orderIndex ASC, id ASC")
    suspend fun getChecklistSnapshot(taskId: Long): List<ChecklistItem>

    @Query("SELECT * FROM checklist_items")
    suspend fun getAllChecklistSnapshot(): List<ChecklistItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChecklistItem(item: ChecklistItem): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllChecklistItems(items: List<ChecklistItem>)

    @Update
    suspend fun updateChecklistItem(item: ChecklistItem)

    @Delete
    suspend fun deleteChecklistItem(item: ChecklistItem)

    @Query("SELECT * FROM task_attachments WHERE taskId = :taskId ORDER BY orderIndex ASC, id ASC")
    fun getAttachmentsForTask(taskId: Long): Flow<List<TaskAttachment>>

    @Query("SELECT * FROM task_attachments WHERE taskId = :taskId ORDER BY orderIndex ASC, id ASC")
    suspend fun getAttachmentsSnapshot(taskId: Long): List<TaskAttachment>

    @Query("SELECT * FROM task_attachments")
    suspend fun getAllAttachmentsSnapshot(): List<TaskAttachment>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttachment(attachment: TaskAttachment): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllAttachments(attachments: List<TaskAttachment>)

    @Update
    suspend fun updateAttachment(attachment: TaskAttachment)

    @Delete
    suspend fun deleteAttachment(attachment: TaskAttachment)
}

@Database(
    entities = [TaskItem::class, ChecklistItem::class, TaskAttachment::class],
    version = 6,
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
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
