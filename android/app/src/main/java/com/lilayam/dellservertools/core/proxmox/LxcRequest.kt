package com.lilayam.dellservertools.core.proxmox

/**
 * The choices for a new LXC container, and the mapping to Proxmox `POST /nodes/{node}/lxc`
 * parameters. Kept separate from the UI so it can be unit tested.
 */
data class LxcRequest(
    val vmid: Int,
    val hostname: String,
    val ostemplate: String,            // volid, e.g. "local:vztmpl/debian-12-standard_...tar.zst"
    val storage: String,               // rootfs storage, e.g. "local-zfs"
    val diskGb: Int,
    val cores: Int,
    val memoryMb: Int,
    val swapMb: Int,
    val bridge: String,                // e.g. "vmbr0"
    val useDhcp: Boolean,
    val staticCidr: String = "",       // e.g. "192.0.2.50/24" when useDhcp is false
    val gateway: String = "",          // e.g. "192.0.2.1"
    val unprivileged: Boolean = true,
    val nesting: Boolean = true,
    val password: String = "",
    val sshPublicKey: String = "",
    val start: Boolean = true,
    val onboot: Boolean = false,
) {
    /** A hostname Proxmox accepts: DNS label characters, no leading/trailing dash. */
    fun hostnameError(): String? {
        if (hostname.isBlank()) return "Enter a hostname"
        if (hostname.length > 63) return "Hostname is too long"
        if (!Regex("^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?$").matches(hostname)) {
            return "Letters, digits and dashes only; must start and end with a letter or digit"
        }
        return null
    }

    fun validate(): String? {
        hostnameError()?.let { return it }
        if (vmid < 100) return "Container ID must be 100 or higher"
        if (ostemplate.isBlank()) return "Choose a template"
        if (storage.isBlank()) return "Choose a storage for the disk"
        if (diskGb < 1) return "Disk must be at least 1 GB"
        if (cores < 1) return "At least 1 core"
        if (memoryMb < 16) return "At least 16 MB of memory"
        if (!useDhcp) {
            if (!CIDR.matches(staticCidr)) return "Static IP must look like 192.0.2.50/24"
        }
        if (password.isNotEmpty() && password.length < 5) return "Password must be at least 5 characters"
        if (password.isEmpty() && sshPublicKey.isBlank()) return "Set a root password or an SSH key"
        return null
    }

    fun toParams(): Map<String, String> {
        val net = buildString {
            append("name=eth0,bridge=$bridge")
            if (useDhcp) {
                append(",ip=dhcp")
            } else {
                append(",ip=$staticCidr")
                if (gateway.isNotBlank()) append(",gw=$gateway")
            }
        }
        val params = linkedMapOf(
            "vmid" to vmid.toString(),
            "hostname" to hostname,
            "ostemplate" to ostemplate,
            "rootfs" to "$storage:$diskGb",
            "cores" to cores.toString(),
            "memory" to memoryMb.toString(),
            "swap" to swapMb.toString(),
            "net0" to net,
            "unprivileged" to if (unprivileged) "1" else "0",
            "onboot" to if (onboot) "1" else "0",
            "start" to if (start) "1" else "0",
        )
        if (nesting) params["features"] = "nesting=1"
        if (password.isNotEmpty()) params["password"] = password
        if (sshPublicKey.isNotBlank()) params["ssh-public-keys"] = sshPublicKey.trim()
        return params
    }

    companion object {
        private val CIDR = Regex("^(\\d{1,3})(\\.\\d{1,3}){3}/\\d{1,2}$")

        fun defaults(vmid: Int) = LxcRequest(
            vmid = vmid,
            hostname = "ct-$vmid",
            ostemplate = "",
            storage = "",
            diskGb = 16,
            cores = 2,
            memoryMb = 2048,
            swapMb = 512,
            bridge = "vmbr0",
            useDhcp = true,
        )
    }
}
