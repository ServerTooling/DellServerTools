package com.lilayam.dellservertools.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lilayam.dellservertools.core.proxmox.DownloadUrlRequest
import com.lilayam.dellservertools.core.proxmox.Format
import com.lilayam.dellservertools.core.proxmox.PruneEntry
import com.lilayam.dellservertools.core.proxmox.PruneOptions
import com.lilayam.dellservertools.core.proxmox.RestoreRequest
import com.lilayam.dellservertools.core.proxmox.StorageFiles
import com.lilayam.dellservertools.core.proxmox.StorageForm
import com.lilayam.dellservertools.core.proxmox.StorageKind
import com.lilayam.dellservertools.core.proxmox.StorageVolume
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- storage screen actions

/** The buttons under a storage's summary: upload, download, prune, edit, remove. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StorageActions(screen: Screen.Storage, content: List<String>, editable: Boolean, vms: AppViewModels) {
    val vm = vms.proxmox
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val fileContents = content.filter { it == "iso" || it == "vztmpl" }
    var picked by remember { mutableStateOf<PickedFile?>(null) }
    var download by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) picked = PickedFile.of(context, uri)
    }
    val idle = state.busy == null

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (fileContents.isNotEmpty()) {
            OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }, enabled = idle, modifier = Modifier.testTag("storage-upload")) {
                Text("Upload")
            }
            OutlinedButton(onClick = { download = true }, enabled = idle, modifier = Modifier.testTag("storage-download")) {
                Text("Download from URL")
            }
        }
        if ("backup" in content) {
            OutlinedButton(
                onClick = { vms.servers.navigate(Screen.Prune(screen.profileId, screen.node, screen.storage)) },
                modifier = Modifier.testTag("storage-prune"),
            ) { Text("Prune backups") }
        }
        if (editable) {
            OutlinedButton(
                onClick = { vms.servers.navigate(Screen.StorageForm(screen.profileId, screen.node, screen.storage)) },
                modifier = Modifier.testTag("storage-edit"),
            ) { Text("Edit") }
            OutlinedButton(onClick = { remove = true }, enabled = idle, modifier = Modifier.testTag("storage-remove")) {
                Text("Remove", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    picked?.let { file ->
        UploadDialog(file, fileContents, onDismiss = { picked = null }) { contentType, name ->
            vm.upload(screen.node, screen.storage, contentType, name, file.size) {
                context.contentResolver.openInputStream(file.uri) ?: throw IOException("Can't open ${file.name}")
            }
        }
    }
    if (download) DownloadUrlDialog(screen, fileContents, vms) { download = false }
    if (remove) {
        TypedConfirmDialog(
            title = "Remove storage ${screen.storage}?",
            text = "Proxmox forgets this storage. The files and disks on it are not deleted, but guests using it " +
                "can't start until it is added again.",
            expected = screen.storage,
            confirmLabel = "Remove",
            tag = "storage-remove",
            onConfirm = { vm.deleteStorage(screen.storage) { vms.servers.back() } },
            onDismiss = { remove = false },
        )
    }
}

/** A file picked on the phone; [size] is -1 when the provider doesn't say. */
private data class PickedFile(val uri: Uri, val name: String, val size: Long) {
    companion object {
        fun of(context: Context, uri: Uri): PickedFile {
            var name = uri.lastPathSegment.orEmpty().substringAfterLast('/')
            var size = -1L
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !c.isNull(it) }?.let { name = c.getString(it) }
                        c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                    }
                }
            return PickedFile(uri, name, size)
        }
    }
}

private fun guessContent(name: String, contents: List<String>): String =
    contents.firstOrNull { c -> StorageFiles.extensions(c).any { name.lowercase().endsWith(it) } } ?: contents.first()

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContentChoice(contents: List<String>, selected: String, onSelect: (String) -> Unit) {
    if (contents.size < 2) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        contents.forEach { c ->
            androidx.compose.material3.FilterChip(
                selected = c == selected,
                onClick = { onSelect(c) },
                label = { Text(contentLabel(c)) },
                modifier = Modifier.testTag("content-$c"),
            )
        }
    }
}

@Composable
private fun UploadDialog(file: PickedFile, contents: List<String>, onDismiss: () -> Unit, onUpload: (String, String) -> Unit) {
    var content by remember { mutableStateOf(guessContent(file.name, contents)) }
    var name by remember { mutableStateOf(file.name) }
    val problem = if (file.size < 0) "The phone can't tell this file's size, so it can't be uploaded." else StorageFiles.filenameError(content, name)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Upload ${Format.bytes(file.size.coerceAtLeast(0))}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ContentChoice(contents, content) { content = it }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.trim() },
                    label = { Text("File name on the server") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("upload-name"),
                )
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text("Keep the app open until the upload finishes.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onUpload(content, name)
                    onDismiss()
                },
                enabled = problem == null,
                modifier = Modifier.testTag("upload-go"),
            ) { Text("Upload") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DownloadUrlDialog(screen: Screen.Storage, contents: List<String>, vms: AppViewModels, onDismiss: () -> Unit) {
    val vm = vms.proxmox
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var content by remember { mutableStateOf(contents.first()) }
    var checksum by remember { mutableStateOf("") }
    var algorithm by remember { mutableStateOf("sha256") }
    var verify by remember { mutableStateOf(true) }
    var info by remember { mutableStateOf<String?>(null) }
    val request = DownloadUrlRequest(url, name, content, checksum, algorithm, verify)
    val problem = request.validate()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download from URL") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The server downloads the file itself, straight into \"${screen.storage}\".", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        // Follow the URL's file name until the user types their own.
                        if (name.isEmpty() || name == StorageFiles.filenameFromUrl(url)) name = StorageFiles.filenameFromUrl(it.trim())
                        url = it.trim()
                        info = null
                    },
                    label = { Text("URL") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().testTag("download-url"),
                )
                TextButton(
                    onClick = {
                        info = "Checking…"
                        scope.launch {
                            vm.fetch { it.queryUrlMetadata(screen.node, url, verify) }
                                .onSuccess { m ->
                                    if (m.filename.isNotEmpty()) name = m.filename
                                    content = guessContent(name, contents)
                                    info = listOfNotNull(m.size?.let(Format::bytes), m.mimetype.ifEmpty { null }).joinToString(" · ")
                                        .ifEmpty { "Reachable." }
                                }
                                .onFailure { info = it.message }
                        }
                    },
                    enabled = url.startsWith("http"),
                    modifier = Modifier.testTag("download-query"),
                ) { Text("Look up name and size") }
                info?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                ContentChoice(contents, content) { content = it }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.trim() },
                    label = { Text("File name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("download-name"),
                )
                Dropdown("Checksum type", DownloadUrlRequest.ALGORITHMS.map { it to it.uppercase() }, algorithm) { algorithm = it }
                OutlinedTextField(
                    value = checksum,
                    onValueChange = { checksum = it.trim() },
                    label = { Text("Checksum (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("download-checksum"),
                )
                CheckRow("Verify the server's certificate", verify) { verify = it }
                if (url.isNotEmpty()) problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    vm.downloadUrl(screen.node, screen.storage, request)
                    onDismiss()
                },
                enabled = problem == null,
                modifier = Modifier.testTag("download-go"),
            ) { Text("Download") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** What tapping a volume offers: restore a backup, its notes and protection, or deleting it. */
@Composable
internal fun VolumeDialog(screen: Screen.Storage, volume: StorageVolume, vms: AppViewModels, onDismiss: () -> Unit) {
    val vm = vms.proxmox
    val state by vm.state.collectAsStateWithLifecycle()
    val isBackup = volume.content == "backup"
    var notes by remember { mutableStateOf(volume.notes) }
    var protected by remember { mutableStateOf(volume.protected) }
    var confirmDelete by remember { mutableStateOf(false) }
    // As in the web UI: a guest's own disk is removed with the guest (or as an unused disk), not from here.
    val owner = state.resources.firstOrNull { it.vmid != null && it.vmid == volume.vmid && it.guestType != null }
    val inUse = owner != null && (volume.content == "images" || volume.content == "rootdir")
    val blocked = when {
        volume.protected -> "Protected backups can't be deleted. Turn protection off first."
        inUse -> "Belongs to ${owner?.guestType?.label} ${owner?.vmid}. Remove it from the guest's hardware instead."
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(volume.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    listOfNotNull(contentLabel(volume.content), Format.bytes(volume.size), volume.ctime?.let(::formatStorageTime))
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (isBackup && RestoreRequest.typeOf(volume) != null) {
                    OutlinedButton(
                        onClick = {
                            onDismiss()
                            vms.servers.navigate(Screen.Restore(screen.profileId, screen.node, volid = volume.volid))
                        },
                        modifier = Modifier.fillMaxWidth().testTag("volume-restore"),
                    ) { Text("Restore…") }
                }
                if (isBackup) {
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("Notes") },
                        modifier = Modifier.fillMaxWidth().testTag("volume-notes"),
                    )
                    CheckRow("Protected (never pruned or deleted)", protected, Modifier.testTag("volume-protected")) { protected = it }
                    OutlinedButton(
                        onClick = {
                            vm.updateVolume(screen.node, screen.storage, volume, notes, protected)
                            onDismiss()
                        },
                        enabled = (notes != volume.notes || protected != volume.protected) && state.busy == null,
                        modifier = Modifier.fillMaxWidth().testTag("volume-save"),
                    ) { Text("Save notes and protection") }
                }
                blocked?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(
                    onClick = {
                        if (!confirmDelete) {
                            confirmDelete = true
                        } else {
                            vm.deleteVolume(screen.node, screen.storage, volume)
                            onDismiss()
                        }
                    },
                    enabled = blocked == null && state.busy == null,
                    modifier = Modifier.fillMaxWidth().testTag("volume-delete"),
                ) {
                    Text(
                        if (confirmDelete) "Tap again to delete for good" else "Delete",
                        color = if (blocked == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** A destructive action confirmed by typing a name or ID. */
@Composable
internal fun TypedConfirmDialog(
    title: String,
    text: String,
    expected: String,
    confirmLabel: String,
    tag: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var typed by remember { mutableStateOf("") }
    val confirmed = typed.trim() == expected
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text, color = MaterialTheme.colorScheme.error)
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text("Type \"$expected\" to confirm") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("$tag-confirm"),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
                enabled = confirmed,
                modifier = Modifier.testTag("$tag-go"),
            ) { Text(confirmLabel, color = if (confirmed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

internal fun formatStorageTime(epochSeconds: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochSeconds * 1000))

// ---------------------------------------------------------------- prune

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PruneScreen(screen: Screen.Prune, vms: AppViewModels) {
    val vm = vms.proxmox
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    ForwardMessages(state.message, vms) { vm.clearMessage() }

    // The fields hold text so a cleared box means "not set".
    var fields by remember { mutableStateOf<Map<String, String>?>(null) }
    var vmid by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<List<PruneEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }

    fun options(): PruneOptions {
        val f = fields.orEmpty()
        fun n(k: String) = f[k]?.toIntOrNull()
        return PruneOptions(n("keep-last"), n("keep-hourly"), n("keep-daily"), n("keep-weekly"), n("keep-monthly"), n("keep-yearly"))
    }

    fun loadPreview() {
        error = null
        preview = null
        scope.launch {
            vm.fetch { it.prunePreview(screen.node, screen.storage, options(), vmid.toIntOrNull()) }
                .onSuccess { preview = it }
                .onFailure { error = it.message }
        }
    }

    // Start from the storage's own retention settings, as the web UI does.
    LaunchedEffect(screen, state.connected) {
        if (fields != null) return@LaunchedEffect
        val current = vm.fetch { it.storageDefinition(screen.storage) }.getOrNull()?.get("prune-backups")
        fields = PruneOptions.parse(current).counts.associate { (k, n) -> k to n?.toString().orEmpty() }
        loadPreview()
    }

    val opts = options()
    val removed = preview.orEmpty().filter { it.removed }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = { Text("Prune backups on ${screen.storage}") },
            navigationIcon = { IconButton(onClick = { vms.servers.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        BusyBar(state.busy)
        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            item {
                Text(
                    "How many backups to keep per guest. Empty boxes don't count; protected backups are always kept.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PRUNE_LABELS.forEach { (key, label) ->
                        NumField(
                            fields?.get(key).orEmpty(),
                            { v -> fields = fields.orEmpty() + (key to v.filter(Char::isDigit).take(4)); preview = null },
                            label,
                            Modifier.width(110.dp).testTag("prune-$key"),
                        )
                    }
                    NumField(
                        vmid,
                        { v -> vmid = v.filter(Char::isDigit).take(9); preview = null },
                        "Only guest",
                        Modifier.width(110.dp).testTag("prune-vmid"),
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = ::loadPreview, enabled = fields != null, modifier = Modifier.testTag("prune-preview")) {
                        Text("Preview")
                    }
                    Button(
                        onClick = { confirm = true },
                        enabled = preview != null && removed.isNotEmpty() && state.busy == null,
                        modifier = Modifier.testTag("prune-go"),
                    ) { Text("Remove ${removed.size}") }
                }
            }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (!opts.prunes) item { Text("No keep count set: every backup is kept.", style = MaterialTheme.typography.bodySmall) }
            val list = preview
            if (list == null && error == null) item { Text("Loading…") }
            if (list != null && list.isEmpty()) item { Text("No backups on ${screen.storage}.") }
            items(list.orEmpty(), key = { it.volid }) { e ->
                Card(Modifier.fillMaxWidth().testTag("prune-entry-${e.name}")) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                listOfNotNull(e.type.uppercase().ifEmpty { null }, e.vmid?.toString()).joinToString(" ") +
                                    e.ctime?.let { " · ${formatStorageTime(it)}" }.orEmpty(),
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(e.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(
                            e.mark,
                            color = if (e.removed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }

    if (confirm) {
        ConfirmDialog(
            title = "Remove ${removed.size} backups?",
            text = "They are deleted from ${screen.storage} for good.",
            confirmLabel = "Remove",
            onConfirm = { vm.pruneBackups(screen.node, screen.storage, opts, vmid.toIntOrNull()) { loadPreview() } },
            onDismiss = { confirm = false },
        )
    }
}

internal val PRUNE_LABELS = listOf(
    "keep-last" to "Last",
    "keep-hourly" to "Hourly",
    "keep-daily" to "Daily",
    "keep-weekly" to "Weekly",
    "keep-monthly" to "Monthly",
    "keep-yearly" to "Yearly",
)

// ---------------------------------------------------------------- add / edit storage

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StorageFormScreen(screen: Screen.StorageForm, vms: AppViewModels) {
    val vm = vms.proxmox
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    ForwardMessages(state.message, vms) { vm.clearMessage() }

    var form by remember {
        mutableStateOf(if (screen.storage == null) StorageForm("", StorageKind.DIR, setOf("iso", "vztmpl", "backup")) else null)
    }
    var loadError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(screen, state.connected) {
        val id = screen.storage ?: return@LaunchedEffect
        if (form != null) return@LaunchedEffect
        vm.fetch { it.storageDefinition(id) }
            .onSuccess { config ->
                form = StorageForm.fromConfig(config)
                if (form == null) loadError = "The app can't edit ${config["type"]} storage yet."
            }
            .onFailure { loadError = it.message }
    }
    var suggestions by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    fun suggest(field: String, block: (com.lilayam.dellservertools.core.proxmox.ProxmoxClient) -> List<String>) {
        scope.launch { vm.fetch(block).onSuccess { suggestions = suggestions + (field to it) } }
    }
    // What's on the node to pick from instead of typing.
    LaunchedEffect(form?.kind, form?.isNew) {
        val f = form ?: return@LaunchedEffect
        if (!f.isNew) return@LaunchedEffect
        when (f.kind) {
            StorageKind.ZFS -> suggest("pool") { it.scanZfs(screen.node) }
            StorageKind.LVM, StorageKind.LVMTHIN -> suggest("vgname") { it.scanLvm(screen.node) }
            else -> Unit
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = { Text(if (screen.storage == null) "Add storage" else "Edit storage ${screen.storage}") },
            navigationIcon = { IconButton(onClick = { vms.servers.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        BusyBar(state.busy)
        val f = form
        if (f == null) {
            Text(loadError ?: "Loading…", modifier = Modifier.padding(16.dp))
            return@Column
        }
        val problem = f.validate()
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (f.isNew) {
                OutlinedTextField(
                    value = f.id,
                    onValueChange = { form = f.copy(id = it.trim()) },
                    label = { Text("ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("storage-id"),
                )
                Dropdown("Type", StorageKind.entries.map { it.name to it.label }, f.kind.name) { name ->
                    val kind = StorageKind.valueOf(name)
                    suggestions = emptyMap()
                    form = f.copy(kind = kind, content = f.content.filter { it in kind.contents }.toSet().ifEmpty { setOf(kind.contents.first()) })
                }
            } else {
                Text("${f.kind.label} storage", fontWeight = FontWeight.SemiBold)
            }

            for (field in f.kind.fields) {
                val fixed = !f.isNew && field in f.kind.fixedFields
                val secret = field == "password"
                OutlinedTextField(
                    value = f[field],
                    onValueChange = { form = f.copy(fields = f.fields + (field to it)) },
                    label = { Text(FIELD_LABELS[field].orEmpty() + if (secret && !f.isNew) " (blank = unchanged)" else "") },
                    singleLine = true,
                    readOnly = fixed,
                    enabled = !fixed,
                    visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                    keyboardOptions = if (secret) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
                    modifier = Modifier.fillMaxWidth().testTag("storage-field-$field"),
                )
                val options = suggestions[field].orEmpty()
                if (options.isNotEmpty() && !fixed) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        options.forEach { option ->
                            SuggestionChip(
                                onClick = {
                                    form = f.copy(fields = f.fields + (field to option))
                                    if (field == "vgname" && f.kind == StorageKind.LVMTHIN) suggest("thinpool") { it.scanLvmThin(screen.node, option) }
                                },
                                label = { Text(option) },
                            )
                        }
                    }
                }
                if (field == "server" && f.kind == StorageKind.NFS && f.isNew) {
                    TextButton(
                        onClick = { suggest("export") { it.scanNfs(screen.node, f["server"].trim()) } },
                        enabled = f["server"].isNotBlank(),
                    ) { Text("Find exports") }
                }
            }

            Text("Content", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                f.kind.contents.forEach { c ->
                    androidx.compose.material3.FilterChip(
                        selected = c in f.content,
                        onClick = { form = f.copy(content = if (c in f.content) f.content - c else f.content + c) },
                        label = { Text(contentLabel(c)) },
                        modifier = Modifier.testTag("storage-content-$c"),
                    )
                }
            }
            CheckRow("Enabled", f.enabled, Modifier.testTag("storage-enabled")) { form = f.copy(enabled = it) }
            if (f.kind == StorageKind.DIR) {
                CheckRow("Shared (the same files on every node)", f.shared) { form = f.copy(shared = it) }
            }
            if ("backup" in f.content) {
                Text("Backup retention (empty = keep all)", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val counts = f.prune.counts.toMap()
                    PRUNE_LABELS.forEach { (key, label) ->
                        NumField(
                            counts[key]?.toString().orEmpty(),
                            { v -> form = f.copy(prune = f.prune.withCount(key, v.filter(Char::isDigit).take(4).toIntOrNull())) },
                            label,
                            Modifier.width(110.dp).testTag("storage-$key"),
                        )
                    }
                }
            }
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button(
                onClick = { vm.saveStorage(f) { vms.servers.back() } },
                enabled = problem == null && state.busy == null,
                modifier = Modifier.fillMaxWidth().testTag("storage-save"),
            ) { Text(if (f.isNew) "Add storage" else "Save") }
        }
    }
}

internal fun PruneOptions.withCount(key: String, n: Int?): PruneOptions = when (key) {
    "keep-last" -> copy(keepLast = n, keepAll = false)
    "keep-hourly" -> copy(keepHourly = n, keepAll = false)
    "keep-daily" -> copy(keepDaily = n, keepAll = false)
    "keep-weekly" -> copy(keepWeekly = n, keepAll = false)
    "keep-monthly" -> copy(keepMonthly = n, keepAll = false)
    "keep-yearly" -> copy(keepYearly = n, keepAll = false)
    else -> this
}

private val FIELD_LABELS = mapOf(
    "path" to "Directory on the node",
    "server" to "Server",
    "export" to "NFS export",
    "share" to "Share",
    "username" to "Username",
    "password" to "Password",
    "domain" to "Domain",
    "datastore" to "Datastore",
    "fingerprint" to "Certificate fingerprint",
    "namespace" to "Namespace",
    "pool" to "ZFS pool",
    "vgname" to "Volume group",
    "thinpool" to "Thin pool",
)
