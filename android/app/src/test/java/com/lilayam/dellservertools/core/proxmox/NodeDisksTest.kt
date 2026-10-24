package com.lilayam.dellservertools.core.proxmox

import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NodeDisksTest {
    private val enc: (String) -> String = { java.net.URLEncoder.encode(it, "UTF-8") }

    @Test
    fun `zfs mirror sends the devices, layout and storage flag`() {
        val form = DiskCreateForm(DiskKind.ZFS, "tank", listOf("/dev/sdc", "/dev/sdd"), raidLevel = "mirror", compression = "lz4")
        assertNull(form.validate())
        assertEquals(
            mapOf(
                "name" to "tank", "raidlevel" to "mirror", "devices" to "/dev/sdc,/dev/sdd", "ashift" to "12",
                "compression" to "lz4", "add_storage" to "1",
            ),
            form.params(),
        )
    }

    @Test
    fun `zfs layouts need enough disks`() {
        val one = DiskCreateForm(DiskKind.ZFS, "tank", listOf("/dev/sdc"))
        assertEquals("mirror needs at least 2 disks.", one.validate())
        assertNull(one.copy(raidLevel = "single").validate())
        val five = DiskCreateForm(DiskKind.ZFS, "tank", (1..5).map { "/dev/sd$it" }, raidLevel = "raid10")
        assertEquals("raid10 needs an even number of disks.", five.validate())
        assertEquals("raidz3 needs at least 5 disks.", five.copy(devices = five.devices.take(4), raidLevel = "raidz3").validate())
        assertEquals("ashift is 9 to 16.", five.copy(raidLevel = "raidz", ashift = "20").validate())
    }

    @Test
    fun `lvm, thin and directory take one disk`() {
        assertEquals(
            mapOf("name" to "data", "device" to "/dev/sdc", "add_storage" to "0"),
            DiskCreateForm(DiskKind.LVMTHIN, "data", listOf("/dev/sdc"), addStorage = false).params(),
        )
        assertEquals(
            mapOf("name" to "usb", "device" to "/dev/sdc", "filesystem" to "xfs", "add_storage" to "1"),
            DiskCreateForm(DiskKind.DIRECTORY, "usb", listOf("/dev/sdc"), filesystem = "xfs").params(),
        )
        assertEquals("LVM uses exactly one disk.", DiskCreateForm(DiskKind.LVM, "vg", listOf("/dev/sdc", "/dev/sdd")).validate())
        assertEquals("Pick a disk.", DiskCreateForm(DiskKind.LVM, "vg").validate())
        assertEquals(
            "Name: letters, digits, '-', '_' and '.', starting with a letter.",
            DiskCreateForm(DiskKind.LVM, "1vg", listOf("/dev/sdc")).validate(),
        )
    }

    @Test
    fun `only disks nothing uses can be built on`() {
        val disks = listOf(
            PhysicalDisk("/dev/sda", "", "ssd", 1, "PASSED", "", "ZFS"),
            PhysicalDisk("/dev/sdb", "", "hdd", 1, "PASSED", "", ""),
            PhysicalDisk("/dev/sdc", "", "hdd", 1, "PASSED", "", "partitions"),
        )
        assertEquals(listOf("/dev/sdb"), DiskCreateForm.unused(disks).map { it.devPath })
    }

    @Test
    fun `parses the four panels and builds destroy paths`() {
        val zfs = DiskVolume.zfs(JSONArray("""[{"name":"rpool","size":100,"alloc":40,"free":60,"health":"ONLINE","frag":3}]"""))
        assertEquals(DiskVolume(DiskKind.ZFS, "rpool", "ONLINE · frag 3%", 100, 40), zfs.single())
        val lvm = DiskVolume.lvm(JSONObject("""{"leaf":0,"children":[{"name":"pve","size":100,"free":30,"children":[{"name":"/dev/sda3"}]}]}"""))
        assertEquals(DiskVolume(DiskKind.LVM, "pve", "/dev/sda3", 100, 70), lvm.single())
        val thin = DiskVolume.lvmThin(JSONArray("""[{"lv":"data","vg":"pve","lv_size":100,"used":5}]""")).single()
        assertEquals("/nodes/pve/disks/lvmthin/data?volume-group=pve&cleanup-config=1&cleanup-disks=1", thin.destroyPath("pve", enc))
        val dir = DiskVolume.directories(JSONArray("""[{"path":"/mnt/pve/usb","device":"/dev/sdc1","type":"ext4"}]""")).single()
        assertEquals("usb", dir.name)
        assertEquals("/nodes/pve/disks/directory/usb?cleanup-config=1&cleanup-disks=1", dir.destroyPath("pve", enc))
    }

    @Test
    fun `smart attributes and text`() {
        val ata = SmartData.parse(
            JSONObject("""{"health":"PASSED","attributes":[{"id":"  5","name":"Reallocated_Sector_Ct","value":90,"threshold":10,"raw":"8","fail":"FAILING_NOW"}]}"""),
        )
        assertEquals(SmartAttribute("5", "Reallocated_Sector_Ct", "90", "10", "8", failing = true), ata.attributes.single())
        val nvme = SmartData.parse(JSONObject("""{"health":"PASSED","type":"text","text":"Percentage Used: 3%"}"""))
        assertEquals("Percentage Used: 3%", nvme.text)
        assertEquals(emptyList<SmartAttribute>(), nvme.attributes)
    }
}
