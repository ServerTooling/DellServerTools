package com.lilayam.dellservertools.e2e

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lilayam.dellservertools.core.proxmox.PinnedTls
import com.lilayam.dellservertools.testing.FakeIdracWeb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The iDRAC screen preview against a fake iDRAC6 web interface on the device. */
@RunWith(AndroidJUnit4::class)
class IdracScreenE2ETest : E2ETest() {
    private lateinit var idrac: FakeIdracWeb

    @Before
    fun startFakeIdrac() {
        idrac = FakeIdracWeb()
    }

    @After
    fun stopFakeIdrac() = idrac.close()

    @Test
    fun showsTheServerScreenAndLogsOutWhenLeaving() {
        launch()
        tap("add-idrac")
        type("field-name", "Lab iDRAC")
        type("field-host", "127.0.0.1")
        type("field-password", "calvin")
        tap("field-remember")
        type("field-web-port", idrac.port.toString(), replace = true)
        tap("save")

        waitForText("Lab iDRAC")
        compose.onNodeWithContentDescription("Screen of Lab iDRAC").performClick()

        waitForText("Trust this server?")
        compose.onNodeWithText(PinnedTls.fingerprint(idrac.certificate.certificate)).assertExists()
        compose.onNodeWithText("Trust and connect").performClick()

        waitUntil { idrac.captures.get() >= 1 }
        waitForText("Updated", substring = true)
        compose.onNodeWithContentDescription("Server screen").assertExists()

        compose.onNodeWithContentDescription("Back").performClick()
        waitForText("Lab iDRAC")
        waitUntil { idrac.logouts.get() == 1 }
        assertEquals(1, idrac.logins.get())
    }
}
