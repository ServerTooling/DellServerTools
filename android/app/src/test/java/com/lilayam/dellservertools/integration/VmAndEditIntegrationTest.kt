package com.lilayam.dellservertools.integration

import com.lilayam.dellservertools.core.proxmox.GuestType
import com.lilayam.dellservertools.core.proxmox.PinnedTls
import com.lilayam.dellservertools.core.proxmox.ProxmoxClient
import com.lilayam.dellservertools.core.proxmox.ProxmoxCredentials
import com.lilayam.dellservertools.core.proxmox.VmRequest
import com.lilayam.dellservertools.testing.FakeProxmox
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class VmAndEditIntegrationTest {
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
    fun `lists isos`() {
        val isos = client.isoImages("pve", "local")
        assertTrue(isos.any { it.name.contains("debian-12.7") })
    }

    @Test
    fun `creates a vm with the expected params`() {
        val req = VmRequest(
            vmid = 105, name = "test-vm", cores = 4, sockets = 1, memoryMb = 4096,
            diskStorage = "local-zfs", diskGb = 32, isoVolid = "local:iso/debian-12.7.0-amd64-netinst.iso", bridge = "vmbr0",
        )
        val upid = client.createQemu("pve", req.toParams())
        assertTrue(upid!!.startsWith("UPID:pve:"))
        assertEquals("105", pve.lastVmCreateParams["vmid"])
        assertEquals("local-zfs:32", pve.lastVmCreateParams["scsi0"])
        assertEquals("local:iso/debian-12.7.0-amd64-netinst.iso,media=cdrom", pve.lastVmCreateParams["ide2"])
        assertEquals("order=scsi0;ide2;net0", pve.lastVmCreateParams["boot"])
    }

    @Test
    fun `edits a guest config via PUT`() {
        client.updateConfig("pve", GuestType.QEMU, 100, mapOf("memory" to "8192", "cores" to "6"))
        val put = pve.requestsTo("/qemu/100/config", "PUT").single()
        assertEquals("PUT", put.method)
        assertEquals("8192", pve.lastConfigParams["memory"])
        assertEquals("6", pve.lastConfigParams["cores"])
    }

    @Test
    fun `empty config change sends nothing`() {
        client.updateConfig("pve", GuestType.LXC, 200, emptyMap())
        assertTrue(pve.requestsTo("/lxc/200/config", "PUT").isEmpty())
    }
}
