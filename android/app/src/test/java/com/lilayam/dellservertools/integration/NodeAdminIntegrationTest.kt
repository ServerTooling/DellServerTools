package com.lilayam.dellservertools.integration

import com.lilayam.dellservertools.core.proxmox.AcmeAccountForm
import com.lilayam.dellservertools.core.proxmox.AcmeDomain
import com.lilayam.dellservertools.core.proxmox.CustomCertificateForm
import com.lilayam.dellservertools.core.proxmox.DiskCreateForm
import com.lilayam.dellservertools.core.proxmox.DiskKind
import com.lilayam.dellservertools.core.proxmox.PinnedTls
import com.lilayam.dellservertools.core.proxmox.ProxmoxClient
import com.lilayam.dellservertools.core.proxmox.ProxmoxCredentials
import com.lilayam.dellservertools.testing.FakeProxmox
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class NodeAdminIntegrationTest {
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
    fun `reads SMART, wipes and initializes a disk`() {
        val smart = client.smart("pve", "/dev/sdc")
        assertEquals("PASSED", smart.health)
        assertEquals("/dev/sdc", pve.calls.last { it.path.startsWith("/nodes/pve/disks/smart") }.params["disk"])
        assertTrue(client.wipeDisk("pve", "/dev/sdc")!!.startsWith("UPID:"))
        assertEquals(mapOf("disk" to "/dev/sdc"), pve.lastCall("PUT", "/nodes/pve/disks/wipedisk")!!.params)
        client.initGpt("pve", "/dev/sdc")
        assertEquals(mapOf("disk" to "/dev/sdc"), pve.lastCall("POST", "/nodes/pve/disks/initgpt")!!.params)
    }

    @Test
    fun `creates a ZFS pool and a directory, then destroys one`() {
        assertEquals(listOf("rpool"), client.diskVolumes("pve").map { it.name })
        client.createDiskVolume("pve", DiskCreateForm(DiskKind.ZFS, "tank", listOf("/dev/sdc"), raidLevel = "single"))
        assertEquals("single", pve.lastCall("POST", "/nodes/pve/disks/zfs")!!.params["raidlevel"])
        client.createDiskVolume("pve", DiskCreateForm(DiskKind.DIRECTORY, "usb", listOf("/dev/sdc")))
        assertEquals(listOf("rpool", "tank", "usb"), client.diskVolumes("pve").map { it.name })

        val usb = client.diskVolumes("pve").first { it.name == "usb" }
        client.destroyDiskVolume("pve", usb)
        val call = pve.calls.last { it.method == "DELETE" }
        assertEquals("/nodes/pve/disks/directory/usb", call.path.substringBefore('?'))
        assertEquals(mapOf("cleanup-config" to "1", "cleanup-disks" to "1"), call.params)
        assertEquals(listOf("rpool", "tank"), client.diskVolumes("pve").map { it.name })
    }

    @Test
    fun `uploads and removes a custom certificate`() {
        assertEquals(listOf("pve-ssl.pem"), client.certificates("pve").map { it.filename })
        val cert = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----"
        val info = client.uploadCertificate("pve", CustomCertificateForm(cert))
        assertEquals("CC:CC:CC", info!!.fingerprint)
        assertEquals(
            mapOf("certificates" to "$cert\n", "force" to "1", "restart" to "1"),
            pve.lastCall("POST", "/nodes/pve/certificates/custom")!!.params,
        )
        assertEquals(listOf("pve-ssl.pem", "pveproxy-ssl.pem"), client.certificates("pve").map { it.filename })
        client.deleteCustomCertificate("pve")
        assertNull(pve.customCertificate)
        assertEquals(mapOf("restart" to "1"), pve.lastCall("DELETE", "/nodes/pve/certificates/custom")!!.params)
    }

    @Test
    fun `acme domains, account and order`() {
        val acme = client.acmeSettings("pve")
        assertEquals(listOf("pve.example.org"), acme.domains.map { it.domain })
        client.setNodeConfig("pve", acme.setDomain(AcmeDomain(acme.freeSlot!!, "lab.example.org", "example-dns")))
        assertEquals("domain=lab.example.org,plugin=example-dns", pve.nodeConfig["acmedomain1"])
        assertEquals("cfg1", pve.lastCall("PUT", "/nodes/pve/config")!!.params["digest"])
        client.setNodeConfig("pve", client.acmeSettings("pve").removeDomain("acmedomain0"))
        assertEquals(listOf("lab.example.org"), client.acmeSettings("pve").domains.map { it.domain })

        assertEquals(listOf("example-dns"), client.acmeDnsPlugins())
        val dir = client.acmeDirectories().single().url
        val tos = client.acmeTermsOfService(dir)
        assertEquals("https://acme.example.org/terms.pdf", tos)
        client.registerAcmeAccount(AcmeAccountForm("lab", "admin@example.org", dir, tos))
        assertEquals(listOf("default", "lab"), client.acmeAccounts())

        assertTrue(client.orderAcmeCertificate("pve", renew = false)!!.startsWith("UPID:"))
        client.orderAcmeCertificate("pve", renew = true)
        assertEquals(mapOf("force" to "1"), pve.lastCall("PUT", "/nodes/pve/certificates/acme/certificate")!!.params)
    }
}
