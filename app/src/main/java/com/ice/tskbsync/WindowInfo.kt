package com.ice.tskbsync

import kotlinx.serialization.Serializable

@Serializable
data class WindowInfo(
    val hwnd: Long,
    val title: String,
    val icon: String,
    val preview: String = "",
    val is_active: Boolean = false,
    val pid: Int = 0,
    val process_name: String = "",
    val class_name: String = ""
)

@Serializable
data class ScreenInfo(
    val monitor_index: Int = 0,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val is_primary: Boolean = false,
    val device: String = "",
    val name: String = ""
)
