package com.lilayam.dellservertools.core.proxmox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RestoreRequestTest {

    private fun vm() = RestoreRequest(
        type = GuestType.QEMU,
        archive = "local:backup/vzdump-qemu-100-2026_10_13-01_00_00.vma.zst",
        vmid = 100,
        existingType = GuestType.QEMU,
        overwrite = true,
    )

    private fun ct() = RestoreRequest(
        type = GuestType.LXC,
        archive = "local:backup/vzdump-lxc-200-2026_10_12-01_00_00.tar.zst",
        vmid = 201,
        unique = true,
    )

    private fun volume(volid: String, format: String = "", subtype: String = "") =
        StorageVolume(volid, "backup", format, 0, null, null, "", false, subtype)

    @Test
    fun `vm restore over the existing guest sends archive and force`() {
        val p = vm().copy(storage = "local-zfs", start = true).toParams()
        assertEquals(
            mapOf(
                "vmid" to "100",
                "archive" to "local:backup/vzdump-qemu-100-2026_10_13-01_00_00.vma.zst",
                "storage" to "local-zfs",
                "force" to "1",
                "start" to "1",
            ),
            p,
        )
    }

    @Test
    fun `container restore to a new id sends ostemplate, restore and unprivileged`() {
        val p = ct().toParams()
        assertEquals(
            mapOf(
                "vmid" to "201",
                "ostemplate" to "local:backup/vzdump-lxc-200-2026_10_12-01_00_00.tar.zst",
                "restore" to "1",
                "unprivileged" to "1",
                "unique" to "1",
            ),
            p,
        )
        assertEquals("0", ct().copy(unprivileged = false).toParams()["unprivileged"])
    }

    @Test
    fun `force is never sent for a free id`() {
        assertNull(ct().copy(overwrite = true).toParams()["force"])
    }

    @Test
    fun `validation`() {
        assertNull(vm().validate())
        assertNull(ct().validate())
        assertNotNull(ct().copy(vmid = 99).validate())
        assertNotNull(ct().copy(archive = "").validate())
        // An id in use needs Overwrite ticked, and only a guest of the same type can be replaced.
        assertNotNull(vm().copy(overwrite = false).validate())
        assertNotNull(ct().copy(existingType = GuestType.QEMU, overwrite = true).validate())
        assertNull(ct().copy(existingType = GuestType.LXC, overwrite = true).validate())
    }

    @Test
    fun `backup type from subtype, pbs format or file name`() {
        assertEquals(GuestType.QEMU, RestoreRequest.typeOf(volume("local:backup/x.vma.zst", subtype = "qemu")))
        assertEquals(GuestType.LXC, RestoreRequest.typeOf(volume("local:backup/vzdump-lxc-200-2026_10_12-01_00_00.tar.zst")))
        assertEquals(GuestType.LXC, RestoreRequest.typeOf(volume("local:backup/vzdump-openvz-200-2015_01_01-01_00_00.tar.gz")))
        assertEquals(GuestType.QEMU, RestoreRequest.typeOf(volume("local:backup/vzdump-qemu-100-2026_10_13-01_00_00.vma.zst")))
        assertEquals(GuestType.QEMU, RestoreRequest.typeOf(volume("pbs:backup/vm/100/2026-10-13T01:00:00Z", format = "pbs-vm")))
        assertEquals(GuestType.LXC, RestoreRequest.typeOf(volume("pbs:backup/ct/200/2026-10-13T01:00:00Z")))
        assertNull(RestoreRequest.typeOf(volume("local:backup/something-else.tar")))
    }

    @Test
    fun `backup config stops at the first snapshot section`() {
        val config = RestoreRequest.parseConfig("#note\narch: amd64\nhostname: gh-runner-1\nunprivileged: 1\n\n[clean]\nhostname: old\n")
        assertEquals(mapOf("arch" to "amd64", "hostname" to "gh-runner-1", "unprivileged" to "1"), config)
    }
}
