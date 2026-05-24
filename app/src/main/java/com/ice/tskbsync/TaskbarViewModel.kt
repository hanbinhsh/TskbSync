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
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
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

class TaskbarViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsManager = SettingsManager(application)
    private var connectionJob: Job? = null
    private var liveStreamJob: Job? = null

    private val client = HttpClient {
        install(ContentNegotiation) { json() }
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

    private val _theme = mutableStateOf(ThemeSettings(0xFF6200EE.toInt(), "", 0.5f, 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0.7f, 0, 0f, 0.85f, false, false, false, 720, 72, 30))
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

    val gridPreviewCache = mutableStateMapOf<Long, String>()

    private var activeWsSession: DefaultClientWebSocketSession? = null
    val discoveredServers = mutableStateListOf<String>()
    private var isDiscovering = false

    init {
        viewModelScope.launch { settingsManager.pcIp.collectLatest { _pcIp.value = it } }
        viewModelScope.launch { settingsManager.password.collectLatest { _password.value = it } }
        viewModelScope.launch { settingsManager.themeSettings.collectLatest { _theme.value = it } }
        viewModelScope.launch { settingsManager.layoutMode.collectLatest { _layoutMode.value = it } }
        viewModelScope.launch { settingsManager.gridColumns.collectLatest { _gridColumns.value = it } }
        viewModelScope.launch { settingsManager.showTitles.collectLatest { _showTitles.value = it } }

        viewModelScope.launch {
            delay(1500)
            if (_pcIp.value.isNotEmpty()) connect(_pcIp.value)
        }
    }

    fun connect(ip: String) {
        connectionJob?.cancel()
        _error.value = null
        val pass = _password.value
        connectionJob = viewModelScope.launch {
            try {
                applyBackendStreamConfig(_theme.value, ip)
                client.webSocket("ws://$ip:8000/ws") {
                    activeWsSession = this
                    send(Frame.Text(pass))
                    _isConnected.value = true

                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            if (text.startsWith("error:")) {
                                _error.value = text.substringAfter("error: "); break
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
                    "?use_wgc=${streamSettings.useWgc}" +
                    "&max_dim=${streamSettings.streamMaxDim}" +
                    "&quality=${streamSettings.streamQuality}" +
                    "&fps=${streamSettings.streamFps}"
                Log.i("TaskbarLive", "Connecting live stream: $liveUrl")
                var receivedFrames = 0
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
                                _focusedLiveFrame.value = bytes
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
            newTheme.useWgc != oldTheme.useWgc ||
            newTheme.streamMaxDim != oldTheme.streamMaxDim ||
            newTheme.streamQuality != oldTheme.streamQuality ||
            newTheme.streamFps != oldTheme.streamFps
        ) {
            viewModelScope.launch { applyBackendStreamConfig(newTheme) }
        }
        viewModelScope.launch { settingsManager.saveTheme(newTheme) }
    }

    private suspend fun applyBackendStreamConfig(settings: ThemeSettings, ipOverride: String? = null) {
        val ip = ipOverride ?: _pcIp.value
        if (ip.isEmpty()) return
        try {
            client.post("http://$ip:8000/config/wgc") {
                parameter("enabled", settings.useWgc)
                parameter("max_dim", settings.streamMaxDim)
                parameter("quality", settings.streamQuality)
                parameter("fps", settings.streamFps)
            }
        } catch (e: Exception) { }
    }

    fun saveWallpaper(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val path = settingsManager.copyImageToInternal(uri)
            if (path.isNotEmpty()) { updateTheme(_theme.value.copy(bgImagePath = path)) }
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

    override fun onCleared() { super.onCleared(); client.close() }
}
