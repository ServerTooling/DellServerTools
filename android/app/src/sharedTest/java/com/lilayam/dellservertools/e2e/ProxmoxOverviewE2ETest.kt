package com.lilayam.dellservertools.e2e

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
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
class ProxmoxOverviewE2ETest : ProxmoxE2ETestBase() {

    @Test
    fun overviewShowsNodesGuestsAndStorage() {
        addServerAndConnect()
        waitForText("100 · web-01")
        scrollListTo("200 · gh-runner-1")
        // A runner's job tags are spelled out, with a link to the run; the other tags stay as tags.
        compose.onNodeWithText("#gh-runner  #owner-example-org  #runnerid-41").assertExists()
        compose.onNodeWithText("example-org/app · PR #12 (feature-login)").assertExists()
        compose.onNodeWithText("ci › unit-2-4 · run 42").assertExists()
        compose.onNodeWithTag("runner-run-200").assertExists()
        scrollListTo("local")
        compose.onNodeWithText("local").assertExists()

        val login = pve.requestsTo("/access/ticket").single()
        assertEquals("POST", login.method)
    }
}
