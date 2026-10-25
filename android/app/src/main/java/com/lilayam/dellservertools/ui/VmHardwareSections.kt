package com.lilayam.dellservertools.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lilayam.dellservertools.core.proxmox.GuestEdit
import com.lilayam.dellservertools.core.proxmox.IpConfig
import com.lilayam.dellservertools.core.proxmox.PciDevice
import com.lilayam.dellservertools.core.proxmox.TemplateFile
import com.lilayam.dellservertools.core.proxmox.UsbDevice
import com.lilayam.dellservertools.core.proxmox.VmHardware
import kotlinx.coroutines.launch

/** Config keys the typed VM sections below edit, so "All settings" doesn't list them twice. */
internal val VM_TYPED_KEYS = setOf(
    "bios", "machine", "vga", "cpu", "scsihw", "ostype",
    "ciuser", "cipassword", "sshkeys", "ipconfig0", "citype",
)

/** Firmware, machine, display, CPU type, SCSI controller and OS type, as the web UI's Hardware and Options tabs. */
@Composable
internal fun VmHardwareSection(e: GuestEdit, storages: List<String>, onChange: (GuestEdit) -> Unit) {
    Section("Hardware")
    Dropdown("BIOS", VmHardware.withCurrent(VmHardware.BIOS, e["bios"]), e["bios"]) { onChange(e.with("bios", it)) }
    if (e["bios"] == "ovmf" && "efidisk0" !in e.values) {
        Note("UEFI keeps its settings (boot entries, Secure Boot keys) on an EFI disk. Without one they reset at every start.")
        var adding by remember { mutableStateOf(false) }
        OutlinedButton(onClick = { adding = true }, modifier = Modifier.testTag("hw-add-efi")) { Text("Add EFI disk") }
        if (adding) {
            StorageChoiceDialog("Add EFI disk", storages, onDismiss = { adding = false }) {
                onChange(e.with("efidisk0", VmHardware.efiDisk(it)))
                adding = false
            }
        }
    }
    if (e["bios"] == "ovmf" && "efidisk0" in e.values && !VmHardware.efiDiskHasKeys(e["efidisk0"])) {
        Note(
            "This EFI disk has no Secure Boot keys, so Secure Boot is off (Windows 11 setup refuses to install). " +
                "Remove efidisk0 below, save, then add the EFI disk again: a new one comes with the keys.",
        )
    }
    Dropdown("Machine", VmHardware.withCurrent(VmHardware.MACHINES, e["machine"]), e["machine"]) { onChange(e.with("machine", it)) }
    val vga = VmHardware.main(e["vga"])
    Dropdown("Display", VmHardware.withCurrent(VmHardware.DISPLAYS, vga), vga) { onChange(VmHardware.withMain(e, "vga", it)) }
    val cpu = VmHardware.main(e["cpu"])
    Dropdown("CPU type", VmHardware.withCurrent(VmHardware.CPU_TYPES, cpu), cpu) { onChange(VmHardware.withMain(e, "cpu", it)) }
    Dropdown("SCSI controller", VmHardware.withCurrent(VmHardware.SCSI_CONTROLLERS, e["scsihw"]), e["scsihw"]) {
        onChange(e.with("scsihw", it))
    }
    Dropdown("Guest OS", VmHardware.withCurrent(VmHardware.OS_TYPES, e["ostype"]), e["ostype"]) { onChange(e.with("ostype", it)) }
    Note("Firmware, machine and controller changes apply at the next stop and start.")
}

/** Each CD drive with an ISO picker instead of a volume id to type. */
@Composable
internal fun CdDrivesSection(e: GuestEdit, isos: List<TemplateFile>, onChange: (GuestEdit) -> Unit, onRemove: (String) -> Unit) {
    Section("CD/DVD drives")
    val drives = VmHardware.cdDrives(e)
    if (drives.isEmpty()) Note("No CD drive.")
    val choices = listOf("" to "Empty") + isos.map { it.volid to it.name }
    drives.forEach { key ->
        val iso = VmHardware.cdIso(e[key])
        Column(Modifier.testTag("cd-$key")) {
            Dropdown(key, VmHardware.withCurrent(choices, iso), iso) { onChange(VmHardware.withCdIso(e, key, it)) }
            TextButton(onClick = { onRemove(key) }, modifier = Modifier.testTag("cd-remove-$key")) { Text("Remove drive $key") }
        }
    }
    val next = VmHardware.nextCdKey(e)
    if (next != null) {
        OutlinedButton(
            onClick = { onChange(VmHardware.withCdIso(e, next, isos.firstOrNull()?.volid.orEmpty())) },
            modifier = Modifier.testTag("cd-add"),
        ) { Text("Add CD drive") }
    }
}

/** USB and PCI passthrough, TPM, serial port, RNG and audio: listed with a remove button and added from a menu. */
@Composable
internal fun DevicesSection(
    e: GuestEdit,
    node: String,
    storages: List<String>,
    vms: AppViewModels,
    onChange: (GuestEdit) -> Unit,
    row: @Composable (String) -> Unit,
) {
    Section("Devices")
    val keys = e.allKeys().filter { VmHardware.isDevice(it) }
    if (keys.isEmpty()) Note("No passthrough or extra devices.")
    keys.forEach { row(it) }
    var adding by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { adding = true }, modifier = Modifier.testTag("dev-add")) { Text("Add device") }
    if (adding) AddDeviceDialog(e, node, storages, vms, onDismiss = { adding = false }) { onChange(it); adding = false }
}

private enum class DeviceKind(val label: String) {
    USB("USB device from the host"),
    PCI("PCI device from the host"),
    TPM("TPM state (Windows 11)"),
    SERIAL("Serial port"),
    RNG("VirtIO random number generator"),
    AUDIO("Audio device"),
}

@Composable
private fun AddDeviceDialog(
    e: GuestEdit,
    node: String,
    storages: List<String>,
    vms: AppViewModels,
    onDismiss: () -> Unit,
    onAdd: (GuestEdit) -> Unit,
) {
    var kind by remember { mutableStateOf<DeviceKind?>(null) }
    // Loaded from the tap that opens the picker, not from a LaunchedEffect in it: under Robolectric the effect's
    // result never reached the dialog, while a scope launched from a click does (as in the download dialog).
    val scope = rememberCoroutineScope()
    var usb by remember { mutableStateOf<Result<List<UsbDevice>>?>(null) }
    var pci by remember { mutableStateOf<Result<List<PciDevice>>?>(null) }
    when (kind) {
        null -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Add device") },
            text = {
                Column {
                    DeviceKind.entries.forEach { k ->
                        val present = when (k) {
                            DeviceKind.TPM -> "tpmstate0" in e.values
                            DeviceKind.RNG -> "rng0" in e.values
                            DeviceKind.AUDIO -> "audio0" in e.values
                            else -> false
                        }
                        TextButton(
                            onClick = {
                                when (k) {
                                    DeviceKind.SERIAL -> onAdd(e.with(e.nextKey("serial"), VmHardware.SERIAL))
                                    DeviceKind.RNG -> onAdd(e.with("rng0", VmHardware.RNG))
                                    DeviceKind.AUDIO -> onAdd(e.with("audio0", VmHardware.AUDIO))
                                    DeviceKind.USB -> {
                                        kind = k
                                        scope.launch { usb = vms.proxmox.fetch { c -> c.usbDevices(node) } }
                                    }
                                    DeviceKind.PCI -> {
                                        kind = k
                                        scope.launch { pci = vms.proxmox.fetch { c -> c.pciDevices(node) } }
                                    }
                                    else -> kind = k
                                }
                            },
                            enabled = !present,
                            modifier = Modifier.fillMaxWidth().testTag("dev-kind-${k.name.lowercase()}"),
                        ) { Text(k.label + if (present) " (already added)" else "") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
        DeviceKind.TPM -> StorageChoiceDialog("Add TPM state", storages, onDismiss = onDismiss) {
            onAdd(e.with("tpmstate0", VmHardware.tpmState(it)))
        }
        DeviceKind.USB -> HostDevicePicker(
            title = "USB device",
            devices = usb,
            onDismiss = onDismiss,
            describe = { d: UsbDevice -> "${d.name.ifEmpty { "Unknown device" }} (${d.vendorId}:${d.productId})" },
            note = "Passed through by vendor and product ID, so it keeps working on another USB port.",
        ) { d -> onAdd(e.with(e.nextKey("usb"), VmHardware.usb(d))) }
        DeviceKind.PCI -> PciPicker(e, pci, onDismiss, onAdd)
        // Added straight from the list above, no second dialog.
        DeviceKind.SERIAL, DeviceKind.RNG, DeviceKind.AUDIO -> Unit
    }
}

/** Lists what the node has and adds the tapped one. */
@Composable
private fun <T> HostDevicePicker(
    title: String,
    devices: Result<List<T>>?,
    onDismiss: () -> Unit,
    describe: (T) -> String,
    note: String,
    extra: @Composable () -> Unit = {},
    onPick: (T) -> Unit,
) {
    val error = devices?.exceptionOrNull()?.let { it.message ?: "Couldn't load the devices." }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Note(note)
                extra()
                val list = devices?.getOrNull()
                when {
                    error != null -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    list == null -> Text("Loading…")
                    list.isEmpty() -> Text("None found on the node.")
                }
                list.orEmpty().forEachIndexed { i, d ->
                    Text(
                        describe(d),
                        modifier = Modifier.fillMaxWidth().clickable { onPick(d) }.padding(vertical = 10.dp).testTag("dev-pick-$i"),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PciPicker(e: GuestEdit, devices: Result<List<PciDevice>>?, onDismiss: () -> Unit, onAdd: (GuestEdit) -> Unit) {
    var allFunctions by remember { mutableStateOf(true) }
    // PCI-Express needs the q35 machine.
    var pcie by remember { mutableStateOf(e["machine"].contains("q35")) }
    HostDevicePicker(
        title = "PCI device",
        devices = devices,
        onDismiss = onDismiss,
        describe = { d: PciDevice -> "${d.id} ${d.name}" + if (d.iommuGroup >= 0) " (IOMMU group ${d.iommuGroup})" else "" },
        note = "Needs IOMMU enabled on the host. The VM can't be snapshotted with RAM or migrated while it has the device.",
        extra = {
            CheckRow("All functions", allFunctions) { allFunctions = it }
            CheckRow("PCI-Express (q35 only)", pcie, Modifier.testTag("dev-pcie")) { pcie = it }
        },
    ) { d -> onAdd(e.with(e.nextKey("hostpci"), VmHardware.pci(d, allFunctions, pcie))) }
}

@Composable
internal fun StorageChoiceDialog(title: String, storages: List<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var storage by remember(storages) { mutableStateOf(storages.firstOrNull { it.contains("zfs") } ?: storages.firstOrNull().orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Dropdown("Storage", storages.map { it to it }, storage) { storage = it }
                Note("Created when you save.")
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(storage) }, enabled = storage.isNotEmpty(), modifier = Modifier.testTag("storage-choice-go")) {
                Text("Add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The web UI's Cloud-Init tab: user, password, SSH keys, IP and DNS, and regenerating the drive. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CloudInitSection(
    e: GuestEdit,
    storages: List<String>,
    canRegenerate: Boolean,
    onRegenerate: () -> Unit,
    onChange: (GuestEdit) -> Unit,
) {
    Section("Cloud-init")
    val drive = VmHardware.cloudInitDrive(e)
    if (drive == null) {
        Note("For cloud images: the VM reads its user, SSH keys and network settings from a small cloud-init drive.")
        var adding by remember { mutableStateOf(false) }
        val key = VmHardware.nextCloudInitKey(e)
        OutlinedButton(onClick = { adding = true }, enabled = key != null, modifier = Modifier.testTag("ci-add")) {
            Text("Add cloud-init drive")
        }
        if (adding && key != null) {
            StorageChoiceDialog("Add cloud-init drive", storages, onDismiss = { adding = false }) {
                onChange(e.with(key, VmHardware.cloudInitDisk(it)))
                adding = false
            }
        }
        return
    }
    Note("Drive $drive. Changes reach the VM when the drive is regenerated, at the latest at its next start.")
    OutlinedTextField(
        value = e["ciuser"],
        onValueChange = { onChange(e.with("ciuser", it.trim())) },
        label = { Text("User (blank = image default)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().testTag("ci-user"),
    )
    val masked = VmHardware.passwordIsMasked(e["cipassword"])
    OutlinedTextField(
        value = if (masked) "" else e["cipassword"],
        onValueChange = { onChange(if (it.isEmpty() && masked) e else e.with("cipassword", it)) },
        label = { Text(if (masked) "Password (set; type to change)" else "Password") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().testTag("ci-password"),
    )
    if (masked) {
        TextButton(onClick = { onChange(e.without("cipassword")) }) { Text("Remove password") }
    }
    OutlinedTextField(
        value = VmHardware.sshKeys(e),
        onValueChange = { onChange(VmHardware.withSshKeys(e, it)) },
        label = { Text("SSH public keys (one per line)") },
        minLines = 2,
        maxLines = 6,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().testTag("ci-sshkeys"),
    )
    val ip = IpConfig.parse(e["ipconfig0"])
    fun setIp(new: IpConfig) = onChange(e.with("ipconfig0", new.format()))
    CheckRow("DHCP (IPv4)", e["ipconfig0"].isEmpty() || ip.dhcp, Modifier.testTag("ci-dhcp")) { on ->
        setIp(if (on) ip.copy(ip = "dhcp", gateway = "") else ip.copy(ip = ""))
    }
    if (e["ipconfig0"].isNotEmpty() && !ip.dhcp) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = ip.ip,
                onValueChange = { setIp(ip.copy(ip = it.trim())) },
                label = { Text("IPv4/CIDR") },
                singleLine = true,
                modifier = Modifier.testTag("ci-ip"),
            )
            OutlinedTextField(
                value = ip.gateway,
                onValueChange = { setIp(ip.copy(gateway = it.trim())) },
                label = { Text("Gateway") },
                singleLine = true,
                modifier = Modifier.testTag("ci-gw"),
            )
        }
        ip.validate()?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    OutlinedTextField(
        value = e["nameserver"],
        onValueChange = { onChange(e.with("nameserver", it.trim())) },
        label = { Text("DNS servers (blank = use host)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = e["searchdomain"],
        onValueChange = { onChange(e.with("searchdomain", it.trim())) },
        label = { Text("Search domain (blank = use host)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedButton(onClick = onRegenerate, enabled = canRegenerate, modifier = Modifier.testTag("ci-regenerate")) {
        Text("Regenerate image")
    }
    if (!canRegenerate) Note("Save your changes first; the image is built from the saved settings.")
}
