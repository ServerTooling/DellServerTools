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

/** Full flow against a fake Proxmox API running on the device, over HTTPS with a self-signed certificate. */
@RunWith(AndroidJUnit4::class)
class ProxmoxE2ETest : E2ETest() {
    private lateinit var pve: FakeProxmox

    @Before
    fun startFakeProxmox() {
        pve = FakeProxmox()
    }

    @After
    fun stopFakeProxmox() = pve.close()

    private fun addServerAndConnect() {
        launch()
        tap("add-proxmox")
        type("field-name", "Lab PVE")
        type("field-host", "127.0.0.1")
        type("field-port", pve.port.toString(), replace = true)
        type("field-password", "s3cret")
        tap("field-remember")
        tap("save")

        waitForText("Lab PVE")
        compose.onNodeWithText("Lab PVE").performClick()

        // First connection: the self-signed certificate must be approved, showing its fingerprint.
        waitForText("Trust this server?")
        compose.onNodeWithText(PinnedTls.fingerprint(pve.certificate.certificate)).assertExists()
        compose.onNodeWithText("Trust and connect").performClick()

        waitForText("Proxmox VE 8.2.4-8.2")
    }

    @Test
    fun overviewShowsNodesGuestsAndStorage() {
        addServerAndConnect()
        waitForText("100 · web-01")
        scrollListTo("200 · gh-runner-1")
        compose.onNodeWithText("#gh-runner").assertExists()
        scrollListTo("local")
        compose.onNodeWithText("local").assertExists()

        val login = pve.requestsTo("/access/ticket").single()
        assertEquals("POST", login.method)
    }

    @Test
    fun startAVmAndFollowTheTask() {
        addServerAndConnect()
        waitForText("100 · web-01")
        compose.onNodeWithText("100 · web-01").performClick()

        waitForText("Start")
        compose.onNodeWithText("clean").assertExists() // snapshot list
        compose.onNodeWithText("Start").performClick()

        waitUntil { pve.vmRunning.get() }
        val start = pve.requestsTo("/qemu/100/status/start", "POST").single()
        assertEquals("csrf-token", start.getHeader("CSRFPreventionToken"))

        // Task result reported and the screen refreshed to the running state.
        waitForText("Start VM 100: done")
        waitForText("Shut down")
        assertTrue(pve.requests.any { it.path!!.contains("/tasks/") && it.path!!.contains("/status") })
    }

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
