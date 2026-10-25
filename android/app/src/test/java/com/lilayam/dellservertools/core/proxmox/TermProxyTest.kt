package com.lilayam.dellservertools.core.proxmox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TermProxyTest {
    private val ticket = TermProxyTicket(5901, "PVEVNC:AB::x+y/z=", "root@pam")

    @Test
    fun `node shell and container console paths`() {
        assertEquals("/nodes/pve", TermProxy.path("pve", null))
        assertEquals("/nodes/pve/lxc/200", TermProxy.path("pve", 200))
        assertEquals(
            "/api2/json/nodes/pve/vncwebsocket?port=5901&vncticket=PVEVNC%3AAB%3A%3Ax%2By%2Fz%3D",
            TermProxy.websocketPath("pve", null, ticket),
        )
        assertEquals(
            "/api2/json/nodes/pve/lxc/200/vncwebsocket?port=5901&vncticket=PVEVNC%3AAB%3A%3Ax%2By%2Fz%3D",
            TermProxy.websocketPath("pve", 200, ticket),
        )
    }

    @Test
    fun `messages are framed as the web UI's xterm frames them`() {
        assertEquals("root@pam:PVEVNC:AB::x+y/z=\n", String(TermProxy.login(ticket)))
        assertEquals("0:3:ls\r", String(TermProxy.input("ls\r")))
        // The length counts UTF-8 bytes, not characters.
        assertEquals("0:2:é", String(TermProxy.input("é"), Charsets.UTF_8))
        assertEquals("1:80:25:", String(TermProxy.resize(80, 25)))
        assertEquals("2", String(TermProxy.PING))
    }
}
