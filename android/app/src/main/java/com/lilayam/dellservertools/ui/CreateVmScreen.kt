package com.lilayam.dellservertools.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lilayam.dellservertools.core.ServerProfile
import com.lilayam.dellservertools.core.proxmox.OsType
import com.lilayam.dellservertools.core.proxmox.VmBios
import com.lilayam.dellservertools.core.proxmox.VmRequest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateVmScreen(profile: ServerProfile, node: String, vms: AppViewModels) {
    val vm = vms.proxmox
    val state by vm.state.collectAsStateWithLifecycle()
    val opts = state.createOptions?.takeIf { it.node == node }

    LaunchedEffect(node) { vm.loadCreateOptions(node) }
    ForwardMessages(state.message, vms) { vm.clearMessage() }

    var vmid by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var storage by remember { mutableStateOf("") }
    var disk by remember { mutableStateOf("32") }
    var cores by remember { mutableStateOf("2") }
    var sockets by remember { mutableStateOf("1") }
    var memory by remember { mutableStateOf("4096") }
    var iso by remember { mutableStateOf("") }
    var bridge by remember { mutableStateOf("") }
    var osType by remember { mutableStateOf(OsType.LINUX) }
    var bios by remember { mutableStateOf(VmBios.SEABIOS) }
    var agent by remember { mutableStateOf(true) }
    var start by remember { mutableStateOf(false) }

    LaunchedEffect(opts?.loading) {
        if (opts != null && !opts.loading) {
            if (vmid.isBlank()) vmid = opts.nextVmid.toString()
            if (name.isBlank()) name = "vm-${opts.nextVmid}"
            if (storage.isBlank()) storage = opts.vmStorages.firstOrNull { it.contains("zfs") } ?: opts.vmStorages.firstOrNull().orEmpty()
            if (bridge.isBlank()) bridge = opts.bridges.firstOrNull().orEmpty()
        }
    }

    val request = VmRequest(
        vmid = vmid.toIntOrNull() ?: 0,
        name = name.trim(),
        cores = cores.toIntOrNull() ?: 0,
        sockets = sockets.toIntOrNull() ?: 0,
        memoryMb = memory.toIntOrNull() ?: 0,
        diskStorage = storage,
        diskGb = disk.toIntOrNull() ?: 0,
        isoVolid = iso,
        bridge = bridge,
        osType = osType,
        bios = bios,
        agent = agent,
        start = start,
    )
    val problem = request.validate()

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = { Text("New VM on $node") },
            navigationIcon = { IconButton(onClick = { vms.servers.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        BusyBar(state.busy)

        if (opts == null || opts.loading) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer8()
                Text("Loading storage and ISOs…")
            }
            return@Column
        }

        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp).weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            opts.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField(vmid, { vmid = it.filter(Char::isDigit).take(9) }, "ID", Modifier.width(110.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.trim() },
                    label = { Text("Name") },
                    singleLine = true,
                    isError = name.isNotBlank() && request.nameError() != null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    modifier = Modifier.weight(1f).testTag("vm-name"),
                )
            }

            Dropdown("OS type", OsType.entries.map { it.api to it.label }, osType.api) { v -> osType = OsType.entries.first { it.api == v } }
            if (opts.isos.isEmpty()) {
                Text(
                    "No ISO images found. You can still create the VM and attach an installer ISO later, " +
                        "or upload one in the web interface.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Dropdown("Installer ISO (optional)", listOf("" to "— none —") + opts.isos.map { it.volid to it.name }, iso) { iso = it }
            }

            Dropdown("Disk storage", opts.vmStorages.map { it to it }, storage) { storage = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField(disk, { disk = it.filter(Char::isDigit).take(6) }, "Disk GB", Modifier.weight(1f))
                NumField(memory, { memory = it.filter(Char::isDigit).take(7) }, "Memory MB", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField(cores, { cores = it.filter(Char::isDigit).take(3) }, "Cores", Modifier.weight(1f))
                NumField(sockets, { sockets = it.filter(Char::isDigit).take(2) }, "Sockets", Modifier.weight(1f))
            }

            Dropdown("Network bridge", opts.bridges.map { it to it }, bridge) { bridge = it }
            Dropdown("Firmware", VmBios.entries.map { it.api to it.label }, bios.api) { v -> bios = VmBios.entries.first { it.api == v } }

            CheckRow("QEMU guest agent", agent) { agent = it }
            CheckRow("Start after creating", start) { start = it }

            problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (bios == VmBios.OVMF) {
                Text(
                    "UEFI adds an EFI disk. Windows 11 also needs a TPM — add that in the web interface after creating.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Button(
                onClick = { vm.createVm(node, request) { vms.servers.back() } },
                enabled = problem == null && state.busy == null,
                modifier = Modifier.fillMaxWidth().testTag("vm-submit"),
            ) { Text("Create VM") }
        }
    }
}
