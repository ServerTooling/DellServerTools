package com.lilayam.dellservertools.integration

import com.jcraft.jsch.JSchException
import com.lilayam.dellservertools.core.ConnectionSettings
import com.lilayam.dellservertools.core.HostKeyStore
import com.lilayam.dellservertools.core.IdracCommands
import com.lilayam.dellservertools.core.SshShellSession
import com.lilayam.dellservertools.core.TerminalEmulator
import com.lilayam.dellservertools.core.UnknownHostKeyException
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyPairGenerator
import kotlin.concurrent.thread
import org.apache.sshd.common.cipher.BuiltinCiphers
import org.apache.sshd.common.kex.BuiltinDHFactories
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.mac.BuiltinMacs
import org.apache.sshd.common.signature.BuiltinSignatures
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.ServerBuilder
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.shell.ShellFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Runs [SshShellSession] against an in-process SSH server.
 *
 * The "iDRAC6" server offers only what real iDRAC6 firmware does: SHA-1
 * Diffie-Hellman group 1, `ssh-rsa` and CBC ciphers.
 */
class SshIntegrationTest {
    private val servers = mutableListOf<SshServer>()

    @AfterEach
    fun tearDown() = servers.forEach { it.stop(true) }

    private class MemoryHostKeys : HostKeyStore {
        val keys = mutableMapOf<String, String>()
        override fun fingerprintFor(hostId: String) = keys[hostId]
        override fun save(hostId: String, fingerprint: String) {
            keys[hostId] = fingerprint
        }
    }

    private fun startServer(legacyOnly: Boolean): Int {
        val server = SshServer.setUpDefaultServer()
        server.host = "127.0.0.1"
        server.port = 0
        server.keyPairProvider = KeyPairProvider.wrap(
            KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair(),
        )
        if (legacyOnly) {
            server.keyExchangeFactories = listOf(ServerBuilder.DH2KEX.apply(BuiltinDHFactories.dhg1))
            server.cipherFactories = listOf(BuiltinCiphers.aes128cbc, BuiltinCiphers.tripledescbc)
            server.macFactories = listOf(BuiltinMacs.hmacsha1)
            server.signatureFactories = listOf(BuiltinSignatures.rsa)
        }
        server.passwordAuthenticator = PasswordAuthenticator { user, password, _ -> user == "root" && password == "calvin" }
        server.keyboardInteractiveAuthenticator = null
        server.shellFactory = ShellFactory { FakeIdracShell() }
        server.start()
        servers += server
        return server.port
    }

    /** Talks like the iDRAC6 SM-CLP shell, including `console com2`. */
    private class FakeIdracShell : Command {
        private lateinit var input: InputStream
        private lateinit var output: OutputStream
        private var exit: ExitCallback? = null

        override fun setInputStream(input: InputStream) {
            this.input = input
        }

        override fun setOutputStream(output: OutputStream) {
            this.output = output
        }

        override fun setErrorStream(err: OutputStream) = Unit

        override fun setExitCallback(callback: ExitCallback) {
            exit = callback
        }

        override fun start(channel: ChannelSession, env: Environment) {
            thread(isDaemon = true) { runCatching { loop() } }
        }

        override fun destroy(channel: ChannelSession) = Unit

        private fun write(text: String) {
            output.write(text.toByteArray())
            output.flush()
        }

        private fun loop() {
            write("\r\n/admin1-> ")
            val line = StringBuilder()
            var inConsole = false
            while (true) {
                val b = input.read()
                if (b < 0) break
                if (inConsole) {
                    if (b == 0x1c) {
                        inConsole = false
                        write("\r\n/admin1-> ")
                    }
                    continue
                }
                when (b.toChar()) {
                    '\r', '\n' -> {
                        val command = line.toString().trim()
                        line.clear()
                        if (command.isEmpty()) continue
                        when (command) {
                            "racadm serveraction powerstatus" -> write("\r\nServer power status: \u001b[1mON\u001b[0m\r\n/admin1-> ")
                            "console com2" -> {
                                inConsole = true
                                write("\r\nConnected to serial device.\r\n\u001b[2J\u001b[H\u001b[1;1HPowerEdge R710\u001b[3;1Hpve login: ")
                            }
                            "exit" -> {
                                exit?.onExit(0)
                                return
                            }
                            else -> write("\r\nCOMMAND PROCESSING FAILED\r\n/admin1-> ")
                        }
                    }
                    else -> line.append(b.toChar())
                }
            }
        }
    }

    private fun readUntil(input: InputStream, term: TerminalEmulator, needle: String, timeoutMs: Long = 10_000): String {
        val buffer = ByteArray(4096)
        val deadline = System.currentTimeMillis() + timeoutMs
        var screen = ""
        while (System.currentTimeMillis() < deadline) {
            if (input.available() > 0) {
                val n = input.read(buffer)
                term.feed(buffer, 0, n)
                screen = term.snapshot().lines.joinToString("\n")
                if (screen.contains(needle)) return screen
            } else {
                Thread.sleep(20)
            }
        }
        throw AssertionError("Timed out waiting for \"$needle\". Screen:\n$screen")
    }

    private fun settings(port: Int, password: String = "calvin") = ConnectionSettings("127.0.0.1", port, "root", password)

    @Test
    fun `modern-only algorithms cannot talk to an iDRAC6`() {
        val port = startServer(legacyOnly = true)
        val e = assertThrows<JSchException> { SshShellSession(settings(port), MemoryHostKeys(), legacyAlgorithms = false).connect(80, 25) }
        assertTrue(e.message!!.contains("Algorithm negotiation fail"), e.message)
    }

    @Test
    fun `iDRAC6 session - trust on first use, racadm, serial console and back`() {
        val port = startServer(legacyOnly = true)
        val keys = MemoryHostKeys()

        val unknown = assertThrows<UnknownHostKeyException> {
            SshShellSession(settings(port), keys, legacyAlgorithms = true).connect(80, 25)
        }
        assertFalse(unknown.changed)
        assertTrue(unknown.fingerprint.startsWith("SHA256:"))
        assertTrue(keys.keys.isEmpty(), "a key must not be stored without approval")

        val session = SshShellSession(settings(port), keys, approvedFingerprint = unknown.fingerprint, legacyAlgorithms = true)
        val input = session.connect(80, 25)
        assertEquals(unknown.fingerprint, keys.fingerprintFor(session.hostId))
        val term = TerminalEmulator(80, 25)
        readUntil(input, term, "/admin1->")

        session.send("racadm serveraction powerstatus\r")
        readUntil(input, term, "Server power status: ON")

        session.send(IdracCommands.serialConsole.command + "\r")
        val console = readUntil(input, term, "pve login:")
        assertTrue(console.contains("PowerEdge R710"))

        val promptsBefore = term.snapshot().lines.count { it.contains("/admin1->") }
        session.send(IdracCommands.EXIT_SERIAL_CONSOLE)
        val deadline = System.currentTimeMillis() + 10_000
        while (term.snapshot().lines.count { it.contains("/admin1->") } <= promptsBefore) {
            check(System.currentTimeMillis() < deadline) { "Ctrl+\\ did not return to the iDRAC prompt" }
            readUntil(input, term, "/admin1->", 1_000)
        }
        assertTrue(session.isConnected)
        session.disconnect()

        // Second time: key already trusted, no approval needed.
        SshShellSession(settings(port), keys, legacyAlgorithms = true).apply {
            connect(80, 25)
            disconnect()
        }
    }

    @Test
    fun `wrong password and changed host key are rejected`() {
        val port = startServer(legacyOnly = true)
        val keys = MemoryHostKeys()
        val fingerprint = assertThrows<UnknownHostKeyException> {
            SshShellSession(settings(port), keys, legacyAlgorithms = true).connect(80, 25)
        }.fingerprint

        val auth = assertThrows<JSchException> {
            SshShellSession(settings(port, password = "wrong"), keys, approvedFingerprint = fingerprint, legacyAlgorithms = true)
                .connect(80, 25)
        }
        assertTrue(auth.message!!.contains("Auth fail"), auth.message)

        keys.save("ssh:127.0.0.1:$port", "SHA256:not-the-real-key")
        val changed = assertThrows<UnknownHostKeyException> {
            SshShellSession(settings(port), keys, legacyAlgorithms = true).connect(80, 25)
        }
        assertTrue(changed.changed)
        assertEquals(fingerprint, changed.fingerprint)
    }

    @Test
    fun `modern server works without legacy algorithms (Proxmox host)`() {
        val port = startServer(legacyOnly = false)
        val keys = MemoryHostKeys()
        val fingerprint = assertThrows<UnknownHostKeyException> {
            SshShellSession(settings(port), keys).connect(80, 25)
        }.fingerprint
        val session = SshShellSession(settings(port), keys, approvedFingerprint = fingerprint)
        val input = session.connect(80, 25)
        readUntil(input, TerminalEmulator(80, 25), "/admin1->")
        session.disconnect()
    }
}
