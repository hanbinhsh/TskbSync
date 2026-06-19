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
    val color: Int = 0xFF6200EE.toInt(),
    val bgImagePath: String = "",
    val showWallpaper: Boolean = true,
    val bgAlpha: Float = 0.5f,
    val titleColor: Int = 0xFFFFFFFF.toInt(),
    val containerColor: Int = 0xFFFFFFFF.toInt(),
    val containerAlpha: Float = 0.7f,
    val topBarColor: Int = 0x00000000,
    val topBarAlpha: Float = 0.0f,
    val rowContainerAlpha: Float = 0.85f,
    val showPreviews: Boolean = false,
    val livePreviewEnabled: Boolean = false,
    val streamMaxDim: Int = 720,
    val streamQuality: Int = 72,
    val streamFps: Int = 30,
    val useHardwareEncoding: Boolean = false,
    val useHighPerformanceWindowStreaming: Boolean = false,
    val enableAudio: Boolean = false,
    val audioDelayMs: Int = 0,
    val audioBufferMs: Int = 60,
    val showVirtualDisplayButton: Boolean = true,
    val showWidgetTitles: Boolean = true,
    val widgetBackgroundColor: Int = 0xFF202124.toInt(),
    val widgetBackgroundAlpha: Float = 0.87f,
    val widgetItemSizeDp: Int = 56,
    val widgetTransparentOnError: Boolean = true,
    val gridPreviewIntervalMs: Int = 2000,
    val clipLivePreview: Boolean = false,
    val livePreviewCornerPx: Int = 18,
    val fullscreenSideControlsEnabled: Boolean = true,
    val fullscreenShowModeSwitch: Boolean = true,
    val fullscreenShowWindowControls: Boolean = true,
    val fullscreenShowShortcuts: Boolean = true,
    val fullscreenRememberPanelOpen: Boolean = false,
    val mouseSensitivity: Float = 1.8f
) {
    companion object {
        // Single source of truth for defaults, used by SettingsManager and the ViewModel.
        val DEFAULT = ThemeSettings()
    }
}

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
        private val ENABLE_AUDIO_KEY = booleanPreferencesKey("enable_audio")
        private val AUDIO_DELAY_MS_KEY = intPreferencesKey("audio_delay_ms")
        private val AUDIO_BUFFER_MS_KEY = intPreferencesKey("audio_buffer_ms")
        private val SHOW_VIRTUAL_DISPLAY_BUTTON_KEY = booleanPreferencesKey("show_virtual_display_button")
        private val SHOW_WIDGET_TITLES_KEY = booleanPreferencesKey("show_widget_titles")
        private val WIDGET_BACKGROUND_COLOR_KEY = intPreferencesKey("widget_background_color")
        private val WIDGET_BACKGROUND_ALPHA_KEY = floatPreferencesKey("widget_background_alpha")
        private val WIDGET_ITEM_SIZE_DP_KEY = intPreferencesKey("widget_item_size_dp")
        private val WIDGET_TRANSPARENT_ON_ERROR_KEY = booleanPreferencesKey("widget_transparent_on_error")
        private val GRID_PREVIEW_INTERVAL_MS_KEY = intPreferencesKey("grid_preview_interval_ms")
        private val CLIP_LIVE_PREVIEW_KEY = booleanPreferencesKey("clip_live_preview")
        private val LIVE_PREVIEW_CORNER_PX_KEY = intPreferencesKey("live_preview_corner_px")
        private val FULLSCREEN_SIDE_CONTROLS_ENABLED_KEY = booleanPreferencesKey("fullscreen_side_controls_enabled")
        private val FULLSCREEN_SHOW_MODE_SWITCH_KEY = booleanPreferencesKey("fullscreen_show_mode_switch")
        private val FULLSCREEN_SHOW_WINDOW_CONTROLS_KEY = booleanPreferencesKey("fullscreen_show_window_controls")
        private val FULLSCREEN_SHOW_SHORTCUTS_KEY = booleanPreferencesKey("fullscreen_show_shortcuts")
        private val FULLSCREEN_REMEMBER_PANEL_OPEN_KEY = booleanPreferencesKey("fullscreen_remember_panel_open")
        private val MOUSE_SENSITIVITY_KEY = floatPreferencesKey("mouse_sensitivity")
        private val WINDOW_FILTER_KEY = stringPreferencesKey("window_filter")
        private val SHORTCUTS_KEY = stringPreferencesKey("shortcuts")
        private val USE_ENCRYPTION_KEY = booleanPreferencesKey("use_encryption")
    }

    val pcIp: Flow<String> = context.dataStore.data.map { it[PC_IP_KEY] ?: "" }
    val password: Flow<String> = context.dataStore.data.map { it[PASSWORD_KEY] ?: "" }
    val layoutMode: Flow<String> = context.dataStore.data.map { it[LAYOUT_MODE_KEY] ?: "grid" }
    val gridColumns: Flow<Int> = context.dataStore.data.map { it[GRID_COLUMNS_KEY] ?: 3 }
    val showTitles: Flow<Boolean> = context.dataStore.data.map { it[SHOW_TITLES_KEY] ?: true }
    val useEncryption: Flow<Boolean> = context.dataStore.data.map { it[USE_ENCRYPTION_KEY] ?: false }
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
        val d = ThemeSettings.DEFAULT
        ThemeSettings(
            color = pref[THEME_COLOR_KEY] ?: d.color,
            bgImagePath = pref[BG_IMAGE_PATH_KEY] ?: d.bgImagePath,
            showWallpaper = pref[SHOW_WALLPAPER_KEY] ?: d.showWallpaper,
            bgAlpha = pref[BG_ALPHA_KEY] ?: d.bgAlpha,
            titleColor = pref[TITLE_COLOR_KEY] ?: d.titleColor,
            containerColor = pref[CONTAINER_COLOR_KEY] ?: d.containerColor,
            containerAlpha = pref[CONTAINER_ALPHA_KEY] ?: d.containerAlpha,
            topBarColor = pref[TOP_BAR_COLOR_KEY] ?: d.topBarColor,
            topBarAlpha = pref[TOP_BAR_ALPHA_KEY] ?: d.topBarAlpha,
            rowContainerAlpha = pref[ROW_CONTAINER_ALPHA_KEY] ?: d.rowContainerAlpha,
            showPreviews = pref[SHOW_PREVIEWS_KEY] ?: d.showPreviews,
            livePreviewEnabled = pref[LIVE_PREVIEW_KEY] ?: d.livePreviewEnabled,
            streamMaxDim = pref[STREAM_MAX_DIM_KEY] ?: d.streamMaxDim,
            streamQuality = pref[STREAM_QUALITY_KEY] ?: d.streamQuality,
            streamFps = pref[STREAM_FPS_KEY] ?: d.streamFps,
            useHardwareEncoding = pref[HARDWARE_ENCODING_KEY] ?: d.useHardwareEncoding,
            useHighPerformanceWindowStreaming = pref[HIGH_PERFORMANCE_WINDOW_STREAMING_KEY] ?: d.useHighPerformanceWindowStreaming,
            enableAudio = pref[ENABLE_AUDIO_KEY] ?: d.enableAudio,
            audioDelayMs = pref[AUDIO_DELAY_MS_KEY] ?: d.audioDelayMs,
            audioBufferMs = pref[AUDIO_BUFFER_MS_KEY] ?: d.audioBufferMs,
            showVirtualDisplayButton = pref[SHOW_VIRTUAL_DISPLAY_BUTTON_KEY] ?: d.showVirtualDisplayButton,
            showWidgetTitles = pref[SHOW_WIDGET_TITLES_KEY] ?: d.showWidgetTitles,
            widgetBackgroundColor = pref[WIDGET_BACKGROUND_COLOR_KEY] ?: d.widgetBackgroundColor,
            widgetBackgroundAlpha = pref[WIDGET_BACKGROUND_ALPHA_KEY] ?: d.widgetBackgroundAlpha,
            widgetItemSizeDp = pref[WIDGET_ITEM_SIZE_DP_KEY] ?: d.widgetItemSizeDp,
            widgetTransparentOnError = pref[WIDGET_TRANSPARENT_ON_ERROR_KEY] ?: d.widgetTransparentOnError,
            gridPreviewIntervalMs = pref[GRID_PREVIEW_INTERVAL_MS_KEY] ?: d.gridPreviewIntervalMs,
            clipLivePreview = pref[CLIP_LIVE_PREVIEW_KEY] ?: d.clipLivePreview,
            livePreviewCornerPx = pref[LIVE_PREVIEW_CORNER_PX_KEY] ?: d.livePreviewCornerPx,
            fullscreenSideControlsEnabled = pref[FULLSCREEN_SIDE_CONTROLS_ENABLED_KEY] ?: d.fullscreenSideControlsEnabled,
            fullscreenShowModeSwitch = pref[FULLSCREEN_SHOW_MODE_SWITCH_KEY] ?: d.fullscreenShowModeSwitch,
            fullscreenShowWindowControls = pref[FULLSCREEN_SHOW_WINDOW_CONTROLS_KEY] ?: d.fullscreenShowWindowControls,
            fullscreenShowShortcuts = pref[FULLSCREEN_SHOW_SHORTCUTS_KEY] ?: d.fullscreenShowShortcuts,
            fullscreenRememberPanelOpen = pref[FULLSCREEN_REMEMBER_PANEL_OPEN_KEY] ?: d.fullscreenRememberPanelOpen,
            mouseSensitivity = pref[MOUSE_SENSITIVITY_KEY] ?: d.mouseSensitivity
        )
    }

    suspend fun savePcIp(ip: String) { context.dataStore.edit { it[PC_IP_KEY] = ip } }
    suspend fun savePassword(pwd: String) { context.dataStore.edit { it[PASSWORD_KEY] = pwd } }
    suspend fun saveLayoutMode(mode: String) { context.dataStore.edit { it[LAYOUT_MODE_KEY] = mode } }
    suspend fun saveGridColumns(cols: Int) { context.dataStore.edit { it[GRID_COLUMNS_KEY] = cols } }
    suspend fun saveShowTitles(show: Boolean) { context.dataStore.edit { it[SHOW_TITLES_KEY] = show } }
    suspend fun saveUseEncryption(enabled: Boolean) { context.dataStore.edit { it[USE_ENCRYPTION_KEY] = enabled } }
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
            pref[ENABLE_AUDIO_KEY] = settings.enableAudio
            pref[AUDIO_DELAY_MS_KEY] = settings.audioDelayMs
            pref[AUDIO_BUFFER_MS_KEY] = settings.audioBufferMs
            pref[SHOW_VIRTUAL_DISPLAY_BUTTON_KEY] = settings.showVirtualDisplayButton
            pref[SHOW_WIDGET_TITLES_KEY] = settings.showWidgetTitles
            pref[WIDGET_BACKGROUND_COLOR_KEY] = settings.widgetBackgroundColor
            pref[WIDGET_BACKGROUND_ALPHA_KEY] = settings.widgetBackgroundAlpha
            pref[WIDGET_ITEM_SIZE_DP_KEY] = settings.widgetItemSizeDp
            pref[WIDGET_TRANSPARENT_ON_ERROR_KEY] = settings.widgetTransparentOnError
            pref[GRID_PREVIEW_INTERVAL_MS_KEY] = settings.gridPreviewIntervalMs
            pref[CLIP_LIVE_PREVIEW_KEY] = settings.clipLivePreview
            pref[LIVE_PREVIEW_CORNER_PX_KEY] = settings.livePreviewCornerPx
            pref[FULLSCREEN_SIDE_CONTROLS_ENABLED_KEY] = settings.fullscreenSideControlsEnabled
            pref[FULLSCREEN_SHOW_MODE_SWITCH_KEY] = settings.fullscreenShowModeSwitch
            pref[FULLSCREEN_SHOW_WINDOW_CONTROLS_KEY] = settings.fullscreenShowWindowControls
            pref[FULLSCREEN_SHOW_SHORTCUTS_KEY] = settings.fullscreenShowShortcuts
            pref[FULLSCREEN_REMEMBER_PANEL_OPEN_KEY] = settings.fullscreenRememberPanelOpen
            pref[MOUSE_SENSITIVITY_KEY] = settings.mouseSensitivity
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
