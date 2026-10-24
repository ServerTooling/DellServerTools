package com.lilayam.dellservertools.core.proxmox

/**
 * Keys pressed in a QEMU VM through Proxmox's sendkey API (the `qm sendkey` names). They go from
 * the server straight to the VM, so they work whatever the noVNC page does with the phone keyboard.
 */
object VmKeys {
    data class Key(val label: String, val name: String)

    val keys = listOf(
        Key("Enter", "ret"),
        Key("Esc", "esc"),
        Key("Tab", "tab"),
        Key("Space", "spc"),
        Key("Up", "up"),
        Key("Down", "down"),
        Key("F2", "f2"),
        Key("F12", "f12"),
        Key("Ctrl+Alt+Del", "ctrl-alt-delete"),
    )

    fun params(key: String): Map<String, String> = mapOf("key" to key)

    /**
     * Windows ISOs show "Press any key to boot from CD or DVD" for about five seconds after the
     * firmware logo, which is easy to miss on a phone. After a reset, Enter is pressed every
     * [BOOT_PRESS_INTERVAL_MS] for [BOOT_PRESS_WINDOW_MS]: long enough to cover the logo and the
     * prompt, short enough to stop while Windows is still loading, before Setup's first page
     * (where Enter would press Next).
     */
    const val BOOT_KEY = "ret"
    const val BOOT_PRESS_INTERVAL_MS = 500L
    const val BOOT_PRESS_WINDOW_MS = 15_000L

    /** How many presses the boot window holds. */
    val bootPresses: Int get() = (BOOT_PRESS_WINDOW_MS / BOOT_PRESS_INTERVAL_MS).toInt()
}
