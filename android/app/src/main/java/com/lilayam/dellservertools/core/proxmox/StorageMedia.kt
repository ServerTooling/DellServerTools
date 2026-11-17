package com.lilayam.dellservertools.core.proxmox

import org.json.JSONArray
import org.json.JSONObject

/**
 * Which kind of disks (SSD, HDD, ...) a storage sits on. Proxmox has no call for it, so it is worked out from the
 * storage.cfg entry and the node's disks: ZFS pools and LVM groups name their devices, a directory storage is found
 * by its mount or, when it was mounted by hand, by a whole-disk filesystem of about its size, and `local` lives on
 * the root filesystem, which the installer puts on `rpool` or the `pve` volume group.
 */
object StorageMedia {
    /** What was read from the node; every part may be empty when the token can't read it. */
    data class NodeDisks(
        val disks: List<PhysicalDisk>,
        /** storage.cfg entries by storage ID. */
        val definitions: Map<String, Map<String, String>>,
        /** ZFS pool name to the device names in its vdev tree. */
        val zfsDevices: Map<String, List<String>> = emptyMap(),
        /** LVM volume group to its physical volumes. */
        val lvm: Map<String, List<String>> = emptyMap(),
        /** Mount path to device, for directories made by the Disks panel. */
        val mounts: Map<String, String> = emptyMap(),
    )

    private const val ROOT_DIR = "/var/lib/vz"

    /** Filesystems a whole disk can carry as a directory storage (`used` of `GET /disks/list`). */
    private val FILESYSTEMS = setOf("ext2", "ext3", "ext4", "xfs", "btrfs", "f2fs")

    /** Display order of the kinds in a label. */
    private val ORDER = listOf("NVMe", "SSD", "HDD", "USB")

    /**
     * Seagate model numbers say whether the drive spins: ST + capacity + form letter, where F means flash
     * (ST400FM0053 is a SAS SSD) and the rest are hard drives (ST900MM0168, ST4000VN008). SAS drives often report
     * no rotation rate, so Proxmox lists them as "unknown".
     */
    private val SEAGATE_HDD = Regex("\\bST\\d+[A-EG-Z][A-Z]\\d")

    /** Pools whose devices are needed: those of zfspool storages, plus `rpool` for the root filesystem. */
    fun poolsToRead(definitions: Map<String, Map<String, String>>): Set<String> =
        definitions.values.filter { it["type"] == "zfspool" }.mapNotNull { it["pool"]?.substringBefore('/') }.toSet() + "rpool"

    /** True when an LVM group may matter: an LVM storage, or a root filesystem that isn't on `rpool`. */
    fun needsLvm(definitions: Map<String, Map<String, String>>, zfsDevices: Map<String, List<String>>): Boolean =
        definitions.values.any { it["type"] == "lvm" || it["type"] == "lvmthin" } || "rpool" !in zfsDevices

    /** Labels such as "SSD" or "SSD + HDD" by storage ID, for the storages whose disks could be found. */
    fun labels(storages: List<StorageInfo>, node: NodeDisks): Map<String, String> =
        storages.mapNotNull { s -> disksOf(s, node)?.let(::describe)?.let { s.storage to it } }.toMap()

    /** The disks under [storage], or null when they can't be told. */
    fun disksOf(storage: StorageInfo, node: NodeDisks): List<PhysicalDisk>? {
        val def = node.definitions[storage.storage] ?: return null
        val disks = when (storage.type) {
            "zfspool" -> def["pool"]?.substringBefore('/')?.let { node.zfsDevices[it] }?.let { match(it, node.disks) }
            "lvm", "lvmthin" -> def["vgname"]?.let { node.lvm[it] }?.let { match(it, node.disks) }
            "dir" -> def["path"]?.let { directory(it.trimEnd('/'), storage.total, node) }
            else -> null
        }
        return disks?.takeIf { it.isNotEmpty() }
    }

    /** "SSD", "SSD + HDD", or with "unknown" for disks that don't say; null if none says. */
    fun describe(disks: List<PhysicalDisk>): String? {
        val kinds = disks.map(::kind)
        val known = kinds.filterNotNull().distinct().sortedBy { ORDER.indexOf(it) }
        if (known.isEmpty()) return null
        return (known + listOfNotNull("unknown".takeIf { null in kinds })).joinToString(" + ")
    }

    fun kind(disk: PhysicalDisk): String? = when (disk.type.lowercase()) {
        "ssd" -> "SSD"
        "nvme" -> "NVMe"
        "hdd" -> "HDD"
        "usb" -> "USB"
        else -> "HDD".takeIf { SEAGATE_HDD.containsMatchIn(disk.model.uppercase()) }
    }

    /** `GET /disks/lvm`: the root's children are volume groups, theirs the physical volumes. */
    fun lvmTree(o: JSONObject): Map<String, List<String>> = o.optJSONArray("children").objects().associate { vg ->
        vg.optString("name") to vg.optJSONArray("children").objects().map { it.optString("name") }
    }

    fun directoryMounts(data: JSONArray): Map<String, String> =
        data.objects().associate { it.optString("path").trimEnd('/') to it.optString("device") }

    private fun directory(path: String, total: Long, node: NodeDisks): List<PhysicalDisk>? {
        // A by-uuid device doesn't name its disk; fall through to the size match then.
        node.mounts[path]?.let { device -> match(listOf(device), node.disks).takeIf { it.isNotEmpty() }?.let { return it } }
        if (path == ROOT_DIR || path.startsWith("$ROOT_DIR/")) {
            return (node.zfsDevices["rpool"] ?: node.lvm["pve"])?.let { match(it, node.disks) }
        }
        if (total <= 0) return null
        // Mounted by hand: a disk holding just a filesystem, a little bigger than the filesystem's size.
        val fits = node.disks.filter { it.used in FILESYSTEMS && total <= it.size && total >= it.size * 0.9 }
        return fits.takeIf { it.map(::kind).distinct().size == 1 }
    }

    /** The disks the device names point at: `sda`, `sda3`, `/dev/sda3`, `ata-…-part3`, `wwn-0x…`, `nvme0n1p3`. */
    private fun match(devices: List<String>, disks: List<PhysicalDisk>): List<PhysicalDisk> =
        devices.mapNotNull { device ->
            val name = device.substringAfterLast('/')
            disks.firstOrNull { d -> ids(d).any { name == it || isPartition(name, it) } }
        }.distinct()

    private fun ids(d: PhysicalDisk): List<String> = listOfNotNull(
        d.devPath.substringAfterLast('/'),
        d.byIdLink.substringAfterLast('/'),
        d.wwn.takeIf { it.isNotBlank() }?.let { "wwn-$it" },
    ).filter { it.isNotBlank() }

    private val PARTITION = Regex("-part\\d+|p?\\d+")

    private fun isPartition(name: String, disk: String) = name.startsWith(disk) && PARTITION.matches(name.removePrefix(disk))

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}
