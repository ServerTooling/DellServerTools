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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lilayam.dellservertools.core.ServerProfile
import com.lilayam.dellservertools.core.proxmox.CloneRequest
import com.lilayam.dellservertools.core.proxmox.GuestType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloneGuestScreen(profile: ServerProfile, screen: Screen.CloneGuest, vms: AppViewModels) {
    val vm = vms.proxmox
    val state by vm.state.collectAsStateWithLifecycle()
    val opts = state.createOptions?.takeIf { it.node == screen.node }
    val isCt = screen.type == GuestType.LXC

    LaunchedEffect(screen.node) { vm.loadCreateOptions(screen.node) }
    ForwardMessages(state.message, vms) { vm.clearMessage() }

    // A linked clone is only possible from a template.
    val isTemplate = state.resources.any {
        it.vmid == screen.vmid && it.node == screen.node && it.guestType == screen.type && it.template
    }
    val storages = if (isCt) opts?.ctStorages.orEmpty() else opts?.vmStorages.orEmpty()

    var newId by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(if (screen.name.isBlank()) "" else "${screen.name}-clone") }
    var full by remember { mutableStateOf(true) }
    var storage by remember { mutableStateOf("") }
    var pool by remember { mutableStateOf("") }

    // Prefill the next free id once the options load.
    LaunchedEffect(opts?.loading) {
        if (opts != null && !opts.loading && newId.isBlank()) newId = opts.nextVmid.toString()
    }

    val request = CloneRequest(
        type = screen.type,
        sourceVmid = screen.vmid,
        newId = newId.toIntOrNull() ?: 0,
        name = name.trim(),
        full = full,
        sourceIsTemplate = isTemplate,
        storage = if (full) storage else "",
        pool = pool.trim(),
    )
    val problem = request.validate()

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = { Text("Clone ${screen.type.label} ${screen.vmid}") },
            navigationIcon = { IconButton(onClick = { vms.servers.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        BusyBar(state.busy)

        if (opts == null || opts.loading) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer8()
                Text("Loading storage…")
            }
            return@Column
        }

        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp).weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            opts.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Text(
                "Clone \"${screen.name}\" into a new ${screen.type.label}.",
                style = MaterialTheme.typography.bodySmall,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField(newId, { newId = it.filter(Char::isDigit).take(9) }, "New ID", Modifier.width(120.dp).testTag("clone-newid"))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.trim() },
                    label = { Text(if (isCt) "Hostname" else "Name") },
                    singleLine = true,
                    isError = name.isNotBlank() && request.nameError() != null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    modifier = Modifier.weight(1f).testTag("clone-name"),
                )
            }

            if (isTemplate) {
                // Linked clones reference the template's disks; only sensible for a template source.
                CheckRow("Full clone (copy the disks)", full) { full = it }
                if (!full) {
                    Text(
                        "Linked clone: shares the template's disks, so it's fast and small but the template " +
                            "can't be deleted while linked clones exist.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                Text(
                    "This is a full clone (independent copy). Linked clones are only possible from a template.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (full) {
                Dropdown(
                    "Target storage",
                    listOf("" to "— same as source —") + storages.map { it to it },
                    storage,
                ) { storage = it }
            }

            OutlinedTextField(
                value = pool,
                onValueChange = { pool = it.trim() },
                label = { Text("Resource pool (optional)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().testTag("clone-pool"),
            )

            problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            Button(
                onClick = { vm.cloneGuest(screen.node, screen.type, screen.vmid, request) { vms.servers.back() } },
                enabled = problem == null && state.busy == null,
                modifier = Modifier.fillMaxWidth().testTag("clone-submit"),
            ) { Text("Clone ${screen.type.label}") }
        }
    }
}
