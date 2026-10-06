package com.lilayam.dellservertools.core

import org.junit.jupiter.api.Assertions.assertEquals
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
