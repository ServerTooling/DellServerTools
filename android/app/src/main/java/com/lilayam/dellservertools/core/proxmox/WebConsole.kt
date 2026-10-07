package com.lilayam.dellservertools.core.proxmox

/**
 * Support for Proxmox's web consoles (xterm.js for containers and the node shell, noVNC for a VM's screen)
 * inside the in-app WebView. Both focus a hidden input from script, which Android won't show the soft
 * keyboard for, so the app focuses it itself and offers the keys a phone keyboard lacks.
 */
object WebConsole {

    /** Keys the key bar can press; the UI maps them to platform key codes. */
    enum class Key { ENTER, TAB, ESCAPE, BACKSPACE, UP, DOWN, LEFT, RIGHT, C, D, Z, L }

    data class ConsoleKey(val label: String, val key: Key, val ctrl: Boolean = false)

    /** True for console pages (see [ProxmoxClient.consoleUrl]), not the main web interface. */
    fun isConsoleUrl(url: String): Boolean = queryParams(url).any { it.startsWith("console=") }

    /** A text (xterm.js) console, where a tap means "I want to type"; on a VM's screen it's a mouse click. */
    fun isTerminal(url: String): Boolean = queryParams(url).contains("xtermjs=1")

    private fun queryParams(url: String): List<String> = url.substringAfter('?', "").split('&')

    /** Keys a phone keyboard lacks (Proxmox's noVNC page has its own Ctrl+Alt+Del). */
    val keys: List<ConsoleKey> = listOf(
        ConsoleKey("Enter", Key.ENTER),
        ConsoleKey("Ctrl+C", Key.C, ctrl = true),
        ConsoleKey("Ctrl+D", Key.D, ctrl = true),
        ConsoleKey("Ctrl+Z", Key.Z, ctrl = true),
        ConsoleKey("Tab", Key.TAB),
        ConsoleKey("Esc", Key.ESCAPE),
        ConsoleKey("↑", Key.UP),
        ConsoleKey("↓", Key.DOWN),
        ConsoleKey("←", Key.LEFT),
        ConsoleKey("→", Key.RIGHT),
        ConsoleKey("Bksp", Key.BACKSPACE),
        ConsoleKey("Ctrl+L", Key.L, ctrl = true),
    )

    /**
     * Focuses the console's keyboard input (xterm.js's helper textarea, or noVNC's on-screen-keyboard input)
     * and evaluates to whether one was found, so the app can then raise the soft keyboard for it.
     */
    const val FOCUS_INPUT_SCRIPT =
        "(function(){var e=document.querySelector('.xterm-helper-textarea')" +
            "||document.getElementById('noVNC_keyboardinput');" +
            "if(!e)return false;e.focus();return true;})()"
}
