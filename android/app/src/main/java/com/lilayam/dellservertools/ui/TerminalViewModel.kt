package com.lilayam.dellservertools.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jcraft.jsch.JSchException
import com.lilayam.dellservertools.core.ConnectionSettings
import com.lilayam.dellservertools.core.ServerProfile
import com.lilayam.dellservertools.core.ServerType
import com.lilayam.dellservertools.core.SshShellSession
import com.lilayam.dellservertools.core.TerminalEmulator
import com.lilayam.dellservertools.core.UnknownHostKeyException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED }

data class FingerprintPrompt(val fingerprint: String, val changed: Boolean)

data class TerminalUiState(
    val profileId: String? = null,
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val error: String? = null,
    /** Login was rejected; the stored/typed password is probably wrong. */
    val authFailed: Boolean = false,
    val hostKeyPrompt: FingerprintPrompt? = null,
    val lines: List<String> = emptyList(),
    val message: String? = null,
)

/** One interactive SSH shell (iDRAC6 or Proxmox host) and its screen. */
class TerminalViewModel(application: Application) : AndroidViewModel(application) {
    private val trust = TrustStore(application)
    private var terminal = TerminalEmulator(columns = COLUMNS, rows = ROWS)

    private val _state = MutableStateFlow(TerminalUiState())
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()

    private var session: SshShellSession? = null
    private var lastTarget: Pair<ServerProfile, String>? = null
    private var readerJob: Job? = null
    private var refreshJob: Job? = null

    @Volatile
    private var dirty = false

    /** Connects unless already connected/connecting to this same server. */
    fun ensureConnected(profile: ServerProfile, password: String) {
        val s = _state.value
        if (s.profileId == profile.id && s.status != ConnectionStatus.DISCONNECTED) return
        if (s.profileId == profile.id && s.hostKeyPrompt != null) return
        if (s.profileId == profile.id && s.authFailed) return
        connect(profile, password)
    }

    fun connect(profile: ServerProfile, password: String, approvedFingerprint: String? = null) {
        if (_state.value.profileId != profile.id) {
            closeSession()
            terminal = TerminalEmulator(columns = COLUMNS, rows = ROWS)
            _state.value = TerminalUiState(profileId = profile.id)
        } else if (_state.value.status != ConnectionStatus.DISCONNECTED) {
            return
        }
        lastTarget = profile to password
        val settings = when (profile.type) {
            ServerType.IDRAC6 -> ConnectionSettings(profile.host, profile.port, profile.username, password)
            ServerType.PROXMOX -> ConnectionSettings(profile.host, profile.sshPort, profile.sshUsername, password)
        }
        _state.update {
            it.copy(status = ConnectionStatus.CONNECTING, error = null, authFailed = false, hostKeyPrompt = null)
        }

        val newSession = SshShellSession(
            settings = settings,
            hostKeys = trust,
            approvedFingerprint = approvedFingerprint,
            legacyAlgorithms = profile.type == ServerType.IDRAC6,
        )
        session = newSession
        val term = terminal
        readerJob = viewModelScope.launch(Dispatchers.IO) {
            val input = try {
                newSession.connect(COLUMNS, ROWS)
            } catch (e: UnknownHostKeyException) {
                newSession.disconnect()
                _state.update {
                    it.copy(
                        status = ConnectionStatus.DISCONNECTED,
                        hostKeyPrompt = FingerprintPrompt(e.fingerprint, e.changed),
                    )
                }
                return@launch
            } catch (e: Exception) {
                newSession.disconnect()
                val authFailed = e is JSchException && e.message?.contains("Auth", ignoreCase = true) == true
                _state.update {
                    it.copy(status = ConnectionStatus.DISCONNECTED, error = describeError(e, profile), authFailed = authFailed)
                }
                return@launch
            }

            term.feed("Connected to ${settings.username}@${settings.host}\r\n")
            dirty = true
            _state.update { it.copy(status = ConnectionStatus.CONNECTED) }
            startRefreshing()

            val buffer = ByteArray(8192)
            try {
                while (isActive) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n > 0) {
                        term.feed(buffer, 0, n)
                        dirty = true
                    }
                }
            } catch (_: Exception) {
                // Connection dropped; reported below.
            }
            if (session === newSession) {
                term.feed("\r\n[Disconnected]\r\n")
                dirty = true
                newSession.disconnect()
                session = null
                _state.update { it.copy(status = ConnectionStatus.DISCONNECTED) }
            }
        }
    }

    fun reconnect() {
        val (profile, password) = lastTarget ?: return
        connect(profile, password)
    }

    fun acceptHostKey() {
        val prompt = _state.value.hostKeyPrompt ?: return
        val (profile, password) = lastTarget ?: return
        _state.update { it.copy(hostKeyPrompt = null) }
        connect(profile, password, approvedFingerprint = prompt.fingerprint)
    }

    fun rejectHostKey() = _state.update { it.copy(hostKeyPrompt = null, error = "Host key not trusted.") }

    fun disconnect() {
        if (session == null) return
        closeSession()
        terminal.feed("\r\n[Disconnected]\r\n")
        dirty = true
        _state.update { it.copy(status = ConnectionStatus.DISCONNECTED) }
    }

    private fun closeSession() {
        val current = session ?: return
        session = null
        readerJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) { current.disconnect() }
    }

    /** Types a line followed by Enter. */
    fun sendLine(text: String) = sendRaw(text + "\r")

    fun sendRaw(text: String) {
        val current = session ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { current.send(text) }.onFailure { e ->
                _state.update { it.copy(message = "Send failed: ${e.message}") }
            }
        }
    }

    fun clear() {
        terminal.clear()
        publish()
    }

    fun copyableOutput(): String = terminal.snapshot().lines.joinToString("\n")

    fun clearMessage() = _state.update { it.copy(message = null) }

    private fun startRefreshing() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            while (isActive) {
                if (dirty) publish()
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    private fun publish() {
        dirty = false
        val lines = terminal.snapshot().lines
        _state.update { it.copy(lines = lines) }
    }

    private fun describeError(e: Throwable, profile: ServerProfile): String {
        val causes = generateSequence(e) { it.cause }.toList()
        val what = if (profile.type == ServerType.IDRAC6) "the iDRAC" else "the Proxmox host"
        return when {
            causes.any { it is UnknownHostException } -> "Unknown host. Check the address."
            causes.any { it is SocketTimeoutException } ->
                "Timed out. Is the phone on the same network as $what, and is SSH enabled?"
            causes.any { it is NoRouteToHostException } -> "No route to $what. Check Wi-Fi / VPN."
            causes.any { it is ConnectException } -> if (profile.type == ServerType.IDRAC6) {
                "Connection refused. Enable SSH on the iDRAC (Remote Access > Network/Security > Services)."
            } else {
                "Connection refused on SSH port ${profile.sshPort}."
            }
            e is JSchException && e.message?.contains("Auth", ignoreCase = true) == true -> if (profile.type == ServerType.IDRAC6) {
                "Login failed. Check the iDRAC username and password (not the numbers from the .jnlp file)."
            } else {
                "Login failed. Check the username and password (root SSH login must be allowed)."
            }
            e is JSchException && e.message?.contains("Algorithm negotiation fail", ignoreCase = true) == true ->
                "SSH algorithm negotiation failed: ${e.message}"
            else -> e.message ?: e.javaClass.simpleName
        }
    }

    override fun onCleared() {
        session?.disconnect()
        session = null
        super.onCleared()
    }

    companion object {
        const val COLUMNS = 80
        const val ROWS = 25
        private const val REFRESH_INTERVAL_MS = 60L
    }
}
