package com.lilayam.dellservertools.core.proxmox

import com.lilayam.dellservertools.core.formatHostForUrl
import java.io.IOException
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
    private val tls = PinnedTls(pinnedFingerprint)

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

    fun guestStatus(node: String, type: GuestType, vmid: Int): GuestStatus =
        ProxmoxJson.guestStatus(get("${guestPath(node, type, vmid)}/status/current") as JSONObject)

    fun guestConfig(node: String, type: GuestType, vmid: Int): List<Pair<String, String>> =
        ProxmoxJson.config(get("${guestPath(node, type, vmid)}/config") as JSONObject)

    /** start, stop, shutdown, reboot, suspend, resume, reset. Returns the task UPID. */
    fun guestAction(node: String, type: GuestType, vmid: Int, action: String): String? =
        post("${guestPath(node, type, vmid)}/status/$action", emptyMap()) as? String

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

    /** Changes config of an existing container or VM (synchronous PUT). */
    fun updateConfig(node: String, type: GuestType, vmid: Int, params: Map<String, String>) {
        if (params.isNotEmpty()) request("PUT", "${guestPath(node, type, vmid)}/config", params)
    }

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

    fun get(path: String): Any? = request("GET", path, emptyMap())

    fun post(path: String, params: Map<String, String>): Any? = request("POST", path, params)

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

    private fun rawRequest(method: String, path: String, params: Map<String, String>, authenticated: Boolean): Any? {
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
        conn.readTimeout = 60_000
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
            if (hasBody) {
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

    private fun guestPath(node: String, type: GuestType, vmid: Int) = "/nodes/${enc(node)}/${type.apiName}/$vmid"

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
