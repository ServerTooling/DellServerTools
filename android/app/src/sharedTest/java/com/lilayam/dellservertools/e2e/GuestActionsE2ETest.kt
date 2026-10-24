package com.lilayam.dellservertools.e2e

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GuestActionsE2ETest : ProxmoxE2ETestBase() {

    private fun openVm() {
        addServerAndConnect()
        waitForText("100 · web-01")
        compose.onNodeWithText("100 · web-01").performClick()
        waitUntil { compose.onAllNodesWithTag("guest-protection").fetchSemanticsNodes().isNotEmpty() }
    }

    /** The guest screen is a lazy list: buttons below the fold only exist once scrolled to. */
    private fun tapAction(tag: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(tag))
        waitEnabled(tag)
        tap(tag)
    }

    private fun waitEnabled(tag: String) =
        waitUntil { compose.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }

    private fun confirmInDialog(label: String) {
        waitUntil { compose.onAllNodes(hasText(label) and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText(label) and hasAnyAncestor(isDialog())).performClick()
    }

    @Test
    fun movesADiskTurnsOnProtectionAndConvertsToATemplate() {
        pve.extraImageStorage = true
        openVm()

        tapAction("guest-move-disk")
        waitUntil { compose.onAllNodesWithTag("move-target-fast").fetchSemanticsNodes().isNotEmpty() }
        tap("move-target-fast")
        tap("move-format-qcow2")
        tap("move-go")
        waitUntil { pve.lastCall("POST", "/nodes/pve/qemu/100/move_disk") != null }
        assertEquals(
            mapOf("disk" to "scsi0", "storage" to "fast", "delete" to "1", "format" to "qcow2"),
            pve.lastCall("POST", "/nodes/pve/qemu/100/move_disk")!!.params,
        )

        waitEnabled("guest-protection")
        tap("guest-protection")
        waitUntil { pve.lastConfigParams["protection"] == "1" }

        tapAction("guest-template")
        type("template-confirm", "100")
        tap("template-go")
        waitUntil { pve.vmTemplate }
        waitForText("· template", substring = true)
    }

    @Test
    fun snapshotsWithRamHibernatesAndStopsTheTask() {
        pve.vmRunning.set(true)
        openVm()

        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Take snapshot"))
        compose.onNodeWithText("Take snapshot").performClick()
        type("snapshot-name", "live")
        tap("snapshot-ram")
        tap("snapshot-go")
        waitUntil { pve.lastCall("POST", "/nodes/pve/qemu/100/snapshot") != null }
        assertEquals(mapOf("snapname" to "live", "vmstate" to "1"), pve.lastCall("POST", "/nodes/pve/qemu/100/snapshot")!!.params)

        // Hibernate starts a task that keeps running until it is stopped from the busy bar. Only set that once the
        // snapshot task has finished (Hibernate enabled again), or the snapshot task would never end either.
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Hibernate"))
        waitUntil { compose.onAllNodes(hasText("Hibernate") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        pve.taskRunning = true
        compose.onNode(hasText("Hibernate") and isEnabled()).performClick()
        confirmInDialog("Hibernate")
        waitUntil { pve.lastCall("POST", "/nodes/pve/qemu/100/status/suspend") != null }
        assertEquals(mapOf("todisk" to "1"), pve.lastCall("POST", "/nodes/pve/qemu/100/status/suspend")!!.params)

        waitUntil { compose.onAllNodesWithTag("busy-stop").fetchSemanticsNodes().isNotEmpty() }
        tap("busy-stop")
        confirmInDialog("Stop task")
        waitUntil { pve.calls.any { it.method == "DELETE" && it.path.startsWith("/nodes/pve/tasks/") } }
        waitUntil { compose.onAllNodesWithTag("busy-stop").fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun stopsARunningTaskFromItsLog() {
        pve.taskRunning = true
        addServerAndConnect()
        waitForText("100 · web-01")
        compose.onNodeWithContentDescription("Tasks").performClick()
        waitForText("qmstart 100")
        compose.onNodeWithText("qmstart 100").performClick()
        waitUntil { compose.onAllNodesWithTag("task-stop").fetchSemanticsNodes().isNotEmpty() }
        tap("task-stop")
        confirmInDialog("Stop task")
        waitUntil { pve.calls.any { it.method == "DELETE" && it.path.startsWith("/nodes/pve/tasks/") } }
        waitUntil { compose.onAllNodesWithTag("task-stop").fetchSemanticsNodes().isEmpty() }
    }
}
