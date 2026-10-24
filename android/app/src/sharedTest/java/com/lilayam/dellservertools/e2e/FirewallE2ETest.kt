package com.lilayam.dellservertools.e2e

import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirewallE2ETest : ProxmoxE2ETestBase() {

    private fun waitFor(tag: String) = waitUntil { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    // Buttons are disabled while a change runs, and a tap on a disabled one is silently dropped.
    private fun tapEnabled(tag: String) {
        waitUntil { compose.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        tap(tag)
    }

    private fun openDatacenterFirewall() {
        addServerAndConnect()
        waitForText("100 · web-01")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("open-firewall"))
        tap("open-firewall")
        waitForText("Datacenter firewall")
        waitFor("fw-rule-1")
    }

    private val dcRules get() = pve.firewallRules.getValue("/cluster/firewall/rules")

    @Test
    fun editsDatacenterRulesAndTurnsTheFirewallOn() {
        openDatacenterFirewall()

        tap("fw-rule-enable-1")
        waitUntil { dcRules[1]["enable"] == "1" }

        tapEnabled("fw-rule-up-1")
        waitUntil { dcRules[0]["macro"] == "Ping" }

        tapEnabled("fw-rule-add")
        tap("fw-proto-tcp")
        type("fw-dport", "443")
        tap("fw-rule-save")
        waitUntil { pve.lastCall("POST", "/cluster/firewall/rules") != null }
        assertEquals(
            mapOf("type" to "in", "action" to "ACCEPT", "enable" to "1", "proto" to "tcp", "dport" to "443"),
            pve.lastCall("POST", "/cluster/firewall/rules")!!.params,
        )
        waitFor("fw-rule-2")

        tap("fw-tab-options")
        waitFor("fw-opt-enable")
        tapEnabled("fw-opt-enable")
        waitUntil { pve.firewallOptions["/cluster/firewall"]?.get("enable") == "1" }
    }

    @Test
    fun addsAnAliasAndAnIpSetAddress() {
        openDatacenterFirewall()

        tap("fw-tab-aliases")
        waitFor("fw-alias-lab")
        tap("fw-alias-add")
        type("fw-alias-name", "office")
        type("fw-alias-cidr", "198.51.100.0/24")
        tap("fw-alias-save")
        waitUntil { pve.firewallAliases["office"]?.first == "198.51.100.0/24" }

        tap("fw-tab-ipsets")
        waitFor("fw-ipset-admins")
        tap("fw-ipset-admins")
        waitForText("192.0.2.10", substring = true)
        tap("fw-ipset-add-entry-admins")
        type("fw-ipset-value", "192.0.2.20")
        tap("fw-ipset-save")
        waitUntil { "192.0.2.20" in pve.ipSets.getValue("admins") }
    }

    @Test
    fun turnsOnAVmFirewallAndReadsItsLog() {
        addServerAndConnect()
        waitForText("100 · web-01")
        compose.onNodeWithText("100 · web-01").performClick()
        waitFor("guest-protection")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("guest-firewall"))
        tap("guest-firewall")
        waitForText("VM 100 firewall")

        tap("fw-tab-options")
        waitFor("fw-opt-enable")
        tap("fw-opt-enable")
        waitUntil { pve.firewallOptions["/nodes/pve/qemu/100/firewall"]?.get("enable") == "1" }

        tap("fw-tab-log")
        waitForText("policy DROP", substring = true)
    }
}
