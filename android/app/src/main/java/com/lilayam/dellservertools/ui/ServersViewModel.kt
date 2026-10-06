package com.lilayam.dellservertools.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lilayam.dellservertools.core.JnlpParser
import com.lilayam.dellservertools.core.ServerProfile
import com.lilayam.dellservertools.core.ServerType
import com.lilayam.dellservertools.core.proxmox.GuestType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Home : Screen
    data class Edit(val profile: ServerProfile, val isNew: Boolean) : Screen
    data class Terminal(val profileId: String) : Screen
    data class Proxmox(val profileId: String) : Screen
    data class Node(val profileId: String, val node: String) : Screen
    data class Guest(val profileId: String, val node: String, val type: GuestType, val vmid: Int, val name: String) : Screen
    data class Tasks(val profileId: String) : Screen
    data class TaskLog(val profileId: String, val node: String, val upid: String, val title: String) : Screen
    data class Web(val profileId: String, val url: String, val title: String, val isMainUi: Boolean) : Screen
}

/** Something that needs a password the app doesn't have yet. */
data class PasswordRequest(val profileId: String, val profileName: String, val then: Screen)

data class ServersUiState(
    val profiles: List<ServerProfile> = emptyList(),
    val backStack: List<Screen> = listOf(Screen.Home),
    val passwordRequest: PasswordRequest? = null,
    val message: String? = null,
) {
    val screen: Screen get() = backStack.last()
}

/** Saved servers, secrets and screen navigation. */
class ServersViewModel(application: Application) : AndroidViewModel(application) {
    private val profileStore = ProfileStore(application)
    private val secrets = SecretStore(application)

    /** Passwords typed this session but not remembered. Never written to disk. */
    private val sessionPasswords = mutableMapOf<String, String>()

    private val _state = MutableStateFlow(ServersUiState(profiles = profileStore.load()))
    val state: StateFlow<ServersUiState> = _state.asStateFlow()

    fun profile(id: String): ServerProfile? = _state.value.profiles.firstOrNull { it.id == id }

    fun password(profile: ServerProfile): String? =
        sessionPasswords[profile.id] ?: if (profile.rememberPassword) secrets.load(profile.id, SecretKind.PASSWORD) else null

    fun apiTokenSecret(profile: ServerProfile): String? = secrets.load(profile.id, SecretKind.API_TOKEN)

    // ------------------------------------------------------------ navigation

    fun navigate(screen: Screen) = _state.update { it.copy(backStack = it.backStack + screen) }

    /** Returns false when already at the home screen. */
    fun back(): Boolean {
        if (_state.value.backStack.size <= 1) return false
        _state.update { it.copy(backStack = it.backStack.dropLast(1)) }
        return true
    }

    fun home() = _state.update { it.copy(backStack = listOf(Screen.Home)) }

    /** Opens the main screen for a server, asking for its password first if needed. */
    fun open(profile: ServerProfile) {
        val target = when (profile.type) {
            ServerType.IDRAC6 -> Screen.Terminal(profile.id)
            ServerType.PROXMOX -> Screen.Proxmox(profile.id)
        }
        val needsPassword = !(profile.type == ServerType.PROXMOX && profile.usesApiToken)
        openWithPassword(profile, target, needsPassword)
    }

    /** SSH shell on the Proxmox host. */
    fun openShell(profile: ServerProfile) = openWithPassword(profile, Screen.Terminal(profile.id), needsPassword = true)

    private fun openWithPassword(profile: ServerProfile, target: Screen, needsPassword: Boolean) {
        if (needsPassword && password(profile).isNullOrEmpty()) {
            _state.update { it.copy(passwordRequest = PasswordRequest(profile.id, profile.displayName, target)) }
        } else {
            navigate(target)
        }
    }

    fun submitPassword(password: String, remember: Boolean) {
        val request = _state.value.passwordRequest ?: return
        val profile = profile(request.profileId) ?: return
        sessionPasswords[profile.id] = password
        if (remember) {
            secrets.save(profile.id, SecretKind.PASSWORD, password)
            upsert(profile.copy(rememberPassword = true))
        }
        _state.update { it.copy(passwordRequest = null) }
        navigate(request.then)
    }

    fun cancelPasswordRequest() = _state.update { it.copy(passwordRequest = null) }

    /** Called when a login failed, so the next attempt asks again. */
    fun forgetSessionPassword(profileId: String) {
        sessionPasswords.remove(profileId)
    }

    // ------------------------------------------------------------ profiles

    fun newProfile(type: ServerType) = navigate(
        Screen.Edit(
            ServerProfile(type = type, name = "", host = "", username = "root"),
            isNew = true,
        ),
    )

    fun edit(profile: ServerProfile) = navigate(Screen.Edit(profile, isNew = false))

    fun save(profile: ServerProfile, password: String, apiTokenSecret: String) {
        if (password.isNotEmpty()) sessionPasswords[profile.id] = password
        secrets.save(profile.id, SecretKind.PASSWORD, if (profile.rememberPassword) password.ifEmpty { null } else null)
        if (profile.apiTokenId.isBlank()) {
            secrets.save(profile.id, SecretKind.API_TOKEN, null)
        } else if (apiTokenSecret.isNotEmpty()) {
            secrets.save(profile.id, SecretKind.API_TOKEN, apiTokenSecret)
        }
        upsert(profile)
        back()
    }

    fun delete(profile: ServerProfile) {
        secrets.deleteAll(profile.id)
        sessionPasswords.remove(profile.id)
        _state.update { s ->
            val profiles = s.profiles.filterNot { it.id == profile.id }
            profileStore.save(profiles)
            s.copy(profiles = profiles, backStack = listOf(Screen.Home))
        }
    }

    private fun upsert(profile: ServerProfile) = _state.update { s ->
        val existing = s.profiles.indexOfFirst { it.id == profile.id }
        val profiles = if (existing >= 0) {
            s.profiles.toMutableList().also { it[existing] = profile }
        } else {
            s.profiles + profile
        }
        profileStore.save(profiles)
        s.copy(profiles = profiles)
    }

    /** Reads a viewer.jnlp and opens a pre-filled iDRAC6 server form. */
    fun importJnlp(uri: Uri) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readNBytesCompat(MAX_JNLP_BYTES + 1)
                        require(bytes.size <= MAX_JNLP_BYTES) { "File is too large to be a viewer.jnlp" }
                        bytes.toString(Charsets.UTF_8)
                    } ?: error("Could not open the file")
                }
            }
            text.onSuccess(::importJnlpText).onFailure { e -> showMessage("Could not read that file: ${e.message}") }
        }
    }

    fun importJnlpText(text: String) {
        runCatching { JnlpParser.parse(text) }
            .onSuccess { info ->
                val existing = _state.value.profiles.firstOrNull { it.type == ServerType.IDRAC6 && it.host == info.host }
                val profile = existing ?: ServerProfile.fromJnlp(info)
                _state.update { it.copy(backStack = listOf(Screen.Home)) }
                navigate(Screen.Edit(profile, isNew = existing == null))
                showMessage(
                    if (existing != null) {
                        "This iDRAC is already saved."
                    } else {
                        "Loaded ${info.host}. Enter your iDRAC password and save."
                    },
                )
            }
            .onFailure { e -> showMessage(e.message ?: "Not a valid viewer.jnlp file") }
    }

    fun showMessage(message: String) = _state.update { it.copy(message = message) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < limit) {
            val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private companion object {
        const val MAX_JNLP_BYTES = 256 * 1024
    }
}
