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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lilayam.dellservertools.core.ServerProfile
import com.lilayam.dellservertools.core.proxmox.AplTemplate
import com.lilayam.dellservertools.core.proxmox.LxcRequest
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateCtScreen(profile: ServerProfile, node: String, vms: AppViewModels) {
    val vm = vms.proxmox
    val state by vm.state.collectAsStateWithLifecycle()
    val opts = state.createOptions?.takeIf { it.node == node }

    LaunchedEffect(node) { vm.loadCreateOptions(node) }
    ForwardMessages(state.message, vms) { vm.clearMessage() }

    // Form fields
    var vmid by remember { mutableStateOf("") }
    var hostname by remember { mutableStateOf("") }
    var template by remember { mutableStateOf("") }
    var storage by remember { mutableStateOf("") }
    var disk by remember { mutableStateOf("16") }
    var cores by remember { mutableStateOf("2") }
    var memory by remember { mutableStateOf("2048") }
    var swap by remember { mutableStateOf("512") }
    var bridge by remember { mutableStateOf("") }
    var dhcp by remember { mutableStateOf(true) }
    var cidr by remember { mutableStateOf("") }
    var gateway by remember { mutableStateOf("") }
    var unprivileged by remember { mutableStateOf(true) }
    var nesting by remember { mutableStateOf(true) }
    var password by remember { mutableStateOf("") }
    var sshKey by remember { mutableStateOf("") }
    var start by remember { mutableStateOf(true) }
    var showDownload by remember { mutableStateOf(false) }

    // Fill defaults once options load.
    LaunchedEffect(opts?.loading) {
        if (opts != null && !opts.loading) {
            if (vmid.isBlank()) vmid = opts.nextVmid.toString()
            if (hostname.isBlank()) hostname = "ct-${opts.nextVmid}"
            if (template.isBlank()) template = opts.templates.firstOrNull { it.name.contains("debian") }?.volid
                ?: opts.templates.firstOrNull()?.volid.orEmpty()
            if (storage.isBlank()) storage = opts.ctStorages.firstOrNull { it.contains("zfs") }
                ?: opts.ctStorages.firstOrNull().orEmpty()
            if (bridge.isBlank()) bridge = opts.bridges.firstOrNull().orEmpty()
        }
    }

    val request = LxcRequest(
        vmid = vmid.toIntOrNull() ?: 0,
        hostname = hostname.trim(),
        ostemplate = template,
        storage = storage,
        diskGb = disk.toIntOrNull() ?: 0,
        cores = cores.toIntOrNull() ?: 0,
        memoryMb = memory.toIntOrNull() ?: 0,
        swapMb = swap.toIntOrNull() ?: 0,
        bridge = bridge,
        useDhcp = dhcp,
        staticCidr = cidr.trim(),
        gateway = gateway.trim(),
        unprivileged = unprivileged,
        nesting = nesting,
        password = password,
        sshPublicKey = sshKey,
        start = start,
    )
    val problem = request.validate()

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = { Text("New container on $node") },
            navigationIcon = { IconButton(onClick = { vms.servers.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        BusyBar(state.busy)

        if (opts == null || opts.loading) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer8()
                Text("Loading templates and storage…")
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
                    value = hostname,
                    onValueChange = { hostname = it.trim() },
                    label = { Text("Hostname") },
                    singleLine = true,
                    isError = hostname.isNotBlank() && request.hostnameError() != null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    modifier = Modifier.weight(1f).testTag("ct-hostname"),
                )
            }

            if (opts.templates.isEmpty()) {
                Text(
                    "No container templates downloaded yet. Download one first.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Dropdown("Template", opts.templates.map { it.volid to it.name }, template) { template = it }
            }
            OutlinedButton(onClick = { showDownload = true }) { Text("Download a template…") }

            Dropdown("Disk storage", opts.ctStorages.map { it to it }, storage) { storage = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField(disk, { disk = it.filter(Char::isDigit).take(5) }, "Disk GB", Modifier.weight(1f))
                NumField(cores, { cores = it.filter(Char::isDigit).take(3) }, "Cores", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField(memory, { memory = it.filter(Char::isDigit).take(7) }, "Memory MB", Modifier.weight(1f))
                NumField(swap, { swap = it.filter(Char::isDigit).take(7) }, "Swap MB", Modifier.weight(1f))
            }

            Dropdown("Network bridge", opts.bridges.map { it to it }, bridge) { bridge = it }
            CheckRow("Get IP automatically (DHCP)", dhcp) { dhcp = it }
            if (!dhcp) {
                OutlinedTextField(
                    value = cidr,
                    onValueChange = { cidr = it.trim() },
                    label = { Text("Static IP / CIDR") },
                    placeholder = { Text("192.168.5.50/24") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = gateway,
                    onValueChange = { gateway = it.trim() },
                    label = { Text("Gateway") },
                    placeholder = { Text("192.168.5.1") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            CheckRow("Unprivileged (root inside ≠ root on host)", unprivileged) { unprivileged = it }
            CheckRow("Allow nesting (Docker inside, CI runners)", nesting) { nesting = it }

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Root password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().testTag("ct-password"),
            )
            OutlinedTextField(
                value = sshKey,
                onValueChange = { sshKey = it },
                label = { Text("SSH public key (optional)") },
                placeholder = { Text("ssh-ed25519 AAAA…") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            CheckRow("Start after creating", start) { start = it }

            problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            Button(
                onClick = { vm.createContainer(node, request) { vms.servers.back() } },
                enabled = problem == null && state.busy == null,
                modifier = Modifier.fillMaxWidth().testTag("ct-submit"),
            ) { Text("Create container") }
        }
    }

    if (showDownload) {
        DownloadTemplateDialog(
            node = node,
            storage = opts?.templateStorages?.firstOrNull() ?: "local",
            vms = vms,
            onDismiss = { showDownload = false },
        )
    }
}

@Composable
private fun DownloadTemplateDialog(node: String, storage: String, vms: AppViewModels, onDismiss: () -> Unit) {
    val vm = vms.proxmox
    val scope = rememberCoroutineScope()
    var available by remember { mutableStateOf<List<AplTemplate>?>(null) }
    LaunchedEffect(node) { available = vm.availableTemplates(node).filter { it.section == "system" } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download a template") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Into storage \"$storage\". Common small base images:", style = MaterialTheme.typography.bodySmall)
                val list = available
                if (list == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer8()
                        Text("Loading list…")
                    }
                } else {
                    val preferred = list.filter { t -> listOf("debian-12", "debian-13", "ubuntu-24", "alpine-3").any { t.name.contains(it) } }
                    (preferred.ifEmpty { list }).take(25).forEach { t ->
                        TextButton(onClick = {
                            scope.launch {
                                vm.downloadTemplate(node, storage, t.template)
                                onDismiss()
                            }
                        }) { Text(t.name, fontFamily = FontFamily.Monospace) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Dropdown(label: String, items: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val shown = items.firstOrNull { it.first == selected }?.second ?: ""
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = shown,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = {
                IconButton(onClick = { expanded = !expanded }, enabled = items.isNotEmpty()) {
                    Icon(Icons.Filled.ArrowDropDown, "Choose")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = {
                    onSelect(value)
                    expanded = false
                })
            }
        }
    }
}

@Composable
internal fun NumField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        modifier = modifier,
    )
}

@Composable
internal fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun Spacer8() = androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
