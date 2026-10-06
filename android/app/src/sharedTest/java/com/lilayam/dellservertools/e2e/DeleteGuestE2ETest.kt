package com.lilayam.dellservertools.e2e

import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeleteGuestE2ETest : ProxmoxE2ETestBase() {

    @Test
    fun deletesAGuestAfterTypingItsId() {
        addServerAndConnect()
        waitForText("100 · web-01")
        compose.onNodeWithText("100 · web-01").performClick()

        waitUntil { compose.onAllNodesWithContentDescription("Delete").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Delete").performClick()

        waitForText("Delete VM 100?")
        // Delete stays disabled until the typed id matches. (Dialog field: type without scrolling.)
        compose.onNodeWithTag("delete-confirm").performTextInput("100")
        compose.onNodeWithTag("delete-go").performClick()

        waitUntil { pve.deleted != null }
        assertEquals("qemu" to 100, pve.deleted)
        // Back on the overview.
        waitForText("100 · web-01")
    }
}
