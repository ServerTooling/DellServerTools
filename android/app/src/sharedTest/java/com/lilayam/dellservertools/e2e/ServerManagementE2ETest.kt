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
}
