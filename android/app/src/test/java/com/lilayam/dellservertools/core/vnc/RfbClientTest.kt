package com.lilayam.dellservertools.core.vnc

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RfbClientTest {
    private val sent = mutableListOf<ByteArray>()
    private val challenge = ByteArray(16) { it.toByte() }

    private fun server(vararg parts: ByteArray) = ByteArrayInputStream(parts.fold(ByteArray(0)) { a, b -> a + b })

    private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
    private fun u32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    private fun serverInit(w: Int, h: Int) = u16(w) + u16(h) + ByteArray(16) + u32(6) + "vm-100".toByteArray()

    private fun greeting(w: Int = 4, h: Int = 2) = arrayOf(
        "RFB 003.008\n".toByteArray(), byteArrayOf(1, 2), challenge, u32(0), serverInit(w, h),
    )

    @Test
    fun `handshake answers VNC auth and sets our pixel format and encodings`() {
        val client = RfbClient(server(*greeting())) { sent += it }
        client.handshake("PVEVNC:ticket")

        assertEquals("RFB 003.008\n", String(sent[0]))
        assertArrayEquals(byteArrayOf(2), sent[1])
        assertArrayEquals(RfbClient.vncAuthResponse(challenge, "PVEVNC:ticket"), sent[2])
        assertArrayEquals(byteArrayOf(1), sent[3]) // shared
        assertArrayEquals(RfbClient.PIXEL_FORMAT, sent[4])
        assertArrayEquals(byteArrayOf(2, 0, 0, 3) + u32(1) + u32(0) + u32(-223), sent[5])
        assertEquals(4, client.width)
        assertEquals(2, client.height)
        assertEquals("vm-100", client.name)
    }

    @Test
    fun `only the first 8 password characters count, like every VNC server`() {
        assertArrayEquals(
            RfbClient.vncAuthResponse(challenge, "PVEVNC:6"),
            RfbClient.vncAuthResponse(challenge, "PVEVNC:6720F1A2::long-ticket"),
        )
    }

    @Test
    fun `a refused password reports the server's reason`() {
        val reason = "Authentication failed".toByteArray()
        val client = RfbClient(server("RFB 003.008\n".toByteArray(), byteArrayOf(1, 2), challenge, u32(1), u32(reason.size), reason)) { sent += it }
        val e = assertThrows<RfbException> { client.handshake("wrong") }
        assertTrue(e.message!!.contains("Authentication failed"))
    }

    @Test
    fun `raw and copyrect rectangles update the ARGB pixels and a resize replaces them`() {
        val raw = byteArrayOf(0, 0) + u16(1) + u16(1) + u16(0) + u16(2) + u16(1) + u32(0) +
            byteArrayOf(0, 0, 255.toByte(), 0, 255.toByte(), 0, 0, 0) // red, blue (B, G, R, X)
        val copy = byteArrayOf(0, 0) + u16(1) + u16(0) + u16(1) + u16(2) + u16(1) + u32(1) + u16(1) + u16(0)
        val resize = byteArrayOf(0, 0) + u16(1) + u16(0) + u16(0) + u16(8) + u16(6) + u32(-223)
        val client = RfbClient(server(*greeting(), raw, copy, resize)) { sent += it }
        client.handshake("x")

        assertEquals(RfbClient.Update.Frame(1), client.readMessage())
        assertEquals(0xffff0000.toInt(), client.pixels[1])
        assertEquals(0xff0000ff.toInt(), client.pixels[2])
        assertEquals(RfbClient.Update.Frame(1), client.readMessage())
        assertEquals(0xffff0000.toInt(), client.pixels[4]) // row 1 starts with the copied pixels
        assertEquals(0xff0000ff.toInt(), client.pixels[5])
        assertEquals(RfbClient.Update.Resized(8, 6), client.readMessage())
        assertEquals(48, client.pixels.size)
    }

    @Test
    fun `a rectangle outside the screen is refused instead of corrupting memory`() {
        val bad = byteArrayOf(0, 0) + u16(1) + u16(3) + u16(0) + u16(2) + u16(1) + u32(0) + ByteArray(8)
        val client = RfbClient(server(*greeting(), bad)) { sent += it }
        client.handshake("x")
        assertThrows<RfbException> { client.readMessage() }
    }

    @Test
    fun `input events have the RFB layout`() {
        val client = RfbClient(server(*greeting())) { sent += it }
        client.handshake("x")
        sent.clear()
        client.tap(Keysyms.DELETE, Keysyms.CTRL, Keysyms.ALT)
        client.pointer(3, 9, 1) // y is clamped to the 2-pixel-high screen
        client.requestUpdate(incremental = true)

        val out = ByteArrayOutputStream().apply { sent.forEach { write(it) } }.toByteArray()
        val keyDown = { k: Int -> byteArrayOf(4, 1, 0, 0) + u32(k) }
        val keyUp = { k: Int -> byteArrayOf(4, 0, 0, 0) + u32(k) }
        val expected = keyDown(Keysyms.CTRL) + keyDown(Keysyms.ALT) + keyDown(Keysyms.DELETE) + keyUp(Keysyms.DELETE) +
            keyUp(Keysyms.ALT) + keyUp(Keysyms.CTRL) + byteArrayOf(5, 1) + u16(3) + u16(1) +
            byteArrayOf(3, 1) + u16(0) + u16(0) + u16(4) + u16(2)
        assertArrayEquals(expected, out)
    }

    @Test
    fun `typed characters map to keysyms`() {
        assertEquals('a'.code, Keysyms.forChar('a'))
        assertEquals(Keysyms.ENTER, Keysyms.forChar('\n'))
        assertEquals(0xe9, Keysyms.forChar('é'))
        assertEquals(0x010020ac, Keysyms.forChar('€'))
        assertEquals(0xffc9, Keysyms.f(12))
    }
}
