package com.lilayam.dellservertools.e2e

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
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
class NodeDisksE2ETest : ProxmoxE2ETestBase() {

    private fun waitFor(tag: String) = waitUntil { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun waitEnabled(tag: String) =
        waitUntil { compose.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }

    /** The tab's lazy list (the tab row scrolls too, but sideways and without indices). */
    private fun scrollTabTo(tag: String) {
        waitUntil { runCatching { compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(tag)) }.isSuccess }
    }

    private fun confirmInDialog(label: String) {
        waitUntil { compose.onAllNodes(hasText(label) and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText(label) and hasAnyAncestor(isDialog())).performClick()
    }

    private fun openSystemTab(tab: String) {
        addServerAndConnect()
        waitForText("100 · web-01")
        waitForText("pve")
        compose.onNodeWithText("pve").performClick()
        waitForText("Node pve")
        waitFor("node-system")
        tap("node-system")
        waitForText("Node pve system")
        tap("sys-tab-$tab")
    }

    @Test
    fun createsAndDestroysAZfsPoolAndWipesADisk() {
        openSystemTab("disks")
        waitFor("disk-sdc")

        scrollTabTo("disk-create-zfs")
        waitEnabled("disk-create-zfs")
        tap("disk-create-zfs")
        type("disk-name", "tank")
        tap("disk-raid-single")
        tap("disk-create-go")
        waitUntil { pve.diskVolumes["tank"] == "zfs" }
        assertEquals(
            mapOf(
                "name" to "tank", "raidlevel" to "single", "devices" to "/dev/sdc", "ashift" to "12",
                "compression" to "on", "add_storage" to "1",
            ),
            pve.lastCall("POST", "/nodes/pve/disks/zfs")!!.params,
        )

        scrollTabTo("vol-destroy-zfs-tank")
        waitEnabled("vol-destroy-zfs-tank")
        tap("vol-destroy-zfs-tank")
        type("destroy-confirm", "tank")
        tap("destroy-go")
        waitUntil { "tank" !in pve.diskVolumes.keys }

        scrollTabTo("disk-sdc")
        tap("disk-sdc")
        waitForText("SMART: PASSED")
        tap("disk-wipe")
        type("wipe-confirm", "sdc")
        tap("wipe-go")
        waitUntil { pve.lastCall("PUT", "/nodes/pve/disks/wipedisk") != null }
        assertEquals(mapOf("disk" to "/dev/sdc"), pve.lastCall("PUT", "/nodes/pve/disks/wipedisk")!!.params)
    }

    @Test
    fun uploadsACertificateAndOrdersOneForANewDomain() {
        openSystemTab("certs")
        waitFor("cert-pve-ssl.pem")
        tap("cert-upload")
        type("cert-pem", "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----")
        tap("cert-upload-go")
        waitUntil { pve.customCertificate == "CC:CC:CC" }
        waitFor("cert-pveproxy-ssl.pem")

        scrollTabTo("acme-add")
        waitEnabled("acme-add")
        tap("acme-add")
        type("acme-domain", "lab.example.org")
        tap("acme-plugin-example-dns")
        tap("acme-domain-save")
        waitUntil { pve.nodeConfig["acmedomain1"] == "domain=lab.example.org,plugin=example-dns" }

        scrollTabTo("acme-order")
        waitEnabled("acme-order")
        tap("acme-order")
        confirmInDialog("Order")
        waitUntil { pve.lastCall("POST", "/nodes/pve/certificates/acme/certificate") != null }
    }
}
