package com.lilayam.dellservertools.core.proxmox

import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StorageMediaTest {
    private val gib = 1L shl 30

    private fun disk(dev: String, type: String, size: Long, used: String, model: String = "", byId: String = "", wwn: String = "") =
        PhysicalDisk("/dev/$dev", model, type, size, "PASSED", "", used, byIdLink = byId, wwn = wwn)

    /** rpool mirrored on two SSDs, a 4 TB HDD and two SAS drives Proxmox can't classify. */
    private val disks = listOf(
        disk("sda", "ssd", 224 * gib, "BIOS boot", "EXAMPLE_SSD", byId = "/dev/disk/by-id/ata-EXAMPLE_SSD_A1"),
        disk("sdb", "ssd", 224 * gib, "BIOS boot", "EXAMPLE_SSD", wwn = "0x5000000000000002"),
        disk("sdc", "hdd", 3726 * gib, "xfs", "ST4000VN008-2DR166"),
        disk("sdd", "unknown", 838 * gib, "ext4", "ST900MM0168"),
        disk("sde", "unknown", 838 * gib, "ext4", "ST900MM0168"),
        disk("sdf", "usb", 4 * gib, "iso9660", "USB_STICK"),
    )

    private val definitions = mapOf(
        "local" to mapOf("type" to "dir", "path" to "/var/lib/vz"),
        "local-zfs" to mapOf("type" to "zfspool", "pool" to "rpool/data"),
        "hdd-dir" to mapOf("type" to "dir", "path" to "/mnt/hdd-dir"),
        "sas-dir" to mapOf("type" to "dir", "path" to "/mnt/pve/sas-dir"),
        "backups" to mapOf("type" to "nfs", "path" to "/mnt/pve/backups"),
    )

    // The pool names its disks by ID, as the installer does.
    private val zfs = mapOf("rpool" to listOf("rpool", "mirror-0", "ata-EXAMPLE_SSD_A1-part3", "wwn-0x5000000000000002-part3"))

    private fun storage(id: String, type: String, total: Long) = StorageInfo(id, type, emptyList(), 0, total, true)

    @Test
    fun `labels each storage from the disks under it`() {
        val storages = listOf(
            storage("local", "dir", 150 * gib),
            storage("local-zfs", "zfspool", 198 * gib),
            storage("hdd-dir", "dir", 3725 * gib),
            storage("sas-dir", "dir", 825 * gib),
            storage("backups", "nfs", 1000 * gib),
        )
        val node = StorageMedia.NodeDisks(disks, definitions, zfs)
        assertEquals(
            mapOf("local" to "SSD", "local-zfs" to "SSD", "hdd-dir" to "HDD", "sas-dir" to "HDD"),
            StorageMedia.labels(storages, node),
        )
    }

    @Test
    fun `a directory made by the Disks panel is found by its device`() {
        val node = StorageMedia.NodeDisks(disks, definitions, zfs, mounts = mapOf("/mnt/pve/sas-dir" to "/dev/sdc1"))
        assertEquals(listOf(disks[2]), StorageMedia.disksOf(storage("sas-dir", "dir", 825 * gib), node))
    }

    @Test
    fun `a by-hand mount whose size fits disks of different kinds stays unlabelled`() {
        val mixed = disks + disk("sdg", "ssd", 838 * gib, "ext4")
        assertNull(StorageMedia.disksOf(storage("sas-dir", "dir", 825 * gib), StorageMedia.NodeDisks(mixed, definitions, zfs)))
    }

    @Test
    fun `an LVM install keeps local on the pve volume group`() {
        val defs = mapOf("local" to mapOf("type" to "dir", "path" to "/var/lib/vz"), "local-lvm" to mapOf("type" to "lvmthin", "vgname" to "pve"))
        val nvme = listOf(disk("nvme0n1", "nvme", 500 * gib, "LVM"))
        val node = StorageMedia.NodeDisks(nvme, defs, lvm = mapOf("pve" to listOf("/dev/nvme0n1p3")))
        assertTrue(StorageMedia.needsLvm(defs, emptyMap()))
        assertEquals(
            mapOf("local" to "NVMe", "local-lvm" to "NVMe"),
            StorageMedia.labels(listOf(storage("local", "dir", 90 * gib), storage("local-lvm", "lvmthin", 400 * gib)), node),
        )
    }

    @Test
    fun `mixed and unknown disks say so`() {
        assertEquals("SSD + HDD", StorageMedia.describe(listOf(disks[2], disks[0])))
        assertEquals("SSD + unknown", StorageMedia.describe(listOf(disks[0], disk("sdx", "unknown", 1, ""))))
        assertNull(StorageMedia.describe(listOf(disk("sdx", "unknown", 1, "", "SOME DISK"))))
    }

    @Test
    fun `Seagate model numbers tell hard drives from flash`() {
        assertEquals("HDD", StorageMedia.kind(disk("sdd", "unknown", 1, "", "ST900MM0168")))
        assertEquals("HDD", StorageMedia.kind(disk("sdd", "unknown", 1, "", "SEAGATE ST4000NM0035")))
        assertNull(StorageMedia.kind(disk("sdd", "unknown", 1, "", "ST400FM0053")))
        assertNull(StorageMedia.kind(disk("sdd", "unknown", 1, "", "PERC H700")))
    }

    @Test
    fun `reads the pools, volume groups and mounts it needs`() {
        assertEquals(setOf("rpool", "tank"), StorageMedia.poolsToRead(definitions + ("t" to mapOf("type" to "zfspool", "pool" to "tank/sub"))))
        assertFalse(StorageMedia.needsLvm(definitions, zfs))
        assertEquals(
            mapOf("pve" to listOf("/dev/sda3")),
            StorageMedia.lvmTree(JSONObject("""{"leaf":0,"children":[{"name":"pve","children":[{"name":"/dev/sda3"}]}]}""")),
        )
        assertEquals(
            mapOf("/mnt/pve/fast" to "/dev/disk/by-uuid/abc"),
            StorageMedia.directoryMounts(JSONArray("""[{"path":"/mnt/pve/fast/","device":"/dev/disk/by-uuid/abc","type":"ext4"}]""")),
        )
    }

    @Test
    fun `the disk list keeps the IDs pools use`() {
        val parsed = ProxmoxJson.disks(
            JSONArray("""[{"devpath":"/dev/sda","type":"ssd","by_id_link":"/dev/disk/by-id/ata-X","wwn":"0x5"},{"devpath":"/dev/sdb","wwn":"unknown"}]"""),
        )
        assertEquals(listOf("/dev/disk/by-id/ata-X" to "0x5", "" to ""), parsed.map { it.byIdLink to it.wwn })
    }
}
