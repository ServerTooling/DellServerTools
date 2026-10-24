package com.lilayam.dellservertools.core.proxmox

import com.lilayam.dellservertools.core.formatHostForUrl
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection
import org.json.JSONArray
import org.json.JSONObject

class ProxmoxApiException(val httpStatus: Int, message: String) : IOException(message)

sealed interface ProxmoxCredentials {
    /** Username like `root@pam`. Gives a ticket, which also logs the in-app web UI in. */
    data class Password(val username: String, val password: String) : ProxmoxCredentials

    /** Token ID like `root@pam!phone` plus its secret. */
    data class ApiToken(val tokenId: String, val secret: String) : ProxmoxCredentials
}

data class ProxmoxTicket(val ticket: String, val csrfToken: String, val username: String)

/**
 * Minimal client for the Proxmox VE REST API (`/api2/json`).
 *
 * Every call blocks; run it off the main thread.
 */
class ProxmoxClient(
    host: String,
    port: Int,
    private val credentials: ProxmoxCredentials,
    pinnedFingerprint: String?,
) {
    val baseUrl: String = "https://${formatHostForUrl(host)}:$port"
    internal val tls = PinnedTls(pinnedFingerprint)

    @Volatile
    var ticket: ProxmoxTicket? = null
        private set

    /** Logs in (password auth) or checks the token, failing fast with a clear error. */
    fun login() {
        when (credentials) {
            is ProxmoxCredentials.Password -> {
                val data = rawRequest(
                    "POST",
                    "/access/ticket",
                    mapOf("username" to credentials.username, "password" to credentials.password),
                    authenticated = false,
                ) as? JSONObject ?: throw ProxmoxApiException(0, "Unexpected login response")
                if (data.has("NeedTFA") && data.optInt("NeedTFA") == 1 || data.optString("ticket").contains("!tfa!")) {
                    throw ProxmoxApiException(
                        0,
                        "This account uses two-factor authentication. Create an API token in Proxmox " +
                            "(Datacenter > Permissions > API Tokens) and enter it in the server settings.",
                    )
                }
                ticket = ProxmoxTicket(
                    ticket = data.getString("ticket"),
                    csrfToken = data.getString("CSRFPreventionToken"),
                    username = data.optString("username", credentials.username),
                )
            }
            is ProxmoxCredentials.ApiToken -> get("/version")
        }
    }

    /** The certificate that made the last request fail, if that was the reason. */
    val rejectedCertificate: UntrustedCertificateException? get() = tls.rejected

    fun version(): String = (get("/version") as JSONObject).let { "${it.optString("version")}-${it.optString("release")}" }

    fun resources(): List<ClusterResource> = ProxmoxJson.resources(get("/cluster/resources") as JSONArray)

    fun nodeStatus(node: String): NodeStatus = ProxmoxJson.nodeStatus(get("/nodes/${enc(node)}/status") as JSONObject)

    fun nodeCommand(node: String, command: String) {
        post("/nodes/${enc(node)}/status", mapOf("command" to command))
    }

    fun storage(node: String): List<StorageInfo> = ProxmoxJson.storage(get("/nodes/${enc(node)}/storage") as JSONArray)

    fun storageStatus(node: String, storage: String): StorageStatus =
        ProxmoxJson.storageStatus(get("/nodes/${enc(node)}/storage/${enc(storage)}/status") as JSONObject)

    fun storageConfig(storage: String): List<Pair<String, String>> =
        ProxmoxJson.storageConfig(get("/storage/${enc(storage)}") as JSONObject)

    fun storageContent(node: String, storage: String): List<StorageVolume> =
        ProxmoxJson.storageContent(get("/nodes/${enc(node)}/storage/${enc(storage)}/content") as JSONArray)

    fun zfsPool(node: String, pool: String): ZfsPool =
        ProxmoxJson.zfsPool(get("/nodes/${enc(node)}/disks/zfs/${enc(pool)}") as JSONObject)

    fun disks(node: String): List<PhysicalDisk> = ProxmoxJson.disks(get("/nodes/${enc(node)}/disks/list") as JSONArray)

    // ---- VM hardware

    /** USB devices plugged into the node, for passthrough. */
    fun usbDevices(node: String): List<UsbDevice> =
        VmHardware.usbDevices(get("/nodes/${enc(node)}/hardware/usb") as JSONArray)

    /** PCI devices of the node (bridges and the like left out by Proxmox's default filter), for passthrough. */
    fun pciDevices(node: String): List<PciDevice> =
        VmHardware.pciDevices(get("/nodes/${enc(node)}/hardware/pci") as JSONArray)

    /** Rebuilds a VM's cloud-init drive from its current cloud-init settings. */
    fun regenerateCloudInit(node: String, vmid: Int) {
        request("PUT", "${guestPath(node, GuestType.QEMU, vmid)}/cloudinit", emptyMap())
    }

    // ---- storage management

    /** Deletes one volume (ISO, template, backup, unused disk). Returns the task UPID when it runs as a task. */
    fun deleteVolume(node: String, storage: String, volid: String): String? =
        request("DELETE", "${storagePath(node, storage)}/content/${enc(volid)}", emptyMap()) as? String

    /** Sets a backup's notes and protection flag (a protected backup is never pruned or deleted). */
    fun updateVolume(node: String, storage: String, volid: String, notes: String, protected: Boolean) {
        request(
            "PUT",
            "${storagePath(node, storage)}/content/${enc(volid)}",
            mapOf("notes" to notes, "protected" to if (protected) "1" else "0"),
        )
    }

    /** File name and size of a URL, as the server sees it, before downloading. */
    fun queryUrlMetadata(node: String, url: String, verifyCertificates: Boolean = true): UrlMetadata =
        UrlMetadata.parse(
            request(
                "GET",
                "/nodes/${enc(node)}/query-url-metadata",
                mapOf("url" to url, "verify-certificates" to if (verifyCertificates) "1" else "0"),
            ) as? JSONObject,
        )

    /** Makes the server download an ISO or template itself. Returns the task UPID. */
    fun downloadUrl(node: String, storage: String, request: DownloadUrlRequest): String? =
        post("${storagePath(node, storage)}/download-url", request.toParams()) as? String

    /**
     * Uploads an ISO or template from the phone ([content] `iso` or `vztmpl`), streaming [input] of exactly [size]
     * bytes so a multi-GB ISO never sits in memory. Returns the UPID of the task that moves it into place.
     */
    fun upload(
        node: String,
        storage: String,
        content: String,
        filename: String,
        size: Long,
        input: InputStream,
        onProgress: (Long) -> Unit = {},
    ): String? {
        // Renew an expired ticket now: the body can't be sent twice, so the usual retry on 401 can't apply.
        get("/version")
        val boundary = "----dellservertools" + System.nanoTime()
        val head = buildString {
            append("--$boundary\r\nContent-Disposition: form-data; name=\"content\"\r\n\r\n$content\r\n")
            // pveproxy wants the file as the last part, under the name "filename".
            append("--$boundary\r\nContent-Disposition: form-data; name=\"filename\"; filename=\"")
            append(filename.replace("\"", "").replace("\r", "").replace("\n", ""))
            append("\"\r\nContent-Type: application/octet-stream\r\n\r\n")
        }.toByteArray(Charsets.UTF_8)
        val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val body = RequestBody("multipart/form-data; boundary=$boundary", head.size + size + tail.size) { out ->
            out.write(head)
            val buffer = ByteArray(256 * 1024)
            var sent = 0L
            var reported = 0L
            while (sent < size) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), size - sent).toInt())
                if (n < 0) throw IOException("The file ended after $sent of $size bytes")
                out.write(buffer, 0, n)
                sent += n
                if (sent - reported >= PROGRESS_STEP || sent == size) {
                    reported = sent
                    onProgress(sent)
                }
            }
            out.write(tail)
        }
        return rawRequest("POST", "${storagePath(node, storage)}/upload", emptyMap(), authenticated = true, body = body) as? String
    }

    /** Which backups a retention policy would keep or remove; [vmid] limits it to one guest. */
    fun prunePreview(node: String, storage: String, options: PruneOptions, vmid: Int? = null): List<PruneEntry> =
        PruneEntry.parse(request("GET", "${storagePath(node, storage)}/prunebackups", pruneParams(options, vmid)) as JSONArray)

    /** Removes the backups [prunePreview] marks `remove`. Returns the task UPID. */
    fun pruneBackups(node: String, storage: String, options: PruneOptions, vmid: Int? = null): String? =
        request("DELETE", "${storagePath(node, storage)}/prunebackups", pruneParams(options, vmid)) as? String

    private fun pruneParams(options: PruneOptions, vmid: Int?): Map<String, String> {
        val p = linkedMapOf("prune-backups" to options.toParam())
        vmid?.let { p["vmid"] = it.toString() }
        return p
    }

    /** The storage.cfg entry as a map (`GET /storage/{id}`). */
    fun storageDefinition(storage: String): Map<String, String> =
        (get("/storage/${enc(storage)}") as JSONObject).let { o ->
            o.keys().asSequence().filter { it != "digest" }.associateWith { o.opt(it)?.toString().orEmpty() }
        }

    fun createStorage(form: StorageForm) {
        post("/storage", form.createParams())
    }

    fun updateStorage(form: StorageForm) {
        val params = form.updateParams()
        if (params.isNotEmpty()) request("PUT", "/storage/${enc(form.id)}", params)
    }

    /** Removes the storage definition only; the data on it stays where it is. */
    fun deleteStorage(storage: String) {
        request("DELETE", "/storage/${enc(storage)}", emptyMap())
    }

    /** ZFS pools, LVM volume groups and thin pools on the node, and NFS exports of a server, for the add form. */
    fun scanZfs(node: String): List<String> = scan(node, "zfs", emptyMap(), "pool")

    fun scanLvm(node: String): List<String> = scan(node, "lvm", emptyMap(), "vg")

    fun scanLvmThin(node: String, vg: String): List<String> = scan(node, "lvmthin", mapOf("vg" to vg), "lv")

    fun scanNfs(node: String, server: String): List<String> = scan(node, "nfs", mapOf("server" to server), "path")

    private fun scan(node: String, type: String, params: Map<String, String>, field: String): List<String> {
        val data = request("GET", "/nodes/${enc(node)}/scan/$type", params) as? JSONArray ?: return emptyList()
        return (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString(field)?.takeIf(String::isNotEmpty) }
    }

    fun guestStatus(node: String, type: GuestType, vmid: Int): GuestStatus =
        ProxmoxJson.guestStatus(get("${guestPath(node, type, vmid)}/status/current") as JSONObject)

    fun guestConfig(node: String, type: GuestType, vmid: Int): List<Pair<String, String>> =
        ProxmoxJson.config(get("${guestPath(node, type, vmid)}/config") as JSONObject)

    /** The guest's current addresses: LXC asks the container, QEMU asks the guest agent (fails when it isn't running). */
    fun guestAddresses(node: String, type: GuestType, vmid: Int): List<GuestAddress> = when (type) {
        GuestType.LXC -> GuestNetwork.lxcInterfaces(get("${guestPath(node, type, vmid)}/interfaces") as JSONArray)
        GuestType.QEMU ->
            GuestNetwork.agentInterfaces(get("${guestPath(node, type, vmid)}/agent/network-get-interfaces") as JSONObject)
    }

    /** start, stop, shutdown, reboot, suspend, resume, reset. Returns the task UPID. */
    fun guestAction(node: String, type: GuestType, vmid: Int, action: String): String? =
        post("${guestPath(node, type, vmid)}/status/$action", emptyMap()) as? String

    /** Presses [key] (a [VmKeys] name, e.g. "ret") in a running QEMU VM. */
    fun sendKey(node: String, vmid: Int, key: String) {
        put("${guestPath(node, GuestType.QEMU, vmid)}/sendkey", VmKeys.params(key))
    }

    fun snapshots(node: String, type: GuestType, vmid: Int): List<Snapshot> =
        ProxmoxJson.snapshots(get("${guestPath(node, type, vmid)}/snapshot") as JSONArray)

    fun createSnapshot(node: String, type: GuestType, vmid: Int, name: String, description: String): String? {
        val params = mutableMapOf("snapname" to name)
        if (description.isNotBlank()) params["description"] = description
        return post("${guestPath(node, type, vmid)}/snapshot", params) as? String
    }

    fun rollbackSnapshot(node: String, type: GuestType, vmid: Int, name: String): String? =
        post("${guestPath(node, type, vmid)}/snapshot/${enc(name)}/rollback", emptyMap()) as? String

    fun deleteSnapshot(node: String, type: GuestType, vmid: Int, name: String): String? =
        request("DELETE", "${guestPath(node, type, vmid)}/snapshot/${enc(name)}", emptyMap()) as? String

    /** Starts a backup of one guest. Storage blank means the node's default backup storage. */
    fun backup(node: String, vmid: Int, storage: String?, mode: String = "snapshot"): String? {
        val params = mutableMapOf("vmid" to vmid.toString(), "mode" to mode, "compress" to "zstd")
        if (!storage.isNullOrBlank()) params["storage"] = storage
        return post("/nodes/${enc(node)}/vzdump", params) as? String
    }

    /** Backup archives on a storage, newest first. */
    fun backups(node: String, storage: String): List<StorageVolume> =
        ProxmoxJson.storageContent(get("/nodes/${enc(node)}/storage/${enc(storage)}/content?content=backup") as JSONArray)

    /** The guest config saved in a backup, as `key: value` lines. */
    fun backupConfig(node: String, volid: String): Map<String, String> =
        RestoreRequest.parseConfig(get("/nodes/${enc(node)}/vzdump/extractconfig?volume=${enc(volid)}") as? String ?: "")

    /** Restores a backup into a new or an existing guest. Returns the task UPID. */
    fun restoreGuest(node: String, request: RestoreRequest): String? =
        post("/nodes/${enc(node)}/${request.type.apiName}", request.toParams()) as? String

    // ---- container creation ----

    /** Next free VM/CT id in the cluster. */
    fun nextVmid(): Int = (get("/cluster/nextid") as? String)?.toIntOrNull() ?: 100

    /** Already-downloaded templates (content=vztmpl) across a storage, as volids. */
    fun templates(node: String, storage: String): List<TemplateFile> =
        ProxmoxJson.content(get("/nodes/${enc(node)}/storage/${enc(storage)}/content?content=vztmpl") as JSONArray)

    /** ISO images (content=iso) on a storage. */
    fun isoImages(node: String, storage: String): List<TemplateFile> =
        ProxmoxJson.content(get("/nodes/${enc(node)}/storage/${enc(storage)}/content?content=iso") as JSONArray)

    /** Creates a QEMU VM. Returns the task UPID. */
    fun createQemu(node: String, params: Map<String, String>): String? =
        post("/nodes/${enc(node)}/qemu", params) as? String

    /**
     * Changes config of an existing container or VM. VMs use the async POST, which the API recommends for
     * anything that allocates storage or hotplugs, and return its task UPID; containers only have a PUT.
     */
    fun updateConfig(node: String, type: GuestType, vmid: Int, params: Map<String, String>): String? {
        if (params.isEmpty()) return null
        val method = if (type == GuestType.QEMU) "POST" else "PUT"
        return request(method, "${guestPath(node, type, vmid)}/config", params) as? String
    }

    /** Grows a disk or mount point, e.g. `size=+8G`. Returns a task UPID when the server runs it as a task. */
    fun resizeDisk(node: String, type: GuestType, vmid: Int, disk: String, size: String): String? =
        request("PUT", "${guestPath(node, type, vmid)}/resize", mapOf("disk" to disk, "size" to size)) as? String

    /** Deletes a container or VM. Returns the task UPID. */
    fun deleteGuest(node: String, type: GuestType, vmid: Int, purge: Boolean, destroyUnreferenced: Boolean): String? {
        val params = mutableMapOf<String, String>()
        if (purge) params["purge"] = "1"
        if (destroyUnreferenced) params["destroy-unreferenced-disks"] = "1"
        return request("DELETE", guestPath(node, type, vmid), params) as? String
    }

    /** Network bridges on the node. */
    fun bridges(node: String): List<String> =
        ProxmoxJson.bridges(get("/nodes/${enc(node)}/network?type=bridge") as JSONArray)

    /** Downloadable appliance templates (the `pveam available` list). */
    fun availableTemplates(node: String): List<AplTemplate> =
        ProxmoxJson.aplinfo(get("/nodes/${enc(node)}/aplinfo") as JSONArray)

    /** Starts downloading a template into a storage. Returns the task UPID. */
    fun downloadTemplate(node: String, storage: String, template: String): String? =
        post("/nodes/${enc(node)}/aplinfo", mapOf("storage" to storage, "template" to template)) as? String

    /** Creates an LXC container. Returns the task UPID. */
    fun createLxc(node: String, params: Map<String, String>): String? =
        post("/nodes/${enc(node)}/lxc", params) as? String

    /** Clones a container or VM. Returns the task UPID. */
    fun cloneGuest(node: String, type: GuestType, vmid: Int, params: Map<String, String>): String? =
        post("${guestPath(node, type, vmid)}/clone", params) as? String

    fun tasks(node: String, limit: Int = 50): List<TaskSummary> =
        ProxmoxJson.tasks(get("/nodes/${enc(node)}/tasks?limit=$limit") as JSONArray)

    /** Recent tasks across the whole cluster (or the single node). */
    fun clusterTasks(): List<TaskSummary> = ProxmoxJson.tasks(get("/cluster/tasks") as JSONArray)

    fun taskStatus(node: String, upid: String): TaskStatus =
        ProxmoxJson.taskStatus(get("/nodes/${enc(node)}/tasks/${enc(upid)}/status") as JSONObject)

    fun taskLog(node: String, upid: String, limit: Int = 1000): List<String> =
        ProxmoxJson.taskLog(get("/nodes/${enc(node)}/tasks/${enc(upid)}/log?limit=$limit") as JSONArray)

    /** URL of the web UI, or of a noVNC / xterm.js console page inside it. */
    fun webUrl(mobile: Boolean): String = "$baseUrl/?mobile=${if (mobile) 1 else 0}"

    fun consoleUrl(node: String, type: GuestType?, vmid: Int?, name: String?): String {
        val params = when (type) {
            GuestType.QEMU -> "console=kvm&novnc=1&vmid=$vmid&vmname=${enc(name.orEmpty())}&node=${enc(node)}&resize=scale"
            GuestType.LXC -> "console=lxc&xtermjs=1&vmid=$vmid&vmname=${enc(name.orEmpty())}&node=${enc(node)}"
            null -> "console=shell&xtermjs=1&vmid=0&node=${enc(node)}&cmd="
        }
        return "$baseUrl/?$params"
    }

    /**
     * What pve-runners' API takes in its Authorization header: the same API token, or the login
     * ticket as Proxmox's own cookie (logging in first if needed). The service checks it with Proxmox.
     */
    fun runnersAuthorization(): String = when (credentials) {
        is ProxmoxCredentials.ApiToken -> "PVEAPIToken=${credentials.tokenId}=${credentials.secret}"
        is ProxmoxCredentials.Password -> {
            val t = ticket ?: run { login(); ticket!! }
            "PVEAuthCookie=${enc(t.ticket)}"
        }
    }

    /** Whether a 401 might be an expired ticket, so logging in again could help. */
    val canRenewLogin: Boolean get() = credentials is ProxmoxCredentials.Password

    fun get(path: String): Any? = request("GET", path, emptyMap())

    fun post(path: String, params: Map<String, String>): Any? = request("POST", path, params)

    fun put(path: String, params: Map<String, String>): Any? = request("PUT", path, params)

    private fun request(method: String, path: String, params: Map<String, String>): Any? {
        if (credentials is ProxmoxCredentials.Password && ticket == null) login()
        return try {
            rawRequest(method, path, params, authenticated = true)
        } catch (e: ProxmoxApiException) {
            // Tickets expire after two hours; log in again once.
            if (e.httpStatus == 401 && credentials is ProxmoxCredentials.Password) {
                login()
                rawRequest(method, path, params, authenticated = true)
            } else {
                throw e
            }
        }
    }

    /** A request body other than the usual form, sent with a fixed length so it is streamed, not buffered. */
    private class RequestBody(val contentType: String, val length: Long, val write: (OutputStream) -> Unit)

    private fun rawRequest(
        method: String,
        path: String,
        params: Map<String, String>,
        authenticated: Boolean,
        body: RequestBody? = null,
    ): Any? {
        val form = params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val hasBody = method == "POST" || method == "PUT"
        val url = URL(
            "$baseUrl/api2/json$path" + if (!hasBody && form.isNotEmpty()) {
                (if (path.contains('?')) "&" else "?") + form
            } else {
                ""
            },
        )
        val conn = url.openConnection() as HttpsURLConnection
        conn.sslSocketFactory = tls.socketFactory
        conn.hostnameVerifier = tls.hostnameVerifier
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        // After an upload's last byte, pveproxy still copies the file before it answers.
        conn.readTimeout = if (body != null) 10 * 60_000 else 60_000
        conn.setRequestProperty("Accept", "application/json")
        if (authenticated) {
            when (credentials) {
                is ProxmoxCredentials.ApiToken ->
                    conn.setRequestProperty("Authorization", "PVEAPIToken=${credentials.tokenId}=${credentials.secret}")
                is ProxmoxCredentials.Password -> ticket?.let {
                    conn.setRequestProperty("Cookie", "PVEAuthCookie=${enc(it.ticket)}")
                    if (method != "GET") conn.setRequestProperty("CSRFPreventionToken", it.csrfToken)
                }
            }
        }
        try {
            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.length)
                conn.setRequestProperty("Content-Type", body.contentType)
                conn.outputStream.use(body.write)
            } else if (hasBody) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val body = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                .orEmpty()
            if (status !in 200..299) {
                throw ProxmoxApiException(status, errorMessage(status, conn.responseMessage, body))
            }
            if (body.isBlank()) return null
            return JSONObject(body).opt("data").takeUnless { it == JSONObject.NULL }
        } finally {
            conn.disconnect()
        }
    }

    private fun errorMessage(status: Int, reason: String?, body: String): String {
        val details = runCatching {
            val json = JSONObject(body)
            val errors = json.optJSONObject("errors")
            buildList {
                json.optString("message").takeIf { it.isNotBlank() }?.let { add(it.trim()) }
                errors?.keys()?.forEach { key -> add("$key: ${errors.optString(key).trim()}") }
            }.joinToString("; ")
        }.getOrNull().orEmpty()
        return when {
            status == 401 -> "Not authorized: ${details.ifBlank { reason.orEmpty() }}. Check username, realm and password."
            details.isNotBlank() -> "HTTP $status: $details"
            else -> "HTTP $status ${reason.orEmpty()}".trim()
        }
    }

    private fun storagePath(node: String, storage: String) = "/nodes/${enc(node)}/storage/${enc(storage)}"

    private companion object {
        const val PROGRESS_STEP = 4L * 1024 * 1024
    }

    private fun guestPath(node: String, type: GuestType, vmid: Int) = "/nodes/${enc(node)}/${type.apiName}/$vmid"

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
