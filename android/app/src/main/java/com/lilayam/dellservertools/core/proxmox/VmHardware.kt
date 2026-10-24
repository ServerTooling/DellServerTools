package com.lilayam.dellservertools.core.proxmox

import java.net.URLDecoder
import java.net.URLEncoder
import org.json.JSONArray

/**
 * The typed VM hardware choices of the web UI's Hardware, Options and Cloud-Init tabs, as edits of a [GuestEdit]:
 * firmware, machine, display, CPU type, controllers, CD drives, passthrough and extra devices, cloud-init.
 */
object VmHardware {
    // Pairs of config value and label; "" is the Proxmox default, which is sent as a delete.
    val BIOS = listOf("" to "SeaBIOS (default)", "ovmf" to "OVMF (UEFI)")
    val MACHINES = listOf("" to "i440fx (default)", "q35" to "q35")
    val DISPLAYS = listOf(
        "" to "Default",
        "std" to "Standard VGA",
        "virtio" to "VirtIO-GPU",
        "qxl" to "SPICE (qxl)",
        "vmware" to "VMware compatible",
        "serial0" to "Serial terminal 0",
        "none" to "None",
    )
    val CPU_TYPES = listOf(
        "" to "Default (x86-64-v2-AES)",
        "host" to "host",
        "x86-64-v2-AES" to "x86-64-v2-AES",
        "x86-64-v3" to "x86-64-v3",
        "x86-64-v4" to "x86-64-v4",
        "kvm64" to "kvm64",
        "qemu64" to "qemu64",
        "max" to "max",
    )
    val SCSI_CONTROLLERS = listOf(
        "" to "Default (LSI 53C895A)",
        "virtio-scsi-single" to "VirtIO SCSI single",
        "virtio-scsi-pci" to "VirtIO SCSI",
        "lsi" to "LSI 53C895A",
        "lsi53c810" to "LSI 53C810",
        "megasas" to "MegaRAID SAS 8708EM2",
        "pvscsi" to "VMware PVSCSI",
    )
    val OS_TYPES = listOf(
        "" to "Other / not set",
        "l26" to "Linux 6.x - 2.6",
        "win11" to "Windows 11/2022/2025",
        "win10" to "Windows 10/2016/2019",
        "win8" to "Windows 8/2012",
        "win7" to "Windows 7/2008r2",
        "wxp" to "Windows XP/2003",
        "solaris" to "Solaris",
        "other" to "Other",
    )

    /** Extra devices shown in their own list rather than with the raw settings. */
    private val DEVICE = Regex("^(usb|hostpci|serial|parallel|rng|audio|virtiofs)\\d+$")

    fun isDevice(key: String): Boolean = DEVICE.matches(key)

    /** The leading part of `virtio,memory=32` or `host,flags=+aes`. */
    fun main(value: String): String = value.substringBefore(',').takeUnless { it.contains('=') }.orEmpty()

    /** Replaces the leading part and keeps the options; an empty part with no options clears the key. */
    fun withMain(e: GuestEdit, key: String, main: String): GuestEdit {
        val value = e[key]
        val rest = if (value.isEmpty() || value.substringBefore(',').contains('=')) {
            value
        } else {
            value.substringAfter(',', "")
        }
        val parts = listOf(main, rest).filter { it.isNotEmpty() }
        if (main.isEmpty()) {
            // Options without a type (e.g. `memory=32`) are still valid for vga; cpu needs its type.
            return e.with(key, if (key == "cpu") "" else rest)
        }
        return e.with(key, parts.joinToString(","))
    }

    /** A dropdown list that also shows a value the app doesn't know (e.g. `pc-q35-8.1`) instead of hiding it. */
    fun withCurrent(items: List<Pair<String, String>>, value: String): List<Pair<String, String>> =
        if (items.any { it.first == value }) items else items + (value to value)

    // ---- CD drives

    fun cdDrives(e: GuestEdit): List<String> = e.allKeys().filter { it in e.values && GuestEdit.isDisk(it) && e.isCdrom(it) }

    /** The ISO in a CD drive, or "" when empty. */
    fun cdIso(value: String): String = value.substringBefore(',').let { if (it == "none" || it == "cdrom") "" else it }

    /** Puts an ISO (volid) in a drive, or empties it with "". */
    fun withCdIso(e: GuestEdit, key: String, iso: String): GuestEdit = e.with(key, "${iso.ifEmpty { "none" }},media=cdrom")

    /** A free bus for a new CD drive: ide2 as in the web UI, then the other IDE slots, then SATA. */
    fun nextCdKey(e: GuestEdit): String? =
        (listOf("ide2", "ide0", "ide1", "ide3") + (0..5).map { "sata$it" }).firstOrNull { it !in e.values && it !in e.current }

    // ---- extra devices

    fun efiDisk(storage: String): String = "$storage:1,efitype=4m,pre-enrolled-keys=1"

    fun tpmState(storage: String): String = "$storage:1,version=v2.0"

    /** A USB device passed through by vendor and product id, so it follows the device to another port. */
    fun usb(device: UsbDevice): String = "host=${device.vendorId}:${device.productId}"

    fun pci(device: PciDevice, allFunctions: Boolean, pcie: Boolean): String {
        val id = if (allFunctions) device.id.substringBeforeLast('.') else device.id
        return id + if (pcie) ",pcie=1" else ""
    }

    const val SERIAL = "socket"
    const val RNG = "source=/dev/urandom"
    const val AUDIO = "device=ich9-intel-hda,driver=spice"

    // ---- cloud-init

    /** The cloud-init drive, e.g. `ide0` holding `local-zfs:vm-100-cloudinit`. */
    fun cloudInitDrive(e: GuestEdit): String? = e.allKeys().firstOrNull { it in e.values && GuestEdit.isDisk(it) && e[it].contains("cloudinit") }

    fun nextCloudInitKey(e: GuestEdit): String? =
        (listOf("ide0", "ide1", "ide3", "ide2") + (0..5).map { "sata$it" }).firstOrNull { it !in e.values && it !in e.current }

    fun cloudInitDisk(storage: String): String = "$storage:cloudinit"

    /** Proxmox stores `sshkeys` URL-encoded; this is the plain text, one key per line. */
    fun sshKeys(e: GuestEdit): String = runCatching { URLDecoder.decode(e["sshkeys"], "UTF-8") }.getOrDefault(e["sshkeys"])

    fun withSshKeys(e: GuestEdit, keys: String): GuestEdit {
        val clean = keys.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        return e.with("sshkeys", if (clean.isEmpty()) "" else URLEncoder.encode(clean, "UTF-8").replace("+", "%20"))
    }

    /** Whether the config only holds the mask Proxmox shows instead of the cloud-init password. */
    fun passwordIsMasked(value: String): Boolean = value.isNotEmpty() && value.all { it == '*' }

    fun usbDevices(data: JSONArray): List<UsbDevice> = (0 until data.length()).mapNotNull { data.optJSONObject(it) }
        .map { o ->
            UsbDevice(
                vendorId = o.optString("vendid").removePrefix("0x"),
                productId = o.optString("prodid").removePrefix("0x"),
                name = listOf(o.optString("manufacturer"), o.optString("product")).filter { it.isNotBlank() }.joinToString(" "),
                hub = o.optInt("class") == 9,
            )
        }
        .filter { !it.hub && it.vendorId.isNotEmpty() && it.productId.isNotEmpty() }

    fun pciDevices(data: JSONArray): List<PciDevice> = (0 until data.length()).mapNotNull { data.optJSONObject(it) }
        .map { o ->
            PciDevice(
                id = o.optString("id"),
                name = listOf(o.optString("vendor_name"), o.optString("device_name")).filter { it.isNotBlank() }.joinToString(" "),
                iommuGroup = o.optInt("iommugroup", -1),
            )
        }
        .filter { it.id.isNotEmpty() }
        .sortedBy { it.id }
}

data class UsbDevice(val vendorId: String, val productId: String, val name: String, val hub: Boolean = false)

data class PciDevice(val id: String, val name: String, val iommuGroup: Int)

/** A cloud-init `ipconfigN` value: `ip=dhcp` or `ip=192.0.2.5/24,gw=192.0.2.1`, optionally `ip6=auto`. */
data class IpConfig(val ip: String = "dhcp", val gateway: String = "", val ip6: String = "") {
    val dhcp: Boolean get() = ip == "dhcp"

    fun validate(): String? {
        if (!dhcp && !Regex("^[0-9.]+/\\d{1,2}$").matches(ip)) return "IP must look like 192.0.2.50/24"
        if (gateway.isNotEmpty() && !Regex("^[0-9.]+$").matches(gateway)) return "Gateway must be an IPv4 address"
        return null
    }

    fun format(): String = listOfNotNull(
        "ip=$ip",
        gateway.takeIf { it.isNotEmpty() && !dhcp }?.let { "gw=$it" },
        ip6.takeIf { it.isNotEmpty() }?.let { "ip6=$it" },
    ).joinToString(",")

    companion object {
        fun parse(value: String): IpConfig {
            val o = GuestEdit.parseOptions(value)
            return IpConfig(ip = o["ip"] ?: "dhcp", gateway = o["gw"].orEmpty(), ip6 = o["ip6"].orEmpty())
        }
    }
}
