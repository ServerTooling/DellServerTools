package com.lilayam.dellservertools.core.proxmox

/**
 * The editable fields common to a container or VM, and the diff against the current
 * config that becomes a `PUT .../config` request (only changed keys are sent).
 */
data class GuestEdit(
    val type: GuestType,
    /** hostname (CT) or name (VM). */
    val name: String,
    val cores: Int,
    val memoryMb: Int,
    /** CT only: swap in MB. */
    val swapMb: Int = 0,
    /** VM only: CPU sockets. */
    val sockets: Int = 1,
    val onboot: Boolean = false,
    /** CT only: nesting feature. */
    val nesting: Boolean = false,
    /** VM only: QEMU guest agent. */
    val agent: Boolean = false,
    val description: String = "",
) {
    fun validate(): String? {
        if (name.isBlank()) return "Enter a ${if (type == GuestType.LXC) "hostname" else "name"}"
        if (cores < 1) return "At least 1 core"
        if (memoryMb < 16) return "At least 16 MB of memory"
        if (type == GuestType.QEMU && sockets < 1) return "At least 1 socket"
        return null
    }

    /** Params for the keys that differ from [current] (config as key/value pairs). */
    fun changedParams(current: Map<String, String>): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val nameKey = if (type == GuestType.LXC) "hostname" else "name"
        fun set(key: String, value: String) {
            if ((current[key] ?: "") != value) out[key] = value
        }
        set(nameKey, name)
        set("cores", cores.toString())
        set("memory", memoryMb.toString())
        set("onboot", if (onboot) "1" else "0")
        set("description", description)
        if (type == GuestType.LXC) {
            set("swap", swapMb.toString())
            // features is "nesting=1,keyctl=1,..." — only toggle nesting, preserve the rest.
            val features = parseFeatures(current["features"])
            val updated = features.toMutableMap()
            if (nesting) updated["nesting"] = "1" else updated.remove("nesting")
            val featureStr = updated.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
            if ((current["features"] ?: "") != featureStr) out["features"] = featureStr
        } else {
            set("sockets", sockets.toString())
            // agent config is "1" or "enabled=1,..."; normalise to the enabled flag.
            val enabled = agentEnabled(current["agent"])
            if (enabled != agent) out["agent"] = if (agent) "1" else "0"
        }
        return out
    }

    companion object {
        fun parseFeatures(value: String?): Map<String, String> =
            value.orEmpty().split(',').mapNotNull {
                val p = it.split('=', limit = 2)
                if (p.size == 2 && p[0].isNotBlank()) p[0].trim() to p[1].trim() else null
            }.toMap()

        fun agentEnabled(value: String?): Boolean {
            val v = value.orEmpty()
            if (v.isEmpty()) return false
            if (v == "1") return true
            if (v == "0") return false
            return parseFeatures(v)["enabled"] == "1" || v.startsWith("1")
        }

        /** Builds an edit pre-filled from a guest's current config key/values. */
        fun from(type: GuestType, config: Map<String, String>): GuestEdit {
            val nameKey = if (type == GuestType.LXC) "hostname" else "name"
            return GuestEdit(
                type = type,
                name = config[nameKey].orEmpty(),
                cores = config["cores"]?.toIntOrNull() ?: 1,
                memoryMb = config["memory"]?.toIntOrNull() ?: 512,
                swapMb = config["swap"]?.toIntOrNull() ?: 0,
                sockets = config["sockets"]?.toIntOrNull() ?: 1,
                onboot = config["onboot"] == "1",
                nesting = parseFeatures(config["features"])["nesting"] == "1",
                agent = agentEnabled(config["agent"]),
                description = config["description"].orEmpty(),
            )
        }
    }
}
