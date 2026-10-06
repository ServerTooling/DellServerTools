package com.lilayam.dellservertools.core.proxmox

import org.json.JSONArray
import org.json.JSONObject

enum class GuestType(val apiName: String, val label: String) {
    QEMU("qemu", "VM"),
    LXC("lxc", "CT"),
}

/** One row of `GET /cluster/resources`. */
data class ClusterResource(
    val id: String,
    val type: String,
    val node: String,
    val name: String,
    val status: String,
    val vmid: Int?,
    val cpu: Double,
    val maxCpu: Int,
    val mem: Long,
    val maxMem: Long,
    val disk: Long,
    val maxDisk: Long,
    val uptime: Long,
    val template: Boolean,
    val storage: String?,
    val tags: List<String>,
) {
    val guestType: GuestType?
        get() = when (type) {
            "qemu" -> GuestType.QEMU
            "lxc" -> GuestType.LXC
            else -> null
        }

    val isRunning: Boolean get() = status == "running" || status == "online"
}

data class NodeStatus(
    val cpu: Double,
    val cpuCount: Int,
    val cpuModel: String,
    val memUsed: Long,
    val memTotal: Long,
    val swapUsed: Long,
    val swapTotal: Long,
    val rootUsed: Long,
    val rootTotal: Long,
    val uptime: Long,
    val loadAverage: List<String>,
    val pveVersion: String,
    val kernel: String,
)

data class GuestStatus(
    val status: String,
    /** QEMU's own state, e.g. `paused` while `status` still says `running`. */
    val qmpStatus: String?,
    val name: String,
    val cpu: Double,
    val cpus: Int,
    val mem: Long,
    val maxMem: Long,
    val disk: Long,
    val maxDisk: Long,
    val uptime: Long,
    val lock: String?,
) {
    val isRunning: Boolean get() = status == "running"
    val isPaused: Boolean get() = qmpStatus == "paused" || qmpStatus == "suspended"
}

data class Snapshot(
    val name: String,
    val description: String,
    val time: Long?,
    val parent: String?,
) {
    /** The pseudo-snapshot Proxmox lists for the live state. */
    val isCurrent: Boolean get() = name == "current"
}

data class TaskSummary(
    val upid: String,
    val node: String,
    val type: String,
    val id: String,
    val user: String,
    val startTime: Long,
    val endTime: Long?,
    val status: String?,
) {
    val isRunning: Boolean get() = endTime == null && status.isNullOrEmpty()
    val isOk: Boolean get() = status == "OK"
}

data class TaskStatus(val running: Boolean, val exitStatus: String?)

data class StorageInfo(
    val storage: String,
    val type: String,
    val content: List<String>,
    val used: Long,
    val total: Long,
    val active: Boolean,
)

/** Parsing of Proxmox API JSON (`{"data": ...}` already unwrapped). */
object ProxmoxJson {

    fun resources(data: JSONArray): List<ClusterResource> = data.objects().map { o ->
        ClusterResource(
            id = o.optString("id"),
            type = o.optString("type"),
            node = o.optString("node"),
            name = when (o.optString("type")) {
                "storage" -> o.optString("storage")
                "node" -> o.optString("node")
                else -> o.optString("name")
            }.ifEmpty { o.optString("id") },
            status = o.optString("status"),
            vmid = if (o.has("vmid")) o.optInt("vmid") else null,
            cpu = o.optDouble("cpu", 0.0).finiteOr0(),
            maxCpu = o.optInt("maxcpu"),
            mem = o.optLong("mem"),
            maxMem = o.optLong("maxmem"),
            disk = o.optLong("disk"),
            maxDisk = o.optLong("maxdisk"),
            uptime = o.optLong("uptime"),
            template = o.optInt("template", 0) == 1,
            storage = o.optString("storage").ifEmpty { null },
            tags = o.optString("tags").split(';', ',', ' ').map { it.trim() }.filter { it.isNotEmpty() },
        )
    }

    fun nodeStatus(o: JSONObject): NodeStatus {
        val memory = o.optJSONObject("memory") ?: JSONObject()
        val swap = o.optJSONObject("swap") ?: JSONObject()
        val rootfs = o.optJSONObject("rootfs") ?: JSONObject()
        val cpuInfo = o.optJSONObject("cpuinfo") ?: JSONObject()
        val load = o.optJSONArray("loadavg")
        return NodeStatus(
            cpu = o.optDouble("cpu", 0.0).finiteOr0(),
            cpuCount = cpuInfo.optInt("cpus"),
            cpuModel = cpuInfo.optString("model"),
            memUsed = memory.optLong("used"),
            memTotal = memory.optLong("total"),
            swapUsed = swap.optLong("used"),
            swapTotal = swap.optLong("total"),
            rootUsed = rootfs.optLong("used"),
            rootTotal = rootfs.optLong("total"),
            uptime = o.optLong("uptime"),
            loadAverage = if (load == null) emptyList() else (0 until load.length()).map { load.optString(it) },
            pveVersion = o.optString("pveversion"),
            kernel = o.optString("kversion"),
        )
    }

    fun guestStatus(o: JSONObject): GuestStatus = GuestStatus(
        status = o.optString("status"),
        qmpStatus = o.optString("qmpstatus").ifEmpty { null },
        name = o.optString("name"),
        cpu = o.optDouble("cpu", 0.0).finiteOr0(),
        cpus = o.optInt("cpus"),
        mem = o.optLong("mem"),
        maxMem = o.optLong("maxmem"),
        disk = o.optLong("disk"),
        maxDisk = o.optLong("maxdisk"),
        uptime = o.optLong("uptime"),
        lock = o.optString("lock").ifEmpty { null },
    )

    fun snapshots(data: JSONArray): List<Snapshot> = data.objects().map { o ->
        Snapshot(
            name = o.optString("name"),
            description = o.optString("description").trim(),
            time = if (o.has("snaptime")) o.optLong("snaptime") else null,
            parent = o.optString("parent").ifEmpty { null },
        )
    }.sortedWith(compareBy<Snapshot> { it.isCurrent }.thenBy { it.time ?: Long.MAX_VALUE })

    fun tasks(data: JSONArray): List<TaskSummary> = data.objects().map { o ->
        TaskSummary(
            upid = o.optString("upid"),
            node = o.optString("node"),
            type = o.optString("type"),
            id = o.optString("id"),
            user = o.optString("user"),
            startTime = o.optLong("starttime"),
            endTime = if (o.has("endtime")) o.optLong("endtime") else null,
            status = o.optString("status").ifEmpty { null },
        )
    }.sortedByDescending { it.startTime }

    fun taskStatus(o: JSONObject): TaskStatus = TaskStatus(
        running = o.optString("status") == "running",
        exitStatus = o.optString("exitstatus").ifEmpty { null },
    )

    fun taskLog(data: JSONArray): List<String> =
        data.objects().sortedBy { it.optInt("n") }.map { it.optString("t") }

    fun storage(data: JSONArray): List<StorageInfo> = data.objects().map { o ->
        StorageInfo(
            storage = o.optString("storage"),
            type = o.optString("type"),
            content = o.optString("content").split(',').map { it.trim() }.filter { it.isNotEmpty() },
            used = o.optLong("used"),
            total = o.optLong("total"),
            active = o.optInt("active", 1) == 1,
        )
    }.sortedBy { it.storage }

    /** VM / CT configuration as ordered key-value pairs (digest and pending noise removed). */
    fun config(o: JSONObject): List<Pair<String, String>> =
        o.keys().asSequence()
            .filter { it != "digest" }
            .map { it to o.opt(it)?.toString().orEmpty() }
            .sortedWith(compareBy({ configOrder(it.first) }, { it.first }))
            .toList()

    private fun configOrder(key: String): Int = when {
        key in listOf("name", "hostname", "description", "ostype", "cores", "sockets", "memory", "swap") -> 0
        key.matches(Regex("(scsi|virtio|sata|ide|efidisk|tpmstate|rootfs|mp)\\d*")) -> 1
        key.matches(Regex("net\\d+")) -> 2
        else -> 3
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

    private fun Double.finiteOr0(): Double = if (isFinite()) this else 0.0
}

object Format {
    fun bytes(value: Long): String {
        if (value < 1024) return "$value B"
        val units = listOf("KiB", "MiB", "GiB", "TiB", "PiB")
        var v = value.toDouble() / 1024
        var i = 0
        while (v >= 1024 && i < units.lastIndex) {
            v /= 1024
            i++
        }
        return if (v >= 100) "%.0f %s".format(v, units[i]) else "%.1f %s".format(v, units[i])
    }

    fun percent(fraction: Double): String = "%.0f%%".format((fraction * 100).coerceIn(0.0, 100.0 * 64))

    fun duration(seconds: Long): String {
        if (seconds <= 0) return "-"
        val d = seconds / 86_400
        val h = (seconds % 86_400) / 3_600
        val m = (seconds % 3_600) / 60
        return when {
            d > 0 -> "${d}d ${h}h"
            h > 0 -> "${h}h ${m}m"
            else -> "${m}m ${seconds % 60}s"
        }
    }
}
