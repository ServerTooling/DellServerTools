package com.lilayam.dellservertools.e2e

import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GraphsE2ETest : ProxmoxE2ETestBase() {

    private fun waitFor(tag: String) = waitUntil { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun rrdCalls(path: String, timeframe: String) =
        pve.calls.count { it.path.substringBefore('?') == path && it.params["timeframe"] == timeframe }

    @Test
    fun showsNodeGraphsForAnotherTimeSpan() {
        addServerAndConnect()
        waitForText("100 · web-01")
        compose.onNodeWithText("pve").performClick()
        waitForText("Node pve")
        waitFor("node-graphs")
        tap("node-graphs")
        waitForText("Node pve graphs")
        waitFor("graph-cpu")
        waitUntil { rrdCalls("/nodes/pve/rrddata", "hour") > 0 }

        tap("graph-tf-DAY")
        waitUntil { rrdCalls("/nodes/pve/rrddata", "day") > 0 }
        waitFor("graph-cpu")
        // The latest CPU sample (0.2) as a percentage.
        waitForText(" CPU 20.0%")
    }

    @Test
    fun showsGuestGraphs() {
        addServerAndConnect()
        waitForText("100 · web-01")
        compose.onNodeWithText("100 · web-01").performClick()
        waitUntil { compose.onAllNodesWithTag("guest-protection").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("guest-graphs"))
        tap("guest-graphs")
        waitForText("VM 100 graphs")
        waitFor("graph-cpu")
        waitForText(" CPU 50.0%")
        waitUntil { rrdCalls("/nodes/pve/qemu/100/rrddata", "hour") > 0 }
    }
}
