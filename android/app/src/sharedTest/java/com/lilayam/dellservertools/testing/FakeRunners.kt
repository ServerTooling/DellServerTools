package com.lilayam.dellservertools.testing

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject

/**
 * A fake of pve-runners' /api/v1, answering like internal/setup/api.go: Proxmox credentials in the
 * Authorization header, settings as groups of fields, whole-form saves, background actions.
 */
class FakeRunners(private val accepts: (String) -> Boolean) {
    /** Number of upcoming requests to refuse with 401, as if the ticket expired. */
    val rejectTimes = AtomicInteger(0)

    /** The values of the last accepted save. */
    @Volatile
    var lastSave: Map<String, String>? = null

    val saves = AtomicInteger(0)

    /** Lets the running action finish. */
    val finishAction = AtomicBoolean(false)

    @Volatile
    var lastActionArgs: Map<String, String>? = null

    private val values = linkedMapOf(
        "proxmox.max_runners" to "4",
        "autoscale.enabled" to "true",
        "update.channel" to "edge",
        "pool.default.cores" to "4",
        "pool.default.memory_mb" to "4096",
        "pool.default.max" to "4",
        "pool.default.warm" to "2",
        "target.0.pools" to "default",
        "target.0.runner_group" to "",
    )

    @Volatile
    private var lokiSet = false

    @Volatile
    private var running: String? = null

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun error(code: Int, msg: String) = json(JSONObject().put("error", msg).toString(), code)

    @Synchronized
    fun handle(request: RecordedRequest): MockResponse {
        val auth = request.getHeader("Authorization").orEmpty()
        if (!accepts(auth) || rejectTimes.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
            return error(401, "a Proxmox API token or ticket with Sys.Modify is needed")
        }
        val path = request.path.orEmpty().removePrefix("/api/v1")
        return when {
            path == "/status" -> json(status())
            path == "/settings" && request.method == "GET" -> json(settings())
            path == "/settings" && request.method == "PUT" -> save(JSONObject(request.body.clone().readUtf8()))
            path == "/actions" -> json(JSONArray(listOf(action("images-pull"), action("spawn"))).toString())
            path.startsWith("/actions/") && request.method == "GET" -> json(action(path.removePrefix("/actions/")).toString())
            path.startsWith("/actions/") && request.method == "POST" -> start(path.removePrefix("/actions/"), request.body.clone().readUtf8())
            else -> error(404, "not found")
        }
    }

    private fun status() = JSONObject()
        .put("version", "build-30").put("host", "pve1").put("max", values["proxmox.max_runners"]!!.toInt())
        .put("runners", JSONArray().put(JSONObject().put("id", 9000).put("name", "gh-pve1-default-9000").put("status", "running")
            .put("pool", "default").put("owner", "example-org").put("age", "3m")))
        .put("pending", JSONArray()).put("storage", "local-zfs")
        .put("warnings", JSONArray().put("runner network has no isolation"))
        .toString()

    private fun field(name: String, label: String, kind: String, options: List<String>? = null) = JSONObject()
        .put("name", name).put("label", label).put("kind", kind).put("value", values[name] ?: "false")
        .apply { options?.let { put("options", JSONArray(it)) } }

    private fun group(id: String, title: String, vararg fields: JSONObject) =
        JSONObject().put("id", id).put("title", title).put("fields", JSONArray(fields.toList()))

    private fun settings() = JSONObject()
        .put("host_id", "pve1")
        .put("sections", JSONArray()
            .put(group("s-this-host", "This host", field("proxmox.max_runners", "Max runners", "number"),
                field("autoscale.enabled", "Autoscale", "bool")))
            .put(group("s-updates", "Updates", field("update.channel", "Channel", "select", listOf("edge", "stable"))))
            .put(group("s-logs-grafana-loki", "Logs (Grafana Loki)",
                JSONObject().put("name", "loki_token").put("label", "Token").put("kind", "secret").put("value", "").put("set", lokiSet))))
        .put("pools", JSONArray().put(group("pool.default", "Pool default",
            field("pool.default.cores", "Cores", "number"), field("pool.default.memory_mb", "Memory (MB)", "number"),
            field("pool.default.max", "Max runners", "number"), field("pool.default.warm", "Warm spares after a job", "number"),
            field("pool.default.delete", "Delete this pool on save", "bool"))))
        .put("targets", JSONArray().put(group("target.0", "Org example-org", field("target.0.pools", "Pools", "text"),
            field("target.0.runner_group", "Runner group", "text"), field("target.0.remove", "Remove on save", "bool"))))
        .toString()

    private fun save(body: JSONObject): MockResponse {
        val v = body.optJSONObject("values") ?: return error(400, "expected {\"values\": {...}}")
        val map = v.keys().asSequence().associateWith { v.getString(it) }
        val max = map["proxmox.max_runners"].orEmpty()
        if (max.toIntOrNull() == null) return error(422, "proxmox.max_runners must be a whole number, got \"$max\"")
        lastSave = map
        saves.incrementAndGet()
        for (k in values.keys) map[k]?.let { values[k] = it }
        if (map["loki_token"].orEmpty().isNotBlank()) lokiSet = true
        return json("{\"restarting\":true}")
    }

    private fun action(name: String): JSONObject {
        val o = when (name) {
            "images-pull" -> JSONObject().put("name", name).put("label", "Pull latest images").put("group", "Images")
                .put("help", "Fetch every pool's newest image now.").put("args", JSONArray())
            else -> JSONObject().put("name", "spawn").put("label", "Start a runner").put("group", "Runners")
                .put("args", JSONArray().put("pool")).put("confirm", "Start a runner now?")
        }
        if (running == name) {
            val done = finishAction.get()
            o.put("last", JSONObject().put("running", !done).put("started", "04:30:00")
                .put("output", if (done) "pulling\npulled template-ubuntu-latest\n" else "pulling\n")
                .apply { if (done) put("ended", "04:31:00") })
        }
        return o
    }

    private fun start(name: String, body: String): MockResponse {
        if (running == name && !finishAction.get()) return json(action(name).toString(), 409)
        val args = runCatching { JSONObject(body).optJSONObject("args") }.getOrNull()
        lastActionArgs = args?.keys()?.asSequence()?.associateWith { args.getString(it) }.orEmpty()
        running = name
        finishAction.set(false)
        return json(action(name).toString(), 202)
    }
}
