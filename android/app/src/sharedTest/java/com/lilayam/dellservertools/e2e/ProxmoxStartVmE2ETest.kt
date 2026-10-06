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
class ProxmoxStartVmE2ETest : ProxmoxE2ETestBase() {

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
}
