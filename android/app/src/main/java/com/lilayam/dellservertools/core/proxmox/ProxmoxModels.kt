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
) {
    fun holds(contentType: String) = content.contains(contentType)
}

/** `GET /nodes/{node}/storage/{storage}/status`. */
data class StorageStatus(
    val type: String,
    val content: List<String>,
    val shared: Boolean,
    val enabled: Boolean,
    val active: Boolean,
    val used: Long,
    val avail: Long,
    val total: Long,
)

/** One item of a storage's content: a disk image, backup, ISO, template, ... */
data class StorageVolume(
    val volid: String,
    val content: String,
    val format: String,
    val size: Long,
    val vmid: Int?,
    val ctime: Long?,
    val notes: String,
    val protected: Boolean,
) {
    val name: String get() = volid.substringAfter(':').substringAfterLast('/')
}

/** A ZFS pool's health (`GET /nodes/{node}/disks/zfs/{name}`), with the vdev tree flattened. */
data class ZfsPool(
    val name: String,
    val state: String,
    val status: String,
    val action: String,
    val scan: String,
    val errors: String,
    val devices: List<ZfsDevice>,
)

/** One line of `zpool status`: [depth] 0 is the pool itself. */
data class ZfsDevice(
    val name: String,
    val depth: Int,
    val state: String,
    val read: String,
    val write: String,
    val cksum: String,
    val msg: String,
)

/** A physical disk of the node (`GET /nodes/{node}/disks/list`). */
data class PhysicalDisk(
    val devPath: String,
    val model: String,
    val type: String,
    val size: Long,
    val health: String,
    val wearout: String,
    val used: String,
)

/** A container template already present on a storage. */
data class TemplateFile(val volid: String, val size: Long) {
    /** The file name without the `storage:vztmpl/` prefix, e.g. `debian-12-standard_...tar.zst`. */
    val name: String get() = volid.substringAfterLast('/')
}

/** A downloadable appliance template (from `pveam available`). */
data class AplTemplate(val template: String, val os: String, val section: String, val description: String) {
    val name: String get() = template.substringAfterLast('/')
}

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

    fun content(data: JSONArray): List<TemplateFile> = data.objects().map { o ->
        TemplateFile(volid = o.optString("volid"), size = o.optLong("size"))
    }.sortedByDescending { it.name }

    fun bridges(data: JSONArray): List<String> =
        data.objects().map { it.optString("iface") }.filter { it.isNotEmpty() }.sorted()

    fun aplinfo(data: JSONArray): List<AplTemplate> = data.objects().map { o ->
        AplTemplate(
            template = o.optString("template"),
            os = o.optString("os"),
            section = o.optString("section"),
            description = o.optString("description").trim(),
        )
    }.filter { it.template.isNotEmpty() }.sortedBy { it.name }

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

    fun storageStatus(o: JSONObject): StorageStatus = StorageStatus(
        type = o.optString("type"),
        content = o.optString("content").split(',').map { it.trim() }.filter { it.isNotEmpty() },
        shared = o.optInt("shared", 0) == 1,
        enabled = o.optInt("enabled", 1) == 1,
        active = o.optInt("active", 1) == 1,
        used = o.optLong("used"),
        avail = o.optLong("avail"),
        total = o.optLong("total"),
    )

    /** `GET /storage/{storage}`: the storage.cfg entry, minus what the status already shows. */
    fun storageConfig(o: JSONObject): List<Pair<String, String>> =
        o.keys().asSequence()
            .filter { it !in setOf("digest", "storage", "type", "content", "shared") }
            .map { it to o.opt(it)?.toString().orEmpty() }
            .sortedBy { it.first }
            .toList()

    fun storageContent(data: JSONArray): List<StorageVolume> = data.objects().map { o ->
        StorageVolume(
            volid = o.optString("volid"),
            content = o.optString("content"),
            format = o.optString("format"),
            size = o.optLong("size"),
            vmid = o.optString("vmid").toIntOrNull(),
            ctime = if (o.has("ctime")) o.optLong("ctime") else null,
            notes = o.optString("notes").trim(),
            protected = o.optInt("protected", 0) == 1,
        )
    }.sortedWith(compareBy<StorageVolume> { it.content }.thenByDescending { it.ctime ?: 0L }.thenBy { it.volid })

    fun zfsPool(o: JSONObject): ZfsPool {
        val devices = mutableListOf<ZfsDevice>()
        fun walk(d: JSONObject, depth: Int) {
            devices += ZfsDevice(
                name = d.optString("name"),
                depth = depth,
                state = d.optString("state"),
                read = d.optString("read"),
                write = d.optString("write"),
                cksum = d.optString("cksum"),
                msg = d.optString("msg").trim(),
            )
            d.optJSONArray("children")?.objects()?.forEach { walk(it, depth + 1) }
        }
        o.optJSONArray("children")?.objects()?.forEach { walk(it, 0) }
        return ZfsPool(
            name = o.optString("name"),
            state = o.optString("state"),
            status = o.optString("status").trim(),
            action = o.optString("action").trim(),
            scan = o.optString("scan").trim(),
            errors = o.optString("errors").trim(),
            devices = devices,
        )
    }

    fun disks(data: JSONArray): List<PhysicalDisk> = data.objects().map { o ->
        PhysicalDisk(
            devPath = o.optString("devpath"),
            model = o.optString("model"),
            type = o.optString("type"),
            size = o.optLong("size"),
            health = o.optString("health"),
            // Percent of rated life left (100 = new), or "N/A" for disks that don't report it.
            wearout = o.optString("wearout"),
            used = o.optString("used"),
        )
    }.sortedBy { it.devPath }

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
