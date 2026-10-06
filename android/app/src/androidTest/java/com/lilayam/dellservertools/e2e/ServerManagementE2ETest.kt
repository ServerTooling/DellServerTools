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
class ServerManagementE2ETest : E2ETest() {

    @Test
    fun addEditPersistAndDeleteAnIdrac() {
        launch()
        waitForText("Add your iDRAC6", substring = true)

        tap("add-idrac")
        compose.onNodeWithTag("save").performScrollTo().assertIsNotEnabled()
        type("field-name", "Lab R710")
        type("field-host", "192.0.2.15")
        tap("save")

        waitForText("Lab R710")
        compose.onNodeWithText("iDRAC6 · root@192.0.2.15").assertExists()

        // Survives an app restart.
        launch()
        waitForText("Lab R710")

        compose.onNodeWithContentDescription("Edit Lab R710").performClick()
        type("field-name", "R710", replace = true)
        tap("save")
        waitForText("R710")
        compose.onNodeWithText("Lab R710").assertDoesNotExist()

        compose.onNodeWithContentDescription("Edit R710").performClick()
        compose.onNodeWithText("Delete server").performScrollTo().performClick()
        compose.onNodeWithText("Delete").performClick()
        waitForText("Add your iDRAC6", substring = true)
        compose.onNodeWithText("R710").assertDoesNotExist()
    }

    @Test
    fun sharingAViewerJnlpPrefillsTheIdracForm() {
        val jnlp = """
            <?xml version="1.0" encoding="UTF-8"?>
            <jnlp codebase="https://192.0.2.15:443" spec="1.0+">
              <application-desc main-class="com.avocent.idrac.kvm.Main">
                <argument>ip=192.0.2.15</argument>
                <argument>title=idrac-ABC1234%2C+PowerEdge+R710%2C+User%3Aoperator</argument>
                <argument>user=1804289383</argument>
                <argument>passwd=846930886</argument>
                <argument>kmport=5900</argument>
              </application-desc>
            </jnlp>
        """.trimIndent()
        launch(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, jnlp))

        waitForText("Add iDRAC6")
        compose.onNodeWithTag("field-host").assertTextContains("192.0.2.15")
        compose.onNodeWithTag("field-username").assertTextContains("operator")
        compose.onNodeWithTag("field-name").assertTextContains("idrac-ABC1234 · PowerEdge R710")
        tap("save")

        waitForText("idrac-ABC1234 · PowerEdge R710")
        compose.onNodeWithText("iDRAC6 · operator@192.0.2.15").assertExists()
    }

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
