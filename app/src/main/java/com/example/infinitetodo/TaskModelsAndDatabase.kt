package com.example.infinitetodo

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.room.*
import kotlinx.coroutines.flow.Flow

// -----------------------------------------------------------------------------------------
// ENUMS & THEMES
// -----------------------------------------------------------------------------------------
enum class TaskPriority {
    LOW, MEDIUM, HIGH, URGENT
}

enum class RecurrenceRule {
    NONE, DAILY, WEEKLY, FORTNIGHTLY, MONTHLY, SIX_MONTHLY, YEARLY, CUSTOM
}

enum class AttachmentType {
    FILE, IMAGE, AUDIO, VIDEO, CONTACT
}

enum class AppThemeMode {
    LIGHT, DARK, SYSTEM, EMERALD, SUNSET, OCEAN
}

enum class TaskViewMode {
    DETAILED, COMPACT
}

// -----------------------------------------------------------------------------------------
// THEME PREFERENCES & COLOR PALETTES
// -----------------------------------------------------------------------------------------
object ThemePreferences {
    private const val PREF_NAME = "todo_tree_theme_prefs"
    private const val KEY_THEME = "app_theme_mode"
    private const val KEY_VIEW_MODE = "app_view_mode"

    fun saveTheme(context: Context, theme: AppThemeMode) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, theme.name)
            .apply()
    }

    fun getTheme(context: Context): AppThemeMode {
        val name = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_THEME, AppThemeMode.SYSTEM.name)
        return try {
            AppThemeMode.valueOf(name ?: AppThemeMode.SYSTEM.name)
        } catch (_: Exception) {
            AppThemeMode.SYSTEM
        }
    }

    fun saveViewMode(context: Context, mode: TaskViewMode) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VIEW_MODE, mode.name)
            .apply()
    }

    fun getViewMode(context: Context): TaskViewMode {
        val name = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_VIEW_MODE, TaskViewMode.DETAILED.name)
        return try {
            TaskViewMode.valueOf(name ?: TaskViewMode.DETAILED.name)
        } catch (_: Exception) {
            TaskViewMode.DETAILED
        }
    }
}

// Custom Theme Color Schemes
private val EmeraldColorScheme = lightColorScheme(
    primary = Color(0xFF2E7D32),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA5D6A7),
    onPrimaryContainer = Color(0xFF1B5E20),
    secondary = Color(0xFF00796B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF80CBC4),
    onSecondaryContainer = Color(0xFF004D40),
    tertiary = Color(0xFF388E3C),
    background = Color(0xFFF1F8E9),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE8F5E9)
)

private val SunsetColorScheme = lightColorScheme(
    primary = Color(0xFFD84315),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFCCBC),
    onPrimaryContainer = Color(0xFFBF360C),
    secondary = Color(0xFFF57C00),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE0B2),
    onSecondaryContainer = Color(0xFFE65100),
    tertiary = Color(0xFFC2185B),
    background = Color(0xFFFFF8E1),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFFBE9E7)
)

private val OceanColorScheme = lightColorScheme(
    primary = Color(0xFF0277BD),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB3E5FC),
    onPrimaryContainer = Color(0xFF01579B),
    secondary = Color(0xFF00838F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB2EBF2),
    onSecondaryContainer = Color(0xFF006064),
    tertiary = Color(0xFF1565C0),
    background = Color(0xFFE1F5FE),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE0F7FA)
)

private val StandardLightColorScheme = lightColorScheme(
    primary = Color(0xFF1976D2),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFBBDEFB),
    onPrimaryContainer = Color(0xFF0D47A1),
    secondary = Color(0xFF455A64),
    onSecondary = Color.White,
    background = Color(0xFFFAFAFA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFECEFF1)
)

private val StandardDarkColorScheme = darkColorScheme(
    primary = Color(0xFF90CAF9),
    onPrimary = Color(0xFF0D47A1),
    primaryContainer = Color(0xFF1565C0),
    onPrimaryContainer = Color(0xFFE3F2FD),
    secondary = Color(0xFFB0BEC5),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    surfaceVariant = Color(0xFF263238)
)

@Composable
fun InfiniteTodoTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    isDarkSystem: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = when (themeMode) {
        AppThemeMode.LIGHT -> StandardLightColorScheme
        AppThemeMode.DARK -> StandardDarkColorScheme
        AppThemeMode.EMERALD -> EmeraldColorScheme
        AppThemeMode.SUNSET -> SunsetColorScheme
        AppThemeMode.OCEAN -> OceanColorScheme
        AppThemeMode.SYSTEM -> if (isDarkSystem) StandardDarkColorScheme else StandardLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

// -----------------------------------------------------------------------------------------
// ROOM DATABASE ENTITIES
// -----------------------------------------------------------------------------------------
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
    val completedTimestamp: Long? = null,
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

// -----------------------------------------------------------------------------------------
// DAO & DATABASE DEFINITIONS
// -----------------------------------------------------------------------------------------
@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE parentId IS NULL ORDER BY orderIndex ASC, id DESC")
    fun getRootTasks(): Flow<List<TaskItem>>

    @Query("SELECT * FROM tasks WHERE parentId = :parentId ORDER BY orderIndex ASC, id ASC")
    fun getSubtasks(parentId: Long): Flow<List<TaskItem>>

    @Query("SELECT * FROM tasks WHERE parentId = :parentId ORDER BY orderIndex ASC, id ASC")
    suspend fun getSubtasksSync(parentId: Long): List<TaskItem>

    @Query("SELECT * FROM tasks ORDER BY createdTimestamp DESC")
    fun getAllTasks(): Flow<List<TaskItem>>

    @Query("SELECT * FROM tasks ORDER BY createdTimestamp DESC")
    suspend fun getAllTasksSync(): List<TaskItem>

    @Query("SELECT * FROM tasks")
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
    suspend fun insertAllTasks(tasks: List<TaskItem>): List<Long>

    @Update
    suspend fun updateTask(task: TaskItem)

    @Delete
    suspend fun deleteTask(task: TaskItem)

    @Query("DELETE FROM tasks")
    suspend fun clearAllTasks()

    // Checklists
    @Query("SELECT * FROM checklists WHERE taskId = :taskId ORDER BY orderIndex ASC, id ASC")
    fun getChecklist(taskId: Long): Flow<List<ChecklistItem>>

    @Query("SELECT * FROM checklists WHERE taskId = :taskId ORDER BY orderIndex ASC, id ASC")
    suspend fun getChecklistSnapshot(taskId: Long): List<ChecklistItem>

    @Query("SELECT * FROM checklists")
    suspend fun getAllChecklistSnapshot(): List<ChecklistItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChecklistItem(item: ChecklistItem): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllChecklistItems(items: List<ChecklistItem>): List<Long>

    @Update
    suspend fun updateChecklistItem(item: ChecklistItem)

    @Delete
    suspend fun deleteChecklistItem(item: ChecklistItem)

    // Attachments
    @Query("SELECT * FROM attachments WHERE taskId = :taskId ORDER BY orderIndex ASC, id ASC")
    fun getAttachments(taskId: Long): Flow<List<RichAttachment>>

    @Query("SELECT * FROM attachments WHERE taskId = :taskId ORDER BY orderIndex ASC, id ASC")
    suspend fun getAttachmentsSnapshot(taskId: Long): List<RichAttachment>

    @Query("SELECT * FROM attachments")
    suspend fun getAllAttachmentsSnapshot(): List<RichAttachment>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttachment(attachment: RichAttachment): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllAttachments(attachments: List<RichAttachment>): List<Long>

    @Update
    suspend fun updateAttachment(attachment: RichAttachment)

    @Delete
    suspend fun deleteAttachment(attachment: RichAttachment)
}

@Database(
    entities = [TaskItem::class, ChecklistItem::class, RichAttachment::class],
    version = 3,
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
