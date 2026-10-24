package com.lilayam.dellservertools.core.proxmox

/**
 * An edit of a container's or VM's whole config: [values] starts as [current] and the screen changes it key by
 * key, through the typed helpers below or the raw "All settings" list. [changedParams] turns the difference into
 * one `PUT .../config` request: changed or new keys as values, removed keys in `delete`.
 */
data class GuestEdit(
    val type: GuestType,
    val current: Map<String, String>,
    val values: Map<String, String> = current,
) {
    operator fun get(key: String): String = values[key].orEmpty()

    /** An empty value removes the key, the way the Proxmox web interface clears a field. */
    fun with(key: String, value: String): GuestEdit =
        copy(values = if (value.isEmpty()) values - key else values + (key to value))

    fun without(key: String): GuestEdit = copy(values = values - key)

    val nameKey: String get() = if (type == GuestType.LXC) "hostname" else "name"
    val isTemplate: Boolean get() = current["template"] == "1"

    fun flag(key: String): Boolean = this[key] == "1"

    /** Booleans default to off, so off is sent as a delete rather than an explicit 0 the guest never had. */
    fun withFlag(key: String, on: Boolean): GuestEdit =
        if (on) with(key, "1") else if (current[key] == "0") with(key, "0") else without(key)

    /** CT `features` (`nesting=1,keyctl=1,...`). */
    fun feature(name: String): Boolean = parseOptions(this["features"])[name] == "1"

    /** Toggles one CT feature and keeps the rest as they were. */
    fun withFeature(name: String, on: Boolean): GuestEdit {
        val features = parseOptions(this["features"]).toMutableMap()
        if (on) features[name] = "1" else features.remove(name)
        return with("features", features.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" })
    }

    /** VM `agent` is `1` or `enabled=1,fstrim_cloned_disks=1`; only the enabled part is toggled. */
    val agent: Boolean get() = agentEnabled(values["agent"])

    fun withAgent(on: Boolean): GuestEdit {
        val opts = parseOptions(this["agent"]).filterKeys { it != "enabled" && it.isNotEmpty() }
        if (opts.isEmpty()) return withFlag("agent", on)
        return with("agent", (listOf("enabled=${if (on) 1 else 0}") + opts.map { "${it.key}=${it.value}" }).joinToString(","))
    }

    /** One part of `startup` (`order=1,up=30,down=60`). */
    fun startup(part: String): String = parseOptions(this["startup"])[part].orEmpty()

    fun withStartup(part: String, value: String): GuestEdit {
        val parts = parseOptions(this["startup"]).toMutableMap()
        if (value.isEmpty()) parts.remove(part) else parts[part] = value
        return with("startup", listOf("order", "up", "down").mapNotNull { k -> parts[k]?.let { "$k=$it" } }.joinToString(","))
    }

    /** VM devices that can boot: disks and CD drives (not EFI/TPM state) and network cards, `scsi0` before `scsi10`. */
    fun bootDevices(): List<String> = values.keys.filter { BOOTABLE.matches(it) }.sortedWith(KEY_ORDER)

    /**
     * The VM's boot order, first device first. `boot: order=scsi0;ide2;net0` is today's format; older configs (and
     * a missing `boot`, which means "cdn") list device kinds instead: c = `bootdisk` or the first disk, d = the CD
     * drives, n = the network cards.
     */
    fun bootOrder(): List<String> {
        val devices = bootDevices()
        val boot = this["boot"]
        if (boot.contains("order=")) {
            return parseOptions(boot)["order"].orEmpty().split(';').map(String::trim).filter { it in devices }.distinct()
        }
        val disks = devices.filter { !it.startsWith("net") && !isCdrom(it) }
        return (boot.ifEmpty { "cdn" }).flatMap { kind ->
            when (kind) {
                'c' -> listOfNotNull(this["bootdisk"].takeIf { it in disks } ?: disks.firstOrNull())
                'd' -> devices.filter(::isCdrom)
                'n' -> devices.filter { it.startsWith("net") }
                else -> emptyList()
            }
        }.distinct()
    }

    /** Sets the boot order in the current format; the web interface needs at least one device, and so does this. */
    fun withBootOrder(order: List<String>): GuestEdit =
        if (order.isEmpty()) this else with("boot", "order=" + order.joinToString(";"))

    /** Moves [device] one place earlier (-1) or later (+1) in the boot order. */
    fun moveBootDevice(device: String, by: Int): GuestEdit {
        val order = bootOrder().toMutableList()
        val from = order.indexOf(device)
        val to = from + by
        if (from < 0 || to !in order.indices) return this
        order.add(to, order.removeAt(from))
        return withBootOrder(order)
    }

    /** Turns booting from [device] on (added last) or off. */
    fun withBootDevice(device: String, on: Boolean): GuestEdit {
        val order = bootOrder()
        return withBootOrder(if (on) (order - device) + device else order - device)
    }

    fun isCdrom(key: String): Boolean = values[key].orEmpty().contains("media=cdrom")

    /** Whether saving changes the boot order, which a running VM only picks up after a stop and start. */
    val bootOrderChanged: Boolean get() = this["boot"] != current["boot"].orEmpty()

    fun validate(): String? {
        if (this[nameKey].isBlank()) return "Enter a ${if (type == GuestType.LXC) "hostname" else "name"}"
        val cores = this["cores"]
        if (cores.isNotEmpty() && (cores.toIntOrNull() ?: 0) < 1) return "At least 1 core"
        val memory = this["memory"]
        if (memory.isNotEmpty() && (memory.toIntOrNull() ?: 0) < 16) return "At least 16 MB of memory"
        if (type == GuestType.QEMU && this["sockets"].isNotEmpty() && (this["sockets"].toIntOrNull() ?: 0) < 1) {
            return "At least 1 socket"
        }
        for (key in listOf("swap", "cpuunits")) {
            if (this[key].isNotEmpty() && this[key].toIntOrNull() == null) return "$key must be a whole number"
        }
        if (this["cpulimit"].isNotEmpty() && this["cpulimit"].toDoubleOrNull() == null) return "CPU limit must be a number"
        for (part in listOf("order", "up", "down")) {
            if (startup(part).isNotEmpty() && startup(part).toIntOrNull() == null) return "Startup $part must be a whole number"
        }
        if (type == GuestType.QEMU && values["ipconfig0"] != current["ipconfig0"] && this["ipconfig0"].isNotEmpty()) {
            IpConfig.parse(this["ipconfig0"]).validate()?.let { return it }
        }
        for (key in values.keys + current.keys) {
            if (values[key] == current[key]) continue
            if (!KEY.matches(key)) return "\"$key\" is not a valid setting name"
            if (isReadOnly(key)) return "$key can't be changed here"
            if (key.startsWith("unused") && key in values) return "$key can only be removed"
            // A CD drive's size follows its ISO; only real disks must be grown instead.
            if (isDisk(key) && key in current && key in values && !isCdrom(key) && diskSize(values[key]) != diskSize(current[key])) {
                return "Use Grow disk to change the size of $key"
            }
        }
        return null
    }

    /** Params for a `PUT .../config`: every changed or new key, plus `delete` for the removed ones. */
    fun changedParams(): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for ((key, value) in values) if (current[key] != value) out[key] = value
        val removed = current.keys.filter { it !in values }
        if (removed.isNotEmpty()) out["delete"] = removed.joinToString(",")
        return out
    }

    /** The same unsaved changes on top of a freshly loaded config (e.g. after a disk was grown). */
    fun rebase(newCurrent: Map<String, String>): GuestEdit {
        val merged = newCurrent.toMutableMap()
        for ((key, value) in values) if (current[key] != value) merged[key] = value
        for (key in current.keys) if (key !in values) merged.remove(key)
        return GuestEdit(type, newCurrent, merged)
    }

    /** Keys the API refuses to change on an existing guest; shown with a note instead of a field. */
    fun isReadOnly(key: String): Boolean = key in READ_ONLY_COMMON || key in (if (type == GuestType.LXC) READ_ONLY_LXC else READ_ONLY_QEMU)

    /** Next free `<prefix>N` key, e.g. `net1` or `mp0`. */
    fun nextKey(prefix: String): String = generateSequence(0) { it + 1 }.map { "$prefix$it" }.first { it !in values && it !in current }

    /** Disk keys whose volume can be grown with [ProxmoxClient.resizeDisk]: not CD-ROMs, bind mounts or EFI/TPM state. */
    fun growableDisks(): List<String> = values.keys.filter { key ->
        val v = values.getValue(key)
        isDisk(key) && key in current && !key.startsWith("efidisk") && !key.startsWith("tpmstate") &&
            !v.contains("media=cdrom") && v != "none" && !v.startsWith("/") && diskSize(v) != null
    }.sortedWith(KEY_ORDER)

    /** Every key in the config before or after the edit, `net2` before `net10`. */
    fun allKeys(): List<String> = (values.keys + current.keys).distinct().sortedWith(KEY_ORDER)

    companion object {
        private val KEY = Regex("[a-z][a-z0-9_-]*")
        private val KEY_ORDER = compareBy<String>({ it.trimEnd { c -> c.isDigit() } }, { it.takeLastWhile(Char::isDigit).toIntOrNull() ?: -1 })
        private val BOOTABLE = Regex("^(scsi|virtio|sata|ide|net)\\d+$")
        private val DISK = Regex("^(rootfs|mp|scsi|virtio|sata|ide|efidisk|tpmstate)(\\d*)$")
        private val READ_ONLY_COMMON = setOf("digest", "template", "lock", "parent", "snaptime")
        private val READ_ONLY_LXC = setOf("unprivileged", "lxc")
        private val READ_ONLY_QEMU = setOf("vmstate", "runningmachine", "runningcpu", "meta")

        fun isDisk(key: String): Boolean = DISK.matches(key)

        /** The `size=` part of a disk value, e.g. `16G`. */
        fun diskSize(value: String?): String? = parseOptions(value)["size"]

        fun parseOptions(value: String?): Map<String, String> =
            value.orEmpty().split(',').mapNotNull {
                val p = it.split('=', limit = 2)
                if (p.size == 2 && p[0].isNotBlank()) p[0].trim() to p[1].trim() else null
            }.toMap()

        fun agentEnabled(value: String?): Boolean {
            val v = value.orEmpty()
            if (v.isEmpty() || v == "0") return false
            if (v == "1") return true
            return parseOptions(v)["enabled"] == "1" || v.startsWith("1")
        }

        /** A `PUT .../resize` size that grows the disk by [gb] GiB. */
        fun growBy(gb: Int): String = "+${gb}G"
    }
}
