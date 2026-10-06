package com.lilayam.dellservertools.core

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64

data class ConnectionSettings(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
)

/** Thrown when the iDRAC presents an SSH host key the user hasn't approved yet. */
class UnknownHostKeyException(
    val fingerprint: String,
    val changed: Boolean,
) : Exception(
    if (changed) "The server's SSH host key has CHANGED" else "New SSH host key",
)

/** Remembers approved SSH host key / TLS certificate fingerprints (trust on first use). */
interface HostKeyStore {
    fun fingerprintFor(hostId: String): String?
    fun save(hostId: String, fingerprint: String)
}

/**
 * Interactive SSH shell (iDRAC6 or a Linux host such as Proxmox VE).
 *
 * The iDRAC6 SSH server is old: it only speaks SHA-1 key exchange, `ssh-rsa` /
 * `ssh-dss` host keys and CBC ciphers, all of which modern clients disable. With
 * [legacyAlgorithms] they are re-enabled, for this connection only, after the
 * modern defaults.
 */
class SshShellSession(
    private val settings: ConnectionSettings,
    private val hostKeys: HostKeyStore,
    /** Fingerprint the user just approved in the UI for this host, if any. */
    private val approvedFingerprint: String? = null,
    private val legacyAlgorithms: Boolean = false,
) {
    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var output: OutputStream? = null

    val hostId: String get() = "ssh:${settings.host}:${settings.port}"

    /** Connects and opens the shell. Returns the stream of shell output. */
    fun connect(columns: Int, rows: Int): InputStream {
        val jsch = JSch()
        val repository = TofuHostKeyRepository(hostId, hostKeys, approvedFingerprint)
        jsch.setHostKeyRepository(repository)

        val s = jsch.getSession(settings.username, settings.host, settings.port)
        s.setPassword(settings.password.toByteArray(Charsets.UTF_8))
        s.userInfo = PasswordUserInfo(settings.password)
        s.setConfig("StrictHostKeyChecking", "yes")
        s.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        if (legacyAlgorithms) {
            s.setConfig("kex", appendAlgorithms(JSch.getConfig("kex"), LEGACY_KEX))
            s.setConfig("server_host_key", appendAlgorithms(JSch.getConfig("server_host_key"), LEGACY_HOST_KEYS))
            s.setConfig("cipher.s2c", appendAlgorithms(JSch.getConfig("cipher.s2c"), LEGACY_CIPHERS))
            s.setConfig("cipher.c2s", appendAlgorithms(JSch.getConfig("cipher.c2s"), LEGACY_CIPHERS))
            s.setConfig("mac.s2c", appendAlgorithms(JSch.getConfig("mac.s2c"), LEGACY_MACS))
            s.setConfig("mac.c2s", appendAlgorithms(JSch.getConfig("mac.c2s"), LEGACY_MACS))
            s.setConfig("dhgex_min", "1024")
        }
        s.serverAliveInterval = 30_000
        s.serverAliveCountMax = 4
        session = s

        try {
            s.connect(CONNECT_TIMEOUT_MS)
        } catch (e: JSchException) {
            repository.rejected?.let { throw it }
            throw e
        }

        val ch = s.openChannel("shell") as ChannelShell
        ch.setPtyType("vt100", columns, rows, 0, 0)
        val input = ch.inputStream
        output = ch.outputStream
        ch.connect(CONNECT_TIMEOUT_MS)
        channel = ch
        return input
    }

    val isConnected: Boolean
        get() = channel?.isConnected == true && session?.isConnected == true

    @Synchronized
    fun send(text: String) {
        val out = output ?: throw IllegalStateException("Not connected")
        out.write(text.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    fun disconnect() {
        runCatching { channel?.disconnect() }
        runCatching { session?.disconnect() }
        channel = null
        session = null
        output = null
    }

    private class PasswordUserInfo(private val password: String) : UserInfo, UIKeyboardInteractive {
        override fun getPassphrase(): String? = null
        override fun getPassword(): String = password
        override fun promptPassword(message: String?) = true
        override fun promptPassphrase(message: String?) = false
        override fun promptYesNo(message: String?) = false
        override fun showMessage(message: String?) = Unit
        override fun promptKeyboardInteractive(
            destination: String?,
            name: String?,
            instruction: String?,
            prompt: Array<out String>?,
            echo: BooleanArray?,
        ): Array<String> = Array(prompt?.size ?: 0) { password }
    }

    private class TofuHostKeyRepository(
        private val hostId: String,
        private val store: HostKeyStore,
        private val approvedFingerprint: String?,
    ) : HostKeyRepository {
        var rejected: UnknownHostKeyException? = null
            private set

        override fun check(host: String?, key: ByteArray): Int {
            val fingerprint = fingerprint(key)
            val known = store.fingerprintFor(hostId)
            return when {
                known == fingerprint -> HostKeyRepository.OK
                approvedFingerprint == fingerprint -> {
                    store.save(hostId, fingerprint)
                    HostKeyRepository.OK
                }
                else -> {
                    rejected = UnknownHostKeyException(fingerprint, changed = known != null)
                    if (known == null) HostKeyRepository.NOT_INCLUDED else HostKeyRepository.CHANGED
                }
            }
        }

        override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID(): String = "dell-server-tools"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 20_000

        private val LEGACY_KEX = listOf(
            "diffie-hellman-group14-sha1",
            "diffie-hellman-group-exchange-sha1",
            "diffie-hellman-group1-sha1",
        )
        private val LEGACY_HOST_KEYS = listOf("ssh-rsa", "ssh-dss")
        private val LEGACY_CIPHERS = listOf("aes128-cbc", "aes256-cbc", "3des-cbc")
        private val LEGACY_MACS = listOf("hmac-sha1", "hmac-md5")

        internal fun appendAlgorithms(current: String?, extra: List<String>): String {
            val list = current.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            extra.forEach { if (it !in list) list.add(it) }
            return list.joinToString(",")
        }

        /** OpenSSH-style `SHA256:...` fingerprint of a raw public key blob. */
        fun fingerprint(key: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(key)
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
        }
    }
}
