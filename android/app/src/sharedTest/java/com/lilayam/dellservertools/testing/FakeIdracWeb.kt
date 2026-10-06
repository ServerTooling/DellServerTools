package com.lilayam.dellservertools.testing

import java.net.InetAddress
import java.net.URLDecoder
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

/**
 * Fake of the iDRAC6 web interface endpoints behind the "Virtual Console Preview":
 * login, console preview capture, the captured image, and logout.
 */
class FakeIdracWeb(
    private val username: String = "root",
    private val password: String = "calvin",
) : AutoCloseable {
    val certificate: HeldCertificate = HeldCertificate.Builder().commonName("idrac.test").build()
    val server = MockWebServer()
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    val logins = AtomicInteger(0)
    val logouts = AtomicInteger(0)
    val captures = AtomicInteger(0)

    /** Login result code to return for correct credentials ("5" = too many sessions). */
    @Volatile
    var loginResult = "0"

    /** When set, the next preview request is answered like an expired session. */
    val expireSession = AtomicBoolean(false)

    @Volatile
    private var session: String? = null

    init {
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return handle(request)
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    val port: Int get() = server.port

    private fun xml(body: String) = MockResponse().setHeader("Content-Type", "text/xml").setBody(body)

    private fun toLogin() = MockResponse().setResponseCode(302).setHeader("Location", "/login.html")

    private fun authorized(request: RecordedRequest): Boolean {
        val s = session ?: return false
        return request.getHeader("Cookie")?.contains("_appwebSessionId_=$s") == true && request.getHeader("ST2") == "st2token"
    }

    private fun handle(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        return when {
            path == "/data/login" && request.method == "POST" -> {
                val form = request.body.readUtf8().split('&').associate {
                    val (k, v) = it.split('=', limit = 2)
                    k to URLDecoder.decode(v, "UTF-8")
                }
                val ok = form["user"] == username && form["password"] == password
                val result = if (ok) loginResult else "1"
                if (result == "0") {
                    logins.incrementAndGet()
                    session = "sess${logins.get()}"
                    xml(
                        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><root><status>ok</status><authResult>0</authResult>" +
                            "<forwardUrl>index.html?ST1=st1token,ST2=st2token</forwardUrl></root>",
                    ).addHeader("Set-Cookie", "_appwebSessionId_=$session; path=/; secure; HttpOnly")
                } else {
                    xml("<?xml version=\"1.0\" encoding=\"UTF-8\"?><root><status>ok</status><authResult>$result</authResult></root>")
                }
            }
            path.startsWith("/data?get=consolepreview[manual%20") -> {
                if (expireSession.getAndSet(false)) session = null
                if (!authorized(request)) return toLogin()
                captures.incrementAndGet()
                xml("<?xml version=\"1.0\" encoding=\"UTF-8\"?><root><status>ok</status></root>")
            }
            path.startsWith("/capconsole/scapture0.png?") -> {
                if (!authorized(request)) return toLogin()
                MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(SCREEN_PNG))
            }
            path == "/data/logout" -> {
                logouts.incrementAndGet()
                session = null
                xml("<?xml version=\"1.0\" encoding=\"UTF-8\"?><root><status>ok</status></root>")
            }
            else -> MockResponse().setResponseCode(404)
        }
    }

    override fun close() = server.shutdown()

    companion object {
        /** A 1x1 PNG standing in for the server's screen. */
        val SCREEN_PNG: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=",
        )
    }
}
