package com.lilayam.dellservertools.e2e

import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditGuestE2ETest : ProxmoxE2ETestBase() {

    private fun openContainerEdit() {
        addServerAndConnect()
        waitForText("100 · web-01")
        scrollListTo("200 · gh-runner-1")
        compose.onNodeWithText("200 · gh-runner-1").performClick()
        waitUntil { compose.onAllNodesWithContentDescription("Edit").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Edit").performClick()
        waitForText("Edit CT 200")
        waitUntil { compose.onAllNodesWithTag("edit-submit").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun editsNetworkTagsAndRawSettingsOfAContainer() {
        openContainerEdit()
        type("edit-tags", "ci")
        tap("edit-add-net")
        waitUntil { compose.onAllNodesWithTag("edit-field-net1").fetchSemanticsNodes().isNotEmpty() }
        type("edit-add-key", "hookscript")
        type("edit-add-value", "local:snippets/hook.sh")
        tap("edit-add-submit")
        tap("edit-remove-features")
        tap("edit-submit")

        waitUntil { pve.lastConfigParams.isNotEmpty() }
        assertEquals("ci", pve.lastConfigParams["tags"])
        assertEquals("name=eth1,bridge=vmbr0,ip=dhcp,type=veth", pve.lastConfigParams["net1"])
        assertEquals("local:snippets/hook.sh", pve.lastConfigParams["hookscript"])
        assertEquals("features", pve.lastConfigParams["delete"])
    }

    @Test
    fun growsTheRootDisk() {
        openContainerEdit()
        tap("edit-grow-rootfs")
        compose.onNodeWithTag("grow-gb").performTextClearance()
        compose.onNodeWithTag("grow-gb").performTextInput("4")
        compose.onNodeWithTag("grow-submit").performClick()

        waitUntil { pve.lastResizeParams.isNotEmpty() }
        assertEquals(mapOf("disk" to "rootfs", "size" to "+4G"), pve.lastResizeParams)
    }
}
