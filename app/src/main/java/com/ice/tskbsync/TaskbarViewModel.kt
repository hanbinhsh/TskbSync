package com.ice.tskbsync

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.roundToLong

data class H264VideoConfig(val width: Int, val height: Int, val fps: Int = 60)

@kotlinx.serialization.Serializable
data class StartMenuAppInfo(
    val label: String,
    val path: String,
    val target: String = ""
)

data class TouchPointInput(
    val id: Int,
    val action: String,
    val x: Float,
    val y: Float,
    val primary: Boolean = false
)

private data class AudioPacket(
    val ptsUs: Long,
    val seq: Long,
    val payload: ByteArray
)

class TaskbarViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsManager = SettingsManager(application)
    private val clientId: String =
        Settings.Secure.getString(application.contentResolver, Settings.Secure.ANDROID_ID)
            ?: UUID.randomUUID().toString()
    private var connectionJob: Job? = null
    private var liveStreamJob: Job? = null
    private var inputJob: Job? = null
    private var audioJob: Job? = null
    private var audioSessionKey: String? = null
    private val audioTrackLock = Any()
    private var activeAudioTrack: AudioTrack? = null
    private var audioGeneration = 0

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

    private val _theme = mutableStateOf(ThemeSettings(0xFF6200EE.toInt(), "", true, 0.5f, 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0.7f, 0, 0f, 0.85f, false, false, 720, 72, 30, false, false, false, 0, 60, true, true, 0xFF202124.toInt(), 0.87f, 56, true, 2000, false, 18, true, true, true, true, false, 1.8f))
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
    private val _h264VideoConfig = mutableStateOf<H264VideoConfig?>(null)
    val h264VideoConfig: State<H264VideoConfig?> = _h264VideoConfig
    private val _h264Error = mutableStateOf<String?>(null)
    val h264Error: State<String?> = _h264Error
    private val _h264Status = mutableStateOf<H264StatusInfo?>(null)
    val h264Status: State<H264StatusInfo?> = _h264Status
    private val _audioStatus = mutableStateOf<String?>(null)
    val audioStatus: State<String?> = _audioStatus

    private val _startMenuApps = mutableStateOf<List<StartMenuAppInfo>>(emptyList())
    val startMenuApps: State<List<StartMenuAppInfo>> = _startMenuApps
    private val _startMenuAppsLoading = mutableStateOf(false)
    val startMenuAppsLoading: State<Boolean> = _startMenuAppsLoading
    private val _h264Frames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val h264Frames: SharedFlow<ByteArray> = _h264Frames

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
    private var lastMultiTouchSentAt = 0L
    private var pendingMultiTouchPoints: List<TouchPointInput>? = null
    private var pendingMultiTouchJob: Job? = null
    private val multiTouchSendIntervalMs = 8L

    val gridPreviewCache = mutableStateMapOf<Long, String>()
    private val _screens = mutableStateOf<List<ScreenInfo>>(emptyList())
    val screens: State<List<ScreenInfo>> = _screens
    private val _extendedDisplayStatus = mutableStateOf<ExtendedDisplayStatus?>(null)
    val extendedDisplayStatus: State<ExtendedDisplayStatus?> = _extendedDisplayStatus
    private val _extendedDisplayConnecting = mutableStateOf(false)
    val extendedDisplayConnecting: State<Boolean> = _extendedDisplayConnecting
    private val _extendedDisplayDriverChanging = mutableStateOf(false)
    val extendedDisplayDriverChanging: State<Boolean> = _extendedDisplayDriverChanging
    private var activeScreenStreamIndex: Int? = null
    private var suppressScreenGoneErrorsUntil = 0L

    private var activeWsSession: DefaultClientWebSocketSession? = null
    private var wantsGridPreviews = false
    val discoveredServers = mutableStateListOf<String>()
    private var isDiscovering = false
    private val reconnectBaseDelayMs = 1500L
    private val reconnectMaxDelayMs = 8000L

    init {
        viewModelScope.launch {
            combine(settingsManager.pcIp, settingsManager.password) { ip, password -> ip to password }
                .collectLatest { (ip, password) ->
                _pcIp.value = ip
                _password.value = password
                if (ip.isNotEmpty() && connectionJob?.isActive != true) {
                    connect(ip)
                }
            }
        }
        viewModelScope.launch {
            settingsManager.themeSettings.collectLatest { settings ->
                _theme.value = settings
                if (settings.enableAudio && _isConnected.value) {
                    startAudioStreamIfEnabled(settings)
                } else if (!settings.enableAudio) {
                    stopAudioStream()
                }
            }
        }
        viewModelScope.launch { settingsManager.layoutMode.collectLatest { _layoutMode.value = it } }
        viewModelScope.launch { settingsManager.gridColumns.collectLatest { _gridColumns.value = it } }
        viewModelScope.launch { settingsManager.showTitles.collectLatest { _showTitles.value = it } }
        viewModelScope.launch { settingsManager.shortcuts.collectLatest { _shortcuts.value = it } }
        viewModelScope.launch { settingsManager.windowFilter.collectLatest { _windowFilter.value = it } }
    }

    fun connect(ip: String) {
        connectionJob?.cancel()
        stopAudioStream()
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
                        send(Frame.Text(if (wantsGridPreviews) "grid_preview:1" else "grid_preview:0"))
                        _isConnected.value = true
                        _error.value = null
                        reconnectDelay = reconnectBaseDelayMs
                        TaskbarWidgetProvider.updateAll(getApplication())
                        startAudioStreamIfEnabled()

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
                    stopAudioStream()
                }

                if (!isActive) break
                delay(reconnectDelay)
                reconnectDelay = (reconnectDelay * 2).coerceAtMost(reconnectMaxDelayMs)
            }
        }
    }

    fun startLiveStream(hwnd: Long) {
        activeScreenStreamIndex = null
        _selectedRowHwnd.value = hwnd
        liveStreamJob?.cancel()
        _focusedLiveFrame.value = null
        _h264VideoConfig.value = null
        _h264Error.value = null
        val ip = _pcIp.value
        if (ip.isEmpty() || hwnd == 0L) return

        startAudioStreamIfEnabled()
        liveStreamJob = viewModelScope.launch {
            try {
                val streamSettings = _theme.value
                applyBackendStreamConfig(streamSettings, ip)
                val livePath = if (streamSettings.useHighPerformanceWindowStreaming) "live_h264" else "live"
                val liveUrl = "ws://$ip:8000/$livePath/$hwnd" +
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
                                if (streamSettings.useHighPerformanceWindowStreaming) {
                                    _h264Frames.tryEmit(bytes)
                                } else {
                                    val now = android.os.SystemClock.uptimeMillis()
                                    if (now - lastPublishedAt >= publishIntervalMs) {
                                        lastPublishedAt = now
                                        _focusedLiveFrame.value = bytes
                                    }
                                }
                            }
                            is Frame.Text -> {
                                val text = frame.readText()
                                Log.i("TaskbarLive", "Received text frame: ${text.take(120)}")
                                val parsed = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
                                if (parsed?.get("type")?.jsonPrimitive?.content == "video_config") {
                                    val width = parsed["width"]?.jsonPrimitive?.intOrNull ?: 0
                                    val height = parsed["height"]?.jsonPrimitive?.intOrNull ?: 0
                                    val fps = parsed["fps"]?.jsonPrimitive?.intOrNull ?: 60
                                    if (width > 0 && height > 0) {
                                        _h264Error.value = null
                                        _h264VideoConfig.value = H264VideoConfig(width, height, fps)
                                    }
                                } else if (parsed?.get("type")?.jsonPrimitive?.content == "error") {
                                    val message = parsed["message"]?.jsonPrimitive?.content ?: "Hardware stream failed"
                                    _h264Error.value = message
                                    _inputStatus.value = message
                                } else if (text.isNotEmpty() && !text.startsWith("status:") && !text.startsWith("error:")) {
                                    _focusedLiveFrame.value = android.util.Base64.decode(text, android.util.Base64.DEFAULT)
                                }
                            }
                            else -> Unit
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("TaskbarViewModel", "Live stream failed", e)
            }
        }
    }

    fun stopLiveStream() {
        liveStreamJob?.cancel()
        activeScreenStreamIndex = null
        _focusedLiveFrame.value = null
        _h264VideoConfig.value = null
        _h264Error.value = null
    }

    private fun startAudioStreamIfEnabled(settings: ThemeSettings = _theme.value) {
        if (!settings.enableAudio) return
        val ip = _pcIp.value
        val pass = _password.value
        if (ip.isEmpty()) return
        val sessionKey = "$ip|$pass|${settings.audioDelayMs}|${settings.audioBufferMs}"
        if (audioJob?.isActive == true && audioSessionKey == sessionKey) return

        audioJob?.cancel()
        releaseActiveAudioTrack()
        val generation = ++audioGeneration
        audioSessionKey = sessionKey
        _audioStatus.value = null

        audioJob = viewModelScope.launch {
            val audioUrl = "ws://$ip:8000/audio/loopback?sample_rate=48000&channels=2&frame_ms=10"
            try {
                client.webSocket(audioUrl) {
                    send(Frame.Text(pass))
                    var packets: Channel<AudioPacket>? = null
                    var playerJob: Job? = null
                    try {
                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Text -> {
                                    val text = frame.readText()
                                    val parsed = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
                                    if (parsed?.get("type")?.jsonPrimitive?.content == "audio_config") {
                                        val sampleRate = parsed["sample_rate"]?.jsonPrimitive?.intOrNull ?: 48000
                                        val channels = parsed["channels"]?.jsonPrimitive?.intOrNull ?: 2
                                        val source = parsed["source"]?.jsonPrimitive?.content ?: "audio"
                                        packets?.close()
                                        playerJob?.cancel()
                                        val newPackets = Channel<AudioPacket>(capacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
                                        packets = newPackets
                                        playerJob = launch(Dispatchers.IO) {
                                            playAudioPackets(
                                                packets = newPackets,
                                                sampleRate = sampleRate,
                                                channels = channels,
                                                targetBufferMs = settings.audioBufferMs.coerceIn(20, 240),
                                                delayMs = settings.audioDelayMs.coerceIn(-300, 500),
                                                generation = generation
                                            )
                                        }
                                        _audioStatus.value = "Audio connected: ${sampleRate}Hz ${channels}ch via $source"
                                    } else if (parsed?.get("type")?.jsonPrimitive?.content == "error") {
                                        val message = parsed["message"]?.jsonPrimitive?.content ?: "Audio stream failed"
                                        _audioStatus.value = message
                                        _inputStatus.value = message
                                    }
                                }
                                is Frame.Binary -> {
                                    val channel = packets
                                    if (channel != null && generation == audioGeneration) {
                                        parseAudioPacket(frame.readBytes())?.let { channel.trySend(it) }
                                    }
                                }
                                else -> Unit
                            }
                        }
                    } finally {
                        packets?.close()
                        playerJob?.cancel()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = "Audio failed: ${e.message ?: e::class.java.simpleName}"
                Log.e("TaskbarAudio", message, e)
                _audioStatus.value = message
                _inputStatus.value = message
            } finally {
                if (audioSessionKey == sessionKey) {
                    audioJob = null
                    audioSessionKey = null
                }
            }
        }
    }

    private fun stopAudioStream() {
        audioJob?.cancel()
        audioJob = null
        audioGeneration++
        releaseActiveAudioTrack()
        audioSessionKey = null
        _audioStatus.value = null
    }

    private fun registerActiveAudioTrack(track: AudioTrack) {
        val oldTrack = synchronized(audioTrackLock) {
            val old = activeAudioTrack
            activeAudioTrack = track
            old
        }
        if (oldTrack !== track) releaseAudioTrack(oldTrack)
    }

    private fun releaseActiveAudioTrack() {
        val track = synchronized(audioTrackLock) {
            val current = activeAudioTrack
            activeAudioTrack = null
            current
        }
        releaseAudioTrack(track)
    }

    private fun releaseAudioTrackIfActive(track: AudioTrack) {
        val shouldRelease = synchronized(audioTrackLock) {
            if (activeAudioTrack === track) {
                activeAudioTrack = null
                true
            } else {
                false
            }
        }
        if (shouldRelease) releaseAudioTrack(track)
    }

    private fun releaseAudioTrack(track: AudioTrack?) {
        if (track == null) return
        try {
            track.pause()
            track.flush()
        } catch (e: Exception) {
        }
        try {
            track.release()
        } catch (e: Exception) {
        }
    }

    private fun parseAudioPacket(bytes: ByteArray): AudioPacket? {
        if (bytes.size < 16) return null
        val header = ByteBuffer.wrap(bytes, 0, 16).order(ByteOrder.LITTLE_ENDIAN)
        val ptsUs = header.long
        val seq = header.int.toLong() and 0xFFFFFFFFL
        val payloadSize = header.int
        if (payloadSize <= 0 || bytes.size < 16 + payloadSize) return null
        return AudioPacket(ptsUs, seq, bytes.copyOfRange(16, 16 + payloadSize))
    }

    private suspend fun playAudioPackets(
        packets: Channel<AudioPacket>,
        sampleRate: Int,
        channels: Int,
        targetBufferMs: Int,
        delayMs: Int,
        generation: Int
    ) {
        val channelConfig = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val bytesPerMs = (sampleRate * channels * 2 / 1000).coerceAtLeast(1)
        val bytesPerFrame = (channels * 2).coerceAtLeast(2)
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
        val startupBufferMs = targetBufferMs.coerceIn(20, 80)
        val maxBufferedMs = (startupBufferMs + 80).coerceAtMost(180)
        val trackBuffer = maxOf(minBuffer, bytesPerMs * (startupBufferMs + kotlin.math.abs(delayMs) + 80))
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelConfig)
            .build()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val track = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(audioFormat)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(trackBuffer)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        runCatching { track.setBufferSizeInFrames((trackBuffer / bytesPerFrame).coerceAtLeast(1)) }
        runCatching { track.setStartThresholdInFrames(((bytesPerMs * startupBufferMs) / bytesPerFrame).coerceAtLeast(1)) }
        val queue = ArrayDeque<AudioPacket>()
        val silence10Ms = ByteArray(bytesPerMs * 10)

        fun bufferedMs(): Int = queue.sumOf { it.payload.size } / bytesPerMs

        suspend fun receiveNext(timeoutMs: Long): AudioPacket? {
            return withTimeoutOrNull(timeoutMs) {
                packets.receiveCatching().getOrNull()
            }
        }

        fun dropDuration(ms: Int) {
            var remaining = ms * bytesPerMs
            while (remaining > 0 && queue.isNotEmpty()) {
                val first = queue.removeFirst()
                remaining -= first.payload.size
            }
        }

        suspend fun writePcm(bytes: ByteArray, length: Int = bytes.size): Boolean {
            var offset = 0
            val safeLength = length.coerceAtMost(bytes.size)
            while (
                offset < safeLength &&
                currentCoroutineContext().isActive &&
                generation == audioGeneration
            ) {
                val written = track.write(bytes, offset, safeLength - offset, AudioTrack.WRITE_BLOCKING)
                if (written > 0) {
                    offset += written
                } else {
                    return false
                }
            }
            return offset >= safeLength
        }

        try {
            registerActiveAudioTrack(track)
            track.play()
            while (bufferedMs() < startupBufferMs) {
                if (generation != audioGeneration) return
                val next = receiveNext(120) ?: break
                queue.addLast(next)
            }
            if (delayMs > 0) {
                var remaining = delayMs
                while (remaining > 0) {
                    val writeMs = remaining.coerceAtMost(10)
                    if (!writePcm(silence10Ms, bytesPerMs * writeMs)) return
                    remaining -= writeMs
                }
            } else if (delayMs < 0) {
                dropDuration(-delayMs)
            }

            while (currentCoroutineContext().isActive && generation == audioGeneration) {
                val next = receiveNext(if (queue.isEmpty()) 30 else 1)
                if (next != null) queue.addLast(next)
                while (bufferedMs() > maxBufferedMs && queue.isNotEmpty()) {
                    queue.removeFirst()
                }
                val packet = if (queue.isNotEmpty()) queue.removeFirst() else null
                if (packet != null) {
                    if (!writePcm(packet.payload)) break
                } else {
                    if (!writePcm(silence10Ms)) break
                }
            }
        } finally {
            releaseAudioTrackIfActive(track)
        }
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
            } catch (e: CancellationException) {
                throw e
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
                            if (message != null && !shouldSuppressScreenGoneError(message)) {
                                _inputStatus.value = message
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("TaskbarInput", "Screen input failed", e)
                val message = "Input connection failed: ${e.message ?: e::class.java.simpleName}"
                if (!shouldSuppressScreenGoneError(message)) {
                    _inputStatus.value = message
                }
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
        pendingMultiTouchJob?.cancel()
        pendingMultiTouchJob = null
        pendingMultiTouchPoints = null
    }

    fun clearInputStatus() {
        _inputStatus.value = null
    }

    private fun shouldSuppressScreenGoneError(message: String): Boolean {
        return SystemClock.uptimeMillis() < suppressScreenGoneErrorsUntil &&
            message.contains("screen not found", ignoreCase = true)
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

    fun sendMouseInput(action: String, a: Float, b: Float) {
        sendRemoteInput(buildJsonObject {
            put("type", "mouse")
            when (action) {
                // Touchpad-style relative move: a/b carry the pixel delta (dx/dy).
                "move_rel" -> {
                    put("action", "move_rel")
                    put("dx", a)
                    put("dy", b)
                }
                // Tap-to-click: click at the current cursor position (no repositioning).
                "click" -> {
                    put("action", "click")
                    put("button", "left")
                    put("relative", true)
                }
                // Legacy absolute positioning (unused by touchpad mode, kept for safety).
                else -> {
                    put("action", action)
                    put("x", a.coerceIn(0f, 1f))
                    put("y", b.coerceIn(0f, 1f))
                }
            }
        })
    }

    fun sendMouseButtonClick(button: String) {
        sendRemoteInput(buildJsonObject {
            put("type", "mouse")
            put("action", "click")
            put("button", button)
            put("relative", true)
        })
    }

    fun sendMouseWheel(delta: Int) {
        sendRemoteInput(buildJsonObject {
            put("type", "mouse")
            put("action", "wheel")
            put("delta", delta)
            put("relative", true)
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

    fun sendMultiTouchInput(points: List<TouchPointInput>) {
        if (points.isEmpty()) return
        val hasEdgeEvent = points.any { it.action == "down" || it.action == "up" }
        val now = SystemClock.uptimeMillis()
        if (hasEdgeEvent || now - lastMultiTouchSentAt >= multiTouchSendIntervalMs) {
            pendingMultiTouchJob?.cancel()
            pendingMultiTouchJob = null
            pendingMultiTouchPoints = null
            sendMultiTouchInputNow(points)
            lastMultiTouchSentAt = now
            return
        }

        pendingMultiTouchPoints = points
        if (pendingMultiTouchJob?.isActive == true) return
        val delayMs = (multiTouchSendIntervalMs - (now - lastMultiTouchSentAt)).coerceAtLeast(1L)
        pendingMultiTouchJob = viewModelScope.launch {
            delay(delayMs)
            val latest = pendingMultiTouchPoints
            pendingMultiTouchPoints = null
            if (!latest.isNullOrEmpty()) {
                sendMultiTouchInputNow(latest)
                lastMultiTouchSentAt = SystemClock.uptimeMillis()
            }
        }
    }

    private fun sendMultiTouchInputNow(points: List<TouchPointInput>) {
        sendRemoteInput(buildJsonObject {
            put("type", "touch_multi")
            putJsonArray("points") {
                points.forEach { point ->
                    add(buildJsonObject {
                        put("id", point.id.coerceIn(0, 9))
                        put("action", point.action)
                        put("x", point.x.coerceIn(0f, 1f))
                        put("y", point.y.coerceIn(0f, 1f))
                        put("primary", point.primary)
                    })
                }
            }
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
            when (shortcut.type) {
                ShortcutActionType.KEYS -> {
                    put("type", "shortcut")
                    putJsonArray("keys") {
                        shortcut.keys.forEach { add(it) }
                    }
                }
                ShortcutActionType.START_MENU_APP -> {
                    put("type", "launch_start_menu_app")
                    put("target", shortcut.target)
                }
                ShortcutActionType.COMMAND -> {
                    put("type", "run_command")
                    put("command", shortcut.target)
                }
            }
        })
    }

    fun fetchStartMenuApps() {
        viewModelScope.launch {
            val ip = _pcIp.value
            if (ip.isEmpty()) return@launch
            _startMenuAppsLoading.value = true
            try {
                _startMenuApps.value = client.get("http://$ip:8000/start-menu/apps") {
                    header("password", _password.value)
                }.body()
            } catch (e: Exception) {
                _error.value = "Start menu apps fetch failed: ${e.message ?: e::class.java.simpleName}"
            } finally {
                _startMenuAppsLoading.value = false
            }
        }
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
        viewModelScope.launch {
            settingsManager.savePcIp(ip)
            settingsManager.savePassword(pass)
            TaskbarWidgetProvider.updateAll(getApplication())
        }
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
        if (newTheme.useHardwareEncoding != oldTheme.useHardwareEncoding && newTheme.useHardwareEncoding) {
            fetchH264Status(refresh = true)
        }
        if (
            newTheme.enableAudio != oldTheme.enableAudio ||
            newTheme.audioDelayMs != oldTheme.audioDelayMs ||
            newTheme.audioBufferMs != oldTheme.audioBufferMs
        ) {
            if (newTheme.enableAudio && _isConnected.value) {
                startAudioStreamIfEnabled(newTheme)
            } else if (!newTheme.enableAudio) {
                stopAudioStream()
            }
        }
        viewModelScope.launch {
            settingsManager.saveTheme(newTheme)
            if (
                newTheme.showWidgetTitles != oldTheme.showWidgetTitles ||
                newTheme.widgetBackgroundColor != oldTheme.widgetBackgroundColor ||
                newTheme.widgetBackgroundAlpha != oldTheme.widgetBackgroundAlpha ||
                newTheme.widgetItemSizeDp != oldTheme.widgetItemSizeDp ||
                newTheme.widgetTransparentOnError != oldTheme.widgetTransparentOnError
            ) {
                TaskbarWidgetProvider.updateAll(getApplication())
            }
        }
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

    fun setGridPreviewActive(active: Boolean) {
        if (wantsGridPreviews == active) return
        wantsGridPreviews = active
        viewModelScope.launch {
            try {
                activeWsSession?.send(Frame.Text(if (active) "grid_preview:1" else "grid_preview:0"))
            } catch (e: Exception) {
                Log.d("TaskbarViewModel", "Grid preview mode update failed: ${e.localizedMessage}")
            }
        }
    }

    fun fetchH264Status(refresh: Boolean = false) {
        viewModelScope.launch {
            val ip = _pcIp.value
            if (ip.isEmpty()) return@launch
            try {
                _h264Status.value = client.get("http://$ip:8000/h264/status") {
                    parameter("refresh", refresh)
                }.body()
            } catch (e: Exception) {
                _h264Status.value = H264StatusInfo(
                    usable = false,
                    message = "Failed to fetch H.264 status: ${e.localizedMessage}"
                )
            }
        }
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
        activeScreenStreamIndex = monitorIndex
        liveStreamJob?.cancel()
        _focusedLiveFrame.value = null
        _h264VideoConfig.value = null
        _h264Error.value = null
        val ip = _pcIp.value
        if (ip.isEmpty() || monitorIndex <= 0) return
        startAudioStreamIfEnabled()
        liveStreamJob = viewModelScope.launch {
            try {
                val streamSettings = _theme.value
                applyBackendStreamConfig(streamSettings, ip)
                val useScreenHardware = streamSettings.useHardwareEncoding
                val livePath = if (useScreenHardware) "live_h264/screen" else "live/screen"
                val liveUrl = "ws://$ip:8000/$livePath/$monitorIndex" +
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
                            if (useScreenHardware) {
                                _h264Frames.tryEmit(bytes)
                            } else {
                                val now = android.os.SystemClock.uptimeMillis()
                                if (now - lastPublishedAt >= publishIntervalMs) {
                                    lastPublishedAt = now
                                    _focusedLiveFrame.value = bytes
                                }
                            }
                        } else if (frame is Frame.Text) {
                            val text = frame.readText()
                            Log.i("TaskbarLive", text)
                            val parsed = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
                            if (parsed?.get("type")?.jsonPrimitive?.content == "video_config") {
                                val width = parsed["width"]?.jsonPrimitive?.intOrNull ?: 0
                                val height = parsed["height"]?.jsonPrimitive?.intOrNull ?: 0
                                val fps = parsed["fps"]?.jsonPrimitive?.intOrNull ?: 60
                                if (width > 0 && height > 0) {
                                    _h264Error.value = null
                                    _h264VideoConfig.value = H264VideoConfig(width, height, fps)
                                }
                            } else if (parsed?.get("type")?.jsonPrimitive?.content == "error") {
                                val message = parsed["message"]?.jsonPrimitive?.content ?: "Hardware stream failed"
                                if (!shouldSuppressScreenGoneError(message)) {
                                    _h264Error.value = message
                                    _inputStatus.value = message
                                }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
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

    fun fetchExtendedDisplayStatus() {
        viewModelScope.launch {
            val ip = _pcIp.value
            if (ip.isEmpty()) return@launch
            fetchExtendedDisplayStatusNow(ip)
        }
    }

    private suspend fun fetchExtendedDisplayStatusNow(ip: String = _pcIp.value): ExtendedDisplayStatus? {
        if (ip.isEmpty()) return null
        return try {
            val status: ExtendedDisplayStatus = client.get("http://$ip:8000/extended_display/status") {
                header("password", _password.value)
            }.body()
            _extendedDisplayStatus.value = status
            status
        } catch (e: Exception) {
            val status = ExtendedDisplayStatus(
                available = false,
                message = "Extended display status failed: ${e.localizedMessage}"
            )
            _extendedDisplayStatus.value = status
            status
        }
    }

    fun connectExtendedDisplay(onConnected: (ScreenInfo) -> Unit) {
        if (_extendedDisplayConnecting.value) return
        val ip = _pcIp.value
        if (ip.isEmpty()) return
        _extendedDisplayConnecting.value = true
        viewModelScope.launch {
            try {
                val response: ExtendedDisplayConnectResponse = client.post("http://$ip:8000/extended_display/connect") {
                    header("password", _password.value)
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject {
                        put("client_id", clientId)
                    }.toString())
                }.body()
                fetchScreens()
                fetchExtendedDisplayStatus()
                val screen = response.screen ?: ScreenInfo(monitor_index = response.monitor_index)
                if (screen.monitor_index > 0) {
                    onConnected(screen)
                } else {
                    _inputStatus.value = response.message.ifBlank { "Extended display connected, but no monitor index was returned" }
                }
            } catch (e: ClientRequestException) {
                _inputStatus.value = "Extended display unavailable: ${e.response.status}"
                fetchExtendedDisplayStatus()
            } catch (e: ServerResponseException) {
                _inputStatus.value = "Extended display failed: ${e.response.status}"
                fetchExtendedDisplayStatus()
            } catch (e: Exception) {
                Log.e("TaskbarViewModel", "Connect extended display failed", e)
                _inputStatus.value = "Extended display failed: ${e.localizedMessage}"
                fetchExtendedDisplayStatus()
            } finally {
                _extendedDisplayConnecting.value = false
            }
        }
    }

    fun disconnectExtendedDisplayBinding() {
        val ip = _pcIp.value
        if (ip.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                client.post("http://$ip:8000/extended_display/disconnect") {
                    header("password", _password.value)
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject { put("client_id", clientId) }.toString())
                }
            }
            fetchExtendedDisplayStatus()
        }
    }

    fun setExtendedDisplayDriverEnabled(enabled: Boolean) {
        if (_extendedDisplayDriverChanging.value) return
        val ip = _pcIp.value
        if (ip.isEmpty()) return
        _extendedDisplayDriverChanging.value = true
        val previousMonitorIndex = _extendedDisplayStatus.value?.monitor_index ?: 0
        val activeMonitorIndex = activeScreenStreamIndex ?: previousMonitorIndex
        if (!enabled && activeMonitorIndex > 0) {
            suppressScreenGoneErrorsUntil = SystemClock.uptimeMillis() + 6000L
            stopLiveStream()
            stopRemoteInput()
            _inputStatus.value = null
        }
        viewModelScope.launch {
            try {
                val response: ExtendedDisplayStatus = client.post("http://$ip:8000/extended_display/driver_state") {
                    header("password", _password.value)
                    contentType(ContentType.Application.Json)
                    timeout { requestTimeoutMillis = 25000 }
                    setBody(buildJsonObject {
                        put("client_id", clientId)
                        put("enabled", enabled)
                    }.toString())
                }.body()
                _extendedDisplayStatus.value = response
                fetchScreens()
                if (!enabled) {
                    _inputStatus.value = null
                    _h264Error.value = null
                }
            } catch (e: ClientRequestException) {
                val status = fetchExtendedDisplayStatusNow(ip)
                if (status?.driver_enabled == enabled || (!enabled && status?.available == false)) {
                    _inputStatus.value = null
                    _h264Error.value = null
                } else {
                    _inputStatus.value = when (e.response.status.value) {
                        403 -> "Run the PC backend as administrator to change the virtual display driver"
                        404 -> "No compatible virtual display driver was found"
                        else -> "Virtual display driver change failed: ${e.response.status}"
                    }
                }
            } catch (e: ServerResponseException) {
                val status = fetchExtendedDisplayStatusNow(ip)
                if (status?.driver_enabled == enabled || (!enabled && status?.available == false)) {
                    _inputStatus.value = null
                    _h264Error.value = null
                } else {
                    _inputStatus.value = "Virtual display driver change failed: ${e.response.status}"
                }
            } catch (e: Exception) {
                Log.e("TaskbarViewModel", "Set VDD driver state failed", e)
                val status = fetchExtendedDisplayStatusNow(ip)
                if (status?.driver_enabled == enabled || (!enabled && status?.available == false)) {
                    _inputStatus.value = null
                    _h264Error.value = null
                } else {
                    _inputStatus.value = "Virtual display driver change failed: ${e.localizedMessage}"
                }
            } finally {
                _extendedDisplayDriverChanging.value = false
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
