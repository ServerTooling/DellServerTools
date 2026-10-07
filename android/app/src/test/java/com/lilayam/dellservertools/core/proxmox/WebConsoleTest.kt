package com.lilayam.dellservertools.core.proxmox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebConsoleTest {
    // consoleUrl only formats a URL; nothing connects.
    private val client = ProxmoxClient("192.0.2.10", 8006, ProxmoxCredentials.Password("root@pam", "placeholder"), null)

    @Test
    fun `console pages are recognised, the main web UI is not`() {
        val ct = client.consoleUrl("pve", GuestType.LXC, 102, "ct")
        val vm = client.consoleUrl("pve", GuestType.QEMU, 100, "vm")
        val node = client.consoleUrl("pve", null, null, null)

        assertTrue(WebConsole.isConsoleUrl(ct))
        assertTrue(WebConsole.isConsoleUrl(vm))
        assertTrue(WebConsole.isConsoleUrl(node))
        assertFalse(WebConsole.isConsoleUrl("https://192.0.2.10:8006/"))
        assertFalse(WebConsole.isConsoleUrl("https://192.0.2.10:8006/?mobile=1"))
    }

    @Test
    fun `only text consoles take a tap as a request for the keyboard`() {
        assertTrue(WebConsole.isTerminal(client.consoleUrl("pve", GuestType.LXC, 102, "ct")))
        assertTrue(WebConsole.isTerminal(client.consoleUrl("pve", null, null, null)))
        assertFalse(WebConsole.isTerminal(client.consoleUrl("pve", GuestType.QEMU, 100, "vm")))
    }

    @Test
    fun `key bar offers the keys a phone keyboard lacks`() {
        val labels = WebConsole.keys.map { it.label }
        assertEquals(listOf("Enter", "Ctrl+C", "Ctrl+D", "Ctrl+Z", "Tab", "Esc", "↑", "↓", "←", "→", "Bksp", "Ctrl+L"), labels)
        assertEquals(WebConsole.ConsoleKey("Ctrl+C", WebConsole.Key.C, ctrl = true), WebConsole.keys[1])
    }

    @Test
    fun `focus script targets xterm and noVNC inputs`() {
        assertTrue(".xterm-helper-textarea" in WebConsole.FOCUS_INPUT_SCRIPT)
        assertTrue("noVNC_keyboardinput" in WebConsole.FOCUS_INPUT_SCRIPT)
    }
}
