package com.lilayam.dellservertools.e2e

import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageDetailE2ETest : ProxmoxE2ETestBase() {

    @Test
    fun tappingStorageOpensItsDetails() {
        addServerAndConnect()
        waitForText("100 · web-01")

        // From the node: the node's disks, then a ZFS storage with pool health and its volumes.
        compose.onAllNodesWithText("pve").onFirst().performClick()
        waitForText("Node pve")
        waitForText("Xeon")
        scrollListTo("/dev/sdb")
        compose.onNodeWithTag("storage-local-zfs (zfspool)").performClick()
        waitForText("Storage local-zfs")
        waitForText("ZFS pool rpool")
        scrollListTo("vm-100-disk-0")
        scrollListTo("subvol-200-disk-0")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Back").performClick()

        // From the overview: a directory storage with its configuration.
        waitForText("100 · web-01")
        scrollListTo("local")
        compose.onNodeWithTag("storage-local").performClick()
        waitForText("Storage local")
        waitForText("/var/lib/vz")
    }
}
