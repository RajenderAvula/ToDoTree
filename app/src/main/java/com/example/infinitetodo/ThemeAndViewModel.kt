package com.example.infinitetodo

import android.content.Context
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object ThemePreferences {
    private const val PREFS_NAME = "infinite_todo_prefs"
    private const val KEY_THEME = "selected_theme_mode"
    private const val KEY_VIEW_MODE = "selected_view_mode"

    fun saveTheme(context: Context, mode: AppThemeMode) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_THEME, mode.name).apply()
    }

    fun getTheme(context: Context): AppThemeMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_THEME, AppThemeMode.SYSTEM.name) ?: AppThemeMode.SYSTEM.name
        return try {
            AppThemeMode.valueOf(name)
        } catch (_: Exception) {
            AppThemeMode.SYSTEM
        }
    }

    fun saveViewMode(context: Context, mode: TaskViewMode) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_VIEW_MODE, mode.name).apply()
    }

    fun getViewMode(context: Context): TaskViewMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_VIEW_MODE, TaskViewMode.DETAILED.name) ?: TaskViewMode.DETAILED.name
        return try {
            TaskViewMode.valueOf(name)
        } catch (_: Exception) {
            TaskViewMode.DETAILED
        }
    }
}

private val EmeraldLightColors = lightColorScheme(
    primary = Color(0xFF006C4C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF89F8C7),
    onPrimaryContainer = Color(0xFF002114),
    secondary = Color(0xFF4D6356),
    background = Color(0xFFFBFDF9),
    surface = Color(0xFFFBFDF9)
)

private val SunsetLightColors = lightColorScheme(
    primary = Color(0xFFB3271E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD5),
    onPrimaryContainer = Color(0xFF410002),
    secondary = Color(0xFF775652),
    background = Color(0xFFFFFBFA),
    surface = Color(0xFFFFFBFA)
)

private val OceanLightColors = lightColorScheme(
    primary = Color(0xFF006495),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCBE6FF),
    onPrimaryContainer = Color(0xFF001E30),
    secondary = Color(0xFF50606E),
    background = Color(0xFFFCFCFF),
    surface = Color(0xFFFCFCFF)
)

@Composable
fun InfiniteTodoTheme(
    themeMode: AppThemeMode,
    isDarkSystem: Boolean,
    content: @Composable () -> Unit
) {
    val colorScheme = when (themeMode) {
        AppThemeMode.LIGHT -> lightColorScheme()
        AppThemeMode.DARK -> darkColorScheme()
        AppThemeMode.SYSTEM -> if (isDarkSystem) darkColorScheme() else lightColorScheme()
        AppThemeMode.EMERALD -> EmeraldLightColors
        AppThemeMode.SUNSET -> SunsetLightColors
        AppThemeMode.OCEAN -> OceanLightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
