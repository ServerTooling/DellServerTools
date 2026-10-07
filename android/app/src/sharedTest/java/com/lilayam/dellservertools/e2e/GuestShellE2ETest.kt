package com.lilayam.dellservertools.e2e

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GuestShellE2ETest : ProxmoxE2ETestBase() {

    @Test
    fun containerShellOpensTheNativeHostTerminal() {
        addServerAndConnect()
        waitForText("100 · web-01")            // overview populated
        scrollListTo("200 · gh-runner-1")      // the container card is below the fold
        compose.onNodeWithText("200 · gh-runner-1").performClick()

        // The native Shell action enables once the running container's status loads.
        waitForText("Running")
        tap("guest-shell")

        // It opens the Proxmox host's native SSH terminal (with its keyboard + key bar),
        // not the mobile-unfriendly web console.
        waitForText("Lab PVE shell")
    }
}
