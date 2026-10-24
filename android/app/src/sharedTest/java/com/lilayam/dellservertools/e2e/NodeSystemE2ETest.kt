package com.lilayam.dellservertools.e2e

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NodeSystemE2ETest : ProxmoxE2ETestBase() {

    private fun waitFor(tag: String) = waitUntil { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    // The tabs are lazy lists that compose only what is on screen, buttons are disabled while a change runs (a tap
    // on a disabled one is dropped), and a snackbar can cover a button, so: scroll, wait, click through semantics.
    private fun tapInList(tag: String) {
        waitUntil {
            runCatching { compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(tag)) }.isSuccess &&
                compose.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun confirmInDialog(label: String) {
        waitUntil { compose.onAllNodes(hasText(label) and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText(label) and hasAnyAncestor(isDialog())).performClick()
    }

    private fun openSystem() {
        addServerAndConnect()
        waitForText("100 · web-01")
        waitForText("pve")
        compose.onNodeWithText("pve").performClick()
        waitForText("Node pve")
        waitFor("node-system")
        tap("node-system")
        waitForText("Node pve system")
    }

    @Test
    fun addsABridgeAndAppliesTheNetwork() {
        openSystem()
        waitFor("net-vmbr0")
        tapInList("net-add-bridge")
        type("net-cidr", "192.0.2.60/24")
        tap("net-ports-eno1")
        tap("net-save")
        waitUntil { pve.network["vmbr2"]?.get("cidr") == "192.0.2.60/24" }
        assertEquals("eno1", pve.network.getValue("vmbr2")["bridge_ports"])

        tapInList("net-apply")
        confirmInDialog("Apply")
        waitUntil { pve.networkApplied }
    }

    @Test
    fun changesDnsStartsAServiceAndAddsARepository() {
        openSystem()

        tap("sys-tab-dns")
        waitFor("dns-search")
        type("dns-search", "lab.example.org", replace = true)
        tap("dns-save")
        waitUntil { pve.dnsSearch == "lab.example.org" }

        tap("sys-tab-services")
        waitFor("svc-start-postfix")
        tapInList("svc-start-postfix")
        confirmInDialog("Start")
        waitUntil { pve.lastCall("POST", "/nodes/pve/services/postfix/start") != null }

        tap("sys-tab-updates")
        waitForText("pve-manager")
        tapInList("apt-refresh")
        waitUntil { pve.lastCall("POST", "/nodes/pve/apt/update") != null }

        tap("sys-tab-repos")
        waitFor("repo-add-no-subscription")
        tapInList("repo-add-no-subscription")
        waitUntil { "no-subscription" in pve.addedRepos }

        tap("sys-tab-syslog")
        waitForText("Started pveproxy", substring = true)
    }
}
