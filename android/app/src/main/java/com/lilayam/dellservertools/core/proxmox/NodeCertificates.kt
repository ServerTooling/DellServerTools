package com.lilayam.dellservertools.core.proxmox

import org.json.JSONArray
import org.json.JSONObject

/** One certificate of `GET /nodes/{node}/certificates/info`; `pveproxy-ssl.pem` is a custom or ACME one. */
data class CertificateInfo(
    val filename: String,
    val fingerprint: String,
    val subject: String,
    val issuer: String,
    val notBefore: Long,
    val notAfter: Long,
    val san: List<String>,
) {
    /** The certificate the web UI and API serve: the custom/ACME one when present, else Proxmox's own. */
    val isCustom: Boolean get() = filename == CUSTOM

    companion object {
        const val CUSTOM = "pveproxy-ssl.pem"
        const val DEFAULT = "pve-ssl.pem"

        fun parse(o: JSONObject): CertificateInfo = CertificateInfo(
            filename = o.optString("filename"),
            fingerprint = o.optString("fingerprint").uppercase(),
            subject = o.optString("subject"),
            issuer = o.optString("issuer"),
            notBefore = o.optLong("notbefore"),
            notAfter = o.optLong("notafter"),
            san = o.optJSONArray("san").strings(),
        )

        fun list(data: JSONArray): List<CertificateInfo> =
            (0 until data.length()).mapNotNull { data.optJSONObject(it) }.map(::parse)
                .filter { it.filename != "pve-root-ca.pem" }

        /** The one the API answers with: the custom one wins over Proxmox's own. */
        fun served(certs: List<CertificateInfo>): CertificateInfo? =
            certs.firstOrNull { it.isCustom } ?: certs.firstOrNull { it.filename == DEFAULT }
    }
}

/** `POST /nodes/{node}/certificates/custom`: PEM text pasted in; without a key the current one is kept. */
data class CustomCertificateForm(val certificates: String = "", val key: String = "") {
    fun validate(): String? = when {
        "-----BEGIN CERTIFICATE-----" !in certificates -> "Paste the certificate (and chain) in PEM form."
        key.isNotBlank() && !Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----").containsMatchIn(key) -> "The key must be a PEM private key."
        else -> null
    }

    /** `force` replaces an existing custom certificate, `restart` makes pveproxy serve it at once. */
    fun params(): Map<String, String> = linkedMapOf("certificates" to certificates.trim() + "\n").apply {
        if (key.isNotBlank()) put("key", key.trim() + "\n")
        put("force", "1")
        put("restart", "1")
    }
}

/** A domain on the node's ACME certificate; no [plugin] means the HTTP challenge (standalone, port 80). */
data class AcmeDomain(val slot: String, val domain: String, val plugin: String = "")

/** The ACME part of `GET /nodes/{node}/config`: `acme: account=…` and `acmedomain0..5: domain=…,plugin=…`. */
data class AcmeSettings(val account: String, val domains: List<AcmeDomain>, val digest: String) {
    val freeSlot: String? get() = SLOTS.firstOrNull { s -> domains.none { it.slot == s } }

    /** `PUT /nodes/{node}/config` for a new or changed domain, keeping the account (`default` when none is set). */
    fun setDomain(domain: AcmeDomain, account: String = this.account.ifEmpty { "default" }): Map<String, String> =
        linkedMapOf(
            "acme" to "account=$account",
            domain.slot to "domain=${domain.domain}" + if (domain.plugin.isNotEmpty()) ",plugin=${domain.plugin}" else "",
        ).withDigest()

    fun removeDomain(slot: String): Map<String, String> = linkedMapOf("delete" to slot).withDigest()

    private fun LinkedHashMap<String, String>.withDigest() = apply { if (digest.isNotEmpty()) put("digest", digest) }

    companion object {
        val SLOTS = (0..5).map { "acmedomain$it" }
        private val DOMAIN = Regex("([a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,}")

        fun validDomain(domain: String): Boolean = DOMAIN.matches(domain)

        fun parse(config: JSONObject): AcmeSettings {
            val props = parseProps(config.optString("acme"))
            val domains = SLOTS.mapNotNull { slot ->
                val p = parseProps(config.optString(slot)).ifEmpty { return@mapNotNull null }
                AcmeDomain(slot, p["domain"].orEmpty(), p["plugin"].orEmpty())
            }.toMutableList()
            // Before 6.2 the domains sat in `acme: domains=a;b` and always used the HTTP challenge.
            props["domains"]?.split(';')?.filter { it.isNotBlank() }?.forEach { d -> domains += AcmeDomain("acme", d) }
            return AcmeSettings(props["account"].orEmpty(), domains, config.optString("digest"))
        }

        /** `a=b,c=d`; a bare first value is the default key `domain`, as in `acmedomain0: example.org`. */
        private fun parseProps(value: String): Map<String, String> =
            value.split(',').filter { it.isNotBlank() }.associate {
                if ('=' in it) it.substringBefore('=').trim() to it.substringAfter('=').trim() else "domain" to it.trim()
            }
    }
}

/** An ACME directory (`GET /cluster/acme/directories`) such as Let's Encrypt V2 or its staging server. */
data class AcmeDirectory(val name: String, val url: String)

/** `POST /cluster/acme/account`: [tosUrl] is the terms the user accepted, which the CA requires. */
data class AcmeAccountForm(val name: String = "default", val contact: String = "", val directory: String = "", val tosUrl: String = "") {
    fun validate(): String? = when {
        !Regex("[a-zA-Z0-9_.-]+").matches(name) -> "Name: letters, digits, '-', '_' and '.'."
        !Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+").matches(contact) -> "Enter the e-mail address for expiry notices."
        directory.isBlank() -> "Pick a directory."
        else -> null
    }

    fun params(): Map<String, String> = linkedMapOf("name" to name, "contact" to contact, "directory" to directory).apply {
        if (tosUrl.isNotBlank()) put("tos_url", tosUrl)
    }
}

object AcmeJson {
    fun accounts(data: JSONArray): List<String> = (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("name") }

    fun directories(data: JSONArray): List<AcmeDirectory> =
        (0 until data.length()).mapNotNull { data.optJSONObject(it) }.map { AcmeDirectory(it.optString("name"), it.optString("url")) }

    /** DNS plugins only; the HTTP challenge needs none. */
    fun dnsPlugins(data: JSONArray): List<String> =
        (0 until data.length()).mapNotNull { data.optJSONObject(it) }.filter { it.optString("type") == "dns" }.map { it.optString("plugin") }
}

private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }
