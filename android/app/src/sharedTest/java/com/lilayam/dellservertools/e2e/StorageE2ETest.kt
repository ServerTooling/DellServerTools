package com.lilayam.dellservertools.e2e

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageE2ETest : ProxmoxE2ETestBase() {

    private fun openLocalStorage() {
        addServerAndConnect()
        waitForText("100 · web-01")
        scrollListTo("local")
        compose.onNodeWithTag("storage-local").performClick()
        waitForText("Storage local")
        waitForText("/var/lib/vz")
    }

    /** The actions sit below the details, outside the first screen of the lazy list. */
    private fun tapAction(tag: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag(tag))
        tap(tag)
    }

    private fun waitEnabled(tag: String) =
        waitUntil { compose.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun downloadsAnIsoFromAUrl() {
        openLocalStorage()
        tapAction("storage-download")
        type("download-url", "https://example.org/debian.iso")
        tap("download-query")
        // The server's answer replaces the name taken from the URL.
        waitForText("debian-13.1.0-amd64-netinst.iso")
        tap("download-go")

        waitUntil { pve.lastCall("POST", "/nodes/pve/storage/local/download-url") != null }
        assertEquals(
            mapOf("url" to "https://example.org/debian.iso", "filename" to "debian-13.1.0-amd64-netinst.iso", "content" to "iso"),
            pve.lastCall("POST", "/nodes/pve/storage/local/download-url")!!.params,
        )
    }

    @Test
    fun protectsABackupAndDeletesAnIso() {
        openLocalStorage()
        scrollListTo("vzdump-qemu-100-2026_10_13-01_00_00.vma.zst")
        tap("volume-vzdump-qemu-100-2026_10_13-01_00_00.vma.zst")
        tap("volume-protected")
        tap("volume-save")
        waitUntil { pve.calls.any { it.method == "PUT" } }
        assertEquals(mapOf("notes" to "web-01", "protected" to "1"), pve.calls.last { it.method == "PUT" }.params)

        scrollListTo("debian-12.7.0-amd64-netinst.iso")
        // The "Update …: done" snackbar can cover the card, so click through semantics rather than a touch.
        compose.onNodeWithTag("volume-debian-12.7.0-amd64-netinst.iso").performSemanticsAction(SemanticsActions.OnClick)
        waitEnabled("volume-delete")
        tap("volume-delete")
        waitForText("Tap again to delete for good")
        tap("volume-delete")
        waitUntil { pve.calls.any { it.method == "DELETE" } }
        assertEquals(
            "/nodes/pve/storage/local/content/local%3Aiso%2Fdebian-12.7.0-amd64-netinst.iso",
            pve.calls.last { it.method == "DELETE" }.path,
        )
    }

    @Test
    fun prunesOldBackups() {
        openLocalStorage()
        tapAction("storage-prune")
        waitForText("Prune backups on local")
        // `local` has no retention set, so at first everything is kept.
        waitForText("No keep count set: every backup is kept.")
        type("prune-keep-last", "1")
        tap("prune-preview")
        // Enabled once the preview marks something for removal.
        waitEnabled("prune-go")
        tap("prune-go")
        compose.onNodeWithText("Remove").performClick()

        waitUntil { pve.lastCall("DELETE", "/nodes/pve/storage/local/prunebackups") != null }
        assertEquals(mapOf("prune-backups" to "keep-last=1"), pve.lastCall("DELETE", "/nodes/pve/storage/local/prunebackups")!!.params)
    }

    @Test
    fun addsADirectoryStorageFromTheNode() {
        addServerAndConnect()
        waitForText("100 · web-01")
        compose.onNodeWithText("pve").performClick()
        waitForText("Node pve")
        scrollListTo("Add storage")
        tap("node-add-storage")
        waitForText("Add storage")
        type("storage-id", "usb")
        type("storage-field-path", "/mnt/usb")
        tap("storage-save")

        waitUntil { pve.lastCall("POST", "/storage") != null }
        assertEquals(
            mapOf("storage" to "usb", "type" to "dir", "content" to "iso,vztmpl,backup", "path" to "/mnt/usb"),
            pve.lastCall("POST", "/storage")!!.params,
        )
    }

    @Test
    fun disablesAndRemovesAStorage() {
        openLocalStorage()
        tapAction("storage-edit")
        waitForText("Edit storage local")
        waitUntil { compose.onAllNodes(hasTestTag("storage-save")).fetchSemanticsNodes().isNotEmpty() }
        tap("storage-enabled")
        tap("storage-save")
        waitUntil { pve.lastCall("PUT", "/storage/local") != null }
        assertEquals(mapOf("disable" to "1"), pve.lastCall("PUT", "/storage/local")!!.params)

        waitForText("Storage local")
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("storage-remove"))
        waitEnabled("storage-remove")
        tap("storage-remove")
        compose.onNodeWithTag("storage-remove-confirm").performTextInput("local")
        compose.onNodeWithTag("storage-remove-go").performClick()
        waitUntil { pve.lastCall("DELETE", "/storage/local") != null }
        assertNotNull(pve.lastCall("DELETE", "/storage/local"))
    }
}
