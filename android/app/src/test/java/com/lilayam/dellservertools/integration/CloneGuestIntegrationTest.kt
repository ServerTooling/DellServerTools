package com.lilayam.dellservertools.integration

import com.lilayam.dellservertools.core.proxmox.CloneRequest
import com.lilayam.dellservertools.core.proxmox.GuestType
import com.lilayam.dellservertools.core.proxmox.PinnedTls
import com.lilayam.dellservertools.core.proxmox.ProxmoxClient
import com.lilayam.dellservertools.core.proxmox.ProxmoxCredentials
import com.lilayam.dellservertools.testing.FakeProxmox
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CloneGuestIntegrationTest {
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
    fun `clones a vm with the expected params`() {
        val req = CloneRequest(
            type = GuestType.QEMU,
            sourceVmid = 100,
            newId = 201,
            name = "web-02",
            full = true,
            storage = "local-zfs",
            pool = "lab",
        )
        val upid = client.cloneGuest("pve", GuestType.QEMU, 100, req.toParams())
        assertTrue(upid!!.startsWith("UPID:pve:"))
        assertEquals("qemu" to 100, pve.cloned)
        assertEquals("201", pve.lastCloneParams["newid"])
        assertEquals("web-02", pve.lastCloneParams["name"])
        assertEquals("1", pve.lastCloneParams["full"])
        assertEquals("local-zfs", pve.lastCloneParams["storage"])
        assertEquals("lab", pve.lastCloneParams["pool"])
        assertEquals(1, pve.requestsTo("/qemu/100/clone", "POST").size)
    }

    @Test
    fun `clones a container using hostname`() {
        val req = CloneRequest(
            type = GuestType.LXC,
            sourceVmid = 200,
            newId = 202,
            name = "runner-2",
            full = true,
        )
        val upid = client.cloneGuest("pve", GuestType.LXC, 200, req.toParams())
        assertTrue(upid!!.startsWith("UPID:pve:"))
        assertEquals("lxc" to 200, pve.cloned)
        assertEquals("runner-2", pve.lastCloneParams["hostname"])
        assertNull(pve.lastCloneParams["name"])
    }

    @Test
    fun `linked clone omits the storage`() {
        val req = CloneRequest(
            type = GuestType.QEMU,
            sourceVmid = 100,
            newId = 201,
            name = "linked",
            full = false,
            sourceIsTemplate = true,
        )
        client.cloneGuest("pve", GuestType.QEMU, 100, req.toParams())
        assertEquals("0", pve.lastCloneParams["full"])
        assertNull(pve.lastCloneParams["storage"])
    }
}
