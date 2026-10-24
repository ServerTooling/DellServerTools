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

    @Volatile
    var lastCreateParams: Map<String, String> = emptyMap()

    @Volatile
    var lastVmCreateParams: Map<String, String> = emptyMap()

    @Volatile
    var lastConfigParams: Map<String, String> = emptyMap()

    @Volatile
    var lastResizeParams: Map<String, String> = emptyMap()

    @Volatile
    var deleted: Pair<String, Int>? = null  // (type, vmid)

    @Volatile
    var lastCloneParams: Map<String, String> = emptyMap()

    @Volatile
    var cloned: Pair<String, Int>? = null  // (type, source vmid)

    /** Whether tasks report "running" until someone stops them with DELETE. */
    @Volatile
    var taskRunning = false

    /** Adds a second storage for disks and containers, as a target for moving disks. */
    @Volatile
    var extraImageStorage = false

    /** Whether VM 100 has been converted to a template. */
    @Volatile
    var vmTemplate = false

    /** Backup jobs of `/cluster/backup` as their JSON objects, keyed by id; POST adds, PUT edits, DELETE removes. */
    val backupJobs = java.util.concurrent.ConcurrentHashMap(
        mapOf(
            "backup-1a2b" to """{"id":"backup-1a2b","type":"vzdump","schedule":"21:00","storage":"local","mode":"snapshot",
                "compress":"zstd","all":1,"exclude":"200","enabled":1,"notes-template":"{{guestname}}","next-run":1792000000,
                "prune-backups":{"keep-last":"3","keep-weekly":"2"}}""",
        ),
    )

    /** Firewall rules per rules path (e.g. `/cluster/firewall/rules`), in order; positions are list indexes. */
    val firewallRules = java.util.concurrent.ConcurrentHashMap<String, MutableList<Map<String, String>>>().apply {
        put(
            "/cluster/firewall/rules",
            mutableListOf(
                mapOf("type" to "in", "action" to "ACCEPT", "enable" to "1", "proto" to "tcp", "dport" to "22", "source" to "192.0.2.0/24", "comment" to "ssh from lab"),
                mapOf("type" to "in", "action" to "DROP", "enable" to "0", "macro" to "Ping"),
            ),
        )
    }

    /** Firewall options per firewall path; PUT merges, `delete` removes. */
    val firewallOptions = java.util.concurrent.ConcurrentHashMap<String, MutableMap<String, String>>()

    val firewallAliases = java.util.concurrent.ConcurrentHashMap(mapOf("lab" to ("192.0.2.0/24" to "lab network")))
    val ipSets = java.util.concurrent.ConcurrentHashMap(mapOf("admins" to mutableListOf("192.0.2.10")))
    val securityGroups = CopyOnWriteArrayList(listOf("webserver"))

    /** Keys pressed in VM 100 through the sendkey API, in order. */
    val sentKeys = CopyOnWriteArrayList<String>()

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
                calls += Call(
                    request.method.orEmpty(),
                    request.path.orEmpty().removePrefix("/api2/json").substringBefore('?'),
                    params(request),
                )
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

    /** One request as the API saw it: method, path without the query, and its form or query parameters. */
    data class Call(val method: String, val path: String, val params: Map<String, String>)

    val calls = CopyOnWriteArrayList<Call>()

    /** The last call to [path] (no query) with [method], or null. */
    fun lastCall(method: String, path: String): Call? = calls.lastOrNull { it.method == method && it.path == path }

    /** The raw bodies of uploads, for checking the multipart encoding. */
    val uploads = CopyOnWriteArrayList<ByteArray>()

    private fun params(request: RecordedRequest): Map<String, String> {
        val query = request.path.orEmpty().substringAfter('?', "")
        val type = request.getHeader("Content-Type").orEmpty()
        val text = if (type.startsWith("application/x-www-form-urlencoded")) request.body.clone().readUtf8() else query
        return decode(text)
    }

    private fun decode(text: String): Map<String, String> =
        text.split('&').filter { it.contains('=') }.associate {
            val (k, v) = it.split('=', limit = 2)
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }

    private fun form(request: RecordedRequest): Map<String, String> =
        request.body.clone().readUtf8().split('&').filter { it.contains('=') }.associate {
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
        if (request.path.orEmpty().startsWith("/api/v1/")) return runners.handle(request)
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
            // ---- VM hardware
            path == "/nodes/pve/hardware/usb" -> json(
                """[{"busnum":1,"devnum":1,"vendid":"0x1d6b","prodid":"0x0002","product":"EHCI Host Controller","class":9},
                    {"busnum":1,"devnum":3,"vendid":"0x0781","prodid":"0x5581","manufacturer":"SanDisk","product":"Ultra","class":0}]""",
            )
            path == "/nodes/pve/hardware/pci" -> json(
                """[{"id":"0000:04:00.0","vendor_name":"NVIDIA Corporation","device_name":"GP107","iommugroup":15,"class":"0x030000"}]""",
            )
            path == "/nodes/pve/qemu/100/cloudinit" && request.method == "PUT" -> json("null")
            // ---- storage management
            request.method == "DELETE" && path.startsWith("/nodes/pve/storage/local/content/") ->
                json("\"UPID:pve:00000020:imgdel:local:root@pam:\"")
            request.method == "PUT" && path.startsWith("/nodes/pve/storage/local/content/") -> json("null")
            path.startsWith("/nodes/pve/query-url-metadata") ->
                json("""{"filename":"debian-13.1.0-amd64-netinst.iso","size":754974720,"mimetype":"application/x-iso9660-image"}""")
            path == "/nodes/pve/storage/local/download-url" && request.method == "POST" ->
                json("\"UPID:pve:00000021:download:local:root@pam:\"")
            path == "/nodes/pve/storage/local/upload" && request.method == "POST" -> {
                uploads += request.body.clone().readByteArray()
                json("\"UPID:pve:00000022:imgcopy::root@pam:\"")
            }
            path.startsWith("/nodes/pve/storage/local/prunebackups") && request.method == "GET" -> {
                val pruning = params(request)["prune-backups"] != "keep-all=1"
                json(
                    """[
                      {"volid":"local:backup/vzdump-qemu-100-2026_10_13-01_00_00.vma.zst","mark":"keep","ctime":1791853200,"vmid":100,"type":"qemu"},
                      {"volid":"local:backup/vzdump-qemu-100-2026_10_06-01_00_00.vma.zst","mark":"${if (pruning) "remove" else "keep"}","ctime":1791248400,"vmid":100,"type":"qemu"},
                      {"volid":"local:backup/vzdump-lxc-200-2026_10_12-01_00_00.tar.zst","mark":"keep","ctime":1791766800,"vmid":200,"type":"lxc"}
                    ]""",
                )
            }
            path.startsWith("/nodes/pve/storage/local/prunebackups") && request.method == "DELETE" ->
                json("\"UPID:pve:00000023:prunebackups:local:root@pam:\"")
            path == "/storage" && request.method == "POST" -> json("null")
            path.startsWith("/storage/") && (request.method == "PUT" || request.method == "DELETE") -> json("null")
            path == "/nodes/pve/scan/zfs" -> json("""[{"pool":"tank","size":1000,"alloc":10,"free":990}]""")
            path == "/nodes/pve/scan/lvm" -> json("""[{"vg":"pve","size":1000,"free":10}]""")
            path.startsWith("/nodes/pve/scan/lvmthin") -> json("""[{"lv":"data"}]""")
            path.startsWith("/nodes/pve/scan/nfs") -> json("""[{"path":"/export/iso","options":"192.0.2.0/24"}]""")
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
            path == "/nodes/pve/storage" -> json(
                """[
                  {"storage":"local","type":"dir","content":"iso,backup,vztmpl","used":10,"total":100,"active":1},
                  {"storage":"local-zfs","type":"zfspool","content":"rootdir,images","used":104,"total":207,"active":1}
                  ${if (extraImageStorage) EXTRA_STORAGE else ""}
                ]""",
            )
            path.startsWith("/nodes/pve/storage/local/content") && path.contains("content=vztmpl") ->
                json("""[{"volid":"local:vztmpl/debian-12-standard_12.7-1_amd64.tar.zst","size":130000000},{"volid":"local:vztmpl/alpine-3.20-default_20240908_amd64.tar.xz","size":3000000}]""")
            path.startsWith("/nodes/pve/storage/local/content") && path.contains("content=backup") -> json(BACKUPS)
            path == "/nodes/pve/storage/local/content" -> json(BACKUPS.trimEnd().removeSuffix("]") + ",$ISO]")
            path.startsWith("/nodes/pve/vzdump/extractconfig") -> {
                val volume = URLDecoder.decode(path.substringAfter("volume="), "UTF-8")
                when {
                    volume.contains("vzdump-qemu-100-") -> json("\"name: web-01\\nmemory: 2048\\n\\n[clean]\\nname: old\\n\"")
                    volume.contains("vzdump-lxc-200-") -> json("\"arch: amd64\\nhostname: gh-runner-1\\nunprivileged: 1\\n\"")
                    else -> error(500, "unable to read config")
                }
            }
            path.startsWith("/nodes/pve/storage/local/content") && path.contains("content=iso") ->
                json("""[{"volid":"local:iso/debian-12.7.0-amd64-netinst.iso","size":660000000}]""")
            path == "/nodes/pve/storage/local-zfs/status" -> json(
                """{"type":"zfspool","content":"rootdir,images","shared":0,"enabled":1,"active":1,"used":104,"avail":103,"total":207}""",
            )
            path == "/nodes/pve/storage/local/status" -> json(
                """{"type":"dir","content":"iso,backup,vztmpl","shared":0,"enabled":1,"active":1,"used":10,"avail":90,"total":100}""",
            )
            path == "/storage/local-zfs" -> json(
                """{"storage":"local-zfs","type":"zfspool","pool":"rpool/data","sparse":1,"content":"images,rootdir","digest":"abc"}""",
            )
            path == "/storage/local" -> json("""{"storage":"local","type":"dir","path":"/var/lib/vz","content":"iso,backup,vztmpl","digest":"abc"}""")
            path == "/nodes/pve/storage/local-zfs/content" -> json(
                """[
                  {"volid":"local-zfs:vm-100-disk-0","content":"images","format":"raw","size":34359738368,"vmid":100},
                  {"volid":"local-zfs:subvol-200-disk-0","content":"rootdir","format":"subvol","size":17179869184,"vmid":200}
                ]""",
            )
            path == "/nodes/pve/disks/zfs/rpool" -> json(
                """{"name":"rpool","state":"ONLINE","errors":"No known data errors","scan":"scrub repaired 0B in 00:10:00",
                   "children":[{"name":"rpool","state":"ONLINE","read":0,"write":0,"cksum":0,
                     "children":[{"name":"mirror-0","state":"ONLINE","read":0,"write":0,"cksum":0,
                       "children":[{"name":"sda3","state":"ONLINE","read":0,"write":0,"cksum":0,"leaf":1},
                                   {"name":"sdb3","state":"ONLINE","read":0,"write":0,"cksum":2,"leaf":1}]}]}]}""",
            )
            path == "/nodes/pve/disks/list" -> json(
                """[{"devpath":"/dev/sda","model":"TEST SSD","type":"ssd","size":256060514304,"health":"PASSED","wearout":97,"used":"ZFS"},
                    {"devpath":"/dev/sdb","model":"TEST HDD","type":"hdd","size":2000398934016,"health":"PASSED","wearout":"N/A","used":"ZFS"}]""",
            )
            path.startsWith("/nodes/pve/storage/") && path.contains("/content") -> json("[]")
            path == "/cluster/nextid" -> json("\"201\"")
            path.contains("/firewall") -> firewall(request, path.substringBefore('?'))
            // ---- backup jobs
            path == "/cluster/backup" && request.method == "GET" -> json(backupJobs.values.joinToString(",", "[", "]"))
            path == "/cluster/backup" && request.method == "POST" -> {
                val p = form(request)
                val id = "backup-new${backupJobs.size}"
                backupJobs[id] = org.json.JSONObject(p + ("id" to id) + ("type" to "vzdump")).toString()
                json("null")
            }
            path.startsWith("/cluster/backup/") && request.method == "PUT" -> json("null")
            path.startsWith("/cluster/backup/") && request.method == "DELETE" -> {
                backupJobs.remove(path.removePrefix("/cluster/backup/"))
                json("null")
            }
            path == "/cluster/backup-info/not-backed-up" -> json("""[{"vmid":200,"name":"gh-runner-1","type":"lxc"}]""")
            path == "/nodes/pve/vzdump" && request.method == "POST" -> json("\"UPID:pve:00000040:vzdump::root@pam:\"")
            path.startsWith("/nodes/pve/network") -> json("""[{"iface":"vmbr0","type":"bridge"},{"iface":"vmbr1","type":"bridge"}]""")
            path == "/nodes/pve/aplinfo" && request.method == "GET" ->
                json("""[{"template":"system/debian-12-standard_12.7-1_amd64.tar.zst","os":"debian-12","section":"system","description":"Debian 12"},{"template":"system/alpine-3.20-default_20240908_amd64.tar.xz","os":"alpine","section":"system","description":"Alpine"}]""")
            path == "/nodes/pve/aplinfo" && request.method == "POST" -> json("\"UPID:pve:00000010:download:local:root@pam:\"")
            path == "/nodes/pve/lxc" && request.method == "POST" -> {
                lastCreateParams = form(request)
                json("\"UPID:pve:00000011:vzcreate:${form(request)["vmid"]}:root@pam:\"")
            }
            path == "/nodes/pve/qemu" && request.method == "POST" -> {
                lastVmCreateParams = form(request)
                json("\"UPID:pve:00000012:qmcreate:${form(request)["vmid"]}:root@pam:\"")
            }
            Regex("/nodes/pve/(lxc|qemu)/(\\d+)/clone").matches(path.substringBefore('?')) && request.method == "POST" -> {
                val m = Regex("/nodes/pve/(lxc|qemu)/(\\d+)/clone").find(path.substringBefore('?'))!!
                // Tests wait on lastCloneParams, then read cloned: write it first.
                cloned = m.groupValues[1] to m.groupValues[2].toInt()
                lastCloneParams = form(request)
                json("\"UPID:pve:00000014:${if (m.groupValues[1] == "qemu") "qmclone" else "vzclone"}:${m.groupValues[2]}:root@pam:\"")
            }
            // Containers only have the synchronous PUT; VMs are edited with the async POST, which starts a task.
            path.matches(Regex("/nodes/pve/lxc/\\d+/config")) && request.method == "PUT" -> {
                lastConfigParams = form(request)
                json("null")
            }
            path.matches(Regex("/nodes/pve/qemu/\\d+/config")) && request.method == "POST" -> {
                lastConfigParams = form(request)
                json("\"UPID:pve:00000015:qmconfig:${path.split('/')[4]}:root@pam:\"")
            }
            path.matches(Regex("/nodes/pve/(lxc|qemu)/\\d+/resize")) && request.method == "PUT" -> {
                lastResizeParams = form(request)
                if (path.contains("/lxc/")) json("\"UPID:pve:00000016:resize:${path.split('/')[4]}:root@pam:\"") else json("null")
            }
            request.method == "DELETE" && Regex("/nodes/pve/(lxc|qemu)/\\d+").matches(path.substringBefore('?')) -> {
                val m = Regex("/nodes/pve/(lxc|qemu)/(\\d+)").find(path.substringBefore('?'))!!
                deleted = m.groupValues[1] to m.groupValues[2].toInt()
                json("\"UPID:pve:00000013:${if (m.groupValues[1] == "qemu") "qmdestroy" else "vzdestroy"}:${m.groupValues[2]}:root@pam:\"")
            }
            path == "/nodes/pve/qemu/100/status/current" -> json(
                """{"status":"$status","name":"web-01","cpus":2,"maxmem":2147483648,"mem":0,"uptime":${if (vmRunning.get()) 60 else 0}}""",
            )
            path == "/nodes/pve/qemu/100/config" -> json(
                """{"name":"web-01","memory":2048,"cores":2,"boot":"order=scsi0;net0","scsi0":"local-zfs:vm-100-disk-0,size=32G",
                    "ide2":"local:iso/installer.iso,media=cdrom","net0":"virtio=BC:24:11:00:00:02,bridge=vmbr0",
                    ${if (vmTemplate) "\"template\":1," else ""}"digest":"abc"}""",
            )
            path == "/nodes/pve/lxc/200/status/current" -> json(
                """{"status":"running","name":"gh-runner-1","cpus":4,"maxmem":2147483648,"mem":20971520,"uptime":120}""",
            )
            path == "/nodes/pve/lxc/200/config" -> json(
                """{"hostname":"gh-runner-1","memory":2048,"cores":4,"swap":512,"rootfs":"local-zfs:subvol-200-disk-0,size=16G","features":"nesting=1",
                    "net0":"name=eth0,bridge=vmbr0,hwaddr=BC:24:11:00:00:01,ip=dhcp,type=veth","digest":"abc"}""",
            )
            // PVE 8.1+ shape: inet/inet6 hold one address each, ip-addresses holds all of them.
            path == "/nodes/pve/lxc/200/interfaces" -> json(
                """[{"name":"lo","hwaddr":"00:00:00:00:00:00","inet":"127.0.0.1/8","inet6":"::1/128",
                     "ip-addresses":[{"ip-address":"127.0.0.1","ip-address-type":"inet","prefix":8}]},
                    {"name":"eth0","hwaddr":"bc:24:11:00:00:01","inet":"192.0.2.50/24","inet6":"fe80::be24:11ff:fe00:1/64",
                     "ip-addresses":[{"ip-address":"192.0.2.50","ip-address-type":"inet","prefix":24},
                                     {"ip-address":"2001:db8::50","ip-address-type":"inet6","prefix":64},
                                     {"ip-address":"fe80::be24:11ff:fe00:1","ip-address-type":"inet6","prefix":64}]}]""",
            )
            path == "/nodes/pve/qemu/100/agent/network-get-interfaces" -> error(500, "QEMU guest agent is not running")
            path == "/nodes/pve/lxc/200/snapshot" && request.method == "GET" -> json("[]")
            path == "/nodes/pve/qemu/100/snapshot" && request.method == "GET" ->
                json("""[{"name":"current","running":0},{"name":"clean","snaptime":1700000000,"description":"fresh install"}]""")
            path.matches(Regex("/nodes/pve/(qemu|lxc)/\\d+/snapshot")) && request.method == "POST" ->
                json("\"UPID:pve:00000002:snapshot:${path.split('/')[4]}:root@pam:\"")
            path.startsWith("/nodes/pve/qemu/100/snapshot/clean") && request.method == "DELETE" ->
                json("\"UPID:pve:00000003:delsnapshot:100:root@pam:\"")
            path == "/nodes/pve/qemu/100/status/start" && request.method == "POST" -> {
                vmRunning.set(true)
                json("\"UPID:pve:00000001:qmstart:100:root@pam:\"")
            }
            path == "/nodes/pve/qemu/100/status/reset" && request.method == "POST" -> {
                sentKeys.clear()
                json("\"UPID:pve:00000014:qmreset:100:root@pam:\"")
            }
            path == "/nodes/pve/qemu/100/sendkey" && request.method == "PUT" -> {
                sentKeys += form(request).getValue("key")
                json("null")
            }
            path == "/nodes/pve/qemu/100/status/stop" && request.method == "POST" -> {
                vmRunning.set(false)
                json("\"UPID:pve:00000004:qmstop:100:root@pam:\"")
            }
            path.startsWith("/nodes/pve/tasks/") && request.method == "DELETE" -> {
                taskRunning = false
                json("null")
            }
            path.startsWith("/nodes/pve/tasks/") && path.contains("/status") ->
                if (taskRunning) json("""{"status":"running"}""") else json("""{"status":"stopped","exitstatus":"OK"}""")
            path.matches(Regex("/nodes/pve/(qemu|lxc)/\\d+/(move_disk|move_volume)")) && request.method == "POST" ->
                json("\"UPID:pve:00000030:${if (path.endsWith("move_disk")) "qmmove" else "move_volume"}:${path.split('/')[4]}:root@pam:\"")
            path.matches(Regex("/nodes/pve/(qemu|lxc)/\\d+/template")) && request.method == "POST" -> {
                if (path == "/nodes/pve/qemu/100/template") vmTemplate = true
                json("\"UPID:pve:00000031:qmtemplate:${path.split('/')[4]}:root@pam:\"")
            }
            path == "/nodes/pve/qemu/100/status/suspend" && request.method == "POST" -> {
                vmRunning.set(false)
                json("\"UPID:pve:00000032:qmsuspend:100:root@pam:\"")
            }
            path.startsWith("/nodes/pve/tasks/") && path.contains("/log") -> json("""[{"n":1,"t":"starting"},{"n":2,"t":"TASK OK"}]""")
            path == "/cluster/tasks" -> json(
                """[{"upid":"UPID:pve:00000001:qmstart:100:root@pam:","node":"pve","type":"qmstart","id":"100","user":"root@pam","starttime":1700000000,"endtime":1700000001,"status":"OK"}]""",
            )
            else -> error(501, "Method '$path' not implemented in FakeProxmox")
        }
    }

    private fun firewall(request: RecordedRequest, path: String): MockResponse {
        val method = request.method.orEmpty()
        val rules = Regex("^(.*/firewall/rules|/cluster/firewall/groups/[^/]+)(?:/(\\d+))?$").find(path)
        if (rules != null && path != "/cluster/firewall/groups/macros") {
            val list = firewallRules.getOrPut(rules.groupValues[1]) { mutableListOf() }
            val pos = rules.groupValues[2].toIntOrNull()
            return when {
                method == "GET" && pos == null -> json(
                    list.mapIndexed { i, r -> org.json.JSONObject(r + ("pos" to i.toString())).put("pos", i).put("enable", r["enable"]?.toInt() ?: 0) }
                        .joinToString(",", "[", "]"),
                )
                method == "POST" && pos == null -> {
                    list.add(0, form(request))
                    json("null")
                }
                method == "PUT" && pos != null -> {
                    val p = form(request)
                    val rule = list[pos]
                    val moveto = p["moveto"]?.toInt()
                    if (moveto != null) {
                        // Proxmox's order: the rule goes before the rule that was at `moveto`.
                        val others = list.filterIndexed { i, _ -> i != pos }.toMutableList()
                        val before = list.getOrNull(moveto)
                        val at = if (before == null || moveto >= list.size) others.size else others.indexOf(before)
                        others.add(at, rule)
                        list.clear()
                        list.addAll(others)
                    } else {
                        val gone = p["delete"].orEmpty().split(',').toSet()
                        list[pos] = (rule + p - "delete").filterKeys { it !in gone }
                    }
                    json("null")
                }
                method == "DELETE" && pos != null -> {
                    list.removeAt(pos)
                    json("null")
                }
                else -> error(501, "firewall $method $path")
            }
        }
        val options = Regex("^(.*/firewall)/options$").find(path)
        if (options != null) {
            val map = firewallOptions.getOrPut(options.groupValues[1]) { mutableMapOf() }
            if (method == "PUT") {
                val p = form(request)
                p["delete"]?.split(',')?.forEach { map.remove(it) }
                map.putAll(p - "delete")
                return json("null")
            }
            return json(org.json.JSONObject(map as Map<*, *>).toString())
        }
        return when {
            path == "/cluster/firewall/aliases" && method == "GET" -> json(
                firewallAliases.entries.joinToString(",", "[", "]") { (n, v) ->
                    org.json.JSONObject(mapOf("name" to n, "cidr" to v.first, "comment" to v.second)).toString()
                },
            )
            path == "/cluster/firewall/aliases" && method == "POST" -> {
                val p = form(request)
                firewallAliases[p.getValue("name")] = p.getValue("cidr") to p["comment"].orEmpty()
                json("null")
            }
            path.startsWith("/cluster/firewall/aliases/") && method == "PUT" -> {
                val p = form(request)
                firewallAliases[path.substringAfterLast('/')] = p.getValue("cidr") to p["comment"].orEmpty()
                json("null")
            }
            path.startsWith("/cluster/firewall/aliases/") && method == "DELETE" -> {
                firewallAliases.remove(path.substringAfterLast('/'))
                json("null")
            }
            path == "/cluster/firewall/ipset" && method == "GET" ->
                json(ipSets.keys.joinToString(",", "[", "]") { """{"name":"$it"}""" })
            path == "/cluster/firewall/ipset" && method == "POST" -> {
                ipSets[form(request).getValue("name")] = mutableListOf()
                json("null")
            }
            path.startsWith("/cluster/firewall/ipset/") -> {
                val parts = path.removePrefix("/cluster/firewall/ipset/").split('/')
                val set = ipSets.getValue(parts[0])
                when {
                    method == "GET" -> json(set.joinToString(",", "[", "]") { """{"cidr":"$it"}""" })
                    method == "POST" -> {
                        set += form(request).getValue("cidr")
                        json("null")
                    }
                    method == "DELETE" && parts.size == 2 -> {
                        set.remove(URLDecoder.decode(parts[1], "UTF-8"))
                        json("null")
                    }
                    method == "DELETE" -> {
                        ipSets.remove(parts[0])
                        json("null")
                    }
                    else -> error(501, "ipset $method $path")
                }
            }
            path == "/cluster/firewall/groups" && method == "GET" ->
                json(securityGroups.joinToString(",", "[", "]") { """{"group":"$it"}""" })
            path == "/cluster/firewall/groups" && method == "POST" -> {
                securityGroups += form(request).getValue("group")
                json("null")
            }
            path == "/cluster/firewall/macros" -> json("""[{"macro":"SSH","descr":"Secure shell traffic"},{"macro":"Ping","descr":"ICMP echo request"}]""")
            path.endsWith("/firewall/log") -> json("""[{"n":1,"t":"100 6 net0-IN 24/Oct/2026:10:00:00 +0000 policy DROP: IN=fwbr100i0 SRC=192.0.2.99"}]""")
            else -> error(501, "firewall $method $path")
        }
    }

    private companion object {
        const val EXTRA_STORAGE = """,{"storage":"fast","type":"lvmthin","content":"rootdir,images","used":1,"total":500,"active":1}"""
        const val ISO = """{"volid":"local:iso/debian-12.7.0-amd64-netinst.iso","content":"iso","format":"iso","size":660000000}"""

        /** Newest first per guest; one VM and one container backup on `local`. */
        const val BACKUPS = """[
          {"volid":"local:backup/vzdump-qemu-100-2026_10_13-01_00_00.vma.zst","content":"backup","format":"vma.zst","size":1073741824,
           "vmid":100,"ctime":1791853200,"notes":"web-01","subtype":"qemu"},
          {"volid":"local:backup/vzdump-lxc-200-2026_10_12-01_00_00.tar.zst","content":"backup","format":"tar.zst","size":536870912,
           "vmid":200,"ctime":1791766800,"notes":"gh-runner-1","subtype":"lxc"}
        ]"""
    }

    /** pve-runners' JSON API, served on the same port (point the profile's runners port here). */
    val runners = FakeRunners { auth ->
        auth == "PVEAPIToken=root@pam!ci=token-secret" ||
            auth.startsWith("PVEAuthCookie=") && URLDecoder.decode(auth.removePrefix("PVEAuthCookie="), "UTF-8") == ticket
    }

    fun requestsTo(pathSuffix: String, method: String? = null): List<RecordedRequest> =
        requests.filter { it.path.orEmpty().substringBefore('?').endsWith(pathSuffix) && (method == null || it.method == method) }

    override fun close() = server.shutdown()
}
