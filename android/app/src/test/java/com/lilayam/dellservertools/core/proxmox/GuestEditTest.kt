package com.lilayam.dellservertools.core.proxmox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GuestEditTest {
    private val ct = mapOf(
        "hostname" to "gh-runner-1", "cores" to "4", "memory" to "8192", "swap" to "512", "onboot" to "1",
        "features" to "keyctl=1", "rootfs" to "local-zfs:subvol-200-disk-0,size=16G",
        "mp0" to "/srv/data,mp=/data", "net0" to "name=eth0,bridge=vmbr0,ip=dhcp,type=veth",
        "unprivileged" to "1", "unused0" to "local-zfs:subvol-200-disk-1",
    )

    @Test
    fun `no changes yields empty params`() {
        assertTrue(GuestEdit(GuestType.LXC, ct).changedParams().isEmpty())
    }

    @Test
    fun `only changed and new keys are sent`() {
        val p = GuestEdit(GuestType.LXC, ct).with("memory", "16384").with("tags", "ci;runner").changedParams()
        assertEquals(mapOf("memory" to "16384", "tags" to "ci;runner"), p)
    }

    @Test
    fun `removed and cleared keys go into delete`() {
        val p = GuestEdit(GuestType.LXC, ct).with("swap", "").without("onboot").changedParams()
        assertEquals(mapOf("delete" to "swap,onboot"), p)
    }

    @Test
    fun `nesting toggles keep the other features`() {
        val on = GuestEdit(GuestType.LXC, ct).withFeature("nesting", true)
        assertTrue(on.feature("nesting"))
        assertEquals("keyctl=1,nesting=1", on.changedParams()["features"])
        val off = GuestEdit(GuestType.LXC, ct + ("features" to "nesting=1,keyctl=1")).withFeature("nesting", false)
        assertEquals("keyctl=1", off.changedParams()["features"])
    }

    @Test
    fun `turning a flag off deletes it unless it was an explicit 0`() {
        assertEquals("onboot", GuestEdit(GuestType.LXC, ct).withFlag("onboot", false).changedParams()["delete"])
        val explicit = GuestEdit(GuestType.LXC, ct + ("protection" to "0"))
        assertTrue(explicit.withFlag("protection", true).withFlag("protection", false).changedParams().isEmpty())
        assertEquals("1", GuestEdit(GuestType.LXC, ct).withFlag("protection", true).changedParams()["protection"])
    }

    @Test
    fun `vm agent keeps its other options`() {
        val vm = mapOf("name" to "vm1", "cores" to "2", "memory" to "2048", "agent" to "enabled=1,fstrim_cloned_disks=1")
        val e = GuestEdit(GuestType.QEMU, vm)
        assertTrue(e.agent)
        assertEquals("enabled=0,fstrim_cloned_disks=1", e.withAgent(false).changedParams()["agent"])
        assertEquals("1", GuestEdit(GuestType.QEMU, vm - "agent").withAgent(true).changedParams()["agent"])
    }

    @Test
    fun `agent enabled parses both 1 and enabled form`() {
        assertTrue(GuestEdit.agentEnabled("1"))
        assertTrue(GuestEdit.agentEnabled("enabled=1,fstrim_cloned_disks=1"))
        assertFalse(GuestEdit.agentEnabled("0"))
        assertFalse(GuestEdit.agentEnabled(null))
    }

    @Test
    fun `startup parts are written in proxmox order`() {
        val e = GuestEdit(GuestType.LXC, ct).withStartup("up", "30").withStartup("order", "2")
        assertEquals("order=2,up=30", e.changedParams()["startup"])
        assertEquals("2", e.startup("order"))
        assertTrue(e.withStartup("order", "").withStartup("up", "").changedParams().isEmpty())
    }

    @Test
    fun `validation refuses read-only keys, disk size edits and bad numbers`() {
        val e = GuestEdit(GuestType.LXC, ct)
        assertNull(e.validate())
        assertEquals("unprivileged can't be changed here", e.with("unprivileged", "0").validate())
        assertEquals("Use Grow disk to change the size of rootfs", e.with("rootfs", "local-zfs:subvol-200-disk-0,size=32G").validate())
        assertNull(e.with("rootfs", "local-zfs:subvol-200-disk-0,size=16G,quota=1").validate())
        assertEquals("unused0 can only be removed", e.with("unused0", "other").validate())
        assertNull(e.without("unused0").validate())
        assertEquals("At least 1 core", e.with("cores", "0").validate())
        assertEquals("CPU limit must be a number", e.with("cpulimit", "1.2.3").validate())
        assertEquals("\"Bad Key\" is not a valid setting name", e.with("Bad Key", "1").validate())
        assertEquals("Enter a hostname", e.with("hostname", "").validate())
    }

    @Test
    fun `growable disks skip bind mounts, cdroms and efi state`() {
        assertEquals(listOf("rootfs"), GuestEdit(GuestType.LXC, ct).growableDisks())
        val vm = mapOf(
            "name" to "vm", "scsi10" to "local-zfs:vm-1-disk-2,size=8G", "scsi2" to "local-zfs:vm-1-disk-1,size=32G",
            "ide2" to "local:iso/x.iso,media=cdrom,size=600M", "efidisk0" to "local-zfs:vm-1-disk-0,size=1M",
        )
        assertEquals(listOf("scsi2", "scsi10"), GuestEdit(GuestType.QEMU, vm).growableDisks())
        // A disk added in this edit doesn't exist yet, so it can't be grown.
        assertEquals(listOf("scsi2", "scsi10"), GuestEdit(GuestType.QEMU, vm).with("scsi0", "local-zfs:8").growableDisks())
    }

    @Test
    fun `next key and natural key order`() {
        val e = GuestEdit(GuestType.LXC, ct + ("net1" to "x") + ("net10" to "y"))
        assertEquals("net2", e.nextKey("net"))
        assertEquals("mp1", e.nextKey("mp"))
        assertEquals(listOf("net0", "net1", "net10"), e.allKeys().filter { it.startsWith("net") })
    }

    @Test
    fun `rebase keeps unsaved changes on a reloaded config`() {
        val e = GuestEdit(GuestType.LXC, ct).with("memory", "4096").without("onboot")
        val reloaded = ct + ("rootfs" to "local-zfs:subvol-200-disk-0,size=24G")
        val r = e.rebase(reloaded)
        assertEquals("local-zfs:subvol-200-disk-0,size=24G", r["rootfs"])
        assertEquals(mapOf("memory" to "4096", "delete" to "onboot"), r.changedParams())
    }

    @Test
    fun `grow size is relative`() {
        assertEquals("+8G", GuestEdit.growBy(8))
    }
}
