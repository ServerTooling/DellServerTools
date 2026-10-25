package com.lilayam.dellservertools.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lilayam.dellservertools.core.SpecialKeys

/**
 * The node's shell or a container's console, as in the web UI but drawn by the app's own terminal, so it uses
 * the app's Proxmox login (no SSH password) and the phone keyboard works.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxmoxShellScreen(screen: Screen.ProxmoxShell, vms: AppViewModels) {
    val context = LocalContext.current
    val proxmox = vms.proxmox
    val scope = rememberCoroutineScope()
    var attempt by remember { mutableIntStateOf(0) }
    val session = remember(attempt) {
        ProxmoxShellSession(scope, { proxmox.openTerminal(screen.node, screen.vmid) }, proxmox::describeError)
            .also { it.start() }
    }
    DisposableEffect(session) { onDispose { session.close() } }
    val state by session.state.collectAsStateWithLifecycle()
    ReconnectWhenBack(state.dropped) { attempt++ }

    var input by rememberSaveable { mutableStateOf("") }
    var fontSize by rememberSaveable { mutableFloatStateOf(12f) }
    var showSelectText by remember { mutableStateOf(false) }
    fun sendLine() {
        session.send(input + "\r")
        input = ""
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = {
                Column {
                    Text(screen.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(
                            when {
                                state.connected -> Color(0xFF2E7D32)
                                state.status.startsWith("Connecting") -> Color(0xFFF9A825)
                                else -> Color(0xFFC62828)
                            },
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            state.status,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("shell-status"),
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
                    clipboard.setPrimaryClip(ClipData.newPlainText("Terminal output", session.copyableOutput()))
                }) { Icon(Icons.Filled.ContentCopy, "Copy output") }
                IconButton(onClick = { attempt++ }, modifier = Modifier.testTag("shell-reconnect")) {
                    Icon(Icons.Filled.Refresh, "Reconnect")
                }
            },
        )

        TerminalOutput(
            lines = state.lines,
            fontSize = fontSize,
            onLongPress = { showSelectText = true },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )

        KeyRow(keys = SpecialKeys.terminal, enabled = state.connected, leading = {}, onKey = session::send)

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("e.g. pveversion") },
                singleLine = true,
                enabled = state.connected,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = { sendLine() }),
                modifier = Modifier.weight(1f).testTag("shell-input"),
            )
            IconButton(onClick = { sendLine() }, enabled = state.connected, modifier = Modifier.testTag("shell-send")) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send")
            }
        }
    }

    if (showSelectText) {
        SelectTextDialog(
            text = session.copyableOutput(),
            fontSize = fontSize,
            onCopyAll = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Terminal output", session.copyableOutput()))
            },
            onDismiss = { showSelectText = false },
        )
    }
}
