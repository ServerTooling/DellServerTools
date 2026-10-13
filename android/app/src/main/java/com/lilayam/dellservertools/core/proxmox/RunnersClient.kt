package com.lilayam.dellservertools.core.proxmox

import com.lilayam.dellservertools.core.formatHostForUrl
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection
import org.json.JSONArray
import org.json.JSONObject

class RunnersApiException(val httpStatus: Int, message: String) : IOException(message)

data class RunnerRow(val id: Int, val name: String, val status: String, val pool: String, val owner: String, val age: String)

/** What `pve-runners status` reports. */
data class RunnersStatus(
    val version: String,
    val host: String,
    val max: Int,
    val runners: List<RunnerRow>,
    val pending: List<String>,
    val storage: String,
    val warnings: List<String>,
)

/**
 * One input of the settings form. [kind] is text, number, bool, select or secret; a secret's value is
 * never sent by the service, only whether it is [set]. Booleans are "true"/"false".
 */
data class RunnerSetting(
    val name: String,
    val label: String,
    val help: String,
    val kind: String,
    val value: String,
    val options: List<String>,
    val set: Boolean,
)

data class SettingsGroup(val id: String, val title: String, val fields: List<RunnerSetting>)

data class RunnersSettings(
    val hostId: String,
    val sections: List<SettingsGroup>,
    val pools: List<SettingsGroup>,
    val targets: List<SettingsGroup>,
) {
    val allFields: List<RunnerSetting> get() = (sections + pools + targets).flatMap { it.fields }

    fun value(name: String): String? = allFields.firstOrNull { it.name == name }?.value

    /**
     * The body of a save. The service treats a missing field as emptied, so it gets every field as
     * shown with [edits] applied; a secret is sent only when typed (blank keeps the stored one).
     */
    fun saveValues(edits: Map<String, String>, newPool: String = ""): Map<String, String> {
        val values = linkedMapOf<String, String>()
        for (f in allFields) {
            values[f.name] = edits[f.name] ?: if (f.kind == "secret") "" else f.value
        }
        for ((k, v) in edits) if (k.endsWith("_clear")) values[k] = v
        if (newPool.isNotBlank()) values["new_pool"] = newPool.trim()
        return values
    }
}

data class ActionRun(val running: Boolean, val output: String, val error: String, val started: String, val ended: String)

/** A CLI command the service runs in the background, like the page's buttons. */
data class RunnerAction(
    val name: String,
    val label: String,
    val help: String,
    val group: String,
    val args: List<String>,
    val confirm: String,
    val last: ActionRun?,
)

/**
 * pve-runners' JSON API on the Proxmox host. It serves the node's certificate, so the pin the user
 * approved for the Proxmox API covers it too, and it takes the same Proxmox credentials.
 */
class RunnersClient(private val proxmox: ProxmoxClient, host: String, port: Int) {
    val baseUrl: String = "https://${formatHostForUrl(host)}:$port/api/v1"

    fun status(): RunnersStatus = RunnersJson.status(JSONObject(request("GET", "/status")))

    fun settings(): RunnersSettings = RunnersJson.settings(JSONObject(request("GET", "/settings")))

    /** Saves and restarts the service; a validation error comes back as a [RunnersApiException] (422). */
    fun saveSettings(values: Map<String, String>) {
        request("PUT", "/settings", JSONObject().put("values", JSONObject(values)))
    }

    fun actions(): List<RunnerAction> {
        val array = JSONArray(request("GET", "/actions"))
        return (0 until array.length()).map { RunnersJson.action(array.getJSONObject(it)) }
    }

    fun action(name: String): RunnerAction = RunnersJson.action(JSONObject(request("GET", "/actions/${enc(name)}")))

    /** Starts an action; one already running is not started twice and comes back as it is (409). */
    fun runAction(name: String, args: Map<String, String>): RunnerAction {
        val body = JSONObject().put("args", JSONObject(args))
        return try {
            RunnersJson.action(JSONObject(request("POST", "/actions/${enc(name)}", body)))
        } catch (e: RunnersApiException) {
            if (e.httpStatus != 409) throw e
            action(name)
        }
    }

    private fun request(method: String, path: String, body: JSONObject? = null): String = try {
        rawRequest(method, path, body)
    } catch (e: RunnersApiException) {
        // Tickets expire after two hours; log in again once, as the Proxmox client does.
        if (e.httpStatus == 401 && proxmox.canRenewLogin) {
            proxmox.login()
            rawRequest(method, path, body)
        } else {
            throw e
        }
    }

    private fun rawRequest(method: String, path: String, body: JSONObject?): String {
        val conn = URL(baseUrl + path).openConnection() as HttpsURLConnection
        conn.sslSocketFactory = proxmox.tls.socketFactory
        conn.hostnameVerifier = proxmox.tls.hostnameVerifier
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Authorization", proxmox.runnersAuthorization())
        try {
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                .orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty()
                throw RunnersApiException(status, message.ifBlank { "HTTP $status ${conn.responseMessage.orEmpty()}".trim() })
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

/** Parses the service's JSON; kept apart from the client so it is testable on its own. */
object RunnersJson {
    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { optString(it) }

    fun status(o: JSONObject): RunnersStatus {
        val rows = o.optJSONArray("runners")
        return RunnersStatus(
            version = o.optString("version"),
            host = o.optString("host"),
            max = o.optInt("max"),
            runners = if (rows == null) emptyList() else (0 until rows.length()).map {
                val r = rows.getJSONObject(it)
                RunnerRow(r.optInt("id"), r.optString("name"), r.optString("status"), r.optString("pool"),
                    r.optString("owner"), r.optString("age"))
            },
            pending = o.optJSONArray("pending").strings(),
            storage = o.optString("storage"),
            warnings = o.optJSONArray("warnings").strings(),
        )
    }

    private fun groups(a: JSONArray?): List<SettingsGroup> = if (a == null) emptyList() else (0 until a.length()).map { i ->
        val g = a.getJSONObject(i)
        val fields = g.optJSONArray("fields") ?: JSONArray()
        SettingsGroup(g.optString("id"), g.optString("title"), (0 until fields.length()).map { j ->
            val f = fields.getJSONObject(j)
            RunnerSetting(f.optString("name"), f.optString("label"), f.optString("help"), f.optString("kind"),
                f.optString("value"), f.optJSONArray("options").strings(), f.optBoolean("set"))
        })
    }

    fun settings(o: JSONObject) = RunnersSettings(
        hostId = o.optString("host_id"),
        sections = groups(o.optJSONArray("sections")),
        pools = groups(o.optJSONArray("pools")),
        targets = groups(o.optJSONArray("targets")),
    )

    fun action(o: JSONObject): RunnerAction {
        val last = o.optJSONObject("last")?.let {
            ActionRun(it.optBoolean("running"), it.optString("output"), it.optString("error"), it.optString("started"),
                it.optString("ended"))
        }
        return RunnerAction(o.optString("name"), o.optString("label"), o.optString("help"), o.optString("group"),
            o.optJSONArray("args").strings(), o.optString("confirm"), last)
    }
}
