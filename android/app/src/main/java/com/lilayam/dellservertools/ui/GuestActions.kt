package com.lilayam.dellservertools.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.lilayam.dellservertools.core.proxmox.GuestEdit
import com.lilayam.dellservertools.core.proxmox.GuestType
import com.lilayam.dellservertools.core.proxmox.MoveDiskRequest

/** Moves one disk (VM) or volume (CT) to another storage of the node, like the web UI's Disk Action > Move Storage. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MoveDiskDialog(
    node: String,
    type: GuestType,
    vmid: Int,
    config: Map<String, String>,
    running: Boolean,
    /** The node's storages that hold this guest type's disks; null while loading. */
    targets: Result<List<String>>?,
    vms: AppViewModels,
    onDismiss: () -> Unit,
) {
    val edit = remember(config) { GuestEdit(type, config) }
    val disks = remember(edit) { MoveDiskRequest.movable(edit) }
    var disk by remember { mutableStateOf(disks.firstOrNull().orEmpty()) }
    var storage by remember { mutableStateOf("") }
    var format by remember { mutableStateOf("") }
    var deleteSource by remember { mutableStateOf(true) }
    val storages = targets?.getOrNull()
    val loadError = targets?.exceptionOrNull()?.let { it.message ?: "Couldn't load the storages." }
    val current = MoveDiskRequest.storageOf(config[disk].orEmpty())
    val request = MoveDiskRequest(type, disk, storage, deleteSource, format)
    // A container's volumes only move while it is stopped; a VM's move live.
    val blocked = type == GuestType.LXC && running
    val error = if (blocked) "Stop the container first." else request.validate(current)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (type == GuestType.QEMU) "Move disk" else "Move volume") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (disks.isEmpty()) Text("No disk can be moved.")
                disks.forEach { key ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(selected = disk == key, role = Role.RadioButton, onClick = { disk = key })
                            .testTag("move-disk-$key"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = disk == key, onClick = null)
                        Text("$key on ${MoveDiskRequest.storageOf(config[key].orEmpty())}")
                    }
                }
                Text("Target storage", style = MaterialTheme.typography.labelLarge)
                when {
                    loadError != null -> Text(loadError.orEmpty(), color = MaterialTheme.colorScheme.error)
                    storages == null -> Text("Loading…")
                    storages.orEmpty().isEmpty() -> Text("No storage on $node holds ${if (type == GuestType.QEMU) "disk images" else "containers"}.")
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    storages.orEmpty().forEach { s ->
                        FilterChip(
                            selected = storage == s,
                            onClick = { storage = s },
                            label = { Text(s) },
                            modifier = Modifier.testTag("move-target-$s"),
                        )
                    }
                }
                if (type == GuestType.QEMU) {
                    Text("Format", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (listOf("") + MoveDiskRequest.FORMATS).forEach { f ->
                            FilterChip(
                                selected = format == f,
                                onClick = { format = f },
                                label = { Text(f.ifEmpty { "Keep" }) },
                                modifier = Modifier.testTag("move-format-${f.ifEmpty { "keep" }}"),
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = deleteSource, onCheckedChange = { deleteSource = it }, modifier = Modifier.testTag("move-delete"))
                    Text("Delete the old copy (otherwise it stays as an unused disk)", style = MaterialTheme.typography.bodySmall)
                }
                if (storage.isNotEmpty() || blocked) error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    vms.proxmox.moveDisk(node, vmid, request)
                    onDismiss()
                },
                enabled = error == null,
                modifier = Modifier.testTag("move-go"),
            ) { Text("Move") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Take snapshot, with the VM's memory when it is running, so a rollback resumes it. */
@Composable
internal fun SnapshotDialog(canSaveRam: Boolean, onCreate: (String, String, Boolean) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var withRam by remember { mutableStateOf(false) }
    val valid = snapshotNamePattern.matches(name)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Take snapshot") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.trim() },
                    label = { Text("Name") },
                    placeholder = { Text("before_update") },
                    isError = name.isNotEmpty() && !valid,
                    supportingText = { Text("Letters, digits, - and _; must start with a letter") },
                    singleLine = true,
                    modifier = Modifier.testTag("snapshot-name"),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                )
                if (canSaveRam) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = withRam, onCheckedChange = { withRam = it }, modifier = Modifier.testTag("snapshot-ram"))
                        Text("Include RAM (a rollback resumes the running VM)", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onCreate(name, description, canSaveRam && withRam)
                    onDismiss()
                },
                enabled = valid,
                modifier = Modifier.testTag("snapshot-go"),
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val snapshotNamePattern = Regex("^[A-Za-z][A-Za-z0-9_-]{0,39}$")
