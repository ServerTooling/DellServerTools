package com.lilayam.dellservertools.integration

import com.lilayam.dellservertools.core.proxmox.GuestType
import com.lilayam.dellservertools.core.proxmox.MoveDiskRequest
import com.lilayam.dellservertools.core.proxmox.PinnedTls
import com.lilayam.dellservertools.core.proxmox.ProxmoxClient
import com.lilayam.dellservertools.core.proxmox.ProxmoxCredentials
import com.lilayam.dellservertools.testing.FakeProxmox
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GuestActionsIntegrationTest {
    private lateinit var pve: FakeProxmox
    private lateinit var client: ProxmoxClient

    @BeforeEach
    fun setUp() {
        pve = FakeProxmox()
        client = ProxmoxClient("127.0.0.1", pve.port, ProxmoxCredentials.Password("root@pam", "s3cret"), PinnedTls.fingerprint(pve.certificate.certificate))
        client.login()
    }

    @AfterEach
    fun tearDown() = pve.close()

    @Test
    fun `moves a vm disk and a container volume`() {
        val upid = client.moveDisk("pve", 100, MoveDiskRequest(GuestType.QEMU, "scsi0", "fast", format = "raw"))
        assertTrue(upid!!.startsWith("UPID:"))
        assertEquals(
            mapOf("disk" to "scsi0", "storage" to "fast", "delete" to "1", "format" to "raw"),
            pve.lastCall("POST", "/nodes/pve/qemu/100/move_disk")!!.params,
        )
        client.moveDisk("pve", 200, MoveDiskRequest(GuestType.LXC, "rootfs", "fast", deleteSource = false))
        assertEquals(mapOf("volume" to "rootfs", "storage" to "fast"), pve.lastCall("POST", "/nodes/pve/lxc/200/move_volume")!!.params)
    }

    @Test
    fun `hibernates, snapshots with ram and converts to a template`() {
        client.hibernate("pve", 100)
        assertEquals(mapOf("todisk" to "1"), pve.lastCall("POST", "/nodes/pve/qemu/100/status/suspend")!!.params)

        client.createSnapshot("pve", GuestType.QEMU, 100, "live", "", withRam = true)
        assertEquals(mapOf("snapname" to "live", "vmstate" to "1"), pve.lastCall("POST", "/nodes/pve/qemu/100/snapshot")!!.params)
        // Containers have no RAM state to save.
        client.createSnapshot("pve", GuestType.LXC, 200, "ct", "", withRam = true)
        assertEquals(mapOf("snapname" to "ct"), pve.lastCall("POST", "/nodes/pve/lxc/200/snapshot")!!.params)

        client.convertToTemplate("pve", GuestType.QEMU, 100)
        assertTrue(pve.vmTemplate)
        assertEquals("1", client.guestConfig("pve", GuestType.QEMU, 100).toMap()["template"])
    }

    @Test
    fun `stops a running task`() {
        pve.taskRunning = true
        val upid = "UPID:pve:00000030:qmmove:100:root@pam:"
        assertTrue(client.taskStatus("pve", upid).running)
        client.stopTask("pve", upid)
        assertTrue(pve.calls.any { it.method == "DELETE" && it.path.startsWith("/nodes/pve/tasks/UPID") })
        assertEquals("csrf-token", pve.requests.last { it.method == "DELETE" }.getHeader("CSRFPreventionToken"))
        assertFalse(client.taskStatus("pve", upid).running)
    }
}
