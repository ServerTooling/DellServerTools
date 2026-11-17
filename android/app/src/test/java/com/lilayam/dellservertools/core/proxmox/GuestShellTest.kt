package com.lilayam.dellservertools.core.proxmox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GuestShellTest {

    @Test
    fun `container gets an interactive shell via pct enter`() {
        assertEquals("pct enter 103", GuestShell.enterCommand(GuestType.LXC, 103))
        assertEquals("pct enter 200", GuestShell.enterCommand(GuestType.LXC, 200))
    }

    @Test
    fun `vm has no native shell yet`() {
        assertNull(GuestShell.enterCommand(GuestType.QEMU, 100))
    }

    @Test
    fun `a stale lock is cleared with pct or qm unlock`() {
        assertEquals("pct unlock 8900", GuestShell.unlockCommand(GuestType.LXC, 8900))
        assertEquals("qm unlock 100", GuestShell.unlockCommand(GuestType.QEMU, 100))
    }
}
