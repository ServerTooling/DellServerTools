package com.lilayam.dellservertools.core.proxmox

import org.json.JSONArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BackupJobTest {
    private val stored = BackupJob.parse(
        JSONArray(
            """[{"id":"backup-1a2b","type":"vzdump","schedule":"21:00","storage":"local","mode":"snapshot","compress":"zstd",
                 "all":1,"exclude":"200,101","enabled":1,"next-run":1792000000,"prune-backups":{"keep-weekly":"2","keep-last":"3"}},
                {"id":"other","type":"something-else"}]""",
        ),
    )

    @Test
    fun `parses jobs and their retention object`() {
        assertEquals(listOf("backup-1a2b"), stored.map { it.id })
        val job = stored.single()
        assertEquals(1792000000L, job.nextRun)
        val f = job.form
        assertEquals(BackupSelection.EXCLUDE, f.selection)
        assertEquals(setOf(101, 200), f.vmids)
        assertEquals(PruneOptions(keepLast = 3, keepWeekly = 2), f.prune)
        assertEquals("keep-last=3,keep-weekly=2", job.config["prune-backups"])
        assertEquals("", f.notesTemplate)
    }

    @Test
    fun `a new job sends every setting`() {
        val f = BackupJobForm(storage = "local", selection = BackupSelection.INCLUDE, vmids = setOf(200, 100), mailto = "ops@example.org")
        assertNull(f.validate())
        assertEquals(
            mapOf(
                "schedule" to "21:00", "storage" to "local", "mode" to "snapshot", "compress" to "zstd", "vmid" to "100,200",
                "enabled" to "1", "mailto" to "ops@example.org", "mailnotification" to "always", "notes-template" to "{{guestname}}",
            ),
            f.createParams(),
        )
        assertEquals(mapOf("all" to "1"), BackupJobForm(storage = "local").createParams().filterKeys { it == "all" })
    }

    @Test
    fun `an edit only sends what changed and deletes what went away`() {
        val f = stored.single().form
        assertEquals(emptyMap<String, String>(), f.updateParams())
        // Exclude order and the retention object's key order don't count as changes.
        assertEquals(emptyMap<String, String>(), f.copy(vmids = setOf(200, 101)).updateParams())

        val include = f.copy(selection = BackupSelection.INCLUDE, vmids = setOf(100), enabled = false, prune = null)
        assertEquals(
            mapOf("vmid" to "100", "enabled" to "0", "delete" to "all,exclude,prune-backups"),
            include.updateParams(),
        )
        assertEquals(mapOf("delete" to "exclude"), f.copy(selection = BackupSelection.ALL).updateParams())
        assertEquals(mapOf("schedule" to "sat 02:00"), f.copy(schedule = " sat 02:00 ").updateParams())
    }

    @Test
    fun `run now sends the job's backup settings to vzdump`() {
        val f = stored.single().form
        assertEquals(
            mapOf(
                "storage" to "local", "mode" to "snapshot", "compress" to "zstd", "all" to "1", "exclude" to "101,200",
                "prune-backups" to "keep-last=3,keep-weekly=2",
            ),
            f.runParams(),
        )
    }

    @Test
    fun `validation`() {
        val ok = BackupJobForm(storage = "local")
        assertNull(ok.validate())
        assertNotNull(ok.copy(schedule = "").validate())
        assertNotNull(ok.copy(schedule = "21:00; rm -rf /").validate())
        assertNull(ok.copy(schedule = "mon..fri 01:30").validate())
        assertNotNull(ok.copy(storage = "").validate())
        assertNotNull(ok.copy(selection = BackupSelection.INCLUDE).validate())
        assertNotNull(ok.copy(mode = "fast").validate())
        assertNotNull(ok.copy(mailto = "not-an-address").validate())
        assertNotNull(ok.copy(prune = PruneOptions(keepLast = -1)).validate())
    }

    @Test
    fun `not backed up guests`() {
        val list = UnprotectedGuest.parse(JSONArray("""[{"vmid":200,"name":"gh-runner-1","type":"lxc"},{"vmid":100,"name":"web-01","type":"qemu"}]"""))
        assertEquals(listOf(100, 200), list.map { it.vmid })
    }
}
