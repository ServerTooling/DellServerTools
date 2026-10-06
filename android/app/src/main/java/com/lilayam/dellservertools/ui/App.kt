package com.lilayam.dellservertools.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Shared view models, created by the activity. */
class AppViewModels(
    val servers: ServersViewModel,
    val terminal: TerminalViewModel,
    val proxmox: ProxmoxViewModel,
)

@Composable
fun DellServerToolsApp(vms: AppViewModels, onExit: () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colors) {
        val state by vms.servers.state.collectAsStateWithLifecycle()
        val snackbar = remember { SnackbarHostState() }

        LaunchedEffect(state.message) {
            state.message?.let {
                snackbar.showSnackbar(it)
                vms.servers.clearMessage()
            }
        }

        BackHandler(enabled = state.screen !is Screen.Web) {
            if (!vms.servers.back()) onExit()
        }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { padding ->
            Box(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .safeDrawingPadding(),
            ) {
                when (val screen = state.screen) {
                    Screen.Home -> HomeScreen(state.profiles, vms.servers)
                    is Screen.Edit -> EditServerScreen(screen, vms.servers)
                    is Screen.Terminal -> WithProfile(vms, screen.profileId) { TerminalScreen(it, vms) }
                    is Screen.Proxmox -> WithProfile(vms, screen.profileId) { ProxmoxHomeScreen(it, vms) }
                    is Screen.Node -> WithProfile(vms, screen.profileId) { NodeScreen(it, screen.node, vms) }
                    is Screen.Guest -> WithProfile(vms, screen.profileId) { GuestScreen(it, screen, vms) }
                    is Screen.Tasks -> WithProfile(vms, screen.profileId) { TasksScreen(it, vms) }
                    is Screen.TaskLog -> WithProfile(vms, screen.profileId) { TaskLogScreen(screen, vms) }
                    is Screen.Web -> WithProfile(vms, screen.profileId) { WebScreen(it, screen, vms) }
                }
            }
        }

        state.passwordRequest?.let { request ->
            PasswordDialog(
                serverName = request.profileName,
                onSubmit = vms.servers::submitPassword,
                onCancel = vms.servers::cancelPasswordRequest,
            )
        }
    }
}

@Composable
private fun WithProfile(
    vms: AppViewModels,
    profileId: String,
    content: @Composable (com.lilayam.dellservertools.core.ServerProfile) -> Unit,
) {
    val profile = vms.servers.profile(profileId)
    if (profile == null) {
        LaunchedEffect(profileId) { vms.servers.home() }
    } else {
        content(profile)
    }
}

@Composable
private fun PasswordDialog(serverName: String, onSubmit: (String, Boolean) -> Unit, onCancel: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var remember by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Password for $serverName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (password.isNotEmpty()) onSubmit(password, remember) }),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = remember, onCheckedChange = { remember = it })
                    Text("Remember on this phone (encrypted)")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(password, remember) }, enabled = password.isNotEmpty()) { Text("Connect") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/** Asks the user to trust an SSH host key or a self-signed TLS certificate. */
@Composable
fun TrustDialog(kind: String, prompt: FingerprintPrompt, onAccept: () -> Unit, onReject: () -> Unit) {
    AlertDialog(
        onDismissRequest = onReject,
        title = { Text(if (prompt.changed) "$kind changed!" else "Trust this server?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (prompt.changed) {
                        "The server's $kind is different from last time. That's expected after a reinstall, " +
                            "certificate renewal or iDRAC reset, but could also mean someone is intercepting the connection."
                    } else {
                        "First connection to this server. Check that this $kind fingerprint matches the server:"
                    },
                )
                Text(prompt.fingerprint, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = onAccept) { Text("Trust and connect") } },
        dismissButton = { TextButton(onClick = onReject) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                onDismiss()
            }) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
