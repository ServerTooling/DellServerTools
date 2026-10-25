package com.lilayam.dellservertools.e2e

import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lilayam.dellservertools.core.vnc.Keysyms
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VmConsoleE2ETest : ProxmoxE2ETestBase() {

    @Test
    fun drawsTheVmScreenAndSendsKeys() {
        pve.vmRunning.set(true)
        addServerAndConnect()
        waitForText("100 · web-01")
        compose.onNodeWithText("100 · web-01").performClick()
        waitUntil { compose.onAllNodesWithTag("guest-console").fetchSemanticsNodes().isNotEmpty() }
        tap("guest-console")

        waitForText("Console 100")
        waitUntil {
            compose.onAllNodesWithText("Connected to vm-100", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(pve.vnc.authenticated)
        waitUntil { pve.vnc.updatesSent > 0 }

        tap("vnc-key-enter")
        waitUntil { Keysyms.ENTER in pve.vnc.keysDown }
    }
}
