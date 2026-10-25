package com.lilayam.dellservertools.core.proxmox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VncProxyTest {
    @Test
    fun `vncproxy asks for a websocket ticket`() {
        assertEquals(mapOf("websocket" to "1"), VncProxy.params())
    }

    @Test
    fun `websocket path carries the port and the encoded ticket`() {
        assertEquals(
            "/api2/json/nodes/pve/qemu/100/vncwebsocket?port=5900&vncticket=PVEVNC%3AAB%3A%3Ax%2By%2Fz%3D",
            VncProxy.websocketPath("pve", 100, VncTicket(5900, "PVEVNC:AB::x+y/z=")),
        )
    }
}
