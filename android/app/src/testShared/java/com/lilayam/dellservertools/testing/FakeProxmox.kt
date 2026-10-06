package com.lilayam.dellservertools.testing

import java.net.InetAddress
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

/**
 * A tiny fake of the Proxmox VE API on HTTPS with a self-signed certificate,
 * enough to drive the app's client through login, listing, actions and tasks.
 */
class FakeProxmox : AutoCloseable {
    val certificate: HeldCertificate = HeldCertificate.Builder()
        .commonName("pve.test")
        .addSubjectAlternativeName("localhost")
        .build()

    val server = MockWebServer()
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    val vmRunning = AtomicBoolean(false)
    val logins = AtomicInteger(0)

    /** Number of upcoming authenticated requests to reject with 401, as if the ticket expired. */
    val expireTicketTimes = AtomicInteger(0)

    @Volatile
    var ticket = "PVE:root@pam:65A0B1C2::sig+with/special=chars=="

    init {
        server.useHttps(
            HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(),
            false,
        )
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return handle(request)
            }
        }
        // Explicit IPv4 loopback: the app is pointed at 127.0.0.1.
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    val port: Int get() = server.port

    private fun json(data: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody("""{"data":$data}""")

    private fun error(code: Int, reason: String) =
        MockResponse().setStatus("HTTP/1.1 $code $reason").setHeader("Content-Type", "application/json").setBody("""{"data":null}""")

    private fun form(request: RecordedRequest): Map<String, String> =
        request.body.readUtf8().split('&').filter { it.contains('=') }.associate {
            val (k, v) = it.split('=', limit = 2)
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }

    private fun authorized(request: RecordedRequest): Boolean {
        request.getHeader("Authorization")?.let { return it == "PVEAPIToken=root@pam!ci=token-secret" }
        val cookie = request.getHeader("Cookie") ?: return false
        val value = URLDecoder.decode(cookie.substringAfter("PVEAuthCookie="), "UTF-8")
        if (value != ticket) return false
        if (request.method != "GET" && request.getHeader("CSRFPreventionToken") != "csrf-token") return false
        return true
    }

    private fun handle(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty().removePrefix("/api2/json")
        if (path == "/access/ticket" && request.method == "POST") {
            val f = form(request)
            return if (f["username"] == "root@pam" && f["password"] == "s3cret") {
                logins.incrementAndGet()
                json("""{"ticket":"$ticket","CSRFPreventionToken":"csrf-token","username":"root@pam"}""")
            } else {
                error(401, "authentication failure")
            }
        }
        if (!authorized(request)) return error(401, "No ticket")
        if (expireTicketTimes.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) return error(401, "No ticket")

        val status = if (vmRunning.get()) "running" else "stopped"
        return when {
            path == "/version" -> json("""{"version":"8.2.4","release":"8.2"}""")
            path == "/cluster/resources" -> json(
                """[
                  {"id":"node/pve","type":"node","node":"pve","status":"online","cpu":0.03,"maxcpu":16,"mem":4294967296,"maxmem":34359738368,"uptime":7200},
                  {"id":"qemu/100","type":"qemu","node":"pve","vmid":100,"name":"web-01","status":"$status","maxcpu":2,"maxmem":2147483648},
                  {"id":"lxc/200","type":"lxc","node":"pve","vmid":200,"name":"gh-runner-1","status":"running","maxcpu":4,"tags":"gh-runner"},
                  {"id":"storage/pve/local","type":"storage","node":"pve","storage":"local","status":"available","disk":10,"maxdisk":100}
                ]""",
            )
            path == "/nodes/pve/status" -> json(
                """{"cpu":0.03,"uptime":7200,"loadavg":["0.1","0.2","0.3"],"pveversion":"pve-manager/8.2.4","kversion":"Linux 6.8",
                   "memory":{"used":1,"total":2},"swap":{"used":0,"total":0},"rootfs":{"used":1,"total":2},"cpuinfo":{"cpus":16,"model":"Xeon"}}""",
            )
            path == "/nodes/pve/storage" -> json("""[{"storage":"local","type":"dir","content":"iso,backup","used":10,"total":100,"active":1}]""")
            path == "/nodes/pve/qemu/100/status/current" -> json(
                """{"status":"$status","name":"web-01","cpus":2,"maxmem":2147483648,"mem":0,"uptime":${if (vmRunning.get()) 60 else 0}}""",
            )
            path == "/nodes/pve/qemu/100/config" -> json("""{"name":"web-01","memory":2048,"cores":2,"digest":"abc"}""")
            path == "/nodes/pve/qemu/100/snapshot" && request.method == "GET" ->
                json("""[{"name":"current","running":0},{"name":"clean","snaptime":1700000000,"description":"fresh install"}]""")
            path == "/nodes/pve/qemu/100/snapshot" && request.method == "POST" -> json("\"UPID:pve:00000002:snapshot:100:root@pam:\"")
            path.startsWith("/nodes/pve/qemu/100/snapshot/clean") && request.method == "DELETE" ->
                json("\"UPID:pve:00000003:delsnapshot:100:root@pam:\"")
            path == "/nodes/pve/qemu/100/status/start" && request.method == "POST" -> {
                vmRunning.set(true)
                json("\"UPID:pve:00000001:qmstart:100:root@pam:\"")
            }
            path == "/nodes/pve/qemu/100/status/stop" && request.method == "POST" -> {
                vmRunning.set(false)
                json("\"UPID:pve:00000004:qmstop:100:root@pam:\"")
            }
            path.startsWith("/nodes/pve/tasks/") && path.contains("/status") -> json("""{"status":"stopped","exitstatus":"OK"}""")
            path.startsWith("/nodes/pve/tasks/") && path.contains("/log") -> json("""[{"n":1,"t":"starting"},{"n":2,"t":"TASK OK"}]""")
            path == "/cluster/tasks" -> json(
                """[{"upid":"UPID:pve:00000001:qmstart:100:root@pam:","node":"pve","type":"qmstart","id":"100","user":"root@pam","starttime":1700000000,"endtime":1700000001,"status":"OK"}]""",
            )
            else -> error(501, "Method '$path' not implemented in FakeProxmox")
        }
    }

    fun requestsTo(pathSuffix: String, method: String? = null): List<RecordedRequest> =
        requests.filter { it.path.orEmpty().substringBefore('?').endsWith(pathSuffix) && (method == null || it.method == method) }

    override fun close() = server.shutdown()
}
