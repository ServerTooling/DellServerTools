package com.lilayam.dellservertools.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lilayam.dellservertools.core.ServerProfile
import com.lilayam.dellservertools.core.proxmox.GuestEdit
import com.lilayam.dellservertools.core.proxmox.GuestType

/** Keys with their own field in the typed sections, so "All settings" doesn't list them twice. */
private val TYPED_KEYS = setOf(
    "hostname", "name", "cores", "memory", "swap", "sockets", "description", "onboot", "protection", "tags",
    "cpulimit", "cpuunits", "nameserver", "searchdomain", "boot",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditGuestScreen(profile: ServerProfile, screen: Screen.EditGuest, vms: AppViewModels) {
    val vm = vms.proxmox
    val state by vm.state.collectAsStateWithLifecycle()
    val isCt = screen.type == GuestType.LXC

    LaunchedEffect(screen.vmid) {
        vm.loadGuest(screen.node, screen.type, screen.vmid)
        vm.loadCreateOptions(screen.node)
    }
    ForwardMessages(state.message, vms) { vm.clearMessage() }

    val detail = state.guest?.takeIf { it.vmid == screen.vmid && it.node == screen.node }
    val config = detail?.config?.toMap()
    val opts = state.createOptions?.takeIf { it.node == screen.node && !it.loading }

    var edit by remember(screen.vmid) { mutableStateOf<GuestEdit?>(null) }
    // A reload (after Grow disk, say) keeps what the user already changed on top of the new config.
    LaunchedEffect(config) {
        if (config != null) edit = edit?.rebase(config) ?: GuestEdit(screen.type, config)
    }
    var growing by remember { mutableStateOf<String?>(null) }
    var addingDisk by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<String?>(null) }
    var askRestart by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = { Text("Edit ${screen.type.label} ${screen.vmid}") },
            navigationIcon = { IconButton(onClick = { vms.servers.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        BusyBar(state.busy)

        val e = edit
        if (e == null) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer8()
                Text("Loading current settings…")
            }
            return@Column
        }
        val problem = e.validate()
        val changeCount = e.changedParams().let { p -> p.size - (if ("delete" in p) 1 else 0) + (p["delete"]?.split(',')?.size ?: 0) }
        fun remove(key: String) {
            // Removing an unused volume deletes its data; removing a disk only detaches it.
            if (key.startsWith("unused") || GuestEdit.isDisk(key)) confirmRemove = key else edit = e.without(key)
        }

        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp).weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (e.isTemplate) Note("This is a template: new clones get these settings.")

            Section("General")
            OutlinedTextField(
                value = e[e.nameKey],
                onValueChange = { edit = e.with(e.nameKey, it.trim()) },
                label = { Text(if (isCt) "Hostname" else "Name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().testTag("edit-name"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField(e["cores"], { edit = e.with("cores", it.filter(Char::isDigit).take(3)) }, "Cores", Modifier.weight(1f))
                NumField(e["memory"], { edit = e.with("memory", it.filter(Char::isDigit).take(7)) }, "Memory MB", Modifier.weight(1f).testTag("edit-memory"))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isCt) {
                    NumField(e["swap"], { edit = e.with("swap", it.filter(Char::isDigit).take(7)) }, "Swap MB", Modifier.weight(1f))
                } else {
                    NumField(e["sockets"], { edit = e.with("sockets", it.filter(Char::isDigit).take(2)) }, "Sockets", Modifier.weight(1f))
                }
                NumField(
                    e["cpulimit"], { edit = e.with("cpulimit", it.filter { c -> c.isDigit() || c == '.' }.take(6)) },
                    "CPU limit (0 = none)", Modifier.weight(1f).testTag("edit-cpulimit"),
                )
            }
            NumField(e["cpuunits"], { edit = e.with("cpuunits", it.filter(Char::isDigit).take(6)) }, "CPU weight (cpuunits)", Modifier.fillMaxWidth())
            OutlinedTextField(
                value = e["tags"],
                onValueChange = { edit = e.with("tags", it) },
                label = { Text("Tags (separated by ;)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().testTag("edit-tags"),
            )
            OutlinedTextField(
                value = e["description"],
                onValueChange = { edit = e.with("description", it) },
                label = { Text("Description / notes") },
                modifier = Modifier.fillMaxWidth(),
            )
            CheckRow("Start on boot", e.flag("onboot")) { edit = e.withFlag("onboot", it) }
            CheckRow("Protection (block delete and disk removal)", e.flag("protection")) { edit = e.withFlag("protection", it) }
            if (isCt) {
                CheckRow("Allow nesting (Docker inside, CI runners)", e.feature("nesting")) { edit = e.withFeature("nesting", it) }
                CheckRow("keyctl (Docker in unprivileged CTs)", e.feature("keyctl")) { edit = e.withFeature("keyctl", it) }
                CheckRow("FUSE mounts", e.feature("fuse")) { edit = e.withFeature("fuse", it) }
            } else {
                CheckRow("QEMU guest agent", e.agent) { edit = e.withAgent(it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField(e.startup("order"), { edit = e.withStartup("order", it.filter(Char::isDigit).take(4)) }, "Start order", Modifier.weight(1f))
                NumField(e.startup("up"), { edit = e.withStartup("up", it.filter(Char::isDigit).take(5)) }, "Up delay s", Modifier.weight(1f))
                NumField(e.startup("down"), { edit = e.withStartup("down", it.filter(Char::isDigit).take(5)) }, "Down timeout s", Modifier.weight(1f))
            }
            if (!isCt) BootOrder(e) { edit = it }
            if (isCt) {
                Section("DNS")
                OutlinedTextField(
                    value = e["nameserver"], onValueChange = { edit = e.with("nameserver", it.trim()) },
                    label = { Text("DNS servers (blank = use host)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = e["searchdomain"], onValueChange = { edit = e.with("searchdomain", it.trim()) },
                    label = { Text("Search domain (blank = use host)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }

            Section(if (isCt) "Disks and mount points" else "Disks")
            val growable = e.growableDisks().toSet()
            e.allKeys().filter { GuestEdit.isDisk(it) || it.startsWith("unused") }.forEach { key ->
                if (key !in e.values) {
                    RemovedRow(key) { edit = e.with(key, e.current.getValue(key)) }
                    return@forEach
                }
                RawRow(e, key, onChange = { edit = e.with(key, it) }, onRemove = { remove(key) })
                if (key in growable) {
                    OutlinedButton(
                        onClick = { growing = key },
                        enabled = state.busy == null,
                        modifier = Modifier.testTag("edit-grow-$key"),
                    ) { Text("Grow $key (now ${GuestEdit.diskSize(e[key])})") }
                }
            }
            OutlinedButton(onClick = { addingDisk = true }, modifier = Modifier.testTag("edit-add-disk")) {
                Text(if (isCt) "Add mount point" else "Add disk")
            }

            Section("Network")
            e.allKeys().filter { it.matches(Regex("net\\d+")) }.forEach { key ->
                if (key in e.values) {
                    RawRow(e, key, onChange = { edit = e.with(key, it) }, onRemove = { remove(key) })
                } else {
                    RemovedRow(key) { edit = e.with(key, e.current.getValue(key)) }
                }
            }
            OutlinedButton(
                onClick = {
                    val key = e.nextKey("net")
                    val bridge = opts?.bridges?.firstOrNull() ?: "vmbr0"
                    edit = e.with(key, if (isCt) "name=eth${key.drop(3)},bridge=$bridge,ip=dhcp,type=veth" else "virtio,bridge=$bridge")
                },
                modifier = Modifier.testTag("edit-add-net"),
            ) { Text("Add network interface") }

            Section("All settings")
            Note("Every other key of the guest config, as Proxmox stores it. Clear a value or tap the bin to remove it.")
            e.allKeys()
                .filter { it !in TYPED_KEYS && !GuestEdit.isDisk(it) && !it.startsWith("unused") && !it.matches(Regex("net\\d+")) }
                .forEach { key ->
                    if (key in e.values) {
                        RawRow(e, key, onChange = { edit = e.with(key, it) }, onRemove = { remove(key) })
                    } else {
                        RemovedRow(key) { edit = e.with(key, e.current.getValue(key)) }
                    }
                }
            AddSetting(e) { k, v -> edit = e.with(k, v) }

            HorizontalDivider()
            Text(
                "CPU and memory changes apply after the next reboot unless hotplug is enabled. " +
                    "Changes a running container can't take yet stay pending until it restarts.",
                style = MaterialTheme.typography.bodySmall,
            )
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            Button(
                onClick = {
                    val needsRestart = e.bootOrderChanged && detail?.status?.isRunning == true
                    vm.editGuest(screen.node, screen.type, screen.vmid, e) {
                        if (needsRestart) askRestart = true else vms.servers.back()
                    }
                },
                enabled = problem == null && state.busy == null,
                modifier = Modifier.fillMaxWidth().testTag("edit-submit"),
            ) { Text(if (changeCount == 0) "Save changes" else "Save $changeCount change${if (changeCount == 1) "" else "s"}") }
        }

        growing?.let { disk ->
            GrowDialog(disk, GuestEdit.diskSize(e[disk]).orEmpty(), onDismiss = { growing = null }) { gb ->
                vm.resizeDisk(screen.node, screen.type, screen.vmid, disk, gb)
                growing = null
            }
        }
        if (addingDisk) {
            val storages = (if (isCt) opts?.ctStorages else opts?.vmStorages).orEmpty()
            AddDiskDialog(isCt, storages, onDismiss = { addingDisk = false }) { storage, gb, path ->
                val key = if (isCt) e.nextKey("mp") else e.nextKey("scsi")
                edit = e.with(key, if (isCt) "$storage:$gb,mp=$path" else "$storage:$gb")
                addingDisk = false
            }
        }
        if (askRestart) {
            ConfirmDialog(
                title = "Stop and start VM ${screen.vmid} now?",
                text = "The new boot order is used from the next start. A reboot from inside the VM isn't enough: " +
                    "Proxmox has to stop it and start it again. Stopping is like pulling the plug.",
                confirmLabel = "Stop and start now",
                onConfirm = {
                    vm.stopAndStart(screen.node, screen.vmid)
                    vms.servers.back()
                },
                onDismiss = {
                    askRestart = false
                    vms.servers.back()
                },
            )
        }
        confirmRemove?.let { key ->
            val unused = key.startsWith("unused")
            ConfirmDialog(
                title = "Remove $key?",
                text = if (unused) {
                    "Saving deletes this volume and its data for good."
                } else if (key in e.current) {
                    "Saving detaches it; the volume stays on the storage as an unused disk until you remove that too."
                } else {
                    "It hasn't been created yet, so nothing is lost."
                },
                confirmLabel = "Remove",
                onConfirm = { edit = e.without(key) },
                onDismiss = { confirmRemove = null },
            )
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun RemovedRow(key: String, onUndo: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$key: removed when you save", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onUndo, modifier = Modifier.testTag("edit-undo-$key")) { Text("Undo") }
    }
}

/** One config key as text; read-only keys are shown as text with the reason. */
@Composable
private fun RawRow(e: GuestEdit, key: String, onChange: (String) -> Unit, onRemove: () -> Unit) {
    if (e.isReadOnly(key)) {
        Column {
            Text("$key: ${e[key]}", style = MaterialTheme.typography.bodySmall)
            Note(if (key == "unprivileged") "Fixed when the container is created." else "Read-only, set by Proxmox.")
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = e[key],
            onValueChange = onChange,
            label = { Text(key) },
            readOnly = key.startsWith("unused"),
            maxLines = 4,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            modifier = Modifier.weight(1f).testTag("edit-field-$key"),
        )
        IconButton(onClick = onRemove, modifier = Modifier.testTag("edit-remove-$key")) { Icon(Icons.Filled.Delete, "Remove $key") }
    }
}

@Composable
private fun AddSetting(e: GuestEdit, onAdd: (String, String) -> Unit) {
    var key by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    val clash = key in e.values
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = key, onValueChange = { key = it.trim().lowercase() }, label = { Text("New key") }, singleLine = true,
            isError = clash,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            modifier = Modifier.weight(1f).testTag("edit-add-key"),
        )
        OutlinedTextField(
            value = value, onValueChange = { value = it }, label = { Text("Value") }, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            modifier = Modifier.weight(1f).testTag("edit-add-value"),
        )
        TextButton(
            onClick = {
                onAdd(key, value)
                key = ""
                value = ""
            },
            enabled = key.isNotEmpty() && value.isNotEmpty() && !clash,
            modifier = Modifier.testTag("edit-add-submit"),
        ) { Text("Add") }
    }
}

@Composable
private fun GrowDialog(disk: String, size: String, onDismiss: () -> Unit, onGrow: (Int) -> Unit) {
    var gb by remember { mutableStateOf("8") }
    val n = gb.toIntOrNull() ?: 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Grow $disk") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Now $size. Disks can only grow; this runs right away.", style = MaterialTheme.typography.bodySmall)
                NumField(gb, { gb = it.filter(Char::isDigit).take(5) }, "Add GiB", Modifier.fillMaxWidth().testTag("grow-gb"))
            }
        },
        confirmButton = {
            TextButton(onClick = { onGrow(n) }, enabled = n > 0, modifier = Modifier.testTag("grow-submit")) { Text("Grow") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AddDiskDialog(isCt: Boolean, storages: List<String>, onDismiss: () -> Unit, onAdd: (String, Int, String) -> Unit) {
    var storage by remember(storages) { mutableStateOf(storages.firstOrNull { it.contains("zfs") } ?: storages.firstOrNull().orEmpty()) }
    var gb by remember { mutableStateOf("8") }
    var path by remember { mutableStateOf("/data") }
    val n = gb.toIntOrNull() ?: 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isCt) "Add mount point" else "Add disk") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Dropdown("Storage", storages.map { it to it }, storage) { storage = it }
                NumField(gb, { gb = it.filter(Char::isDigit).take(5) }, "Size GiB", Modifier.fillMaxWidth().testTag("disk-size"))
                if (isCt) {
                    OutlinedTextField(
                        value = path, onValueChange = { path = it.trim() }, label = { Text("Path in the container") },
                        singleLine = true, modifier = Modifier.fillMaxWidth().testTag("disk-path"),
                    )
                }
                Text("Created when you save.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(storage, n, path) },
                enabled = storage.isNotEmpty() && n > 0 && (!isCt || path.startsWith("/")),
                modifier = Modifier.testTag("disk-submit"),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The VM's boot order with buttons instead of the web interface's drag and drop. */
@Composable
private fun BootOrder(e: GuestEdit, onChange: (GuestEdit) -> Unit) {
    Section("Boot order")
    val order = e.bootOrder()
    order.forEachIndexed { i, device ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("boot-row-$device")) {
            Column(Modifier.weight(1f)) {
                Text("${i + 1}. $device")
                Note(bootDeviceSummary(e, device))
            }
            IconButton(
                onClick = { onChange(e.moveBootDevice(device, -1)) },
                enabled = i > 0,
                modifier = Modifier.testTag("boot-up-$device"),
            ) { Icon(Icons.Filled.KeyboardArrowUp, "Move $device up") }
            IconButton(
                onClick = { onChange(e.moveBootDevice(device, 1)) },
                enabled = i < order.lastIndex,
                modifier = Modifier.testTag("boot-down-$device"),
            ) { Icon(Icons.Filled.KeyboardArrowDown, "Move $device down") }
            Switch(
                checked = true,
                onCheckedChange = { onChange(e.withBootDevice(device, false)) },
                enabled = order.size > 1,
                modifier = Modifier.testTag("boot-on-$device"),
            )
        }
    }
    e.bootDevices().filter { it !in order }.forEach { device ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("boot-row-$device")) {
            Column(Modifier.weight(1f)) {
                Text(device)
                Note(bootDeviceSummary(e, device) + " · not used to boot")
            }
            Switch(
                checked = false,
                onCheckedChange = { onChange(e.withBootDevice(device, true)) },
                modifier = Modifier.testTag("boot-on-$device"),
            )
        }
    }
    Note("A running VM uses a new boot order only after it is stopped and started again.")
}

private fun bootDeviceSummary(e: GuestEdit, device: String): String {
    val value = e[device]
    val volume = value.substringBefore(',')
    return when {
        device.startsWith("net") -> "Network (${volume.substringBefore('=')})"
        e.isCdrom(device) -> if (volume == "none" || volume.isEmpty()) "CD drive, empty" else "CD: ${volume.substringAfterLast('/')}"
        else -> "Disk" + (GuestEdit.diskSize(value)?.let { " $it" } ?: "")
    }
}
