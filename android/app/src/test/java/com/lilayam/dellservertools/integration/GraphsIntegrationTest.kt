package com.lilayam.dellservertools.integration

import com.lilayam.dellservertools.core.proxmox.GuestType
import com.lilayam.dellservertools.core.proxmox.PinnedTls
import com.lilayam.dellservertools.core.proxmox.ProxmoxClient
import com.lilayam.dellservertools.core.proxmox.ProxmoxCredentials
import com.lilayam.dellservertools.core.proxmox.Timeframe
import com.lilayam.dellservertools.testing.FakeProxmox
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GraphsIntegrationTest {
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
    fun `asks for averaged samples of the chosen time span`() {
        assertEquals(2, client.nodeRrd("pve", Timeframe.DAY).size)
        assertEquals(mapOf("timeframe" to "day", "cf" to "AVERAGE"), pve.calls.last().params)

        val guest = client.guestRrd("pve", GuestType.QEMU, 100, Timeframe.WEEK)
        assertEquals("/nodes/pve/qemu/100/rrddata", pve.calls.last().path.substringBefore('?'))
        assertEquals(listOf(0.25, null, 0.5), guest.map { it.values["cpu"] })

        assertEquals(listOf(100.0, 200.0), client.storageRrd("pve", "local", Timeframe.YEAR).map { it.values["used"] })
        assertEquals("year", pve.calls.last().params["timeframe"])
    }
}
