package com.lilayam.dellservertools.integration

import com.lilayam.dellservertools.core.proxmox.BackupJobForm
import com.lilayam.dellservertools.core.proxmox.BackupSelection
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

class BackupJobsIntegrationTest {
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
    fun `lists, adds, edits and removes jobs`() {
        val job = client.backupJobs().single()
        assertEquals("backup-1a2b", job.id)
        assertEquals(listOf(200), client.notBackedUp().map { it.vmid })

        client.createBackupJob(BackupJobForm(storage = "local", schedule = "sat 02:00", selection = BackupSelection.INCLUDE, vmids = setOf(100)))
        val created = pve.lastCall("POST", "/cluster/backup")!!.params
        assertEquals("sat 02:00", created["schedule"])
        assertEquals("100", created["vmid"])
        assertNull(created["all"])
        assertEquals(2, client.backupJobs().size)

        client.updateBackupJob(job.form.copy(enabled = false))
        assertEquals(mapOf("enabled" to "0"), pve.lastCall("PUT", "/cluster/backup/backup-1a2b")!!.params)
        // Nothing changed: no request at all.
        val puts = pve.calls.count { it.method == "PUT" }
        client.updateBackupJob(job.form)
        assertEquals(puts, pve.calls.count { it.method == "PUT" })

        client.deleteBackupJob("backup-1a2b")
        assertTrue(client.backupJobs().none { it.id == "backup-1a2b" })
    }

    @Test
    fun `runs a job now through vzdump`() {
        val upid = client.runBackupJob("pve", client.backupJobs().single().form)
        assertTrue(upid!!.startsWith("UPID:"))
        val params = pve.lastCall("POST", "/nodes/pve/vzdump")!!.params
        assertEquals("1", params["all"])
        assertEquals("200", params["exclude"])
        assertNull(params["schedule"])
    }
}
