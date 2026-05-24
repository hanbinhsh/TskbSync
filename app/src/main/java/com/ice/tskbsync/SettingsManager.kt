package com.ice.tskbsync

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.FileOutputStream

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class ThemeSettings(
    val color: Int,
    val bgImagePath: String,
    val bgAlpha: Float,
    val titleColor: Int,
    val containerColor: Int,
    val containerAlpha: Float,
    val topBarColor: Int,
    val topBarAlpha: Float,
    val rowContainerAlpha: Float,
    val showPreviews: Boolean,
    val useWgc: Boolean,
    val livePreviewEnabled: Boolean,
    val streamMaxDim: Int,
    val streamQuality: Int,
    val streamFps: Int
)

class SettingsManager(private val context: Context) {
    companion object {
        private val PC_IP_KEY = stringPreferencesKey("pc_ip")
        private val PASSWORD_KEY = stringPreferencesKey("password")
        private val THEME_COLOR_KEY = intPreferencesKey("theme_color")
        private val BG_IMAGE_PATH_KEY = stringPreferencesKey("bg_image_path")
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
        private val USE_WGC_KEY = booleanPreferencesKey("use_wgc")
        private val LIVE_PREVIEW_KEY = booleanPreferencesKey("live_preview")
        private val STREAM_MAX_DIM_KEY = intPreferencesKey("stream_max_dim")
        private val STREAM_QUALITY_KEY = intPreferencesKey("stream_quality")
        private val STREAM_FPS_KEY = intPreferencesKey("stream_fps")
    }

    val pcIp: Flow<String> = context.dataStore.data.map { it[PC_IP_KEY] ?: "" }
    val password: Flow<String> = context.dataStore.data.map { it[PASSWORD_KEY] ?: "" }
    val layoutMode: Flow<String> = context.dataStore.data.map { it[LAYOUT_MODE_KEY] ?: "grid" }
    val gridColumns: Flow<Int> = context.dataStore.data.map { it[GRID_COLUMNS_KEY] ?: 3 }
    val showTitles: Flow<Boolean> = context.dataStore.data.map { it[SHOW_TITLES_KEY] ?: true }
    
    val themeSettings: Flow<ThemeSettings> = context.dataStore.data.map { pref ->
        ThemeSettings(
            color = pref[THEME_COLOR_KEY] ?: 0xFF6200EE.toInt(),
            bgImagePath = pref[BG_IMAGE_PATH_KEY] ?: "",
            bgAlpha = pref[BG_ALPHA_KEY] ?: 0.5f,
            titleColor = pref[TITLE_COLOR_KEY] ?: 0xFFFFFFFF.toInt(),
            containerColor = pref[CONTAINER_COLOR_KEY] ?: 0xFFFFFFFF.toInt(),
            containerAlpha = pref[CONTAINER_ALPHA_KEY] ?: 0.7f,
            topBarColor = pref[TOP_BAR_COLOR_KEY] ?: 0x00000000,
            topBarAlpha = pref[TOP_BAR_ALPHA_KEY] ?: 0.0f,
            rowContainerAlpha = pref[ROW_CONTAINER_ALPHA_KEY] ?: 0.85f,
            showPreviews = pref[SHOW_PREVIEWS_KEY] ?: false,
            useWgc = pref[USE_WGC_KEY] ?: false,
            livePreviewEnabled = pref[LIVE_PREVIEW_KEY] ?: false,
            streamMaxDim = pref[STREAM_MAX_DIM_KEY] ?: 720,
            streamQuality = pref[STREAM_QUALITY_KEY] ?: 72,
            streamFps = pref[STREAM_FPS_KEY] ?: 30
        )
    }

    suspend fun savePcIp(ip: String) { context.dataStore.edit { it[PC_IP_KEY] = ip } }
    suspend fun savePassword(pwd: String) { context.dataStore.edit { it[PASSWORD_KEY] = pwd } }
    suspend fun saveLayoutMode(mode: String) { context.dataStore.edit { it[LAYOUT_MODE_KEY] = mode } }
    suspend fun saveGridColumns(cols: Int) { context.dataStore.edit { it[GRID_COLUMNS_KEY] = cols } }
    suspend fun saveShowTitles(show: Boolean) { context.dataStore.edit { it[SHOW_TITLES_KEY] = show } }

    suspend fun saveTheme(settings: ThemeSettings) {
        context.dataStore.edit { pref ->
            pref[THEME_COLOR_KEY] = settings.color
            pref[BG_IMAGE_PATH_KEY] = settings.bgImagePath
            pref[BG_ALPHA_KEY] = settings.bgAlpha
            pref[TITLE_COLOR_KEY] = settings.titleColor
            pref[CONTAINER_COLOR_KEY] = settings.containerColor
            pref[CONTAINER_ALPHA_KEY] = settings.containerAlpha
            pref[TOP_BAR_COLOR_KEY] = settings.topBarColor
            pref[TOP_BAR_ALPHA_KEY] = settings.topBarAlpha
            pref[ROW_CONTAINER_ALPHA_KEY] = settings.rowContainerAlpha
            pref[SHOW_PREVIEWS_KEY] = settings.showPreviews
            pref[USE_WGC_KEY] = settings.useWgc
            pref[LIVE_PREVIEW_KEY] = settings.livePreviewEnabled
            pref[STREAM_MAX_DIM_KEY] = settings.streamMaxDim
            pref[STREAM_QUALITY_KEY] = settings.streamQuality
            pref[STREAM_FPS_KEY] = settings.streamFps
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
