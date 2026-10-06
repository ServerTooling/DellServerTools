package com.lilayam.dellservertools.core

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

enum class ServerType(val label: String) {
    IDRAC6("iDRAC6"),
    PROXMOX("Proxmox VE"),
}

/**
 * A saved server. Secrets (password, API token secret) are not part of the
 * profile; they live encrypted in [com.lilayam.dellservertools.ui.SecretStore].
 */
data class ServerProfile(
    val id: String = UUID.randomUUID().toString(),
    val type: ServerType,
    val name: String,
    val host: String,
    val username: String,
    /** iDRAC6: SSH port. Proxmox: HTTPS API / web UI port. */
    val port: Int = defaultPort(type),
    /** Proxmox only: SSH port of the host, for the shell. */
    val sshPort: Int = 22,
    /** Proxmox only: authentication realm, `pam` or `pve`. */
    val realm: String = "pam",
    /** Proxmox only: optional API token ID (`user@realm!name`) used instead of the password for the API. */
    val apiTokenId: String = "",
    val rememberPassword: Boolean = false,
) {
    val displayName: String get() = name.ifBlank { host }

    /** Proxmox API user, e.g. `root@pam`. */
    val proxmoxUser: String
        get() = if (username.contains('@')) username else "$username@${realm.ifBlank { "pam" }}"

    /** Username for SSH; Proxmox users are written `root@pam` in the API but `root` for SSH. */
    val sshUsername: String get() = username.substringBefore('@')

    val usesApiToken: Boolean get() = type == ServerType.PROXMOX && apiTokenId.isNotBlank()

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("type", type.name)
        .put("name", name)
        .put("host", host)
        .put("username", username)
        .put("port", port)
        .put("sshPort", sshPort)
        .put("realm", realm)
        .put("apiTokenId", apiTokenId)
        .put("rememberPassword", rememberPassword)

    companion object {
        fun defaultPort(type: ServerType): Int = when (type) {
            ServerType.IDRAC6 -> 22
            ServerType.PROXMOX -> 8006
        }

        fun fromJson(json: JSONObject): ServerProfile? {
            val type = runCatching { ServerType.valueOf(json.getString("type")) }.getOrNull() ?: return null
            return ServerProfile(
                id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
                type = type,
                name = json.optString("name"),
                host = json.optString("host"),
                username = json.optString("username"),
                port = json.optInt("port", defaultPort(type)),
                sshPort = json.optInt("sshPort", 22),
                realm = json.optString("realm", "pam"),
                apiTokenId = json.optString("apiTokenId"),
                rememberPassword = json.optBoolean("rememberPassword", false),
            )
        }

        fun listToJson(profiles: List<ServerProfile>): String =
            JSONArray().apply { profiles.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(text: String?): List<ServerProfile> {
            if (text.isNullOrBlank()) return emptyList()
            val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
            return (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(::fromJson) }
        }

        /** A new iDRAC6 profile pre-filled from a viewer.jnlp file. */
        fun fromJnlp(info: JnlpInfo): ServerProfile = ServerProfile(
            type = ServerType.IDRAC6,
            name = listOfNotNull(info.serverName, info.model).joinToString(" · ").ifBlank { info.host },
            host = info.host,
            username = info.username.orEmpty(),
        )
    }
}
