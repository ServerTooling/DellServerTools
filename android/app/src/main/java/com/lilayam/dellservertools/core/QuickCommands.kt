package com.lilayam.dellservertools.core

/** Shortcuts that type a command into an SSH shell. */
data class QuickCommand(
    val label: String,
    val command: String,
    val description: String,
    /** Changes server power state, so the UI asks before sending it. */
    val needsConfirmation: Boolean = false,
)

object IdracCommands {

    /** Ctrl+\ — leaves `console com2` and returns to the iDRAC prompt. */
    const val EXIT_SERIAL_CONSOLE = "\u001c"

    val serialConsole = QuickCommand(
        label = "Server console",
        command = "console com2",
        description = "Attach to the server's serial console (Serial Over LAN). " +
            "Press \"Exit console\" (Ctrl+\\) to get back to the iDRAC prompt.",
    )

    /** Pressed shortly after attaching so a waiting login prompt is redrawn. */
    const val SERIAL_CONSOLE_WAKE_DELAY_MS = 1_500L

    val quickCommands: List<QuickCommand> = listOf(
        serialConsole,
        QuickCommand("Power status", "racadm serveraction powerstatus", "Show whether the server is on or off."),
        QuickCommand("System info", "racadm getsysinfo", "iDRAC, BIOS and host system summary."),
        QuickCommand("Event log", "racadm getsel", "System event log (hardware errors, power events)."),
        QuickCommand("iDRAC log", "racadm getraclog", "iDRAC log (logins, configuration changes)."),
        QuickCommand("SOL settings", "racadm getconfig -g cfgIpmiSol", "Serial Over LAN configuration."),
        QuickCommand(
            "Power on",
            "racadm serveraction powerup",
            "Turn the server on.",
            needsConfirmation = true,
        ),
        QuickCommand(
            "Graceful shutdown",
            "racadm serveraction graceshutdown",
            "Ask the operating system to shut down (ACPI power button).",
            needsConfirmation = true,
        ),
        QuickCommand(
            "Power off",
            "racadm serveraction powerdown",
            "Cut power immediately. Unsaved data on the server is lost.",
            needsConfirmation = true,
        ),
        QuickCommand(
            "Power cycle",
            "racadm serveraction powercycle",
            "Power off, then back on.",
            needsConfirmation = true,
        ),
        QuickCommand(
            "Hard reset",
            "racadm serveraction hardreset",
            "Reset the server immediately, like pressing the reset button.",
            needsConfirmation = true,
        ),
        QuickCommand(
            "Reset iDRAC",
            "racadm racreset",
            "Reboot the iDRAC itself (the server keeps running). This session will drop.",
            needsConfirmation = true,
        ),
    )
}

object ProxmoxShellCommands {
    val quickCommands: List<QuickCommand> = listOf(
        QuickCommand("VMs", "qm list", "List virtual machines on this node."),
        QuickCommand("Containers", "pct list", "List LXC containers on this node."),
        QuickCommand("Storage", "pvesm status", "Storage usage."),
        QuickCommand("Version", "pveversion -v", "Proxmox VE package versions."),
        QuickCommand(
            "Enable iDRAC console login",
            "systemctl enable --now serial-getty@ttyS1.service && systemctl --no-pager status serial-getty@ttyS1.service | head -n 5",
            "Starts a login prompt on the serial port COM2 (ttyS1), which the iDRAC6 \"Server console\" " +
                "(console com2) is connected to. After this you can log in to this host from the iDRAC.",
            needsConfirmation = true,
        ),
        QuickCommand("Cluster", "pvecm status", "Cluster / quorum status."),
        QuickCommand("Disk usage", "df -h", "Filesystem usage."),
        QuickCommand("Memory", "free -h", "Memory and swap usage."),
        QuickCommand("Top", "top -b -n 1 | head -n 30", "Busiest processes."),
        QuickCommand("ZFS", "zpool status", "ZFS pool health."),
        QuickCommand("Network", "ip -br addr", "Network interfaces and addresses."),
        QuickCommand("Recent log", "journalctl -n 100 --no-pager", "Last 100 lines of the system journal."),
        QuickCommand("Failed units", "systemctl --failed --no-pager", "Services that failed to start."),
        QuickCommand(
            "Reboot host",
            "reboot",
            "Reboot the Proxmox host. All running guests are shut down first.",
            needsConfirmation = true,
        ),
        QuickCommand(
            "Shut down host",
            "shutdown -h now",
            "Power off the Proxmox host. You will need the iDRAC to turn it back on.",
            needsConfirmation = true,
        ),
    )
}

/** A key on the extra keyboard row and the bytes it sends. */
data class SpecialKey(val label: String, val sequence: String)

object SpecialKeys {
    private const val ESC = "\u001b"

    val terminal: List<SpecialKey> = listOf(
        SpecialKey("Enter", "\r"),
        SpecialKey("Ctrl+C", "\u0003"),
        SpecialKey("Ctrl+D", "\u0004"),
        SpecialKey("Ctrl+Z", "\u001a"),
        SpecialKey("Tab", "\t"),
        SpecialKey("Esc", ESC),
        SpecialKey("↑", "$ESC[A"),
        SpecialKey("↓", "$ESC[B"),
        SpecialKey("←", "$ESC[D"),
        SpecialKey("→", "$ESC[C"),
        SpecialKey("Bksp", "\u007f"),
        SpecialKey("Ctrl+L", "\u000c"),
    )

    /**
     * Keys for the PowerEdge BIOS over serial redirection, which uses Dell's
     * `<Esc><key>` sequences instead of VT220 function keys.
     */
    val bios: List<SpecialKey> = listOf(
        SpecialKey("F2 Setup", "${ESC}2"),
        SpecialKey("F10", "${ESC}0"),
        SpecialKey("F11 Boot", "$ESC!"),
        SpecialKey("F12 PXE", "$ESC@"),
        SpecialKey("Ctrl+E", "\u0005"),
        SpecialKey("Ctrl+R", "\u0012"),
        SpecialKey("Ctrl+S", "\u0013"),
        SpecialKey("Ctrl+Alt+Del", "${ESC}R${ESC}r${ESC}R"),
    )
}
