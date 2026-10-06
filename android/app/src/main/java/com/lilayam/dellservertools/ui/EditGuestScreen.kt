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
import com.lilayam.dellservertools.core.proxmox.GuestEdit
import com.lilayam.dellservertools.core.proxmox.GuestType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditGuestScreen(profile: ServerProfile, screen: Screen.EditGuest, vms: AppViewModels) {
    val vm = vms.proxmox
    val state by vm.state.collectAsStateWithLifecycle()
    val isCt = screen.type == GuestType.LXC

    LaunchedEffect(screen.vmid) { vm.loadGuest(screen.node, screen.type, screen.vmid) }
    ForwardMessages(state.message, vms) { vm.clearMessage() }

    val detail = state.guest?.takeIf { it.vmid == screen.vmid && it.node == screen.node }
    val config = detail?.config?.toMap()

    // Local editable state, filled once the config loads.
    var loaded by remember(screen.vmid) { mutableStateOf(false) }
    var name by remember(screen.vmid) { mutableStateOf("") }
    var cores by remember(screen.vmid) { mutableStateOf("") }
    var memory by remember(screen.vmid) { mutableStateOf("") }
    var swap by remember(screen.vmid) { mutableStateOf("") }
    var sockets by remember(screen.vmid) { mutableStateOf("") }
    var description by remember(screen.vmid) { mutableStateOf("") }
    var onboot by remember(screen.vmid) { mutableStateOf(false) }
    var nesting by remember(screen.vmid) { mutableStateOf(false) }
    var agent by remember(screen.vmid) { mutableStateOf(false) }

    LaunchedEffect(config != null) {
        if (config != null && !loaded) {
            val e = GuestEdit.from(screen.type, config)
            name = e.name
            cores = e.cores.toString()
            memory = e.memoryMb.toString()
            swap = e.swapMb.toString()
            sockets = e.sockets.toString()
            description = e.description
            onboot = e.onboot
            nesting = e.nesting
            agent = e.agent
            loaded = true
        }
    }

    val edit = GuestEdit(
        type = screen.type,
        name = name.trim(),
        cores = cores.toIntOrNull() ?: 0,
        memoryMb = memory.toIntOrNull() ?: 0,
        swapMb = swap.toIntOrNull() ?: 0,
        sockets = sockets.toIntOrNull() ?: 1,
        onboot = onboot,
        nesting = nesting,
        agent = agent,
        description = description,
    )
    val problem = edit.validate()

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = { Text("Edit ${screen.type.label} ${screen.vmid}") },
            navigationIcon = { IconButton(onClick = { vms.servers.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        BusyBar(state.busy)

        if (config == null || !loaded) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer8()
                Text("Loading current settings…")
            }
            return@Column
        }

        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp).weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.trim() },
                label = { Text(if (isCt) "Hostname" else "Name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().testTag("edit-name"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField(cores, { cores = it.filter(Char::isDigit).take(3) }, "Cores", Modifier.weight(1f))
                NumField(memory, { memory = it.filter(Char::isDigit).take(7) }, "Memory MB", Modifier.weight(1f).testTag("edit-memory"))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isCt) {
                    NumField(swap, { swap = it.filter(Char::isDigit).take(7) }, "Swap MB", Modifier.weight(1f))
                } else {
                    NumField(sockets, { sockets = it.filter(Char::isDigit).take(2) }, "Sockets", Modifier.weight(1f))
                }
            }
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description / notes") },
                modifier = Modifier.fillMaxWidth(),
            )
            CheckRow("Start on boot", onboot) { onboot = it }
            if (isCt) {
                CheckRow("Allow nesting (Docker inside, CI runners)", nesting) { nesting = it }
            } else {
                CheckRow("QEMU guest agent", agent) { agent = it }
            }

            Text(
                "CPU and memory changes apply after the next reboot unless hotplug is enabled. " +
                    "Disks, extra NICs and advanced options are in the web interface.",
                style = MaterialTheme.typography.bodySmall,
            )
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            Button(
                onClick = {
                    vm.editGuest(screen.node, screen.type, screen.vmid, edit, config) { vms.servers.back() }
                },
                enabled = problem == null && state.busy == null,
                modifier = Modifier.fillMaxWidth().testTag("edit-submit"),
            ) { Text("Save changes") }
        }
    }
}
