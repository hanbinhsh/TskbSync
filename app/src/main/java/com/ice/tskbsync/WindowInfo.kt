package com.ice.tskbsync

import kotlinx.serialization.Serializable

@Serializable
data class WindowInfo(
    val hwnd: Long,
    val title: String,
    val icon: String,
    val preview: String = "",
    val is_active: Boolean = false,
    val pid: Int = 0
)
