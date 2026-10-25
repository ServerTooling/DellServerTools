package com.lilayam.dellservertools.core.proxmox

import com.lilayam.dellservertools.core.vnc.WebSocket
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder

/** A one-time terminal ticket from `POST .../termproxy`; [user] is who the shell logs in as (e.g. root@pam). */
data class TermProxyTicket(val port: Int, val ticket: String, val user: String)

/**
 * Proxmox's terminal proxy, the connection behind the web UI's xterm.js shells: `POST .../termproxy` for a port
 * and ticket, then `.../vncwebsocket` with them. On the socket, the client logs in with `user:ticket\n` and
 * waits for `OK`; after that the server sends raw terminal output and the client sends framed messages:
 * `0:<byte length>:<data>` for input, `1:<cols>:<rows>:` for its size and `2` as a keep-alive.
 */
object TermProxy {
    /** The node's own shell when [vmid] is null, otherwise that container's console. */
    fun path(node: String, vmid: Int?): String =
        if (vmid == null) "/nodes/${enc(node)}" else "/nodes/${enc(node)}/lxc/$vmid"

    fun websocketPath(node: String, vmid: Int?, ticket: TermProxyTicket): String =
        "/api2/json${path(node, vmid)}/vncwebsocket?port=${ticket.port}&vncticket=${enc(ticket.ticket)}"

    fun login(ticket: TermProxyTicket): ByteArray = "${ticket.user}:${ticket.ticket}\n".toByteArray(Charsets.UTF_8)

    fun input(text: String): ByteArray {
        val data = text.toByteArray(Charsets.UTF_8)
        return "0:${data.size}:".toByteArray(Charsets.US_ASCII) + data
    }

    fun resize(columns: Int, rows: Int): ByteArray = "1:$columns:$rows:".toByteArray(Charsets.US_ASCII)

    val PING: ByteArray = "2".toByteArray(Charsets.US_ASCII)

    /** The web UI pings every 30 s; pveproxy closes a silent connection. */
    const val PING_INTERVAL_MS = 30_000L

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

/** One termproxy shell over an open [WebSocket]. [send] and [ping] may be called from any thread. */
class TermProxySession(private val ws: WebSocket, private val ticket: TermProxyTicket) {
    /** Logs in and sets the terminal size; returns the terminal output stream. */
    fun start(columns: Int, rows: Int): InputStream {
        ws.send(TermProxy.login(ticket))
        val answer = ByteArray(2)
        var read = 0
        while (read < 2) {
            val n = ws.input.read(answer, read, 2 - read)
            if (n < 0) throw EOFException("The terminal closed during login")
            read += n
        }
        if (String(answer, Charsets.US_ASCII) != "OK") throw IOException("The terminal refused the login")
        ws.send(TermProxy.resize(columns, rows))
        return ws.input
    }

    fun send(text: String) = ws.send(TermProxy.input(text))

    fun ping() = ws.send(TermProxy.PING)

    fun close() = ws.close()
}
