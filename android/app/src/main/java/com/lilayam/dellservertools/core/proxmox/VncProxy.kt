package com.lilayam.dellservertools.core.proxmox

import java.net.URLEncoder

/**
 * Opening a VM's screen the way Proxmox's noVNC does: `POST .../vncproxy` with `websocket=1` returns a port
 * and a one-time ticket (valid for a few seconds), then `.../vncwebsocket?port=&vncticket=` carries the VNC
 * protocol, whose password is that same ticket.
 */
data class VncTicket(val port: Int, val ticket: String)

object VncProxy {
    fun params(): Map<String, String> = mapOf("websocket" to "1")

    fun websocketPath(node: String, vmid: Int, ticket: VncTicket): String =
        "/api2/json/nodes/${enc(node)}/qemu/$vmid/vncwebsocket?port=${ticket.port}&vncticket=${enc(ticket.ticket)}"

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
