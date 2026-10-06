package com.lilayam.dellservertools.integration

import com.lilayam.dellservertools.core.proxmox.LxcRequest
import com.lilayam.dellservertools.core.proxmox.PinnedTls
import com.lilayam.dellservertools.core.proxmox.ProxmoxClient
import com.lilayam.dellservertools.core.proxmox.ProxmoxCredentials
import com.lilayam.dellservertools.testing.FakeProxmox
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CreateCtIntegrationTest {
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
    fun `reads create options`() {
        assertEquals(201, client.nextVmid())
        val storages = client.storage("pve")
        assertEquals(listOf("local"), storages.filter { it.holds("vztmpl") }.map { it.storage })
        assertEquals(listOf("local-zfs"), storages.filter { it.holds("rootdir") }.map { it.storage })
        assertEquals(listOf("vmbr0", "vmbr1"), client.bridges("pve"))
        val templates = client.templates("pve", "local")
        assertTrue(templates.any { it.name.contains("debian-12") })
    }

    @Test
    fun `creates a container and posts the right params`() {
        val req = LxcRequest(
            vmid = 201,
            hostname = "gh-runner-1",
            ostemplate = "local:vztmpl/debian-12-standard_12.7-1_amd64.tar.zst",
            storage = "local-zfs",
            diskGb = 16,
            cores = 4,
            memoryMb = 8192,
            swapMb = 2048,
            bridge = "vmbr0",
            useDhcp = true,
            unprivileged = true,
            nesting = true,
            password = "s3cret",
        )
        val upid = client.createLxc("pve", req.toParams())
        assertTrue(upid!!.startsWith("UPID:pve:"))
        assertEquals("201", pve.lastCreateParams["vmid"])
        assertEquals("gh-runner-1", pve.lastCreateParams["hostname"])
        assertEquals("local-zfs:16", pve.lastCreateParams["rootfs"])
        assertEquals("nesting=1", pve.lastCreateParams["features"])
        assertEquals("1", pve.lastCreateParams["unprivileged"])
        assertEquals("name=eth0,bridge=vmbr0,ip=dhcp", pve.lastCreateParams["net0"])
    }

    @Test
    fun `lists and downloads a template`() {
        val available = client.availableTemplates("pve")
        assertTrue(available.any { it.name.contains("debian-12") })
        val upid = client.downloadTemplate("pve", "local", "system/debian-12-standard_12.7-1_amd64.tar.zst")
        assertTrue(upid!!.startsWith("UPID:pve:"))
        assertEquals(1, pve.requestsTo("/aplinfo", "POST").size)
    }
}
