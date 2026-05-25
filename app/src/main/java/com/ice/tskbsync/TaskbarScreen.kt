package com.ice.tskbsync

import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Base64
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.SharedFlow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.snapshotFlow
import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun TaskbarScreen(viewModel: TaskbarViewModel, navController: NavController) {
    val windows by viewModel.windows
    val isConnected by viewModel.isConnected
    val error by viewModel.error
    val pcIp by viewModel.pcIp
    val theme by viewModel.theme
    val layoutMode by viewModel.layoutMode
    val gridCols by viewModel.gridColumns
    val showTitles by viewModel.showTitles
    val lastSyncTime by viewModel.lastSyncTime
    val inputStatus by viewModel.inputStatus
    val screens by viewModel.screens

    val accentColor = Color(theme.color)
    val titleColor = Color(theme.titleColor)
    val containerBgBase = Color(theme.containerColor)
    val topBarColor = Color(theme.topBarColor).copy(alpha = theme.topBarAlpha)
    val bgColor = lerp(MaterialTheme.colorScheme.surface, accentColor, 0.1f)
    var isLandscapeSingleMode by rememberSaveable { mutableStateOf(false) }
    var showLandscapeShortcutBar by rememberSaveable { mutableStateOf(false) }
    var showWindowControlBar by rememberSaveable { mutableStateOf(false) }
    var landscapeInputMode by rememberSaveable { mutableStateOf("touch") }
    var rightPanelMode by rememberSaveable { mutableStateOf<String?>(null) } // null/window list, "mouse", "screen"
    var selectedScreenIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var keyboardText by remember { mutableStateOf(TextFieldValue("")) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val coroutineScope = rememberCoroutineScope()
    val contentMode = if (isLandscapeSingleMode) "landscape" else layoutMode
    val controlledWindow = windows.firstOrNull { it.hwnd == viewModel.selectedRowHwnd.value }
        ?: windows.firstOrNull { it.is_active }
        ?: windows.firstOrNull()

    LaunchedEffect(isLandscapeSingleMode, layoutMode, theme.showPreviews) {
        if (!theme.showPreviews || (!isLandscapeSingleMode && layoutMode == "grid")) {
            viewModel.stopLiveStream()
        }
    }

    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = accentColor, onSurface = titleColor)) {
        Scaffold(
            containerColor = bgColor,
            topBar = {
                CenterAlignedTopAppBar(
                    navigationIcon = {
                        if (showWindowControlBar) {
                            IconButton(onClick = { showWindowControlBar = false }) {
                                Icon(Icons.Default.Close, null, tint = titleColor)
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = {
                                    showWindowControlBar = true
                                    showLandscapeShortcutBar = false
                                    viewModel.fetchScreens()
                                }) {
                                    Icon(Icons.Default.OpenInNew, null, tint = titleColor)
                                }
                                if (isLandscapeSingleMode) {
                                    IconButton(onClick = {
                                        showLandscapeShortcutBar = !showLandscapeShortcutBar
                                        if (showLandscapeShortcutBar) showWindowControlBar = false
                                    }) {
                                        Icon(
                                            if (showLandscapeShortcutBar) Icons.Default.Close else Icons.Default.KeyboardCommandKey,
                                            null,
                                            tint = titleColor
                                        )
                                    }
                                }
                            }
                        }
                    },
                    title = {
                        if (showWindowControlBar) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                            ) {
                                if (controlledWindow == null) {
                                    AssistChip(
                                        onClick = {},
                                        label = { Text("No window", maxLines = 1) },
                                        leadingIcon = { Icon(Icons.Default.Info, null, modifier = Modifier.size(18.dp)) }
                                    )
                                } else {
                                    IconButton(onClick = { viewModel.controlWindow(controlledWindow.hwnd, "close") }) {
                                        Icon(Icons.Default.Close, null, tint = titleColor)
                                    }
                                    IconButton(onClick = { viewModel.controlWindow(controlledWindow.hwnd, "minimize") }) {
                                        Icon(Icons.Default.Minimize, null, tint = titleColor)
                                    }
                                    IconButton(onClick = {
                                        viewModel.controlWindow(
                                            controlledWindow.hwnd,
                                            if (controlledWindow.is_maximized) "restore" else "maximize"
                                        )
                                    }) {
                                        Icon(
                                            if (controlledWindow.is_maximized) Icons.Default.CloseFullscreen else Icons.Default.OpenInFull,
                                            null,
                                            tint = titleColor
                                        )
                                    }
                                    screens.forEach { screen ->
                                        AssistChip(
                                            onClick = { viewModel.controlWindow(controlledWindow.hwnd, "move_to_screen", screen.monitor_index) },
                                            label = { Text("Display ${screen.monitor_index}", maxLines = 1) },
                                            leadingIcon = { Icon(Icons.Default.StayCurrentLandscape, null, modifier = Modifier.size(18.dp)) }
                                        )
                                    }
                                }
                            }
                        } else if (isLandscapeSingleMode && showLandscapeShortcutBar) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                            ) {
                                FilterChip(
                                    selected = landscapeInputMode == "touch",
                                    onClick = { landscapeInputMode = if (landscapeInputMode == "touch") "mouse" else "touch" },
                                    label = { Text(if (landscapeInputMode == "touch") "Touch" else "Mouse") },
                                    leadingIcon = { Icon(if (landscapeInputMode == "touch") Icons.Default.TouchApp else Icons.Default.Mouse, null, modifier = Modifier.size(18.dp)) }
                                )
                                IconButton(onClick = {
                                    coroutineScope.launch {
                                        kotlinx.coroutines.delay(80)
                                        focusRequester.requestFocus()
                                        keyboardController?.show()
                                    }
                                }) {
                                    Icon(Icons.Default.Keyboard, null, tint = titleColor)
                                }
                                viewModel.shortcuts.value.forEach { shortcut ->
                                    AssistChip(
                                        onClick = { viewModel.sendShortcut(shortcut) },
                                        label = { Text(shortcut.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                    )
                                }
                            }
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("TskbSync", fontWeight = FontWeight.Bold, color = titleColor)
                                if (isConnected && lastSyncTime > 0) {
                                    Text(
                                        "Synced: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(lastSyncTime))}",
                                        style = MaterialTheme.typography.labelSmall, color = titleColor.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        if (!showLandscapeShortcutBar && !showWindowControlBar) {
                            IconButton(onClick = { isLandscapeSingleMode = !isLandscapeSingleMode }) {
                                Icon(
                                    if (isLandscapeSingleMode) Icons.Default.StayCurrentPortrait else Icons.Default.StayCurrentLandscape,
                                    null,
                                    tint = titleColor
                                )
                            }
                            IconButton(onClick = {
                                viewModel.toggleLayout()
                                if (isLandscapeSingleMode) {
                                    isLandscapeSingleMode = false
                                    showLandscapeShortcutBar = false
                                    rightPanelMode = null
                                    selectedScreenIndex = null
                                    viewModel.stopRemoteInput()
                                }
                            }) {
                                Icon(if (layoutMode == "grid") Icons.Default.ViewStream else Icons.Default.GridView, null, tint = titleColor)
                            }
                            IconButton(onClick = { navController.navigate("settings") }) {
                                Icon(Icons.Default.Settings, null, tint = titleColor)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = topBarColor)
                )
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize()) {
                BasicTextField(
                    value = keyboardText,
                    onValueChange = { newValue ->
                        val oldText = keyboardText.text
                        val nextText = newValue.text
                        when {
                            nextText.length > oldText.length -> {
                                val prefix = oldText.commonPrefixWith(nextText)
                                val inserted = nextText.substring(prefix.length)
                                viewModel.sendTextInput(inserted)
                            }
                            nextText.length < oldText.length -> {
                                repeat(oldText.length - nextText.length) { viewModel.sendKeyInput("BACKSPACE") }
                            }
                        }
                        keyboardText = TextFieldValue("")
                    },
                    modifier = Modifier
                        .size(1.dp)
                        .alpha(0f)
                        .focusRequester(focusRequester)
                        .onKeyEvent {
                            when (it.key) {
                                Key.Enter -> {
                                    viewModel.sendKeyInput("ENTER")
                                    true
                                }
                                Key.Backspace -> {
                                    viewModel.sendKeyInput("BACKSPACE")
                                    true
                                }
                                else -> false
                            }
                        }
                )

                if (theme.showWallpaper && theme.bgImagePath.isNotEmpty()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current).data(theme.bgImagePath).crossfade(true).build(),
                        contentDescription = null, modifier = Modifier.fillMaxSize().alpha(theme.bgAlpha), contentScale = ContentScale.Crop
                    )
                }

                if (isLandscapeSingleMode) {
                    BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                        val isAlreadyLandscape = maxWidth > maxHeight
                        val landscapePreviewUsesH264 = if (selectedScreenIndex != null) {
                            theme.useHardwareEncoding
                        } else {
                            theme.useHighPerformanceWindowStreaming
                        }
                        val landscapeContent: @Composable BoxScope.() -> Unit = {
                            if (!isConnected) {
                                ConnectionStatusPlaceholder(pcIp, error, titleColor) { viewModel.connect(pcIp) }
                            } else {
                                LandscapeSingleMode(
                                    windows = windows,
                                    titleColor = titleColor,
                                    accentColor = accentColor,
                                    containerColor = containerBgBase,
                                    containerAlpha = theme.rowContainerAlpha,
                                    showTitles = showTitles,
                                    showPreviews = theme.showPreviews,
                                    useHardwareEncoding = landscapePreviewUsesH264,
                                    clipLivePreview = theme.clipLivePreview,
                                    livePreviewCornerPx = theme.livePreviewCornerPx,
                                    focusedLiveFrame = viewModel.focusedLiveFrame,
                                    h264VideoConfig = viewModel.h264VideoConfig,
                                    h264Error = viewModel.h264Error,
                                    h264Frames = viewModel.h264Frames,
                                    inputStatus = inputStatus,
                                    onDismissInputStatus = { viewModel.clearInputStatus() },
                                    selectedRowHwnd = viewModel.selectedRowHwnd.value,
                                    rightPanelMode = rightPanelMode,
                                    screens = screens,
                                    selectedScreenIndex = selectedScreenIndex,
                                    inputMode = landscapeInputMode,
                                    onToggleMouseMode = {
                                        rightPanelMode = if (rightPanelMode == "mouse") null else "mouse"
                                    },
                                    onToggleScreenMode = {
                                        val next = if (rightPanelMode == "screen") null else "screen"
                                        rightPanelMode = next
                                        if (next == "screen") {
                                            viewModel.fetchScreens()
                                        }
                                    },
                                    onScreenSelected = { screen ->
                                        selectedScreenIndex = screen.monitor_index
                                        viewModel.startLiveScreenStream(screen.monitor_index)
                                        viewModel.startRemoteScreenInput(screen.monitor_index)
                                    },
                                    onMouseLeftClick = { viewModel.sendMouseButtonClick("left") },
                                    onMouseMiddleClick = { viewModel.sendMouseButtonClick("middle") },
                                    onMouseRightClick = { viewModel.sendMouseButtonClick("right") },
                                    onMouseWheel = { delta -> viewModel.sendMouseWheel(delta) },
                                    onSwitch = { viewModel.switchWindow(it) },
                                    onRowPageChange = { viewModel.rememberRowWindow(it) },
                                    onStartInput = {
                                        if (selectedScreenIndex == null) viewModel.startRemoteInput(it)
                                    },
                                    onStopInput = { viewModel.stopRemoteInput() },
                                    onMouseInput = { action, x, y -> viewModel.sendMouseInput(action, x, y) },
                                    onTouchInput = { action, x, y -> viewModel.sendTouchInput(action, x, y) },
                                    onMultiTouchInput = { points -> viewModel.sendMultiTouchInput(points) },
                                    onCenterChange = { hwnd ->
                                        if (hwnd != null) {
                                            selectedScreenIndex = null
                                            viewModel.startLiveStream(hwnd)
                                            viewModel.startRemoteInput(hwnd)
                                        } else {
                                            viewModel.stopLiveStream()
                                        }
                                    }
                                )
                            }
                        }
                        if (isAlreadyLandscape) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                                content = landscapeContent
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .requiredWidth(maxHeight)
                                    .requiredHeight(maxWidth)
                                    .rotate(90f),
                                contentAlignment = Alignment.Center,
                                content = landscapeContent
                            )
                        }
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!isConnected) {
                        ConnectionStatusPlaceholder(pcIp, error, titleColor) { viewModel.connect(pcIp) }
                    } else {
                        AnimatedContent(
                            targetState = contentMode,
                            transitionSpec = {
                                (fadeIn(animationSpec = tween(180)) + scaleIn(initialScale = 0.98f, animationSpec = tween(180)))
                                    .togetherWith(fadeOut(animationSpec = tween(140)) + scaleOut(targetScale = 1.02f, animationSpec = tween(140)))
                                    .using(SizeTransform(clip = false))
                            },
                            label = "mainContentMode",
                            modifier = Modifier.fillMaxSize()
                        ) { mode ->
                            when (mode) {
                                "grid" -> {
                                    LazyVerticalGrid(
                                        columns = GridCells.Fixed(gridCols),
                                        contentPadding = PaddingValues(16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(12.dp),
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        items(windows) { window ->
                                            val bitmap = rememberIconBitmap(window.icon, window.hwnd)
                                            val previewStr = viewModel.gridPreviewCache[window.hwnd] ?: ""
                                            val previewBitmap = rememberPreviewBitmap(previewStr, window.hwnd)
                                            WindowGridItem(window, showTitles, titleColor, accentColor, containerBgBase.copy(alpha = theme.containerAlpha), bitmap, previewBitmap, theme.showPreviews) { viewModel.switchWindow(window.hwnd) }
                                        }
                                    }
                                }

                                else -> {
                                    CarouselPagerMode(
                                        windows = windows,
                                        showTitles = showTitles,
                                        titleColor = titleColor,
                                        accentColor = accentColor,
                                        containerColor = containerBgBase,
                                        containerAlpha = theme.rowContainerAlpha,
                                        showPreviews = theme.showPreviews,
                                        useHardwareEncoding = theme.useHighPerformanceWindowStreaming,
                                        clipLivePreview = theme.clipLivePreview,
                                        livePreviewCornerPx = theme.livePreviewCornerPx,
                                        focusedLiveFrame = viewModel.focusedLiveFrame,
                                        h264VideoConfig = viewModel.h264VideoConfig,
                                        h264Error = viewModel.h264Error,
                                        h264Frames = viewModel.h264Frames,
                                        selectedRowHwnd = viewModel.selectedRowHwnd.value,
                                        onSwitch = { viewModel.switchWindow(it) },
                                        onRowPageChange = { viewModel.rememberRowWindow(it) },
                                        onCenterChange = { hwnd ->
                                            if (hwnd != null) viewModel.startLiveStream(hwnd)
                                            else viewModel.stopLiveStream()
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
}

@Composable
fun CarouselPagerMode(
    windows: List<WindowInfo>,
    showTitles: Boolean,
    titleColor: Color,
    accentColor: Color,
    containerColor: Color,
    containerAlpha: Float,
    showPreviews: Boolean,
    useHardwareEncoding: Boolean,
    clipLivePreview: Boolean,
    livePreviewCornerPx: Int,
    focusedLiveFrame: State<ByteArray?>,
    h264VideoConfig: State<H264VideoConfig?>,
    h264Error: State<String?>,
    h264Frames: SharedFlow<ByteArray>,
    selectedRowHwnd: Long?,
    // Removed the maxWidth parameter here
    onSwitch: (Long) -> Unit,
    onRowPageChange: (Long) -> Unit,
    onCenterChange: (Long?) -> Unit
) {
    if (windows.isEmpty()) return

    val initialPage = windows.indexOfFirst { it.hwnd == selectedRowHwnd }
        .takeIf { it >= 0 }
        ?: windows.indexOfFirst { it.is_active }.takeIf { it >= 0 }
        ?: 0
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { windows.size })
    val coroutineScope = rememberCoroutineScope()

    // ICON WIDTH: 72dp + SPACING: 8dp = 80dp total slot
    val itemWidth = 72.dp
    val itemSpacing = 8.dp
    val totalSlotWidth = itemWidth + itemSpacing

    LaunchedEffect(pagerState.currentPage, showPreviews) {
        val hwnd = windows.getOrNull(pagerState.currentPage)?.hwnd
        if (hwnd != null) onRowPageChange(hwnd)
        onCenterChange(if (showPreviews) hwnd else null)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            val currentWindow = windows.getOrNull(pagerState.currentPage)
            if (currentWindow != null && showPreviews) {
                val previewContainerColor = containerColor.copy(alpha = containerAlpha.coerceAtLeast(0.18f) * 0.55f)
                val previewShape = RoundedCornerShape(24.dp)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(previewShape)
                        .background(previewContainerColor)
                        .clickable { onSwitch(currentWindow.hwnd) }
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    LivePreviewContainer(
                        frame = focusedLiveFrame,
                        currentWindow = currentWindow,
                        accentColor = accentColor,
                        containerColor = previewContainerColor,
                        clipPreview = clipLivePreview,
                        cornerPx = livePreviewCornerPx,
                        useHardwareEncoding = useHardwareEncoding,
                        h264VideoConfig = h264VideoConfig,
                        h264Error = h264Error,
                        h264Frames = h264Frames
                    )
                }
            }
        }

        // --- Bottom Pager ---
        BoxWithConstraints(
            modifier = Modifier
                .padding(bottom = 20.dp, start = 16.dp, end = 16.dp)
                .fillMaxWidth()
                .height(if (showTitles) 85.dp else 70.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(containerColor.copy(alpha = containerAlpha))
        ) {
            // Since the outer parameter was removed, maxWidth now correctly points to the current Box width.
            val sidePadding = (this.maxWidth - totalSlotWidth) / 2

            HorizontalPager(
                state = pagerState,
                pageSize = PageSize.Fixed(totalSlotWidth),
                contentPadding = PaddingValues(horizontal = sidePadding),
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) { page ->
                val window = windows[page]
                val isCentered = pagerState.currentPage == page
                val bitmap = rememberIconBitmap(window.icon, window.hwnd)

                // Content centered in the Fixed slot
                Box(modifier = Modifier.fillMaxHeight().width(totalSlotWidth), contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier.fillMaxHeight().width(itemWidth).clip(RoundedCornerShape(16.dp))
                            .clickable {
                                coroutineScope.launch { pagerState.animateScrollToPage(page) }
                                onSwitch(window.hwnd)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val activeBorder = if (window.is_active) BorderStroke(2.dp, accentColor) else null
                            Surface(shape = RoundedCornerShape(12.dp), color = Color.Transparent, border = activeBorder, modifier = Modifier.padding(2.dp)) {
                                IconContainer(bitmap, window.title, if (isCentered) 46.dp else 36.dp, accentColor, RoundedCornerShape(12.dp))
                            }
                            if (showTitles) {
                                Text(
                                    text = window.title, style = MaterialTheme.typography.labelSmall,
                                    color = titleColor.copy(alpha = if (isCentered) 1.0f else 0.5f),
                                    maxLines = 1, fontSize = 9.sp, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LandscapeSingleMode(
    windows: List<WindowInfo>,
    titleColor: Color,
    accentColor: Color,
    containerColor: Color,
    containerAlpha: Float,
    showTitles: Boolean,
    showPreviews: Boolean,
    useHardwareEncoding: Boolean,
    clipLivePreview: Boolean,
    livePreviewCornerPx: Int,
    focusedLiveFrame: State<ByteArray?>,
    h264VideoConfig: State<H264VideoConfig?>,
    h264Error: State<String?>,
    h264Frames: SharedFlow<ByteArray>,
    inputStatus: String?,
    onDismissInputStatus: () -> Unit,
    selectedRowHwnd: Long?,
    rightPanelMode: String?,
    screens: List<ScreenInfo>,
    selectedScreenIndex: Int?,
    inputMode: String,
    onToggleMouseMode: () -> Unit,
    onToggleScreenMode: () -> Unit,
    onScreenSelected: (ScreenInfo) -> Unit,
    onMouseLeftClick: () -> Unit,
    onMouseMiddleClick: () -> Unit,
    onMouseRightClick: () -> Unit,
    onMouseWheel: (Int) -> Unit,
    onSwitch: (Long) -> Unit,
    onRowPageChange: (Long) -> Unit,
    onStartInput: (Long) -> Unit,
    onStopInput: () -> Unit,
    onMouseInput: (String, Float, Float) -> Unit,
    onTouchInput: (String, Float, Float) -> Unit,
    onMultiTouchInput: (List<TouchPointInput>) -> Unit,
    onCenterChange: (Long?) -> Unit
) {
    if (windows.isEmpty()) return

    val currentWindow = windows.firstOrNull { it.hwnd == selectedRowHwnd }
        ?: windows.firstOrNull { it.is_active }
        ?: windows.first()
    val liveBitmapState = if (useHardwareEncoding) null else rememberLiveBitmapFrame(focusedLiveFrame, currentWindow.hwnd)
    val liveFrameSize = if (useHardwareEncoding) {
        h264VideoConfig.value?.let { it.width to it.height }
    } else {
        liveBitmapState?.value?.let { it.width to it.height }
    }

    LaunchedEffect(currentWindow.hwnd, showPreviews, selectedScreenIndex) {
        onRowPageChange(currentWindow.hwnd)
        if (selectedScreenIndex == null) {
            onCenterChange(if (showPreviews) currentWindow.hwnd else null)
        }
    }

    DisposableEffect(currentWindow.hwnd) {
        if (selectedScreenIndex == null) onStartInput(currentWindow.hwnd)
        onDispose { onStopInput() }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val taskbarWidth = 84.dp
        val previewContainerColor = containerColor.copy(alpha = containerAlpha.coerceAtLeast(0.18f) * 0.55f)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 14.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(end = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val previewShape = RoundedCornerShape(18.dp)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(previewShape)
                            .background(previewContainerColor)
                            .padding(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(inputMode, currentWindow.hwnd, liveFrameSize) {
                                    fun normalizedInPreview(px: Float, py: Float): Pair<Float, Float> {
                                        val frame = liveFrameSize
                                        if (frame == null || frame.first <= 0 || frame.second <= 0) {
                                            return (px / size.width).coerceIn(0f, 1f) to (py / size.height).coerceIn(0f, 1f)
                                        }
                                        val scale = min(size.width / frame.first.toFloat(), size.height / frame.second.toFloat())
                                        val drawnWidth = frame.first * scale
                                        val drawnHeight = frame.second * scale
                                        val left = (size.width - drawnWidth) / 2f
                                        val top = (size.height - drawnHeight) / 2f
                                        return ((px - left) / drawnWidth).coerceIn(0f, 1f) to
                                            ((py - top) / drawnHeight).coerceIn(0f, 1f)
                                    }

                                    awaitEachGesture {
                                        val down = awaitFirstDown()
                                        val (startX, startY) = normalizedInPreview(down.position.x, down.position.y)
                                        var lastX = startX
                                        var lastY = startY
                                        var moved = false
                                        if (inputMode == "touch") {
                                            onMultiTouchInput(
                                                listOf(
                                                    TouchPointInput(
                                                        id = (down.id.value % 10L).toInt(),
                                                        action = "down",
                                                        x = startX,
                                                        y = startY,
                                                        primary = true
                                                    )
                                                )
                                            )
                                        } else {
                                            onMouseInput("move", startX, startY)
                                        }

                                        do {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull() ?: break
                                            val (x, y) = normalizedInPreview(change.position.x, change.position.y)
                                            if (abs(x - startX) > 0.006f || abs(y - startY) > 0.006f) moved = true
                                            if (inputMode == "touch") {
                                                val points = event.changes.mapNotNull { pointer ->
                                                    if (!pointer.pressed && !pointer.previousPressed) return@mapNotNull null
                                                    val (px, py) = normalizedInPreview(pointer.position.x, pointer.position.y)
                                                    val action = when {
                                                        pointer.pressed && !pointer.previousPressed -> "down"
                                                        pointer.pressed && pointer.previousPressed -> "move"
                                                        !pointer.pressed && pointer.previousPressed -> "up"
                                                        else -> "move"
                                                    }
                                                    TouchPointInput(
                                                        id = (pointer.id.value % 10L).toInt(),
                                                        action = action,
                                                        x = px,
                                                        y = py,
                                                        primary = pointer.id == down.id
                                                    )
                                                }
                                                onMultiTouchInput(points)
                                            } else if (change.pressed) {
                                                onMouseInput("move", x, y)
                                            } else {
                                                if (!moved) {
                                                    onMouseInput("click", x, y)
                                                } else {
                                                    onMouseInput("move", x, y)
                                                }
                                            }
                                            lastX = x
                                            lastY = y
                                            change.consume()
                                        } while (event.changes.any { it.pressed })
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (showPreviews) {
                                LivePreviewContainer(
                                    frame = focusedLiveFrame,
                                    currentWindow = currentWindow,
                                    accentColor = accentColor,
                                    containerColor = previewContainerColor,
                                    clipPreview = clipLivePreview,
                                    cornerPx = livePreviewCornerPx,
                                    decodedBitmap = liveBitmapState?.value,
                                    useHardwareEncoding = useHardwareEncoding,
                                    h264VideoConfig = h264VideoConfig,
                                    h264Error = h264Error,
                                    h264Frames = h264Frames
                                )
                            } else {
                                IconContainer(
                                    bitmap = rememberIconBitmap(currentWindow.icon, currentWindow.hwnd),
                                    title = currentWindow.title,
                                    size = 132.dp,
                                    accent = accentColor,
                                    shape = RoundedCornerShape(24.dp)
                                )
                            }
                            if (inputStatus != null) {
                                Surface(
                                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.92f),
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(10.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp)
                                    ) {
                                        Text(
                                            text = inputStatus,
                                            fontSize = 11.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        IconButton(
                                            onClick = onDismissInputStatus,
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.Default.Close, null, modifier = Modifier.size(15.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .width(taskbarWidth)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(20.dp))
                        .background(containerColor.copy(alpha = containerAlpha))
                        .padding(vertical = 8.dp, horizontal = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onToggleMouseMode,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(if (rightPanelMode == "mouse") accentColor.copy(alpha = 0.18f) else Color.Transparent),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Mouse,
                                    contentDescription = "Mouse panel",
                                    tint = if (rightPanelMode == "mouse") accentColor else titleColor.copy(alpha = 0.78f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(4.dp))
                        IconButton(
                            onClick = onToggleScreenMode,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(if (rightPanelMode == "screen") accentColor.copy(alpha = 0.18f) else Color.Transparent),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.StayCurrentLandscape,
                                    contentDescription = "Screen panel",
                                    tint = if (rightPanelMode == "screen") accentColor else titleColor.copy(alpha = 0.78f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                    if (rightPanelMode == "mouse") {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            FilledTonalButton(
                                onClick = onMouseLeftClick,
                                contentPadding = PaddingValues(horizontal = 4.dp),
                                modifier = Modifier.fillMaxWidth().height(34.dp)
                            ) { Text("Left", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
                            FilledTonalButton(
                                onClick = onMouseMiddleClick,
                                contentPadding = PaddingValues(horizontal = 4.dp),
                                modifier = Modifier.fillMaxWidth().height(34.dp)
                            ) { Text("Middle", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
                            FilledTonalButton(
                                onClick = onMouseRightClick,
                                contentPadding = PaddingValues(horizontal = 4.dp),
                                modifier = Modifier.fillMaxWidth().height(34.dp)
                            ) { Text("Right", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.Black.copy(alpha = 0.12f))
                                    .pointerInput(Unit) {
                                        awaitEachGesture {
                                            val down = awaitFirstDown()
                                            var prevY = down.position.y
                                            do {
                                                val event = awaitPointerEvent()
                                                val change = event.changes.firstOrNull() ?: break
                                                val dy = change.position.y - prevY
                                                if (kotlin.math.abs(dy) > 6f) {
                                                    onMouseWheel(((-dy) * 1.2f).roundToInt().coerceIn(-120, 120))
                                                    prevY = change.position.y
                                                }
                                                change.consume()
                                            } while (event.changes.any { it.pressed })
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Wheel", fontSize = 10.sp, color = titleColor.copy(alpha = 0.8f))
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                        if (rightPanelMode == "screen") {
                            if (screens.isEmpty()) {
                                Text(
                                    "No screens",
                                    color = titleColor.copy(alpha = 0.58f),
                                    fontSize = 10.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(top = 12.dp)
                                )
                            } else {
                                screens.forEach { screen ->
                                    val selected = selectedScreenIndex == screen.monitor_index
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (selected) accentColor.copy(alpha = 0.18f) else Color.Transparent,
                                        border = if (selected) BorderStroke(1.dp, accentColor) else null,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onScreenSelected(screen) }
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 7.dp)
                                        ) {
                                            if (selected) Icon(Icons.Default.Check, null, tint = accentColor, modifier = Modifier.size(14.dp))
                                            Text(
                                                "Display ${screen.monitor_index}",
                                                color = titleColor,
                                                fontSize = 10.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                textAlign = TextAlign.Center
                                            )
                                            Text(
                                                "${screen.width}x${screen.height}",
                                                color = titleColor.copy(alpha = 0.62f),
                                                fontSize = 8.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                }
                            }
                        } else windows.forEach { window ->
                            val isSelected = window.hwnd == currentWindow.hwnd
                            val bitmap = rememberIconBitmap(window.icon, window.hwnd)
                            val border = if (isSelected) BorderStroke(2.dp, accentColor) else null

                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (isSelected) accentColor.copy(alpha = 0.14f) else Color.Transparent,
                                border = border,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onRowPageChange(window.hwnd)
                                        onCenterChange(window.hwnd)
                                        onSwitch(window.hwnd)
                                    }
                            ) {
                                Column(
                                    modifier = Modifier.padding(vertical = 7.dp, horizontal = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    IconContainer(bitmap, window.title, if (isSelected) 42.dp else 34.dp, accentColor, RoundedCornerShape(12.dp))
                                    if (showTitles) {
                                        Text(
                                            text = window.title,
                                        color = titleColor.copy(alpha = if (isSelected) 1f else 0.62f),
                                        fontSize = 8.sp,
                                        lineHeight = 8.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(top = 3.dp)
                                        )
                                    }
                                }
                            }
                        }
                        }
                    }
                    }
                }
                }
            }
        }
    }

@Composable
fun ConnectionStatusPlaceholder(pcIp: String, error: String?, tint: Color, onRetry: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Disconnected", style = MaterialTheme.typography.headlineSmall, color = tint.copy(alpha = 0.5f))
        Text("Target: ${pcIp.ifEmpty { "None" }}", color = tint.copy(alpha = 0.7f))
        if (error != null) { Text(text = error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp), fontSize = 12.sp) }
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRetry) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text("Retry Connection") }
    }
}

@Composable
fun WindowGridItem(window: WindowInfo, showTitle: Boolean, textColor: Color, accent: Color, cardBg: Color, bitmap: android.graphics.Bitmap?, previewBitmap: android.graphics.Bitmap?, showPreviews: Boolean, onClick: () -> Unit) {
    val activeBorder = if (window.is_active) BorderStroke(2.dp, accent) else null
    Box(modifier = Modifier.aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(cardBg).clickable { onClick() }) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Surface(shape = RoundedCornerShape(12.dp), color = Color.Transparent, border = activeBorder, modifier = if (showPreviews && previewBitmap != null) Modifier.weight(1f).fillMaxWidth() else Modifier) {
                if (showPreviews && previewBitmap != null) {
                    Image(bitmap = previewBitmap.asImageBitmap(), contentDescription = window.title, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                } else { IconContainer(bitmap, window.title, 48.dp, accent, RoundedCornerShape(12.dp)) }
            }
            if (showTitle) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = window.title, style = MaterialTheme.typography.labelSmall, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 2.dp))
            }
        }
        if (showPreviews && previewBitmap != null && bitmap != null) {
            Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(18.dp).align(Alignment.TopEnd).padding(4.dp))
        }
    }
}

@Composable
fun IconContainer(bitmap: android.graphics.Bitmap?, title: String, size: androidx.compose.ui.unit.Dp, accent: Color, shape: androidx.compose.ui.graphics.Shape = CircleShape) {
    if (bitmap != null) {
        Image(bitmap = bitmap.asImageBitmap(), contentDescription = title, modifier = Modifier.size(size).clip(shape), contentScale = ContentScale.Fit)
    } else {
        Box(modifier = Modifier.size(size).background(accent.copy(alpha = 0.2f), shape), contentAlignment = Alignment.Center) {
            Text(title.take(1).uppercase(), color = accent)
        }
    }
}

@Composable
fun rememberPreviewBitmap(previewStr: String, hwnd: Long): android.graphics.Bitmap? {
    var bitmap by remember(hwnd) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(previewStr) {
        if (previewStr.isNotEmpty()) {
            // Move heavy Base64 decode and bitmap conversion to the IO thread.
            val newBitmap = withContext(Dispatchers.IO) {
                val decodedString = Base64.decode(previewStr, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
            }
            if (newBitmap != null) { bitmap = newBitmap }
        }
    }
    return bitmap
}

@Composable
fun rememberIconBitmap(iconStr: String, hwnd: Long): android.graphics.Bitmap? {
    var bitmap by remember(hwnd) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(iconStr) {
        if (iconStr.isNotEmpty()) {
            // Same approach: run this on the IO thread.
            val newBitmap = withContext(Dispatchers.IO) {
                val decodedString = Base64.decode(iconStr, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
            }
            if (newBitmap != null) { bitmap = newBitmap }
        }
    }
    return bitmap
}

@Composable
fun rememberLiveBitmapFrame(frame: State<ByteArray?>, hwnd: Long): State<Bitmap?> {
    val bitmapState = remember(hwnd) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(hwnd) {
        var lastRenderedAt = 0L
        snapshotFlow { frame.value }.collectLatest { bytes ->
            if (bytes == null || bytes.isEmpty()) return@collectLatest
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastRenderedAt < 16L) return@collectLatest
            val decoded = withContext(Dispatchers.IO) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
            if (decoded != null) {
                lastRenderedAt = android.os.SystemClock.uptimeMillis()
                bitmapState.value = decoded
            }
        }
    }
    return bitmapState
}

fun lerp(start: Color, end: Color, fraction: Float): Color {
    return Color(
        red = start.red + (end.red - start.red) * fraction,
        green = start.green + (end.green - start.green) * fraction,
        blue = start.blue + (end.blue - start.blue) * fraction,
        alpha = start.alpha + (end.alpha - start.alpha) * fraction
    )
}

@Composable
fun LivePreviewContainer(
    frame: State<ByteArray?>,
    currentWindow: WindowInfo,
    accentColor: Color,
    containerColor: Color,
    clipPreview: Boolean,
    cornerPx: Int,
    decodedBitmap: Bitmap? = null,
    useHardwareEncoding: Boolean = false,
    h264VideoConfig: State<H264VideoConfig?>? = null,
    h264Error: State<String?>? = null,
    h264Frames: SharedFlow<ByteArray>? = null
) {
    if (useHardwareEncoding) {
        val error = h264Error?.value
        if (!error.isNullOrBlank()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(18.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }
            return
        }
        val config = h264VideoConfig?.value
        if (config != null && h264Frames != null) {
            H264PreviewContainer(
                config = config,
                frames = h264Frames,
                title = currentWindow.title,
                clipPreview = clipPreview,
                cornerPx = cornerPx
            )
            return
        }
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Starting H.264...", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f), fontSize = 12.sp)
        }
        return
    }

    val bitmap by if (decodedBitmap != null) {
        remember(decodedBitmap) { mutableStateOf(decodedBitmap) }
    } else {
        rememberLiveBitmapFrame(frame, currentWindow.hwnd)
    }
    val density = LocalDensity.current
    val previewShape = if (clipPreview) {
        RoundedCornerShape(with(density) { cornerPx.coerceAtLeast(0).toDp() })
    } else {
        RoundedCornerShape(0.dp)
    }

    val previewBitmap = bitmap
    if (previewBitmap != null) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            val imageAspect = previewBitmap.width.toFloat() / previewBitmap.height.toFloat().coerceAtLeast(1f)
            val containerAspect = maxWidth.value / maxHeight.value.coerceAtLeast(1f)
            val imageWidth = if (containerAspect > imageAspect) maxHeight * imageAspect else maxWidth
            val imageHeight = if (containerAspect > imageAspect) maxHeight else maxWidth / imageAspect

            Image(
                bitmap = previewBitmap.asImageBitmap(),
                contentDescription = currentWindow.title,
                modifier = Modifier
                    .width(imageWidth)
                    .height(imageHeight)
                    .clip(previewShape),
                contentScale = ContentScale.Crop
            )
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            IconContainer(
                bitmap = rememberIconBitmap(currentWindow.icon, currentWindow.hwnd),
                title = currentWindow.title,
                size = 140.dp,
                accent = accentColor,
                shape = RoundedCornerShape(24.dp)
            )
        }
    }
}

@Composable
fun H264PreviewContainer(
    config: H264VideoConfig,
    frames: SharedFlow<ByteArray>,
    title: String,
    clipPreview: Boolean,
    cornerPx: Int
) {
    var surface by remember { mutableStateOf<Surface?>(null) }
    val density = LocalDensity.current
    val previewShape = if (clipPreview) {
        RoundedCornerShape(with(density) { cornerPx.coerceAtLeast(0).toDp() })
    } else {
        RoundedCornerShape(0.dp)
    }

    LaunchedEffect(surface, config) {
        val targetSurface = surface ?: return@LaunchedEffect
        val parser = AnnexBNalParser()
        val bufferInfo = MediaCodec.BufferInfo()
        var codec: MediaCodec? = null
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        val pendingFrameNals = mutableListOf<ByteArray>()
        var ptsUs = 0L
        val frameDurationUs = 1_000_000L / config.fps.coerceAtLeast(1)
        var queuedUnits = 0
        var renderedUnits = 0
        var seenKeyFrame = false
        var observedNals = 0
        fun drainOutput() {
            val activeCodec = codec ?: return
            var outputIndex = activeCodec.dequeueOutputBuffer(bufferInfo, 0)
            while (outputIndex >= 0) {
                activeCodec.releaseOutputBuffer(outputIndex, true)
                renderedUnits++
                outputIndex = activeCodec.dequeueOutputBuffer(bufferInfo, 0)
            }
        }
        fun configureDecoderIfReady(): MediaCodec? {
            codec?.let { return it }
            val spsBytes = sps ?: return null
            val ppsBytes = pps ?: return null
            val format = MediaFormat.createVideoFormat("video/avc", config.width, config.height)
            runCatching { format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1) }
            runCatching { format.setInteger(MediaFormat.KEY_FRAME_RATE, config.fps.coerceAtLeast(1)) }
            format.setByteBuffer("csd-0", ByteBuffer.wrap(spsBytes))
            format.setByteBuffer("csd-1", ByteBuffer.wrap(ppsBytes))
            val newCodec = MediaCodec.createDecoderByType("video/avc")
            newCodec.configure(format, targetSurface, null, 0)
            newCodec.start()
            codec = newCodec
            Log.i("TaskbarH264", "decoder configured ${config.width}x${config.height} fps=${config.fps} sps=${spsBytes.size} pps=${ppsBytes.size}")
            return newCodec
        }
        fun queueAccessUnit(accessUnit: ByteArray, flags: Int) {
            val activeCodec = configureDecoderIfReady() ?: return
            drainOutput()
            var inputIndex = activeCodec.dequeueInputBuffer(5_000)
            var attempts = 0
            while (inputIndex < 0 && attempts < 4) {
                drainOutput()
                inputIndex = activeCodec.dequeueInputBuffer(5_000)
                attempts++
            }
            if (inputIndex < 0) return
            val inputBuffer = activeCodec.getInputBuffer(inputIndex)
            inputBuffer?.clear()
            if (inputBuffer != null && accessUnit.size <= inputBuffer.capacity()) {
                inputBuffer.put(accessUnit)
                activeCodec.queueInputBuffer(inputIndex, 0, accessUnit.size, ptsUs, flags)
                queuedUnits++
                ptsUs += frameDurationUs
                if (queuedUnits % 120 == 0) {
                    Log.i("TaskbarH264", "queued=$queuedUnits rendered=$renderedUnits bytes=${accessUnit.size} flags=$flags")
                }
            } else if (inputBuffer != null) {
                Log.w("TaskbarH264", "drop oversized access unit bytes=${accessUnit.size} capacity=${inputBuffer.capacity()}")
            }
        }
        fun flushPendingFrame() {
            if (pendingFrameNals.isEmpty()) return
            val isKeyFrame = pendingFrameNals.any { AnnexBNalParser.nalType(it) == 5 }
            val hasVcl = pendingFrameNals.any { AnnexBNalParser.nalType(it) == 1 || AnnexBNalParser.nalType(it) == 5 }
            if (!hasVcl) {
                pendingFrameNals.clear()
                return
            }
            if (isKeyFrame) seenKeyFrame = true
            if (!seenKeyFrame) {
                pendingFrameNals.clear()
                return
            }
            queueAccessUnit(joinNals(pendingFrameNals), if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            pendingFrameNals.clear()
        }
        try {
            frames.collect { chunk ->
                parser.push(chunk).forEach { nal ->
                    when (val type = AnnexBNalParser.nalType(nal)) {
                        -1 -> Unit
                        7 -> {
                            flushPendingFrame()
                            sps = nal
                            configureDecoderIfReady()
                        }
                        8 -> {
                            flushPendingFrame()
                            pps = nal
                            configureDecoderIfReady()
                        }
                        1, 5 -> {
                            if (pendingFrameNals.any { AnnexBNalParser.nalType(it) == 1 || AnnexBNalParser.nalType(it) == 5 }) {
                                flushPendingFrame()
                            }
                            pendingFrameNals.add(nal)
                            observedNals++
                            if (observedNals <= 24) {
                                Log.i("TaskbarH264", "nal type=$type bytes=${nal.size}")
                            }
                        }
                        6, 9 -> {
                            if (type == 9) {
                                flushPendingFrame()
                            }
                            pendingFrameNals.add(nal)
                            observedNals++
                            if (observedNals <= 24) {
                                Log.i("TaskbarH264", "nal type=$type bytes=${nal.size}")
                            }
                        }
                        else -> {
                            if (pendingFrameNals.isNotEmpty()) pendingFrameNals.add(nal)
                            observedNals++
                            if (observedNals <= 24) {
                                Log.i("TaskbarH264", "nal type=$type bytes=${nal.size}")
                            }
                        }
                    }
                    drainOutput()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("TaskbarH264", "decoder failed", e)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val imageAspect = config.width.toFloat() / config.height.toFloat().coerceAtLeast(1f)
        val containerAspect = maxWidth.value / maxHeight.value.coerceAtLeast(1f)
        val imageWidth = if (containerAspect > imageAspect) maxHeight * imageAspect else maxWidth
        val imageHeight = if (containerAspect > imageAspect) maxHeight else maxWidth / imageAspect

        AndroidView(
            factory = { context ->
                SurfaceView(context).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            surface = holder.surface
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            surface = holder.surface
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            surface = null
                        }
                    })
                }
            },
            modifier = Modifier
                .width(imageWidth)
                .height(imageHeight)
                .clip(previewShape),
            update = { it.contentDescription = title }
        )
    }
}

private class AnnexBNalParser {
    private var buffer = ByteArray(0)

    fun push(chunk: ByteArray): List<ByteArray> {
        if (chunk.isEmpty()) return emptyList()
        buffer += chunk
        val starts = findStartCodes(buffer)
        if (starts.size < 2) return emptyList()
        val out = ArrayList<ByteArray>()
        for (i in 0 until starts.lastIndex) {
            val start = starts[i]
            val end = starts[i + 1]
            if (end > start) {
                out.add(buffer.copyOfRange(start, end))
            }
        }
        buffer = buffer.copyOfRange(starts.last(), buffer.size)
        return out
    }

    companion object {
        fun nalType(nal: ByteArray): Int {
            val offset = startCodeLength(nal)
            return if (offset in nal.indices) nal[offset].toInt() and 0x1f else -1
        }

        private fun startCodeLength(nal: ByteArray): Int =
            if (nal.size >= 4 && nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 0.toByte() && nal[3] == 1.toByte()) 4
            else if (nal.size >= 3 && nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 1.toByte()) 3
            else 0
    }

    private fun findStartCodes(data: ByteArray): List<Int> {
        val result = ArrayList<Int>()
        var i = 0
        while (i < data.size - 3) {
            val threeByte = data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()
            val fourByte = i < data.size - 4 && data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            if (threeByte || fourByte) {
                result.add(i)
                i += if (fourByte) 4 else 3
            } else {
                i++
            }
        }
        return result
    }
}

private fun joinNals(nals: List<ByteArray>): ByteArray {
    val size = nals.sumOf { it.size }
    val joined = ByteArray(size)
    var offset = 0
    nals.forEach { nal ->
        nal.copyInto(joined, offset)
        offset += nal.size
    }
    return joined
}
