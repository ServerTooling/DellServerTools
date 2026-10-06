package com.lilayam.dellservertools.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommandsAndSshTest {

    @Test
    fun `power changing commands ask for confirmation`() {
        val dangerous = listOf("powerup", "powerdown", "powercycle", "hardreset", "graceshutdown", "racreset")
        IdracCommands.quickCommands
            .filter { cmd -> dangerous.any { cmd.command.endsWith(it) } }
            .also { assertEquals(dangerous.size, it.size) }
            .forEach { assertTrue(it.needsConfirmation, it.label) }
        ProxmoxShellCommands.quickCommands
            .filter { it.command.startsWith("reboot") || it.command.startsWith("shutdown") }
            .forEach { assertTrue(it.needsConfirmation, it.label) }
    }

    @Test
    fun `detects the iDRAC prompt versus an attached serial console`() {
        assertTrue(IdracCommands.isAtIdracPrompt(listOf("Connected to root@192.0.2.15", "/admin1-> ", "")))
        assertTrue(IdracCommands.isAtIdracPrompt(listOf("/admin1/system1->")))
        assertFalse(IdracCommands.isAtIdracPrompt(listOf("/admin1-> console com2", "Connected to Serial Device 2. To end type: ^\\")))
        assertFalse(IdracCommands.isAtIdracPrompt(listOf("/admin1-> racadm getsysinfo", "Power Status = ON")))
        assertFalse(IdracCommands.isAtIdracPrompt(emptyList()))
    }

    @Test
    fun `screen keys use Dell's serial redirection sequences`() {
        val keys = SpecialKeys.screen.associate { it.label to it.sequence }
        assertEquals("\u001b1", keys["F1"])
        assertEquals("\u001b2", keys["F2"])
        assertEquals("\u001b@", keys["F12"])
        assertEquals("\r", keys["Enter"])
    }

    @Test
    fun `serial console escape is ctrl backslash`() {
        assertEquals("\u001c", IdracCommands.EXIT_SERIAL_CONSOLE)
    }

    @Test
    fun `legacy algorithms are appended after the defaults without duplicates`() {
        assertEquals(
            "curve25519-sha256,diffie-hellman-group14-sha1,diffie-hellman-group1-sha1",
            SshShellSession.appendAlgorithms(
                "curve25519-sha256,diffie-hellman-group14-sha1",
                listOf("diffie-hellman-group14-sha1", "diffie-hellman-group1-sha1"),
            ),
        )
        assertEquals("ssh-rsa", SshShellSession.appendAlgorithms(null, listOf("ssh-rsa")))
    }

    @Test
    fun `ssh fingerprints use the OpenSSH SHA256 format`() {
        // sha256("abc") = ba7816bf..., base64 without padding.
        assertEquals("SHA256:ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0", SshShellSession.fingerprint("abc".toByteArray()))
    }
}
