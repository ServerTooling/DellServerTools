package com.lilayam.dellservertools.e2e

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupJobsE2ETest : ProxmoxE2ETestBase() {

    private fun openBackupJobs() {
        addServerAndConnect()
        waitForText("100 · web-01")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("open-backup-jobs"))
        tap("open-backup-jobs")
        waitForText("Every day 21:00")
    }

    private fun waitFor(tag: String) = waitUntil { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun runsAJobAndShowsGuestsWithoutBackups() {
        openBackupJobs()
        waitForText("All guests except 200 → local")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("unprotected-200"))

        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("backup-run-backup-1a2b"))
        tap("backup-run-backup-1a2b")
        waitUntil { pve.lastCall("POST", "/nodes/pve/vzdump") != null }
        val params = pve.lastCall("POST", "/nodes/pve/vzdump")!!.params
        assertEquals("1", params["all"])
        assertEquals("200", params["exclude"])
    }

    @Test
    fun addsAJobAndDisablesAnother() {
        openBackupJobs()

        tap("backup-add")
        waitFor("backup-storage-local")
        tap("backup-preset-3")
        tap("backup-selection-INCLUDE")
        tap("backup-guest-100")
        tap("backup-save")
        waitUntil { pve.lastCall("POST", "/cluster/backup") != null }
        val created = pve.lastCall("POST", "/cluster/backup")!!.params
        assertEquals("sat 02:00", created["schedule"])
        assertEquals("100", created["vmid"])
        assertEquals("local", created["storage"])

        // Back on the list, which now has both jobs.
        waitForText("Saturday 02:00")
        tap("backup-job-backup-1a2b")
        waitFor("backup-enabled")
        tap("backup-enabled")
        waitUntil { compose.onAllNodes(hasTestTag("backup-enabled") and isOff()).fetchSemanticsNodes().isNotEmpty() }
        waitUntil { compose.onAllNodes(hasTestTag("backup-save") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        // The "Add backup job: done" snackbar can still cover the button, which would take a touch; click it directly.
        compose.onNodeWithTag("backup-save").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        waitUntil { pve.lastCall("PUT", "/cluster/backup/backup-1a2b") != null }
        assertEquals(mapOf("enabled" to "0"), pve.lastCall("PUT", "/cluster/backup/backup-1a2b")!!.params)
    }
}
