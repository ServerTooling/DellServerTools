package com.lilayam.dellservertools.e2e

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PermissionsE2ETest : ProxmoxE2ETestBase() {

    private fun waitFor(tag: String) = waitUntil { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    /** The tab's lazy list (the tab row scrolls too, but sideways and without indices). */
    private fun scrollTabTo(tag: String) {
        waitUntil { runCatching { compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(tag)) }.isSuccess }
    }

    // A tap on a disabled button is dropped, and the previous change's snackbar can cover a button and swallow a real
    // touch, so wait for it to be enabled and click through semantics.
    private fun tapEnabled(tag: String) {
        waitUntil { compose.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun confirmInDialog(label: String) {
        waitUntil { compose.onAllNodes(hasText(label) and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText(label) and hasAnyAncestor(isDialog())).performClick()
    }

    private fun openPermissions() {
        addServerAndConnect()
        waitForText("100 · web-01")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("open-permissions"))
        tap("open-permissions")
        waitForText("Permissions")
        waitFor("user-root@pam")
    }

    @Test
    fun addsAUserAndGivesItAPermission() {
        openPermissions()
        tap("user-add")
        type("user-name", "carol")
        type("user-password", "longenough")
        tap("user-group-admins")
        tap("user-save")
        waitUntil { pve.users.containsKey("carol@pve") }
        assertEquals("admins", pve.users.getValue("carol@pve")["groups"])
        assertEquals("longenough", pve.lastCall("POST", "/access/users")!!.params["password"])

        tap("perm-tab-acl")
        waitFor("acl-0")
        tapEnabled("acl-add")
        type("acl-path", "/vms/100", replace = true)
        waitFor("acl-who-carol@pve")
        tap("acl-who-carol@pve")
        tap("acl-role-PVEAuditor")
        tap("acl-save")
        waitUntil { pve.acl.any { it["ugid"] == "carol@pve" } }
        assertEquals(
            mapOf("path" to "/vms/100", "type" to "user", "ugid" to "carol@pve", "roleid" to "PVEAuditor", "propagate" to "1"),
            pve.acl.first { it["ugid"] == "carol@pve" },
        )
    }

    @Test
    fun createsATokenAPoolAndRemovesASecondFactor() {
        openPermissions()

        tap("perm-tab-tokens")
        waitFor("token-root@pam!ci")
        tap("token-add")
        type("token-id", "deploy")
        tap("token-save")
        waitForText("11111111-2222-3333-4444-555555555555")
        tap("token-done")
        waitFor("token-root@pam!deploy")

        tap("perm-tab-pools")
        waitFor("pool-add")
        tapEnabled("pool-add")
        type("pool-id", "lab")
        tap("pool-save")
        waitFor("pool-lab")
        tapEnabled("pool-lab")
        waitFor("pool-guest-100")
        tap("pool-guest-100")
        tap("pool-save")
        waitUntil { pve.pools["lab"]?.contains(100) == true }

        tap("perm-tab-tfa")
        scrollTabTo("tfa-delete-alice@pve-totp1")
        tapEnabled("tfa-delete-alice@pve-totp1")
        confirmInDialog("Remove")
        waitUntil { pve.tfa.isEmpty() }
    }
}
