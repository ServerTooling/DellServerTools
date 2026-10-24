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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lilayam.dellservertools.core.proxmox.BackupJob
import com.lilayam.dellservertools.core.proxmox.BackupJobForm
import com.lilayam.dellservertools.core.proxmox.BackupSelection
import com.lilayam.dellservertools.core.proxmox.PruneOptions
import com.lilayam.dellservertools.core.proxmox.UnprotectedGuest
import kotlinx.coroutines.launch

/** Datacenter > Backup: the scheduled jobs, Run now, and the guests no job covers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupJobsScreen(screen: Screen.BackupJobs, vms: AppViewModels) {
    val vm = vms.proxmox
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    ForwardMessages(state.message, vms) { vm.clearMessage() }

    var jobs by remember { mutableStateOf<List<BackupJob>?>(null) }
    var unprotected by remember { mutableStateOf<List<UnprotectedGuest>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    fun reload() = scope.launch {
        vm.fetch { it.backupJobs() }.onSuccess { jobs = it; loadError = null }.onFailure { loadError = it.message }
        vm.fetch { it.notBackedUp() }.onSuccess { unprotected = it }
    }
    LaunchedEffect(state.connected) { if (state.connected) reload() }
    // A finished Run now or save brings the list (next run, last state) up to date.
    LaunchedEffect(state.busy) { if (state.busy == null && state.connected) reload() }
    val firstNode = state.resources.firstOrNull { it.type == "node" }?.node

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Backup jobs") },
            navigationIcon = { IconButton(onClick = { vms.servers.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(
                    onClick = { vms.servers.navigate(Screen.BackupJobEdit(screen.profileId, null)) },
                    modifier = Modifier.testTag("backup-add"),
                ) { Icon(Icons.Filled.Add, "Add backup job") }
            },
        )
        BusyBar(state.busy, onStop = state.busyTask?.let { (n, upid) -> { vm.stopTask(n, upid) } })
        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            val list = jobs
            when {
                list == null -> item { Text(loadError ?: "Loading…") }
                list.isEmpty() -> item { Text("No backup jobs. Add one to back up guests on a schedule.") }
            }
            items(list.orEmpty(), key = { it.id }) { job ->
                val f = job.form
                Card(
                    Modifier.fillMaxWidth()
                        .clickable { vms.servers.navigate(Screen.BackupJobEdit(screen.profileId, job.id)) }
                        .testTag("backup-job-${job.id}"),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(scheduleLabel(f.schedule), fontWeight = FontWeight.SemiBold)
                        Text("${selectionLabel(f)} → ${f.storage}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            listOfNotNull(
                                BackupJobForm.MODES.firstOrNull { it.first == f.mode }?.second ?: f.mode,
                                BackupJobForm.COMPRESSIONS.firstOrNull { it.first == f.compress }?.second,
                                f.node.ifBlank { null }?.let { "node $it" },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            when {
                                !f.enabled -> "Disabled"
                                job.nextRun != null -> "Next run ${formatStorageTime(job.nextRun)}"
                                else -> "Enabled"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (f.enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        )
                        if (f.comment.isNotBlank()) Text(f.comment, style = MaterialTheme.typography.bodySmall)
                        val node = f.node.ifBlank { null } ?: firstNode
                        TextButton(
                            onClick = { node?.let { vm.runBackupJob(it, f) } },
                            enabled = node != null && state.busy == null,
                            modifier = Modifier.testTag("backup-run-${job.id}"),
                        ) { Text("Run now") }
                    }
                }
            }
            item {
                Text(
                    "Not backed up",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (jobs != null && unprotected.isEmpty()) {
                item { Text("Every guest is in an enabled job.", style = MaterialTheme.typography.bodySmall) }
            }
            items(unprotected, key = { "unprotected-${it.vmid}" }) { g ->
                Text(
                    "${g.vmid} · ${g.name.ifBlank { "?" }} (${if (g.type == "lxc") "CT" else "VM"})",
                    modifier = Modifier.testTag("unprotected-${g.vmid}"),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BackupJobFormScreen(screen: Screen.BackupJobEdit, vms: AppViewModels) {
    val vm = vms.proxmox
    val state by vm.state.collectAsStateWithLifecycle()
    ForwardMessages(state.message, vms) { vm.clearMessage() }

    var form by remember { mutableStateOf(if (screen.id == null) BackupJobForm() else null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var storages by remember { mutableStateOf<List<String>>(emptyList()) }
    var confirmRemove by remember { mutableStateOf(false) }
    val nodes = state.resources.filter { it.type == "node" }.map { it.node }
    val guests = state.resources.filter { it.guestType != null && it.vmid != null && !it.template }.sortedBy { it.vmid }

    LaunchedEffect(screen, state.connected) {
        val id = screen.id ?: return@LaunchedEffect
        if (form != null) return@LaunchedEffect
        vm.fetch { c -> c.backupJobs().firstOrNull { it.id == id } }
            .onSuccess { job -> if (job == null) loadError = "Job $id no longer exists." else form = job.form }
            .onFailure { loadError = it.message }
    }
    LaunchedEffect(nodes.firstOrNull()) {
        val node = nodes.firstOrNull() ?: return@LaunchedEffect
        vm.fetch { c -> c.storage(node).filter { it.holds("backup") }.map { it.storage } }.onSuccess { found ->
            storages = found
            // A new job starts on the first storage that takes backups.
            form?.let { f -> if (f.isNew && f.storage.isEmpty() && found.isNotEmpty()) form = f.copy(storage = found.first()) }
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = { Text(if (screen.id == null) "Add backup job" else "Backup job ${screen.id}") },
            navigationIcon = { IconButton(onClick = { vms.servers.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        BusyBar(state.busy)
        val f = form
        if (f == null) {
            Text(loadError ?: "Loading…", modifier = Modifier.padding(16.dp))
            return@Column
        }
        val problem = f.validate()
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CheckRow("Enabled", f.enabled, Modifier.testTag("backup-enabled")) { form = f.copy(enabled = it) }

            OutlinedTextField(
                value = f.schedule,
                onValueChange = { form = f.copy(schedule = it) },
                label = { Text("Schedule") },
                supportingText = { Text(scheduleLabel(f.schedule)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("backup-schedule"),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BackupJobForm.SCHEDULE_PRESETS.forEachIndexed { i, (value, label) ->
                    SuggestionChip(
                        onClick = { form = f.copy(schedule = value) },
                        label = { Text(label) },
                        modifier = Modifier.testTag("backup-preset-$i"),
                    )
                }
            }

            Text("Storage", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (storages + listOf(f.storage).filter { it.isNotEmpty() && it !in storages }).forEach { s ->
                    FilterChip(
                        selected = f.storage == s,
                        onClick = { form = f.copy(storage = s) },
                        label = { Text(s) },
                        modifier = Modifier.testTag("backup-storage-$s"),
                    )
                }
            }
            if (nodes.size > 1) {
                Dropdown("Node", listOf("" to "All nodes") + nodes.map { it to it }, f.node) { form = f.copy(node = it) }
            }

            Text("Guests", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BackupSelection.entries.forEach { sel ->
                    FilterChip(
                        selected = f.selection == sel,
                        onClick = { form = f.copy(selection = sel) },
                        label = { Text(sel.label) },
                        modifier = Modifier.testTag("backup-selection-${sel.name}"),
                    )
                }
            }
            if (f.selection != BackupSelection.ALL) {
                guests.forEach { g ->
                    val id = g.vmid ?: return@forEach
                    CheckRow("$id · ${g.name}", id in f.vmids, Modifier.testTag("backup-guest-$id")) { on ->
                        form = f.copy(vmids = if (on) f.vmids + id else f.vmids - id)
                    }
                }
            }

            Text("Mode", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BackupJobForm.MODES.forEach { (value, label) ->
                    FilterChip(
                        selected = f.mode == value,
                        onClick = { form = f.copy(mode = value) },
                        label = { Text(label) },
                        modifier = Modifier.testTag("backup-mode-$value"),
                    )
                }
            }
            Text("Compression", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BackupJobForm.COMPRESSIONS.forEach { (value, label) ->
                    FilterChip(
                        selected = f.compress == value,
                        onClick = { form = f.copy(compress = value) },
                        label = { Text(label) },
                        modifier = Modifier.testTag("backup-compress-$value"),
                    )
                }
            }

            CheckRow("Own retention (otherwise the storage's)", f.prune != null, Modifier.testTag("backup-own-retention")) { on ->
                form = f.copy(prune = if (on) PruneOptions(keepLast = 3) else null)
            }
            f.prune?.let { prune ->
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val counts = prune.counts.toMap()
                    PRUNE_LABELS.forEach { (key, label) ->
                        NumField(
                            counts[key]?.toString().orEmpty(),
                            { v -> form = f.copy(prune = prune.withCount(key, v.filter(Char::isDigit).take(4).toIntOrNull())) },
                            label,
                            Modifier.width(110.dp).testTag("backup-$key"),
                        )
                    }
                }
            }

            OutlinedTextField(
                value = f.notesTemplate,
                onValueChange = { form = f.copy(notesTemplate = it) },
                label = { Text("Notes ({{guestname}}, {{vmid}}, {{node}}, {{cluster}})") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("backup-notes"),
            )
            OutlinedTextField(
                value = f.mailto,
                onValueChange = { form = f.copy(mailto = it) },
                label = { Text("Mail to (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("backup-mailto"),
            )
            if (f.mailto.isNotBlank()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("always" to "Always", "failure" to "On failure only").forEach { (value, label) ->
                        FilterChip(
                            selected = f.mailNotification == value,
                            onClick = { form = f.copy(mailNotification = value) },
                            label = { Text(label) },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = f.comment,
                onValueChange = { form = f.copy(comment = it) },
                label = { Text("Comment") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("backup-comment"),
            )

            problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button(
                onClick = { vm.saveBackupJob(f) { vms.servers.back() } },
                enabled = problem == null && state.busy == null,
                modifier = Modifier.fillMaxWidth().testTag("backup-save"),
            ) { Text(if (f.isNew) "Add job" else "Save") }
            if (!f.isNew) {
                OutlinedButton(
                    onClick = { confirmRemove = true },
                    enabled = state.busy == null,
                    modifier = Modifier.fillMaxWidth().testTag("backup-remove"),
                ) { Text("Remove job", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (confirmRemove) {
        val id = form?.id.orEmpty()
        ConfirmDialog(
            title = "Remove backup job $id?",
            text = "The schedule goes away. Backups it already made stay on the storage.",
            confirmLabel = "Remove",
            onConfirm = { vm.deleteBackupJob(id) { vms.servers.back() } },
            onDismiss = { confirmRemove = false },
        )
    }
}

private fun selectionLabel(f: BackupJobForm): String {
    val ids = f.vmids.sorted().joinToString(", ")
    return when (f.selection) {
        BackupSelection.ALL -> "All guests"
        BackupSelection.INCLUDE -> "Guests $ids"
        BackupSelection.EXCLUDE -> "All guests except $ids"
    }
}

/** A readable form of the presets; anything else is shown as Proxmox writes it. */
private fun scheduleLabel(schedule: String): String =
    BackupJobForm.SCHEDULE_PRESETS.firstOrNull { it.first == schedule.trim() }?.second ?: schedule.trim()
