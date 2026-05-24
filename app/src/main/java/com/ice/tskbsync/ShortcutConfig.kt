package com.ice.tskbsync

import kotlinx.serialization.Serializable

@Serializable
data class ShortcutConfig(
    val label: String,
    val keys: List<String>
)

val defaultShortcutConfigs = listOf(
    ShortcutConfig("Esc", listOf("ESC")),
    ShortcutConfig("Enter", listOf("ENTER")),
    ShortcutConfig("Alt+Tab", listOf("ALT", "TAB")),
    ShortcutConfig("Ctrl+C", listOf("CTRL", "C")),
    ShortcutConfig("Ctrl+V", listOf("CTRL", "V")),
    ShortcutConfig("Win", listOf("WIN")),
    ShortcutConfig("Backspace", listOf("BACKSPACE"))
)
