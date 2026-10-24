package com.lilayam.dellservertools.core.proxmox

import org.json.JSONArray
import org.json.JSONObject

/** One interface of `GET /nodes/{node}/network`, with the settings the app edits. */
data class NetworkInterface(
    val iface: String,
    val type: String,
    val active: Boolean,
    val autostart: Boolean,
    val cidr: String = "",
    val gateway: String = "",
    val cidr6: String = "",
    val gateway6: String = "",
    val bridgePorts: String = "",
    val vlanAware: Boolean = false,
    val slaves: String = "",
    val bondMode: String = "",
    val vlanRawDevice: String = "",
    val vlanId: String = "",
    val mtu: String = "",
    val comments: String = "",
) {
    /** Physical NICs can't be created or deleted, only configured. */
    val isPhysical: Boolean get() = type == "eth"

    companion object {
        fun parse(data: JSONArray): List<NetworkInterface> = (0 until data.length()).mapNotNull { data.optJSONObject(it) }
            .map { o ->
                fun s(k: String) = o.opt(k)?.toString().orEmpty()
                NetworkInterface(
                    iface = s("iface"),
                    type = s("type"),
                    active = o.optInt("active", 0) == 1,
                    autostart = o.optInt("autostart", 0) == 1,
                    cidr = s("cidr"),
                    gateway = s("gateway"),
                    cidr6 = s("cidr6"),
                    gateway6 = s("gateway6"),
                    bridgePorts = s("bridge_ports"),
                    vlanAware = o.optInt("bridge_vlan_aware", 0) == 1,
                    slaves = s("slaves"),
                    bondMode = s("bond_mode"),
                    vlanRawDevice = s("vlan-raw-device"),
                    vlanId = s("vlan-id"),
                    mtu = s("mtu"),
                    comments = s("comments").trim(),
                )
            }
            .sortedWith(compareBy({ TYPE_ORDER.indexOf(it.type).let { i -> if (i < 0) 99 else i } }, { it.iface }))

        private val TYPE_ORDER = listOf("eth", "bond", "bridge", "vlan", "OVSBridge", "OVSBond", "OVSPort", "OVSIntPort")
    }
}

/**
 * The create/edit form of a Linux bridge, bond, VLAN or NIC (`POST`/`PUT /nodes/{node}/network[/{iface}]`). Changes
 * are only written to `/etc/network/interfaces.new`; nothing changes on the host until Apply.
 */
data class NetworkForm(
    val iface: String,
    val type: String,
    val autostart: Boolean = true,
    val cidr: String = "",
    val gateway: String = "",
    val cidr6: String = "",
    val gateway6: String = "",
    val bridgePorts: String = "",
    val vlanAware: Boolean = false,
    val slaves: String = "",
    val bondMode: String = "active-backup",
    val vlanRawDevice: String = "",
    val vlanId: String = "",
    val mtu: String = "",
    val comments: String = "",
    val current: NetworkInterface? = null,
) {
    val isNew: Boolean get() = current == null

    fun validate(): String? {
        if (type !in TYPES) return "Type must be bridge, bond, vlan or eth"
        val nameOk = when (type) {
            "bridge" -> Regex("^vmbr\\d{1,4}$").matches(iface)
            "bond" -> Regex("^bond\\d{1,4}$").matches(iface)
            "vlan" -> Regex("^vlan\\d{1,4}$").matches(iface) || Regex("^[A-Za-z0-9]+\\.\\d{1,4}$").matches(iface)
            else -> Regex("^[A-Za-z0-9_.-]{1,20}$").matches(iface)
        }
        if (!nameOk) {
            return when (type) {
                "bridge" -> "Bridge name: vmbrN, e.g. vmbr1"
                "bond" -> "Bond name: bondN"
                "vlan" -> "VLAN name: vlanN or <device>.<tag>, e.g. eno1.10"
                else -> "Unknown interface name"
            }
        }
        if (cidr.isNotEmpty() && !CIDR4.matches(cidr)) return "IPv4/CIDR: e.g. 192.0.2.10/24"
        if (gateway.isNotEmpty() && !IP4.matches(gateway)) return "Gateway: an IPv4 address"
        if (gateway.isNotEmpty() && cidr.isEmpty()) return "A gateway needs an IPv4 address"
        if (cidr6.isNotEmpty() && !CIDR6.matches(cidr6)) return "IPv6/CIDR: e.g. 2001:db8::10/64"
        if (gateway6.isNotEmpty() && !IP6.matches(gateway6)) return "IPv6 gateway: an IPv6 address"
        if (mtu.isNotEmpty() && (mtu.toIntOrNull() ?: 0) !in 1280..65520) return "MTU: 1280 to 65520"
        if (!LIST.matches(bridgePorts) || !LIST.matches(slaves)) return "Ports: interface names separated by spaces"
        if (type == "bond" && slaves.isBlank()) return "A bond needs at least one slave NIC"
        if (type == "bond" && bondMode !in BOND_MODES) return "Unknown bond mode"
        if (type == "vlan" && iface.startsWith("vlan")) {
            if (vlanRawDevice.isBlank()) return "Pick the device the VLAN runs on"
            if ((vlanId.toIntOrNull() ?: 0) !in 1..4094) return "VLAN tag: 1 to 4094"
        }
        return null
    }

    private fun values(): Map<String, String> {
        val p = linkedMapOf("type" to type)
        if (autostart) p["autostart"] = "1"
        listOf("cidr" to cidr, "gateway" to gateway, "cidr6" to cidr6, "gateway6" to gateway6, "mtu" to mtu, "comments" to comments)
            .forEach { (k, v) -> if (v.isNotBlank()) p[k] = v.trim() }
        when (type) {
            "bridge" -> {
                if (bridgePorts.isNotBlank()) p["bridge_ports"] = normalizeList(bridgePorts)
                if (vlanAware) p["bridge_vlan_aware"] = "1"
            }
            "bond" -> {
                p["slaves"] = normalizeList(slaves)
                p["bond_mode"] = bondMode
            }
            "vlan" -> if (iface.startsWith("vlan")) {
                p["vlan-raw-device"] = vlanRawDevice
                p["vlan-id"] = vlanId
            }
        }
        return p
    }

    fun createParams(): Map<String, String> = linkedMapOf("iface" to iface) + values()

    /** PUT needs the type every time; everything else only when it changed, cleared ones go into `delete`. */
    fun updateParams(): Map<String, String> {
        val old = current?.let { fromInterface(it).values() }.orEmpty()
        val now = values()
        val p = linkedMapOf("type" to type)
        for ((k, v) in now) if (k != "type" && old[k] != v) p[k] = v
        val gone = old.keys.filter { it !in now }
        if (gone.isNotEmpty()) p["delete"] = gone.joinToString(",")
        return p
    }

    companion object {
        val TYPES = listOf("bridge", "bond", "vlan", "eth")
        val BOND_MODES = listOf("balance-rr", "active-backup", "balance-xor", "broadcast", "802.3ad", "balance-tlb", "balance-alb")
        private val IP4 = Regex("^(\\d{1,3}\\.){3}\\d{1,3}$")
        private val CIDR4 = Regex("^(\\d{1,3}\\.){3}\\d{1,3}/\\d{1,2}$")
        private val IP6 = Regex("^[0-9A-Fa-f:]+$")
        private val CIDR6 = Regex("^[0-9A-Fa-f:]+/\\d{1,3}$")
        private val LIST = Regex("^[A-Za-z0-9_.\\- ]*$")

        private fun normalizeList(v: String) = v.trim().split(Regex("\\s+")).joinToString(" ")

        fun fromInterface(i: NetworkInterface) = NetworkForm(
            iface = i.iface, type = i.type, autostart = i.autostart, cidr = i.cidr, gateway = i.gateway, cidr6 = i.cidr6,
            gateway6 = i.gateway6, bridgePorts = i.bridgePorts, vlanAware = i.vlanAware, slaves = i.slaves,
            bondMode = i.bondMode.ifEmpty { "active-backup" }, vlanRawDevice = i.vlanRawDevice, vlanId = i.vlanId, mtu = i.mtu,
            comments = i.comments, current = i,
        )

        /** The next free name of a kind, e.g. vmbr2 when vmbr0 and vmbr1 exist. */
        fun nextName(prefix: String, existing: List<NetworkInterface>): String {
            val used = existing.map { it.iface }.toSet()
            return (0..9999).asSequence().map { "$prefix$it" }.first { it !in used }
        }
    }
}

/** `GET /nodes/{node}/dns`: the search domain and up to three name servers. */
data class DnsSettings(val search: String, val servers: List<String>) {
    fun validate(): String? {
        if (search.isBlank()) return "Enter a search domain"
        if (!Regex("^[A-Za-z0-9.-]+$").matches(search.trim())) return "Search domain: e.g. example.org"
        if (servers.size > 3) return "At most three DNS servers"
        servers.filter { it.isNotBlank() }.forEach { if (!Regex("^[0-9A-Fa-f.:]+$").matches(it.trim())) return "DNS server: an IP address" }
        return null
    }

    fun params(): Map<String, String> {
        val p = linkedMapOf("search" to search.trim())
        servers.map { it.trim() }.filter { it.isNotEmpty() }.forEachIndexed { i, s -> p["dns${i + 1}"] = s }
        return p
    }

    companion object {
        fun parse(o: JSONObject): DnsSettings =
            DnsSettings(o.optString("search"), listOf("dns1", "dns2", "dns3").map { o.optString(it) }.filter { it.isNotEmpty() })
    }
}

/** A systemd service of `GET /nodes/{node}/services`. */
data class NodeService(val name: String, val description: String, val state: String, val unitState: String) {
    val running: Boolean get() = state == "running"

    companion object {
        fun parse(data: JSONArray): List<NodeService> = (0 until data.length()).mapNotNull { data.optJSONObject(it) }
            .map {
                NodeService(
                    name = it.optString("service", it.optString("name")),
                    description = it.optString("desc"),
                    state = it.optString("state"),
                    unitState = it.optString("unit-state"),
                )
            }
            .sortedBy { it.name }
    }
}

/** A package with an update waiting (`GET /nodes/{node}/apt/update`). */
data class PackageUpdate(val name: String, val oldVersion: String, val newVersion: String, val title: String) {
    companion object {
        fun parse(data: JSONArray): List<PackageUpdate> = (0 until data.length()).mapNotNull { data.optJSONObject(it) }
            .map { PackageUpdate(it.optString("Package"), it.optString("OldVersion"), it.optString("Version"), it.optString("Title")) }
            .sortedBy { it.name }
    }
}

/** `GET /nodes/{node}/apt/repositories`: every configured repository line, and the standard ones that can be added. */
data class AptRepositories(val digest: String, val entries: List<AptRepository>, val standard: List<StandardRepository>) {
    companion object {
        fun parse(o: JSONObject): AptRepositories {
            val entries = mutableListOf<AptRepository>()
            val files = o.optJSONArray("files") ?: JSONArray()
            for (f in 0 until files.length()) {
                val file = files.optJSONObject(f) ?: continue
                val path = file.optString("path")
                val repos = file.optJSONArray("repositories") ?: JSONArray()
                for (i in 0 until repos.length()) {
                    val r = repos.optJSONObject(i) ?: continue
                    fun list(k: String) = r.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                    entries += AptRepository(
                        path = path,
                        index = i,
                        enabled = r.optInt("Enabled", 1) == 1,
                        types = list("Types"),
                        uris = list("URIs"),
                        suites = list("Suites"),
                        components = list("Components"),
                        comment = r.optString("Comment").trim(),
                    )
                }
            }
            val std = o.optJSONArray("standard-repos") ?: JSONArray()
            val standard = (0 until std.length()).mapNotNull { std.optJSONObject(it) }.map {
                StandardRepository(it.optString("handle"), it.optString("name"), if (it.isNull("status")) null else it.optInt("status") == 1)
            }
            return AptRepositories(o.optString("digest"), entries, standard)
        }
    }
}

data class AptRepository(
    val path: String,
    val index: Int,
    val enabled: Boolean,
    val types: List<String>,
    val uris: List<String>,
    val suites: List<String>,
    val components: List<String>,
    val comment: String,
) {
    val line: String get() = "${types.joinToString(" ")} ${uris.joinToString(" ")} ${suites.joinToString(" ")} ${components.joinToString(" ")}"
}

/** A repository Proxmox knows by name; [enabled] is null when it isn't configured at all. */
data class StandardRepository(val handle: String, val name: String, val enabled: Boolean?)

object NodeSystemJson {
    fun journal(data: JSONArray): List<String> = (0 until data.length()).map { data.optString(it) }

    /** Timezone names for the picker, from the JVM's tz database (the same names Proxmox takes). */
    fun timezones(): List<String> = java.time.ZoneId.getAvailableZoneIds()
        .filter { it.contains('/') && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }
        .sorted() + "UTC"
}
