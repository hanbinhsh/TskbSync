package com.ice.tskbsync

import kotlinx.serialization.Serializable

@Serializable
data class WindowInfo(
    val hwnd: Long,
    val title: String,
    val icon: String,
    val preview: String = "",
    val is_active: Boolean = false,
    val is_maximized: Boolean = false,
    val pid: Int = 0,
    val process_name: String = "",
    val class_name: String = ""
)

@Serializable
data class ScreenInfo(
    val monitor_index: Int = 0,
    val ddagrab_output_idx: Int = 0,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val is_primary: Boolean = false,
    val is_virtual: Boolean = false,
    val virtual_driver: String = "",
    val extended_owner: String = "",
    val device: String = "",
    val name: String = ""
)

@Serializable
data class ExtendedDisplayMode(
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0
)

@Serializable
data class ExtendedDisplayStatus(
    val available: Boolean = false,
    val message: String = "",
    val driver_control_available: Boolean = false,
    val driver_enabled: Boolean = false,
    val requires_admin: Boolean = false,
    val is_admin: Boolean = false,
    val driver_instance_id: String = "",
    val driver_status: String = "",
    val driver_name: String = "",
    val driver_error: String = "",
    val bound_client_id: String = "",
    val monitor_index: Int = 0,
    val screen: ScreenInfo? = null,
    val current_mode: ExtendedDisplayMode? = null,
    val supported_modes: List<ExtendedDisplayMode> = emptyList(),
    val mode_monitor_index: Int = 0
)

@Serializable
data class ExtendedDisplayConnectResponse(
    val status: String = "",
    val message: String = "",
    val monitor_index: Int = 0,
    val screen: ScreenInfo? = null,
    val current_mode: ExtendedDisplayMode? = null
)

@Serializable
data class H264EncoderProbe(
    val encoder: String = "",
    val available: Boolean = false,
    val usable: Boolean = false,
    val profile: String = "",
    val message: String = ""
)

@Serializable
data class H264StatusInfo(
    val ffmpeg_path: String = "",
    val selected_encoder: String = "",
    val selected_profile: String = "",
    val usable: Boolean = false,
    val message: String = "",
    val native_streamer_path: String = "",
    val native_screen_capture: Boolean = false,
    val native_screen_message: String = "",
    val direct_screen_capture: Boolean = false,
    val direct_screen_message: String = "",
    val results: List<H264EncoderProbe> = emptyList(),
    val lookup_order: List<String> = emptyList()
)
