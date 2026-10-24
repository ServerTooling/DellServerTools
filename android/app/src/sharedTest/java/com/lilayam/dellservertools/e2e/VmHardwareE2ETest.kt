package com.lilayam.dellservertools.e2e

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VmHardwareE2ETest : ProxmoxE2ETestBase() {

    private fun openEditVm() {
        addServerAndConnect()
        waitForText("100 · web-01")
        compose.onNodeWithText("100 · web-01").performClick()
        waitUntil { compose.onAllNodesWithContentDescription("Edit").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Edit").performClick()
        waitForText("Edit VM 100")
        waitUntil { compose.onAllNodesWithTag("edit-submit").fetchSemanticsNodes().isNotEmpty() }
    }

    /** Waits for [tag]; on a timeout the failure shows every window's tree, dialogs included. */
    private fun waitForTag(tag: String) {
        try {
            waitUntil { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: ComposeTimeoutException) {
            val roots = compose.onAllNodes(isRoot())
            val trees = (0 until roots.fetchSemanticsNodes().size).joinToString("\n") { roots[it].printToString() }
            throw AssertionError("No $tag. Last calls: ${pve.calls.takeLast(4)}\n$trees", e)
        }
    }

    @Test
    fun picksAnIsoAndAddsTpmUsbAndCloudInit() {
        openEditVm()

        // The CD drive's ISO comes from a list of the node's ISOs instead of a typed volume id.
        compose.onNode(hasTestTag("cd-ide2")).performScrollTo()
        compose.onNode(hasContentDescription("Choose") and hasAnyAncestor(hasTestTag("cd-ide2"))).performClick()
        waitForText("debian-12.7.0-amd64-netinst.iso")
        compose.onNodeWithText("debian-12.7.0-amd64-netinst.iso").performClick()

        tap("dev-add")
        tap("dev-kind-tpm")
        tap("storage-choice-go")

        tap("dev-add")
        tap("dev-kind-usb")
        waitForTag("dev-pick-0")
        compose.onNode(hasTestTag("dev-pick-0")).performClick()

        tap("ci-add")
        tap("storage-choice-go")
        waitUntil { compose.onAllNodesWithTag("ci-user").fetchSemanticsNodes().isNotEmpty() }
        type("ci-user", "admin")

        tap("edit-submit")
        waitUntil { pve.lastConfigParams.isNotEmpty() }
        assertEquals(
            mapOf(
                "ide2" to "local:iso/debian-12.7.0-amd64-netinst.iso,media=cdrom",
                "tpmstate0" to "local-zfs:1,version=v2.0",
                "usb0" to "host=0781:5581",
                "ide0" to "local-zfs:cloudinit",
                "ciuser" to "admin",
            ),
            pve.lastConfigParams,
        )
    }
}
