package com.lilayam.dellservertools.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lilayam.dellservertools.core.proxmox.AcmeAccountForm
import com.lilayam.dellservertools.core.proxmox.AcmeDomain
import com.lilayam.dellservertools.core.proxmox.AcmeSettings
import com.lilayam.dellservertools.core.proxmox.CertificateInfo
import com.lilayam.dellservertools.core.proxmox.CustomCertificateForm
import com.lilayam.dellservertools.core.proxmox.DiskCreateForm
import com.lilayam.dellservertools.core.proxmox.DiskKind
import com.lilayam.dellservertools.core.proxmox.DiskVolume
import com.lilayam.dellservertools.core.proxmox.Format
import com.lilayam.dellservertools.core.proxmox.PhysicalDisk
import com.lilayam.dellservertools.core.proxmox.SmartData

private fun PhysicalDisk.short(): String = devPath.removePrefix("/dev/")

// ---------------------------------------------------------------- disks

/** Node > Disks of the web UI: SMART, wipe, GPT, and LVM / LVM-Thin / Directory / ZFS on unused disks. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DisksTab(node: String, vms: AppViewModels, version: Int, busy: Boolean, reload: () -> Unit) {
    val vm = vms.proxmox
    val (disks, error) = rememberLoaded(vms, version) { it.disks(node) }
    val (volumes, _) = rememberLoaded(vms, version) { it.diskVolumes(node) }
    var opened by remember { mutableStateOf<PhysicalDisk?>(null) }
    var wiping by remember { mutableStateOf<PhysicalDisk?>(null) }
    var creating by remember { mutableStateOf<DiskCreateForm?>(null) }
    var destroying by remember { mutableStateOf<DiskVolume?>(null) }
    val unused = DiskCreateForm.unused(disks.orEmpty())

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        loading(disks, error)
        items(disks.orEmpty(), key = { "disk-${it.devPath}" }) { d ->
            Card(Modifier.fillMaxWidth().clickable { opened = d }.testTag("disk-${d.short()}")) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(if (d.health.uppercase() in setOf("PASSED", "OK")) Green else if (d.health.isBlank()) Amber else Red)
                        Text("  ${d.devPath}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(Format.bytes(d.size), style = MaterialTheme.typography.labelSmall)
                    }
                    Text(
                        listOf(d.type, d.model, d.used.ifBlank { "unused" }).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            Column {
                Text("Create on an unused disk", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                if (disks != null && unused.isEmpty()) {
                    Text("No unused disk. Wipe one first.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DiskKind.entries.forEach { kind ->
                    OutlinedButton(
                        onClick = { creating = DiskCreateForm(kind, devices = unused.take(1).map { it.devPath }) },
                        enabled = !busy && unused.isNotEmpty(),
                        modifier = Modifier.testTag("disk-create-${kind.path}"),
                    ) { Text(kind.label) }
                }
            }
        }
        if (!volumes.isNullOrEmpty()) {
            item { Text("Built on the disks", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp)) }
        }
        items(volumes.orEmpty(), key = { "vol-${it.kind}-${it.name}" }) { v ->
            Card(Modifier.fillMaxWidth().testTag("vol-${v.kind.path}-${v.name}")) {
                Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${v.name} (${v.kind.label})", fontWeight = FontWeight.SemiBold)
                        val usage = if (v.size > 0) "${Format.bytes(v.used)} / ${Format.bytes(v.size)}" else ""
                        Text(listOf(usage, v.detail).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(
                        onClick = { destroying = v },
                        enabled = !busy,
                        modifier = Modifier.testTag("vol-destroy-${v.kind.path}-${v.name}"),
                    ) { Text("Destroy", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }

    opened?.let { d ->
        DiskDialog(
            node,
            d,
            vms,
            busy,
            onDismiss = { opened = null },
            onInitGpt = { vm.task("Initialize ${d.devPath} with GPT", node, reload) { it.initGpt(node, d.devPath) }; opened = null },
            onWipe = { wiping = d; opened = null },
        )
    }
    wiping?.let { d ->
        TypedConfirmDialog(
            title = "Wipe ${d.devPath}?",
            text = "Erases the partition table and every file system signature on ${d.devPath}. Its data is gone.",
            expected = d.short(),
            confirmLabel = "Wipe",
            tag = "wipe",
            onConfirm = { vm.task("Wipe ${d.devPath}", node, reload) { it.wipeDisk(node, d.devPath) } },
            onDismiss = { wiping = null },
        )
    }
    creating?.let { form ->
        DiskCreateDialog(form, unused, onDismiss = { creating = null }) { f ->
            vm.task("Create ${f.kind.label} ${f.name}", node, reload) { it.createDiskVolume(node, f) }
            creating = null
        }
    }
    destroying?.let { v ->
        TypedConfirmDialog(
            title = "Destroy ${v.kind.label} ${v.name}?",
            text = "Removes ${v.name}, its storage entry and wipes its disks. Every guest disk and backup on it is lost.",
            expected = v.name,
            confirmLabel = "Destroy",
            tag = "destroy",
            onConfirm = { vm.task("Destroy ${v.kind.label} ${v.name}", node, reload) { it.destroyDiskVolume(node, v) } },
            onDismiss = { destroying = null },
        )
    }
}

@Composable
private fun DiskDialog(
    node: String,
    disk: PhysicalDisk,
    vms: AppViewModels,
    busy: Boolean,
    onDismiss: () -> Unit,
    onInitGpt: () -> Unit,
    onWipe: () -> Unit,
) {
    var smart by remember { mutableStateOf<SmartData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(disk.devPath) {
        vms.proxmox.fetch { it.smart(node, disk.devPath) }.onSuccess { smart = it }.onFailure { error = it.message }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(disk.devPath) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(listOf(disk.model, disk.type, Format.bytes(disk.size)).filter { it.isNotBlank() }.joinToString(" · "))
                if (disk.used.isNotBlank()) Text("Used by: ${disk.used}", style = MaterialTheme.typography.bodySmall)
                val s = smart
                when {
                    s != null -> {
                        Text("SMART: ${s.health.ifBlank { "unknown" }}", fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("smart-health"))
                        s.attributes.forEach { a ->
                            Text(
                                "${a.id} ${a.name}: ${a.value} (threshold ${a.threshold}) raw ${a.raw}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (a.failing) Red else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        if (s.attributes.isEmpty() && s.text.isNotBlank()) {
                            Text(s.text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                    }
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                    else -> Text("Reading SMART…")
                }
                if (disk.used.isBlank()) {
                    TextButton(onClick = onInitGpt, enabled = !busy, modifier = Modifier.testTag("disk-gpt")) { Text("Initialize with GPT") }
                }
                TextButton(onClick = onWipe, enabled = !busy, modifier = Modifier.testTag("disk-wipe")) {
                    Text("Wipe disk", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiskCreateDialog(initial: DiskCreateForm, unused: List<PhysicalDisk>, onDismiss: () -> Unit, onCreate: (DiskCreateForm) -> Unit) {
    var f by remember { mutableStateOf(initial) }
    val problem = f.validate()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create ${f.kind.label}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RuleField("Name", f.name, "disk-name") { f = f.copy(name = it) }
                Text(if (f.kind == DiskKind.ZFS) "Disks" else "Disk", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    unused.forEach { d ->
                        val on = d.devPath in f.devices
                        FilterChip(
                            selected = on,
                            onClick = {
                                f = when {
                                    f.kind != DiskKind.ZFS -> f.copy(devices = listOf(d.devPath))
                                    on -> f.copy(devices = f.devices - d.devPath)
                                    else -> f.copy(devices = f.devices + d.devPath)
                                }
                            },
                            label = { Text("${d.short()} ${Format.bytes(d.size)}") },
                            modifier = Modifier.testTag("disk-dev-${d.short()}"),
                        )
                    }
                }
                when (f.kind) {
                    DiskKind.ZFS -> {
                        Text("RAID level", style = MaterialTheme.typography.labelMedium)
                        ChipRow(DiskCreateForm.RAID_MIN.keys.map { it to it }, f.raidLevel, "disk-raid") { f = f.copy(raidLevel = it) }
                        Dropdown("Compression", DiskCreateForm.COMPRESSIONS.map { it to it }, f.compression) { f = f.copy(compression = it) }
                        RuleField("ashift", f.ashift, "disk-ashift") { f = f.copy(ashift = it.filter(Char::isDigit)) }
                    }
                    DiskKind.DIRECTORY -> {
                        Text("File system", style = MaterialTheme.typography.labelMedium)
                        ChipRow(DiskCreateForm.FILESYSTEMS.map { it to it }, f.filesystem, "disk-fs") { f = f.copy(filesystem = it) }
                    }
                    else -> Unit
                }
                CheckRow("Add storage", f.addStorage, Modifier.testTag("disk-add-storage")) { f = f.copy(addStorage = it) }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(f) }, enabled = problem == null, modifier = Modifier.testTag("disk-create-go")) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- certificates

/** Node > Certificates of the web UI: what is served, a custom upload, and ACME (Let's Encrypt) domains and orders. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CertificatesTab(node: String, vms: AppViewModels, version: Int, busy: Boolean, reload: () -> Unit) {
    val vm = vms.proxmox
    val (certs, error) = rememberLoaded(vms, version) { it.certificates(node) }
    val (acme, _) = rememberLoaded(vms, version) { it.acmeSettings(node) }
    val (accounts, _) = rememberLoaded(vms, version) { it.acmeAccounts() }
    val (plugins, _) = rememberLoaded(vms, version) { it.acmeDnsPlugins() }
    var uploading by remember { mutableStateOf(false) }
    var removingCustom by remember { mutableStateOf(false) }
    var domain by remember { mutableStateOf<AcmeDomain?>(null) }
    var registering by remember { mutableStateOf(false) }
    var ordering by remember { mutableStateOf<Boolean?>(null) }
    val list = certs.orEmpty()
    val custom = list.firstOrNull { it.isCustom }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        loading(certs, error)
        items(list, key = { "cert-${it.filename}" }) { c -> CertificateCard(c, served = c == CertificateInfo.served(list)) }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { uploading = true }, enabled = !busy, modifier = Modifier.testTag("cert-upload")) {
                    Text("Upload custom certificate")
                }
                if (custom != null) {
                    OutlinedButton(onClick = { removingCustom = true }, enabled = !busy, modifier = Modifier.testTag("cert-remove")) {
                        Text("Remove custom", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        item { Text("ACME (Let's Encrypt)", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp)) }
        if (acme != null) {
            val a: AcmeSettings = acme
            item {
                Text(
                    if (accounts.isNullOrEmpty()) "No ACME account yet: register one to order certificates."
                    else "Account: ${a.account.ifEmpty { "default" }} (registered: ${accounts.joinToString()})",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            items(a.domains, key = { "acme-${it.slot}-${it.domain}" }) { d ->
                Card(Modifier.fillMaxWidth().testTag("acme-domain-${d.domain}")) {
                    Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(d.domain, fontWeight = FontWeight.SemiBold)
                            Text(if (d.plugin.isEmpty()) "HTTP challenge (port 80)" else "DNS: ${d.plugin}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (d.slot != "acme") {
                            TextButton(
                                onClick = { vm.change("Remove ${d.domain}", reload) { it.setNodeConfig(node, a.removeDomain(d.slot)) } },
                                enabled = !busy,
                                modifier = Modifier.testTag("acme-remove-${d.domain}"),
                            ) { Text("Remove") }
                        }
                    }
                }
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    a.freeSlot?.let { slot ->
                        OutlinedButton(onClick = { domain = AcmeDomain(slot, "") }, enabled = !busy, modifier = Modifier.testTag("acme-add")) {
                            Text("Add domain")
                        }
                    }
                    OutlinedButton(onClick = { registering = true }, enabled = !busy, modifier = Modifier.testTag("acme-register")) {
                        Text("Register account")
                    }
                    if (a.domains.isNotEmpty() && !accounts.isNullOrEmpty()) {
                        OutlinedButton(onClick = { ordering = false }, enabled = !busy, modifier = Modifier.testTag("acme-order")) {
                            Text("Order certificate")
                        }
                        if (custom != null) {
                            OutlinedButton(onClick = { ordering = true }, enabled = !busy, modifier = Modifier.testTag("acme-renew")) {
                                Text("Renew")
                            }
                        }
                    }
                }
            }
        }
    }

    if (uploading) {
        UploadCertificateDialog(onDismiss = { uploading = false }) { form ->
            vm.change("Upload certificate", reload) { c ->
                c.uploadCertificate(node, form)?.let { vm.reconnectWithCertificate(it.fingerprint) }
            }
            uploading = false
        }
    }
    if (removingCustom) {
        ConfirmDialog(
            title = "Remove the custom certificate?",
            text = "The node serves Proxmox's own self-signed certificate again. The app trusts it at once, since it is " +
                "the one it already knows from this node.",
            confirmLabel = "Remove",
            onConfirm = {
                val own = list.firstOrNull { it.filename == CertificateInfo.DEFAULT }?.fingerprint
                vm.change("Remove custom certificate", reload) { c ->
                    c.deleteCustomCertificate(node)
                    vm.reconnectWithCertificate(own)
                }
            },
            onDismiss = { removingCustom = false },
        )
    }
    domain?.let { d ->
        AcmeDomainDialog(d, plugins.orEmpty(), onDismiss = { domain = null }) { saved ->
            val settings = acme ?: return@AcmeDomainDialog
            vm.change("Add ${saved.domain}", reload) { it.setNodeConfig(node, settings.setDomain(saved)) }
            domain = null
        }
    }
    if (registering) {
        AcmeAccountDialog(vms, onDismiss = { registering = false }) { form ->
            vm.task("Register ACME account ${form.name}", node, reload) { it.registerAcmeAccount(form) }
            registering = false
        }
    }
    ordering?.let { renew ->
        ConfirmDialog(
            title = if (renew) "Renew the certificate?" else "Order a certificate?",
            text = "The CA checks every domain, then pveproxy restarts with the new certificate. The app then asks you to " +
                "trust it once, since its fingerprint changes.",
            confirmLabel = if (renew) "Renew" else "Order",
            onConfirm = { vm.task(if (renew) "Renew certificate" else "Order certificate", node, reload) { it.orderAcmeCertificate(node, renew) } },
            onDismiss = { ordering = null },
        )
    }
}

@Composable
private fun CertificateCard(c: CertificateInfo, served: Boolean) {
    Card(Modifier.fillMaxWidth().testTag("cert-${c.filename}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val expired = c.notAfter in 1 until System.currentTimeMillis() / 1000
                StatusDot(if (expired) Red else if (served) Green else Amber)
                Text("  ${c.filename}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (served) Text("served", style = MaterialTheme.typography.labelSmall)
            }
            Text("Subject: ${c.subject}", style = MaterialTheme.typography.bodySmall)
            Text("Issuer: ${c.issuer}", style = MaterialTheme.typography.bodySmall)
            if (c.notAfter > 0) Text("Valid until ${formatStorageTime(c.notAfter)}", style = MaterialTheme.typography.bodySmall)
            if (c.san.isNotEmpty()) Text("Names: ${c.san.joinToString()}", style = MaterialTheme.typography.bodySmall)
            Text(c.fingerprint, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
    }
}

@Composable
private fun UploadCertificateDialog(onDismiss: () -> Unit, onUpload: (CustomCertificateForm) -> Unit) {
    var f by remember { mutableStateOf(CustomCertificateForm()) }
    val problem = f.validate()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Upload custom certificate") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Paste the PEM text. pveproxy restarts with it, and the app trusts the new certificate since you " +
                        "installed it yourself.",
                    style = MaterialTheme.typography.bodySmall,
                )
                PemField("Certificate chain", f.certificates, "cert-pem") { f = f.copy(certificates = it) }
                PemField("Private key (empty keeps the current one)", f.key, "cert-key") { f = f.copy(key = it) }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onUpload(f) }, enabled = problem == null, modifier = Modifier.testTag("cert-upload-go")) { Text("Upload") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PemField(label: String, value: String, tag: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 240.dp).testTag(tag),
    )
}

@Composable
private fun AcmeDomainDialog(initial: AcmeDomain, plugins: List<String>, onDismiss: () -> Unit, onSave: (AcmeDomain) -> Unit) {
    var d by remember { mutableStateOf(initial) }
    val valid = AcmeSettings.validDomain(d.domain)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add ACME domain") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RuleField("Domain", d.domain, "acme-domain") { d = d.copy(domain = it.lowercase()) }
                Text("Challenge", style = MaterialTheme.typography.labelMedium)
                ChipRow(listOf("" to "HTTP") + plugins.map { it to "DNS: $it" }, d.plugin, "acme-plugin") { d = d.copy(plugin = it) }
                if (d.plugin.isEmpty()) {
                    Text("HTTP needs port 80 of this node reachable from the internet.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(d) }, enabled = valid, modifier = Modifier.testTag("acme-domain-save")) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AcmeAccountDialog(vms: AppViewModels, onDismiss: () -> Unit, onRegister: (AcmeAccountForm) -> Unit) {
    val (directories, error) = rememberLoaded(vms, 0) { it.acmeDirectories() }
    var f by remember { mutableStateOf(AcmeAccountForm()) }
    var tos by remember { mutableStateOf("") }
    var accepted by remember { mutableStateOf(false) }
    LaunchedEffect(directories) { if (f.directory.isEmpty()) directories?.firstOrNull()?.let { f = f.copy(directory = it.url) } }
    LaunchedEffect(f.directory) {
        tos = ""
        accepted = false
        if (f.directory.isNotEmpty()) vms.proxmox.fetch { it.acmeTermsOfService(f.directory) }.onSuccess { tos = it }
    }
    val problem = f.validate() ?: if (tos.isNotEmpty() && !accepted) "Accept the terms of service." else null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Register ACME account") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RuleField("Account name", f.name, "acme-account-name") { f = f.copy(name = it) }
                RuleField("E-mail", f.contact, "acme-account-email") { f = f.copy(contact = it) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                ChipRow(directories.orEmpty().map { it.url to it.name }, f.directory, "acme-dir") { f = f.copy(directory = it) }
                if (tos.isNotEmpty()) {
                    Text("Terms of service: $tos", style = MaterialTheme.typography.bodySmall)
                    CheckRow("I accept the terms of service", accepted, Modifier.testTag("acme-tos")) { accepted = it }
                }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onRegister(f.copy(tosUrl = tos)) },
                enabled = problem == null,
                modifier = Modifier.testTag("acme-account-go"),
            ) { Text("Register") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
