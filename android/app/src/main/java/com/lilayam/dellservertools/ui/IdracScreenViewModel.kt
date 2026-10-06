package com.lilayam.dellservertools.ui

import android.app.Application
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lilayam.dellservertools.core.ServerProfile
import com.lilayam.dellservertools.core.idrac.IdracLoginException
import com.lilayam.dellservertools.core.idrac.IdracWebClient
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class IdracScreenUiState(
    val profileId: String? = null,
    val image: ImageBitmap? = null,
    val updatedAt: Long? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val authFailed: Boolean = false,
    val certPrompt: FingerprintPrompt? = null,
)

/** The iDRAC6 "Virtual Console Preview": a still image of the server's screen. */
class IdracScreenViewModel(application: Application) : AndroidViewModel(application) {
    private val trust = TrustStore(application)
    private val lock = Mutex()

    private val _state = MutableStateFlow(IdracScreenUiState())
    val state: StateFlow<IdracScreenUiState> = _state.asStateFlow()

    private var client: IdracWebClient? = null
    private var profile: ServerProfile? = null
    private var password: String? = null

    fun open(profile: ServerProfile, password: String) {
        if (this.profile == profile && this.password == password && client != null) return
        close()
        this.profile = profile
        this.password = password
        client = newClient(profile, password)
        _state.value = IdracScreenUiState(profileId = profile.id)
        refresh()
    }

    private fun newClient(profile: ServerProfile, password: String) =
        IdracWebClient(profile.host, profile.webPort, profile.username, password, trust.fingerprintFor(tlsId(profile)))

    fun refresh() {
        val c = client ?: return
        val s = _state.value
        if (s.loading || s.certPrompt != null || s.authFailed) return
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                lock.withLock {
                    runCatching {
                        val bytes = c.captureScreen()
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                            ?: error("The iDRAC sent an image the phone couldn't decode")
                    }
                }
            }
            if (c !== client) return@launch
            result.onSuccess { image ->
                _state.update { it.copy(image = image, updatedAt = System.currentTimeMillis(), loading = false, error = null) }
            }.onFailure { e ->
                val cert = c.rejectedCertificate
                _state.update {
                    when {
                        cert != null && generateSequence(e) { x -> x.cause }.any { x -> x is SSLException } ->
                            it.copy(loading = false, certPrompt = FingerprintPrompt(cert.fingerprint, cert.changed))
                        e is IdracLoginException -> it.copy(loading = false, authFailed = true, error = e.message)
                        else -> it.copy(loading = false, error = describe(e))
                    }
                }
            }
        }
    }

    fun acceptCertificate() {
        val prompt = _state.value.certPrompt ?: return
        val p = profile ?: return
        val pw = password ?: return
        trust.save(tlsId(p), prompt.fingerprint)
        client = newClient(p, pw)
        _state.update { it.copy(certPrompt = null, error = null) }
        refresh()
    }

    fun rejectCertificate() = _state.update { it.copy(certPrompt = null, error = "Certificate not trusted.") }

    /** Retry after a login error, e.g. once the user closed other iDRAC web sessions. */
    fun retry() {
        val p = profile ?: return
        val pw = password ?: return
        close()
        profile = p
        password = pw
        client = newClient(p, pw)
        _state.update { it.copy(authFailed = false, error = null) }
        refresh()
    }

    /** Ends the iDRAC web session; the iDRAC6 only has a few of them. */
    fun close() {
        val c = client ?: return
        client = null
        profile = null
        password = null
        viewModelScope.launch(Dispatchers.IO) { lock.withLock { c.logout() } }
    }

    private fun describe(e: Throwable): String {
        val causes = generateSequence(e) { it.cause }.toList()
        return when {
            causes.any { it is UnknownHostException } -> "Unknown host. Check the iDRAC address."
            causes.any { it is SocketTimeoutException } -> "Timed out. Is the phone on the same network as the iDRAC?"
            causes.any { it is ConnectException } ->
                "Connection refused on port ${profile?.webPort ?: 443} (the iDRAC web interface)."
            else -> e.message ?: e.javaClass.simpleName
        }
    }

    override fun onCleared() {
        client?.let { c -> Thread { runCatching { c.logout() } }.start() }
        client = null
        super.onCleared()
    }

    private companion object {
        fun tlsId(profile: ServerProfile) = TrustStore.tlsId(profile.host, profile.webPort)
    }
}
