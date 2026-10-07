package com.lilayam.dellservertools.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lilayam.dellservertools.core.IdracCommands
import com.lilayam.dellservertools.core.ProxmoxShellCommands
import com.lilayam.dellservertools.core.QuickCommand
import com.lilayam.dellservertools.core.ServerProfile
import com.lilayam.dellservertools.core.ServerType
import com.lilayam.dellservertools.core.SpecialKey
import com.lilayam.dellservertools.core.SpecialKeys

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(profile: ServerProfile, vms: AppViewModels, initialCommand: String? = null) {
    val vm = vms.terminal
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val isIdrac = profile.type == ServerType.IDRAC6

    LaunchedEffect(profile.id) {
        val password = vms.servers.password(profile)
        if (password.isNullOrEmpty()) {
            vms.servers.back()
            vms.servers.openShell(profile)
        } else {
            vm.ensureConnected(profile, password)
        }
    }
    LaunchedEffect(state.authFailed) {
        if (state.authFailed) vms.servers.forgetSessionPassword(profile.id)
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            vms.servers.showMessage(it)
            vm.clearMessage()
        }
    }

    var input by rememberSaveable { mutableStateOf("") }
    var fontSize by rememberSaveable { mutableFloatStateOf(12f) }
    var showBiosKeys by rememberSaveable { mutableStateOf(false) }
    var pendingCommand by remember { mutableStateOf<QuickCommand?>(null) }
    var showHelp by remember { mutableStateOf(false) }
    val connected = state.status == ConnectionStatus.CONNECTED && state.profileId == profile.id
    val lines = if (state.profileId == profile.id) state.lines else emptyList()

    // Drop straight into a guest (e.g. `pct enter 103`) once the host shell is up, exactly once.
    var initialSent by rememberSaveable(profile.id, initialCommand) { mutableStateOf(initialCommand.isNullOrBlank()) }
    LaunchedEffect(connected, initialCommand) {
        if (connected && !initialSent && !initialCommand.isNullOrBlank()) {
            vm.sendLine(initialCommand)
            initialSent = true
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        if (isIdrac) profile.displayName else "${profile.displayName} shell",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(
                            when (state.status) {
                                ConnectionStatus.CONNECTED -> Color(0xFF2E7D32)
                                ConnectionStatus.CONNECTING -> Color(0xFFF9A825)
                                ConnectionStatus.DISCONNECTED -> Color(0xFFC62828)
                            },
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when (state.status) {
                                ConnectionStatus.CONNECTED -> "Connected"
                                ConnectionStatus.CONNECTING -> "Connecting…"
                                ConnectionStatus.DISCONNECTED -> "Disconnected"
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = { vms.servers.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            },
            actions = {
                IconButton(onClick = { fontSize = (fontSize - 1f).coerceAtLeast(7f) }) {
                    Icon(Icons.Filled.TextDecrease, "Smaller text")
                }
                IconButton(onClick = { fontSize = (fontSize + 1f).coerceAtMost(22f) }) {
                    Icon(Icons.Filled.TextIncrease, "Larger text")
                }
                IconButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Terminal output", vm.copyableOutput()))
                }) { Icon(Icons.Filled.ContentCopy, "Copy output") }
                IconButton(onClick = vm::clear) { Icon(Icons.Filled.DeleteSweep, "Clear") }
                if (isIdrac) {
                    IconButton(onClick = { vms.servers.openScreen(profile) }) {
                        Icon(Icons.Filled.DesktopWindows, "Server screen")
                    }
                }
                if (isIdrac) {
                    IconButton(onClick = { showHelp = true }) { Icon(Icons.AutoMirrored.Filled.HelpOutline, "Help") }
                }
                if (connected) {
                    IconButton(onClick = vm::disconnect) { Icon(Icons.Filled.LinkOff, "Disconnect") }
                } else {
                    IconButton(
                        onClick = {
                            if (state.authFailed) {
                                vms.servers.back()
                                vms.servers.openShell(profile)
                            } else {
                                vm.reconnect()
                            }
                        },
                        enabled = state.status == ConnectionStatus.DISCONNECTED,
                    ) { Icon(Icons.Filled.Refresh, "Reconnect") }
                }
            },
        )

        state.error?.takeIf { state.profileId == profile.id }?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (isIdrac) {
                item {
                    AssistChip(
                        onClick = { vm.sendRaw(IdracCommands.EXIT_SERIAL_CONSOLE) },
                        label = { Text("Exit console (Ctrl+\\)") },
                        enabled = connected,
                    )
                }
            }
            items(if (isIdrac) IdracCommands.quickCommands else ProxmoxShellCommands.quickCommands) { command ->
                AssistChip(
                    onClick = {
                        when {
                            command.needsConfirmation -> pendingCommand = command
                            command == IdracCommands.serialConsole -> {
                                vm.sendLine(command.command)
                                vm.sendRaw("\r", delayMs = IdracCommands.SERIAL_CONSOLE_WAKE_DELAY_MS)
                            }
                            else -> vm.sendLine(command.command)
                        }
                    },
                    label = { Text(command.label) },
                    enabled = connected,
                    colors = if (command.needsConfirmation) {
                        AssistChipDefaults.assistChipColors(labelColor = MaterialTheme.colorScheme.error)
                    } else {
                        AssistChipDefaults.assistChipColors()
                    },
                )
            }
        }

        TerminalOutput(lines = lines, fontSize = fontSize, modifier = Modifier.weight(1f).fillMaxWidth())

        KeyRow(
            keys = if (showBiosKeys) SpecialKeys.bios else SpecialKeys.terminal,
            enabled = connected,
            leading = {
                if (isIdrac) {
                    FilterChip(
                        selected = showBiosKeys,
                        onClick = { showBiosKeys = !showBiosKeys },
                        label = { Text("BIOS keys") },
                    )
                }
            },
            onKey = vm::sendRaw,
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(if (isIdrac) "e.g. racadm getsysinfo" else "e.g. qm list") },
                singleLine = true,
                enabled = connected,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = {
                    vm.sendLine(input)
                    input = ""
                }),
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    vm.sendLine(input)
                    input = ""
                },
                enabled = connected,
            ) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
        }
    }

    pendingCommand?.let { command ->
        ConfirmDialog(
            title = "${command.label}?",
            text = "${command.description}\n\nCommand: ${command.command}",
            confirmLabel = command.label,
            onConfirm = { vm.sendLine(command.command) },
            onDismiss = { pendingCommand = null },
        )
    }
    state.hostKeyPrompt?.takeIf { state.profileId == profile.id }?.let { prompt ->
        TrustDialog("SSH host key", prompt, onAccept = vm::acceptHostKey, onReject = vm::rejectHostKey)
    }
    if (showHelp) IdracHelpDialog(onDismiss = { showHelp = false })
}

@Composable
private fun TerminalOutput(lines: List<String>, fontSize: Float, modifier: Modifier) {
    val listState = rememberLazyListState()
    val hScroll = rememberScrollState()
    var followOutput by remember { mutableStateOf(true) }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            followOutput = lastVisible >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(lines.size, lines.lastOrNull()) {
        if (followOutput && lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }

    Surface(modifier = modifier, color = Color(0xFF101010)) {
        Box(Modifier.horizontalScroll(hScroll)) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(8.dp),
                // Wide enough for 80 monospace columns.
                modifier = Modifier.width((fontSize * 0.62f * (TerminalViewModel.COLUMNS + 2)).dp),
            ) {
                itemsIndexed(lines) { _, line ->
                    Text(
                        text = line.ifEmpty { " " },
                        color = Color(0xFFE0E0E0),
                        fontFamily = FontFamily.Monospace,
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize * 1.2f).sp,
                        softWrap = false,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyRow(
    keys: List<SpecialKey>,
    enabled: Boolean,
    leading: @Composable () -> Unit,
    onKey: (String) -> Unit,
) {
    HorizontalDivider()
    LazyRow(
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item { leading() }
        items(keys) { key ->
            OutlinedButton(
                onClick = { onKey(key.sequence) },
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.height(36.dp),
            ) { Text(key.label, fontSize = 13.sp) }
        }
    }
}

@Composable
private fun IdracHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("iDRAC6 console") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "You are at the iDRAC's own prompt (/admin1->). It understands racadm commands, not Linux " +
                        "commands. Use the chips at the top for power, logs and system info.",
                )
                Text("Server console is blank?", fontWeight = FontWeight.SemiBold)
                Text(
                    "\"Server console\" (console com2) connects you to the server's serial port COM2. You only " +
                        "see something if the server is using that port; your keystrokes are sent to it but " +
                        "nothing echoes until a program on the server answers.",
                )
                Text(
                    "1. Login prompt (no reboot needed): open your Proxmox server's SSH shell in this app and " +
                        "tap \"Enable iDRAC console login\" (runs serial-getty on ttyS1). Then come back, tap " +
                        "\"Server console\" and press Enter: you get \"login:\".\n\n" +
                        "2. Linux boot messages: add console=tty0 console=ttyS1,115200n8 to the kernel command " +
                        "line (GRUB_CMDLINE_LINUX_DEFAULT in /etc/default/grub, then update-grub; or " +
                        "/etc/kernel/cmdline + proxmox-boot-tool refresh) and reboot.\n\n" +
                        "3. BIOS / POST screens: in BIOS (F2) > Serial Communication choose \"On with Console " +
                        "Redirection via COM2\", Failsafe Baud Rate 115200, Redirection After Boot Enabled. " +
                        "This needs a monitor or the graphical console once.",
                    fontSize = 13.sp,
                )
                Text("Graphical console (the real screen)", fontWeight = FontWeight.SemiBold)
                Text(
                    "The iDRAC6 graphical console only exists as an old Java program. Run it in Docker on an " +
                        "always-on x86 computer other than this server (it can't show its own reboot), e.g. " +
                        "the domistyle/idrac6 image, and put its address (http://that-computer:5800) in " +
                        "\"Graphical console URL\" when editing this iDRAC. A screen button then appears here.",
                )
                Text("\"BIOS keys\" sends F2 / F10 / F11 / F12 while the server boots over the serial console.")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}
