package com.ice.tskbsync

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.websocket.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import kotlin.math.roundToLong

class TaskbarViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsManager = SettingsManager(application)
    private var connectionJob: Job? = null
    private var liveStreamJob: Job? = null
    private var inputJob: Job? = null

    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(WebSockets) {
            contentConverter = KotlinxWebsocketSerializationConverter(Json)
            pingIntervalMillis = 10000
        }
        install(HttpTimeout) { requestTimeoutMillis = 5000 }
    }

    private val _windows = mutableStateOf<List<WindowInfo>>(emptyList())
    val windows: State<List<WindowInfo>> = _windows

    private val _isConnected = mutableStateOf(false)
    val isConnected: State<Boolean> = _isConnected

    private val _error = mutableStateOf<String?>(null)
    val error: State<String?> = _error

    private val _pcIp = mutableStateOf("")
    val pcIp: State<String> = _pcIp

    private val _password = mutableStateOf("")
    val password: State<String> = _password

    private val _theme = mutableStateOf(ThemeSettings(0xFF6200EE.toInt(), "", true, 0.5f, 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0.7f, 0, 0f, 0.85f, false, false, 720, 72, 30, 2000, false, 18))
    val theme: State<ThemeSettings> = _theme

    private val _layoutMode = mutableStateOf("grid")
    val layoutMode: State<String> = _layoutMode

    private val _gridColumns = mutableStateOf(3)
    val gridColumns: State<Int> = _gridColumns

    private val _showTitles = mutableStateOf(true)
    val showTitles: State<Boolean> = _showTitles

    private val _lastSyncTime = mutableStateOf(0L)
    val lastSyncTime: State<Long> = _lastSyncTime

    // Decoupled Previews
    private val _focusedLiveFrame = mutableStateOf<ByteArray?>(null)
    val focusedLiveFrame: State<ByteArray?> = _focusedLiveFrame

    private val _selectedRowHwnd = mutableStateOf<Long?>(null)
    val selectedRowHwnd: State<Long?> = _selectedRowHwnd

    private val _shortcuts = mutableStateOf(defaultShortcutConfigs)
    val shortcuts: State<List<ShortcutConfig>> = _shortcuts

    private val _windowFilter = mutableStateOf(WindowFilterSettings())
    val windowFilter: State<WindowFilterSettings> = _windowFilter

    private val _inputStatus = mutableStateOf<String?>(null)
    val inputStatus: State<String?> = _inputStatus

    private var inputWsSession: DefaultClientWebSocketSession? = null
    private var remoteInputTarget: String? = null

    val gridPreviewCache = mutableStateMapOf<Long, String>()
    private val _screens = mutableStateOf<List<ScreenInfo>>(emptyList())
    val screens: State<List<ScreenInfo>> = _screens

    private var activeWsSession: DefaultClientWebSocketSession? = null
    val discoveredServers = mutableStateListOf<String>()
    private var isDiscovering = false
    private val reconnectBaseDelayMs = 1500L
    private val reconnectMaxDelayMs = 8000L

    init {
        viewModelScope.launch {
            settingsManager.pcIp.collectLatest { ip ->
                _pcIp.value = ip
                if (ip.isNotEmpty() && connectionJob?.isActive != true) {
                    connect(ip)
                }
            }
        }
        viewModelScope.launch { settingsManager.password.collectLatest { _password.value = it } }
        viewModelScope.launch { settingsManager.themeSettings.collectLatest { _theme.value = it } }
        viewModelScope.launch { settingsManager.layoutMode.collectLatest { _layoutMode.value = it } }
        viewModelScope.launch { settingsManager.gridColumns.collectLatest { _gridColumns.value = it } }
        viewModelScope.launch { settingsManager.showTitles.collectLatest { _showTitles.value = it } }
        viewModelScope.launch { settingsManager.shortcuts.collectLatest { _shortcuts.value = it } }
        viewModelScope.launch { settingsManager.windowFilter.collectLatest { _windowFilter.value = it } }
    }

    fun connect(ip: String) {
        connectionJob?.cancel()
        _error.value = null
        val pass = _password.value
        connectionJob = viewModelScope.launch {
            var reconnectDelay = reconnectBaseDelayMs
            while (isActive) {
                try {
                    applyBackendStreamConfig(_theme.value, ip)
                    applyGridPreviewConfig(_theme.value, ip)
                    applyWindowFilterConfig(_windowFilter.value, ip)
                    client.webSocket("ws://$ip:8000/ws") {
                        activeWsSession = this
                        send(Frame.Text(pass))
                        _isConnected.value = true
                        _error.value = null
                        reconnectDelay = reconnectBaseDelayMs

                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val text = frame.readText()
                                if (text.startsWith("error:")) {
                                    _error.value = text.substringAfter("error: ")
                                    break
                                }
                                if (text == "pong") continue

                                try {
                                    val json = Json.parseToJsonElement(text).jsonObject
                                    when (json["type"]?.jsonPrimitive?.content) {
                                        "list" -> {
                                            val list = Json.decodeFromJsonElement<List<WindowInfo>>(json["data"]!!)
                                            _windows.value = list
                                            _lastSyncTime.value = System.currentTimeMillis()
                                        }
                                        "grid_previews" -> {
                                            val data = json["data"]?.jsonObject ?: return@webSocket
                                            data.forEach { (hwnd, b64) ->
                                                gridPreviewCache[hwnd.toLong()] = b64.jsonPrimitive.content
                                            }
                                            _lastSyncTime.value = System.currentTimeMillis()
                                        }
                                    }
                                } catch (e: Exception) { }
                            }
                        }
                    }
                } catch (e: Exception) {
                    _error.value = "Connection failed: ${e.localizedMessage}"
                } finally {
                    _isConnected.value = false
                    _windows.value = emptyList()
                    activeWsSession = null
                }

                if (!isActive) break
                delay(reconnectDelay)
                reconnectDelay = (reconnectDelay * 2).coerceAtMost(reconnectMaxDelayMs)
            }
        }
    }

    fun startLiveStream(hwnd: Long) {
        _selectedRowHwnd.value = hwnd
        liveStreamJob?.cancel()
        _focusedLiveFrame.value = null
        val ip = _pcIp.value
        if (ip.isEmpty() || hwnd == 0L) return

        liveStreamJob = viewModelScope.launch {
            try {
                val streamSettings = _theme.value
                applyBackendStreamConfig(streamSettings, ip)
                val liveUrl = "ws://$ip:8000/live/$hwnd" +
                    "?max_dim=${streamSettings.streamMaxDim}" +
                    "&quality=${streamSettings.streamQuality}" +
                    "&fps=${streamSettings.streamFps}"
                Log.i("TaskbarLive", "Connecting live stream: $liveUrl")
                var receivedFrames = 0
                var lastPublishedAt = 0L
                val publishIntervalMs = (1000f / streamSettings.streamFps.coerceIn(1, 120)).roundToLong().coerceAtLeast(1L)
                client.webSocket(liveUrl) {
                    Log.i("TaskbarLive", "Live stream connected: hwnd=$hwnd")
                    for (frame in incoming) {
                        when (frame) {
                            is Frame.Binary -> {
                                val bytes = frame.readBytes()
                                receivedFrames++
                                if (receivedFrames == 1 || receivedFrames % 60 == 0) {
                                    Log.i("TaskbarLive", "Received binary frame count=$receivedFrames bytes=${bytes.size}")
                                }
                                val now = android.os.SystemClock.uptimeMillis()
                                if (now - lastPublishedAt >= publishIntervalMs) {
                                    lastPublishedAt = now
                                    _focusedLiveFrame.value = bytes
                                }
                            }
                            is Frame.Text -> {
                                val text = frame.readText()
                                Log.i("TaskbarLive", "Received text frame: ${text.take(120)}")
                                if (text.isNotEmpty() && !text.startsWith("status:") && !text.startsWith("error:")) {
                                    _focusedLiveFrame.value = android.util.Base64.decode(text, android.util.Base64.DEFAULT)
                                }
                            }
                            else -> Unit
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("TaskbarViewModel", "Live stream failed", e)
            }
        }
    }

    fun stopLiveStream() {
        liveStreamJob?.cancel()
        _focusedLiveFrame.value = null
    }

    fun startRemoteInput(hwnd: Long) {
        inputJob?.cancel()
        inputWsSession = null
        _inputStatus.value = null
        val ip = _pcIp.value
        val pass = _password.value
        if (ip.isEmpty() || hwnd == 0L) return
        inputJob = viewModelScope.launch {
            try {
                client.webSocket("ws://$ip:8000/input/$hwnd") {
                    inputWsSession = this
                    remoteInputTarget = "window:$hwnd"
                    send(Frame.Text(pass))
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            Log.i("TaskbarInput", text)
                            val message = runCatching {
                                val json = Json.parseToJsonElement(text).jsonObject
                                val type = json["type"]?.jsonPrimitive?.content
                                val value = json["message"]?.jsonPrimitive?.content ?: text
                                if (type == "error") "Input error: $value" else null
                            }.getOrNull()
                            if (message != null) _inputStatus.value = message
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("TaskbarInput", "Remote input failed", e)
                _inputStatus.value = "Input connection failed: ${e.message ?: e::class.java.simpleName}"
            } finally {
                inputWsSession = null
                remoteInputTarget = null
            }
        }
    }

    fun startRemoteScreenInput(monitorIndex: Int) {
        inputJob?.cancel()
        inputWsSession = null
        _inputStatus.value = null
        val ip = _pcIp.value
        val pass = _password.value
        if (ip.isEmpty() || monitorIndex <= 0) return
        inputJob = viewModelScope.launch {
            try {
                client.webSocket("ws://$ip:8000/input/screen/$monitorIndex") {
                    inputWsSession = this
                    remoteInputTarget = "screen:$monitorIndex"
                    send(Frame.Text(pass))
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            Log.i("TaskbarInput", text)
                            val message = runCatching {
                                val json = Json.parseToJsonElement(text).jsonObject
                                val type = json["type"]?.jsonPrimitive?.content
                                val value = json["message"]?.jsonPrimitive?.content ?: text
                                if (type == "error") "Input error: $value" else null
                            }.getOrNull()
                            if (message != null) _inputStatus.value = message
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("TaskbarInput", "Screen input failed", e)
                _inputStatus.value = "Input connection failed: ${e.message ?: e::class.java.simpleName}"
            } finally {
                inputWsSession = null
                remoteInputTarget = null
            }
        }
    }

    fun stopRemoteInput() {
        inputJob?.cancel()
        inputWsSession = null
        remoteInputTarget = null
    }

    fun clearInputStatus() {
        _inputStatus.value = null
    }

    fun sendRemoteInput(payload: JsonObject) {
        viewModelScope.launch {
            try {
                val session = inputWsSession
                if (session == null) {
                    _inputStatus.value = "Input not connected"
                    return@launch
                }
                session.send(Frame.Text(payload.toString()))
            } catch (e: Exception) {
                Log.e("TaskbarInput", "Send input failed", e)
                _inputStatus.value = "Input send failed: ${e.message ?: e::class.java.simpleName}"
            }
        }
    }

    fun sendMouseInput(action: String, x: Float, y: Float) {
        sendRemoteInput(buildJsonObject {
            put("type", "mouse")
            put("action", action)
            put("x", x.coerceIn(0f, 1f))
            put("y", y.coerceIn(0f, 1f))
        })
    }

    fun sendMouseButtonClick(button: String) {
        sendRemoteInput(buildJsonObject {
            put("type", "mouse")
            put("action", "click")
            put("button", button)
            put("x", 0.5f)
            put("y", 0.5f)
        })
    }

    fun sendMouseWheel(delta: Int) {
        sendRemoteInput(buildJsonObject {
            put("type", "mouse")
            put("action", "wheel")
            put("delta", delta)
            put("x", 0.5f)
            put("y", 0.5f)
        })
    }

    fun sendTouchInput(action: String, x: Float, y: Float) {
        sendRemoteInput(buildJsonObject {
            put("type", "touch")
            put("action", action)
            put("x", x.coerceIn(0f, 1f))
            put("y", y.coerceIn(0f, 1f))
        })
    }

    fun sendTextInput(text: String) {
        if (text.isEmpty()) return
        sendRemoteInput(buildJsonObject {
            put("type", "text")
            put("text", text)
        })
    }

    fun sendKeyInput(key: String) {
        sendRemoteInput(buildJsonObject {
            put("type", "key")
            put("key", key)
        })
    }

    fun sendShortcut(shortcut: ShortcutConfig) {
        sendRemoteInput(buildJsonObject {
            put("type", "shortcut")
            putJsonArray("keys") {
                shortcut.keys.forEach { add(it) }
            }
        })
    }

    fun saveShortcuts(shortcuts: List<ShortcutConfig>) {
        viewModelScope.launch { settingsManager.saveShortcuts(shortcuts) }
    }

    fun restoreDefaultShortcuts() {
        saveShortcuts(defaultShortcutConfigs)
    }

    fun updateWindowFilter(settings: WindowFilterSettings) {
        viewModelScope.launch {
            settingsManager.saveWindowFilter(settings)
            applyWindowFilterConfig(settings)
        }
    }

    fun rememberRowWindow(hwnd: Long) {
        _selectedRowHwnd.value = hwnd
    }

    fun observeWindow(hwnd: Long) {
        // Only for server-side focus hints if needed, but startLiveStream is the real trigger now
        viewModelScope.launch {
            try {
                activeWsSession?.send(Frame.Text("observe:$hwnd"))
            } catch (e: Exception) { }
        }
    }

    fun updateSettings(ip: String, pass: String) {
        viewModelScope.launch { settingsManager.savePcIp(ip); settingsManager.savePassword(pass) }
    }

    fun updateDisplaySettings(cols: Int, show: Boolean) {
        viewModelScope.launch { settingsManager.saveGridColumns(cols); settingsManager.saveShowTitles(show) }
    }

    fun updateTheme(newTheme: ThemeSettings) {
        val oldTheme = _theme.value
        if (
            newTheme.streamMaxDim != oldTheme.streamMaxDim ||
            newTheme.streamQuality != oldTheme.streamQuality ||
            newTheme.streamFps != oldTheme.streamFps
        ) {
            viewModelScope.launch { applyBackendStreamConfig(newTheme) }
        }
        if (newTheme.gridPreviewIntervalMs != oldTheme.gridPreviewIntervalMs) {
            viewModelScope.launch { applyGridPreviewConfig(newTheme) }
        }
        viewModelScope.launch { settingsManager.saveTheme(newTheme) }
    }

    private suspend fun applyBackendStreamConfig(settings: ThemeSettings, ipOverride: String? = null) {
        val ip = ipOverride ?: _pcIp.value
        if (ip.isEmpty()) return
        try {
            client.post("http://$ip:8000/config/wgc") {
                parameter("enabled", true)
                parameter("max_dim", settings.streamMaxDim)
                parameter("quality", settings.streamQuality)
                parameter("fps", settings.streamFps)
            }
        } catch (e: Exception) { }
    }

    private suspend fun applyGridPreviewConfig(settings: ThemeSettings, ipOverride: String? = null) {
        val ip = ipOverride ?: _pcIp.value
        if (ip.isEmpty()) return
        try {
            client.post("http://$ip:8000/config/grid_preview") {
                parameter("interval_ms", settings.gridPreviewIntervalMs)
            }
        } catch (e: Exception) { }
    }

    private suspend fun applyWindowFilterConfig(settings: WindowFilterSettings, ipOverride: String? = null) {
        val ip = ipOverride ?: _pcIp.value
        if (ip.isEmpty()) return
        try {
            client.post("http://$ip:8000/config/window_filter") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("enabled", settings.enabled)
                    put("hide_system_windows", settings.hideSystemWindows)
                    putJsonArray("title_contains") { settings.titleContains.forEach { add(it) } }
                    putJsonArray("process_names") { settings.processNames.forEach { add(it) } }
                    putJsonArray("class_names") { settings.classNames.forEach { add(it) } }
                }.toString())
            }
        } catch (e: Exception) { }
    }

    fun startLiveScreenStream(monitorIndex: Int) {
        liveStreamJob?.cancel()
        _focusedLiveFrame.value = null
        val ip = _pcIp.value
        if (ip.isEmpty() || monitorIndex <= 0) return
        liveStreamJob = viewModelScope.launch {
            try {
                val streamSettings = _theme.value
                applyBackendStreamConfig(streamSettings, ip)
                val liveUrl = "ws://$ip:8000/live/screen/$monitorIndex" +
                    "?max_dim=${streamSettings.streamMaxDim}" +
                    "&quality=${streamSettings.streamQuality}" +
                    "&fps=${streamSettings.streamFps}"
                Log.i("TaskbarLive", "Connecting screen stream: $liveUrl")
                var lastPublishedAt = 0L
                val publishIntervalMs = (1000f / streamSettings.streamFps.coerceIn(1, 120)).roundToLong().coerceAtLeast(1L)
                client.webSocket(liveUrl) {
                    for (frame in incoming) {
                        if (frame is Frame.Binary) {
                            val bytes = frame.readBytes()
                            val now = android.os.SystemClock.uptimeMillis()
                            if (now - lastPublishedAt >= publishIntervalMs) {
                                lastPublishedAt = now
                                _focusedLiveFrame.value = bytes
                            }
                        } else if (frame is Frame.Text) {
                            Log.i("TaskbarLive", frame.readText())
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("TaskbarViewModel", "Screen stream failed", e)
            }
        }
    }

    fun fetchScreens() {
        viewModelScope.launch {
            val ip = _pcIp.value
            if (ip.isEmpty()) return@launch
            try {
                val res: List<ScreenInfo> = client.get("http://$ip:8000/screens").body()
                _screens.value = res
                Log.i("TaskbarScreens", "Fetched screens: ${res.size}")
            } catch (e: Exception) {
                Log.e("TaskbarViewModel", "Fetch screens failed", e)
                _inputStatus.value = "Failed to fetch screen list: ${e.localizedMessage}"
            }
        }
    }

    fun saveWallpaper(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val path = settingsManager.copyImageToInternal(uri)
            if (path.isNotEmpty()) { updateTheme(_theme.value.copy(bgImagePath = path, showWallpaper = true)) }
        }
    }

    fun toggleLayout() {
        val newMode = if (_layoutMode.value == "grid") "row" else "grid"
        viewModelScope.launch { settingsManager.saveLayoutMode(newMode) }
    }

    fun discoverServers() {
        if (isDiscovering) return
        isDiscovering = true
        discoveredServers.clear()
        viewModelScope.launch(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(8001); socket.soTimeout = 2500
                val buffer = ByteArray(1024); val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < 3000) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                        val senderIp = packet.address.hostAddress ?: continue
                        if (!discoveredServers.contains(senderIp)) { discoveredServers.add(senderIp) }
                    } catch (e: Exception) { }
                }
            } catch (e: Exception) { }
            finally { socket?.close(); isDiscovering = false }
        }
    }

    fun switchWindow(hwnd: Long) {
        viewModelScope.launch {
            try { client.post("http://${_pcIp.value}:8000/switch/$hwnd") { header("password", _password.value) } } catch (e: Exception) { }
        }
    }

    fun controlWindow(hwnd: Long, action: String, monitorIndex: Int? = null) {
        viewModelScope.launch {
            val ip = _pcIp.value
            if (ip.isEmpty() || hwnd == 0L) return@launch
            try {
                client.post("http://$ip:8000/window/$hwnd/control") {
                    header("password", _password.value)
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject {
                        put("action", action)
                        if (monitorIndex != null) put("monitor_index", monitorIndex)
                    }.toString())
                }
            } catch (e: Exception) {
                Log.e("TaskbarViewModel", "Window control failed", e)
                _inputStatus.value = "Window control failed: ${e.localizedMessage}"
            }
        }
    }

    override fun onCleared() {
        connectionJob?.cancel()
        liveStreamJob?.cancel()
        inputJob?.cancel()
        super.onCleared()
        client.close()
    }
}
