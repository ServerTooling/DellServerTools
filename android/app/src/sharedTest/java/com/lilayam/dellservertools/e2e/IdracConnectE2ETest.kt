package com.lilayam.dellservertools.e2e

import android.content.Intent
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IdracConnectE2ETest : E2ETest() {

    @Test
    fun connectingAsksForThePasswordAndExplainsConnectionErrors() {
        launch()
        tap("add-idrac")
        type("field-name", "Offline iDRAC")
        type("field-host", "127.0.0.1")
        // Nothing listens on port 1, so the connection is refused immediately.
        type("field-port", "1", replace = true)
        tap("save")

        waitForText("Offline iDRAC")
        compose.onNodeWithText("Offline iDRAC").performClick()

        // Password was not saved, so the app asks for it.
        waitForText("Password for Offline iDRAC")
        compose.onNodeWithTag("password-dialog-field").performTextInput("calvin")
        compose.onNodeWithText("Connect").performClick()

        waitForText("Connection refused", substring = true)
        compose.onNodeWithText("Disconnected").assertExists()
    }
}
