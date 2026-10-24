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
class BootOrderE2ETest : ProxmoxE2ETestBase() {

    @Test
    fun putsTheCdDriveFirstInTheBootOrder() {
        addServerAndConnect()
        waitForText("100 · web-01")
        compose.onNodeWithText("100 · web-01").performClick()
        waitUntil { compose.onAllNodesWithContentDescription("Edit").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Edit").performClick()
        waitForText("Edit VM 100")
        waitUntil { compose.onAllNodesWithTag("edit-submit").fetchSemanticsNodes().isNotEmpty() }

        tap("boot-on-ide2")
        waitUntil { compose.onAllNodesWithTag("boot-up-ide2").fetchSemanticsNodes().isNotEmpty() }
        tap("boot-up-ide2")
        tap("boot-up-ide2")
        waitUntil { compose.onAllNodesWithText("1. ide2").fetchSemanticsNodes().isNotEmpty() }
        tap("edit-submit")

        waitUntil { pve.lastConfigParams.isNotEmpty() }
        assertEquals(mapOf("boot" to "order=ide2;scsi0;net0"), pve.lastConfigParams)
    }
}
