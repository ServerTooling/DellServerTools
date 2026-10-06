package com.lilayam.dellservertools.e2e

import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CloneGuestE2ETest : ProxmoxE2ETestBase() {

    @Test
    fun clonesAVmFromTheForm() {
        addServerAndConnect()
        waitForText("100 · web-01")
        compose.onNodeWithText("100 · web-01").performClick()

        // The Clone action in the guest screen's top bar appears once it opens.
        waitUntil { compose.onAllNodesWithContentDescription("Clone").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Clone").performClick()

        waitForText("Clone VM 100")
        // Storage loads asynchronously; the submit button appears only once it has.
        waitUntil { compose.onAllNodesWithTag("clone-submit").fetchSemanticsNodes().isNotEmpty() }
        // New ID and name are the first fields (no scrolling); set a deterministic name.
        type("clone-name", "web-02", replace = true)
        tap("clone-submit")

        waitUntil { pve.lastCloneParams.isNotEmpty() }
        assertEquals("qemu" to 100, pve.cloned)
        assertEquals("201", pve.lastCloneParams["newid"])
        assertEquals("web-02", pve.lastCloneParams["name"])
        assertEquals("1", pve.lastCloneParams["full"])
        // Back on the overview after the task finishes.
        waitForText("100 · web-01")
    }
}
