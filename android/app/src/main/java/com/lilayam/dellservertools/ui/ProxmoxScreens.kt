package com.lilayam.dellservertools.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lilayam.dellservertools.core.ServerProfile
import com.lilayam.dellservertools.core.proxmox.ClusterResource
import com.lilayam.dellservertools.core.proxmox.Format
import com.lilayam.dellservertools.core.proxmox.GuestType
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

private val Green = Color(0xFF2E7D32)
private val Amber = Color(0xFFF9A825)
private val Red = Color(0xFFC62828)
private val Grey = Color(0xFF9E9E9E)

private fun statusColor(status: String): Color = when (status) {
    "running", "online", "available", "OK" -> Green
    "paused", "suspended", "prelaunch" -> Amber
    "stopped", "offline", "unknown" -> Red
    else -> Grey
}

// ---------------------------------------------------------------- overview

@OptIn(ExperimentalMaterial3Api::class)
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
                    onClick = {
                        val node = state.resources.firstOrNull { it.type == "node" }?.node
                        if (node != null) vms.servers.navigate(Screen.CreateCt(profile.id, node))
                    },
                    enabled = state.connected,
                ) { Icon(Icons.Filled.Add, "Create container") }
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
                items(storage, key = { it.id }) { s -> StorageRow(s.name, s.node, s.disk, s.maxDisk, s.status) }
            }
        }
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
private fun StorageRow(name: String, node: String, used: Long, total: Long, status: String) {
    Card(Modifier.fillMaxWidth()) {
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
internal fun BusyBar(busy: String?) {
    if (busy == null) return
    Column(Modifier.fillMaxWidth()) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("$busy…", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    }
}

@Composable
private fun AutoRefresh(enabled: Boolean, intervalMs: Long, refresh: () -> Unit) {
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
                    FilledTonalButton(onClick = {
                        vm.consoleUrl(node, null, null, null)?.let {
                            vms.servers.navigate(Screen.Web(profile.id, it, "$node shell", isMainUi = false))
                        }
                    }) { Text("Web shell") }
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
            if (detail?.storage?.isNotEmpty() == true) {
                section("Storage")
                items(detail.storage, key = { it.storage }) { s ->
                    StorageRow(
                        "${s.storage} (${s.type})",
                        s.content.joinToString(", "),
                        s.used,
                        s.total,
                        if (s.active) "available" else "unknown",
                    )
                }
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

    val actions = buildList {
        if (status == null) return@buildList
        if (!status.isRunning) {
            add(GuestAction("start", "Start", null))
        } else {
            if (status.isPaused) {
                add(GuestAction("resume", "Resume", null))
            } else {
                add(GuestAction("shutdown", "Shut down", "Ask the guest OS to shut down cleanly."))
                add(GuestAction("reboot", "Reboot", "Ask the guest OS to reboot."))
                if (screen.type == GuestType.QEMU) add(GuestAction("suspend", "Pause", "Freeze the VM in memory."))
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
        )
        BusyBar(state.busy)
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
                                enabled = state.busy == null,
                            ) { Text(action.label) }
                        } else {
                            OutlinedButton(onClick = { confirm = action }, enabled = state.busy == null) {
                                Text(action.label, color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified)
                            }
                        }
                    }
                    FilledTonalButton(
                        onClick = {
                            vm.consoleUrl(screen.node, screen.type, screen.vmid, screen.name)?.let {
                                vms.servers.navigate(Screen.Web(profile.id, it, "Console ${screen.vmid}", isMainUi = false))
                            }
                        },
                        enabled = status?.isRunning == true,
                    ) { Text("Console") }
                    OutlinedButton(onClick = { backupDialog = true }, enabled = state.busy == null) { Text("Back up now") }
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
                            TextButton(onClick = { snapshotConfirm = "rollback" to snap.name }, enabled = state.busy == null) {
                                Text("Roll back")
                            }
                            TextButton(onClick = { snapshotConfirm = "delete" to snap.name }, enabled = state.busy == null) {
                                Text("Delete", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
            item {
                OutlinedButton(onClick = { snapshotDialog = true }, enabled = state.busy == null && detail != null) {
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
                        "To change hardware or options, use the full web interface (globe icon on the server screen).",
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
            onConfirm = { vm.guestAction(screen.node, screen.type, screen.vmid, action.action, "${action.label} $kind ${screen.vmid}") },
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
            onCreate = { name, description -> vm.createSnapshot(screen.node, screen.type, screen.vmid, name, description) },
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
}

private val snapshotNamePattern = Regex("^[A-Za-z][A-Za-z0-9_-]{0,39}$")

@Composable
private fun SnapshotDialog(onCreate: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    val valid = snapshotNamePattern.matches(name)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Take snapshot") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.trim() },
                    label = { Text("Name") },
                    placeholder = { Text("before_update") },
                    isError = name.isNotEmpty() && !valid,
                    supportingText = { Text("Letters, digits, - and _; must start with a letter") },
                    singleLine = true,
                )
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description") })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onCreate(name, description)
                onDismiss()
            }, enabled = valid) { Text("Create") }
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
        )
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
