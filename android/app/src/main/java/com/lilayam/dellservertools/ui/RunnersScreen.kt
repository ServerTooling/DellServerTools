package com.lilayam.dellservertools.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lilayam.dellservertools.core.ServerProfile
import com.lilayam.dellservertools.core.proxmox.RunnerAction
import com.lilayam.dellservertools.core.proxmox.RunnerSetting
import com.lilayam.dellservertools.core.proxmox.RunnersSettings
import com.lilayam.dellservertools.core.proxmox.SettingsGroup

private val tabs = listOf("Overview", "Actions", "Settings")

/** The pve-runners service on this Proxmox host, in place of its old web page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunnersScreen(profile: ServerProfile, vms: AppViewModels) {
    val vm = vms.runners
    val state by vm.state.collectAsStateWithLifecycle()
    val proxmox by vms.proxmox.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(0) }

    LaunchedEffect(profile, proxmox.connected) {
        if (proxmox.connected) vms.proxmox.runnersClient(profile)?.let(vm::open) else vms.servers.back()
    }
    // Follow running actions closely; otherwise keep the overview fresh.
    AutoRefresh(state.settings != null, if (state.anyRunning) 2_000 else 10_000) { vm.poll() }
    ForwardMessages(state.message, vms) { vm.clearMessage() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text("Runners", maxLines = 1)
                    val sub = listOfNotNull(state.settings?.hostId, state.status?.version).joinToString(" · ")
                    if (sub.isNotEmpty()) Text("pve-runners · $sub", style = MaterialTheme.typography.labelSmall)
                }
            },
            navigationIcon = { IconButton(onClick = { vms.servers.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { IconButton(onClick = { vm.reload() }) { Icon(Icons.Filled.Refresh, "Refresh") } },
        )
        PrimaryTabRow(selectedTabIndex = tab) {
            tabs.forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = { tab = i }, modifier = Modifier.testTag("runners-tab-$title"), text = {
                    Text(if (i == 1 && state.anyRunning) "$title •" else title)
                })
            }
        }
        if (state.loading || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())

        val settings = state.settings
        when {
            settings == null && state.error != null -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("runners-error"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.reload() }) { Text("Retry") }
                    OutlinedButton(onClick = { vms.servers.edit(profile) }) { Text("Edit server") }
                }
            }
            settings == null -> Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Connecting to pve-runners…")
            }
            tab == 0 -> OverviewTab(state, settings)
            tab == 1 -> ActionsTab(state.actions, vm)
            else -> SettingsTab(state, settings, vm)
        }
    }
}

private fun LazyListScope.heading(title: String) = item {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun OverviewTab(state: RunnersUiState, settings: RunnersSettings) {
    val status = state.status
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("runners-overview")) {
        state.statusError?.let { item { Text("Status unavailable: $it", color = MaterialTheme.colorScheme.error) } }
        if (status != null) {
            items(status.warnings) { w ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text("Warning: $w", modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${status.runners.size} / ${status.max} runners", style = MaterialTheme.typography.titleMedium)
                        LinearProgressIndicator(
                            progress = { if (status.max > 0) (status.runners.size.toFloat() / status.max).coerceAtMost(1f) else 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("Version ${status.version} · host ${status.host}", style = MaterialTheme.typography.bodySmall)
                        Text("Container storage: ${status.storage.ifBlank { "–" }}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            heading("Runners")
            if (status.runners.isEmpty()) {
                item { Text("No runners right now. They start when a job is queued.", style = MaterialTheme.typography.bodyMedium) }
            }
            items(status.runners, key = { it.id }) { r ->
                Card(Modifier.fillMaxWidth().testTag("runner-${r.id}")) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${r.id}", fontFamily = FontFamily.Monospace, modifier = Modifier.padding(end = 8.dp))
                            Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(r.status, color = if (r.status == "running") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("${r.pool} · ${r.owner} · ${r.age}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (status.pending.isNotEmpty()) {
                item { Text("Next cleanup: ${status.pending.joinToString(", ")}", style = MaterialTheme.typography.bodySmall) }
            }
        }
        heading("Orgs and accounts")
        if (settings.targets.isEmpty()) item { Text("None yet. Add one with `pve-runners setup` on the host.") }
        items(settings.targets) { t ->
            SummaryRow(t.title, t.fields.firstOrNull { it.name.endsWith(".pools") }?.value.orEmpty())
        }
        heading("Pools")
        items(settings.pools) { p ->
            fun v(key: String) = p.fields.firstOrNull { it.name.endsWith(".$key") }?.value.orEmpty()
            val warm = v("warm").takeIf { it.isNotBlank() && it != "0" }?.let { " · $it warm" }.orEmpty()
            SummaryRow(p.title.removePrefix("Pool "), "${v("cores")} cores · ${v("memory_mb")} MB · max ${v("max")}$warm")
        }
    }
}

@Composable
private fun SummaryRow(title: String, detail: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f))
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ActionsTab(actions: List<RunnerAction>, vm: RunnersViewModel) {
    var confirm by remember { mutableStateOf<Pair<RunnerAction, Map<String, String>>?>(null) }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.groupBy { it.group }.forEach { (group, inGroup) ->
            heading(group)
            items(inGroup, key = { it.name }) { a ->
                ActionCard(a) { args -> if (a.confirm.isNotBlank()) confirm = a to args else vm.runAction(a.name, args) }
            }
        }
    }
    confirm?.let { (a, args) ->
        ConfirmDialog(a.label, a.confirm, "Yes, run", onConfirm = { vm.runAction(a.name, args) }, onDismiss = { confirm = null })
    }
}

@Composable
private fun ActionCard(a: RunnerAction, onRun: (Map<String, String>) -> Unit) {
    val args = remember(a.name) { mutableStateMapOf<String, String>() }
    val running = a.last?.running == true
    Card(Modifier.fillMaxWidth().testTag("action-${a.name}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(a.label, style = MaterialTheme.typography.titleSmall)
                    if (a.help.isNotBlank()) Text(a.help, style = MaterialTheme.typography.bodySmall)
                }
                FilledTonalButton(
                    onClick = { onRun(a.args.associateWith { args[it].orEmpty().trim() }) },
                    enabled = !running,
                    modifier = Modifier.testTag("run-${a.name}"),
                ) { Text("Run") }
            }
            a.args.forEach { name ->
                OutlinedTextField(
                    value = args[name].orEmpty(), onValueChange = { args[name] = it }, label = { Text(name) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("arg-${a.name}-$name"),
                )
            }
            a.last?.let { run ->
                val label = when {
                    run.running -> "Running since ${run.started}…"
                    run.error.isNotBlank() -> "Failed, ran ${run.started}–${run.ended}"
                    else -> "Done, ran ${run.started}–${run.ended}"
                }
                Text(label, style = MaterialTheme.typography.labelMedium,
                    color = if (run.error.isNotBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                val text = run.output + if (run.error.isNotBlank()) "error: ${run.error}" else ""
                if (text.isNotBlank()) {
                    Text(
                        text.trimEnd(), fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("output-${a.name}"),
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsTab(state: RunnersUiState, settings: RunnersSettings, vm: RunnersViewModel) {
    val open = remember { mutableStateMapOf<String, Boolean>() }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f).testTag("runners-settings"),
        ) {
            state.saveError?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("save-error")) } }
            heading("Orgs and accounts")
            item { Text("Adding an org needs GitHub in a browser: run `pve-runners setup` on the host.", style = MaterialTheme.typography.bodySmall) }
            items(settings.targets, key = { it.id }) { g -> GroupCard(g, state, vm, open) }
            heading("Pools")
            items(settings.pools, key = { it.id }) { g -> GroupCard(g, state, vm, open) }
            item {
                OutlinedTextField(
                    value = state.newPool, onValueChange = vm::setNewPool, singleLine = true,
                    label = { Text("New pool name (optional)") },
                    supportingText = { Text("Starts from the published image with its name as the label; edit it after saving.") },
                    modifier = Modifier.fillMaxWidth().testTag("field-new_pool"),
                )
            }
            heading("Host")
            items(settings.sections, key = { it.id }) { g -> GroupCard(g, state, vm, open) }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (state.dirty) "Unsaved changes. Saving restarts the service." else "Saving restarts the service.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
            )
            if (state.dirty) OutlinedButton(onClick = vm::discardEdits) { Text("Undo") }
            Button(onClick = vm::save, enabled = state.dirty && !state.saving, modifier = Modifier.testTag("runners-save")) { Text("Save") }
        }
    }
}

@Composable
private fun GroupCard(g: SettingsGroup, state: RunnersUiState, vm: RunnersViewModel, open: MutableMap<String, Boolean>) {
    val expanded = open[g.id] == true
    val changed = g.fields.any { it.name in state.edits || "${it.name}_clear" in state.edits }
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { open[g.id] = !expanded }.padding(12.dp).testTag("group-${g.id}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(g.title + if (changed) " •" else "", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (expanded) "Collapse" else "Expand")
        }
        if (expanded) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                g.fields.forEach { f -> SettingField(f, state.edits, vm) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingField(f: RunnerSetting, edits: Map<String, String>, vm: RunnersViewModel) {
    val value = edits[f.name] ?: if (f.kind == "secret") "" else f.value
    val help: (@Composable () -> Unit)? = f.help.takeIf { it.isNotBlank() }?.let { { Text(it) } }
    val tag = Modifier.testTag("field-${f.name}")
    when (f.kind) {
        "bool" -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(f.label)
                if (f.help.isNotBlank()) Text(f.help, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = value == "true", onCheckedChange = { vm.edit(f.name, it.toString()) }, modifier = tag)
        }
        "select" -> Column {
            Text(f.label, style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().then(tag)) {
                f.options.forEachIndexed { i, option ->
                    SegmentedButton(selected = value == option, onClick = { vm.edit(f.name, option) },
                        shape = SegmentedButtonDefaults.itemShape(i, f.options.size)) { Text(option) }
                }
            }
            if (f.help.isNotBlank()) Text(f.help, style = MaterialTheme.typography.bodySmall)
        }
        "secret" -> Column {
            val clear = edits["${f.name}_clear"] == "true"
            OutlinedTextField(
                value = value, onValueChange = { vm.edit(f.name, it) }, label = { Text(f.label) }, singleLine = true,
                enabled = !clear, supportingText = help,
                placeholder = { Text(if (f.set) "Set; leave empty to keep" else "Not set") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().then(tag),
            )
            if (f.set) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = clear, onCheckedChange = { vm.edit("${f.name}_clear", it.toString()) },
                        modifier = Modifier.testTag("field-${f.name}_clear"))
                    Text("Remove it")
                }
            }
        }
        else -> OutlinedTextField(
            value = value, onValueChange = { vm.edit(f.name, it) }, label = { Text(f.label) }, singleLine = true,
            supportingText = help,
            keyboardOptions = KeyboardOptions(keyboardType = if (f.kind == "number") KeyboardType.Number else KeyboardType.Text),
            modifier = Modifier.fillMaxWidth().then(tag),
        )
    }
}
