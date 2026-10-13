package com.lilayam.dellservertools.core.proxmox

import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProxmoxJsonTest {

    @Test
    fun `parses cluster resources`() {
        val json = JSONArray(
            """
            [
              {"id":"node/pve","type":"node","node":"pve","status":"online","cpu":0.05,"maxcpu":16,
               "mem":8589934592,"maxmem":34359738368,"uptime":3600},
              {"id":"qemu/100","type":"qemu","node":"pve","vmid":100,"name":"ubuntu","status":"running",
               "cpu":0.25,"maxcpu":4,"mem":2147483648,"maxmem":4294967296,"tags":"web;prod"},
              {"id":"lxc/101","type":"lxc","node":"pve","vmid":101,"name":"dns","status":"stopped","template":0},
              {"id":"qemu/9000","type":"qemu","node":"pve","vmid":9000,"name":"tmpl","status":"stopped","template":1},
              {"id":"storage/pve/local","type":"storage","node":"pve","storage":"local","status":"available",
               "disk":1000,"maxdisk":4000}
            ]
            """,
        )
        val resources = ProxmoxJson.resources(json)
        assertEquals(5, resources.size)

        val node = resources[0]
        assertEquals("pve", node.name)
        assertNull(node.guestType)
        assertTrue(node.isRunning)

        val vm = resources[1]
        assertEquals(GuestType.QEMU, vm.guestType)
        assertEquals(100, vm.vmid)
        assertEquals(listOf("web", "prod"), vm.tags)
        assertTrue(vm.isRunning)

        val ct = resources[2]
        assertEquals(GuestType.LXC, ct.guestType)
        assertFalse(ct.isRunning)
        assertFalse(ct.template)

        assertTrue(resources[3].template)
        assertEquals("local", resources[4].storage)
        assertEquals("local", resources[4].name)
    }

    @Test
    fun `parses node status`() {
        val status = ProxmoxJson.nodeStatus(
            JSONObject(
                """
                {"cpu":0.1,"uptime":86400,"loadavg":["0.10","0.20","0.30"],"pveversion":"pve-manager/8.2.4",
                 "kversion":"Linux 6.8.12","memory":{"used":1,"total":2},"swap":{"used":0,"total":0},
                 "rootfs":{"used":3,"total":4},"cpuinfo":{"cpus":16,"model":"Intel(R) Xeon(R) CPU X5670"}}
                """,
            ),
        )
        assertEquals(16, status.cpuCount)
        assertEquals(listOf("0.10", "0.20", "0.30"), status.loadAverage)
        assertEquals(2, status.memTotal)
        assertEquals("pve-manager/8.2.4", status.pveVersion)
    }

    @Test
    fun `guest status recognises paused vms`() {
        val status = ProxmoxJson.guestStatus(JSONObject("""{"status":"running","qmpstatus":"paused","name":"vm"}"""))
        assertTrue(status.isRunning)
        assertTrue(status.isPaused)
        assertNull(status.lock)
    }

    @Test
    fun `snapshots are ordered by time with current last`() {
        val snaps = ProxmoxJson.snapshots(
            JSONArray(
                """
                [{"name":"current","parent":"b","running":1},
                 {"name":"b","snaptime":200,"description":"after\n"},
                 {"name":"a","snaptime":100}]
                """,
            ),
        )
        assertEquals(listOf("a", "b", "current"), snaps.map { it.name })
        assertEquals("after", snaps[1].description)
        assertTrue(snaps[2].isCurrent)
    }

    @Test
    fun `tasks are newest first and running ones have no end`() {
        val tasks = ProxmoxJson.tasks(
            JSONArray(
                """
                [{"upid":"UPID:1","node":"pve","type":"qmstart","id":"100","user":"root@pam","starttime":10,"endtime":12,"status":"OK"},
                 {"upid":"UPID:2","node":"pve","type":"vzdump","id":"101","user":"root@pam","starttime":20}]
                """,
            ),
        )
        assertEquals(listOf("UPID:2", "UPID:1"), tasks.map { it.upid })
        assertTrue(tasks[0].isRunning)
        assertTrue(tasks[1].isOk)
    }

    @Test
    fun `task log is ordered by line number`() {
        val log = ProxmoxJson.taskLog(JSONArray("""[{"n":2,"t":"second"},{"n":1,"t":"first"}]"""))
        assertEquals(listOf("first", "second"), log)
    }

    @Test
    fun `task status`() {
        assertTrue(ProxmoxJson.taskStatus(JSONObject("""{"status":"running"}""")).running)
        val done = ProxmoxJson.taskStatus(JSONObject("""{"status":"stopped","exitstatus":"OK"}"""))
        assertFalse(done.running)
        assertEquals("OK", done.exitStatus)
    }

    @Test
    fun `config puts the important keys first and drops the digest`() {
        val config = ProxmoxJson.config(
            JSONObject("""{"digest":"x","net0":"virtio=..","boot":"order=scsi0","scsi0":"local-lvm:vm-100-disk-0","name":"vm","memory":2048}"""),
        )
        assertEquals(listOf("memory", "name", "scsi0", "net0", "boot"), config.map { it.first })
    }

    @Test
    fun `formats sizes and durations`() {
        assertEquals("512 B", Format.bytes(512))
        assertEquals("1.0 KiB", Format.bytes(1024))
        assertEquals("1.5 GiB", Format.bytes(1610612736))
        assertEquals("100 GiB", Format.bytes(107374182400))
        assertEquals("1d 1h", Format.duration(90000))
        assertEquals("2m 5s", Format.duration(125))
        assertEquals("-", Format.duration(0))
        assertEquals("25%", Format.percent(0.25))
    }

    @Test
    fun `tls fingerprints use the colon separated format Proxmox shows`() {
        assertEquals(
            "BA:78:16:BF:8F:01:CF:EA:41:41:40:DE:5D:AE:22:23:B0:03:61:A3:96:17:7A:9C:B4:10:FF:61:F2:00:15:AD",
            PinnedTls.fingerprint("abc".toByteArray()),
        )
    }

    @Test
    fun `parses storage status and keeps only the extra config keys`() {
        val status = ProxmoxJson.storageStatus(
            JSONObject("""{"type":"nfs","content":"backup, iso","shared":1,"enabled":1,"active":0,"used":5,"avail":6,"total":11}"""),
        )
        assertEquals(listOf("backup", "iso"), status.content)
        assertTrue(status.shared)
        assertFalse(status.active)

        val config = ProxmoxJson.storageConfig(
            JSONObject("""{"storage":"nas","type":"nfs","content":"backup","shared":1,"digest":"x","server":"192.0.2.10","export":"/backup"}"""),
        )
        assertEquals(listOf("export" to "/backup", "server" to "192.0.2.10"), config)
    }

    @Test
    fun `parses storage content grouped by type, newest first`() {
        val volumes = ProxmoxJson.storageContent(
            JSONArray(
                """
                [
                  {"volid":"nas:backup/vzdump-lxc-200-2026_01_01.tar.zst","content":"backup","size":10,"vmid":"200","ctime":100},
                  {"volid":"nas:backup/vzdump-qemu-100-2026_01_02.vma.zst","content":"backup","size":20,"vmid":100,"ctime":200,
                   "notes":" weekly ","protected":1},
                  {"volid":"nas:iso/debian.iso","content":"iso","format":"iso","size":30}
                ]
                """,
            ),
        )
        assertEquals(listOf(100, 200, null), volumes.map { it.vmid })
        assertEquals("vzdump-qemu-100-2026_01_02.vma.zst", volumes[0].name)
        assertEquals("weekly", volumes[0].notes)
        assertTrue(volumes[0].protected)
        assertEquals("debian.iso", volumes[2].name)
    }

    @Test
    fun `flattens the ZFS vdev tree depth first`() {
        val pool = ProxmoxJson.zfsPool(
            JSONObject(
                """
                {"name":"tank","state":"DEGRADED","status":"One or more devices has been removed.","errors":"No known data errors",
                 "children":[{"name":"tank","state":"DEGRADED","read":0,"write":0,"cksum":0,"children":[
                   {"name":"raidz1-0","state":"DEGRADED","read":0,"write":0,"cksum":0,"children":[
                     {"name":"sdc","state":"ONLINE","read":0,"write":0,"cksum":0},
                     {"name":"sdd","state":"REMOVED","read":"0","write":"0","cksum":"0","msg":"was /dev/sdd1"}]}]}]}
                """,
            ),
        )
        assertEquals("DEGRADED", pool.state)
        assertEquals(listOf("tank" to 0, "raidz1-0" to 1, "sdc" to 2, "sdd" to 2), pool.devices.map { it.name to it.depth })
        assertEquals("was /dev/sdd1", pool.devices.last().msg)
    }

    @Test
    fun `parses physical disks`() {
        val disks = ProxmoxJson.disks(
            JSONArray(
                """[{"devpath":"/dev/sdb","model":"HDD","type":"hdd","size":2,"health":"PASSED","wearout":"N/A","used":"LVM"},
                    {"devpath":"/dev/sda","model":"SSD","type":"ssd","size":1,"health":"FAILED","wearout":12}]""",
            ),
        )
        assertEquals(listOf("/dev/sda", "/dev/sdb"), disks.map { it.devPath })
        assertEquals("12", disks[0].wearout)
        assertEquals("", disks[0].used)
    }
}
