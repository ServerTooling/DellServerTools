package com.lilayam.dellservertools.core.proxmox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MoveDiskRequestTest {
    @Test
    fun `vm disks move with move_disk and the web ui's params`() {
        val r = MoveDiskRequest(GuestType.QEMU, "scsi0", "fast", deleteSource = true, format = "qcow2")
        assertEquals("move_disk", r.path)
        assertEquals(mapOf("disk" to "scsi0", "storage" to "fast", "delete" to "1", "format" to "qcow2"), r.toParams())
        assertNull(r.validate("local-zfs"))
        // Keeping the source leaves it as an unused disk, so no delete param.
        assertEquals(mapOf("disk" to "scsi0", "storage" to "fast"), MoveDiskRequest(GuestType.QEMU, "scsi0", "fast", false).toParams())
    }

    @Test
    fun `container volumes move with move_volume and no format`() {
        val r = MoveDiskRequest(GuestType.LXC, "rootfs", "fast", format = "qcow2")
        assertEquals("move_volume", r.path)
        assertEquals(mapOf("volume" to "rootfs", "storage" to "fast", "delete" to "1"), r.toParams())
        assertNotNull(r.validate("local-zfs"))
        assertNull(r.copy(format = "").validate("local-zfs"))
    }

    @Test
    fun `validation`() {
        assertEquals("Pick a disk", MoveDiskRequest(GuestType.QEMU, "", "fast").validate(""))
        assertEquals("Pick a target storage", MoveDiskRequest(GuestType.QEMU, "scsi0", "").validate("local-zfs"))
        assertEquals("It is already on local-zfs", MoveDiskRequest(GuestType.QEMU, "scsi0", "local-zfs").validate("local-zfs"))
        // Converting in place is allowed.
        assertNull(MoveDiskRequest(GuestType.QEMU, "scsi0", "local", format = "qcow2").validate("local"))
        assertNotNull(MoveDiskRequest(GuestType.QEMU, "scsi0", "fast", format = "vdi").validate("local-zfs"))
    }

    @Test
    fun `only real volumes are movable`() {
        val config = mapOf(
            "scsi0" to "local-zfs:vm-100-disk-0,size=32G",
            "efidisk0" to "local-zfs:vm-100-disk-1,efitype=4m,size=1M",
            "ide2" to "local:iso/installer.iso,media=cdrom",
            "ide0" to "local-zfs:vm-100-cloudinit,media=cdrom",
            "sata1" to "local-zfs:vm-100-cloudinit",
            "net0" to "virtio=BC:24:11:00:00:02,bridge=vmbr0",
        )
        assertEquals(listOf("efidisk0", "scsi0").sorted(), MoveDiskRequest.movable(GuestEdit(GuestType.QEMU, config)).sorted())
        val ct = mapOf("rootfs" to "local-zfs:subvol-200-disk-0,size=16G", "mp0" to "/srv/data,mp=/data", "mp1" to "local-zfs:subvol-200-disk-1,mp=/x")
        assertEquals(listOf("mp1", "rootfs").sorted(), MoveDiskRequest.movable(GuestEdit(GuestType.LXC, ct)).sorted())
        assertEquals("local-zfs", MoveDiskRequest.storageOf(config.getValue("scsi0")))
    }
}
