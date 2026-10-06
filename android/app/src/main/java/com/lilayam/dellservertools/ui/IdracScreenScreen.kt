package com.lilayam.dellservertools.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lilayam.dellservertools.core.ServerProfile
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.lilayam.dellservertools.core.SpecialKeys
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

/** The server's screen as the iDRAC6 sees it ("Virtual Console Preview"), refreshed automatically. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdracScreenScreen(profile: ServerProfile, vms: AppViewModels) {
    val vm = vms.idracScreen
    val state by vm.state.collectAsStateWithLifecycle()
    val terminalState by vms.terminal.state.collectAsStateWithLifecycle()
    var autoRefresh by rememberSaveable { mutableStateOf(true) }
    var typed by rememberSaveable { mutableStateOf("") }
    var confirmReset by remember { mutableStateOf(false) }

    // Keys go to the server through the iDRAC serial console (console com2).
    val sendKeys: (String) -> Unit = { keys ->
        val password = vms.servers.password(profile)
        if (password.isNullOrEmpty()) {
            vms.servers.showMessage("Enter the iDRAC password first (open the console once).")
        } else {
            vms.terminal.sendToServerConsole(profile, password, keys)
            vm.refreshSoon()
        }
    }

    LaunchedEffect(profile) {
        val password = vms.servers.password(profile)
        if (password.isNullOrEmpty()) {
            vms.servers.back()
            vms.servers.openScreen(profile)
        } else {
            vm.open(profile, password)
        }
    }
    LaunchedEffect(state.authFailed) {
        if (state.authFailed) vms.servers.forgetSessionPassword(profile.id)
    }
    LaunchedEffect(autoRefresh) {
        while (autoRefresh) {
            delay(REFRESH_MS)
            vm.refresh()
        }
    }
    // Free the iDRAC web session as soon as the screen is left.
    DisposableEffect(profile.id) { onDispose { vm.close() } }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("${profile.displayName} screen", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = { vms.servers.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = vm::refresh) { Icon(Icons.Filled.Refresh, "Refresh screen") }
                IconButton(onClick = {
                    vms.servers.back()
                    vms.servers.open(profile)
                }) { Icon(Icons.Filled.Terminal, "iDRAC console") }
                if (profile.consoleUrl.isNotBlank()) {
                    IconButton(onClick = {
                        vms.servers.navigate(
                            Screen.Web(profile.id, profile.consoleUrl, "${profile.displayName} console", isMainUi = false),
                        )
                    }) { Icon(Icons.Filled.DesktopWindows, "Full graphical console") }
                }
            },
        )

        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            val image = state.image
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = "Server screen",
                    contentScale = ContentScale.Fit,
                    // The preview is small; nearest-neighbour scaling keeps the text sharp instead of blurry.
                    filterQuality = FilterQuality.None,
                    modifier = Modifier
                        .fillMaxSize()
                        .transformable(transform)
                        .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                )
            } else if (state.loading) {
                CircularProgressIndicator()
            }
            if (state.loading && image != null) {
                CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(8.dp).size(20.dp), strokeWidth = 2.dp)
            }
        }

        val serial = terminalState.status.takeIf { terminalState.profileId == profile.id }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(SpecialKeys.screen) { key ->
                OutlinedButton(
                    onClick = { sendKeys(key.sequence) },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.height(40.dp),
                ) { Text(key.label) }
            }
            item {
                OutlinedButton(
                    onClick = { confirmReset = true },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.height(40.dp),
                ) { Text(SpecialKeys.ctrlAltDel.label, color = MaterialTheme.colorScheme.error) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                placeholder = { Text("Type text, then send (adds Enter)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = {
                    sendKeys(typed + "\r")
                    typed = ""
                }),
                modifier = Modifier.weight(1f).testTag("screen-text"),
            )
            IconButton(onClick = {
                sendKeys(typed + "\r")
                typed = ""
            }) { Icon(Icons.AutoMirrored.Filled.Send, "Send text") }
        }

        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            state.error?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::retry) { Text("Retry") }
                    if (state.authFailed) {
                        OutlinedButton(onClick = {
                            vms.servers.back()
                            vms.servers.openScreen(profile)
                        }) { Text("Enter password") }
                    }
                }
            }
            terminalState.error?.takeIf { terminalState.profileId == profile.id }?.let {
                Text("Keys: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (state.updatedAt?.let { "Updated ${DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(it))}" }
                        ?: "Waiting for the first image…") +
                        (state.image?.let { " · ${it.width}×${it.height}" } ?: "") +
                        when (serial) {
                            ConnectionStatus.CONNECTED -> " · keys: serial console connected"
                            ConnectionStatus.CONNECTING -> " · keys: connecting…"
                            else -> ""
                        },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Text("Auto", style = MaterialTheme.typography.bodySmall)
                Switch(checked = autoRefresh, onCheckedChange = { autoRefresh = it }, modifier = Modifier.padding(start = 8.dp))
            }
            Text(
                "Keys are sent through the iDRAC serial console. The BIOS receives them only if " +
                    "BIOS > Serial Communication is \"On with Console Redirection via COM2\" (see Help).",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    state.certPrompt?.let { prompt ->
        TrustDialog("TLS certificate", prompt, onAccept = vm::acceptCertificate, onReject = vm::rejectCertificate)
    }
    terminalState.hostKeyPrompt?.takeIf { terminalState.profileId == profile.id }?.let { prompt ->
        TrustDialog("SSH host key", prompt, onAccept = vms.terminal::acceptHostKey, onReject = vms.terminal::rejectHostKey)
    }
    if (confirmReset) {
        ConfirmDialog(
            title = "Send Ctrl+Alt+Del?",
            text = "Restarts the server immediately if it is at the BIOS or a boot screen.",
            confirmLabel = "Send",
            onConfirm = { sendKeys(SpecialKeys.ctrlAltDel.sequence) },
            onDismiss = { confirmReset = false },
        )
    }
}

private const val REFRESH_MS = 3_000L
