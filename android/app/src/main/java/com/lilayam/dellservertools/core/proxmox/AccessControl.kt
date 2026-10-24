package com.lilayam.dellservertools.core.proxmox

import org.json.JSONArray
import org.json.JSONObject

/** One user of `GET /access/users?full=1`, with the API tokens it owns. */
data class PveUser(
    val userid: String,
    val enabled: Boolean,
    /** Epoch seconds; 0 never expires. */
    val expire: Long = 0,
    val firstname: String = "",
    val lastname: String = "",
    val email: String = "",
    val comment: String = "",
    val groups: List<String> = emptyList(),
    val tokens: List<ApiToken> = emptyList(),
) {
    val realm: String get() = userid.substringAfterLast('@')

    companion object {
        fun parse(data: JSONArray): List<PveUser> = data.objects().map { o ->
            val userid = o.optString("userid")
            PveUser(
                userid = userid,
                enabled = o.optInt("enable", 1) == 1,
                expire = o.optLong("expire"),
                firstname = o.optString("firstname"),
                lastname = o.optString("lastname"),
                email = o.optString("email"),
                comment = o.optString("comment").trim(),
                groups = o.list("groups"),
                tokens = o.optJSONArray("tokens").objects().map { ApiToken.parse(userid, it) },
            )
        }.sortedBy { it.userid }
    }
}

/** The add/edit form of Datacenter > Permissions > Users. The user ID and password are only set on create. */
data class UserForm(
    val userid: String = "",
    val password: String = "",
    val enabled: Boolean = true,
    val expire: Long = 0,
    val firstname: String = "",
    val lastname: String = "",
    val email: String = "",
    val comment: String = "",
    val groups: List<String> = emptyList(),
    val isNew: Boolean = true,
) {
    fun validate(): String? = when {
        isNew && !USERID.matches(userid) -> "User name@realm, e.g. alice@pve."
        // Only the pve realm keeps passwords; PAM users get theirs on the host, LDAP/AD ones from the directory.
        isNew && userid.endsWith("@pve") && password.length < 8 -> "The password needs at least 8 characters."
        email.isNotBlank() && !email.contains('@') -> "That isn't an e-mail address."
        else -> null
    }

    fun createParams(): Map<String, String> = linkedMapOf("userid" to userid).apply {
        if (password.isNotEmpty() && userid.endsWith("@pve")) put("password", password)
        putAll(common())
    }

    /** Every field is sent, so a cleared one is cleared on the server too. */
    fun updateParams(): Map<String, String> = common()

    private fun common(): Map<String, String> = linkedMapOf(
        "enable" to if (enabled) "1" else "0",
        "expire" to expire.toString(),
        "firstname" to firstname.trim(),
        "lastname" to lastname.trim(),
        "email" to email.trim(),
        "comment" to comment.trim(),
        "groups" to groups.joinToString(","),
    )

    companion object {
        private val USERID = Regex("[^\\s:/@]+@[A-Za-z][A-Za-z0-9.\\-_]+")

        fun from(u: PveUser) = UserForm(
            userid = u.userid,
            enabled = u.enabled,
            expire = u.expire,
            firstname = u.firstname,
            lastname = u.lastname,
            email = u.email,
            comment = u.comment,
            groups = u.groups,
            isNew = false,
        )
    }
}

data class PveGroup(val groupid: String, val comment: String, val users: List<String>) {
    companion object {
        fun parse(data: JSONArray): List<PveGroup> = data.objects().map {
            PveGroup(it.optString("groupid"), it.optString("comment").trim(), it.optString("users").split(',').filter { u -> u.isNotBlank() })
        }.sortedBy { it.groupid }

        val ID = Regex("[A-Za-z0-9.\\-_]+")
    }
}

/** An API token `userid!tokenid`; with [privsep] it only has the permissions given to the token itself. */
data class ApiToken(val userid: String, val tokenid: String, val privsep: Boolean, val expire: Long = 0, val comment: String = "") {
    val fullId: String get() = "$userid!$tokenid"

    companion object {
        fun parse(userid: String, o: JSONObject) = ApiToken(
            userid = userid,
            tokenid = o.optString("tokenid"),
            privsep = o.optInt("privsep", 1) == 1,
            expire = o.optLong("expire"),
            comment = o.optString("comment").trim(),
        )

        val ID = Regex("[A-Za-z][A-Za-z0-9.\\-_]+")

        fun createParams(privsep: Boolean, expire: Long, comment: String): Map<String, String> = linkedMapOf(
            "privsep" to if (privsep) "1" else "0",
            "expire" to expire.toString(),
        ).apply { if (comment.isNotBlank()) put("comment", comment.trim()) }
    }
}

/** The answer to creating a token: the secret is only ever shown this once. */
data class NewApiToken(val fullId: String, val secret: String)

/** Who an ACL entry is for, and the `PUT /access/acl` parameter that names it. */
enum class AclType(val param: String, val label: String) {
    USER("users", "User"), GROUP("groups", "Group"), TOKEN("tokens", "API token");

    companion object {
        fun of(type: String): AclType = when (type) {
            "group" -> GROUP
            "token" -> TOKEN
            else -> USER
        }
    }
}

/** One line of Datacenter > Permissions: [ugid] gets [role] on [path] (and below it with [propagate]). */
data class AclEntry(val path: String, val type: AclType, val ugid: String, val role: String, val propagate: Boolean = true) {
    fun addParams(): Map<String, String> = linkedMapOf(
        "path" to path,
        "roles" to role,
        type.param to ugid,
        "propagate" to if (propagate) "1" else "0",
    )

    fun removeParams(): Map<String, String> = linkedMapOf("path" to path, "roles" to role, type.param to ugid, "delete" to "1")

    fun validate(): String? = when {
        !path.startsWith("/") -> "The path starts with /, e.g. / or /vms/100."
        ugid.isBlank() -> "Pick who gets the role."
        role.isBlank() -> "Pick a role."
        else -> null
    }

    companion object {
        fun parse(data: JSONArray): List<AclEntry> = data.objects().map {
            AclEntry(it.optString("path"), AclType.of(it.optString("type")), it.optString("ugid"), it.optString("roleid"), it.optInt("propagate", 1) == 1)
        }.sortedWith(compareBy({ it.path }, { it.ugid }, { it.role }))

        /** The paths the web UI offers, plus each guest, storage, node and pool. */
        fun suggestedPaths(nodes: List<String>, vmids: List<Int>, storages: List<String>, pools: List<String>): List<String> =
            listOf("/", "/vms", "/storage", "/nodes", "/pool", "/sdn", "/access", "/access/groups", "/access/realm") +
                nodes.map { "/nodes/$it" } + vmids.sorted().map { "/vms/$it" } + storages.map { "/storage/$it" } +
                pools.map { "/pool/$it" }
    }
}

/** A role and its privileges; built-in ([special]) ones can't be changed. */
data class PveRole(val roleid: String, val privs: List<String>, val special: Boolean) {
    companion object {
        fun parse(data: JSONArray): List<PveRole> = data.objects().map {
            PveRole(it.optString("roleid"), it.optString("privs").split(',').filter { p -> p.isNotBlank() }.sorted(), it.optInt("special") == 1)
        }.sortedWith(compareBy({ !it.special }, { it.roleid }))

        /** Every privilege there is: the Administrator role holds them all. */
        fun allPrivileges(roles: List<PveRole>): List<String> = roles.firstOrNull { it.roleid == "Administrator" }?.privs.orEmpty()

        fun createParams(roleid: String, privs: Collection<String>): Map<String, String> =
            linkedMapOf("roleid" to roleid, "privs" to privs.sorted().joinToString(","))

        val ID = Regex("[A-Za-z0-9.\\-_]+")
    }
}

/** A resource pool and the guests and storages in it. */
data class PvePool(val poolid: String, val comment: String, val guests: List<Int> = emptyList(), val storages: List<String> = emptyList()) {
    companion object {
        fun list(data: JSONArray): List<PvePool> = data.objects().map { PvePool(it.optString("poolid"), it.optString("comment").trim()) }
            .sortedBy { it.poolid }

        /** `GET /pools/{poolid}`: members are guests (`vmid`) and storages (`storage`). */
        fun detail(poolid: String, o: JSONObject): PvePool {
            val members = o.optJSONArray("members").objects()
            return PvePool(
                poolid,
                o.optString("comment").trim(),
                members.filter { it.has("vmid") && it.optString("type") != "storage" }.map { it.optInt("vmid") }.sorted(),
                members.filter { it.optString("type") == "storage" }.map { it.optString("storage") }.sorted(),
            )
        }

        /** `PUT /pools/{poolid}` adding (or with [remove], taking out) guests and storages. */
        fun membersParams(vmids: List<Int>, storages: List<String>, remove: Boolean): Map<String, String> = linkedMapOf<String, String>().apply {
            if (vmids.isNotEmpty()) put("vms", vmids.joinToString(","))
            if (storages.isNotEmpty()) put("storage", storages.joinToString(","))
            if (remove) put("delete", "1")
        }
    }
}

/** A second factor of a user (`GET /access/tfa`). */
data class TfaEntry(val userid: String, val id: String, val type: String, val description: String, val created: Long, val enabled: Boolean) {
    companion object {
        fun parse(data: JSONArray): List<TfaEntry> = data.objects().flatMap { u ->
            val userid = u.optString("userid")
            u.optJSONArray("entries").objects().map {
                TfaEntry(userid, it.optString("id"), it.optString("type"), it.optString("description"), it.optLong("created"), it.optInt("enable", 1) == 1)
            }
        }
    }
}

object AccessJson {
    /** Realm names for the user form, e.g. pam and pve. */
    fun realms(data: JSONArray): List<String> = data.objects().map { it.optString("realm") }.sorted()

    fun newToken(o: JSONObject): NewApiToken = NewApiToken(o.optString("full-tokenid"), o.optString("value"))
}

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

/** Proxmox sends some lists as arrays and some as comma-separated strings. */
private fun JSONObject.list(key: String): List<String> = when (val v = opt(key)) {
    is JSONArray -> (0 until v.length()).map { v.optString(it) }
    is String -> v.split(',').filter { it.isNotBlank() }
    else -> emptyList()
}

/** Expiry dates as typed in the forms, `YYYY-MM-DD`, stored by Proxmox as epoch seconds (0 = never). */
object ExpiryDate {
    /** Blank is "never" (0); null when the text isn't a date. Taken as the start of that day, UTC. */
    fun parse(text: String): Long? {
        if (text.isBlank()) return 0
        return runCatching { java.time.LocalDate.parse(text.trim()).atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond() }.getOrNull()
    }

    fun format(epochSeconds: Long): String =
        if (epochSeconds <= 0) "" else java.time.Instant.ofEpochSecond(epochSeconds).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
}
