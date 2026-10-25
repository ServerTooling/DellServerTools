package com.lilayam.dellservertools.testing

import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * QEMU's VNC server as Proxmox exposes it through `vncwebsocket`: RFB 3.8, VNC password auth with the
 * vncproxy ticket, and a small all-red screen sent as one Raw rectangle on the first update request.
 */
class FakeVnc(private val password: String, val width: Int = 4, val height: Int = 2) : WebSocketListener() {
    /** Keysyms pressed (key-down events), in order. */
    val keysDown = CopyOnWriteArrayList<Int>()

    /** Pointer events as (x, y, buttons). */
    val pointer = CopyOnWriteArrayList<Triple<Int, Int, Int>>()

    @Volatile
    var authenticated = false

    @Volatile
    var updatesSent = 0

    private val buf = ByteArrayOutputStream()
    private var data = ByteArray(0)
    private var step = 0
    private val challenge = ByteArray(16) { (it * 7 + 3).toByte() }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        webSocket.send("RFB 003.008\n".toByteArray().toByteString())
    }

    @Synchronized
    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        buf.write(bytes.toByteArray())
        data = buf.toByteArray()
        var used = 0
        while (true) {
            val n = handle(webSocket, data, used)
            if (n == 0) break
            used += n
        }
        buf.reset()
        buf.write(data, used, data.size - used)
    }

    /** Handles one client message starting at [off]; returns its length, or 0 if it isn't complete yet. */
    private fun handle(ws: WebSocket, d: ByteArray, off: Int): Int {
        val left = d.size - off
        fun u8(i: Int) = d[off + i].toInt() and 0xff
        fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        fun u32(i: Int) = (u16(i) shl 16) or u16(i + 2)
        when (step) {
            0 -> { // client version
                if (left < 12) return 0
                ws.send(byteArrayOf(1, 2).toByteString()) // one security type: VNC auth
                step = 1
                return 12
            }
            1 -> { // chosen security type
                if (left < 1) return 0
                ws.send(challenge.toByteString())
                step = 2
                return 1
            }
            2 -> { // DES response
                if (left < 16) return 0
                authenticated = d.copyOfRange(off, off + 16).contentEquals(expectedResponse())
                if (!authenticated) {
                    val reason = "Authentication failed".toByteArray()
                    ws.send((int32(1) + int32(reason.size) + reason).toByteString())
                    ws.close(1000, null)
                    return 16
                }
                ws.send(int32(0).toByteString())
                step = 3
                return 16
            }
            3 -> { // ClientInit
                if (left < 1) return 0
                val name = "vm-100".toByteArray()
                val pixelFormat = byteArrayOf(32, 24, 0, 1, 0, 255.toByte(), 0, 255.toByte(), 0, 255.toByte(), 16, 8, 0, 0, 0, 0)
                ws.send((int16(width) + int16(height) + pixelFormat + int32(name.size) + name).toByteString())
                step = 4
                return 1
            }
        }
        if (left < 1) return 0
        return when (u8(0)) {
            0 -> if (left < 20) 0 else 20 // SetPixelFormat
            2 -> if (left < 4 || left < 4 + 4 * u16(2)) 0 else 4 + 4 * u16(2) // SetEncodings
            3 -> { // FramebufferUpdateRequest: answer the full (non-incremental) ones with the red screen
                if (left < 10) return 0
                if (u8(1) == 0) {
                    val pixels = ByteArray(width * height * 4) { if (it % 4 == 2) 255.toByte() else 0 } // B, G, R, X
                    ws.send((byteArrayOf(0, 0) + int16(1) + int16(0) + int16(0) + int16(width) + int16(height) + int32(0) + pixels).toByteString())
                    updatesSent++
                }
                10
            }
            4 -> { // KeyEvent
                if (left < 8) return 0
                if (u8(1) == 1) keysDown += u32(4)
                8
            }
            5 -> { // PointerEvent
                if (left < 6) return 0
                pointer += Triple(u16(2), u16(4), u8(1))
                6
            }
            else -> error("FakeVnc: unexpected client message ${u8(0)}")
        }
    }

    private fun expectedResponse(): ByteArray {
        // The VNC DES quirk, written independently of the app's code: each key byte's bits mirrored.
        val key = ByteArray(8) { i ->
            val b = if (i < password.length) password[i].code else 0
            var r = 0
            for (bit in 0 until 8) if (b and (1 shl bit) != 0) r = r or (0x80 ushr bit)
            r.toByte()
        }
        return Cipher.getInstance("DES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES")) }.doFinal(challenge)
    }

    private fun int16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
    private fun int32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
}
