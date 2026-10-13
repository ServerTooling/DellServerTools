package com.lilayam.dellservertools.e2e

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The pve-runners screens (overview, actions, settings) against the fake service. */
@RunWith(AndroidJUnit4::class)
class RunnersE2ETest : ProxmoxE2ETestBase() {

    /** For widgets outside the scrolling list (tabs, the save bar), which [tap] can't scroll to. */
    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performClick()
    }

    private fun openGroup(id: String) {
        compose.onNodeWithTag("runners-settings").performScrollToNode(hasTestTag("group-$id"))
        tap("group-$id")
    }

    @Test
    fun overviewActionsAndSettings() {
        addServerAndConnect()
        waitForText("100 · web-01")
        scrollListTo("GitHub runners (pve-runners)")
        tap("open-runners")

        // Overview: the status, its warnings and the configured org and pool.
        waitForText("1 / 4 runners")
        waitForText("gh-pve1-default-9000")
        compose.onNodeWithText("Warning: runner network has no isolation").assertExists()
        compose.onNodeWithTag("runners-overview").performScrollToNode(hasText("Org example-org"))

        // Actions: arguments are sent, a confirm is asked, and the output follows the run.
        click("runners-tab-Actions")
        type("arg-spawn-pool", "default")
        tap("run-spawn")
        waitForText("Start a runner now?")
        compose.onNodeWithText("Yes, run").performClick()
        waitUntil { pve.runners.lastActionArgs == mapOf("pool" to "default") }
        waitForText("Running since 04:30:00…")
        pve.runners.finishAction.set(true)
        waitForText("Done, ran 04:30:00–04:31:00")

        // Settings: a refused save shows the service's reason and leaves the edit in place.
        click("runners-tab-Settings")
        openGroup("s-this-host")
        type("field-proxmox.max_runners", "lots", replace = true)
        click("runners-save")
        waitForText("proxmox.max_runners must be a whole number, got \"lots\"")
        assertEquals(0, pve.runners.saves.get())

        type("field-proxmox.max_runners", "8", replace = true)
        tap("field-autoscale.enabled")
        click("runners-save")
        waitUntil { pve.runners.saves.get() == 1 }
        val saved = pve.runners.lastSave!!
        assertEquals("8", saved["proxmox.max_runners"])
        assertEquals("false", saved["autoscale.enabled"])
        assertEquals("4", saved["pool.default.cores"])
        assertEquals("default", saved["target.0.pools"])

        // After the restart the screens show what the service now has.
        click("runners-tab-Overview")
        waitForText("1 / 8 runners")
    }
}
