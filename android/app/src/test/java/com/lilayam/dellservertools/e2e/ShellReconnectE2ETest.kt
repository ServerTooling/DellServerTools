package com.lilayam.dellservertools.e2e

import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lilayam.dellservertools.core.proxmox.PinnedTls
import java.io.OutputStream
import java.security.KeyPairGenerator
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.shell.ShellFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper

/**
 * The host shell reconnects by itself when its connection was cut while the app was in the background, and only
 * then. JVM only: the SSH server library doesn't run on a device.
 */
@RunWith(AndroidJUnit4::class)
class ShellReconnectE2ETest : ProxmoxE2ETestBase() {
    private lateinit var ssh: SshServer
    private val shells = AtomicInteger()

    @Before
    fun startSsh() {
        ssh = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = KeyPairProvider.wrap(
                KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair(),
            )
            passwordAuthenticator = PasswordAuthenticator { _, password, _ -> password == "s3cret" }
            keyboardInteractiveAuthenticator = null
            shellFactory = ShellFactory { PromptShell() }
            start()
        }
    }

    @After
    fun stopSsh() = ssh.stop(true)

    @Test
    fun reconnectsAfterTheConnectionWasCutInTheBackground() {
        openShell()

        // As Android does to a backgrounded app's sockets.
        leaveAndReturn { dropConnections() }

        waitForTerminal { shells.get() == 2 }
        waitForTerminal { compose.onAllNodesWithText("Connected").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aConnectionLostWhileInUseStaysClosed() {
        openShell()

        dropConnections()

        waitForTerminal { compose.onAllNodesWithText("Disconnected").fetchSemanticsNodes().isNotEmpty() }
        repeat(10) { ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS) }
        assertEquals(1, shells.get())
    }

    private fun openShell() {
        launch()
        tap("add-proxmox")
        type("field-name", "Lab PVE")
        type("field-host", "127.0.0.1")
        type("field-port", pve.port.toString(), replace = true)
        type("field-password", "s3cret")
        tap("field-remember")
        type("field-ssh-port", ssh.port.toString(), replace = true)
        tap("save")
        waitForText("Lab PVE")
        compose.onNodeWithText("Lab PVE").performClick()
        waitForText("Trust this server?")
        compose.onNodeWithText(PinnedTls.fingerprint(pve.certificate.certificate)).assertExists()
        compose.onNodeWithText("Trust and connect").performClick()
        waitForText("Proxmox VE 8.2.4-8.2")

        compose.onNodeWithContentDescription("SSH shell").performClick()
        waitForText("Trust and connect") // the host's SSH key, first use
        compose.onNodeWithText("Trust and connect").performClick()
        waitForTerminal { compose.onAllNodesWithText("root@pve:~#", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, shells.get())
    }

    private fun dropConnections() = ssh.activeSessions.forEach { it.close(true) }

    /** The terminal redraws on a main-thread timer, which Robolectric's paused clock only moves when told to. */
    private fun waitForTerminal(condition: () -> Boolean) = waitUntil {
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        condition()
    }

    private inner class PromptShell : Command {
        private lateinit var output: OutputStream

        override fun setInputStream(input: java.io.InputStream) = Unit

        override fun setOutputStream(output: OutputStream) {
            this.output = output
        }

        override fun setErrorStream(err: OutputStream) = Unit
        override fun setExitCallback(callback: ExitCallback) = Unit
        override fun destroy(channel: ChannelSession) = Unit

        override fun start(channel: ChannelSession, env: Environment) {
            shells.incrementAndGet()
            output.write("root@pve:~# ".toByteArray())
            output.flush()
        }
    }
}
