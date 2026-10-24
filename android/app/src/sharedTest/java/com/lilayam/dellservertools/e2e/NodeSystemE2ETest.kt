package com.lilayam.dellservertools.e2e

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NodeSystemE2ETest : ProxmoxE2ETestBase() {

    private fun waitFor(tag: String) = waitUntil { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

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
        tap("net-add-bridge")
        type("net-cidr", "192.0.2.60/24")
        tap("net-ports-eno1")
        tap("net-save")
        waitUntil { pve.network["vmbr2"]?.get("cidr") == "192.0.2.60/24" }
        assertEquals("eno1", pve.network.getValue("vmbr2")["bridge_ports"])

        tap("net-apply")
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
        tap("svc-start-postfix")
        confirmInDialog("Start")
        waitUntil { pve.lastCall("POST", "/nodes/pve/services/postfix/start") != null }

        tap("sys-tab-updates")
        waitForText("pve-manager")
        tap("apt-refresh")
        waitUntil { pve.lastCall("POST", "/nodes/pve/apt/update") != null }

        tap("sys-tab-repos")
        waitFor("repo-add-no-subscription")
        tap("repo-add-no-subscription")
        waitUntil { "no-subscription" in pve.addedRepos }

        tap("sys-tab-syslog")
        waitForText("Started pveproxy", substring = true)
    }
}
