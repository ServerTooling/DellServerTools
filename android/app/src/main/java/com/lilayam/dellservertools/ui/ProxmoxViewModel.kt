package com.lilayam.dellservertools.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lilayam.dellservertools.core.ServerProfile
import com.lilayam.dellservertools.core.proxmox.GuestEdit
import com.lilayam.dellservertools.core.proxmox.LxcRequest
import com.lilayam.dellservertools.core.proxmox.VmRequest
import com.lilayam.dellservertools.core.proxmox.ClusterResource
import com.lilayam.dellservertools.core.proxmox.GuestStatus
import com.lilayam.dellservertools.core.proxmox.GuestType
import com.lilayam.dellservertools.core.proxmox.NodeStatus
import com.lilayam.dellservertools.core.proxmox.ProxmoxApiException
import com.lilayam.dellservertools.core.proxmox.ProxmoxClient
import com.lilayam.dellservertools.core.proxmox.ProxmoxCredentials
import com.lilayam.dellservertools.core.proxmox.Snapshot
import com.lilayam.dellservertools.core.proxmox.StorageInfo
import com.lilayam.dellservertools.core.proxmox.TaskSummary
import com.lilayam.dellservertools.core.proxmox.UntrustedCertificateException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class GuestDetail(
    val node: String,
    val type: GuestType,
    val vmid: Int,
    val status: GuestStatus? = null,
    val config: List<Pair<String, String>> = emptyList(),
    val snapshots: List<Snapshot> = emptyList(),
)

data class NodeDetail(
    val node: String,
    val status: NodeStatus? = null,
    val storage: List<StorageInfo> = emptyList(),
)

data class ProxmoxUiState(
    val profileId: String? = null,
    val connected: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val authFailed: Boolean = false,
    val certPrompt: FingerprintPrompt? = null,
    val version: String? = null,
    val resources: List<ClusterResource> = emptyList(),
    val guest: GuestDetail? = null,
    val node: NodeDetail? = null,
    val tasks: List<TaskSummary> = emptyList(),
    val taskLog: List<String> = emptyList(),
    val taskLogRunning: Boolean = false,
    /** Label of the action currently running, e.g. "Shutdown VM 100". */
    val busy: String? = null,
    val message: String? = null,
    val createOptions: GuestCreateOptions? = null,
)

/** What the create forms need: templates/ISOs, storages, bridges and the next free id. */
data class GuestCreateOptions(
    val node: String,
    val loading: Boolean = true,
    val nextVmid: Int = 100,
    val ctStorages: List<String> = emptyList(),          // storages for a CT rootfs (rootdir)
    val vmStorages: List<String> = emptyList(),          // storages for a VM disk (images)
    val templateStorages: List<String> = emptyList(),
    val templates: List<com.lilayam.dellservertools.core.proxmox.TemplateFile> = emptyList(),
    val isoStorages: List<String> = emptyList(),
    val isos: List<com.lilayam.dellservertools.core.proxmox.TemplateFile> = emptyList(),
    val bridges: List<String> = emptyList(),
    val error: String? = null,
)

/** Talks to one Proxmox VE server through its REST API. */
class ProxmoxViewModel(application: Application) : AndroidViewModel(application) {
    private val trust = TrustStore(application)

    private val _state = MutableStateFlow(ProxmoxUiState())
    val state: StateFlow<ProxmoxUiState> = _state.asStateFlow()

    private var client: ProxmoxClient? = null
    private var profile: ServerProfile? = null
    private var credentials: ProxmoxCredentials? = null
    private val loginLock = Mutex()

    /** Ticket + CSRF token for logging the embedded web UI in, when using password auth. */
    val webTicket: String? get() = client?.ticket?.ticket

    val baseUrl: String? get() = client?.baseUrl

    fun pinnedFingerprint(profile: ServerProfile): String? =
        trust.fingerprintFor(TrustStore.tlsId(profile.host, profile.port))

    fun consoleUrl(node: String, type: GuestType?, vmid: Int?, name: String?): String? =
        client?.consoleUrl(node, type, vmid, name)

    fun webUiUrl(mobile: Boolean): String? = client?.webUrl(mobile)

    /** Connects to the server unless already connected to it with the same credentials. */
    fun open(profile: ServerProfile, password: String?, apiTokenSecret: String?) {
        val creds = if (profile.usesApiToken) {
            ProxmoxCredentials.ApiToken(profile.apiTokenId.trim(), apiTokenSecret.orEmpty())
        } else {
            ProxmoxCredentials.Password(profile.proxmoxUser, password.orEmpty())
        }
        val s = _state.value
        if (s.profileId == profile.id && this.profile == profile && credentials == creds) {
            if (s.connected || s.loading || s.certPrompt != null || s.authFailed) return
        }
        this.profile = profile
        this.credentials = creds
        client = ProxmoxClient(profile.host, profile.port, creds, pinnedFingerprint(profile))
        _state.value = ProxmoxUiState(profileId = profile.id, loading = true)
        connect()
    }

    fun retry() {
        val p = profile ?: return
        val creds = credentials ?: return
        client = ProxmoxClient(p.host, p.port, creds, pinnedFingerprint(p))
        _state.update { it.copy(error = null, authFailed = false, loading = true) }
        connect()
    }

    private fun connect() {
        val c = client ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    loginLock.withLock { c.login() }
                    val version = c.version()
                    version to c.resources()
                }
            }
            if (c !== client) return@launch
            result.onSuccess { (version, resources) ->
                _state.update {
                    it.copy(connected = true, loading = false, version = version, resources = resources, error = null)
                }
            }.onFailure { e -> handleFailure(c, e) }
        }
    }

    fun acceptCertificate() {
        val prompt = _state.value.certPrompt ?: return
        val p = profile ?: return
        trust.save(TrustStore.tlsId(p.host, p.port), prompt.fingerprint)
        _state.update { it.copy(certPrompt = null) }
        retry()
    }

    fun rejectCertificate() =
        _state.update { it.copy(certPrompt = null, loading = false, error = "Certificate not trusted.") }

    // ------------------------------------------------------------ reads

    fun refreshResources() = load { c ->
        val resources = c.resources()
        _state.update { it.copy(resources = resources, error = null) }
    }

    fun loadNode(node: String) {
        if (_state.value.node?.node != node) _state.update { it.copy(node = NodeDetail(node)) }
        load { c ->
            val status = c.nodeStatus(node)
            val storage = runCatching { c.storage(node) }.getOrDefault(emptyList())
            _state.update { it.copy(node = NodeDetail(node, status, storage)) }
        }
    }

    fun loadGuest(node: String, type: GuestType, vmid: Int, full: Boolean = true) {
        val current = _state.value.guest
        if (current?.vmid != vmid || current.node != node) {
            _state.update { it.copy(guest = GuestDetail(node, type, vmid)) }
        }
        load { c ->
            val status = c.guestStatus(node, type, vmid)
            val previous = _state.value.guest?.takeIf { it.vmid == vmid && it.node == node }
            val config = if (full || previous == null) c.guestConfig(node, type, vmid) else previous.config
            val snapshots = if (full || previous == null) {
                runCatching { c.snapshots(node, type, vmid) }.getOrDefault(emptyList())
            } else {
                previous.snapshots
            }
            _state.update {
                if (it.guest?.vmid == vmid && it.guest.node == node) {
                    it.copy(guest = GuestDetail(node, type, vmid, status, config, snapshots))
                } else {
                    it
                }
            }
        }
    }

    fun loadTasks() = load { c ->
        val tasks = c.clusterTasks()
        _state.update { it.copy(tasks = tasks) }
    }

    fun loadTaskLog(node: String, upid: String) = load { c ->
        val log = c.taskLog(node, upid)
        val running = c.taskStatus(node, upid).running
        _state.update { it.copy(taskLog = log, taskLogRunning = running) }
    }

    fun clearTaskLog() = _state.update { it.copy(taskLog = emptyList(), taskLogRunning = false) }

    // ------------------------------------------------------------ actions

    fun guestAction(node: String, type: GuestType, vmid: Int, action: String, label: String) =
        runTask(label, node, refreshGuest = Triple(node, type, vmid)) { c -> c.guestAction(node, type, vmid, action) }

    fun createSnapshot(node: String, type: GuestType, vmid: Int, name: String, description: String) =
        runTask("Snapshot \"$name\"", node, refreshGuest = Triple(node, type, vmid)) { c ->
            c.createSnapshot(node, type, vmid, name, description)
        }

    fun rollbackSnapshot(node: String, type: GuestType, vmid: Int, name: String) =
        runTask("Roll back to \"$name\"", node, refreshGuest = Triple(node, type, vmid)) { c ->
            c.rollbackSnapshot(node, type, vmid, name)
        }

    fun deleteSnapshot(node: String, type: GuestType, vmid: Int, name: String) =
        runTask("Delete snapshot \"$name\"", node, refreshGuest = Triple(node, type, vmid)) { c ->
            c.deleteSnapshot(node, type, vmid, name)
        }

    fun backup(node: String, type: GuestType, vmid: Int, storage: String?) =
        runTask("Backup ${type.label} $vmid", node, refreshGuest = Triple(node, type, vmid)) { c ->
            c.backup(node, vmid, storage)
        }

    // ------------------------------------------------------------ create CT

    fun loadCreateOptions(node: String) {
        val c = client ?: return
        _state.update { it.copy(createOptions = GuestCreateOptions(node = node, loading = true)) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val nextId = c.nextVmid()
                    val storages = c.storage(node)
                    val tmplStorages = storages.filter { it.active && it.holds("vztmpl") }.map { it.storage }
                    val isoStorages = storages.filter { it.active && it.holds("iso") }.map { it.storage }
                    val ctStorages = storages.filter { it.active && it.holds("rootdir") }.map { it.storage }
                    val vmStorages = storages.filter { it.active && it.holds("images") }.map { it.storage }
                    val templates = tmplStorages.flatMap { s -> runCatching { c.templates(node, s) }.getOrDefault(emptyList()) }
                    val isos = isoStorages.flatMap { s -> runCatching { c.isoImages(node, s) }.getOrDefault(emptyList()) }
                    val bridges = runCatching { c.bridges(node) }.getOrDefault(emptyList()).ifEmpty { listOf("vmbr0") }
                    GuestCreateOptions(node, false, nextId, ctStorages, vmStorages, tmplStorages, templates, isoStorages, isos, bridges)
                }
            }
            if (c !== client) return@launch
            result.onSuccess { opts -> _state.update { it.copy(createOptions = opts) } }
                .onFailure { e ->
                    _state.update { it.copy(createOptions = GuestCreateOptions(node, loading = false, error = describe(e))) }
                }
        }
    }

    fun clearCreateOptions() = _state.update { it.copy(createOptions = null) }

    /** Creates the container and, on success, reports it and refreshes the list. */
    fun createContainer(node: String, request: LxcRequest, onCreated: () -> Unit) =
        runTask("Create CT ${request.vmid} (${request.hostname})", node, refreshGuest = null, afterDone = onCreated) { c ->
            c.createLxc(node, request.toParams())
        }

    fun createVm(node: String, request: VmRequest, onCreated: () -> Unit) =
        runTask("Create VM ${request.vmid} (${request.name})", node, refreshGuest = null, afterDone = onCreated) { c ->
            c.createQemu(node, request.toParams())
        }

    /** Applies config changes to an existing CT/VM, then reloads it. */
    fun editGuest(
        node: String,
        type: GuestType,
        vmid: Int,
        edit: GuestEdit,
        current: Map<String, String>,
        onDone: () -> Unit,
    ) {
        val c = client ?: return
        if (_state.value.busy != null) {
            _state.update { it.copy(message = "Wait for \"${it.busy}\" to finish") }
            return
        }
        val changes = edit.changedParams(current)
        if (changes.isEmpty()) {
            _state.update { it.copy(message = "No changes to save") }
            onDone()
            return
        }
        val label = "Update ${type.label} $vmid"
        _state.update { it.copy(busy = label) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { c.updateConfig(node, type, vmid, changes) } }
            _state.update {
                it.copy(busy = null, message = result.fold({ "$label: saved" }, { e -> "$label failed: ${describe(e)}" }))
            }
            if (result.isSuccess) {
                loadGuest(node, type, vmid)
                onDone()
            }
        }
    }

    /** Downloads a template, then refreshes the template list in the open create form. */
    fun downloadTemplate(node: String, storage: String, template: String) {
        val label = "Download ${template.substringAfterLast('/')}"
        runTask(label, node, refreshGuest = null, afterDone = { loadCreateOptions(node) }) { c ->
            c.downloadTemplate(node, storage, template)
        }
    }

    /** The `pveam available` list, for the download picker. */
    suspend fun availableTemplates(node: String): List<com.lilayam.dellservertools.core.proxmox.AplTemplate> {
        val c = client ?: return emptyList()
        return withContext(Dispatchers.IO) { runCatching { c.availableTemplates(node) }.getOrDefault(emptyList()) }
    }

    fun nodeCommand(node: String, command: String, label: String) {
        val c = client ?: return
        _state.update { it.copy(busy = label) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { c.nodeCommand(node, command) } }
            _state.update {
                it.copy(
                    busy = null,
                    message = result.fold({ "$label: sent. The connection will drop." }, { e -> "$label failed: ${describe(e)}" }),
                )
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    /** Runs an API call that starts a task, then follows the task until it finishes. */
    private fun runTask(
        label: String,
        node: String,
        refreshGuest: Triple<String, GuestType, Int>?,
        afterDone: (() -> Unit)? = null,
        start: (ProxmoxClient) -> String?,
    ) {
        val c = client ?: return
        if (_state.value.busy != null) {
            _state.update { it.copy(message = "Wait for \"${it.busy}\" to finish") }
            return
        }
        _state.update { it.copy(busy = label) }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val upid = start(c)
                    if (upid.isNullOrBlank() || !upid.startsWith("UPID:")) return@runCatching "done"
                    val deadline = System.currentTimeMillis() + TASK_WAIT_MS
                    while (System.currentTimeMillis() < deadline) {
                        val status = c.taskStatus(node, upid)
                        if (!status.running) {
                            return@runCatching status.exitStatus ?: "OK"
                        }
                        delay(1_000)
                    }
                    "still running (see Tasks)"
                }
            }
            val message = outcome.fold(
                { exit -> if (exit == "OK" || exit == "done") "$label: done" else "$label: $exit" },
                { e -> "$label failed: ${describe(e)}" },
            )
            _state.update { it.copy(busy = null, message = message) }
            refreshResources()
            refreshGuest?.let { (n, t, id) -> loadGuest(n, t, id) }
            val succeeded = outcome.getOrNull()?.let { it == "OK" || it == "done" } == true
            if (succeeded) afterDone?.invoke()
        }
    }

    private fun load(block: suspend (ProxmoxClient) -> Unit) {
        val c = client ?: return
        if (!_state.value.connected) return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { block(c) } }
            if (c !== client) return@launch
            result.onFailure { e -> handleFailure(c, e) }
        }
    }

    private fun handleFailure(c: ProxmoxClient, e: Throwable) {
        val cert = c.rejectedCertificate
        when {
            cert != null && generateSequence(e) { it.cause }.any { it is SSLException || it is UntrustedCertificateException } ->
                _state.update {
                    it.copy(loading = false, connected = false, certPrompt = FingerprintPrompt(cert.fingerprint, cert.changed))
                }
            e is ProxmoxApiException && e.httpStatus == 401 && !_state.value.connected ->
                _state.update { it.copy(loading = false, authFailed = true, error = describe(e)) }
            !_state.value.connected ->
                _state.update { it.copy(loading = false, error = describe(e)) }
            else ->
                _state.update { it.copy(error = describe(e)) }
        }
    }

    private fun describe(e: Throwable): String {
        val causes = generateSequence(e) { it.cause }.toList()
        return when {
            causes.any { it is UnknownHostException } -> "Unknown host. Check the address."
            causes.any { it is SocketTimeoutException } -> "Timed out. Is the phone on the same network as the server?"
            causes.any { it is NoRouteToHostException } -> "No route to the server. Check Wi-Fi / VPN."
            causes.any { it is ConnectException } -> "Connection refused. Is the web UI port (usually 8006) right?"
            else -> e.message ?: e.javaClass.simpleName
        }
    }

    private companion object {
        const val TASK_WAIT_MS = 5 * 60 * 1000L
    }
}
