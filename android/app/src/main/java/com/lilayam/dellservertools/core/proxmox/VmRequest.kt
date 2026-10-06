package com.lilayam.dellservertools.core.proxmox

/** OS types offered when creating a VM (Proxmox `ostype` values). */
enum class OsType(val api: String, val label: String) {
    LINUX("l26", "Linux"),
    WIN11("win11", "Windows 11"),
    WIN10("win10", "Windows 10"),
    OTHER("other", "Other"),
}

enum class VmBios(val api: String, val label: String) {
    SEABIOS("seabios", "SeaBIOS (default)"),
    OVMF("ovmf", "OVMF (UEFI)"),
}

/** The choices for a new QEMU VM, mapped to `POST /nodes/{node}/qemu` params. */
data class VmRequest(
    val vmid: Int,
    val name: String,
    val cores: Int,
    val sockets: Int,
    val memoryMb: Int,
    val diskStorage: String,
    val diskGb: Int,
    val isoVolid: String = "",        // ide2 cdrom, optional, e.g. "local:iso/debian-12.iso"
    val bridge: String,
    val osType: OsType = OsType.LINUX,
    val bios: VmBios = VmBios.SEABIOS,
    val q35: Boolean = false,
    val agent: Boolean = true,
    val start: Boolean = false,
    val onboot: Boolean = false,
) {
    fun nameError(): String? {
        if (name.isBlank()) return "Enter a name"
        if (name.length > 63) return "Name is too long"
        if (!Regex("^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$").matches(name)) {
            return "Letters, digits, dots and dashes only; must start and end with a letter or digit"
        }
        return null
    }

    fun validate(): String? {
        nameError()?.let { return it }
        if (vmid < 100) return "VM ID must be 100 or higher"
        if (diskStorage.isBlank()) return "Choose a storage for the disk"
        if (diskGb < 1) return "Disk must be at least 1 GB"
        if (cores < 1) return "At least 1 core"
        if (sockets < 1) return "At least 1 socket"
        if (memoryMb < 16) return "At least 16 MB of memory"
        if (bridge.isBlank()) return "Choose a network bridge"
        return null
    }

    fun toParams(): Map<String, String> {
        val useQ35 = q35 || bios == VmBios.OVMF
        val params = linkedMapOf(
            "vmid" to vmid.toString(),
            "name" to name,
            "cores" to cores.toString(),
            "sockets" to sockets.toString(),
            "memory" to memoryMb.toString(),
            "ostype" to osType.api,
            "scsihw" to "virtio-scsi-single",
            "scsi0" to "$diskStorage:$diskGb",
            "net0" to "virtio,bridge=$bridge,firewall=1",
            "bios" to bios.api,
            "agent" to if (agent) "1" else "0",
            "onboot" to if (onboot) "1" else "0",
            "start" to if (start) "1" else "0",
        )
        if (useQ35) params["machine"] = "q35"
        if (bios == VmBios.OVMF) params["efidisk0"] = "$diskStorage:1,efitype=4m,pre-enrolled-keys=1"
        val boot = buildString {
            append("order=scsi0")
            if (isoVolid.isNotBlank()) append(";ide2")
            append(";net0")
        }
        params["boot"] = boot
        if (isoVolid.isNotBlank()) params["ide2"] = "$isoVolid,media=cdrom"
        return params
    }

    companion object {
        fun defaults(vmid: Int) = VmRequest(
            vmid = vmid,
            name = "vm-$vmid",
            cores = 2,
            sockets = 1,
            memoryMb = 4096,
            diskStorage = "",
            diskGb = 32,
            bridge = "vmbr0",
        )
    }
}
