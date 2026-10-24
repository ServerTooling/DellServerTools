package com.lilayam.dellservertools.core.proxmox

/**
 * Moving one disk (VM, `POST .../qemu/{vmid}/move_disk`) or volume (CT, `POST .../lxc/{vmid}/move_volume`) to
 * another storage. Without [deleteSource] the old copy stays behind as an unused disk.
 */
data class MoveDiskRequest(
    val type: GuestType,
    val disk: String,
    val storage: String,
    val deleteSource: Boolean = true,
    /** VM only: raw, qcow2 or vmdk; blank keeps the current format where the target allows it. */
    val format: String = "",
) {
    val path: String get() = if (type == GuestType.QEMU) "move_disk" else "move_volume"

    fun validate(currentStorage: String): String? {
        if (disk.isBlank()) return "Pick a disk"
        if (storage.isBlank()) return "Pick a target storage"
        if (storage == currentStorage && format.isBlank()) return "It is already on $storage"
        if (format.isNotBlank() && (type != GuestType.QEMU || format !in FORMATS)) return "Unknown format $format"
        return null
    }

    fun toParams(): Map<String, String> {
        val p = linkedMapOf(if (type == GuestType.QEMU) "disk" to disk else "volume" to disk, "storage" to storage)
        if (deleteSource) p["delete"] = "1"
        if (type == GuestType.QEMU && format.isNotBlank()) p["format"] = format
        return p
    }

    companion object {
        val FORMATS = listOf("raw", "qcow2", "vmdk")

        /** Disks and volumes that can move: real volumes on a storage, not CD drives, bind mounts or the cloud-init drive. */
        fun movable(e: GuestEdit): List<String> = e.allKeys().filter { key ->
            val v = e.current[key].orEmpty()
            key in e.current && GuestEdit.isDisk(key) && !v.contains("media=cdrom") && !v.contains("cloudinit") &&
                !v.startsWith("/") && v.substringBefore(',').contains(':')
        }

        /** The storage a disk value lives on, e.g. `local-zfs` for `local-zfs:vm-100-disk-0,size=32G`. */
        fun storageOf(value: String): String = value.substringBefore(',').substringBefore(':')
    }
}
