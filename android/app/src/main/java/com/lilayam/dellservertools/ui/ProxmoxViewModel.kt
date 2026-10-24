package com.lilayam.dellservertools.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lilayam.dellservertools.core.ServerProfile
import com.lilayam.dellservertools.core.proxmox.CloneRequest
import com.lilayam.dellservertools.core.proxmox.DownloadUrlRequest
import com.lilayam.dellservertools.core.proxmox.PruneOptions
import com.lilayam.dellservertools.core.proxmox.StorageForm
import com.lilayam.dellservertools.core.proxmox.GuestEdit
import com.lilayam.dellservertools.core.proxmox.GuestAddress
import com.lilayam.dellservertools.core.proxmox.GuestNetwork
import com.lilayam.dellservertools.core.proxmox.LxcRequest
import com.lilayam.dellservertools.core.proxmox.MoveDiskRequest
import com.lilayam.dellservertools.core.proxmox.RestoreRequest
import com.lilayam.dellservertools.core.proxmox.VmKeys
import com.lilayam.dellservertools.core.proxmox.VmRequest
import com.lilayam.dellservertools.core.proxmox.ClusterResource
import com.lilayam.dellservertools.core.proxmox.GuestStatus
import com.lilayam.dellservertools.core.proxmox.GuestType
import com.lilayam.dellservertools.core.proxmox.NodeStatus
import com.lilayam.dellservertools.core.proxmox.PhysicalDisk
import com.lilayam.dellservertools.core.proxmox.ProxmoxApiException
import com.lilayam.dellservertools.core.proxmox.ProxmoxClient
import com.lilayam.dellservertools.core.proxmox.ProxmoxCredentials
import com.lilayam.dellservertools.core.proxmox.RunnersClient
import com.lilayam.dellservertools.core.proxmox.Snapshot
import com.lilayam.dellservertools.core.proxmox.StorageInfo
import com.lilayam.dellservertools.core.proxmox.StorageStatus
import com.lilayam.dellservertools.core.proxmox.StorageVolume
import com.lilayam.dellservertools.core.proxmox.TaskSummary
import com.lilayam.dellservertools.core.proxmox.UntrustedCertificateException
import com.lilayam.dellservertools.core.proxmox.ZfsPool
import java.io.InputStream
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
    /** Addresses the running guest reports; empty when it is stopped or can't tell (no QEMU guest agent). */
    val addresses: List<GuestAddress> = emptyList(),
)

data class NodeDetail(
    val node: String,
    val status: NodeStatus? = null,
    val storage: List<StorageInfo> = emptyList(),
    val disks: List<PhysicalDisk> = emptyList(),
)

data class StorageDetail(
    val node: String,
    val storage: String,
    val status: StorageStatus? = null,
    val config: List<Pair<String, String>> = emptyList(),
    val volumes: List<StorageVolume> = emptyList(),
    val zfs: ZfsPool? = null,
)

/** Backup archives on every backup storage of a node, for the restore screen. */
data class BackupList(
    val node: String,
    val loading: Boolean = true,
    val volumes: List<StorageVolume> = emptyList(),
    val error: String? = null,
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
    val storage: StorageDetail? = null,
    val tasks: List<TaskSummary> = emptyList(),
    val taskLog: List<String> = emptyList(),
    val taskLogRunning: Boolean = false,
    /** Label of the action currently running, e.g. "Shutdown VM 100". */
    val busy: String? = null,
    /** Node and UPID of the task behind [busy], once the server has started it, so it can be stopped. */
    val busyTask: Pair<String, String>? = null,
    val message: String? = null,
    val createOptions: GuestCreateOptions? = null,
    val backups: BackupList? = null,
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

    /** The pve-runners service on this host, reached with the same login and certificate pin. */
    fun runnersClient(profile: ServerProfile): RunnersClient? =
        client?.takeIf { this.profile?.id == profile.id }?.let { RunnersClient(it, profile.host, profile.runnersPort) }

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
        return@load { it.copy(resources = resources, error = null) }
    }

    fun loadNode(node: String) {
        if (_state.value.node?.node != node) _state.update { it.copy(node = NodeDetail(node)) }
        load { c ->
            val status = c.nodeStatus(node)
            val storage = runCatching { c.storage(node) }.getOrDefault(emptyList())
            // Needs Sys.Audit; a token without it still sees the rest of the node.
            val disks = runCatching { c.disks(node) }.getOrDefault(emptyList())
            return@load { it.copy(node = NodeDetail(node, status, storage, disks)) }
        }
    }

    fun loadStorage(node: String, storage: String) {
        val current = _state.value.storage
        if (current?.node != node || current.storage != storage) {
            _state.update { it.copy(storage = StorageDetail(node, storage)) }
        }
        load { c ->
            val status = c.storageStatus(node, storage)
            // The storage.cfg entry needs Datastore.Allocate, the pool status Sys.Audit: optional extras.
            val config = runCatching { c.storageConfig(storage) }.getOrDefault(emptyList())
            val volumes = if (status.active) runCatching { c.storageContent(node, storage) }.getOrDefault(emptyList()) else emptyList()
            val pool = config.firstOrNull { it.first == "pool" }?.second?.substringBefore('/')
            val zfs = if (status.type == "zfspool" && pool != null) runCatching { c.zfsPool(node, pool) }.getOrNull() else null
            return@load { it.copy(storage = StorageDetail(node, storage, status, config, volumes, zfs)) }
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
            // Re-read on every refresh: a container that just started gets its DHCP lease seconds later.
            val canAsk = status.isRunning && (type == GuestType.LXC || GuestNetwork.agentEnabled(config))
            val addresses = if (canAsk) runCatching { c.guestAddresses(node, type, vmid) }.getOrDefault(emptyList()) else emptyList()
            return@load {
                if (it.guest?.vmid == vmid && it.guest.node == node) {
                    it.copy(guest = GuestDetail(node, type, vmid, status, config, snapshots, addresses))
                } else {
                    it
                }
            }
        }
    }

    fun loadTasks() = load { c ->
        val tasks = c.clusterTasks()
        return@load { it.copy(tasks = tasks) }
    }

    fun loadTaskLog(node: String, upid: String) = load { c ->
        val log = c.taskLog(node, upid)
        val running = c.taskStatus(node, upid).running
        return@load { it.copy(taskLog = log, taskLogRunning = running) }
    }

    fun clearTaskLog() = _state.update { it.copy(taskLog = emptyList(), taskLogRunning = false) }

    // ------------------------------------------------------------ actions

    fun guestAction(node: String, type: GuestType, vmid: Int, action: String, label: String) =
        runTask(label, node, refreshGuest = Triple(node, type, vmid)) { c -> c.guestAction(node, type, vmid, action) }

    /** Stops a VM and starts it again, so config changes that wait for a fresh start (boot order) apply. */
    fun stopAndStart(node: String, vmid: Int) =
        runTask("Stop VM $vmid", node, Triple(node, GuestType.QEMU, vmid), afterDone = {
            guestAction(node, GuestType.QEMU, vmid, "start", "Start VM $vmid")
        }) { c -> c.guestAction(node, GuestType.QEMU, vmid, "stop") }

    /** Presses one key in a running VM; only failures are reported, the console shows the rest. */
    fun sendKey(node: String, vmid: Int, key: VmKeys.Key) {
        val c = client ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { c.sendKey(node, vmid, key.name) } }
            result.onFailure { e -> _state.update { it.copy(message = "${key.label} failed: ${describe(e)}") } }
        }
    }

    /**
     * Resets the VM and keeps pressing Enter through the firmware logo, so the "Press any key to boot
     * from CD or DVD" prompt of a Windows ISO is answered (see [VmKeys.BOOT_PRESS_WINDOW_MS]).
     */
    fun resetAndBootFromCd(node: String, vmid: Int) {
        val c = client ?: return
        val label = "Boot VM $vmid from CD"
        if (_state.value.busy != null) {
            _state.update { it.copy(message = "Wait for \"${it.busy}\" to finish") }
            return
        }
        val seconds = VmKeys.BOOT_PRESS_WINDOW_MS / 1000
        _state.update { it.copy(busy = label, message = "$label: resetting, then pressing Enter for $seconds s") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val upid = c.guestAction(node, GuestType.QEMU, vmid, "reset")
                    if (upid != null && upid.startsWith("UPID:")) {
                        val deadline = System.currentTimeMillis() + TASK_WAIT_MS
                        while (c.taskStatus(node, upid).running && System.currentTimeMillis() < deadline) delay(250)
                    }
                    repeat(VmKeys.bootPresses) {
                        c.sendKey(node, vmid, VmKeys.BOOT_KEY)
                        delay(VmKeys.BOOT_PRESS_INTERVAL_MS)
                    }
                }
            }
            val message = result.fold(
                { "$label: done. If Windows Setup isn't loading, try again." },
                { e -> "$label failed: ${describe(e)}" },
            )
            _state.update { it.copy(busy = null, message = message) }
        }
    }

    fun createSnapshot(node: String, type: GuestType, vmid: Int, name: String, description: String, withRam: Boolean = false) =
        runTask("Snapshot \"$name\"", node, refreshGuest = Triple(node, type, vmid)) { c ->
            c.createSnapshot(node, type, vmid, name, description, withRam)
        }

    /** Saves a running VM's memory to disk and stops it; Start resumes it. */
    fun hibernate(node: String, vmid: Int) =
        runTask("Hibernate VM $vmid", node, Triple(node, GuestType.QEMU, vmid)) { c -> c.hibernate(node, vmid) }

    fun moveDisk(node: String, vmid: Int, request: MoveDiskRequest) =
        runTask("Move ${request.disk} of ${request.type.label} $vmid to ${request.storage}", node, Triple(node, request.type, vmid)) { c ->
            c.moveDisk(node, vmid, request)
        }

    fun convertToTemplate(node: String, type: GuestType, vmid: Int) =
        runTask("Convert ${type.label} $vmid to a template", node, Triple(node, type, vmid)) { c ->
            c.convertToTemplate(node, type, vmid)
        }

    /** Asks the server to stop a task; the one [busy] follows then ends with its error status. */
    fun stopTask(node: String, upid: String, onDone: () -> Unit = {}) {
        val c = client ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { c.stopTask(node, upid) } }
            result.onFailure { e -> _state.update { it.copy(message = "Stop task failed: ${describe(e)}") } }
            onDone()
        }
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

    // ------------------------------------------------------------ storage

    /** Runs one server call off the main thread for a screen that keeps the result itself. */
    suspend fun <T> fetch(block: (ProxmoxClient) -> T): Result<T> {
        val c = client ?: return Result.failure(IllegalStateException("Not connected"))
        return withContext(Dispatchers.IO) { runCatching { block(c) } }
            .recoverCatching { e -> throw IllegalStateException(describe(e), e) }
    }

    fun deleteVolume(node: String, storage: String, volume: StorageVolume) =
        runTask("Delete ${volume.name}", node, refreshGuest = null, afterDone = { loadStorage(node, storage) }) { c ->
            c.deleteVolume(node, storage, volume.volid)
        }

    fun updateVolume(node: String, storage: String, volume: StorageVolume, notes: String, protected: Boolean) =
        runTask("Update ${volume.name}", node, refreshGuest = null, afterDone = { loadStorage(node, storage) }) { c ->
            c.updateVolume(node, storage, volume.volid, notes, protected)
            null
        }

    fun downloadUrl(node: String, storage: String, request: DownloadUrlRequest) =
        runTask("Download ${request.filename}", node, refreshGuest = null, afterDone = { loadStorage(node, storage) }) { c ->
            c.downloadUrl(node, storage, request)
        }

    /** Streams a file from the phone into the storage, showing the percentage sent in the busy bar. */
    fun upload(node: String, storage: String, content: String, filename: String, size: Long, open: () -> InputStream) {
        val label = "Upload $filename"
        runTask(label, node, refreshGuest = null, afterDone = { loadStorage(node, storage) }) { c ->
            open().use { input ->
                c.upload(node, storage, content, filename, size, input) { sent ->
                    val percent = if (size > 0) sent * 100 / size else 100
                    viewModelScope.launch(Dispatchers.Main) {
                        _state.update { if (it.busy?.startsWith(label) == true) it.copy(busy = "$label ($percent%)") else it }
                    }
                }
            }
        }
    }

    fun pruneBackups(node: String, storage: String, options: PruneOptions, vmid: Int?, onDone: () -> Unit) =
        runTask("Prune backups on $storage", node, refreshGuest = null, afterDone = onDone) { c ->
            c.pruneBackups(node, storage, options, vmid)
        }

    fun saveStorage(form: StorageForm, onDone: () -> Unit) {
        val label = if (form.isNew) "Add storage ${form.id}" else "Update storage ${form.id}"
        runTask(label, "", refreshGuest = null, afterDone = onDone) { c ->
            if (form.isNew) c.createStorage(form) else c.updateStorage(form)
            null
        }
    }

    fun deleteStorage(storage: String, onDone: () -> Unit) =
        runTask("Remove storage $storage", "", refreshGuest = null, afterDone = onDone) { c ->
            c.deleteStorage(storage)
            null
        }

    // ------------------------------------------------------------ restore

    fun loadBackups(node: String) {
        if (_state.value.backups?.node != node) _state.update { it.copy(backups = BackupList(node)) }
        load { c ->
            val storages = c.storage(node).filter { it.active && it.holds("backup") }
            // One unreadable storage (e.g. an offline PBS) shouldn't hide the backups on the others.
            val results = storages.map { s -> runCatching { c.backups(node, s.storage) } }
            val volumes = results.flatMap { it.getOrDefault(emptyList()) }.sortedByDescending { it.ctime ?: 0L }
            val error = results.firstNotNullOfOrNull { it.exceptionOrNull() }?.let(::describe)
            return@load { it.copy(backups = BackupList(node, loading = false, volumes = volumes, error = error)) }
        }
    }

    /** The guest config saved in a backup; empty when it can't be read. */
    suspend fun backupConfig(node: String, volid: String): Map<String, String> {
        val c = client ?: return emptyMap()
        return withContext(Dispatchers.IO) { runCatching { c.backupConfig(node, volid) }.getOrDefault(emptyMap()) }
    }

    fun restoreBackup(node: String, request: RestoreRequest, onRestored: () -> Unit) =
        runTask("Restore ${request.type.label} ${request.vmid}", node, refreshGuest = null, afterDone = onRestored) { c ->
            c.restoreGuest(node, request)
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

    /** Deletes a CT/VM, then calls back (e.g. to leave the guest screen). */
    fun deleteGuest(node: String, type: GuestType, vmid: Int, purge: Boolean, onDeleted: () -> Unit) =
        runTask("Delete ${type.label} $vmid", node, refreshGuest = null, afterDone = onDeleted) { c ->
            c.deleteGuest(node, type, vmid, purge = purge, destroyUnreferenced = purge)
        }

    fun createVm(node: String, request: VmRequest, onCreated: () -> Unit) =
        runTask("Create VM ${request.vmid} (${request.name})", node, refreshGuest = null, afterDone = onCreated) { c ->
            c.createQemu(node, request.toParams())
        }

    /** Clones a CT/VM and, on success, reports it and refreshes the list. */
    fun cloneGuest(node: String, type: GuestType, vmid: Int, request: CloneRequest, onCloned: () -> Unit) =
        runTask("Clone ${type.label} $vmid to ${request.newId}", node, refreshGuest = null, afterDone = onCloned) { c ->
            c.cloneGuest(node, type, vmid, request.toParams())
        }

    /** Applies config changes to an existing CT/VM, then reloads it. */
    fun editGuest(node: String, type: GuestType, vmid: Int, edit: GuestEdit, onDone: () -> Unit) {
        val changes = edit.changedParams()
        if (changes.isEmpty()) {
            _state.update { it.copy(message = "No changes to save") }
            onDone()
            return
        }
        runTask("Update ${type.label} $vmid", node, Triple(node, type, vmid), afterDone = onDone) { c ->
            c.updateConfig(node, type, vmid, changes)
        }
    }

    /** Rebuilds the VM's cloud-init drive from its saved settings. */
    fun regenerateCloudInit(node: String, vmid: Int) =
        runTask("Regenerate cloud-init of VM $vmid", node, Triple(node, GuestType.QEMU, vmid)) { c ->
            c.regenerateCloudInit(node, vmid)
            null
        }

    /** Grows one disk or mount point by [gb] GiB. */
    fun resizeDisk(node: String, type: GuestType, vmid: Int, disk: String, gb: Int) =
        runTask("Grow $disk of ${type.label} $vmid by $gb GiB", node, Triple(node, type, vmid)) { c ->
            c.resizeDisk(node, type, vmid, disk, GuestEdit.growBy(gb))
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
                    withContext(Dispatchers.Main) { _state.update { it.copy(busyTask = node to upid) } }
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
            _state.update { it.copy(busy = null, busyTask = null, message = message) }
            refreshResources()
            refreshGuest?.let { (n, t, id) -> loadGuest(n, t, id) }
            val succeeded = outcome.getOrNull()?.let { it == "OK" || it == "done" } == true
            if (succeeded) afterDone?.invoke()
        }
    }

    /**
     * Runs [block]'s server calls off the main thread and applies the state change it returns on the main thread:
     * a StateFlow written from an IO worker can recompose on that worker, which the Compose tests caught as
     * "LayoutNode should be attached to an owner".
     */
    private fun load(block: suspend (ProxmoxClient) -> (ProxmoxUiState) -> ProxmoxUiState) {
        val c = client ?: return
        if (!_state.value.connected) return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { block(c) } }
            if (c !== client) return@launch
            result.fold({ change -> _state.update(change) }, { e -> handleFailure(c, e) })
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
