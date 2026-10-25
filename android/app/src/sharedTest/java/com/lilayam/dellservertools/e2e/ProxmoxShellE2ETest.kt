package com.lilayam.dellservertools.e2e

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The node's shell and a container's console, drawn by the app's terminal over Proxmox's termproxy. */
@RunWith(AndroidJUnit4::class)
class ProxmoxShellE2ETest : ProxmoxE2ETestBase() {

    private fun waitForOutput(text: String) =
        waitUntil { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun nodeShellOpensAndTakesInput() {
        addServerAndConnect()
        waitForText("100 · web-01")
        waitForText("pve")
        compose.onNodeWithText("pve").performClick()
        waitForText("Node pve")
        waitUntil { compose.onAllNodesWithTag("node-shell").fetchSemanticsNodes().isNotEmpty() }
        tap("node-shell")

        waitForText("pve shell")
        waitForOutput("root@pve:~#")
        waitUntil { runCatching { compose.onNodeWithTag("shell-send").assertIsEnabled() }.isSuccess }
        type("shell-input", "pveversion")
        tap("shell-send")

        waitUntil { "pveversion\r" in pve.nodeShell.input }
        waitForOutput("pveversion")
        assertEquals(listOf("80x25"), pve.nodeShell.sizes)
    }

    @Test
    fun containerConsoleOpensNatively() {
        addServerAndConnect()
        waitForText("100 · web-01")
        scrollListTo("200 · gh-runner-1") // the container card is below the fold
        compose.onNodeWithText("200 · gh-runner-1").performClick()
        waitUntil { runCatching { compose.onNodeWithTag("guest-console").assertIsEnabled() }.isSuccess }
        tap("guest-console")

        waitForText("Console 200")
        waitForOutput("root@gh-runner-1:~#")
        assertEquals(1, pve.ctConsole.logins)
    }
}
