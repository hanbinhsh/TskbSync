package com.ice.tskbsync

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Serializable
data class WindowFilterSettings(
    val enabled: Boolean = true,
    val hideSystemWindows: Boolean = true,
    val titleContains: List<String> = emptyList(),
    val processNames: List<String> = emptyList(),
    val classNames: List<String> = emptyList()
)

data class ThemeSettings(
    val color: Int,
    val bgImagePath: String,
    val showWallpaper: Boolean,
    val bgAlpha: Float,
    val titleColor: Int,
    val containerColor: Int,
    val containerAlpha: Float,
    val topBarColor: Int,
    val topBarAlpha: Float,
    val rowContainerAlpha: Float,
    val showPreviews: Boolean,
    val livePreviewEnabled: Boolean,
    val streamMaxDim: Int,
    val streamQuality: Int,
    val streamFps: Int,
    val useHardwareEncoding: Boolean,
    val useHighPerformanceWindowStreaming: Boolean,
    val showVirtualDisplayButton: Boolean,
    val gridPreviewIntervalMs: Int,
    val clipLivePreview: Boolean,
    val livePreviewCornerPx: Int
)

class SettingsManager(private val context: Context) {
    companion object {
        private val PC_IP_KEY = stringPreferencesKey("pc_ip")
        private val PASSWORD_KEY = stringPreferencesKey("password")
        private val THEME_COLOR_KEY = intPreferencesKey("theme_color")
        private val BG_IMAGE_PATH_KEY = stringPreferencesKey("bg_image_path")
        private val SHOW_WALLPAPER_KEY = booleanPreferencesKey("show_wallpaper")
        private val BG_ALPHA_KEY = floatPreferencesKey("bg_alpha")
        private val LAYOUT_MODE_KEY = stringPreferencesKey("layout_mode")
        private val GRID_COLUMNS_KEY = intPreferencesKey("grid_columns")
        private val SHOW_TITLES_KEY = booleanPreferencesKey("show_titles")
        private val TITLE_COLOR_KEY = intPreferencesKey("title_color")
        private val CONTAINER_COLOR_KEY = intPreferencesKey("container_color")
        private val CONTAINER_ALPHA_KEY = floatPreferencesKey("container_alpha")
        private val TOP_BAR_COLOR_KEY = intPreferencesKey("top_bar_color")
        private val TOP_BAR_ALPHA_KEY = floatPreferencesKey("top_bar_alpha")
        private val ROW_CONTAINER_ALPHA_KEY = floatPreferencesKey("row_container_alpha")
        private val SHOW_PREVIEWS_KEY = booleanPreferencesKey("show_previews")
        private val LIVE_PREVIEW_KEY = booleanPreferencesKey("live_preview")
        private val STREAM_MAX_DIM_KEY = intPreferencesKey("stream_max_dim")
        private val STREAM_QUALITY_KEY = intPreferencesKey("stream_quality")
        private val STREAM_FPS_KEY = intPreferencesKey("stream_fps")
        private val HARDWARE_ENCODING_KEY = booleanPreferencesKey("hardware_encoding")
        private val HIGH_PERFORMANCE_WINDOW_STREAMING_KEY = booleanPreferencesKey("high_performance_window_streaming")
        private val SHOW_VIRTUAL_DISPLAY_BUTTON_KEY = booleanPreferencesKey("show_virtual_display_button")
        private val GRID_PREVIEW_INTERVAL_MS_KEY = intPreferencesKey("grid_preview_interval_ms")
        private val CLIP_LIVE_PREVIEW_KEY = booleanPreferencesKey("clip_live_preview")
        private val LIVE_PREVIEW_CORNER_PX_KEY = intPreferencesKey("live_preview_corner_px")
        private val WINDOW_FILTER_KEY = stringPreferencesKey("window_filter")
        private val SHORTCUTS_KEY = stringPreferencesKey("shortcuts")
    }

    val pcIp: Flow<String> = context.dataStore.data.map { it[PC_IP_KEY] ?: "" }
    val password: Flow<String> = context.dataStore.data.map { it[PASSWORD_KEY] ?: "" }
    val layoutMode: Flow<String> = context.dataStore.data.map { it[LAYOUT_MODE_KEY] ?: "grid" }
    val gridColumns: Flow<Int> = context.dataStore.data.map { it[GRID_COLUMNS_KEY] ?: 3 }
    val showTitles: Flow<Boolean> = context.dataStore.data.map { it[SHOW_TITLES_KEY] ?: true }
    val shortcuts: Flow<List<ShortcutConfig>> = context.dataStore.data.map { pref ->
        val raw = pref[SHORTCUTS_KEY]
        if (raw.isNullOrEmpty()) {
            defaultShortcutConfigs
        } else {
            try {
                Json.decodeFromString<List<ShortcutConfig>>(raw)
            } catch (e: Exception) {
                defaultShortcutConfigs
            }
        }
    }
    val windowFilter: Flow<WindowFilterSettings> = context.dataStore.data.map { pref ->
        val raw = pref[WINDOW_FILTER_KEY]
        if (raw.isNullOrEmpty()) {
            WindowFilterSettings()
        } else {
            try {
                Json.decodeFromString<WindowFilterSettings>(raw)
            } catch (e: Exception) {
                WindowFilterSettings()
            }
        }
    }
    
    val themeSettings: Flow<ThemeSettings> = context.dataStore.data.map { pref ->
        ThemeSettings(
            color = pref[THEME_COLOR_KEY] ?: 0xFF6200EE.toInt(),
            bgImagePath = pref[BG_IMAGE_PATH_KEY] ?: "",
            showWallpaper = pref[SHOW_WALLPAPER_KEY] ?: true,
            bgAlpha = pref[BG_ALPHA_KEY] ?: 0.5f,
            titleColor = pref[TITLE_COLOR_KEY] ?: 0xFFFFFFFF.toInt(),
            containerColor = pref[CONTAINER_COLOR_KEY] ?: 0xFFFFFFFF.toInt(),
            containerAlpha = pref[CONTAINER_ALPHA_KEY] ?: 0.7f,
            topBarColor = pref[TOP_BAR_COLOR_KEY] ?: 0x00000000,
            topBarAlpha = pref[TOP_BAR_ALPHA_KEY] ?: 0.0f,
            rowContainerAlpha = pref[ROW_CONTAINER_ALPHA_KEY] ?: 0.85f,
            showPreviews = pref[SHOW_PREVIEWS_KEY] ?: false,
            livePreviewEnabled = pref[LIVE_PREVIEW_KEY] ?: false,
            streamMaxDim = pref[STREAM_MAX_DIM_KEY] ?: 720,
            streamQuality = pref[STREAM_QUALITY_KEY] ?: 72,
            streamFps = pref[STREAM_FPS_KEY] ?: 30,
            useHardwareEncoding = pref[HARDWARE_ENCODING_KEY] ?: false,
            useHighPerformanceWindowStreaming = pref[HIGH_PERFORMANCE_WINDOW_STREAMING_KEY] ?: false,
            showVirtualDisplayButton = pref[SHOW_VIRTUAL_DISPLAY_BUTTON_KEY] ?: true,
            gridPreviewIntervalMs = pref[GRID_PREVIEW_INTERVAL_MS_KEY] ?: 2000,
            clipLivePreview = pref[CLIP_LIVE_PREVIEW_KEY] ?: false,
            livePreviewCornerPx = pref[LIVE_PREVIEW_CORNER_PX_KEY] ?: 18
        )
    }

    suspend fun savePcIp(ip: String) { context.dataStore.edit { it[PC_IP_KEY] = ip } }
    suspend fun savePassword(pwd: String) { context.dataStore.edit { it[PASSWORD_KEY] = pwd } }
    suspend fun saveLayoutMode(mode: String) { context.dataStore.edit { it[LAYOUT_MODE_KEY] = mode } }
    suspend fun saveGridColumns(cols: Int) { context.dataStore.edit { it[GRID_COLUMNS_KEY] = cols } }
    suspend fun saveShowTitles(show: Boolean) { context.dataStore.edit { it[SHOW_TITLES_KEY] = show } }
    suspend fun saveShortcuts(shortcuts: List<ShortcutConfig>) {
        context.dataStore.edit { it[SHORTCUTS_KEY] = Json.encodeToString(shortcuts) }
    }

    suspend fun saveWindowFilter(settings: WindowFilterSettings) {
        context.dataStore.edit { it[WINDOW_FILTER_KEY] = Json.encodeToString(settings) }
    }

    suspend fun saveTheme(settings: ThemeSettings) {
        context.dataStore.edit { pref ->
            pref[THEME_COLOR_KEY] = settings.color
            pref[BG_IMAGE_PATH_KEY] = settings.bgImagePath
            pref[SHOW_WALLPAPER_KEY] = settings.showWallpaper
            pref[BG_ALPHA_KEY] = settings.bgAlpha
            pref[TITLE_COLOR_KEY] = settings.titleColor
            pref[CONTAINER_COLOR_KEY] = settings.containerColor
            pref[CONTAINER_ALPHA_KEY] = settings.containerAlpha
            pref[TOP_BAR_COLOR_KEY] = settings.topBarColor
            pref[TOP_BAR_ALPHA_KEY] = settings.topBarAlpha
            pref[ROW_CONTAINER_ALPHA_KEY] = settings.rowContainerAlpha
            pref[SHOW_PREVIEWS_KEY] = settings.showPreviews
            pref[LIVE_PREVIEW_KEY] = settings.livePreviewEnabled
            pref[STREAM_MAX_DIM_KEY] = settings.streamMaxDim
            pref[STREAM_QUALITY_KEY] = settings.streamQuality
            pref[STREAM_FPS_KEY] = settings.streamFps
            pref[HARDWARE_ENCODING_KEY] = settings.useHardwareEncoding
            pref[HIGH_PERFORMANCE_WINDOW_STREAMING_KEY] = settings.useHighPerformanceWindowStreaming
            pref[SHOW_VIRTUAL_DISPLAY_BUTTON_KEY] = settings.showVirtualDisplayButton
            pref[GRID_PREVIEW_INTERVAL_MS_KEY] = settings.gridPreviewIntervalMs
            pref[CLIP_LIVE_PREVIEW_KEY] = settings.clipLivePreview
            pref[LIVE_PREVIEW_CORNER_PX_KEY] = settings.livePreviewCornerPx
        }
    }

    fun copyImageToInternal(uri: Uri): String {
        return try {
            val fileName = "wallpaper_${System.currentTimeMillis()}.png"
            val file = File(context.filesDir, fileName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            file.absolutePath
        } catch (e: Exception) { "" }
    }
}
