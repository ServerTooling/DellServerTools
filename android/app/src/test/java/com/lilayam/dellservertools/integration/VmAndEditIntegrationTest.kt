package com.lilayam.dellservertools.integration

import com.lilayam.dellservertools.core.proxmox.GuestEdit
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
    fun `edits a vm config via the async POST`() {
        val upid = client.updateConfig("pve", GuestType.QEMU, 100, mapOf("memory" to "8192", "cores" to "6"))
        assertTrue(upid!!.startsWith("UPID:pve:"))
        pve.requestsTo("/qemu/100/config", "POST").single()
        assertEquals("8192", pve.lastConfigParams["memory"])
        assertEquals("6", pve.lastConfigParams["cores"])
    }

    @Test
    fun `edits a container config via PUT, with added and removed keys`() {
        val edit = GuestEdit(GuestType.LXC, client.guestConfig("pve", GuestType.LXC, 200).toMap())
            .with("mp0", "local-zfs:8,mp=/data")
            .with("net1", "name=eth1,bridge=vmbr1,ip=dhcp,type=veth")
            .with("tags", "ci")
            .without("swap")
        client.updateConfig("pve", GuestType.LXC, 200, edit.changedParams())
        pve.requestsTo("/lxc/200/config", "PUT").single()
        assertEquals(
            mapOf(
                "mp0" to "local-zfs:8,mp=/data", "net1" to "name=eth1,bridge=vmbr1,ip=dhcp,type=veth",
                "tags" to "ci", "delete" to "swap",
            ),
            pve.lastConfigParams,
        )
    }

    @Test
    fun `grows a container disk`() {
        val upid = client.resizeDisk("pve", GuestType.LXC, 200, "rootfs", GuestEdit.growBy(8))
        assertTrue(upid!!.startsWith("UPID:pve:"))
        pve.requestsTo("/lxc/200/resize", "PUT").single()
        assertEquals(mapOf("disk" to "rootfs", "size" to "+8G"), pve.lastResizeParams)
    }

    @Test
    fun `empty config change sends nothing`() {
        client.updateConfig("pve", GuestType.LXC, 200, emptyMap())
        assertTrue(pve.requestsTo("/lxc/200/config", "PUT").isEmpty())
    }

    @Test
    fun `deletes a vm with purge`() {
        val upid = client.deleteGuest("pve", GuestType.QEMU, 100, purge = true, destroyUnreferenced = true)
        assertTrue(upid!!.startsWith("UPID:pve:"))
        assertEquals("qemu" to 100, pve.deleted)
        val del = pve.requestsTo("/qemu/100", "DELETE").single()
        assertTrue(del.path!!.contains("purge=1"))
        assertTrue(del.path!!.contains("destroy-unreferenced-disks=1"))
    }

    @Test
    fun `deletes a container without purge`() {
        client.deleteGuest("pve", GuestType.LXC, 200, purge = false, destroyUnreferenced = false)
        assertEquals("lxc" to 200, pve.deleted)
        val del = pve.requestsTo("/lxc/200", "DELETE").single()
        assertTrue(!del.path!!.contains("purge"))
    }
}
