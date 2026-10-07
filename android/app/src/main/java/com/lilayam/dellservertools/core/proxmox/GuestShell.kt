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
}
