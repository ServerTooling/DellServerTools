package com.lilayam.dellservertools.core.proxmox

import org.json.JSONArray
import org.json.JSONObject

/** What the Disks panel can build on unused disks; [path] is the API's `/nodes/{node}/disks/{path}`. */
enum class DiskKind(val path: String, val label: String) {
    LVM("lvm", "LVM"),
    LVMTHIN("lvmthin", "LVM-Thin"),
    DIRECTORY("directory", "Directory"),
    ZFS("zfs", "ZFS"),
}

/**
 * The create form of the web UI's Disks > LVM / LVM-Thin / Directory / ZFS panels. ZFS takes several disks, the
 * others exactly one. [addStorage] also adds a storage of the same name, as the web UI does by default.
 */
data class DiskCreateForm(
    val kind: DiskKind,
    val name: String = "",
    val devices: List<String> = emptyList(),
    val raidLevel: String = "mirror",
    val ashift: String = "12",
    val compression: String = "on",
    val filesystem: String = "ext4",
    val addStorage: Boolean = true,
) {
    fun validate(): String? {
        if (!NAME.matches(name)) return "Name: letters, digits, '-', '_' and '.', starting with a letter."
        if (devices.isEmpty()) return "Pick a disk."
        if (kind != DiskKind.ZFS) return if (devices.size == 1) null else "${kind.label} uses exactly one disk."
        val min = RAID_MIN[raidLevel] ?: return "Unknown layout $raidLevel."
        if (devices.size < min) return "$raidLevel needs at least $min disks."
        if (raidLevel == "raid10" && devices.size % 2 != 0) return "raid10 needs an even number of disks."
        if (ashift.toIntOrNull() !in 9..16) return "ashift is 9 to 16."
        return null
    }

    /** `POST /nodes/{node}/disks/{kind.path}` parameters. */
    fun params(): Map<String, String> {
        val p = linkedMapOf("name" to name)
        when (kind) {
            DiskKind.ZFS -> {
                p["raidlevel"] = raidLevel
                p["devices"] = devices.joinToString(",")
                p["ashift"] = ashift
                p["compression"] = compression
            }
            DiskKind.DIRECTORY -> {
                p["device"] = devices.single()
                p["filesystem"] = filesystem
            }
            else -> p["device"] = devices.single()
        }
        p["add_storage"] = if (addStorage) "1" else "0"
        return p
    }

    companion object {
        private val NAME = Regex("[a-zA-Z][a-zA-Z0-9_.-]*[a-zA-Z0-9]")

        /** Fewest disks per ZFS layout, as the web UI checks. */
        val RAID_MIN = linkedMapOf("single" to 1, "mirror" to 2, "raid10" to 4, "raidz" to 3, "raidz2" to 4, "raidz3" to 5)
        val COMPRESSIONS = listOf("on", "off", "lz4", "zstd", "gzip", "lzjb", "zle")
        val FILESYSTEMS = listOf("ext4", "xfs")

        /** Disks nothing uses yet: no partitions, LVM, ZFS or mount. */
        fun unused(disks: List<PhysicalDisk>): List<PhysicalDisk> = disks.filter { it.used.isBlank() || it.used == "unused" }
    }
}

/** One thing built on the node's disks, as listed by the four Disks panels, with how to destroy it. */
data class DiskVolume(
    val kind: DiskKind,
    val name: String,
    val detail: String,
    val size: Long = 0,
    val used: Long = 0,
    /** The volume group a thin pool sits in; the delete call needs it. */
    val volumeGroup: String = "",
) {
    /** `DELETE` path that also removes the storage entry and wipes the disks, like the web UI's "Destroy" with both boxes. */
    fun destroyPath(node: String, enc: (String) -> String): String {
        val vg = if (kind == DiskKind.LVMTHIN) "volume-group=${enc(volumeGroup)}&" else ""
        return "/nodes/${enc(node)}/disks/${kind.path}/${enc(name)}?${vg}cleanup-config=1&cleanup-disks=1"
    }

    companion object {
        fun zfs(data: JSONArray): List<DiskVolume> = data.objects().map {
            DiskVolume(
                DiskKind.ZFS,
                it.optString("name"),
                listOfNotNull(it.optString("health").ifEmpty { null }, it.optString("frag").ifEmpty { null }?.let { f -> "frag $f%" })
                    .joinToString(" · "),
                size = it.optLong("size"),
                used = it.optLong("alloc"),
            )
        }

        /** `GET /disks/lvm` is a tree: the root's children are volume groups, theirs the physical volumes. */
        fun lvm(data: JSONObject): List<DiskVolume> = data.optJSONArray("children").objects().map { vg ->
            val pvs = vg.optJSONArray("children").objects().map { it.optString("name") }
            DiskVolume(
                DiskKind.LVM,
                vg.optString("name"),
                pvs.joinToString(", "),
                size = vg.optLong("size"),
                used = vg.optLong("size") - vg.optLong("free"),
            )
        }

        fun lvmThin(data: JSONArray): List<DiskVolume> = data.objects().map {
            DiskVolume(
                DiskKind.LVMTHIN,
                it.optString("lv"),
                "in ${it.optString("vg")}",
                size = it.optLong("lv_size"),
                used = it.optLong("used"),
                volumeGroup = it.optString("vg"),
            )
        }

        /** Mounts are named after their path, `/mnt/pve/<name>`. */
        fun directories(data: JSONArray): List<DiskVolume> = data.objects().map {
            val path = it.optString("path")
            DiskVolume(DiskKind.DIRECTORY, path.substringAfterLast('/'), "${it.optString("device")} · ${it.optString("type")} · $path")
        }
    }
}

/** `GET /nodes/{node}/disks/smart`: ATA disks report attributes, others (NVMe, SAS) plain text. */
data class SmartData(val health: String, val attributes: List<SmartAttribute>, val text: String) {
    companion object {
        fun parse(o: JSONObject): SmartData = SmartData(
            health = o.optString("health"),
            attributes = o.optJSONArray("attributes").objects().map {
                SmartAttribute(
                    id = it.opt("id")?.toString().orEmpty().trim(),
                    name = it.optString("name"),
                    value = it.opt("value")?.toString().orEmpty(),
                    threshold = it.opt("threshold")?.toString().orEmpty(),
                    raw = it.opt("raw")?.toString().orEmpty(),
                    failing = it.optString("fail").let { f -> f.isNotBlank() && f != "-" },
                )
            },
            text = o.optString("text"),
        )
    }
}

data class SmartAttribute(val id: String, val name: String, val value: String, val threshold: String, val raw: String, val failing: Boolean)

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
