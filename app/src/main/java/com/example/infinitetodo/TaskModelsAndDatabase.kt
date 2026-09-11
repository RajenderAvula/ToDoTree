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
    val isCompleted: Boolean = false,
    val reminderTimestamp: Long? = null,
    val attachmentUri: String? = null,
    val attachmentName: String? = null,
    val calendarEventId: Long? = null,
    val contactName: String? = null,
    val contactPhone: String? = null,
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

    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    suspend fun getTaskById(id: Long): TaskItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: TaskItem): Long

    @Update
    suspend fun updateTask(task: TaskItem)

    @Delete
    suspend fun deleteTask(task: TaskItem)
}

@Database(entities = [TaskItem::class], version = 2, exportSchema = false)
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
