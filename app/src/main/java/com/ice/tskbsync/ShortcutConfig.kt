package com.ice.tskbsync

import kotlinx.serialization.Serializable

@Serializable
enum class ShortcutActionType {
    KEYS,
    START_MENU_APP,
    COMMAND
}

@Serializable
data class ShortcutConfig(
    val label: String,
    val keys: List<String> = emptyList(),
    val type: ShortcutActionType = ShortcutActionType.KEYS,
    val target: String = ""
) {
    fun description(): String = when (type) {
        ShortcutActionType.KEYS -> keys.joinToString(" + ")
        ShortcutActionType.START_MENU_APP -> target
        ShortcutActionType.COMMAND -> target
    }
}

val defaultShortcutConfigs = listOf(
    ShortcutConfig("Esc", listOf("ESC")),
    ShortcutConfig("Enter", listOf("ENTER")),
    ShortcutConfig("Alt+Tab", listOf("ALT", "TAB")),
    ShortcutConfig("Ctrl+C", listOf("CTRL", "C")),
    ShortcutConfig("Ctrl+V", listOf("CTRL", "V")),
    ShortcutConfig("Win", listOf("WIN")),
    ShortcutConfig("Backspace", listOf("BACKSPACE"))
)
