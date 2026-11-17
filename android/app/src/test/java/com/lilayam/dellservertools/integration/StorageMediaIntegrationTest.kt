package com.lilayam.dellservertools.integration

import com.lilayam.dellservertools.core.proxmox.PinnedTls
import com.lilayam.dellservertools.core.proxmox.ProxmoxClient
import com.lilayam.dellservertools.core.proxmox.ProxmoxCredentials
import com.lilayam.dellservertools.core.proxmox.StorageMedia
import com.lilayam.dellservertools.testing.FakeProxmox
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class StorageMediaIntegrationTest {
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
    fun `labels the node's storages from storage cfg, the pool and the disks`() {
        val definitions = client.storageDefinitions()
        assertEquals("rpool/data", definitions.getValue("local-zfs")["pool"])
        assertEquals("/var/lib/vz", definitions.getValue("local")["path"])
        val zfs = StorageMedia.poolsToRead(definitions).associateWith { client.zfsPool("pve", it).devices.map { d -> d.name } }
        val node = StorageMedia.NodeDisks(
            client.disks("pve"),
            definitions,
            zfs,
            client.lvmPhysicalVolumes("pve"),
            client.directoryDevices("pve"),
        )
        // The fake's rpool mirrors an SSD and an HDD partition; local is a folder on the same pool.
        assertEquals(mapOf("local" to "SSD + HDD", "local-zfs" to "SSD + HDD"), StorageMedia.labels(client.storage("pve"), node))
        assertEquals(emptyMap<String, List<String>>(), node.lvm)
        assertEquals(emptyMap<String, String>(), node.mounts)
    }
}
