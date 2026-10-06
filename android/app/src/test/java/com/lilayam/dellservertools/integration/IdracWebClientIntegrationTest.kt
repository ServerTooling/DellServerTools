package com.lilayam.dellservertools.integration

import com.lilayam.dellservertools.core.idrac.IdracLoginException
import com.lilayam.dellservertools.core.idrac.IdracWebClient
import com.lilayam.dellservertools.core.proxmox.PinnedTls
import com.lilayam.dellservertools.testing.FakeIdracWeb
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** [IdracWebClient] (screen preview) against a fake iDRAC6 web interface over HTTPS. */
class IdracWebClientIntegrationTest {
    private lateinit var idrac: FakeIdracWeb
    private lateinit var fingerprint: String

    @BeforeEach
    fun setUp() {
        idrac = FakeIdracWeb()
        fingerprint = PinnedTls.fingerprint(idrac.certificate.certificate)
    }

    @AfterEach
    fun tearDown() = idrac.close()

    private fun client(password: String = "calvin", pin: String? = fingerprint) =
        IdracWebClient("127.0.0.1", idrac.port, "root", password, pin)

    @Test
    fun `captures the screen with session cookie and ST2 token, then logs out`() {
        val c = client()
        val image = c.captureScreen()
        assertArrayEquals(FakeIdracWeb.SCREEN_PNG, image)
        assertTrue(IdracWebClient.isImage(image))
        assertEquals(1, idrac.logins.get())
        assertEquals(1, idrac.captures.get())

        val preview = idrac.requests.first { it.path!!.startsWith("/data?get=consolepreview") }
        assertEquals("st2token", preview.getHeader("ST2"))
        assertTrue(preview.getHeader("Cookie")!!.contains("_appwebSessionId_=sess1"))

        // A second capture reuses the session.
        c.captureScreen()
        assertEquals(1, idrac.logins.get())

        c.logout()
        assertFalse(c.isLoggedIn)
        assertEquals(1, idrac.logouts.get())
    }

    @Test
    fun `expired session logs in again once`() {
        val c = client()
        c.captureScreen()
        idrac.expireSession.set(true)
        assertArrayEquals(FakeIdracWeb.SCREEN_PNG, c.captureScreen())
        assertEquals(2, idrac.logins.get())
    }

    @Test
    fun `wrong password is reported clearly`() {
        val e = assertThrows<IdracLoginException> { client(password = "nope").captureScreen() }
        assertTrue(e.message!!.contains("wrong iDRAC username or password"), e.message)
    }

    @Test
    fun `too many web sessions is reported clearly`() {
        idrac.loginResult = "5"
        val e = assertThrows<IdracLoginException> { client().captureScreen() }
        assertTrue(e.message!!.contains("too many open web sessions"), e.message)
    }

    @Test
    fun `self-signed certificate must be approved first`() {
        val c = client(pin = null)
        assertThrows<Exception> { c.captureScreen() }
        assertEquals(fingerprint, c.rejectedCertificate!!.fingerprint)
        assertTrue(idrac.requests.isEmpty(), "the password must not be sent before the certificate is trusted")
    }

    @Test
    fun `recognises png and jpeg only`() {
        assertTrue(IdracWebClient.isImage(FakeIdracWeb.SCREEN_PNG))
        assertTrue(IdracWebClient.isImage(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertFalse(IdracWebClient.isImage("<html>login</html>".toByteArray()))
    }
}
