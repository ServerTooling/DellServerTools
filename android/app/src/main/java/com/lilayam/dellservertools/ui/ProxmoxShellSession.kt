package com.lilayam.dellservertools.ui

import com.lilayam.dellservertools.core.TerminalEmulator
import com.lilayam.dellservertools.core.proxmox.TermProxy
import com.lilayam.dellservertools.core.proxmox.TermProxySession
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ProxmoxShellState(
    val status: String = "Connecting…",
    val connected: Boolean = false,
    /** The shell was up and then the connection was lost (e.g. while the app was in the background). */
    val dropped: Boolean = false,
    val lines: List<String> = emptyList(),
)

/**
 * One Proxmox web-UI shell (the node's or a container's console) over termproxy, drawn by the app's own
 * terminal. The read loop runs on an IO coroutine; input goes out on its own thread.
 */
class ProxmoxShellSession(
    private val scope: CoroutineScope,
    private val open: () -> TermProxySession,
    private val describe: (Throwable) -> String,
) {
    private val terminal = TerminalEmulator(columns = TerminalViewModel.COLUMNS, rows = TerminalViewModel.ROWS)
    private val _state = MutableStateFlow(ProxmoxShellState())
    val state: StateFlow<ProxmoxShellState> = _state.asStateFlow()

    @Volatile
    private var session: TermProxySession? = null

    @Volatile
    private var closed = false

    @Volatile
    private var dirty = false
    private val sender = Executors.newSingleThreadExecutor()
    private var jobs = emptyList<Job>()

    fun start() {
        val reader = scope.launch(Dispatchers.IO) {
            try {
                val s = open()
                session = s
                if (closed) {
                    s.close()
                    return@launch
                }
                val input = s.start(TerminalViewModel.COLUMNS, TerminalViewModel.ROWS)
                _state.update { it.copy(status = "Connected", connected = true) }
                val buffer = ByteArray(8192)
                while (!closed) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n > 0) {
                        terminal.feed(buffer, 0, n)
                        dirty = true
                    }
                }
                if (!closed) _state.update { it.copy(status = "Disconnected", connected = false, dropped = it.connected) }
            } catch (e: Exception) {
                if (!closed) {
                    _state.update { it.copy(status = "Disconnected: ${describe(e)}", connected = false, dropped = it.connected) }
                }
            } finally {
                runCatching { session?.close() }
            }
        }
        // Redrawn on a timer rather than per read, so a burst of output doesn't rebuild the screen each time.
        val painter = scope.launch(Dispatchers.Default) {
            while (isActive) {
                if (dirty) {
                    dirty = false
                    val lines = terminal.snapshot().lines
                    _state.update { it.copy(lines = lines) }
                }
                delay(REFRESH_INTERVAL_MS)
            }
        }
        val pinger = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(TermProxy.PING_INTERVAL_MS)
                input { it.ping() }
            }
        }
        jobs = listOf(reader, painter, pinger)
    }

    /** Runs [action] on the input thread; a failure shows up as the read loop's disconnect. */
    private fun input(action: (TermProxySession) -> Unit) {
        val s = session ?: return
        if (closed) return
        sender.execute { runCatching { action(s) } }
    }

    fun send(text: String) = input { it.send(text) }

    fun copyableOutput(): String = terminal.snapshot().text()

    /** Safe on the main thread: the socket is closed on the input thread (closing writes a frame). */
    fun close() {
        closed = true
        jobs.forEach { it.cancel() }
        val s = session
        sender.execute { runCatching { s?.close() } }
        sender.shutdown()
    }

    private companion object {
        const val REFRESH_INTERVAL_MS = 60L
    }
}
