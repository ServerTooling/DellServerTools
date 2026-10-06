package com.lilayam.dellservertools.core.proxmox

/**
 * The choices for cloning a container or VM, and the mapping to Proxmox
 * `POST /nodes/{node}/{qemu|lxc}/{vmid}/clone` parameters. Kept separate from the
 * UI so it can be unit tested.
 */
data class CloneRequest(
    val type: GuestType,
    /** The id of the guest being cloned (the source). */
    val sourceVmid: Int,
    val newId: Int,
    /** name (VM) or hostname (CT) for the clone; blank keeps Proxmox's default. */
    val name: String = "",
    /** A full clone copies the disks; a linked clone only references them and is allowed from a template. */
    val full: Boolean = true,
    /** Whether the source is a template. A linked clone is only possible from a template. */
    val sourceIsTemplate: Boolean = false,
    /** Target storage for a full clone; blank keeps the source's storage. */
    val storage: String = "",
    /** Target node; blank clones onto the same node. */
    val target: String = "",
    /** Clone from this snapshot instead of the current state (optional). */
    val snapname: String = "",
    /** Add the clone to this resource pool (optional). */
    val pool: String = "",
) {
    private val isCt get() = type == GuestType.LXC

    /** A name/hostname Proxmox accepts, or null when blank (Proxmox then picks a default). */
    fun nameError(): String? {
        if (name.isBlank()) return null
        if (name.length > 63) return "Name is too long"
        // CT hostnames are DNS labels; VM names also allow dots.
        val pattern = if (isCt) {
            Regex("^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?$")
        } else {
            Regex("^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$")
        }
        if (!pattern.matches(name)) {
            return "Letters, digits${if (isCt) "" else ", dots"} and dashes only; must start and end with a letter or digit"
        }
        return null
    }

    fun validate(): String? {
        nameError()?.let { return it }
        if (newId < 100) return "New ID must be 100 or higher"
        if (newId == sourceVmid) return "New ID must differ from the source ID"
        if (!full && !sourceIsTemplate) return "A linked clone is only possible from a template; use a full clone"
        if (!full && storage.isNotBlank()) return "A linked clone keeps the source's storage; clear the target storage"
        return null
    }

    fun toParams(): Map<String, String> {
        val params = linkedMapOf(
            "newid" to newId.toString(),
            "full" to if (full) "1" else "0",
        )
        if (name.isNotBlank()) params[if (isCt) "hostname" else "name"] = name.trim()
        // A linked clone must stay on the source's storage, so only send it for a full clone.
        if (full && storage.isNotBlank()) params["storage"] = storage
        if (target.isNotBlank()) params["target"] = target
        if (snapname.isNotBlank()) params["snapname"] = snapname
        if (pool.isNotBlank()) params["pool"] = pool
        return params
    }
}
