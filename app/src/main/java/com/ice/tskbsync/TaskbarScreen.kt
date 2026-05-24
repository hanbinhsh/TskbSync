package com.ice.tskbsync

import android.graphics.BitmapFactory
import android.util.Log
import android.util.Base64
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.snapshotFlow
import android.graphics.Bitmap

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

    val accentColor = Color(theme.color)
    val titleColor = Color(theme.titleColor)
    val containerBgBase = Color(theme.containerColor)
    val topBarColor = Color(theme.topBarColor).copy(alpha = theme.topBarAlpha)
    val bgColor = lerp(MaterialTheme.colorScheme.surface, accentColor, 0.1f)
    var isLandscapeSingleMode by remember { mutableStateOf(false) }
    val contentMode = if (isLandscapeSingleMode) "landscape" else layoutMode

    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = accentColor, onSurface = titleColor)) {
        Scaffold(
            containerColor = bgColor,
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("TskbSync", fontWeight = FontWeight.Bold, color = titleColor)
                            if (isConnected && lastSyncTime > 0) {
                                Text(
                                    "Synced: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(lastSyncTime))}",
                                    style = MaterialTheme.typography.labelSmall, color = titleColor.copy(alpha = 0.6f)
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { isLandscapeSingleMode = !isLandscapeSingleMode }) {
                            Icon(
                                if (isLandscapeSingleMode) Icons.Default.StayCurrentPortrait else Icons.Default.StayCurrentLandscape,
                                null,
                                tint = titleColor
                            )
                        }
                        IconButton(onClick = { viewModel.toggleLayout() }) {
                            Icon(if (layoutMode == "grid") Icons.Default.ViewStream else Icons.Default.GridView, null, tint = titleColor)
                        }
                        IconButton(onClick = { navController.navigate("settings") }) {
                            Icon(Icons.Default.Settings, null, tint = titleColor)
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = topBarColor)
                )
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize()) {
                if (theme.bgImagePath.isNotEmpty()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current).data(theme.bgImagePath).crossfade(true).build(),
                        contentDescription = null, modifier = Modifier.fillMaxSize().alpha(theme.bgAlpha), contentScale = ContentScale.Crop
                    )
                }

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

                                "landscape" -> {
                                    LandscapeSingleMode(
                                        windows = windows,
                                        titleColor = titleColor,
                                        accentColor = accentColor,
                                        containerColor = containerBgBase,
                                        containerAlpha = theme.rowContainerAlpha,
                                        showTitles = showTitles,
                                        showPreviews = theme.showPreviews,
                                        focusedLiveFrame = viewModel.focusedLiveFrame,
                                        selectedRowHwnd = viewModel.selectedRowHwnd.value,
                                        useWgc = theme.useWgc,
                                        onSwitch = { viewModel.switchWindow(it) },
                                        onRowPageChange = { viewModel.rememberRowWindow(it) },
                                        onCenterChange = { hwnd ->
                                            if (hwnd != null) viewModel.startLiveStream(hwnd)
                                            else viewModel.stopLiveStream()
                                        }
                                    )
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
                                        focusedLiveFrame = viewModel.focusedLiveFrame,
                                        selectedRowHwnd = viewModel.selectedRowHwnd.value,
                                        useWgc = theme.useWgc,
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

@Composable
fun CarouselPagerMode(
    windows: List<WindowInfo>,
    showTitles: Boolean,
    titleColor: Color,
    accentColor: Color,
    containerColor: Color,
    containerAlpha: Float,
    showPreviews: Boolean,
    focusedLiveFrame: State<ByteArray?>,
    selectedRowHwnd: Long?,
    useWgc: Boolean,
    // 删除了这里的 maxWidth 参数
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

    LaunchedEffect(pagerState.currentPage, useWgc) {
        val hwnd = windows.getOrNull(pagerState.currentPage)?.hwnd
        if (hwnd != null) onRowPageChange(hwnd)
        onCenterChange(hwnd)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            val currentWindow = windows.getOrNull(pagerState.currentPage)
            if (currentWindow != null && showPreviews) {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = containerColor.copy(alpha = containerAlpha.coerceAtLeast(0.4f))),
                    elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
                    modifier = Modifier.fillMaxHeight().aspectRatio(0.75f).clickable { onSwitch(currentWindow.hwnd) }
                ) {
                    Box(modifier = Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
                        LivePreviewContainer(
                            frame = focusedLiveFrame,
                            currentWindow = currentWindow,
                            accentColor = accentColor
                        )
                    }
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
            // 由于外部的参数已被删除，这里的 maxWidth 将 100% 正确指向当前 Box 的剩余实际可用宽度！
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
    focusedLiveFrame: State<ByteArray?>,
    selectedRowHwnd: Long?,
    useWgc: Boolean,
    onSwitch: (Long) -> Unit,
    onRowPageChange: (Long) -> Unit,
    onCenterChange: (Long?) -> Unit
) {
    if (windows.isEmpty()) return

    val currentWindow = windows.firstOrNull { it.hwnd == selectedRowHwnd }
        ?: windows.firstOrNull { it.is_active }
        ?: windows.first()

    LaunchedEffect(currentWindow.hwnd, useWgc) {
        onRowPageChange(currentWindow.hwnd)
        onCenterChange(currentWindow.hwnd)
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val portraitWidth = maxWidth
        val portraitHeight = maxHeight
        val taskbarWidth = 92.dp

        Row(
            modifier = Modifier
                .requiredWidth(portraitHeight)
                .requiredHeight(portraitWidth)
                .rotate(90f)
                .padding(start = 14.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(end = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = containerColor.copy(alpha = containerAlpha.coerceAtLeast(0.4f))),
                    elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .clickable { onSwitch(currentWindow.hwnd) }
                ) {
                    Box(modifier = Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.Center) {
                        if (showPreviews) {
                            LivePreviewContainer(
                                frame = focusedLiveFrame,
                                currentWindow = currentWindow,
                                accentColor = accentColor
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
                    }
                }
            }

            Column(
                modifier = Modifier
                    .width(taskbarWidth)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(20.dp))
                    .background(containerColor.copy(alpha = containerAlpha))
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp, horizontal = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                windows.forEach { window ->
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
            // 将耗时的 Base64 解码和 Bitmap 转换放入 IO 线程！
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
            // 同理，放入 IO 线程
            val newBitmap = withContext(Dispatchers.IO) {
                val decodedString = Base64.decode(iconStr, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
            }
            if (newBitmap != null) { bitmap = newBitmap }
        }
    }
    return bitmap
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
    accentColor: Color
) {
    var bitmap by remember(currentWindow.hwnd) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(currentWindow.hwnd) {
        snapshotFlow { frame.value }.collect { bytes ->
            if (bytes == null || bytes.isEmpty()) return@collect

            val decoded = withContext(Dispatchers.IO) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }

            if (decoded != null) {
                Log.i("TaskbarLive", "Decoded preview bitmap ${decoded.width}x${decoded.height}")
                bitmap = decoded
            } else {
                Log.w("TaskbarLive", "Failed to decode preview bytes=${bytes.size}")
            }
        }
    }

    val previewBitmap = bitmap
    if (previewBitmap != null) {
        Image(
            bitmap = previewBitmap.asImageBitmap(),
            contentDescription = currentWindow.title,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Fit
        )
    } else {
        IconContainer(
            bitmap = rememberIconBitmap(currentWindow.icon, currentWindow.hwnd),
            title = currentWindow.title,
            size = 140.dp,
            accent = accentColor,
            shape = RoundedCornerShape(24.dp)
        )
    }
}
