package com.lilayam.dellservertools.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lilayam.dellservertools.core.ServerProfile
import com.lilayam.dellservertools.core.ServerType
import com.lilayam.dellservertools.core.update.AppRelease

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(profiles: List<ServerProfile>, vm: ServersViewModel, update: UpdateViewModel) {
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importJnlp)
    }
    var showHelp by remember { mutableStateOf(false) }
    val updateState by update.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Dell Server Tools") },
            actions = {
                IconButton(onClick = { showHelp = true }) { Icon(Icons.AutoMirrored.Filled.HelpOutline, "Help") }
            },
        )
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f),
        ) {
            updateState.available?.let { release ->
                item { UpdateCard(release, updateState.progress, update) }
            }
            if (profiles.isEmpty()) {
                item {
                    Text(
                        "Add your iDRAC6 and your Proxmox server to get started. " +
                            "Everything stays on this phone.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(profiles, key = { it.id }) { profile ->
                Card(Modifier.fillMaxWidth().clickable { vm.open(profile) }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (profile.type == ServerType.IDRAC6) Icons.Filled.Dns else Icons.Filled.Computer,
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(profile.displayName, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${profile.type.label} · ${profile.username}@${profile.host}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (profile.type == ServerType.IDRAC6) {
                            IconButton(onClick = { vm.openScreen(profile) }) {
                                Icon(Icons.Filled.DesktopWindows, "Screen of ${profile.displayName}")
                            }
                        }
                        IconButton(onClick = { vm.edit(profile) }) { Icon(Icons.Filled.Edit, "Edit ${profile.displayName}") }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = { vm.newProfile(ServerType.IDRAC6) }, modifier = Modifier.fillMaxWidth().testTag("add-idrac")) {
                        Text("Add iDRAC6")
                    }
                    Button(onClick = { vm.newProfile(ServerType.PROXMOX) }, modifier = Modifier.fillMaxWidth().testTag("add-proxmox")) {
                        Text("Add Proxmox VE server")
                    }
                    OutlinedButton(onClick = { pickFile.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Import iDRAC viewer.jnlp")
                    }
                }
            }
        }
    }

    if (showHelp) {
        AppHelpDialog(
            version = updateState.installedVersion,
            checking = updateState.checking,
            onCheckForUpdate = { update.checkForUpdate() },
            onDismiss = { showHelp = false },
        )
    }
}

@Composable
private fun UpdateCard(release: AppRelease, progress: Float?, update: UpdateViewModel) {
    Card(Modifier.fillMaxWidth().testTag("update-card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Update available: build ${release.versionCode}", fontWeight = FontWeight.SemiBold)
            if (progress != null) {
                if (progress >= 0f) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Text("Downloading…", style = MaterialTheme.typography.bodySmall)
            } else {
                Text(
                    "Android asks you to confirm the install. Your servers and saved passwords are kept.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = update::downloadAndInstall, modifier = Modifier.testTag("update-install")) {
                        Text("Update")
                    }
                    TextButton(onClick = update::dismiss) { Text("Later") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditServerScreen(screen: Screen.Edit, vm: ServersViewModel) {
    val original = screen.profile
    val isIdrac = original.type == ServerType.IDRAC6
    var name by rememberSaveable { mutableStateOf(original.name) }
    var host by rememberSaveable { mutableStateOf(original.host) }
    var username by rememberSaveable { mutableStateOf(original.username) }
    var password by rememberSaveable { mutableStateOf(vm.password(original).orEmpty()) }
    var remember by rememberSaveable { mutableStateOf(original.rememberPassword) }
    var port by rememberSaveable { mutableStateOf(original.port.toString()) }
    var sshPort by rememberSaveable { mutableStateOf(original.sshPort.toString()) }
    var realm by rememberSaveable { mutableStateOf(original.realm) }
    var tokenId by rememberSaveable { mutableStateOf(original.apiTokenId) }
    var tokenSecret by rememberSaveable { mutableStateOf("") }
    var consoleUrl by rememberSaveable { mutableStateOf(original.consoleUrl) }
    var webPort by rememberSaveable { mutableStateOf(original.webPort.toString()) }
    val webPortValue = webPort.toIntOrNull()?.takeIf { it in 1..65535 }
    val consoleUrlValid = consoleUrl.isBlank() || consoleUrl.startsWith("http://") || consoleUrl.startsWith("https://")
    var showPassword by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val portValue = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val sshPortValue = sshPort.toIntOrNull()?.takeIf { it in 1..65535 }
    val hasTokenSecret = tokenId.isBlank() || tokenSecret.isNotEmpty() ||
        (original.apiTokenId.isNotBlank() && vm.apiTokenSecret(original) != null)
    val valid = host.isNotBlank() && username.isNotBlank() && portValue != null &&
        (isIdrac || sshPortValue != null) && hasTokenSecret && consoleUrlValid &&
        (!isIdrac || webPortValue != null)

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = { Text(if (screen.isNew) "Add ${original.type.label}" else "Edit ${original.displayName}") },
            navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name (optional)") },
                placeholder = { Text(if (isIdrac) "R710 iDRAC" else "Proxmox") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("field-name"),
            )
            OutlinedTextField(
                value = host,
                onValueChange = { host = it.trim() },
                label = { Text(if (isIdrac) "iDRAC IP address" else "Proxmox IP address") },
                placeholder = { Text("192.0.2.10") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().testTag("field-host"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it.trim() },
                    label = { Text("Username") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    modifier = Modifier.weight(1f).testTag("field-username"),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text(if (isIdrac) "SSH port" else "Web port") },
                    singleLine = true,
                    isError = portValue == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(110.dp).testTag("field-port"),
                )
            }
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                supportingText = { if (!remember) Text("Not saved; you'll be asked when connecting.") },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            if (showPassword) "Hide password" else "Show password",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("field-password"),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = remember, onCheckedChange = { remember = it }, modifier = Modifier.testTag("field-remember"))
                Text("Remember password (encrypted with the Android Keystore)")
            }

            if (isIdrac) {
                Text(
                    "Use the same login as the iDRAC web page (Dell default: root / calvin). " +
                        "SSH must be enabled on the iDRAC.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = webPort,
                    onValueChange = { webPort = it.filter(Char::isDigit).take(5) },
                    label = { Text("Web port (for the screen preview)") },
                    singleLine = true,
                    isError = webPortValue == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag("field-web-port"),
                )
                OutlinedTextField(
                    value = consoleUrl,
                    onValueChange = { consoleUrl = it.trim() },
                    label = { Text("Graphical console URL (optional)") },
                    placeholder = { Text("http://192.0.2.20:5800") },
                    supportingText = {
                        Text(
                            if (consoleUrlValid) {
                                "noVNC page of the iDRAC6 Java viewer running in Docker on another computer " +
                                    "(see the help). Shows the real screen, including BIOS and boot."
                            } else {
                                "Must start with http:// or https://"
                            },
                        )
                    },
                    isError = !consoleUrlValid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().testTag("field-console-url"),
                )
            } else {
                Text("Realm", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("pam" to "Linux PAM", "pve" to "Proxmox VE").forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = realm == value,
                            onClick = { realm = value },
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                        ) { Text(label) }
                    }
                }
                OutlinedTextField(
                    value = sshPort,
                    onValueChange = { sshPort = it.filter(Char::isDigit).take(5) },
                    label = { Text("SSH port (for the shell)") },
                    singleLine = true,
                    isError = sshPortValue == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag("field-ssh-port"),
                )
                Text("API token (optional)", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Only needed if your account uses two-factor login. Create one under " +
                        "Datacenter > Permissions > API Tokens (uncheck Privilege Separation for full access).",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = tokenId,
                    onValueChange = { tokenId = it.trim() },
                    label = { Text("Token ID") },
                    placeholder = { Text("root@pam!phone") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (tokenId.isNotBlank()) {
                    OutlinedTextField(
                        value = tokenSecret,
                        onValueChange = { tokenSecret = it.trim() },
                        label = { Text("Token secret") },
                        placeholder = { Text(if (hasTokenSecret) "(saved — leave empty to keep)" else "xxxxxxxx-xxxx-…") },
                        singleLine = true,
                        isError = !hasTokenSecret,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Button(
                onClick = {
                    vm.save(
                        original.copy(
                            name = name.trim(),
                            host = host,
                            username = username,
                            port = portValue ?: original.port,
                            sshPort = sshPortValue ?: original.sshPort,
                            realm = realm,
                            apiTokenId = tokenId,
                            rememberPassword = remember,
                            consoleUrl = if (isIdrac) consoleUrl else "",
                            webPort = webPortValue ?: original.webPort,
                        ),
                        password = password,
                        apiTokenSecret = tokenSecret,
                    )
                },
                enabled = valid,
                modifier = Modifier.fillMaxWidth().testTag("save"),
            ) { Text("Save") }
            if (!screen.isNew) {
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete server", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete ${original.displayName}?",
            text = "Removes the saved server and its stored password from this phone.",
            confirmLabel = "Delete",
            onConfirm = { vm.delete(original) },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
fun AppHelpDialog(version: String, checking: Boolean, onCheckForUpdate: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("About") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("iDRAC6", fontWeight = FontWeight.SemiBold)
                Text(
                    "Logs in to the iDRAC6 over SSH (no Java). From there you can run racadm commands " +
                        "(power on/off/reset, logs, sensors) and open the server's text console with " +
                        "\"console com2\" (Serial Over LAN). The graphical Java Virtual Console is not supported.",
                )
                Text(
                    "A viewer.jnlp file only tells the app the iDRAC address and username. The user=/passwd= " +
                        "numbers in it are one-time tokens; use your real iDRAC password.",
                )
                Text("Proxmox VE", fontWeight = FontWeight.SemiBold)
                Text(
                    "Uses the Proxmox API: overview, start/stop/shutdown/reboot of VMs and containers, " +
                        "snapshots, backups, tasks, node reboot/shutdown, plus an SSH shell. Anything else " +
                        "is available in the full Proxmox web interface built into the app (VM consoles included), " +
                        "logged in automatically.",
                )
                Text("Security", fontWeight = FontWeight.SemiBold)
                Text(
                    "Passwords are only stored if you ask, encrypted with the Android Keystore. SSH host keys " +
                        "and Proxmox's self-signed certificate are pinned the first time you connect; you are " +
                        "warned if they change.",
                )
                Text(
                    "Open source (Apache-2.0): github.com/ServerTooling/DellServerTools",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
                Text("Version $version", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        dismissButton = {
            TextButton(onClick = onCheckForUpdate, enabled = !checking, modifier = Modifier.testTag("check-update")) {
                Text(if (checking) "Checking…" else "Check for updates")
            }
        },
    )
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).background(color, CircleShape))
}
