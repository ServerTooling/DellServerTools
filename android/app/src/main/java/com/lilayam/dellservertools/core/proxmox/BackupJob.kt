package com.lilayam.dellservertools.core.proxmox

import org.json.JSONArray
import org.json.JSONObject

/** Which guests a backup job takes, as in the web UI's Selection mode. */
enum class BackupSelection(val label: String) {
    ALL("All"),
    INCLUDE("Selected"),
    EXCLUDE("All except selected"),
}

/**
 * A scheduled backup job of Datacenter > Backup (`/cluster/backup`). [current] is the stored job when editing, empty
 * when adding; values are the API's, so the update only sends what changed.
 */
data class BackupJobForm(
    val schedule: String = "21:00",
    val storage: String = "",
    val selection: BackupSelection = BackupSelection.ALL,
    val vmids: Set<Int> = emptySet(),
    val node: String = "",
    val mode: String = "snapshot",
    val compress: String = "zstd",
    val enabled: Boolean = true,
    val mailto: String = "",
    /** "always" or "failure"; only matters with [mailto]. */
    val mailNotification: String = "always",
    val notesTemplate: String = "{{guestname}}",
    val comment: String = "",
    /** Null uses the target storage's retention. */
    val prune: PruneOptions? = null,
    val current: Map<String, String> = emptyMap(),
) {
    val id: String get() = current["id"].orEmpty()
    val isNew: Boolean get() = current.isEmpty()

    fun validate(): String? {
        if (schedule.isBlank()) return "Enter a schedule"
        if (!SCHEDULE.matches(schedule.trim())) return "Schedule: e.g. 21:00, sat 02:00, mon..fri 01:30 or hourly"
        if (storage.isBlank()) return "Pick a storage"
        if (selection != BackupSelection.ALL && vmids.isEmpty()) return "Pick at least one guest"
        if (mode !in MODES.map { it.first }) return "Unknown mode $mode"
        if (compress !in COMPRESSIONS.map { it.first }) return "Unknown compression $compress"
        if (mailto.isNotBlank() && mailto.split(',', ';', ' ').filter { it.isNotBlank() }.any { !it.contains('@') }) {
            return "Mail to: one or more addresses, separated by commas"
        }
        prune?.validate()?.let { return it }
        return null
    }

    /** Every setting as the API's parameters; unset ones are left out. */
    fun values(): Map<String, String> {
        val p = linkedMapOf("schedule" to schedule.trim(), "storage" to storage, "mode" to mode, "compress" to compress)
        val ids = vmids.sorted().joinToString(",")
        when (selection) {
            BackupSelection.ALL -> p["all"] = "1"
            BackupSelection.INCLUDE -> p["vmid"] = ids
            BackupSelection.EXCLUDE -> {
                p["all"] = "1"
                p["exclude"] = ids
            }
        }
        if (node.isNotBlank()) p["node"] = node
        p["enabled"] = if (enabled) "1" else "0"
        if (mailto.isNotBlank()) {
            p["mailto"] = mailto.split(',', ';', ' ').filter { it.isNotBlank() }.joinToString(",")
            p["mailnotification"] = mailNotification
        }
        if (notesTemplate.isNotBlank()) p["notes-template"] = notesTemplate.trim()
        if (comment.isNotBlank()) p["comment"] = comment.trim()
        prune?.let { p["prune-backups"] = it.toParam() }
        return p
    }

    fun createParams(): Map<String, String> = values()

    /** Only what changed; settings that went away (e.g. the selection's other key) go into `delete`. */
    fun updateParams(): Map<String, String> {
        val now = values()
        val p = linkedMapOf<String, String>()
        for ((k, v) in now) if (normalized(k, current[k]) != normalized(k, v)) p[k] = v
        val delete = EDITABLE.filter { it in current && it !in now }
        if (delete.isNotEmpty()) p["delete"] = delete.joinToString(",")
        return p
    }

    /** The job's settings for `POST /nodes/{node}/vzdump`, which is what the web UI's Run now sends. */
    fun runParams(): Map<String, String> =
        values().filterKeys { it !in setOf("schedule", "enabled", "comment", "node") }

    private fun normalized(key: String, value: String?): String = when (key) {
        "enabled" -> if (value == "0") "0" else "1"
        "prune-backups" -> PruneOptions.parse(value).toParam()
        "vmid", "exclude" -> value.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.sorted().joinToString(",")
        else -> value.orEmpty()
    }

    companion object {
        val MODES = listOf(
            "snapshot" to "Snapshot (no downtime)",
            "suspend" to "Suspend (short pause)",
            "stop" to "Stop (consistent, downtime)",
        )
        val COMPRESSIONS = listOf("zstd" to "ZSTD", "lzo" to "LZO", "gzip" to "GZIP", "0" to "None")

        /** Common schedules as the web UI offers them: calendar events in Proxmox's syntax. */
        val SCHEDULE_PRESETS = listOf(
            "21:00" to "Every day 21:00",
            "02:30" to "Every day 02:30",
            "mon..fri 00:00" to "Mon–Fri 00:00",
            "sat 02:00" to "Saturday 02:00",
            "sun 01:00" to "Sunday 01:00",
            "hourly" to "Every hour",
        )

        private val SCHEDULE = Regex("^[A-Za-z0-9:,.*/~ -]{1,128}$")
        private val EDITABLE = listOf(
            "all", "vmid", "exclude", "node", "mailto", "mailnotification", "notes-template", "comment", "prune-backups",
        )

        fun fromConfig(config: Map<String, String>): BackupJobForm {
            val all = config["all"] == "1"
            val ids = (if (all) config["exclude"] else config["vmid"]).orEmpty().split(',')
                .mapNotNull { it.trim().toIntOrNull() }.toSet()
            return BackupJobForm(
                schedule = config["schedule"].orEmpty(),
                storage = config["storage"].orEmpty(),
                selection = when {
                    !all -> BackupSelection.INCLUDE
                    ids.isNotEmpty() -> BackupSelection.EXCLUDE
                    else -> BackupSelection.ALL
                },
                vmids = ids,
                node = config["node"].orEmpty(),
                mode = config["mode"] ?: "snapshot",
                compress = config["compress"] ?: "0",
                enabled = config["enabled"] != "0",
                mailto = config["mailto"].orEmpty(),
                mailNotification = config["mailnotification"] ?: "always",
                notesTemplate = config["notes-template"].orEmpty(),
                comment = config["comment"].orEmpty(),
                prune = config["prune-backups"]?.let(PruneOptions::parse),
                current = config,
            )
        }
    }
}

/** A row of `GET /cluster/backup`: the job's raw settings, plus when it runs next. */
data class BackupJob(val config: Map<String, String>, val nextRun: Long?) {
    val id: String get() = config["id"].orEmpty()
    val form: BackupJobForm get() = BackupJobForm.fromConfig(config)

    companion object {
        fun parse(data: JSONArray): List<BackupJob> = (0 until data.length()).mapNotNull { data.optJSONObject(it) }
            .filter { it.optString("type", "vzdump") == "vzdump" }
            .map { o -> BackupJob(flatten(o), o.optLong("next-run", 0).takeIf { it > 0 }) }
            .sortedBy { it.id }

        /** Job settings as strings; `prune-backups` can come as an object, which becomes its property string. */
        fun flatten(o: JSONObject): Map<String, String> = o.keys().asSequence()
            .filter { it != "digest" && it != "next-run" }
            .associateWith { key ->
                when (val v = o.opt(key)) {
                    is JSONObject -> v.keys().asSequence().sorted().joinToString(",") { "$it=${v.opt(it)}" }
                    else -> v?.toString().orEmpty()
                }
            }
    }
}

/** A guest that no backup job covers (`GET /cluster/backup-info/not-backed-up`). */
data class UnprotectedGuest(val vmid: Int, val name: String, val type: String) {
    companion object {
        fun parse(data: JSONArray): List<UnprotectedGuest> = (0 until data.length()).mapNotNull { data.optJSONObject(it) }
            .map { UnprotectedGuest(it.optInt("vmid"), it.optString("name"), it.optString("type")) }
            .sortedBy { it.vmid }
    }
}
