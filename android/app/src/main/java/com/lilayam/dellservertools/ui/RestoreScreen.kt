package com.lilayam.dellservertools.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lilayam.dellservertools.core.proxmox.Format
import com.lilayam.dellservertools.core.proxmox.GuestType
import com.lilayam.dellservertools.core.proxmox.RestoreRequest
import com.lilayam.dellservertools.core.proxmox.StorageVolume
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreScreen(screen: Screen.Restore, vms: AppViewModels) {
    val vm = vms.proxmox
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(screen.node, state.connected) {
        vm.loadBackups(screen.node)
        vm.loadCreateOptions(screen.node)
    }
    ForwardMessages(state.message, vms) { vm.clearMessage() }

    val list = state.backups?.takeIf { it.node == screen.node }
    val backups = list?.volumes.orEmpty()
        .filter { RestoreRequest.typeOf(it) != null }
        .filter { screen.vmid == null || it.vmid == screen.vmid }
    var selectedId by remember { mutableStateOf(screen.volid) }
    val selected = backups.firstOrNull { it.volid == selectedId }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = { Text(if (screen.vmid != null) "Restore ${screen.vmid}" else "Restore backup") },
            navigationIcon = {
                IconButton(onClick = { if (selected != null && screen.volid == null) selectedId = null else vms.servers.back() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
            },
        )
        BusyBar(state.busy)
        if (selected != null) {
            RestoreForm(screen, selected, vms)
            return@Column
        }
        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            list?.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            when {
                list == null || list.loading -> item { Text("Loading backups…") }
                backups.isEmpty() -> item {
                    Text(
                        if (screen.vmid != null) "No backups of ${screen.vmid} on ${screen.node}." else "No backups on ${screen.node}.",
                    )
                }
                else -> item { Text("Pick the backup to restore.", style = MaterialTheme.typography.bodySmall) }
            }
            items(backups, key = { it.volid }) { b ->
                BackupCard(b, Modifier.clickable { selectedId = b.volid }.testTag("backup-${b.name}"))
            }
        }
    }
}

@Composable
private fun BackupCard(b: StorageVolume, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val type = RestoreRequest.typeOf(b)
                Text(
                    listOfNotNull(type?.label, b.vmid?.toString()).joinToString(" ") +
                        b.ctime?.let { " · ${formatBackupTime(it)}" }.orEmpty(),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(Format.bytes(b.size), style = MaterialTheme.typography.labelSmall)
            }
            if (b.notes.isNotEmpty()) Text(b.notes, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            Text(
                listOfNotNull(b.volid.substringBefore(':'), "protected".takeIf { b.protected }).joinToString(" · ") + " · " + b.name,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RestoreForm(screen: Screen.Restore, backup: StorageVolume, vms: AppViewModels) {
    val vm = vms.proxmox
    val state by vm.state.collectAsStateWithLifecycle()
    val type = RestoreRequest.typeOf(backup) ?: return
    val isCt = type == GuestType.LXC
    val opts = state.createOptions?.takeIf { it.node == screen.node && !it.loading }
    val storages = if (isCt) opts?.ctStorages.orEmpty() else opts?.vmStorages.orEmpty()

    // The config inside the backup: its name, and whether a container was unprivileged.
    var config by remember(backup.volid) { mutableStateOf<Map<String, String>?>(null) }
    LaunchedEffect(backup.volid) { config = vm.backupConfig(screen.node, backup.volid) }

    // From a guest's screen the usual restore replaces that guest; elsewhere a free id is the safe default.
    var vmid by remember(backup.volid) { mutableStateOf(screen.vmid?.toString().orEmpty()) }
    LaunchedEffect(opts) { if (vmid.isBlank() && opts != null) vmid = opts.nextVmid.toString() }
    var storage by remember(backup.volid) { mutableStateOf("") }
    var unprivileged by remember(backup.volid) { mutableStateOf(true) }
    LaunchedEffect(config) { config?.get("unprivileged")?.let { unprivileged = it == "1" } }
    var start by remember(backup.volid) { mutableStateOf(false) }
    var overwrite by remember(backup.volid) { mutableStateOf(false) }
    var confirmOverwrite by remember { mutableStateOf(false) }

    val target = vmid.toIntOrNull() ?: 0
    val existing = state.resources.firstOrNull { it.vmid == target && it.guestType != null }
    // New MACs by default for a copy next to the original; an overwritten guest keeps its MACs (and DHCP leases).
    var unique by remember(backup.volid, existing == null) { mutableStateOf(existing == null) }
    val request = RestoreRequest(
        type = type,
        archive = backup.volid,
        vmid = target,
        storage = storage,
        existingType = existing?.guestType,
        overwrite = overwrite,
        unprivileged = unprivileged,
        unique = unique,
        start = start,
    )
    val problem = request.validate()
    val restore = { vm.restoreBackup(screen.node, request) { vms.servers.back() } }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BackupCard(backup)
        val name = config?.get(if (isCt) "hostname" else "name")
        if (name != null) Text("Backup of \"$name\".", style = MaterialTheme.typography.bodySmall)

        NumField(vmid, { vmid = it.filter(Char::isDigit).take(9) }, "Restore to ID", Modifier.width(160.dp).testTag("restore-vmid"))
        if (existing != null && existing.guestType == type) {
            Text(
                "ID $target is ${existing.guestType?.label} \"${existing.name}\". Overwriting replaces it and its disks " +
                    "with the backup." + if (existing.status == "running") " Stop it first, or Proxmox refuses." else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            CheckRow("Overwrite ${type.label} $target", overwrite, Modifier.testTag("restore-overwrite")) { overwrite = it }
        }
        Dropdown(
            "Target storage",
            listOf("" to "— as in the backup —") + storages.map { it to it },
            storage,
        ) { storage = it }
        if (isCt) CheckRow("Unprivileged container", unprivileged) { unprivileged = it }
        CheckRow("New MAC addresses", unique) { unique = it }
        CheckRow("Start after restore", start) { start = it }

        problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button(
            onClick = { if (request.overwrite) confirmOverwrite = true else restore() },
            enabled = problem == null && state.busy == null,
            modifier = Modifier.fillMaxWidth().testTag("restore-submit"),
        ) { Text(if (request.overwrite) "Overwrite ${type.label} $target" else "Restore") }
    }

    if (confirmOverwrite) {
        OverwriteDialog(type.label, target, onConfirm = restore, onDismiss = { confirmOverwrite = false })
    }
}

@Composable
private fun OverwriteDialog(kind: String, vmid: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    val confirmed = typed.trim() == vmid.toString()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Overwrite $kind $vmid?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "$kind $vmid and its disks are replaced by the backup. Anything changed since the backup is lost.",
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it.filter(Char::isDigit) },
                    label = { Text("Type the ID ($vmid) to confirm") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag("restore-confirm"),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
                enabled = confirmed,
                modifier = Modifier.testTag("restore-go"),
            ) { Text("Overwrite", color = if (confirmed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun formatBackupTime(epochSeconds: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochSeconds * 1000))
