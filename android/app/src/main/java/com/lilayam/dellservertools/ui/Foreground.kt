package com.lilayam.dellservertools.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

/** How long after coming back a lost connection still counts as cut in the background: Android often reports it late. */
private const val LATE_DROP_MS = 10_000L

/**
 * Calls [reconnect] when a console or shell connection was lost because the app was in the background: Android
 * cuts a backgrounded app's sockets after a while. A connection that ends while the app is in use (`exit`, the
 * server going away) stays closed, as before.
 */
@Composable
internal fun ReconnectWhenBack(dropped: Boolean, reconnect: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by lifecycle.currentStateAsState()
    val foreground = state.isAtLeast(Lifecycle.State.RESUMED)
    val times = remember { LongArray(2) } // [0] went away, [1] came back
    // Recorded in the callbacks themselves: a stopped activity may not recompose until it is back.
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> times[0] = System.currentTimeMillis()
                Lifecycle.Event.ON_RESUME -> times[1] = System.currentTimeMillis()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(foreground, dropped) {
        val (awayAt, backAt) = times[0] to times[1]
        if (foreground && dropped && awayAt > 0 && System.currentTimeMillis() - backAt < LATE_DROP_MS) reconnect()
    }
}
