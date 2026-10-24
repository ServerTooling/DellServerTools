package com.lilayam.dellservertools.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lilayam.dellservertools.core.ServerProfile
import com.lilayam.dellservertools.core.proxmox.NetworkForm
import com.lilayam.dellservertools.core.proxmox.NetworkInterface
import com.lilayam.dellservertools.core.proxmox.NodeSystemJson

private enum class SystemTab(val label: String) {
    NETWORK("Network"), DNS("DNS & time"), HOSTS("Hosts"), SERVICES("Services"), UPDATES("Updates"), REPOS("Repositories"), SYSLOG("Syslog")
}

/** A node's System, Network and Updates panels of the web UI, one tab at a time. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeSystemScreen(profile: ServerProfile, screen: Screen.NodeSystem, vms: AppViewModels) {
    val vm = vms.proxmox
    val state by vm.state.collectAsStateWithLifecycle()
    ForwardMessages(state.message, vms) { vm.clearMessage() }
    var selected by remember { mutableIntStateOf(0) }
    var version by remember { mutableIntStateOf(0) }
    val reload: () -> Unit = { version++ }
    val busy = state.busy != null
    val node = screen.node

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Node $node system") },
            navigationIcon = { IconButton(onClick = { vms.servers.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { IconButton(onClick = { reload() }) { Icon(Icons.Filled.Refresh, "Refresh") } },
        )
        BusyBar(state.busy, onStop = state.busyTask?.let { (n, upid) -> { vm.stopTask(n, upid) } })
        ScrollableTabRow(selectedTabIndex = selected, edgePadding = 8.dp) {
            SystemTab.entries.forEachIndexed { i, tab ->
                Tab(
                    selected = selected == i,
                    onClick = { selected = i },
                    text = { Text(tab.label) },
                    modifier = Modifier.testTag("sys-tab-${tab.name.lowercase()}"),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            when (SystemTab.entries[selected]) {
                SystemTab.NETWORK -> NetworkTab(node, vms, version, busy, reload)
                SystemTab.DNS -> DnsTab(node, vms, version, busy, reload)
                SystemTab.HOSTS -> HostsTab(node, vms, version, busy, reload)
                SystemTab.SERVICES -> ServicesTab(node, vms, version, busy, reload)
                SystemTab.UPDATES -> UpdatesTab(profile, node, vms, version, busy, reload)
                SystemTab.REPOS -> ReposTab(node, vms, version, busy, reload)
                SystemTab.SYSLOG -> SyslogTab(node, vms, version)
            }
        }
    }
}

// ---------------------------------------------------------------- network

@Composable
private fun NetworkTab(node: String, vms: AppViewModels, version: Int, busy: Boolean, reload: () -> Unit) {
    val vm = vms.proxmox
    val (ifaces, error) = rememberLoaded(vms, version) { it.networkInterfaces(node) }
    var editing by remember { mutableStateOf<NetworkForm?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var confirmApply by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(false) }
    val list = ifaces.orEmpty()
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                "Changes are saved as pending and only take effect when you apply them. A wrong address or bridge can cut " +
                    "the connection to this server, so check twice before applying from afar.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { confirmApply = true }, enabled = !busy, modifier = Modifier.testTag("net-apply")) {
                    Text(if (pending) "Apply pending changes" else "Apply configuration")
                }
                OutlinedButton(
                    onClick = { vm.change("Revert network changes", { pending = false; reload() }) { it.revertNetwork(node) } },
                    enabled = !busy,
                    modifier = Modifier.testTag("net-revert"),
                ) { Text("Revert") }
            }
        }
        loading(ifaces, error)
        items(list, key = { "iface-${it.iface}" }) { i ->
            Card(Modifier.fillMaxWidth().clickable { editing = NetworkForm.fromInterface(i) }.testTag("net-${i.iface}")) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(if (i.active) Green else Amber)
                        Text("  ${i.iface}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(i.type, style = MaterialTheme.typography.labelSmall)
                    }
                    listOfNotNull(
                        i.cidr.ifEmpty { null },
                        i.gateway.ifEmpty { null }?.let { "gw $it" },
                        i.cidr6.ifEmpty { null },
                        i.bridgePorts.ifEmpty { null }?.let { "ports $it" },
                        i.slaves.ifEmpty { null }?.let { "slaves $it (${i.bondMode})" },
                        i.vlanRawDevice.ifEmpty { null }?.let { "on $it tag ${i.vlanId}" },
                        if (i.vlanAware) "VLAN aware" else null,
                        if (!i.autostart) "no autostart" else null,
                    ).joinToString(" · ").takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (i.comments.isNotBlank()) Text(i.comments, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { editing = NetworkForm(NetworkForm.nextName("vmbr", list), "bridge") },
                    enabled = !busy && ifaces != null,
                    modifier = Modifier.testTag("net-add-bridge"),
                ) { Text("Add bridge") }
                OutlinedButton(
                    onClick = { editing = NetworkForm(NetworkForm.nextName("bond", list), "bond", autostart = true) },
                    enabled = !busy && ifaces != null,
                ) { Text("Add bond") }
                OutlinedButton(
                    onClick = { editing = NetworkForm(NetworkForm.nextName("vlan", list), "vlan") },
                    enabled = !busy && ifaces != null,
                ) { Text("Add VLAN") }
            }
        }
    }
    editing?.let { form ->
        NetworkDialog(form, list, onDismiss = { editing = null }, onDelete = { deleting = form.iface; editing = null }) { saved ->
            val label = if (saved.isNew) "Add ${saved.iface}" else "Update ${saved.iface}"
            vm.change(label, { pending = true; reload() }) { c -> if (saved.isNew) c.createNetwork(node, saved) else c.updateNetwork(node, saved) }
            editing = null
        }
    }
    deleting?.let { iface ->
        ConfirmDialog(
            title = "Remove $iface?",
            text = "Pending until you apply. Guests on it lose their network then.",
            confirmLabel = "Remove",
            onConfirm = { vm.change("Remove $iface", { pending = true; reload() }) { it.deleteNetwork(node, iface) } },
            onDismiss = { deleting = null },
        )
    }
    if (confirmApply) {
        ConfirmDialog(
            title = "Apply the network configuration?",
            text = "The node reloads its interfaces now. If an address or bridge is wrong, you lose this connection and need " +
                "the iDRAC console to fix it.",
            confirmLabel = "Apply",
            onConfirm = { vm.task("Apply network configuration", node, { pending = false; reload() }) { it.applyNetwork(node) } },
            onDismiss = { confirmApply = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NetworkDialog(
    initial: NetworkForm,
    existing: List<NetworkInterface>,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onSave: (NetworkForm) -> Unit,
) {
    var f by remember { mutableStateOf(initial) }
    val problem = f.validate()
    val nics = existing.filter { it.type == "eth" || it.type == "bond" }.map { it.iface }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (f.isNew) "Add ${f.type}" else "${f.iface} (${f.type})") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (f.isNew) RuleField("Name", f.iface, "net-name") { f = f.copy(iface = it) }
                RuleField("IPv4/CIDR", f.cidr, "net-cidr") { f = f.copy(cidr = it) }
                RuleField("Gateway (IPv4)", f.gateway, "net-gateway") { f = f.copy(gateway = it) }
                RuleField("IPv6/CIDR", f.cidr6, "net-cidr6") { f = f.copy(cidr6 = it) }
                RuleField("Gateway (IPv6)", f.gateway6, "net-gateway6") { f = f.copy(gateway6 = it) }
                when (f.type) {
                    "bridge" -> {
                        PortsField("Bridge ports", f.bridgePorts, nics, "net-ports") { f = f.copy(bridgePorts = it) }
                        CheckRow("VLAN aware", f.vlanAware, Modifier.testTag("net-vlan-aware")) { f = f.copy(vlanAware = it) }
                    }
                    "bond" -> {
                        PortsField("Slaves", f.slaves, nics.filter { it != f.iface }, "net-slaves") { f = f.copy(slaves = it) }
                        Dropdown("Mode", NetworkForm.BOND_MODES.map { it to it }, f.bondMode) { f = f.copy(bondMode = it) }
                    }
                    "vlan" -> if (f.iface.startsWith("vlan")) {
                        ChipRow(nics.map { it to it }, f.vlanRawDevice, "net-raw") { f = f.copy(vlanRawDevice = it) }
                        RuleField("VLAN tag", f.vlanId, "net-vlan-id") { f = f.copy(vlanId = it.filter(Char::isDigit)) }
                    }
                }
                RuleField("MTU (optional)", f.mtu, "net-mtu") { f = f.copy(mtu = it.filter(Char::isDigit)) }
                CheckRow("Autostart", f.autostart, Modifier.testTag("net-autostart")) { f = f.copy(autostart = it) }
                OutlinedTextField(
                    value = f.comments,
                    onValueChange = { f = f.copy(comments = it) },
                    label = { Text("Comment") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (!f.isNew && !initial.current!!.isPhysical) {
                    TextButton(onClick = onDelete, modifier = Modifier.testTag("net-delete")) {
                        Text("Remove ${f.iface}", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(f) }, enabled = problem == null, modifier = Modifier.testTag("net-save")) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Space-separated interface names, with the node's NICs as chips to tap instead of typing. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PortsField(label: String, value: String, choices: List<String>, tag: String, onChange: (String) -> Unit) {
    val current = value.split(' ').filter { it.isNotBlank() }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        choices.forEach { nic ->
            androidx.compose.material3.FilterChip(
                selected = nic in current,
                onClick = { onChange((if (nic in current) current - nic else current + nic).joinToString(" ")) },
                label = { Text(nic) },
                modifier = Modifier.testTag("$tag-$nic"),
            )
        }
    }
}

// ---------------------------------------------------------------- DNS and time

@Composable
private fun DnsTab(node: String, vms: AppViewModels, version: Int, busy: Boolean, reload: () -> Unit) {
    val vm = vms.proxmox
    val (loaded, error) = rememberLoaded(vms, version) { c -> c.dns(node) to c.timezone(node) }
    var dns by remember(loaded) { mutableStateOf(loaded?.first) }
    var zone by remember(loaded) { mutableStateOf(loaded?.second.orEmpty()) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val d = dns
        if (d == null) {
            Text(error ?: "Loading…")
            return@Column
        }
        Text("DNS", style = MaterialTheme.typography.titleSmall)
        RuleField("Search domain", d.search, "dns-search") { dns = d.copy(search = it) }
        (0 until 3).forEach { i ->
            RuleField("DNS server ${i + 1}", d.servers.getOrElse(i) { "" }, "dns-server-${i + 1}") { v ->
                val servers = (0 until 3).map { j -> if (j == i) v else d.servers.getOrElse(j) { "" } }
                dns = d.copy(servers = servers.dropLastWhile { it.isBlank() })
            }
        }
        val problem = d.validate()
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button(
            onClick = { vm.change("Save DNS", reload) { it.setDns(node, d) } },
            enabled = problem == null && !busy && d != loaded?.first,
            modifier = Modifier.fillMaxWidth().testTag("dns-save"),
        ) { Text("Save DNS") }

        Text("Time zone", style = MaterialTheme.typography.titleSmall)
        RuleField("Time zone", zone, "time-zone") { zone = it }
        val zones = remember { NodeSystemJson.timezones() }
        val matches = if (zone.length >= 2) zones.filter { it.contains(zone, ignoreCase = true) && it != zone }.take(6) else emptyList()
        matches.forEach { z -> TextButton(onClick = { zone = z }) { Text(z) } }
        Button(
            onClick = { vm.change("Set time zone $zone", reload) { it.setTimezone(node, zone) } },
            enabled = !busy && zone in zones && zone != loaded?.second,
            modifier = Modifier.fillMaxWidth().testTag("time-save"),
        ) { Text("Set time zone") }
    }
}

// ---------------------------------------------------------------- hosts

@Composable
private fun HostsTab(node: String, vms: AppViewModels, version: Int, busy: Boolean, reload: () -> Unit) {
    val (loaded, error) = rememberLoaded(vms, version) { it.hosts(node) }
    var text by remember(loaded) { mutableStateOf(loaded?.first.orEmpty()) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (loaded == null) {
            Text(error ?: "Loading…")
            return@Column
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("/etc/hosts") },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp).testTag("hosts-text"),
        )
        Button(
            onClick = { vms.proxmox.change("Save /etc/hosts", reload) { it.setHosts(node, text, loaded.second) } },
            enabled = !busy && text != loaded.first,
            modifier = Modifier.fillMaxWidth().testTag("hosts-save"),
        ) { Text("Save") }
    }
}

// ---------------------------------------------------------------- services

@Composable
private fun ServicesTab(node: String, vms: AppViewModels, version: Int, busy: Boolean, reload: () -> Unit) {
    val (services, error) = rememberLoaded(vms, version) { it.services(node) }
    var confirm by remember { mutableStateOf<Pair<String, String>?>(null) } // service, action
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        loading(services, error)
        items(services.orEmpty(), key = { "svc-${it.name}" }) { s ->
            Card(Modifier.fillMaxWidth().testTag("svc-${s.name}")) {
                Column(Modifier.padding(start = 12.dp, top = 8.dp, end = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(if (s.running) Green else if (s.state == "dead" || s.state == "stopped") Red else Amber)
                        Text("  ${s.name}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(s.state, style = MaterialTheme.typography.labelSmall)
                    }
                    if (s.description.isNotBlank()) Text(s.description, style = MaterialTheme.typography.bodySmall)
                    Row {
                        listOf("start" to "Start", "stop" to "Stop", "restart" to "Restart").forEach { (action, label) ->
                            val enabled = !busy && (action == "start") != s.running
                            TextButton(
                                onClick = { confirm = s.name to action },
                                enabled = enabled,
                                modifier = Modifier.testTag("svc-$action-${s.name}"),
                            ) { Text(label) }
                        }
                    }
                }
            }
        }
    }
    confirm?.let { (service, action) ->
        ConfirmDialog(
            title = "${action.replaceFirstChar { it.uppercase() }} $service?",
            text = if (service in CRITICAL_SERVICES) {
                "The app talks to the node through $service: the connection drops for a moment."
            } else {
                "Runs systemctl $action $service on the node."
            },
            confirmLabel = action.replaceFirstChar { it.uppercase() },
            onConfirm = { vms.proxmox.task("${action.replaceFirstChar { it.uppercase() }} $service", node, reload) { it.serviceAction(node, service, action) } },
            onDismiss = { confirm = null },
        )
    }
}

private val CRITICAL_SERVICES = setOf("pveproxy", "pvedaemon", "sshd")

// ---------------------------------------------------------------- updates

@Composable
private fun UpdatesTab(profile: ServerProfile, node: String, vms: AppViewModels, version: Int, busy: Boolean, reload: () -> Unit) {
    val (updates, error) = rememberLoaded(vms, version) { it.pendingUpdates(node) }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { vms.proxmox.task("Refresh package lists", node, reload) { it.refreshUpdates(node) } },
                    enabled = !busy,
                    modifier = Modifier.testTag("apt-refresh"),
                ) { Text("Refresh") }
                // The web UI also upgrades in a terminal: apt may ask questions.
                OutlinedButton(
                    onClick = { vms.servers.openShell(profile, "apt-get dist-upgrade") },
                    enabled = updates?.isNotEmpty() == true,
                    modifier = Modifier.testTag("apt-upgrade"),
                ) { Text("Upgrade in shell") }
            }
        }
        loading(updates, error)
        if (updates?.isEmpty() == true) item { Text("Up to date. Refresh to check again.") }
        items(updates.orEmpty(), key = { "pkg-${it.name}" }) { p ->
            Column(Modifier.fillMaxWidth().testTag("pkg-${p.name}")) {
                Text(p.name, fontWeight = FontWeight.SemiBold)
                Text("${p.oldVersion} → ${p.newVersion}", style = MaterialTheme.typography.bodySmall)
                if (p.title.isNotBlank()) Text(p.title, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ---------------------------------------------------------------- repositories

@Composable
private fun ReposTab(node: String, vms: AppViewModels, version: Int, busy: Boolean, reload: () -> Unit) {
    val vm = vms.proxmox
    val (repos, error) = rememberLoaded(vms, version) { it.aptRepositories(node) }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        loading(repos, error)
        val r = repos ?: return@LazyColumn
        items(r.entries, key = { "repo-${it.path}-${it.index}" }) { e ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.line, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        Text("${e.path}${if (e.comment.isNotBlank()) " · ${e.comment}" else ""}", style = MaterialTheme.typography.labelSmall)
                    }
                    Switch(
                        checked = e.enabled,
                        onCheckedChange = { on ->
                            vm.change("${if (on) "Enable" else "Disable"} repository", reload) { it.setAptRepositoryEnabled(node, e, on, r.digest) }
                        },
                        enabled = !busy,
                        modifier = Modifier.testTag("repo-${e.path.substringAfterLast('/')}-${e.index}"),
                    )
                }
            }
        }
        val addable = r.standard.filter { it.enabled == null }
        if (addable.isNotEmpty()) {
            item { Text("Add a standard repository", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
            items(addable, key = { "std-${it.handle}" }) { s ->
                OutlinedButton(
                    onClick = { vm.change("Add ${s.name}", reload) { it.addStandardRepository(node, s.handle, r.digest) } },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("repo-add-${s.handle}"),
                ) { Text(s.name) }
            }
        }
    }
}

// ---------------------------------------------------------------- syslog

@Composable
private fun SyslogTab(node: String, vms: AppViewModels, version: Int) {
    val (lines, error) = rememberLoaded(vms, version) { it.journal(node) }
    LazyColumn(contentPadding = PaddingValues(8.dp)) {
        loading(lines, error)
        items(lines.orEmpty()) { line -> Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
    }
}
