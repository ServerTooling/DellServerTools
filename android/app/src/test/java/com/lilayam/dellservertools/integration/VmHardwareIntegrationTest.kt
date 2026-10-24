package com.lilayam.dellservertools.integration

import com.lilayam.dellservertools.core.proxmox.PinnedTls
import com.lilayam.dellservertools.core.proxmox.ProxmoxClient
import com.lilayam.dellservertools.core.proxmox.ProxmoxCredentials
import com.lilayam.dellservertools.testing.FakeProxmox
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class VmHardwareIntegrationTest {
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
    fun `lists the node's usb and pci devices`() {
        assertEquals(listOf("0781:5581"), client.usbDevices("pve").map { "${it.vendorId}:${it.productId}" })
        assertEquals(listOf("0000:04:00.0"), client.pciDevices("pve").map { it.id })
    }

    @Test
    fun `regenerates the cloud-init drive with a put`() {
        client.regenerateCloudInit("pve", 100)
        assertNotNull(pve.lastCall("PUT", "/nodes/pve/qemu/100/cloudinit"))
        assertEquals("csrf-token", pve.requestsTo("/qemu/100/cloudinit", "PUT").single().getHeader("CSRFPreventionToken"))
    }
}
