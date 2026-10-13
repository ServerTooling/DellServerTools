package com.lilayam.dellservertools.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lilayam.dellservertools.core.proxmox.RunnerAction
import com.lilayam.dellservertools.core.proxmox.RunnersApiException
import com.lilayam.dellservertools.core.proxmox.RunnersClient
import com.lilayam.dellservertools.core.proxmox.RunnersSettings
import com.lilayam.dellservertools.core.proxmox.RunnersStatus
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RunnersUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val status: RunnersStatus? = null,
    val statusError: String? = null,
    val settings: RunnersSettings? = null,
    val actions: List<RunnerAction> = emptyList(),
    /** Settings changed in the form but not saved, by field name. */
    val edits: Map<String, String> = emptyMap(),
    val newPool: String = "",
    val saving: Boolean = false,
    /** Why the last save was refused (the service's validation message). */
    val saveError: String? = null,
    val message: String? = null,
) {
    val anyRunning: Boolean get() = actions.any { it.last?.running == true }
    val dirty: Boolean get() = edits.isNotEmpty() || newPool.isNotBlank()
}

/** The pve-runners service on a Proxmox host: status, settings and actions, as its page had them. */
class RunnersViewModel : ViewModel() {
    private val _state = MutableStateFlow(RunnersUiState())
    val state: StateFlow<RunnersUiState> = _state.asStateFlow()

    private var client: RunnersClient? = null

    fun open(client: RunnersClient) {
        if (this.client?.baseUrl == client.baseUrl && _state.value.settings != null) {
            this.client = client
            return
        }
        this.client = client
        _state.value = RunnersUiState(loading = true)
        reload()
    }

    /** Loads everything; unsaved edits are kept unless [discardEdits]. */
    fun reload(discardEdits: Boolean = false) {
        val c = client ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val settings = c.settings()
                    val actions = c.actions()
                    val status = runCatching { c.status() }
                    Triple(settings, actions, status)
                }
            }
            if (c !== client) return@launch
            result.onSuccess { (settings, actions, status) ->
                _state.update {
                    it.copy(loading = false, error = null, settings = settings, actions = actions,
                        status = status.getOrNull() ?: it.status, statusError = status.exceptionOrNull()?.let(::describe),
                        edits = if (discardEdits) emptyMap() else it.edits, newPool = if (discardEdits) "" else it.newPool)
                }
            }.onFailure { e -> _state.update { it.copy(loading = false, error = describe(e)) } }
        }
    }

    /** Refreshes the status and the running actions' output, for the auto-refresh. */
    fun poll() {
        val c = client ?: return
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) { runCatching { c.status() } }
            val actions = withContext(Dispatchers.IO) { runCatching { c.actions() } }
            if (c !== client) return@launch
            _state.update {
                it.copy(status = status.getOrNull() ?: it.status, statusError = status.exceptionOrNull()?.let(::describe),
                    actions = actions.getOrNull() ?: it.actions)
            }
        }
    }

    fun edit(name: String, value: String) = _state.update { s ->
        val shown = s.settings?.allFields?.firstOrNull { it.name == name }
        val edits = if (name.endsWith("_clear") && value == "false") s.edits - name else if (shown != null && shown.kind != "secret" && shown.value == value) s.edits - name else s.edits + (name to value)
        s.copy(edits = edits, saveError = null)
    }

    fun setNewPool(name: String) = _state.update { it.copy(newPool = name, saveError = null) }

    fun discardEdits() = _state.update { it.copy(edits = emptyMap(), newPool = "", saveError = null) }

    /** Saves every setting; the service validates the whole config and restarts with it. */
    fun save() {
        val c = client ?: return
        val s = _state.value
        val settings = s.settings ?: return
        val values = settings.saveValues(s.edits, s.newPool)
        _state.update { it.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { c.saveSettings(values) } }
            if (c !== client) return@launch
            result.onSuccess {
                _state.update { it.copy(saving = false, message = "Saved. pve-runners is restarting.") }
                // The service comes back within seconds; read what it now has.
                delay(RESTART_WAIT_MS)
                reload(discardEdits = true)
            }.onFailure { e ->
                val msg = if (e is RunnersApiException && e.httpStatus == 422) e.message else describe(e)
                _state.update { it.copy(saving = false, saveError = msg) }
            }
        }
    }

    fun runAction(name: String, args: Map<String, String>) {
        val c = client ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { c.runAction(name, args) } }
            if (c !== client) return@launch
            result.onSuccess { started ->
                _state.update { s -> s.copy(actions = s.actions.map { if (it.name == name) started else it }) }
            }.onFailure { e -> _state.update { it.copy(message = "Couldn't start: ${describe(e)}") } }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private fun describe(e: Throwable): String {
        val causes = generateSequence(e) { it.cause }.toList()
        return when {
            e is RunnersApiException && e.httpStatus == 401 ->
                "pve-runners refused the login: the account or API token needs Sys.Modify on this node."
            causes.any { it is ConnectException } ->
                "pve-runners isn't answering. Is it installed and running, and is its port right in the server settings?"
            causes.any { it is SocketTimeoutException } -> "pve-runners timed out."
            else -> e.message ?: e.javaClass.simpleName
        }
    }

    private companion object {
        const val RESTART_WAIT_MS = 4_000L
    }
}
