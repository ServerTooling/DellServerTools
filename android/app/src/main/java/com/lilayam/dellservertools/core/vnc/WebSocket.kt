package com.lilayam.dellservertools.core.vnc

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The client side of a WebSocket (RFC 6455) over an already connected socket, just enough for
 * Proxmox's `vncwebsocket`: binary frames in both directions, pings answered, close ends the stream.
 * [input] reads the payload of incoming binary frames as one continuous stream.
 */
class WebSocket private constructor(private val socket: Socket, private val raw: InputStream, private val out: OutputStream) :
    Closeable {

    private val random = SecureRandom()
    private var frame = ByteArray(0)
    private var pos = 0

    val input: InputStream = object : InputStream() {
        override fun read(): Int {
            if (!fill()) return -1
            return frame[pos++].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val n = minOf(len, frame.size - pos)
            System.arraycopy(frame, pos, b, off, n)
            pos += n
            return n
        }
    }

    /** Reads frames until there are payload bytes to hand out; false at the end of the stream. */
    private fun fill(): Boolean {
        while (pos >= frame.size) {
            val next = readFrame() ?: return false
            frame = next
            pos = 0
        }
        return true
    }

    private fun readFrame(): ByteArray? {
        while (true) {
            val b0 = raw.read()
            if (b0 < 0) return null
            val b1 = readByte()
            val opcode = b0 and 0x0f
            var length = (b1 and 0x7f).toLong()
            if (length == 126L) length = ((readByte() shl 8) or readByte()).toLong()
            if (length == 127L) length = (0 until 8).fold(0L) { acc, _ -> (acc shl 8) or readByte().toLong() }
            if (length > MAX_FRAME) throw IOException("WebSocket frame too large ($length bytes)")
            val mask = if (b1 and 0x80 != 0) ByteArray(4).also(::readFully) else null
            val payload = ByteArray(length.toInt()).also(::readFully)
            if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            when (opcode) {
                OP_CONTINUATION, OP_BINARY -> return payload
                OP_TEXT -> throw IOException("Unexpected text frame on a binary WebSocket")
                OP_CLOSE -> {
                    runCatching { sendFrame(OP_CLOSE, ByteArray(0)) }
                    return null
                }
                OP_PING -> sendFrame(OP_PONG, payload)
                else -> Unit // pong or reserved: ignore
            }
        }
    }

    private fun readByte(): Int = raw.read().also { if (it < 0) throw EOFException("WebSocket closed mid-frame") }

    private fun readFully(buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = raw.read(buf, off, buf.size - off)
            if (n < 0) throw EOFException("WebSocket closed mid-frame")
            off += n
        }
    }

    fun send(data: ByteArray) = sendFrame(OP_BINARY, data)

    @Synchronized
    private fun sendFrame(opcode: Int, payload: ByteArray) {
        val header = ByteArrayOutputStream(14)
        header.write(0x80 or opcode)
        when {
            payload.size < 126 -> header.write(0x80 or payload.size)
            payload.size < 65536 -> {
                header.write(0x80 or 126)
                header.write(payload.size shr 8)
                header.write(payload.size and 0xff)
            }
            else -> {
                header.write(0x80 or 127)
                for (shift in 56 downTo 0 step 8) header.write(((payload.size.toLong() shr shift) and 0xff).toInt())
            }
        }
        // Client frames must be masked (RFC 6455 5.3).
        val mask = ByteArray(4).also(random::nextBytes)
        header.write(mask)
        val masked = ByteArray(payload.size) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
        out.write(header.toByteArray())
        out.write(masked)
        out.flush()
    }

    override fun close() {
        runCatching { sendFrame(OP_CLOSE, ByteArray(0)) }
        socket.close()
    }

    companion object {
        private const val OP_CONTINUATION = 0
        private const val OP_TEXT = 1
        private const val OP_BINARY = 2
        private const val OP_CLOSE = 8
        private const val OP_PING = 9
        private const val OP_PONG = 10
        private const val MAX_FRAME = 64L * 1024 * 1024
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        /**
         * Upgrades [socket] (connected, TLS already handshaken) to a WebSocket on [path]. [headers] carry
         * the authentication; [protocol] is what Proxmox expects ("binary").
         */
        fun open(socket: Socket, hostHeader: String, path: String, headers: Map<String, String>, protocol: String = "binary"): WebSocket {
            val key = Base64.getEncoder().encodeToString(ByteArray(16).also(SecureRandom()::nextBytes))
            val request = buildString {
                append("GET $path HTTP/1.1\r\n")
                append("Host: $hostHeader\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Key: $key\r\n")
                append("Sec-WebSocket-Version: 13\r\n")
                append("Sec-WebSocket-Protocol: $protocol\r\n")
                headers.forEach { (name, value) -> append("$name: $value\r\n") }
                append("\r\n")
            }
            val out = socket.getOutputStream()
            out.write(request.toByteArray(Charsets.UTF_8))
            out.flush()

            val raw = socket.getInputStream()
            val status = readLine(raw)
            val responseHeaders = generateSequence { readLine(raw).takeIf { it.isNotEmpty() } }
                .mapNotNull { line -> line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).trim().lowercase() to line.substring(it + 1).trim() } }
                .toMap()
            val code = status.split(' ').getOrNull(1)?.toIntOrNull()
            if (code != 101) {
                socket.close()
                throw WebSocketRefused(code ?: 0, status)
            }
            val expected = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.US_ASCII)),
            )
            if (responseHeaders["sec-websocket-accept"] != expected) {
                socket.close()
                throw IOException("WebSocket handshake failed: bad Sec-WebSocket-Accept")
            }
            return WebSocket(socket, raw, out)
        }

        /** One CRLF-terminated header line, read byte by byte so no frame data is buffered away. */
        private fun readLine(input: InputStream): String {
            val line = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) throw EOFException("Connection closed during the WebSocket handshake")
                if (b == '\n'.code) break
                if (b != '\r'.code) line.write(b)
                if (line.size() > 8192) throw IOException("WebSocket handshake header too long")
            }
            return line.toString(Charsets.UTF_8.name())
        }
    }
}

/** The server answered the upgrade with something other than 101, e.g. 401 when the login expired. */
class WebSocketRefused(val code: Int, statusLine: String) : IOException("WebSocket refused: $statusLine")
