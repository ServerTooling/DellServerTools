package com.lilayam.dellservertools.core.proxmox

/**
 * How to drop into a guest's shell from the Proxmox host's SSH shell.
 *
 * Containers get a real interactive shell with `pct enter`, which renders in the app's native
 * terminal (working keyboard + key bar) instead of the mobile-unfriendly web xterm.js console.
 */
object GuestShell {
    /**
     * The host-shell command that opens an interactive shell inside a running guest, or null when
     * there is no native shell for that [type] yet (QEMU VMs use the graphical/noVNC console; a
     * serial console via `qm terminal` is a future addition).
     */
    fun enterCommand(type: GuestType, vmid: Int): String? = when (type) {
        GuestType.LXC -> "pct enter $vmid"
        GuestType.QEMU -> null
    }

    /**
     * The host-shell command that clears a guest's config lock. A task killed midway (a host reboot or
     * a stopped service during create, clone, backup, ...) leaves its lock behind, and Proxmox then
     * refuses to start, change or delete the guest. The API can't clear it, so it goes through the shell
     * like in the web UI's docs.
     */
    fun unlockCommand(type: GuestType, vmid: Int): String = when (type) {
        GuestType.LXC -> "pct unlock $vmid"
        GuestType.QEMU -> "qm unlock $vmid"
    }
}
