package com.lilayam.dellservertools.e2e

import android.content.Context
import android.content.Intent
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
import org.junit.After
import org.junit.Before
import org.junit.Rule

/** Shared setup for end-to-end tests: a clean app and some UI helpers. */
abstract class E2ETest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    protected val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun clearAppData() {
        // The orchestrator also clears data between tests; this keeps IDE runs clean too.
        listOf("profiles", "secrets", "trust").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @After
    fun closeApp() {
        scenario?.close()
    }

    protected fun launch(intent: Intent? = null) {
        scenario?.close()
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
