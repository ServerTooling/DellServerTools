package com.lilayam.dellservertools.e2e

import android.content.Context
import android.content.Intent
import android.view.ViewGroup
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lilayam.dellservertools.MainActivity
import com.lilayam.dellservertools.testing.FakeAndroidKeyStore
import org.junit.After
import org.junit.Before
import org.junit.Rule

/**
 * Shared setup for end-to-end tests: a clean app and some UI helpers.
 *
 * These tests run on a device/emulator (`connectedDebugAndroidTest`) and on the
 * JVM under Robolectric (`testDebugUnitTest`).
 */
abstract class E2ETest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    protected val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun clearAppData() {
        FakeAndroidKeyStore.installIfRobolectric()
        // The orchestrator clears data between tests on devices; this covers Robolectric and IDE runs.
        deleteAppData()
    }

    @After
    fun closeApp() {
        closeScenario()
        deleteAppData()
    }

    private fun closeScenario() {
        scenario?.let { s ->
            // Robolectric doesn't detach a destroyed activity's views, which would leave its Compose
            // root visible to the next test. Detaching them explicitly is harmless on a device.
            runCatching { s.onActivity { (it.window.decorView as ViewGroup).removeAllViews() } }
            s.close()
        }
        scenario = null
    }

    private fun deleteAppData() {
        listOf("profiles", "secrets", "trust").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
            context.deleteSharedPreferences(it)
        }
    }

    protected fun launch(intent: Intent? = null) {
        closeScenario()
        scenario = if (intent == null) {
            ActivityScenario.launch(MainActivity::class.java)
        } else {
            ActivityScenario.launch(intent.setClass(context, MainActivity::class.java))
        }
    }

    protected fun waitForText(text: String, substring: Boolean = false, timeoutMs: Long = 20_000) {
        compose.waitUntil(timeoutMs) {
            compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    protected fun waitUntil(timeoutMs: Long = 20_000, condition: () -> Boolean) = compose.waitUntil(timeoutMs, condition)

    protected fun type(tag: String, text: String, replace: Boolean = false) {
        val node = compose.onNodeWithTag(tag).performScrollTo()
        if (replace) node.performTextClearance()
        node.performTextInput(text)
    }

    protected fun tap(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    /** Scrolls the screen's list until a node with [text] is composed. */
    protected fun scrollListTo(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
    }

    protected fun hasTextExactly(text: String): SemanticsMatcher = hasText(text, substring = false)
}
