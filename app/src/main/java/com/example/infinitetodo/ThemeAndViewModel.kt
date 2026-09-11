package com.example.infinitetodo

import android.content.Context
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class AppThemeMode {
    SYSTEM, LIGHT, DARK, EMERALD, SUNSET, OCEAN
}

enum class TaskViewMode {
    DETAILED, COMPACT
}

object ThemePreferences {
    private const val PREFS_NAME = "todo_tree_theme_prefs"
    private const val KEY_THEME = "key_app_theme"
    private const val KEY_VIEW_MODE = "key_view_mode"

    fun saveTheme(context: Context, theme: AppThemeMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, theme.name)
            .apply()
    }

    fun getTheme(context: Context): AppThemeMode {
        val name = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_THEME, AppThemeMode.SYSTEM.name)
        return try {
            AppThemeMode.valueOf(name ?: AppThemeMode.SYSTEM.name)
        } catch (_: Exception) {
            AppThemeMode.SYSTEM
        }
    }

    fun saveViewMode(context: Context, mode: TaskViewMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VIEW_MODE, mode.name)
            .apply()
    }

    fun getViewMode(context: Context): TaskViewMode {
        val name = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_VIEW_MODE, TaskViewMode.DETAILED.name)
        return try {
            TaskViewMode.valueOf(name ?: TaskViewMode.DETAILED.name)
        } catch (_: Exception) {
            TaskViewMode.DETAILED
        }
    }
}

// Custom theme color palettes
private val EmeraldColorScheme = lightColorScheme(
    primary = Color(0xFF0F766E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCFBF1),
    onPrimaryContainer = Color(0xFF115E59),
    secondary = Color(0xFF047857),
    background = Color(0xFFF0FDF4),
    surface = Color(0xFFFFFFFF)
)

private val SunsetColorScheme = lightColorScheme(
    primary = Color(0xFFEA580C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFEDD5),
    onPrimaryContainer = Color(0xFF9A3412),
    secondary = Color(0xFFD97706),
    background = Color(0xFFFFF7ED),
    surface = Color(0xFFFFFFFF)
)

private val OceanColorScheme = lightColorScheme(
    primary = Color(0xFF0284C7),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF0369A1),
    secondary = Color(0xFF2563EB),
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFFFFFFF)
)

@Composable
fun InfiniteTodoTheme(
    themeMode: AppThemeMode,
    isDarkSystem: Boolean,
    content: @Composable () -> Unit
) {
    val colorScheme = when (themeMode) {
        AppThemeMode.SYSTEM -> if (isDarkSystem) darkColorScheme() else lightColorScheme()
        AppThemeMode.LIGHT -> lightColorScheme()
        AppThemeMode.DARK -> darkColorScheme()
        AppThemeMode.EMERALD -> EmeraldColorScheme
        AppThemeMode.SUNSET -> SunsetColorScheme
        AppThemeMode.OCEAN -> OceanColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
