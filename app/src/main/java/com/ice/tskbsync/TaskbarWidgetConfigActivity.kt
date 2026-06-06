package com.ice.tskbsync

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.ice.tskbsync.ui.theme.TskbSyncTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class TaskbarWidgetConfigActivity : ComponentActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED)

        val settingsManager = SettingsManager(this)
        setContent {
            TskbSyncTheme {
                WidgetConfigScreen(
                    settingsManager = settingsManager,
                    onCancel = { finish() },
                    onSave = { theme ->
                        lifecycleScope.launch {
                            settingsManager.saveTheme(theme)
                            TaskbarWidgetProvider.updateAll(this@TaskbarWidgetConfigActivity)
                            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                                setResult(
                                    RESULT_OK,
                                    Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                                )
                            }
                            finish()
                        }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WidgetConfigScreen(
    settingsManager: SettingsManager,
    onCancel: () -> Unit,
    onSave: (ThemeSettings) -> Unit
) {
    var loadedTheme by remember { mutableStateOf<ThemeSettings?>(null) }
    var showTitles by remember { mutableStateOf(true) }
    var backgroundColor by remember { mutableIntStateOf(0xFF202124.toInt()) }
    var backgroundAlpha by remember { mutableFloatStateOf(0.87f) }
    var itemSize by remember { mutableIntStateOf(72) }
    var transparentOnError by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val theme = settingsManager.themeSettings.first()
        loadedTheme = theme
        showTitles = theme.showWidgetTitles
        backgroundColor = theme.widgetBackgroundColor
        backgroundAlpha = theme.widgetBackgroundAlpha
        itemSize = theme.widgetItemSizeDp
        transparentOnError = theme.widgetTransparentOnError
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Widget Settings") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            SettingSwitchLine(
                title = "Show Window Names",
                checked = showTitles,
                onCheckedChange = { showTitles = it }
            )
            Text("Background Color", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(
                    0xFF202124.toInt(),
                    0xFF111827.toInt(),
                    0xFF2E2B5F.toInt(),
                    0xFF0F766E.toInt(),
                    0xFF7C2D12.toInt(),
                    0xFF000000.toInt()
                ).forEach { color ->
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Color(color))
                            .clickable { backgroundColor = color }
                    )
                }
            }

            Column {
                Text("Transparency: ${(backgroundAlpha * 100).roundToInt()}%")
                Slider(
                    value = backgroundAlpha,
                    onValueChange = { backgroundAlpha = it },
                    valueRange = 0.15f..1f
                )
            }

            Text("Size", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(56 to "Compact", 72 to "Normal", 88 to "Large").forEach { (size, label) ->
                    FilterChip(
                        selected = itemSize == size,
                        onClick = { itemSize = size },
                        label = { Text(label) }
                    )
                }
            }

            SettingSwitchLine(
                title = "Hide When Connection Fails",
                checked = transparentOnError,
                onCheckedChange = { transparentOnError = it }
            )

            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        val base = loadedTheme ?: return@Button
                        onSave(
                            base.copy(
                                showWidgetTitles = showTitles,
                                widgetBackgroundColor = backgroundColor,
                                widgetBackgroundAlpha = backgroundAlpha,
                                widgetItemSizeDp = itemSize,
                                widgetTransparentOnError = transparentOnError
                            )
                        )
                    },
                    modifier = Modifier.weight(1f),
                    enabled = loadedTheme != null
                ) {
                    Text("Save")
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun SettingSwitchLine(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
