package com.lilayam.dellservertools.e2e

import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
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
abstract class ProxmoxE2ETestBase : E2ETest() {
    protected lateinit var pve: FakeProxmox

    @Before
    fun startFakeProxmox() {
        pve = FakeProxmox()
    }

    @After
    fun stopFakeProxmox() = pve.close()

    protected fun addServerAndConnect() {
        launch()
        tap("add-proxmox")
        type("field-name", "Lab PVE")
        type("field-host", "127.0.0.1")
        type("field-port", pve.port.toString(), replace = true)
        type("field-password", "s3cret")
        // The fake serves pve-runners' API on the same port.
        type("field-runners-port", pve.port.toString(), replace = true)
        tap("field-remember")
        tap("save")

        waitForText("Lab PVE")
        // The newest entry: on Robolectric a profile saved by an earlier test of the class can outlive its cleanup.
        compose.onAllNodesWithText("Lab PVE").onLast().performClick()

        // First connection: the self-signed certificate must be approved, showing its fingerprint.
        waitForText("Trust this server?")
        compose.onNodeWithText(PinnedTls.fingerprint(pve.certificate.certificate)).assertExists()
        compose.onNodeWithText("Trust and connect").performClick()

        waitForText("Proxmox VE 8.2.4-8.2")
    }
}
