package com.lilayam.dellservertools.e2e

import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CreateVmAndEditE2ETest : ProxmoxE2ETestBase() {

    @Test
    fun createsAVmFromTheForm() {
        addServerAndConnect()
        waitForText("100 · web-01")

        compose.onNodeWithContentDescription("Create container or VM").performClick()
        compose.onNodeWithText("Virtual machine").performClick()

        waitForText("New VM on pve")
        waitForText("Create VM")
        type("vm-name", "test-vm", replace = true)
        tap("vm-submit")

        waitUntil { pve.lastVmCreateParams.isNotEmpty() }
        assertEquals("201", pve.lastVmCreateParams["vmid"])
        assertEquals("test-vm", pve.lastVmCreateParams["name"])
        assertEquals("local-zfs:32", pve.lastVmCreateParams["scsi0"])
        assertEquals("virtio-scsi-single", pve.lastVmCreateParams["scsihw"])
        waitForText("100 · web-01")
    }

    @Test
    fun editsAnExistingVm() {
        addServerAndConnect()
        waitForText("100 · web-01")
        compose.onNodeWithText("100 · web-01").performClick()
        // The Edit action in the guest screen's top bar is always visible once it opens.
        waitUntil { compose.onAllNodesWithContentDescription("Edit").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Edit").performClick()
        waitForText("Edit VM 100")
        // Config loads asynchronously; the submit button appears only once it has.
        waitUntil { compose.onAllNodesWithTag("edit-submit").fetchSemanticsNodes().isNotEmpty() }
        // Name is the first field (no scrolling); change it and wait for the new value to register.
        type("edit-name", "web-02", replace = true)
        waitUntil { compose.onAllNodesWithText("web-02").fetchSemanticsNodes().isNotEmpty() }
        tap("edit-submit")

        waitUntil { pve.lastConfigParams.isNotEmpty() }
        assertEquals("web-02", pve.lastConfigParams["name"])
    }
}
