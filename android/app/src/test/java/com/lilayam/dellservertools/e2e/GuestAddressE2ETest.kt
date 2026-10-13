package com.lilayam.dellservertools.e2e

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GuestAddressE2ETest : ProxmoxE2ETestBase() {

    @Test
    fun containerScreenShowsTheLiveDhcpAddressAndCopiesIt() {
        addServerAndConnect()
        waitForText("100 · web-01")
        scrollListTo("200 · gh-runner-1")
        compose.onNodeWithText("200 · gh-runner-1").performClick()

        // The config only says ip=dhcp; the address comes from the running container.
        waitForText("192.0.2.50")
        compose.onNodeWithText("2001:db8::50").assertExists()
        compose.onNodeWithText("fe80::be24:11ff:fe00:1").assertDoesNotExist()

        compose.onNodeWithText("192.0.2.50").performClick()
        waitForText("Copied 192.0.2.50")
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("192.0.2.50", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }
}
