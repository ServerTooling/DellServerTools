package com.lilayam.dellservertools.e2e

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lilayam.dellservertools.core.vnc.Keysyms
import org.junit.Assert.assertEquals
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
        // Enabled once the VM's status says it is running.
        waitUntil { runCatching { compose.onNodeWithTag("guest-console").assertIsEnabled() }.isSuccess }
        tap("guest-console")

        waitForText("Console 100")
        waitUntil {
            compose.onAllNodesWithText("Connected to vm-100", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(pve.vnc.authenticated)
        waitUntil { pve.vnc.updatesSent > 0 }

        tap("vnc-key-enter")
        waitUntil { Keysyms.ENTER in pve.vnc.keysDown }

        // "@" and capitals need Shift held, or the VM gets "2" and lower case.
        type("vnc-type", "a@B")
        tap("vnc-send")
        val typed = listOf('a'.code, Keysyms.SHIFT, '@'.code, Keysyms.SHIFT, 'B'.code)
        waitUntil { pve.vnc.keysDown.toList().takeLast(5) == typed }
    }

    @Test
    fun reconnectsAfterTheConnectionWasCutInTheBackground() {
        pve.vmRunning.set(true)
        addServerAndConnect()
        waitForText("100 · web-01")
        compose.onNodeWithText("100 · web-01").performClick()
        waitUntil { runCatching { compose.onNodeWithTag("guest-console").assertIsEnabled() }.isSuccess }
        tap("guest-console")
        waitUntil { pve.vnc.updatesSent > 0 }

        // As Android does to a backgrounded app's sockets.
        leaveAndReturn { pve.vnc.disconnectAll() }

        waitUntil { pve.vnc.connections == 2 && pve.vnc.authenticated }
        waitForText("Connected to vm-100", substring = true)
        assertEquals(2, pve.requestsTo("/nodes/pve/qemu/100/vncproxy", "POST").size)
    }
}
