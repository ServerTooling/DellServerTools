package com.lilayam.dellservertools.e2e

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Create-CT form against the fake Proxmox API: fill it in, submit, check the API params. */
@RunWith(AndroidJUnit4::class)
class CreateCtE2ETest : ProxmoxE2ETestBase() {

    @Test
    fun createsAContainerFromTheForm() {
        addServerAndConnect()
        waitForText("100 · web-01")

        compose.onNodeWithContentDescription("Create container or VM").performClick()
        compose.onNodeWithText("Container (LXC)").performClick()
        // Options load (templates, storage, bridges, next id).
        waitForText("New container on pve")
        waitForText("Create container")

        // Hostname defaults to ct-201; set our own.
        type("ct-hostname", "gh-runner-1", replace = true)
        type("ct-password", "s3cretpw")
        tap("ct-submit")

        waitUntil { pve.lastCreateParams.isNotEmpty() }
        assertEquals("201", pve.lastCreateParams["vmid"])
        assertEquals("gh-runner-1", pve.lastCreateParams["hostname"])
        assertEquals("local-zfs:16", pve.lastCreateParams["rootfs"])
        assertEquals("nesting=1", pve.lastCreateParams["features"])
        assertEquals("1", pve.lastCreateParams["unprivileged"])
        assertEquals("local:vztmpl/debian-12-standard_12.7-1_amd64.tar.zst", pve.lastCreateParams["ostemplate"])

        // Back on the overview after creation.
        waitForText("100 · web-01")
    }
}
