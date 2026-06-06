package com.ice.tskbsync

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

class TaskbarWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        TaskbarWidgetActionReceiver.refreshWidgets(context, appWidgetIds, showLoading = true)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        TaskbarWidgetActionReceiver.resetPage(context, appWidgetId)
        TaskbarWidgetActionReceiver.refreshWidgets(context, intArrayOf(appWidgetId), showLoading = false)
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TaskbarWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                TaskbarWidgetActionReceiver.refreshWidgets(context, ids, showLoading = false)
            }
        }
    }
}

class TaskbarWidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_SWITCH -> {
                        val hwnd = intent.getLongExtra(EXTRA_HWND, 0L)
                        if (hwnd != 0L) switchWindow(context, hwnd)
                        renderWidgets(context, allWidgetIds(context), showLoading = false)
                    }
                    ACTION_PREV, ACTION_NEXT -> {
                        val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                            shiftPage(context, widgetId, if (intent.action == ACTION_NEXT) 1 else -1)
                        }
                        renderWidgets(context, allWidgetIds(context), showLoading = false)
                    }
                    ACTION_REFRESH -> renderWidgets(
                        context,
                        intent.getIntArrayExtra(EXTRA_WIDGET_IDS) ?: allWidgetIds(context),
                        showLoading = true
                    )
                    else -> renderWidgets(context, allWidgetIds(context), showLoading = false)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val ACTION_REFRESH = "com.ice.tskbsync.widget.REFRESH"
        private const val ACTION_SWITCH = "com.ice.tskbsync.widget.SWITCH"
        private const val ACTION_PREV = "com.ice.tskbsync.widget.PREV"
        private const val ACTION_NEXT = "com.ice.tskbsync.widget.NEXT"
        private const val EXTRA_HWND = "hwnd"
        private const val EXTRA_WIDGET_ID = "widget_id"
        private const val EXTRA_WIDGET_IDS = "widget_ids"
        private const val PREFS = "taskbar_widget"
        private const val PAGE_PREFIX = "page_"
        private val json = Json { ignoreUnknownKeys = true }

        fun refreshWidgets(context: Context, appWidgetIds: IntArray, showLoading: Boolean) {
            val intent = Intent(context, TaskbarWidgetActionReceiver::class.java).apply {
                action = ACTION_REFRESH
                putExtra(EXTRA_WIDGET_IDS, appWidgetIds)
            }
            context.sendBroadcast(intent)
            if (showLoading) {
                val manager = AppWidgetManager.getInstance(context)
                appWidgetIds.forEach { id ->
                    manager.updateAppWidget(
                        id,
                        baseViews(
                            context,
                            "Refreshing...",
                            showWindows = false,
                            options = manager.getAppWidgetOptions(id)
                        )
                    )
                }
            }
        }

        private suspend fun renderWidgets(context: Context, appWidgetIds: IntArray, showLoading: Boolean) {
            val manager = AppWidgetManager.getInstance(context)
            if (showLoading) {
                appWidgetIds.forEach { id ->
                    manager.updateAppWidget(
                        id,
                        baseViews(
                            context,
                            "Refreshing...",
                            showWindows = false,
                            options = manager.getAppWidgetOptions(id)
                        )
                    )
                }
            }

            val settings = SettingsManager(context)
            val ip = settings.pcIp.first()
            val password = settings.password.first()
            val theme = settings.themeSettings.first()

            if (ip.isBlank()) {
                appWidgetIds.forEach { id ->
                    manager.updateAppWidget(
                        id,
                        baseViews(
                            context,
                            "Configure PC IP in TskbSync",
                            showWindows = false,
                            theme = theme,
                            failed = true,
                            options = manager.getAppWidgetOptions(id)
                        )
                    )
                }
                return
            }

            val result = runCatching { fetchWindows(ip, password) }
            val windows = result.getOrNull()
            val error = result.exceptionOrNull()?.message ?: "Connection failed"

            appWidgetIds.forEach { id ->
                val views = if (windows == null) {
                    baseViews(
                        context,
                        error,
                        showWindows = false,
                        theme = theme,
                        failed = true,
                        options = manager.getAppWidgetOptions(id)
                    )
                } else if (windows.isEmpty()) {
                    baseViews(
                        context,
                        "No windows",
                        showWindows = false,
                        theme = theme,
                        options = manager.getAppWidgetOptions(id)
                    )
                } else {
                    buildWindowViews(context, manager, id, windows, theme)
                }
                manager.updateAppWidget(id, views)
            }
        }

        private fun buildWindowViews(
            context: Context,
            manager: AppWidgetManager,
            appWidgetId: Int,
            windows: List<WindowInfo>,
            theme: ThemeSettings
        ): RemoteViews {
            val options = manager.getAppWidgetOptions(appWidgetId)
            val views = baseViews(context, "", showWindows = true, theme = theme, options = options)
            views.removeAllViews(R.id.widgetWindowContainer)
            val itemSize = theme.widgetItemSizeDp.coerceIn(44, 88)
            val maxItems = maxItemsForWidget(options, itemSize, theme.showWidgetTitles)
            Log.i(
                "TaskbarWidget",
                "widget=$appWidgetId minW=${options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, -1)} " +
                    "maxW=${options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, -1)} " +
                    "itemSize=$itemSize showTitles=${theme.showWidgetTitles} maxItems=$maxItems windows=${windows.size}"
            )
            val pageCount = ((windows.size + maxItems - 1) / maxItems).coerceAtLeast(1)
            val savedPage = getPage(context, appWidgetId)
            val page = savedPage.coerceIn(0, pageCount - 1)
            if (page != savedPage) setPage(context, appWidgetId, page)
            val pageWindows = windows.drop(page * maxItems).take(maxItems)
            pageWindows.forEach { window ->
                val item = RemoteViews(context.packageName, itemLayoutForSize(itemSize, theme.showWidgetTitles))
                item.setTextViewText(R.id.widgetWindowTitle, window.title)
                item.setViewVisibility(R.id.widgetWindowTitle, if (theme.showWidgetTitles) View.VISIBLE else View.GONE)
                item.setInt(
                    R.id.widgetWindowItem,
                    "setBackgroundResource",
                    if (window.is_active) R.drawable.taskbar_widget_item_active_background else R.drawable.taskbar_widget_item_background
                )
                decodeIcon(window.icon)?.let {
                    item.setImageViewBitmap(R.id.widgetWindowIcon, it)
                } ?: item.setImageViewResource(R.id.widgetWindowIcon, R.mipmap.ic_launcher)
                item.setOnClickPendingIntent(R.id.widgetWindowItem, switchPendingIntent(context, window.hwnd))
                views.addView(R.id.widgetWindowContainer, item)
            }
            val hasPages = pageCount > 1
            val hasPrevious = hasPages && page > 0
            val hasNext = hasPages && page < pageCount - 1
            views.setViewVisibility(R.id.widgetPrev, if (hasPages) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widgetNext, if (hasPages) View.VISIBLE else View.GONE)
            views.setImageViewResource(
                R.id.widgetPrev,
                if (hasPrevious) R.drawable.ic_widget_chevron_left else R.drawable.ic_widget_chevron_left_disabled
            )
            views.setImageViewResource(
                R.id.widgetNext,
                if (hasNext) R.drawable.ic_widget_chevron_right else R.drawable.ic_widget_chevron_right_disabled
            )
            views.setOnClickPendingIntent(R.id.widgetPrev, pagePendingIntent(context, appWidgetId, ACTION_PREV))
            views.setOnClickPendingIntent(R.id.widgetNext, pagePendingIntent(context, appWidgetId, ACTION_NEXT))
            views.setImageViewBitmap(
                R.id.widgetBackground,
                roundedBackgroundBitmap(
                    context,
                    widgetBackgroundColor(theme, failed = false),
                    options
                )
            )
            return views
        }

        private fun baseViews(
            context: Context,
            status: String,
            showWindows: Boolean,
            theme: ThemeSettings? = null,
            failed: Boolean = false,
            options: Bundle = Bundle()
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.taskbar_widget)
            val hideOnFailure = failed && theme?.widgetTransparentOnError == true
            views.setViewVisibility(R.id.widgetShell, if (hideOnFailure) View.GONE else View.VISIBLE)
            views.setViewVisibility(R.id.widgetStatus, if (showWindows) View.GONE else View.VISIBLE)
            views.setViewVisibility(R.id.widgetWindowContainer, if (showWindows) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widgetPrev, View.GONE)
            views.setViewVisibility(R.id.widgetNext, View.GONE)
            views.setTextViewText(R.id.widgetStatus, if (hideOnFailure) "" else status)
            views.setImageViewBitmap(
                R.id.widgetBackground,
                roundedBackgroundBitmap(context, widgetBackgroundColor(theme, failed), options)
            )
            views.setOnClickPendingIntent(R.id.widgetRefresh, refreshPendingIntent(context))
            return views
        }

        private fun allWidgetIds(context: Context): IntArray {
            val manager = AppWidgetManager.getInstance(context)
            return manager.getAppWidgetIds(ComponentName(context, TaskbarWidgetProvider::class.java))
        }

        private fun itemLayoutForSize(itemSize: Int, showTitles: Boolean): Int {
            if (!showTitles) {
                return when {
                    itemSize <= 60 -> R.layout.taskbar_widget_window_item_compact
                    itemSize >= 84 -> R.layout.taskbar_widget_window_item_large_icon
                    else -> R.layout.taskbar_widget_window_item_icon
                }
            }
            return when {
                itemSize <= 60 -> R.layout.taskbar_widget_window_item_compact
                itemSize >= 84 -> R.layout.taskbar_widget_window_item_large
                else -> R.layout.taskbar_widget_window_item
            }
        }

        private fun maxItemsForWidget(options: Bundle, itemSize: Int, showTitles: Boolean): Int {
            val controlsWidth = 98
            val base = (widgetWidthDp(options) - controlsWidth) / itemSlotWidthDp(itemSize, showTitles)
            val widthCompensation = if (showTitles) 1 else 2
            return (base + widthCompensation).coerceIn(1, 12)
        }

        private fun itemSlotWidthDp(itemSize: Int, showTitles: Boolean): Int {
            if (!showTitles) {
                return when {
                    itemSize <= 60 -> 45
                    itemSize >= 84 -> 66
                    else -> 54
                }
            }
            return when {
                itemSize <= 60 -> 45
                itemSize >= 84 -> 98
                else -> 80
            }
        }

        private fun widgetWidthDp(options: Bundle): Int {
            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 260)
            val maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minWidth)
            return maxOf(minWidth, maxWidth, 260)
        }

        private fun widgetBackgroundColor(theme: ThemeSettings?, failed: Boolean): Int {
            val base = theme?.widgetBackgroundColor ?: 0xFF202124.toInt()
            val alpha = when {
                theme == null -> 0.87f
                failed && theme.widgetTransparentOnError -> theme.widgetBackgroundAlpha * 0.28f
                else -> theme.widgetBackgroundAlpha
            }.coerceIn(0f, 1f)
            return Color.argb(
                (alpha * 255).toInt().coerceIn(0, 255),
                Color.red(base),
                Color.green(base),
                Color.blue(base)
            )
        }

        private fun roundedBackgroundBitmap(
            context: Context,
            color: Int,
            options: Bundle,
            preferredWidthDp: Int? = null
        ): Bitmap {
            val density = context.resources.displayMetrics.density
            val widthDp = preferredWidthDp ?: widgetWidthDp(options)
            val heightDp = maxOf(
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 48),
                48
            )
            val width = (widthDp * density).toInt().coerceIn(320, 1400)
            val height = (heightDp * density).toInt().coerceIn(72, 260)
            val radius = (height * 0.28f).coerceAtMost(24f * density)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, paint)
            return bitmap
        }

        private fun refreshPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, TaskbarWidgetActionReceiver::class.java).apply {
                action = ACTION_REFRESH
            }
            return PendingIntent.getBroadcast(
                context,
                101,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun pagePendingIntent(context: Context, appWidgetId: Int, action: String): PendingIntent {
            val intent = Intent(context, TaskbarWidgetActionReceiver::class.java).apply {
                this.action = action
                putExtra(EXTRA_WIDGET_ID, appWidgetId)
            }
            val requestCode = (if (action == ACTION_NEXT) 20_000 else 10_000) + appWidgetId
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun switchPendingIntent(context: Context, hwnd: Long): PendingIntent {
            val intent = Intent(context, TaskbarWidgetActionReceiver::class.java).apply {
                action = ACTION_SWITCH
                putExtra(EXTRA_HWND, hwnd)
            }
            return PendingIntent.getBroadcast(
                context,
                (hwnd xor (hwnd ushr 32)).toInt(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun decodeIcon(icon: String): Bitmap? {
            if (icon.isBlank()) return null
            return try {
                val cleaned = icon.substringAfter(",", icon)
                val bytes = Base64.decode(cleaned, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) {
                null
            }
        }

        private fun fetchWindows(ip: String, password: String): List<WindowInfo> {
            val connection = (URL("http://$ip:8000/windows").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 2500
                readTimeout = 3500
                setRequestProperty("password", password)
            }
            return connection.use {
                val code = it.responseCode
                if (code != 200) throw IllegalStateException("Connection failed ($code)")
                json.decodeFromString(it.inputStream.bufferedReader().use { reader -> reader.readText() })
            }
        }

        private fun switchWindow(context: Context, hwnd: Long) {
            val settings = SettingsManager(context)
            val ip = kotlinx.coroutines.runBlocking { settings.pcIp.first() }
            val password = kotlinx.coroutines.runBlocking { settings.password.first() }
            if (ip.isBlank()) return
            val connection = (URL("http://$ip:8000/switch/$hwnd").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 2500
                readTimeout = 2500
                setRequestProperty("password", password)
                doOutput = true
            }
            connection.use {
                it.outputStream.use { out -> out.write(ByteArray(0)) }
                it.responseCode
            }
        }

        private fun getPage(context: Context, appWidgetId: Int): Int {
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt("$PAGE_PREFIX$appWidgetId", 0)
        }

        private fun setPage(context: Context, appWidgetId: Int, page: Int) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt("$PAGE_PREFIX$appWidgetId", page)
                .apply()
        }

        fun resetPage(context: Context, appWidgetId: Int) {
            setPage(context, appWidgetId, 0)
        }

        private fun shiftPage(context: Context, appWidgetId: Int, delta: Int) {
            setPage(context, appWidgetId, (getPage(context, appWidgetId) + delta).coerceAtLeast(0))
        }

        private inline fun <T : HttpURLConnection, R> T.use(block: (T) -> R): R {
            return try {
                block(this)
            } finally {
                disconnect()
            }
        }
    }
}
