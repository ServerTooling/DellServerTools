package com.lilayam.dellservertools.core.proxmox

import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Where a firewall configuration lives: the datacenter, a node, a guest, or a datacenter security group. Each has
 * its own rules; options, aliases, IP sets and the log only exist at some levels, as in the web UI.
 */
data class FirewallScope(
    val node: String? = null,
    val type: GuestType? = null,
    val vmid: Int? = null,
    val group: String? = null,
) {
    val isCluster: Boolean get() = node == null && group == null
    val isNode: Boolean get() = node != null && vmid == null
    val isGuest: Boolean get() = node != null && vmid != null

    /** The API path of this level's firewall, e.g. `/nodes/pve/qemu/100/firewall`. */
    val path: String
        get() = when {
            group != null -> "/cluster/firewall/groups/${enc(group)}"
            isGuest -> "/nodes/${enc(node!!)}/${type!!.apiName}/$vmid/firewall"
            isNode -> "/nodes/${enc(node!!)}/firewall"
            else -> "/cluster/firewall"
        }

    /** Rules of a security group live directly under the group, not under `/rules`. */
    val rulesPath: String get() = if (group != null) path else "$path/rules"

    val hasOptions: Boolean get() = group == null
    val hasAliases: Boolean get() = isCluster || isGuest
    val hasIpSets: Boolean get() = isCluster || isGuest
    val hasLog: Boolean get() = isNode || isGuest

    val title: String
        get() = when {
            group != null -> "Security group $group"
            isGuest -> "${type!!.label} $vmid firewall"
            isNode -> "Node $node firewall"
            else -> "Datacenter firewall"
        }

    /** The option switches and choices of this level's Options tab. */
    val options: List<FirewallOption>
        get() = when {
            group != null -> emptyList()
            isGuest -> GUEST_OPTIONS
            isNode -> NODE_OPTIONS
            else -> CLUSTER_OPTIONS
        }

    companion object {
        private fun enc(v: String) = URLEncoder.encode(v, "UTF-8").replace("+", "%20")

        private val POLICIES = listOf("DROP", "ACCEPT", "REJECT")
        private val LOG_LEVELS = listOf("nolog", "emerg", "alert", "crit", "err", "warning", "notice", "info", "debug")

        private val CLUSTER_OPTIONS = listOf(
            FirewallOption("enable", "Firewall", default = "0"),
            FirewallOption("policy_in", "Input policy", POLICIES, "DROP"),
            FirewallOption("policy_out", "Output policy", POLICIES, "ACCEPT"),
            FirewallOption("ebtables", "ebtables", default = "1"),
        )
        private val NODE_OPTIONS = listOf(
            FirewallOption("enable", "Firewall", default = "1"),
            FirewallOption("nosmurfs", "SMURFS filter", default = "1"),
            FirewallOption("tcpflags", "TCP flags filter", default = "0"),
            FirewallOption("ndp", "NDP", default = "1"),
            FirewallOption("log_level_in", "Log level in", LOG_LEVELS, "nolog"),
            FirewallOption("log_level_out", "Log level out", LOG_LEVELS, "nolog"),
        )
        private val GUEST_OPTIONS = listOf(
            FirewallOption("enable", "Firewall", default = "0"),
            FirewallOption("policy_in", "Input policy", POLICIES, "DROP"),
            FirewallOption("policy_out", "Output policy", POLICIES, "ACCEPT"),
            FirewallOption("dhcp", "DHCP", default = "1"),
            FirewallOption("ndp", "NDP", default = "1"),
            FirewallOption("radv", "Router advertisement", default = "0"),
            FirewallOption("macfilter", "MAC filter", default = "1"),
            FirewallOption("ipfilter", "IP filter", default = "0"),
            FirewallOption("log_level_in", "Log level in", LOG_LEVELS, "nolog"),
            FirewallOption("log_level_out", "Log level out", LOG_LEVELS, "nolog"),
        )
    }
}

/** One option: a switch when [choices] is empty, otherwise one of [choices]. [default] is Proxmox's when unset. */
data class FirewallOption(val key: String, val label: String, val choices: List<String> = emptyList(), val default: String) {
    val isSwitch: Boolean get() = choices.isEmpty()

    fun value(options: Map<String, String>): String = options[key]?.ifEmpty { null } ?: default

    /** The PUT for a new value; setting the default back clears the key, as the web UI does. */
    fun params(value: String): Map<String, String> = if (value == default) mapOf("delete" to key) else mapOf(key to value)
}

/** A rule as `GET .../rules` lists it. [type] is in, out or group (a security group reference). */
data class FirewallRule(
    val pos: Int,
    val type: String,
    val action: String,
    val enabled: Boolean,
    val macro: String = "",
    val source: String = "",
    val dest: String = "",
    val proto: String = "",
    val dport: String = "",
    val sport: String = "",
    val iface: String = "",
    val log: String = "",
    val comment: String = "",
) {
    /** One line saying what the rule matches, e.g. `tcp 22 from 192.0.2.0/24`. */
    val summary: String
        get() = listOfNotNull(
            macro.ifEmpty { null },
            proto.ifEmpty { null },
            dport.ifEmpty { null }?.let { "port $it" },
            sport.ifEmpty { null }?.let { "from port $it" },
            source.ifEmpty { null }?.let { "from $it" },
            dest.ifEmpty { null }?.let { "to $it" },
            iface.ifEmpty { null }?.let { "on $it" },
        ).joinToString(" ").ifEmpty { "any" }

    companion object {
        fun parse(data: JSONArray): List<FirewallRule> = (0 until data.length()).mapNotNull { data.optJSONObject(it) }
            .map { o ->
                FirewallRule(
                    pos = o.optInt("pos"),
                    type = o.optString("type"),
                    action = o.optString("action"),
                    enabled = o.optInt("enable", 0) == 1,
                    macro = o.optString("macro"),
                    source = o.optString("source"),
                    dest = o.optString("dest"),
                    proto = o.optString("proto"),
                    dport = o.optString("dport"),
                    sport = o.optString("sport"),
                    iface = o.optString("iface"),
                    log = o.optString("log"),
                    comment = o.optString("comment"),
                )
            }
            .sortedBy { it.pos }
    }
}

/** The add/edit form of a rule; [current] is the rule being edited, null when adding. */
data class FirewallRuleForm(
    val type: String = "in",
    val action: String = "ACCEPT",
    val enabled: Boolean = true,
    val macro: String = "",
    val source: String = "",
    val dest: String = "",
    val proto: String = "",
    val dport: String = "",
    val sport: String = "",
    val iface: String = "",
    val log: String = "",
    val comment: String = "",
    val current: FirewallRule? = null,
) {
    val isGroup: Boolean get() = type == "group"

    fun validate(): String? {
        if (type !in TYPES) return "Direction must be in, out or group"
        if (isGroup) {
            if (!NAME.matches(action)) return "Pick a security group"
            return null
        }
        if (action !in ACTIONS) return "Action must be ACCEPT, DROP or REJECT"
        if (macro.isNotEmpty() && (proto.isNotEmpty() || dport.isNotEmpty() || sport.isNotEmpty())) {
            return "A macro sets protocol and ports itself; clear them or the macro"
        }
        if ((dport.isNotEmpty() || sport.isNotEmpty()) && proto !in PORT_PROTOCOLS) return "Ports need protocol tcp or udp"
        for ((label, v) in listOf("Destination port" to dport, "Source port" to sport)) {
            if (v.isNotEmpty() && !PORTS.matches(v)) return "$label: a port, range (1000:2000) or list (80,443)"
        }
        for ((label, v) in listOf("Source" to source, "Destination" to dest)) {
            if (v.isNotEmpty() && !ADDRESS.matches(v)) return "$label: an IP, CIDR, range, alias or +ipset"
        }
        if (iface.isNotEmpty() && !IFACE.matches(iface)) return "Interface: e.g. net0"
        return null
    }

    /** The rule's fields as API params; empty fields are left out. */
    fun values(): Map<String, String> {
        val p = linkedMapOf("type" to type, "action" to action, "enable" to if (enabled) "1" else "0")
        if (!isGroup) {
            listOf(
                "macro" to macro, "proto" to proto, "dport" to dport, "sport" to sport, "source" to source, "dest" to dest,
                "log" to log,
            ).forEach { (k, v) -> if (v.isNotBlank()) p[k] = v.trim() }
        }
        if (iface.isNotBlank()) p["iface"] = iface.trim()
        if (comment.isNotBlank()) p["comment"] = comment.trim()
        return p
    }

    fun createParams(): Map<String, String> = values()

    /** Changed fields only; a cleared field goes into `delete`. */
    fun updateParams(): Map<String, String> {
        val old = current?.let(::fromRule)?.values().orEmpty()
        val now = values()
        val p = linkedMapOf<String, String>()
        for ((k, v) in now) if (old[k] != v) p[k] = v
        val gone = old.keys.filter { it !in now }
        if (gone.isNotEmpty()) p["delete"] = gone.joinToString(",")
        return p
    }

    companion object {
        val TYPES = listOf("in", "out", "group")
        val ACTIONS = listOf("ACCEPT", "DROP", "REJECT")
        val PROTOCOLS = listOf("", "tcp", "udp", "icmp", "icmpv6", "esp", "ah", "gre", "sctp")
        private val PORT_PROTOCOLS = setOf("tcp", "udp", "sctp")
        private val NAME = Regex("^[A-Za-z][A-Za-z0-9_-]{1,17}$")
        private val PORTS = Regex("^[A-Za-z0-9]+(:[A-Za-z0-9]+)?(,[A-Za-z0-9]+(:[A-Za-z0-9]+)?)*$")

        // Addresses, CIDRs, ranges, lists, aliases (dc/name, guest/name) and IP sets (+name); never anything with spaces.
        private val ADDRESS = Regex("^!?[A-Za-z0-9.:/+_,-]+$")
        private val IFACE = Regex("^[A-Za-z][A-Za-z0-9_.-]{0,19}$")

        fun fromRule(r: FirewallRule): FirewallRuleForm = FirewallRuleForm(
            type = r.type, action = r.action, enabled = r.enabled, macro = r.macro, source = r.source, dest = r.dest,
            proto = r.proto, dport = r.dport, sport = r.sport, iface = r.iface, log = r.log, comment = r.comment, current = r,
        )
    }
}

/** A named address (`/cluster/firewall/aliases` or a guest's). */
data class FirewallAlias(val name: String, val cidr: String, val comment: String) {
    companion object {
        fun parse(data: JSONArray): List<FirewallAlias> = (0 until data.length()).mapNotNull { data.optJSONObject(it) }
            .map { FirewallAlias(it.optString("name"), it.optString("cidr"), it.optString("comment")) }
            .sortedBy { it.name }
    }
}

/** An IP set and, once loaded, its entries. */
data class IpSet(val name: String, val comment: String) {
    companion object {
        fun parse(data: JSONArray): List<IpSet> = (0 until data.length()).mapNotNull { data.optJSONObject(it) }
            .map { IpSet(it.optString("name"), it.optString("comment")) }
            .sortedBy { it.name }
    }
}

data class IpSetEntry(val cidr: String, val nomatch: Boolean, val comment: String) {
    companion object {
        fun parse(data: JSONArray): List<IpSetEntry> = (0 until data.length()).mapNotNull { data.optJSONObject(it) }
            .map { IpSetEntry(it.optString("cidr"), it.optInt("nomatch", 0) == 1, it.optString("comment")) }
            .sortedBy { it.cidr }
    }
}

/** A datacenter security group, usable in rules of every level. */
data class SecurityGroup(val name: String, val comment: String) {
    companion object {
        fun parse(data: JSONArray): List<SecurityGroup> = (0 until data.length()).mapNotNull { data.optJSONObject(it) }
            .map { SecurityGroup(it.optString("group"), it.optString("comment")) }
            .sortedBy { it.name }
    }
}

/** Names and addresses checked before they go to the server. */
object FirewallNames {
    private val NAME = Regex("^[A-Za-z][A-Za-z0-9_-]{1,17}$")
    private val CIDR = Regex("^[0-9A-Fa-f.:]+(/\\d{1,3})?$")

    fun nameError(name: String): String? =
        if (NAME.matches(name)) null else "Name: 2-18 letters, digits, - and _, starting with a letter"

    fun cidrError(cidr: String): String? = if (CIDR.matches(cidr.trim())) null else "An IP address or CIDR, e.g. 192.0.2.0/24"

    /** A macro list entry of `GET /cluster/firewall/macros`. */
    fun macros(data: JSONArray): List<Pair<String, String>> = (0 until data.length()).mapNotNull { data.optJSONObject(it) }
        .map { it.optString("macro") to it.optString("descr") }
        .sortedBy { it.first.lowercase() }

    fun options(o: JSONObject): Map<String, String> =
        o.keys().asSequence().filter { it != "digest" }.associateWith { o.opt(it)?.toString().orEmpty() }

    fun log(data: JSONArray): List<String> = (0 until data.length()).mapNotNull { data.optJSONObject(it) }
        .sortedBy { it.optInt("n") }
        .map { it.optString("t") }
}
