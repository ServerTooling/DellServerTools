package com.lilayam.dellservertools.integration

import com.lilayam.dellservertools.core.proxmox.GuestType
import com.lilayam.dellservertools.core.proxmox.PinnedTls
import com.lilayam.dellservertools.core.proxmox.ProxmoxApiException
import com.lilayam.dellservertools.core.proxmox.ProxmoxClient
import com.lilayam.dellservertools.core.proxmox.ProxmoxCredentials
import com.lilayam.dellservertools.testing.FakeProxmox
import java.net.URLDecoder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Drives [ProxmoxClient] against a fake Proxmox API over real HTTPS. */
class ProxmoxClientIntegrationTest {
    private lateinit var pve: FakeProxmox
    private lateinit var fingerprint: String
    private val password = ProxmoxCredentials.Password("root@pam", "s3cret")

    @BeforeEach
    fun setUp() {
        pve = FakeProxmox()
        fingerprint = PinnedTls.fingerprint(pve.certificate.certificate)
    }

    @AfterEach
    fun tearDown() = pve.close()

    private fun client(credentials: ProxmoxCredentials = password, pin: String? = fingerprint) =
        ProxmoxClient("127.0.0.1", pve.port, credentials, pin)

    @Test
    fun `self-signed certificate is rejected until pinned and reports its fingerprint`() {
        val c = client(pin = null)
        assertThrows<Exception> { c.login() }
        val rejected = c.rejectedCertificate!!
        assertEquals(fingerprint, rejected.fingerprint)
        assertFalse(rejected.changed)
        assertTrue(pve.requests.isEmpty(), "nothing may be sent before the certificate is trusted")
    }

    @Test
    fun `a different certificate than the pinned one is reported as changed`() {
        val c = client(pin = "00:11:22")
        assertThrows<Exception> { c.login() }
        assertTrue(c.rejectedCertificate!!.changed)
    }

    @Test
    fun `password login sends ticket cookie and CSRF token`() {
        val c = client()
        c.login()
        assertEquals("8.2.4-8.2", c.version())

        val login = pve.requestsTo("/access/ticket").single()
        assertEquals("POST", login.method)

        c.guestAction("pve", GuestType.QEMU, 100, "start")
        val start = pve.requestsTo("/qemu/100/status/start", "POST").single()
        assertEquals("csrf-token", start.getHeader("CSRFPreventionToken"))
        val cookie = URLDecoder.decode(start.getHeader("Cookie")!!.substringAfter("PVEAuthCookie="), "UTF-8")
        assertEquals(pve.ticket, cookie)
    }

    @Test
    fun `wrong password gives a 401 with the server's reason`() {
        val e = assertThrows<ProxmoxApiException> { client(ProxmoxCredentials.Password("root@pam", "nope")).login() }
        assertEquals(401, e.httpStatus)
        assertTrue(e.message!!.contains("authentication failure"), e.message)
    }

    @Test
    fun `expired ticket triggers one automatic re-login`() {
        val c = client()
        c.login()
        pve.expireTicketTimes.set(1)
        assertEquals(4, c.resources().size)
        assertEquals(2, pve.logins.get())
    }

    @Test
    fun `api token auth uses the PVEAPIToken header and no ticket`() {
        val c = client(ProxmoxCredentials.ApiToken("root@pam!ci", "token-secret"))
        c.login()
        assertNull(c.ticket)
        assertEquals(4, c.resources().size)
        assertTrue(pve.requestsTo("/access/ticket").isEmpty())
    }

    @Test
    fun `lists resources, node and guest details`() {
        val c = client()
        val resources = c.resources()
        assertEquals(listOf("pve", "web-01", "gh-runner-1", "local"), resources.map { it.name })
        assertEquals(listOf("gh-runner"), resources.first { it.vmid == 200 }.tags)

        assertEquals(16, c.nodeStatus("pve").cpuCount)
        assertEquals(listOf("local", "local-zfs"), c.storage("pve").map { it.storage })
        assertEquals("stopped", c.guestStatus("pve", GuestType.QEMU, 100).status)
        assertEquals(listOf("name", "cores", "memory").sorted(), c.guestConfig("pve", GuestType.QEMU, 100).map { it.first }.sorted())
        assertEquals(listOf("clean", "current"), c.snapshots("pve", GuestType.QEMU, 100).map { it.name })
        assertEquals(1, c.clusterTasks().size)
    }

    @Test
    fun `start then follow the task until it finishes`() {
        val c = client()
        val upid = c.guestAction("pve", GuestType.QEMU, 100, "start")!!
        assertTrue(upid.startsWith("UPID:pve:"))
        val status = c.taskStatus("pve", upid)
        assertFalse(status.running)
        assertEquals("OK", status.exitStatus)
        assertEquals("running", c.guestStatus("pve", GuestType.QEMU, 100).status)
        assertEquals(listOf("starting", "TASK OK"), c.taskLog("pve", upid))
        // The UPID contains ':' and '@' and must be URL-encoded in the path.
        assertTrue(pve.requests.any { it.path!!.contains("UPID%3Apve%3A00000001") })
    }

    @Test
    fun `snapshots are created with form params and deleted with DELETE`() {
        val c = client()
        c.createSnapshot("pve", GuestType.QEMU, 100, "before_update", "pre apt upgrade")
        val create = pve.requestsTo("/qemu/100/snapshot", "POST").single()
        val body = create.body.readUtf8()
        assertTrue(body.contains("snapname=before_update"), body)
        assertTrue(body.contains("description=pre%20apt%20upgrade"), body)

        c.deleteSnapshot("pve", GuestType.QEMU, 100, "clean")
        assertEquals(1, pve.requestsTo("/qemu/100/snapshot/clean", "DELETE").size)
    }

    @Test
    fun `unknown endpoints surface the HTTP reason`() {
        val e = assertThrows<ProxmoxApiException> { client().get("/nodes/pve/does-not-exist") }
        assertEquals(501, e.httpStatus)
        assertTrue(e.message!!.contains("not implemented"), e.message)
    }

    @Test
    fun `console urls point at the web UI`() {
        val c = client()
        assertEquals(
            "https://127.0.0.1:${pve.port}/?console=kvm&novnc=1&vmid=100&vmname=web-01&node=pve&resize=scale",
            c.consoleUrl("pve", GuestType.QEMU, 100, "web-01"),
        )
        assertTrue(c.consoleUrl("pve", null, null, null).contains("console=shell"))
        assertEquals("https://127.0.0.1:${pve.port}/?mobile=1", c.webUrl(mobile = true))
    }
}
