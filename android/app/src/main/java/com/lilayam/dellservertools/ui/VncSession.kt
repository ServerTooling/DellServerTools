package com.lilayam.dellservertools.ui

import android.graphics.Bitmap
import com.lilayam.dellservertools.core.vnc.Keysyms
import com.lilayam.dellservertools.core.vnc.RfbClient
import com.lilayam.dellservertools.core.vnc.WebSocket
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VncState(
    val status: String = "Connecting…",
    val connected: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
    /** Bumped after every screen update, so the screen redraws [VncSession.bitmap]. */
    val frame: Long = 0,
    /** The screen was showing and then the connection was lost (e.g. while the app was in the background). */
    val dropped: Boolean = false,
)

/**
 * One connection to a VM's screen. The read loop runs on an IO coroutine and copies each update into
 * [bitmap]; input goes out on its own thread so a tap never waits for a screen update.
 */
class VncSession(
    private val scope: CoroutineScope,
    private val open: () -> Pair<WebSocket, String>,
    private val describe: (Throwable) -> String,
) {
    private val _state = MutableStateFlow(VncState())
    val state: StateFlow<VncState> = _state.asStateFlow()

    @Volatile
    var bitmap: Bitmap? = null
        private set

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var rfb: RfbClient? = null

    @Volatile
    private var closed = false
    private val sender = Executors.newSingleThreadExecutor()

    fun start() {
        _state.value = VncState()
        scope.launch(Dispatchers.IO) {
            try {
                val (ws, password) = open()
                socket = ws
                if (closed) {
                    ws.close()
                    return@launch
                }
                val client = RfbClient(ws.input, ws::send)
                client.handshake(password)
                rfb = client
                newBitmap(client)
                _state.update { it.copy(status = "Connected to ${client.name}", connected = true, width = client.width, height = client.height) }
                client.requestUpdate(incremental = false)
                while (!closed) {
                    when (val update = client.readMessage()) {
                        is RfbClient.Update.Frame -> {
                            draw(client)
                            client.requestUpdate(incremental = true)
                        }
                        is RfbClient.Update.Resized -> {
                            newBitmap(client)
                            _state.update { it.copy(width = update.width, height = update.height) }
                            client.requestUpdate(incremental = false)
                        }
                        RfbClient.Update.Other -> Unit
                    }
                }
            } catch (e: Exception) {
                if (!closed) {
                    _state.update {
                        it.copy(status = "Disconnected: ${describe(e)}", connected = false, dropped = it.connected)
                    }
                }
            } finally {
                runCatching { socket?.close() }
            }
        }
    }

    private fun newBitmap(client: RfbClient) {
        bitmap = if (client.width > 0 && client.height > 0) {
            Bitmap.createBitmap(client.width, client.height, Bitmap.Config.ARGB_8888)
        } else {
            null
        }
    }

    private fun draw(client: RfbClient) {
        val b = bitmap ?: return
        val px = client.pixels
        if (b.width != client.width || b.height != client.height || px.size != b.width * b.height) return
        b.setPixels(px, 0, b.width, 0, 0, b.width, b.height)
        _state.update { it.copy(frame = it.frame + 1) }
    }

    /** Runs [action] on the input thread; a failure shows up as the read loop's disconnect. */
    private fun input(action: (RfbClient) -> Unit) {
        val client = rfb ?: return
        if (closed) return
        sender.execute { runCatching { action(client) } }
    }

    fun tap(keysym: Int, vararg modifiers: Int) = input { it.tap(keysym, *modifiers) }

    fun type(text: String) = input { client -> text.forEach { client.tap(Keysyms.forChar(it), *Keysyms.modifiersFor(it)) } }

    /** A click at a screen position: move, press, release. [button] 1 = left, 4 = right. */
    fun click(x: Int, y: Int, button: Int = 1) = input {
        it.pointer(x, y, 0)
        it.pointer(x, y, button)
        it.pointer(x, y, 0)
    }

    /** Safe on the main thread: the socket is closed on the input thread (closing writes a frame). */
    fun close() {
        closed = true
        rfb = null
        val ws = socket
        sender.execute { runCatching { ws?.close() } }
        sender.shutdown()
    }
}
