package com.lilayam.dellservertools.core.proxmox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VmKeysTest {
    @Test
    fun `params send the qm key name`() {
        assertEquals(mapOf("key" to "ctrl-alt-delete"), VmKeys.params("ctrl-alt-delete"))
    }

    @Test
    fun `key names are the ones qm sendkey accepts`() {
        assertEquals(
            listOf("ret", "esc", "tab", "spc", "up", "down", "f2", "f12", "ctrl-alt-delete"),
            VmKeys.keys.map { it.name },
        )
    }

    @Test
    fun `boot presses Enter often enough to hit a five second prompt and stop before Setup`() {
        assertEquals("ret", VmKeys.BOOT_KEY)
        assertTrue(VmKeys.BOOT_PRESS_INTERVAL_MS <= 1_000, "the prompt lasts about five seconds")
        assertTrue(VmKeys.BOOT_PRESS_WINDOW_MS in 10_000..20_000)
        assertEquals(30, VmKeys.bootPresses)
    }
}
