package com.lilayam.dellservertools.e2e

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lilayam.dellservertools.core.proxmox.PinnedTls
import com.lilayam.dellservertools.testing.FakeProxmox
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProxmoxNavigationE2ETest : ProxmoxE2ETestBase() {

    @Test
    fun certificateIsRememberedAndTasksAndNodeScreensWork() {
        addServerAndConnect()
        waitForText("100 · web-01")

        compose.onNodeWithContentDescription("Tasks").performClick()
        waitForText("qmstart 100")
        compose.onNodeWithText("qmstart 100").performClick()
        waitForText("TASK OK")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Back").performClick()

        waitForText("pve")
        compose.onNodeWithText("pve").performClick()
        waitForText("Node pve")
        waitForText("Xeon")
        compose.onNodeWithContentDescription("Back").performClick()

        // Re-opening the server must not ask about the certificate again.
        compose.onNodeWithContentDescription("Back").performClick()
        waitForText("Lab PVE")
        launch()
        waitForText("Lab PVE")
        compose.onNodeWithText("Lab PVE").performClick()
        waitForText("100 · web-01")
        compose.onNodeWithText("Trust this server?").assertDoesNotExist()
    }
}
