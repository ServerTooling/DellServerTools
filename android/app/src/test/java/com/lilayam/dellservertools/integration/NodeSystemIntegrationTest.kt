package com.lilayam.dellservertools.integration

import com.lilayam.dellservertools.core.proxmox.DnsSettings
import com.lilayam.dellservertools.core.proxmox.NetworkForm
import com.lilayam.dellservertools.core.proxmox.PinnedTls
import com.lilayam.dellservertools.core.proxmox.ProxmoxClient
import com.lilayam.dellservertools.core.proxmox.ProxmoxCredentials
import com.lilayam.dellservertools.testing.FakeProxmox
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class NodeSystemIntegrationTest {
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
    fun `adds a bridge, edits one and applies`() {
        assertEquals(listOf("eno1", "vmbr0", "vmbr1"), client.networkInterfaces("pve").map { it.iface })
        client.createNetwork("pve", NetworkForm("vmbr2", "bridge", cidr = "192.0.2.60/24"))
        assertEquals("192.0.2.60/24", pve.network.getValue("vmbr2")["cidr"])

        val vmbr0 = client.networkInterfaces("pve").first { it.iface == "vmbr0" }
        client.updateNetwork("pve", NetworkForm.fromInterface(vmbr0).copy(comments = "uplink"))
        assertEquals(mapOf("type" to "bridge", "comments" to "uplink"), pve.lastCall("PUT", "/nodes/pve/network/vmbr0")!!.params)

        assertTrue(client.applyNetwork("pve")!!.startsWith("UPID:"))
        assertTrue(pve.networkApplied)
        client.revertNetwork("pve")
        assertTrue(pve.lastCall("DELETE", "/nodes/pve/network") != null)
        client.deleteNetwork("pve", "vmbr2")
        assertTrue(!pve.network.containsKey("vmbr2"))
    }

    @Test
    fun `dns, hosts and time`() {
        client.setDns("pve", DnsSettings("lab.example.org", listOf("192.0.2.1", "192.0.2.2")))
        assertEquals(mapOf("search" to "lab.example.org", "dns1" to "192.0.2.1", "dns2" to "192.0.2.2"), pve.lastCall("PUT", "/nodes/pve/dns")!!.params)
        assertEquals("lab.example.org", client.dns("pve").search)

        val (hosts, digest) = client.hosts("pve")
        client.setHosts("pve", hosts + "192.0.2.7 nas\n", digest)
        assertEquals("d1", pve.lastCall("POST", "/nodes/pve/hosts")!!.params["digest"])
        assertTrue(pve.hostsFile.endsWith("192.0.2.7 nas\n"))

        client.setTimezone("pve", "UTC")
        assertEquals("UTC", client.timezone("pve"))
    }

    @Test
    fun `services, updates, repositories and journal`() {
        assertEquals(listOf("postfix", "pveproxy"), client.services("pve").map { it.name })
        assertTrue(client.serviceAction("pve", "postfix", "start")!!.startsWith("UPID:"))
        assertTrue(pve.lastCall("POST", "/nodes/pve/services/postfix/start") != null)
        assertThrows(IllegalArgumentException::class.java) { client.serviceAction("pve", "postfix", "rm") }

        assertEquals("pve-manager", client.pendingUpdates("pve").single().name)
        assertTrue(client.refreshUpdates("pve")!!.startsWith("UPID:"))

        val repos = client.aptRepositories("pve")
        client.setAptRepositoryEnabled("pve", repos.entries[1], false, repos.digest)
        assertEquals(
            mapOf("path" to "/etc/apt/sources.list", "index" to "1", "enabled" to "0", "digest" to "abc"),
            pve.lastCall("POST", "/nodes/pve/apt/repositories")!!.params,
        )
        client.addStandardRepository("pve", "no-subscription", repos.digest)
        assertEquals(true, client.aptRepositories("pve").standard.first { it.handle == "no-subscription" }.enabled)

        assertEquals(2, client.journal("pve").size)
    }
}
