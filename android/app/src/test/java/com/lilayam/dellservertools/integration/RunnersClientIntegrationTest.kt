package com.lilayam.dellservertools.integration

import com.lilayam.dellservertools.core.proxmox.PinnedTls
import com.lilayam.dellservertools.core.proxmox.ProxmoxClient
import com.lilayam.dellservertools.core.proxmox.ProxmoxCredentials
import com.lilayam.dellservertools.core.proxmox.RunnersApiException
import com.lilayam.dellservertools.core.proxmox.RunnersClient
import com.lilayam.dellservertools.testing.FakeProxmox
import com.lilayam.dellservertools.testing.FakeRunners
import java.net.URLDecoder
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Drives [RunnersClient] against a fake pve-runners API over real HTTPS, with the Proxmox pin. */
class RunnersClientIntegrationTest {
    private lateinit var pve: FakeProxmox
    private lateinit var fingerprint: String

    @BeforeEach
    fun setUp() {
        pve = FakeProxmox()
        fingerprint = PinnedTls.fingerprint(pve.certificate.certificate)
    }

    @AfterEach
    fun tearDown() = pve.close()

    private fun runners(credentials: ProxmoxCredentials, pin: String? = fingerprint) =
        RunnersClient(ProxmoxClient("127.0.0.1", pve.port, credentials, pin), "127.0.0.1", pve.port)

    private val password = ProxmoxCredentials.Password("root@pam", "s3cret")

    @Test
    fun `password login sends the ticket as Proxmox's cookie in the Authorization header`() {
        val r = runners(password)
        assertEquals("build-30", r.status().version)
        assertEquals(1, pve.logins.get())
        val sent = pve.requestsTo("/api/v1/status").single()
        val auth = sent.getHeader("Authorization")!!
        assertTrue(auth.startsWith("PVEAuthCookie="))
        assertEquals(pve.ticket, URLDecoder.decode(auth.removePrefix("PVEAuthCookie="), "UTF-8"))
        // No cookie: the service takes credentials only from the header.
        assertEquals(null, sent.getHeader("Cookie"))
    }

    @Test
    fun `an API token is sent as is`() {
        val r = runners(ProxmoxCredentials.ApiToken("root@pam!ci", "token-secret"))
        assertEquals("pve1", r.settings().hostId)
        assertEquals("PVEAPIToken=root@pam!ci=token-secret", pve.requestsTo("/api/v1/settings").single().getHeader("Authorization"))
        assertEquals(0, pve.logins.get())
    }

    @Test
    fun `an expired ticket logs in again once`() {
        val r = runners(password)
        r.status()
        pve.runners.rejectTimes.set(1)
        assertEquals(1, r.actions().count { it.name == "spawn" })
        assertEquals(2, pve.logins.get())
    }

    @Test
    fun `a refused token is reported, not retried`() {
        val r = runners(ProxmoxCredentials.ApiToken("root@pam!ci", "wrong"))
        val e = assertThrows<RunnersApiException> { r.status() }
        assertEquals(401, e.httpStatus)
        assertEquals(1, pve.requestsTo("/api/v1/status").size)
    }

    @Test
    fun `nothing is sent to a server whose certificate isn't the pinned one`() {
        assertThrows<Exception> { runners(password, pin = "00:11").status() }
        assertTrue(pve.requests.none { it.path!!.startsWith("/api/v1/") })
    }

    @Test
    fun `settings save sends every field and reports validation errors`() {
        val r = runners(password)
        val settings = r.settings()
        r.saveSettings(settings.saveValues(mapOf("proxmox.max_runners" to "8", "loki_token" to "glc_x")))
        val put = pve.requestsTo("/api/v1/settings", "PUT").single()
        assertEquals("application/json", put.getHeader("Content-Type"))
        val saved = pve.runners.lastSave!!
        assertEquals(settings.allFields.map { it.name }.toSet(), saved.keys)
        assertEquals("8", saved["proxmox.max_runners"])
        assertEquals("glc_x", saved["loki_token"])
        assertEquals("false", saved["pool.default.delete"])
        assertEquals("8", r.settings().value("proxmox.max_runners"))

        val e = assertThrows<RunnersApiException> { r.saveSettings(settings.saveValues(mapOf("proxmox.max_runners" to "lots"))) }
        assertEquals(422, e.httpStatus)
        assertEquals("proxmox.max_runners must be a whole number, got \"lots\"", e.message)
        assertEquals(1, pve.runners.saves.get())
    }

    @Test
    fun `adding an org or account asks for a one-time browser link`() {
        val r = runners(password)
        assertEquals(FakeRunners.LINK, r.addTargetLink(" example-two ", org = true))
        assertEquals("org" to "example-two", pve.runners.lastTarget)
        assertEquals(JSONObject().put("org", "example-two").toString(),
            pve.requestsTo("/api/v1/targets", "POST").single().body.readUtf8())
        r.addTargetLink("someone", org = false)
        assertEquals("user" to "someone", pve.runners.lastTarget)

        val e = assertThrows<RunnersApiException> { r.addTargetLink("not a name", org = true) }
        assertEquals(422, e.httpStatus)
    }

    @Test
    fun `actions start once and report their output`() {
        val r = runners(password)
        val started = r.runAction("spawn", mapOf("pool" to "default"))
        assertTrue(started.last!!.running)
        assertEquals(mapOf("pool" to "default"), pve.runners.lastActionArgs)
        assertEquals(JSONObject().put("args", JSONObject().put("pool", "default")).toString(),
            pve.requestsTo("/api/v1/actions/spawn", "POST").single().body.readUtf8())
        // A second start while it runs isn't an error: it shows the run already going.
        assertTrue(r.runAction("spawn", mapOf("pool" to "default")).last!!.running)
        pve.runners.finishAction.set(true)
        val done = r.action("spawn").last!!
        assertFalse(done.running)
        assertEquals("pulling\npulled template-ubuntu-latest\n", done.output)
    }
}
