package com.lilayam.dellservertools.integration

import com.lilayam.dellservertools.core.proxmox.DownloadUrlRequest
import com.lilayam.dellservertools.core.proxmox.PinnedTls
import com.lilayam.dellservertools.core.proxmox.ProxmoxClient
import com.lilayam.dellservertools.core.proxmox.ProxmoxCredentials
import com.lilayam.dellservertools.core.proxmox.PruneOptions
import com.lilayam.dellservertools.core.proxmox.StorageForm
import com.lilayam.dellservertools.core.proxmox.StorageKind
import com.lilayam.dellservertools.testing.FakeProxmox
import java.io.ByteArrayInputStream
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class StorageIntegrationTest {
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
    fun `deletes a volume and updates a backup's notes and protection`() {
        val upid = client.deleteVolume("pve", "local", "local:iso/debian-12.7.0-amd64-netinst.iso")
        assertTrue(upid!!.startsWith("UPID:pve:"))
        // The volume id is one encoded path segment.
        assertTrue(pve.requests.any { it.method == "DELETE" && it.path == "/api2/json/nodes/pve/storage/local/content/local%3Aiso%2Fdebian-12.7.0-amd64-netinst.iso" })

        client.updateVolume("pve", "local", "local:backup/vzdump-qemu-100-2026_10_13-01_00_00.vma.zst", "before upgrade", true)
        val put = pve.calls.last { it.method == "PUT" }
        assertEquals("/nodes/pve/storage/local/content/local%3Abackup%2Fvzdump-qemu-100-2026_10_13-01_00_00.vma.zst", put.path)
        assertEquals(mapOf("notes" to "before upgrade", "protected" to "1"), put.params)
    }

    @Test
    fun `looks up a url and downloads it on the server`() {
        val meta = client.queryUrlMetadata("pve", "https://example.org/debian.iso")
        assertEquals("debian-13.1.0-amd64-netinst.iso", meta.filename)
        assertEquals(754974720L, meta.size)
        assertEquals(
            mapOf("url" to "https://example.org/debian.iso", "verify-certificates" to "1"),
            pve.lastCall("GET", "/nodes/pve/query-url-metadata")!!.params,
        )

        val req = DownloadUrlRequest("https://example.org/debian.iso", meta.filename, "iso", checksum = "abc123")
        assertTrue(client.downloadUrl("pve", "local", req)!!.startsWith("UPID:"))
        assertEquals(req.toParams(), pve.lastCall("POST", "/nodes/pve/storage/local/download-url")!!.params)
    }

    @Test
    fun `uploads a file as multipart with the file last`() {
        val data = ByteArray(10_000) { (it % 251).toByte() }
        val progress = mutableListOf<Long>()
        val upid = client.upload("pve", "local", "iso", "tiny.iso", data.size.toLong(), ByteArrayInputStream(data)) { progress += it }
        assertTrue(upid!!.startsWith("UPID:"))
        assertEquals(listOf(data.size.toLong()), progress)

        val request = pve.requestsTo("/nodes/pve/storage/local/upload", "POST").single()
        val type = request.getHeader("Content-Type")!!
        assertTrue(type.startsWith("multipart/form-data; boundary="))
        val boundary = type.substringAfter("boundary=")
        // Streamed with a fixed length, not chunked.
        assertEquals(pve.uploads.single().size.toString(), request.getHeader("Content-Length"))

        val body = pve.uploads.single()
        val text = String(body, Charsets.ISO_8859_1)
        val contentPart = text.indexOf("name=\"content\"\r\n\r\niso\r\n")
        val filePart = text.indexOf("name=\"filename\"; filename=\"tiny.iso\"\r\nContent-Type: application/octet-stream\r\n\r\n")
        assertTrue(contentPart in 0 until filePart)
        val start = filePart + "name=\"filename\"; filename=\"tiny.iso\"\r\nContent-Type: application/octet-stream\r\n\r\n".length
        assertTrue(data.contentEquals(body.copyOfRange(start, start + data.size)))
        assertEquals("\r\n--$boundary--\r\n", text.substring(start + data.size))
    }

    @Test
    fun `refuses a file shorter than announced`() {
        val result = runCatching {
            client.upload("pve", "local", "iso", "short.iso", 100, ByteArrayInputStream(ByteArray(10)))
        }
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("ended after 10 of 100 bytes"))
    }

    @Test
    fun `previews and prunes backups`() {
        val keep = PruneOptions(keepLast = 1)
        val preview = client.prunePreview("pve", "local", keep, vmid = 100)
        assertEquals(listOf("keep", "remove", "keep"), preview.map { it.mark })
        assertEquals(
            mapOf("prune-backups" to "keep-last=1", "vmid" to "100"),
            pve.lastCall("GET", "/nodes/pve/storage/local/prunebackups")!!.params,
        )

        assertTrue(client.pruneBackups("pve", "local", keep)!!.startsWith("UPID:"))
        assertEquals(mapOf("prune-backups" to "keep-last=1"), pve.lastCall("DELETE", "/nodes/pve/storage/local/prunebackups")!!.params)
    }

    @Test
    fun `adds, edits and removes a storage definition`() {
        val form = StorageForm("tank", StorageKind.ZFS, setOf("images", "rootdir"), fields = mapOf("pool" to "tank"))
        client.createStorage(form)
        assertEquals(form.createParams(), pve.lastCall("POST", "/storage")!!.params)

        val local = client.storageDefinition("local")
        assertEquals("dir", local["type"])
        assertEquals("iso,backup,vztmpl", local["content"])
        val edit = StorageForm.fromConfig(local)!!.copy(enabled = false)
        client.updateStorage(edit)
        assertEquals(mapOf("disable" to "1"), pve.lastCall("PUT", "/storage/local")!!.params)

        // Nothing changed: no request at all.
        val before = pve.calls.size
        client.updateStorage(StorageForm.fromConfig(local)!!)
        assertEquals(before, pve.calls.size)

        client.deleteStorage("local")
        assertTrue(pve.lastCall("DELETE", "/storage/local") != null)
    }

    @Test
    fun `scans the node for pools, volume groups and exports`() {
        assertEquals(listOf("tank"), client.scanZfs("pve"))
        assertEquals(listOf("pve"), client.scanLvm("pve"))
        assertEquals(listOf("data"), client.scanLvmThin("pve", "pve"))
        assertEquals(mapOf("vg" to "pve"), pve.lastCall("GET", "/nodes/pve/scan/lvmthin")!!.params)
        assertEquals(listOf("/export/iso"), client.scanNfs("pve", "192.0.2.20"))
        assertEquals(mapOf("server" to "192.0.2.20"), pve.lastCall("GET", "/nodes/pve/scan/nfs")!!.params)
    }
}
