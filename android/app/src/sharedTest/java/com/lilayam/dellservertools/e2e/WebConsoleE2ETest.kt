package com.lilayam.dellservertools.e2e

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebConsoleE2ETest : ProxmoxE2ETestBase() {

    @Test
    fun containerWebConsoleOffersKeyboardAndKeyBar() {
        addServerAndConnect()
        waitForText("100 · web-01")
        scrollListTo("200 · gh-runner-1")
        compose.onNodeWithText("200 · gh-runner-1").performClick()

        waitForText("Running")
        compose.onNodeWithText("Console").performClick()

        // The web console gets a button to raise the soft keyboard and a bar of keys phones lack.
        waitForText("Console 200")
        compose.onNodeWithTag("console-keyboard").assertExists()
        compose.onNodeWithTag("console-keys").assertExists()
        compose.onNodeWithText("Ctrl+C").assertExists()
        compose.onNodeWithText("Esc").assertExists()
    }
}
