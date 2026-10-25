package com.lilayam.dellservertools.testing

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8

/**
 * Proxmox's termproxy as `vncwebsocket` exposes it: the client logs in with `user:ticket\n` and gets `OK`, then
 * sends `0:<length>:<data>` input, `1:<cols>:<rows>:` sizes and `2` pings. Plays a shell that echoes input and
 * answers Enter with a new [prompt].
 */
class FakeTermProxy(private val user: String, private val ticket: String, private val prompt: String) : WebSocketListener() {
    /** Text typed into the shell (the data of `0:` messages), in order. */
    val input = CopyOnWriteArrayList<String>()

    /** Terminal sizes the client set, as "cols x rows". */
    val sizes = CopyOnWriteArrayList<String>()

    @Volatile
    var logins = 0

    @Volatile
    var pings = 0

    private val loggedIn = ConcurrentHashMap<WebSocket, Boolean>()
    private val open = CopyOnWriteArrayList<WebSocket>()

    override fun onOpen(webSocket: WebSocket, response: Response) {
        open += webSocket
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        val text = bytes.utf8()
        if (loggedIn[webSocket] != true) {
            if (text == "$user:$ticket\n") {
                loggedIn[webSocket] = true
                logins++
                webSocket.send("OK$prompt".encodeUtf8())
            } else {
                webSocket.close(1008, "bad login")
            }
            return
        }
        when {
            text == "2" -> pings++
            text.startsWith("1:") -> text.split(':').let { sizes += "${it[1]}x${it[2]}" }
            text.startsWith("0:") -> {
                val data = text.substringAfter(':').substringAfter(':')
                input += data
                webSocket.send(data.replace("\r", "\r\n$prompt").encodeUtf8())
            }
        }
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(code, null)
        open -= webSocket
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        open -= webSocket
    }

    /** Closes every connection still open (e.g. as Android cuts a backgrounded app's sockets). */
    fun disconnectAll() = open.forEach { runCatching { it.close(1001, null) } }
}
