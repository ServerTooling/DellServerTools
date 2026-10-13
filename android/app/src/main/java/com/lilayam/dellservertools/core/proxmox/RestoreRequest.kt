package com.lilayam.dellservertools.core.proxmox

/**
 * Restoring a vzdump backup, and the mapping to Proxmox parameters: a VM restore is `POST /nodes/{node}/qemu`
 * with `archive`, a container restore is `POST /nodes/{node}/lxc` with the archive as `ostemplate` and `restore=1`.
 */
data class RestoreRequest(
    val type: GuestType,
    /** The backup's volume id, e.g. `local:backup/vzdump-qemu-100-2026_10_13-01_00_00.vma.zst`. */
    val archive: String,
    val vmid: Int,
    /** Target storage for the disks; blank restores them to the storages named in the backup. */
    val storage: String = "",
    /** The type of the guest that already has [vmid], or null when the id is free. */
    val existingType: GuestType? = null,
    /** Replace the guest that has [vmid] (Proxmox's `force`). */
    val overwrite: Boolean = false,
    /** Containers only; sent explicitly so the result never depends on Proxmox's default. */
    val unprivileged: Boolean = true,
    /** New MAC addresses, so a copy can run next to the original on the same bridge. */
    val unique: Boolean = false,
    val start: Boolean = false,
) {
    fun validate(): String? {
        if (archive.isBlank()) return "Pick a backup"
        if (vmid < 100) return "ID must be 100 or higher"
        if (existingType != null) {
            if (existingType != type) {
                return "ID $vmid is a ${existingType.label}; a ${type.label} backup can't replace it. Pick another ID."
            }
            if (!overwrite) return "ID $vmid is in use: tick Overwrite or pick another ID"
        }
        return null
    }

    fun toParams(): Map<String, String> {
        val params = linkedMapOf("vmid" to vmid.toString())
        when (type) {
            GuestType.QEMU -> params["archive"] = archive
            GuestType.LXC -> {
                params["ostemplate"] = archive
                params["restore"] = "1"
                params["unprivileged"] = if (unprivileged) "1" else "0"
            }
        }
        if (storage.isNotBlank()) params["storage"] = storage
        // Only ever sent for the id the user confirmed; a free id never needs it.
        if (overwrite && existingType != null) params["force"] = "1"
        if (unique) params["unique"] = "1"
        if (start) params["start"] = "1"
        return params
    }

    companion object {
        /** Whether a backup volume holds a VM or a container, or null when it can't tell. */
        fun typeOf(volume: StorageVolume): GuestType? {
            when (volume.subtype) {
                "qemu" -> return GuestType.QEMU
                "lxc", "openvz" -> return GuestType.LXC
            }
            when (volume.format) {
                "pbs-vm" -> return GuestType.QEMU
                "pbs-ct" -> return GuestType.LXC
            }
            val name = volume.name
            return when {
                name.startsWith("vzdump-qemu-") -> GuestType.QEMU
                name.startsWith("vzdump-lxc-") || name.startsWith("vzdump-openvz-") -> GuestType.LXC
                // Proxmox Backup Server: `pbs:backup/vm/100/<time>` or `.../ct/200/<time>`.
                volume.volid.contains(":backup/vm/") -> GuestType.QEMU
                volume.volid.contains(":backup/ct/") -> GuestType.LXC
                else -> null
            }
        }

        /** The guest config stored in a backup (`GET /nodes/{node}/vzdump/extractconfig`), without its snapshots. */
        fun parseConfig(text: String): Map<String, String> =
            text.lineSequence()
                .takeWhile { !it.startsWith("[") }
                .filter { it.isNotBlank() && !it.startsWith("#") && it.contains(':') }
                .associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
    }
}
