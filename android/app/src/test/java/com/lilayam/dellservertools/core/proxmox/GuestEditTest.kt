package com.lilayam.dellservertools.core.proxmox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GuestEditTest {

    @Test
    fun `reads a container config into an edit`() {
        val config = mapOf(
            "hostname" to "gh-runner-1", "cores" to "4", "memory" to "8192", "swap" to "512",
            "onboot" to "1", "features" to "nesting=1,keyctl=1", "description" to "ci",
        )
        val e = GuestEdit.from(GuestType.LXC, config)
        assertEquals("gh-runner-1", e.name)
        assertEquals(4, e.cores)
        assertEquals(8192, e.memoryMb)
        assertEquals(512, e.swapMb)
        assertTrue(e.onboot)
        assertTrue(e.nesting)
    }

    @Test
    fun `only changed container fields are sent, features preserved`() {
        val config = mapOf("hostname" to "a", "cores" to "4", "memory" to "8192", "swap" to "512", "features" to "keyctl=1")
        val edit = GuestEdit.from(GuestType.LXC, config).copy(memoryMb = 16384, nesting = true)
        val changes = edit.changedParams(config)
        assertEquals("16384", changes["memory"])
        // keyctl preserved, nesting added
        assertEquals("keyctl=1,nesting=1", changes["features"])
        assertNull(changes["cores"])
        assertNull(changes["hostname"])
    }

    @Test
    fun `turning nesting off removes it but keeps other features`() {
        val config = mapOf("hostname" to "a", "cores" to "2", "memory" to "1024", "features" to "nesting=1,keyctl=1")
        val edit = GuestEdit.from(GuestType.LXC, config).copy(nesting = false)
        assertEquals("keyctl=1", edit.changedParams(config)["features"])
    }

    @Test
    fun `vm uses name, sockets and agent`() {
        val config = mapOf("name" to "vm1", "cores" to "2", "sockets" to "1", "memory" to "2048", "agent" to "1")
        val e = GuestEdit.from(GuestType.QEMU, config)
        assertEquals("vm1", e.name)
        assertTrue(e.agent)
        val changes = e.copy(name = "vm2", sockets = 2, agent = false).changedParams(config)
        assertEquals("vm2", changes["name"])
        assertEquals("2", changes["sockets"])
        assertEquals("0", changes["agent"])
    }

    @Test
    fun `agent enabled parses both 1 and enabled form`() {
        assertTrue(GuestEdit.agentEnabled("1"))
        assertTrue(GuestEdit.agentEnabled("enabled=1,fstrim_cloned_disks=1"))
        assertFalse(GuestEdit.agentEnabled("0"))
        assertFalse(GuestEdit.agentEnabled(null))
    }

    @Test
    fun `no changes yields empty params`() {
        val config = mapOf("hostname" to "a", "cores" to "2", "memory" to "1024", "swap" to "0", "onboot" to "0", "description" to "")
        val edit = GuestEdit.from(GuestType.LXC, config)
        assertTrue(edit.changedParams(config).isEmpty())
    }
}
