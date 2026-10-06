package com.lilayam.dellservertools.core.proxmox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CloneRequestTest {

    private fun vm() = CloneRequest(
        type = GuestType.QEMU,
        sourceVmid = 100,
        newId = 201,
        name = "web-02",
    )

    private fun ct() = CloneRequest(
        type = GuestType.LXC,
        sourceVmid = 200,
        newId = 202,
        name = "runner-2",
    )

    @Test
    fun `full vm clone maps to the expected api params`() {
        val p = vm().copy(storage = "local-zfs", pool = "lab", target = "pve2").toParams()
        assertEquals("201", p["newid"])
        assertEquals("1", p["full"])
        assertEquals("web-02", p["name"])
        assertNull(p["hostname"])
        assertEquals("local-zfs", p["storage"])
        assertEquals("pve2", p["target"])
        assertEquals("lab", p["pool"])
        assertNull(p["snapname"])
    }

    @Test
    fun `container clone uses hostname and can clone from a snapshot`() {
        val p = ct().copy(snapname = "clean").toParams()
        assertEquals("runner-2", p["hostname"])
        assertNull(p["name"])
        assertEquals("clean", p["snapname"])
    }

    @Test
    fun `blank name lets proxmox pick a default`() {
        val p = vm().copy(name = "").toParams()
        assertNull(p["name"])
        assertNull(p["hostname"])
    }

    @Test
    fun `linked clone of a template omits the storage`() {
        val p = vm().copy(full = false, sourceIsTemplate = true, storage = "local-zfs").toParams()
        assertEquals("0", p["full"])
        // A linked clone keeps the source's storage, so it is never sent.
        assertNull(p["storage"])
    }

    @Test
    fun `validation accepts a good request`() {
        assertNull(vm().validate())
        assertNull(ct().validate())
        assertNull(vm().copy(name = "web.example-2").validate())
    }

    @Test
    fun `validation rejects bad ids`() {
        assertNotNull(vm().copy(newId = 99).validate())
        assertNotNull(vm().copy(newId = 100).validate()) // same as source
    }

    @Test
    fun `validation rejects bad names and hostnames`() {
        assertNotNull(vm().copy(name = "bad name").validate())
        assertNotNull(ct().copy(name = "-bad").validate())
        assertNotNull(ct().copy(name = "dots.not.allowed").validate()) // CT hostnames have no dots
    }

    @Test
    fun `linked clone only allowed from a template`() {
        assertNotNull(vm().copy(full = false, sourceIsTemplate = false).validate())
        assertNull(vm().copy(full = false, sourceIsTemplate = true).validate())
        // A linked clone can't target a different storage.
        assertNotNull(vm().copy(full = false, sourceIsTemplate = true, storage = "other").validate())
    }
}
