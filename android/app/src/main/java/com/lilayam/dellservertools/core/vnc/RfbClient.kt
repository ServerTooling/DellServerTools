package com.lilayam.dellservertools.core.vnc

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class RfbException(message: String) : IOException(message)

/**
 * A minimal VNC (RFB 3.8) client: VNC password auth, 32-bit true colour, Raw and CopyRect encodings plus
 * DesktopSize. That is all QEMU's VNC server needs, and it keeps the code small enough to test fully.
 *
 * [pixels] holds the remote screen as ARGB, [width] x [height]. One thread calls [readMessage] in a
 * loop; any thread may send input (writes go through [send], which must be thread-safe).
 */
class RfbClient(input: InputStream, private val send: (ByteArray) -> Unit) {
    private val inp = DataInputStream(input)

    var width = 0
        private set
    var height = 0
        private set
    var name = ""
        private set
    @Volatile
    var pixels = IntArray(0)
        private set

    /** What one server message changed. */
    sealed interface Update {
        /** Pixels inside these rectangles changed; the client should ask for the next update. */
        data class Frame(val rects: Int) : Update
        /** The remote screen changed size; [pixels] is new. */
        data class Resized(val width: Int, val height: Int) : Update
        data object Other : Update
    }

    /** Version, security, init and our pixel format and encodings. [password] is only used for VNC auth. */
    fun handshake(password: String) {
        val version = ByteArray(12).also(inp::readFully).toString(Charsets.US_ASCII)
        if (!version.startsWith("RFB ")) throw RfbException("Not a VNC server")
        val minor = version.substring(8, 11).toIntOrNull() ?: 3
        val major = version.substring(4, 7).toIntOrNull() ?: 3
        val useOld = major == 3 && minor < 7
        send((if (useOld) "RFB 003.003\n" else "RFB 003.008\n").toByteArray(Charsets.US_ASCII))

        val type = if (useOld) {
            inp.readInt().also { if (it == 0) throw RfbException(readReason()) }
        } else {
            val count = inp.readUnsignedByte()
            if (count == 0) throw RfbException(readReason())
            val offered = ByteArray(count).also(inp::readFully).map { it.toInt() and 0xff }
            val chosen = when {
                SEC_VNC_AUTH in offered -> SEC_VNC_AUTH
                SEC_NONE in offered -> SEC_NONE
                else -> throw RfbException("Unsupported VNC security types $offered")
            }
            send(byteArrayOf(chosen.toByte()))
            chosen
        }
        if (type == SEC_VNC_AUTH) {
            val challenge = ByteArray(16).also(inp::readFully)
            send(vncAuthResponse(challenge, password))
        }
        if (type == SEC_VNC_AUTH || !useOld) {
            when (inp.readInt()) {
                0 -> Unit
                else -> throw RfbException(if (useOld) "VNC authentication failed" else "VNC authentication failed: ${readReason()}")
            }
        }

        send(byteArrayOf(1)) // ClientInit: shared, so the web console can stay open too
        width = inp.readUnsignedShort()
        height = inp.readUnsignedShort()
        inp.readFully(ByteArray(16)) // server pixel format; we set our own below
        name = String(ByteArray(inp.readInt()).also(inp::readFully), Charsets.UTF_8)
        pixels = IntArray(width * height)

        send(PIXEL_FORMAT)
        send(setEncodings(ENC_COPYRECT, ENC_RAW, ENC_DESKTOP_SIZE))
    }

    fun requestUpdate(incremental: Boolean) {
        send(bytes(3, if (incremental) 1 else 0, *u16(0), *u16(0), *u16(width), *u16(height)))
    }

    /** Reads and applies one server message. Blocks until it arrives. */
    fun readMessage(): Update = when (val type = inp.readUnsignedByte()) {
        0 -> readFramebufferUpdate()
        1 -> { // SetColourMapEntries: not used in true colour
            inp.readUnsignedByte()
            inp.readUnsignedShort()
            inp.readFully(ByteArray(inp.readUnsignedShort() * 6))
            Update.Other
        }
        2 -> Update.Other // Bell
        3 -> { // ServerCutText
            inp.readFully(ByteArray(3))
            val length = inp.readInt()
            if (length !in 0..MAX_CUT_TEXT) throw RfbException("Bad clipboard length $length")
            inp.readFully(ByteArray(length))
            Update.Other
        }
        else -> throw RfbException("Unknown VNC message $type")
    }

    private fun readFramebufferUpdate(): Update {
        inp.readUnsignedByte()
        val count = inp.readUnsignedShort()
        var resized: Update.Resized? = null
        repeat(count) {
            val x = inp.readUnsignedShort()
            val y = inp.readUnsignedShort()
            val w = inp.readUnsignedShort()
            val h = inp.readUnsignedShort()
            when (val encoding = inp.readInt()) {
                ENC_RAW -> readRaw(x, y, w, h)
                ENC_COPYRECT -> copyRect(inp.readUnsignedShort(), inp.readUnsignedShort(), x, y, w, h)
                ENC_DESKTOP_SIZE -> {
                    width = w
                    height = h
                    pixels = IntArray(w * h)
                    resized = Update.Resized(w, h)
                }
                else -> throw RfbException("Unexpected VNC encoding $encoding")
            }
        }
        return resized ?: Update.Frame(count)
    }

    private fun readRaw(x: Int, y: Int, w: Int, h: Int) {
        checkRect(x, y, w, h)
        val row = ByteArray(w * 4)
        val px = pixels
        for (r in 0 until h) {
            inp.readFully(row)
            var o = (y + r) * width + x
            var i = 0
            while (i < row.size) {
                // Our pixel format is little-endian with red at bit 16: bytes are B, G, R, unused.
                px[o++] = (0xff shl 24) or ((row[i + 2].toInt() and 0xff) shl 16) or
                    ((row[i + 1].toInt() and 0xff) shl 8) or (row[i].toInt() and 0xff)
                i += 4
            }
        }
    }

    private fun copyRect(sx: Int, sy: Int, x: Int, y: Int, w: Int, h: Int) {
        checkRect(x, y, w, h)
        checkRect(sx, sy, w, h)
        val px = pixels
        val copy = IntArray(w * h)
        for (r in 0 until h) System.arraycopy(px, (sy + r) * width + sx, copy, r * w, w)
        for (r in 0 until h) System.arraycopy(copy, r * w, px, (y + r) * width + x, w)
    }

    private fun checkRect(x: Int, y: Int, w: Int, h: Int) {
        if (x + w > width || y + h > height) throw RfbException("Rectangle $w x $h at $x,$y is outside the $width x $height screen")
    }

    fun key(keysym: Int, down: Boolean) {
        send(bytes(4, if (down) 1 else 0, 0, 0, *u32(keysym)))
    }

    /** Presses and releases [keysym] while holding [modifiers] (e.g. Ctrl+Alt+Del). */
    fun tap(keysym: Int, vararg modifiers: Int) {
        modifiers.forEach { key(it, true) }
        key(keysym, true)
        key(keysym, false)
        modifiers.reversed().forEach { key(it, false) }
    }

    /** [buttons] is a mask: 1 left, 2 middle, 4 right. */
    fun pointer(x: Int, y: Int, buttons: Int) {
        send(bytes(5, buttons, *u16(x.coerceIn(0, maxOf(width - 1, 0))), *u16(y.coerceIn(0, maxOf(height - 1, 0)))))
    }

    private fun readReason(): String = String(ByteArray(inp.readInt().coerceIn(0, 4096)).also(inp::readFully), Charsets.UTF_8)

    companion object {
        const val SEC_NONE = 1
        const val SEC_VNC_AUTH = 2
        const val ENC_RAW = 0
        const val ENC_COPYRECT = 1
        const val ENC_DESKTOP_SIZE = -223
        private const val MAX_CUT_TEXT = 1 shl 20

        /** 32 bpp, depth 24, little-endian, true colour, 8 bits per channel, red/green/blue at 16/8/0. */
        val PIXEL_FORMAT: ByteArray = bytes(0, 0, 0, 0, 32, 24, 0, 1, 0, 255, 0, 255, 0, 255, 16, 8, 0, 0, 0, 0)

        fun setEncodings(vararg encodings: Int): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(bytes(2, 0, *u16(encodings.size)))
            encodings.forEach { out.write(bytes(*u32(it))) }
            return out.toByteArray()
        }

        /**
         * VNC authentication: DES-encrypt the challenge with the first 8 bytes of the password, each byte's bits
         * reversed (a quirk of the original VNC implementation).
         */
        fun vncAuthResponse(challenge: ByteArray, password: String): ByteArray {
            val key = ByteArray(8)
            password.toByteArray(Charsets.ISO_8859_1).copyInto(key, endIndex = minOf(8, password.length))
            for (i in key.indices) key[i] = (Integer.reverse(key[i].toInt() and 0xff) ushr 24).toByte()
            val cipher = Cipher.getInstance("DES/ECB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
            return cipher.doFinal(challenge)
        }

        private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
        private fun u16(v: Int) = intArrayOf((v shr 8) and 0xff, v and 0xff)
        private fun u32(v: Int) = intArrayOf((v ushr 24) and 0xff, (v shr 16) and 0xff, (v shr 8) and 0xff, v and 0xff)
    }
}
