package com.lilayam.dellservertools.integration

import com.lilayam.dellservertools.core.proxmox.GuestType
import com.lilayam.dellservertools.core.proxmox.PinnedTls
import com.lilayam.dellservertools.core.proxmox.ProxmoxClient
import com.lilayam.dellservertools.core.proxmox.ProxmoxCredentials
import com.lilayam.dellservertools.core.proxmox.RestoreRequest
import com.lilayam.dellservertools.testing.FakeProxmox
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RestoreIntegrationTest {
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
    fun `lists backups and reads the config inside one`() {
        val backups = client.backups("pve", "local")
        assertEquals(listOf(100, 200), backups.map { it.vmid })
        assertEquals(listOf(GuestType.QEMU, GuestType.LXC), backups.map { RestoreRequest.typeOf(it) })
        assertEquals(1, pve.requests.count { it.path.orEmpty().endsWith("/storage/local/content?content=backup") })

        val config = client.backupConfig("pve", backups[1].volid)
        assertEquals("gh-runner-1", config["hostname"])
        assertEquals("1", config["unprivileged"])
        // The volume id travels encoded in the query.
        assertTrue(pve.requests.any { it.path.orEmpty().contains("extractconfig?volume=local%3Abackup%2Fvzdump-lxc-200-") })
    }

    @Test
    fun `restores a vm over itself`() {
        val req = RestoreRequest(
            type = GuestType.QEMU,
            archive = "local:backup/vzdump-qemu-100-2026_10_13-01_00_00.vma.zst",
            vmid = 100,
            existingType = GuestType.QEMU,
            overwrite = true,
        )
        val upid = client.restoreGuest("pve", req)
        assertTrue(upid!!.startsWith("UPID:pve:"))
        assertEquals(1, pve.requestsTo("/nodes/pve/qemu", "POST").size)
        assertEquals(
            mapOf("vmid" to "100", "archive" to req.archive, "force" to "1"),
            pve.lastVmCreateParams,
        )
    }

    @Test
    fun `restores a container to a new id`() {
        val req = RestoreRequest(
            type = GuestType.LXC,
            archive = "local:backup/vzdump-lxc-200-2026_10_12-01_00_00.tar.zst",
            vmid = 201,
            storage = "local-zfs",
            unprivileged = true,
            unique = true,
        )
        client.restoreGuest("pve", req)
        assertEquals(1, pve.requestsTo("/nodes/pve/lxc", "POST").size)
        assertEquals(
            mapOf(
                "vmid" to "201",
                "ostemplate" to req.archive,
                "restore" to "1",
                "unprivileged" to "1",
                "storage" to "local-zfs",
                "unique" to "1",
            ),
            pve.lastCreateParams,
        )
    }
}
