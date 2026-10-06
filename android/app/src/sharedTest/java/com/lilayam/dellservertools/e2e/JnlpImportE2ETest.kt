package com.lilayam.dellservertools.e2e

import android.content.Intent
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JnlpImportE2ETest : E2ETest() {

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
        // The "Loaded ..." message can cover the Save button on small screens; let it go away first.
        waitUntil(15_000) { compose.onAllNodesWithText("Loaded", substring = true).fetchSemanticsNodes().isEmpty() }
        tap("save")

        waitForText("iDRAC6 · operator@192.0.2.15")
        compose.onNodeWithText("idrac-ABC1234 · PowerEdge R710").assertExists()
    }
}
