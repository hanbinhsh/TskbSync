package com.ice.tskbsync

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(viewModel: TaskbarViewModel, navController: NavController) {
    val pcIp by viewModel.pcIp
    val password by viewModel.password
    val theme by viewModel.theme
    val useEncryption by viewModel.useEncryption
    val discovered = viewModel.discoveredServers
    val gridCols by viewModel.gridColumns
    val showTitles by viewModel.showTitles
    val shortcuts by viewModel.shortcuts
    val startMenuApps by viewModel.startMenuApps
    val startMenuAppsLoading by viewModel.startMenuAppsLoading
    val windowFilter by viewModel.windowFilter
    val h264Status by viewModel.h264Status
    val audioStatus by viewModel.audioStatus
    val extendedDisplayStatus by viewModel.extendedDisplayStatus
    val extendedDisplayDriverChanging by viewModel.extendedDisplayDriverChanging
    
    var tempIp by remember { mutableStateOf(pcIp) }
    var tempPass by remember { mutableStateOf(password) }
    var tempBgAlpha by remember { mutableStateOf(theme.bgAlpha) }
    var tempContainerAlpha by remember { mutableStateOf(theme.containerAlpha) }
    var tempTopBarAlpha by remember { mutableStateOf(theme.topBarAlpha) }
    var tempRowAlpha by remember { mutableStateOf(theme.rowContainerAlpha) }
    var titleFilterText by remember(windowFilter.titleContains) { mutableStateOf(windowFilter.titleContains.joinToString("\n")) }
    var processFilterText by remember(windowFilter.processNames) { mutableStateOf(windowFilter.processNames.joinToString("\n")) }
    var classFilterText by remember(windowFilter.classNames) { mutableStateOf(windowFilter.classNames.joinToString("\n")) }
    
    var colorPickerMode by remember { mutableStateOf("accent") } 
    var showColorDialog by remember { mutableStateOf(false) }
    var editingShortcutIndex by remember { mutableStateOf<Int?>(null) }
    
    val themeColor = Color(theme.color)
    val titleColor = Color(theme.titleColor)
    val context = LocalContext.current

    LaunchedEffect(pcIp) {
        if (pcIp.isNotEmpty()) {
            viewModel.fetchH264Status()
            viewModel.fetchExtendedDisplayStatus()
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { viewModel.saveWallpaper(it) }
    }

    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            primary = themeColor,
            surface = lerp(MaterialTheme.colorScheme.surface, themeColor, 0.05f)
        )
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Settings", color = themeColor) },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = themeColor)
                        }
                    }
                )
            }
        ) { padding ->
            var selectedTab by remember { mutableIntStateOf(0) }
            val tabs = listOf(
                "Connection",
                "Layout",
                "Control",
                "Streaming",
                "Fullscreen",
                "Audio",
                "Virtual Display",
                "Appearance",
                "Shortcuts",
                "Advanced"
            )

            val settingsContent: @Composable (Modifier) -> Unit = { contentModifier ->
                LazyColumn(
                    modifier = contentModifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    when (selectedTab) {
                        0 -> item {
                            SectionTitle("Connection", themeColor)
                            SettingsSection("Server") {
                                    var expanded by remember { mutableStateOf(false) }
                                    Box {
                                        OutlinedTextField(
                                            value = tempIp,
                                            onValueChange = { tempIp = it },
                                            label = { Text("Server IP") },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true,
                                            trailingIcon = {
                                                IconButton(onClick = {
                                                    expanded = true
                                                    viewModel.discoverServers()
                                                }) { Icon(Icons.Default.ArrowDropDown, null) }
                                            }
                                        )
                                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                            if (discovered.isEmpty()) {
                                                DropdownMenuItem(text = { Text("Scanning...") }, onClick = {})
                                            } else {
                                                discovered.forEach { server ->
                                                    DropdownMenuItem(
                                                        text = {
                                                            if (server.name == server.ip) Text(server.ip)
                                                            else Text("${server.name}  (${server.ip})")
                                                        },
                                                        onClick = { tempIp = server.ip; expanded = false }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = tempPass,
                                        onValueChange = { tempPass = it },
                                        label = { Text("Password") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        leadingIcon = { Icon(Icons.Default.Lock, null) }
                                    )
                                    Spacer(Modifier.height(16.dp))
                                    Button(
                                        onClick = {
                                            viewModel.updateSettings(tempIp, tempPass)
                                            viewModel.connect(tempIp)
                                            navController.popBackStack()
                                        },
                                        modifier = Modifier.fillMaxWidth().height(56.dp),
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        Text("Apply and Connect", fontWeight = FontWeight.Bold)
                                    }
                            }
                            SettingsSection("Security") {
                                SettingsSwitchRow(
                                    title = "Encrypt Connection (TLS)",
                                    subtitle = "Must match the tray's encryption setting on the PC. " +
                                        "Accepts the backend's self-signed certificate.",
                                    checked = useEncryption,
                                    onCheckedChange = { viewModel.updateUseEncryption(it) }
                                )
                            }
                        }

                        1 -> item {
                            SectionTitle("Layout", themeColor)
                            SettingsSection("Grid") {
                                SettingsSliderRow("Grid Columns", gridCols, "", 2f..12f, 9) {
                                    viewModel.updateDisplaySettings(it, showTitles)
                                }
                                SettingsSwitchRow(
                                    title = "Show Window Titles",
                                    checked = showTitles,
                                    onCheckedChange = { viewModel.updateDisplaySettings(gridCols, it) }
                                )
                                SettingsSwitchRow(
                                    title = "Show Window Previews",
                                    checked = theme.showPreviews,
                                    onCheckedChange = { viewModel.updateTheme(theme.copy(showPreviews = it)) }
                                )
                                SettingsSwitchRow(
                                    title = "Show Widget Titles",
                                    subtitle = "Only affects the Android home screen widget",
                                    checked = theme.showWidgetTitles,
                                    onCheckedChange = { viewModel.updateTheme(theme.copy(showWidgetTitles = it)) }
                                )
                                Button(
                                    onClick = { context.startActivity(Intent(context, TaskbarWidgetConfigActivity::class.java)) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Open Widget Settings")
                                }
                                SettingsSliderRow("Grid Refresh", theme.gridPreviewIntervalMs, " ms", 500f..3000f, 24) {
                                    viewModel.updateTheme(theme.copy(gridPreviewIntervalMs = it))
                                }
                            }
                        }

                        2 -> item {
                            SectionTitle("Control", themeColor)
                            SettingsSection(
                                "Mouse",
                                subtitle = "Touchpad pointer speed in Mouse mode"
                            ) {
                                SettingsFloatSliderRow(
                                    "Mouse Sensitivity",
                                    theme.mouseSensitivity,
                                    "x",
                                    0.1f..4.0f,
                                    38
                                ) {
                                    viewModel.updateTheme(theme.copy(mouseSensitivity = it))
                                }
                            }
                        }

                        3 -> item {
                            SectionTitle("Streaming", themeColor)
                            SettingsSection("Video") {
                                SettingsSliderRow("Stream Resolution", theme.streamMaxDim, "p", 360f..3840f, 28) {
                                    viewModel.updateTheme(theme.copy(streamMaxDim = it))
                                }
                                SettingsSliderRow("Stream Quality", theme.streamQuality, "%", 35f..100f, 12) {
                                    viewModel.updateTheme(theme.copy(streamQuality = it))
                                }
                                SettingsSliderRow("Stream FPS", theme.streamFps, " fps", 5f..160f, 30) {
                                    viewModel.updateTheme(theme.copy(streamFps = it))
                                }
                            }
                            SettingsSection("Preview") {
                                SettingsSwitchRow(
                                    title = "Clip Large Preview Corners",
                                    checked = theme.clipLivePreview,
                                    onCheckedChange = { viewModel.updateTheme(theme.copy(clipLivePreview = it)) }
                                )
                                SettingsSliderRow("Large Preview Corner", theme.livePreviewCornerPx, " px", 0f..64f, 15) {
                                    viewModel.updateTheme(theme.copy(livePreviewCornerPx = it))
                                }
                            }
                            SettingsSection("Performance") {
                                SettingsSwitchRow(
                                    title = "Use Hardware Encoding for Screen Streaming",
                                    checked = theme.useHardwareEncoding,
                                    onCheckedChange = { viewModel.updateTheme(theme.copy(useHardwareEncoding = it)) }
                                )
                                SettingsSwitchRow(
                                    title = "High Performance Window Streaming",
                                    checked = theme.useHighPerformanceWindowStreaming,
                                    onCheckedChange = { viewModel.updateTheme(theme.copy(useHighPerformanceWindowStreaming = it)) }
                                )
                            }
                        }

                        4 -> item {
                            SectionTitle("Fullscreen", themeColor)
                            SettingsSection("Side Controls") {
                                SettingsSwitchRow(
                                    title = "Enable Fullscreen Side Controls",
                                    subtitle = "Show a small toggle in fullscreen black bars",
                                    checked = theme.fullscreenSideControlsEnabled,
                                    onCheckedChange = { viewModel.updateTheme(theme.copy(fullscreenSideControlsEnabled = it)) }
                                )
                                SettingsSwitchRow(
                                    title = "Show Mode Switch",
                                    subtitle = "Touch / Mouse controls in the fullscreen side panel",
                                    checked = theme.fullscreenShowModeSwitch,
                                    onCheckedChange = { viewModel.updateTheme(theme.copy(fullscreenShowModeSwitch = it)) }
                                )
                                SettingsSwitchRow(
                                    title = "Show Window Controls",
                                    subtitle = "Window, screen, and mouse quick controls in fullscreen",
                                    checked = theme.fullscreenShowWindowControls,
                                    onCheckedChange = { viewModel.updateTheme(theme.copy(fullscreenShowWindowControls = it)) }
                                )
                                SettingsSwitchRow(
                                    title = "Show Shortcuts",
                                    subtitle = "Custom shortcut buttons in fullscreen",
                                    checked = theme.fullscreenShowShortcuts,
                                    onCheckedChange = { viewModel.updateTheme(theme.copy(fullscreenShowShortcuts = it)) }
                                )
                                SettingsSwitchRow(
                                    title = "Remember Panel Open State",
                                    subtitle = "Use the last fullscreen side panel state next time",
                                    checked = theme.fullscreenRememberPanelOpen,
                                    onCheckedChange = { viewModel.updateTheme(theme.copy(fullscreenRememberPanelOpen = it)) }
                                )
                            }
                        }

                        5 -> item {
                            SectionTitle("Audio", themeColor)
                            SettingsSection("Playback") {
                                SettingsSwitchRow(
                                    title = "Enable Audio",
                                    subtitle = audioStatus ?: "PC system audio plays only during live preview/control",
                                    checked = theme.enableAudio,
                                    onCheckedChange = { viewModel.updateTheme(theme.copy(enableAudio = it)) }
                                )
                                SettingsSliderRow("Audio Delay", theme.audioDelayMs, " ms", -300f..500f, 15) {
                                    viewModel.updateTheme(theme.copy(audioDelayMs = it))
                                }
                                SettingsSliderRow("Audio Buffer", theme.audioBufferMs, " ms", 20f..240f, 21) {
                                    viewModel.updateTheme(theme.copy(audioBufferMs = it))
                                }
                            }
                        }

                        6 -> item {
                            SectionTitle("Virtual Display", themeColor)
                            SettingsSection("Entry") {
                                SettingsSwitchRow(
                                    title = "Show Virtual Display Button",
                                    checked = theme.showVirtualDisplayButton,
                                    onCheckedChange = { viewModel.updateTheme(theme.copy(showVirtualDisplayButton = it)) }
                                )
                            }
                            SettingsSection("Driver") {
                                val statusText = when {
                                    extendedDisplayStatus == null -> "Status not loaded"
                                    extendedDisplayStatus?.requires_admin == true -> "Run PC backend as administrator"
                                    extendedDisplayDriverChanging -> "Updating..."
                                    extendedDisplayStatus?.driver_enabled == false && extendedDisplayStatus?.available == false -> "Disabled"
                                    extendedDisplayStatus?.driver_control_available != true -> "No compatible VDD found"
                                    else -> extendedDisplayStatus?.driver_status?.ifBlank { extendedDisplayStatus?.message } ?: ""
                                }
                                SettingsSwitchRow(
                                    title = "Enable Virtual Display Driver",
                                    subtitle = statusText,
                                    checked = extendedDisplayStatus?.driver_enabled == true,
                                    enabled = extendedDisplayStatus?.driver_control_available == true &&
                                        extendedDisplayStatus?.requires_admin != true &&
                                        !extendedDisplayDriverChanging,
                                    onCheckedChange = { viewModel.setExtendedDisplayDriverEnabled(it) }
                                )
                            }
                        }

                        7 -> item {
                            SectionTitle("Appearance", themeColor)
                            SettingsSection("Colors") {
                                ColorListItem("Theme Accent", Color(theme.color)) { colorPickerMode = "accent"; showColorDialog = true }
                                ColorListItem("Text Color", Color(theme.titleColor)) { colorPickerMode = "text"; showColorDialog = true }
                                ColorListItem("Container Bg", Color(theme.containerColor)) { colorPickerMode = "container"; showColorDialog = true }
                                ColorListItem("Top Bar Color", Color(theme.topBarColor)) { colorPickerMode = "topbar"; showColorDialog = true }
                            }
                            SettingsSection("Opacity") {
                                OpacitySlider("Grid Opacity", tempContainerAlpha) {
                                    tempContainerAlpha = it
                                    viewModel.updateTheme(theme.copy(containerAlpha = it))
                                }
                                OpacitySlider("Row Opacity", tempRowAlpha) {
                                    tempRowAlpha = it
                                    viewModel.updateTheme(theme.copy(rowContainerAlpha = it))
                                }
                                OpacitySlider("Top Bar Opacity", tempTopBarAlpha) {
                                    tempTopBarAlpha = it
                                    viewModel.updateTheme(theme.copy(topBarAlpha = it))
                                }
                            }
                            SettingsSection("Wallpaper") {
                                ListItem(
                                    headlineContent = { Text("Wallpaper") },
                                    leadingContent = { Icon(Icons.Default.Image, null) },
                                    trailingContent = {
                                        IconButton(onClick = { launcher.launch("image/*") }) {
                                            Icon(if (theme.bgImagePath.isEmpty()) Icons.Default.FileUpload else Icons.Default.Cached, null)
                                        }
                                    },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                                )
                                if (theme.bgImagePath.isNotEmpty()) {
                                    SettingsSwitchRow(
                                        title = "Show Wallpaper",
                                        checked = theme.showWallpaper,
                                        onCheckedChange = { viewModel.updateTheme(theme.copy(showWallpaper = it)) }
                                    )
                                    OpacitySlider("Wallpaper Opacity", tempBgAlpha) {
                                        tempBgAlpha = it
                                        viewModel.updateTheme(theme.copy(bgAlpha = it))
                                    }
                                }
                            }
                        }

                        8 -> item {
                            SectionTitle("Shortcuts", themeColor)
                            SettingsSection("Commands") {
                                shortcuts.forEachIndexed { index, shortcut ->
                                    ListItem(
                                        headlineContent = { Text(shortcut.label) },
                                        supportingContent = { Text(shortcut.description()) },
                                        leadingContent = { Icon(shortcutIcon(shortcut.type), null) },
                                        trailingContent = {
                                            Row {
                                                IconButton(onClick = { editingShortcutIndex = index }) {
                                                    Icon(Icons.Default.Edit, null)
                                                }
                                                IconButton(onClick = {
                                                    viewModel.saveShortcuts(shortcuts.filterIndexed { i, _ -> i != index })
                                                }) {
                                                    Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                                                }
                                            }
                                        },
                                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                                    )
                                }
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            viewModel.fetchStartMenuApps()
                                            editingShortcutIndex = shortcuts.size
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Add, null)
                                        Spacer(Modifier.width(6.dp))
                                        Text("Add")
                                    }
                                    Button(
                                        onClick = { viewModel.restoreDefaultShortcuts() },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Restore, null)
                                        Spacer(Modifier.width(6.dp))
                                        Text("Defaults")
                                    }
                                }
                            }
                        }

                        9 -> item {
                            SectionTitle("Advanced", themeColor)
                            SettingsSection("Hardware Encoder Diagnostics") {
                                H264StatusPanel(
                                    status = h264Status,
                                    enabled = theme.useHardwareEncoding,
                                    onRefresh = { viewModel.fetchH264Status(refresh = true) }
                                )
                            }
                            SettingsSection("Window Filter") {
                                SettingsSwitchRow(
                                    title = "Enable Window Filter",
                                    checked = windowFilter.enabled,
                                    onCheckedChange = { viewModel.updateWindowFilter(windowFilter.copy(enabled = it)) }
                                )
                                SettingsSwitchRow(
                                    title = "Hide Common System Windows",
                                    checked = windowFilter.hideSystemWindows,
                                    onCheckedChange = { viewModel.updateWindowFilter(windowFilter.copy(hideSystemWindows = it)) }
                                )
                                OutlinedTextField(
                                    value = titleFilterText,
                                    onValueChange = {
                                        titleFilterText = it
                                        viewModel.updateWindowFilter(windowFilter.copy(
                                            titleContains = parseRuleLines(it),
                                            processNames = parseRuleLines(processFilterText),
                                            classNames = parseRuleLines(classFilterText)
                                        ))
                                    },
                                    label = { Text("Title keywords, one per line") },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 2,
                                    maxLines = 4
                                )
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = processFilterText,
                                    onValueChange = {
                                        processFilterText = it
                                        viewModel.updateWindowFilter(windowFilter.copy(
                                            titleContains = parseRuleLines(titleFilterText),
                                            processNames = parseRuleLines(it),
                                            classNames = parseRuleLines(classFilterText)
                                        ))
                                    },
                                    label = { Text("Process names, one per line") },
                                    supportingText = { Text("Example: TextInputHost.exe") },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 2,
                                    maxLines = 4
                                )
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = classFilterText,
                                    onValueChange = {
                                        classFilterText = it
                                        viewModel.updateWindowFilter(windowFilter.copy(
                                            titleContains = parseRuleLines(titleFilterText),
                                            processNames = parseRuleLines(processFilterText),
                                            classNames = parseRuleLines(it)
                                        ))
                                    },
                                    label = { Text("Window class names, one per line") },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 2,
                                    maxLines = 4
                                )
                                TextButton(
                                    onClick = {
                                        titleFilterText = ""
                                        processFilterText = ""
                                        classFilterText = ""
                                        viewModel.updateWindowFilter(WindowFilterSettings())
                                    }
                                ) {
                                    Icon(Icons.Default.Restore, null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Restore Default Filter")
                                }
                            }
                        }
                    }
                }
            }

            BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (maxWidth > maxHeight) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        SettingsSideTabs(
                            tabs = tabs,
                            selectedTab = selectedTab,
                            onSelected = { selectedTab = it },
                            color = themeColor,
                            modifier = Modifier.fillMaxHeight().width(176.dp)
                        )
                        VerticalDivider()
                        settingsContent(Modifier.weight(1f).fillMaxHeight())
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        ScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 12.dp) {
                            tabs.forEachIndexed { index, label ->
                                Tab(
                                    selected = selectedTab == index,
                                    onClick = { selectedTab = index },
                                    text = { Text(label) }
                                )
                            }
                        }
                        settingsContent(Modifier.fillMaxSize())
                    }
                }
            }
        }
    }

    if (showColorDialog) {
        val currentSysPrimary = MaterialTheme.colorScheme.primary.toArgb()
        ColorPickerDialog(
            title = "Select ${colorPickerMode.replaceFirstChar { it.uppercase() }} Color",
            onDismiss = { showColorDialog = false },
            onColorSelected = { color ->
                val newTheme = when(colorPickerMode) {
                    "accent" -> theme.copy(color = color)
                    "text" -> theme.copy(titleColor = color)
                    "container" -> theme.copy(containerColor = color)
                    "topbar" -> theme.copy(topBarColor = color)
                    else -> theme
                }
                viewModel.updateTheme(newTheme)
                showColorDialog = false
            },
            onSetDefault = {
                val color = if (colorPickerMode == "text") 0xFFFFFFFF.toInt() else currentSysPrimary
                val newTheme = when(colorPickerMode) {
                    "accent" -> theme.copy(color = color)
                    "text" -> theme.copy(titleColor = color)
                    "container" -> theme.copy(containerColor = color)
                    "topbar" -> theme.copy(topBarColor = color, topBarAlpha = 0f)
                    else -> theme
                }
                viewModel.updateTheme(newTheme)
                showColorDialog = false
            }
        )
    }

    editingShortcutIndex?.let { index ->
        val existing = shortcuts.getOrNull(index)
        ShortcutEditDialog(
            shortcut = existing,
            startMenuApps = startMenuApps,
            startMenuAppsLoading = startMenuAppsLoading,
            onRefreshStartMenuApps = { viewModel.fetchStartMenuApps() },
            onDismiss = { editingShortcutIndex = null },
            onSave = { shortcut ->
                val next = shortcuts.toMutableList()
                if (index in next.indices) next[index] = shortcut else next.add(shortcut)
                viewModel.saveShortcuts(next)
                editingShortcutIndex = null
            }
        )
    }
}

@Composable
fun SettingsSection(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), shape = RoundedCornerShape(16.dp)) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            content()
        }
    }
}

@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.takeIf { it.isNotBlank() }?.let {
            {
                Text(
                    text = it,
                    maxLines = 2,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        trailingContent = {
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
fun SettingsSliderRow(
    label: String,
    value: Int,
    suffix: String,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Int) -> Unit
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
        Text("$label: $value$suffix", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = range,
            steps = steps
        )
    }
}

@Composable
fun SettingsFloatSliderRow(
    label: String,
    value: Float,
    suffix: String,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
        Text(
            "$label: ${"%.1f".format(value)}$suffix",
            style = MaterialTheme.typography.bodySmall
        )
        Slider(
            value = value,
            onValueChange = { onValueChange((it * 10).roundToInt() / 10f) },
            valueRange = range,
            steps = steps
        )
    }
}

@Composable
fun ColorListItem(label: String, color: Color, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Box(Modifier.size(24.dp).clip(CircleShape).background(color).clickable { onClick() }) },
        trailingContent = { Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp)) },
        modifier = Modifier.clickable { onClick() },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
fun OpacitySlider(label: String, value: Float, onValueChange: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text("$label: ${(value * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
        Slider(value = value, onValueChange = onValueChange)
    }
}

@Composable
fun StreamSlider(
    label: String,
    value: Int,
    suffix: String,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Int) -> Unit
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text("$label: $value$suffix", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = range,
            steps = steps
        )
    }
}

@Composable
fun H264StatusPanel(
    status: H264StatusInfo?,
    enabled: Boolean,
    onRefresh: () -> Unit
) {
    val bg = if (status?.usable == true) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = if (enabled) 0.45f else 0.22f)
    }
    val fg = if (status?.usable == true) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }

    Surface(
        color = bg,
        contentColor = fg,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (status?.usable == true) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = when {
                        status == null -> "Hardware encoder status not checked"
                        status.usable -> "Hardware encoder ready: ${status.selected_encoder}" +
                            if (status.selected_profile.isBlank()) "" else " (${status.selected_profile})"
                        enabled -> "Hardware encoder unavailable"
                        else -> "Hardware encoder disabled"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onRefresh, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Check")
                }
            }
            if (status != null) {
                Text(
                    text = "FFmpeg: ${status.ffmpeg_path.ifEmpty { "Not found" }}",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp
                )
                Text(
                    text = "Native streamer: ${if (status.native_screen_capture) "Available" else "Unavailable"}" +
                        if (status.native_screen_message.isBlank()) "" else " - ${status.native_screen_message.lineSequence().firstOrNull().orEmpty()}",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    maxLines = 2
                )
                Text(
                    text = "Direct screen capture: ${if (status.direct_screen_capture) "Available" else "Unavailable"}" +
                        if (status.direct_screen_message.isBlank()) "" else " - ${status.direct_screen_message.lineSequence().firstOrNull().orEmpty()}",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    maxLines = 2
                )
                if (status.message.isNotBlank()) {
                    Text(
                        text = status.message,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        maxLines = 4
                    )
                }
                status.results.forEach { result ->
                    Text(
                        text = "${result.encoder}: ${if (result.usable) "OK ${result.profile}" else if (result.available) "Failed" else "Missing"}" +
                            if (result.message.isBlank()) "" else " - ${result.message.lineSequence().firstOrNull().orEmpty()}",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 10.sp,
                        maxLines = 2
                    )
                }
            } else {
                Text(
                    text = "Set TSKBSYNC_FFMPEG/TSKBSYNC_FFMPEG_DIR, use pc_backend\\ffmpeg_path.txt, or use PATH.",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp
                )
            }
        }
    }
}

private fun parseRuleLines(text: String): List<String> =
    text.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()

private fun shortcutIcon(type: ShortcutActionType) = when (type) {
    ShortcutActionType.KEYS -> Icons.Default.KeyboardCommandKey
    ShortcutActionType.START_MENU_APP -> Icons.Default.Apps
    ShortcutActionType.COMMAND -> Icons.Default.Terminal
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortcutEditDialog(
    shortcut: ShortcutConfig?,
    startMenuApps: List<StartMenuAppInfo>,
    startMenuAppsLoading: Boolean,
    onRefreshStartMenuApps: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (ShortcutConfig) -> Unit
) {
    var label by remember(shortcut) { mutableStateOf(shortcut?.label ?: "") }
    var keysText by remember(shortcut) { mutableStateOf(shortcut?.keys?.joinToString("+") ?: "") }
    var actionType by remember(shortcut) { mutableStateOf(shortcut?.type ?: ShortcutActionType.KEYS) }
    var target by remember(shortcut) { mutableStateOf(shortcut?.target ?: "") }
    var appMenuExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (shortcut == null) "Add Shortcut" else "Edit Shortcut") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf(
                        ShortcutActionType.KEYS to "Keys",
                        ShortcutActionType.START_MENU_APP to "Start",
                        ShortcutActionType.COMMAND to "Command"
                    ).forEachIndexed { index, item ->
                        SegmentedButton(
                            selected = actionType == item.first,
                            onClick = { actionType = item.first },
                            shape = SegmentedButtonDefaults.itemShape(index, 3),
                            label = { Text(item.second) }
                        )
                    }
                }
                when (actionType) {
                    ShortcutActionType.KEYS -> OutlinedTextField(
                        value = keysText,
                        onValueChange = { keysText = it.uppercase() },
                        label = { Text("Keys, e.g. CTRL+V") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    ShortcutActionType.START_MENU_APP -> {
                        Box {
                            OutlinedTextField(
                                value = target,
                                onValueChange = { target = it },
                                label = { Text("Start menu program") },
                                supportingText = { Text("Choose a discovered .lnk or paste a program/shortcut path") },
                                trailingIcon = {
                                    Row {
                                        IconButton(onClick = onRefreshStartMenuApps) {
                                            Icon(Icons.Default.Refresh, null)
                                        }
                                        IconButton(onClick = { appMenuExpanded = true }) {
                                            Icon(Icons.Default.ArrowDropDown, null)
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            DropdownMenu(
                                expanded = appMenuExpanded,
                                onDismissRequest = { appMenuExpanded = false }
                            ) {
                                if (startMenuAppsLoading) {
                                    DropdownMenuItem(text = { Text("Loading...") }, onClick = {})
                                } else if (startMenuApps.isEmpty()) {
                                    DropdownMenuItem(text = { Text("No programs found. Refresh or paste a path.") }, onClick = {})
                                } else {
                                    startMenuApps.forEach { app ->  // .take(80)
                                        DropdownMenuItem(
                                            text = { Text(app.label) },
                                            onClick = {
                                                label = label.ifBlank { app.label }
                                                target = app.path
                                                appMenuExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    ShortcutActionType.COMMAND -> OutlinedTextField(
                        value = target,
                        onValueChange = { target = it },
                        label = { Text("Command") },
                        supportingText = { Text("Runs on the PC. Only add commands you trust.") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val keys = keysText.split("+", ",", " ")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    val cleanTarget = target.trim()
                    val canSave = label.isNotBlank() && when (actionType) {
                        ShortcutActionType.KEYS -> keys.isNotEmpty()
                        ShortcutActionType.START_MENU_APP, ShortcutActionType.COMMAND -> cleanTarget.isNotEmpty()
                    }
                    if (canSave) {
                        onSave(
                            ShortcutConfig(
                                label = label.trim(),
                                keys = if (actionType == ShortcutActionType.KEYS) keys else emptyList(),
                                type = actionType,
                                target = if (actionType == ShortcutActionType.KEYS) "" else cleanTarget
                            )
                        )
                    }
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPickerDialog(title: String, onDismiss: () -> Unit, onColorSelected: (Int) -> Unit, onSetDefault: () -> Unit) {
    var hexText by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                val colors = listOf(
                    0xFF6200EE, 0xFF03DAC5, 0xFFF44336, 0xFF2196F3, 0xFF4CAF50, 0xFFFF9800,
                    0xFFFFFFFF, 0xFF000000, 0xFF333333, 0xFF888888, 0xFFFFC107, 0xFF9C27B0
                )
                FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = 4, horizontalArrangement = Arrangement.Center) {
                    colors.forEach { color ->
                        Box(
                            Modifier.padding(6.dp).size(48.dp).clip(CircleShape).background(Color(color))
                                .clickable { onColorSelected(color.toInt()) }
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = hexText,
                    onValueChange = { 
                        hexText = it.take(8)
                        if (it.length == 6 || it.length == 8) {
                            try {
                                val c = if (it.length == 6) "FF$it" else it
                                val parsed = c.toLong(16).toInt()
                                onColorSelected(parsed)
                            } catch (e: Exception) {}
                        }
                    },
                    label = { Text("Custom Hex (e.g. FF6200EE)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onSetDefault, modifier = Modifier.fillMaxWidth()) {
                    Text("Set to Default")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun SectionTitle(title: String, color: Color) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier.padding(bottom = 8.dp, top = 8.dp)
    )
}

@Composable
fun SettingsSideTabs(
    tabs: List<String>,
    selectedTab: Int,
    onSelected: (Int) -> Unit,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 10.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        tabs.forEachIndexed { index, label ->
            val selected = selectedTab == index
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (selected) color.copy(alpha = 0.16f) else Color.Transparent,
                contentColor = if (selected) color else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clickable { onSelected(index) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = label,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
