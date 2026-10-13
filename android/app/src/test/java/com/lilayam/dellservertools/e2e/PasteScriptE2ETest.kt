package com.lilayam.dellservertools.e2e

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lilayam.dellservertools.core.proxmox.PinnedTls
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyPairGenerator
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.shell.ShellFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * "Paste script" and "Select text" in the Proxmox host shell, against an in-process SSH server that plays
 * the host. JVM only: the SSH server library doesn't run on a device.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PasteScriptE2ETest : ProxmoxE2ETestBase() {
    private lateinit var ssh: SshServer

    /** Exec commands the host received, with their stdin. */
    private val execs = CopyOnWriteArrayList<Pair<String, ByteArray>>()

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
            shellFactory = ShellFactory { FakeHostShell() }
            commandFactory = CommandFactory { _, command -> RecordingExec(command) }
            start()
        }
    }

    @After
    fun stopSsh() = ssh.stop(true)

    @Test
    fun pastedScriptIsSavedAndRunAndItsOutputCanBeSelectedAndCopied() {
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
        waitForText("Connected")
        waitForText("root@pve:~#", substring = true)

        tap("paste-script")
        type("script-path", "/root/spoved-setup.sh", replace = true)
        // A phone paste may carry CRLF; tabs must survive.
        type("script-body", "#!/bin/bash\r\n\techo ready\r\n")
        type("script-args", "300")
        screenshot("1-paste-script-dialog")
        compose.onNodeWithTag("script-run").performClick() // dialog buttons are not in a scrollable

        waitUntil { execs.isNotEmpty() }
        val (command, stdin) = execs.single()
        assertEquals("cat > '/root/spoved-setup.sh' && chmod 700 '/root/spoved-setup.sh'", command)
        assertEquals("#!/bin/bash\n\techo ready\n", String(stdin))
        waitForText("ran /root/spoved-setup.sh with 300", substring = true)
        screenshot("2-terminal-after-run")

        compose.onNodeWithTag("terminal-output").performTouchInput { longClick() }
        waitForText("Select text")
        compose.onNodeWithTag("select-text").assertTextContains("ran /root/spoved-setup.sh with 300", substring = true)
        screenshot("3-select-text")

        compose.onNodeWithTag("select-copy-all").performClick()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val copied = clipboard.primaryClip!!.getItemAt(0).text.toString()
        assertTrue(copied, copied.contains("bash '/root/spoved-setup.sh' 300\nran /root/spoved-setup.sh with 300"))
    }

    /** Saves a PNG for the pull request, when the graphics backend allows it; never fails the test. */
    private fun screenshot(name: String) {
        val dir = File("build/screenshots").apply { mkdirs() }
        runCatching {
            compose.waitForIdle()
            val bitmap = compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap()
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { File(dir, "$name.skipped.txt").writeText(it.stackTraceToString()) }
    }

    /** A Proxmox host's bash, as far as this test needs: echo, a prompt, the run and list lines. */
    private class FakeHostShell : Command {
        private lateinit var input: InputStream
        private lateinit var output: OutputStream

        override fun setInputStream(input: InputStream) {
            this.input = input
        }

        override fun setOutputStream(output: OutputStream) {
            this.output = output
        }

        override fun setErrorStream(err: OutputStream) = Unit
        override fun setExitCallback(callback: ExitCallback) = Unit
        override fun destroy(channel: ChannelSession) = Unit

        override fun start(channel: ChannelSession, env: Environment) {
            thread(isDaemon = true) { runCatching { loop() } }
        }

        private fun write(text: String) {
            output.write(text.toByteArray())
            output.flush()
        }

        private fun loop() {
            write("root@pve:~# ")
            val line = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return
                if (b != '\r'.code) {
                    line.append(b.toChar())
                    write(b.toChar().toString())
                    continue
                }
                val run = Regex("""bash '([^']+)' (.*)""").matchEntire(line.toString())
                line.clear()
                write("\r\n")
                if (run != null) write("ran ${run.groupValues[1]} with ${run.groupValues[2]}\r\n")
                write("root@pve:~# ")
            }
        }
    }

    private inner class RecordingExec(private val command: String) : Command {
        private lateinit var input: InputStream
        private var exit: ExitCallback? = null

        override fun setInputStream(input: InputStream) {
            this.input = input
        }

        override fun setOutputStream(output: OutputStream) = Unit
        override fun setErrorStream(err: OutputStream) = Unit

        override fun setExitCallback(callback: ExitCallback) {
            exit = callback
        }

        override fun destroy(channel: ChannelSession) = Unit

        override fun start(channel: ChannelSession, env: Environment) {
            thread(isDaemon = true) {
                execs += command to input.readBytes()
                exit?.onExit(0)
            }
        }
    }
}
