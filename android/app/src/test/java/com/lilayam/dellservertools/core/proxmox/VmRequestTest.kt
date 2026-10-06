package com.lilayam.dellservertools.core.proxmox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VmRequestTest {

    private fun base() = VmRequest(
        vmid = 105,
        name = "test-vm",
        cores = 4,
        sockets = 1,
        memoryMb = 4096,
        diskStorage = "local-zfs",
        diskGb = 32,
        bridge = "vmbr0",
    )

    @Test
    fun `basic seabios vm maps to expected params`() {
        val p = base().toParams()
        assertEquals("105", p["vmid"])
        assertEquals("test-vm", p["name"])
        assertEquals("4", p["cores"])
        assertEquals("1", p["sockets"])
        assertEquals("4096", p["memory"])
        assertEquals("l26", p["ostype"])
        assertEquals("virtio-scsi-single", p["scsihw"])
        assertEquals("local-zfs:32", p["scsi0"])
        assertEquals("virtio,bridge=vmbr0,firewall=1", p["net0"])
        assertEquals("seabios", p["bios"])
        assertEquals("order=scsi0;net0", p["boot"])
        assertNull(p["ide2"])
        assertNull(p["machine"])
        assertNull(p["efidisk0"])
    }

    @Test
    fun `iso is attached as a cdrom and added to the boot order`() {
        val p = base().copy(isoVolid = "local:iso/debian-12.iso").toParams()
        assertEquals("local:iso/debian-12.iso,media=cdrom", p["ide2"])
        assertEquals("order=scsi0;ide2;net0", p["boot"])
    }

    @Test
    fun `ovmf adds an efi disk and q35 machine`() {
        val p = base().copy(bios = VmBios.OVMF).toParams()
        assertEquals("ovmf", p["bios"])
        assertEquals("q35", p["machine"])
        assertEquals("local-zfs:1,efitype=4m,pre-enrolled-keys=1", p["efidisk0"])
    }

    @Test
    fun `agent and start flags`() {
        assertEquals("1", base().copy(agent = true, start = true).toParams()["agent"])
        assertEquals("1", base().copy(start = true).toParams()["start"])
        assertEquals("0", base().copy(agent = false).toParams()["agent"])
    }

    @Test
    fun `validation catches bad input`() {
        assertNull(base().validate())
        assertNotNull(base().copy(name = "bad name").validate())
        assertNotNull(base().copy(diskStorage = "").validate())
        assertNotNull(base().copy(diskGb = 0).validate())
        assertNotNull(base().copy(cores = 0).validate())
        assertNotNull(base().copy(memoryMb = 8).validate())
        assertNotNull(base().copy(vmid = 10).validate())
        assertNull(base().copy(name = "web.example-1").validate())
    }
}
