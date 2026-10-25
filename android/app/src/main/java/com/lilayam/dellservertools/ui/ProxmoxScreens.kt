package com.lilayam.dellservertools.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lilayam.dellservertools.core.ServerProfile
import com.lilayam.dellservertools.core.proxmox.ClusterResource
import com.lilayam.dellservertools.core.proxmox.FirewallScope
import com.lilayam.dellservertools.core.proxmox.Format
import com.lilayam.dellservertools.core.proxmox.StorageVolume
import com.lilayam.dellservertools.core.proxmox.GuestEdit
import com.lilayam.dellservertools.core.proxmox.GuestNetwork
import com.lilayam.dellservertools.core.proxmox.MoveDiskRequest
import com.lilayam.dellservertools.core.proxmox.GuestShell
import com.lilayam.dellservertools.core.proxmox.GuestType
import com.lilayam.dellservertools.core.proxmox.PhysicalDisk
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal val Green = Color(0xFF2E7D32)
internal val Amber = Color(0xFFF9A825)
internal val Red = Color(0xFFC62828)
private val Grey = Color(0xFF9E9E9E)

private fun statusColor(status: String): Color = when (status) {
    "running", "online", "available", "OK" -> Green
    "paused", "suspended", "prelaunch" -> Amber
    "stopped", "offline", "unknown" -> Red
    else -> Grey
}

// ---------------------------------------------------------------- overview

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProxmoxHomeScreen(profile: ServerProfile, vms: AppViewModels) {
    val vm = vms.proxmox
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(profile) {
        val password = vms.servers.password(profile)
        if (!profile.usesApiToken && password.isNullOrEmpty()) {
            vms.servers.back()
            vms.servers.open(profile)
        } else {
            vm.open(profile, password, vms.servers.apiTokenSecret(profile))
        }
    }
    LaunchedEffect(state.authFailed) {
        if (state.authFailed) vms.servers.forgetSessionPassword(profile.id)
    }
    AutoRefresh(state.connected, 5_000) { vm.refreshResources() }
    ForwardMessages(state.message, vms) { vm.clearMessage() }

    var filter by rememberSaveable { mutableStateOf("all") }
    var showCreate by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(profile.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    state.version?.let { Text("Proxmox VE $it", style = MaterialTheme.typography.labelSmall) }
                }
            },
            navigationIcon = { IconButton(onClick = { vms.servers.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = { openWebUi(profile, vms) }, enabled = state.connected) {
                    Icon(Icons.Filled.Public, "Full web interface")
                }
                IconButton(
                    onClick = { showCreate = true },
                    enabled = state.connected,
                ) { Icon(Icons.Filled.Add, "Create container or VM") }
                IconButton(onClick = { vms.servers.openShell(profile) }) { Icon(Icons.Filled.Terminal, "SSH shell") }
                IconButton(onClick = { vms.servers.navigate(Screen.Tasks(profile.id)) }, enabled = state.connected) {
                    Icon(Icons.AutoMirrored.Filled.List, "Tasks")
                }
                IconButton(onClick = { if (state.connected) vm.refreshResources() else vm.retry() }) {
                    Icon(Icons.Filled.Refresh, "Refresh")
                }
            },
        )
        BusyBar(state.busy)

        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            if (state.loading) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Connecting to ${profile.host}…")
                    }
                }
            }
            state.error?.let { error ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(error, color = MaterialTheme.colorScheme.error)
                        if (!state.connected) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = vm::retry) { Text("Retry") }
                                if (state.authFailed && !profile.usesApiToken) {
                                    OutlinedButton(onClick = {
                                        vms.servers.back()
                                        vms.servers.open(profile)
                                    }) { Text("Enter password") }
                                }
                                OutlinedButton(onClick = { vms.servers.edit(profile) }) { Text("Edit server") }
                            }
                        }
                    }
                }
            }

            val nodes = state.resources.filter { it.type == "node" }
            val guests = state.resources.filter { it.guestType != null }.sortedBy { it.vmid }
            val storage = state.resources.filter { it.type == "storage" }

            if (nodes.isNotEmpty()) {
                section("Nodes")
                items(nodes, key = { it.id }) { node ->
                    ResourceCard(node, onClick = { vms.servers.navigate(Screen.Node(profile.id, node.node)) })
                }
            }
            if (guests.isNotEmpty()) {
                section("VMs & containers")
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("all" to "All (${guests.size})", "running" to "Running", "stopped" to "Stopped").forEach { (key, label) ->
                            FilterChip(selected = filter == key, onClick = { filter = key }, label = { Text(label) })
                        }
                    }
                }
                val shown = guests.filter {
                    when (filter) {
                        "running" -> it.isRunning
                        "stopped" -> !it.isRunning
                        else -> true
                    }
                }
                items(shown, key = { it.id }) { guest ->
                    ResourceCard(guest, onClick = { openGuest(profile, guest, vms) })
                }
            }
            if (storage.isNotEmpty()) {
                section("Storage")
                items(storage, key = { it.id }) { s ->
                    StorageRow(s.name, s.node, s.disk, s.maxDisk, s.status) {
                        vms.servers.navigate(Screen.Storage(profile.id, s.node, s.storage ?: s.name))
                    }
                }
            }
            // Last, so the guests stay on the first screen.
            if (state.connected) {
                section("Datacenter")
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { vms.servers.navigate(Screen.BackupJobs(profile.id)) },
                            modifier = Modifier.testTag("open-backup-jobs"),
                        ) { Text("Backup jobs") }
                        OutlinedButton(
                            onClick = { vms.servers.navigate(Screen.Firewall(profile.id, FirewallScope())) },
                            modifier = Modifier.testTag("open-firewall"),
                        ) { Text("Firewall") }
                        OutlinedButton(
                            onClick = { vms.servers.navigate(Screen.Permissions(profile.id)) },
                            modifier = Modifier.testTag("open-permissions"),
                        ) { Text("Permissions") }
                    }
                }
                item {
                    OutlinedButton(
                        onClick = { vms.servers.navigate(Screen.Runners(profile.id)) },
                        modifier = Modifier.fillMaxWidth().testTag("open-runners"),
                    ) { Text("GitHub runners (pve-runners)") }
                }
            }
        }
    }

    if (showCreate) {
        val node = state.resources.firstOrNull { it.type == "node" }?.node
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("Create new…") },
            text = { Text("On node ${node ?: "?"}") },
            confirmButton = {
                TextButton(onClick = {
                    showCreate = false
                    if (node != null) vms.servers.navigate(Screen.CreateCt(profile.id, node))
                }) { Text("Container (LXC)") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showCreate = false
                    if (node != null) vms.servers.navigate(Screen.CreateVm(profile.id, node))
                }) { Text("Virtual machine") }
            },
        )
    }

    state.certPrompt?.let { prompt ->
        TrustDialog("TLS certificate", prompt, onAccept = vm::acceptCertificate, onReject = vm::rejectCertificate)
    }
}

private fun openGuest(profile: ServerProfile, guest: ClusterResource, vms: AppViewModels) {
    val type = guest.guestType ?: return
    val vmid = guest.vmid ?: return
    vms.servers.navigate(Screen.Guest(profile.id, guest.node, type, vmid, guest.name))
}

private fun openWebUi(profile: ServerProfile, vms: AppViewModels) {
    val url = vms.proxmox.webUiUrl(mobile = false) ?: return
    vms.servers.navigate(Screen.Web(profile.id, url, "${profile.displayName} web UI", isMainUi = true))
}

private fun LazyListScope.section(title: String) {
    item {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun ResourceCard(resource: ClusterResource, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(statusColor(resource.status))
                Spacer(Modifier.width(10.dp))
                val title = when {
                    resource.vmid != null -> "${resource.vmid} · ${resource.name}"
                    else -> resource.name
                }
                Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                val kind = resource.guestType?.label ?: "Node"
                Text(
                    if (resource.template) "Template" else "$kind · ${resource.status}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (resource.isRunning) {
                UsageLine("CPU", resource.cpu, "${Format.percent(resource.cpu)} of ${resource.maxCpu} CPU")
                if (resource.maxMem > 0) {
                    UsageLine(
                        "RAM",
                        resource.mem.toDouble() / resource.maxMem,
                        "${Format.bytes(resource.mem)} / ${Format.bytes(resource.maxMem)}",
                    )
                }
                Text("Up ${Format.duration(resource.uptime)}", style = MaterialTheme.typography.bodySmall)
            }
            if (resource.tags.isNotEmpty()) {
                Text(resource.tags.joinToString("  ") { "#$it" }, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun UsageLine(label: String, fraction: Double, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(36.dp))
        LinearProgressIndicator(
            progress = { fraction.toFloat().coerceIn(0f, 1f) },
            color = when {
                fraction > 0.9 -> Red
                fraction > 0.75 -> Amber
                else -> MaterialTheme.colorScheme.primary
            },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun StorageRow(name: String, node: String, used: Long, total: Long, status: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("storage-$name")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(statusColor(status))
                Spacer(Modifier.width(10.dp))
                Text(name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(node, style = MaterialTheme.typography.labelSmall)
            }
            if (total > 0) {
                UsageLine("Used", used.toDouble() / total, "${Format.bytes(used)} / ${Format.bytes(total)}")
            }
        }
    }
}

@Composable
private fun DiskRow(disk: PhysicalDisk) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(diskHealthColor(disk.health))
                Spacer(Modifier.width(10.dp))
                Text(disk.devPath, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(Format.bytes(disk.size), style = MaterialTheme.typography.labelSmall)
            }
            KeyValue("Model", disk.model)
            KeyValue("Type", disk.type)
            KeyValue("SMART", disk.health)
            KeyValue("Life left", disk.wearout.takeIf { it.toIntOrNull() != null }?.let { "$it%" }.orEmpty())
            KeyValue("Used by", disk.used)
        }
    }
}

private fun diskHealthColor(health: String): Color = when (health.uppercase()) {
    "PASSED", "OK" -> Green
    "", "UNKNOWN" -> Grey
    else -> Red
}

internal fun contentLabel(content: String): String = when (content) {
    "images" -> "VM disks"
    "rootdir" -> "Container volumes"
    "iso" -> "ISO images"
    "vztmpl" -> "Container templates"
    "backup" -> "Backups"
    "snippets" -> "Snippets"
    "import" -> "Import"
    else -> content
}

// ---------------------------------------------------------------- storage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(screen: Screen.Storage, vms: AppViewModels) {
    val vm = vms.proxmox
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(screen, state.connected) { vm.loadStorage(screen.node, screen.storage) }
    AutoRefresh(state.connected, 15_000) { vm.loadStorage(screen.node, screen.storage) }
    ForwardMessages(state.message, vms) { vm.clearMessage() }

    val detail = state.storage?.takeIf { it.node == screen.node && it.storage == screen.storage }
    val status = detail?.status
    var openVolume by remember { mutableStateOf<StorageVolume?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Storage ${screen.storage}") },
            navigationIcon = { IconButton(onClick = { vms.servers.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        BusyBar(state.busy)
        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            if (detail == null || status == null) {
                item { Text(state.error ?: "Loading…") }
                return@LazyColumn
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (status.total > 0) {
                            UsageLine(
                                "Used",
                                status.used.toDouble() / status.total,
                                "${Format.bytes(status.used)} / ${Format.bytes(status.total)}",
                            )
                        }
                        KeyValue("Free", if (status.total > 0) Format.bytes(status.avail) else "")
                        KeyValue("Type", status.type)
                        KeyValue("Node", screen.node)
                        KeyValue("Content", status.content.joinToString(", ") { contentLabel(it) })
                        KeyValue("State", if (!status.enabled) "disabled" else if (status.active) "active" else "inactive")
                        KeyValue("Shared", if (status.shared) "yes" else "no")
                    }
                }
            }
            detail.zfs?.let { zfs ->
                section("ZFS pool ${zfs.name}")
                item {
                    Card(Modifier.fillMaxWidth().testTag("zfs-pool")) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StatusDot(if (zfs.state == "ONLINE") Green else Red)
                                Spacer(Modifier.width(10.dp))
                                Text(zfs.state, fontWeight = FontWeight.SemiBold)
                            }
                            KeyValue("Scan", zfs.scan)
                            KeyValue("Errors", zfs.errors)
                            KeyValue("Status", zfs.status)
                            KeyValue("Action", zfs.action)
                            HorizontalDivider()
                            Text("Device · state · read/write/cksum errors", style = MaterialTheme.typography.labelSmall)
                            zfs.devices.forEach { d ->
                                Text(
                                    "${"  ".repeat(d.depth)}${d.name}  ${d.state}  ${d.read}/${d.write}/${d.cksum}" +
                                        if (d.msg.isNotEmpty()) "  ${d.msg}" else "",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = if (d.state.isEmpty() || d.state == "ONLINE") Color.Unspecified else Red,
                                )
                            }
                        }
                    }
                }
            }
            if (detail.config.isNotEmpty()) {
                section("Configuration")
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            detail.config.forEach { (k, v) -> KeyValue(k, v) }
                        }
                    }
                }
            }
            // Below the details, so the summary and configuration stay on the first screen.
            // The storage.cfg entry only loads with Datastore.Allocate, which editing needs too.
            section("Actions")
            item { StorageActions(screen, status.content, editable = detail.config.isNotEmpty(), vms) }
            detail.volumes.groupBy { it.content }.forEach { (content, volumes) ->
                section("${contentLabel(content)} (${volumes.size}, ${Format.bytes(volumes.sumOf { it.size })})")
                items(volumes, key = { it.volid }) { v ->
                    Card(Modifier.fillMaxWidth().clickable { openVolume = v }.testTag("volume-${v.name}")) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    v.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(Format.bytes(v.size), style = MaterialTheme.typography.labelSmall)
                            }
                            val meta = listOfNotNull(
                                v.vmid?.let { "guest $it" },
                                v.format.takeIf { it.isNotEmpty() },
                                v.ctime?.let(::formatTime),
                                "protected".takeIf { v.protected },
                            )
                            if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                            if (v.notes.isNotEmpty()) Text(v.notes, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
    openVolume?.let { v -> VolumeDialog(screen, v, vms) { openVolume = null } }
}

@Composable
internal fun BusyBar(busy: String?, onStop: (() -> Unit)? = null) {
    if (busy == null) return
    var confirmStop by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
            Text("$busy…", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f).padding(vertical = 4.dp))
            if (onStop != null) {
                TextButton(onClick = { confirmStop = true }, modifier = Modifier.testTag("busy-stop")) {
                    Text("Stop", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (confirmStop && onStop != null) {
        ConfirmDialog(
            title = "Stop \"$busy\"?",
            text = "The server aborts the task. What it already did (e.g. part of a copied disk) may stay behind.",
            confirmLabel = "Stop task",
            onConfirm = onStop,
            onDismiss = { confirmStop = false },
        )
    }
}

@Composable
internal fun AutoRefresh(enabled: Boolean, intervalMs: Long, refresh: () -> Unit) {
    LaunchedEffect(enabled) {
        while (enabled) {
            delay(intervalMs)
            refresh()
        }
    }
}

@Composable
internal fun ForwardMessages(message: String?, vms: AppViewModels, clear: () -> Unit) {
    LaunchedEffect(message) {
        message?.let {
            vms.servers.showMessage(it)
            clear()
        }
    }
}

// ---------------------------------------------------------------- node

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NodeScreen(profile: ServerProfile, node: String, vms: AppViewModels) {
    val vm = vms.proxmox
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(node, state.connected) { vm.loadNode(node) }
    AutoRefresh(state.connected, 5_000) { vm.loadNode(node) }
    ForwardMessages(state.message, vms) { vm.clearMessage() }
    var confirm by remember { mutableStateOf<Pair<String, String>?>(null) }

    val detail = state.node?.takeIf { it.node == node }
    val status = detail?.status

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Node $node") },
            navigationIcon = { IconButton(onClick = { vms.servers.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        BusyBar(state.busy)
        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilledTonalButton(onClick = { vms.servers.openShell(profile) }) { Text("SSH shell") }
                    FilledTonalButton(
                        onClick = { vms.servers.navigate(Screen.ProxmoxShell(profile.id, node, null, "$node shell")) },
                        modifier = Modifier.testTag("node-shell"),
                    ) { Text("Shell") }
                    FilledTonalButton(
                        onClick = { vms.servers.navigate(Screen.Restore(profile.id, node)) },
                        modifier = Modifier.testTag("node-restore"),
                    ) { Text("Restore backup") }
                    FilledTonalButton(
                        onClick = { vms.servers.navigate(Screen.Firewall(profile.id, FirewallScope(node = node))) },
                        modifier = Modifier.testTag("node-firewall"),
                    ) { Text("Firewall") }
                    FilledTonalButton(
                        onClick = { vms.servers.navigate(Screen.NodeSystem(profile.id, node)) },
                        modifier = Modifier.testTag("node-system"),
                    ) { Text("System") }
                    FilledTonalButton(
                        onClick = { vms.servers.navigate(Screen.Graphs(profile.id, node, "Node $node graphs")) },
                        modifier = Modifier.testTag("node-graphs"),
                    ) { Text("Graphs") }
                    OutlinedButton(onClick = { confirm = "reboot" to "Reboot node $node" }) { Text("Reboot") }
                    OutlinedButton(onClick = { confirm = "shutdown" to "Shut down node $node" }) {
                        Text("Shut down", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (status == null) {
                item { Text(state.error ?: "Loading…") }
            } else {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            UsageLine("CPU", status.cpu, "${Format.percent(status.cpu)} of ${status.cpuCount}")
                            if (status.memTotal > 0) {
                                UsageLine(
                                    "RAM",
                                    status.memUsed.toDouble() / status.memTotal,
                                    "${Format.bytes(status.memUsed)} / ${Format.bytes(status.memTotal)}",
                                )
                            }
                            if (status.swapTotal > 0) {
                                UsageLine(
                                    "Swap",
                                    status.swapUsed.toDouble() / status.swapTotal,
                                    "${Format.bytes(status.swapUsed)} / ${Format.bytes(status.swapTotal)}",
                                )
                            }
                            if (status.rootTotal > 0) {
                                UsageLine(
                                    "/",
                                    status.rootUsed.toDouble() / status.rootTotal,
                                    "${Format.bytes(status.rootUsed)} / ${Format.bytes(status.rootTotal)}",
                                )
                            }
                            KeyValue("Uptime", Format.duration(status.uptime))
                            KeyValue("Load", status.loadAverage.joinToString(" "))
                            KeyValue("CPU", status.cpuModel)
                            KeyValue("Version", status.pveVersion)
                            KeyValue("Kernel", status.kernel)
                        }
                    }
                }
            }
            if (detail?.status != null) {
                section("Storage")
                item {
                    OutlinedButton(
                        onClick = { vms.servers.navigate(Screen.StorageForm(profile.id, node, null)) },
                        modifier = Modifier.testTag("node-add-storage"),
                    ) { Text("Add storage") }
                }
                items(detail.storage, key = { it.storage }) { s ->
                    StorageRow(
                        "${s.storage} (${s.type})",
                        s.content.joinToString(", "),
                        s.used,
                        s.total,
                        if (s.active) "available" else "unknown",
                    ) { vms.servers.navigate(Screen.Storage(profile.id, node, s.storage)) }
                }
            }
            if (detail?.disks?.isNotEmpty() == true) {
                section("Disks")
                items(detail.disks, key = { it.devPath }) { DiskRow(it) }
            }
            val guests = state.resources.filter { it.guestType != null && it.node == node }.sortedBy { it.vmid }
            if (guests.isNotEmpty()) {
                section("Guests")
                items(guests, key = { it.id }) { guest -> ResourceCard(guest, onClick = { openGuest(profile, guest, vms) }) }
            }
        }
    }

    confirm?.let { (command, label) ->
        ConfirmDialog(
            title = "$label?",
            text = "All running VMs and containers on this node will be stopped. " +
                if (command == "shutdown") "You will need the iDRAC to power it back on." else "",
            confirmLabel = label.substringBefore(" node"),
            onConfirm = { vm.nodeCommand(node, command, label) },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    if (value.isBlank()) return
    Row {
        Text(key, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(80.dp))
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

// ---------------------------------------------------------------- guest

/**
 * The guest's IP addresses, tap to copy: what the running guest reports, else the static ones from its
 * config. A DHCP guest only shows one while running (a VM also needs the QEMU guest agent).
 */
@Composable
private fun GuestAddresses(detail: GuestDetail, vms: AppViewModels) {
    val context = LocalContext.current
    val live = detail.addresses
    val shown = live.ifEmpty { GuestNetwork.staticAddresses(detail.config) }
    shown.forEachIndexed { i, a ->
        Row(
            Modifier.fillMaxWidth().testTag("guest-ip").clickable {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("IP address", a.address))
                vms.servers.showMessage("Copied ${a.address}")
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (i == 0) "IP" else "", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(80.dp))
            Text(a.address, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            Text(
                "  ${a.iface}${if (live.isEmpty()) " · static" else ""}",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
    val running = detail.status?.isRunning == true
    if (shown.isEmpty() && running && detail.type == GuestType.QEMU && !GuestNetwork.agentEnabled(detail.config)) {
        KeyValue("IP", "Turn on the QEMU guest agent (Options) to see it.")
    }
}

private data class GuestAction(val action: String, val label: String, val confirm: String?)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GuestScreen(profile: ServerProfile, screen: Screen.Guest, vms: AppViewModels) {
    val vm = vms.proxmox
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(screen.vmid, state.connected) { vm.loadGuest(screen.node, screen.type, screen.vmid) }
    AutoRefresh(state.connected, 4_000) { vm.loadGuest(screen.node, screen.type, screen.vmid, full = false) }
    ForwardMessages(state.message, vms) { vm.clearMessage() }

    val detail = state.guest?.takeIf { it.vmid == screen.vmid && it.node == screen.node }
    val status = detail?.status
    val kind = screen.type.label
    var confirm by remember { mutableStateOf<GuestAction?>(null) }
    var snapshotDialog by remember { mutableStateOf(false) }
    var backupDialog by remember { mutableStateOf(false) }
    var snapshotConfirm by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showDelete by remember { mutableStateOf(false) }
    var moveDisk by remember { mutableStateOf(false) }
    var moveTargets by remember { mutableStateOf<Result<List<String>>?>(null) }
    val scope = rememberCoroutineScope()
    var convertTemplate by remember { mutableStateOf(false) }
    val config = detail?.config.orEmpty().toMap()
    val isTemplate = config["template"] == "1"
    // A clone or backup holds the lock; Proxmox would refuse every action with "can't lock file ... got timeout".
    val locked = status?.actionsLocked == true
    val idle = state.busy == null && !locked

    val actions = buildList {
        if (status == null || isTemplate) return@buildList
        if (!status.isRunning) {
            add(GuestAction("start", "Start", null))
        } else {
            if (status.isPaused) {
                add(GuestAction("resume", "Resume", null))
            } else {
                add(GuestAction("shutdown", "Shut down", "Ask the guest OS to shut down cleanly."))
                add(GuestAction("reboot", "Reboot", "Ask the guest OS to reboot."))
                if (screen.type == GuestType.QEMU) {
                    add(GuestAction("suspend", "Pause", "Freeze the VM in memory."))
                    add(GuestAction("hibernate", "Hibernate", "Save the VM's memory to disk and stop it. Start resumes it."))
                }
            }
            add(GuestAction("stop", "Stop", "Pull the plug immediately. Unsaved data in the guest is lost."))
            if (screen.type == GuestType.QEMU) {
                add(GuestAction("reset", "Reset", "Hard-reset the VM, like pressing its reset button."))
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text("${screen.vmid} · ${status?.name?.ifBlank { null } ?: screen.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("$kind on ${screen.node}", style = MaterialTheme.typography.labelSmall)
                }
            },
            navigationIcon = { IconButton(onClick = { vms.servers.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = {
                    vms.servers.navigate(Screen.EditGuest(profile.id, screen.node, screen.type, screen.vmid, screen.name))
                }) { Icon(Icons.Filled.Edit, "Edit") }
                IconButton(onClick = {
                    vms.servers.navigate(Screen.CloneGuest(profile.id, screen.node, screen.type, screen.vmid, screen.name))
                }) { Icon(Icons.Filled.ContentCopy, "Clone") }
                IconButton(onClick = { showDelete = true }, enabled = idle) {
                    Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                }
            },
        )
        BusyBar(state.busy, onStop = state.busyTask?.let { (n, upid) -> { vm.stopTask(n, upid) } })
        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (status == null) {
                            Text(state.error ?: "Loading…")
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val shown = if (status.isPaused) "paused" else status.status
                                StatusDot(statusColor(shown))
                                Spacer(Modifier.width(8.dp))
                                Text(shown.replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.SemiBold)
                                status.lock?.let { Text("  · locked ($it)", style = MaterialTheme.typography.labelSmall) }
                                if (isTemplate) Text("  · template", style = MaterialTheme.typography.labelSmall)
                            }
                            if (locked) {
                                Text(
                                    "A ${status.lock} task is still running on this $kind. Start and changes come back when it finishes.",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.testTag("guest-locked"),
                                )
                            }
                            if (status.isRunning) {
                                UsageLine("CPU", status.cpu, "${Format.percent(status.cpu)} of ${status.cpus}")
                                if (status.maxMem > 0) {
                                    UsageLine(
                                        "RAM",
                                        status.mem.toDouble() / status.maxMem,
                                        "${Format.bytes(status.mem)} / ${Format.bytes(status.maxMem)}",
                                    )
                                }
                                KeyValue("Uptime", Format.duration(status.uptime))
                            }
                            if (status.maxDisk > 0) KeyValue("Disk", Format.bytes(status.maxDisk))
                            detail?.let { GuestAddresses(it, vms) }
                            if (detail?.config?.isNotEmpty() == true) {
                                // Protection blocks deleting the guest and its disks, as in the web UI's Options tab.
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Protection (can't be deleted)", modifier = Modifier.weight(1f))
                                    Switch(
                                        checked = config["protection"] == "1",
                                        onCheckedChange = { on ->
                                            val edit = GuestEdit(screen.type, config).with("protection", if (on) "1" else "")
                                            vm.editGuest(screen.node, screen.type, screen.vmid, edit) {}
                                        },
                                        enabled = idle,
                                        modifier = Modifier.testTag("guest-protection"),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    actions.forEach { action ->
                        val destructive = action.action in setOf("stop", "reset")
                        if (action.confirm == null) {
                            FilledTonalButton(
                                onClick = { vm.guestAction(screen.node, screen.type, screen.vmid, action.action, "${action.label} $kind ${screen.vmid}") },
                                enabled = idle,
                                modifier = Modifier.testTag("guest-action-${action.action}"),
                            ) { Text(action.label) }
                        } else {
                            OutlinedButton(onClick = { confirm = action }, enabled = idle) {
                                Text(action.label, color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified)
                            }
                        }
                    }
                    GuestShell.enterCommand(screen.type, screen.vmid)?.let { shellCommand ->
                        FilledTonalButton(
                            onClick = { vms.servers.openShell(profile, shellCommand) },
                            enabled = status?.isRunning == true,
                            modifier = Modifier.testTag("guest-shell"),
                        ) { Text("Shell") }
                    }
                    FilledTonalButton(
                        onClick = {
                            vms.servers.navigate(
                                when (screen.type) {
                                    GuestType.QEMU -> Screen.VmConsole(profile.id, screen.node, screen.vmid, screen.name)
                                    GuestType.LXC -> Screen.ProxmoxShell(profile.id, screen.node, screen.vmid, "Console ${screen.vmid}")
                                },
                            )
                        },
                        enabled = status?.isRunning == true,
                        modifier = Modifier.testTag("guest-console"),
                    ) { Text("Console") }
                    OutlinedButton(onClick = { backupDialog = true }, enabled = idle) { Text("Back up now") }
                    if (detail?.config?.isNotEmpty() == true && MoveDiskRequest.movable(GuestEdit(screen.type, config)).isNotEmpty()) {
                        OutlinedButton(
                            onClick = {
                                moveDisk = true
                                moveTargets = null
                                // Loaded from the tap, not from a LaunchedEffect in the dialog: under Robolectric that effect's result never
                                // reached the dialog, while a scope launched from a click does (as in the download dialog).
                                val content = if (screen.type == GuestType.QEMU) "images" else "rootdir"
                                scope.launch {
                                    moveTargets = vm.fetch { c -> c.storage(screen.node).filter { it.active && it.holds(content) }.map { it.storage } }
                                }
                            },
                            enabled = idle,
                            modifier = Modifier.testTag("guest-move-disk"),
                        ) { Text(if (screen.type == GuestType.QEMU) "Move disk" else "Move volume") }
                    }
                    if (status != null && !status.isRunning && !isTemplate && detail?.config?.isNotEmpty() == true) {
                        OutlinedButton(
                            onClick = { convertTemplate = true },
                            enabled = idle,
                            modifier = Modifier.testTag("guest-template"),
                        ) { Text("Convert to template") }
                    }
                    OutlinedButton(
                        onClick = { vms.servers.navigate(Screen.Restore(profile.id, screen.node, screen.vmid)) },
                        enabled = !locked,
                        modifier = Modifier.testTag("guest-restore"),
                    ) { Text("Restore") }
                    OutlinedButton(
                        onClick = {
                            vms.servers.navigate(Screen.Firewall(profile.id, FirewallScope(screen.node, screen.type, screen.vmid)))
                        },
                        modifier = Modifier.testTag("guest-firewall"),
                    ) { Text("Firewall") }
                    OutlinedButton(
                        onClick = {
                            vms.servers.navigate(
                                Screen.Graphs(profile.id, screen.node, "$kind ${screen.vmid} graphs", screen.type, screen.vmid),
                            )
                        },
                        modifier = Modifier.testTag("guest-graphs"),
                    ) { Text("Graphs") }
                }
            }
            if (profile.usesApiToken) {
                item {
                    Text(
                        "Console opens the Proxmox web page; log in there once (API tokens can't open consoles).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            section("Snapshots")
            val snapshots = detail?.snapshots.orEmpty().filterNot { it.isCurrent }
            if (snapshots.isEmpty()) {
                item { Text("No snapshots.", style = MaterialTheme.typography.bodySmall) }
            }
            items(snapshots, key = { "snap-${it.name}" }) { snap ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(snap.name, fontWeight = FontWeight.SemiBold)
                        snap.time?.let { Text(formatTime(it), style = MaterialTheme.typography.labelSmall) }
                        if (snap.description.isNotBlank()) Text(snap.description, style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { snapshotConfirm = "rollback" to snap.name }, enabled = idle) {
                                Text("Roll back")
                            }
                            TextButton(onClick = { snapshotConfirm = "delete" to snap.name }, enabled = idle) {
                                Text("Delete", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
            item {
                OutlinedButton(onClick = { snapshotDialog = true }, enabled = idle && detail != null) {
                    Text("Take snapshot")
                }
            }

            if (detail?.config?.isNotEmpty() == true) {
                section("Configuration")
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(12.dp).horizontalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            detail.config.forEach { (key, value) ->
                                Row {
                                    Text(key, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.width(110.dp))
                                    Text(value, fontFamily = FontFamily.Monospace, fontSize = 12.sp, softWrap = false)
                                }
                            }
                        }
                    }
                }
                item {
                    Text(
                        "To change hardware or options, tap Edit (pencil) at the top.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    confirm?.let { action ->
        ConfirmDialog(
            title = "${action.label} $kind ${screen.vmid}?",
            text = action.confirm.orEmpty(),
            confirmLabel = action.label,
            onConfirm = {
                if (action.action == "hibernate") {
                    vm.hibernate(screen.node, screen.vmid)
                } else {
                    vm.guestAction(screen.node, screen.type, screen.vmid, action.action, "${action.label} $kind ${screen.vmid}")
                }
            },
            onDismiss = { confirm = null },
        )
    }
    snapshotConfirm?.let { (what, name) ->
        ConfirmDialog(
            title = if (what == "rollback") "Roll back to \"$name\"?" else "Delete snapshot \"$name\"?",
            text = if (what == "rollback") {
                "The $kind goes back to the state of this snapshot. Changes made since are lost."
            } else {
                "The snapshot is removed. The current state of the $kind is not affected."
            },
            confirmLabel = if (what == "rollback") "Roll back" else "Delete",
            onConfirm = {
                if (what == "rollback") {
                    vm.rollbackSnapshot(screen.node, screen.type, screen.vmid, name)
                } else {
                    vm.deleteSnapshot(screen.node, screen.type, screen.vmid, name)
                }
            },
            onDismiss = { snapshotConfirm = null },
        )
    }
    if (snapshotDialog) {
        SnapshotDialog(
            canSaveRam = screen.type == GuestType.QEMU && status?.isRunning == true,
            onCreate = { name, description, withRam ->
                vm.createSnapshot(screen.node, screen.type, screen.vmid, name, description, withRam)
            },
            onDismiss = { snapshotDialog = false },
        )
    }
    if (backupDialog) {
        val storages = state.resources.filter { it.type == "storage" && it.node == screen.node }.mapNotNull { it.storage }
        BackupDialog(
            storages = storages,
            onBackup = { storage -> vm.backup(screen.node, screen.type, screen.vmid, storage) },
            onDismiss = { backupDialog = false },
        )
    }
    if (moveDisk) {
        MoveDiskDialog(screen.node, screen.type, screen.vmid, config, status?.isRunning == true, moveTargets, vms) { moveDisk = false }
    }
    if (convertTemplate) {
        TypedConfirmDialog(
            title = "Convert $kind ${screen.vmid} to a template?",
            text = "A template can't be started or turned back into a normal $kind. Its disks become read-only bases " +
                "for clones.",
            expected = screen.vmid.toString(),
            confirmLabel = "Convert",
            tag = "template",
            onConfirm = { vm.convertToTemplate(screen.node, screen.type, screen.vmid) },
            onDismiss = { convertTemplate = false },
        )
    }
    if (showDelete) {
        DeleteGuestDialog(
            kind = kind,
            vmid = screen.vmid,
            name = status?.name?.ifBlank { null } ?: screen.name,
            running = status?.isRunning == true,
            onDelete = { purge -> vm.deleteGuest(screen.node, screen.type, screen.vmid, purge) { vms.servers.back() } },
            onDismiss = { showDelete = false },
        )
    }
}

@Composable
private fun DeleteGuestDialog(
    kind: String,
    vmid: Int,
    name: String,
    running: Boolean,
    onDelete: (purge: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var typed by remember { mutableStateOf("") }
    var purge by remember { mutableStateOf(true) }
    val confirmed = typed.trim() == vmid.toString()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete $kind $vmid?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "This permanently destroys \"$name\" and its disks. It cannot be undone.",
                    color = MaterialTheme.colorScheme.error,
                )
                if (running) {
                    Text(
                        "It is still running — stop it first, or the delete will be refused.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it.filter(Char::isDigit) },
                    label = { Text("Type the ID ($vmid) to confirm") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag("delete-confirm"),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = purge, onCheckedChange = { purge = it })
                    Text("Also remove from backups and job configs (purge)", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDelete(purge)
                    onDismiss()
                },
                enabled = confirmed,
                modifier = Modifier.testTag("delete-go"),
            ) { Text("Delete", color = if (confirmed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BackupDialog(storages: List<String>, onBackup: (String?) -> Unit, onDismiss: () -> Unit) {
    var storage by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Back up now") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Snapshot-mode backup (zstd). The guest keeps running.")
                Text("Target storage (must allow backups; empty = node default):", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    storages.forEach { s ->
                        FilterChip(selected = storage == s, onClick = { storage = if (storage == s) "" else s }, label = { Text(s) })
                    }
                }
                OutlinedTextField(value = storage, onValueChange = { storage = it.trim() }, label = { Text("Storage") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onBackup(storage.ifBlank { null })
                onDismiss()
            }) { Text("Start backup") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun formatTime(epochSeconds: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochSeconds * 1000))

// ---------------------------------------------------------------- tasks

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(profile: ServerProfile, vms: AppViewModels) {
    val vm = vms.proxmox
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.connected) { vm.loadTasks() }
    AutoRefresh(state.connected, 5_000) { vm.loadTasks() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Recent tasks") },
            navigationIcon = { IconButton(onClick = { vms.servers.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 4.dp)) {
            if (state.tasks.isEmpty()) {
                item { Text(state.error ?: "No tasks yet.", modifier = Modifier.padding(16.dp)) }
            }
            items(state.tasks, key = { it.upid }) { task ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            vms.servers.navigate(
                                Screen.TaskLog(profile.id, task.node, task.upid, "${task.type} ${task.id}".trim()),
                            )
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(
                        when {
                            task.isRunning -> Amber
                            task.isOk -> Green
                            else -> Red
                        },
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${task.type} ${task.id}".trim(), fontWeight = FontWeight.SemiBold)
                        Text(
                            "${formatTime(task.startTime)} · ${task.user} · ${task.node}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        if (!task.isRunning && !task.isOk) {
                            Text(task.status.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskLogScreen(screen: Screen.TaskLog, vms: AppViewModels) {
    val vm = vms.proxmox
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(screen.upid) { vm.loadTaskLog(screen.node, screen.upid) }
    AutoRefresh(state.taskLogRunning, 2_000) { vm.loadTaskLog(screen.node, screen.upid) }
    DisposableEffect(screen.upid) { onDispose { vm.clearTaskLog() } }
    var confirmStop by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(state.taskLog.size) {
        if (state.taskLog.isNotEmpty()) listState.scrollToItem(state.taskLog.size - 1)
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(screen.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (state.taskLogRunning) Text("Running…", style = MaterialTheme.typography.labelSmall)
                }
            },
            navigationIcon = { IconButton(onClick = { vms.servers.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                if (state.taskLogRunning) {
                    TextButton(onClick = { confirmStop = true }, modifier = Modifier.testTag("task-stop")) {
                        Text("Stop", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
        )
        if (confirmStop) {
            ConfirmDialog(
                title = "Stop this task?",
                text = "The server aborts the task. What it already did may stay behind.",
                confirmLabel = "Stop task",
                onConfirm = { vm.stopTask(screen.node, screen.upid) { vm.loadTaskLog(screen.node, screen.upid) } },
                onDismiss = { confirmStop = false },
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
        ) {
            items(state.taskLog) { line ->
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
    }
}
