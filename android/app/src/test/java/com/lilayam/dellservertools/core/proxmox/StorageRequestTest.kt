package com.lilayam.dellservertools.core.proxmox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StorageRequestTest {

    @Test
    fun `prune options round trip and keep everything when nothing is set`() {
        val o = PruneOptions.parse("keep-daily=7,keep-last=3, keep-monthly=6")
        assertEquals(PruneOptions(keepLast = 3, keepDaily = 7, keepMonthly = 6), o)
        assertEquals("keep-last=3,keep-daily=7,keep-monthly=6", o.toParam())
        assertTrue(o.prunes)

        assertEquals("keep-all=1", PruneOptions().toParam())
        assertFalse(PruneOptions().prunes)
        assertFalse(PruneOptions.parse("keep-all=1").prunes)
        assertEquals("keep-all=1", PruneOptions.parse(null).toParam())
    }

    @Test
    fun `prune options refuse negative counts and keep-all with counts`() {
        assertNotNull(PruneOptions(keepLast = -1).validate())
        assertNotNull(PruneOptions(keepLast = 1, keepAll = true).validate())
        assertNull(PruneOptions(keepWeekly = 0).validate())
    }

    @Test
    fun `file names must fit the content type`() {
        assertNull(StorageFiles.filenameError("iso", "debian-12.7.0-amd64-netinst.iso"))
        assertNull(StorageFiles.filenameError("iso", "virtio-win.IMG"))
        assertNull(StorageFiles.filenameError("vztmpl", "alpine-3.20-default_20240908_amd64.tar.xz"))
        assertNotNull(StorageFiles.filenameError("iso", "image.qcow2"))
        assertNotNull(StorageFiles.filenameError("vztmpl", "debian.iso"))
        assertNotNull(StorageFiles.filenameError("iso", "../etc/passwd.iso"))
        assertNotNull(StorageFiles.filenameError("iso", ".hidden.iso"))
        assertNotNull(StorageFiles.filenameError("iso", ""))
    }

    @Test
    fun `file name comes from the url path`() {
        assertEquals(
            "debian-13.1.0-amd64-netinst.iso",
            StorageFiles.filenameFromUrl("https://cdimage.example.org/debian-cd/current/debian-13.1.0-amd64-netinst.iso?x=1#y"),
        )
        assertEquals("my image.iso", StorageFiles.filenameFromUrl("https://example.org/isos/my%20image.iso"))
    }

    @Test
    fun `download request maps to the api params`() {
        val r = DownloadUrlRequest(
            url = " https://example.org/a.iso ",
            filename = "a.iso",
            content = "iso",
            checksum = "ABCDEF01",
            checksumAlgorithm = "sha512",
            verifyCertificates = false,
        )
        assertNull(r.validate())
        assertEquals(
            mapOf(
                "url" to "https://example.org/a.iso",
                "filename" to "a.iso",
                "content" to "iso",
                "checksum" to "abcdef01",
                "checksum-algorithm" to "sha512",
                "verify-certificates" to "0",
            ),
            r.toParams(),
        )
        // No checksum, certificates verified: only the three required params.
        assertEquals(setOf("url", "filename", "content"), r.copy(checksum = "", verifyCertificates = true).toParams().keys)
        assertNotNull(r.copy(url = "ftp://example.org/a.iso").validate())
        assertNotNull(r.copy(checksum = "not-hex").validate())
    }

    @Test
    fun `new directory storage maps to create params`() {
        val f = StorageForm(
            id = "usb-backups",
            kind = StorageKind.DIR,
            content = setOf("backup", "iso"),
            fields = mapOf("path" to "/mnt/usb"),
            prune = PruneOptions(keepLast = 2, keepWeekly = 4),
        )
        assertNull(f.validate())
        assertEquals(
            mapOf(
                "storage" to "usb-backups",
                "type" to "dir",
                "content" to "iso,backup",
                "path" to "/mnt/usb",
                "prune-backups" to "keep-last=2,keep-weekly=4",
            ),
            f.createParams(),
        )
    }

    @Test
    fun `new storage is validated per type`() {
        val pbs = StorageForm("pbs1", StorageKind.PBS, setOf("backup"), fields = mapOf("server" to "192.0.2.10", "datastore" to "main"))
        assertEquals("Enter the username", pbs.validate())
        val full = pbs.copy(fields = pbs.fields + mapOf("username" to "backup@pbs", "password" to "pw", "fingerprint" to "AB:CD"))
        assertNotNull(full.validate())
        val fp = (1..32).joinToString(":") { "AB" }
        val ok = full.copy(fields = full.fields + ("fingerprint" to fp))
        assertNull(ok.validate())
        assertEquals("pw", ok.createParams()["password"])
        assertEquals(fp, ok.createParams()["fingerprint"])

        assertNotNull(StorageForm("1bad", StorageKind.ZFS, setOf("images"), fields = mapOf("pool" to "tank")).validate())
        assertNotNull(StorageForm("zfs2", StorageKind.ZFS, setOf("iso"), fields = mapOf("pool" to "tank")).validate())
        assertNotNull(StorageForm("d", StorageKind.DIR, setOf("iso"), fields = mapOf("path" to "relative")).validate())
        assertNotNull(StorageForm("dir2", StorageKind.DIR, emptySet(), fields = mapOf("path" to "/x")).validate())
    }

    @Test
    fun `editing sends only what changed`() {
        val config = mapOf(
            "storage" to "local",
            "type" to "dir",
            "path" to "/var/lib/vz",
            "content" to "backup,iso,vztmpl",
            "prune-backups" to "keep-last=3",
        )
        val f = StorageForm.fromConfig(config)!!
        assertNull(f.validate())
        assertFalse(f.isNew)
        assertEquals(emptyMap<String, String>(), f.updateParams())

        val edited = f.copy(content = f.content + "snippets", enabled = false, prune = PruneOptions(keepLast = 5))
        assertEquals(
            mapOf("content" to "iso,vztmpl,backup,snippets", "disable" to "1", "prune-backups" to "keep-last=5"),
            edited.updateParams(),
        )

        // Re-enabling and dropping the retention both go into delete.
        val disabled = StorageForm.fromConfig(config + ("disable" to "1"))!!
        assertFalse(disabled.enabled)
        assertEquals(mapOf("delete" to "disable,prune-backups"), disabled.copy(enabled = true, prune = PruneOptions()).updateParams())
    }

    @Test
    fun `editing a pbs storage changes the password only when one is typed`() {
        val config = mapOf(
            "storage" to "pbs1",
            "type" to "pbs",
            "server" to "192.0.2.10",
            "datastore" to "main",
            "username" to "backup@pbs",
            "content" to "backup",
        )
        val f = StorageForm.fromConfig(config)!!
        assertEquals("", f["password"])
        assertNull(f.validate())
        assertEquals(emptyMap<String, String>(), f.updateParams())
        assertEquals(mapOf("password" to "new"), f.copy(fields = f.fields + ("password" to "new")).updateParams())
        assertEquals(
            mapOf("username" to "root@pam"),
            f.copy(fields = f.fields + ("username" to "root@pam")).updateParams(),
        )
        assertNull(StorageForm.fromConfig(config + ("type" to "rbd")))
    }

    @Test
    fun `prune preview is sorted by guest, newest first`() {
        val entries = PruneEntry.parse(
            org.json.JSONArray(
                """[{"volid":"s:backup/a","mark":"keep","ctime":1,"vmid":200,"type":"lxc"},
                    {"volid":"s:backup/b","mark":"remove","ctime":1,"vmid":100,"type":"qemu"},
                    {"volid":"s:backup/c","mark":"protected","ctime":2,"vmid":100,"type":"qemu"}]""",
            ),
        )
        assertEquals(listOf("c", "b", "a"), entries.map { it.name })
        assertEquals(listOf(false, true, false), entries.map { it.removed })
    }
}
