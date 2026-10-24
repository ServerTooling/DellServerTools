package com.lilayam.dellservertools.core.proxmox

import org.json.JSONArray
import org.json.JSONObject

/**
 * A backup retention policy, Proxmox's `prune-backups` property string (e.g. `keep-last=3,keep-daily=7`).
 * Null counts are unset; with none set and [keepAll] false, Proxmox's default is to keep everything.
 */
data class PruneOptions(
    val keepLast: Int? = null,
    val keepHourly: Int? = null,
    val keepDaily: Int? = null,
    val keepWeekly: Int? = null,
    val keepMonthly: Int? = null,
    val keepYearly: Int? = null,
    val keepAll: Boolean = false,
) {
    val counts: List<Pair<String, Int?>>
        get() = listOf(
            "keep-last" to keepLast,
            "keep-hourly" to keepHourly,
            "keep-daily" to keepDaily,
            "keep-weekly" to keepWeekly,
            "keep-monthly" to keepMonthly,
            "keep-yearly" to keepYearly,
        )

    /** Whether this would remove anything at all: without a count set, every backup is kept. */
    val prunes: Boolean get() = !keepAll && counts.any { it.second != null }

    fun validate(): String? {
        if (counts.any { (_, n) -> n != null && n < 0 }) return "Keep counts can't be negative"
        if (keepAll && counts.any { it.second != null }) return "Keep all can't be combined with other counts"
        return null
    }

    /** The property string; "keep-all=1" when nothing is set, which is what Proxmox assumes then anyway. */
    fun toParam(): String {
        if (keepAll || counts.all { it.second == null }) return "keep-all=1"
        return counts.filter { it.second != null }.joinToString(",") { (k, n) -> "$k=$n" }
    }

    companion object {
        fun parse(value: String?): PruneOptions {
            val parts = GuestEdit.parseOptions(value)
            fun n(key: String) = parts[key]?.toIntOrNull()
            return PruneOptions(
                keepLast = n("keep-last"),
                keepHourly = n("keep-hourly"),
                keepDaily = n("keep-daily"),
                keepWeekly = n("keep-weekly"),
                keepMonthly = n("keep-monthly"),
                keepYearly = n("keep-yearly"),
                keepAll = parts["keep-all"] == "1",
            )
        }
    }
}

/** One backup in a prune preview (`GET .../prunebackups`). [mark] is keep, remove, protected or renamed. */
data class PruneEntry(val volid: String, val mark: String, val ctime: Long?, val vmid: Int?, val type: String) {
    val removed: Boolean get() = mark == "remove"
    val name: String get() = volid.substringAfter(':').substringAfterLast('/')

    companion object {
        fun parse(data: JSONArray): List<PruneEntry> = (0 until data.length()).mapNotNull { data.optJSONObject(it) }
            .map { o ->
                PruneEntry(
                    volid = o.optString("volid"),
                    mark = o.optString("mark"),
                    ctime = o.optLong("ctime", 0).takeIf { it > 0 },
                    vmid = o.optInt("vmid", 0).takeIf { it > 0 },
                    type = o.optString("type"),
                )
            }
            .sortedWith(compareBy<PruneEntry>({ it.vmid ?: 0 }).thenByDescending { it.ctime ?: 0 })
    }
}

/** What `GET /nodes/{node}/query-url-metadata` says about a URL before downloading it. */
data class UrlMetadata(val filename: String, val size: Long?, val mimetype: String) {
    companion object {
        fun parse(o: JSONObject?): UrlMetadata = UrlMetadata(
            filename = o?.optString("filename").orEmpty(),
            size = o?.optLong("size", -1)?.takeIf { it >= 0 },
            mimetype = o?.optString("mimetype").orEmpty(),
        )
    }
}

/** File names Proxmox accepts for uploaded or downloaded ISOs and container templates. */
object StorageFiles {
    private val NAME = Regex("^[A-Za-z0-9_.+-][A-Za-z0-9_.+ -]*$")

    fun extensions(content: String): List<String> = when (content) {
        "iso" -> listOf(".iso", ".img")
        "vztmpl" -> listOf(".tar.gz", ".tar.xz", ".tar.zst")
        else -> emptyList()
    }

    fun filenameError(content: String, filename: String): String? {
        if (filename.isBlank()) return "Enter a file name"
        if (filename.contains('/') || filename.startsWith(".") || !NAME.matches(filename)) {
            return "Letters, digits, '.', '_', '-' and '+' only"
        }
        val ext = extensions(content)
        if (ext.isNotEmpty() && ext.none { filename.lowercase().endsWith(it) }) {
            return "Must end in ${ext.joinToString(" or ")}"
        }
        return null
    }

    /** The last path segment of a URL, without query or fragment. */
    fun filenameFromUrl(url: String): String =
        url.substringBefore('#').substringBefore('?').trimEnd('/').substringAfterLast('/')
            .let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
}

/** `POST /nodes/{node}/storage/{storage}/download-url`: let the server fetch an ISO or template itself. */
data class DownloadUrlRequest(
    val url: String,
    val filename: String,
    val content: String,
    val checksum: String = "",
    val checksumAlgorithm: String = "sha256",
    val verifyCertificates: Boolean = true,
) {
    fun validate(): String? {
        val u = url.trim()
        if (!(u.startsWith("https://") || u.startsWith("http://")) || u.length < 10) return "Enter an http(s) URL"
        StorageFiles.filenameError(content, filename)?.let { return it }
        if (checksum.isNotBlank()) {
            if (checksumAlgorithm !in ALGORITHMS) return "Unknown checksum algorithm"
            if (!Regex("^[0-9a-fA-F]+$").matches(checksum.trim())) return "The checksum must be hexadecimal"
        }
        return null
    }

    fun toParams(): Map<String, String> {
        val p = linkedMapOf("url" to url.trim(), "filename" to filename.trim(), "content" to content)
        if (checksum.isNotBlank()) {
            p["checksum"] = checksum.trim().lowercase()
            p["checksum-algorithm"] = checksumAlgorithm
        }
        if (!verifyCertificates) p["verify-certificates"] = "0"
        return p
    }

    companion object {
        val ALGORITHMS = listOf("md5", "sha1", "sha224", "sha256", "sha384", "sha512")
    }
}

/** A storage type the app can add, with the content types it can hold and the fields it needs. */
enum class StorageKind(
    val apiName: String,
    val label: String,
    val contents: List<String>,
    val fields: List<String>,
    val required: List<String>,
) {
    DIR("dir", "Directory", FILE_CONTENTS, listOf("path"), listOf("path")),
    NFS("nfs", "NFS", FILE_CONTENTS, listOf("server", "export"), listOf("server", "export")),
    CIFS(
        "cifs", "SMB/CIFS", FILE_CONTENTS,
        listOf("server", "share", "username", "password", "domain"), listOf("server", "share"),
    ),
    PBS(
        "pbs", "Proxmox Backup Server", listOf("backup"),
        listOf("server", "datastore", "username", "password", "fingerprint", "namespace"),
        listOf("server", "datastore", "username", "password"),
    ),
    ZFS("zfspool", "ZFS", BLOCK_CONTENTS, listOf("pool"), listOf("pool")),
    LVM("lvm", "LVM", BLOCK_CONTENTS, listOf("vgname"), listOf("vgname")),
    LVMTHIN("lvmthin", "LVM-Thin", BLOCK_CONTENTS, listOf("vgname", "thinpool"), listOf("vgname", "thinpool")),
    ;

    /** Fields only the add form sets; Proxmox refuses changing them later. */
    val fixedFields: List<String> get() = fields - SECRET_FIELDS - listOf("fingerprint", "domain", "username")

    companion object {
        fun of(apiName: String): StorageKind? = entries.firstOrNull { it.apiName == apiName }
    }
}

private val FILE_CONTENTS = listOf("images", "rootdir", "iso", "vztmpl", "backup", "snippets", "import")
private val BLOCK_CONTENTS = listOf("images", "rootdir")
private val SECRET_FIELDS = listOf("password")

/**
 * The add/edit form of a storage (`POST /storage`, `PUT /storage/{id}`). [current] is the storage.cfg entry
 * when editing, empty when adding. Passwords are never read back: blank means unchanged.
 */
data class StorageForm(
    val id: String,
    val kind: StorageKind,
    val content: Set<String>,
    val enabled: Boolean = true,
    val shared: Boolean = false,
    val fields: Map<String, String> = emptyMap(),
    val prune: PruneOptions = PruneOptions(),
    val current: Map<String, String> = emptyMap(),
) {
    val isNew: Boolean get() = current.isEmpty()

    operator fun get(field: String): String = fields[field].orEmpty()

    fun validate(): String? {
        if (isNew && !ID.matches(id)) return "ID: starts with a letter, ends with a letter or digit; '-', '_' and '.' allowed"
        if (content.isEmpty()) return "Pick at least one content type"
        content.firstOrNull { it !in kind.contents }?.let { return "${kind.label} storage can't hold $it" }
        for (f in kind.required) {
            if (f in SECRET_FIELDS && !isNew) continue
            if (this[f].isBlank()) return "Enter the $f"
        }
        if (kind == StorageKind.DIR && isNew && !this["path"].startsWith("/")) return "The path must start with /"
        if (kind == StorageKind.PBS && this["fingerprint"].isNotBlank() && !FINGERPRINT.matches(this["fingerprint"].trim())) {
            return "Fingerprint looks like AA:BB:… (32 pairs)"
        }
        prune.validate()?.let { return it }
        return null
    }

    fun createParams(): Map<String, String> {
        val p = linkedMapOf("storage" to id, "type" to kind.apiName, "content" to contentParam())
        for (f in kind.fields) this[f].trim().takeIf { it.isNotEmpty() }?.let { p[f] = it }
        if (kind == StorageKind.DIR && shared) p["shared"] = "1"
        if (!enabled) p["disable"] = "1"
        if ("backup" in content && prune.prunes) p["prune-backups"] = prune.toParam()
        return p
    }

    /** Only what changed; a cleared flag or retention goes into `delete`. */
    fun updateParams(): Map<String, String> {
        val p = linkedMapOf<String, String>()
        val delete = mutableListOf<String>()
        if (contentParam() != sortedContent(current["content"])) p["content"] = contentParam()
        val wasEnabled = current["disable"] != "1"
        if (enabled != wasEnabled) {
            if (enabled) delete += "disable" else p["disable"] = "1"
        }
        if (kind == StorageKind.DIR && shared != (current["shared"] == "1")) {
            if (shared) p["shared"] = "1" else delete += "shared"
        }
        for (f in kind.fields - kind.fixedFields) {
            val v = this[f].trim()
            if (f in SECRET_FIELDS) {
                if (v.isNotEmpty()) p[f] = v
            } else if (v != current[f].orEmpty()) {
                if (v.isEmpty()) delete += f else p[f] = v
            }
        }
        val oldPrune = current["prune-backups"]
        val newPrune = if ("backup" in content && prune.prunes) prune.toParam() else null
        if (newPrune != null && newPrune != PruneOptions.parse(oldPrune).takeIf { it.prunes }?.toParam()) {
            p["prune-backups"] = newPrune
        } else if (newPrune == null && oldPrune != null && PruneOptions.parse(oldPrune).prunes) {
            delete += "prune-backups"
        }
        if (delete.isNotEmpty()) p["delete"] = delete.joinToString(",")
        return p
    }

    private fun contentParam(): String = content.sortedBy { kind.contents.indexOf(it) }.joinToString(",")

    private fun sortedContent(value: String?): String =
        value.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .sortedBy { kind.contents.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }.joinToString(",")

    companion object {
        private val ID = Regex("^[A-Za-z][A-Za-z0-9_.-]{0,98}[A-Za-z0-9]$")
        private val FINGERPRINT = Regex("^([0-9A-Fa-f]{2}:){31}[0-9A-Fa-f]{2}$")

        /** The edit form for an existing storage.cfg entry, or null for a type the app can't edit. */
        fun fromConfig(config: Map<String, String>): StorageForm? {
            val kind = StorageKind.of(config["type"].orEmpty()) ?: return null
            return StorageForm(
                id = config["storage"].orEmpty(),
                kind = kind,
                content = config["content"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                enabled = config["disable"] != "1",
                shared = config["shared"] == "1",
                fields = kind.fields.filter { it !in SECRET_FIELDS }.associateWith { config[it].orEmpty() },
                prune = PruneOptions.parse(config["prune-backups"]),
                current = config,
            )
        }
    }
}
