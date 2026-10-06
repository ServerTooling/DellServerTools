package com.lilayam.dellservertools.core.idrac

import com.lilayam.dellservertools.core.formatHostForUrl
import com.lilayam.dellservertools.core.proxmox.PinnedTls
import com.lilayam.dellservertools.core.proxmox.UntrustedCertificateException
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

class IdracLoginException(message: String) : IOException(message)

/**
 * The parts of the iDRAC6 web interface needed for the "Virtual Console Preview":
 * a still image of the server's screen, the same one the iDRAC home page shows.
 *
 * Flow (as used by the web UI itself): `POST /data/login` returns a session cookie
 * and an `ST2` token, `GET /data?get=consolepreview[manual <time>]` asks the iDRAC
 * to grab the screen, `GET /capconsole/scapture0.png?<time>` downloads it, and
 * `GET /data/logout` ends the session. The iDRAC6 allows only a handful of web
 * sessions, so callers must [logout] when done.
 *
 * Every call blocks; run it off the main thread.
 */
class IdracWebClient(
    host: String,
    port: Int,
    private val username: String,
    private val password: String,
    pinnedFingerprint: String?,
) {
    val baseUrl = "https://${formatHostForUrl(host)}:$port"
    private val tls = PinnedTls(pinnedFingerprint)

    @Volatile
    private var cookies: String? = null

    @Volatile
    private var st2: String? = null

    val isLoggedIn: Boolean get() = cookies != null

    /** The certificate that made the last request fail, if that was the reason. */
    val rejectedCertificate: UntrustedCertificateException? get() = tls.rejected

    fun login() {
        val form = "user=${enc(username)}&password=${enc(password)}"
        val response = request("POST", "/data/login", form, authenticated = false)
        val body = response.text()
        val result = Regex("<authResult>\\s*(\\d+)\\s*</authResult>").find(body)?.groupValues?.get(1)
            ?: throw IdracLoginException("Unexpected login response from the iDRAC (HTTP ${response.status})")
        if (result != "0") throw IdracLoginException(loginError(result))
        st2 = Regex("ST2=([0-9A-Za-z]+)").find(body)?.groupValues?.get(1)
        cookies = response.cookies.takeIf { it.isNotEmpty() }?.joinToString("; ")
            ?: throw IdracLoginException("The iDRAC accepted the login but sent no session cookie")
    }

    /** Returns a PNG (or JPEG) of the server's current screen. */
    fun captureScreen(): ByteArray {
        if (!isLoggedIn) login()
        return try {
            capture()
        } catch (e: SessionExpiredException) {
            // Web sessions time out after a while; log in again once.
            login()
            capture()
        }
    }

    private fun capture(): ByteArray {
        val stamp = System.currentTimeMillis()
        val trigger = request("GET", "/data?get=consolepreview[manual%20$stamp]")
        if (trigger.status == 401 || trigger.looksLikeLoginPage()) throw SessionExpiredException()
        repeat(CAPTURE_ATTEMPTS) { attempt ->
            val image = request("GET", "/capconsole/scapture0.png?$stamp")
            if (image.status == 401 || image.looksLikeLoginPage()) throw SessionExpiredException()
            if (image.status == 200 && isImage(image.body)) return image.body
            if (attempt < CAPTURE_ATTEMPTS - 1) Thread.sleep(CAPTURE_RETRY_MS)
        }
        throw IOException("The iDRAC did not return a screen image")
    }

    fun logout() {
        if (!isLoggedIn) return
        runCatching { request("GET", "/data/logout") }
        cookies = null
        st2 = null
    }

    private class SessionExpiredException : IOException("iDRAC web session expired")

    private class Response(val status: Int, val body: ByteArray, val cookies: List<String>, val contentType: String?) {
        fun text() = body.toString(Charsets.UTF_8)
        fun looksLikeLoginPage() = contentType?.contains("html", ignoreCase = true) == true &&
            text().contains("login", ignoreCase = true)
    }

    private fun request(method: String, path: String, form: String? = null, authenticated: Boolean = true): Response {
        val conn = URL(baseUrl + path).openConnection() as HttpsURLConnection
        conn.sslSocketFactory = tls.socketFactory
        conn.hostnameVerifier = tls.hostnameVerifier
        conn.instanceFollowRedirects = false
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        if (authenticated) {
            cookies?.let { conn.setRequestProperty("Cookie", it) }
            st2?.let { conn.setRequestProperty("ST2", it) }
        }
        try {
            if (form != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val body = (if (status < 400) conn.inputStream else conn.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
            val setCookies = conn.headerFields.entries
                .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
                .flatMap { it.value }
                .map { it.substringBefore(';').trim() }
                .filter { it.contains('=') }
            // A redirect (usually to the login page) means the session is gone.
            if (status in 300..399) return Response(401, body, setCookies, conn.contentType)
            return Response(status, body, setCookies, conn.contentType)
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    companion object {
        private const val CAPTURE_ATTEMPTS = 3
        private const val CAPTURE_RETRY_MS = 1_000L

        fun isImage(bytes: ByteArray): Boolean {
            val png = bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
                bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()
            val jpeg = bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
            return png || jpeg
        }

        internal fun loginError(code: String): String = when (code) {
            "1", "2" -> "Login failed: wrong iDRAC username or password."
            "5" -> "The iDRAC has too many open web sessions. Log out of the iDRAC web page " +
                "(or wait a few minutes) and try again."
            else -> "The iDRAC refused the login (code $code). Check the username and password, " +
                "and that there aren't too many open web sessions."
        }
    }
}
