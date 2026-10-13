package com.lilayam.dellservertools.e2e

import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RestoreBackupE2ETest : ProxmoxE2ETestBase() {

    @Test
    fun restoresAVmOverItselfAfterTypingItsId() {
        addServerAndConnect()
        waitForText("100 · web-01")
        compose.onNodeWithText("100 · web-01").performClick()
        waitUntil { compose.onAllNodesWithTag("guest-restore").fetchSemanticsNodes().isNotEmpty() }
        tap("guest-restore")

        // Only this guest's backups are listed.
        waitForText("Restore 100")
        val vmBackup = "backup-vzdump-qemu-100-2026_10_13-01_00_00.vma.zst"
        waitUntil { compose.onAllNodesWithTag(vmBackup).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodesWithTag("backup-vzdump-lxc-200-2026_10_12-01_00_00.tar.zst").fetchSemanticsNodes().isEmpty())
        tap(vmBackup)

        // The target defaults to the guest itself, which must be confirmed as an overwrite.
        waitForText("Backup of \"web-01\".")
        tap("restore-overwrite")
        tap("restore-submit")
        type("restore-confirm", "100")
        tap("restore-go")

        waitUntil { pve.lastVmCreateParams.isNotEmpty() }
        assertEquals("100", pve.lastVmCreateParams["vmid"])
        assertEquals("local:backup/vzdump-qemu-100-2026_10_13-01_00_00.vma.zst", pve.lastVmCreateParams["archive"])
        assertEquals("1", pve.lastVmCreateParams["force"])
        assertEquals(null, pve.lastVmCreateParams["unique"])
    }

    @Test
    fun restoresAContainerBackupFromStorageToANewId() {
        addServerAndConnect()
        waitForText("100 · web-01")
        scrollListTo("local")
        compose.onNodeWithTag("storage-local").performClick()
        waitForText("Storage local")
        waitForText("/var/lib/vz")
        scrollListTo("vzdump-lxc-200-2026_10_12-01_00_00.tar.zst")
        tap("volume-vzdump-lxc-200-2026_10_12-01_00_00.tar.zst")

        // Preselected; the free id comes from Proxmox and the unprivileged flag from the backup's config.
        waitForText("Backup of \"gh-runner-1\".")
        waitUntil { compose.onAllNodesWithText("201").fetchSemanticsNodes().isNotEmpty() }
        tap("restore-submit")

        waitUntil { pve.lastCreateParams.isNotEmpty() }
        assertEquals("201", pve.lastCreateParams["vmid"])
        assertEquals("local:backup/vzdump-lxc-200-2026_10_12-01_00_00.tar.zst", pve.lastCreateParams["ostemplate"])
        assertEquals("1", pve.lastCreateParams["restore"])
        assertEquals("1", pve.lastCreateParams["unprivileged"])
        assertEquals("1", pve.lastCreateParams["unique"])
        assertEquals(null, pve.lastCreateParams["force"])
        // Back on the storage screen once the task finishes.
        waitForText("Storage local")
    }
}
